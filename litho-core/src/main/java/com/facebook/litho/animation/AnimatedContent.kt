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
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.getUpdatedState
import com.facebook.litho.layout.ContentPosition
import com.facebook.litho.layout.StackLayout
import com.facebook.litho.onCleanup
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.litho.withNewScope
import com.facebook.rendercore.IntSize
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify
import java.util.concurrent.atomic.AtomicLong

/**
 * Describes how an [AnimatedContent] transitions between two states: how the target content enters
 * and how the outgoing content exits, plus an optional size animation.
 *
 * @param targetContentEnter How the new (target) content enters.
 * @param initialContentExit How the previous (outgoing) content exits.
 * @param sizeTransform Optional container size animation between content sizes. (Reserved — the
 *   default is visual-only; layout-affecting size animation is opt-in and gated on benchmarks.)
 */
@ExperimentalLithoApi
class ContentTransform
internal constructor(
    val targetContentEnter: EnterTransition,
    val initialContentExit: ExitTransition,
    val sizeTransform: SizeTransform? = null,
)

/**
 * Produces the [AnimationSpec] for an [AnimatedContent] container size animation between two
 * content sizes. (Reserved for the opt-in, layout-affecting size-morph path.)
 */
@ExperimentalLithoApi
fun interface SizeTransform {
  fun createAnimationSpec(initialSize: IntSize, targetSize: IntSize): AnimationSpec<Float>
}

/**
 * Scope for [AnimatedContent.transitionSpec]. Exposes the [initialState] and [targetState] of the
 * current transition (so the spec can vary by direction, e.g. via [isTransitioningTo] or
 * `slideIntoContainer`) and collects the enter/exit specs that make up the [ContentTransform].
 *
 * The spec lambda declares the transition by setting [enter] / [exit]; anything not set defaults to
 * `None`. Assigning the same property more than once is last-wins, matching the per-effect merge
 * semantics of [EnterTransition.plus].
 */
@ExperimentalLithoApi
class AnimatedContentTransitionScope<T>
internal constructor(
    val initialState: T,
    val targetState: T,
) {
  /** How the target content enters. Last assignment wins; defaults to no enter animation. */
  var enter: EnterTransition = EnterTransition.None

  /** How the outgoing content exits. Last assignment wins; defaults to no exit animation. */
  var exit: ExitTransition = ExitTransition.None

  /** True if this transition is from `this` value to [target]. */
  infix fun T.isTransitioningTo(target: T): Boolean =
      this == initialState && target == this@AnimatedContentTransitionScope.targetState

  @PublishedApi
  internal fun buildContentTransform(): ContentTransform =
      ContentTransform(targetContentEnter = enter, initialContentExit = exit)
}

/**
 * A container that animates between content for different [targetState] values. The target content
 * enters while previously-keyed content exits; both are kept in the container's own subtree (an
 * overlapping [StackLayout]) until each exit settles — the same container-owned retention model as
 * [AnimatedVisibility]. No `DisappearingHost` / rendercore machinery is involved.
 *
 * Content is keyed by [contentKey]; reusing the same key updates the content inputs without a swap.
 * The default transition is a cross-fade.
 *
 * ```kotlin
 * child(AnimatedContent(targetState = page) { p -> PageContent(p) })
 * ```
 *
 * Note: the default is **visual-only** (no layout-affecting size animation). `SizeTransform` is
 * part of the API surface but the size-morph path is opt-in and not yet wired (pending perf
 * validation); the container currently sizes to the target content.
 *
 * @param targetState The state whose content should currently be shown.
 * @param transitionSpec Declares the transition via [AnimatedContentTransitionScope.enter] /
 *   [AnimatedContentTransitionScope.exit]. Defaults to a cross-fade.
 * @param contentKey Identity for a state's content; reusing a key preserves the slot and its
 *   motion. Keys must have stable equality and hash codes while retained.
 * @param contentPosition Alignment of overlapping content.
 * @param style Style applied to the container.
 * @param label Debug label for the underlying transitions.
 * @param onSettled Called asynchronously for the latest committed target after its enter and all
 *   retained exits settle and removal has been requested. Content-key changes notify even without
 *   motion. Same-key payload updates do not create notifications; an outstanding completion uses
 *   the latest committed payload and callback. Initial content is silent. Nested animations are
 *   excluded. Does not guarantee physical unmount or draw.
 * @param playback Optional durable control of all retained enter and exit animations.
 * @param content Builder for the content of a given state.
 */
