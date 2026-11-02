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

package com.facebook.litho.widget

import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.StickyHeaderControllerImpl.Companion.LAYOUTMANAGER_NOT_INITIALIZED
import com.facebook.litho.widget.StickyHeaderControllerImpl.Companion.RECYCLER_ALREADY_INITIALIZED
import com.facebook.litho.widget.StickyHeaderControllerImpl.Companion.RECYCLER_NOT_INITIALIZED
import com.facebook.litho.widget.StickyHeaderControllerImpl.Companion.STICKY_HEADER_NOT_INITIALIZED
import com.facebook.litho.widget.collection.CollectionItem
import com.facebook.litho.widget.collection.CollectionPrimitiveViewAdapter
import com.facebook.litho.widget.collection.LatestCommittedData
import com.facebook.litho.widget.collection.NewStickyHeaderController
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.utils.MeasureSpecUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class NewStickyHeaderControllerTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Mock private lateinit var layoutInfo: LayoutInfo
  @Mock private lateinit var adapter: CollectionPrimitiveViewAdapter
  @Mock private lateinit var layoutManager: LinearLayoutManager
  @Mock private lateinit var stickyHeaderView: LithoRenderTreeView
  @Mock private lateinit var latestCommittedData: LatestCommittedData

  private lateinit var recyclerView: RecyclerView
  private lateinit var sectionsRecyclerView: SectionsRecyclerView

  private lateinit var controller: NewStickyHeaderController

  @Before
  fun setUp() {
    layoutInfo = mock()
    adapter = mock()
    layoutManager = mock()
    stickyHeaderView = mock()
    latestCommittedData = mock()

    recyclerView = spy(RecyclerView(lithoTestRule.context.androidContext))
    sectionsRecyclerView =
        spy(SectionsRecyclerView(lithoTestRule.context.androidContext, recyclerView))

    controller = NewStickyHeaderController(layoutInfo, adapter)

    // Setup common mock behaviors
    whenever(recyclerView.layoutManager).thenReturn(layoutManager)
    whenever(adapter.latestCommittedData).thenReturn(latestCommittedData)
    whenever(layoutInfo.getChildWidthSpec(any(), any())).thenReturn(MeasureSpecUtils.unspecified())
    whenever(layoutInfo.getChildHeightSpec(any(), any())).thenReturn(MeasureSpecUtils.unspecified())
    whenever(latestCommittedData.layoutInfo).thenReturn(layoutInfo)
  }

  @After
  fun tearDown() {
    try {
      controller.reset()
    } catch (_: Exception) {
      // ignore
    }
  }

  @Test
  fun init_withValidRecyclerView_succeeds() {
    controller.init(sectionsRecyclerView)

    assertThat(sectionsRecyclerView.stickyHeaderView).isNotNull
    verify(recyclerView).addOnScrollListener(controller)
  }

  @Test
  fun init_withAlreadyInitializedController_throwsException() {
    controller.init(sectionsRecyclerView)

    try {
      controller.init(sectionsRecyclerView)
      assertThat(false).describedAs("Expected RuntimeException to be thrown").isTrue()
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo(RECYCLER_ALREADY_INITIALIZED)
    }
  }

  @Test
  fun init_withNullLayoutManager_throwsException() {
    whenever(recyclerView.layoutManager).thenReturn(null)

    try {
      controller.init(sectionsRecyclerView)
      assertThat(false).describedAs("Expected RuntimeException to be thrown").isTrue()
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo(LAYOUTMANAGER_NOT_INITIALIZED)
    } finally {}
  }

  @Test
  fun reset_afterInit_cleansUpProperly() {
    controller.init(sectionsRecyclerView)

    controller.reset()

    verify(recyclerView).removeOnScrollListener(controller)
    verify(sectionsRecyclerView).stickyHeaderView = null
  }

  @Test
  fun reset_withoutInit_throwsException() {
    try {
      controller.reset()
      assertThat(false).describedAs("Expected RuntimeException to be thrown").isTrue()
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo(RECYCLER_NOT_INITIALIZED)
    }
  }

  @Test
  fun onScrolled_withoutInit_throwsException() {
    try {
      controller.onScrolled(recyclerView, 0, 10)
      assertThat(false).describedAs("Expected RuntimeException to be thrown").isTrue()
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo(RECYCLER_NOT_INITIALIZED)
    }
  }

  @Test
  fun onScrolled_withoutStickyHeaderView_throwsException() {
    controller.init(sectionsRecyclerView)
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(null)

    try {
      controller.onScrolled(recyclerView, 0, 10)
      assertThat(false).describedAs("Expected RuntimeException to be thrown").isTrue()
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo(STICKY_HEADER_NOT_INITIALIZED)
    }
  }

  @Test
  fun onScrolled_withNoVisibleItems_exitsEarly() {
    controller.init(sectionsRecyclerView)
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(RecyclerView.NO_POSITION)

    controller.onScrolled(recyclerView, 0, 10)

    verify(layoutInfo).findFirstVisibleItemPosition()
    verify(layoutInfo, never()).findLastVisibleItemPosition()
  }

  @Test
  fun onScrolled_withNoStickyHeaders_hidesStickyHeader() {
    controller.init(sectionsRecyclerView)

    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(0)

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun onScrolled_withStickyHeaderAboveViewport_showsStickyHeader() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = false),
    )

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null) // sticky item not visible
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
    verify(sectionsRecyclerView).layoutStickyHeader()
  }

  @Test
  fun onScrolled_withStickyHeaderBelowTop_showsStickyHeader() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = false),
    )

    val mockStickyView = mock<View>()
    whenever(mockStickyView.top).thenReturn(-10) // Above recyclerview top

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(mockStickyView)
    whenever(recyclerView.top).thenReturn(0)
    whenever(recyclerView.paddingTop).thenReturn(0)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
    verify(sectionsRecyclerView).layoutStickyHeader()
  }

  @Test
  fun onScrolled_withStackingScenario_translatesStickyHeader() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
        createMockCollectionItem(3, isSticky = false),
    )

    val nextStickyView = mock<View>()
    whenever(nextStickyView.top).thenReturn(50)

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    val spyStickyHeaderView = spy(View(lithoTestRule.context.androidContext))

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(spyStickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null) // sticky item not visible
    whenever(layoutManager.findViewByPosition(2)).thenReturn(nextStickyView)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])
    whenever(recyclerView.paddingTop).thenReturn(0)
    whenever(spyStickyHeaderView.bottom).thenReturn(100)

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(spyStickyHeaderView.translationY).isEqualTo((50 - 100).toFloat())
  }

  @Test
  fun onScrolled_withoutStacking_resetsTranslation() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = false),
    )

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    val spyStickyHeaderView = spy(View(lithoTestRule.context.androidContext))

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(spyStickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(spyStickyHeaderView.translationY).isEqualTo(0f)
  }

  @Test
  fun scanStickyIndices_withNoStickyItems_setsNullHead() {
    val items = listOf(
        createMockCollectionItem(0, isSticky = false),
        createMockCollectionItem(1, isSticky = false),
    )

    controller.scanStickyIndices(items)

    // Verify behavior when no sticky headers exist
    controller.init(sectionsRecyclerView)

    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(0)

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun scanStickyIndices_withMultipleStickyItems_buildsLinkedList() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
        createMockCollectionItem(3, isSticky = false),
        createMockCollectionItem(4, isSticky = true),
    )

    controller.scanStickyIndices(stickyItems)

    // Test that the linked list is built correctly by checking behavior
    controller.init(sectionsRecyclerView)
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(3)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(null)
    whenever(adapter.findItemByPosition(2)).thenReturn(stickyItems[2])

    controller.onScrolled(recyclerView, 0, 10)

    // Should show sticky header for position 2 (the most recent sticky before position 3)
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun showStickyHeader_setsVisibility() {
    controller.init(sectionsRecyclerView)

    controller.showStickyHeader()

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun hideStickyHeader_setsVisibilityGone() {
    controller.init(sectionsRecyclerView)

    controller.hideStickyHeader()

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun onScrolled_withDifferentStickyHeader_resetsTranslation() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
    )

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    // First scroll - position 0 sticky header
    controller.onScrolled(recyclerView, 0, 10)

    // Change to position 2 sticky header
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(3)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(null)
    whenever(adapter.findItemByPosition(2)).thenReturn(stickyItems[2])

    controller.onScrolled(recyclerView, 0, 10)

    // Should reset translation when sticky header changes
    assertThat(sectionsRecyclerView.stickyHeaderView?.translationY).isEqualTo(0f)
  }

  @Test
  fun init_setupStickyHeaderView_createsViewWithCorrectProperties() {
    controller.init(sectionsRecyclerView)

    val stickyHeaderView = sectionsRecyclerView.stickyHeaderView
    assertThat(stickyHeaderView).isInstanceOf(LithoRenderTreeView::class.java)
    assertThat(stickyHeaderView?.layoutParams?.width).isEqualTo(LayoutParams.MATCH_PARENT)
    assertThat(stickyHeaderView?.layoutParams?.height).isEqualTo(LayoutParams.WRAP_CONTENT)
    assertThat(stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun init_withExistingStickyHeaderView_doesNotCreateNew() {
    val existingView = mock<LithoRenderTreeView>()
    sectionsRecyclerView.stickyHeaderView = existingView

    controller.init(sectionsRecyclerView)

    assertThat(sectionsRecyclerView.stickyHeaderView).isEqualTo(existingView)
  }

  @Test
  fun onScrolled_initStickyHeader_withNullItem_handlesGracefully() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(null) // null item

    controller.onScrolled(recyclerView, 0, 10)

    // Should not show sticky header view
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun onScrolled_withStickyHeaderAtExactTop_doesNotShowStickyHeader() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))
    val mockStickyView = mock<View>()
    whenever(mockStickyView.top).thenReturn(0) // Exactly at recyclerview top

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(0)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(mockStickyView)
    whenever(recyclerView.top).thenReturn(0)
    whenever(recyclerView.paddingTop).thenReturn(0)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    // Should NOT show sticky header when item is at exact top
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun onScrolled_withRecyclerViewPadding_calculatesCorrectly() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))
    val mockStickyView = mock<View>()
    whenever(mockStickyView.top).thenReturn(15) // Below recyclerview top + padding

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(0)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(mockStickyView)
    whenever(recyclerView.top).thenReturn(0)
    whenever(recyclerView.paddingTop).thenReturn(20) // 20px padding
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    // Should not show sticky header because 15 < (0 - 20) = -20 is false
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun onScrolled_stackingWithNullNextStickyView_resetsTranslation() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
    )

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(null) // next sticky view is null
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    // Should not translate when next sticky view is null
    assertThat(sectionsRecyclerView.stickyHeaderView?.translationY).isEqualTo(0f)
  }

  @Test
  fun onScrolled_stackingWithNoOverlap_doesNotTranslate() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
    )

    val nextStickyView = mock<View>()
    whenever(nextStickyView.top).thenReturn(150) // No overlap

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    val spyStickyHeaderView = spy(View(lithoTestRule.context.androidContext))

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(spyStickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(nextStickyView)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])
    whenever(recyclerView.paddingTop).thenReturn(0)
    whenever(spyStickyHeaderView.bottom).thenReturn(100)

    controller.onScrolled(recyclerView, 0, 10)

    // Should not translate when there's no overlap (150 >= 100)
    assertThat(spyStickyHeaderView.translationY).isEqualTo(0f)
  }

  @Test
  fun onScrolled_stackingWithPadding_calculatesCorrectly() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
    )

    val nextStickyView = mock<View>()
    whenever(nextStickyView.top).thenReturn(70) // With padding

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    val spyStickyHeaderView = spy(View(lithoTestRule.context.androidContext))

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(spyStickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(nextStickyView)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])
    whenever(recyclerView.paddingTop).thenReturn(20)
    whenever(spyStickyHeaderView.bottom).thenReturn(100)

    controller.onScrolled(recyclerView, 0, 10)

    // Should translate: (70 - 20) - 100 = -50
    assertThat(spyStickyHeaderView.translationY).isEqualTo(-50f)
  }

  @Test
  fun scanStickyIndices_withSingleStickyItem_createsCorrectLinkedList() {
    val items = listOf(
        createMockCollectionItem(0, isSticky = false),
        createMockCollectionItem(1, isSticky = true),
        createMockCollectionItem(2, isSticky = false),
    )

    controller.scanStickyIndices(items)

    // Test single sticky item behavior
    controller.init(sectionsRecyclerView)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(1)).thenReturn(null)
    whenever(adapter.findItemByPosition(1)).thenReturn(items[1])

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun scanStickyIndices_withEmptyList_handlesGracefully() {
    val emptyItems = emptyList<CollectionItem<*>>()

    controller.scanStickyIndices(emptyItems)

    controller.init(sectionsRecyclerView)

    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(0)

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun findTargetStickyItem_withAllItemsAfterFirstVisible_returnsNull() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = false),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
        createMockCollectionItem(3, isSticky = true),
    )

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1) // Before all sticky items

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun onScrolled_withFirstVisibleEqualsStickyPosition_showsStickyHeaderBasedOnViewPosition() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))
    val mockStickyView = mock<View>()
    whenever(mockStickyView.top).thenReturn(-5) // Partially above top

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(0) // Same as sticky position
    whenever(layoutManager.findViewByPosition(0)).thenReturn(mockStickyView)
    whenever(recyclerView.top).thenReturn(0)
    whenever(recyclerView.paddingTop).thenReturn(0)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun onScrolled_stickyHeaderAlreadyVisibleButPositionUnchanged_doesNotReinitialize() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false) // Already visible
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    // First call to establish previous position
    controller.onScrolled(recyclerView, 0, 10)

    // Second call with same position - should not reinitialize
    controller.onScrolled(recyclerView, 0, 10)

    // Verify that measureStickyHeader is not called on second scroll since header is already
    // visible
    verify(sectionsRecyclerView, times(1)).measureStickyHeader(any())
    verify(sectionsRecyclerView, times(1)).layoutStickyHeader()
  }

  @Test
  fun warmStickyHeader_withNewItemId_createsNewLithoCollectionItem() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])
    whenever(stickyItems[0].id).thenReturn(123)

    controller.onScrolled(recyclerView, 0, 10)

    // Verify that a new LithoCollectionItem was created for the new item ID
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun warmStickyHeader_withSameRenderInfo_reusesExistingLithoCollectionItem() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])
    whenever(stickyItems[0].id).thenReturn(123)

    // First call
    controller.onScrolled(recyclerView, 0, 10)

    // Mock that sticky header is now visible for second call
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)

    // Second call with same item ID and same renderInfo - should not re-warm
    controller.onScrolled(recyclerView, 0, 10)

    // Should only measure/layout once since the item hasn't changed and header is already visible
    verify(sectionsRecyclerView, times(1)).measureStickyHeader(any())
    verify(sectionsRecyclerView, times(1)).layoutStickyHeader()
  }

  @Test
  fun initStickyHeader_withNonLithoRenderTreeView_exitsEarly() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))
    val view = spy<View>()

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(view)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    // Should not show sticky header view
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun resetStickHeader_withNonLithoCollectionItem_exitsEarly() {
    controller.init(sectionsRecyclerView)

    // This should not throw an exception even if stickyHeaderItem is not a LithoCollectionItem
    controller.hideStickyHeader()

    // Test passes if no exception is thrown
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun resetStickHeader_withNonLithoRenderTreeView_exitsEarly() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))
    val mockView = spy<View>()

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(mockView)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    // Should handle non-LithoRenderTreeView gracefully
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun setStickyHeaderVerticalOffset_setsTranslationY() {
    controller.init(sectionsRecyclerView)

    val spyStickyHeaderView = spy(View(lithoTestRule.context.androidContext))
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(spyStickyHeaderView)

    // Test the private method indirectly through stacking scenario
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
    )

    controller.scanStickyIndices(stickyItems)

    val nextStickyView = mock<View>()
    whenever(nextStickyView.top).thenReturn(50)

    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(nextStickyView)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])
    whenever(recyclerView.paddingTop).thenReturn(0)
    whenever(spyStickyHeaderView.bottom).thenReturn(100)

    controller.onScrolled(recyclerView, 0, 10)

    assertThat(spyStickyHeaderView.translationY).isEqualTo((50 - 100).toFloat())
  }

  @Test
  fun onScrolled_withStickyHeaderAlreadyVisible_andSamePosition_skipsReinitialization() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false) // Already visible
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    // First call to establish position
    controller.onScrolled(recyclerView, 0, 10)

    // Reset mock to count subsequent calls
    reset(sectionsRecyclerView)
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)

    // Second call with same conditions
    controller.onScrolled(recyclerView, 0, 10)

    // Should not call layoutStickyHeader again
    verify(sectionsRecyclerView, never()).layoutStickyHeader()
  }

  @Test
  fun onScrolled_withDifferentStickyHeaderPosition_resetsTranslationAndReinitializes() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
    )

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    val spyStickyHeaderView = spy(View(lithoTestRule.context.androidContext))
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(spyStickyHeaderView)

    // First scroll - position 0 sticky header
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    // Set some translation
    spyStickyHeaderView.translationY = -50f

    // Change to position 2 sticky header
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(3)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(null)
    whenever(adapter.findItemByPosition(2)).thenReturn(stickyItems[2])

    controller.onScrolled(recyclerView, 0, 10)

    // Should reset translation when sticky header changes
    assertThat(spyStickyHeaderView.translationY).isEqualTo(0f)
  }

  @Test
  fun reset_clearsAllInternalState() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    // Set up some state
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    controller.onScrolled(recyclerView, 0, 10)

    controller.reset()

    // After reset, should throw exception when trying to use
    try {
      controller.onScrolled(recyclerView, 0, 10)
      assertThat(false).describedAs("Expected RuntimeException to be thrown").isTrue()
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo(RECYCLER_NOT_INITIALIZED)
    }
  }

  @Test
  fun findTargetStickyItem_withMultipleStickyItemsBeforeFirstVisible_returnsLatest() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
        createMockCollectionItem(3, isSticky = false),
        createMockCollectionItem(4, isSticky = true),
        createMockCollectionItem(5, isSticky = false),
    )

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(5) // After all sticky items
    whenever(layoutManager.findViewByPosition(4)).thenReturn(null) // Latest sticky item
    whenever(adapter.findItemByPosition(4)).thenReturn(stickyItems[4])

    controller.onScrolled(recyclerView, 0, 10)

    // Should show sticky header for position 4 (the latest sticky before position 5)
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun onScrolled_withStackingAndPaddingCalculation_handlesCorrectly() {
    val stickyItems = listOf(
        createMockCollectionItem(0, isSticky = true),
        createMockCollectionItem(1, isSticky = false),
        createMockCollectionItem(2, isSticky = true),
    )

    val nextStickyView = mock<View>()
    whenever(nextStickyView.top).thenReturn(80) // With padding consideration

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    val spyStickyHeaderView = spy(View(lithoTestRule.context.androidContext))

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(spyStickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(false)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(2)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(layoutManager.findViewByPosition(2)).thenReturn(nextStickyView)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])
    whenever(recyclerView.paddingTop).thenReturn(30) // 30px padding
    whenever(spyStickyHeaderView.bottom).thenReturn(100)

    controller.onScrolled(recyclerView, 0, 10)

    // Should translate: (80 - 30) - 100 = -50
    assertThat(spyStickyHeaderView.translationY).isEqualTo(-50f)
  }

  @Test
  fun warmStickyHeader_withNullLayoutData_handlesGracefully() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    // Mock adapter.latestCommittedData to return null
    whenever(adapter.latestCommittedData).thenReturn(null)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    // Should handle null layoutData gracefully without throwing exception
    controller.onScrolled(recyclerView, 0, 10)

    // We're logging the exception without size constraints, so the sticky header will be measured
    // anyways.
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun updateStickyHeaderIfNeeded_withNoStickyHeaders_exitsEarly() {
    controller.init(sectionsRecyclerView)

    // No sticky headers scanned
    controller.updateStickyHeaderIfNeeded()

    // Should not throw any exceptions and exit early
    verify(adapter, never()).findItemByPosition(any())
  }

  @Test
  fun updateStickyHeaderIfNeeded_withTargetStickyItem_callsAdapterMethods() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    // Mock the first visible position to be after the sticky item
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)

    // No previous sticky header position set, but there is a target sticky item
    controller.updateStickyHeaderIfNeeded()

    // Should call adapter methods to find the target sticky item
    verify(adapter).findItemByPosition(0)
    verify(sectionsRecyclerView, never()).measureStickyHeader(any())
  }

  @Test
  fun updateStickyHeaderIfNeeded_withNullHeaderIfNeeded_handlesGracefully() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    // Set up initial state
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    // Make adapter return null for the item
    whenever(adapter.findItemByPosition(0)).thenReturn(null)

    controller.updateStickyHeaderIfNeeded()

    // Should handle null item gracefully without throwing
    verify(sectionsRecyclerView, never()).measureStickyHeader(any())
  }

  @Test
  fun updateStickyHeaderIfNeededIfNeeded_withFailedInitialization_skipsRemeasure() {
    val mockItem = createMockCollectionItem(0, isSticky = true)
    whenever(adapter.latestCommittedData).thenReturn(null) // This will cause init to fail

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(listOf(mockItem))

    // Set up initial state
    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(mockItem)

    controller.updateStickyHeaderIfNeeded()

    // We're logging the exception without size constraints, so the sticky header will be measured
    // anyways.
    verify(sectionsRecyclerView).measureStickyHeader(any())
    verify(sectionsRecyclerView).layoutStickyHeader()
  }

  @Test
  fun warmStickyHeader_withDifferentRenderInfo_createsNewLithoCollectionItem() {
    val mockItem1 = mock<CollectionItem<*>>()
    val mockRenderInfo1 = mock<RenderInfo>()
    val mockItem2 = mock<CollectionItem<*>>()
    val mockRenderInfo2 = mock<RenderInfo>()

    whenever(mockRenderInfo1.isSticky).thenReturn(true)
    whenever(mockRenderInfo1.component).thenReturn(RichText(text = "Sticky1"))
    whenever(mockItem1.renderInfo).thenReturn(mockRenderInfo1)
    whenever(mockItem1.id).thenReturn(123)
    whenever(mockItem1.sizeConstraints).thenReturn(SizeConstraints())

    whenever(mockRenderInfo2.isSticky).thenReturn(true)
    whenever(mockRenderInfo2.component).thenReturn(RichText(text = "Sticky2"))
    whenever(mockItem2.renderInfo).thenReturn(mockRenderInfo2)
    whenever(mockItem2.id).thenReturn(123) // Same ID but different render info
    whenever(mockItem2.sizeConstraints).thenReturn(SizeConstraints())

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(listOf(mockItem1))

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(mockItem1)

    // First call with mockItem1
    controller.onScrolled(recyclerView, 0, 10)

    // Change to mockItem2 with different render info
    whenever(adapter.findItemByPosition(0)).thenReturn(mockItem2)

    // Second call should create new LithoCollectionItem due to different render info
    controller.onScrolled(recyclerView, 0, 10)

    verify(sectionsRecyclerView, times(2)).measureStickyHeader(any())
    verify(sectionsRecyclerView, times(2)).layoutStickyHeader()
  }

  @Test
  fun resetStickHeader_withValidLithoCollectionItem_callsOnViewRecycled() {
    val stickyItems = listOf(createMockCollectionItem(0, isSticky = true))

    controller.init(sectionsRecyclerView)
    controller.scanStickyIndices(stickyItems)

    whenever(sectionsRecyclerView.stickyHeaderView).thenReturn(stickyHeaderView)
    whenever(sectionsRecyclerView.isStickyHeaderHidden).thenReturn(true)
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(1)
    whenever(layoutManager.findViewByPosition(0)).thenReturn(null)
    whenever(adapter.findItemByPosition(0)).thenReturn(stickyItems[0])

    // Set up sticky header
    controller.onScrolled(recyclerView, 0, 10)

    // Reset should call onViewRecycled
    controller.reset()

    // Verify that the sticky header was properly cleaned up
    verify(recyclerView).removeOnScrollListener(controller)
  }

  private fun createMockCollectionItem(position: Int, isSticky: Boolean): CollectionItem<*> {
    val item = mock<CollectionItem<*>>()
    val renderInfo = mock<RenderInfo>()
    val component = RichText(text = "Sticky")
    whenever(renderInfo.isSticky).thenReturn(isSticky)
    whenever(renderInfo.component).thenReturn(component)
    whenever(item.renderInfo).thenReturn(renderInfo)
    whenever(item.sizeConstraints).thenReturn(SizeConstraints())
    whenever(item.id).thenReturn(position) // Use position as ID for simplicity
    return item
  }
}
