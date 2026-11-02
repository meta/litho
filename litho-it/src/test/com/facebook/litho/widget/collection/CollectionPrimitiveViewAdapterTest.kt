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
import android.view.ViewGroup
import com.facebook.litho.Component
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.meta.rangemanager.PrepareScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionPrimitiveViewAdapterTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private lateinit var adapter: CollectionPrimitiveViewAdapter
  @Mock private lateinit var mockViewGroup: ViewGroup
  @Mock private lateinit var mockLatestCommittedData: LatestCommittedData
  @Mock private lateinit var mockView: View
  @Mock
  private lateinit var mockRootHostHolder: CollectionItemRootHostHolder<View, CollectionItem<View>>

  @Before
  fun setup() {
    MockitoAnnotations.initMocks(this)
    adapter = CollectionPrimitiveViewAdapter()

    // Setup mock view holder creator
    adapter.viewHolderCreator = { parent, viewType -> mockRootHostHolder }

    // Setup mock root host holder to return a valid view
    whenever(mockRootHostHolder.view).thenReturn(mockView)
  }

  private fun createTestItem(
      idString: String = "test_id",
      viewTypeInt: Int = 1,
  ): CollectionItem<View> {
    val renderInfo =
        ComponentRenderInfo.create()
            .component(mock<Component>())
            .customAttribute(CollectionItem.ID_CUSTOM_ATTR_KEY, idString)
            .build()
    return TestCollectionItem(idString.hashCode(), viewTypeInt, renderInfo)
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

  @Test
  fun `test adapter item count for non-circular list`() {
    val items = listOf(createTestItem("1"), createTestItem("2"), createTestItem("3"))
    adapter.setItems(items)

    assertThat(adapter.itemCount).isEqualTo(3)
  }

  @Test
  fun `test adapter item count for circular list with items`() {
    adapter.isCircular = true
    val items = listOf(createTestItem("1"), createTestItem("2"), createTestItem("3"))
    adapter.setItems(items)

    assertThat(adapter.itemCount).isEqualTo(Int.MAX_VALUE)
  }

  @Test
  fun `test adapter item count for circular list with single item`() {
    adapter.isCircular = true
    val items = listOf(createTestItem("1"))
    adapter.setItems(items)

    assertThat(adapter.itemCount).isEqualTo(Int.MAX_VALUE)
  }

  @Test
  fun `test adapter item count for circular list with no items`() {
    adapter.isCircular = true
    adapter.setItems(emptyList())

    assertThat(adapter.itemCount).isEqualTo(0)
  }

  @Test
  fun `test stable IDs disabled for circular lists`() {
    // Initialize stable IDs first by setting isCircular explicitly
    adapter.isCircular = false // This sets stable IDs to true
    assertThat(adapter.hasStableIds()).isTrue()

    adapter.isCircular = true

    assertThat(adapter.hasStableIds()).isFalse()
  }

  @Test
  fun `test stable IDs enabled for non-circular lists`() {
    adapter.isCircular = false

    assertThat(adapter.hasStableIds()).isTrue()
  }

  @Test
  fun `test position normalization for circular list`() {
    adapter.isCircular = true
    val items = listOf(
        createTestItem("1", viewTypeInt = 10),
        createTestItem("2", viewTypeInt = 20),
        createTestItem("3", viewTypeInt = 30),
    )
    adapter.setItems(items)

    // Test position wrapping
    assertThat(adapter.getItemViewType(0)).isEqualTo(10) // position 0 -> item 0
    assertThat(adapter.getItemViewType(1)).isEqualTo(20) // position 1 -> item 1
    assertThat(adapter.getItemViewType(2)).isEqualTo(30) // position 2 -> item 2
    assertThat(adapter.getItemViewType(3)).isEqualTo(10) // position 3 -> item 0 (wrapped)
    assertThat(adapter.getItemViewType(4)).isEqualTo(20) // position 4 -> item 1 (wrapped)
    assertThat(adapter.getItemViewType(5)).isEqualTo(30) // position 5 -> item 2 (wrapped)

    // Test large positions
    val largePos = Int.MAX_VALUE - 1
    val expectedViewType = items[largePos % items.size].viewType
    assertThat(adapter.getItemViewType(largePos)).isEqualTo(expectedViewType)
  }

  @Test
  fun `test position normalization for non-circular list`() {
    val items = listOf(
        createTestItem("1", viewTypeInt = 10),
        createTestItem("2", viewTypeInt = 20),
        createTestItem("3", viewTypeInt = 30),
    )
    adapter.setItems(items)

    // Test normal position access returns correct view types
    assertThat(adapter.getItemViewType(0)).isEqualTo(10)
    assertThat(adapter.getItemViewType(1)).isEqualTo(20)
    assertThat(adapter.getItemViewType(2)).isEqualTo(30)
  }

  @Test
  fun `test findPositionById in circular list`() {
    adapter.isCircular = true
    val items = listOf(createTestItem("item1"), createTestItem("item2"), createTestItem("item3"))
    adapter.setItems(items)

    assertThat(adapter.findPositionById("item1")).isEqualTo(0)
    assertThat(adapter.findPositionById("item2")).isEqualTo(1)
    assertThat(adapter.findPositionById("item3")).isEqualTo(2)
    assertThat(adapter.findPositionById("nonexistent")).isEqualTo(-1)
  }

  @Test
  fun `test findItemByPosition in circular list`() {
    adapter.isCircular = true
    val item1 = createTestItem("item1")
    val item2 = createTestItem("item2")
    val item3 = createTestItem("item3")
    val items = listOf(item1, item2, item3)
    adapter.setItems(items)

    // Test position wrapping
    assertThat(adapter.findItemByPosition(0)).isEqualTo(item1)
    assertThat(adapter.findItemByPosition(1)).isEqualTo(item2)
    assertThat(adapter.findItemByPosition(2)).isEqualTo(item3)
    assertThat(adapter.findItemByPosition(3)).isEqualTo(item1) // Wrapped
    assertThat(adapter.findItemByPosition(4)).isEqualTo(item2) // Wrapped
    assertThat(adapter.findItemByPosition(5)).isEqualTo(item3) // Wrapped

    // Test large positions
    assertThat(adapter.findItemByPosition(Int.MAX_VALUE - 1))
        .isEqualTo(items[(Int.MAX_VALUE - 1) % items.size])
  }

  @Test
  fun `test findItemByPosition with invalid position`() {
    val items = listOf(createTestItem("1"), createTestItem("2"))
    adapter.setItems(items)

    assertThat(adapter.findItemByPosition(-1)).isNull()
    assertThat(adapter.findItemByPosition(5)).isNull()
  }

  @Test
  fun `test setItems validation for circular list - same size allowed`() {
    adapter.isCircular = true
    val items1 = listOf(createTestItem("1"), createTestItem("2"))
    val items2 = listOf(createTestItem("3"), createTestItem("4"))

    adapter.setItems(items1)

    // Same size should be allowed
    adapter.setItems(items2)
    assertThat(adapter.getItems()).isEqualTo(items2)
  }

  @Test
  fun `test setItems validation for circular list - empty to items allowed`() {
    adapter.isCircular = true
    adapter.setItems(emptyList())

    val items = listOf(createTestItem("1"), createTestItem("2"))
    adapter.setItems(items)
    assertThat(adapter.getItems()).isEqualTo(items)
  }

  @Test
  fun `test setItems validation for circular list - items to empty allowed`() {
    adapter.isCircular = true
    val items = listOf(createTestItem("1"), createTestItem("2"))
    adapter.setItems(items)

    adapter.setItems(emptyList())
    assertThat(adapter.getItems()).isEmpty()
  }

  @Test(expected = IllegalArgumentException::class)
  fun `test setItems validation for circular list - different size throws exception`() {
    adapter.isCircular = true
    val items1 = listOf(createTestItem("1"), createTestItem("2"))
    val items2 = listOf(createTestItem("3"), createTestItem("4"), createTestItem("5"))

    adapter.setItems(items1)
    adapter.setItems(items2) // Should throw
  }

  @Test
  fun `test setItems validation for non-circular list - different sizes allowed`() {
    val items1 = listOf(createTestItem("1"), createTestItem("2"))
    val items2 = listOf(createTestItem("3"), createTestItem("4"), createTestItem("5"))

    adapter.setItems(items1)
    adapter.setItems(items2)
    assertThat(adapter.getItems()).isEqualTo(items2)
  }

  @Test
  fun `test onCreateViewHolder calls viewHolderCreator`() {
    val viewHolder = adapter.onCreateViewHolder(mockViewGroup, 1)

    assertThat(viewHolder).isInstanceOf(PrimitiveRecyclerViewHolder::class.java)
  }

  @Test(expected = IllegalStateException::class)
  fun `test onCreateViewHolder throws when viewHolderCreator not set`() {
    adapter.viewHolderCreator = null
    adapter.onCreateViewHolder(mockViewGroup, 1)
  }

  @Test
  fun `test onBindViewHolder basic functionality`() {
    val item = createTestItem("test")
    adapter.setItems(listOf(item))

    val viewHolder = PrimitiveRecyclerViewHolder(mockRootHostHolder)

    // This test just verifies that onBindViewHolder doesn't throw an exception
    // when called with valid parameters
    try {
      adapter.onBindViewHolder(viewHolder, 0)
      // If we get here without an exception, the test passes
    } catch (e: Exception) {
      throw AssertionError("onBindViewHolder threw an exception: ${e.message}", e)
    }
  }

  @Test
  fun `test onViewRecycled clears view holder data`() {
    val testItem = createTestItem("test")
    val viewHolder = PrimitiveRecyclerViewHolder(mockRootHostHolder)
    viewHolder.delegate.data = testItem

    adapter.onViewRecycled(viewHolder)

    assertThat(viewHolder.delegate.data).isNull()
  }

  @Test
  fun `test getItemId returns item id as long`() {
    val item = createTestItem("test_id_123")
    adapter.setItems(listOf(item))

    // Note: getItemId expects the position to be valid for the items list
    // The actual item ID is the hash code we set in createTestItem
    assertThat(adapter.getItemId(0)).isEqualTo("test_id_123".hashCode().toLong())
  }

  @Test
  fun `test getSnapshotOfItemsAsMutableList`() {
    val items = listOf(createTestItem("1"), createTestItem("2"))
    adapter.setItems(items)

    val snapshot = adapter.getSnapshotOfItemsAsMutableList()
    assertThat(snapshot).isEqualTo(items.toMutableList())
    assertThat(snapshot).isInstanceOf(MutableList::class.java)

    // Verify it's a copy
    snapshot.clear()
    assertThat(adapter.getItems()).isEqualTo(items) // Original unchanged
  }

  @Test
  fun `test circular list edge case with empty items`() {
    adapter.isCircular = true
    adapter.setItems(emptyList())

    assertThat(adapter.itemCount).isEqualTo(0)
    assertThat(adapter.getItems()).isEmpty()
    assertThat(adapter.findItemByPosition(0)).isNull()
    assertThat(adapter.findPositionById("any")).isEqualTo(-1)
  }

  @Test
  fun `test circular list with large position calculations`() {
    adapter.isCircular = true
    val items = listOf(
        createTestItem("1", viewTypeInt = 100),
        createTestItem("2", viewTypeInt = 200),
        createTestItem("3", viewTypeInt = 300),
    )
    adapter.setItems(items)

    // Test positions around Integer.MAX_VALUE
    val nearMaxPosition = Int.MAX_VALUE - 5
    val expectedItemIndex = nearMaxPosition % items.size
    val expectedItem = items[expectedItemIndex]

    assertThat(adapter.findItemByPosition(nearMaxPosition)).isEqualTo(expectedItem)
    assertThat(adapter.getItemViewType(nearMaxPosition)).isEqualTo(expectedItem.viewType)
  }

  @Test
  fun `test latestCommittedData setter updates correctly`() {
    assertThat(adapter.latestCommittedData).isNull()

    adapter.latestCommittedData = mockLatestCommittedData

    assertThat(adapter.latestCommittedData).isEqualTo(mockLatestCommittedData)
  }
}
