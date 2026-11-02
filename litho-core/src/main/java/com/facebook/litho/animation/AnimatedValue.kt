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
import com.facebook.litho.HookUpdater
import com.facebook.litho.StateUpdater
import com.facebook.litho.Value
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateReadRecorder

/**
 * A mutable, animation-aware value holder that implements [Value] for direct use in Litho Style
 * APIs.
 *
 * `AnimatedValue` integrates with Litho's state read tracking for automatic phase-skipping: when a
 * component reads [value] during bind (e.g., `Style.alpha(animatedFloat)`), only the bind phase
 * runs on animation updates — layout and resolve are skipped.
 *
 * ## Value Ownership
 *
 * Unlike [State][com.facebook.litho.State], the animated value is stored directly in this instance
 * rather than in the component state container. State updates use a no-op
 * [HookUpdater][com.facebook.litho.HookUpdater] — they serve purely as re-render signals. The
 * actual value is read from [_currentValue] when the component re-evaluates.
 *
 * ## Animation Lifecycle
 * - [retarget]: Starts an animation toward a target value. If an animation is already running, it
 *   is interrupted with velocity preservation for smooth retargeting. The caller is responsible for
 *   scheduling its owner for frame callbacks.
 * - [snapTo]: Immediately sets the value without animation and triggers a re-render.
 * - [restart]: Restarts an animation from an explicit initial value, published on the next frame.
 * - [stop]: Stops the current animation at its present value.
 *
 * ## Frame Updates
 *
 * The owner's [AnimationDriver] advances its clock and schedules frames. Independent values use
 * [onFrame]; groups evaluate every slot via [evaluateFrame] before publishing invalidations via
 * [publishFrame]. Values only read their shared [clock].
 *
 * @param T The type of the animated value.
 * @see AnimationController
 * @see AnimationSpec
 */
