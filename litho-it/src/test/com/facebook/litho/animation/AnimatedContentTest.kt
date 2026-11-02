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
import com.facebook.litho.flexbox.alignSelf
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/** Tests for the public [AnimatedContent] keyed content-swap API. */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimatedContentTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private var flushAfterAnimationFrame = true

  @Rule
  @JvmField
  val animationRule = LithoAnimationTestRule {
    if (flushAfterAnimationFrame) lithoTestRule.idle()
  }

  private val linearFade = tween<Float>(duration = 1_000.ms, easing = Easing.Linear)

  private fun slotCount(testView: TestLithoView): Int =
      testView.findAllComponents(Marker::class).size

  @Test
  fun initialResolveDiscardedBeforeCommit_doesNotRetainUnshownContent() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val committedTargets = Collections.synchronizedList(mutableListOf<Item>())
    val first = Item("A", "Discarded")
    val second = Item("B", "Initial visible target")
    fun content(target: Item): Component = AnimatedContent(
        targetState = target,
        contentKey = { it.key },
        transitionSpec = {
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
    ) { state ->
      CommitMarker(state, state.key == target.key, committedTargets, renders)
    }
    val tree = ComponentTree.create(lithoTestRule.context).build()
    for (target in listOf(first, second)) {
      val resolved = resolveInBackground(tree, content(target))
      resolved.awaitResult()
      assertThat(committedTargets).isEmpty()
    }

    val view = lithoTestRule.render(componentTree = tree) { content(second) }
    lithoTestRule.idle()

    assertThat(committedTargets).containsExactly(second)
    assertThat(items(view)).containsExactly(second)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun unshownOutgoingSlotBecomesTargetBeforeRemoval_playsEnter() {
    val identity = AnimatedContentSlotIdentity("A")
    var settlementReports = 0
    fun content(isTarget: Boolean): Component = AnimatedContentSlot(
        state = "A",
        enter = fadeIn(linearFade),
        exit = fadeOut(linearFade),
        isTarget = isTarget,
        identity = identity,
        animateEnter = true,
        label = "unshown-slot",
        onSlotSettled = { settlementReports++ },
    ) { state ->
      Row(style = Style.width(50.px).height(50.px).viewTag("slot-$state"))
    }
    // Hold the slot after its exit report to exercise reentry before removal applies.
    val view = lithoTestRule.render { content(isTarget = false) }
    lithoTestRule.idle()
    val runtime = checkNotNull(identity.animation)
    assertThat(settlementReports).isEqualTo(1)
    assertThat(identity.hasExited).isTrue()
    assertThat(slotAlpha(view, "A")).isEqualTo(0f)

    view.setRoot(content(isTarget = true))
    lithoTestRule.idle()
    assertThat(identity.animation).isSameAs(runtime)
    assertThat(identity.hasExited).isFalse()
    animationRule.advanceBy(200.ms)
    assertThat(checkNotNull(slotAlpha(view, "A"))).isGreaterThan(0f).isLessThan(1f)
    animationRule.advanceBy(1_800.ms)

    assertThat(slotAlpha(view, "A")).isEqualTo(1f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    assertThat(settlementReports).isEqualTo(2)
  }

  @Test
  fun restoredTree_persistsTargetsAndReleasesOutgoingContent() {
    assertRestoredTreePersistsTargets(inLayout = false)
  }

  @Test
  fun restoredLayoutState_persistsTargetsAndReleasesOutgoingContent() {
    assertRestoredTreePersistsTargets(inLayout = true)
  }

  private fun assertRestoredTreePersistsTargets(inLayout: Boolean) {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())

    fun content(item: Item): Component {
      val animated = AnimatedContent(
          targetState = item,
          contentKey = { it.key },
          transitionSpec = {},
      ) { state ->
        ItemMarker(state, renders)
      }
      return if (inLayout) RenderWithConstraints { animated } else animated
    }

    val originalView = lithoTestRule.render { content(Item("X", "Initial")) }
    lithoTestRule.idle()
    repeat(20) { index ->
      originalView.setRoot(content(Item("X", "Update-$index")))
      lithoTestRule.idle()
    }
    val originalTree = originalView.componentTree
    val treeState = originalTree.acquireTreeState()
    val identity = renders.last().identity
    val treeId = originalTree.id
    originalView.release()

    val restoredTree =
        ComponentTree.create(lithoTestRule.context)
            .treeState(treeState)
            .overrideComponentTreeId(treeId)
            .build()
    val restoredView =
        lithoTestRule.render(componentTree = restoredTree) {
          content(Item("X", "Update-19"))
        }
    lithoTestRule.idle()
    assertThat(renders.last().identity).isSameAs(identity)

    restoredView.setRoot(content(Item("Y", "Second")))
    lithoTestRule.idle()
    restoredView.setRoot(content(Item("Z", "Third")))
    lithoTestRule.idle()

    assertThat(items(restoredView)).containsExactly(Item("Z", "Third"))
    animationRule.runNextFrame()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun exitSettlesAfterSnapshot_doesNotReturnOnCommit() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val first = Item("A", "First")
    val second = Item("B", "Second")
    val third = Item("C", "Third")
    val testView = lithoTestRule.render { itemContent(first, renders) }
    testView.setSizePx(100, 100).measure().layout()
    lithoTestRule.idle()
    testView.setRoot(itemContent(second, renders))
    lithoTestRule.idle()
    animationRule.advanceBy(900.ms)
    assertThat(itemAlpha(testView, first)).isGreaterThan(0f)

    val background = resolveInBackground(testView.componentTree, itemContent(third, renders))
    background.awaitResult()
    try {
      assertThat(testView.findViewWithTagOrNull(third)).isNull()
      flushAfterAnimationFrame = false
      animationRule.advanceBy(200.ms)
      flushAfterAnimationFrame = true
      assertThat(itemAlpha(testView, first)).isEqualTo(0f)
      shadowOf(Looper.getMainLooper()).runOneTask()
      lithoTestRule.idle()
      animationRule.advanceBy(2_000.ms)

      assertThat(animationRule.hasScheduledFrames()).isFalse()
      assertThat(items(testView)).containsExactly(third)
    } finally {
      flushAfterAnimationFrame = true
    }
  }

  @Test
  fun staleProjectionAfterReentry_keepsTheNewExit() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val first = Item("A", "A")
    val second = Item("B", "B")
    val third = Item("C", "C")
    val resolveStarted = CountDownLatch(1)
    val finishResolve = CountDownLatch(1)
    val blockOnce = AtomicBoolean(true)

    fun branchingContent(target: Item): Component = AnimatedContent(
        targetState = target,
        contentKey = { it.key },
        transitionSpec = {
          if (target == third && blockOnce.compareAndSet(true, false)) {
            resolveStarted.countDown()
            finishResolve.awaitSignal()
          }
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
    ) { state ->
      ItemMarker(state, renders)
    }

    val testView = lithoTestRule.render { branchingContent(first) }
    testView.setSizePx(100, 100).measure().layout()
    lithoTestRule.idle()
    testView.setRoot(branchingContent(second))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    val exiting = slot(testView, first)
    val oldReport = exiting.onSlotSettled
    val runtime = checkNotNull(exiting.identity.animation)
    val tree = testView.componentTree
    val returned = resolveInBackground(tree, branchingContent(first))
    returned.awaitResult()
    val background = resolveInBackground(tree, branchingContent(third))
    try {
      resolveStarted.awaitSignal()
      repeat(8) {
        if (runtime.targetState != EnterExitState.Visible) {
          shadowOf(Looper.getMainLooper()).runOneTask()
        }
      }
      assertThat(runtime.targetState).isEqualTo(EnterExitState.Visible)
      finishResolve.countDown()
      background.awaitResult()
      shadowOf(Looper.getMainLooper()).runOneTask()
      repeat(8) {
        if (testView.findViewWithTagOrNull(third) == null) {
          shadowOf(Looper.getMainLooper()).runOneTask()
        }
      }
      assertThat(testView.findViewWithTagOrNull(third)).isNotNull()
      oldReport()
      lithoTestRule.idle()
      assertThat(items(testView)).contains(first, third)
      animationRule.advanceBy(400.ms)
      assertThat(items(testView)).contains(first, third)
      animationRule.advanceBy(1_500.ms)
      lithoTestRule.idle()
      assertThat(items(testView)).containsExactly(third)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
    } finally {
      finishResolve.countDown()
      background.awaitResult()
    }
  }

  @Test
  fun staleEqualTarget_persistsReversionAfterAnInterveningCommit() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val first = Item("A", "A")
    val second = Item("B", "B")
    val resolveStarted = CountDownLatch(1)
    val finishResolve = CountDownLatch(1)
    val blockReturn = AtomicBoolean(false)

    fun branchingContent(target: Item): Component = AnimatedContent(
        targetState = target,
        contentKey = { it.key },
        transitionSpec = {
          if (target == first && blockReturn.compareAndSet(true, false)) {
            resolveStarted.countDown()
            finishResolve.awaitSignal()
          }
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
    ) { state ->
      ItemMarker(state, renders)
    }

    val testView = lithoTestRule.render { branchingContent(first) }
    testView.setSizePx(100, 100).measure().layout()
    lithoTestRule.idle()
    val tree = testView.componentTree
    val previous = resolveInBackground(tree, branchingContent(second))
    previous.awaitResult()
    blockReturn.set(true)
    val background = resolveInBackground(tree, branchingContent(first))
    try {
      resolveStarted.awaitSignal()
      repeat(8) {
        if (testView.findViewWithTagOrNull(second) == null) {
          shadowOf(Looper.getMainLooper()).runOneTask()
        }
      }
      assertThat(testView.findViewWithTagOrNull(second)).isNotNull()
      finishResolve.countDown()
      background.awaitResult()
      shadowOf(Looper.getMainLooper()).runOneTask()
      lithoTestRule.idle()
      assertThat(items(testView)).containsExactly(first)
      animationRule.advanceBy(2_000.ms)
      assertThat(items(testView)).containsExactly(first)
    } finally {
      finishResolve.countDown()
      background.awaitResult()
    }
  }

  @Test
  fun counterReversalBeforeFirstFrame_keepsIntermediateContentInvisible() {
    fun content(target: Int) = AnimatedContent(
        targetState = target,
        transitionSpec = {
          if (targetState >= initialState) {
            enter = fadeIn() + slideInHorizontally { it }
            exit = fadeOut() + slideOutHorizontally { -it }
          } else {
            enter = fadeIn() + slideInHorizontally { -it }
            exit = fadeOut() + slideOutHorizontally { it }
          }
        },
    ) { state ->
      Row(style = Style.width(50.px).height(50.px).viewTag("slot-$state"))
    }
    for (step in listOf(1, -1)) {
      val view = lithoTestRule.render { content(0) }
      lithoTestRule.idle()
      view.setRoot(content(step))
      lithoTestRule.idle()
      val intermediate = checkNotNull(view.findViewWithTagOrNull("slot-$step")).parent as View
      assertThat(intermediate.alpha).isEqualTo(0f)
      view.setRoot(content(0))
      lithoTestRule.idle()
      repeat(10) {
        animationRule.runNextFrame()
        view.findViewWithTagOrNull("slot-$step")?.let {
          assertThat((it.parent as View).alpha).isEqualTo(0f)
        }
      }
      animationRule.advanceBy(2_000.ms)
      assertThat(view.findViewWithTagOrNull("slot-$step")).isNull()
      view.release()
    }
  }

  @Test
  fun exitBeforeEnter_withoutExitAnimationReleases() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val first = Item("A", "A")
    val second = Item("B", "B")
    val third = Item("C", "C")
    fun content(target: Item): Component = AnimatedContent(
        targetState = target,
        contentKey = { it.key },
        transitionSpec = { enter = fadeIn(linearFade) },
    ) { state ->
      ItemMarker(state, renders)
    }

    val testView = lithoTestRule.render { content(first) }
    testView.setSizePx(100, 100).measure().layout()
    lithoTestRule.idle()
    val tree = testView.componentTree
    val previous = resolveInBackground(tree, content(second))
    previous.awaitResult()
    shadowOf(Looper.getMainLooper()).runOneTask()
    assertThat(testView.findViewWithTagOrNull(second)).isNotNull()
    val runtime = checkNotNull(slot(testView, second).identity.animation)
    assertThat(runtime.currentState).isEqualTo(EnterExitState.BeforeEnter)
    assertThat(runtime.targetState).isEqualTo(EnterExitState.BeforeEnter)
    assertThat(runtime.hasAnimator).isFalse()

    val next = resolveInBackground(tree, content(third))
    next.awaitResult()
    shadowOf(Looper.getMainLooper()).runOneTask()
    lithoTestRule.idle()
    assertThat(runtime.targetState).isEqualTo(EnterExitState.AfterExit)
    assertThat(runtime.currentState).isEqualTo(EnterExitState.BeforeEnter)
    assertThat(runtime.hasAnimator).isFalse()
    assertThat(items(testView)).doesNotContain(second)
    animationRule.advanceBy(2_000.ms)
    assertThat(items(testView)).containsExactly(third)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun repeatedRemovedSlotReports_doNotRequestAResolve() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val transitions = Collections.synchronizedList(mutableListOf<Pair<Item, Item>>())
    val first = Item("A", "First")
    val second = Item("B", "Second")
    val testView = lithoTestRule.render { itemContent(first, renders, transitions) }
    lithoTestRule.idle()
    testView.setRoot(itemContent(second, renders, transitions))
    lithoTestRule.idle()
    val report = slot(testView, first).onSlotSettled
    animationRule.advanceBy(2_000.ms)
    assertThat(items(testView)).containsExactly(second)
    transitions.clear()

    repeat(5) { report() }
    lithoTestRule.idle()

    assertThat(transitions).isEmpty()
    assertThat(items(testView)).containsExactly(second)
  }

  @Test
  fun equalKeys_preserveLatestPayloadAndOutgoingInputs() {
    val keys = listOf(
        "A" to String(charArrayOf('A')),
        null to null,
        CollidingKey(1) to CollidingKey(1),
    )
    for ((key, equalKey) in keys) {
      val original = AnimatedContentState.create(key, "Old")
      val first = original.withTarget(equalKey, "First update")
      val latest = original.withTarget(equalKey, "Latest update")
      val committed = original.acceptCommitted(first).acceptCommitted(latest)
      val next = committed.withTarget(CollidingKey(2), "Other")

      assertThat(committed.slots).hasSize(1)
      assertThat(committed.slots.last().key).isEqualTo(key)
      assertThat(committed.slots.last().state).isEqualTo("Latest update")
      assertThat(committed.withTarget(equalKey, "Latest update")).isSameAs(committed)
      assertThat(next.initialState).isEqualTo("Latest update")
      assertThat(next.slots.map { it.state }).containsExactly("Latest update", "Other")
    }
  }

  @Test
  fun reentry_preservesOtherOutgoingRoles() {
    val original = AnimatedContentState.create("A", "A")
    val first = original.withTarget("B", "B")
    val second = first.withTarget("C", "C")
    val returned = second.withTarget("B", "B updated")

    assertThat(returned.slots.first()).isSameAs(second.slots.first())
    assertThat(returned.slots.last().key).isEqualTo(first.slots.last().key)
    assertThat(returned.initialState).isEqualTo("C")
    assertThat(returned.removeExited(first.slots.first()).slots.map { it.state })
        .containsExactly("C", "B updated")

    val next = returned.withTarget("D", "D")
    assertThat(next.removeExited(first.slots.last())).isSameAs(next)
    assertThat(next.removeExited(returned.slots.last()).slots.map { it.state })
        .containsExactly("A", "C", "D")
  }

  @Test
  fun switchByKey_retainsOutgoingThenReleasesIt() {
    val testView = lithoTestRule.render { content(target = "A") }
    lithoTestRule.idle()
    assertThat(slotCount(testView)).isEqualTo(1)

    testView.setRoot(content(target = "B"))
    lithoTestRule.idle()
    // The outgoing "A" is retained alongside the new target "B" while it animates out.
    assertThat(slotCount(testView)).`as`("outgoing retained on swap").isEqualTo(2)

    // After "A" exit settles, it is released; only "B" remains.
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(slotCount(testView)).`as`("released after settle").isEqualTo(1)
  }

  @Test
  fun reuseSameKey_doesNotAddSlot() {
    val testView = lithoTestRule.render { content(target = "A") }
    lithoTestRule.idle()
    assertThat(slotCount(testView)).isEqualTo(1)

    testView.setRoot(content(target = "A"))
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(slotCount(testView)).isEqualTo(1)
  }

  @Test
  fun equalPayloadInstances_doNotRequestAnotherContainerRender() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val transitions = Collections.synchronizedList(mutableListOf<Pair<Item, Item>>())

    fun content(item: Item, label: String): Component = AnimatedContent(
        targetState = item,
        contentKey = { it.key },
        transitionSpec = {
          transitions.add(initialState to targetState)
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
        label = label,
    ) { state ->
      ItemMarker(state, renders)
    }

    val original = Item("A", "Original")
    val testView = lithoTestRule.render { content(original, "Initial") }
    lithoTestRule.idle()
    val identity = renders.last().identity

    for ((index, item) in listOf(original, Item("A", "Updated")).withIndex()) {
      testView.setRoot(content(item, "Changed-$index"))
      lithoTestRule.idle()
      animationRule.runNextFrame()
      transitions.clear()
      val equal = item.copy()
      assertThat(equal).isEqualTo(item).isNotSameAs(item)

      testView.setRoot(content(equal, "Equal-$index"))
      lithoTestRule.idle()

      assertThat(transitions).hasSize(1)
      assertThat(items(testView)).containsExactly(equal)
      assertThat(renders.last().identity).isSameAs(identity)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      lithoTestRule.idle()
      assertThat(transitions).hasSize(1)
    }
  }

  @Test
  fun identityComparedPayloadUpdates_persistWithoutRestartOrUpdateLoop() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val transitions = Collections.synchronizedList(mutableListOf<Pair<Item, Item>>())
    val old = Item("A", "Old")
    val updated = Item("A", "Updated")
    val next = Item("B", "Other")

    fun content(item: Item): Component = AnimatedContent(
        targetState = IdentityPayload(item),
        contentKey = { it.item.key },
        transitionSpec = {
          transitions.add(initialState.item to targetState.item)
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
    ) { payload ->
      ItemMarker(payload.item, renders)
    }

    val testView = lithoTestRule.render { content(old) }
    lithoTestRule.idle()
    val identity = renders.last().identity
    transitions.clear()
    testView.setRoot(content(updated))
    lithoTestRule.idle()

    assertThat(items(testView)).containsExactly(updated)
    assertThat(renders.last().identity).isSameAs(identity)
    animationRule.runNextFrame()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    assertThat(transitions).hasSize(2)
    lithoTestRule.idle()
    assertThat(transitions).hasSize(2)

    transitions.clear()
    testView.setRoot(content(next))
    lithoTestRule.idle()
    assertThat(transitions).allMatch { it == (updated to next) }
    assertThat(items(testView)).containsExactlyInAnyOrder(updated, next)
    animationRule.advanceBy(2_000.ms)
    assertThat(items(testView)).containsExactly(next)
    val settledRenders = transitions.size
    lithoTestRule.idle()
    assertThat(transitions).hasSize(settledRenders)
  }

  @Test
  fun sameCharacterCountUpdate_preservesProgressAndUsesLatestRollDirection() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val transitions = Collections.synchronizedList(mutableListOf<Pair<Item, Item>>())
    val first = Item('9', "99")
    val second = Item('0', "100")
    val updated = Item('0', "110")
    val next = Item('9', "109")
    val testView = lithoTestRule.render { itemContent(first, renders, transitions) }
    lithoTestRule.idle()
    testView.setRoot(itemContent(second, renders, transitions))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    val identity = renders.last { it.item == second }.identity
    val alpha = itemAlpha(testView, second)
    assertThat(alpha).isNotNull().isGreaterThan(0f).isLessThan(1f)

    testView.setRoot(itemContent(updated, renders, transitions))
    lithoTestRule.idle()
    assertThat(renders.last { it.item == updated }.identity).isSameAs(identity)
    assertThat(itemAlpha(testView, updated)).isEqualTo(alpha)
    assertThat(items(testView)).containsExactlyInAnyOrder(first, updated)

    transitions.clear()
    testView.setRoot(itemContent(next, renders, transitions))
    lithoTestRule.idle()
    assertThat(transitions).isNotEmpty
    assertThat(transitions).allMatch { (initial, target) ->
      initial == updated && target == next && target.title.toInt() < initial.title.toInt()
    }
    animationRule.advanceBy(2_000.ms)
    assertThat(items(testView)).containsExactly(next)
  }

  @Test
  fun sameKeyUpdate_refreshesOutgoingPayloadAndInitialStateWithoutIdling() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val transitions = Collections.synchronizedList(mutableListOf<Pair<Item, Item>>())
    val old = Item(1, "Old")
    val updated = Item(1, "New")
    val next = Item(2, "Other")
    val testView = lithoTestRule.render { itemContent(old, renders, transitions) }
    lithoTestRule.idle()
    val identity = renders.last().identity
    renders.clear()

    testView.setRoot(itemContent(updated, renders, transitions))
    assertThat(renders).isNotEmpty
    assertThat(renders).allMatch { it.item == updated && it.identity === identity }
    assertThat(items(testView)).containsExactly(updated)
    renders.clear()
    transitions.clear()
    testView.setRoot(itemContent(next, renders, transitions))

    assertThat(renders.filter { it.item.key == updated.key }).isNotEmpty
    assertThat(renders.filter { it.item.key == updated.key }).allMatch { it.item == updated }
    assertThat(transitions).isNotEmpty
    assertThat(transitions).allMatch { it == (updated to next) }
    lithoTestRule.idle()
    assertThat(items(testView)).containsExactlyInAnyOrder(updated, next)
  }

  @Test
  fun collidingKeyStrings_preserveSlotStateAcrossReorderAndRelease() {
    val keys = listOf(1 to "1", null to "null", CollidingKey(1) to CollidingKey(2))
    for ((firstKey, secondKey) in keys) {
      val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
      val first = Item(firstKey, "First")
      val second = Item(secondKey, "Second")
      val testView = lithoTestRule.render { itemContent(first, renders) }
      lithoTestRule.idle()
      val firstIdentity = renders.last().identity

      testView.setRoot(itemContent(second, renders))
      lithoTestRule.idle()
      val secondIdentity = renders.last { it.item == second }.identity
      assertThat(secondIdentity).isNotSameAs(firstIdentity)
      assertThat(renders.filter { it.item == first }).allMatch { it.identity === firstIdentity }

      testView.setRoot(itemContent(first, renders))
      lithoTestRule.idle()
      assertThat(renders.filter { it.item == first }).allMatch { it.identity === firstIdentity }
      assertThat(renders.filter { it.item == second }).allMatch { it.identity === secondIdentity }

      animationRule.advanceBy(2_000.ms)
      lithoTestRule.idle()
      assertThat(items(testView)).containsExactly(first)
      assertThat(renders.last().identity).isSameAs(firstIdentity)
    }
  }

  @Test
  fun rapidTargetChanges_preserveDistinctSlotStateWithoutIdling() {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val first = Item(1, "First")
    val second = Item(2, "Second")
    val third = Item(3, "Third")
    val testView = lithoTestRule.render { itemContent(first, renders) }
    lithoTestRule.idle()
    testView.setRoot(itemContent(second, renders))
    testView.setRoot(itemContent(third, renders))
    lithoTestRule.idle()

    val identities =
        listOf(first, second, third).map { item ->
          val itemRenders = renders.filter { it.item == item }
          assertThat(itemRenders).isNotEmpty
          val identity = itemRenders.first().identity
          assertThat(itemRenders).allMatch { it.identity === identity }
          identity
        }
    assertThat(identities.toSet()).hasSize(3)

    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(items(testView)).containsExactly(third)
  }

  @Test
  fun resolveBeforePreviousCommitEffect_preservesContentIdentity() {
    assertConcurrentContentIdentity(secondKey = 2, thirdKey = 3)
  }

  @Test
  fun equalKeyConcurrentResolves_preserveContentIdentity() {
    assertConcurrentContentIdentity(
        secondKey = CollidingKey(1),
        thirdKey = CollidingKey(1),
        overlapResolves = true,
    )
  }

  @Test
  fun nullKeyConcurrentResolves_preserveContentIdentity() {
    assertConcurrentContentIdentity(secondKey = null, thirdKey = null, overlapResolves = true)
  }

  private fun assertConcurrentContentIdentity(
      secondKey: Any?,
      thirdKey: Any?,
      overlapResolves: Boolean = false,
  ) {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val first = Item(1, "First")
    val second = Item(secondKey, "Second")
    val third = Item(thirdKey, "Third")
    val previousResolveStarted = CountDownLatch(1)
    val finishPreviousResolve = CountDownLatch(1)
    val resolveStarted = CountDownLatch(1)
    val finishResolve = CountDownLatch(1)
    val blockOnce = AtomicBoolean(true)

    fun branchingContent(target: Item): Component = AnimatedContent(
        targetState = target,
        contentKey = { it.key },
        transitionSpec = {
          if (target == second && overlapResolves) {
            previousResolveStarted.countDown()
            finishPreviousResolve.awaitSignal()
          }
          if (target == third && blockOnce.compareAndSet(true, false)) {
            resolveStarted.countDown()
            finishResolve.awaitSignal()
          }
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
    ) { item ->
      ItemMarker(item, renders)
    }

    val testView = lithoTestRule.render { branchingContent(first) }
    testView.setSizePx(100, 100).measure().layout()
    lithoTestRule.idle()
    val tree = testView.componentTree
    val previousResolve = resolveInBackground(tree, branchingContent(second))
    if (overlapResolves) previousResolveStarted.awaitSignal() else previousResolve.awaitResult()

    val background = resolveInBackground(tree, branchingContent(third))
    try {
      resolveStarted.awaitSignal()
      finishPreviousResolve.countDown()
      previousResolve.awaitResult()
      // Promote B without draining the state-update task its effect queues.
      shadowOf(Looper.getMainLooper()).runOneTask()
      finishResolve.countDown()
      background.awaitResult()
      testView.measure().layout()
      assertThat(items(testView)).containsExactlyInAnyOrder(first, third)
      val secondIdentity = renders.first { it.item == second }.identity
      val thirdIdentity = renders.first { it.item == third }.identity

      if (secondKey == thirdKey) assertThat(thirdIdentity).isSameAs(secondIdentity)
      else assertThat(thirdIdentity).isNotSameAs(secondIdentity)
      lithoTestRule.idle()
      assertThat(renders.filter { it.item == third }).allMatch { it.identity === thirdIdentity }
      assertThat(items(testView)).doesNotContain(second)
      animationRule.advanceBy(2_000.ms)
      assertThat(items(testView)).containsExactly(third)
    } finally {
      finishPreviousResolve.countDown()
      finishResolve.countDown()
      previousResolve.awaitResult()
      background.awaitResult()
    }
  }

  @Test
  fun completedExit_sameKeyStartsFreshContentEvenWhileOldCallbackIsRetained() {
    assertOldExitReportPreservesReturnedContent(releaseBeforeReentry = true)
  }

  @Test
  fun staleExitReport_afterReentryDoesNotRemoveNewOutgoingRole() {
    assertOldExitReportPreservesReturnedContent(releaseBeforeReentry = false)
  }

  private fun assertOldExitReportPreservesReturnedContent(releaseBeforeReentry: Boolean) {
    val renders = Collections.synchronizedList(mutableListOf<ItemRender>())
    val first = Item(1, "First")
    val second = Item(2, "Second")
    val returned = Item(1, "Updated")
    val testView = lithoTestRule.render { itemContent(first, renders) }
    lithoTestRule.idle()
    val originalIdentity = renders.last().identity
    testView.setRoot(itemContent(second, renders))
    lithoTestRule.idle()
    val oldReport = slot(testView, first).onSlotSettled
    if (releaseBeforeReentry) {
      animationRule.advanceBy(2_000.ms)
      assertThat(items(testView)).containsExactly(second)
    }

    testView.setRoot(itemContent(returned, renders))
    lithoTestRule.idle()
    val returnedIdentity = renders.last { it.item == returned }.identity
    if (releaseBeforeReentry) assertThat(returnedIdentity).isNotSameAs(originalIdentity)
    else assertThat(returnedIdentity).isSameAs(originalIdentity)
    testView.setRoot(itemContent(second, renders))
    lithoTestRule.idle()
    oldReport()
    lithoTestRule.idle()

    assertThat(items(testView)).containsExactlyInAnyOrder(returned, second)
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(items(testView)).containsExactly(second)
  }

  @Test
  fun rapidSwitch_keepsSlotsBoundedThenReleases() {
    val testView = lithoTestRule.render { content(target = "A") }
    lithoTestRule.idle()

    testView.setRoot(content(target = "B"))
    testView.setRoot(content(target = "C"))

    animationRule.advanceBy(500.ms)
    assertThat(slotCount(testView)).isBetween(1, 3)

    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(slotCount(testView)).isEqualTo(1)
  }

  @Test
  fun reorder_returnsToEarlierTarget_settlesWithoutLeak() {
    val testView = lithoTestRule.render { content(target = "A") }
    lithoTestRule.idle()

    testView.setRoot(content(target = "B"))
    lithoTestRule.idle()
    // "A" is exiting at index 0; "B" is the target at index 1.
    assertThat(slotCount(testView)).isEqualTo(2)

    // Return to "A": it reorders from outgoing back to target while "B" now exits. Stable keys keep
    // each slot's identity across the reorder, so no slot is duplicated or stranded.
    testView.setRoot(content(target = "A"))
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(slotCount(testView)).`as`("settles back to the single retargeted slot").isEqualTo(1)
  }

  @Test
  fun container_sizesToTarget_notOutgoing() {
    // "A" is 80 wide, "B" is 120 wide. The container must size to whichever is the *target*.
    val testView = lithoTestRule.render { sizedContent(target = "A") }
    lithoTestRule.idle()
    assertThat(containerWidth(testView)).isEqualTo(80)

    // Mid-transition (outgoing "A" still present), the container already sizes to the target "B"
    // because the target is the in-flow child, not the oldest outgoing slot.
    testView.setRoot(sizedContent(target = "B"))
    lithoTestRule.idle()
    assertThat(containerWidth(testView))
        .`as`("container follows the target, not the outgoing slot")
        .isEqualTo(120)
  }

  @Test
  fun outgoingSlot_exitIsTimeDriven_spansFadeDurationThenReleases() {
    // The exit fade spec is 1_000ms (linearFade). A time-driven exit keeps the outgoing slot
    // mounted across that window and releases it only once it elapses — not an instant snap (the
    // never-shown path) nor a premature release. The alpha ramp of the shared engine is covered
    // by AnimatedVisibilityTest.fadeOut_appliesDecreasingAlpha (same applyEnterExitContent).
    val testView = lithoTestRule.render { content(target = "A") }
    lithoTestRule.idle()

    testView.setRoot(content(target = "B"))

    animationRule.advanceBy(400.ms)
    assertThat(slotCount(testView)).`as`("outgoing still fading early in the window").isEqualTo(2)

    animationRule.advanceBy(400.ms)
    assertThat(slotCount(testView))
        .`as`("outgoing still fading near the end of the window")
        .isEqualTo(2)

    animationRule.advanceBy(400.ms)
    lithoTestRule.idle()
    assertThat(slotCount(testView)).`as`("released once the fade duration elapses").isEqualTo(1)
  }

  @Test
  fun transitionSpec_initialStateStaysStableAfterOutgoingSettles() {
    val seen = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    fun recording(target: String): Component = AnimatedContent(
        targetState = target,
        transitionSpec = {
          seen.add(initialState to targetState)
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
    ) {
      Marker()
    }

    val testView = lithoTestRule.render { recording("A") }
    lithoTestRule.idle()

    testView.setRoot(recording("B"))
    // Let "A" finish exiting and be released, collapsing the container to just "B".
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()

    // The B transition's initialState must remain "A" for B's whole lifetime — even after "A" is
    // gone. If it collapsed to "B" (initialState == targetState) the apparent direction would flip.
    val forTargetB = seen.filter { it.second == "B" }
    assertThat(forTargetB).isNotEmpty
    assertThat(forTargetB).allMatch { it.first == "A" }
  }

  @Test
  fun transitionSpec_seesOutgoingTargetFromFirstRenderOfReversal() {
    val seen = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    fun recording(target: String): Component = AnimatedContent(
        targetState = target,
        transitionSpec = {
          seen.add(initialState to targetState)
          enter = fadeIn(linearFade)
          exit = fadeOut(linearFade)
        },
    ) {
      Marker()
    }

    val testView = lithoTestRule.render { recording("A") }
    lithoTestRule.idle()
    testView.setRoot(recording("B"))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    seen.clear()

    testView.setRoot(recording("A"))
    lithoTestRule.idle()

    assertThat(seen).isNotEmpty
    assertThat(seen).allMatch { it == ("B" to "A") }
  }

  @Test
  fun exitNone_releasesOutgoingImmediately_noStacking() {
    val testView = lithoTestRule.render { noTransitionContent(target = "A") }
    lithoTestRule.idle()
    assertThat(slotCount(testView)).isEqualTo(1)

    testView.setRoot(noTransitionContent(target = "B"))
    lithoTestRule.idle()
    // With no exit animation, the outgoing "A" must be released immediately, not left mounted on
    // top
    // of "B". (An exit with no animated values never fires onSettled, so without an explicit guard
    // it
    // would stay stacked forever.)
    assertThat(slotCount(testView))
        .`as`("no-exit-animation outgoing is released immediately, not stacked")
        .isEqualTo(1)
  }

  @Test
  fun slideIntoContainer_isContainerRelativeWithDirectionalSigns() {
    val scope = AnimatedContentTransitionScope("A", "B")
    with(scope) {
      // Left: enters from the right edge → starts at +full (positive X), measured on the container.
      val left = checkNotNull(slideIntoContainer(SlideDirection.Left).data.slide)
      assertThat(left.relativeToContainer).isTrue
      assertThat(left.offsetX?.invoke(100)).isEqualTo(100)
      assertThat(left.offsetY).isNull()
      // Right: enters from the left → starts at -full.
      assertThat(slideIntoContainer(SlideDirection.Right).data.slide?.offsetX?.invoke(100))
          .isEqualTo(-100)
      // Up: enters from below → +full on Y; X unused.
      val up = checkNotNull(slideIntoContainer(SlideDirection.Up).data.slide)
      assertThat(up.offsetX).isNull()
      assertThat(up.offsetY?.invoke(100)).isEqualTo(100)
      // The offset lambda is applied to the container extent.
      assertThat(
          slideIntoContainer(SlideDirection.Left) { it / 2 }.data.slide?.offsetX?.invoke(100),
      )
          .isEqualTo(50)
    }
  }

  @Test
  fun slideOutOfContainer_endsOnTheTowardsEdge() {
    val scope = AnimatedContentTransitionScope("A", "B")
    with(scope) {
      // Left: exits moving left → ends at -full.
      val left = checkNotNull(slideOutOfContainer(SlideDirection.Left).data.slide)
      assertThat(left.relativeToContainer).isTrue
      assertThat(left.offsetX?.invoke(100)).isEqualTo(-100)
      // Down: exits moving down → +full on Y.
      assertThat(slideOutOfContainer(SlideDirection.Down).data.slide?.offsetY?.invoke(100))
          .isEqualTo(100)
    }
  }

  @Test
  fun containerSlide_swapsAndSettlesWithoutLeak() {
    val testView = lithoTestRule.render { containerSlideContent("A") }
    lithoTestRule.idle()
    assertThat(slotCount(testView)).isEqualTo(1)

    testView.setRoot(containerSlideContent("B"))
    lithoTestRule.idle()
    assertThat(slotCount(testView)).`as`("outgoing retained while it slides out").isEqualTo(2)

    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(slotCount(testView)).`as`("slides settle, outgoing released").isEqualTo(1)
  }

  // --- Helpers ---

  private fun containerSlideContent(target: String): Component = AnimatedContent(
      targetState = target,
      transitionSpec = {
        enter = slideIntoContainer(SlideDirection.Left, linearFade)
        exit = slideOutOfContainer(SlideDirection.Left, linearFade)
      },
  ) {
    Marker()
  }

  private fun noTransitionContent(target: String): Component = AnimatedContent(
      targetState = target,
      // No enter/exit set: both default to None, so the swap is instant with no animation.
      transitionSpec = {},
  ) {
    Marker()
  }

  /**
   * The enter/exit binders are applied to a wrapper around the slot's content, so the tagged view's
   * parent is the one carrying the animated alpha.
   */
  private fun slotAlpha(testView: TestLithoView, state: String): Float? =
      (testView.findViewWithTagOrNull("slot-$state")?.parent as? View)?.alpha

  private fun taggedContent(target: String): Component = AnimatedContent(
      targetState = target,
      transitionSpec = {
        enter = fadeIn(linearFade)
        exit = fadeOut(linearFade)
      },
  ) { state ->
    Row(style = Style.width(50.px).height(50.px).viewTag("slot-$state"))
  }

  @Test
  fun reverseBeforeSettle_returningContentAnimatesInsteadOfSnapping() {
    val testView = lithoTestRule.render { taggedContent("A") }
    lithoTestRule.idle()

    testView.setRoot(taggedContent("B"))
    lithoTestRule.idle()
    animationRule.advanceBy(200.ms)
    val midExit = checkNotNull(slotAlpha(testView, "A"))
    assertThat(midExit).isGreaterThan(0f).isLessThan(1f)

    // Reverse before "A" finishes exiting. Reported from the last settled state the segment
    // collapses to (Visible, Visible), no branch matches, and the enter falls through to snap() —
    // "A" would jump straight back to fully opaque.
    testView.setRoot(taggedContent("A"))
    lithoTestRule.idle()
    animationRule.advanceBy(16.ms)

    assertThat(checkNotNull(slotAlpha(testView, "A"))).isGreaterThan(midExit).isLessThan(1f)
  }

  private fun content(target: String): Component = AnimatedContent(
      targetState = target,
      transitionSpec = {
        enter = fadeIn(linearFade)
        exit = fadeOut(linearFade)
      },
  ) {
    Marker()
  }

  private fun containerWidth(testView: TestLithoView): Int? =
      testView.findViewWithTagOrNull(ROOT_TAG)?.width

  /**
   * An [AnimatedContent] with per-state content widths, wrapped in a [Column] so the container
   * sizes to its in-flow child rather than to the root size spec. The container is tagged for
   * measurement.
   */
  private fun sizedContent(target: String): Component = Column {
    child(
        AnimatedContent(
            targetState = target,
            transitionSpec = {
              enter = fadeIn(linearFade)
              exit = fadeOut(linearFade)
            },
            style = Style.alignSelf(YogaAlign.FLEX_START).viewTag(ROOT_TAG),
        ) { state ->
          Row(style = Style.width(widthForState(state).px).height(50.px))
        },
    )
  }

  private fun widthForState(state: String): Int =
      when (state) {
        "A" -> 80
        "B" -> 120
        else -> 60
      }

  private class Marker : KComponent() {
    override fun ComponentScope.render(): Component = Row(style = Style.width(50.px).height(50.px))
  }

  private fun resolveInBackground(tree: ComponentTree, component: Component): FutureTask<Unit> {
    val sizeSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
    val task = FutureTask {
      tree.setRootAndSizeSpecSync(component, sizeSpec, sizeSpec)
    }
    Thread(task).start()
    return task
  }

  private fun CountDownLatch.awaitSignal() {
    check(await(10, TimeUnit.SECONDS)) { "Timed out waiting for resolve signal" }
  }

  private fun <T> Future<T>.awaitResult(): T = get(10, TimeUnit.SECONDS)

  private fun slot(testView: TestLithoView, item: Item): AnimatedContentSlot<*> =
      testView.findAllComponents(AnimatedContentSlot::class).first {
        it is AnimatedContentSlot<*> && it.state == item
      } as AnimatedContentSlot<*>

  private fun items(testView: TestLithoView): List<Item> =
      testView.findAllComponents(ItemMarker::class).map { (it as ItemMarker).item }

  private data class Item(val key: Any?, val title: String)

  private class IdentityPayload(val item: Item)

  private data class ItemRender(val item: Item, val identity: Any)

  private data class CollidingKey(val id: Int) {
    override fun toString(): String = "same"

    override fun hashCode(): Int = 0
  }

  private class CommitMarker(
      private val item: Item,
      private val isTarget: Boolean,
      private val committedTargets: MutableList<Item>,
      private val renders: MutableList<ItemRender>,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(item, isTarget) {
        if (isTarget) committedTargets.add(item)
        null
      }
      return ItemMarker(item, renders)
    }
  }

  private class ItemMarker(
      val item: Item,
      private val renders: MutableList<ItemRender>,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val identity = useState { Any() }
      renders.add(ItemRender(item, identity.value))
      return Row(style = Style.width(50.px).height(50.px).viewTag(item))
    }
  }

  private fun itemContent(
      target: Item,
      renders: MutableList<ItemRender>,
      transitions: MutableList<Pair<Item, Item>>? = null,
  ): Component = AnimatedContent(
      targetState = target,
      contentKey = { it.key },
      transitionSpec = {
        transitions?.add(initialState to targetState)
        enter = fadeIn(linearFade)
        exit = fadeOut(linearFade)
      },
  ) { item ->
    ItemMarker(item, renders)
  }

  private fun itemAlpha(testView: TestLithoView, item: Item): Float? =
      (testView.findViewWithTagOrNull(item)?.parent as? View)?.alpha

  companion object {
    private const val ROOT_TAG = "ac_root"
  }
}
