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

import android.view.Choreographer
import com.facebook.rendercore.ms
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnimationControllerTest {

  @Test
  fun withFrameDriverForTest_ticksAnimationsDeterministically() {
    val frameDriver = TestAnimationFrameDriver()
    val frames = mutableListOf<Long>()

    AnimationController.withFrameDriverForTest(frameDriver) {
      AnimationController.register(
          FrameDrivenAnimation { frameTimeNanos ->
            frames.add(frameTimeNanos)
            frames.size < 2
          },
      )

      assertThat(AnimationController.hasRunningAnimations).isTrue()
      assertThat(frameDriver.hasScheduledFrameCallbacks()).isTrue()

      frameDriver.runNextFrame(100.ms)
      assertThat(frames).containsExactly(0L)
      assertThat(AnimationController.hasRunningAnimations).isTrue()
      assertThat(frameDriver.hasScheduledFrameCallbacks()).isTrue()

      frameDriver.runNextFrame(150.ms)
      assertThat(frames).containsExactly(0L, 150.ms.inWholeNanoseconds)
      assertThat(AnimationController.hasRunningAnimations).isFalse()
      assertThat(frameDriver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun scaledSegmentsPreserveContinuityAndExcludeIdle() {
    val driver = TestAnimationFrameDriver()
    val times = mutableListOf<Long>()
    val animation = FrameDrivenAnimation { timeNanos ->
      times.add(timeNanos)
      true
    }
    AnimationController.withFrameDriverForTest(driver) {
      AnimationController.register(animation)
      driver.runNextFrame(100.ms)
      driver.runNextFrame(200.ms)
      AnimationController.setDurationScaleForTest(2f)
      driver.runNextFrame(200.ms)
      AnimationController.setDurationScaleForTest(1f)
      driver.runNextFrame(1.nanoseconds)
      assertThat(times).containsExactly(0L, 200_000_000L, 300_000_000L, 300_000_001L)

      AnimationController.unregister(animation)
      driver.runNextFrame(60_000.ms)
      AnimationController.register(animation)
      driver.runNextFrame(16.ms)
      assertThat(times.last()).isEqualTo(300_000_001L)
      driver.runNextFrame(1.nanoseconds)
      assertThat(times.last()).isEqualTo(300_000_002L)
    }
  }

  @Test
  fun scaleNormalizationPreservesZeroAndBoundsPositiveSpeed() {
    val driver = TestAnimationFrameDriver()
    val times = mutableListOf<Long>()
    AnimationController.withFrameDriverForTest(driver) {
      for (scale in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
        AnimationController.setDurationScaleForTest(scale)
        assertThat(AnimationController.durationScale).isEqualTo(1f)
      }
      AnimationController.setDurationScaleForTest(Float.MIN_VALUE)
      assertThat(AnimationController.durationScale).isEqualTo(0.01f)
      AnimationController.register(
          FrameDrivenAnimation { timeNanos ->
            times.add(timeNanos)
            true
          },
      )
      driver.runNextFrame(1.ms)
      driver.runNextFrame(1.ms)
      assertThat(times).containsExactly(0L, (1_000_000L / 0.01f.toDouble()).toLong())

      AnimationController.setDurationScaleForTest(0f)
      assertThat(AnimationController.durationScale).isZero()
      assertThat(AnimationController.hasRunningAnimations).isFalse()
      driver.runNextFrame(1.ms)
      assertThat(times.last()).isEqualTo(times[times.lastIndex - 1])
      assertThat(driver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun disabledScaleHonorsFrameRequestsDuringTickAndCompletionWithoutPolling() {
    val driver = TestAnimationFrameDriver()
    AnimationController.withFrameDriverForTest(driver) {
      AnimationController.setDurationScaleForTest(0f)
      var ticks = 0
      lateinit var animation: FrameDrivenAnimation
      animation = FrameDrivenAnimation {
        ++ticks
        if (ticks == 1) AnimationController.register(animation, forceFrame = true)
        true
      }
      AnimationController.register(animation)
      driver.runNextFrame(16.ms)
      assertThat(ticks).isEqualTo(1)
      assertThat(driver.hasScheduledFrameCallbacks()).isTrue()
      driver.runNextFrame(16.ms)
      assertThat(ticks).isEqualTo(2)
      assertThat(driver.hasScheduledFrameCallbacks()).isFalse()

      AnimationController.enqueueCompletion {
        AnimationController.register(animation, forceFrame = true)
      }
      driver.runNextFrame(16.ms)
      assertThat(ticks).isEqualTo(3)
      assertThat(driver.hasScheduledFrameCallbacks()).isTrue()
      driver.runNextFrame(16.ms)
      assertThat(ticks).isEqualTo(4)
      assertThat(driver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun withFrameDriverForTest_unregisterCancelsScheduledFrame() {
    val frameDriver = TestAnimationFrameDriver()
    val animation = FrameDrivenAnimation { true }

    AnimationController.withFrameDriverForTest(frameDriver) {
      AnimationController.register(animation)

      assertThat(AnimationController.hasRunningAnimations).isTrue()
      assertThat(frameDriver.hasScheduledFrameCallbacks()).isTrue()

      AnimationController.unregister(animation)

      assertThat(AnimationController.hasRunningAnimations).isFalse()
      assertThat(frameDriver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun completionWithoutAnimations_waitsForAFrameAndSurvivesLastUnregister() {
    val driver = TestAnimationFrameDriver()
    val notifications = mutableListOf<String>()
    val animation = FrameDrivenAnimation { true }
    AnimationController.withFrameDriverForTest(driver) {
      AnimationController.register(animation)
      AnimationController.enqueueCompletion({ notifications.add("completed") })
      AnimationController.unregister(animation)

      assertThat(notifications).isEmpty()
      assertThat(AnimationController.hasRunningAnimations).isFalse()
      assertThat(driver.hasScheduledFrameCallbacks()).isTrue()
      driver.runNextFrame(16.ms)
      assertThat(notifications).containsExactly("completed")
      assertThat(driver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun completionProducedDuringTick_isDeliveredAfterAllAnimationsTick() {
    val driver = TestAnimationFrameDriver()
    val events = mutableListOf<String>()
    AnimationController.withFrameDriverForTest(driver) {
      AnimationController.register(
          FrameDrivenAnimation {
            events.add("first tick")
            assertThat(AnimationController.completionFrameTimeNanos).isEqualTo(Long.MIN_VALUE)
            AnimationController.enqueueCompletion {
              assertThat(AnimationController.completionFrameTimeNanos).isZero()
              events.add("completion")
            }
            false
          },
      )
      AnimationController.register(
          FrameDrivenAnimation {
            events.add("second tick")
            false
          },
      )

      driver.runNextFrame(16.ms)

      assertThat(events).containsExactly("first tick", "second tick", "completion")
      assertThat(AnimationController.completionFrameTimeNanos).isEqualTo(Long.MIN_VALUE)
      assertThat(driver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun completionProducedDuringDelivery_waitsForTheNextFrame() {
    val driver = TestAnimationFrameDriver()
    val notifications = mutableListOf<String>()
    AnimationController.withFrameDriverForTest(driver) {
      AnimationController.enqueueCompletion(
          {
            notifications.add("first")
            AnimationController.enqueueCompletion({ notifications.add("second") })
          },
      )

      driver.runNextFrame(16.ms)
      assertThat(notifications).containsExactly("first")
      assertThat(driver.hasScheduledFrameCallbacks()).isTrue()
      driver.runNextFrame(16.ms)
      assertThat(notifications).containsExactly("first", "second")
      assertThat(driver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun failingCompletion_preservesRemainingDeliveryAndNewWork() {
    val driver = TestAnimationFrameDriver()
    val notifications = mutableListOf<String>()
    val failure = IllegalStateException("completion failed")
    AnimationController.withFrameDriverForTest(driver) {
      AnimationController.enqueueCompletion(
          {
            AnimationController.enqueueCompletion({ notifications.add("next frame") })
            throw failure
          },
      )
      AnimationController.enqueueCompletion({ notifications.add("same frame") })

      assertThatThrownBy { driver.runNextFrame(16.ms) }.isSameAs(failure)
      assertThat(notifications).containsExactly("same frame")
      assertThat(driver.hasScheduledFrameCallbacks()).isTrue()
      driver.runNextFrame(16.ms)
      assertThat(notifications).containsExactly("same frame", "next frame")
      assertThat(driver.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  @Test
  fun driverReset_discardsPendingCompletions() {
    val first = TestAnimationFrameDriver()
    val second = TestAnimationFrameDriver()
    val notifications = mutableListOf<String>()
    AnimationController.withFrameDriverForTest(first) {
      AnimationController.enqueueCompletion({ notifications.add("discarded") })
    }
    AnimationController.withFrameDriverForTest(second) {
      second.runNextFrame(16.ms)
      assertThat(notifications).isEmpty()
      assertThat(first.hasScheduledFrameCallbacks()).isFalse()
      assertThat(second.hasScheduledFrameCallbacks()).isFalse()
    }
  }

  private class TestAnimationFrameDriver : AnimationFrameDriver {

    private val scheduledCallbacks = linkedSetOf<Choreographer.FrameCallback>()
    private var currentFrameTimeNanos: Long = 0L

    override fun postFrameCallback(callback: Choreographer.FrameCallback) {
      scheduledCallbacks.add(callback)
    }

    override fun removeFrameCallback(callback: Choreographer.FrameCallback) {
      scheduledCallbacks.remove(callback)
    }

    fun hasScheduledFrameCallbacks(): Boolean = scheduledCallbacks.isNotEmpty()

    fun runNextFrame(frameDelay: Duration) {
      currentFrameTimeNanos += frameDelay.inWholeNanoseconds
      val callbacks = scheduledCallbacks.toList()
      scheduledCallbacks.clear()
      callbacks.forEach { it.doFrame(currentFrameTimeNanos) }
    }
  }
}
