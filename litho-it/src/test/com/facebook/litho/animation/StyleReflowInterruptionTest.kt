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

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Looper.getMainLooper
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LayoutTreeFuture
import com.facebook.litho.LifecycleStep.StepInfo
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.TreeFuture
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.ThreadTestingUtils
import com.facebook.litho.testing.TimeOutSemaphore
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.LayoutWithSizeSpecLifecycleTester
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import com.facebook.rendercore.utils.MeasureSpecUtils.unspecified
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Frame-stepped coverage for read-based reflow when other work commits mid-animation. An unrelated
 * state change re-resolves the tree (a new root node), which takes the reflow node off its fast
 * animation-frame path; it must hold its current interpolated size rather than flash the
 * destination for a frame.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class StyleReflowInterruptionTest {

  @Rule
  @JvmField
  val lithoTestRule =
      LithoTestRule(ComponentsConfiguration.defaultInstance.copy(enableStateReadTracking = true))

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var expanded: State<Boolean>? = null
  private var unrelated: State<Int>? = null
  private var neighbor: State<Int>? = null
  private var layoutHeight: Animatable<Dimen>? = null
  private var secondExpanded: State<Boolean>? = null
  private var deferredRenderCount: Int = 0
  private var declaredHeight: State<Dimen>? = null

  @Test
  fun unrelatedCommitMidAnimation_holdsInterpolatedSize_noFlash() {
    val testView = lithoTestRule.render { FlickerComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val growHeight = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height }
    assertThat(growHeight()).isEqualTo(100)

    // Trigger the reflow; the trigger commit pins to the start size (no flash to the target).
    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(100)

    // Advance to the middle of the tween.
    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = growHeight()
    assertThat(mid).isGreaterThan(100)
    assertThat(mid).isLessThan(250)

    // An unrelated commit lands mid-animation (re-resolves the tree). The reflow node must hold its
    // interpolated size, not flash to the destination.
    checkNotNull(unrelated).updateSync { it + 1 }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(mid)

    // The animation still converges afterwards.
    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(250)
  }

  /**
   * The same interruption, but the committing state lives in a SIBLING component rather than the
   * one that declares the reflow. Resolve then reconciles the reflow node by reuse, so the layout
   * serves it from the layout cache and writes no props for it — the path where the node neither
   * re-authors its Yoga size nor re-records its animation subscription.
   */
  @Test
  fun siblingOwnedCommitMidAnimation_holdsInterpolatedSize_andStillConverges() {
    val testView = lithoTestRule.render { NeighborStateComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val growHeight = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height }
    assertThat(growHeight()).isEqualTo(100)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(100)

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = growHeight()
    assertThat(mid).isGreaterThan(100)
    assertThat(mid).isLessThan(250)

    // The reflow node is untouched by this update, so it is reused and cache-served. It must hold
    // its interpolated size rather than jump to the destination or freeze at the cached size.
    checkNotNull(neighbor).updateSync { it + 1 }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(mid)

    // And the subscription must have survived that commit, or no further frame would relayout.
    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(250)
  }

  /**
   * The reflow node sits inside a container that is itself untouched by the committing update, so
   * the container hits the layout cache and its whole Yoga subtree — the reflow node included — is
   * cloned rather than rebuilt. The clone carries the size the animation forced onto that node, so
   * the capture must not mistake it for the node's natural size and retarget the animation to it.
   */
  @Test
  fun cacheServedAncestorMidAnimation_capturesNaturalSize_notTheInterpolatedOne() {
    val testView = lithoTestRule.render { NestedNeighborStateComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val growHeight = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height }
    assertThat(growHeight()).isEqualTo(100)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = growHeight()
    assertThat(mid).isGreaterThan(100)
    assertThat(mid).isLessThan(250)

    // Commits from a sibling; the reflow node's container is clean and served from the cache.
    checkNotNull(neighbor).updateSync { it + 1 }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(mid)

    // The destination is still the declared 250, not the size the commit happened to catch.
    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(250)
  }

  @Test
  fun layoutOnlyHeightChangeDuringWidthReflow_retargetsBothAxes() {
    val testView = lithoTestRule.render { ConcurrentLayoutUpdateComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    assertThat(grow().width).isEqualTo(100)
    assertThat(grow().height).isEqualTo(100)

    // Start a width reflow, then change the same node's declared height through a layout-only value
    // without re-resolving the component tree.
    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val midWidth = grow().width
    assertThat(midWidth).isGreaterThan(100).isLessThan(250)

    checkNotNull(layoutHeight).jumpTo(250.px)
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    // The node must capture 250 as its new natural height and pin this commit to the in-flight
    // size.
    // Treating the whole pass as a reflow frame skips that capture, leaving the original
    // width-only destination in place.
    assertThat(grow().width).isEqualTo(midWidth)
    assertThat(grow().height).isEqualTo(100)

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().height).isGreaterThan(100).isLessThan(250)

    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(grow().width).isEqualTo(250)
    assertThat(grow().height).isEqualTo(250)
  }

  /**
   * The coalesced counterpart of the test above. `snapTo` dispatches outside a frame, so its height
   * change gets a pass of its own; `animateTo` ticks inside the `AnimationController` batch, so the
   * reflow value and the height value land in ONE pass carrying both ids. Matching the reflow id
   * alone treats that as an animation frame and skips the capture the moved height needs.
   */
  @Test
  fun coalescedHeightAnimationDuringWidthReflow_stillCapturesNaturalSize() {
    val testView = lithoTestRule.render { ConcurrentLayoutUpdateComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    assertThat(grow().width).isEqualTo(100)
    assertThat(grow().height).isEqualTo(100)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().width).isGreaterThan(100).isLessThan(250)

    // Both values now tick on the same frame.
    checkNotNull(layoutHeight)
        .startAnimation(250.px, tween(duration = 144.ms, easing = Easing.Linear))
    animationRule.advanceBy(72.ms)
    testView.measure().layout()

    // The capturing frame is itself pinned to the in-flight size, so the retarget onto the moved
    // natural height only becomes visible over the frames after it.
    repeat(6) {
      animationRule.advanceBy(24.ms)
      testView.measure().layout()
    }
    // Still mid-reflow, so no later single-id pass has settled and captured anyway.
    assertThat(grow().width).isLessThan(250)
    assertThat(grow().height).isGreaterThan(100)

    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(grow().width).isEqualTo(250)
    assertThat(grow().height).isEqualTo(250)
  }

  /**
   * The dropped-layout counterpart of the test above. Rn is the n-th resolve and Ln the layout of
   * Rn's result; `h` is the node's declared height, a state read only while laying out.
   * 1. A width reflow is in flight.
   * 2. R2 applies `h = 250px` and commits on the layout thread. L2 is held.
   * 3. The next frame's tick commits R3, whose only update is the reflow value, and L3 lays out on
   *    the main thread while L2 is held.
   * 4. L2 is released and dropped, since R3 is now the committed resolve.
   *
   * L3 must not count as an animation frame for the node: `h` moved its natural size, and L3 is the
   * only pass that sees it before the width settles. Counting it skips the capture, so the height
   * stays put until the reflow ends.
   */
  @Test
  fun droppedLayoutHeightChangeDuringWidthReflow_stillCapturesNaturalSize() {
    val testView = lithoTestRule.render { DroppedLayoutHeightComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    assertThat(grow().width).isEqualTo(100)
    assertThat(grow().height).isEqualTo(100)

    // Step 1. Sync, so that the reflow is in flight before R2 is requested.
    @SuppressLint("KComponentSyncUpdateDetector") checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().width).isGreaterThan(100).isLessThan(250)

    val componentTree = testView.componentTree
    val layout2Held = TimeOutSemaphore(0)
    val releaseLayout2 = TimeOutSemaphore(0)
    val holdLayout2 = AtomicBoolean(true)
    componentTree.setFutureExecutionListener(
        object : TreeFuture.FutureExecutionListener {
          override fun onPreExecution(
              version: Int,
              futureExecutionType: TreeFuture.FutureExecutionType,
              attribution: String,
          ) {
            if (
                attribution == LayoutTreeFuture.DESCRIPTION &&
                    holdLayout2.compareAndSet(true, false)
            ) {
              layout2Held.release()
              releaseLayout2.acquire()
            }
          }

          override fun onPostExecution(version: Int, released: Boolean, attribution: String) = Unit
        },
    )

    // Step 2. `update` batches through the main thread, so R2 is requested on the layout thread
    // directly.
    checkNotNull(declaredHeight).update(250.px)
    componentTree.updateStateInternal(true, "R2")
    val bgLatch = ThreadTestingUtils.runOnBackgroundThread { lithoTestRule.finishAsyncUpdates() }
    layout2Held.acquire()

    // Step 3.
    animationRule.advanceBy(24.ms)
    testView.measure().layout()

    // Step 4.
    releaseLayout2.release()
    bgLatch.acquire()
    componentTree.setFutureExecutionListener(null)

    repeat(6) {
      animationRule.advanceBy(24.ms)
      testView.measure().layout()
    }
    assertThat(grow().width).isLessThan(250)
    assertThat(grow().height)
        .describedAs("L3 captured the moved natural height, so the height retargets mid-reflow")
        .isGreaterThan(100)

    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(grow().width).isEqualTo(250)
    assertThat(grow().height).isEqualTo(250)
  }

  /**
   * Two reflow nodes ticking on the same frame both ride it, so the frame stays one layout pass.
   *
   * Known limitation: if the two are layout-coupled, each animates toward a destination captured
   * before the other moved, correcting only when it settles. Making them capture instead would cost
   * a second `calculateLayout` on every frame that more than one reflow node animates.
   */
  @Test
  fun twoReflowNodesTickingTogether_bothRideTheFrame() {
    val testView = lithoTestRule.render { TwoReflowNodesComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val captured = { checkNotNull(testView.committedLayoutState).reflowTargetSizes?.size ?: 0 }

    checkNotNull(expanded).updateSync { true }
    checkNotNull(secondExpanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(captured()).isZero()
  }

  /**
   * The allocation envelope for the common case: a lone reflow node driving its own frame is not
   * collected, so the pass allocates neither the capture list nor the target map.
   */
  @Test
  fun loneReflowNodeDrivingItsOwnFrame_collectsNothing() {
    val testView = lithoTestRule.render { FlickerComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val captured = { checkNotNull(testView.committedLayoutState).reflowTargetSizes?.size ?: 0 }

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(captured()).isZero()
  }

  /**
   * The cost an idle reflow node carries on someone else's frame: it is collected, but it is the
   * only one — the driving node still rides its frame.
   */
  @Test
  fun idleReflowNodeOnAnotherNodesFrame_isTheOnlyOneCollected() {
    val testView = lithoTestRule.render { TwoReflowNodesComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val captured = { checkNotNull(testView.committedLayoutState).reflowTargetSizes?.size ?: 0 }

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(captured()).isEqualTo(1)
  }

  private inner class FlickerComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      val unrelatedState = useState { 0 }.also { unrelated = it }
      return Column(style = Style.width(200.px)) {
        child(
            Row(
                style =
                    Style.width(200.px)
                        .height(if (expandedState.value) 250.px else 100.px)
                        .backgroundColor(Color.RED)
                        .transition {
                          bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ),
        )
        child(Row(style = Style.width(200.px).height(100.px).backgroundColor(Color.BLUE)))
        // Reads `unrelated` (through the tag), so its update re-resolves the tree without touching
        // the reflow node.
        child(
            Row(
                style =
                    Style.width(200.px).height(20.px).viewTag("unrelated-${unrelatedState.value}"),
            ),
        )
      }
    }
  }

  private inner class DroppedLayoutHeightComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      val height = useState { 100.px }
      declaredHeight = height
      return Column(style = Style.width(300.px)) {
        child(
            Row(
                style =
                    Style.width(if (expandedState.value) 250.px else 100.px)
                        // Not read here: `Style.height(Value)` reads the state while laying out.
                        .height(height)
                        .transition {
                          bounds(tween(duration = 400.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ),
        )
      }
    }
  }

  private inner class ConcurrentLayoutUpdateComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      val animatedHeight =
          useAnimatable(100.px, DimenTypeConverter(resourceResolver)).also { layoutHeight = it }
      return Column(style = Style.width(300.px)) {
        child(
            Row(
                style =
                    Style.width(if (expandedState.value) 250.px else 100.px)
                        .height(animatedHeight.value)
                        .transition {
                          bounds(tween(duration = 400.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ),
        )
      }
    }
  }

  /**
   * A re-measure with new size constraints reuses the same resolve result, so it looks like an
   * animation frame on everything but the constraints. It is a real commit: the node must measure
   * and capture its natural size under the new width and start heading there, instead of riding out
   * the rest of the animation on its pre-change trajectory.
   */
  @Test
  fun sizeConstraintChangeMidAnimation_retargetsToTheNewNaturalWidth() {
    val testView =
        lithoTestRule.render(
            widthSpec = exactly(200),
            heightSpec = unspecified(),
        ) {
          FullWidthComponent()
        }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    assertThat(grow().width).isEqualTo(200)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().height).isGreaterThan(100)

    // Widen mid-animation. The commit itself still holds the in-flight width (no flash), but it
    // must capture 300 as the new natural so the animation is retargeted towards it.
    testView.setSizeSpecs(exactly(300), unspecified())
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(grow().width).isEqualTo(200)

    animationRule.advanceBy(24.ms)
    testView.measure().layout()
    assertThat(grow().width)
        .describedAs("width should be animating towards the new constraint, not stuck at the old")
        .isGreaterThan(200)

    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(grow().width).isEqualTo(300)
    assertThat(grow().height).isEqualTo(250)
  }

  /**
   * The case reflow exists for: a node with no declared height, whose natural size comes from its
   * content. Nothing may re-author such a node's height as anything other than "undeclared", or it
   * stops tracking its content once the animation settles.
   */
  @Test
  fun wrapContentReflow_animatesBetweenContentSizes_andStillTracksContentAfterwards() {
    val testView = lithoTestRule.render { WrapContentComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val growHeight = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height }
    val collapsed = growHeight()
    assertThat(collapsed).isGreaterThan(0)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(collapsed)

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = growHeight()
    assertThat(mid).isGreaterThan(collapsed)

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    val expandedHeight = growHeight()
    assertThat(expandedHeight).isGreaterThan(mid)

    // Settled on content, not on a size frozen into the node. Collapsing must return to exactly
    // the original content height.
    checkNotNull(expanded).updateSync { false }
    shadowOf(getMainLooper()).idle()
    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(collapsed)
  }

  @Test
  fun renderWithConstraintsDescendant_reflowsInsideAndOutsideTheBoundary() {
    val testView = lithoTestRule.render { DeferredReflowWithOuterSiblingComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    val innerSibling = {
      checkNotNull(testView.findViewWithTagOrNull(DEFERRED_SIBLING_TAG))
    }
    val outerSibling = { checkNotNull(testView.findViewWithTagOrNull(OUTER_SIBLING_TAG)) }
    assertThat(grow().height).isEqualTo(100)
    assertThat(innerSibling().top).isEqualTo(100)
    assertThat(outerSibling().top).isEqualTo(200)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(100)
    assertThat(innerSibling().top).isEqualTo(100)
    assertThat(outerSibling().top).isEqualTo(200)

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().height).isGreaterThan(100).isLessThan(250)
    assertThat(innerSibling().top).isEqualTo(grow().height)
    assertThat(outerSibling().top).isEqualTo(grow().height + 100)
    assertThat(checkNotNull(testView.committedLayoutState).reflowTargetSizes).isNull()

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(250)
    assertThat(innerSibling().top).isEqualTo(250)
    assertThat(outerSibling().top).isEqualTo(350)
  }

  @Test
  fun renderWithConstraintsWrapContentDescendant_reflowsAndStillTracksContent() {
    val testView = lithoTestRule.render { DeferredWrapContentComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val growHeight = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height }
    val collapsed = growHeight()
    assertThat(collapsed).isGreaterThan(0)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(collapsed)

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val midpoint = growHeight()
    assertThat(midpoint).isGreaterThan(collapsed)

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(growHeight()).isGreaterThan(midpoint)

    checkNotNull(expanded).updateSync { false }
    shadowOf(getMainLooper()).idle()
    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(collapsed)
  }

  @Test
  fun measuredRenderWithConstraintsDescendant_reflowsWithoutRerendering() {
    val testView = lithoTestRule.render { MeasuredDeferredReflowComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    val sibling = { checkNotNull(testView.findViewWithTagOrNull(DEFERRED_SIBLING_TAG)) }
    assertThat(grow().height).isEqualTo(100)
    assertThat(sibling().top).isEqualTo(100)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(100)
    assertThat(sibling().top).isEqualTo(100)
    val rendersAfterCommit = deferredRenderCount

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().height).isGreaterThan(100).isLessThan(250)
    assertThat(sibling().top).isEqualTo(grow().height)
    assertThat(deferredRenderCount).isEqualTo(rendersAfterCommit)

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(250)
    assertThat(sibling().top).isEqualTo(250)
  }

  @Test
  fun nestedRenderWithConstraintsDescendant_reflowsAcrossBothBoundaries() {
    val testView = lithoTestRule.render { NestedDeferredReflowComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    val innerSibling = {
      checkNotNull(testView.findViewWithTagOrNull(DEFERRED_SIBLING_TAG))
    }
    val outerSibling = { checkNotNull(testView.findViewWithTagOrNull(OUTER_SIBLING_TAG)) }
    assertThat(grow().height).isEqualTo(100)
    assertThat(innerSibling().top).isEqualTo(100)
    assertThat(outerSibling().top).isEqualTo(350)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(100)
    assertThat(innerSibling().top).isEqualTo(100)
    assertThat(outerSibling().top).isEqualTo(350)

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().height).isGreaterThan(100).isLessThan(250)
    assertThat(innerSibling().top).isEqualTo(grow().height)
    assertThat(outerSibling().top).isEqualTo(350)
    assertThat(checkNotNull(testView.committedLayoutState).reflowTargetSizes).isNull()

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(250)
    assertThat(innerSibling().top).isEqualTo(250)
    assertThat(outerSibling().top).isEqualTo(350)
  }

  @Test
  fun onCreateLayoutWithSizeSpecDescendant_reflowsItsSibling() {
    val steps = mutableListOf<StepInfo>()
    val testView = lithoTestRule.render {
      LayoutWithSizeSpecLifecycleTester.create(context)
          .steps(steps)
          .body(SizeSpecDeferredReflowBody())
          .build()
    }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    val sibling = { checkNotNull(testView.findViewWithTagOrNull(DEFERRED_SIBLING_TAG)) }
    assertThat(grow().height).isEqualTo(40)
    assertThat(sibling().top).isEqualTo(40)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(40)
    assertThat(sibling().top).isEqualTo(40)
    val callbacksAfterCommit = steps.size

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().height).isGreaterThan(40).isLessThan(80)
    assertThat(sibling().top).isEqualTo(grow().height)
    assertThat(steps).hasSize(callbacksAfterCommit)

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(80)
    assertThat(sibling().top).isEqualTo(80)
  }

  @Test
  fun renderWithConstraintsDescendant_retargetsFromItsInFlightSize() {
    val testView = lithoTestRule.render { DeferredReflowWithOuterSiblingComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val beforeRetarget = grow().height
    assertThat(beforeRetarget).isGreaterThan(100).isLessThan(250)

    checkNotNull(expanded).updateSync { false }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(beforeRetarget)

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(grow().height).isEqualTo(100)
  }

  /** A state-based animated size continues to reflow a separately measured subtree. */
  @Test
  fun renderWithConstraintsDescendant_stateBasedAnimatedSizeStillReflows() {
    val testView = lithoTestRule.render { DeferredAnimatedDimenComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val grow = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)) }
    val sibling = { checkNotNull(testView.findViewWithTagOrNull(DEFERRED_SIBLING_TAG)) }
    assertThat(grow().height).isEqualTo(100)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    assertThat(grow().height)
        .describedAs("animated height mid-tween")
        .isGreaterThan(100)
        .isLessThan(250)
    assertThat(sibling().top).describedAs("sibling follows").isEqualTo(grow().height)
  }

  /**
   * The cache-served ancestor case for a node with no declared height, where the restore has to
   * hand the dimension back as undeclared rather than pinning it to a value. The node must still
   * settle on its content height, and still track content afterwards.
   */
  @Test
  fun cacheServedAncestorMidAnimation_wrapContentNode_stillSettlesOnContent() {
    val testView = lithoTestRule.render { NestedWrapContentComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val growHeight = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height }
    val collapsed = growHeight()
    assertThat(collapsed).isGreaterThan(0)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = growHeight()
    assertThat(mid).isGreaterThan(collapsed)

    // Commits from a sibling; the reflow node's container is clean and served from the cache.
    checkNotNull(neighbor).updateSync { it + 1 }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(mid)

    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    val expandedHeight = growHeight()
    assertThat(expandedHeight).isGreaterThan(mid)

    // Still content-driven: collapsing returns to exactly the original content height.
    checkNotNull(expanded).updateSync { false }
    shadowOf(getMainLooper()).idle()
    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(collapsed)
  }

  /** [WrapContentComponent] nested beside the state owner, so its container is cache-served. */
  private inner class NestedWrapContentComponent : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.width(200.px)) {
          child(WrapContentComponent())
          // Owns the state, so only this subtree is dirty when it updates.
          child(NeighborComponent())
        }
  }

  private inner class WrapContentComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      return Column(style = Style.width(200.px)) {
        // No declared height: the row is sized by how many children it holds.
        child(
            Column(
                style =
                    Style.width(200.px)
                        .backgroundColor(Color.RED)
                        .transition {
                          bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ) {
              child(Row(style = Style.width(200.px).height(40.px)))
              if (expandedState.value) {
                child(Row(style = Style.width(200.px).height(40.px)))
                child(Row(style = Style.width(200.px).height(40.px)))
              }
            },
        )
        child(Row(style = Style.width(200.px).height(100.px).backgroundColor(Color.BLUE)))
      }
    }
  }

  private inner class DeferredAnimatedDimenComponent : KComponent() {
    override fun ComponentScope.render(): Component = RenderWithConstraints { _ ->
      val expandedState = useState { false }.also { expanded = it }
      val animatedHeight = useAnimatedDimen(
          if (expandedState.value) 250.px else 100.px,
          tween(duration = 144.ms, easing = Easing.Linear),
      )
      Column(style = Style.width(200.px)) {
        child(Row(style = Style.width(200.px).height(animatedHeight).viewTag(GROW_TAG)))
        child(Row(style = Style.width(200.px).height(100.px).viewTag(DEFERRED_SIBLING_TAG)))
      }
    }
  }

  private inner class DeferredReflowWithOuterSiblingComponent : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.width(200.px)) {
          child(
              RenderWithConstraints { _ ->
                val expandedState = useState { false }.also { expanded = it }
                Column(style = Style.width(200.px)) {
                  child(
                      Row(
                          style =
                              Style.width(200.px)
                                  .height(if (expandedState.value) 250.px else 100.px)
                                  .transition {
                                    bounds(
                                        tween(duration = 144.ms, easing = Easing.Linear),
                                        reflow = true,
                                    )
                                  }
                                  .viewTag(GROW_TAG),
                      ),
                  )
                  child(
                      Row(
                          style = Style.width(200.px).height(100.px).viewTag(DEFERRED_SIBLING_TAG),
                      ),
                  )
                }
              },
          )
          child(
              Row(style = Style.width(200.px).height(100.px).viewTag(OUTER_SIBLING_TAG)),
          )
        }
  }

  private inner class DeferredWrapContentComponent : KComponent() {
    override fun ComponentScope.render(): Component = RenderWithConstraints { _ ->
      WrapContentComponent()
    }
  }

  private inner class MeasuredDeferredReflowComponent : KComponent() {
    override fun ComponentScope.render(): Component =
        RenderWithConstraints(style = Style.width(200.px).height(350.px)) { sizeConstraints ->
          deferredRenderCount++
          val expandedState = useState { false }.also { expanded = it }
          val measured =
              Column(style = Style.width(200.px)) {
                child(
                    Row(
                        style =
                            Style.width(200.px)
                                .height(if (expandedState.value) 250.px else 100.px)
                                .transition {
                                  bounds(
                                      tween(duration = 144.ms, easing = Easing.Linear),
                                      reflow = true,
                                  )
                                }
                                .viewTag(GROW_TAG),
                    ),
                )
                child(
                    Row(
                        style = Style.width(200.px).height(100.px).viewTag(DEFERRED_SIBLING_TAG),
                    ),
                )
              }
          measured.measure(sizeConstraints)
          measured
        }
  }

  private inner class NestedDeferredReflowComponent : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.width(200.px)) {
          child(
              RenderWithConstraints(style = Style.width(200.px).height(350.px)) { _ ->
                RenderWithConstraints(style = Style.width(200.px).height(350.px)) { _ ->
                  val expandedState = useState { false }.also { expanded = it }
                  Column(style = Style.width(200.px)) {
                    child(
                        Row(
                            style =
                                Style.width(200.px)
                                    .height(if (expandedState.value) 250.px else 100.px)
                                    .transition {
                                      bounds(
                                          tween(duration = 144.ms, easing = Easing.Linear),
                                          reflow = true,
                                      )
                                    }
                                    .viewTag(GROW_TAG),
                        ),
                    )
                    child(
                        Row(
                            style =
                                Style.width(200.px).height(100.px).viewTag(DEFERRED_SIBLING_TAG),
                        ),
                    )
                  }
                }
              },
          )
          child(Row(style = Style.width(200.px).height(100.px).viewTag(OUTER_SIBLING_TAG)))
        }
  }

  private inner class SizeSpecDeferredReflowBody : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      return Column(style = Style.width(200.px)) {
        child(
            Row(
                style =
                    Style.width(200.px)
                        .height(if (expandedState.value) 80.px else 40.px)
                        .transition {
                          bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ),
        )
        child(
            Row(style = Style.width(200.px).height(10.px).viewTag(DEFERRED_SIBLING_TAG)),
        )
      }
    }
  }

  private inner class FullWidthComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      return Column {
        child(
            Row(
                style =
                    Style.widthPercent(100f)
                        .height(if (expandedState.value) 250.px else 100.px)
                        .backgroundColor(Color.RED)
                        .transition {
                          bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ),
        )
      }
    }
  }

  private inner class NeighborStateComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      return Column(style = Style.width(200.px)) {
        child(
            Row(
                style =
                    Style.width(200.px)
                        .height(if (expandedState.value) 250.px else 100.px)
                        .backgroundColor(Color.RED)
                        .transition {
                          bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ),
        )
        child(Row(style = Style.width(200.px).height(100.px).backgroundColor(Color.BLUE)))
        // Owns the state, so only this subtree is dirty when it updates.
        child(NeighborComponent())
      }
    }
  }

  /** Like [NeighborStateComponent], but the reflow node is nested inside its own container. */
  private inner class NestedNeighborStateComponent : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.width(200.px)) {
          child(ReflowContainerComponent())
          // Owns the state, so only this subtree is dirty when it updates.
          child(NeighborComponent())
        }
  }

  private inner class ReflowContainerComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      return Column {
        child(
            Row(
                style =
                    Style.width(200.px)
                        .height(if (expandedState.value) 250.px else 100.px)
                        .backgroundColor(Color.RED)
                        .transition {
                          bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                        }
                        .viewTag(GROW_TAG),
            ),
        )
      }
    }
  }

  private inner class NeighborComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val count = useState { 0 }.also { neighbor = it }
      return Row(style = Style.width(200.px).height(20.px).viewTag("neighbor-${count.value}"))
    }
  }

  private inner class TwoReflowNodesComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val firstState = useState { false }.also { expanded = it }
      val secondState = useState { false }.also { secondExpanded = it }
      return Column(style = Style.width(300.px)) {
        child(reflowRow(if (firstState.value) 250.px else 100.px, GROW_TAG))
        child(reflowRow(if (secondState.value) 250.px else 100.px, SECOND_GROW_TAG))
      }
    }

    private fun reflowRow(width: Dimen, tag: String): Component = Row(
        style =
            Style.width(width)
                .height(50.px)
                .transition {
                  bounds(tween(duration = 400.ms, easing = Easing.Linear), reflow = true)
                }
                .viewTag(tag),
    )
  }

  companion object {
    private const val GROW_TAG = "interruption_reflow_grow"
    private const val DEFERRED_SIBLING_TAG = "interruption_reflow_deferred_sibling"
    private const val OUTER_SIBLING_TAG = "interruption_reflow_outer_sibling"
    private const val SECOND_GROW_TAG = "interruption_reflow_grow_second"
  }
}
