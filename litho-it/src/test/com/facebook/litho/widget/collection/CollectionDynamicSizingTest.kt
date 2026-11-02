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

import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.LithoView
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for dynamic cross-axis sizing functionality in CollectionRecyclerComponent.
 *
 * These tests verify that when CrossAxisWrapMode.Dynamic is used, the collection size dynamically
 * expands to accommodate children that are larger than the collection's initial cross-axis size.
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionDynamicSizingTest {

  @Rule
  @JvmField
  val lithoTestRule =
      LithoTestRule(ComponentsConfiguration.defaultInstance.copy(enableNewCollection = true))

  // region: dynamic cross-axis sizing tests

  @Test
  fun testDynamicCrossAxisSizingVerticalWithWiderChild() {
    // Test that when a child is wider than the collection in a vertical list
    // with CrossAxisWrapMode.Dynamic, the collection width expands
    val items = listOf(0, 1, 2, 3, 4, 5)
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified()) {
          createTestLazyListFromInts(
              items = items,
              orientation = RecyclerView.VERTICAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              maxItemWidth = 200.px, // Wider than collection width
              style = Style.height(500.px).minWidth(100.px),
          )
        }

    // After measuring children, the collection width should expand to accommodate the widest child
    lithoTestRule.idle()

    // Verify collection size width has expanded
    assertThat(getRecyclerView(testLithoView)?.width).isEqualTo(200)
    repeat(items.size) { index ->
      assertThat(getRecyclerView(testLithoView)?.getChildAt(index)?.width).isEqualTo(200)
    }
  }

  @Test
  fun testDynamicCrossAxisSizingHorizontalWithTallerChild() {
    // Test that when a child is taller than the collection in a horizontal list
    // with CrossAxisWrapMode.Dynamic, the collection height expands
    val items = listOf(0, 1, 2, 3, 4, 5)
    val testLithoView =
        lithoTestRule.render(heightSpec = unspecified()) {
          createTestLazyListFromInts(
              items = items,
              orientation = RecyclerView.HORIZONTAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              maxItemHeight = 200.px, // Taller than collection height
              style = Style.width(500.px).minHeight(100.px),
          )
        }

    // After measuring children, the collection height should expand to accommodate the tallest
    // child
    lithoTestRule.idle()

    // Verify collection size height has expanded
    assertThat(getRecyclerView(testLithoView)?.height).isEqualTo(200)
    repeat(items.size) { index ->
      assertThat(getRecyclerView(testLithoView)?.getChildAt(index)?.height).isEqualTo(200)
    }
  }

  @Test
  fun testDynamicCrossAxisSizingDoesNotShrink() {
    // Test that dynamic sizing only expands, never shrinks
    var items = listOf(0, 1, 2)
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified()) {
          createTestLazyListFromInts(
              items = items,
              orientation = RecyclerView.VERTICAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              maxItemWidth = 200.px, // Initially wider than collection
              style = Style.height(500.px).minWidth(100.px),
          )
        }

    lithoTestRule.idle()

    val expandedWidth = getRecyclerView(testLithoView)?.width
    assertThat(expandedWidth).isEqualTo(200)

    // Now update with narrower items - collection should not shrink
    items = listOf(0, 1, 2, 3)
    testLithoView.setRoot {
      createTestLazyListFromInts(
          items = items,
          orientation = RecyclerView.VERTICAL,
          crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
          maxItemWidth = 50.px, // Much narrower than expanded width
          style = Style.height(500.px).minWidth(100.px),
      )
    }

    lithoTestRule.idle()

    // Collection width should remain at the expanded size
    assertThat(getRecyclerView(testLithoView)?.width).isEqualTo(expandedWidth)
    repeat(items.size) { index ->
      assertThat(getRecyclerView(testLithoView)?.getChildAt(index)?.width).isEqualTo(expandedWidth)
    }
  }

  @Test
  fun testDynamicCrossAxisSizingWithNoWrapModeDoesNotExpand() {
    // Test that without CrossAxisWrapMode.Dynamic, collection size doesn't change
    val items = listOf(0, 1, 2)
    val testLithoView = lithoTestRule.render {
      createTestLazyListFromInts(
          items = items,
          orientation = RecyclerView.VERTICAL,
          crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
          maxItemWidth = 200.px, // Wider than collection
          style = Style.height(500.px).width(100.px),
      )
    }

    lithoTestRule.idle()

    // Collection width should remain at original size
    assertThat(getRecyclerView(testLithoView)?.width).isEqualTo(100)
    repeat(items.size) { index ->
      assertThat(getRecyclerView(testLithoView)?.getChildAt(index)?.width).isEqualTo(100)
    }
  }

  @Test
  fun testDynamicCrossAxisSizingVerticalWithMultipleWideChildren() {
    // Test that collection expands to accommodate the widest child
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified()) {
          createTestLazyList(
              items =
                  listOf(
                      TestItem(0, "item_0"),
                      TestItem(1, "item_1"),
                      TestItem(2, "item_2"),
                  ),
              orientation = RecyclerView.VERTICAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              maxItemWidth = 150.px, // Initial width
              style = Style.height(500.px).minWidth(100.px),
          )
        }

    lithoTestRule.idle()

    val firstExpandedWidth = getRecyclerView(testLithoView)?.width
    assertThat(firstExpandedWidth).isEqualTo(150)

    // Update with an even wider item
    testLithoView.setRoot {
      createTestLazyList(
          items =
              listOf(
                  TestItem(4, "item_4"),
                  TestItem(5, "item_5"),
                  TestItem(6, "item_6"),
                  TestItem(7, "item_7"),
              ),
          orientation = RecyclerView.VERTICAL,
          crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
          maxItemWidth = 250.px, // Even wider
          style = Style.height(500.px).minWidth(100.px),
      )
    }

    lithoTestRule.idle()

    // Collection should expand further
    assertThat(getRecyclerView(testLithoView)?.width).isEqualTo(250)
    repeat(4) { index ->
      assertThat(getRecyclerView(testLithoView)?.getChildAt(index)?.width).isEqualTo(250)
    }
  }

  @Test
  fun testDynamicSizingInitializesWithCorrectSize() {
    // Test that the collection initializes with correct size when Dynamic mode is enabled
    val items = listOf(0, 1, 2)
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified()) {
          createTestLazyListFromInts(
              items = items,
              orientation = RecyclerView.VERTICAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              style = Style.height(600.px).minWidth(300.px),
          )
        }

    lithoTestRule.idle()

    // Collection should start with the provided constraints
    assertThat(getRecyclerView(testLithoView)?.width).isEqualTo(300)
    repeat(items.size) { index ->
      assertThat(getRecyclerView(testLithoView)?.getChildAt(index)?.width).isEqualTo(300)
    }
  }

  @Test
  fun testDynamicSizingInHorizontalListWithMaxChildHeight() {
    // Test horizontal list expands to tallest child height
    val testLithoView =
        lithoTestRule.render(heightSpec = unspecified()) {
          createTestLazyList(
              items =
                  listOf(
                      TestItem(0, "item_0"),
                      TestItem(1, "item_1"),
                      TestItem(2, "item_2"),
                  ),
              orientation = RecyclerView.HORIZONTAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              maxItemHeight = 150.px, // Taller than collection
              style = Style.width(600.px).minHeight(100.px),
          )
        }

    lithoTestRule.idle()

    // Verify height expanded
    assertThat(getRecyclerView(testLithoView)?.height).isEqualTo(150)
    repeat(3) { index ->
      assertThat(getRecyclerView(testLithoView)?.getChildAt(index)?.height).isEqualTo(150)
    }
  }

  @Test
  fun testCalculateLayoutUsesCollectionSizeForInitialization() {
    // Test that calculateLayout initializes maxChildWidth/Height with collection size
    val items = listOf(0, 1, 2)
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified()) {
          createTestLazyListFromInts(
              items = items,
              orientation = RecyclerView.VERTICAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              style = Style.height(400.px).minWidth(200.px),
          )
        }

    lithoTestRule.idle()

    // Verify collection size is set correctly with the minimum height
    assertThat(getRecyclerView(testLithoView)?.width).isEqualTo(200)
    assertThat(getRecyclerView(testLithoView)?.height).isEqualTo(400)
  }

  @Test
  fun testDynamicSizingGrowsOnScrollInVerticalListWithWiderChild() {
    // Test that collection width grows when scrolling reveals a wider child
    // Create a list with items where the last item is wider than initial items
    val items = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified()) {
          createTestLazyListWithVariableWidths(
              items = items,
              orientation = RecyclerView.VERTICAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              itemHeight = 100.px,
              initialItemWidth = 100.px,
              lastItemWidth = 300.px,
              style = Style.height(300.px), // Only shows 3 items initially
          )
        }

    lithoTestRule.idle()

    val recyclerView = getRecyclerView(testLithoView)
    requireNotNull(recyclerView)
    val adapter = recyclerView.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Initially, collection width should be 100px (size of first visible items)
    assertThat(latestCommittedData.collectionSize.width).isEqualTo(100)

    // Scroll to reveal the last item which is wider (300px)
    lithoTestRule.act(testLithoView) { recyclerView.scrollBy(0, 800) }

    // Collection width should now expand to accommodate the wider child
    assertThat(latestCommittedData.collectionSize.width).isEqualTo(300)
  }

  @Test
  fun testDynamicSizingGrowsOnScrollInHorizontalListWithTallerChild() {
    // Test that collection height grows when scrolling reveals a taller child
    // Create a list with items where the last item is taller than initial items
    val items = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    val testLithoView =
        lithoTestRule.render(heightSpec = unspecified()) {
          createTestLazyListWithVariableHeights(
              items = items,
              orientation = RecyclerView.HORIZONTAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              itemWidth = 100.px,
              initialItemHeight = 100.px,
              lastItemHeight = 300.px,
              style = Style.width(300.px), // Only shows 3 items initially
          )
        }

    lithoTestRule.idle()

    val recyclerView = getRecyclerView(testLithoView)
    requireNotNull(recyclerView)
    val adapter = recyclerView.adapter as? CollectionPrimitiveViewAdapter
    requireNotNull(adapter)
    val latestCommittedData = adapter.latestCommittedData
    requireNotNull(latestCommittedData)

    // Initially, collection height should be 100px (size of first visible items)
    assertThat(latestCommittedData.collectionSize.height).isEqualTo(100)

    // Scroll to reveal the last item which is taller (300px)
    lithoTestRule.act(testLithoView) { recyclerView.scrollBy(800, 0) }

    // Collection height should now expand to accommodate the taller child

    assertThat(latestCommittedData.collectionSize.height).isEqualTo(300)
  }

  // endregion

  // region: helper functions

  private fun getRecyclerView(testLithoView: TestLithoView): RecyclerView? {
    return ((testLithoView.findViewWithTagOrNull("recycler_view") as LithoView?)?.getChildAt(0)
            as SectionsRecyclerView?)
        ?.recyclerView
  }

  private fun ResourcesScope.createTestLazyList(
      items: List<TestItem> = emptyList(),
      lazyCollectionController: LazyCollectionController? = null,
      @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
      crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
      maxItemHeight: Dimen? = null,
      maxItemWidth: Dimen? = null,
      style: Style = Style,
  ): Component {
    return LazyList(
        orientation = orientation,
        lazyCollectionController = lazyCollectionController,
        crossAxisWrapMode = crossAxisWrapMode,
        style = Style.viewTag("recycler_view") + style,
    ) {
      items.forEachIndexed { index, item ->
        val itemWidth =
            if (maxItemWidth != null) {
              if (index % 2 == 0) {
                maxItemWidth
              } else {
                (maxItemWidth.toPixels() - 50).px
              }
            } else {
              maxItemWidth
            }
        val itemHeight =
            if (maxItemHeight != null) {
              if (index % 2 == 0) {
                maxItemHeight
              } else {
                (maxItemHeight.toPixels() - 50).px
              }
            } else {
              maxItemHeight
            }
        child(id = { item.id }, item = item, deps = { arrayOf(item.content) }) {
          val style =
              when {
                itemHeight != null && itemWidth != null -> Style.height(itemHeight).width(itemWidth)
                itemHeight != null -> Style.height(itemHeight)
                itemWidth != null -> Style.width(itemWidth)
                else -> Style
              }
          Text(text = item.content, style = style)
        }
      }
    }
  }

  // Convenience function for backward compatibility with integer lists
  private fun ResourcesScope.createTestLazyListFromInts(
      items: List<Int> = emptyList(),
      lazyCollectionController: LazyCollectionController? = null,
      @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
      crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
      maxItemHeight: Dimen? = null,
      maxItemWidth: Dimen? = null,
      style: Style = Style,
  ): Component {
    return createTestLazyList(
        items.map { TestItem(it) },
        lazyCollectionController,
        orientation,
        crossAxisWrapMode,
        maxItemHeight,
        maxItemWidth,
        style,
    )
  }

  private fun ResourcesScope.createTestLazyListWithVariableWidths(
      items: List<Int> = emptyList(),
      @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
      crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
      itemHeight: Dimen,
      initialItemWidth: Dimen,
      lastItemWidth: Dimen,
      style: Style = Style,
  ): Component {
    return LazyList(
        orientation = orientation,
        crossAxisWrapMode = crossAxisWrapMode,
        style = Style.viewTag("recycler_view") + style,
    ) {
      items.forEachIndexed { index, item ->
        val itemWidth = if (index == items.lastIndex) lastItemWidth else initialItemWidth
        child(id = { item }, item = item, deps = { arrayOf(item) }) {
          Text(text = "item_$item", style = Style.height(itemHeight).width(itemWidth))
        }
      }
    }
  }

  private fun ResourcesScope.createTestLazyListWithVariableHeights(
      items: List<Int> = emptyList(),
      @RecyclerView.Orientation orientation: Int = RecyclerView.HORIZONTAL,
      crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
      itemWidth: Dimen,
      initialItemHeight: Dimen,
      lastItemHeight: Dimen,
      style: Style = Style,
  ): Component {
    return LazyList(
        orientation = orientation,
        crossAxisWrapMode = crossAxisWrapMode,
        style = Style.viewTag("recycler_view") + style,
    ) {
      items.forEachIndexed { index, item ->
        val itemHeight = if (index == items.lastIndex) lastItemHeight else initialItemHeight
        child(id = { item }, item = item, deps = { arrayOf(item) }) {
          Text(text = "item_$item", style = Style.height(itemHeight).width(itemWidth))
        }
      }
    }
  }

  /** Test data class that represents an item with comparable content */
  data class TestItem(val id: Int, val content: String) {
    constructor(id: Int) : this(id, "item_$id")
  }

  // endregion
}
