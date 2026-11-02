/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.animation

import androidx.annotation.MainThread
import com.facebook.litho.ComponentScope
import com.facebook.litho.GlobalKey
import com.facebook.litho.HookGlobalKey
import com.facebook.litho.HookKey
import com.facebook.litho.RenderScope
import com.facebook.litho.StateUpdater
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.getUpdatedState
import com.facebook.litho.onCleanup
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.useEffect
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.ResourceResolver
import com.facebook.rendercore.graphics.Color

/**
 * Creates an [InfiniteAnimationGroup] container for continuous, lifecycle-owned looping animations
 * — shimmer, pulse, spinners, breathing effects, loading dots. Each child loops between an explicit
 * `from` and `to`, starts with the component, and stops when it unmounts.
 *
 * Unlike target-settling APIs ([useAnimatedValue], [useAnimationGroup], `Style.transition`,
 * [AnimatedVisibility], [AnimatedContent]), infinite animations never settle, so they are kept
 * separate: they accept only [InfiniteAnimationSpec]s built from duration-based specs and have no
 * completion semantics.
 *
 * ```kotlin
 * val infinite = useInfiniteAnimationGroup()
 * val alpha = infinite.animateFloat(from = 0.5f, to = 1f, animation = tween(600.ms))
 * Dot(style = Style.alpha(alpha))
 * ```
 *
 * Mirrors [useAnimationGroup]'s pure-render / commit-time construction: `render()` only *describes*
 * the loops (allocating no [AnimatedValue] and mutating nothing), so concurrent resolves cannot
 * race; the long-lived values are created in [InfiniteAnimationGroupState.reconcile] on the main
 * thread.
 *
 * @param shouldRun Whether the loops advance. `false` unregisters the children and holds their
 *   current values; flipping back to `true` restarts each loop from its `from` (a stop/hold +
 *   restart, not an elapsed-time pause/resume). Wire this to visibility to avoid an always-on frame
 *   loop off-screen.
 * @param label Optional label for debugging/tracing.
 * @param playback Optional durable pause/resume control that preserves loop progress.
 */
@ExperimentalLithoApi
@Hook
fun ComponentScope.useInfiniteAnimationGroup(
    shouldRun: Boolean = true,
    label: String = "infinite",
    playback: AnimationPlayback? = null,
): InfiniteAnimationGroup {
  val baseHookKey = animationHookKey()
  val lithoTree =
      context.lithoTree ?: error("useInfiniteAnimationGroup must be called within a LithoTree")

  val state = getUpdatedState {
    InfiniteAnimationGroupState(
        treeId = lithoTree.id,
        globalKey = context.globalKey,
        baseHookKey = baseHookKey,
        isLayoutContext = context.isContextForLayout,
        stateUpdater = lithoTree.stateUpdater,
        label = label,
    )
  }

  val infinite = InfiniteAnimationGroup(state, resourceResolver)

  // Reconcile record + run state in one effect. The fresh wrapper makes it run every render: it
  // creates the values on the first run, then (re)starts or stops slots as those change.
  useEffect(infinite) {
    state.driver.bind(playback)
    state.reconcile(infinite.createRecord(), shouldRun)
    null
  }

  // Stop + unregister everything on unmount.
  useEffect(Unit) {
    onCleanup { state.dispose() }
  }

  return infinite
}

/**
 * Immutable per-slot descriptor collected during `render()`: the [slot] it claims plus the pure
 * loop configuration needed to create and drive its value at commit. Carries no [AnimatedValue] and
 * no mutable state.
 */
internal class InfiniteSlotDescriptor<T>(
    val slot: Int,
    val typeConverter: TypeConverter<T>,
    val from: T,
    val to: T,
    val animation: DurationBasedAnimationSpec<T>,
    val repeatMode: RepeatMode,
    val initialStartOffset: StartOffset,
    val label: String,
) {

  /**
   * Whether the user-facing loop config matches [other] — drives restart detection across renders.
   * Excludes [slot]/[typeConverter]/[label] (the converter is per-render for `Dimen`, so it must
   * not participate); inline-equal specs (e.g. `tween(600.ms)` each render) compare equal and so do
   * not restart the loop.
   */
  fun configEquals(other: InfiniteSlotDescriptor<*>): Boolean =
      from == other.from &&
          to == other.to &&
          animation == other.animation &&
          repeatMode == other.repeatMode &&
          initialStartOffset == other.initialStartOffset

  /**
   * Whether [other] occupies this slot with the same value type. Drives the shape-stability check:
   * a slot's long-lived `AnimatedValue<T>` is created once for type `T`, so the per-slot type must
   * stay stable across renders. (Type only — labels are debug strings and may be dynamic.)
   */
  fun shapeEquals(other: InfiniteSlotDescriptor<*>): Boolean =
      typeConverter::class == other.typeConverter::class
}

