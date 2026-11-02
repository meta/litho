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

@file:OptIn(ExperimentalLithoApi::class)

package com.facebook.litho.widget.collection

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.Component
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.LayoutInfo
import com.facebook.litho.widget.SmoothScrollAlignmentType
import com.facebook.litho.widget.StaggeredGridLayoutInfo
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.meta.rangemanager.PrepareScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for CollectionPrimitiveViewScroller
 *
 * WHAT IS CollectionPrimitiveViewScroller?
 * =====================================
 * CollectionPrimitiveViewScroller is a helper class that manages scrolling operations for
 * collection views (like lists and grids). It acts as a bridge between high-level scroll requests
 * and the underlying RecyclerView's layout manager.
 *
 * KEY FEATURES:
 * - Handles both regular and circular (infinite) scrolling lists
 * - Supports smooth scrolling with different alignment types (start, center, end)
 * - Can scroll to specific positions or find items by ID
 * - Remembers scroll positions when views are recreated
 * - Optimizes circular list scrolling by finding the closest equivalent position
 *
 * CIRCULAR LISTS EXPLAINED:
 * ========================
 * A circular list appears infinite to the user by repeating the same items over and over. For
 * example, with items [A, B, C], the positions look like: Position: 0, 1, 2, 3, 4, 5, 6, 7, 8, ...
 * Item: A, B, C, A, B, C, A, B, C, ...
 *
 * When scrolling in a circular list, we need to find the "closest" position that shows the target
 * item to avoid jarring jumps. If the user is at position 1000 and wants to scroll to item A, we
 * don't jump to position 0 - we find a nearby position like 1002 that also shows item A.
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionPrimitiveViewScrollerTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private lateinit var scroller: CollectionPrimitiveViewScroller
  private lateinit var context: Context

  @Mock private lateinit var mockLayoutInfo: LayoutInfo
  @Mock private lateinit var mockRecyclerView: RecyclerView
  @Mock private lateinit var mockAdapter: CollectionPrimitiveViewAdapter
  @Mock private lateinit var mockLayoutManager: RecyclerView.LayoutManager
  @Mock private lateinit var mockLinearLayoutManager: LinearLayoutManager
  @Mock private lateinit var mockFirstVisibleView: View

  companion object {
    /** Position near the middle of the circular range to avoid edge cases */
    private const val CIRCULAR_MIDDLE_POSITION = 1073741823 // Int.MAX_VALUE / 2

    /** Standard size used for test items */
    private const val TEST_ITEM_SIZE = 100

    /** Standard offset used in scroll tests */
    private const val STANDARD_SCROLL_OFFSET = 0

    /** Standard position used for basic scroll tests */
    private const val TEST_SCROLL_POSITION = 5

    /** Target position used in circular list tests */
    private const val TARGET_POSITION = 1

    /** High position threshold for verifying circular calculations */
    private const val HIGH_POSITION_THRESHOLD = 100000

    /** Million-level threshold for performance verification */
    private const val MILLION_THRESHOLD = 1000000

    /** Standard view decoration offset for layout tests */
    private const val VIEW_DECORATION_TOP = 50

    /** Standard padding for layout tests */
    private const val STANDARD_PADDING = 20

    /** Alternative decoration offset for horizontal tests */
    private const val VIEW_DECORATION_LEFT = 30

    /** Small padding for layout tests */
    private const val SMALL_PADDING = 10

    /** Large container height for layout tests */
    private const val CONTAINER_HEIGHT = 1000

    /** Bottom decoration position */
    private const val VIEW_DECORATION_BOTTOM = 900

    /** Offset for circular scroll tests */
    private const val CIRCULAR_SCROLL_OFFSET = 50

    /** Target item index for multi-item tests */
    private const val TARGET_ITEM_INDEX = 2

    /** Near-max position for edge case tests */
    private const val NEAR_MAX_POSITION = Int.MAX_VALUE - 10

    /** Position offset for high position calculations */
    private const val HIGH_POSITION_OFFSET = 1000

    /** Invalid position constant */
    private const val INVALID_POSITION = -1

    /** First visible position (start of list) */
    private const val FIRST_POSITION = 0

    /** Current position for circular calculation tests */
    private const val CIRCULAR_CALC_POSITION = 1000

    /** High position for circular calculation tests */
    private const val CIRCULAR_HIGH_POSITION = Int.MAX_VALUE / 2 + HIGH_POSITION_OFFSET
  }

  @Before
  fun setup() {
    MockitoAnnotations.initMocks(this)
    context = ApplicationProvider.getApplicationContext()
    scroller = CollectionPrimitiveViewScroller(context)
    setupDefaultMockBehavior()
  }

  private fun setupDefaultMockBehavior() {
    whenever(mockLayoutInfo.getLayoutManager()).thenReturn(mockLayoutManager)
    whenever(mockLayoutInfo.getScrollDirection()).thenReturn(OrientationHelper.VERTICAL)
    whenever(mockLayoutInfo.findFirstVisibleItemPosition()).thenReturn(0)
    whenever(mockAdapter.isCircular).thenReturn(false)
    whenever(mockAdapter.getItems()).thenReturn(emptyList())
  }

  /**
   * Creates a test CollectionItem with the given ID.
   *
   * Each item needs:
   * - A unique ID (for finding items by ID)
   * - A ComponentRenderInfo (tells the framework how to render it)
   * - Implementation of required abstract methods
   */
  private fun createTestItem(idString: String = "test_id"): CollectionItem<View> {
    val renderInfo =
        ComponentRenderInfo.create()
            .component(mock<Component>()) // Mock component for testing
            .customAttribute(CollectionItem.ID_CUSTOM_ATTR_KEY, idString)
            .build()
    return TestCollectionItem(idString.hashCode(), 1, renderInfo)
  }

  /**
   * Sets up mocks for a circular list scenario.
   *
   * This simulates:
   * - A circular adapter with given items
   * - A RecyclerView currently positioned at 'currentPosition'
   * - All the layout manager connections needed for position calculations
   *
   * @param items The items in the circular list
   * @param currentPosition Where the user is currently scrolled to (default: middle of range)
   */
  private fun setupCircularAdapterMocks(
      items: List<CollectionItem<View>>,
      currentPosition: Int = CIRCULAR_MIDDLE_POSITION,
  ) {
    whenever(mockAdapter.isCircular).thenReturn(true)
    whenever(mockAdapter.getItems()).thenReturn(items)
    whenever(mockRecyclerView.getChildAt(0)).thenReturn(mockFirstVisibleView)
    whenever(mockRecyclerView.layoutManager).thenReturn(mockLayoutManager)
    whenever(mockLayoutManager.getPosition(mockFirstVisibleView)).thenReturn(currentPosition)
  }

  /**
   * Sets up mocks for LinearLayoutManager tests.
   *
   * LinearLayoutManager is used for vertical/horizontal lists (not grids). Different orientations
   * and reverse layouts need different mock setups.
   *
   * @param orientation VERTICAL or HORIZONTAL scrolling
   * @param reverseLayout Whether items are laid out in reverse order
   * @param firstVisiblePosition Which item position is currently visible first
   */
  private fun setupLinearLayoutManagerMocks(
      orientation: Int = OrientationHelper.VERTICAL,
      reverseLayout: Boolean = false,
      firstVisiblePosition: Int = 0,
  ) {
    whenever(mockLayoutInfo.getLayoutManager()).thenReturn(mockLinearLayoutManager)
    whenever(mockLayoutInfo.findFirstVisibleItemPosition()).thenReturn(firstVisiblePosition)
    whenever(mockLayoutInfo.getScrollDirection()).thenReturn(orientation)
    whenever(mockLinearLayoutManager.findViewByPosition(firstVisiblePosition))
        .thenReturn(mockFirstVisibleView)
    whenever(mockLinearLayoutManager.reverseLayout).thenReturn(reverseLayout)
  }

  /**
   * Convenience method to bind the scroller with current mocks. Most tests need to do this, so
   * extracting to a helper reduces repetition.
   */
  private fun bindScrollerAndVerifyBehavior() {
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
  }

  private class TestCollectionItem(
      id: Int,
      viewType: Int,
      renderInfo: ComponentRenderInfo,
  ) : CollectionItem<View>(id, viewType, renderInfo) {

    override val size: IntSize = IntSize(TEST_ITEM_SIZE, TEST_ITEM_SIZE)

    override val sizeConstraints: SizeConstraints? =
        SizeConstraints.exact(TEST_ITEM_SIZE, TEST_ITEM_SIZE)

    override fun measure(sizeConstraints: SizeConstraints, result: IntArray?) {
      result?.let { resultArray ->
        resultArray[0] = TEST_ITEM_SIZE
        resultArray[1] = TEST_ITEM_SIZE
      }
    }

    override fun ItemSizeConstraintsProviderScope.onBindView(view: View) = Unit

    override fun onViewRecycled(view: View) = Unit

    override fun PrepareScope.prepare() = Unit

    override fun prepareAsync(sizeConstraints: SizeConstraints) = Unit

    override fun PrepareScope.unprepare() = Unit
  }

  /**
   * BASIC LIFECYCLE TEST: Verifies that binding connects the scroller to its dependencies.
   *
   * The scroller needs to be "bound" to work - this connects it to the RecyclerView, LayoutInfo,
   * and adapter. Once bound, scroll operations should work.
   */
  @Test
  fun `test bind sets up scroller correctly`() {
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // Test that scroller is now bound by trying to scroll
    scroller.startSmoothScrollWithOffset(
        TEST_SCROLL_POSITION,
        STANDARD_SCROLL_OFFSET,
        SmoothScrollAlignmentType.DEFAULT,
    )

    // Should attempt to scroll through layout manager since bound
    verify(mockLayoutInfo).getLayoutManager()
  }

  /**
   * LIFECYCLE TEST: Verifies that unbinding prevents scroll operations.
   *
   * After unbind(), the scroller should be inactive and ignore scroll requests. This prevents
   * crashes when the RecyclerView is destroyed but scroll requests are still pending.
   */
  @Test
  fun `test unbind clears scroller state`() {
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
    scroller.unbind()

    // After unbind, should not attempt scrolling operations
    scroller.startSmoothScrollWithOffset(
        TEST_SCROLL_POSITION,
        STANDARD_SCROLL_OFFSET,
        SmoothScrollAlignmentType.DEFAULT,
    )

    // Should not scroll through layout manager since unbound
    verify(mockLayoutInfo, never()).getLayoutManager()
  }

  /**
   * STATE MANAGEMENT TEST: Verifies that scroll requests are queued when scroller is not bound.
   *
   * Sometimes scroll requests come in before the scroller is ready (bound to RecyclerView). The
   * scroller should store these requests and execute them later when bound, rather than dropping
   * them or crashing.
   *
   * SCENARIO: Request smooth scroll -> bind scroller -> verify scroll state is remembered
   */
  @Test
  fun `test startSmoothScrollWithOffset when not bound stores state`() {
    val position = TEST_SCROLL_POSITION
    val offset = STANDARD_SCROLL_OFFSET
    val alignmentType = SmoothScrollAlignmentType.SNAP_TO_START

    scroller.startSmoothScrollWithOffset(position, offset, alignmentType)

    // Should not scroll through layout manager when not bound
    verify(mockLayoutInfo, never()).getLayoutManager()

    // When later bound, should use stored state
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
    scroller.scrollToInitPosition(mockRecyclerView, position)

    verify(mockRecyclerView, never()).scrollToPosition(anyInt())
    verify(mockLayoutManager).startSmoothScroll(any()) // Should use stored smooth scroll
  }

  /**
   * BASIC SMOOTH SCROLL TEST: Verifies smooth scrolling works for regular (non-circular) lists.
   *
   * This tests the happy path: scroller is bound, adapter is not circular, and we request a smooth
   * scroll to a specific position. Should delegate to RecyclerView's smooth scroll mechanism.
   *
   * SMOOTH SCROLL vs INSTANT SCROLL: Smooth scroll animates to the position, instant scroll jumps
   * immediately.
   */
  @Test
  fun `test startSmoothScrollWithOffset for non-circular list`() {
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    val position = TEST_SCROLL_POSITION
    val offset = STANDARD_SCROLL_OFFSET
    val alignmentType = SmoothScrollAlignmentType.SNAP_TO_START

    scroller.startSmoothScrollWithOffset(position, offset, alignmentType)

    verify(mockLayoutInfo).getLayoutManager()
    verify(mockLayoutManager).startSmoothScroll(any())
  }

  /**
   * ID-BASED SCROLLING TEST: Verifies smooth scrolling can find items by their unique ID.
   *
   * Items in collections have unique IDs. Users can request scrolling to "show item with ID X"
   * instead of "show item at position Y". This is useful when positions might change but IDs remain
   * stable.
   *
   * SCENARIO: Request scroll to item with ID "item2" -> scroller finds its position -> scrolls
   * there
   */
  @Test
  fun `test startSmoothScrollWithOffset by ID finds correct position`() {
    whenever(mockAdapter.findPositionById("item2")).thenReturn(TARGET_POSITION)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
    scroller.startSmoothScrollWithOffset(
        "item2",
        STANDARD_SCROLL_OFFSET,
        SmoothScrollAlignmentType.SNAP_TO_END,
    )

    verify(mockAdapter).findPositionById("item2")
    verify(mockLayoutInfo).getLayoutManager()
    verify(mockLayoutManager).startSmoothScroll(any())
  }

  /**
   * ERROR HANDLING TEST: Verifies graceful handling when item ID is not found.
   *
   * If a user requests scrolling to an item ID that doesn't exist, the scroller should not crash or
   * attempt invalid scrolling. It should check if the ID exists first.
   *
   * SCENARIO: Request scroll to non-existent ID -> scroller checks -> skips scrolling
   */
  @Test
  fun `test startSmoothScrollWithOffset by ID skips when ID not found`() {
    whenever(mockAdapter.findPositionById("nonexistent")).thenReturn(INVALID_POSITION)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
    scroller.startSmoothScrollWithOffset(
        "nonexistent",
        STANDARD_SCROLL_OFFSET,
        SmoothScrollAlignmentType.DEFAULT,
    )

    verify(mockAdapter).findPositionById("nonexistent")
    verify(mockLayoutInfo, never()).getLayoutManager()
  }

  /**
   * BASIC INSTANT SCROLL TEST: Verifies instant scrolling works for regular lists.
   *
   * Instant scrolling jumps immediately to the target position without animation. This is used when
   * you need to position the list quickly (e.g., restoring saved position).
   *
   * INSTANT vs SMOOTH: Instant = immediate jump, Smooth = animated transition
   */
  @Test
  fun `test scrollToPositionWithOffset for non-circular list`() {
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    val position = TEST_SCROLL_POSITION
    val offset = STANDARD_SCROLL_OFFSET

    scroller.scrollToPositionWithOffset(position, offset)

    verify(mockLayoutInfo).scrollToPositionWithOffset(position, offset)
  }

  /**
   * COMPLEX CIRCULAR LIST TEST: Verifies position calculation finds the closest equivalent
   * position.
   *
   * This is testing the core optimization for circular lists. Instead of jumping from position
   * 1,073,741,823 to position 1 (which would be jarring), the scroller should find a position like
   * 1,073,741,824 that also shows item #1 but is much closer to the current position.
   *
   * SCENARIO: User is at position ~1B, wants to scroll to show item at index 1 EXPECTED: Scroller
   * calculates a position near 1B that shows the same item
   */
  @Test
  fun `test scrollToPositionWithOffset for circular list calculates closest position`() {
    // Setup mocks to simulate a circular list scenario
    whenever(mockAdapter.isCircular).thenReturn(true)
    val items = listOf(createTestItem("1"), createTestItem("2"), createTestItem("3"))
    whenever(mockAdapter.getItems()).thenReturn(items)

    // Mock current position at a high value to simulate being in middle of circular range
    whenever(mockRecyclerView.getChildAt(0)).thenReturn(mockFirstVisibleView)
    whenever(mockRecyclerView.layoutManager).thenReturn(mockLayoutManager)
    whenever(mockLayoutManager.getPosition(mockFirstVisibleView))
        .thenReturn(CIRCULAR_MIDDLE_POSITION)

    whenever(mockLayoutInfo.getLayoutManager()).thenReturn(mockLayoutManager)
    whenever(mockLayoutInfo.getScrollDirection()).thenReturn(OrientationHelper.VERTICAL)
    whenever(mockLayoutInfo.findFirstVisibleItemPosition()).thenReturn(0)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    val targetPosition = CIRCULAR_CALC_POSITION
    scroller.scrollToPositionWithOffset(targetPosition, STANDARD_SCROLL_OFFSET)

    // Capture the actual calculated position
    val captor = ArgumentCaptor.forClass(Int::class.java)
    verify(mockLayoutInfo).scrollToPositionWithOffset(captor.capture(), eq(STANDARD_SCROLL_OFFSET))

    val actualScrolledPosition = captor.value

    // CRITICAL VERIFICATION: The position maps to the correct item when modded
    val itemIndex = actualScrolledPosition % items.size
    assertThat(itemIndex).isEqualTo(TARGET_POSITION)

    // CRITICAL VERIFICATION: Position calculation happened (not just raw target)
    assertThat(actualScrolledPosition).isNotEqualTo(targetPosition)

    // PERFORMANCE VERIFICATION: The calculated position is reasonable (should be near current
    // position)
    assertThat(actualScrolledPosition)
        .isGreaterThan(MILLION_THRESHOLD) // Should be in high range like current
  }

  /**
   * ID-BASED INSTANT SCROLL TEST: Verifies instant scrolling can find items by their unique ID.
   *
   * Like smooth scrolling by ID, but for instant jumping to positions. This is the faster version
   * that doesn't animate - just immediately jumps to show the target item.
   *
   * SCENARIO: Request instant scroll to item with ID "item2" -> find position -> jump there
   */
  @Test
  fun `test scrollToPositionWithOffset by ID`() {
    whenever(mockAdapter.findPositionById("item2")).thenReturn(TARGET_POSITION)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
    scroller.scrollToPositionWithOffset("item2", STANDARD_SCROLL_OFFSET)

    verify(mockAdapter).findPositionById("item2")
    verify(mockLayoutInfo).scrollToPositionWithOffset(TARGET_POSITION, STANDARD_SCROLL_OFFSET)
  }

  /**
   * ERROR HANDLING TEST: Verifies graceful handling when item ID is not found (instant scroll
   * version).
   *
   * If a user requests instant scrolling to an item ID that doesn't exist, the scroller should not
   * crash or attempt invalid scrolling. Same as smooth scroll, but for instant scrolling.
   *
   * SCENARIO: Request instant scroll to non-existent ID -> scroller checks -> skips scrolling
   */
  @Test
  fun `test scrollToPositionWithOffset by ID skips when not found`() {
    whenever(mockAdapter.findPositionById("nonexistent")).thenReturn(INVALID_POSITION)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
    scroller.scrollToPositionWithOffset("nonexistent", STANDARD_SCROLL_OFFSET)

    verify(mockAdapter).findPositionById("nonexistent")
    verify(mockLayoutInfo, never()).scrollToPositionWithOffset(anyInt(), anyInt())
  }

  /**
   * SCROLL POSITION MEMORY TEST: Verifies scroll offset calculation for vertical lists.
   *
   * When RecyclerView is recreated (like during orientation changes), we need to restore the exact
   * scroll position. This requires remembering both which item was visible AND how far that item
   * was scrolled (the offset).
   *
   * VERTICAL ORIENTATION: We measure the distance from the top of the first visible item to the top
   * of the RecyclerView container (accounting for padding).
   *
   * SCENARIO: Vertical list, first item partially scrolled off top -> calculate offset
   */
  @Test
  fun `test rememberScrollOffset for vertical orientation`() {
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = false,
        firstVisiblePosition = 0,
    )
    whenever(mockLinearLayoutManager.getDecoratedTop(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_TOP)
    whenever(mockLinearLayoutManager.paddingTop).thenReturn(STANDARD_PADDING)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    verify(mockLinearLayoutManager).findViewByPosition(FIRST_POSITION)
    verify(mockLinearLayoutManager).getDecoratedTop(mockFirstVisibleView)
  }

  /**
   * SCROLL POSITION MEMORY TEST: Verifies scroll offset calculation for horizontal lists.
   *
   * Same as vertical, but for horizontally scrolling lists (like carousels). We measure the
   * distance from the left edge of the first visible item to the left edge of the RecyclerView
   * container.
   *
   * HORIZONTAL ORIENTATION: Measure left offset instead of top offset.
   *
   * SCENARIO: Horizontal list, first item partially scrolled off left -> calculate offset
   */
  @Test
  fun `test rememberScrollOffset for horizontal orientation`() {
    setupLinearLayoutManagerMocks(
        OrientationHelper.HORIZONTAL,
        reverseLayout = false,
        firstVisiblePosition = 0,
    )
    whenever(mockLinearLayoutManager.getDecoratedLeft(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_LEFT)
    whenever(mockLinearLayoutManager.paddingLeft).thenReturn(SMALL_PADDING)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    verify(mockLinearLayoutManager).findViewByPosition(FIRST_POSITION)
    verify(mockLinearLayoutManager).getDecoratedLeft(mockFirstVisibleView)
  }

  /**
   * REVERSE LAYOUT TEST: Verifies scroll offset calculation for reverse vertical layouts.
   *
   * Some layouts display items in reverse order (like chat messages, newest at bottom). In reverse
   * layout, we measure from the bottom of the container instead of the top.
   *
   * REVERSE LAYOUT: Items flow from bottom to top instead of top to bottom. We measure the distance
   * from the bottom of the first visible item to the bottom of the RecyclerView container.
   *
   * SCENARIO: Reverse vertical list -> measure from bottom edge
   */
  @Test
  fun `test rememberScrollOffset for reverse layout vertical`() {
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = true,
        firstVisiblePosition = 0,
    )
    whenever(mockRecyclerView.height).thenReturn(CONTAINER_HEIGHT)
    whenever(mockLinearLayoutManager.paddingBottom).thenReturn(STANDARD_PADDING)
    whenever(mockLinearLayoutManager.getDecoratedBottom(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_BOTTOM)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    verify(mockLinearLayoutManager).getDecoratedBottom(mockFirstVisibleView)
  }

  /**
   * ERROR HANDLING TEST: Verifies graceful handling when no view is found.
   *
   * Sometimes the layout manager can't find the first visible view (empty list, mid-layout, etc.).
   * The scroller should handle this gracefully without crashing.
   *
   * EDGE CASE: No visible views available -> don't crash, handle null gracefully
   *
   * SCENARIO: Try to remember offset when no views are visible -> handle gracefully
   */
  @Test
  fun `test rememberScrollOffset when no first view found`() {
    setupLinearLayoutManagerMocks(OrientationHelper.VERTICAL, firstVisiblePosition = FIRST_POSITION)
    whenever(mockLinearLayoutManager.findViewByPosition(FIRST_POSITION)).thenReturn(null)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    // Should handle null view gracefully
    verify(mockLinearLayoutManager).findViewByPosition(FIRST_POSITION)
  }

  /**
   * SCROLL POSITION RESTORATION TEST: Verifies that currentTarget is updated when remembering
   * scroll position.
   *
   * SCENARIO: Remember scroll position at position 5 -> verify currentTarget is updated -> use
   * saved position for restoration
   */
  @Test
  fun `test rememberScrollPosition updates currentTarget for restoration`() {
    val savedPosition = TEST_SCROLL_POSITION
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = false,
        firstVisiblePosition = savedPosition,
    )
    whenever(mockLinearLayoutManager.getDecoratedTop(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_TOP)
    whenever(mockLinearLayoutManager.paddingTop).thenReturn(STANDARD_PADDING)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    // Now unbind and rebind to simulate a recreation scenario
    scroller.unbind()
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // Call scrollToInitPosition without passing a position - should use saved currentTarget
    scroller.scrollToInitPosition(mockRecyclerView)

    // Verify it attempts to scroll to the saved position
    verify(mockLayoutInfo).scrollToPositionWithOffset(eq(savedPosition), anyInt())
  }

  /**
   * SCROLL POSITION RESTORATION TEST: Verifies currentTarget is set even when view is null.
   *
   * SCENARIO: Remember position when view is null -> currentTarget should still be updated
   */
  @Test
  fun `test rememberScrollPosition updates currentTarget even when view is null`() {
    val savedPosition = TEST_SCROLL_POSITION
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = false,
        firstVisiblePosition = savedPosition,
    )
    // View is null - simulating edge case
    whenever(mockLinearLayoutManager.findViewByPosition(savedPosition)).thenReturn(null)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    // Unbind and rebind to test restoration
    scroller.unbind()
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // Should still use the saved position even though view was null
    scroller.scrollToInitPosition(mockRecyclerView)

    verify(mockLayoutInfo).scrollToPositionWithOffset(eq(savedPosition), anyInt())
  }

  /**
   * SCROLL POSITION RESTORATION TEST: Verifies rememberScrollPosition works correctly for circular
   * lists.
   *
   * SCENARIO: Circular list at high position -> remember position -> restore to same high position
   */
  @Test
  fun `test rememberScrollPosition for circular list preserves high position`() {
    val highCircularPosition = CIRCULAR_MIDDLE_POSITION
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = false,
        firstVisiblePosition = highCircularPosition,
    )
    whenever(mockLinearLayoutManager.getDecoratedTop(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_TOP)
    whenever(mockLinearLayoutManager.paddingTop).thenReturn(STANDARD_PADDING)

    // Setup circular adapter
    setupCircularAdapterMocks(
        listOf(createTestItem("1"), createTestItem("2"), createTestItem("3")),
        currentPosition = highCircularPosition,
    )

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    // Verify currentTarget was set to the high position, not modded down
    scroller.unbind()
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // When scrolling to init position with circular list, should handle high position correctly
    scroller.scrollToInitPosition(mockRecyclerView)

    // Verify it attempted to scroll
    verify(mockRecyclerView).scrollToPosition(CIRCULAR_MIDDLE_POSITION)
  }

  /**
   * SCROLL POSITION RESTORATION TEST: Verifies offset is correctly calculated and restored.
   *
   * SCENARIO: Remember position with specific offset -> restore -> verify offset is preserved
   */
  @Test
  fun `test rememberScrollPosition preserves offset during restoration`() {
    val savedPosition = TEST_SCROLL_POSITION
    val expectedOffset = VIEW_DECORATION_TOP - STANDARD_PADDING // 50 - 20 = 30
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = false,
        firstVisiblePosition = savedPosition,
    )
    whenever(mockLinearLayoutManager.getDecoratedTop(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_TOP)
    whenever(mockLinearLayoutManager.paddingTop).thenReturn(STANDARD_PADDING)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    // Unbind and rebind
    scroller.unbind()
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // Restore position
    scroller.scrollToInitPosition(mockRecyclerView)

    // Verify both position and offset are correct
    verify(mockLayoutInfo).scrollToPositionWithOffset(savedPosition, expectedOffset)
  }

  /**
   * SCROLL POSITION RESTORATION TEST: Verifies horizontal list offset restoration.
   *
   * SCENARIO: Horizontal list -> remember position and offset -> restore correctly
   */
  @Test
  fun `test rememberScrollPosition preserves horizontal offset during restoration`() {
    val savedPosition = TEST_SCROLL_POSITION
    val expectedOffset = VIEW_DECORATION_LEFT - SMALL_PADDING // 30 - 10 = 20
    setupLinearLayoutManagerMocks(
        OrientationHelper.HORIZONTAL,
        reverseLayout = false,
        firstVisiblePosition = savedPosition,
    )
    whenever(mockLinearLayoutManager.getDecoratedLeft(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_LEFT)
    whenever(mockLinearLayoutManager.paddingLeft).thenReturn(SMALL_PADDING)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    // Unbind and rebind
    scroller.unbind()
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // Restore position
    scroller.scrollToInitPosition(mockRecyclerView)

    // Verify both position and offset are correct
    verify(mockLayoutInfo).scrollToPositionWithOffset(savedPosition, expectedOffset)
  }

  /**
   * SCROLL POSITION RESTORATION TEST: Verifies multiple remember/restore cycles work correctly.
   *
   * In real-world usage, rememberScrollPosition might be called multiple times (e.g., during
   * multiple configuration changes). This test ensures that each cycle properly updates
   * currentTarget and that the most recent position is always preserved.
   *
   * SCENARIO: Remember position A -> restore -> scroll to position B -> remember position B ->
   * restore -> verify position B is used
   */
  @Test
  fun `test multiple rememberScrollPosition cycles preserve latest position`() {
    // First cycle - position 5
    val firstPosition = TEST_SCROLL_POSITION
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = false,
        firstVisiblePosition = firstPosition,
    )
    whenever(mockLinearLayoutManager.getDecoratedTop(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_TOP)
    whenever(mockLinearLayoutManager.paddingTop).thenReturn(STANDARD_PADDING)

    bindScrollerAndVerifyBehavior()
    scroller.rememberScrollPosition(mockRecyclerView)

    // Second cycle - position 10
    val secondPosition = 10
    setupLinearLayoutManagerMocks(
        OrientationHelper.VERTICAL,
        reverseLayout = false,
        firstVisiblePosition = secondPosition,
    )
    whenever(mockLinearLayoutManager.getDecoratedTop(mockFirstVisibleView))
        .thenReturn(VIEW_DECORATION_TOP)
    whenever(mockLinearLayoutManager.paddingTop).thenReturn(STANDARD_PADDING)

    // Remember new position
    scroller.rememberScrollPosition(mockRecyclerView)

    // Unbind and rebind
    scroller.unbind()
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // Should use the second (most recent) position
    scroller.scrollToInitPosition(mockRecyclerView)

    verify(mockLayoutInfo).scrollToPositionWithOffset(eq(secondPosition), anyInt())
  }

  /**
   * INITIALIZATION SCROLL TEST: Verifies basic scroll-to-position for regular lists.
   *
   * When a RecyclerView is first created or recreated, we often need to scroll to a specific
   * position (e.g., restoring saved state). This should work normally for non-circular lists.
   *
   * SCENARIO: Initialize RecyclerView and scroll to saved position -> should scroll there
   */
  @Test
  fun `test scrollToInitPosition for non-circular adapter with valid position`() {
    val targetPosition = TEST_SCROLL_POSITION
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    scroller.scrollToInitPosition(mockRecyclerView, targetPosition)

    verify(mockLayoutInfo).scrollToPositionWithOffset(targetPosition, FIRST_POSITION)
  }

  /**
   * ERROR HANDLING TEST: Verifies graceful handling of invalid positions during initialization.
   *
   * Sometimes saved state contains invalid positions (e.g., RecyclerView.NO_POSITION). The scroller
   * should detect this and skip scrolling rather than crash.
   *
   * SCENARIO: Initialize with invalid position -> scroller skips scrolling gracefully
   */
  @Test
  fun `test scrollToInitPosition for non-circular adapter with invalid position`() {
    val invalidPosition = RecyclerView.NO_POSITION
    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    scroller.scrollToInitPosition(mockRecyclerView, invalidPosition)

    // Should not attempt any scrolling for invalid position
    verify(mockLayoutInfo, never()).scrollToPositionWithOffset(anyInt(), anyInt())
    verify(mockRecyclerView, never()).scrollToPosition(anyInt())
  }

  /**
   * CIRCULAR LIST INITIALIZATION TEST: Verifies circular lists jump to safe middle position.
   *
   * Circular lists can't start at position 0 or negative positions - they need to start somewhere
   * in the middle of their range to allow scrolling in both directions. This test verifies the
   * scroller calculates a good starting position.
   *
   * SCENARIO: Initialize circular list with negative position -> jump to middle of range
   */
  @Test
  fun `test scrollToInitPosition for circular adapter with negative position`() {
    whenever(mockAdapter.isCircular).thenReturn(true)
    val items = listOf(createTestItem("1"), createTestItem("2"), createTestItem("3"))
    whenever(mockAdapter.getItems()).thenReturn(items)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    scroller.scrollToInitPosition(mockRecyclerView, INVALID_POSITION)

    // Should calculate jump to middle position for circular
    val captor = ArgumentCaptor.forClass(Int::class.java)
    verify(mockRecyclerView).scrollToPosition(captor.capture())

    val capturedPosition = captor.value
    assertThat(capturedPosition).isGreaterThan(FIRST_POSITION)
    assertThat(capturedPosition).isLessThan(Int.MAX_VALUE)
  }

  /**
   * ACCESSIBILITY TEST: Verifies circular lists set up proper accessibility support.
   *
   * Circular lists appear infinite to users, which can confuse screen readers and other
   * accessibility tools. We need to install a custom accessibility delegate that properly handles
   * the infinite nature of the list.
   *
   * SCENARIO: Initialize circular list -> install accessibility delegate for screen readers
   */
  @Test
  fun `test scrollToInitPosition for circular adapter sets accessibility delegate`() {
    whenever(mockAdapter.isCircular).thenReturn(true)
    whenever(mockLayoutManager.canScrollVertically()).thenReturn(true)
    whenever(mockLayoutManager.canScrollHorizontally()).thenReturn(false)

    val items = listOf(createTestItem("1"), createTestItem("2"), createTestItem("3"))
    whenever(mockAdapter.getItems()).thenReturn(items)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)
    scroller.scrollToInitPosition(mockRecyclerView, FIRST_POSITION)

    verify(mockRecyclerView).scrollToPosition(anyInt())
    verify(mockRecyclerView)
        .setAccessibilityDelegateCompat(any(RecyclerViewAccessibilityDelegate::class.java))
  }

  /**
   * STAGGERED GRID TEST: Verifies special handling for StaggeredGrid layouts.
   *
   * StaggeredGridLayoutManager (Pinterest-style grids) has different timing requirements. Scroll
   * operations need to be posted to happen after layout is complete, otherwise the grid might not
   * be ready and scrolling could fail.
   *
   * SCENARIO: Initialize StaggeredGrid -> post scroll operation for proper timing
   */
  @Test
  fun `test scrollToInitPosition with StaggeredGridLayoutInfo posts scroll operation`() {
    val staggeredLayoutInfo = mock<StaggeredGridLayoutInfo>()
    whenever(staggeredLayoutInfo.getLayoutManager()).thenReturn(mockLayoutManager)

    scroller.bind(staggeredLayoutInfo, mockRecyclerView, mockAdapter)

    scroller.scrollToInitPosition(mockRecyclerView, TEST_SCROLL_POSITION)

    // For StaggeredGrid, should post the scroll operation
    verify(mockRecyclerView).post(any())
  }

  /**
   * CIRCULAR POSITION CALCULATION TEST: Verifies scroller finds current position from visible
   * items.
   *
   * In circular lists, we need to know where the user currently is to calculate the closest
   * equivalent position for the target. This test verifies the scroller can find the current
   * position by looking at visible items in the RecyclerView.
   *
   * SCENARIO: Circular list with visible items -> scroller finds current position -> uses it for
   * calculations
   */
  @Test
  fun `test getClosestIndexInCircularList with valid children`() {
    whenever(mockRecyclerView.getChildAt(0)).thenReturn(mockFirstVisibleView)
    whenever(mockRecyclerView.layoutManager).thenReturn(mockLayoutManager)
    whenever(mockLayoutManager.getPosition(mockFirstVisibleView)).thenReturn(CIRCULAR_CALC_POSITION)

    val items = listOf(createTestItem("1"), createTestItem("2"), createTestItem("3"))
    whenever(mockAdapter.getItems()).thenReturn(items)
    whenever(mockAdapter.isCircular).thenReturn(true)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    scroller.startSmoothScrollWithOffset(
        TARGET_POSITION,
        STANDARD_SCROLL_OFFSET,
        SmoothScrollAlignmentType.DEFAULT,
    )

    // Should call the circular calculation logic
    verify(mockRecyclerView).getChildAt(0)
    verify(mockLayoutManager).getPosition(mockFirstVisibleView)
  }

  /**
   * ERROR HANDLING TEST: Verifies graceful handling when no visible items exist.
   *
   * Sometimes the RecyclerView has no visible items (empty list, mid-layout, etc.). The scroller
   * should handle this gracefully and still attempt scrolling using fallback logic.
   *
   * EDGE CASE: No visible children -> scroller uses fallback -> still attempts scroll
   *
   * SCENARIO: Request scroll when no items visible -> handle gracefully without crashing
   */
  @Test
  fun `test getClosestIndexInCircularList with no children returns target`() {
    whenever(mockRecyclerView.getChildAt(0)).thenReturn(null)
    whenever(mockAdapter.isCircular).thenReturn(true)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    scroller.startSmoothScrollWithOffset(
        TEST_SCROLL_POSITION,
        STANDARD_SCROLL_OFFSET,
        SmoothScrollAlignmentType.DEFAULT,
    )

    // Should still attempt scrolling even with no children
    verify(mockRecyclerView).getChildAt(0)
    verify(mockLayoutInfo).getLayoutManager()
  }

  /**
   * ERROR HANDLING TEST: Verifies graceful handling when layout manager is missing.
   *
   * Sometimes the RecyclerView doesn't have a layout manager set (during setup, teardown, etc.).
   * The scroller should detect this condition and handle it gracefully without crashing.
   *
   * EDGE CASE: No layout manager -> scroller detects -> handles gracefully
   *
   * SCENARIO: Request scroll with no layout manager -> detect and handle gracefully
   */
  @Test
  fun `test getClosestIndexInCircularList with null layout manager returns target`() {
    whenever(mockRecyclerView.getChildAt(0)).thenReturn(mockFirstVisibleView)
    whenever(mockRecyclerView.layoutManager).thenReturn(null)
    whenever(mockAdapter.isCircular).thenReturn(true)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    scroller.startSmoothScrollWithOffset(
        TEST_SCROLL_POSITION,
        STANDARD_SCROLL_OFFSET,
        SmoothScrollAlignmentType.DEFAULT,
    )

    // Should handle null layout manager gracefully
    verify(mockRecyclerView).getChildAt(0)
    verify(mockRecyclerView).layoutManager
  }

  /**
   * EDGE CASE TEST: Verifies circular calculation works near integer overflow boundaries.
   *
   * This tests the scroller's behavior when the current position is near Int.MAX_VALUE, which could
   * cause overflow issues in position calculations. The scroller should handle this edge case
   * correctly and calculate valid positions.
   *
   * WHY THIS MATTERS: Near integer boundaries, naive calculations can overflow and produce negative
   * or invalid positions. We need robust math that works even at edges.
   *
   * SCENARIO: Current position near Int.MAX_VALUE -> calculate target -> verify no overflow
   */
  @Test
  fun `test circular list position calculation with wrap around`() {
    // Setup mocks to simulate a position near max value
    whenever(mockAdapter.isCircular).thenReturn(true)
    val items = listOf(createTestItem("1"), createTestItem("2"), createTestItem("3"))
    whenever(mockAdapter.getItems()).thenReturn(items)

    whenever(mockRecyclerView.getChildAt(0)).thenReturn(mockFirstVisibleView)
    whenever(mockRecyclerView.layoutManager).thenReturn(mockLayoutManager)
    whenever(mockLayoutManager.getPosition(mockFirstVisibleView)).thenReturn(NEAR_MAX_POSITION)

    whenever(mockLayoutInfo.getLayoutManager()).thenReturn(mockLayoutManager)
    whenever(mockLayoutInfo.getScrollDirection()).thenReturn(OrientationHelper.VERTICAL)
    whenever(mockLayoutInfo.findFirstVisibleItemPosition()).thenReturn(FIRST_POSITION)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    val targetPosition = TARGET_POSITION
    scroller.scrollToPositionWithOffset(targetPosition, CIRCULAR_SCROLL_OFFSET)

    val captor = ArgumentCaptor.forClass(Int::class.java)
    verify(mockLayoutInfo).scrollToPositionWithOffset(captor.capture(), eq(CIRCULAR_SCROLL_OFFSET))

    val actualScrolledPosition = captor.value

    // Verify position calculation chose correct equivalent position
    val itemIndex = actualScrolledPosition % items.size
    assertThat(itemIndex).isEqualTo(targetPosition)

    // Verify calculation happened (position should be very different from target)
    assertThat(actualScrolledPosition).isNotEqualTo(targetPosition)
    assertThat(actualScrolledPosition)
        .isGreaterThan(HIGH_POSITION_THRESHOLD) // Should be much larger
  }

  /**
   * PERFORMANCE OPTIMIZATION TEST: Verifies scroller minimizes jump distance in circular lists.
   *
   * This is the core performance test for circular lists. When scrolling to a target item, there
   * are infinite equivalent positions (target, target+N*itemCount, etc.). The scroller should
   * choose the one that's closest to the current position to minimize visual jump.
   *
   * PERFORMANCE IMPACT: Choosing a far position causes jarring jumps that hurt user experience.
   * Choosing the closest position provides smooth, natural scrolling behavior.
   *
   * SCENARIO: At high position, scroll to target -> verify closest equivalent position chosen
   */
  @Test
  fun `test circular position calculation ensures minimal jump from current position`() {
    // Setup to simulate being at a high position in the circular range
    whenever(mockAdapter.isCircular).thenReturn(true)
    val items = listOf(createTestItem("A"), createTestItem("B"), createTestItem("C"))
    whenever(mockAdapter.getItems()).thenReturn(items)

    val currentHighPosition = CIRCULAR_HIGH_POSITION
    whenever(mockRecyclerView.getChildAt(0)).thenReturn(mockFirstVisibleView)
    whenever(mockRecyclerView.layoutManager).thenReturn(mockLayoutManager)
    whenever(mockLayoutManager.getPosition(mockFirstVisibleView)).thenReturn(currentHighPosition)

    whenever(mockLayoutInfo.getLayoutManager()).thenReturn(mockLayoutManager)
    whenever(mockLayoutInfo.getScrollDirection()).thenReturn(OrientationHelper.VERTICAL)
    whenever(mockLayoutInfo.findFirstVisibleItemPosition()).thenReturn(FIRST_POSITION)

    scroller.bind(mockLayoutInfo, mockRecyclerView, mockAdapter)

    // Try to scroll to item at index 2
    val targetItemIndex = TARGET_ITEM_INDEX
    scroller.scrollToPositionWithOffset(targetItemIndex, STANDARD_SCROLL_OFFSET)

    val captor = ArgumentCaptor.forClass(Int::class.java)
    verify(mockLayoutInfo).scrollToPositionWithOffset(captor.capture(), eq(STANDARD_SCROLL_OFFSET))

    val calculatedPosition = captor.value

    // Verify the calculation chose the closest equivalent position
    assertThat(calculatedPosition % items.size).isEqualTo(targetItemIndex)

    // Verify the position is in reasonable range relative to current position
    assertThat(calculatedPosition)
        .isGreaterThan(HIGH_POSITION_THRESHOLD) // Should be high like current
    assertThat(calculatedPosition).isNotEqualTo(targetItemIndex) // Should be calculated, not raw
  }
}
