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
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.Style
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.SectionsRecyclerView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Tests for [Collection]'s pagination prop */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionOnNearViewportTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private fun getLazyCollectionRecyclerView(
      testLithoView: TestLithoView,
      lazyCollectionTag: String,
  ): RecyclerView? =
      ((testLithoView.findViewWithTagOrNull(lazyCollectionTag) as LithoView?)?.getChildAt(0)
              as SectionsRecyclerView?)
          ?.recyclerView

  @Test
  fun `test onNearViewport is called on scroll`() {
    val enterCounts = AtomicIntegerArray(5)

    class Test : KComponent() {
      override fun ComponentScope.render(): Component = LazyList(
          style = Style.viewTag("collection_tag"),
      ) {
        (0..4).forEach {
          child(
              id = it,
              onNearViewport = OnNearCallback { enterCounts[it]++ },
              component = Text("$it"),
          )
        }
      }
    }

    val testLithoView = lithoTestRule.render(widthPx = 100, heightPx = 100) { Test() }
    lithoTestRule.idle()

    assertThat(enterCounts).containsExactly(1, 1, 1, 0, 0)

    val recyclerView = getLazyCollectionRecyclerView(testLithoView, "collection_tag")

    // Scroll one item
    lithoTestRule.act(testLithoView) { recyclerView?.scrollBy(0, 50) }
    assertThat(enterCounts).containsExactly(1, 1, 1, 1, 0)

    // Scroll another item
    lithoTestRule.act(testLithoView) { recyclerView?.scrollBy(0, 50) }
    assertThat(enterCounts).containsExactly(1, 1, 1, 1, 1)
  }

  @Test
  fun `test onNearViewport respects offsetBeforeTail`() {
    val enterCounts = AtomicIntegerArray(5)

    class Test : KComponent() {
      override fun ComponentScope.render(): Component = LazyList(
          style = Style.viewTag("collection_tag"),
      ) {
        (0..3).forEach {
          child(
              onNearViewport = OnNearCallback { enterCounts[it]++ },
              component = Text("$it"),
          )
        }
        child(
            onNearViewport = OnNearCallback(offset = 2) { enterCounts[4]++ },
            component = Text("4"),
        )
      }
    }
    val testLithoView = lithoTestRule.render(widthPx = 100, heightPx = 100) { Test() }
    lithoTestRule.idle()

    // Item 4's enter callback has already been triggered
    assertThat(enterCounts).containsExactly(1, 1, 1, 0, 1)

    val recyclerView = getLazyCollectionRecyclerView(testLithoView, "collection_tag")

    // Scroll one item
    lithoTestRule.act(testLithoView) { recyclerView?.scrollBy(0, 50) }
    assertThat(enterCounts).containsExactly(1, 1, 1, 1, 1)

    // Scroll another item
    lithoTestRule.act(testLithoView) { recyclerView?.scrollBy(0, 50) }
    assertThat(enterCounts).containsExactly(1, 1, 1, 1, 1)
  }

  enum class Item {
    Item1,
    Item2,
  }

  @Test
  fun `test onNearViewport triggered by changing ids`() {
    val item1EnterCount = AtomicInteger(0)
    val item2EnterCount = AtomicInteger(0)

    class Test(val item: Item) : KComponent() {
      override fun ComponentScope.render(): Component = LazyList(
          style = Style.viewTag("collection_tag"),
      ) {
        when (item) {
          Item.Item1 -> {
            child(
                id = Item.Item1,
                onNearViewport = OnNearCallback { item1EnterCount.getAndIncrement() },
                component = Text("Item"),
            )
          }
          Item.Item2 -> {
            child(
                id = Item.Item2,
                onNearViewport = OnNearCallback { item2EnterCount.getAndIncrement() },
                component = Text("Item"),
            )
          }
        }
      }
    }

    val testLithoView = lithoTestRule.render(widthPx = 100, heightPx = 100) { Test(Item.Item1) }
    lithoTestRule.idle()

    assertThat(item1EnterCount.get()).isEqualTo(1)
    assertThat(item2EnterCount.get()).isEqualTo(0)

    lithoTestRule.render(lithoView = testLithoView.lithoView, widthPx = 100, heightPx = 100) {
      Test(Item.Item2)
    }
    lithoTestRule.idle()

    assertThat(item1EnterCount.get()).isEqualTo(1)
    assertThat(item2EnterCount.get()).isEqualTo(1)
  }
}
