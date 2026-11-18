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

package com.facebook.litho.testing

import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.collection.CollectionInfraOverride
import com.facebook.litho.widget.collection.CollectionRecyclerComponent
import com.facebook.litho.widget.collection.LazyList
import com.facebook.litho.widget.collection.LazyStaggeredGrid
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class TestCollectionTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `empty list reports zero items and no visible content`() {
    val collection = renderWithChildren()

    assertThat(collection.itemCount).isEqualTo(0)
    assertThat(collection.items).isEmpty()
    assertThat(collection.visibleItems).isEmpty()
    assertThat(collection.fullyVisibleItems).isEmpty()
    assertThat(collection.getLithoViews()).isEmpty()
  }

  @Test
  fun `single child reports count one with index zero`() {
    val collection = renderWithChildren(50)

    assertThat(collection.itemCount).isEqualTo(1)
    assertThat(collection.items).hasSize(1)
    assertThat(collection.items[0].index).isEqualTo(0)
    assertThat(collection.getItemAtIndex(0).index).isEqualTo(0)
  }

  @Test
  fun `multiple children preserve order and index`() {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyList {
            child(id = "first", component = Column(style = Style.height(10.px)))
            child(id = "middle", component = Column(style = Style.height(10.px)))
            child(id = "last", component = Column(style = Style.height(10.px)))
          }
        }
    val collection = testLithoView.findCollectionComponent()!!

    assertThat(collection.itemCount).isEqualTo(3)
    assertThat(collection.items.map { it.index }).containsExactly(0, 1, 2)
    assertThat(collection.getItemAtIndex(1).id).isEqualTo("middle")
    assertThat(collection.firstItem.id).isEqualTo("first")
    assertThat(collection.lastItem.id).isEqualTo("last")
  }

  @Test
  fun `item at invalid index throws`() {
    val collection = renderWithChildren(50)

    assertThatThrownBy { collection.getItemAtIndex(collection.itemCount) }
        .isInstanceOf(IndexOutOfBoundsException::class.java)
  }

  @Test
  fun `first item on empty list throws`() {
    val collection = renderWithChildren()

    assertThatThrownBy { collection.firstItem }.isInstanceOf(NoSuchElementException::class.java)
  }

  @Test
  fun `find first item returns first match for class and kclass`() {
    val collection = renderMixedChildren()
    val textClass = collection.items[1].component!!.javaClass
    val columnClass = collection.items[0].component!!.javaClass

    assertThat(collection.findFirstItem(textClass)?.index).isEqualTo(1)
    assertThat(collection.findFirstItem(columnClass)?.index).isEqualTo(0)
    assertThat(collection.findFirstItem(textClass.kotlin)?.index).isEqualTo(1)
  }

  @Test
  fun `find first item for absent type returns null`() {
    val collection = renderMixedChildren()

    assertThat(collection.findFirstItem(EmptyComponentForTests::class.java)).isNull()
    assertThat(collection.findFirstItem(EmptyComponentForTests::class)).isNull()
  }

  @Test
  fun `find items returns only matches for class and kclass varargs`() {
    val collection = renderThreeChildren()
    val textClass = collection.items[1].component!!.javaClass
    val columnClass = collection.items[0].component!!.javaClass

    assertThat(collection.findItems(textClass).map { it.index }).containsExactly(1)
    assertThat(collection.findItems(columnClass).map { it.index }).containsExactly(0, 2)
    assertThat(collection.findItems(textClass.kotlin).map { it.index }).containsExactly(1)
    assertThat(collection.findItems(textClass, columnClass)).hasSize(3)
  }

  @Test
  fun `find items for absent type returns empty`() {
    val collection = renderMixedChildren()

    assertThat(collection.findItems(EmptyComponentForTests::class.java)).isEmpty()
    assertThat(collection.findItems(EmptyComponentForTests::class)).isEmpty()
  }

  @Test
  fun `exactly filling children are all fully visible`() {
    val collection = renderWithChildren(50, 50)

    assertThat(collection.firstVisibleIndex).isEqualTo(0)
    assertThat(collection.lastVisibleIndex).isEqualTo(1)
    assertThat(collection.firstFullyVisibleIndex).isEqualTo(0)
    assertThat(collection.lastFullyVisibleIndex).isEqualTo(1)
    assertThat(collection.visibleItems.map { it.index }).containsExactly(0, 1)
    assertThat(collection.fullyVisibleItems.map { it.index }).containsExactly(0, 1)
  }

  @Test
  fun `partially visible child is visible but not fully visible`() {
    val collection = renderWithChildren(150)

    assertThat(collection.firstVisibleIndex).isEqualTo(0)
    assertThat(collection.lastVisibleIndex).isEqualTo(0)
    assertThat(collection.firstFullyVisibleIndex).isEqualTo(RecyclerView.NO_POSITION)
    assertThat(collection.lastFullyVisibleIndex).isEqualTo(RecyclerView.NO_POSITION)
    assertThat(collection.visibleItems).hasSize(1)
    assertThat(collection.fullyVisibleItems).isEmpty()
  }

  @Test
  fun `children beyond viewport are excluded from visible items`() {
    val collection = renderWithChildren(100, 100, 100)

    assertThat(collection.itemCount).isEqualTo(3)
    assertThat(collection.visibleItems.size).isLessThan(collection.itemCount)
    assertThat(collection.visibleItems.map { it.index }).doesNotContain(2)
    assertThat(collection.lastVisibleIndex).isLessThan(2)
    assertThat(collection.lastItem.index).isEqualTo(2)
  }

  @Test
  fun `first and last visible items match range ends`() {
    val collection = renderWithChildren(40, 40, 40)

    assertThat(collection.firstVisibleItem.index).isEqualTo(collection.firstVisibleIndex)
    assertThat(collection.lastVisibleItem.index).isEqualTo(collection.lastVisibleIndex)
    assertThat(collection.firstVisibleItem.index).isEqualTo(0)
    assertThat(collection.lastVisibleItem.index).isEqualTo(2)
  }

  @Test
  fun `measured dimensions match viewport`() {
    val collection = renderWithChildren(50)

    assertThat(collection.measuredWidth).isEqualTo(100)
    assertThat(collection.measuredHeight).isEqualTo(100)
  }

  @Test
  fun `recycler view is cached across accesses`() {
    val collection = renderWithChildren(50)

    assertThat(collection.recyclerView).isSameAs(collection.recyclerView)
  }

  @Test
  fun `new infra children preserve order and index`() {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyList(infraOverride = CollectionInfraOverride.FORCE_NEW) {
            child(id = "first", component = Column(style = Style.height(10.px)))
            child(id = "middle", component = Column(style = Style.height(10.px)))
            child(id = "last", component = Column(style = Style.height(10.px)))
          }
        }
    val collection = testLithoView.findCollectionComponent()!!

    assertThat(collection.itemCount).isEqualTo(3)
    assertThat(collection.items.map { it.index }).containsExactly(0, 1, 2)
    assertThat(collection.getItemAtIndex(1).id).isEqualTo("middle")
    assertThat(collection.firstItem.id).isEqualTo("first")
    assertThat(collection.lastItem.id).isEqualTo("last")
  }

  @Test
  fun `new infra visible range follows layout manager`() {
    val collection = renderWithChildren(50, 50, infraOverride = CollectionInfraOverride.FORCE_NEW)

    assertThat(collection.itemCount).isEqualTo(2)
    assertThat(collection.firstVisibleIndex).isEqualTo(0)
    assertThat(collection.lastVisibleIndex).isEqualTo(1)
    assertThat(collection.firstFullyVisibleIndex).isEqualTo(0)
    assertThat(collection.lastFullyVisibleIndex).isEqualTo(1)
    assertThat(collection.visibleItems.map { it.index }).containsExactly(0, 1)
    assertThat(collection.fullyVisibleItems.map { it.index }).containsExactly(0, 1)
  }

  @Test
  fun `new infra partially visible child is not fully visible`() {
    val collection = renderWithChildren(150, infraOverride = CollectionInfraOverride.FORCE_NEW)

    assertThat(collection.firstVisibleIndex).isEqualTo(0)
    assertThat(collection.firstFullyVisibleIndex).isEqualTo(RecyclerView.NO_POSITION)
    assertThat(collection.lastFullyVisibleIndex).isEqualTo(RecyclerView.NO_POSITION)
    assertThat(collection.visibleItems).hasSize(1)
    assertThat(collection.fullyVisibleItems).isEmpty()
  }

  @Test
  fun `new infra measured dimensions match viewport`() {
    val collection = renderWithChildren(50, infraOverride = CollectionInfraOverride.FORCE_NEW)

    assertThat(collection.measuredWidth).isEqualTo(100)
    assertThat(collection.measuredHeight).isEqualTo(100)
  }

  @Test
  fun `new infra find by type locates adapter items`() {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyList(infraOverride = CollectionInfraOverride.FORCE_NEW) {
            child(Column(style = Style.height(50.px)))
            child(Text(text = "word", style = Style.height(50.px)))
          }
        }
    val collection = testLithoView.findCollectionComponent()!!
    val textClass = collection.items[1].component!!.javaClass
    val columnClass = collection.items[0].component!!.javaClass

    assertThat(collection.findFirstItem(textClass)?.index).isEqualTo(1)
    assertThat(collection.findFirstItem(columnClass.kotlin)?.index).isEqualTo(0)
    assertThat(collection.findItems(textClass).map { it.index }).containsExactly(1)
    assertThat(collection.findFirstItem(EmptyComponentForTests::class.java)).isNull()
  }

  @Test
  fun `collection without litho view falls back to empty with clear error`() {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyList(infraOverride = CollectionInfraOverride.FORCE_NEW) {
            child(Column(style = Style.height(50.px)))
          }
        }
    val component =
        testLithoView.findComponent(CollectionRecyclerComponent::class.java)
            as CollectionRecyclerComponent
    val collection = TestCollection(component)

    assertThat(collection.itemCount).isEqualTo(0)
    assertThat(collection.items).isEmpty()
    assertThatThrownBy { collection.recyclerView }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `staggered grid visible range aggregates span positions`() {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyStaggeredGrid(
              spans = 2,
              infraOverride = CollectionInfraOverride.FORCE_NEW,
          ) {
            child(Column(style = Style.height(50.px)))
            child(Column(style = Style.height(50.px)))
          }
        }
    val collection = testLithoView.findCollectionComponent()!!

    assertThat(collection.itemCount).isEqualTo(2)
    assertThat(collection.firstVisibleIndex).isEqualTo(0)
    assertThat(collection.lastVisibleIndex).isEqualTo(1)
    assertThat(collection.firstFullyVisibleIndex).isEqualTo(0)
    assertThat(collection.lastFullyVisibleIndex).isEqualTo(1)
    assertThat(collection.visibleItems.map { it.index }).containsExactly(0, 1)
    assertThat(collection.fullyVisibleItems.map { it.index }).containsExactly(0, 1)
  }

  @Test
  fun `litho views match visible item count`() {
    val collection = renderWithChildren(50, 50)

    assertThat(collection.getLithoViews()).hasSize(collection.visibleItems.size)
    assertThat(collection.getLithoViews()).isNotEmpty()
  }

  private fun renderWithChildren(
      vararg heightsPx: Int,
      infraOverride: CollectionInfraOverride = CollectionInfraOverride.DEFAULT,
  ): TestCollection {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyList(infraOverride = infraOverride) {
            heightsPx.forEach { height -> child(Column(style = Style.height(height.px))) }
          }
        }
    return testLithoView.findCollectionComponent()!!
  }

  private fun renderMixedChildren(): TestCollection {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyList {
            child(Column(style = Style.height(50.px)))
            child(Text(text = "word", style = Style.height(50.px)))
          }
        }
    return testLithoView.findCollectionComponent()!!
  }

  private fun renderThreeChildren(): TestCollection {
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          LazyList {
            child(Column(style = Style.height(30.px)))
            child(Text(text = "word", style = Style.height(30.px)))
            child(Column(style = Style.height(30.px)))
          }
        }
    return testLithoView.findCollectionComponent()!!
  }
}
