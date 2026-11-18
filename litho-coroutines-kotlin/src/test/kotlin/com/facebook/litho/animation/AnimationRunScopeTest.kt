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

import android.os.Looper.getMainLooper
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.ms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class, ExperimentalCoroutinesApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimationRunScopeTest {
  @Rule @JvmField val lithoRule = LithoTestRule()
  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private lateinit var animations: AnimationScope
  private lateinit var playback: AnimationPlayback
  private lateinit var otherPlayback: AnimationPlayback
  private lateinit var first: Animatable<Float>
  private lateinit var second: Animatable<Float>
  private val linear = tween<Float>(100.ms, Easing.Linear)

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun motionQueryIsLiveAndIndependentOfPlaybackAndCancellation() {
    val view = lithoRule.render { Host(paused = true) }
    lateinit var run: AnimationRunScope
    val job = animations.launchLatest {
      run = this
      assertThat(coroutineContext).isSameAs(currentCoroutineContext())
      wait(1_000.ms)
    }
    assertThat(run.isMotionEnabled).isTrue()
    animationRule.durationScale = 0f
    assertThat(run.isMotionEnabled).isFalse()
    assertThat(job.isActive).isTrue()
    animationRule.durationScale = 0.5f
    assertThat(run.isMotionEnabled).isTrue()
    job.cancel()
    assertThat(run.isMotionEnabled).isTrue()
    view.release()
  }

  @Test
  fun disabledLoopWaitsWhileFiniteSiblingFinishesAndWakesWhenEnabled() {
    val view = lithoRule.render { Host() }
    animationRule.durationScale = 0f
    var iterations = 0
    var settled = false
    val job = animations.launchLatest {
      launch {
        while (isActive) {
          awaitMotion()
          ++iterations
          first.animateTo(1f, linear)
          first.animateTo(0f, linear)
        }
      }
      second.animateTo(2f, linear)
      settled = true
    }
    assertThat(iterations).isZero()
    assertThat(second.value.value).isZero()
    animationRule.runNextFrame()
    assertThat(second.value.value).isEqualTo(2f)
    assertThat(settled).isTrue()
    assertThat(first.value.value).isZero()
    assertThat(iterations).isZero()
    assertThat(job.isActive).isTrue()
    assertThat(animationRule.hasScheduledFrames()).isFalse()

    animationRule.durationScale = 1f
    assertThat(animationRule.hasScheduledFrames()).isTrue()
    assertThat(iterations).isZero()
    animationRule.runNextFrame()
    assertThat(iterations).isEqualTo(1)
    assertThat(first.value.value).isZero()
    animationRule.advanceBy(50.ms)
    assertThat(first.value.value).isEqualTo(0.5f)
    view.release()
  }

  @Test
  fun disablingDuringIterationSettlesItsStepsThenSuspendsRepetition() {
    val view = lithoRule.render { Host() }
    var iterations = 0
    val job = animations.launchLatest {
      while (isActive) {
        awaitMotion()
        ++iterations
        first.animateTo(1f, linear)
        wait(20.ms)
        first.animateTo(0f, linear)
      }
    }
    assertThat(iterations).isEqualTo(1)
    assertThat(first.value.value).isZero()
    animationRule.advanceBy(50.ms)
    assertThat(first.value.value).isEqualTo(0.5f)
    animationRule.durationScale = 0f
    animationRule.runNextFrame()
    assertThat(first.value.value).isEqualTo(1f)
    animationRule.runNextFrame()
    animationRule.runNextFrame()
    assertThat(first.value.value).isZero()
    animationRule.runNextFrame()
    assertThat(first.value.value).isZero()
    assertThat(iterations).isEqualTo(1)
    assertThat(job.isActive).isTrue()
    assertThat(animationRule.hasScheduledFrames()).isFalse()

    animationRule.durationScale = 2f
    animationRule.runNextFrame()
    assertThat(iterations).isEqualTo(2)
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(0.5f)
    view.release()
  }

  @Test
  fun animatedIterationsStartImmediatelyWithoutAnExtraFrameBetweenCycles() {
    val view = lithoRule.render { Host() }
    var iterations = 0
    val job = animations.launchLatest {
      while (isActive) {
        awaitMotion()
        ++iterations
        first.animateTo(iterations.toFloat(), linear)
      }
    }
    assertThat(iterations).isEqualTo(1)
    assertThat(first.value.value).isZero()
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(1f)
    assertThat(iterations).isEqualTo(2)
    animationRule.advanceBy(50.ms)
    assertThat(first.value.value).isEqualTo(1.5f)
    assertThat(job.isActive).isTrue()
    view.release()
  }

  @Test
  fun availableMotionAndImmediateCommandsCompleteWithoutYielding() {
    val view = lithoRule.render { Host() }
    val job = animations.launchLatest {
      awaitMotion()
      first.snapTo(2f)
      wait(0.ms)
      awaitMotion()
      second.snapTo(5f)
    }
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(second.value.value).isEqualTo(5f)
    assertThat(job.isCompleted).isTrue()
    assertThat(job.isCancelled).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()
  }

  @Test
  fun existingCoroutineScopeBlocksSupportFiniteRepeatsWithMotionDisabled() {
    val view = lithoRule.render { Host() }
    animationRule.durationScale = 0f
    var iterations = 0
    val block: suspend CoroutineScope.() -> Unit = {
      repeat(3) {
        val target = (++iterations).toFloat()
        coroutineScope {
          launch { first.animateTo(target, linear) }
          launch { second.animateTo(target, linear) }
        }
      }
    }
    val job = animations.launchLatest(block)
    assertThat(first.value.value).isZero()
    assertThat(iterations).isEqualTo(1)
    repeat(3) { animationRule.runNextFrame() }
    assertThat(iterations).isEqualTo(3)
    assertThat(first.value.value).isEqualTo(3f)
    assertThat(second.value.value).isEqualTo(3f)
    assertThat(job.isCompleted).isTrue()
    assertThat(job.isCancelled).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()
  }

  @Test
  fun motionWaitFollowsPlaybackRebindingAndSystemChanges() {
    val view = lithoRule.render { Host(paused = true) }
    var continued = false
    val job = animations.launchLatest {
      awaitMotion()
      continued = true
    }
    assertThat(continued).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    animationRule.durationScale = 0f
    animationRule.advanceBy(100.ms)
    animationRule.durationScale = 1f
    assertThat(continued).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.setRoot(Host(useOtherPlayback = true))
    shadowOf(getMainLooper()).idle()
    playback.play()
    animationRule.advanceBy(100.ms)
    assertThat(continued).isFalse()
    otherPlayback.play()
    animationRule.runNextFrame()
    assertThat(continued).isTrue()
    assertThat(job.isCompleted).isTrue()
    view.release()
  }

  @Test
  fun endedRunsDoNotResumeMotionWaits() {
    for (cause in listOf("replacement", "takeover", "unmount")) {
      val view = lithoRule.render { Host() }
      animationRule.durationScale = 0f
      var continued = false
      val job = animations.launchLatest {
        first.snapTo(1f)
        awaitMotion()
        continued = true
      }
      animationRule.runNextFrame()
      var replacement: Job? = null
      when (cause) {
        "replacement" -> replacement = animations.launchLatest { second.animateTo(2f, linear) }
        "takeover" -> first.jumpTo(5f)
        else -> view.release()
      }
      animationRule.runNextFrame()
      animationRule.durationScale = 1f
      assertThat(job.isCancelled).describedAs(cause).isTrue()
      assertThat(job.isCompleted).describedAs(cause).isTrue()
      assertThat(continued).describedAs(cause).isFalse()
      assertThat(animationRule.hasScheduledFrames()).describedAs(cause).isFalse()
      if (replacement != null) {
        assertThat(replacement.isCompleted).isTrue()
        assertThat(second.value.value).isEqualTo(2f)
      }
      if (cause == "takeover") assertThat(first.value.value).isEqualTo(5f)
      if (cause != "unmount") view.release()
    }
  }

  @Test
  fun runCancellationReleasesNonCancellableWaits() {
    for (command in listOf("motion", "wait")) {
      val motionWait = command == "motion"
      val view = lithoRule.render { Host(paused = !motionWait) }
      animationRule.durationScale = 0f
      var continued = false
      val job = animations.launchLatest {
        withContext(NonCancellable) {
          if (motionWait) awaitMotion() else wait(1_000.ms)
          continued = true
        }
      }
      if (motionWait) animationRule.runNextFrame()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      job.cancel()
      assertThat(job.isCompleted).describedAs(command).isFalse()
      animationRule.runNextFrame()
      assertThat(job.isCancelled).describedAs(command).isTrue()
      assertThat(job.isCompleted).describedAs(command).isTrue()
      assertThat(continued).describedAs(command).isFalse()
      assertThat(animationRule.hasScheduledFrames()).describedAs(command).isFalse()
      view.release()
    }
  }

  @Test
  fun queuedResumptionRechecksMotionAndPlayback() {
    val queued = StandardTestDispatcher()
    Dispatchers.setMain(queued)
    val view = lithoRule.render { Host() }
    animationRule.durationScale = 0f
    var continued = false
    animations.launchLatest {
      awaitMotion()
      continued = true
    }
    queued.scheduler.runCurrent()
    animationRule.runNextFrame()
    animationRule.durationScale = 1f
    animationRule.runNextFrame()
    animationRule.durationScale = 0f
    queued.scheduler.runCurrent()
    assertThat(continued).isFalse()
    animationRule.runNextFrame()
    assertThat(animationRule.hasScheduledFrames()).isFalse()

    animationRule.durationScale = 1f
    animationRule.runNextFrame()
    playback.pause()
    queued.scheduler.runCurrent()
    assertThat(continued).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    playback.play()
    animationRule.runNextFrame()
    queued.scheduler.runCurrent()
    assertThat(continued).isTrue()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()
    queued.scheduler.runCurrent()
  }

  @Test
  fun cancelledChildrenCannotUseTheOuterRunReceiver() {
    for (command in listOf("motion", "wait")) {
      val view = lithoRule.render { Host() }
      var rejected = false
      var continued = false
      val job = animations.launchLatest {
        launch {
          currentCoroutineContext().cancel()
          try {
            if (command == "motion") awaitMotion() else wait(0.ms)
            continued = true
          } catch (_: CancellationException) {
            rejected = true
          }
        }
        second.animateTo(2f, linear)
      }
      assertThat(rejected).isTrue()
      assertThat(continued).isFalse()
      assertThat(job.isActive).isTrue()
      animationRule.advanceBy(100.ms)
      assertThat(second.value.value).isEqualTo(2f)
      assertThat(job.isCompleted).isTrue()
      assertThat(job.isCancelled).isFalse()
      view.release()
    }
  }

  @Test
  fun cancellingAChildWaitPreservesSiblingWork() {
    for (command in listOf("motion", "wait")) {
      val view = lithoRule.render { Host(paused = true) }
      lateinit var child: Job
      val job = animations.launchLatest {
        child = launch {
          if (command == "motion") awaitMotion() else wait(1_000.ms)
        }
        launch { second.animateTo(2f, linear) }
      }
      shadowOf(getMainLooper()).idle()
      child.cancel()
      assertThat(child.isCompleted).isTrue()
      assertThat(job.isActive).isTrue()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      playback.play()
      animationRule.advanceBy(100.ms)
      assertThat(second.value.value).isEqualTo(2f)
      assertThat(job.isCompleted).isTrue()
      assertThat(job.isCancelled).isFalse()
      view.release()
    }
  }

  @Test
  fun capturedRunCommandsRejectUnrelatedCoroutines() {
    val view = lithoRule.render { Host(paused = true) }
    lateinit var scope: AnimationRunScope
    val run = animations.launchLatest {
      scope = this
      wait(1_000.ms)
    }
    var rejected = 0
    val parent = CoroutineScope(Dispatchers.Main.immediate + Job())
    val outside = parent.launch {
      for (command in listOf("motion", "wait")) {
        try {
          if (command == "motion") scope.awaitMotion() else scope.wait(0.ms)
        } catch (_: IllegalStateException) {
          ++rejected
        }
      }
    }
    assertThat(rejected).isEqualTo(2)
    assertThat(outside.isCompleted).isTrue()
    assertThat(run.isActive).isTrue()
    parent.coroutineContext.cancel()
    view.release()
  }

  private inner class Host(
      private val paused: Boolean = false,
      private val useOtherPlayback: Boolean = false,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      playback = useAnimationPlayback(autoPlay = !paused)
      otherPlayback = useAnimationPlayback(autoPlay = false)
      animations = useAnimationScope(if (useOtherPlayback) otherPlayback else playback)
      first = useAnimatable(0f, FloatTypeConverter)
      second = useAnimatable(0f, FloatTypeConverter)
      return EmptyComponent()
    }
  }
}
