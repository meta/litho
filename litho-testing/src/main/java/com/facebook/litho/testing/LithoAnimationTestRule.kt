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

import com.facebook.litho.animation.AnimationController
import com.facebook.rendercore.ms
import kotlin.time.Duration
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit rule that installs a deterministic animation frame driver for the duration of a test.
 *
 * This rule keeps [AnimationController] global while giving animation tests explicit control over
 * frame delivery and frame timestamps. Tests use duration scale 1 unless [durationScale] is set.
 * [advanceBy] and [advanceTo] advance wall time: if an animation is scheduled but has not received
 * its first frame yet, the rule first dispatches a frame at the current test time, then advances to
 * the requested time. Tests that need Litho or Robolectric work flushed after each frame can
 * provide [afterFrame], for example `shadowOf(getMainLooper()).idle()`.
 */
class LithoAnimationTestRule(
    private val afterFrame: () -> Unit = {},
) : TestRule {

  private val frameDriver = TestAnimationFrameDriver()

  val currentTimeNanos: Long
    get() = frameDriver.currentFrameTimeNanos

  /** Override during a test; defaults to 1. Zero disables motion; positive scales floor at 0.01. */
  var durationScale: Float
    get() = AnimationController.durationScale
    set(value) {
      AnimationController.setDurationScaleForTest(value)
    }

  fun hasScheduledFrames(): Boolean = frameDriver.hasScheduledFrameCallbacks()

  /** Runs one raw frame after [frameDelay]. New duration-based animations start on this frame. */
  fun runNextFrame(frameDelay: Duration = 16.ms) {
    frameDriver.runNextFrame(frameDelay, afterFrame)
  }

  /** Advances wall time by [duration], bootstrapping newly scheduled animations. */
  fun advanceBy(duration: Duration) {
    frameDriver.advanceBy(duration, afterFrame)
  }

  /** Advances wall time until [frameTime], bootstrapping newly scheduled animations. */
  fun advanceTo(frameTime: Duration) {
    frameDriver.advanceTo(frameTime, afterFrame)
  }

  /** Advances wall time until [frameTimeNanos], bootstrapping scheduled animations. */
  fun advanceTo(frameTimeNanos: Long) {
    frameDriver.advanceTo(frameTimeNanos, afterFrame)
  }

  override fun apply(base: Statement, description: Description): Statement =
      object : Statement() {
        override fun evaluate() {
          AnimationController.withFrameDriverForTest(frameDriver) { base.evaluate() }
        }
      }
}
