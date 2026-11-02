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

import android.content.Context
import android.view.Choreographer
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.facebook.rendercore.thread.utils.ThreadUtils
import java.util.ArrayDeque

/**
 * Coalesces active [FrameDrivenAnimation]s onto a shared [AnimationFrameDriver].
 *
 * On each frame, all registered animations are ticked via [FrameDrivenAnimation.onFrame]. Terminal
 * notifications are delivered after value invalidations flush. A frame remains scheduled while
 * either motion or notifications need work.
 *
 * ## Frame Coalescing
 *
 * A single driver callback ticks all active animations per frame, avoiding per-animation callback
 * overhead.
 *
 * ## Testability
 *
 * Tests can temporarily swap the underlying [frameDriver] with [withFrameDriverForTest] to advance
 * frame delivery and frame time deterministically.
 *
 * @see AnimatedValue
 */
object AnimationController {

  private val activeAnimations = mutableSetOf<FrameDrivenAnimation>()
  private val completions = ArrayDeque<PostFrameCallback>()
  private val frameCallback = Choreographer.FrameCallback(::onFrame)
  private var frameScheduled = false
  private var frameRequested = false
  private var isDispatchingFrame = false
  private var frameDriver: AnimationFrameDriver = ChoreographerAnimationFrameDriver
  private val systemDurationScale = SystemDurationScale(::updateDurationScale)
  private var segmentWallStartNanos = Long.MIN_VALUE
  private var segmentAnimationStartNanos = 0L
  private var lastWallTimeNanos = Long.MIN_VALUE

  internal var durationScale: Float = 1f
    private set

  /** Running motion excludes system-disabled animations; false does not imply callbacks drained. */
  val hasRunningAnimations: Boolean
    get() = durationScale > 0f && activeAnimations.isNotEmpty()

  @MainThread
  internal fun bindContext(context: Context) {
    systemDurationScale.bind(context)
  }

  private fun updateDurationScale(scale: Float) {
    ThreadUtils.assertMainThread()
    check(!isDispatchingFrame)
    val normalized =
        when {
          !scale.isFinite() || scale < 0f -> 1f
          scale == 0f -> 0f
          else -> scale.coerceAtLeast(0.01f)
        }
    if (durationScale == normalized) return
    if (lastWallTimeNanos != Long.MIN_VALUE) {
      segmentAnimationStartNanos = animationTime(lastWallTimeNanos)
      segmentWallStartNanos = lastWallTimeNanos
    }
    durationScale = normalized
    if (activeAnimations.isNotEmpty() || completions.isNotEmpty()) scheduleFrame()
  }

  @MainThread
  @VisibleForTesting
  internal fun setDurationScaleForTest(scale: Float) {
    check(systemDurationScale.isOverridden)
    updateDurationScale(scale)
  }

  /**
   * Registers an animation for frame callbacks. Called when an animation starts. Must be called on
   * the main thread.
   */
  @MainThread
  internal fun register(animation: FrameDrivenAnimation, forceFrame: Boolean = false) {
    ThreadUtils.assertMainThread()
    if (activeAnimations.add(animation) || forceFrame) {
      if (isDispatchingFrame) frameRequested = true
      scheduleFrame()
    }
  }

  /**
   * Unregisters an animation from frame callbacks. Called when an animation is stopped or snapped
   * to a value.
   */
  @MainThread
  internal fun unregister(animation: FrameDrivenAnimation) {
    ThreadUtils.assertMainThread()
    if (activeAnimations.remove(animation) && activeAnimations.isEmpty() && completions.isEmpty()) {
      cancelScheduledFrame()
    }
  }

  @MainThread
  internal fun enqueueCompletion(completion: PostFrameCallback) {
    ThreadUtils.assertMainThread()
    completions.addLast(completion)
    scheduleFrame()
  }