class AnimatedValue<T>
internal constructor(
    initialValue: T,
    /** The type converter used for float-space animation computation. */
    val typeConverter: TypeConverter<T>,
    /** The state ID used for read tracking and re-render signaling. */
    val stateId: StateId,
    internal val isLayoutContext: Boolean,
    private val stateUpdater: StateUpdater,
    val label: String,
    internal val clock: AnimationClock,
) : Value<T> {

  @Volatile private var _currentValue: T = initialValue
  @Volatile private var currentAnimator: Animator<T>? = null

  private var runOriginNanos: Long = UNSET_RUN_ORIGIN
  private var frameUpdate: Int = 0
  /**
   * True after this evaluator has received a frame, including while its clock is paused.
   *
   * Set on the first frame after [retarget], cleared on completion or [stop]. Not reset by
   * [retarget] itself, so retargeting an active animation preserves the active state.
   */
  @Volatile
  internal var isActive: Boolean = false
    private set

  private val attribution: String =
      if (label.isEmpty()) ANIMATION_ATTRIBUTION else "$ANIMATION_ATTRIBUTION:$label"

  /** Cached because its fields are fixed for the value's lifetime; reused every frame. */
  private val frameInvalidation = AnimationFrameInvalidation(stateId, isLayoutContext, attribution)

  /**
   * The current animated value.
   *
   * Reading this property records a state read for phase-skipping. When the animation updates, only
   * the phases that actually read this value will re-run.
   */
  override val value: T
    get() {
      StateReadRecorder.read(stateId)
      return _currentValue
    }

  /**
   * The current animated value WITHOUT recording a state read. For holders (e.g. [SlotValue]) that
   * have already recorded their own read against the slot's [stateId] and only need the latest
   * frame value.
   */
  internal val currentValueRaw: T
    get() = _currentValue

  /** True if an animator has been set up via [retarget] and hasn't completed yet. */
  internal val hasAnimator: Boolean
    get() = currentAnimator != null

  /** True if an animation is actively running: has an animator AND is receiving frames. */
  val isRunning: Boolean
    get() = hasAnimator && isActive && clock.isTicking

  /**
   * Starts an animation toward [target] using the given [spec].
   *
   * If an animation is already running, it is interrupted: the current velocity is captured and
   * passed to the new animation for smooth retargeting (particularly effective with spring
   * animations).
   *
   * Must be called on the main thread.
   *
   * @param target The target value to animate toward.
   * @param spec The animation specification (e.g., `tween()`, `spring()`).
   */
  @MainThread
  fun retarget(target: T, spec: AnimationSpec<T>) {
    retarget(target, spec, preserveEqualTargetTiming = false)
  }

  @MainThread
  internal fun retarget(target: T, spec: AnimationSpec<T>, preserveEqualTargetTiming: Boolean) {
    val stationary = _currentValue == target && currentAnimator == null
    if (stationary && !preserveEqualTargetTiming) return
    val velocity = currentVelocity()
    val animator = spec.createAnimator(_currentValue, target, typeConverter, velocity)
    require(spec !is FiniteAnimationSpec<*> || animator.durationNanos >= 0L) {
      "FiniteAnimationSpec must create an animator with a finite duration."
    }
    val noOp = _currentValue == target && animator.isFinished(0L) && animator.getValue(0L) == target
    if (noOp) {
      currentAnimator = null
      resetFrameState()
      isActive = false
      return
    }
    currentAnimator = animator
    runOriginNanos = clock.playTimeNanos
  }

  /**
   * Restarts an animation from an explicit [initialValue] toward [target] with zero initial
   * velocity. Like [retarget], the caller schedules its owner for frames.
   *
   * Deliberately does not write [_currentValue]: the first frame republishes [initialValue] only
   * when it differs from what is mounted (no redundant bind), so [_currentValue] is stale until
   * then.
   */
  @MainThread
  internal fun restart(initialValue: T, target: T, spec: AnimationSpec<T>) {
    stop()
    currentAnimator = spec.createAnimator(initialValue, target, typeConverter)
  }

  /**
   * Immediately sets the value to [target] without animation.
   *
   * If an animation is running, it is cancelled. When [notify] is true (the default) a synchronous
   * state update is triggered so readers re-render to reflect the new value immediately. Pass
   * `notify = false` to set the value silently — e.g. to seed a start value right before
   * [retarget], where a re-render would schedule a relayout that races the first animation frame.
   *
   * Must be called on the main thread.
   */
  @JvmOverloads
  @MainThread
  fun snapTo(target: T, notify: Boolean = true) {
    if (_currentValue == target && currentAnimator == null) return
    currentAnimator = null
    _currentValue = target
    resetFrameState()
    isActive = false
    if (notify) invalidate(dirty = true)
  }

  /**
   * Stops the current animation at its present value.
   *
   * The value remains at whatever it was when `stop()` was called.
   *
   * Must be called on the main thread.
   */
  @MainThread
  fun stop() {
    currentAnimator = null
    resetFrameState()
    isActive = false
  }

  @MainThread
  internal fun finish(target: T) {
    if (!prepareFinish(target)) return
    onComplete?.invoke()
    publishFrame()
  }

  /** Stages a finite endpoint so a group can complete every slot before notifying any reader. */
  internal fun prepareFinish(target: T): Boolean {
    if (currentAnimator == null) return false
    currentAnimator = null
    _currentValue = target
    resetFrameState()
    isActive = false
    frameUpdate = FRAME_FINISHED
    return true
  }

  /**
   * Internal observer invoked on natural completion or [finish], before invalidation can replace
   * the completed work. User notifications are queued by the owner.
   */
  internal var onComplete: (() -> Unit)? = null

  /**
   * Called by this value's owner to advance an independent animation.
   *
   * Updates [_currentValue] based on the elapsed play time and triggers a re-render. If the
   * animation finishes, advances its owner's internal completion before the re-render signal.
   */
  @MainThread
  internal fun onFrame(playTimeNanos: Long): Boolean {
    val update = evaluateFrame(playTimeNanos)
    if (update and FRAME_FINISHED != 0) onComplete?.invoke()
    publishFrame()
    return currentAnimator != null
  }

  /** Evaluates without notifying readers; a group evaluates every slot before publishing any. */
  @MainThread
  internal fun evaluateFrame(playTimeNanos: Long): Int {
    frameUpdate = 0
    val anim = currentAnimator ?: return 0
    if (runOriginNanos == UNSET_RUN_ORIGIN) runOriginNanos = playTimeNanos
    isActive = true
    val playTime =
        if (AnimationController.durationScale == 0f && anim.durationNanos >= 0L) anim.durationNanos
        else playTimeNanos - runOriginNanos
    val newValue = anim.getValue(playTime)
    val changed = _currentValue != newValue
    _currentValue = newValue
    val finished = anim.isFinished(playTime)
    if (finished) {
      currentAnimator = null
      isActive = false
    }
    frameUpdate =
        FRAME_EVALUATED or
            (if (changed) FRAME_CHANGED else 0) or
            (if (finished) FRAME_FINISHED else 0)
    return frameUpdate
  }

  internal fun prepareDisabledValue(value: T) {
    frameUpdate = if (_currentValue != value) FRAME_CHANGED else 0
    _currentValue = value
    resetFrameState()
    isActive = false
  }

  @MainThread
  internal fun publishFrame() {
    val update = frameUpdate
    frameUpdate = 0
    if (update != 0) invalidate(dirty = update and (FRAME_CHANGED or FRAME_FINISHED) != 0)
  }

  private fun currentVelocity(): AnimationVector? {
    val anim = currentAnimator ?: return null
    if (!isActive || runOriginNanos == UNSET_RUN_ORIGIN) return null
    return typeConverter.newVector().also {
      anim.getVelocity(clock.playTimeNanos - runOriginNanos, it)
    }
  }

  private fun resetFrameState() {
    runOriginNanos = UNSET_RUN_ORIGIN
  }

  /**
   * Signals readers of this value.
   *
   * When the [StateUpdater] enables same-frame invalidation, the invalidation is recorded with
   * [AnimationInvalidationDispatcher] for in-frame coalescing only when [dirty] — the caller sets
   * it when the value changed this frame or the animation completed (see [onFrame], [snapTo]). When
   * disabled, it takes the pre-feature path: an unconditional [StateUpdater.updateHookStateSync]
   * re-render signal every frame, regardless of [dirty].
   */
  private fun invalidate(dirty: Boolean) {
    if (stateUpdater.isAnimationFrameInvalidationEnabled) {
      if (!dirty) return
      AnimationInvalidationDispatcher.record(stateUpdater, frameInvalidation)
    } else {
      stateUpdater.updateHookStateSync(stateId, HookUpdater.NoOp, attribution, isLayoutContext)
    }
  }

  override fun equals(other: Any?): Boolean =
      other is AnimatedValue<*> && this.stateId == other.stateId

  override fun hashCode(): Int = stateId.hashCode()

  override fun toString(): String =
      "AnimatedValue(id=$stateId, current=$_currentValue, animating=$isRunning)"

  companion object {
    private const val ANIMATION_ATTRIBUTION = "animation"
    private const val UNSET_RUN_ORIGIN = Long.MIN_VALUE
    private const val FRAME_EVALUATED = 1
    private const val FRAME_CHANGED = 2
    internal const val FRAME_FINISHED = 4
  }
}

internal fun List<AnimatedValue<*>>.hasAnimator(): Boolean {
  for (slot in indices) {
    if (this[slot].hasAnimator) return true
  }
  return false
}

/** The hook-local key used to build an animated value's read-tracking [StateId]. */
internal fun ComponentScope.animationHookKey(): com.facebook.litho.HookKey = requireCurrentHookKey()

/** Creates an independent value and passes its driver to the owning state before either escapes. */
internal inline fun <T, R> createAnimatedValue(
    initialValue: T,
    typeConverter: TypeConverter<T>,
    stateId: StateId,
    isLayoutContext: Boolean,
    stateUpdater: StateUpdater,
    label: String,
    retain: (AnimatedValue<T>, AnimationDriver) -> R,
): R {
  val driver =
      object : AnimationDriver() {
        val value = AnimatedValue(
            initialValue,
            typeConverter,
            stateId,
            isLayoutContext,
            stateUpdater,
            label,
            clock = this,
        )

        override fun advance(playTimeNanos: Long): Boolean = value.onFrame(playTimeNanos)
      }
  return retain(driver.value, driver)
}