@ExperimentalLithoApi
class AnimatedContent<T>(
    private val targetState: T,
    private val transitionSpec: AnimatedContentTransitionScope<T>.() -> Unit = {
      enter = fadeIn()
      exit = fadeOut()
    },
    private val contentKey: (T) -> Any? = { it },
    private val contentPosition: ContentPosition = ContentPosition.TopStart,
    private val style: Style = Style,
    private val label: String = "AnimatedContent",
    private val onSettled: ((T) -> Unit)? = null,
    private val playback: AnimationPlayback? = null,
    private val content: ComponentScope.(T) -> Component?,
) : KComponent() {

  @Suppress("UNNECESSARY_SAFE_CALL")
  override fun ComponentScope.render(): Component {
    val targetKey = contentKey(targetState)
    val retainedContent = useState { AnimatedContentState.create(targetKey, targetState) }
    val retained = retainedContent.value
    val requested = retained.withTarget(targetKey, targetState)
    val identities = arrayOfNulls<AnimatedContentSlotIdentity>(requested.slots.size)
    for (i in requested.slots.indices) {
      identities[i] =
          withNewScope(requested.slots[i].key ?: NullContentKey) {
            getUpdatedState { AnimatedContentSlotIdentity("slot-${nextSlotId.getAndIncrement()}") }
          }
    }
    val current = requested.withIdentities(identities)
    val needsPreparation = current !== retained
    val completion = useAnimatedContentSettlement<T>(targetKey)

    // Initial content uses resting values; only later targets play an enter transition.
    val hasStarted = useState { false }
    useEffect(Unit) {
      hasStarted.update(true)
      onCleanup { completion.unmount() }
    }

    // Child group commit and retarget effects finish before this parent effect freezes eligibility.
    useEffect(current, needsPreparation) {
      val eligible = current.withoutCompletedExits()
      retainedContent.update { it.acceptCommitted(eligible) }
      null
    }
    useEffect(current, onSettled, context) {
      completion.commit(current, onSettled, context)
      null
    }

    val slots = current.slots
    val transform =
        AnimatedContentTransitionScope(current.initialState, targetState)
            .apply(transitionSpec)
            .buildContentTransform()

    // Iterate the active states in reverse so the target (last) is declared FIRST — it stays in
    // normal flow and sizes the container, while the outgoing slots follow as absolute overlays.
    // zIndex is the state's own index (target last → highest), so the target still draws on top;
    // stable per-slot keys preserve hook state across reorders.
    return StackLayout(
        alignItems = contentPosition.toAlignItems(),
        justifyContent = contentPosition.toJustifyContent(),
        style = style,
    ) {
      for (i in slots.indices.reversed()) {
        val slot = slots[i]
        val isTarget = i == slots.lastIndex
        child(
            component =
                AnimatedContentSlot(
                    state = slot.state,
                    enter = transform.targetContentEnter,
                    exit = transform.initialContentExit,
                    isTarget = isTarget,
                    identity = checkNotNull(slot.identity),
                    animateEnter = hasStarted.value,
                    label = label,
                    playback = playback,
                    onSlotSettled = {
                      if (slot.identity?.hasExited == true) {
                        retainedContent.update { it.removeExited(slot) }
                      }
                      completion.onSlotSettled(slot)
                    },
                    content = content,
                ),
            zIndex = i,
            key = checkNotNull(slot.identity).key,
        )
      }
    }
  }

  private companion object {
    val nextSlotId = AtomicLong()

    object NullContentKey
  }
}

/**
 * Hook-owned slot lifetime. Render reads only [key]; committed effects own the runtime binding. UI
 * eligibility checks validate the current exit, including after reentry. Tree-state transfer can
 * preserve the same runtime instance; the old tree must release before the replacement tree rebinds
 * this identity.
 */
internal class AnimatedContentSlotIdentity(val key: String) {
  @get:MainThread
  @Suppress("OPT_IN_USAGE")
  internal var animation: AnimationGroupState<EnterExitState>? = null
    private set

  private var hasExitAnimation = false
  private var hasBeenTarget = false

  @get:MainThread
  @Suppress("OPT_IN_USAGE")
  val hasEntered: Boolean
    get() {
      @Suppress("OPT_IN_USAGE") val animation = animation ?: return false
      return animation.targetState == EnterExitState.Visible && !animation.hasAnimator
    }

  /**
   * A slot from a discarded resolve that never committed as a target has no displayed content to
   * animate out. It can release immediately; previously targeted slots with an exit animation
   * retain pending work, including delays.
   */
  @get:MainThread
  @Suppress("OPT_IN_USAGE")
  val hasExited: Boolean
    get() {
      @Suppress("OPT_IN_USAGE") val animation = animation ?: return false
      return animation.targetState == EnterExitState.AfterExit &&
          (!hasBeenTarget || !hasExitAnimation || !animation.hasAnimator)
    }