/** The immutable per-render record of an [InfiniteAnimationGroup]: its ordered loop [slots]. */
internal class InfiniteAnimationRecord(val slots: List<InfiniteSlotDescriptor<*>>)

/**
 * Persistent runtime for an [InfiniteAnimationGroup]: the per-slot [AnimatedValue]s and the latest
 * committed [InfiniteAnimationRecord]. Stored in Litho hook state, it lives across renders.
 *
 * Mirrors [AnimationGroupState] (pure-render record + commit-time value creation, so concurrent
 * resolves never race and no lock is needed) but has no target/settle semantics: infinite
 * animations never complete, so there is no `onSettled`/`checkCompletion` and the values carry no
 * `onComplete`. Instead, a child loops via [infiniteRepeatable] and is (re)started or stopped by
 * [reconcile] as the run state flips or a loop's config changes.
 */
internal class InfiniteAnimationGroupState(
    private val treeId: Int,
    private val globalKey: GlobalKey,
    private val baseHookKey: HookKey,
    internal val isLayoutContext: Boolean,
    internal val stateUpdater: StateUpdater,
    val label: String,
) {

  internal val driver: AnimationDriver = AnimationDriver(::onFrame)

  /** The latest committed record. Replaced wholesale (copy-on-write) in [reconcile]. */
  @Volatile
  var record: InfiniteAnimationRecord = InfiniteAnimationRecord(emptyList())
    private set

  /** The long-lived per-slot [AnimatedValue]s, created once in [reconcile] (main thread). */
  @Volatile private var animatedValues: List<AnimatedValue<*>> = emptyList()

  private var hasCommitted: Boolean = false

  /** The run state applied by the last [reconcile]; drives run-state-change detection. */
  private var wasRunning: Boolean = false

  /** The committed [AnimatedValue] for [slot], or `null` before the first commit. */
  internal fun animatedValueAt(slot: Int): AnimatedValue<*>? = animatedValues.getOrNull(slot)

  /**
   * Reconciles the render's [record] and [running] state in one pass.
   *
   * Creates the per-slot values on the first run, then restarts or stops each slot as the run state
   * flips or its config changes. A config change while paused snaps to the new `from`.
   */
  @MainThread
  internal fun reconcile(record: InfiniteAnimationRecord, running: Boolean) {
    validateSlotCount(record)
    val previous = this.record
    val firstReconcile = !hasCommitted
    if (firstReconcile) {
      animatedValues = List(record.slots.size) { i -> createSlotValue(record.slots[i]) }
      hasCommitted = true
    }
    this.record = record

    val runStateChanged = running != wasRunning
    wasRunning = running

    var restarted = false
    for (slot in animatedValues.indices) {
      val configChanged = !firstReconcile && !record.slots[slot].configEquals(previous.slots[slot])
      if (!runStateChanged && !configChanged) continue
      when {
        running -> {
          restartSlot(slot)
          restarted = true
        }
        configChanged -> snapSlot(slot)
        else -> stopSlot(slot)
      }
    }
    driver.updatePendingWork(animatedValues.hasAnimator(), forceFrame = restarted)
  }

  private fun onFrame(playTimeNanos: Long): Boolean {
    val values = animatedValues
    for (slot in values.indices) {
      if (AnimationController.durationScale == 0f) prepareDisabledSlot(slot)
      else values[slot].evaluateFrame(playTimeNanos)
    }
    for (slot in values.indices) values[slot].publishFrame()
    return animatedValues.hasAnimator()
  }

  @Suppress("UNCHECKED_CAST")
  private fun prepareDisabledSlot(slot: Int) {
    val value = animatedValues[slot] as AnimatedValue<Any?>
    value.prepareDisabledValue(record.slots[slot].from)
  }

  private fun createSlotValue(description: InfiniteSlotDescriptor<*>): AnimatedValue<*> {
    @Suppress("UNCHECKED_CAST") val typed = description as InfiniteSlotDescriptor<Any?>
    // No onComplete: an infinite loop never finishes, so there is nothing to settle.
    return AnimatedValue(
        initialValue = typed.from,
        typeConverter = typed.typeConverter,
        stateId = childStateId(typed.slot),
        isLayoutContext = isLayoutContext,
        stateUpdater = stateUpdater,
        label = deriveLabel(typed.label),
        clock = driver,
    )
  }

  /**
   * Restarts [slot]'s loop from its `from` toward `to`. Since [AnimatedValue.restart] publishes
   * only on the first frame, restart and registration must stay paired. Only valid while running.
   */
  @Suppress("UNCHECKED_CAST")
  private fun restartSlot(slot: Int) {
    val description = record.slots[slot] as InfiniteSlotDescriptor<Any?>
    val value = animatedValues[slot] as AnimatedValue<Any?>
    value.restart(
        description.from,
        description.to,
        infiniteRepeatable(
            description.animation,
            description.repeatMode,
            description.initialStartOffset,
        ),
    )
  }

  /** Stops [slot]'s loop, holding its current value. */
  private fun stopSlot(slot: Int) {
    val value = animatedValues[slot]
    value.stop()
  }

  /**
   * Snaps [slot] to its `from`. Used when a config change lands while stopped: no frame will
   * follow, so [AnimatedValue.snapTo] publishes the new `from` immediately.
   */
  @Suppress("UNCHECKED_CAST")
  private fun snapSlot(slot: Int) {
    val description = record.slots[slot] as InfiniteSlotDescriptor<Any?>
    val value = animatedValues[slot] as AnimatedValue<Any?>
    value.snapTo(description.from)
  }

  /** Stops all slots and unregisters the group. Called on unmount. */
  @MainThread
  internal fun dispose() {
    for (slot in animatedValues.indices) stopSlot(slot)
    driver.unmount()
    wasRunning = false
  }

  private fun validateSlotCount(newRecord: InfiniteAnimationRecord) {
    if (!hasCommitted) return
    check(newRecord.slots.size == record.slots.size) {
      "InfiniteAnimation '$label' changed animation count across renders: " +
          "${record.slots.size} -> ${newRecord.slots.size}"
    }
    for (slot in newRecord.slots.indices) {
      val previous = record.slots[slot]
      val next = newRecord.slots[slot]
      check(next.shapeEquals(previous)) {
        "InfiniteAnimation '$label' changed animation order or value type at slot $slot. " +
            "Keep animate* calls in a stable order across renders."
      }
    }
  }

  internal fun childStateId(slot: Int): StateId = StateId(
      treeId,
      HookGlobalKey(
          globalKey,
          childHookKey(slot),
          if (isLayoutContext) RenderScope.Layout else RenderScope.Resolve,
      ),
  )

  /** A stable child of this group's hook key for [slot]. */
  private fun childHookKey(slot: Int): HookKey =
      HookKey.create(SLOT_CALL_SITE_ID, slot, parent = baseHookKey, intern = false)

  internal fun deriveLabel(animationLabel: String): String =
      when {
        label.isNotEmpty() && animationLabel.isNotEmpty() -> "$label/$animationLabel"
        label.isNotEmpty() -> label
        else -> animationLabel
      }

  companion object {
    private const val SLOT_CALL_SITE_ID = -1
  }
}

