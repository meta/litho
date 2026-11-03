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

package com.facebook.litho

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import com.facebook.litho.LifecycleStep.StepInfo
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.UnmountOnDetach
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.LithoTestRuleResizeMode
import com.facebook.litho.testing.TestDrawableComponent
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.TestViewComponent
import com.facebook.litho.testing.ViewGroupWithLithoViewChildren
import com.facebook.litho.testing.Whitebox
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.helper.ComponentTestHelper
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.litho.widget.ComponentWrapperTester
import com.facebook.litho.widget.HorizontalScroll
import com.facebook.litho.widget.LayoutSpecLifecycleTester
import com.facebook.litho.widget.LayoutSpecVisibilityEventTester
import com.facebook.litho.widget.MountSpecLifecycleTester
import com.facebook.litho.widget.TextDrawable
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.visibility.FocusedVisibleEvent
import com.facebook.rendercore.visibility.FullImpressionVisibleEvent
import com.facebook.rendercore.visibility.InvisibleEvent
import com.facebook.rendercore.visibility.UnfocusedVisibleEvent
import com.facebook.rendercore.visibility.VisibilityChangedEvent
import com.facebook.rendercore.visibility.VisibilityItem
import com.facebook.rendercore.visibility.VisibilityMountExtension
import com.facebook.rendercore.visibility.VisibilityMountExtension.VisibilityMountExtensionState
import com.facebook.rendercore.visibility.VisibleEvent
import com.facebook.yoga.YogaEdge
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class VisibilityEventsTest {

  @JvmField
  @Rule
  val lithoTestRule: LithoTestRule = LithoTestRule(resizeMode = LithoTestRuleResizeMode.MANUAL)

  @Test
  fun testVisibleEvent() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val testLithoView = lithoTestRule.createTestLithoView(widthPx = 10, heightPx = 5)
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    lithoTestRule.render(lithoView = testLithoView.lithoView, widthPx = 20, heightPx = 20) {
      Column.create(c)
          .child(
              LayoutSpecLifecycleTester.create(c)
                  .steps(steps)
                  .widthPx(10)
                  .heightPx(5)
                  .marginPx(YogaEdge.TOP, 5),
          )
          .build()
    }
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun testNoVisibleEventWhenNotProcessingVisibleOutputs() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val component =
        Column.create(c)
            .child(
                LayoutSpecLifecycleTester.create(c)
                    .steps(steps)
                    .widthPx(10)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 5),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(5))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)

    // invoke notifyVisibleBoundsChanged, but pass 'false' to process visibility outputs.
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 20, 20),
        false,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should still not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
  }

  /**
   * Lays a view out past the bottom of its parent, the state RecyclerView puts an item in to answer
   * a focus search that runs off the edge of a list. Nothing but the root host is mounted.
   */
  private fun layOutOffScreen(steps: MutableList<StepInfo>): TestLithoView {
    val c = lithoTestRule.context
    val component =
        Column.create(c)
            .child(LayoutSpecLifecycleTester.create(c).steps(steps).widthPx(10).heightPx(10))
            .build()

    val parent = FrameLayout(lithoTestRule.context.androidContext)
    parent.measure(exactly(10), exactly(10))
    parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setSizePx(exactly(10), exactly(10))
            .attachToWindow()
            .measure()
            .layout()

    testLithoView.lithoView.translationY = 10f
    parent.addView(testLithoView.lithoView)
    testLithoView.setRoot(component)
    steps.clear()
    return testLithoView
  }

  /** [layOutOffScreen] with the focus search's mount already done. */
  private fun mountOffScreenForFocusSearch(steps: MutableList<StepInfo>): TestLithoView {
    val testLithoView = layOutOffScreen(steps)
    testLithoView.lithoView.addFocusables(ArrayList(), View.FOCUS_DOWN, View.FOCUSABLES_ALL)
    return testLithoView
  }

  /**
   * A probe must not spend the tree's first mount. `hasMounted` gates the root appear transition
   * and `isFirstMount` gates transition collection, and a mount nobody saw is not the display those
   * are waiting for.
   *
   * Committing a root already records a mount for this tree, so the flag is cleared by hand: the
   * case under test is a focus search reaching a view whose first mount is still unspent.
   */
  @Test
  fun testMountingForFocusSearchDoesNotConsumeFirstMount() {
    val c = lithoTestRule.context
    val tracker = LifecycleTracker()
    val component =
        Column.create(c)
            .child(
                MountSpecLifecycleTester.create(c)
                    .widthPx(10)
                    .heightPx(10)
                    .lifecycleTracker(tracker),
            )
            .build()

    val parent = FrameLayout(lithoTestRule.context.androidContext)
    parent.measure(exactly(10), exactly(10))
    parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setSizePx(exactly(10), exactly(10))
            .attachToWindow()
            .measure()
            .layout()

    testLithoView.lithoView.translationY = 10f
    parent.addView(testLithoView.lithoView)
    testLithoView.setRoot(component)
    tracker.reset()

    val mountInfo = requireNotNull(testLithoView.componentTree.uiTreeState).mountInfo
    mountInfo.hasMounted = false

    testLithoView.lithoView.addFocusables(ArrayList(), View.FOCUS_DOWN, View.FOCUSABLES_ALL)

    assertThat(tracker.steps)
        .describedAs("the probe must actually have mounted, or this proves nothing")
        .contains(LifecycleStep.ON_MOUNT)
    assertThat(mountInfo.hasMounted)
        .describedAs("A focus search must not count as the tree's first mount")
        .isFalse
  }

  /**
   * Mount bounds matching the view's full size read downstream as "fully visible and already
   * completely mounted", so a focus search must not record them for content nobody has seen.
   */
  @Test
  fun testMountingForFocusSearchRecordsNoMountBounds() {
    val steps = mutableListOf<StepInfo>()
    val testLithoView = mountOffScreenForFocusSearch(steps)

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Mounting for a focus search must not dispatch a visible event")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(testLithoView.lithoView.previousMountBounds.isEmpty)
        .describedAs("A focus search must not record mount bounds for content nobody has seen")
        .isTrue
  }

  /**
   * Pins the cost of mounting early: the probe consumes the tree's one full mount pass, so becoming
   * visible later goes through incremental mount and never mounts again. Anything the framework
   * only does on a full mount -- notably `TransitionsExtension.beforeMount`, which is what collects
   * appear transitions -- therefore does not get a second chance once a probe has run.
   */
  @Test
  fun testMountingForFocusSearchConsumesTheFullMountPass() {
    val c = lithoTestRule.context
    val tracker = LifecycleTracker()
    val component =
        Column.create(c)
            .child(
                MountSpecLifecycleTester.create(c)
                    .widthPx(10)
                    .heightPx(10)
                    .lifecycleTracker(tracker),
            )
            .build()

    val parent = FrameLayout(lithoTestRule.context.androidContext)
    parent.measure(exactly(10), exactly(10))
    parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setSizePx(exactly(10), exactly(10))
            .attachToWindow()
            .measure()
            .layout()

    testLithoView.lithoView.translationY = 10f
    parent.addView(testLithoView.lithoView)
    testLithoView.setRoot(component)
    tracker.reset()

    testLithoView.lithoView.addFocusables(ArrayList(), View.FOCUS_DOWN, View.FOCUSABLES_ALL)

    assertThat(tracker.steps)
        .describedAs("The probe mounts content the user cannot see")
        .contains(LifecycleStep.ON_MOUNT)

    tracker.reset()
    testLithoView.lithoView.translationY = 0f

    assertThat(tracker.steps)
        .describedAs("Becoming visible does not mount again -- the probe already did it")
        .doesNotContain(LifecycleStep.ON_MOUNT)
  }

  /**
   * The item goes from off-screen to fully on-screen in a single step, with no intervening
   * partially visible frame, as a fling or an instant scroll does. Its impression must still fire.
   */
  @Test
  fun testVisibleEventAfterMountingForFocusSearch() {
    val steps = mutableListOf<StepInfo>()
    val testLithoView = mountOffScreenForFocusSearch(steps)

    testLithoView.lithoView.translationY = 0f

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("The visible event must still be dispatched when the item is really shown")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun testVisibleEventWithHeightRatio() {
    val c = lithoTestRule.context
    val steps = mutableListOf<StepInfo>()
    val component =
        Column.create(c)
            .child(
                LayoutSpecLifecycleTester.create(c)
                    .steps(steps)
                    .visibleHandler(/* visibleWidthRatio */ 0f, /* visibleHeightRatio */ 0.5f, null)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()

    val parent = FrameLayout(lithoTestRule.context.androidContext)
    parent.measure(exactly(10), exactly(1000))
    parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setSizePx(exactly(10), exactly(1000))
            .attachToWindow()
            .measure()
            .layout()

    testLithoView.lithoView.translationY = -6f

    parent.addView(testLithoView.lithoView)
    testLithoView.setRoot(component)

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(
            LifecycleStep.ON_EVENT_VISIBLE,
            LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT,
            LifecycleStep.ON_VISIBILITY_CHANGED,
            LifecycleStep.ON_FOCUSED_EVENT_VISIBLE,
        )

    steps.clear()
    testLithoView.lithoView.translationY = -5f // Boundary condition passes

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible and Change event should be dispatched")
        .containsExactly(
            LifecycleStep.ON_EVENT_VISIBLE,
            LifecycleStep.ON_VISIBILITY_CHANGED,
        )

    steps.clear()
    testLithoView.lithoView.translationY = -4f

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Change event should not dispatched")
        .containsExactly(
            LifecycleStep.ON_VISIBILITY_CHANGED,
        )

    steps.clear()
    testLithoView.lithoView.translationY = 0f

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Full Impression, Focus, and Change event should be dispatched")
        .containsExactly(
            LifecycleStep.ON_FOCUSED_EVENT_VISIBLE,
            LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT,
            LifecycleStep.ON_VISIBILITY_CHANGED,
        )
  }

  @Test
  fun testVisibleEventWithWidthRatio() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val component =
        Column.create(c)
            .child(
                LayoutSpecLifecycleTester.create(c)
                    .steps(steps)
                    .visibleHandler(/* visibleWidthRatio */ 0.4f, /* visibleHeightRatio */ 0f, null)
                    .widthPx(10)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 5),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(5))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 3, 10),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 5, 10),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun testVisibleEventWithHeightAndWidthRatio() {
    val c = lithoTestRule.context
    val steps = mutableListOf<StepInfo>()
    val component =
        Column.create(c)
            .child(
                LayoutSpecLifecycleTester.create(c)
                    .steps(steps)
                    .visibleHandler(
                        /* visibleWidthRatio */ 0.5f, /* visibleHeightRatio */
                        0.5f,
                        null,
                    )
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()

    val parent = FrameLayout(lithoTestRule.context.androidContext)
    parent.measure(exactly(10), exactly(10))
    parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setSizePx(exactly(10), exactly(10))
            .attachToWindow()
            .measure()
            .layout()

    testLithoView.lithoView.translationX = -6f
    testLithoView.lithoView.translationY = -6f

    parent.addView(testLithoView.lithoView)
    testLithoView.setRoot(component)

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(
            LifecycleStep.ON_EVENT_VISIBLE,
            LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT,
            LifecycleStep.ON_VISIBILITY_CHANGED,
            LifecycleStep.ON_FOCUSED_EVENT_VISIBLE,
        )

    steps.clear()
    testLithoView.lithoView.translationY = -5f // 1 Boundary condition does not pass

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visibility events should not be dispatched")
        .isEmpty()

    steps.clear()
    testLithoView.lithoView.translationX = -5f // 2 Boundary condition passes
    testLithoView.lithoView.translationY = -5f

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible and Change event should be dispatched")
        .containsExactly(
            LifecycleStep.ON_EVENT_VISIBLE,
            LifecycleStep.ON_VISIBILITY_CHANGED,
        )

    steps.clear()
    testLithoView.lithoView.translationY = -4f

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Change event should not dispatched")
        .containsExactly(
            LifecycleStep.ON_VISIBILITY_CHANGED,
        )

    steps.clear()
    testLithoView.lithoView.translationX = 0f
    testLithoView.lithoView.translationY = 0f

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Full Impression, Focus, and Change event should be dispatched")
        .containsExactly(
            LifecycleStep.ON_FOCUSED_EVENT_VISIBLE,
            LifecycleStep.ON_VISIBILITY_CHANGED, // because translation x was set to 0
            LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT, // because translation y was set to 0
            LifecycleStep.ON_VISIBILITY_CHANGED,
        )
  }

  @Test
  fun testFocusedOccupiesHalfViewport() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()

    // We should not layout the LithoView at first because the focus event cannot be invoked as the
    // LithoView does not have a parent yet, which is required for FocusedVisible. Consequently, we
    // will not dispatch the focus/unfocus event for a fully visible item for the second time.
    val testLithoView =
        lithoTestRule.createTestLithoView(widthPx = 10, heightPx = 10) {
          LayoutSpecLifecycleTester.create(c).steps(steps).widthPx(10).heightPx(10).build()
        }
    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Focused visible event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
  }

  @Test
  fun testFocusedOccupiesLessThanHalfViewport() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val testLithoView =
        lithoTestRule.createTestLithoView(widthPx = 10, heightPx = 10) {
          LayoutSpecLifecycleTester.create(c).steps(steps).widthPx(10).heightPx(3).build()
        }
    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Focused visible event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
  }

  @Test
  fun testMultipleFocusAndUnfocusEvents() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val focusedHandler = EventHandlerTestUtil.create<FocusedVisibleEvent>(2, content)
    val unfocusedHandler = EventHandlerTestUtil.create<UnfocusedVisibleEvent>(3, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .focusedHandler(focusedHandler)
                    .unfocusedHandler(unfocusedHandler)
                    .widthPx(15)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 8),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 0, 0),
        true,
        VisibilityChangeReason.UNKNOWN,
    )

    // Mount test view in the middle of the view port (focused)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 8, 15, 13),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).containsOnly(focusedHandler)

    // Mount test view on the edge of the viewport (not focused)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 14, 15, 19),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).containsOnly(unfocusedHandler)

    // Mount test view in the middle of the view port (focused)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 8, 15, 14),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).containsOnly(focusedHandler)

    // Mount test view on the edge of the viewport (not focused)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 1, 15, 6),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).containsOnly(unfocusedHandler)
  }

  @Test
  fun testFullImpressionEvent() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val component =
        LayoutSpecLifecycleTester.create(c)
            .steps(steps)
            .widthPx(10)
            .heightPx(5)
            .marginPx(YogaEdge.TOP, 5)
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Full Impression visible event should be dispatched")
        .contains(LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT)
  }

  @Test
  fun testVisibility1fTop() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val component =
        LayoutSpecLifecycleTester.create(c)
            .steps(steps)
            .visibleHandler(/* visibleWidthRatio */ 0f, /* visibleHeightRatio */ 1f, null)
            .widthPx(10)
            .heightPx(5)
            .marginPx(YogaEdge.TOP, 5)
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun testVisibility1fBottom() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val component =
        LayoutSpecLifecycleTester.create(c)
            .steps(steps)
            .visibleHandler(/* visibleWidthRatio */ 0f, /* visibleHeightRatio */ 1f, null)
            .widthPx(10)
            .heightPx(5)
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun testInvisibleEvent() {
    val c = lithoTestRule.context
    val steps: List<StepInfo> = mutableListOf()
    val component =
        Column.create(c)
            .child(
                LayoutSpecLifecycleTester.create(c)
                    .steps(steps)
                    .widthPx(10)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 5),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
  }

  @Test
  fun testVisibleRectChangedEventItemVisible() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibilityChangedHandler = EventHandlerTestUtil.create<VisibilityChangedEvent>(3, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibilityChangedHandler(visibilityChangedHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    var visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(10)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(10)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(100f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(100f)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 4),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).contains(visibilityChangedHandler)
    visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(4)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(10)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(40f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(100f)
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).contains(visibilityChangedHandler)
    visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(5)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(10)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(50f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(100f)
  }

  @Test
  fun testVisibleRectChangedEventItemNotVisible() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibilityChangedHandler = EventHandlerTestUtil.create<VisibilityChangedEvent>(3, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibilityChangedHandler(visibilityChangedHandler)
                    .widthPx(10)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 5),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).contains(visibilityChangedHandler)
    val visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(0)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(0)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(0.0f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(0.0f)
  }

  @Test
  fun whenItemIsFullyVisible_VisibleTopAndLeftShouldBe0() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibilityChangedHandler = EventHandlerTestUtil.create<VisibilityChangedEvent>(3, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibilityChangedHandler(visibilityChangedHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(1_000))
            .measure()
            .layout()
    val visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleTop).isEqualTo(0)
    assertThat(visibilityChangedEvent?.visibleLeft).isEqualTo(0)
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(10)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(10)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(100f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(100f)
  }

  @Test
  fun testVisibleRectChangedEventLargeView() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibilityChangedHandler = EventHandlerTestUtil.create<VisibilityChangedEvent>(3, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibilityChangedHandler(visibilityChangedHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(1_000))
            .measure()
            .layout()
    var visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleTop).isEqualTo(0)
    assertThat(visibilityChangedEvent?.visibleLeft).isEqualTo(0)
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(10)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(10)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(100f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(100f)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 4),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).contains(visibilityChangedHandler)
    visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(4)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(10)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(40f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(100f)
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).contains(visibilityChangedHandler)
    visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(5)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(10)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(50f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(100f)
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(5, 5, 10, 10),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).contains(visibilityChangedHandler)
    visibilityChangedEvent =
        content.getEventState(visibilityChangedHandler) as VisibilityChangedEvent?
    assertThat(visibilityChangedEvent?.visibleTop).isEqualTo(5)
    assertThat(visibilityChangedEvent?.visibleLeft).isEqualTo(5)
    assertThat(visibilityChangedEvent?.visibleHeight).isEqualTo(5)
    assertThat(visibilityChangedEvent?.visibleWidth).isEqualTo(5)
    assertThat(visibilityChangedEvent?.percentVisibleHeight).isEqualTo(50f)
    assertThat(visibilityChangedEvent?.percentVisibleWidth).isEqualTo(50f)
  }

  @Test
  fun testVisibleAndInvisibleEvents() {
    val c = lithoTestRule.context
    val steps: MutableList<StepInfo> = mutableListOf()
    val component =
        Column.create(c)
            .child(
                LayoutSpecLifecycleTester.create(c)
                    .steps(steps)
                    .widthPx(10)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 5),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Invisible event should be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    steps.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 3, 15, 9),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 10, 15, 15),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun testMultipleVisibleEvents() {
    val c = lithoTestRule.context
    val steps1: MutableList<StepInfo> = mutableListOf()
    val steps2: MutableList<StepInfo> = mutableListOf()
    val steps3: MutableList<StepInfo> = mutableListOf()
    val component1 =
        LayoutSpecLifecycleTester.create(c).steps(steps1).widthPx(10).heightPx(5).build()
    val component2 =
        LayoutSpecLifecycleTester.create(c).steps(steps2).widthPx(10).heightPx(5).build()
    val component3 =
        LayoutSpecLifecycleTester.create(c).steps(steps3).widthPx(10).heightPx(5).build()
    val root =
        Column.create(c).key("root").child(component1).child(component2).child(component3).build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_VISIBILITY_CHANGED)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 0),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_VISIBILITY_CHANGED)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 3),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_VISIBILITY_CHANGED)
    steps1.clear()
    steps2.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 3, 15, 11),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_VISIBILITY_CHANGED)
  }

  @Test
  fun testMultipleVisibleAndInvisibleEvents() {
    val c = lithoTestRule.context
    val steps1: MutableList<StepInfo> = mutableListOf()
    val steps2: MutableList<StepInfo> = mutableListOf()
    val steps3: MutableList<StepInfo> = mutableListOf()
    val component1 =
        LayoutSpecLifecycleTester.create(c).steps(steps1).widthPx(10).heightPx(5).build()
    val component2 =
        LayoutSpecLifecycleTester.create(c).steps(steps2).widthPx(10).heightPx(5).build()
    val component3 =
        LayoutSpecLifecycleTester.create(c).steps(steps3).widthPx(10).heightPx(5).build()
    val root =
        Column.create(c).key("root").child(component1).child(component2).child(component3).build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(15), exactly(15))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 15),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 0),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 0),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 3),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 3, 15, 11),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 5, 15, 11),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
  }

  @Test
  fun testSkipFullyVisible() {
    val c = lithoTestRule.context
    val steps1: MutableList<StepInfo> = mutableListOf()
    val steps2: MutableList<StepInfo> = mutableListOf()
    val steps3: MutableList<StepInfo> = mutableListOf()
    val component1 =
        LayoutSpecLifecycleTester.create(c).steps(steps1).widthPx(10).heightPx(5).build()
    val component2 =
        LayoutSpecLifecycleTester.create(c).steps(steps2).widthPx(10).heightPx(5).build()
    val component3 =
        LayoutSpecLifecycleTester.create(c).steps(steps3).widthPx(10).heightPx(5).build()
    val root =
        Column.create(c).key("root").child(component1).child(component2).child(component3).build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(15), exactly(15))
            .measure()
            .layout()
    var visibilityItemMap: Map<Any, VisibilityItem>? =
        getCurrentVisibilityToItemMap(testLithoView.lithoView)
    visibilityItemMap?.keys?.forEach { key ->
      val item = visibilityItemMap?.get(key)
      assertThat(item?.wasFullyVisible()).isTrue
    }
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 15),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    //
    visibilityItemMap = getCurrentVisibilityToItemMap(testLithoView.lithoView)
    assertThat(visibilityItemMap?.size).isEqualTo(3)
    visibilityItemMap?.keys?.forEach { key ->
      val item = visibilityItemMap?.get(key)
      assertThat(item?.wasFullyVisible()).isTrue
    }
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 3, 15, 12),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    visibilityItemMap = getCurrentVisibilityToItemMap(testLithoView.lithoView)
    assertThat(visibilityItemMap?.size).isEqualTo(3)
    var fullyVisibleCount = 0
    visibilityItemMap?.keys?.forEach { key ->
      val item = visibilityItemMap?.get(key)
      fullyVisibleCount += if (item?.wasFullyVisible() == true) 1 else 0
    }
    assertThat(fullyVisibleCount).isEqualTo(1)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 0),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    visibilityItemMap = getCurrentVisibilityToItemMap(testLithoView.lithoView)
    assertThat(visibilityItemMap?.size).isEqualTo(0)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 0),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 3, 15, 12),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    visibilityItemMap = getCurrentVisibilityToItemMap(testLithoView.lithoView)
    assertThat(visibilityItemMap?.size).isEqualTo(3)
    fullyVisibleCount = 0
    visibilityItemMap?.keys?.forEach { key ->
      val item = visibilityItemMap?.get(key)
      fullyVisibleCount += if (item?.wasFullyVisible() == true) 1 else 0
    }
    assertThat(fullyVisibleCount).isEqualTo(1)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 15),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Invisible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_INVISIBLE)
    visibilityItemMap = getCurrentVisibilityToItemMap(testLithoView.lithoView)
    assertThat(visibilityItemMap?.size).isEqualTo(3)
    visibilityItemMap?.keys?.forEach { key ->
      val item = visibilityItemMap?.get(key)
      assertThat(item?.wasFullyVisible()).isTrue
    }
  }

  @Test
  fun testDispatchFocusedHandler() {
    val c = lithoTestRule.context
    val steps1: MutableList<StepInfo> = mutableListOf()
    val steps2: MutableList<StepInfo> = mutableListOf()
    val steps3: MutableList<StepInfo> = mutableListOf()
    val component1 =
        LayoutSpecLifecycleTester.create(c).steps(steps1).widthPx(10).heightPx(5).build()
    val component2 =
        LayoutSpecLifecycleTester.create(c).steps(steps2).widthPx(10).heightPx(5).build()
    val component3 =
        LayoutSpecLifecycleTester.create(c).steps(steps3).widthPx(10).heightPx(5).build()

    val testLithoView =
        lithoTestRule.createTestLithoView(widthPx = 10, heightPx = 15) {
          Column.create(c).key("root").child(component1).child(component2).child(component3).build()
        }

    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    val visibilityItemLongSparseArray: Map<Any, VisibilityItem>? =
        getCurrentVisibilityToItemMap(testLithoView.lithoView)
    visibilityItemLongSparseArray?.keys?.forEach { key ->
      val item = visibilityItemLongSparseArray?.get(key)
      assertThat(item?.wasFullyVisible()).isTrue
    }
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Focused visible event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Focused visible event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Focused visible event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 4, 15, 15),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Focused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Focused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Focused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Unfocused visible event should be dispatched")
        .contains(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    steps1.clear()
    steps2.clear()
    steps3.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 15),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Focused visible event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Focused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Focused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps1))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps2))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(steps3))
        .describedAs("Unfocused visible event should not be dispatched")
        .doesNotContain(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
  }

  @Test
  fun testDetachWithReleasedTreeTriggersInvisibilityItems() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val invisibleEventHandler = EventHandlerTestUtil.create<InvisibleEvent>(2, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .invisibleHandler(invisibleEventHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 10),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    testLithoView.lithoView.release()
    assertThat(content.dispatchedEventHandlers).contains(invisibleEventHandler)
  }

  @Test
  fun testSetComponentWithDifferentKeyGeneratesVisibilityEvents() {
    val context = lithoTestRule.context
    val component1 = TestViewComponent.create(context).key("component1").build()
    val visibleEventHandler1 = EventHandlerTestUtil.create<VisibleEvent>(1, component1)
    val invisibleEventHandler1 = EventHandlerTestUtil.create<InvisibleEvent>(2, component1)
    val focusedEventHandler1 = EventHandlerTestUtil.create<FocusedVisibleEvent>(3, component1)
    val unfocusedEventHandler1 = EventHandlerTestUtil.create<UnfocusedVisibleEvent>(4, component1)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(component1)
                    .visibleHandler(visibleEventHandler1)
                    .invisibleHandler(invisibleEventHandler1)
                    .focusedHandler(focusedEventHandler1)
                    .unfocusedHandler(unfocusedEventHandler1)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView = lithoTestRule.createTestLithoView(widthPx = 100, heightPx = 100) { root }
    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    assertThat(component1.dispatchedEventHandlers).contains(visibleEventHandler1)
    assertThat(component1.dispatchedEventHandlers).contains(focusedEventHandler1)
    val component2 = TestViewComponent.create(context).key("component2").build()
    val visibleEventHandler2 = EventHandlerTestUtil.create<VisibleEvent>(3, component2)
    val newRoot =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(component2)
                    .visibleHandler(visibleEventHandler2)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    testLithoView.setRoot(newRoot)
    ComponentTestHelper.measureAndLayout(testLithoView.lithoView)
    assertThat(component1.dispatchedEventHandlers).contains(invisibleEventHandler1)
    assertThat(component1.dispatchedEventHandlers).contains(unfocusedEventHandler1)
    assertThat(component2.dispatchedEventHandlers).contains(visibleEventHandler2)
  }

  @Test
  fun testSetDifferentComponentTreeWithSameKeysStillCallsInvisibleAndVisibleEvents() {
    val context = lithoTestRule.context
    val firstComponent = TestViewComponent.create(context).build()
    val secondComponent = TestViewComponent.create(context).build()
    val visibleEventHandler1 = EventHandlerTestUtil.create<VisibleEvent>(1, firstComponent)
    val invisibleEventHandler1 = EventHandlerTestUtil.create<InvisibleEvent>(2, firstComponent)
    val visibleEventHandler2 = EventHandlerTestUtil.create<VisibleEvent>(1, secondComponent)
    val invisibleEventHandler2 = EventHandlerTestUtil.create<InvisibleEvent>(2, secondComponent)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(firstComponent)
                    .visibleHandler(visibleEventHandler1)
                    .invisibleHandler(invisibleEventHandler1)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    assertThat(firstComponent.dispatchedEventHandlers).containsExactly(visibleEventHandler1)
    firstComponent.dispatchedEventHandlers.clear()
    val newRoot =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(secondComponent)
                    .visibleHandler(visibleEventHandler2)
                    .invisibleHandler(invisibleEventHandler2)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    testLithoView
        .useComponentTree(ComponentTree.create(context).build())
        .setRoot(newRoot)
        .attachToWindow()
        .setSizeSpecs(exactly(100), exactly(100))
        .measure()
        .layout()
    assertThat(firstComponent.dispatchedEventHandlers).containsExactly(invisibleEventHandler1)
    assertThat(secondComponent.dispatchedEventHandlers).containsExactly(visibleEventHandler2)
  }

  @Test
  fun testSetComponentTreeToNullDispatchesInvisibilityEvents() {
    val context = lithoTestRule.context
    val component = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(1, component)
    val invisibleEventHandler = EventHandlerTestUtil.create<InvisibleEvent>(2, component)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(component)
                    .visibleHandler(visibleEventHandler)
                    .invisibleHandler(invisibleEventHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    assertThat(component.dispatchedEventHandlers).containsExactly(visibleEventHandler)
    component.dispatchedEventHandlers.clear()
    testLithoView.useComponentTree(null)
    assertThat(component.dispatchedEventHandlers).containsExactly(invisibleEventHandler)
  }

  @Test
  fun testTransientStateDoesNotTriggerVisibilityEvents() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(2, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibleHandler(visibleEventHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, -10, 10, -5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.setHasTransientState(true)
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
    testLithoView.lithoView.setMountStateDirty()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, -10, 10, -5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
    testLithoView.lithoView.setHasTransientState(false)
    assertThat(content.dispatchedEventHandlers).contains(visibleEventHandler)
  }

  @Test
  fun visibilityOutputs_setTransientStateFalse_parentInTransientState_processVisibilityOutputs() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(2, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibleHandler(visibleEventHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()

    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .useComponentTree(ComponentTree.create(context).build())
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)
    val view = testLithoView.lithoView.parent as View
    view.setHasTransientState(true)
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, -10, 10, -5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.setHasTransientState(true)
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
    testLithoView.lithoView.setMountStateDirty()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, -10, 10, -5),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
    testLithoView.lithoView.setHasTransientState(false)
    assertThat(content.dispatchedEventHandlers).contains(visibleEventHandler)
    view.setHasTransientState(false)
  }

  @Test
  fun testRemovingComponentTriggersInvisible() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(1, content)
    val invisibleEventHandler = EventHandlerTestUtil.create<InvisibleEvent>(2, content)
    val wrappedContent =
        Wrapper.create(context)
            .delegate(content)
            .widthPx(10)
            .heightPx(5)
            .visibleHandler(visibleEventHandler)
            .invisibleHandler(invisibleEventHandler)
            .build()
    val root = Column.create(context).child(wrappedContent).build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .useComponentTree(ComponentTree.create(context).build())
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(content.dispatchedEventHandlers).contains(visibleEventHandler)
    assertThat(content.dispatchedEventHandlers).doesNotContain(invisibleEventHandler)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.setComponent(Column.create(context).build())
    assertThat(content.dispatchedEventHandlers).contains(invisibleEventHandler)
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
    content.dispatchedEventHandlers.clear()
    testLithoView.lithoView.setComponent(Column.create(context).child(wrappedContent).build())
    assertThat(content.dispatchedEventHandlers).contains(visibleEventHandler)
    assertThat(content.dispatchedEventHandlers).doesNotContain(invisibleEventHandler)
  }

  @Test
  fun testMultipleVisibilityEventsOnSameNode() {
    val context = lithoTestRule.context
    val lifecycleTracker1 = LifecycleTracker()
    val lifecycleTracker2 = LifecycleTracker()
    val lifecycleTracker3 = LifecycleTracker()
    val content1 = ComponentWrapperTester(
        content = Column.create(context).build(),
        lifecycleTracker = lifecycleTracker1,
    )
    val content2 = ComponentWrapperTester(
        content = content1,
        lifecycleTracker = lifecycleTracker2,
    )
    val content3 = ComponentWrapperTester(
        content = content2,
        lifecycleTracker = lifecycleTracker3,
    )
    val testLithoView = lithoTestRule.createTestLithoView(widthPx = 10, heightPx = 10) { content3 }
    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    assertThat(lifecycleTracker1.steps)
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(lifecycleTracker2.steps)
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(lifecycleTracker3.steps)
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(lifecycleTracker1.steps)
        .describedAs("Focus event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(lifecycleTracker2.steps)
        .describedAs("Focus event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(lifecycleTracker3.steps)
        .describedAs("Focus event should be dispatched")
        .contains(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
    assertThat(lifecycleTracker1.steps)
        .describedAs("FullImpressionVisible event should be dispatched")
        .contains(LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT)
    assertThat(lifecycleTracker2.steps)
        .describedAs("FullImpressionVisible event should be dispatched")
        .contains(LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT)
    assertThat(lifecycleTracker3.steps)
        .describedAs("FullImpressionVisible event should be dispatched")
        .contains(LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT)
    lifecycleTracker1.reset()
    lifecycleTracker2.reset()
    lifecycleTracker3.reset()
    testLithoView.lithoView.unbind()
    assertThat(lifecycleTracker1.steps)
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(lifecycleTracker2.steps)
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(lifecycleTracker3.steps)
        .describedAs("Invisible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_INVISIBLE)
    assertThat(lifecycleTracker1.steps)
        .describedAs("Unfocus event should be dispatched")
        .contains(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(lifecycleTracker2.steps)
        .describedAs("Unfocus event should be dispatched")
        .contains(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
    assertThat(lifecycleTracker3.steps)
        .describedAs("Unfocus event should be dispatched")
        .contains(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
  }

  @Test
  fun testSetVisibilityHint() {
    val context = lithoTestRule.context
    val component = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(1, component)
    val invisibleEventHandler = EventHandlerTestUtil.create<InvisibleEvent>(2, component)
    val focusedEventHandler = EventHandlerTestUtil.create<FocusedVisibleEvent>(3, component)
    val unfocusedEventHandler = EventHandlerTestUtil.create<UnfocusedVisibleEvent>(4, component)
    val fullImpressionHandler =
        EventHandlerTestUtil.create<FullImpressionVisibleEvent>(5, component)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(component)
                    .visibleHandler(visibleEventHandler)
                    .invisibleHandler(invisibleEventHandler)
                    .focusedHandler(focusedEventHandler)
                    .unfocusedHandler(unfocusedEventHandler)
                    .fullImpressionHandler(fullImpressionHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView = lithoTestRule.createTestLithoView(widthPx = 100, heightPx = 100) { root }
    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    assertThat(component.dispatchedEventHandlers).contains(visibleEventHandler)
    assertThat(component.dispatchedEventHandlers).contains(focusedEventHandler)
    assertThat(component.dispatchedEventHandlers).contains(fullImpressionHandler)
    component.dispatchedEventHandlers.clear()
    testLithoView.lithoView.setVisibilityHint(false, false)
    assertThat(component.dispatchedEventHandlers).contains(invisibleEventHandler)
    assertThat(component.dispatchedEventHandlers).contains(unfocusedEventHandler)
    component.dispatchedEventHandlers.clear()
    if (ComponentsConfiguration.defaultInstance.unmountOnDetach == UnmountOnDetach.Never) {
      testLithoView.lithoView.setVisibilityHint(true, false)
      assertThat(component.dispatchedEventHandlers).contains(visibleEventHandler)
      assertThat(component.dispatchedEventHandlers).contains(focusedEventHandler)
      assertThat(component.dispatchedEventHandlers).contains(fullImpressionHandler)
    }
  }

  @Test
  fun testSetVisibilityHintRecursive() {
    val context = lithoTestRule.context
    val testComponentInner = TestDrawableComponent.create(context).build()
    val visibleEventHandlerInner = EventHandlerTestUtil.create<VisibleEvent>(1, testComponentInner)
    val invisibleEventHandlerInner =
        EventHandlerTestUtil.create<InvisibleEvent>(2, testComponentInner)
    val mountedTestComponentInner =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(testComponentInner)
                    .visibleHandler(visibleEventHandlerInner)
                    .invisibleHandler(invisibleEventHandlerInner)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .useComponentTree(ComponentTree.create(context).build())
            .setRoot(mountedTestComponentInner)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    val child = ComponentTestHelper.mountComponent(context, mountedTestComponentInner, true, true)
    assertThat(testComponentInner.dispatchedEventHandlers.size).isEqualTo(1)
    assertThat(testComponentInner.dispatchedEventHandlers.contains(visibleEventHandlerInner))
        .isTrue()
    testComponentInner.dispatchedEventHandlers.clear()
    val viewGroup = ViewGroupWithLithoViewChildren(context.androidContext)
    val parent = child.parent as ViewGroup
    parent.removeView(child)
    viewGroup.addView(child)
    val parentView =
        ComponentTestHelper.mountComponent(
            context,
            TestViewComponent.create(context).testView(viewGroup).build(),
            true,
            true,
        )
    parentView.setVisibilityHint(false, false)
    assertThat(testComponentInner.dispatchedEventHandlers.size).isEqualTo(1)
    assertThat(testComponentInner.dispatchedEventHandlers.contains(invisibleEventHandlerInner))
        .isTrue()
    testComponentInner.dispatchedEventHandlers.clear()
    parentView.setVisibilityHint(true, false)
    assertThat(testComponentInner.dispatchedEventHandlers.size).isEqualTo(1)
    assertThat(testComponentInner.dispatchedEventHandlers.contains(visibleEventHandlerInner))
        .isTrue()
  }

  @Test
  fun testMultipleVisibleEventsIncrementalMountDisabled() {
    val context = lithoTestRule.context
    val content1 = TestViewComponent.create(context).build()
    val content2 = TestViewComponent.create(context).build()
    val content3 = TestViewComponent.create(context).build()
    val visibleEventHandler1 = EventHandlerTestUtil.create<VisibleEvent>(1, content1)
    val visibleEventHandler2 = EventHandlerTestUtil.create<VisibleEvent>(2, content2)
    val visibleEventHandler3 = EventHandlerTestUtil.create<VisibleEvent>(3, content3)
    val visibilityChangedHandler = EventHandlerTestUtil.create<VisibilityChangedEvent>(4, content3)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content1)
                    .visibleHandler(visibleEventHandler1)
                    .widthPx(10)
                    .heightPx(5),
            )
            .child(
                Wrapper.create(context)
                    .delegate(content2)
                    .visibleHandler(visibleEventHandler2)
                    .widthPx(10)
                    .heightPx(5),
            )
            .child(
                Wrapper.create(context)
                    .delegate(content3)
                    .visibleHandler(visibleEventHandler3)
                    .visibilityChangedHandler(visibilityChangedHandler)
                    .widthPx(10)
                    .heightPx(5),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .useComponentTree(
                ComponentTree.create(context).incrementalMount(false).build(),
            )
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(content1.dispatchedEventHandlers).contains(visibleEventHandler1)
    assertThat(content2.dispatchedEventHandlers).contains(visibleEventHandler2)
    assertThat(content3.dispatchedEventHandlers).contains(visibleEventHandler3)
    assertThat(content3.dispatchedEventHandlers).contains(visibilityChangedHandler)
    content1.dispatchedEventHandlers.clear()
    content2.dispatchedEventHandlers.clear()
    content3.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 0),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content1.dispatchedEventHandlers).doesNotContain(visibleEventHandler1)
    assertThat(content2.dispatchedEventHandlers).doesNotContain(visibleEventHandler2)
    assertThat(content3.dispatchedEventHandlers).doesNotContain(visibleEventHandler3)
    assertThat(content3.dispatchedEventHandlers).contains(visibilityChangedHandler)
    content1.dispatchedEventHandlers.clear()
    content2.dispatchedEventHandlers.clear()
    content3.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 0, 15, 3),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content1.dispatchedEventHandlers).contains(visibleEventHandler1)
    assertThat(content2.dispatchedEventHandlers).doesNotContain(visibleEventHandler2)
    assertThat(content3.dispatchedEventHandlers).doesNotContain(visibleEventHandler3)
    assertThat(content3.dispatchedEventHandlers).doesNotContain(visibilityChangedHandler)
    content1.dispatchedEventHandlers.clear()
    content2.dispatchedEventHandlers.clear()
    testLithoView.lithoView.notifyVisibleBoundsChanged(
        Rect(0, 3, 15, 11),
        true,
        VisibilityChangeReason.UNKNOWN,
    )
    assertThat(content1.dispatchedEventHandlers).doesNotContain(visibleEventHandler1)
    assertThat(content2.dispatchedEventHandlers).contains(visibleEventHandler2)
    assertThat(content3.dispatchedEventHandlers).contains(visibleEventHandler3)
    assertThat(content3.dispatchedEventHandlers).contains(visibilityChangedHandler)
  }

  @Test
  fun testSetVisibilityHintIncrementalMountDisabled() {
    val context = lithoTestRule.context
    val component = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(1, component)
    val invisibleEventHandler = EventHandlerTestUtil.create<InvisibleEvent>(2, component)
    val focusedEventHandler = EventHandlerTestUtil.create<FocusedVisibleEvent>(3, component)
    val unfocusedEventHandler = EventHandlerTestUtil.create<UnfocusedVisibleEvent>(4, component)
    val fullImpressionHandler =
        EventHandlerTestUtil.create<FullImpressionVisibleEvent>(5, component)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(component)
                    .visibleHandler(visibleEventHandler)
                    .invisibleHandler(invisibleEventHandler)
                    .focusedHandler(focusedEventHandler)
                    .unfocusedHandler(unfocusedEventHandler)
                    .fullImpressionHandler(fullImpressionHandler)
                    .widthPx(10)
                    .heightPx(10),
            )
            .build()
    val testLithoView =
        lithoTestRule.createTestLithoView(
            componentTree = ComponentTree.create(context).incrementalMount(false).build(),
            widthPx = 100,
            heightPx = 100,
        ) {
          root
        }
    // FocusedVisible requires a measured parent
    val frameLayout = FrameLayout(lithoTestRule.context.androidContext)
    frameLayout.addView(testLithoView.lithoView)
    frameLayout.measure(unspecified(), unspecified())
    frameLayout.layout(0, 0, frameLayout.measuredWidth, frameLayout.measuredHeight)

    assertThat(component.dispatchedEventHandlers).contains(visibleEventHandler)
    assertThat(component.dispatchedEventHandlers).contains(focusedEventHandler)
    assertThat(component.dispatchedEventHandlers).contains(fullImpressionHandler)
    component.dispatchedEventHandlers.clear()
    testLithoView.lithoView.setVisibilityHint(false, false)
    assertThat(component.dispatchedEventHandlers).contains(invisibleEventHandler)
    assertThat(component.dispatchedEventHandlers).contains(unfocusedEventHandler)
    component.dispatchedEventHandlers.clear()
    if (ComponentsConfiguration.defaultInstance.unmountOnDetach == UnmountOnDetach.Never) {
      testLithoView.lithoView.setVisibilityHint(true, false)
      assertThat(component.dispatchedEventHandlers).contains(visibleEventHandler)
      assertThat(component.dispatchedEventHandlers).contains(focusedEventHandler)
      assertThat(component.dispatchedEventHandlers).contains(fullImpressionHandler)
    }
  }

  @Test
  fun testVisibilityProcessingNoScrollChange() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(2, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibleHandler(visibleEventHandler)
                    .widthPx(10)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 5),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .useComponentTree(
                ComponentTree.create(context).incrementalMount(false).build(),
            )
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(5))
            .measure()
            .layout()
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
    testLithoView.lithoView.bottom = 10
    testLithoView.lithoView.performLayout(true, 0, 0, 15, 10)
    assertThat(content.dispatchedEventHandlers).contains(visibleEventHandler)
  }

  @Test
  fun setNewComponentTree_noMount_noVisibilityEventsDispatched() {
    val context = lithoTestRule.context
    val content = TestViewComponent.create(context).build()
    val visibleEventHandler = EventHandlerTestUtil.create<VisibleEvent>(2, content)
    val invisibleEventHandler = EventHandlerTestUtil.create<InvisibleEvent>(1, content)
    val root =
        Column.create(context)
            .child(
                Wrapper.create(context)
                    .delegate(content)
                    .visibleHandler(visibleEventHandler)
                    .invisibleHandler(invisibleEventHandler)
                    .widthPx(10)
                    .heightPx(5)
                    .marginPx(YogaEdge.TOP, 5),
            )
            .build()
    val componentTree = ComponentTree.create(context, root).build()
    val testLithoView = lithoTestRule.createTestLithoView()
    testLithoView.lithoView.componentTree = componentTree
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
    content.dispatchedEventHandlers.clear()
    val newComponentTree = ComponentTree.create(context, root).build()
    testLithoView.lithoView.componentTree = newComponentTree
    assertThat(content.dispatchedEventHandlers).doesNotContain(invisibleEventHandler)
    assertThat(content.dispatchedEventHandlers).doesNotContain(visibleEventHandler)
  }

  @Test
  fun processVisibility_componentIsMounted() {
    val context = lithoTestRule.context
    val textDrawableOutput = Output<Any?>()
    val viewOutput = Output<Any?>()
    val nullOutput = Output<Any?>()
    val root: Component =
        Column.create(context)
            .child(
                LayoutSpecVisibilityEventTester(
                    textOutput = textDrawableOutput,
                    viewOutput = viewOutput,
                    nullOutput = nullOutput,
                ),
            )
            .build()
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(root)
            .attachToWindow()
            .setSizeSpecs(exactly(100), exactly(100))
            .measure()
            .layout()
    assertThat(textDrawableOutput.get()).isNotNull
    assertThat(textDrawableOutput.get()).isInstanceOf(TextDrawable::class.java)
    assertThat(viewOutput.get()).isNotNull
    assertThat(viewOutput.get()).isInstanceOf(ComponentHost::class.java)
    assertThat(nullOutput.get()).isNull()
  }

  private fun getCurrentVisibilityToItemMap(lithoView: LithoView): Map<Any, VisibilityItem>? {
    val lithoHostListenerCoordinator: LithoHostListenerCoordinator? =
        Whitebox.getInternalState(lithoView, "lithoHostListenerCoordinator")
    val extensionState: ExtensionState<VisibilityMountExtensionState>? =
        Whitebox.getInternalState(lithoHostListenerCoordinator, "visibilityExtensionState")
    return if (extensionState != null) {
      VisibilityMountExtension.getVisibilityIdToItemMap(extensionState)
    } else {
      null
    }
  }

  @Test
  fun testVisibleEventHorizontalScroll() {
    val c = lithoTestRule.context
    val stepsList: MutableList<List<StepInfo>> = mutableListOf()
    val numberOfItems = 2
    val component = HorizontalScroll(
        child = createHorizontalScrollChildren(c, numberOfItems, stepsList),
        incrementalMountEnabled = true,
    )
    val testLithoView =
        lithoTestRule
            .createTestLithoView()
            .setRoot(component)
            .attachToWindow()
            .setSizeSpecs(exactly(10), exactly(10))
            .measure()
            .layout()
    assertThat(LifecycleStep.getSteps(stepsList[0]))
        .describedAs("Visible event should not be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
    assertThat(LifecycleStep.getSteps(stepsList[1]))
        .describedAs("Visible event should be dispatched")
        .doesNotContain(LifecycleStep.ON_EVENT_VISIBLE)
    val lScrollView = testLithoView.lithoView.getMountItemAt(0).content as HorizontalScrollView
    ReflectionHelpers.setField(lScrollView, "mScrollX", 10)
    ReflectionHelpers.setField(lScrollView, "mScrollY", 0)
    lScrollView.scrollBy(10, 0)
    testLithoView.lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.UNKNOWN)
    assertThat(LifecycleStep.getSteps(stepsList[1]))
        .describedAs("Visible event should be dispatched")
        .contains(LifecycleStep.ON_EVENT_VISIBLE)
  }

  private fun createHorizontalScrollChildren(
      c: ComponentContext,
      numberOfItems: Int,
      stepsList: MutableList<List<StepInfo>>,
  ): Component {
    val rowBuilder = Row.create(c)
    for (i in 0 until numberOfItems) {
      val steps: List<StepInfo> = mutableListOf()
      val component =
          LayoutSpecLifecycleTester.create(c)
              .steps(steps)
              .widthPx(10)
              .heightPx(5)
              .marginPx(YogaEdge.TOP, 5)
              .build()
      rowBuilder.child(component)
      stepsList.add(steps)
    }
    return rowBuilder.build()
  }
}