  @MainThread
  @Suppress("OPT_IN_USAGE")
  fun bind(
      animation: AnimationGroupState<EnterExitState>,
      hasExitAnimation: Boolean,
  ) {
    this.animation = animation
    this.hasExitAnimation = hasExitAnimation
    hasBeenTarget = hasBeenTarget || animation.targetState != EnterExitState.AfterExit
  }

  @MainThread
  @Suppress("OPT_IN_USAGE")
  fun clear() {
    animation = null
  }
}

@Hook
private fun <T> ComponentScope.useAnimatedContentSettlement(
    initialKey: Any?,
): AnimatedContentSettlement<T> = getUpdatedState { AnimatedContentSettlement<T>(initialKey) }

/** Committed membership is also the preparation barrier; BeforeEnter is not a prepared target. */
private class AnimatedContentSettlement<T>(initialKey: Any?) {
  private val settlement = AnimationSettlement(initialKey)
  private var content: AnimatedContentState<T>? = null
  private var onSettled: ((T) -> Unit)? = null
  private val reportSettled: (Any?) -> Unit = { key ->
    val slot = checkNotNull(content).slots.last()
    check(slot.key == key)
    onSettled?.invoke(slot.state)
  }

  @MainThread
  fun commit(
      content: AnimatedContentState<T>,
      onSettled: ((T) -> Unit)?,
      context: ComponentContext,
  ) {
    this.content = content
    this.onSettled = onSettled
    settlement.commit(content.slots.last().key, reportSettled, context)
    checkCompletion()
  }

  @MainThread
  fun onSlotSettled(slot: AnimatedContentEntry<T>) {
    if (!settlement.isPending) return
    val content = content ?: return
    if (content.slots.none { it === slot }) return
    checkCompletion()
  }

  @MainThread
  private fun checkCompletion() {
    if (!settlement.isPending) return
    val slots = content?.slots ?: return
    if (slots.last().identity?.hasEntered != true) return
    for (i in 0 until slots.lastIndex) {
      if (slots[i].identity?.hasExited != true) return
    }
    settlement.complete()
  }

  @MainThread
  fun unmount() {
    content = null
    onSettled = null
    settlement.unmount()
  }
}

internal data class AnimatedContentEntry<T>(
    val key: Any?,
    val state: T,
    val identity: AnimatedContentSlotIdentity? = null,
)

/**
 * Immutable content membership; the final entry is the target. Only committed parent effects
 * install projections, in UI commit order. Exit reports only remove matching immutable entries;
 * reentry creates a new entry even when the slot lifetime is preserved. Unchanged outgoing entries
 * preserve their object identity across projections. Eligibility is frozen on the UI thread before
 * queuing an updater; replayed updaters must not inspect the identities' mutable animation runtime.
 */
internal class AnimatedContentState<T>
private constructor(
    val slots: List<AnimatedContentEntry<T>>,
    val initialState: T,
) {
  fun withTarget(key: Any?, state: T): AnimatedContentState<T> {
    val previousTarget = slots.last()
    if (previousTarget.key == key) {
      if (previousTarget.state == state) return this
      val updated = ArrayList(slots)
      updated[updated.lastIndex] = previousTarget.copy(state = state)
      return AnimatedContentState(updated, initialState)
    }

    val existingIndex = slots.indexOfFirst { it.key == key }
    val target =
        if (existingIndex >= 0) slots[existingIndex].copy(state = state)
        else AnimatedContentEntry(key, state)
    val updated = ArrayList<AnimatedContentEntry<T>>(slots.size + if (existingIndex < 0) 1 else 0)
    for (i in 0 until slots.lastIndex) {
      if (i != existingIndex) updated.add(slots[i])
    }
    updated.add(previousTarget)
    updated.add(target)
    return AnimatedContentState(updated, previousTarget.state)
  }

  fun withIdentities(identities: Array<AnimatedContentSlotIdentity?>): AnimatedContentState<T> {
    var updated: ArrayList<AnimatedContentEntry<T>>? = null
    for (i in slots.indices) {
      if (slots[i].identity !== identities[i]) {
        if (updated == null) updated = ArrayList(slots)
        updated[i] = slots[i].copy(identity = identities[i])
      }
    }
    return if (updated == null) this else AnimatedContentState(updated, initialState)
  }

  /** Freeze this result before constructing an updater, which may run on a background thread. */
  @MainThread
  fun withoutCompletedExits(): AnimatedContentState<T> {
    var updated: ArrayList<AnimatedContentEntry<T>>? = null
    for (i in 0 until slots.lastIndex) {
      if (slots[i].identity?.hasExited == true) {
        if (updated == null) {
          updated = ArrayList(slots.size)
          for (previous in 0 until i) updated.add(slots[previous])
        }
      } else {
        updated?.add(slots[i])
      }
    }
    if (updated == null) return this
    updated.add(slots.last())
    return AnimatedContentState(updated, initialState)
  }

  fun acceptCommitted(projection: AnimatedContentState<T>): AnimatedContentState<T> {
    if (this === projection) return this
    if (
        initialState == projection.initialState &&
            slots.size == projection.slots.size &&
            slots.indices.all { slots[it] === projection.slots[it] }
    )
        return this
    return projection
  }

  fun removeExited(slot: AnimatedContentEntry<T>): AnimatedContentState<T> {
    val index = slots.indexOfFirst { it.key == slot.key }
    if (index < 0 || index == slots.lastIndex) return this
    if (slots[index] !== slot) return this
    val updated = ArrayList(slots)
    updated.removeAt(index)
    return AnimatedContentState(updated, initialState)
  }

  companion object {
    fun <T> create(key: Any?, state: T): AnimatedContentState<T> =
        AnimatedContentState(listOf(AnimatedContentEntry(key, state)), state)
  }
}