/**
 * A stable, slot-scoped [Value] handed back from [InfiniteAnimationGroup.animateValue] during
 * `render()`. The long-lived [AnimatedValue] is not created until commit, but a `Style` binder
 * built in `render()` needs a live handle now. Identified by `(state, slot)`, it reads the
 * committed value once it exists and, before the first commit, falls back to the descriptor's
 * `from`. Reading [value] records the read against the committed slot's id (or its eventual id),
 * the same id the value signals on each frame, so per-frame updates rebind exactly this binder.
 */
internal class InfiniteSlotValue<T>(
    private val state: InfiniteAnimationGroupState,
    private val slot: Int,
    private val descriptor: InfiniteSlotDescriptor<T>,
) : Value<T> {

  override val value: T
    get() {
      @Suppress("UNCHECKED_CAST") val committed = state.animatedValueAt(slot) as AnimatedValue<T>?
      StateReadRecorder.read(committed?.stateId ?: state.childStateId(slot))
      return committed?.currentValueRaw ?: descriptor.from
    }

  override fun equals(other: Any?): Boolean =
      other is InfiniteSlotValue<*> && other.state === state && other.slot == slot

  override fun hashCode(): Int = 31 * System.identityHashCode(state) + slot

  override fun toString(): String = "InfiniteSlotValue(slot=$slot)"
}

