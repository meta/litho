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

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.core.height
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.touchExpansion
import com.facebook.litho.widget.OnClickCallbackComponent
import com.facebook.litho.widget.Text
import com.facebook.rendercore.px
import com.facebook.yoga.YogaEdge
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class TouchExpansionDelegateTest {

  @JvmField @Rule val lithoTestRule: LithoTestRule = LithoTestRule()

  private lateinit var context: ComponentContext

  @Before
  fun setup() {
    context = lithoTestRule.context
  }

  @Test
  fun findExpandedTouchTarget_returnsTargetWithinExpansionWithoutDispatching() {
    val callback = ClickListenerCallback()
    val lithoView = renderExpandedComponent(callback)

    val target = lithoView.findExpandedTouchTarget(5, 5)

    assertThat(target).isNotNull
    assertThat(callback.handled).isFalse
    assertThat(callback.count).isZero()
  }

  @Test
  fun findExpandedTouchTarget_usesInclusiveStartAndExclusiveEndBounds() {
    val lithoView = renderExpandedComponent(ClickListenerCallback())

    assertThat(lithoView.findExpandedTouchTarget(5, 5)).isNotNull
    assertThat(lithoView.findExpandedTouchTarget(24, 24)).isNotNull
    assertThat(lithoView.findExpandedTouchTarget(4, 5)).isNull()
    assertThat(lithoView.findExpandedTouchTarget(25, 24)).isNull()
    assertThat(lithoView.findExpandedTouchTarget(24, 25)).isNull()
  }

  @Test
  fun findExpandedTouchTarget_returnsHighestMountIndexWhenExpansionsOverlap() {
    val lithoView =
        lithoTestRule
            .render {
              Column(style = Style.padding(all = 10.px)) {
                child(
                    OnClickCallbackComponent(
                        callback = ClickListenerCallback(),
                        style = Style.width(10.px).height(10.px).touchExpansion(all = 10.px),
                    ),
                )
                child(
                    OnClickCallbackComponent(
                        callback = ClickListenerCallback(),
                        style = Style.width(10.px).height(10.px).touchExpansion(all = 10.px),
                    ),
                )
              }
            }
            .lithoView

    // The lower child's expansion covers y in [0, 30); the upper child's covers y in [10, 40).
    // (10, 15) sits in both, so the highest mount index (upper) must win; identity comparison
    // avoids coupling the assertion to a legacy View id.
    val overlapTarget = lithoView.findExpandedTouchTarget(10, 15)
    val lowerOnlyTarget = lithoView.findExpandedTouchTarget(10, 5)
    val upperOnlyTarget = lithoView.findExpandedTouchTarget(10, 35)

    assertThat(overlapTarget).isNotNull
    assertThat(overlapTarget).isSameAs(upperOnlyTarget)
    assertThat(overlapTarget).isNotSameAs(lowerOnlyTarget)
  }

  @Test
  fun findExpandedTouchTarget_doesNotPrimeGestureState() {
    val callback = ClickListenerCallback()
    val lithoView = renderExpandedComponent(callback)

    assertThat(lithoView.findExpandedTouchTarget(5, 5)).isNotNull
    lithoView.dispatchTouchEvent(
        MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            5f,
            5f,
            0,
        ),
    )

    assertThat(callback.handled).isFalse
    assertThat(callback.count).isZero()
  }

  @Test
  fun findExpandedTouchTarget_returnsNullAfterUnmount() {
    val lithoView = renderExpandedComponent(ClickListenerCallback())
    assertThat(lithoView.findExpandedTouchTarget(5, 5)).isNotNull

    lithoView.unmountAllItems()

    assertThat(lithoView.findExpandedTouchTarget(5, 5)).isNull()
  }

  @Test
  fun findExpandedTouchTarget_returnsNullWhenAndroidDelegateIsReplaced() {
    val lithoView = renderExpandedComponent(ClickListenerCallback())
    assertThat(lithoView.findExpandedTouchTarget(5, 5)).isNotNull

    lithoView.touchDelegate = TouchDelegate(Rect(), lithoView)

    assertThat(lithoView.findExpandedTouchTarget(5, 5)).isNull()
  }

  @Test
  fun onTouchEventOnEmptyDelegate_shouldNoOp() {
    val host = ComponentHost(ApplicationProvider.getApplicationContext<Context>(), null)
    val delegate = TouchExpansionDelegate(host)
    val handled =
        delegate.onTouchEvent(
            MotionEvent.obtain(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_DOWN,
                0f,
                0f,
                0,
            ),
        )
    assertThat(handled).describedAs("TouchEvent on empty delegate should not handle event").isFalse
  }

  @Test
  fun onTouchEventWithinBounds_shouldBeHandled() {
    val callback = ClickListenerCallback()
    val component =
        Column.create(context)
            .child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px).touchExpansion(all = 5.px),
                ),
            )
            .paddingPx(YogaEdge.ALL, 10)
            .build()
    val testLithoView = lithoTestRule.render { component }
    testLithoView.lithoView.emulateClickEvent(7, 7)
    assertThat(callback.handled)
        .describedAs("TouchEvent within bounds bounds should be handled")
        .isTrue
    assertThat(callback.count)
        .describedAs("TouchEvent within bounds bounds should be handled only once")
        .isEqualTo(1)
  }

  @Test
  fun onTouchEventOutsideBounds_shouldNotBeHandled() {
    val callback = ClickListenerCallback()
    val component =
        Column.create(context)
            .child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px).touchExpansion(all = 5.px),
                ),
            )
            .paddingPx(YogaEdge.ALL, 10)
            .build()
    val testLithoView = lithoTestRule.render { component }
    testLithoView.lithoView.emulateClickEvent(2, 2)
    assertThat(callback.handled)
        .describedAs("TouchEvent within bounds bounds should not be handled")
        .isFalse
  }

  @Test
  fun onTouchEventOnUnmount_shouldNotBeHandled() {
    val callback = ClickListenerCallback()
    val component =
        Column.create(context)
            .child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px).touchExpansion(all = 5.px),
                ),
            )
            .paddingPx(YogaEdge.ALL, 10)
            .build()
    val testLithoView = lithoTestRule.render { component }
    assertThat(testLithoView.lithoView.touchExpansionDelegate?.size())
        .describedAs("touch expansion delegates should be present")
        .isEqualTo(1)
    testLithoView.lithoView.unmountAllItems()
    testLithoView.lithoView.emulateClickEvent(7, 7)
    assertThat(callback.handled)
        .describedAs("TouchEvent within bounds bounds should not be handled")
        .isFalse
    assertThat(testLithoView.lithoView.touchExpansionDelegate?.size())
        .describedAs("all touch expansion delegates should be released")
        .isEqualTo(0)
  }

  @Test
  fun onTouchEventOnUpdatedComponentWithoutTouchExpansion_shouldNotBeHandled() {
    val callback = ClickListenerCallback()
    val component =
        Column.create(context)
            .child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px).touchExpansion(all = 5.px),
                ),
            )
            .paddingPx(YogaEdge.ALL, 10)
            .build()
    val testLithoView = lithoTestRule.render { component }
    val updated =
        Column.create(context)
            .child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px),
                ),
            )
            .paddingPx(YogaEdge.ALL, 10)
            .build()
    testLithoView.setRoot(updated)
    assertThat(testLithoView.lithoView.findExpandedTouchTarget(7, 7)).isNull()
    testLithoView.lithoView.emulateClickEvent(7, 7)
    assertThat(callback.handled)
        .describedAs("TouchEvent within bounds bounds should not be handled")
        .isFalse
    testLithoView.lithoView.emulateClickEvent(11, 11)
    assertThat(callback.handled)
        .describedAs("TouchEvent within bounds bounds should be handled")
        .isTrue
    assertThat(callback.count)
        .describedAs("TouchEvent within bounds bounds should be handled only once")
        .isEqualTo(1)
    testLithoView.lithoView.unmountAllItems()
    assertThat(testLithoView.lithoView.touchExpansionDelegate?.size())
        .describedAs("all touch expansion delegates should be released")
        .isEqualTo(0)
  }

  @Test
  fun onTouchEventOnComponentMoved_shouldBeHandled() {
    val callback = ClickListenerCallback()
    val component =
        Column.create(context)
            .child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px).touchExpansion(all = 5.px),
                ),
            )
            .paddingPx(YogaEdge.ALL, 10)
            .build()
    val testLithoView = lithoTestRule.render { component }
    val updated =
        Column.create(context)
            .child(Text.create(context).text("hello world").widthPx(10).heightPx(10))
            .child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px).touchExpansion(all = 5.px),
                ),
            )
            .paddingPx(YogaEdge.ALL, 10)
            .build()
    testLithoView.setRoot(updated).measure().layout()
    assertThat(testLithoView.lithoView.findExpandedTouchTarget(7, 7)).isNull()
    assertThat(testLithoView.lithoView.findExpandedTouchTarget(7, 21)).isNotNull
    testLithoView.lithoView.emulateClickEvent(7, 7)
    assertThat(callback.handled)
        .describedAs("TouchEvent within bounds bounds should not be handled")
        .isFalse
    testLithoView.lithoView.emulateClickEvent(7, 21)
    assertThat(callback.handled)
        .describedAs("TouchEvent within bounds bounds should be handled")
        .isTrue
    assertThat(callback.count)
        .describedAs("TouchEvent within bounds bounds should be handled only once")
        .isEqualTo(1)
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_reportsExpandedBoundsOfChild() {
    val lithoView = renderExpandedComponent(ClickListenerCallback())
    val child = requireNotNull(lithoView.findExpandedTouchTarget(10, 10))

    assertThat(lithoView.touchDelegateTargets())
        .containsExactly(entry(Rect(5, 5, 25, 25), child.accessibilityViewId()))
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_reportsEveryExpandedChild() {
    val lithoView = lithoTestRule.render { TwoExpandedChildren(secondChild = true) }.lithoView
    // The first child's expansion covers y in [0, 30); the second child's covers y in [10, 40).
    val first = requireNotNull(lithoView.findExpandedTouchTarget(10, 5))
    val second = requireNotNull(lithoView.findExpandedTouchTarget(10, 35))

    assertThat(lithoView.touchDelegateTargets())
        .containsOnly(
            entry(Rect(0, 0, 30, 30), first.accessibilityViewId()),
            entry(Rect(0, 10, 30, 40), second.accessibilityViewId()),
        )
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_dropsUnmountedChild() {
    val testLithoView = lithoTestRule.render { TwoExpandedChildren(secondChild = true) }
    val first = requireNotNull(testLithoView.lithoView.findExpandedTouchTarget(10, 5))

    testLithoView.setRoot(TwoExpandedChildren(secondChild = false))

    assertThat(testLithoView.lithoView.touchExpansionDelegate?.size()).isEqualTo(1)
    assertThat(testLithoView.lithoView.touchDelegateTargets())
        .containsExactly(entry(Rect(0, 0, 30, 30), first.accessibilityViewId()))
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_dropsChildWhoseExpansionIsRemoved() {
    val testLithoView = lithoTestRule.render { TwoExpandedChildren(secondChild = true) }
    val first = requireNotNull(testLithoView.lithoView.findExpandedTouchTarget(10, 5))

    testLithoView.setRoot(TwoExpandedChildren(secondChild = true, expandSecondChild = false))

    assertThat(testLithoView.lithoView.touchDelegateTargets())
        .containsExactly(entry(Rect(0, 0, 30, 30), first.accessibilityViewId()))
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_isReusedWhileNothingChanges() {
    val delegate = requireNotNull(renderExpandedComponent(ClickListenerCallback()).touchDelegate)

    assertThat(delegate.touchDelegateInfo).isSameAs(delegate.touchDelegateInfo)
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_isReusedAfterAnUpdateThatKeepsTheBounds() {
    val testLithoView = lithoTestRule.render { ExpandedChild(sizePx = 10) }
    val delegate = requireNotNull(testLithoView.lithoView.touchDelegate)
    val info = delegate.touchDelegateInfo

    testLithoView.setRoot(ExpandedChild(sizePx = 10))

    assertThat(delegate.touchDelegateInfo).isSameAs(info)
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_followsChildrenMountedAndUnmountedAfterItWasRead() {
    val testLithoView = lithoTestRule.render { TwoExpandedChildren(secondChild = true) }
    val lithoView = testLithoView.lithoView
    val first = requireNotNull(lithoView.findExpandedTouchTarget(10, 5))
    assertThat(lithoView.touchDelegateTargets()).hasSize(2)

    testLithoView.setRoot(TwoExpandedChildren(secondChild = false))
    assertThat(lithoView.touchDelegateTargets())
        .containsExactly(entry(Rect(0, 0, 30, 30), first.accessibilityViewId()))

    testLithoView.setRoot(TwoExpandedChildren(secondChild = true))
    val second = requireNotNull(lithoView.findExpandedTouchTarget(10, 35))
    assertThat(lithoView.touchDelegateTargets())
        .containsOnly(
            entry(Rect(0, 0, 30, 30), first.accessibilityViewId()),
            entry(Rect(0, 10, 30, 40), second.accessibilityViewId()),
        )
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_followsBoundsUpdatedWithoutRemountAfterItWasRead() {
    val testLithoView = lithoTestRule.render { ExpandedChild(sizePx = 10) }
    val lithoView = testLithoView.lithoView
    val child = requireNotNull(lithoView.findExpandedTouchTarget(10, 10))
    val delegate = requireNotNull(lithoView.touchExpansionDelegate)
    assertThat(lithoView.touchDelegateTargets())
        .containsExactly(entry(Rect(5, 5, 25, 25), child.accessibilityViewId()))

    testLithoView.setRoot(ExpandedChild(sizePx = 20))

    assertThat(lithoView.touchExpansionDelegate).isSameAs(delegate)
    assertThat(lithoView.findExpandedTouchTarget(10, 10)).isSameAs(child)
    assertThat(lithoView.touchDelegateTargets())
        .containsExactly(entry(Rect(5, 5, 35, 35), child.accessibilityViewId()))
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_withoutDelegates_reportsHostWithEmptyBounds() {
    val host = ComponentHost(ApplicationProvider.getApplicationContext<Context>(), null)
    val info = TouchExpansionDelegate(host).touchDelegateInfo

    assertThat(info.targets()).containsExactly(entry(Rect(), host.accessibilityViewId()))
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchDelegateInfo_afterUnmountingAll_reportsHostWithEmptyBounds() {
    val lithoView = renderExpandedComponent(ClickListenerCallback())

    lithoView.unmountAllItems()

    assertThat(lithoView.touchDelegateTargets())
        .containsExactly(entry(Rect(), lithoView.accessibilityViewId()))
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchExplorationHover_doesNotDispatchToHostWithMetadataEnabledOrDisabled() {
    for (enabled in listOf(false, true)) {
      val previous =
          com.facebook.litho.config.ComponentsConfiguration.enableTouchDelegateInfoForAccessibility
      try {
        com.facebook.litho.config.ComponentsConfiguration.enableTouchDelegateInfoForAccessibility =
            enabled
        val lithoView = renderExpandedComponent(ClickListenerCallback())
        val delegate = requireNotNull(lithoView.touchExpansionDelegate)
        var hostDispatches = 0
        var childDispatches = 0
        lithoView.setOnHoverListener { _, _ ->
          hostDispatches++
          true
        }
        requireNotNull(lithoView.findExpandedTouchTarget(10, 10)).setOnHoverListener { _, _ ->
          childDispatches++
          true
        }
        assertHoverNotDispatched(delegate)
        assertThat(hostDispatches).isZero()
        assertThat(childDispatches).isZero()
        lithoView.unmountAllItems()
        assertHoverNotDispatched(delegate)
        assertThat(hostDispatches).isZero()
        assertThat(childDispatches).isZero()
      } finally {
        com.facebook.litho.config.ComponentsConfiguration.enableTouchDelegateInfoForAccessibility =
            previous
      }
    }
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchExplorationHover_emptyDelegateDoesNotDispatchExitToHost() {
    val host = ComponentHost(ApplicationProvider.getApplicationContext<Context>(), null)
    var dispatches = 0
    host.setOnHoverListener { _, _ ->
      dispatches++
      true
    }
    assertHoverNotDispatched(TouchExpansionDelegate(host))
    assertThat(dispatches).isZero()
  }

  @Test
  @RequiresApi(Build.VERSION_CODES.Q)
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun touchExplorationHover_platformDelegatePositiveControlDispatchesToTarget() {
    val target = View(ApplicationProvider.getApplicationContext<Context>())
    var dispatches = 0
    target.setOnHoverListener { _, _ ->
      dispatches++
      true
    }
    val delegate = TouchDelegate(Rect(5, 5, 25, 25), target)
    for (action in
        listOf(
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
        )) {
      val event = MotionEvent.obtain(0, 0, action, 10f, 10f, 0)
      try {
        assertThat(delegate.onTouchExplorationHoverEvent(event)).isTrue()
      } finally {
        event.recycle()
      }
    }
    assertThat(dispatches).isEqualTo(3)
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun assertHoverNotDispatched(delegate: TouchExpansionDelegate) {
    for (action in
        listOf(
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
        )) {
      for (coordinate in listOf(10f, 100f)) {
        val event = MotionEvent.obtain(0, 0, action, coordinate, coordinate, 0)
        try {
          assertThat(delegate.onTouchExplorationHoverEvent(event)).isFalse()
          assertThat(event.x).isEqualTo(coordinate)
          assertThat(event.y).isEqualTo(coordinate)
        } finally {
          event.recycle()
        }
      }
    }
  }

  private fun renderExpandedComponent(callback: ClickListenerCallback): LithoView {
    return lithoTestRule
        .render {
          Column(style = Style.padding(all = 10.px)) {
            child(
                OnClickCallbackComponent(
                    callback = callback,
                    style = Style.width(10.px).height(10.px).touchExpansion(all = 5.px),
                ),
            )
          }
        }
        .lithoView
  }

  private class ExpandedChild(private val sizePx: Int) : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.padding(all = 10.px)) {
          child(
              OnClickCallbackComponent(
                  callback = ClickListenerCallback(),
                  style = Style.width(sizePx.px).height(sizePx.px).touchExpansion(all = 5.px),
              ),
          )
        }
  }

  private class TwoExpandedChildren(
      private val secondChild: Boolean,
      private val expandSecondChild: Boolean = true,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.padding(all = 10.px)) {
          child(
              OnClickCallbackComponent(
                  callback = ClickListenerCallback(),
                  style = Style.width(10.px).height(10.px).touchExpansion(all = 10.px),
              ),
          )
          if (secondChild) {
            child(
                OnClickCallbackComponent(
                    callback = ClickListenerCallback(),
                    style =
                        Style.width(10.px).height(10.px).let {
                          if (expandSecondChild) it.touchExpansion(all = 10.px) else it
                        },
                ),
            )
          }
        }
  }

  class ClickListenerCallback : View.OnClickListener {
    var handled = false
    var count = 0

    override fun onClick(v: View) {
      handled = true
      count++
    }

    fun reset() {
      handled = false
      count = 0
    }
  }

  companion object {
    /** The touch delegate info the host puts on its [AccessibilityNodeInfo] once attached. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun View.touchDelegateTargets(): Map<Rect, Long> =
        requireNotNull(touchDelegate).touchDelegateInfo.targets()

    /**
     * Maps each region's bounds to its target's accessibility view id. TouchDelegateInfo keeps only
     * view ids, and the accessors for them are hidden API.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun AccessibilityNodeInfo.TouchDelegateInfo.targets(): Map<Rect, Long> =
        (0 until regionCount).associate { i ->
          val region = getRegionAt(i)
          region.bounds to
              ReflectionHelpers.callInstanceMethod<Long>(
                  this,
                  "getAccessibilityIdForRegion",
                  ClassParameter.from(Region::class.java, region),
              )!!
        }

    private fun View.accessibilityViewId(): Long =
        ReflectionHelpers.callInstanceMethod<Int>(this, "getAccessibilityViewId")!!.toLong()

    fun View.emulateClickEvent(x: Int, y: Int) {
      val down =
          MotionEvent.obtain(
              SystemClock.uptimeMillis(),
              SystemClock.uptimeMillis(),
              MotionEvent.ACTION_DOWN,
              x.toFloat(),
              y.toFloat(),
              0,
          )
      val up =
          MotionEvent.obtain(
              SystemClock.uptimeMillis() + 10,
              SystemClock.uptimeMillis() + 10,
              MotionEvent.ACTION_UP,
              x.toFloat(),
              y.toFloat(),
              0,
          )
      dispatchTouchEvent(down)
      dispatchTouchEvent(up)
    }
  }
}
