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
  internal var completionContext: ComponentContext? = null

  init {
    animatedValue.onComplete = { completeCommand(AnimationEnd.Finished) }
  }

  /** The current animated value, suitable for passing to `Style.*` APIs. */
  val value: Value<T>
    get() = animatedValue

  /** True if an animation is actively running. */
  val isRunning: Boolean
    get() = animatedValue.isRunning

  /**
   * Starts an animation toward [target]. [onEnd] is delivered asynchronously after frame
   * invalidations, including for commands that require no animation. Replacement interrupts the
   * previous command; [stop] or owner unmount cancels it.
   */
  @MainThread
  @JvmOverloads
  fun startAnimation(
      target: T,
      spec: FiniteAnimationSpec<T>,
      onEnd: ((AnimationEnd) -> Unit)? = null,
  ) {
    ThreadUtils.assertMainThread()
    animatedValue.retarget(target, spec, preserveEqualTargetTiming = true)
    driver.anchorInCompletionFrame()
    completeCommand(AnimationEnd.Interrupted)
    this.target = target
    this.onEnd = onEnd
    if (animatedValue.hasAnimator) {
      driver.updatePendingWork(true)
    } else {
      driver.stop()
      completeCommand(AnimationEnd.Finished)
    }
  }

  /**
   * Immediately sets the value to [target] without animation. Must be called on the main thread.
   */
  @MainThread
  fun jumpTo(target: T) {
    ThreadUtils.assertMainThread()
    completeCommand(AnimationEnd.Interrupted)
    this.target = target
    animatedValue.snapTo(target)
    driver.updatePendingWork(animatedValue.hasAnimator)
  }

  /** Stops the current animation at its present value. Must be called on the main thread. */
  @MainThread
  fun stop() {
    ThreadUtils.assertMainThread()
    completeCommand(AnimationEnd.Cancelled)
    animatedValue.stop()
    driver.stop()
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
    val callback = onEnd ?: return
    onEnd = null
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
      animatable.stop()
      animatable.completionContext = null
    }
  }

  return animatable
}
