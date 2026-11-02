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

import android.os.Looper
import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class ContainerAnimationSettlementTest {
  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private var flushAfterFrame = true

  @Rule
  @JvmField
  val animationRule = LithoAnimationTestRule { if (flushAfterFrame) lithoTestRule.idle() }

  private val linear = tween<Float>(1_000.ms, Easing.Linear)

  @Test
  fun hiddenSnapshotBeforeShowCommit_doesNotRemountContent() {
    val notifications = mutableListOf<Int>()
    val callback: (Int) -> Unit = { notifications.add(it) }
    fun content(visible: Boolean, beforeResolve: () -> Unit = {}) = ResolveGate(
        container(
            Surface.Visibility,
            if (visible) 1 else 0,
            callback,
            fadeIn(linear),
            fadeOut(linear),
        ),
        beforeResolve,
    )
    val view = lithoTestRule.render { content(false) }
    view.setSizePx(100, 100).measure().layout()
    lithoTestRule.idle()
    val tree = view.componentTree
    fun resolve(component: Component): FutureTask<Unit> {
      val task = FutureTask {
        val size = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        tree.setRootAndSizeSpecSync(component, size, size)
      }
      Thread(task).start()
      return task
    }
    resolve(content(true)).get(10, TimeUnit.SECONDS)
    val started = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val hidden = resolve(
        content(false) {
          started.countDown()
          assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue()
        },
    )
    val layoutLooper = shadowOf(ComponentTree.getDefaultLayoutThreadLooper())
    val wasPaused = layoutLooper.isPaused
    layoutLooper.pause()
    try {
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
      repeat(8) {
        if (view.findViewWithTagOrNull("content-1") == null) {
          shadowOf(Looper.getMainLooper()).runOneTask()
          view.lithoView.mountComponent(null, true)
        }
      }
      assertThat(view.findViewWithTagOrNull("content-1")).isNotNull()
      finish.countDown()
      hidden.get(10, TimeUnit.SECONDS)
      shadowOf(Looper.getMainLooper()).idle()
      view.measure().layout()
      lithoTestRule.idle()
      assertThat(view.findComponent(Marker::class)).isNull()
      animationRule.advanceBy(2_000.ms)
      assertThat(view.findComponent(Marker::class)).isNull()
      assertThat(notifications).containsExactly(0)
    } finally {
      try {
        finish.countDown()
        hidden.get(10, TimeUnit.SECONDS)
      } finally {
        if (!wasPaused) layoutLooper.unPause()
      }
    }
  }

  @Test
  fun initialContentIsSilentAndNoMotionTargetsSettleAsynchronously() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Int>()
      val callback: (Int) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { container(surface, 0, callback) }
      lithoTestRule.idle()
      assertThat(notifications).isEmpty()
      assertThat(animationRule.hasScheduledFrames()).isFalse()

      view.setRoot(container(surface, 1, callback))
      lithoTestRule.idle()
      assertThat(notifications).isEmpty()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(1)

      view.setRoot(container(surface, 0, callback))
      lithoTestRule.idle()
      assertThat(notifications).containsExactly(1)
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(1, 0)
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(1, 0)
      view.release()
    }
  }

  @Test
  fun callbackUpdatesPreserveProgressAndUseLatestListenerAtDelivery() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<String>()
      val enter = fadeIn(linear)
      val view = lithoTestRule.render { container(surface, 0, enter = enter) }
      lithoTestRule.idle()
      view.setRoot(container(surface, 1, enter = enter))
      lithoTestRule.idle()
      animationRule.advanceBy(200.ms)
      val progress = alpha(view, 1)
      assertThat(progress).isGreaterThan(0f).isLessThan(1f)

      view.setRoot(container(surface, 1, { notifications.add("updated") }, enter))
      lithoTestRule.idle()
      assertThat(alpha(view, 1)).isEqualTo(progress)
      AnimationController.enqueueCompletion {
        view.setRoot(container(surface, 1, { notifications.add("latest") }, enter))
        lithoTestRule.idle()
      }
      animationRule.advanceBy(800.ms)
      assertThat(notifications).containsExactly("latest")
      view.release()
    }
  }

  @Test
  fun supersededAndUnmountedQueuedCompletionsAreSuppressed() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Int>()
      val callback: (Int) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { container(surface, 0, callback) }
      lithoTestRule.idle()
      AnimationController.enqueueCompletion {
        view.setRoot(container(surface, 0, callback))
        lithoTestRule.idle()
      }
      view.setRoot(container(surface, 1, callback))
      lithoTestRule.idle()
      animationRule.runNextFrame()
      assertThat(notifications).isEmpty()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(0)

      AnimationController.enqueueCompletion { view.release() }
      view.setRoot(container(surface, 1, callback))
      lithoTestRule.idle()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(0)
    }
  }

  @Test
  fun restoredContainersMountSilentlyAndIgnoreTheirOldQueuedCompletion() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Int>()
      val callback: (Int) -> Unit = { notifications.add(it) }
      val view = lithoTestRule.render { container(surface, 0, callback) }
      lithoTestRule.idle()
      view.setRoot(container(surface, 1, callback))
      lithoTestRule.idle()
      val treeState = view.componentTree.acquireTreeState()
      val treeId = view.componentTree.id
      view.release()

      val tree =
          ComponentTree.create(lithoTestRule.context)
              .treeState(treeState)
              .overrideComponentTreeId(treeId)
              .build()
      val restored = lithoTestRule.render(componentTree = tree) { container(surface, 1, callback) }
      lithoTestRule.idle()
      animationRule.runNextFrame()
      assertThat(notifications).isEmpty()
      restored.setRoot(container(surface, 0, callback))
      lithoTestRule.idle()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(0)
      restored.release()
    }
  }

  @Test
  fun completionRequestsRemovalWithoutWaitingForItsCommit() {
    for (surface in Surface.entries) {
      val notifications = mutableListOf<Pair<Int, Boolean>>()
      lateinit var view: TestLithoView
      val callback: (Int) -> Unit = {
        notifications.add(it to (view.findViewWithTagOrNull("content-1") != null))
      }
      view = lithoTestRule.render { container(surface, 1, callback, exit = fadeOut(linear)) }
      lithoTestRule.idle()
      view.setRoot(container(surface, 0, callback, exit = fadeOut(linear)))
      lithoTestRule.idle()
      flushAfterFrame = false
      animationRule.advanceBy(1_000.ms)
      assertThat(notifications).containsExactly(0 to true)

      lithoTestRule.idle()
      assertThat(view.findAllComponents(Marker::class).map { (it as Marker).target })
          .doesNotContain(1)
      flushAfterFrame = true
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(0 to true)
      view.release()
    }
  }

  @Test
  fun visibilityReversalWaitsForAllEffectsAndHiddenResetDoesNotNotifyAgain() {
    val notifications = mutableListOf<Int>()
    val callback: (Int) -> Unit = { notifications.add(it) }
    val enter = fadeIn(tween(300.ms)) + scaleIn(linear)
    val exit = fadeOut(linear)
    val view = lithoTestRule.render { container(Surface.Visibility, 1, callback, enter, exit) }
    lithoTestRule.idle()
    view.setRoot(container(Surface.Visibility, 0, callback, enter, exit))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    view.setRoot(container(Surface.Visibility, 1, callback, enter, exit))
    lithoTestRule.idle()
    animationRule.runNextFrame(0.ms)
    animationRule.advanceBy(300.ms)
    assertThat(notifications).isEmpty()
    assertThat(view.findViewWithTagOrNull("content-1")).isNotNull()
    animationRule.advanceBy(700.ms)
    assertThat(notifications).containsExactly(1)

    view.setRoot(container(Surface.Visibility, 0, callback, enter, exit))
    lithoTestRule.idle()
    animationRule.advanceBy(1_000.ms)
    assertThat(notifications).containsExactly(1, 0)
    assertThat(view.findComponent(Marker::class)).isNull()
    animationRule.runNextFrame()
    assertThat(notifications).containsExactly(1, 0)
  }

  @Test
  fun contentWaitsForEnterPreparationEvenWhenNoExitParticipates() {
    val notifications = mutableListOf<Int>()
    val callback: (Int) -> Unit = { notifications.add(it) }
    val enter = fadeIn(tween<Float>(1_000.ms, Easing.Linear, delay = 200.ms))
    val view = lithoTestRule.render { container(Surface.Content, 0, callback, enter) }
    lithoTestRule.idle()
    view.setRoot(container(Surface.Content, 1, callback, enter))
    lithoTestRule.idle()
    animationRule.runNextFrame()
    animationRule.advanceBy(200.ms)
    assertThat(notifications).isEmpty()
    assertThat(alpha(view, 1)).isEqualTo(0f)
    animationRule.advanceBy(500.ms)
    assertThat(notifications).isEmpty()
    assertThat(alpha(view, 1)).isGreaterThan(0f).isLessThan(1f)
    animationRule.advanceBy(500.ms)
    assertThat(notifications).containsExactly(1)
  }

  @Test
  fun latestContentWaitsForOlderOutgoingWorkWithoutRestartingIt() {
    val notifications = mutableListOf<Int>()
    fun content(target: Int) = AnimatedContent(
        targetState = target,
        transitionSpec = {
          enter = fadeIn(tween(300.ms))
          exit = fadeOut(tween(if (initialState == 0) 2_000.ms else 300.ms))
        },
        onSettled = { notifications.add(it) },
    ) {
      marker(it)
    }
    val view = lithoTestRule.render { content(0) }
    lithoTestRule.idle()
    view.setRoot(content(1))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    view.setRoot(content(2))
    lithoTestRule.idle()
    animationRule.runNextFrame(0.ms)
    animationRule.advanceBy(500.ms)
    assertThat(notifications).isEmpty()
    assertThat(view.findViewWithTagOrNull("content-0")).isNotNull()
    assertThat(view.findViewWithTagOrNull("content-1")).isNull()
    assertThat(alpha(view, 2)).isEqualTo(1f)
    animationRule.advanceBy(1_300.ms)
    assertThat(notifications).containsExactly(2)
    assertThat(view.findViewWithTagOrNull("content-0")).isNull()
  }

  @Test
  fun contentReentryRetainsTheSlotAndReportsOnlyTheReturnedTarget() {
    val notifications = mutableListOf<Int>()
    val callback: (Int) -> Unit = { notifications.add(it) }
    val enter = fadeIn(linear)
    val exit = fadeOut(linear)
    val view = lithoTestRule.render { container(Surface.Content, 0, callback, enter, exit) }
    lithoTestRule.idle()
    view.setRoot(container(Surface.Content, 1, callback, enter, exit))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    val outgoing = contentSlot(view, 0)
    val oldReport = outgoing.onSlotSettled
    val identity = outgoing.identity

    view.setRoot(container(Surface.Content, 0, callback, enter, exit))
    lithoTestRule.idle()
    oldReport()
    val returned = contentSlot(view, 0)
    assertThat(returned.identity).isSameAs(identity)
    animationRule.advanceBy(500.ms)
    assertThat(notifications).isEmpty()
    assertThat(view.findViewWithTagOrNull("content-1")).isNotNull()
    animationRule.advanceBy(500.ms)
    assertThat(notifications).containsExactly(0)
    assertThat(view.findViewWithTagOrNull("content-1")).isNull()
  }

  @Test
  fun sameKeyPayloadUpdatesRefreshPendingCompletionAndStaySilentAtRest() {
    val notifications = mutableListOf<Int>()
    fun content(target: Int) = AnimatedContent(
        targetState = target,
        transitionSpec = { enter = fadeIn(linear) },
        contentKey = { if (it == 0) 0 else 1 },
        onSettled = { notifications.add(it) },
    ) {
      marker(it)
    }
    val view = lithoTestRule.render { content(0) }
    lithoTestRule.idle()
    view.setRoot(content(1))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    val progress = alpha(view, 1)
    view.setRoot(content(2))
    lithoTestRule.idle()
    assertThat(alpha(view, 2)).isEqualTo(progress)
    animationRule.advanceBy(800.ms)
    assertThat(notifications).containsExactly(2)

    view.setRoot(content(3))
    lithoTestRule.idle()
    assertThat(notifications).containsExactly(2)
    assertThat(alpha(view, 3)).isEqualTo(1f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    animationRule.runNextFrame()
    assertThat(notifications).containsExactly(2)
  }

  @Test
  fun queuedContentCompletionUsesLatestPayloadWithoutCallbackFeedback() {
    class Payload(val key: Int, val value: Int)

    val notifications = mutableListOf<Payload>()
    lateinit var view: TestLithoView
    fun content(target: Payload): Component = AnimatedContent(
        targetState = target,
        contentKey = { it.key },
        transitionSpec = {},
        onSettled = { payload ->
          notifications.add(payload)
          if (notifications.size < 4) {
            view.setRoot(content(Payload(payload.key, payload.value + 1)))
            lithoTestRule.idle()
          }
        },
    ) {
      marker(it.value)
    }
    view = lithoTestRule.render { content(Payload(0, 0)) }
    lithoTestRule.idle()
    val queuedPayload = Payload(1, 2)
    AnimationController.enqueueCompletion {
      view.setRoot(content(queuedPayload))
      lithoTestRule.idle()
    }
    view.setRoot(content(Payload(1, 1)))
    lithoTestRule.idle()
    assertThat(notifications).isEmpty()

    animationRule.runNextFrame()
    assertThat(notifications).containsExactly(queuedPayload)
    repeat(3) { animationRule.runNextFrame() }
    assertThat(notifications).containsExactly(queuedPayload)
    assertThat(view.findViewWithTagOrNull("content-3")).isNotNull()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun pausedExitsRemainMountedAcrossRebindingAndSettleAfterResuming() {
    for (surface in Surface.entries) {
      val first = AnimationPlayback(true)
      val second = AnimationPlayback(false)
      val notifications = mutableListOf<String>()
      val enter = fadeIn(linear)
      val exit = fadeOut(linear)
      val view = lithoTestRule.render {
        container(surface, 1, enter = enter, exit = exit, playback = first)
      }
      lithoTestRule.idle()
      view.setRoot(container(surface, 0, { notifications.add("old") }, enter, exit, first))
      lithoTestRule.idle()
      animationRule.advanceBy(200.ms)
      val frozen = alpha(view, 1)
      assertThat(frozen).isGreaterThan(0f).isLessThan(1f)
      first.pause()
      animationRule.advanceBy(2_000.ms)
      assertThat(alpha(view, 1)).isEqualTo(frozen)
      assertThat(notifications).isEmpty()
      view.setRoot(container(surface, 0, { notifications.add("latest") }, enter, exit, second))
      lithoTestRule.idle()
      first.play()
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      second.play()
      animationRule.runNextFrame(2_000.ms)
      assertThat(alpha(view, 1)).isEqualTo(frozen)
      animationRule.runNextFrame(800.ms)
      assertThat(view.findAllComponents(Marker::class).filterIsInstance<Marker>().map { it.target })
          .doesNotContain(1)
      assertThat(notifications).containsExactly("latest")
      view.release()
    }
  }

  @Test
  fun pausedNoMotionContainersStillDeliverSettlementAsynchronously() {
    for (surface in Surface.entries) {
      val playback = AnimationPlayback(false)
      val notifications = mutableListOf<Int>()
      val view = lithoTestRule.render { container(surface, 0, playback = playback) }
      lithoTestRule.idle()
      view.setRoot(container(surface, 1, { notifications.add(it) }, playback = playback))
      lithoTestRule.idle()
      assertThat(notifications).isEmpty()
      animationRule.runNextFrame()
      assertThat(notifications).containsExactly(1)
      assertThat(playback.state).isEqualTo(PlaybackState.Paused)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  private fun container(
      surface: Surface,
      target: Int,
      onSettled: ((Int) -> Unit)? = null,
      enter: EnterTransition = EnterTransition.None,
      exit: ExitTransition = ExitTransition.None,
      playback: AnimationPlayback? = null,
  ): Component =
      when (surface) {
        Surface.Content ->
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                  this.enter = enter
                  this.exit = exit
                },
                onSettled = onSettled,
                playback = playback,
            ) {
              marker(it)
            }
        Surface.Visibility ->
            AnimatedVisibility(
                visible = target != 0,
                enter = enter,
                exit = exit,
                onSettled = onSettled?.let { callback -> { callback(if (it) 1 else 0) } },
                playback = playback,
            ) {
              marker(1)
            }
      }

  private fun marker(target: Int): Component = Marker(target)

  private class Marker(val target: Int) : KComponent() {
    override fun ComponentScope.render(): Component =
        Row(style = Style.width(50.px).height(50.px).viewTag("content-$target"))
  }

  private class ResolveGate(
      private val content: Component,
      private val beforeResolve: () -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      beforeResolve()
      return content
    }
  }

  private fun contentSlot(view: TestLithoView, target: Int): AnimatedContentSlot<*> =
      view
          .findAllComponents(AnimatedContentSlot::class)
          .filterIsInstance<AnimatedContentSlot<*>>()
          .single { it.state == target }

  private fun alpha(view: TestLithoView, target: Int): Float =
      (checkNotNull(view.findViewWithTagOrNull("content-$target")).parent as View).alpha

  private enum class Surface {
    Content,
    Visibility,
  }
}
