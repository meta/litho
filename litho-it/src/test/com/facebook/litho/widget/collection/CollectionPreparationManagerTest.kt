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

package com.facebook.litho.widget.collection

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.LayoutInfo
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.meta.rangemanager.PrepareScope
import java.util.Collections
import java.util.IdentityHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionPreparationManagerTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Mock private lateinit var mockLayoutInfo: LayoutInfo
  @Mock private lateinit var mockAdapter: CollectionPrimitiveViewAdapter
  @Mock private lateinit var mockRecyclerView: RecyclerView
  @Mock private lateinit var mockLayoutManager: LinearLayoutManager
  @Mock private lateinit var mockLatestCommittedData: LatestCommittedData

  private lateinit var preparationManager: CollectionPreparationManager

  // Track which items have been prepared/unprepared
  private val enteredItems = mutableSetOf<Int>()
  private val exitedItems = mutableSetOf<Int>()

  @Before
  fun setup() {
    MockitoAnnotations.initMocks(this)

    whenever(mockAdapter.enteredItems).thenReturn(Collections.newSetFromMap(IdentityHashMap()))
    whenever(mockLayoutInfo.getLayoutManager()).thenReturn(mockLayoutManager)
    whenever(mockLayoutManager.stackFromEnd).thenReturn(false)
    whenever(mockLayoutInfo.findFirstVisibleItemPosition()).thenReturn(0)
    whenever(mockLayoutInfo.findLastVisibleItemPosition()).thenReturn(0)
    whenever(mockLayoutInfo.approximateRangeSize(any(), any(), any(), any())).thenReturn(5)

    preparationManager = CollectionPreparationManager(mockLayoutInfo, mockAdapter)

    // Clear tracking sets
    enteredItems.clear()
    exitedItems.clear()
  }

  private fun createTestItem(index: Int): CollectionItem<View> {
    val renderInfo =
        ComponentRenderInfo.create()
            .component(mock<Component>())
            .customAttribute(CollectionItem.ID_CUSTOM_ATTR_KEY, "item_$index")
            .build()
    return TestCollectionItem(index, 1, renderInfo)
  }

  private class TestCollectionItem(
      id: Int,
      viewType: Int,
      renderInfo: ComponentRenderInfo,
  ) : CollectionItem<View>(id, viewType, renderInfo) {

    override val size: IntSize = IntSize(100, 100)
    override val sizeConstraints: SizeConstraints? = SizeConstraints.exact(100, 100)

    override fun measure(sizeConstraints: SizeConstraints, result: IntArray?) {
      result?.let { resultArray ->
        resultArray[0] = 100
        resultArray[1] = 100
      }
    }

    override fun ItemSizeConstraintsProviderScope.onBindView(view: View) = Unit

    override fun onViewRecycled(view: View) = Unit

    override fun PrepareScope.prepare() = Unit

    override fun prepareAsync(sizeConstraints: SizeConstraints) = Unit

    override fun PrepareScope.unprepare() = Unit
  }

  private fun setupItemsAndCallbacks(itemCount: Int) {
    val items = (0 until itemCount).map { createTestItem(it) }
    whenever(mockAdapter.getItems()).thenReturn(items)
    whenever(mockAdapter.isCircular).thenReturn(false)

    // Create a mock layout scope that tracks enter/exit calls
    val mockLayoutScope = mock<CollectionLayoutScope>()
    whenever(mockLayoutScope.collectionItems).thenReturn(items)
    whenever(mockLatestCommittedData.createLayoutScope()).thenReturn(mockLayoutScope)
    whenever(mockAdapter.latestCommittedData).thenReturn(mockLatestCommittedData)

    // Setup callbacks that track which items enter/exit by finding their index
    // This is the correct pattern - use indexOf to get the actual item index
    val onEnterRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      val index = mockAdapter.getItems().indexOf(item)
      if (index >= 0) {
        enteredItems.add(index)
      }
    }
    val onExitRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      val index = mockAdapter.getItems().indexOf(item)
      if (index >= 0) {
        exitedItems.add(index)
      }
    }

    preparationManager.bind(mockRecyclerView, 2.0f, onEnterRange, onExitRange)
  }

  @Test
  fun `items that a data change moves out of the range exit it`() {
    setupItemsAndCallbacks(itemCount = 30)
    val exited = mutableListOf<CollectionItem<*>>()
    val onExitRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      exited.add(item)
    }
    preparationManager.bind(mockRecyclerView, 2.0f, { _ -> }, onExitRange)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))
    preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)
    val prepared = mockAdapter.getItems().take(5)

    // Viewport stays at 0..4; 40 inserted rows move the prepared objects past the range.
    val newItems = (100 until 140).map { index -> createTestItem(index) } + mockAdapter.getItems()
    whenever(mockAdapter.getItems()).thenReturn(newItems)
    whenever(mockLatestCommittedData.createLayoutScope().collectionItems).thenReturn(newItems)
    preparationManager.onItemsChanged()
    assertThat(exited).isEmpty()
    preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)

    assertThat(exited).containsAll(prepared)
  }

  @Test
  fun `an exit that brings other items back into range does not exit them`() {
    setupItemsAndCallbacks(itemCount = 60)
    val previouslyPrepared = mockAdapter.getItems().take(16)
    val exited = mutableListOf<CollectionItem<*>>()
    val onExitRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      if (item in previouslyPrepared) {
        exited.add(item)
        if (exited.size == 1) {
          preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)
        }
      }
    }
    preparationManager.bind(mockRecyclerView, 2.0f, { _ -> }, onExitRange)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))
    preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)

    // The first exit scrolls back before the remaining items' exit callbacks run.
    preparationManager.maybePostUpdateViewportAndComputeRange(35, 39)

    assertThat(exited).hasSize(1)
  }

  @Test
  fun `a replacement manager finishes exits interrupted by an unbind`() {
    setupItemsAndCallbacks(itemCount = 60)
    val previouslyPrepared = mockAdapter.getItems().take(16)
    val exited = mutableListOf<CollectionItem<*>>()
    val onExitRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      exited.add(item)
      preparationManager.unbind(mockRecyclerView)
    }
    preparationManager.bind(mockRecyclerView, 2.0f, { _ -> }, onExitRange)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))
    preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)

    // An inner row's cleanup can recycle its outer row and unbind the inner manager.
    preparationManager.maybePostUpdateViewportAndComputeRange(35, 39)
    assertThat(exited).hasSize(1)
    val replacement = CollectionPreparationManager(mockLayoutInfo, mockAdapter)
    val finishExit: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      exited.add(item)
    }
    replacement.bind(mockRecyclerView, 2.0f, { _ -> }, finishExit)
    replacement.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))

    replacement.maybePostUpdateViewportAndComputeRange(35, 39)

    assertThat(exited).containsExactlyInAnyOrderElementsOf(previouslyPrepared)
  }

  @Test
  fun `a replacement manager prepares the tracked items for its layout`() {
    setupItemsAndCallbacks(itemCount = 30)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))
    preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)
    val prepared = mockAdapter.getItems().take(5)
    val entered = mutableListOf<CollectionItem<*>>()
    val onEnterRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      entered.add(item)
    }
    preparationManager.unbind(mockRecyclerView)
    val replacement = CollectionPreparationManager(mockLayoutInfo, mockAdapter)
    replacement.bind(mockRecyclerView, 2.0f, onEnterRange, { _ -> })
    replacement.estimateItemsInViewPort(IntSize(2000, 1000), IntSize(100, 100))

    replacement.maybePostUpdateViewportAndComputeRange(0, 4)

    assertThat(entered).containsAll(prepared)
  }

  @Test
  fun `a data change prepares an existing item with new render info`() {
    setupItemsAndCallbacks(itemCount = 30)
    val entered = mutableListOf<CollectionItem<*>>()
    val onEnterRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit) = { item ->
      entered.add(item)
    }
    preparationManager.bind(mockRecyclerView, 2.0f, onEnterRange, { _ -> })
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))
    preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)
    val updated = mockAdapter.getItems().first()
    updated.renderInfo = createTestItem(index = 100).renderInfo
    entered.clear()

    preparationManager.onItemsChanged()

    assertThat(entered).contains(updated)
  }

  @Test
  fun `test second range computation with same range does not call onEnterRange again`() {
    // This is the KEY optimization test!
    // First computation: items enter
    // Second computation with SAME range: NO items should enter (they're already prepared)
    // This verifies entered items are not prepared again

    setupItemsAndCallbacks(20)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))

    // First computation
    preparationManager.maybePostUpdateViewportAndComputeRange(5, 9)
    val initialEnteredCount = enteredItems.size
    assertThat(initialEnteredCount).isGreaterThan(0)

    // Clear tracking and compute again with SAME range
    enteredItems.clear()
    exitedItems.clear()

    preparationManager.maybePostUpdateViewportAndComputeRange(5, 9)

    // KEY ASSERTION: No items should enter again because they're already prepared
    assertThat(enteredItems).isEmpty()
    // No items should exit either
    assertThat(exitedItems).isEmpty()
  }

  @Test
  fun `test scrolling forward enters new items only`() {
    // Scroll forward: previously prepared items should NOT enter again (optimization working)

    setupItemsAndCallbacks(30)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))

    // First computation at position 5-9
    preparationManager.maybePostUpdateViewportAndComputeRange(5, 9)
    val firstCallItems = enteredItems.toSet()
    assertThat(firstCallItems).isNotEmpty()

    // Scroll forward to position 10-14
    enteredItems.clear()
    exitedItems.clear()

    preparationManager.maybePostUpdateViewportAndComputeRange(10, 14)

    // KEY ASSERTION: Items from first call should NOT enter again (already prepared)
    // This verifies the optimization prevents redundant onEnterRange calls
    val itemsThatShouldNotEnterAgain = firstCallItems.intersect(enteredItems)
    assertThat(itemsThatShouldNotEnterAgain).isEmpty()

    // But some new items should enter (not all ranges are the same)
    assertThat(enteredItems).isNotEmpty()
  }

  @Test
  fun `test scrolling backward enters and exits items`() {
    // Scroll backward: some items exit, new items enter

    setupItemsAndCallbacks(30)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))

    // Start at a higher position
    preparationManager.maybePostUpdateViewportAndComputeRange(15, 19)
    val firstCallItems = enteredItems.toSet()

    // Scroll back
    enteredItems.clear()
    exitedItems.clear()

    preparationManager.maybePostUpdateViewportAndComputeRange(5, 9)

    // Some items should enter (new to range) and some should exit (left range)
    assertThat(enteredItems.size).isGreaterThan(0)
    assertThat(exitedItems.size).isGreaterThan(0)

    // KEY ASSERTION: Items from first call that are STILL in range should NOT enter again
    val itemsThatShouldNotEnterAgain = firstCallItems.intersect(enteredItems)
    assertThat(itemsThatShouldNotEnterAgain).isEmpty()
  }

  @Test
  fun `test range computation without hasApproximateRangeSize does not prepare items`() {
    // computeRange returns early if estimatedItemsInViewPort is UNSET
    // This verifies the optimization doesn't activate prematurely

    setupItemsAndCallbacks(20)
    // Don't call estimateItemsInViewPort - hasApproximateRangeSize will be false

    assertThat(preparationManager.hasApproximateRangeSize).isFalse()

    enteredItems.clear()
    exitedItems.clear()

    preparationManager.maybePostUpdateViewportAndComputeRange(5, 9)

    // No items should be prepared because estimatedItemsInViewPort is not set
    assertThat(enteredItems).isEmpty()
    assertThat(exitedItems).isEmpty()
  }

  @Test
  fun `test onEnter is called for correct specific items in calculated range`() {
    // Verify that onEnterRange is called for the CORRECT items based on range formula
    // This ensures the optimization didn't break which items get prepared
    //
    // Given: visible 5-9, estimatedItems=5, rangeRatio=2.0
    // rangeSize = max(5, 9-5) = 5
    // rangeStart = 5 - (5 * 2.0) = -5 -> clamped to 0
    // rangeEnd = 5 + 5 + (5 * 2.0) = 20 -> clamped to 19 (totalSize-1)
    // Expected: items 0-19 should enter

    setupItemsAndCallbacks(20)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))

    enteredItems.clear()
    exitedItems.clear()

    preparationManager.maybePostUpdateViewportAndComputeRange(5, 9)

    // Verify EXACTLY which items entered (based on range calculation)
    assertThat(enteredItems)
        .containsExactlyInAnyOrder(
            0,
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            12,
            13,
            14,
            15,
            16,
            17,
            18,
            19,
        )
    assertThat(exitedItems).isEmpty()
  }

  @Test
  fun `test onExit is called for correct specific items leaving range`() {
    // Verify that onExitRange is called for the CORRECT items when they leave the range
    // This ensures the optimization tracks exits properly
    //
    // First call: visible 10-14 -> should prepare roughly 0-24
    // Second call: visible 0-4 -> should prepare roughly 0-14
    // Expected: items 15-24 should EXIT (they're no longer in the new range)

    setupItemsAndCallbacks(30)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))

    // First: prepare items for visible 10-14
    preparationManager.maybePostUpdateViewportAndComputeRange(10, 14)
    val firstCallItems = enteredItems.toSet()

    // Second: scroll to visible 0-4
    enteredItems.clear()
    exitedItems.clear()

    preparationManager.maybePostUpdateViewportAndComputeRange(0, 4)

    // Verify that items from first call that are NO LONGER in range exit
    // Items 15-24 were in first range but not in second range
    // (The exact items depend on range calculation, but we know some high-index items should exit)
    assertThat(exitedItems).isNotEmpty()

    // All exited items should have been in the first call
    assertThat(firstCallItems).containsAll(exitedItems)

    // Items that exited should NOT be entering (they're leaving!)
    assertThat(enteredItems.intersect(exitedItems)).isEmpty()
  }

  @Test
  fun `test only new items enter when ranges partially overlap`() {
    // Verify that when ranges overlap, only NEWLY entering items call onEnter
    // This directly tests tracking items already in the prepared range
    //
    // First: visible 5-9 -> prepares some range
    // Second: visible 7-11 -> overlaps with first, should only enter NEW items

    setupItemsAndCallbacks(30)
    preparationManager.estimateItemsInViewPort(IntSize(1000, 2000), IntSize(100, 100))

    // First call at 5-9
    preparationManager.maybePostUpdateViewportAndComputeRange(5, 9)
    val firstCallItems = enteredItems.toSet()
    assertThat(firstCallItems).isNotEmpty()

    // Second call at 7-11 (overlaps with first)
    enteredItems.clear()
    exitedItems.clear()

    preparationManager.maybePostUpdateViewportAndComputeRange(7, 11)

    // KEY: Items from first call that are STILL in range should NOT enter again
    val overlappingItems = firstCallItems.intersect(enteredItems)
    assertThat(overlappingItems).isEmpty()

    // But some new items should enter
    assertThat(enteredItems).isNotEmpty()

    // All entering items should be NEW (not from first call)
    assertThat(firstCallItems).doesNotContainAnyElementsOf(enteredItems)
  }
}