/**
 * The per-render handle returned by [useInfiniteAnimationGroup] and the public surface for
 * declaring a container's loops ([animateFloat], [animateDimen], …).
 *
 * Each call to [useInfiniteAnimationGroup] creates a fresh `InfiniteAnimation`, isolating
 * per-render bookkeeping (the slot counter, pending descriptors) from the shared
 * [InfiniteAnimationGroupState]. Declaring a loop allocates no [AnimatedValue] and mutates nothing:
 * it records an [InfiniteSlotDescriptor] and returns a stable [Value] handle.
 */
@ExperimentalLithoApi
class InfiniteAnimationGroup
internal constructor(
    internal val state: InfiniteAnimationGroupState,
    private val resourceResolver: ResourceResolver,
) {

  /** Per-render slot counter. Each `animate*` call claims one slot. */
  private var renderSlot = 0
  private val pendingDescriptors = mutableListOf<InfiniteSlotDescriptor<*>>()

  /** Freezes the slots declared this render into the immutable record committed into [state]. */
  internal fun createRecord(): InfiniteAnimationRecord =
      InfiniteAnimationRecord(pendingDescriptors.toList())

  /**
   * Loops a [Float] between [from] and [to].
   *
   * @param animation One forward-cycle spec. Defaults to a [tween].
   * @param repeatMode Whether each cycle restarts from [from] or reverses. Defaults to
   *   [RepeatMode.Reverse].
   * @param initialStartOffset A one-time phase/delay shift of the first cycle — use
   *   [StartOffsetType.FastForward] to stagger several loops.
   */
  fun animateFloat(
      from: Float,
      to: Float,
      animation: DurationBasedAnimationSpec<Float> = tween(),
      repeatMode: RepeatMode = RepeatMode.Reverse,
      initialStartOffset: StartOffset = StartOffset(0),
      label: String = "float",
  ): Value<Float> =
      animateValue(from, to, FloatTypeConverter, animation, repeatMode, initialStartOffset, label)

  /** Loops an [Int] between [from] and [to]. See [animateFloat]. */
  fun animateInt(
      from: Int,
      to: Int,
      animation: DurationBasedAnimationSpec<Int> = tween(),
      repeatMode: RepeatMode = RepeatMode.Reverse,
      initialStartOffset: StartOffset = StartOffset(0),
      label: String = "int",
  ): Value<Int> =
      animateValue(from, to, IntTypeConverter, animation, repeatMode, initialStartOffset, label)

  /** Loops a [Color] (per-channel) between [from] and [to]. See [animateFloat]. */
  fun animateColor(
      from: Color,
      to: Color,
      animation: DurationBasedAnimationSpec<Color> = tween(),
      repeatMode: RepeatMode = RepeatMode.Reverse,
      initialStartOffset: StartOffset = StartOffset(0),
      label: String = "color",
  ): Value<Color> =
      animateValue(from, to, ColorTypeConverter, animation, repeatMode, initialStartOffset, label)

  /** Loops a [Dimen] between [from] and [to]. See [animateFloat]. */
  fun animateDimen(
      from: Dimen,
      to: Dimen,
      animation: DurationBasedAnimationSpec<Dimen> = tween(),
      repeatMode: RepeatMode = RepeatMode.Reverse,
      initialStartOffset: StartOffset = StartOffset(0),
      label: String = "dimen",
  ): Value<Dimen> = animateValue(
      from,
      to,
      DimenTypeConverter(resourceResolver),
      animation,
      repeatMode,
      initialStartOffset,
      label,
  )

  /**
   * Loops a value of type [T] between [from] and [to] using a custom [TypeConverter]. The generic
   * form behind [animateFloat] / [animateInt] / [animateColor] / [animateDimen]. Allocates no
   * [AnimatedValue] — it records a descriptor and returns a stable slot handle.
   */
  fun <T> animateValue(
      from: T,
      to: T,
      typeConverter: TypeConverter<T>,
      animation: DurationBasedAnimationSpec<T> = tween(),
      repeatMode: RepeatMode = RepeatMode.Reverse,
      initialStartOffset: StartOffset = StartOffset(0),
      label: String = "",
  ): Value<T> {
    val slot = renderSlot++
    val descriptor = InfiniteSlotDescriptor(
        slot,
        typeConverter,
        from,
        to,
        animation,
        repeatMode,
        initialStartOffset,
        label,
    )
    pendingDescriptors.add(descriptor)
    return InfiniteSlotValue(state, slot, descriptor)
  }
}
