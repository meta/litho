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
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.lithoTreeScope
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.ms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
class AnimationScopeTest {
  @Rule
  @JvmField
  val lithoRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(enableStateReadTracking = true),
  )
  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private val dispatcher = UnconfinedTestDispatcher()
  private lateinit var animations: AnimationScope
  private lateinit var treeScope: CoroutineScope
  private lateinit var siblingAnimations: AnimationScope
  private lateinit var first: Animatable<Float>
  private lateinit var second: Animatable<Float>
  private lateinit var untouched: Animatable<Float>
  private lateinit var playback: AnimationPlayback
  private lateinit var otherPlayback: AnimationPlayback
  private val linear = tween<Float>(100.ms, Easing.Linear)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun standaloneAwaitHandlesNoOpsTimedEndpointsAndFinish() {
    val view = lithoRule.render { Host() }
    val parent = CoroutineScope(Dispatchers.Main.immediate + Job())
    val steps = mutableListOf<String>()
    val job = parent.launch {
      first.animateTo(0f, snap())
      steps.add("no-op")
      first.animateTo(0f, linear)
      steps.add("timed")
      first.animateTo(2f, linear)
      steps.add("finish")
    }
    assertThat(steps).isEmpty()
    animationRule.runNextFrame()
    assertThat(steps).containsExactly("no-op")
    animationRule.advanceBy(100.ms)
    assertThat(steps).containsExactly("no-op", "timed")
    first.finish()
    assertThat(steps).containsExactly("no-op", "timed")
    animationRule.runNextFrame()
    assertThat(steps).containsExactly("no-op", "timed", "finish")
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(job.isCompleted).isTrue()

    val earlier = animations.launchLatest {
      second.animateTo(1f, linear)
      first.snapTo(5f)
    }
    var returned = false
    val standalone = parent.launch {
      first.animateTo(3f, linear)
      returned = true
    }
    animationRule.advanceBy(100.ms)
    assertThat(earlier.isCompleted).isTrue()
    assertThat(standalone.isCompleted).isTrue()
    assertThat(standalone.isCancelled).isFalse()
    assertThat(returned).isTrue()
    assertThat(first.value.value).isEqualTo(5f)
    view.release()
  }

  @Test
  fun completedClaimsSurviveWaitsAndTakeoverCancelsSiblings() {
    for (operation in listOf("animate", "snap", "stop")) {
      val view = lithoRule.render { Host() }
      val steps = mutableListOf<String>()
      val job = animations.launchLatest {
        first.animateTo(1f, linear)
        coroutineScope {
          launch { second.animateTo(1f, tween(1_000.ms, Easing.Linear)) }
          launch {
            wait(500.ms)
            steps.add("wait")
          }
        }
        steps.add("done")
      }
      animationRule.advanceBy(100.ms)
      assertThat(first.value.value).isEqualTo(1f)
      untouched.jumpTo(3f)
      assertThat(job.isActive).isTrue()
      animationRule.advanceBy(50.ms)
      val siblingValue = second.value.value
      assertThat(siblingValue).isGreaterThan(0f)
      when (operation) {
        "animate" -> first.startAnimation(2f, linear)
        "snap" -> first.jumpTo(2f)
        else -> first.stop()
      }
      assertThat(job.isCancelled).isTrue()
      animationRule.advanceBy(0.ms)
      animationRule.advanceBy(1_000.ms)
      assertThat(second.value.value).isEqualTo(siblingValue)
      assertThat(steps).isEmpty()
      assertThat(first.value.value).isEqualTo(if (operation == "stop") 1f else 2f)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }

    val view = lithoRule.render { Host() }
    val spec = spring<Float>()
    val run = animations.launchLatest { first.animateTo(1f, spec) }
    animationRule.advanceBy(50.ms)
    val velocity = FloatTypeConverter.newVector()
    spec.createAnimator(0f, 1f, FloatTypeConverter).getVelocity(50.ms.inWholeNanoseconds, velocity)
    val expected = spec.createAnimator(first.value.value, 0f, FloatTypeConverter, velocity)
    first.startAnimation(0f, spec)
    assertThat(run.isCancelled).isTrue()
    animationRule.runNextFrame(50.ms)
    assertThat(first.value.value).isEqualTo(expected.getValue(50.ms.inWholeNanoseconds))
    view.release()
  }

  @Test
  fun relaunchPreservesVelocityForImmediateAndQueuedParallelCommands() {
    for (queued in listOf(false, true)) {
      val main = if (queued) StandardTestDispatcher() else dispatcher
      Dispatchers.setMain(main)
      val view = lithoRule.render { Host() }
      val spec = spring<Float>()
      val original = animations.launchLatest {
        launch { first.animateTo(1f, spec) }
        launch { second.animateTo(1f, spec) }
      }
      main.scheduler.runCurrent()
      animationRule.advanceBy(0.ms)
      animationRule.advanceBy(50.ms)
      val initial = first.value.value
      assertThat(original.isActive).describedAs("original active, queued=%s", queued).isTrue()
      assertThat(initial).describedAs("original progress, queued=%s", queued).isGreaterThan(0f)
      val velocity = FloatTypeConverter.newVector()
      spec
          .createAnimator(0f, 1f, FloatTypeConverter)
          .getVelocity(50.ms.inWholeNanoseconds, velocity)
      val expected = spec.createAnimator(initial, 0f, FloatTypeConverter, velocity)
      val replacement = animations.launchLatest {
        launch { first.animateTo(0f, spec) }
        launch { second.animateTo(0f, spec) }
      }
      assertThat(original.isCancelled).isTrue()
      if (queued) {
        animationRule.advanceBy(100.ms)
        assertThat(first.value.value).isEqualTo(initial)
        assertThat(second.value.value).isEqualTo(initial)
        main.scheduler.runCurrent()
        animationRule.advanceBy(0.ms)
      }
      animationRule.runNextFrame(16.ms)
      assertThat(first.value.value).isEqualTo(expected.getValue(16.ms.inWholeNanoseconds))
      assertThat(second.value.value).isEqualTo(first.value.value)
      assertThat(replacement.isActive).isTrue()
      view.release()
    }
  }

  @Test
  fun awaitedCommandsStayOwnedAcrossLoopsAndStructuredChildren() {
    val view = lithoRule.render { Host() }
    var iterations = 0
    val loop = animations.launchLatest {
      repeat(3) {
        first.snapTo(0f)
        first.animateTo(1f, linear)
        ++iterations
      }
    }
    repeat(3) { animationRule.advanceBy(100.ms) }
    assertThat(iterations).isEqualTo(3)
    assertThat(loop.isCompleted).isTrue()
    first.jumpTo(0f)
    var steps = 0
    val parallel = animations.launchLatest {
      launch {
        first.animateTo(1f, linear)
        ++steps
        launch { second.animateTo(3f, linear) }
        first.animateTo(2f, linear)
        ++steps
      }
    }
    assertThat(parallel.isActive).isTrue()
    animationRule.advanceBy(100.ms)
    assertThat(steps).isEqualTo(1)
    assertThat(parallel.isActive).isTrue()
    animationRule.advanceBy(100.ms)
    assertThat(steps).isEqualTo(2)
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(second.value.value).isEqualTo(3f)
    assertThat(parallel.isCompleted).isTrue()

    var lazyChildStarted = false
    val withLazyChild = animations.launchLatest {
      launch(start = CoroutineStart.LAZY) { lazyChildStarted = true }
    }
    assertThat(lazyChildStarted).isFalse()
    assertThat(withLazyChild.isActive).isTrue()
    withLazyChild.cancel()
    assertThat(withLazyChild.isCompleted).isTrue()

    val nonCancellable = animations.launchLatest {
      withContext(NonCancellable) { first.animateTo(6f, tween(1_000.ms)) }
    }
    animationRule.advanceBy(50.ms)
    val beforeCancellation = first.value.value
    nonCancellable.cancel()
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(beforeCancellation)
    assertThat(nonCancellable.isCancelled).isTrue()
    assertThat(nonCancellable.isCompleted).isTrue()
    view.release()
  }

  @Test
  fun sequentialReuseAndRetargetingCancelOnlyTheOlderStep() {
    val view = lithoRule.render { Host() }
    val sequential = animations.launchLatest {
      first.animateTo(1f, linear)
      first.animateTo(2f, linear)
    }
    animationRule.advanceBy(100.ms)
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(sequential.isCompleted).isTrue()
    var older: Job? = null
    var completed = false
    val retargeted = animations.launchLatest {
      older = launch { first.animateTo(3f, tween(1_000.ms)) }
      launch { second.animateTo(1f, linear) }
      wait(50.ms)
      first.animateTo(4f, linear)
      completed = true
    }
    animationRule.advanceBy(50.ms)
    animationRule.advanceBy(100.ms)
    assertThat(checkNotNull(older).isCancelled).isTrue()
    assertThat(first.value.value).isEqualTo(4f)
    assertThat(second.value.value).isEqualTo(1f)
    assertThat(completed).isTrue()
    assertThat(retargeted.isCompleted).isTrue()
    assertThat(retargeted.isCancelled).isFalse()
    view.release()
  }

  @Test
  fun playbackRebindingPreservesPausedCommandsAndZeroScaleFinishesHolds() {
    val view = lithoRule.render { Host(paused = true) }
    var completed = false
    val originalScope = animations
    val job = animations.launchLatest {
      first.animateTo(1f, linear)
      wait(100.ms)
      second.animateTo(2f, linear)
      completed = true
    }
    animationRule.advanceBy(1_000.ms)
    assertThat(first.value.value).isZero()
    playback.play()
    animationRule.advanceBy(50.ms)
    assertThat(first.value.value).isEqualTo(0.5f)
    playback.pause()
    view.setRoot(Host(paused = true, useOtherPlayback = true))
    shadowOf(getMainLooper()).idle()
    assertThat(animations).isSameAs(originalScope)
    playback.play()
    animationRule.advanceBy(1_000.ms)
    assertThat(first.value.value).isEqualTo(0.5f)
    otherPlayback.play()
    animationRule.advanceBy(50.ms)
    assertThat(first.value.value).isEqualTo(1f)
    otherPlayback.pause()
    animationRule.durationScale = 0f
    animationRule.advanceBy(1_000.ms)
    assertThat(job.isActive).isTrue()
    assertThat(completed).isFalse()
    otherPlayback.play()
    animationRule.runNextFrame()
    assertThat(completed).isFalse()
    animationRule.runNextFrame()
    assertThat(second.value.value).isEqualTo(2f)
    assertThat(completed).isTrue()
    assertThat(job.isCompleted).isTrue()
    view.release()
  }

  @Test
  fun waitUsesAnimationTimeWhileExternalDelayKeepsWallTime() {
    val view = lithoRule.render { Host(paused = true) }
    val events = mutableListOf<String>()
    val job = animations.launchLatest {
      coroutineScope {
        launch {
          wait(100.ms)
          events.add("wait")
        }
        launch {
          delay(100)
          events.add("delay")
        }
      }
    }
    dispatcher.scheduler.advanceTimeBy(100)
    dispatcher.scheduler.runCurrent()
    assertThat(events).containsExactly("delay")
    animationRule.advanceBy(1_000.ms)
    assertThat(events).containsExactly("delay")
    playback.play()
    animationRule.durationScale = 2f
    animationRule.advanceBy(100.ms)
    assertThat(events).containsExactly("delay")
    animationRule.advanceBy(100.ms)
    assertThat(events).containsExactly("delay", "wait")
    assertThat(job.isCompleted).isTrue()
    view.release()
  }

  @Test
  fun delayedOldCancellationCannotStopANewerCommand() {
    for (scoped in listOf(false, true)) {
      val queued = StandardTestDispatcher()
      Dispatchers.setMain(queued)
      val view = lithoRule.render { Host() }
      var rejectedCleanup = 0
      val job =
          if (scoped) {
            animations.launchLatest {
              try {
                first.animateTo(1f, tween(1_000.ms))
              } finally {
                withContext(NonCancellable) {
                  try {
                    first.snapTo(6f)
                  } catch (_: CancellationException) {
                    ++rejectedCleanup
                  }
                  try {
                    first.animateTo(6f, linear)
                  } catch (_: CancellationException) {
                    ++rejectedCleanup
                  }
                }
              }
            }
          } else {
            CoroutineScope(Dispatchers.Main.immediate + Job()).launch {
              first.animateTo(1f, tween(1_000.ms))
            }
          }
      queued.scheduler.runCurrent()
      animationRule.advanceBy(50.ms)
      val cancellationThread = Thread { job.cancel() }
      cancellationThread.start()
      cancellationThread.join()
      first.startAnimation(2f, linear)
      queued.scheduler.runCurrent()
      animationRule.advanceBy(100.ms)
      assertThat(first.value.value).isEqualTo(2f)
      assertThat(rejectedCleanup).isEqualTo(if (scoped) 2 else 0)
      assertThat(job.isCancelled).isTrue()
      view.release()
    }
    Dispatchers.setMain(dispatcher)
    val view = lithoRule.render { Host() }
    var childScope: CoroutineScope? = null
    var oldAwait: Job? = null
    val run = animations.launchLatest {
      coroutineScope {
        childScope = this
        oldAwait = launch { first.animateTo(1f, linear) }
        wait(1_000.ms)
      }
    }
    animationRule.advanceBy(50.ms)
    first.finish()
    val newAwait = checkNotNull(childScope).launch { first.animateTo(2f, linear) }
    checkNotNull(oldAwait).cancel()
    animationRule.advanceBy(0.ms)
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(newAwait.isCompleted).isTrue()
    assertThat(run.isActive).isTrue()
    animations.cancel()
    view.release()
  }

  @Test
  fun latestRunAndClaimedValueUnmountCancelTheEntirePreviousRun() {
    val view = lithoRule.render { Host() }
    val old = animations.launchLatest {
      coroutineScope {
        launch { first.animateTo(1f, tween(1_000.ms)) }
        launch { second.animateTo(1f, tween(1_000.ms)) }
      }
    }
    animationRule.advanceBy(50.ms)
    val stoppedSibling = second.value.value
    val replacement = animations.launchLatest {
      first.animateTo(2f, linear)
      wait(1_000.ms)
    }
    assertThat(old.isCancelled).isTrue()
    animationRule.advanceBy(100.ms)
    assertThat(second.value.value).isEqualTo(stoppedSibling)
    assertThat(replacement.isActive).isTrue()
    view.setRoot(Host(showFirst = false))
    shadowOf(getMainLooper()).idle()
    assertThat(replacement.isCancelled).isTrue()
    val host = animations
    view.setRoot(EmptyComponent())
    shadowOf(getMainLooper()).idle()
    assertThat(host.launchLatest {}.isCancelled).isTrue()
    animationRule.advanceBy(1_000.ms)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()

    val nextView = lithoRule.render { Host() }
    val removedInsideRun = animations.launchLatest {
      first.snapTo(1f)
      launch { second.animateTo(2f, tween(1_000.ms)) }
      nextView.setRoot(Host(showFirst = false))
      shadowOf(getMainLooper()).idle()
    }
    assertThat(removedInsideRun.isCancelled).isTrue()
    nextView.release()
  }

  @Test
  fun cancellationReleasesMotionBeforeStructuredCleanupCompletes() {
    for (source in listOf("returned job", "body failure", "child failure", "parent")) {
      val view = lithoRule.render { Host() }
      val failures = mutableListOf<Throwable>()
      val thread = Thread.currentThread()
      val previousHandler = thread.uncaughtExceptionHandler
      try {
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
          failures.add(failure)
        }
        var contextJob: Job? = null
        var cleanedUp = false
        val run = animations.launchLatest {
          contextJob = coroutineContext[Job]
          launch { withContext(NonCancellable) { first.animateTo(1f, tween(1_000.ms)) } }
          launch {
            withContext(NonCancellable) {
              delay(100)
              cleanedUp = true
            }
          }
          if (source == "child failure")
              launch {
                wait(50.ms)
                error("failed run")
              }
          if (source == "body failure") {
            wait(50.ms)
            error("failed run")
          }
        }
        assertThat(run.children.toList()).contains(checkNotNull(contextJob))
        animationRule.advanceBy(50.ms)
        val beforeCancellation = first.value.value
        when (source) {
          "returned job" -> run.cancel()
          "parent" -> checkNotNull(treeScope.coroutineContext[Job]).cancel()
        }
        assertThat(first.isRunning).describedAs(source).isFalse()
        assertThat(run.isCancelled).describedAs(source).isTrue()
        assertThat(run.isCompleted).describedAs(source).isFalse()
        animationRule.advanceBy(200.ms)
        assertThat(first.value.value).isEqualTo(beforeCancellation)
        assertThat(cleanedUp).isFalse()
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()
        assertThat(cleanedUp).isTrue()
        assertThat(run.isCompleted).isTrue()
        if (source == "child failure" || source == "body failure") {
          assertThat(failures.single()).hasMessage("failed run")
        } else {
          assertThat(failures).isEmpty()
        }
      } finally {
        thread.uncaughtExceptionHandler = previousHandler
        view.release()
      }
    }
  }

  @Test
  fun sharedParentPreservesFailureIsolationAndIndependentHosts() {
    val view = lithoRule.render { Host(withSibling = true) }
    val host = animations
    val siblingHost = siblingAnimations
    val failures = mutableListOf<Throwable>()
    val sibling = siblingHost.launchLatest { wait(1_000.ms) }
    val thread = Thread.currentThread()
    val previousHandler = thread.uncaughtExceptionHandler
    val failed =
        try {
          thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
            failures.add(failure)
          }
          host.launchLatest { error("failed run") }
        } finally {
          thread.uncaughtExceptionHandler = previousHandler
        }
    assertThat(failed.isCancelled).isTrue()
    assertThat(failures.single()).hasMessage("failed run")
    assertThat(sibling.isActive).isTrue()

    val previous = host.launchLatest {
      try {
        wait(1_000.ms)
      } finally {
        withContext(NonCancellable) { delay(100) }
      }
    }
    val replacement = host.launchLatest { wait(1_000.ms) }
    assertThat(previous.isCancelled).isTrue()
    assertThat(previous.isCompleted).isFalse()
    view.setRoot(Host(showAnimations = false, withSibling = true))
    shadowOf(getMainLooper()).idle()
    assertThat(replacement.isCancelled).isTrue()
    assertThat(siblingAnimations).isSameAs(siblingHost)
    assertThat(sibling.isActive).isTrue()
    assertThat(host.launchLatest {}.isCancelled).isTrue()
    view.release()
    assertThat(sibling.isCancelled).isTrue()
    dispatcher.scheduler.advanceTimeBy(100)
    dispatcher.scheduler.runCurrent()
    assertThat(previous.isCompleted).isTrue()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun reentrantCancellationPreservesTheNewestRunAndDirectCommand() {
    val view = lithoRule.render { Host() }
    var newest: Job? = null
    val initial = animations.launchLatest {
      try {
        first.animateTo(1f, tween(1_000.ms))
      } finally {
        newest = animations.launchLatest { second.animateTo(2f, linear) }
        first.snapTo(3f)
      }
    }
    animationRule.advanceBy(50.ms)
    val valueBeforeRelaunch = first.value.value
    val superseded = animations.launchLatest { first.animateTo(4f, linear) }
    assertThat(initial.isCancelled).isTrue()
    assertThat(superseded.isCancelled).isTrue()
    animationRule.advanceBy(0.ms)
    animationRule.advanceBy(100.ms)
    assertThat(checkNotNull(newest).isCompleted).isTrue()
    assertThat(first.value.value).isEqualTo(valueBeforeRelaunch)
    assertThat(second.value.value).isEqualTo(2f)

    val directTakeover = animations.launchLatest {
      try {
        first.animateTo(5f, tween(1_000.ms))
      } finally {
        first.snapTo(6f)
      }
    }
    animationRule.advanceBy(50.ms)
    first.startAnimation(7f, linear)
    assertThat(directTakeover.isCancelled).isTrue()
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(7f)

    var resumedCleanup = false
    var cancellation: CancellationException? = null
    val cancelled = animations.launchLatest {
      try {
        first.animateTo(8f, tween(1_000.ms))
      } finally {
        withContext(NonCancellable) {
          try {
            wait(1.ms)
            resumedCleanup = true
          } catch (exception: CancellationException) {
            cancellation = exception
          }
        }
      }
    }
    animationRule.advanceBy(50.ms)
    cancelled.cancel()
    assertThat(cancelled.isCompleted).isTrue()
    assertThat(cancellation).isNotNull()
    assertThat(resumedCleanup).isFalse()

    val host = animations
    val unmounted = host.launchLatest {
      try {
        first.animateTo(9f, linear)
      } finally {
        view.setRoot(Host(showAnimations = false))
        shadowOf(getMainLooper()).idle()
      }
    }
    var replacementStarted = false
    val abandoned = host.launchLatest { replacementStarted = true }
    assertThat(unmounted.isCancelled).isTrue()
    assertThat(abandoned.isCancelled).isTrue()
    assertThat(replacementStarted).isFalse()
    view.release()
  }

  @Test
  fun animatorValidationIsAtomicAcrossFailuresAndReentrantReplacement() {
    val view = lithoRule.render { Host() }
    val original = animations.launchLatest { first.animateTo(1f, tween(1_000.ms)) }
    animationRule.advanceBy(50.ms)
    val before = first.value.value
    val invalid =
        object : FiniteAnimationSpec<Float> {
          override fun createAnimator(
              initialValue: Float,
              targetValue: Float,
              typeConverter: TypeConverter<Float>,
              initialVelocity: AnimationVector?,
          ): Animator<Float> = error("invalid spec")
        }
    assertThatThrownBy { first.startAnimation(7f, invalid) }.hasMessage("invalid spec")
    assertThat(original.isActive).isTrue()
    assertThat(first.value.value).isEqualTo(before)

    var replacement: Job? = null
    val reentrant =
        object : FiniteAnimationSpec<Float> {
          override fun createAnimator(
              initialValue: Float,
              targetValue: Float,
              typeConverter: TypeConverter<Float>,
              initialVelocity: AnimationVector?,
          ): Animator<Float> {
            replacement = animations.launchLatest { first.animateTo(4f, linear) }
            return linear.createAnimator(initialValue, targetValue, typeConverter, initialVelocity)
          }
        }
    first.startAnimation(7f, reentrant)
    assertThat(original.isCancelled).isTrue()
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(4f)
    assertThat(checkNotNull(replacement).isCompleted).isTrue()
    view.release()
  }

  @Test
  fun immediateContinuationsAdvanceTheNextLegOnTheNextFrame() {
    val view = lithoRule.render { Host() }
    val job = animations.launchLatest {
      first.animateTo(1f, linear)
      first.animateTo(2f, linear)
    }
    animationRule.advanceBy(100.ms)
    assertThat(first.value.value).isEqualTo(1f)
    animationRule.runNextFrame(16.ms)
    assertThat(first.value.value).isEqualTo(1.16f)
    animationRule.runNextFrame(84.ms)
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(job.isCompleted).isTrue()
    first.jumpTo(0f)
    val skipped = animations.launchLatest {
      first.animateTo(1f, linear)
      first.animateTo(2f, linear)
    }
    second.startAnimation(
        2f,
        chain<Float> {
          animateTo(1f, linear)
          animateToTarget(linear)
        },
    )
    animationRule.advanceBy(250.ms)
    assertThat(first.value.value).isEqualTo(1f)
    assertThat(second.value.value).isEqualTo(2f)
    assertThat(skipped.isActive).isTrue()
    animationRule.runNextFrame(100.ms)
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(skipped.isCompleted).isTrue()

    val queued = StandardTestDispatcher()
    Dispatchers.setMain(queued)
    first.jumpTo(0f)
    val dispatched = animations.launchLatest {
      first.animateTo(1f, linear)
      first.animateTo(2f, linear)
    }
    queued.scheduler.runCurrent()
    animationRule.advanceBy(100.ms)
    queued.scheduler.runCurrent()
    animationRule.runNextFrame(16.ms)
    assertThat(first.value.value).isEqualTo(1f)
    animationRule.runNextFrame(100.ms)
    queued.scheduler.runCurrent()
    assertThat(first.value.value).isEqualTo(2f)
    assertThat(dispatched.isCompleted).isTrue()
    view.release()
  }

  @Test
  fun cancellingChildrenPreservesTheBodyAndReturnedJobInterop() {
    val view = lithoRule.render { Host() }
    val continued = animations.launchLatest {
      launch { first.animateTo(1f, tween(1_000.ms)) }
      wait(50.ms)
      coroutineContext.cancelChildren()
      second.animateTo(1f, linear)
    }
    animationRule.advanceBy(50.ms)
    assertThat(first.isRunning).isFalse()
    animationRule.advanceBy(100.ms)
    assertThat(second.value.value).isEqualTo(1f)
    assertThat(continued.isCompleted).isTrue()
    assertThat(continued.isCancelled).isFalse()

    var cleanedUp = false
    val cancelledBody = animations.launchLatest {
      launch { first.animateTo(1f, tween(1_000.ms)) }
      launch {
        withContext(NonCancellable) {
          delay(100)
          cleanedUp = true
        }
      }
    }
    animationRule.advanceBy(50.ms)
    val before = first.value.value
    cancelledBody.cancelChildren()
    assertThat(first.isRunning).isFalse()
    assertThat(cancelledBody.isCompleted).isFalse()
    animationRule.advanceBy(200.ms)
    assertThat(first.value.value).isEqualTo(before)
    dispatcher.scheduler.advanceTimeBy(100)
    dispatcher.scheduler.runCurrent()
    assertThat(cleanedUp).isTrue()
    assertThat(cancelledBody.isCompleted).isTrue()
    assertThat(cancelledBody.isCancelled).isFalse()
    view.release()
  }

  @Test
  fun synchronousControlsRemainExternalInRunCodeAndStaleCleanup() {
    val view = lithoRule.render { Host() }
    val stoppedByJump = animations.launchLatest {
      first.animateTo(1f, linear)
      first.jumpTo(2f)
    }
    animationRule.advanceBy(100.ms)
    assertThat(stoppedByJump.isCancelled).isTrue()
    assertThat(first.value.value).isEqualTo(2f)

    val previous = animations.launchLatest {
      try {
        first.animateTo(3f, tween(1_000.ms))
      } finally {
        withContext(NonCancellable) {
          delay(100)
          first.jumpTo(7f)
        }
      }
    }
    animationRule.advanceBy(50.ms)
    val replacement = animations.launchLatest { first.animateTo(4f, tween(1_000.ms)) }
    assertThat(previous.isCancelled).isTrue()
    assertThat(previous.isCompleted).isFalse()
    assertThat(replacement.isActive).isTrue()
    dispatcher.scheduler.advanceTimeBy(100)
    dispatcher.scheduler.runCurrent()
    assertThat(previous.isCompleted).isTrue()
    assertThat(replacement.isCancelled).isTrue()
    assertThat(first.value.value).isEqualTo(7f)
    view.release()
  }

  private inner class Host(
      private val paused: Boolean = false,
      private val useOtherPlayback: Boolean = false,
      private val showFirst: Boolean = true,
      private val showAnimations: Boolean = true,
      private val withSibling: Boolean = false,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      treeScope = checkNotNull(context.lithoTree).lithoTreeScope
      playback = useAnimationPlayback(autoPlay = !paused)
      otherPlayback = useAnimationPlayback(autoPlay = false)
      if (showAnimations) {
        animations = useAnimationScope(if (useOtherPlayback) otherPlayback else playback)
      }
      if (withSibling) siblingAnimations = useAnimationScope()
      if (showFirst) first = useAnimatable(0f, FloatTypeConverter)
      second = useAnimatable(0f, FloatTypeConverter)
      untouched = useAnimatable(0f, FloatTypeConverter)
      return EmptyComponent()
    }
  }
}
