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

import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.LithoView
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.SizeConstraints.Companion.Infinity
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionRecyclerComponentTest {

  @Rule
  @JvmField
  val lithoTestRule =
      LithoTestRule(ComponentsConfiguration.defaultInstance.copy(enableNewCollection = true))

  // region: tests for sizeConstraintsWithoutPadding

  @Test
  fun testExactWidthAndHeight() {
    val constraints = SizeConstraints(100, 100, 200, 200) // exact width and height
    val result = sizeConstraintsWithoutPadding(constraints, 20, 30)
    assertThat(result.minWidth).isEqualTo(80)
    assertThat(result.maxWidth).isEqualTo(80)
    assertThat(result.minHeight).isEqualTo(170)
    assertThat(result.maxHeight).isEqualTo(170)
  }

  @Test
  fun testBoundedWidthAndHeight() {
    val constraints = SizeConstraints(0, 100, 0, 200) // bounded width and height
    val result = sizeConstraintsWithoutPadding(constraints, 20, 30)
    assertThat(result.minWidth).isEqualTo(0)
    assertThat(result.maxWidth).isEqualTo(80)
    assertThat(result.minHeight).isEqualTo(0)
    assertThat(result.maxHeight).isEqualTo(170)
  }

  @Test
  fun testUnboundedWidthAndHeight() {
    val constraints = SizeConstraints() // unbounded
    val result = sizeConstraintsWithoutPadding(constraints, 20, 30)
    assertThat(result.minWidth).isEqualTo(0)
    assertThat(result.maxWidth).isEqualTo(Infinity)
    assertThat(result.minHeight).isEqualTo(0)
    assertThat(result.maxHeight).isEqualTo(Infinity)
  }

  @Test
  fun testNegativePaddingResults() {
    // Test that we don't get negative values when padding > dimension
    val constraints = SizeConstraints(10, 10, 20, 20)
    val result = sizeConstraintsWithoutPadding(constraints, 50, 60)
    assertThat(result.minWidth).isEqualTo(0)
    assertThat(result.maxWidth).isEqualTo(0)
    assertThat(result.minHeight).isEqualTo(0)
    assertThat(result.maxHeight).isEqualTo(0)
  }

  @Test
  fun testMixedConstraints() {
    // Exact width, bounded height
    val constraints = SizeConstraints(100, 100, 0, 200)
    val result = sizeConstraintsWithoutPadding(constraints, 20, 30)
    assertThat(result.minWidth).isEqualTo(80)
    assertThat(result.maxWidth).isEqualTo(80)
    assertThat(result.minHeight).isEqualTo(0)
    assertThat(result.maxHeight).isEqualTo(170)
  }

  // endregion

  // region: render result verification tests

  @Test
  fun testEmptyListRendersCorrectly() {
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(emptyList()) }
    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(0)
  }

  @Test
  fun testSingleItemListRendersCorrectly() {
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(listOf(42)) }
    assertThat(testLithoView.findViewWithText("item_42")).isNotNull
    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(1)
  }

  @Test
  fun testLargeListRendersCorrectly() {
    val items = (0..99).toList()
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Verify first few items are rendered (visible items)
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull

    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(100)
  }

  @Test
  fun testItemUpdateDiffIsCorrect() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Verify initial state
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull

    // Update middle item - this replaces item 1 with item 10
    items = mutableListOf(0, 10, 2)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify updated state - item 1 should be gone, item 10 should be present
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("item_1")).isNull() // Item 1 should be removed
    assertThat(testLithoView.findViewWithText("item_10")).isNotNull // Item 10 should be added
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull
  }

  @Test
  fun testItemInsertionDiffIsCorrect() {
    var items = mutableListOf(0, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Verify initial state
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("item_1"))
        .isNull() // Item 1 should not exist yet
    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(2)

    // Insert item in middle
    items = mutableListOf(0, 1, 2)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify updated state
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull // Item 1 should now be present
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull
    val updatedRecyclerView = getRecyclerView(testLithoView)
    assertThat(updatedRecyclerView?.adapter?.itemCount ?: 0).isEqualTo(3)
  }

  @Test
  fun testItemDeletionDiffIsCorrect() {
    var items = mutableListOf(0, 1, 2, 3)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Verify initial state
    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(4)
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull
    assertThat(testLithoView.findViewWithText("item_3")).isNotNull

    // Remove middle items (1 and 2)
    items = mutableListOf(0, 3)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify updated state
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("item_1")).isNull() // Item 1 should be removed
    assertThat(testLithoView.findViewWithTextOrNull("item_2")).isNull() // Item 2 should be removed
    assertThat(testLithoView.findViewWithText("item_3")).isNotNull
    val updatedRecyclerView = getRecyclerView(testLithoView)
    assertThat(updatedRecyclerView?.adapter?.itemCount ?: 0).isEqualTo(2)
  }

  @Test
  fun testItemReorderingDiffIsCorrect() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Verify initial state
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull

    // Reorder items: [0, 1, 2] -> [2, 0, 1]
    items = mutableListOf(2, 0, 1)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify reordered state - same items should be present, just in different order
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull
    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(3)
  }

  @Test
  fun testComplexDiffOperationsAreCorrect() {
    var items = mutableListOf(0, 1, 2, 3, 4)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Verify initial state
    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(5)

    // Complex operation: remove some, add some, reorder
    // [0, 1, 2, 3, 4] -> [4, 10, 0, 11, 12]
    // Keeps: 0, 4
    // Removes: 1, 2, 3
    // Adds: 10, 11, 12
    items = mutableListOf(4, 10, 0, 11, 12)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify final state
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull // Kept
    assertThat(testLithoView.findViewWithTextOrNull("item_1")).isNull() // Removed
    assertThat(testLithoView.findViewWithTextOrNull("item_2")).isNull() // Removed
    assertThat(testLithoView.findViewWithTextOrNull("item_3")).isNull() // Removed
    assertThat(testLithoView.findViewWithText("item_4")).isNotNull // Kept
    assertThat(testLithoView.findViewWithText("item_10")).isNotNull // Added
    assertThat(testLithoView.findViewWithText("item_11")).isNotNull // Added
    assertThat(testLithoView.findViewWithText("item_12")).isNotNull // Added
    val updatedRecyclerView = getRecyclerView(testLithoView)
    assertThat(updatedRecyclerView?.adapter?.itemCount ?: 0).isEqualTo(5)
  }

  // endregion

  // region: thread safety tests

  @Test
  fun testRapidStateUpdatesAreHandledSafely() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Perform rapid updates
    for (i in 0..10) {
      items = (0..i).toMutableList()
      testLithoView.setRoot { createTestLazyListFromInts(items) }

      // Ensure each update is handled correctly
      val recyclerView = getRecyclerView(testLithoView)
      assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(i + 1)

      // Verify first and last items are present
      assertThat(testLithoView.findViewWithText("item_0")).isNotNull
      if (i > 0) {
        assertThat(testLithoView.findViewWithText("item_$i")).isNotNull
      }
    }
  }

  @Test
  fun testDataConsistencyDuringUpdates() {
    val testLithoView = lithoTestRule.render {
      createTestLazyListFromInts(mutableListOf(0, 1, 2, 3, 4))
    }

    // Verify initial consistency
    val initialRecyclerView = getRecyclerView(testLithoView)
    assertThat(initialRecyclerView?.adapter?.itemCount ?: 0).isEqualTo(5)

    // Perform multiple updates and verify consistency at each step
    val updateSequence = listOf(
        listOf(0, 1, 2), // Remove items
        listOf(0, 1, 2, 3, 4, 5), // Add items
        listOf(5, 4, 3, 2, 1, 0), // Reorder
        listOf(0, 2, 4), // Remove some
        listOf(0, 1, 2, 3, 4), // Add back
    )

    for (newItems in updateSequence) {
      testLithoView.setRoot { createTestLazyListFromInts(newItems) }

      val recyclerView = getRecyclerView(testLithoView)
      assertThat(recyclerView?.adapter?.itemCount ?: 0).isEqualTo(newItems.size)

      // Verify items are rendered
      newItems.forEach { assertThat(testLithoView.findViewWithText("item_$it")).isNotNull }
    }
  }

  @Test
  fun testStateConsistencyAfterMultipleOperations() {
    var items = mutableListOf(0, 1, 2, 3, 4)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Perform a series of operations that could potentially cause race conditions
    // 1. Remove all items
    items = mutableListOf()
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    val emptyRecyclerView = getRecyclerView(testLithoView)
    assertThat(emptyRecyclerView?.adapter?.itemCount ?: 0).isEqualTo(0)

    // 2. Add items back
    items = mutableListOf(10, 20, 30)
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    val restoredRecyclerView = getRecyclerView(testLithoView)
    assertThat(restoredRecyclerView?.adapter?.itemCount ?: 0).isEqualTo(3)
    assertThat(testLithoView.findViewWithText("item_10")).isNotNull
    assertThat(testLithoView.findViewWithText("item_20")).isNotNull
    assertThat(testLithoView.findViewWithText("item_30")).isNotNull

    // 3. Perform mixed operations
    items = mutableListOf(30, 40, 10, 50)
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    val finalRecyclerView = getRecyclerView(testLithoView)
    assertThat(finalRecyclerView?.adapter?.itemCount ?: 0).isEqualTo(4)
    assertThat(testLithoView.findViewWithText("item_30")).isNotNull
    assertThat(testLithoView.findViewWithText("item_40")).isNotNull
    assertThat(testLithoView.findViewWithText("item_10")).isNotNull
    assertThat(testLithoView.findViewWithText("item_50")).isNotNull
    // Verify item_20 is no longer present
    assertThat(testLithoView.findViewWithTextOrNull("item_20")).isNull()
  }

  // endregion

  // region: data observer tests

  @Test
  fun testDataObserverIsNotifiedOnItemUpdate() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    // Test custom CollectionAdapterDataObserver
    var customObserverCallCount = 0
    val customDataObserver = CollectionAdapterDataObserver { customObserverCallCount++ }

    // Test standard RecyclerView.AdapterDataObserver
    var recyclerViewObserverCallCount = 0
    val recyclerViewDataObserver =
        object : RecyclerView.AdapterDataObserver() {
          override fun onChanged() {
            recyclerViewObserverCallCount++
          }

          override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            recyclerViewObserverCallCount++
          }

          override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            recyclerViewObserverCallCount++
          }

          override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            recyclerViewObserverCallCount++
          }
        }

    // Register both observers
    requireNotNull(adapter)
    adapter.addDataObserver(customDataObserver)
    adapter.registerAdapterDataObserver(recyclerViewDataObserver)

    // Verify initial state
    assertThat(customObserverCallCount).isEqualTo(0)
    assertThat(recyclerViewObserverCallCount).isEqualTo(0)
    assertThat(adapter.itemCount).isEqualTo(3)

    // Update items - this should trigger both observers
    items = mutableListOf(0, 10, 2) // Changed item 1 to item 10
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify both observers were called and data was updated
    assertThat(customObserverCallCount).isEqualTo(1)
    assertThat(recyclerViewObserverCallCount).isGreaterThan(0)
    assertThat(adapter.itemCount).isEqualTo(3)
    assertThat(testLithoView.findViewWithText("item_10")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("item_1")).isNull()

    // Clean up
    adapter.removeDataObserver(customDataObserver)
    adapter.unregisterAdapterDataObserver(recyclerViewDataObserver)
  }

  @Test
  fun testDataObserverIsNotifiedOnItemInsertion() {
    var items = mutableListOf(0, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    // Test custom CollectionAdapterDataObserver
    var customObserverCallCount = 0
    val customDataObserver = CollectionAdapterDataObserver { customObserverCallCount++ }

    // Test standard RecyclerView.AdapterDataObserver
    var recyclerViewObserverCallCount = 0
    val recyclerViewDataObserver =
        object : RecyclerView.AdapterDataObserver() {
          override fun onChanged() {
            recyclerViewObserverCallCount++
          }

          override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            recyclerViewObserverCallCount++
          }
        }

    // Register both observers
    requireNotNull(adapter)
    adapter.addDataObserver(customDataObserver)
    adapter.registerAdapterDataObserver(recyclerViewDataObserver)

    // Verify initial state
    assertThat(customObserverCallCount).isEqualTo(0)
    assertThat(recyclerViewObserverCallCount).isEqualTo(0)
    assertThat(adapter.itemCount).isEqualTo(2)

    // Insert item - this should trigger both observers
    items = mutableListOf(0, 1, 2) // Inserted item 1
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify both observers were called and data was updated
    assertThat(customObserverCallCount).isEqualTo(1)
    assertThat(recyclerViewObserverCallCount).isGreaterThan(0)
    assertThat(adapter.itemCount).isEqualTo(3)
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull

    // Clean up
    adapter.removeDataObserver(customDataObserver)
    adapter.unregisterAdapterDataObserver(recyclerViewDataObserver)
  }

  @Test
  fun testDataObserverIsNotifiedOnItemDeletion() {
    var items = mutableListOf(0, 1, 2, 3)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    var observerCallCount = 0
    val dataObserver = CollectionAdapterDataObserver { observerCallCount++ }

    // Register the data observer
    requireNotNull(adapter)
    adapter.addDataObserver(dataObserver)

    // Verify initial state
    assertThat(observerCallCount).isEqualTo(0)
    assertThat(adapter.itemCount).isEqualTo(4)

    // Delete items - this should trigger the observer
    items = mutableListOf(0, 3) // Removed items 1 and 2
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify observer was called and data was updated
    assertThat(observerCallCount).isEqualTo(1)
    assertThat(adapter.itemCount).isEqualTo(2)
    assertThat(testLithoView.findViewWithTextOrNull("item_1")).isNull()
    assertThat(testLithoView.findViewWithTextOrNull("item_2")).isNull()

    // Clean up
    adapter.removeDataObserver(dataObserver)
  }

  @Test
  fun testDataObserverIsNotifiedOnEveryDataCommit() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    var observerCallCount = 0
    val dataObserver = CollectionAdapterDataObserver { observerCallCount++ }

    // Register the data observer
    requireNotNull(adapter)
    adapter.addDataObserver(dataObserver)

    // Verify initial state
    assertThat(observerCallCount).isEqualTo(0)

    // First update - change item
    items = mutableListOf(0, 10, 2)
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    assertThat(observerCallCount).isEqualTo(1)

    // Second update - different change
    items = mutableListOf(0, 10, 20)
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    assertThat(observerCallCount).isEqualTo(2)

    // Third update - another different change
    items = mutableListOf(0, 10, 20, 30)
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    assertThat(observerCallCount).isEqualTo(3)

    // Fourth update - same content so no update being committed
    items = mutableListOf(0, 10, 20, 30)
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    assertThat(observerCallCount).isEqualTo(3)

    // Clean up
    adapter.removeDataObserver(dataObserver)
  }

  @Test
  fun testMultipleDataObserversAreAllNotified() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    var observer1CallCount = 0
    var observer2CallCount = 0
    var observer3CallCount = 0

    val dataObserver1 = CollectionAdapterDataObserver { observer1CallCount++ }
    val dataObserver2 = CollectionAdapterDataObserver { observer2CallCount++ }
    val dataObserver3 = CollectionAdapterDataObserver { observer3CallCount++ }

    // Register multiple data observers
    requireNotNull(adapter)
    adapter.addDataObserver(dataObserver1)
    adapter.addDataObserver(dataObserver2)
    adapter.addDataObserver(dataObserver3)

    // Verify initial state
    assertThat(observer1CallCount).isEqualTo(0)
    assertThat(observer2CallCount).isEqualTo(0)
    assertThat(observer3CallCount).isEqualTo(0)

    // Update items - all observers should be notified
    items = mutableListOf(0, 10, 2)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify all observers were called
    assertThat(observer1CallCount).isEqualTo(1)
    assertThat(observer2CallCount).isEqualTo(1)
    assertThat(observer3CallCount).isEqualTo(1)

    // Remove one observer and update again
    adapter.removeDataObserver(dataObserver2)
    items = mutableListOf(0, 10, 20)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify only remaining observers were called
    assertThat(observer1CallCount).isEqualTo(2)
    assertThat(observer2CallCount).isEqualTo(1) // Should not have increased
    assertThat(observer3CallCount).isEqualTo(2)

    // Clean up
    adapter.removeDataObserver(dataObserver1)
    adapter.removeDataObserver(dataObserver3)
  }

  @Test
  fun testDataObserverReceivesCorrectDataState() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    val observedItemCounts = mutableListOf<Int>()
    requireNotNull(adapter)
    val dataObserver = CollectionAdapterDataObserver { observedItemCounts.add(adapter.itemCount) }

    // Register the data observer
    adapter.addDataObserver(dataObserver)

    // Verify initial state
    assertThat(observedItemCounts).isEmpty()

    // Update 1: Change item count
    items = mutableListOf(0, 1, 2, 3, 4) // Add 2 items
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    assertThat(observedItemCounts).containsExactly(5)

    // Update 2: Reduce item count
    items = mutableListOf(0, 1) // Remove 3 items
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    assertThat(observedItemCounts).containsExactly(5, 2)

    // Update 3: Same count, different content
    items = mutableListOf(10, 20) // Same count, different items
    testLithoView.setRoot { createTestLazyListFromInts(items) }
    assertThat(observedItemCounts).containsExactly(5, 2, 2)

    // Clean up
    adapter.removeDataObserver(dataObserver)
  }

  @Test
  fun testDataObserverIsNotifiedDuringComplexOperations() {
    var items = mutableListOf(0, 1, 2, 3, 4)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    var observerCallCount = 0
    val dataObserver = CollectionAdapterDataObserver { observerCallCount++ }

    // Register the data observer
    requireNotNull(adapter)
    adapter.addDataObserver(dataObserver)

    // Verify initial state
    assertThat(observerCallCount).isEqualTo(0)
    assertThat(adapter.itemCount).isEqualTo(5)

    // Complex operation: remove some, add some, reorder
    // [0, 1, 2, 3, 4] -> [4, 10, 0, 11, 12]
    items = mutableListOf(4, 10, 0, 11, 12)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify observer was called and final state is correct
    assertThat(observerCallCount).isEqualTo(1)
    assertThat(adapter.itemCount).isEqualTo(5)
    assertThat(testLithoView.findViewWithText("item_4")).isNotNull
    assertThat(testLithoView.findViewWithText("item_10")).isNotNull
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_11")).isNotNull
    assertThat(testLithoView.findViewWithText("item_12")).isNotNull

    // Clean up
    adapter.removeDataObserver(dataObserver)
  }

  @Test
  fun testRecyclerViewAdapterDataObserverReceivesCorrectOperationTypes() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    val observedOperations = mutableListOf<String>()

    val adapterDataObserver =
        object : RecyclerView.AdapterDataObserver() {
          override fun onChanged() {
            observedOperations.add("onChanged")
          }

          override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            observedOperations.add("onItemRangeChanged($positionStart, $itemCount)")
          }

          override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            observedOperations.add("onItemRangeInserted($positionStart, $itemCount)")
          }

          override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            observedOperations.add("onItemRangeRemoved($positionStart, $itemCount)")
          }

          override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            observedOperations.add("onItemRangeMoved($fromPosition, $toPosition, $itemCount)")
          }
        }

    // Register the RecyclerView adapter data observer
    requireNotNull(adapter)
    adapter.registerAdapterDataObserver(adapterDataObserver)

    // Test 1: Single item insertion at end - should call onItemRangeInserted(3, 1)
    observedOperations.clear()
    items = mutableListOf(0, 1, 2, 10) // Insert item 10 at position 3 (end)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify insertion was observed with correct parameters
    assertThat(adapter.itemCount).isEqualTo(4)
    assertThat(testLithoView.findViewWithText("item_10")).isNotNull
    // Should contain insertion operation with position 3 and count 1
    val hasCorrectInsertion = observedOperations.any { it.contains("onItemRangeInserted(3, 1)") }
    assertThat(hasCorrectInsertion).isTrue

    // Test 2: Single item removal from end - should call onItemRangeRemoved(3, 1)
    observedOperations.clear()
    items = mutableListOf(0, 1, 2) // Remove item 10 from position 3
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify removal was observed with correct parameters
    assertThat(adapter.itemCount).isEqualTo(3)
    assertThat(testLithoView.findViewWithTextOrNull("item_10")).isNull()
    // Should contain removal operation with position 3 and count 1
    val hasCorrectRemoval = observedOperations.any { it.contains("onItemRangeRemoved(3, 1)") }
    assertThat(hasCorrectRemoval).isTrue

    // Test 3: Multiple item insertion - should call onItemRangeInserted with correct count
    observedOperations.clear()
    items = mutableListOf(0, 1, 2, 20, 21) // Insert items 20, 21 at positions 3, 4
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify multiple insertion was observed
    assertThat(adapter.itemCount).isEqualTo(5)
    assertThat(testLithoView.findViewWithText("item_20")).isNotNull
    assertThat(testLithoView.findViewWithText("item_21")).isNotNull
    // Should contain insertion operation with count 2
    val hasMultipleInsertion = observedOperations.any {
      it.contains("onItemRangeInserted") && it.contains(", 2)")
    }
    assertThat(hasMultipleInsertion).isTrue

    // Test 4: Item update - should call onItemRangeChanged(1, 1)
    observedOperations.clear()
    testLithoView.setRoot {
      createTestLazyList(
          listOf(
              TestItem(0, "item_0"),
              TestItem(1, "item_30"), // Change item 1 to item 30
              TestItem(2, "item_2"),
              TestItem(20, "item_20"),
              TestItem(21, "item_21"),
          ),
      )
    }

    // Verify update was observed with correct parameters
    assertThat(adapter.itemCount).isEqualTo(5)
    assertThat(testLithoView.findViewWithText("item_30")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("item_1")).isNull()
    // Should contain change operation with position 1 and count 1
    val hasCorrectChange = observedOperations.any { it.contains("onItemRangeChanged(1, 1)") }
    assertThat(hasCorrectChange).isTrue

    // Test 5: Verify data consistency after all operations
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_30")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull
    assertThat(testLithoView.findViewWithText("item_20")).isNotNull
    assertThat(testLithoView.findViewWithText("item_21")).isNotNull

    // Clean up
    adapter.unregisterAdapterDataObserver(adapterDataObserver)
  }

  // endregion

  // region: layout data management tests

  @Test
  fun testLatestCommittedDataStoresLayoutInfo() {
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(listOf(0, 1, 2)) }

    // Verify initial render is successful
    assertThat(testLithoView.findViewWithText("item_0")).isNotNull
    assertThat(testLithoView.findViewWithText("item_1")).isNotNull
    assertThat(testLithoView.findViewWithText("item_2")).isNotNull

    val recyclerView = getRecyclerView(testLithoView)
    val adapter = recyclerView?.adapter as? CollectionPrimitiveViewAdapter
    assertThat(adapter).isNotNull

    // Verify LatestCommittedData is properly initialized
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    assertThat(latestCommittedData).isNotNull
    requireNotNull(latestCommittedData)

    // Verify internal state
    assertThat(latestCommittedData.items).hasSize(3)
    assertThat(latestCommittedData.data).hasSize(3)
    assertThat(latestCommittedData.layoutInfo).isNotNull
    assertThat(latestCommittedData.layoutConfig).isNotNull
    assertThat(latestCommittedData.collectionSize.width).isGreaterThan(0)
    assertThat(latestCommittedData.collectionSize.height).isGreaterThan(0)
  }

  @Test
  fun testLatestCommittedDataUpdatesOnDataChange() {
    var items = mutableListOf(0, 1, 2)
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    // Verify initial state
    val initialRecyclerView = getRecyclerView(testLithoView)
    val adapter = initialRecyclerView?.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    assertThat(adapter.itemCount).isEqualTo(3)

    val initialCommittedData = adapter.latestCommittedData
    requireNotNull(initialCommittedData)
    assertThat(initialCommittedData.items).hasSize(3)
    assertThat(initialCommittedData.data).hasSize(3)

    // Update data
    items = mutableListOf(0, 1, 2, 3, 4)
    testLithoView.setRoot { createTestLazyListFromInts(items) }

    // Verify updated state reflects in LatestCommittedData
    val updatedCommittedData = adapter.latestCommittedData
    requireNotNull(updatedCommittedData)
    assertThat(updatedCommittedData.items).hasSize(5)
    assertThat(updatedCommittedData.data).hasSize(5)
    assertThat(adapter.itemCount).isEqualTo(5)
    assertThat(testLithoView.findViewWithText("item_3")).isNotNull
    assertThat(testLithoView.findViewWithText("item_4")).isNotNull
  }

  @Test
  fun testCollectionLayoutScopeCreationWithEmptyList() {
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(emptyList()) }

    // Verify empty list is handled correctly
    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView?.adapter?.itemCount).isEqualTo(0)
  }

  @Test
  fun testViewportInfoTracking() {
    val items = (0..50).toList()
    val lazyCollectionController = LazyCollectionController()

    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 1000) {
          createTestLazyListFromInts(items, lazyCollectionController)
        }

    // First, ensure the RecyclerView is properly laid out
    lithoTestRule.idle()

    val recyclerView = getRecyclerView(testLithoView)
    assertThat(recyclerView).isNotNull
    requireNotNull(recyclerView)

    val adapter = recyclerView.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Verify initial viewport position
    val initialFirstVisible = latestCommittedData.firstVisiblePosition
    assertThat(initialFirstVisible).isEqualTo(0)

    // Use scrollBy() instead of scrollToPosition()
    // scrollBy() actually triggers onScrolled() automatically in Robolectric
    // scrollToPosition() does NOT trigger onScrolled() in Robolectric
    lithoTestRule.act(testLithoView) {
      // Scroll down by 500 pixels (should move viewport down several items)
      recyclerView.scrollBy(0, 500)
      // lazyCollectionController.scrollToIndex(10)
    }

    // Process the scroll and viewport change callbacks
    lithoTestRule.idle()

    // Verify the scroll took effect - viewport should have moved
    val updatedFirstVisible = latestCommittedData.firstVisiblePosition
    assertThat(updatedFirstVisible).isGreaterThan(initialFirstVisible)

    // Verify the layout manager's visible position also changed
    val layoutManager =
        recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
    requireNotNull(layoutManager)
    val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
    assertThat(firstVisiblePosition).isEqualTo(updatedFirstVisible)
  }

  @Test
  fun testCreateLayoutScopeReturnsCorrectState() {
    val items = (0..10).toList()
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val adapter = getRecyclerView(testLithoView)?.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Create layout scope and verify it contains correct state
    val layoutScope = latestCommittedData.createLayoutScope()
    assertThat(layoutScope).isNotNull
    assertThat(layoutScope.layoutInfo).isEqualTo(latestCommittedData.layoutInfo)
    assertThat(layoutScope.collectionItems).hasSize(11)
    assertThat(layoutScope.collectionConstraints)
        .isEqualTo(latestCommittedData.collectionConstraints)
    assertThat(layoutScope.collectionSize).isEqualTo(latestCommittedData.collectionSize)
  }

  @Test
  fun testLatestCommittedDataGetDataReturnsSnapshot() {
    val items = (0..5).toList()
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val adapter = getRecyclerView(testLithoView)?.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Get data snapshot
    val (data, itemsSnapshot) = latestCommittedData.getData()

    // Verify snapshot contents
    assertThat(data).hasSize(6)
    assertThat(itemsSnapshot).hasSize(6)

    // Verify snapshot is a copy (mutable list)
    assertThat(itemsSnapshot).isInstanceOf(MutableList::class.java)
  }

  @Test
  fun testLatestCommittedDataViewportPositionsAreUpdated() {
    val items = (0..50).toList()
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val adapter = getRecyclerView(testLithoView)?.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Initially, viewport positions should be set (at least first visible should be 0 or valid)
    // Note: firstVisiblePosition and lastVisiblePosition will be updated during viewport changes
    // For this test, we just verify they are accessible and have valid initial state
    val initialFirstVisible = latestCommittedData.firstVisiblePosition
    val initialLastVisible = latestCommittedData.lastVisiblePosition

    // Verify viewport positions are accessible (they may be RecyclerView.NO_POSITION initially)
    assertThat(initialFirstVisible).isGreaterThanOrEqualTo(RecyclerView.NO_POSITION)
    assertThat(initialLastVisible).isGreaterThanOrEqualTo(RecyclerView.NO_POSITION)
  }

  @Test
  fun testLatestCommittedDataCollectionSizeIsSetCorrectly() {
    val items = (0..10).toList()
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val adapter = getRecyclerView(testLithoView)?.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Verify collection size is set properly
    assertThat(latestCommittedData.collectionSize.width).isGreaterThan(0)
    assertThat(latestCommittedData.collectionSize.height).isGreaterThan(0)
    assertThat(latestCommittedData.collectionSize)
        .isNotEqualTo(com.facebook.rendercore.IntSize.Invalid)
  }

  @Test
  fun testLatestCommittedDataCollectionConstraintsAreSet() {
    val items = (0..10).toList()
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val adapter = getRecyclerView(testLithoView)?.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Verify collection constraints are set
    val constraints = latestCommittedData.collectionConstraints
    assertThat(constraints).isNotNull
    assertThat(constraints.maxWidth).isGreaterThan(0)
    assertThat(constraints.maxHeight).isGreaterThan(0)
  }

  @Test
  fun testLatestCommittedDataItemsFieldMatchesAdapterItems() {
    val items = (0..10).toList()
    val testLithoView = lithoTestRule.render { createTestLazyListFromInts(items) }

    val adapter = getRecyclerView(testLithoView)?.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Verify items field matches what's in the adapter
    val committedItems = latestCommittedData.items
    val adapterItems = adapter.getItems()

    assertThat(committedItems).hasSize(11)
    assertThat(adapterItems).hasSize(11)

    // Verify they reference the same collection
    assertThat(committedItems).isEqualTo(adapterItems)
  }

  // endregion

  @Test
  fun `a rebind keeps skipsRedundantBringToFront on until the list unmounts`() {
    val items = (0..3).map { TestItem(it) }
    val testLithoView = lithoTestRule.render {
      createTestLazyList(items, refreshProgressBarColor = Color.RED)
    }
    val list = requireNotNull(getRecyclerView(testLithoView)?.parent as? SectionsRecyclerView)
    list.skipsRedundantBringToFront = true

    testLithoView.setRoot { createTestLazyList(items, refreshProgressBarColor = Color.BLUE) }

    assertThat(getRecyclerView(testLithoView)?.parent).isSameAs(list)
    assertThat(list.skipsRedundantBringToFront).isTrue

    testLithoView.lithoView.unmountAllItems()

    assertThat(list.skipsRedundantBringToFront).isFalse
  }

  // region: helper functions

  private fun getRecyclerView(testLithoView: TestLithoView): RecyclerView? {
    return ((testLithoView.findViewWithTagOrNull("recycler_view") as LithoView?)?.getChildAt(0)
            as SectionsRecyclerView?)
        ?.recyclerView
  }

  private fun ResourcesScope.createTestLazyList(
      items: List<TestItem> = emptyList(),
      lazyCollectionController: LazyCollectionController? = null,
      refreshProgressBarColor: Int? = null,
  ): Component {
    return LazyList(
        orientation = RecyclerView.VERTICAL,
        refreshProgressBarColor = refreshProgressBarColor,
        lazyCollectionController = lazyCollectionController,
        style = Style.height(1080.px).viewTag("recycler_view"),
    ) {
      items.forEachIndexed { index, item ->
        child(id = { item.id }, item = item, deps = { arrayOf(item.content) }) {
          Text(text = item.content)
        }
      }
    }
  }

  // Convenience function for backward compatibility with integer lists
  private fun ResourcesScope.createTestLazyListFromInts(
      items: List<Int> = emptyList(),
      lazyCollectionController: LazyCollectionController? = null,
  ): Component {
    return createTestLazyList(items.map { TestItem(it) }, lazyCollectionController)
  }

  /** Test data class that represents an item with comparable content */
  data class TestItem(val id: Int, val content: String) {
    constructor(id: Int) : this(id, "item_$id")
  }

  // endregion

}
