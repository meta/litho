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

import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.ErrorEventHandler
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimationSettlementTest {
  private val errors = mutableListOf<Exception>()

  @Rule
  @JvmField
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableStateReadTracking = true,
          errorEventHandler =
              object : ErrorEventHandler() {
                override fun onError(cc: ComponentContext, e: Exception): Component? {
                  errors.add(e)
                  return null
                }
              },
      ),
  )

  @Rule @JvmField val animationRule = LithoAnimationTestRule { lithoTestRule.idle() }

  @Test
  fun initialComposition_isSilentAndCompletionObservesMountedValue() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Pair<Float, Float>>()
      lateinit var view: TestLithoView
      val callback: (Float) -> Unit = { notifications.add(it to view.lithoView.alpha) }
      view = lithoTestRule.render {
        SettlementComponent(surface, 0f, tween(1_000.ms), callback)
      }
      lithoTestRule.idle()
      assertThat(notifications).isEmpty()
      assertThat(animationRule.hasScheduledFrames()).isFalse()

      view.setRoot(SettlementComponent(surface, 1f, onSettled = callback))
      lithoTestRule.idle()
      assertThat(notifications).isEmpty()
      animationRule.runNextFrame()

      assertThat(notifications).containsExactly(1f to 1f)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun discardedInitialResolve_seedsTheFirstCommittedTargetWithoutMotion() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Float>()
      val callback: (Float) -> Unit = { notifications.add(it) }
      val spec = tween<Float>(1_000.ms)
      val tree =
          ComponentTree.create(lithoTestRule.context)
              .componentsConfiguration(checkNotNull(lithoTestRule.componentsConfiguration))
              .build()
      val sizeSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
      for (target in listOf(0f, 1f)) {
        val resolve = FutureTask {
          tree.setRootAndSizeSpecSync(
              SettlementComponent(surface, target, spec, callback),
              sizeSpec,
              sizeSpec,
          )
        }
        Thread(resolve).start()
        resolve.get(10, TimeUnit.SECONDS)
      }
      val view =
          lithoTestRule.render(componentTree = tree) {
            SettlementComponent(surface, 1f, spec, callback)
          }
      lithoTestRule.idle()

      assertThat(view.lithoView.alpha).isEqualTo(1f)
      assertThat(notifications).isEmpty()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun callbackUpdates_preserveProgressAndUseLatestCommittedCallbackAtDelivery() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<String>()
      val spec = tween<Float>(duration = 1_000.ms, easing = Easing.Linear)
      val view = lithoTestRule.render {
        SettlementComponent(surface, 0f, spec) { notifications.add("initial") }
      }
      lithoTestRule.idle()
      view.setRoot(SettlementComponent(surface, 1f, spec) { notifications.add("old") })
      animationRule.advanceBy(200.ms)
      val progress = view.lithoView.alpha
      assertThat(progress).isGreaterThan(0f).isLessThan(1f)

      view.setRoot(SettlementComponent(surface, 1f, spec) { notifications.add("updated") })
      lithoTestRule.idle()
      assertThat(view.lithoView.alpha).isEqualTo(progress)
      AnimationController.enqueueCompletion(
          {
            view.setRoot(SettlementComponent(surface, 1f, spec) { notifications.add("latest") })
            lithoTestRule.idle()
          },
      )
      animationRule.advanceBy(800.ms)

      assertThat(notifications).containsExactly("latest")
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun listenerAddedDuringTerminalFrame_receivesTheSettledGeneration() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Float>()
      val view = lithoTestRule.render { SettlementComponent(surface, 0f, onSettled = null) }
      lithoTestRule.idle()
      view.setRoot(SettlementComponent(surface, 1f, onSettled = null))
      lithoTestRule.idle()
      AnimationController.enqueueCompletion(
          {
            view.setRoot(SettlementComponent(surface, 1f) { notifications.add(it) })
            lithoTestRule.idle()
          },
      )

      animationRule.runNextFrame()

      assertThat(notifications).containsExactly(1f)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun queuedSettlement_isSuppressedByReversalBeforeDelivery() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Float>()
      val callback: (Float) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { SettlementComponent(surface, 0f, onSettled = callback) }
      lithoTestRule.idle()
      view.setRoot(SettlementComponent(surface, 1f, onSettled = callback))
      lithoTestRule.idle()
      AnimationController.enqueueCompletion(
          {
            view.setRoot(SettlementComponent(surface, 0f, onSettled = callback))
            lithoTestRule.idle()
          },
      )

      animationRule.runNextFrame()
      assertThat(notifications).isEmpty()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(0f)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun queuedSettlement_isSuppressedByUnmountBeforeDelivery() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Float>()
      val callback: (Float) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { SettlementComponent(surface, 0f, onSettled = callback) }
      lithoTestRule.idle()
      view.setRoot(SettlementComponent(surface, 1f, onSettled = callback))
      lithoTestRule.idle()
      AnimationController.enqueueCompletion(
          {
            view.release()
            lithoTestRule.idle()
          },
      )

      animationRule.runNextFrame()

      assertThat(notifications).isEmpty()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
    }
  }

  @Test
  fun queuedSettlement_usesCallbackRemovalBeforeDelivery() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Float>()
      val callback: (Float) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { SettlementComponent(surface, 0f, onSettled = callback) }
      lithoTestRule.idle()
      view.setRoot(SettlementComponent(surface, 1f, onSettled = callback))
      lithoTestRule.idle()
      AnimationController.enqueueCompletion(
          {
            view.setRoot(SettlementComponent(surface, 1f, onSettled = null))
            lithoTestRule.idle()
          },
      )

      animationRule.runNextFrame()

      assertThat(notifications).isEmpty()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun idleSpecUpdate_doesNotCreateAnotherCompletionOrAnimation() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Float>()
      val callback: (Float) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { SettlementComponent(surface, 0f, onSettled = callback) }
      lithoTestRule.idle()
      view.setRoot(SettlementComponent(surface, 1f, onSettled = callback))
      lithoTestRule.idle()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(1f)

      view.setRoot(SettlementComponent(surface, 1f, tween(500.ms), callback))
      lithoTestRule.idle()
      animationRule.runNextFrame()

      assertThat(notifications).containsExactly(1f)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun callbackFailure_reachesLithoErrorHandlerAndPreservesQueuedWork() {
    for (surface in Surface.entries) {
      errors.clear()
      val notifications = mutableListOf<String>()
      val callback: (Float) -> Unit = {
        AnimationController.enqueueCompletion({ notifications.add("next frame") })
        error("settlement callback failed")
      }
      val view = lithoTestRule.render { SettlementComponent(surface, 0f, onSettled = callback) }
      lithoTestRule.idle()
      view.setRoot(SettlementComponent(surface, 1f, onSettled = callback))
      lithoTestRule.idle()

      animationRule.runNextFrame()

      assertThat(errors).hasSize(1)
      assertThat(errors.single()).hasMessageContaining("settlement callback failed")
      assertThat(notifications).isEmpty()
      assertThat(animationRule.hasScheduledFrames()).isTrue()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly("next frame")
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun noOpGroup_settlesAsynchronouslyWhilePaused() {
    for (withSlot in listOf(false, true)) {
      val notifications = mutableListOf<Boolean>()
      val playback = AnimationPlayback(false)
      val callback: (Boolean) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render {
        ConstantGroup(false, withSlot, snap(), playback, callback)
      }
      lithoTestRule.idle()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.setRoot(ConstantGroup(true, withSlot, snap(), playback, callback))
      lithoTestRule.idle()

      assertThat(notifications).isEmpty()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(true)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun equalEndpointsWithTimedWork_waitForPlayAndFullDuration() {
    val specs = listOf(
        tween<Float>(duration = 0.ms, delay = 1_000.ms) to 1f,
        tween<Float>(duration = 1_000.ms) to 1f,
        repeatable<Float>(iterations = 2, animation = tween(500.ms)) to 1f,
        keyframes<Float>(1_000.ms) { 0.5f at 500.ms } to 0.5f,
        spring<Float>().delayed(1_000.ms) to 1f,
        chain<Float> {
          animateTo(0.5f, tween(400.ms, Easing.Linear))
          wait(200.ms)
          animateToTarget(tween(400.ms, Easing.Linear))
        } to 0.5f,
    )
    for ((spec, midpoint) in specs) {
      val notifications = mutableListOf<Boolean>()
      val playback = AnimationPlayback(false)
      val callback: (Boolean) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { ConstantGroup(false, true, spec, playback, callback) }
      lithoTestRule.idle()
      view.setRoot(ConstantGroup(true, true, spec, playback, callback))
      lithoTestRule.idle()
      animationRule.advanceBy(2_000.ms)

      assertThat(notifications).isEmpty()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      playback.play()
      animationRule.advanceBy(500.ms)
      assertThat(view.lithoView.alpha).isEqualTo(midpoint)
      assertThat(notifications).isEmpty()
      animationRule.advanceBy(500.ms)
      assertThat(view.lithoView.alpha).isEqualTo(1f)
      assertThat(notifications).containsExactly(true)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  private enum class Surface {
    Value,
    Group,
  }

  private class SettlementComponent(
      private val surface: Surface,
      private val target: Float,
      private val spec: FiniteAnimationSpec<Float> = snap(),
      private val onSettled: ((Float) -> Unit)?,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val alpha =
          when (surface) {
            Surface.Value -> useAnimatedFloat(target, spec, onSettled = onSettled)
            Surface.Group ->
                useAnimationGroup(target, onSettled = onSettled).animateFloat(
                    transitionSpec = { spec },
                ) {
                  it
                }
          }
      return Row(style = Style.width(100.px).height(100.px).alpha(alpha))
    }
  }

  private class ConstantGroup(
      private val target: Boolean,
      private val withSlot: Boolean,
      private val spec: FiniteAnimationSpec<Float>,
      private val playback: AnimationPlayback,
      private val onSettled: (Boolean) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val group = useAnimationGroup(target, playback = playback, onSettled = onSettled)
      return if (withSlot) {
        Row(
            style =
                Style.width(100.px)
                    .height(100.px)
                    .alpha(group.animateFloat(transitionSpec = { spec }) { 1f }),
        )
      } else Row()
    }
  }
}
