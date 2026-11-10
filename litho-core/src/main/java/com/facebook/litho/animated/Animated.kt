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

package com.facebook.litho.animated

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import androidx.annotation.ColorInt
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.facebook.litho.DynamicValue

object Animated {
  /**
   * Returns an [AnimatedAnimation] ready for running timing animation that calculate animated
   * values and set them on target param.
   *
   * By default it uses [AccelerateDecelerateInterpolator] and duration of 300 milliseconds.
   */
  fun timing(
      target: DynamicValue<Float>,
      to: Float,
      duration: Long = 300,
      easing: Interpolator = Easing.accelerateDecelerate(),
      animationFinishListener: AnimationFinishListener? = null,
      onUpdate: ((Float) -> Unit)? = null,
  ): AnimatedAnimation {

    val animator = ValueAnimator.ofFloat(target.get(), to)
    animator.duration = duration
    animator.interpolator = easing
    animator.addUpdateListener { animation ->
      val animatedValue = animation.animatedValue as Float
      target.set(animatedValue)
      onUpdate?.invoke(animatedValue)
    }
    val animatorAnimation = AnimatorAnimation(animator)
    if (animationFinishListener != null) {
      animatorAnimation.addListener(animationFinishListener)
    }
    return animatorAnimation
  }

  /**
   * Returns an [AnimatedAnimation] ready for running timing animation that calculate animated color
   * values and set them on target param.
   *
   * By default it uses [AccelerateDecelerateInterpolator] and duration of 300 milliseconds.
   */
  fun timingArgb(
      target: DynamicValue<Int>,
      @ColorInt to: Int,
      duration: Long = 300,
      easing: Interpolator = Easing.accelerateDecelerate(),
      animationFinishListener: AnimationFinishListener? = null,
      onUpdate: ((Int) -> Unit)? = null,
  ): AnimatedAnimation {
    val animator = ValueAnimator.ofFloat(0f, 1f)
    animator.duration = duration
    animator.interpolator = easing
    val evaluator: ArgbEvaluator = ArgbEvaluator()
    animator.addUpdateListener { animation ->
      val fraction = animation.animatedValue as Float
      val animatedValue = evaluator.evaluate(fraction, target.get(), to) as Int
      target.set(animatedValue)
      onUpdate?.invoke(animatedValue)
    }
    val animatorAnimation = AnimatorAnimation(animator)
    if (animationFinishListener != null) {
      animatorAnimation.addListener(animationFinishListener)
    }
    return animatorAnimation
  }

  /**
   * Returns an [AnimatedAnimation] ready for running spring animation based on the [SpringConfig]
   * params
   *
   * By default it uses medium stiffness and damping.
   */
  fun spring(
      target: DynamicValue<Float>,
      to: Float,
      springConfig: SpringConfig = SpringConfig(),
      animationFinishListener: AnimationFinishListener? = null,
      onUpdate: ((Float) -> Unit)? = null,
  ): AnimatedAnimation {
    val springAnimation = SpringAnimation(DynamicFloatValueHolder(target))
    val springForce = SpringForce()
    springForce.finalPosition = to
    springForce.stiffness = springConfig.stiffness
    springForce.dampingRatio = springConfig.dampingRatio
    springAnimation.spring = springForce
    onUpdate?.let { springAnimation.addUpdateListener { _, value, _ -> onUpdate(value) } }
    val animatedSpringAnimation = AnimatedSpringAnimation(springAnimation)
    animationFinishListener?.let { animatedSpringAnimation.addListener(animationFinishListener) }

    return animatedSpringAnimation
  }

  /**
   * Returns [SequenceAnimation] ready for running collection of animations in sequence one after
   * another. The order of arguments is the order of the sequence
   */
  fun sequence(vararg animations: AnimatedAnimation): AnimatedAnimation =
      SequenceAnimation(animations)

  /** Returns [ParallelAnimation] ready for running collection of animations in parallel */
  fun parallel(vararg animations: AnimatedAnimation): AnimatedAnimation =
      ParallelAnimation(animations)