  /**
   * Temporarily replaces the frame driver for deterministic tests.
   *
   * Any previously scheduled frame is cleared before the driver swap and after [block] completes so
   * tests start from and restore a clean controller state.
   *
   * @param observeSystemDurationScale Read and observe Android's animator duration scale. When
   *   false, use scale 1 unless explicitly overridden by the test.
   */
  @MainThread
  internal inline fun <T> withFrameDriverForTest(
      driver: AnimationFrameDriver,
      observeSystemDurationScale: Boolean = false,
      block: () -> T,
  ): T {
    ThreadUtils.assertMainThread()
    return systemDurationScale.withOverride(observeSystemDurationScale) {
      val previousDriver = frameDriver
      val previousScale = durationScale
      resetControllerState()
      frameDriver = driver
      durationScale = 1f
      try {
        block()
      } finally {
        resetControllerState()
        durationScale = previousScale
        frameDriver = previousDriver
      }
    }
  }

  private fun scheduleFrame() {
    if (frameScheduled || isDispatchingFrame) return
    frameScheduled = true
    frameDriver.postFrameCallback(frameCallback)
  }

  private fun cancelScheduledFrame() {
    frameRequested = false
    if (frameScheduled) {
      frameDriver.removeFrameCallback(frameCallback)
      frameScheduled = false
    }
    if (!isDispatchingFrame) freezeTime()
  }

  private fun resetControllerState() {
    check(!isDispatchingFrame)
    cancelScheduledFrame()
    activeAnimations.clear()
    completions.clear()
    AnimationInvalidationDispatcher.clear()
    segmentWallStartNanos = Long.MIN_VALUE
    segmentAnimationStartNanos = 0L
    lastWallTimeNanos = Long.MIN_VALUE
  }

  private fun animationTime(wallTimeNanos: Long): Long {
    val elapsed = wallTimeNanos - segmentWallStartNanos
    val scaled =
        when (durationScale) {
          0f -> 0L
          1f -> elapsed
          else -> (elapsed / durationScale.toDouble()).toLong()
        }
    return segmentAnimationStartNanos + scaled
  }

  private fun freezeTime() {
    if (lastWallTimeNanos == Long.MIN_VALUE) return
    segmentAnimationStartNanos = animationTime(lastWallTimeNanos)
    segmentWallStartNanos = Long.MIN_VALUE
    lastWallTimeNanos = Long.MIN_VALUE
  }

  @MainThread
  private fun onFrame(frameTimeNanos: Long) {
    ThreadUtils.assertMainThread()
    check(!isDispatchingFrame) { "AnimationController frame dispatch is not re-entrant" }
    if (activeAnimations.isNotEmpty()) systemDurationScale.initialize()
    frameScheduled = false
    frameRequested = false
    isDispatchingFrame = true
    if (segmentWallStartNanos == Long.MIN_VALUE) segmentWallStartNanos = frameTimeNanos
    lastWallTimeNanos = frameTimeNanos
    val animationTimeNanos = animationTime(frameTimeNanos)
    try {
      if (activeAnimations.isNotEmpty()) {
        // Ticking may change registrations; completion delivery happens after the batch flush.
        val snapshot = activeAnimations.toTypedArray()
        AnimationInvalidationDispatcher.batch {
          for (animation in snapshot) {
            if (!activeAnimations.contains(animation)) continue
            if (!animation.onFrame(animationTimeNanos)) {
              activeAnimations.remove(animation)
            }
          }
        }
      }
      dispatchCompletions()
    } finally {
      isDispatchingFrame = false
      if (
          completions.isNotEmpty() ||
              (activeAnimations.isNotEmpty() && (durationScale > 0f || frameRequested))
      ) {
        scheduleFrame()
      } else {
        frameRequested = false
        freezeTime()
      }
    }
  }

  private fun dispatchCompletions() {
    var failure: Exception? = null
    // Notifications produced by callbacks or error handlers belong to a later frame.
    repeat(completions.size) {
      val completion = completions.removeFirst()
      try {
        completion.onPostFrame()
      } catch (exception: Exception) {
        val previous = failure
        if (previous == null) failure = exception
        else if (previous !== exception) previous.addSuppressed(exception)
      }
    }
    failure?.let { throw it }
  }
}
