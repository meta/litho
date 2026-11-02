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

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.rotation
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import kotlin.math.roundToInt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class SystemDurationScaleTest {
  @get:Rule val lithoTestRule = LithoTestRule()

  @get:Rule val animationRule = LithoAnimationTestRule { lithoTestRule.idle() }

  private val linear = tween<Float>(1_000.ms, Easing.Linear)
  private lateinit var group: AnimationGroup<Float>
  private lateinit var single: Value<Float>
  private lateinit var loop: Value<Float>
  private lateinit var command: Animatable<Float>

  @Test
  fun positiveScalesReachEveryFramePath() {
    for (scale in listOf(0.5f, 1f, 2f, 3f)) {
      animationRule.durationScale = scale
      val view = lithoTestRule.render { Mixed(0f) }
      lithoTestRule.idle()
      view.setRoot(Mixed(1f))
      lithoTestRule.idle()
      command.startAnimation(1f, linear)
      animationRule.advanceBy(200.ms)
      view.measure().layout()
      val expected = 0.2f / scale
      assertThat(single.value).isCloseTo(expected, offset(0.0001f))
      assertThat(group.state.animatedValues.first().currentValueRaw).isEqualTo(single.value)
      assertThat(command.value.value).isEqualTo(single.value)
      assertThat(loop.value).isCloseTo(expected, offset(0.0001f))
      assertThat(view.findViewWithTag("style").rotation).isCloseTo(expected * 100f, offset(0.01f))
      assertThat(view.findViewWithTag("reflow").height)
          .isEqualTo((10 + expected * 100).roundToInt())
      view.release()
    }
  }

  @Test
  fun positiveChangesPreserveProgressAndPausedTimeIsExcluded() {
    val playback = AnimationPlayback(true)
    animationRule.durationScale = 0.5f
    val view = lithoTestRule.render { Mixed(0f, playback) }
    lithoTestRule.idle()
    view.setRoot(Mixed(1f, playback))
    animationRule.advanceBy(200.ms)
    assertThat(single.value).isCloseTo(0.4f, offset(0.0001f))
    animationRule.durationScale = 2f
    animationRule.runNextFrame(200.ms)
    assertThat(single.value).isCloseTo(0.5f, offset(0.0001f))
    assertThat(loop.value).isCloseTo(0.5f, offset(0.0001f))
    animationRule.durationScale = 1f
    animationRule.runNextFrame(100.ms)
    assertThat(single.value).isCloseTo(0.6f, offset(0.0001f))
    assertThat(loop.value).isCloseTo(0.6f, offset(0.0001f))
    playback.pause()
    animationRule.advanceBy(5_000.ms)
    animationRule.durationScale = 0.5f
    playback.play()
    animationRule.runNextFrame(1_000.ms)
    assertThat(single.value).isCloseTo(0.6f, offset(0.0001f))
    animationRule.runNextFrame(100.ms)
    assertThat(single.value).isCloseTo(0.8f, offset(0.0001f))
    view.release()
  }

  @Test
  fun zeroFinishesEligibleFiniteWorkAndPreservesExplicitPause() {
    val playback = AnimationPlayback(false)
    val settled = mutableListOf<Float>()
    val ends = mutableListOf<AnimationEnd>()
    val view = lithoTestRule.render { Mixed(0f, playback) }
    lithoTestRule.idle()
    animationRule.durationScale = 0f
    view.setRoot(Mixed(1f, playback, onSettled = { settled.add(it) }))
    lithoTestRule.idle()
    val specs = listOf(
        tween<Float>(1_000.ms, delay = 500.ms),
        chain<Float> {
              animateTo(2f, tween(300.ms))
              wait(500.ms)
              animateToTarget(spring())
            }
            .delayed(200.ms),
    )
    for (spec in specs) {
      ends.clear()
      command.startAnimation(1f, spec) { ends.add(it) }
      assertThat(ends).isEmpty()
      animationRule.runNextFrame()
      view.measure().layout()
      assertThat(command.value.value).isEqualTo(1f)
      assertThat(ends).containsExactly(AnimationEnd.Finished)
    }
    assertThat(single.value).isEqualTo(0f)
    assertThat(settled).isEmpty()
    assertThat(view.findViewWithTag("style").rotation).isEqualTo(100f)
    assertThat(view.findViewWithTag("reflow").height).isEqualTo(110)
    playback.play()
    animationRule.runNextFrame()
    assertThat(single.value).isEqualTo(1f)
    assertThat(group.state.animatedValues.map { it.currentValueRaw }).containsExactly(1f, 0f, 1f)
    assertThat(settled).containsExactly(1f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()
  }

  @Test
  fun disabledLoopsStayStaticHandleNewConfigurationsAndRestartWhenEnabled() {
    val playback = AnimationPlayback(true)
    val view = lithoTestRule.render { Mixed(0f, playback) }
    animationRule.advanceBy(200.ms)
    animationRule.durationScale = 0f
    animationRule.runNextFrame()
    assertThat(loop.value).isEqualTo(0f)
    assertThat(playback.isRunning).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.setRoot(Mixed(0f, playback))
    lithoTestRule.idle()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.setRoot(Mixed(0f, playback, loopStart = 2f, loopEnd = 4f))
    lithoTestRule.idle()
    assertThat(animationRule.hasScheduledFrames()).isTrue()
    animationRule.runNextFrame()
    assertThat(loop.value).isEqualTo(2f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    animationRule.durationScale = 2f
    animationRule.runNextFrame(10_000.ms)
    assertThat(loop.value).isEqualTo(2f)
    animationRule.runNextFrame(200.ms)
    assertThat(loop.value).isCloseTo(2.2f, offset(0.0001f))
    view.release()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun disabledContainersReleaseOutgoingContentAndDeliverCompletion() {
    val settled = mutableListOf<String>()
    fun containers(target: Int): Component = Column {
      child(
          AnimatedContent(
              targetState = target,
              transitionSpec = {
                enter = fadeIn(linear)
                exit = fadeOut(linear)
              },
              onSettled = { settled.add("content:$it") },
          ) { value ->
            Row(style = Style.width(10.px).height(10.px).viewTag("content:$value"))
          },
      )
      child(
          AnimatedVisibility(
              visible = target == 0,
              enter = fadeIn(linear),
              exit = fadeOut(linear),
              onSettled = { settled.add("visible:$it") },
          ) {
            Row(style = Style.width(10.px).height(10.px).viewTag("visibility"))
          },
      )
    }
    val view = lithoTestRule.render { containers(0) }
    lithoTestRule.idle()
    animationRule.durationScale = 0f
    view.setRoot(containers(1))
    lithoTestRule.idle()
    repeat(3) {
      animationRule.runNextFrame()
      view.measure().layout()
      lithoTestRule.idle()
    }
    assertThat(view.findViewWithTagOrNull("content:0")).isNull()
    assertThat(view.findViewWithTagOrNull("visibility")).isNull()
    assertThat(view.findViewWithTagOrNull("content:1")).isNotNull()
    assertThat(settled).containsExactlyInAnyOrder("content:1", "visible:false")
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()
  }

  @Test
  fun disabledCompletionCanQueueAnotherFiniteRun() {
    val settled = mutableListOf<Float>()
    val view = lithoTestRule.render { Mixed(0f, withLoop = false) }
    lithoTestRule.idle()
    animationRule.durationScale = 0f
    view.setRoot(
        Mixed(
            1f,
            withLoop = false,
            onSettled = { state ->
              settled.add(state)
              view.setRoot(Mixed(2f, withLoop = false, onSettled = { settled.add(it) }))
              lithoTestRule.idle()
            },
        ),
    )
    lithoTestRule.idle()
    animationRule.runNextFrame()
    assertThat(settled).containsExactly(1f)
    assertThat(animationRule.hasScheduledFrames()).isTrue()
    animationRule.runNextFrame()
    assertThat(settled).containsExactly(1f, 2f)
    assertThat(single.value).isEqualTo(2f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()
  }

  private inner class Mixed(
      private val target: Float,
      private val playback: AnimationPlayback? = null,
      private val loopStart: Float = 0f,
      private val loopEnd: Float = 1f,
      private val withLoop: Boolean = true,
      private val onSettled: ((Float) -> Unit)? = null,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val transition = useAnimationGroup(target, playback = playback, onSettled = onSettled)
      group = transition
      val value = transition.animateFloat(transitionSpec = { linear }) { it }
      transition.animateFloat(transitionSpec = { repeatable(2, linear, RepeatMode.Reverse) }) { it }
      transition.animateFloat(transitionSpec = { spring() }) { it }
      single = useAnimatedFloat(target, linear, playback = playback)
      command = useAnimatable(0f, FloatTypeConverter)
      if (withLoop) {
        loop =
            useInfiniteAnimationGroup(playback = playback).animateFloat(loopStart, loopEnd, linear)
      }
      return Column(
          style = Style.translationX(single).rotation(value).translationY(command.value),
      ) {
        child(
            Row(
                style =
                    Style.width(10.px)
                        .height(10.px)
                        .rotation(target * 100f)
                        .transition { rotation = repeatable(2, linear, RepeatMode.Reverse) }
                        .viewTag("style"),
            ),
        )
        child(
            Column {
              child(
                  Row(
                      style =
                          Style.width(10.px)
                              .height((10 + target * 100).px)
                              .transition { bounds(linear, reflow = true) }
                              .viewTag("reflow"),
                  ),
              )
              child(Row(style = Style.width(10.px).height(10.px).viewTag("sibling")))
            },
        )
      }
    }
  }
}