  /**
   * Returns [AnimatedAnimation] ready for running collection of animations in stagger with given
   * value in milliseconds
   */
  fun stagger(staggerMs: Long, vararg animations: AnimatedAnimation): AnimatedAnimation {
    val staggerAnimations: ArrayList<AnimatedAnimation> = ArrayList()
    animations.forEachIndexed { index, animation ->
      staggerAnimations.add(delay(staggerMs * index, animation))
    }
    return ParallelAnimation(staggerAnimations.toTypedArray())
  }

  /**
   * Returns [LoopAnimation] ready for running [AnimatedAnimation] object repeated @repeatCount
   * times. By default it runs infinite number of times.
   */
  fun loop(animation: AnimatedAnimation, repeatCount: Int = -1): AnimatedAnimation =
      LoopAnimation(repeatCount, animation)

  /**
   * Returns [AnimatedAnimation] ready for running single or a collection of animations after given
   * delay in milliseconds
   */
  fun delay(startDelay: Long, animation: AnimatedAnimation): AnimatedAnimation {
    val delayAnimator = ValueAnimator.ofFloat(0f, 1f)
    delayAnimator.startDelay = startDelay
    return sequence(AnimatorAnimation(delayAnimator), animation)
  }
}

/**
 * Interface representing single animation (like [Animated.timing] or [Animated.spring]) or a
 * collection of them
 */
interface AnimatedAnimation {
  fun isActive(): Boolean

  fun start()

  fun cancel()

  fun addListener(finishListener: AnimationFinishListener)
}

/** Listener that allows to listen for animation finish or cancel events. */
fun interface AnimationFinishListener {
  /** triggers when animation or set of animations is completed or cancelled */
  fun onFinish(cancelled: Boolean)
}

private class AnimatedSpringAnimation(val springAnimation: SpringAnimation) : AnimatedAnimation {
  override fun isActive(): Boolean = springAnimation.isRunning

  override fun start() {
    springAnimation.start()
  }

  override fun cancel() {
    springAnimation.cancel()
  }

  @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
  override fun addListener(animationFinishListener: AnimationFinishListener) {
    springAnimation.addEndListener { _, cancelled, _, _ ->
      animationFinishListener.onFinish(cancelled)
    }
  }
}

internal class AnimatorAnimation(val animator: ValueAnimator) : AnimatedAnimation {
  private var animationCancelled: Boolean = false
  private var _isActive: Boolean = false

  override fun isActive(): Boolean = _isActive

  override fun start() {
    _isActive = true
    animationCancelled = false
    animator.start()
  }

  override fun cancel() {
    _isActive = false
    animationCancelled = true
    animator.cancel()
  }

  @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
  override fun addListener(animatorFinishListener: AnimationFinishListener) {
    animator.addListener(
        object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            if (animationCancelled) {
              // do not call onFinish if animation was cancelled
              return
            }
            animatorFinishListener.onFinish(false)
            _isActive = false
          }

          override fun onAnimationCancel(animation: Animator) {
            animatorFinishListener.onFinish(true)
          }
        },
    )
  }
}

/**
 * The stiffer the spring is, the harder it is to stretch it and the faster it undergoes dampening.
 * Spring damping ratio describes behavior after the disturbance, over-damp values (more than 1)
 * make the spring quickly return to the rest position. Under-damp values (less than 1) cause
 * overshooting. Damping equal to 0 will cause the spring to oscillate forever. (The lower the
 * damping ratio, the higher the bounciness)
 */
class SpringConfig(
    var stiffness: Float = SpringForce.STIFFNESS_LOW,
    var dampingRatio: Float = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY,
)

/** Bridge between Android [FloatValueHolder] and Litho DynamicValue<Float> */
private class DynamicFloatValueHolder(val dynamicValue: DynamicValue<Float>) : FloatValueHolder() {
  override fun getValue(): Float {
    return dynamicValue.get()
  }

  override fun setValue(value: Float) {
    dynamicValue.set(value)
  }
}
