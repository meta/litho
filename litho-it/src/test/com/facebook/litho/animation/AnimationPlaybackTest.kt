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
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimationPlaybackTest {
  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { lithoTestRule.idle() }

  private val linear = tween<Float>(1_000.ms, Easing.Linear)
  private lateinit var group: AnimationGroup<Float>
  private lateinit var single: AnimatedValue<Float>
  private lateinit var loop: Value<Float>

  @Test
  fun mixedOwnersPauseRetargetAndResumeTogether() {
    val playback = AnimationPlayback(false)
    val notifications = mutableListOf<Float>()
    val view = lithoTestRule.render { Mixed(0f, playback) }
    lithoTestRule.idle()
    view.setRoot(Mixed(1f, playback, onSettled = { notifications.add(it) }))
    lithoTestRule.idle()
    assertThat(animationRule.hasScheduledFrames()).isFalse()

    playback.play()
    animationRule.advanceBy(200.ms)
    val values = group.state.animatedValues
    assertThat(values.map { it.currentValueRaw }).containsExactly(0.2f, 1f)
    assertThat(single.currentValueRaw).isEqualTo(0.2f)
    val frozenLoop = loop.value
    val frozenTime = group.state.clock.playTimeNanos
    playback.pause()
    assertThat(playback.isRunning).isFalse()
    assertThat(group.state.isRunning).isFalse()
    assertThat(single.isRunning).isFalse()
    animationRule.advanceBy(5_000.ms)
    assertThat(group.state.clock.playTimeNanos).isEqualTo(frozenTime)
    assertThat(loop.value).isEqualTo(frozenLoop)

    view.setRoot(Mixed(2f, playback, onSettled = { notifications.add(it) }))
    lithoTestRule.idle()
    assertThat(values.map { it.currentValueRaw }).containsExactly(0.2f, 1f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    playback.play()
    animationRule.runNextFrame(1_000.ms)
    assertThat(values.map { it.currentValueRaw }).containsExactly(0.2f, 1f)
    assertThat(single.currentValueRaw).isEqualTo(0.2f)
    assertThat(loop.value).isEqualTo(frozenLoop)
    animationRule.runNextFrame(1_000.ms)
    assertThat(values.map { it.currentValueRaw }).containsExactly(2f, 2f)
    assertThat(single.currentValueRaw).isEqualTo(2f)
    assertThat(notifications).containsExactly(2f)

    view.setRoot(Mixed(2f, playback, withLoop = false))
    lithoTestRule.idle()
    assertThat(playback.state).isEqualTo(PlaybackState.Playing)
    assertThat(playback.isRunning).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.release()
    playback.pause()
    playback.play()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun rebindingPreservesProgressAndUsesTheCurrentControllerAndListener() {
    val first = AnimationPlayback(true)
    val second = AnimationPlayback(false)
    val notifications = mutableListOf<String>()
    val view = lithoTestRule.render { Mixed(0f, first, withLoop = false) }
    lithoTestRule.idle()
    view.setRoot(Mixed(1f, first, withLoop = false, onSettled = { notifications.add("old") }))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    view.setRoot(Mixed(1f, second, withLoop = false, onSettled = { notifications.add("latest") }))
    lithoTestRule.idle()
    assertThat(single.currentValueRaw).isEqualTo(0.2f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    first.pause()
    first.play()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    second.play()
    animationRule.runNextFrame(2_000.ms)
    assertThat(single.currentValueRaw).isEqualTo(0.2f)
    animationRule.runNextFrame(800.ms)
    assertThat(notifications).containsExactly("latest")
    assertThat(second.state).isEqualTo(PlaybackState.Playing)
    assertThat(second.isRunning).isFalse()
  }

  @Test
  fun deferredPlayUsesLatestTargetsAndKeepsLaterRunsPlaying() {
    val playback = AnimationPlayback(false)
    val notifications = mutableListOf<Float>()
    val callback: (Float) -> Unit = { notifications.add(it) }
    val view = lithoTestRule.render { Mixed(0f, playback, withLoop = false) }
    lithoTestRule.idle()
    for (target in listOf(1f, 2f)) {
      view.setRoot(Mixed(target, playback, withLoop = false, onSettled = callback))
      lithoTestRule.idle()
      assertThat(single.currentValueRaw).isEqualTo(0f)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
    }
    playback.play()
    playback.play()
    animationRule.advanceBy(1_000.ms)
    assertThat(single.currentValueRaw).isEqualTo(2f)
    assertThat(group.state.currentState).isEqualTo(2f)
    assertThat(notifications).containsExactly(2f)
    view.setRoot(Mixed(3f, playback, withLoop = false, onSettled = callback))
    lithoTestRule.idle()
    animationRule.advanceBy(1_000.ms)
    assertThat(single.currentValueRaw).isEqualTo(3f)
    assertThat(notifications).containsExactly(2f, 3f)
  }

  @Test
  fun pausedRetargetPreservesSpringVelocityAtTheFrozenPlayTime() {
    val playback = AnimationPlayback(true)
    val velocities = mutableListOf<Float?>()
    val spring = spring<Float>(stiffness = Stiffness.Low)
    val spec =
        object : FiniteAnimationSpec<Float> {
          override fun createAnimator(
              initialValue: Float,
              targetValue: Float,
              typeConverter: TypeConverter<Float>,
              initialVelocity: AnimationVector?,
          ): Animator<Float> {
            velocities.add(initialVelocity?.get(0))
            return spring.createAnimator(initialValue, targetValue, typeConverter, initialVelocity)
          }
        }
    fun component(target: Float): Component =
        object : KComponent() {
          override fun ComponentScope.render(): Component {
            single = useAnimatedFloat(target, spec, playback = playback) as AnimatedValue<Float>
            return Row(style = Style.width(10.px).height(10.px).alpha(single))
          }
        }
    val view = lithoTestRule.render { component(0f) }
    lithoTestRule.idle()
    view.setRoot(component(1f))
    lithoTestRule.idle()
    animationRule.advanceBy(100.ms)
    val frozen = single.currentValueRaw
    val expected = FloatTypeConverter.newVector()
    spring
        .createAnimator(0f, 1f, FloatTypeConverter)
        .getVelocity(100.ms.inWholeNanoseconds, expected)
    playback.pause()
    assertThat(single.isRunning).isFalse()
    animationRule.advanceBy(5_000.ms)
    view.setRoot(component(0f))
    lithoTestRule.idle()
    assertThat(single.currentValueRaw).isEqualTo(frozen)
    assertThat(velocities.last()).isEqualTo(expected[0])
    assertThat(expected[0]).isNotEqualTo(0f)
    playback.play()
    animationRule.runNextFrame()
    assertThat(single.currentValueRaw).isEqualTo(frozen)
  }

  @Test
  fun finishPublishesTheWholeLatestGroupAndKeepsFutureRunsPaused() {
    val playback = AnimationPlayback(false)
    val notifications = mutableListOf<Float>()
    val view = lithoTestRule.render { Mixed(0f, playback, withLoop = false) }
    lithoTestRule.idle()
    val oldHandle = group
    val callback: (Float) -> Unit = { notifications.add(it) }
    view.setRoot(Mixed(1f, playback, withLoop = false, onSettled = callback))
    lithoTestRule.idle()
    view.setRoot(Mixed(2f, playback, withLoop = false, onSettled = callback))
    lithoTestRule.idle()
    val observed = mutableListOf<List<Any?>>()
    group.state.onStateSettled = {
      observed.add(group.state.animatedValues.map { it.currentValueRaw })
    }
    oldHandle.finish()
    oldHandle.finish()
    assertThat(observed).containsExactly(listOf(2f, 2f))
    assertThat(notifications).isEmpty()
    animationRule.runNextFrame()
    assertThat(notifications).containsExactly(2f)
    assertThat(playback.state).isEqualTo(PlaybackState.Paused)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    view.setRoot(Mixed(3f, playback, withLoop = false, onSettled = callback))
    lithoTestRule.idle()
    assertThat(group.state.animatedValues.map { it.currentValueRaw }).containsExactly(2f, 2f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun pausingFromSettlementFreezesOtherOwnersWithoutBlockingNotifications() {
    val playback = AnimationPlayback(true)
    val view = lithoTestRule.render { Mixed(0f, playback) }
    lithoTestRule.idle()
    var settled = false
    view.setRoot(
        Mixed(
            1f,
            playback,
            onSettled = {
              settled = true
              playback.pause()
            },
        ),
    )
    lithoTestRule.idle()
    animationRule.advanceBy(1_000.ms)
    assertThat(settled).isTrue()
    assertThat(playback.state).isEqualTo(PlaybackState.Paused)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    val frozen = loop.value
    animationRule.advanceBy(5_000.ms)
    assertThat(loop.value).isEqualTo(frozen)
  }

  private inner class Mixed(
      private val target: Float,
      private val playback: AnimationPlayback,
      private val withLoop: Boolean = true,
      private val onSettled: ((Float) -> Unit)? = null,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      single = useAnimatedFloat(target, linear, playback = playback) as AnimatedValue<Float>
      val transition = useAnimationGroup(target, playback = playback, onSettled = onSettled)
      group = transition
      val alpha = transition.animateFloat(transitionSpec = { linear }) { it }
      val rotation =
          transition.animateFloat(
              transitionSpec = { if (targetState == 1f) tween(100.ms, Easing.Linear) else linear },
          ) {
            it
          }
      return Row(style = Style.width(10.px).height(10.px).alpha(alpha).rotation(rotation)) {
        if (withLoop) child(Pulse(playback))
      }
    }
  }

  private inner class Pulse(private val playback: AnimationPlayback) : KComponent() {
    override fun ComponentScope.render(): Component {
      loop =
          useInfiniteAnimationGroup(playback = playback)
              .animateFloat(
                  from = 0.1f,
                  to = 0.9f,
                  animation = linear,
                  repeatMode = RepeatMode.Restart,
              )
      return Row(style = Style.width(10.px).height(10.px).alpha(loop))
    }
  }
}
