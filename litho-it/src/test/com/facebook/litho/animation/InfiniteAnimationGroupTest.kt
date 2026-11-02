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
import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.ComponentTreeHolder
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class InfiniteAnimationGroupTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private val tolerance = Offset.offset(0.02f)

  private var captured: List<Value<Float>> = emptyList()

  @Before
  fun setUp() {
    captured = emptyList()
  }

  private fun idle() = shadowOf(getMainLooper()).idle()

  /** One linear 0→1 loop over 1000ms (Restart), captured into [captured][0]. */
  private inner class LoopComponent(
      private val running: Boolean = true,
      private val from: Float = 0f,
      private val to: Float = 1f,
      private val initialStartOffset: StartOffset = StartOffset(0),
      private val renderVersion: Int = 0,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val infinite = useInfiniteAnimationGroup(shouldRun = running)
      val v =
          infinite.animateFloat(
              from = from,
              to = to,
              animation = tween(duration = 1_000.ms, easing = Easing.Linear),
              repeatMode = RepeatMode.Restart,
              initialStartOffset = initialStartOffset,
          )
      captured = listOf(v)
      return Row(style = Style.width((10 + renderVersion).px).height(10.px).alpha(v))
    }
  }

  @Test
  fun restoredTree_holderRebuild_restartsAnUnchangedLoop() {
    val component = LoopComponent()
    val holder =
        ComponentTreeHolder.create(ComponentsConfiguration.defaultInstance)
            .renderInfo(
                ComponentRenderInfo.create()
                    .component(component)
                    .customAttribute(ComponentTreeHolder.ACQUIRE_STATE_HANDLER_ON_RELEASE, true)
                    .build(),
            )
            .build()
    val sizeSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
    holder.computeLayoutSync(lithoTestRule.context, sizeSpec, sizeSpec, null)
    val originalTree = checkNotNull(holder.componentTree)
    val original = lithoTestRule.render(componentTree = originalTree) { component }
    idle()
    animationRule.advanceBy(250.ms)
    assertThat(original.lithoView.alpha).isCloseTo(0.25f, tolerance)
    val retainedValue = captured.single()

    holder.acquireStateAndReleaseTree()
    original.release()
    idle()
    assertThat(originalTree.isReleased).isTrue()
    assertThat(holder.componentTree).isNull()
    assertThat(animationRule.hasScheduledFrames()).isFalse()

    captured = emptyList()
    val restoredComponent = LoopComponent(renderVersion = 1)
    holder.renderInfo =
        ComponentRenderInfo.create()
            .component(restoredComponent)
            .customAttribute(ComponentTreeHolder.ACQUIRE_STATE_HANDLER_ON_RELEASE, true)
            .build()
    holder.computeLayoutSync(lithoTestRule.context, sizeSpec, sizeSpec, null)
    val restored =
        lithoTestRule.render(componentTree = checkNotNull(holder.componentTree)) {
          restoredComponent
        }
    idle()
    assertThat(captured.single()).isEqualTo(retainedValue)
    animationRule.advanceBy(500.ms)
    assertThat(restored.lithoView.alpha).isCloseTo(0.5f, tolerance)
    animationRule.advanceBy(750.ms)
    assertThat(restored.lithoView.alpha).isCloseTo(0.25f, tolerance)
    assertThat(animationRule.hasScheduledFrames()).isTrue()
    restored.release()
    idle()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun initialValueIsFrom() {
    lithoTestRule.render { LoopComponent(from = 0.3f, to = 1f) }
    idle()
    assertThat(captured[0].value).isCloseTo(0.3f, tolerance)
  }

  @Test
  fun advancesDeterministicallyWhileRunning() {
    lithoTestRule.render { LoopComponent() }
    idle()

    animationRule.advanceBy(500.ms)
    assertThat(captured[0].value).`as`("midway through the linear cycle").isCloseTo(0.5f, tolerance)

    // Crossing the 1000ms cycle boundary restarts from `from` (Restart mode).
    animationRule.advanceBy(750.ms) // total 1250ms → 250ms into the 2nd cycle
    assertThat(captured[0].value).`as`("restarted into the next cycle").isCloseTo(0.25f, tolerance)
  }

  @Test
  fun runningFalse_holdsAtFromAndDoesNotAdvance() {
    lithoTestRule.render { LoopComponent(running = false) }
    idle()

    animationRule.advanceBy(500.ms)
    assertThat(captured[0].value).`as`("paused loop holds at from").isCloseTo(0f, tolerance)
    assertThat(AnimationController.hasRunningAnimations).isFalse()
  }

  @Test
  fun runningTrue_keepsFrameCallbacksScheduled() {
    lithoTestRule.render { LoopComponent(running = true) }
    idle()

    animationRule.advanceBy(500.ms)
    assertThat(AnimationController.hasRunningAnimations).isTrue()
  }

  @Test
  fun runningFalseToTrue_restartsFromFrom() {
    val testView = lithoTestRule.render { LoopComponent(running = false) }
    idle()
    assertThat(captured[0].value).isCloseTo(0f, tolerance)

    testView.setRoot(LoopComponent(running = true))
    idle()
    animationRule.advanceBy(500.ms)
    assertThat(captured[0].value).`as`("resumes and runs from from").isCloseTo(0.5f, tolerance)
  }

  @Test
  fun inlineEqualTween_doesNotRestartOnUnrelatedRender() {
    val testView = lithoTestRule.render { LoopComponent(renderVersion = 0) }
    idle()
    animationRule.advanceBy(500.ms)
    assertThat(captured[0].value).isCloseTo(0.5f, tolerance)

    testView.setRoot(LoopComponent(renderVersion = 1))
    idle()
    animationRule.advanceBy(250.ms)

    assertThat(captured[0].value)
        .`as`("inline tween with equal config keeps its playhead")
        .isCloseTo(0.75f, tolerance)
  }

  private inner class KeyframesLoopComponent(private val renderVersion: Int = 0) : KComponent() {
    override fun ComponentScope.render(): Component {
      val infinite = useInfiniteAnimationGroup()
      val v =
          infinite.animateFloat(
              from = 0f,
              to = 1f,
              animation =
                  keyframes(duration = 1_000.ms) {
                    0f at 0.ms
                    1f at 1_000.ms
                  },
              repeatMode = RepeatMode.Restart,
              label = "keyframes",
          )
      captured = listOf(v)
      return Row(style = Style.width((10 + renderVersion).px).height(10.px))
    }
  }

  @Test
  fun inlineEqualKeyframes_doesNotRestartOnUnrelatedRender() {
    val testView = lithoTestRule.render { KeyframesLoopComponent(renderVersion = 0) }
    idle()
    animationRule.advanceBy(500.ms)
    assertThat(captured[0].value).isCloseTo(0.5f, tolerance)

    testView.setRoot(KeyframesLoopComponent(renderVersion = 1))
    idle()
    animationRule.advanceBy(250.ms)

    assertThat(captured[0].value)
        .`as`("inline keyframes with equal frames keeps its playhead")
        .isCloseTo(0.75f, tolerance)
  }

  private inner class LambdaEasingLoopComponent(private val renderVersion: Int = 0) : KComponent() {
    override fun ComponentScope.render(): Component {
      val infinite = useInfiniteAnimationGroup()
      val v =
          infinite.animateFloat(
              from = 0f,
              to = 1f,
              animation =
                  tween(
                      duration = 1_000.ms,
                      easing = Easing { fraction -> fraction + renderVersion * 0f },
                  ),
              repeatMode = RepeatMode.Restart,
              label = "lambda",
          )
      captured = listOf(v)
      return Row(style = Style.width((10 + renderVersion).px).height(10.px))
    }
  }

  @Test
  fun inlineCustomLambdaEasing_restartsOnUnrelatedRender() {
    val testView = lithoTestRule.render { LambdaEasingLoopComponent(renderVersion = 0) }
    idle()
    animationRule.advanceBy(500.ms)
    assertThat(captured[0].value).isCloseTo(0.5f, tolerance)

    testView.setRoot(LambdaEasingLoopComponent(renderVersion = 1))
    idle()
    animationRule.advanceBy(250.ms)

    assertThat(captured[0].value)
        .`as`("fresh lambda easing is not value-equal, so the loop restarts")
        .isCloseTo(0f, tolerance)

    animationRule.advanceBy(250.ms)
    assertThat(captured[0].value).isCloseTo(0.25f, tolerance)
  }

  /** Two linear 0→1 loops; the second is fast-forwarded 500ms so it runs half a cycle ahead. */
  private inner class StaggeredComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val infinite = useInfiniteAnimationGroup()
      val a =
          infinite.animateFloat(
              from = 0f,
              to = 1f,
              animation = tween(duration = 1_000.ms, easing = Easing.Linear),
              repeatMode = RepeatMode.Restart,
              label = "a",
          )
      val b =
          infinite.animateFloat(
              from = 0f,
              to = 1f,
              animation = tween(duration = 1_000.ms, easing = Easing.Linear),
              repeatMode = RepeatMode.Restart,
              initialStartOffset = StartOffset(500, StartOffsetType.FastForward),
              label = "b",
          )
      captured = listOf(a, b)
      return Row(style = Style.width(10.px).height(10.px))
    }
  }

  @Test
  fun staggeredChildren_runAtDifferentPhases() {
    lithoTestRule.render { StaggeredComponent() }
    idle()

    animationRule.advanceBy(250.ms)
    val a = captured[0].value
    val b = captured[1].value
    // a is 250ms in (0.25); b is fast-forwarded 500ms so it is 750ms in (0.75).
    assertThat(a).isCloseTo(0.25f, tolerance)
    assertThat(b).isCloseTo(0.75f, tolerance)
    assertThat(b).isGreaterThan(a)
  }

  @Test
  fun sharedClockPreservesIndependentRestartAndStoppedIntervals() {
    var group: InfiniteAnimationGroup? = null
    fun component(revision: Int, running: Boolean = true): Component =
        object : KComponent() {
          override fun ComponentScope.render(): Component {
            val infinite = useInfiniteAnimationGroup(shouldRun = running)
            group = infinite
            repeat(2) { slot ->
              val from = if (slot == 0 && revision == 1) 10f else 0f
              infinite.animateFloat(
                  from = from,
                  to = from + 1f,
                  animation = tween(1_000.ms, Easing.Linear),
                  repeatMode = RepeatMode.Restart,
              )
            }
            return Row(style = Style.width(10.px).height(10.px))
          }
        }
    val view = lithoTestRule.render { component(0) }
    idle()
    val first = checkNotNull(checkNotNull(group).state.animatedValueAt(0))
    val second = checkNotNull(checkNotNull(group).state.animatedValueAt(1))
    assertThat(first.clock).isSameAs(second.clock)
    animationRule.runNextFrame()
    animationRule.runNextFrame(250.ms)
    assertThat(first.currentValueRaw).isEqualTo(0.25f)
    assertThat(second.currentValueRaw).isEqualTo(0.25f)

    view.setRoot(component(1))
    idle()
    assertThat(first.currentValueRaw).isEqualTo(0.25f)
    animationRule.runNextFrame(250.ms)
    assertThat(first.currentValueRaw).isEqualTo(10f)
    assertThat(second.currentValueRaw).isEqualTo(0.5f)
    animationRule.runNextFrame(250.ms)
    assertThat(first.currentValueRaw).isEqualTo(10.25f)
    assertThat(second.currentValueRaw).isEqualTo(0.75f)

    view.setRoot(component(1, running = false))
    idle()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    animationRule.runNextFrame(10_000.ms)
    assertThat(first.currentValueRaw).isEqualTo(10.25f)
    assertThat(second.currentValueRaw).isEqualTo(0.75f)
    view.setRoot(component(1))
    idle()
    animationRule.runNextFrame(250.ms)
    assertThat(first.currentValueRaw).isEqualTo(10f)
    assertThat(second.currentValueRaw).isEqualTo(0f)
    animationRule.runNextFrame(250.ms)
    assertThat(first.currentValueRaw).isEqualTo(10.25f)
    assertThat(second.currentValueRaw).isEqualTo(0.25f)
    view.release()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun multipleChildren_keepIndependentValues() {
    lithoTestRule.render { StaggeredComponent() }
    idle()
    animationRule.advanceBy(250.ms)
    // Two children under one container hold distinct values driven by the same lifecycle.
    assertThat(captured[0].value).isNotEqualTo(captured[1].value)
  }

  private inner class ShapeChangingComponent(private val intFirst: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component {
      val infinite = useInfiniteAnimationGroup(label = "shape")
      if (intFirst) {
        infinite.animateInt(
            from = 0,
            to = 10,
            animation = tween(duration = 1_000.ms, easing = Easing.Linear),
            label = "count",
        )
        infinite.animateFloat(
            from = 0f,
            to = 1f,
            animation = tween(duration = 1_000.ms, easing = Easing.Linear),
            label = "alpha",
        )
      } else {
        infinite.animateFloat(
            from = 0f,
            to = 1f,
            animation = tween(duration = 1_000.ms, easing = Easing.Linear),
            label = "alpha",
        )
        infinite.animateInt(
            from = 0,
            to = 10,
            animation = tween(duration = 1_000.ms, easing = Easing.Linear),
            label = "count",
        )
      }
      return Row(style = Style.width(10.px).height(10.px))
    }
  }

  @Test
  fun childOrderOrTypeChange_failsClearly() {
    val testView = lithoTestRule.render { ShapeChangingComponent(intFirst = false) }
    idle()

    assertThatThrownBy {
          testView.setRoot(ShapeChangingComponent(intFirst = true))
          idle()
        }
        .hasMessageContaining("changed animation order or value type")
  }
}
