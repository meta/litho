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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import androidx.collection.SparseArrayCompat
import androidx.core.graphics.drawable.toDrawable
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestViewComponent
import com.facebook.litho.testing.Whitebox
import com.facebook.litho.testing.helper.ComponentTestHelper
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.testKey
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.litho.widget.Text
import com.facebook.litho.widget.TextInput
import com.facebook.rendercore.MountItem
import com.facebook.yoga.YogaEdge
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Tests [ComponentHost] */
@RunWith(LithoTestRunner::class)
class ComponentHostTest {

  private lateinit var viewGroupHost: Component
  private lateinit var host: TestableComponentHost
  private lateinit var drawableComponent: Component
  private lateinit var viewComponent: Component
  private lateinit var context: ComponentContext

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Before
  fun setup() {
    context = lithoTestRule.context
    viewComponent = TestViewComponent.create(context).key(viewComponentKey).build()
    drawableComponent = SimpleMountSpecTester(style = Style.testKey(drawableComponentKey))
    host = TestableComponentHost(context)
    viewGroupHost = HostComponent.create(context)
  }

  private val dummyMotionEvent =
      MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, 50f, 50f, 0)

  @Test
  fun testInvalidations() {
    assertThat(host.invalidationCount).isEqualTo(0)
    assertThat(host.invalidationRect).isNull()
    val d1 = ColorDrawable()
    d1.setBounds(0, 0, 1, 1)
    val mountItem1 = mount(0, d1)
    assertThat(host.invalidationCount).isEqualTo(1)
    assertThat(host.invalidationRect).isEqualTo(d1.bounds)
    val d2 = ColorDrawable()
    d2.setBounds(0, 0, 2, 2)
    val mountItem2 = mount(1, d2)
    assertThat(host.invalidationCount).isEqualTo(2)
    assertThat(host.invalidationRect).isEqualTo(d2.bounds)
    val v1 = View(context.androidContext)
    val v1Bounds = Rect(0, 0, 10, 10)
    v1.measure(
        View.MeasureSpec.makeMeasureSpec(v1Bounds.width(), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(v1Bounds.height(), View.MeasureSpec.EXACTLY),
    )
    v1.layout(v1Bounds.left, v1Bounds.top, v1Bounds.right, v1Bounds.bottom)
    val mountItem3 = mount(2, v1)
    assertThat(host.invalidationCount).isEqualTo(3)
    assertThat(host.invalidationRect).isEqualTo(v1Bounds)
    unmount(0, mountItem1)
    assertThat(host.invalidationCount).isEqualTo(4)
    assertThat(host.invalidationRect).isEqualTo(d1.bounds)
    unmount(1, mountItem2)
    assertThat(host.invalidationCount).isEqualTo(5)
    assertThat(host.invalidationRect).isEqualTo(d2.bounds)
    unmount(2, mountItem3)
    assertThat(host.invalidationCount).isEqualTo(6)
    assertThat(host.invalidationRect).isEqualTo(v1Bounds)
  }

  @Test
  fun testCallbacks() {
    val d = ColorDrawable()
    assertThat(d.callback).isNull()
    val mountItem = mount(0, d)
    assertThat(d.callback).isEqualTo(host)
    unmount(0, mountItem)
    assertThat(d.callback).isNull()
  }

  @Test
  fun testGetMountItemCount() {
    assertThat(host.mountItemCount).isEqualTo(0)
    val mountItem1 = mount(0, ColorDrawable())
    assertThat(host.mountItemCount).isEqualTo(1)
    mount(1, ColorDrawable())
    assertThat(host.mountItemCount).isEqualTo(2)
    val mountItem3 = mount(2, View(context.androidContext))
    assertThat(host.mountItemCount).isEqualTo(3)
    unmount(0, mountItem1)
    assertThat(host.mountItemCount).isEqualTo(2)
    val mountItem4 = mount(1, ColorDrawable())
    assertThat(host.mountItemCount).isEqualTo(2)
    unmount(2, mountItem3)
    assertThat(host.mountItemCount).isEqualTo(1)
    unmount(1, mountItem4)
    assertThat(host.mountItemCount).isEqualTo(0)
  }

  @Test
  fun testSafelyUnmountAllClearsMountItemsAndChildren() {
    val drawable1 = Color.RED.toDrawable().apply { setBounds(0, 0, 1, 1) }
    val drawable2 = Color.BLUE.toDrawable().apply { setBounds(0, 0, 2, 2) }
    val view = View(context.androidContext)

    mount(0, drawable1)
    mount(1, view)
    mount(2, drawable2)

    assertThat(host.mountItemCount).isEqualTo(3)
    assertThat(drawableItemsSize).isEqualTo(2)
    assertThat(viewItemsSize).isEqualTo(1)
    assertThat(host.childCount).isEqualTo(1)
    assertThat(drawable1.callback).isEqualTo(host)
    assertThat(drawable2.callback).isEqualTo(host)

    host.safelyUnmountAll()

    assertThat(host.mountItemCount).isEqualTo(0)
    assertThat(drawableItemsSize).isEqualTo(0)
    assertThat(viewItemsSize).isEqualTo(0)
    assertThat(host.childCount).isEqualTo(0)
    assertThat(drawable1.callback).isNull()
    assertThat(drawable2.callback).isNull()
  }

  @Test
  fun testGetMountItemAt() {
    assertThat(host.mountItemCount).isEqualTo(0)
    val mountItem1 = mount(0, ColorDrawable())
    val mountItem2 = mount(1, View(context.androidContext))
    val mountItem3 = mount(5, ColorDrawable())
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(host.getMountItemAt(1)).isEqualTo(mountItem2)
    assertThat(host.getMountItemAt(2)).isEqualTo(mountItem3)
    unmount(1, mountItem2)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(host.getMountItemAt(1)).isEqualTo(mountItem3)
    unmount(0, mountItem1)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem3)
  }

  @Test
  fun testOnTouchWithTouchables() {
    assertThat(host.mountItemCount).isEqualTo(0)

    // Touchables are traversed backwards as drawing order.
    // The n.4 is the first parsed, and returning false means the n.2 will be parsed too.
    val touchableDrawableOnItem2 = Mockito.spy(TouchableDrawable())
    val touchableDrawableOnItem4 = Mockito.spy(TouchableDrawable())
    whenever(touchableDrawableOnItem2.shouldHandleTouchEvent(dummyMotionEvent)).thenReturn(true)
    whenever(touchableDrawableOnItem4.shouldHandleTouchEvent(dummyMotionEvent)).thenReturn(false)
    val mountItem1 = mount(0, ColorDrawable())
    val mountItem2 = mount(1, touchableDrawableOnItem2)
    val mountItem3 = mount(2, View(context.androidContext))
    val mountItem4 = mount(5, touchableDrawableOnItem4)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(host.getMountItemAt(1)).isEqualTo(mountItem2)
    assertThat(host.getMountItemAt(2)).isEqualTo(mountItem3)
    assertThat(host.getMountItemAt(3)).isEqualTo(mountItem4)
    host.onTouchEvent(dummyMotionEvent)
    verify(touchableDrawableOnItem4, times(1)).shouldHandleTouchEvent(any())
    verify(touchableDrawableOnItem4, never()).onTouchEvent(any(), any())
    verify(touchableDrawableOnItem2, times(1)).shouldHandleTouchEvent(any())
    verify(touchableDrawableOnItem2, times(1)).onTouchEvent(any(), any())
  }

  @Test
  fun testOnTouchWithDisableTouchables() {
    assertThat(host.mountItemCount).isEqualTo(0)
    val mountItem1 = mount(0, ColorDrawable())
    val mountItem2 = mount(1, TouchableDrawable(), LithoRenderUnit.LAYOUT_FLAG_DISABLE_TOUCHABLE)
    val mountItem3 = mount(2, View(context.androidContext))
    val mountItem4 = mount(4, Mockito.spy(TouchableDrawable()))
    val mountItem5 = mount(5, TouchableDrawable(), LithoRenderUnit.LAYOUT_FLAG_DISABLE_TOUCHABLE)
    val mountItem6 = mount(7, View(context.androidContext))
    val mountItem7 = mount(8, TouchableDrawable(), LithoRenderUnit.LAYOUT_FLAG_DISABLE_TOUCHABLE)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(host.getMountItemAt(1)).isEqualTo(mountItem2)
    assertThat(host.getMountItemAt(2)).isEqualTo(mountItem3)
    assertThat(host.getMountItemAt(3)).isEqualTo(mountItem4)
    assertThat(host.getMountItemAt(4)).isEqualTo(mountItem5)
    assertThat(host.getMountItemAt(5)).isEqualTo(mountItem6)
    assertThat(host.getMountItemAt(6)).isEqualTo(mountItem7)
    host.onTouchEvent(dummyMotionEvent)
    val touchableDrawable = mountItem4.content as TouchableDrawable
    verify(touchableDrawable, times(1)).shouldHandleTouchEvent(any())
    verify(touchableDrawable, times(1)).onTouchEvent(any(), any())
  }

  @Test
  fun testMoveItem() {
    val mountItem1 = mount(1, ColorDrawable())
    val mountItem2 = mount(2, View(context.androidContext))
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(host.getMountItemAt(1)).isEqualTo(mountItem2)
    host.moveItem(mountItem2, 2, 0)
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem2)
    assertThat(host.getMountItemAt(1)).isEqualTo(mountItem1)
    host.moveItem(mountItem2, 0, 1)
    assertThat(host.mountItemCount).isEqualTo(1)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem2)
    host.moveItem(mountItem2, 1, 0)
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.getMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(host.getMountItemAt(1)).isEqualTo(mountItem2)
  }

  @Test
  fun testVisitMountItemsIncludesScrapAndCanStopEarly() {
    val first = mount(1, ColorDrawable())
    val displaced = mount(2, View(context.androidContext))
    host.moveItem(first, 1, 2)

    val visited = mutableListOf<MountItem>()
    val completed = host.visitMountItems { item ->
      visited.add(item)
      true
    }
    assertThat(completed).isTrue()
    assertThat(visited).containsExactly(first, displaced)
    assertThat(host.snapshotMountItems()).containsExactly(first, displaced)

    visited.clear()
    val stopped = host.visitMountItems { item ->
      visited.add(item)
      false
    }
    assertThat(stopped).isFalse()
    assertThat(visited).containsExactly(first)
  }

  @Test
  fun testVisitMountItemsRejectsReentrantStructuralMutation() {
    val first = mount(1, ColorDrawable())

    assertThatThrownBy {
          host.visitMountItems {
            host.unmount(first)
            true
          }
        }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("Cannot mutate Host mount items")

    assertThat(host.mountItemCount).isEqualTo(1)
    assertThat(host.getMountItemAt(0)).isSameAs(first)
  }

  @Test
  fun testMoveItemWithoutTouchables() {
    val d1 = ColorDrawable(Color.BLACK)
    val mountItem1 = mount(1, d1)
    val d2 = ColorDrawable(Color.BLACK)
    val mountItem2 = mount(2, d2)
    assertThat(drawableItemsSize).isEqualTo(2)
    assertThat(getDrawableMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(getDrawableMountItemAt(1)).isEqualTo(mountItem2)
    host.moveItem(mountItem2, 2, 0)

    // There are no Touchable Drawables so this call should return false and not crash.
    assertThat(host.onTouchEvent(MotionEvent.obtain(0, 0, 0, 0f, 0f, 0))).isFalse
  }

  @Test
  fun testDrawableStateChangedOnDrawables() {
    val d1 = mock<ColorDrawable>()
    whenever(d1.bounds).thenReturn(Rect())
    whenever(d1.isStateful).thenReturn(false)
    val mountItem1 = mount(0, d1)
    verify(d1, never()).state = (any<IntArray>())
    unmount(0, mountItem1)
    val d2 = mock<ColorDrawable>()
    whenever(d2.bounds).thenReturn(Rect())
    whenever(d2.isStateful).thenReturn(true)
    mount(0, d2, LithoRenderUnit.LAYOUT_FLAG_DUPLICATE_PARENT_STATE)
    // In the full mount pipeline, initial drawable state is set by attach binders
    // (e.g., RefreshDrawableStateBinder, MountSpecLithoRenderUnit.binderBinder), not by
    // ComponentHost.mountDrawable(). This test exercises ComponentHost in isolation
    // (without MountState), so no state is set during mount.
    host.isSelected = true
    verify(d2, times(1)).state = eq(host.drawableState)
  }

  @Test
  fun testMoveTouchExpansionItem() {
    val view = View(context.androidContext)
    val mountItem = mountTouchExpansionItem(0, view)
    host.moveItem(mountItem, 0, 1)
    unmount(1, mountItem)
  }

  /**
   * Unlike [mountTouchExpansionItem], this sets real expanded touch bounds on the layout data, so
   * the item actually reaches the touch-expansion path in ComponentHost.
   */
  private fun touchExpansionItem(content: Any): MountItem {
    val unit =
        MountSpecLithoRenderUnit.create(
            0,
            viewComponent,
            null,
            context,
            null,
            0,
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
            MountSpecLithoRenderUnit.STATE_UNKNOWN,
            0,
            null,
        )
    val node = create(
        unit,
        Rect(0, 0, 10, 10),
        null,
        LithoLayoutData(10, 10, Rect(1, 1, 1, 1), null, null, false, null),
        null,
    )
    return MountItem(node, content)
  }

  @Test
  fun unmountingAnItemWhoseContentIsTheHostKeepsTheTouchExpansionDelegateIntact() {
    val view = View(context.androidContext)
    host.mount(0, touchExpansionItem(view), Rect())
    assertThat(host.touchExpansionDelegate).isNotNull

    // An item whose content is the host itself was never registered, so unregistering its index
    // would evict whichever view legitimately holds that slot. It is never mounted here, so
    // mountView() does not run and nothing overflows the stack.
    host.unmount(0, touchExpansionItem(host))

    assertThat(host.findExpandedTouchTarget(1, 1))
        .describedAs("the real registration must survive")
        .isEqualTo(view)
  }

  @Test
  fun testDuplicateParentStateOnViews() {
    val v1 = mock<View>()
    mount(0, v1)
    verify(v1, never()).isDuplicateParentStateEnabled = ArgumentMatchers.anyBoolean()
    val v2 = mock<View>()
    mount(1, v2, LithoRenderUnit.LAYOUT_FLAG_DUPLICATE_PARENT_STATE)
    verify(v2, times(1)).isDuplicateParentStateEnabled = eq(true)
    reset(v2)
    unmount(1, v2, LithoRenderUnit.LAYOUT_FLAG_DUPLICATE_PARENT_STATE)
    verify(v2, times(1)).isDuplicateParentStateEnabled = eq(false)
  }

  @Test
  fun testJumpDrawablesToCurrentState() {
    host.jumpDrawablesToCurrentState()
    val d1 = mock<ColorDrawable>()
    whenever(d1.bounds).thenReturn(Rect())
    mount(0, d1)
    val d2 = mock<ColorDrawable>()
    whenever(d2.bounds).thenReturn(Rect())
    mount(1, d2)
    val v1 = mock<View>()
    mount(2, v1)
    host.jumpDrawablesToCurrentState()
    verify(d1, times(1)).jumpToCurrentState()
    verify(d2, times(1)).jumpToCurrentState()
  }

  @Test
  fun testSetVisibility() {
    val d1 = mock<ColorDrawable>()
    whenever(d1.bounds).thenReturn(Rect())
    mount(0, d1)
    val d2 = mock<ColorDrawable>()
    whenever(d2.bounds).thenReturn(Rect())
    mount(1, d2)
    val v1 = mock<View>()
    mount(2, v1)
    host.visibility = View.GONE
    host.visibility = View.INVISIBLE
    host.visibility = View.VISIBLE
    verify(d1, times(2)).setVisible(eq(true), eq(false))
    verify(d1, times(2)).setVisible(eq(false), eq(false))
    verify(d2, times(2)).setVisible(eq(true), eq(false))
    verify(d2, times(2)).setVisible(eq(false), eq(false))
    verify(v1, never()).visibility = ArgumentMatchers.anyInt()
  }

  @Test
  fun testGetDrawables() {
    val d1 = ColorDrawable()
    val mountItem1 = mount(0, d1)
    val d2 = ColorDrawable()
    mount(1, d2)
    val mountItem3 = mount(2, View(context.androidContext))
    var drawables = host.drawables
    assertThat(drawables).hasSize(2)
    assertThat(drawables[0]).isEqualTo(d1)
    assertThat(drawables[1]).isEqualTo(d2)
    unmount(0, mountItem1)
    drawables = host.drawables
    assertThat(drawables).hasSize(1)
    assertThat(drawables[0]).isEqualTo(d2)
    unmount(2, mountItem3)
    drawables = host.drawables
    assertThat(drawables).hasSize(1)
    assertThat(drawables[0]).isEqualTo(d2)
  }

  @Test
  fun testViewTag() {
    val rootComponent =
        Column.create(context)
            .child(SimpleMountSpecTester(style = Style.viewTag("test_tag")))
            .build()
    val lithoView = ComponentTestHelper.mountComponent(context, rootComponent, false, false)
    assertThat(lithoView.findViewWithTag("test_tag") as View).isNotNull
  }

  @Test
  fun testViewTags() {
    assertThat(host.getTag(1)).isNull()
    assertThat(host.getTag(2)).isNull()
    val value1 = Any()
    val value2 = Any()
    val viewTags = SparseArray<Any>()
    viewTags.put(1, value1)
    viewTags.put(2, value2)
    host.addViewTags(viewTags)
    assertThat(host.getTag(1)).isEqualTo(value1)
    assertThat(host.getTag(2)).isEqualTo(value2)
    host.addViewTags(null)
    assertThat(host.getTag(1)).isNull()
    assertThat(host.getTag(2)).isNull()
  }

  @Test
  fun testComponentLongClickListener() {
    assertThat(host.componentLongClickListener).isNull()
    val listener = ComponentLongClickListener()
    host.componentLongClickListener = listener
    assertThat(host.componentLongClickListener).isEqualTo(listener)
    host.componentLongClickListener = null
    assertThat(host.componentLongClickListener).isNull()
  }

  @Test
  fun testComponentFocusChangeListener() {
    assertThat(host.componentFocusChangeListener).isNull()
    val listener = ComponentFocusChangeListener()
    host.componentFocusChangeListener = listener
    assertThat(host.componentFocusChangeListener).isEqualTo(listener)
    host.componentFocusChangeListener = null
    assertThat(host.componentFocusChangeListener).isNull()
  }

  @Test
  fun testComponentTouchListener() {
    assertThat(host.componentTouchListener).isNull()
    val listener = ComponentTouchListener()
    host.componentTouchListener = listener
    assertThat(host.componentTouchListener).isEqualTo(listener)
    host.componentTouchListener = null
    assertThat(host.componentTouchListener).isNull()
  }

  @Test
  fun testGetContentDescriptions() {
    val hostContentDescription = "hostContentDescription"
    host.contentDescription = hostContentDescription
    val drawableContentDescription = "drawableContentDescription"
    val mountItem0 = mount(0, ColorDrawable(), 0, drawableContentDescription)
    val viewContentDescription = "viewContentDescription"
    mount(1, mock<View>(), 0, viewContentDescription)
    assertThat(host.contentDescriptions).contains(hostContentDescription)
    assertThat(host.contentDescriptions).contains(drawableContentDescription)
    assertThat(host.contentDescriptions).doesNotContain(viewContentDescription)
    unmount(0, mountItem0)
    assertThat(host.contentDescriptions).contains(hostContentDescription)
    assertThat(host.contentDescriptions).doesNotContain(drawableContentDescription)
    assertThat(host.contentDescriptions).doesNotContain(viewContentDescription)
  }

  @Test
  fun testAccessibleMountItemOfAViewChildIsItsOwnWhenADrawableIsMountedFirst() {
    // A host's own background, or any drawable child, takes a mount index before the first View.
    // Child-view index 0 then names the drawable's item, not the View's.
    mount(0, Color.RED.toDrawable())
    val view = View(context.androidContext)
    val viewItem = mount(1, view)

    assertThat(ComponentAccessibilityDelegate.getAccessibleMountItem(view)).isSameAs(viewItem)
  }

  @Test
  fun testAccessibleMountItemOfAViewChildIsItsOwnWhenMountedOutOfOrder() {
    // Incremental mount can mount a later index first, so the second View is child 0.
    val second = View(context.androidContext)
    val secondItem = mount(1, second)
    val first = View(context.androidContext)
    val firstItem = mount(0, first)

    assertThat(ComponentAccessibilityDelegate.getAccessibleMountItem(first)).isSameAs(firstItem)
    assertThat(ComponentAccessibilityDelegate.getAccessibleMountItem(second)).isSameAs(secondItem)
  }

  @Test
  fun testAccessibleMountItemOfAViewChildIsItsOwnAfterAMove() {
    // A move changes mount indices without re-adding the Views.
    val first = View(context.androidContext)
    val firstItem = mount(0, first)
    val second = View(context.androidContext)
    val secondItem = mount(1, second)
    host.moveItem(secondItem, 1, 2)
    host.moveItem(firstItem, 0, 1)
    host.moveItem(secondItem, 2, 0)

    assertThat(ComponentAccessibilityDelegate.getAccessibleMountItem(first)).isSameAs(firstItem)
    assertThat(ComponentAccessibilityDelegate.getAccessibleMountItem(second)).isSameAs(secondItem)
  }

  @Test
  fun testGetChildDrawingOrder() {
    assertChildDrawingOrderAfterMove(invalidateOnMove = true)
  }

  @Test
  fun testGetChildDrawingOrderWithoutViewMoveInvalidation() {
    assertChildDrawingOrderAfterMove(invalidateOnMove = false)
  }

  private fun assertChildDrawingOrderAfterMove(invalidateOnMove: Boolean) {
    val originalValue = ComponentsConfiguration.enableComponentHostViewMoveInvalidation
    ComponentsConfiguration.enableComponentHostViewMoveInvalidation = invalidateOnMove
    try {
      val v1 = View(context.androidContext)
      mount(2, v1)
      val v2 = View(context.androidContext)
      val mountItem2 = mount(0, v2)
      assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
      assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(0)
      val v3 = ComponentHost(context)
      val mountItem3 = mount(1, v3)
      assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
      assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(2)
      assertThat(host.getChildDrawingOrder(host.childCount, 2)).isEqualTo(0)
      host.unmount(1, mountItem3)
      assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
      assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(0)
      host.mount(1, mountItem3)
      assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
      assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(2)
      assertThat(host.getChildDrawingOrder(host.childCount, 2)).isEqualTo(0)
      host.unmount(0, mountItem2)
      assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
      assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(0)
      val invalidationCountBeforeMove = host.invalidationCount
      host.moveItem(mountItem3, 1, 3)
      assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(0)
      assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(1)
      assertThat(host.invalidationCount)
          .isEqualTo(invalidationCountBeforeMove + if (invalidateOnMove) 1 else 0)
    } finally {
      ComponentsConfiguration.enableComponentHostViewMoveInvalidation = originalValue
    }
  }

  @Test
  fun testDrawableMoveInvalidatesWithViewMoveInvalidationDisabled() {
    val originalValue = ComponentsConfiguration.enableComponentHostViewMoveInvalidation
    ComponentsConfiguration.enableComponentHostViewMoveInvalidation = false
    try {
      val mountItem = mount(0, Color.RED.toDrawable())
      val invalidationCountBeforeMove = host.invalidationCount

      host.moveItem(mountItem, 0, 1)

      assertThat(host.invalidationCount).isEqualTo(invalidationCountBeforeMove + 1)
    } finally {
      ComponentsConfiguration.enableComponentHostViewMoveInvalidation = originalValue
    }
  }

  @Test
  fun testDispatchDrawWithReentrantDispatchDrawDoesNotCrash() {
    // Regression test for T227433009: a Drawable.draw() callback that re-enters
    // host.dispatchDraw() resets and clears the shared InterleavedDispatchDraw state
    // mid-pass. The outer pass must keep drawing with its entry canvas instead of
    // throwing IllegalArgumentException ("Required value was null").
    var reentered = false
    val nestedCanvas = Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
    val reentrant =
        object : Drawable() {
          var draws = 0

          override fun draw(canvas: Canvas) {
            draws++
            if (!reentered) {
              reentered = true
              host.dispatchDraw(nestedCanvas)
            }
          }

          override fun setAlpha(alpha: Int) = Unit

          override fun setColorFilter(colorFilter: ColorFilter?) = Unit

          override fun getOpacity(): Int = PixelFormat.OPAQUE
        }
    val trailing =
        object : Drawable() {
          var draws = 0

          override fun draw(canvas: Canvas) {
            draws++
          }

          override fun setAlpha(alpha: Int) = Unit

          override fun setColorFilter(colorFilter: ColorFilter?) = Unit

          override fun getOpacity(): Int = PixelFormat.OPAQUE
        }
    mount(0, reentrant).isBound = true
    mount(1, trailing).isBound = true

    host.dispatchDraw(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)))

    assertThat(reentered).isTrue()
    assertThat(reentrant.draws).isEqualTo(2)
    assertThat(trailing.draws).isEqualTo(2)
  }

  @Test
  fun testDisappearingItems() {
    val v1 = View(context.androidContext)
    mount(0, v1)
    val d1 = ColorDrawable(Color.BLACK)
    val mountItem1 = mount(1, d1)
    val v2 = View(context.androidContext)
    val mountItem2 = mount(2, v2)
    val d2 = ColorDrawable(Color.BLACK)
    mount(3, d2)
    assertThat(host.mountItemCount).isEqualTo(4)
    assertThat(host.childCount).isEqualTo(2)
    assertThat(host.hasDisappearingItems()).isFalse
    host.startUnmountDisappearingItem(1, mountItem1)
    assertThat(host.mountItemCount).isEqualTo(3)
    assertThat(host.childCount).isEqualTo(2)
    assertThat(host.hasDisappearingItems()).isTrue
    var wasRemoved: Boolean = host.finaliseDisappearingItem(mountItem1)
    assertThat(wasRemoved).isTrue
    assertThat(host.mountItemCount).isEqualTo(3)
    assertThat(host.childCount).isEqualTo(2)
    assertThat(host.hasDisappearingItems()).isFalse
    host.startUnmountDisappearingItem(2, mountItem2)
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.childCount).isEqualTo(2)
    assertThat(host.hasDisappearingItems()).isTrue
    wasRemoved = host.finaliseDisappearingItem(mountItem2)
    assertThat(wasRemoved).isTrue
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.childCount).isEqualTo(1)
    assertThat(host.hasDisappearingItems()).isFalse
  }

  @Test
  fun testDisappearingItemDrawingOrder() {
    val v1 = View(context.androidContext)
    mount(5, v1)
    val v2 = View(context.androidContext)
    mount(2, v2)
    val v3 = View(context.androidContext)
    val mountItem3 = mount(4, v3)
    val v4 = View(context.androidContext)
    val mountItem4 = mount(0, v4)
    assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(3)
    assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(1)
    assertThat(host.getChildDrawingOrder(host.childCount, 2)).isEqualTo(2)
    assertThat(host.getChildDrawingOrder(host.childCount, 3)).isEqualTo(0)
    assertThat(host.mountItemCount).isEqualTo(4)
    assertThat(host.childCount).isEqualTo(4)

    // mountItem3 started disappearing
    host.startUnmountDisappearingItem(4, mountItem3)
    assertThat(host.mountItemCount).isEqualTo(3)
    assertThat(host.childCount).isEqualTo(4)
    assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(3)
    assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(1)
    assertThat(host.getChildDrawingOrder(host.childCount, 2)).isEqualTo(0)
    assertThat(host.getChildDrawingOrder(host.childCount, 3)).isEqualTo(2)

    // mountItem4 started disappearing. Mounted views draw in mount-index order, but
    // updateChildDrawingOrderIfNeeded() appends disappearing items in the order they started
    // disappearing, so mountItem3 stays behind mountItem4 even though its mount index is higher.
    host.startUnmountDisappearingItem(0, mountItem4)
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.childCount).isEqualTo(4)
    assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
    assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(0)
    assertThat(host.getChildDrawingOrder(host.childCount, 2)).isEqualTo(2)
    assertThat(host.getChildDrawingOrder(host.childCount, 3)).isEqualTo(3)

    // mountItem4 finished disappearing
    var wasRemoved: Boolean = host.finaliseDisappearingItem(mountItem4)
    assertThat(wasRemoved).isTrue
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.childCount).isEqualTo(3)
    assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
    assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(0)
    assertThat(host.getChildDrawingOrder(host.childCount, 2)).isEqualTo(2)

    // mountItem3 finished disappearing
    wasRemoved = host.finaliseDisappearingItem(mountItem3)
    assertThat(wasRemoved).isTrue
    assertThat(host.mountItemCount).isEqualTo(2)
    assertThat(host.childCount).isEqualTo(2)
    assertThat(host.getChildDrawingOrder(host.childCount, 0)).isEqualTo(1)
    assertThat(host.getChildDrawingOrder(host.childCount, 1)).isEqualTo(0)
  }

  @Test
  fun testDrawableItemsSize() {
    assertThat(drawableItemsSize).isEqualTo(0)
    assertThat(drawableItemsSize).isEqualTo(0)
    val d1 = ColorDrawable(Color.BLACK)
    val m1 = mount(0, d1)
    assertThat(drawableItemsSize).isEqualTo(1)
    val d2 = ColorDrawable(Color.BLACK)
    mount(1, d2)
    assertThat(drawableItemsSize).isEqualTo(2)
    unmount(0, m1)
    assertThat(drawableItemsSize).isEqualTo(1)
    val d3 = ColorDrawable(Color.BLACK)
    val m3 = mount(1, d3)
    assertThat(drawableItemsSize).isEqualTo(1)
    unmount(1, m3)
    assertThat(drawableItemsSize).isEqualTo(0)
  }

  @Test
  fun testGetDrawableMountItem() {
    val d1 = ColorDrawable(Color.BLACK)
    val mountItem1 = mount(0, d1)
    val d2 = ColorDrawable(Color.BLACK)
    val mountItem2 = mount(1, d2)
    val d3 = ColorDrawable(Color.BLACK)
    val mountItem3 = mount(5, d3)
    assertThat(getDrawableMountItemAt(0)).isEqualTo(mountItem1)
    assertThat(getDrawableMountItemAt(1)).isEqualTo(mountItem2)
    assertThat(getDrawableMountItemAt(2)).isEqualTo(mountItem3)
  }

  @Test
  fun testGetLinkedDrawableForAnimation() {
    val d1 = ColorDrawable()
    val mountItem1 = mount(0, d1, LithoRenderUnit.LAYOUT_FLAG_MATCH_HOST_BOUNDS)
    val d2 = ColorDrawable()
    val mountItem2 = mount(1, d2)
    val d3 = ColorDrawable()
    val mountItem3 = mount(2, d3, LithoRenderUnit.LAYOUT_FLAG_MATCH_HOST_BOUNDS)
    var drawables = host.linkedDrawablesForAnimation
    assertThat(drawables).hasSize(2)
    assertThat(drawables).contains(d1, d3)
    unmount(0, mountItem1)
    drawables = host.linkedDrawablesForAnimation
    assertThat(drawables).hasSize(1)
    assertThat(drawables).contains(d3)
    unmount(1, mountItem2)
    drawables = host.drawables
    assertThat(drawables).hasSize(1)
    assertThat(drawables).contains(d3)
  }

  @Test
  fun whenTouchExpansionSetOnComponent_shouldHaveTouchDelegateOnParentOfHostView() {
    val component =
        Column.create(context)
            .paddingPx(YogaEdge.ALL, 15)
            .widthPx(100)
            .heightPx(100)
            .child(
                Text.create(context)
                    .text("hello-world")
                    .touchExpansionPx(YogaEdge.ALL, 5)
                    .clickHandler(NoOpEventHandler.getNoOpEventHandler()),
            )
            .child(
                Row.create(context)
                    .wrapInView()
                    .child(
                        TextInput.create(context)
                            .touchExpansionPx(YogaEdge.ALL, 5)
                            .clickHandler(NoOpEventHandler.getNoOpEventHandler()),
                    ),
            )
            .build()
    val testLithoView = lithoTestRule.render { component }
    val delegate = testLithoView.lithoView.touchExpansionDelegate
    assertThat(delegate).describedAs("Should be not null for the host view of the Text").isNotNull
    val child1 = testLithoView.lithoView.getChildAt(0)
    assertThat(child1).isInstanceOf(ComponentHost::class.java)
    assertThat((child1 as ComponentHost).touchExpansionDelegate)
        .describedAs("should be null for the Text")
        .isNull()
    val child2 = testLithoView.lithoView.getChildAt(1)
    assertThat(child2).isInstanceOf(ComponentHost::class.java)
    assertThat((child2 as ComponentHost).touchExpansionDelegate)
        .describedAs("Should be not null for the host view of the Text Input")
        .isNotNull
  }

  private val drawableItemsSize: Int
    get() {
      val drawableItems =
          Whitebox.getInternalState<SparseArrayCompat<*>>(host, "drawableMountItems")
      return Whitebox.invokeMethod(drawableItems, "size")
    }

  private val viewItemsSize: Int
    get() {
      val viewItems = Whitebox.getInternalState<SparseArrayCompat<*>>(host, "viewMountItems")
      return Whitebox.invokeMethod(viewItems, "size")
    }

  private fun getDrawableMountItemAt(index: Int): MountItem {
    val drawableItems = Whitebox.getInternalState<SparseArrayCompat<*>>(host, "drawableMountItems")
    return Whitebox.invokeMethod(drawableItems, "valueAt", index)
  }

  private fun mount(
      index: Int,
      content: Any,
      flags: Int = 0,
      contentDescription: CharSequence? = null,
  ): MountItem {
    val nodeInfo = NodeInfo()
    nodeInfo.contentDescription = contentDescription
    val mountItem =
        MountItemTestHelper.create(
            if (content is Drawable) drawableComponent else viewComponent,
            content,
            context,
            nodeInfo,
            if (content is Drawable) content.bounds else null,
            flags,
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        )
    host.mount(index, mountItem, if (content is Drawable) content.bounds else Rect())
    return mountItem
  }

  private fun unmount(index: Int, view: View, flags: Int): MountItem {
    val nodeInfo = NodeInfo()
    val mountItem =
        MountItemTestHelper.create(
            viewComponent,
            view,
            context,
            nodeInfo,
            null,
            flags,
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        )
    host.unmount(index, mountItem)
    return mountItem
  }

  private fun mountTouchExpansionItem(index: Int, content: Any): MountItem {
    val viewMountItem =
        MountItemTestHelper.create(
            viewComponent,
            content,
            context,
            null,
            Rect(1, 1, 1, 1),
            0,
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        )
    host.mount(index, viewMountItem, Rect())
    return viewMountItem
  }

  private fun unmount(index: Int, mountItem: MountItem) {
    host.unmount(index, mountItem)
  }

  private class TestableComponentHost : ComponentHost {
    var invalidationCount = 0
      private set

    var invalidationRect: Rect? = null
      private set

    var focusRequestCount = 0
      private set

    constructor(context: ComponentContext) : super(context)

    constructor(context: Context) : super(context, null)

    override fun invalidate(dirty: Rect) {
      super.invalidate(dirty)
      trackInvalidation(dirty.left, dirty.top, dirty.right, dirty.bottom)
    }

    override fun invalidate(l: Int, t: Int, r: Int, b: Int) {
      super.invalidate(l, t, r, b)
      trackInvalidation(l, t, r, b)
    }

    override fun invalidate() {
      super.invalidate()
      trackInvalidation(0, 0, width, height)
    }

    override fun onViewAdded(child: View) {
      super.onViewAdded(child)
      trackInvalidation(child.left, child.top, child.right, child.bottom)
    }

    override fun onViewRemoved(child: View) {
      super.onViewRemoved(child)
      trackInvalidation(child.left, child.top, child.right, child.bottom)
    }

    override fun getDescendantFocusability(): Int {
      focusRequestCount++
      return super.getDescendantFocusability()
    }

    private fun trackInvalidation(l: Int, t: Int, r: Int, b: Int) {
      invalidationCount++
      invalidationRect = Rect().apply { this[l, t, r] = b }
    }
  }

  /** Open to allow mocking. */
  private open class TouchableDrawable : ColorDrawable(), Touchable {
    override fun onTouchEvent(event: MotionEvent, host: View): Boolean = true

    override fun shouldHandleTouchEvent(event: MotionEvent): Boolean = true
  }

  companion object {
    private const val drawableComponentKey = "drawable_key"
    private const val viewComponentKey = "view_key"
  }
}
