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
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.HookGlobalKey
import com.facebook.litho.RenderScope
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.getUpdatedState
import com.facebook.litho.onCleanup
import com.facebook.litho.state.StateId
import com.facebook.litho.useEffect
import com.facebook.rendercore.thread.utils.ThreadUtils

/** The immutable terminal outcome of an imperative animation command. */
enum class AnimationEnd {
  Finished,
  Interrupted,
  Cancelled,
}

/**
 * Imperative wrapper around [AnimatedValue].
 *
 * Unlike [useAnimatedValue], callers drive an [Animatable] explicitly from event handlers and bind
 * callbacks via [startAnimation], [jumpTo], and [stop].
 */
@ExperimentalLithoApi
class Animatable<T>
internal constructor(
    internal val animatedValue: AnimatedValue<T>,
    private val driver: AnimationDriver,
) {
  private var target = animatedValue.currentValueRaw
  private var onEnd: ((AnimationEnd) -> Unit)? = null
  private var commandVersion = 0L
  @Volatile
  internal var isHandoff = false
    private set

  internal var runOwner: AnimationRunOwner? = null
    private set

  internal var completionContext: ComponentContext? = null

  init {
    animatedValue.onComplete = { completeCommand(AnimationEnd.Finished) }
  }

  /** The current animated value, suitable for passing to `Style.*` APIs. */
  val value: Value<T>
    get() = animatedValue

  /** True if an animation is actively running. */
  val isRunning: Boolean
    get() = !isHandoff && animatedValue.isRunning

  /**
   * Starts an animation toward [target]. [onEnd] is delivered once after frame invalidations,
   * including no-op commands. Replacement interrupts the previous command; [stop] or unmount
   * cancels it. Taking over a value owned by a coroutine run cancels that run. Continue callback
   * chains only for [AnimationEnd.Finished].
   */
  @MainThread
  @JvmOverloads
  fun startAnimation(
      target: T,
      spec: FiniteAnimationSpec<T>,
      onEnd: ((AnimationEnd) -> Unit)? = null,
  ) {
    ThreadUtils.assertMainThread()
    startCommand(null, target, spec, onEnd)
  }

  internal fun startCommand(
      owner: AnimationRunOwner?,
      target: T,
      spec: FiniteAnimationSpec<T>,
      onEnd: ((AnimationEnd) -> Unit)?,
  ): Long {
    val version = ++commandVersion
    val animator = animatedValue.prepareAnimator(target, spec)
    if (commandVersion == version && runOwner !== owner) {
      runOwner?.interrupt(this, AnimationEnd.Interrupted)
    }
    if (commandVersion != version || owner?.isReleased == true) {
      enqueueEnd(onEnd, AnimationEnd.Interrupted)
      return version
    }
    completeCommand(AnimationEnd.Interrupted)
    if (!claim(owner)) {
      enqueueEnd(onEnd, AnimationEnd.Cancelled)
      return version
    }
    driver.anchorInCompletionFrame()
    animatedValue.installAnimator(animator)
    this.target = target
    this.onEnd = onEnd
    if (animatedValue.hasAnimator) {
      driver.updatePendingWork(true)
    } else {
      driver.stop()
      completeCommand(AnimationEnd.Finished)
    }
    return version
  }

  /**
   * Immediately sets the value to [target] without animation. Must be called on the main thread.
   */
  @MainThread
  fun jumpTo(target: T) {
    ThreadUtils.assertMainThread()
    snapCommand(null, target)
  }

  internal fun snapCommand(owner: AnimationRunOwner?, target: T) {
    if (owner?.isReleased == true) return
    val version = ++commandVersion
    if (runOwner !== owner) runOwner?.interrupt(this, AnimationEnd.Interrupted)
    if (commandVersion != version || !claim(owner)) return
    completeCommand(AnimationEnd.Interrupted)
    this.target = target
    animatedValue.snapTo(target)
    driver.updatePendingWork(animatedValue.hasAnimator)
  }

  /** Stops the current animation at its present value. Must be called on the main thread. */
  @MainThread
  fun stop() {
    ThreadUtils.assertMainThread()
    stopCommand()
  }

  private fun stopCommand() {
    val version = ++commandVersion
    runOwner?.interrupt(this, AnimationEnd.Cancelled)
    if (commandVersion != version) return
    completeCommand(AnimationEnd.Cancelled)
    animatedValue.stop()
    driver.stop()
  }

  internal fun unmount() {
    stopCommand()
    driver.unmount()
  }

  internal fun cancelCommand(owner: AnimationRunOwner?, version: Long) {
    if (runOwner !== owner || commandVersion != version || onEnd == null) return
    ++commandVersion
    completeCommand(AnimationEnd.Cancelled)
    animatedValue.stop()
    driver.stop()
  }

  internal fun releaseClaim(
      owner: AnimationRunOwner,
      preserveAnimation: Boolean,
      outcome: AnimationEnd,
  ) {
    if (runOwner !== owner) return
    completeCommand(outcome)
    runOwner = null
    isHandoff = false
    if (!preserveAnimation) {
      ++commandVersion
      animatedValue.stop()
      driver.stop()
    }
    driver.bind(null)
  }

  internal fun transferClaim(owner: AnimationRunOwner, replacement: AnimationRunOwner) {
    if (runOwner !== owner) return
    completeCommand(AnimationEnd.Cancelled)
    runOwner = replacement
    isHandoff = true
  }

  internal fun freezeHandoff(owner: AnimationRunOwner) {
    if (runOwner !== owner || !isHandoff) return
    driver.stop()
    driver.bind(null)
  }

  private fun claim(owner: AnimationRunOwner?): Boolean {
    if (owner == null) return true
    if (runOwner !== owner) {
      if (!owner.claim(this)) return false
      runOwner = owner
    }
    isHandoff = false
    driver.bind(owner.playback)
    return true
  }

  internal fun bindRunPlayback(owner: AnimationRunOwner, playback: AnimationPlayback?) {
    if (runOwner === owner && !isHandoff) driver.bind(playback)
  }

  /**
   * Publishes the active command's target and ends it [AnimationEnd.Finished] asynchronously.
   *
   * ```kotlin
   * val alpha = useAnimatable(0.4f, FloatTypeConverter)
   * // From an event handler:
   * alpha.startAnimation(1f, tween(300.ms)) { outcome -> /* handle completion */ }
   * // From a later event handler, complete the command immediately:
   * alpha.finish()
   * ```
   */
  @MainThread
  fun finish() {
    ThreadUtils.assertMainThread()
    animatedValue.finish(target)
    driver.updatePendingWork(animatedValue.hasAnimator)
  }

  private fun completeCommand(outcome: AnimationEnd) {
    target = animatedValue.currentValueRaw
    val callback = onEnd
    onEnd = null
    enqueueEnd(callback, outcome)
  }

  private fun enqueueEnd(callback: ((AnimationEnd) -> Unit)?, outcome: AnimationEnd) {
    if (callback == null) return
    val context = completionContext
    AnimationController.enqueueCompletion {
      dispatchAnimationCompletion(context) { callback(outcome) }
    }
  }
}

/**
 * Creates an [Animatable] for imperative animation control.
 *
 * Use this when animation should be triggered directly from a callback instead of being inferred
 * from a target value during render.
 */
@ExperimentalLithoApi
@Hook
fun <T> ComponentScope.useAnimatable(
    initialValue: T,
    typeConverter: TypeConverter<T>,
    label: String = "",
): Animatable<T> {
  val hookKey = animationHookKey()
  val lithoTree = context.lithoTree ?: error("useAnimatable must be called within a LithoTree")
  val stateId = StateId(
      lithoTree.id,
      HookGlobalKey(
          context.globalKey,
          hookKey,
          if (context.isContextForLayout) RenderScope.Layout else RenderScope.Resolve,
      ),
  )

  val animatable = getUpdatedState {
    createAnimatedValue(
        initialValue = initialValue,
        typeConverter = typeConverter,
        stateId = stateId,
        isLayoutContext = context.isContextForLayout,
        stateUpdater = lithoTree.stateUpdater,
        label = label,
    ) { value, driver ->
      Animatable(value, driver)
    }
  }

  val completionContext = context
  useEffect(completionContext) {
    animatable.completionContext = completionContext
    null
  }

  useEffect(Unit) {
    onCleanup {
      animatable.unmount()
      animatable.completionContext = null
    }
  }

  return animatable
}