/**
 * One content slot of an [AnimatedContent]. Plays the enter animation when it is the target and the
 * exit animation otherwise, reporting back via [onSlotSettled] so the container can observe
 * completion and drop exited content. A slot that was never the target (superseded before it was
 * shown, e.g. in a rapid `A -> B -> C`) has no real exit to play and releases immediately.
 */
@ExperimentalLithoApi
internal class AnimatedContentSlot<T>(
    internal val state: T,
    private val enter: EnterTransition,
    private val exit: ExitTransition,
    private val isTarget: Boolean,
    internal val identity: AnimatedContentSlotIdentity,
    private val animateEnter: Boolean,
    private val label: String,
    internal val onSlotSettled: () -> Unit,
    private val playback: AnimationPlayback? = null,
    private val content: ComponentScope.(T) -> Component?,
) : KComponent() {

  override fun ComponentScope.render(): Component? {
    val hasEnterAnimation =
        with(enter.data) { fade != null || scale != null || slide != null || changeSize != null }
    val hasExitAnimation =
        with(exit.data) { fade != null || scale != null || slide != null || changeSize != null }
    val started = useState { !hasEnterAnimation || !animateEnter }

    val driveState =
        when {
          !isTarget -> EnterExitState.AfterExit
          started.value -> EnterExitState.Visible
          else -> EnterExitState.BeforeEnter
        }

    val transition = useAnimationGroup(driveState, label = label, playback = playback)
    // Allocate effect slots unconditionally (before the content-null guard) for stable slot count.
    val progresses = buildEnterExitProgresses(transition, enter.data, exit.data)

    useEffect(Unit) {
      onCleanup { identity.clear() }
    }

    // The group is fresh each render, refreshing both the runtime binding and completion capture.
    useEffect(transition) {
      identity.bind(transition.state, hasExitAnimation)
      if (isTarget) {
        // A slot may first commit as outgoing and become the target before removal applies.
        if (!started.value) started.update(true)
      }
      transition.state.onStateSettled = onSlotSettled
      if (identity.hasExited) onSlotSettled()
      onCleanup { transition.state.onStateSettled = null }
    }

    val child = content(this, state) ?: return null
    return applyEnterExitContent(progresses, transition, enter.data, exit.data, Style, child)
  }
}

@Suppress("OPT_IN_USAGE")
private fun ContentPosition.toAlignItems(): YogaAlign =
    when (this) {
      ContentPosition.TopStart,
      ContentPosition.CenterStart,
      ContentPosition.BottomStart -> YogaAlign.FLEX_START
      ContentPosition.TopCenter,
      ContentPosition.Center,
      ContentPosition.BottomCenter -> YogaAlign.CENTER
      ContentPosition.TopEnd,
      ContentPosition.CenterEnd,
      ContentPosition.BottomEnd -> YogaAlign.FLEX_END
    }

@Suppress("OPT_IN_USAGE")
private fun ContentPosition.toJustifyContent(): YogaJustify =
    when (this) {
      ContentPosition.TopStart,
      ContentPosition.TopCenter,
      ContentPosition.TopEnd -> YogaJustify.FLEX_START
      ContentPosition.CenterStart,
      ContentPosition.Center,
      ContentPosition.CenterEnd -> YogaJustify.CENTER
      ContentPosition.BottomStart,
      ContentPosition.BottomCenter,
      ContentPosition.BottomEnd -> YogaJustify.FLEX_END
    }
