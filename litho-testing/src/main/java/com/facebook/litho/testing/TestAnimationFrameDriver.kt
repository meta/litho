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

package com.facebook.litho.testing

import android.view.Choreographer
import com.facebook.litho.animation.AnimationFrameDriver
import com.facebook.rendercore.ms
import kotlin.time.Duration

/**
 * Deterministic [AnimationFrameDriver] for tests.
 *
 * Tests explicitly advance frame delivery and frame timestamps instead of relying on Robolectric's
 * looper and [android.view.Choreographer] behavior.
 */
internal class TestAnimationFrameDriver : AnimationFrameDriver {

  private val scheduledCallbacks = linkedSetOf<Choreographer.FrameCallback>()
  private var isDispatchingFrame = false
  private var needsStartFrame = false

  var currentFrameTimeNanos: Long = 0L
    private set

  override fun postFrameCallback(callback: Choreographer.FrameCallback) {
    val hadScheduledCallbacks = scheduledCallbacks.isNotEmpty()
    scheduledCallbacks.add(callback)
    if (!hadScheduledCallbacks && !isDispatchingFrame) {
      needsStartFrame = true
    }
  }

  override fun removeFrameCallback(callback: Choreographer.FrameCallback) {
    scheduledCallbacks.remove(callback)
    if (scheduledCallbacks.isEmpty()) {
      needsStartFrame = false
    }
  }

  fun hasScheduledFrameCallbacks(): Boolean = scheduledCallbacks.isNotEmpty()

  fun runNextFrame(
      frameDelay: Duration = 16.ms,
      afterFrame: () -> Unit = {},
  ) {
    dispatchFrame(currentFrameTimeNanos + frameDelay.inWholeNanoseconds, afterFrame)
  }

  fun advanceBy(
      duration: Duration,
      afterFrame: () -> Unit = {},
  ) {
    advanceTo(currentFrameTimeNanos + duration.inWholeNanoseconds, afterFrame)
  }

  fun advanceTo(
      frameTime: Duration,
      afterFrame: () -> Unit = {},
  ) {
    advanceTo(frameTime.inWholeNanoseconds, afterFrame)
  }

  fun advanceTo(
      frameTimeNanos: Long,
      afterFrame: () -> Unit = {},
  ) {
    require(frameTimeNanos >= currentFrameTimeNanos) {
      "Frame time must be monotonic: $frameTimeNanos < $currentFrameTimeNanos"
    }

    var dispatchedFrame = false
    if (
        needsStartFrame && scheduledCallbacks.isNotEmpty() && frameTimeNanos > currentFrameTimeNanos
    ) {
      dispatchedFrame = dispatchFrame(currentFrameTimeNanos, afterFrame)
    }

    if (frameTimeNanos > currentFrameTimeNanos) {
      if (scheduledCallbacks.isNotEmpty()) {
        dispatchFrame(frameTimeNanos, afterFrame)
      } else {
        currentFrameTimeNanos = frameTimeNanos
      }
    } else if (!dispatchedFrame && scheduledCallbacks.isNotEmpty()) {
      dispatchFrame(frameTimeNanos, afterFrame)
    }
  }

  private fun dispatchFrame(
      frameTimeNanos: Long,
      afterFrame: () -> Unit,
  ): Boolean {
    currentFrameTimeNanos = frameTimeNanos
    val callbacks = scheduledCallbacks.toList()
    if (callbacks.isEmpty()) {
      return false
    }
    scheduledCallbacks.clear()
    needsStartFrame = false
    isDispatchingFrame = true
    try {
      callbacks.forEach { it.doFrame(frameTimeNanos) }
    } finally {
      isDispatchingFrame = false
    }
    afterFrame()
    return true
  }
}
