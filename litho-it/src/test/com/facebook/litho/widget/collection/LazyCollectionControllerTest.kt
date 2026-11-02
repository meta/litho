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

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.Style
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.sections.SectionTree
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.RecyclerEventsController
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.litho.widget.SmoothScrollAlignmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LazyCollectionControllerTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private fun getLazyCollectionRecyclerView(
      testLithoView: TestLithoView,
      lazyCollectionTag: String,
  ): RecyclerView? =
      ((testLithoView.findViewWithTagOrNull(lazyCollectionTag) as LithoView?)?.getChildAt(0)
              as SectionsRecyclerView?)
          ?.recyclerView

  fun `test lazyCollectionController recyclerView reference is updated`() {
    val lazyCollectionController = LazyCollectionController()

    class Test : KComponent() {
      override fun ComponentScope.render(): Component {
        return LazyList(
            lazyCollectionController = lazyCollectionController,
            style = Style.viewTag("collection_tag"),
        ) {}
      }
    }

    assertThat(lazyCollectionController.recyclerView).isNull()

    val testLithoView = lithoTestRule.render(widthPx = 100, heightPx = 100) { Test() }
    lithoTestRule.idle()

    assertThat(lazyCollectionController.recyclerView)
        .isSameAs(getLazyCollectionRecyclerView(testLithoView, "collection_tag"))
  }

  @Test
  fun `test showRefreshing delegates to RecyclerEventsController`() {
    val lazyCollectionController = LazyCollectionController()

    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    lazyCollectionController.setRefreshing(true)
    verify(recyclerEventsController).showRefreshing()
  }

  @Test
  fun `test clearRefreshing delegates to RecyclerEventsController`() {
    val lazyCollectionController = LazyCollectionController()

    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    lazyCollectionController.setRefreshing(false)
    verify(recyclerEventsController).clearRefreshing()
  }

  @Test
  fun `test setRefreshing(true) before bind is applied on bind`() {
    val lazyCollectionController = LazyCollectionController()

    lazyCollectionController.setRefreshing(true)
    assertThat(lazyCollectionController.isRefreshing).isTrue()

    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    verify(recyclerEventsController).showRefreshing()
  }

  @Test
  fun `test setRefreshing(false) before bind is applied on bind`() {
    val lazyCollectionController = LazyCollectionController()

    lazyCollectionController.setRefreshing(false)
    assertThat(lazyCollectionController.isRefreshing).isFalse()

    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    verify(recyclerEventsController).clearRefreshing()
  }

  @Test
  fun `test setRefreshing latest-wins for multiple pre-bind calls`() {
    val lazyCollectionController = LazyCollectionController()

    lazyCollectionController.setRefreshing(true)
    lazyCollectionController.setRefreshing(false)
    lazyCollectionController.setRefreshing(true)

    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    verify(recyclerEventsController).showRefreshing()
    verify(recyclerEventsController, org.mockito.kotlin.never()).clearRefreshing()
  }

  @Test
  fun `test pending value cleared after bind so post-bind setRefreshing wins`() {
    val lazyCollectionController = LazyCollectionController()

    lazyCollectionController.setRefreshing(true) // pending
    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController
    // Pending consumed; subsequent setRefreshing(false) goes straight to the controller.
    lazyCollectionController.setRefreshing(false)

    val inOrder = org.mockito.kotlin.inOrder(recyclerEventsController)
    inOrder.verify(recyclerEventsController).showRefreshing()
    inOrder.verify(recyclerEventsController).clearRefreshing()
  }

  @Test
  fun `test pending value not re-applied on second bind`() {
    val lazyCollectionController = LazyCollectionController()

    lazyCollectionController.setRefreshing(true)
    val firstController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = firstController
    verify(firstController).showRefreshing()

    // Unbind and rebind with a fresh controller.
    lazyCollectionController.recyclerEventsController = null
    val secondController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = secondController

    // Original pending value has already been consumed; second controller is untouched.
    org.mockito.kotlin.verifyNoInteractions(secondController)
  }

  @Test
  fun `test setRecyclerUpdate before bind is attached on bind`() {
    val lazyCollectionController = LazyCollectionController()
    val listener: (RecyclerView?) -> Unit = {}

    lazyCollectionController.setRecyclerUpdate(listener)

    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    verify(recyclerEventsController).setOnRecyclerUpdateListener(listener)
  }

  @Test
  fun `test setRecyclerUpdate after bind delegates immediately`() {
    val lazyCollectionController = LazyCollectionController()

    val recyclerEventsController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = recyclerEventsController
    val listener: (RecyclerView?) -> Unit = {}

    lazyCollectionController.setRecyclerUpdate(listener)

    verify(recyclerEventsController).setOnRecyclerUpdateListener(listener)
  }

  @Test
  fun `test setRecyclerUpdate listener is durable and re-applied on every rebind`() {
    val lazyCollectionController = LazyCollectionController()
    val listener: (RecyclerView?) -> Unit = {}
    lazyCollectionController.setRecyclerUpdate(listener)

    val firstController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = firstController
    verify(firstController).setOnRecyclerUpdateListener(listener)

    // Unbind and rebind with a fresh controller (e.g., a retained `ComponentTree` is rehosted onto
    // a new `LithoView` on tab-switch back). The listener is durable — it is retained for the
    // controller's lifetime and auto-re-attached on every bind, so the second controller must also
    // receive it without the caller re-registering.
    lazyCollectionController.recyclerEventsController = null
    verify(firstController).setOnRecyclerUpdateListener(null)
    val secondController = spy<RecyclerEventsController>()
    lazyCollectionController.recyclerEventsController = secondController

    verify(secondController).setOnRecyclerUpdateListener(listener)
  }

  @Test
  fun `test scrollBy delegates to RecyclerView`() {
    val lazyCollectionController = LazyCollectionController()

    val mockRecyclerView = mock<RecyclerView>()
    val recyclerEventsController =
        mock<RecyclerEventsController> { on { recyclerView } doReturn mockRecyclerView }
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    lazyCollectionController.scrollBy(10, 20)
    verify(mockRecyclerView).scrollBy(10, 20)

    lazyCollectionController.scrollBy(30, 40)
    verify(mockRecyclerView).scrollBy(30, 40)
  }

  @Test
  fun `test smoothScrollBy delegates to RecyclerView`() {
    val lazyCollectionController = LazyCollectionController()

    val mockRecyclerView = mock<RecyclerView>()
    val recyclerEventsController =
        mock<RecyclerEventsController> { on { recyclerView } doReturn mockRecyclerView }
    lazyCollectionController.recyclerEventsController = recyclerEventsController

    lazyCollectionController.smoothScrollBy(10, 20)
    verify(mockRecyclerView).smoothScrollBy(10, 20)

    lazyCollectionController.smoothScrollBy(30, 40)
    verify(mockRecyclerView).smoothScrollBy(30, 40)
  }

  @Test
  fun `test scrollToIndex delegates to SectionTree`() {
    val lazyCollectionController = LazyCollectionController()

    val mockSectionTree = mock<SectionTree>()
    val sectionTreeScroller = SectionTreeScroller(mockSectionTree)
    lazyCollectionController.scrollerDelegate = sectionTreeScroller

    lazyCollectionController.scrollToIndex(5, 10)
    verify(mockSectionTree).requestFocusOnRoot(5, 10)
  }

  @Test
  fun `test smoothScrollToIndex delegates to SectionTree`() {
    val lazyCollectionController = LazyCollectionController()

    val mockSectionTree = mock<SectionTree>()
    val sectionTreeScroller = SectionTreeScroller(mockSectionTree)
    lazyCollectionController.scrollerDelegate = sectionTreeScroller

    lazyCollectionController.smoothScrollToIndex(5, 10, SmoothScrollAlignmentType.SNAP_TO_START)
    verify(mockSectionTree).requestSmoothFocusOnRoot(5, 10, SmoothScrollAlignmentType.SNAP_TO_START)
  }

  @Test
  fun `test scrollToId brings child with id into viewport`() {
    val lazyCollectionController = LazyCollectionController()

    class Test : KComponent() {
      override fun ComponentScope.render(): Component = LazyList(
          lazyCollectionController = lazyCollectionController,
          style = Style.viewTag("collection_tag"),
      ) {
        (0..10).forEach { child(id = it, component = Text("$it", Style.viewTag("$it"))) }
      }
    }

    val testLithoView = lithoTestRule.render(widthPx = 100, heightPx = 100) { Test() }
    lithoTestRule.idle()

    lithoTestRule.act(testLithoView) { lazyCollectionController.scrollToId(9) }

    // Additional `setRoot()` to kick the test infra into applying RecyclerView changes
    testLithoView.setRoot(Test())
    lithoTestRule.idle()

    val recyclerView = getLazyCollectionRecyclerView(testLithoView, "collection_tag")
    assertThat(recyclerView).isNotNull
    recyclerView ?: return

    val visibleRange =
        with(recyclerView.layoutManager as LinearLayoutManager) {
          (findFirstVisibleItemPosition()..findLastVisibleItemPosition())
        }
    assertThat(visibleRange).contains(9)
  }
}
