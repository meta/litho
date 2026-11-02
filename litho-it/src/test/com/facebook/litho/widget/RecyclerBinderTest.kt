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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.AccessibilityUtils
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.ComponentsReporter
import com.facebook.litho.EmptyComponent
import com.facebook.litho.EventHandler
import com.facebook.litho.LithoView
import com.facebook.litho.LithoVisibilityEventsController
import com.facebook.litho.RenderCompleteEvent
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec
import com.facebook.litho.SizeSpec.AT_MOST
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.UNSPECIFIED
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.StyleCompat
import com.facebook.litho.Wrapper
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.specmodels.internal.ImmutableList
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.ThreadTestingUtils
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec
import com.facebook.litho.testing.logging.TestComponentsReporter
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.viewcompat.SimpleViewBinder
import com.facebook.litho.viewcompat.ViewBinder
import com.facebook.litho.viewcompat.ViewCreator
import com.facebook.litho.widget.ComponentTreeHolder.ComponentTreeMeasureListenerFactory
import com.facebook.litho.widget.RecyclerBinder.RenderCompleteRunnable
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.RunnableHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper

/** Tests for [RecyclerBinder] */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RecyclerBinderTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  @get:Rule var expectedException: ExpectedException = ExpectedException.none()

  private val holdersForComponents = HashMap<Component, TestComponentTreeHolder>()
  private lateinit var recyclerBinder: RecyclerBinder
  private lateinit var recyclerBinderForAsyncInitRangeBuilder: RecyclerBinder.Builder
  private lateinit var recyclerBinderBuilder: RecyclerBinder.Builder
  private lateinit var circularRecyclerBinder: RecyclerBinder
  private lateinit var layoutInfo: LayoutInfo
  private lateinit var circularLayoutInfo: LayoutInfo
  private lateinit var componentContext: ComponentContext
  private lateinit var layoutThreadShadowLooper: ShadowLooper
  private lateinit var componentTreeHolderFactory: RecyclerBinder.ComponentTreeHolderFactory
  private lateinit var componentTreeHolderFactoryLayoutFinishedListener:
      RecyclerBinder.ComponentTreeHolderFactory

  @Before
  fun setup() {
    holdersForComponents.clear()

    componentContext = ComponentContext(getApplicationContext())
    componentContext.androidContext.setTheme(0)

    componentTreeHolderFactory =
        object : RecyclerBinder.ComponentTreeHolderFactory {
          override fun create(
              renderInfo: RenderInfo,
              layoutHandler: RunnableHandler?,
              componentTreeMeasureListenerFactory: ComponentTreeMeasureListenerFactory?,
              componentsConfiguration: ComponentsConfiguration,
              lifecycleProvider: LithoVisibilityEventsController?,
          ): ComponentTreeHolder {
            val holder = TestComponentTreeHolder(renderInfo)
            if (renderInfo.rendersComponent()) {
              holdersForComponents[renderInfo.component] = holder
              holder.mLayoutHandler = layoutHandler
            }
            return holder
          }
        }

    componentTreeHolderFactoryLayoutFinishedListener =
        object : RecyclerBinder.ComponentTreeHolderFactory {
          override fun create(
              renderInfo: RenderInfo,
              layoutHandler: RunnableHandler?,
              componentTreeMeasureListenerFactory: ComponentTreeMeasureListenerFactory?,
              componentsConfiguration: ComponentsConfiguration,
              lifecycleProvider: LithoVisibilityEventsController?,
          ): ComponentTreeHolder {
            val holder = TestComponentTreeHolder(renderInfo, true)
            if (renderInfo.rendersComponent()) {
              holdersForComponents[renderInfo.component] = holder
              holder.mLayoutHandler = layoutHandler
            }
            return holder
          }
        }

    layoutInfo = mock(LayoutInfo::class.java)
    circularLayoutInfo = mock(LayoutInfo::class.java)

    setupBaseLayoutInfoMock(layoutInfo, OrientationHelper.VERTICAL)
    setupBaseLayoutInfoMock(circularLayoutInfo, OrientationHelper.HORIZONTAL)

    recyclerBinderBuilder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(layoutInfo)
            .componentTreeHolderFactory(componentTreeHolderFactory)

    recyclerBinderForAsyncInitRangeBuilder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(0f).build())
            .layoutInfo(layoutInfo)
            .componentTreeHolderFactory(componentTreeHolderFactoryLayoutFinishedListener)

    recyclerBinder = recyclerBinderBuilder.build(componentContext)

    circularRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(circularLayoutInfo)
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .recyclerBinderConfig(RecyclerBinderConfig.create().isCircular(true).build())
            .build(componentContext)

    layoutThreadShadowLooper =
        Shadow.extract(ComponentTree.getDefaultLayoutThreadLooper()) as ShadowLooper
  }

  private fun runToEndOfTasks() {
    layoutThreadShadowLooper.runToEndOfTasks()
  }

  private fun runOneTask() {
    layoutThreadShadowLooper.runOneTask()
  }

  @After
  fun tearDown() {
    runToEndOfTasks()
  }

  private fun setupBaseLayoutInfoMock(layoutInfo: LayoutInfo, orientation: Int) {
    whenever(layoutInfo.getScrollDirection()).thenReturn(orientation)

    whenever(layoutInfo.getLayoutManager())
        .thenReturn(LinearLayoutManager(componentContext.androidContext, orientation, false))

    whenever(layoutInfo.approximateRangeSize(anyInt(), anyInt(), anyInt(), anyInt()))
        .thenReturn(RANGE_SIZE)

    whenever(layoutInfo.getChildHeightSpec(anyInt(), anyOrNull()))
        .thenReturn(SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY))
    whenever(layoutInfo.getChildWidthSpec(anyInt(), anyOrNull()))
        .thenReturn(SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY))
  }

  @Test
  fun testComponentTreeHolderCreation() {
    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
      recyclerBinder.insertItemAt(0, components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until 100) {
      Assert.assertNotNull(holdersForComponents[components[i].component])
    }
  }

  @Test
  fun testAppendItems() {
    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
      recyclerBinder.appendItem(components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until 100) {
      assertThat(holdersForComponents[components[i].component]).isNotNull
      assertThat(components[i]).isEqualTo(recyclerBinder.getRenderInfoAt(i))
    }
  }

  @Test
  fun testOnMeasureAfterAddingItems() {
    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
      recyclerBinder.insertItemAt(i, components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until 100) {
      assertThat(holdersForComponents[components[i].component]).isNotNull
    }

    val size = Size()
    val widthSpec = makeSizeSpec(200, AT_MOST)
    val heightSpec = makeSizeSpec(200, EXACTLY)

    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    var componentTreeHolder = checkNotNull(holdersForComponents[components[0].component])

    assertThat(componentTreeHolder.isTreeValid).isTrue
    assertThat(componentTreeHolder.mLayoutSyncCalled).isTrue

    val rangeTotal = RANGE_SIZE + (RANGE_SIZE * RANGE_RATIO).toInt()

    for (i in 1..rangeTotal) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(componentTreeHolder.isTreeValid).isTrue
      assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue
      assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
    }

    for (k in rangeTotal + 1 until components.size) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[k].component])

      assertThat(componentTreeHolder.isTreeValid).isFalse
      assertThat(componentTreeHolder.mLayoutAsyncCalled).isFalse
      assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
    }

    assertThat(size.width).isEqualTo(200)
  }

  @Test
  fun onBoundsDefined() {
    val components = prepareLoadedBinder()
    for (i in components.indices) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    recyclerBinder.setSize(200, 200)

    for (i in components.indices) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.mLayoutAsyncCalled).isFalse
      assertThat(holder.mLayoutSyncCalled).isFalse
    }
  }

  @Test
  fun onBoundsDefinedWithDifferentSize() {
    val components = prepareLoadedBinder()
    for (i in components.indices) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    recyclerBinder.setSize(300, 200)

    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    var holder = checkNotNull(holdersForComponents[components[0].component])
    assertThat(holder.isTreeValid).isTrue
    assertThat(holder.mLayoutSyncCalled).isTrue

    for (i in 1..rangeTotal) {
      holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isTrue
      assertThat(holder.mLayoutAsyncCalled).isTrue
    }

    for (i in rangeTotal + 1 until components.size) {
      holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isFalse
      assertThat(holder.mLayoutAsyncCalled).isFalse
      assertThat(holder.mLayoutSyncCalled).isFalse
    }
  }

  @Test
  fun testMount() {
    val recyclerView = mock(RecyclerView::class.java)
    recyclerBinder.mount(recyclerView)

    verify(recyclerView).layoutManager = layoutInfo.getLayoutManager()
    verify(recyclerView).adapter = any(RecyclerView.Adapter::class.java)
    verify(layoutInfo).setRenderInfoCollection(recyclerBinder)
    verify(recyclerView).addOnScrollListener(any(OnScrollListener::class.java))
  }

  @Test
  fun testMountWithStaleView() {
    val recyclerView = mock(RecyclerView::class.java)
    recyclerBinder.mount(recyclerView)

    verify(recyclerView).layoutManager = layoutInfo.getLayoutManager()
    verify(recyclerView).adapter = any(RecyclerView.Adapter::class.java)
    verify(recyclerView).addOnScrollListener(any(OnScrollListener::class.java))

    val secondRecyclerView = mock(RecyclerView::class.java)
    recyclerBinder.mount(secondRecyclerView)

    verify(recyclerView).layoutManager = null
    verify(recyclerView).adapter = null
    verify(recyclerView).removeOnScrollListener(any(OnScrollListener::class.java))

    verify(secondRecyclerView).layoutManager = layoutInfo.getLayoutManager()
    verify(secondRecyclerView).adapter = any(RecyclerView.Adapter::class.java)
    verify(secondRecyclerView).addOnScrollListener(any(OnScrollListener::class.java))
  }

  @Test
  fun testUnmount() {
    val recyclerView = mock(RecyclerView::class.java)
    recyclerBinder.mount(recyclerView)

    verify(recyclerView).layoutManager = layoutInfo.getLayoutManager()
    verify(recyclerView).adapter = any(RecyclerView.Adapter::class.java)
    verify(recyclerView).addOnScrollListener(any(OnScrollListener::class.java))

    recyclerBinder.unmount(recyclerView)

    verify(recyclerView).layoutManager = null
    verify(recyclerView).adapter = null
    verify(layoutInfo).setRenderInfoCollection(null)
    verify(recyclerView).removeOnScrollListener(any(OnScrollListener::class.java))
  }

  @Test
  fun testScrollRestorationVertical() {
    testScrollRestoration(verticalScroll = true, reverseLayout = false)
  }

  @Test
  fun testScrollRestorationVerticalReversed() {
    testScrollRestoration(verticalScroll = true, reverseLayout = true)
  }

  @Test
  fun testScrollRestorationHorizontal() {
    testScrollRestoration(verticalScroll = false, reverseLayout = false)
  }

  @Test
  fun testScrollRestorationHorizontalReversed() {
    testScrollRestoration(verticalScroll = false, reverseLayout = true)
  }

  private fun testScrollRestoration(verticalScroll: Boolean, reverseLayout: Boolean) {
    val firstView = View(componentContext.androidContext)

    val layoutManager = mock(LinearLayoutManager::class.java)
    whenever(layoutManager.findViewByPosition(SCROLL_RESTORATION_VIEW_POSITION))
        .thenReturn(firstView)
    whenever(layoutManager.reverseLayout).thenReturn(reverseLayout)

    val recyclerView = mock(RecyclerView::class.java)
    whenever(recyclerView.layoutManager).thenReturn(layoutManager)

    val layoutInfo = mock(LayoutInfo::class.java)
    whenever(layoutInfo.getScrollDirection())
        .thenReturn(
            if (verticalScroll) OrientationHelper.VERTICAL else OrientationHelper.HORIZONTAL,
        )
    whenever(layoutInfo.getLayoutManager()).thenReturn(layoutManager)

    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(layoutInfo)
            .build(componentContext)

    // Arbitrary relevant dimensions for the RecyclerView, its padding, and the item view.
    val recyclerViewSize = SCROLL_RESTORATION_RECYCLER_VIEW_SIZE
    val paddingEdge = SCROLL_RESTORATION_PADDING_EDGE
    val itemEdge = SCROLL_RESTORATION_ITEM_EDGE

    val paddingSize = if (reverseLayout) recyclerViewSize - paddingEdge else paddingEdge
    val trueOffset = if (reverseLayout) paddingEdge - itemEdge else itemEdge - paddingEdge

    if (verticalScroll) {
      if (reverseLayout) {
        whenever(recyclerView.height).thenReturn(recyclerViewSize)
        whenever(layoutManager.paddingBottom).thenReturn(paddingSize)
        whenever(layoutManager.getDecoratedBottom(firstView)).thenReturn(itemEdge)
      } else {
        whenever(layoutManager.paddingTop).thenReturn(paddingSize)
        whenever(layoutManager.getDecoratedTop(firstView)).thenReturn(itemEdge)
      }
    } else {
      if (reverseLayout) {
        whenever(recyclerView.width).thenReturn(recyclerViewSize)
        whenever(layoutManager.paddingRight).thenReturn(paddingSize)
        whenever(layoutManager.getDecoratedRight(firstView)).thenReturn(itemEdge)
      } else {
        whenever(layoutManager.paddingLeft).thenReturn(paddingSize)
        whenever(layoutManager.getDecoratedLeft(firstView)).thenReturn(itemEdge)
      }
    }

    recyclerBinder.mount(recyclerView)
    // Tell the RecyclerBinder to use our arbitrary position for scroll restoration.
    recyclerBinder.onNewVisibleRange(
        SCROLL_RESTORATION_VIEW_POSITION,
        SCROLL_RESTORATION_VIEW_POSITION,
    )
    // Unmount the RecyclerView, causing the current scroll offset to be stored.
    recyclerBinder.unmount(recyclerView)
    // Remount the RecyclerView, causing it to scroll using the stored scroll offset.
    recyclerBinder.mount(recyclerView)

    verify(layoutInfo).scrollToPositionWithOffset(SCROLL_RESTORATION_VIEW_POSITION, trueOffset)
  }

  @Test
  fun testAddStickyHeaderIfSectionsRecyclerViewExists() {
    val recyclerView = mock(RecyclerView::class.java)
    val recycler = mock(SectionsRecyclerView::class.java)
    whenever(recycler.context).thenReturn(lithoTestRule.context.androidContext)

    whenever(recyclerView.parent).thenReturn(recycler)
    whenever(recyclerView.layoutManager).thenReturn(mock(RecyclerView.LayoutManager::class.java))
    whenever(recycler.recyclerView).thenReturn(recyclerView)

    recyclerBinder.mount(recyclerView)

    verify(recyclerView).adapter = any(RecyclerView.Adapter::class.java)
    verify(recyclerView, times(2)).addOnScrollListener(any(OnScrollListener::class.java))
  }

  @Test
  fun onRemeasureWithDifferentSize() {
    val components = prepareLoadedBinder()
    for (i in components.indices) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    val widthSpec = makeSizeSpec(100, EXACTLY)
    val heightSpec = makeSizeSpec(200, EXACTLY)

    recyclerBinder.measure(Size(), widthSpec, heightSpec, null)
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    var holder = checkNotNull(holdersForComponents[components[0].component])
    assertThat(holder.isTreeValid).isTrue
    assertThat(holder.mLayoutSyncCalled).isTrue

    for (i in 1..rangeTotal) {
      holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isTrue
      assertThat(holder.mLayoutAsyncCalled).isTrue
    }

    for (i in rangeTotal + 1 until components.size) {
      holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isFalse
      assertThat(holder.mLayoutAsyncCalled).isFalse
      assertThat(holder.mLayoutSyncCalled).isFalse
    }
  }

  @Test
  fun testComponentWithDifferentSpanSize() {
    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 100) {
      components.add(
          ComponentRenderInfo.create()
              .component(mock(Component::class.java))
              .spanSize(if (i == 0 || i % 3 == 0) 2 else 1)
              .build(),
      )

      recyclerBinder.insertItemAt(i, components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    whenever(layoutInfo.getChildWidthSpec(anyInt(), anyOrNull())).thenAnswer {
        invocation: InvocationOnMock ->
      val renderInfo = invocation.arguments[1] as RenderInfo
      val spanSize = renderInfo.spanSize

      makeSizeSpec(100 * spanSize, EXACTLY)
    }

    for (i in 0 until 100) {
      assertThat(holdersForComponents[components[i].component]).isNotNull
    }

    val size = Size()
    val widthSpec = makeSizeSpec(200, EXACTLY)
    val heightSpec = makeSizeSpec(200, EXACTLY)

    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    var componentTreeHolder = checkNotNull(holdersForComponents[components[0].component])

    assertThat(componentTreeHolder.isTreeValid).isTrue
    assertThat(componentTreeHolder.mLayoutSyncCalled).isTrue
    assertThat(size.width).isEqualTo(200)

    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    for (i in 1..rangeTotal) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      assertThat(componentTreeHolder.isTreeValid).isTrue
      assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue

      val expectedWidth = if (i % 3 == 0) 200 else 100
      assertThat(componentTreeHolder.mChildWidth).isEqualTo(expectedWidth)
      assertThat(componentTreeHolder.mChildHeight).isEqualTo(100)
    }
  }

  @Test
  fun testAddItemsAfterMeasuring() {
    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)

    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    assertThat(size.width).isEqualTo(200)

    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
      recyclerBinder.insertItemAt(i, components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until 100) {
      Assert.assertNotNull(holdersForComponents[components[i].component])
    }

    var componentTreeHolder: TestComponentTreeHolder?

    val rangeTotal = RANGE_SIZE + (RANGE_SIZE * RANGE_RATIO).toInt()

    // The first component is used to calculate the range
    val firstHolder = checkNotNull(holdersForComponents[components[0].component])
    assertThat(firstHolder.isTreeValid).isTrue
    assertThat(firstHolder.mLayoutSyncCalled).isTrue

    for (i in 1 until RANGE_SIZE) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      assertThat(componentTreeHolder.isTreeValid).isTrue
      assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue
      assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
    }

    for (i in RANGE_SIZE..rangeTotal) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      assertThat(componentTreeHolder.isTreeValid).isTrue
      assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue
      assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
    }

    for (i in rangeTotal + 1 until components.size) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      assertThat(componentTreeHolder.isTreeValid).isFalse
      assertThat(componentTreeHolder.mLayoutAsyncCalled).isFalse
      assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
    }
  }

  @Test
  fun testRequestRemeasure() {
    val size = Size()
    val widthSpec = makeSizeSpec(200, AT_MOST)
    val heightSpec = makeSizeSpec(200, EXACTLY)
    val recyclerView = mock(RecyclerView::class.java)

    recyclerBinder.mount(recyclerView)
    @Suppress("UNCHECKED_CAST")
    recyclerBinder.measure(
        size,
        widthSpec,
        heightSpec,
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    assertThat(size.width).isEqualTo(0)

    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
      recyclerBinder.insertItemAt(i, components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(recyclerView).removeCallbacks(recyclerBinder.remeasureRunnable)
    verify(recyclerView).postOnAnimation(recyclerBinder.remeasureRunnable)
  }

  @Test
  fun testRequestRemeasureInsertRange() {
    val size = Size()
    val widthSpec = makeSizeSpec(200, AT_MOST)
    val heightSpec = makeSizeSpec(200, EXACTLY)
    val recyclerView = mock(RecyclerView::class.java)

    recyclerBinder.mount(recyclerView)
    @Suppress("UNCHECKED_CAST")
    recyclerBinder.measure(
        size,
        widthSpec,
        heightSpec,
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    assertThat(size.width).isEqualTo(0)

    val components = ArrayList<RenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }
    recyclerBinder.insertRangeAt(0, components)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(recyclerView).removeCallbacks(recyclerBinder.remeasureRunnable)
    verify(recyclerView).postOnAnimation(recyclerBinder.remeasureRunnable)
  }

  @Test
  fun testRequestRemeasureUpdateAt() {
    val size = Size()
    val widthSpec = makeSizeSpec(200, AT_MOST)
    val heightSpec = makeSizeSpec(200, EXACTLY)
    val recyclerView = mock(RecyclerView::class.java)

    recyclerBinder.mount(recyclerView)
    @Suppress("UNCHECKED_CAST")
    recyclerBinder.measure(
        size,
        widthSpec,
        heightSpec,
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    assertThat(size.width).isEqualTo(0)

    val components = ArrayList<RenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }
    recyclerBinder.insertRangeAt(0, components)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    reset(recyclerView)

    for (i in 0 until 50) {
      recyclerBinder.updateItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(recyclerView).removeCallbacks(recyclerBinder.remeasureRunnable)
    verify(recyclerView).postOnAnimation(recyclerBinder.remeasureRunnable)
  }

  @Test
  fun testRequestRemeasureUpdateRange() {
    val size = Size()
    val widthSpec = makeSizeSpec(200, AT_MOST)
    val heightSpec = makeSizeSpec(200, EXACTLY)
    val recyclerView = mock(RecyclerView::class.java)

    recyclerBinder.mount(recyclerView)
    @Suppress("UNCHECKED_CAST")
    recyclerBinder.measure(
        size,
        widthSpec,
        heightSpec,
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    assertThat(size.width).isEqualTo(0)

    val components = ArrayList<RenderInfo>()
    val updatedComponents = ArrayList<RenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }
    for (i in 0 until 50) {
      updatedComponents.add(
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    recyclerBinder.insertRangeAt(0, components)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    reset(recyclerView)

    recyclerBinder.updateRangeAt(0, updatedComponents)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(recyclerView).removeCallbacks(recyclerBinder.remeasureRunnable)
    verify(recyclerView).postOnAnimation(recyclerBinder.remeasureRunnable)
  }

  @Test
  fun testDoesntRequestRemeasure() {
    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val recyclerView = mock(RecyclerView::class.java)

    recyclerBinder.mount(recyclerView)
    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    assertThat(size.width).isEqualTo(200)

    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 100) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
      recyclerBinder.insertItemAt(i, components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(recyclerView, never()).removeCallbacks(recyclerBinder.remeasureRunnable)
    verify(recyclerView, never()).postOnAnimation(recyclerBinder.remeasureRunnable)
  }

  @Test
  fun testRangeBiggerThanContent() {
    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until 2) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
      recyclerBinder.insertItemAt(i, components[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until 2) {
      assertThat(holdersForComponents[components[i].component]).isNotNull
    }

    val size = Size()
    val widthSpec = makeSizeSpec(200, AT_MOST)
    val heightSpec = makeSizeSpec(200, EXACTLY)

    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    var componentTreeHolder = checkNotNull(holdersForComponents[components[0].component])

    assertThat(componentTreeHolder.isTreeValid).isTrue
    assertThat(componentTreeHolder.mLayoutSyncCalled).isTrue

    for (i in 1 until 2) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      assertThat(componentTreeHolder.isTreeValid).isTrue
      assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue
      assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
    }

    assertThat(size.width).isEqualTo(200)
  }

  @Test
  fun testMoveRange() {
    val components = prepareLoadedBinder()
    val newRangeStart = 40
    val newRangeEnd = 42
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    recyclerBinder.onNewVisibleRange(newRangeStart, newRangeEnd)

    var componentTreeHolder: TestComponentTreeHolder?
    for (i in components.indices) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      if (i >= newRangeStart - (RANGE_RATIO * RANGE_SIZE) && i <= newRangeStart + rangeTotal) {
        assertThat(componentTreeHolder.isTreeValid).isTrue
        assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue
        assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
      } else {
        assertThat(componentTreeHolder.isTreeValid).isFalse
        assertThat(componentTreeHolder.mLayoutAsyncCalled).isFalse
        assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
        if (i <= rangeTotal) {
          assertThat(componentTreeHolder.mDidAcquireStateHandler).isTrue
        } else {
          assertThat(componentTreeHolder.mDidAcquireStateHandler).isFalse
        }
      }
    }
  }

  @Test
  fun testMoveRange_withRetainMaximumRangeRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE)
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)
    val newRangeStart = 40
    val newRangeEnd = 43

    recyclerBinder.onNewVisibleRange(newRangeStart, newRangeEnd)

    validateRangeWithRetainMaximumRangeStrategy(components, 40, 43, 40, 43)
  }

  @Test
  fun testIncreaseDecreaseMoveRangeAhead_withRetainMaximumRangeRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE)
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    recyclerBinder.onNewVisibleRange(30, 33)
    validateRangeWithRetainMaximumRangeStrategy(components, 30, 33, 30, 33)

    recyclerBinder.onNewVisibleRange(31, 32)
    validateRangeWithRetainMaximumRangeStrategy(components, 31, 32, 30, 33)

    recyclerBinder.onNewVisibleRange(32, 35)
    validateRangeWithRetainMaximumRangeStrategy(components, 32, 35, 30, 33)
  }

  @Test
  fun testIncreaseDecreaseMoveRangeBehind_withRetainMaximumRangeRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE)
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Call 1: visible [30,33] → rangeSize=3, range=[24,39]
    recyclerBinder.onNewVisibleRange(30, 33)
    validateRangeWithRetainMaximumRangeStrategy(components, 30, 33, 30, 33)

    // Call 2: visible [31,32] → range shrinks to [29,34], within retained [24,39],
    // didRangeExtremitiesChange=false → no releases, items [24,39] still valid
    recyclerBinder.onNewVisibleRange(31, 32)
    validateRangeWithRetainMaximumRangeStrategy(components, 31, 32, 30, 33)

    // Call 3: visible [29,32] → range=[23,38], rangeStart=23 < lowestRangeStart=24 →
    // didRangeExtremitiesChange=true.
    recyclerBinder.onNewVisibleRange(29, 32)
    validateRangeWithRetainMaximumRangeStrategy(components, 29, 32, 30, 33)
  }

  // --- RETAIN_MAXIMUM_RANGE_OPTIMIZED tests ---

  @Test
  fun testMoveRange_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    recyclerBinder.onNewVisibleRange(40, 43)

    validateRangeWithRetainMaximumRangeStrategy(components, 40, 43, 40, 43)
  }

  @Test
  fun testRetainRange_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    recyclerBinder.onNewVisibleRange(30, 33)
    validateRangeWithRetainMaximumRangeStrategy(components, 30, 33, 30, 33)

    // Shrink range — retained maximum should keep old bounds
    recyclerBinder.onNewVisibleRange(31, 32)
    validateRangeWithRetainMaximumRangeStrategy(components, 31, 32, 30, 33)

    // Move forward — retained bounds should still include old lower bound
    recyclerBinder.onNewVisibleRange(32, 35)
    validateRangeWithRetainMaximumRangeStrategy(components, 32, 35, 30, 33)
  }

  @Test
  fun testNoOpSkip_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Initial range computation
    recyclerBinder.onNewVisibleRange(40, 43)

    // Reset layout tracking on all holders to detect if anything gets re-laid-out
    for (component in components) {
      val holder = checkNotNull(holdersForComponents[component.component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    // Call with the same visible range — should be a no-op skip
    recyclerBinder.onNewVisibleRange(40, 43)

    // Verify no new layouts were triggered
    for (i in components.indices) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.mLayoutAsyncCalled)
          .describedAs("No-op skip: layoutAsyncCalled expected False for index $i")
          .isFalse
      assertThat(holder.mLayoutSyncCalled)
          .describedAs("No-op skip: layoutSyncCalled expected False for index $i")
          .isFalse
    }
  }

  @Test
  fun testCleanRangeSkip_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Process a wide range to set retained bounds
    recyclerBinder.onNewVisibleRange(30, 35)

    // Reset layout tracking
    for (component in components) {
      val holder = checkNotNull(holdersForComponents[component.component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    // Move visible range within retained bounds (smaller range, no new items to process)
    recyclerBinder.onNewVisibleRange(31, 34)

    // Verify no new layouts were triggered (clean range skip: range within retained bounds,
    // no mutations, same adapter size)
    for (i in components.indices) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.mLayoutAsyncCalled)
          .describedAs("Clean skip: layoutAsyncCalled expected False for index $i")
          .isFalse
      assertThat(holder.mLayoutSyncCalled)
          .describedAs("Clean skip: layoutSyncCalled expected False for index $i")
          .isFalse
    }
  }

  @Test
  fun testDirtyFlagTriggersRetraversal_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Initial range computation
    recyclerBinder.onNewVisibleRange(30, 35)

    // Get the holder for the item we're about to update
    val holderBeforeUpdate = checkNotNull(holdersForComponents[components[32].component])
    assertThat(holderBeforeUpdate.isTreeValid).isTrue

    // Update item 32 — this calls updateHolder which sets retainedRangeDirty = true.
    // updateHolder also calls setRenderInfo on the existing holder, invalidating its tree.
    val updatedRenderInfo =
        ComponentRenderInfo.create().component(mock(Component::class.java)).build()
    recyclerBinder.updateItemAt(32, updatedRenderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // The holder's tree should now be invalid
    assertThat(holderBeforeUpdate.isTreeValid)
        .describedAs("Updated item should have invalid tree before re-computation")
        .isFalse

    // Move visible range within retained bounds — but dirty flag should force re-traversal
    recyclerBinder.onNewVisibleRange(31, 34)

    // The holder should now have been re-laid-out
    assertThat(holderBeforeUpdate.isTreeValid)
        .describedAs("Updated item should be re-laid-out after dirty flag triggers traversal")
        .isTrue
    assertThat(holderBeforeUpdate.mLayoutAsyncCalled)
        .describedAs("Updated item should have had async layout called")
        .isTrue
  }

  @Test
  fun testRangeExpansionTriggersLayout_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Process initial range at positions 30-33
    recyclerBinder.onNewVisibleRange(30, 33)

    // Items within the range+ratio should be laid out. rangeSize=3, rangeStart=30-6=24,
    // rangeEnd=30+3+6=39
    val holder30 = checkNotNull(holdersForComponents[components[30].component])
    assertThat(holder30.isTreeValid)
        .describedAs("Item 30 should be valid after initial range")
        .isTrue

    // Item 50 should NOT be valid yet
    val holder50 = checkNotNull(holdersForComponents[components[50].component])
    assertThat(holder50.isTreeValid)
        .describedAs("Item 50 should not be valid before expansion")
        .isFalse

    // Move range forward beyond retained bounds — should trigger layout for new items
    recyclerBinder.onNewVisibleRange(50, 53)

    // Item 50 should now be valid (in new range)
    assertThat(holder50.isTreeValid).describedAs("Item 50 should be valid after expansion").isTrue
    assertThat(holder50.mLayoutAsyncCalled)
        .describedAs("Item 50 should have async layout called")
        .isTrue
  }

  @Test
  fun testMoveItemSetsDirtyFlag_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Initial range computation at [30,35]
    recyclerBinder.onNewVisibleRange(30, 35)

    // Get holder for item at position 32 (within range)
    val holder32 = checkNotNull(holdersForComponents[components[32].component])
    assertThat(holder32.isTreeValid).isTrue

    // Reset layout tracking
    for (component in components) {
      val holder = checkNotNull(holdersForComponents[component.component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    // Move item from position 32 to position 33. This does NOT change list size,
    // so without the dirty flag fix, computeRange would incorrectly skip traversal.
    recyclerBinder.moveItem(32, 33)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // Move visible range within retained bounds — dirty flag should force re-traversal
    recyclerBinder.onNewVisibleRange(31, 34)

    // The moved holder should have been re-processed during traversal.
    // Since moveItem sets retainedRangeDirty=true, traversal should NOT be skipped.
    val holder33 = checkNotNull(holdersForComponents[components[32].component])
    assertThat(holder33.mLayoutAsyncCalled || holder33.isTreeValid)
        .describedAs("Moved item should be re-laid-out after dirty flag triggers traversal")
        .isTrue
  }

  @Test
  fun testInsertItemSetsDirtyFlag_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Initial range computation
    recyclerBinder.onNewVisibleRange(30, 35)

    // Reset layout tracking
    for (component in components) {
      val holder = checkNotNull(holdersForComponents[component.component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    // Insert a new item at position 32 (within range) and also remove one so size stays the same
    val newRenderInfo = ComponentRenderInfo.create().component(mock(Component::class.java)).build()
    recyclerBinder.insertItemAt(32, newRenderInfo)
    recyclerBinder.removeItemAt(100) // remove last item to keep size unchanged
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // Move visible range within retained bounds — dirty flag should force re-traversal
    recyclerBinder.onNewVisibleRange(31, 34)

    // Verify the newly inserted holder was laid out
    val newHolder = checkNotNull(holdersForComponents[newRenderInfo.component])
    assertThat(newHolder.isTreeValid)
        .describedAs("Inserted item should be laid out after dirty flag triggers traversal")
        .isTrue
  }

  @Test
  fun testRemoveItemSetsDirtyFlag_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    val components = prepareLoadedBinder(recyclerBinder, 100)

    // Initial range computation
    recyclerBinder.onNewVisibleRange(30, 35)

    // Reset layout tracking
    for (component in components) {
      val holder = checkNotNull(holdersForComponents[component.component])
      holder.mLayoutAsyncCalled = false
      holder.mLayoutSyncCalled = false
    }

    // Remove item at position 32 and insert one at the end so size stays the same
    recyclerBinder.removeItemAt(32)
    val newRenderInfo = ComponentRenderInfo.create().component(mock(Component::class.java)).build()
    recyclerBinder.insertItemAt(99, newRenderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // Move visible range within retained bounds — dirty flag should force re-traversal
    recyclerBinder.onNewVisibleRange(31, 34)

    // Item that was at position 33 before removal is now at position 32. The holder for
    // components[33] should be re-processed during the traversal.
    val shiftedHolder = checkNotNull(holdersForComponents[components[33].component])
    assertThat(shiftedHolder.isTreeValid)
        .describedAs("Item shifted by removal should be valid after dirty flag triggers traversal")
        .isTrue
  }

  @Test
  fun testReplaceAllSetsDirtyFlag_withRetainMaximumRangeOptimizedRecyclingStrategy() {
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .estimatedViewportCount(1)
                    .recyclingStrategy(
                        RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE_OPTIMIZED,
                    )
                    .build(),
            )
            .build(componentContext)
    prepareLoadedBinder(recyclerBinder, 100)

    // Initial range computation
    recyclerBinder.onNewVisibleRange(30, 35)

    // Replace all items with new ones (same count, so size doesn't change)
    val newComponents = ArrayList<RenderInfo>()
    for (i in 0 until 100) {
      newComponents.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }
    recyclerBinder.replaceAll(newComponents)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // Move visible range within retained bounds — dirty flag should force re-traversal
    recyclerBinder.onNewVisibleRange(31, 34)

    // Verify the new holder at position 32 (within range) was laid out
    val newComponent = (newComponents[32] as ComponentRenderInfo).component
    val newHolder = checkNotNull(holdersForComponents[newComponent])
    assertThat(newHolder.isTreeValid)
        .describedAs("Replaced item should be laid out after dirty flag triggers traversal")
        .isTrue
  }

  @Test
  fun testRealRangeOverridesEstimatedRange() {
    val components = prepareLoadedBinder()
    val newRangeStart = 40
    val newRangeEnd = 50
    val rangeSize = newRangeEnd - newRangeStart
    val rangeTotal = (rangeSize + (RANGE_RATIO * rangeSize)).toInt()

    recyclerBinder.onNewVisibleRange(newRangeStart, newRangeEnd)

    var componentTreeHolder: TestComponentTreeHolder?
    for (i in components.indices) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      if (i >= newRangeStart - (RANGE_RATIO * rangeSize) && i <= newRangeStart + rangeTotal) {
        assertThat(componentTreeHolder.isTreeValid).isTrue
        assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue
        assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
      } else {
        assertThat(componentTreeHolder.isTreeValid).isFalse
        assertThat(componentTreeHolder.mLayoutAsyncCalled).isFalse
        assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
      }
    }
  }

  @Test
  fun testStickyComponentsOutsideRange_updateShownStickysLayoutAfterViewportChanges() {
    val components = prepareLoadedBinder()
    makeIndexSticky(components, 5)
    makeIndexSticky(components, 40)
    makeIndexSticky(components, 80)

    assertThat(checkNotNull(holdersForComponents[components[5].component]).isTreeValid).isTrue

    val firstVisibleIndex = 40
    val lastVisibleIndex = 50
    recyclerBinder.onNewVisibleRange(firstVisibleIndex, lastVisibleIndex)

    val viewportSize = lastVisibleIndex - firstVisibleIndex
    val rangeStart = (firstVisibleIndex - RANGE_RATIO * viewportSize).toInt()
    val rangeEnd = (lastVisibleIndex + RANGE_RATIO * viewportSize).toInt()

    var componentTreeHolder: TestComponentTreeHolder?
    for (i in components.indices) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])
      val isIndexInRange = rangeStart <= i && i <= rangeEnd
      val isPreviouslyComputedTreeAndSticky =
          i <= rangeEnd && componentTreeHolder.renderInfo.isSticky

      if (isIndexInRange || isPreviouslyComputedTreeAndSticky) {
        assertThat(componentTreeHolder.isTreeValid).describedAs("Holder with index:$i").isTrue
        assertThat(componentTreeHolder.mLayoutAsyncCalled)
            .describedAs("Holder with index:$i")
            .isTrue
        assertThat(componentTreeHolder.mLayoutSyncCalled)
            .describedAs("Holder with index:$i")
            .isFalse
      } else {
        assertThat(componentTreeHolder.isTreeValid).describedAs("Holder with index:$i").isFalse
        assertThat(componentTreeHolder.mLayoutAsyncCalled)
            .describedAs("Holder with index:$i")
            .isFalse
        assertThat(componentTreeHolder.mLayoutSyncCalled)
            .describedAs("Holder with index:$i")
            .isFalse
      }
    }
  }

  @Test
  fun testStickyComponentsOutsideRange_updateShownStickysLayoutAfterComponentUpdates() {
    val components = prepareLoadedBinder()
    makeIndexSticky(components, 5)
    makeIndexSticky(components, 40)
    makeIndexSticky(components, 80)

    val firstVisibleIndex = 40
    val lastVisibleIndex = 50
    recyclerBinder.onNewVisibleRange(firstVisibleIndex, lastVisibleIndex)

    val viewportSize = lastVisibleIndex - firstVisibleIndex
    val rangeStart = (firstVisibleIndex - RANGE_RATIO * viewportSize).toInt()
    val rangeEnd = (lastVisibleIndex + RANGE_RATIO * viewportSize).toInt()

    recyclerBinder.updateItemAt(5, components[5])
    recyclerBinder.updateItemAt(40, components[40])
    recyclerBinder.updateItemAt(80, components[80])
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    var componentTreeHolder: TestComponentTreeHolder?
    for (i in components.indices) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])
      val isIndexInRange = rangeStart <= i && i <= rangeEnd
      val isPreviouslyComputedTreeAndSticky =
          i <= rangeEnd && componentTreeHolder.renderInfo.isSticky

      if (isIndexInRange || isPreviouslyComputedTreeAndSticky) {
        assertThat(componentTreeHolder.isTreeValid).describedAs("Holder with index:$i").isTrue
        assertThat(componentTreeHolder.mLayoutAsyncCalled)
            .describedAs("Holder with index:$i")
            .isTrue
        assertThat(componentTreeHolder.mLayoutSyncCalled)
            .describedAs("Holder with index:$i")
            .isFalse
      } else {
        assertThat(componentTreeHolder.isTreeValid).describedAs("Holder with index:$i").isFalse
        assertThat(componentTreeHolder.mLayoutAsyncCalled)
            .describedAs("Holder with index:$i")
            .isFalse
        assertThat(componentTreeHolder.mLayoutSyncCalled)
            .describedAs("Holder with index:$i")
            .isFalse
      }
    }
  }

  @Test
  fun testMoveRangeToEnd() {
    val components = prepareLoadedBinder()
    val newRangeStart = 99
    val newRangeEnd = 99
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    recyclerBinder.onNewVisibleRange(newRangeStart, newRangeEnd)

    var componentTreeHolder: TestComponentTreeHolder?

    for (i in components.indices) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      if (i >= newRangeStart - (RANGE_RATIO * RANGE_SIZE) && i <= newRangeStart + rangeTotal) {
        assertThat(componentTreeHolder.isTreeValid).isTrue
        assertThat(componentTreeHolder.mLayoutAsyncCalled).isTrue
        assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
      } else {
        assertThat(componentTreeHolder.isTreeValid).isFalse
        assertThat(componentTreeHolder.mLayoutAsyncCalled).isFalse
        assertThat(componentTreeHolder.mLayoutSyncCalled).isFalse
        if (i <= rangeTotal) {
          assertThat(componentTreeHolder.mDidAcquireStateHandler).isTrue
        } else {
          assertThat(componentTreeHolder.mDidAcquireStateHandler).isFalse
        }
      }
    }
  }

  @Test
  fun testMoveItemOutsideFromRange() {
    val components = prepareLoadedBinder()
    recyclerBinder.moveItem(0, 99)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val movedHolder = checkNotNull(holdersForComponents[components[0].component])
    assertThat(movedHolder.isTreeValid).isFalse
    assertThat(movedHolder.mLayoutAsyncCalled).isFalse
    assertThat(movedHolder.mLayoutSyncCalled).isFalse
    assertThat(movedHolder.mDidAcquireStateHandler).isTrue

    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()
    val holderMovedIntoRange =
        checkNotNull(holdersForComponents[components[rangeTotal + 1].component])

    assertThat(holderMovedIntoRange.isTreeValid).isTrue
    assertThat(holderMovedIntoRange.mLayoutAsyncCalled).isTrue
    assertThat(holderMovedIntoRange.mLayoutSyncCalled).isFalse
  }

  @Test
  fun testMoveItemInsideRange() {
    val components = prepareLoadedBinder()
    recyclerBinder.moveItem(99, 4)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val movedHolder = checkNotNull(holdersForComponents[components[99].component])
    assertThat(movedHolder.isTreeValid).isTrue
    assertThat(movedHolder.mLayoutAsyncCalled).isTrue
    assertThat(movedHolder.mLayoutSyncCalled).isFalse
    assertThat(movedHolder.mDidAcquireStateHandler).isFalse
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    val holderMovedOutsideRange =
        checkNotNull(holdersForComponents[components[rangeTotal].component])

    assertThat(holderMovedOutsideRange.isTreeValid).isFalse
    assertThat(holderMovedOutsideRange.mLayoutAsyncCalled).isFalse
    assertThat(holderMovedOutsideRange.mLayoutSyncCalled).isFalse
  }

  @Test
  fun testMoveItemInsideVisibleRange() {
    val components = prepareLoadedBinder()
    recyclerBinder.moveItem(99, 2)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val movedHolder = checkNotNull(holdersForComponents[components[99].component])

    assertThat(movedHolder.isTreeValid).isTrue
    assertThat(movedHolder.mLayoutAsyncCalled).isTrue
    assertThat(movedHolder.mLayoutSyncCalled).isFalse
    assertThat(movedHolder.mDidAcquireStateHandler).isFalse
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    val holderMovedOutsideRange =
        checkNotNull(holdersForComponents[components[rangeTotal].component])

    assertThat(holderMovedOutsideRange.isTreeValid).isFalse
    assertThat(holderMovedOutsideRange.mLayoutAsyncCalled).isFalse
    assertThat(holderMovedOutsideRange.mLayoutSyncCalled).isFalse
    assertThat(holderMovedOutsideRange.mDidAcquireStateHandler).isTrue
  }

  @Test
  fun testMoveItemOutsideRange() {
    val components = prepareLoadedBinder()
    recyclerBinder.moveItem(2, 99)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val movedHolder = checkNotNull(holdersForComponents[components[2].component])
    assertThat(movedHolder.isTreeValid).isFalse
    assertThat(movedHolder.mLayoutAsyncCalled).isFalse
    assertThat(movedHolder.mLayoutSyncCalled).isFalse
    assertThat(movedHolder.mDidAcquireStateHandler).isTrue

    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()
    val holderMovedInsideRange =
        checkNotNull(holdersForComponents[components[rangeTotal + 1].component])

    assertThat(holderMovedInsideRange.isTreeValid).isTrue
    assertThat(holderMovedInsideRange.mLayoutAsyncCalled).isTrue
    assertThat(holderMovedInsideRange.mLayoutSyncCalled).isFalse
    assertThat(holderMovedInsideRange.mDidAcquireStateHandler).isFalse
  }

  @Test
  fun testMoveWithinRange() {
    val components = prepareLoadedBinder()

    val movedHolderOne = checkNotNull(holdersForComponents[components[0].component])
    val movedHolderTwo = checkNotNull(holdersForComponents[components[1].component])

    movedHolderOne.mLayoutSyncCalled = false
    movedHolderOne.mLayoutAsyncCalled = false
    movedHolderOne.mDidAcquireStateHandler = false

    movedHolderTwo.mLayoutSyncCalled = false
    movedHolderTwo.mLayoutAsyncCalled = false
    movedHolderTwo.mDidAcquireStateHandler = false

    recyclerBinder.moveItem(0, 1)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(movedHolderOne.isTreeValid).isTrue
    assertThat(movedHolderOne.mLayoutAsyncCalled).isFalse
    assertThat(movedHolderOne.mLayoutSyncCalled).isFalse
    assertThat(movedHolderOne.mDidAcquireStateHandler).isFalse

    assertThat(movedHolderTwo.isTreeValid).isTrue
    assertThat(movedHolderTwo.mLayoutAsyncCalled).isFalse
    assertThat(movedHolderTwo.mLayoutSyncCalled).isFalse
    assertThat(movedHolderTwo.mDidAcquireStateHandler).isFalse
  }

  @Test
  fun testPreventReleaseAttribute() {
    val components = ArrayList<RenderInfo>()
    for (i in 0 until 100) {
      val builder = ComponentRenderInfo.create().component(EmptyComponent())
      if (i == 0 || i == 7) {
        builder.customAttribute(ComponentTreeHolder.PREVENT_RELEASE_TAG, true)
      }

      components.add(builder.build())
    }
    val binder = RecyclerBinder.Builder().build(componentContext)
    binder.insertRangeAt(0, components)

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)

    binder.measure(size, widthSpec, heightSpec, null)

    val recyclerView = RecyclerView(getApplicationContext())
    binder.mount(recyclerView)

    assertThat(binder.getComponentTreeHolderAt(0)!!.isTreeValid).isTrue

    val firstVisible = 40
    val lastVisible = 50

    binder.onNewVisibleRange(firstVisible, lastVisible)

    assertThat(binder.getComponentTreeHolderAt(0)!!.isTreeValid).isTrue
    assertThat(binder.getComponentTreeHolderAt(7)!!.isTreeValid).isTrue
    assertThat(binder.getComponentTreeHolderAt(1)!!.isTreeValid).isFalse
  }

  @Test
  fun testInsertInVisibleRange() {
    val components = prepareLoadedBinder()
    val newRenderInfo = ComponentRenderInfo.create().component(mock(Component::class.java)).build()

    recyclerBinder.insertItemAt(1, newRenderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    val holder = checkNotNull(holdersForComponents[newRenderInfo.component])

    assertThat(holder.isTreeValid).isTrue
    assertThat(holder.mLayoutSyncCalled).isFalse
    assertThat(holder.mLayoutAsyncCalled).isTrue
    assertThat(holder.mDidAcquireStateHandler).isFalse

    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()
    val holderMovedOutsideRange =
        checkNotNull(holdersForComponents[components[rangeTotal].component])

    assertThat(holderMovedOutsideRange.isTreeValid).isFalse
    assertThat(holderMovedOutsideRange.mLayoutSyncCalled).isFalse
    assertThat(holderMovedOutsideRange.mLayoutAsyncCalled).isFalse
    assertThat(holderMovedOutsideRange.mDidAcquireStateHandler).isTrue
  }

  private fun changeViewportTo(firstVisiblePosition: Int, lastVisiblePosition: Int) {
    whenever(layoutInfo.findFirstVisibleItemPosition()).thenReturn(firstVisiblePosition)
    whenever(layoutInfo.findLastVisibleItemPosition()).thenReturn(lastVisiblePosition)

    recyclerBinder.viewportManager.onViewportChanged(ViewportInfo.State.DATA_CHANGES)
  }

  @Test
  fun testRemoveRangeAboveTheViewport() {
    val components = prepareLoadedBinder()
    val recyclerView = RecyclerView(getApplicationContext())
    recyclerBinder.mount(recyclerView)

    val firstVisible = 40
    val lastVisible = 50
    val rangeSize = lastVisible - firstVisible

    changeViewportTo(firstVisible, lastVisible)

    assertThat(recyclerBinder.viewportManager.shouldUpdate()).isFalse

    val removeRangeSize = rangeSize
    // Remove above the visible range
    recyclerBinder.removeRangeAt(0, removeRangeSize)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.viewportManager.shouldUpdate()).isTrue

    // compute range not yet updated, range will be updated in next frame
    assertThat(recyclerBinder.currentFirstVisiblePosition).isEqualTo(firstVisible)
    assertThat(recyclerBinder.currentLastVisiblePosition).isEqualTo(lastVisible)
  }

  @Test
  fun testRemoveRangeBelowTheViewport() {
    val components = prepareLoadedBinder()
    val recyclerView = RecyclerView(getApplicationContext())
    recyclerBinder.mount(recyclerView)

    val firstVisible = 40
    val lastVisible = 50
    val rangeSize = lastVisible - firstVisible

    changeViewportTo(firstVisible, lastVisible)

    assertThat(recyclerBinder.viewportManager.shouldUpdate()).isFalse

    val removeRangeSize = rangeSize
    // Remove below the visible range
    recyclerBinder.removeRangeAt(lastVisible + 1, removeRangeSize)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.viewportManager.shouldUpdate()).isFalse

    // compute range has been updated and range did not change
    assertThat(recyclerBinder.currentFirstVisiblePosition).isEqualTo(firstVisible)
    assertThat(recyclerBinder.currentLastVisiblePosition).isEqualTo(lastVisible)
  }

  @Test
  fun testInsertInRange() {
    val components = prepareLoadedBinder()
    val newRenderInfo = ComponentRenderInfo.create().component(mock(Component::class.java)).build()

    recyclerBinder.insertItemAt(RANGE_SIZE + 1, newRenderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    val holder = checkNotNull(holdersForComponents[newRenderInfo.component])

    assertThat(holder.isTreeValid).isTrue
    assertThat(holder.mLayoutSyncCalled).isFalse
    assertThat(holder.mLayoutAsyncCalled).isTrue
    assertThat(holder.mDidAcquireStateHandler).isFalse

    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()
    val holderMovedOutsideRange =
        checkNotNull(holdersForComponents[components[rangeTotal].component])

    assertThat(holderMovedOutsideRange.isTreeValid).isFalse
    assertThat(holderMovedOutsideRange.mLayoutSyncCalled).isFalse
    assertThat(holderMovedOutsideRange.mLayoutAsyncCalled).isFalse
    assertThat(holderMovedOutsideRange.mDidAcquireStateHandler).isTrue
  }

  @Test
  fun testInsertRange() {
    val components = prepareLoadedBinder()
    val newComponents = ArrayList<RenderInfo>()

    for (i in 0 until 3) {
      newComponents.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }

    recyclerBinder.insertRangeAt(0, newComponents)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // The new elements were scheduled for layout.
    for (i in 0 until 3) {
      val holder =
          checkNotNull(holdersForComponents[(newComponents[i] as ComponentRenderInfo).component])
      assertThat(holder.isTreeValid).isTrue
      assertThat(holder.mLayoutSyncCalled).isFalse
      assertThat(holder.mLayoutAsyncCalled).isTrue
      assertThat(holder.mDidAcquireStateHandler).isFalse
    }

    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    // The elements that went outside the layout range have been released.
    for (i in rangeTotal - 2..rangeTotal) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isFalse
      assertThat(holder.mLayoutSyncCalled).isFalse
      assertThat(holder.mLayoutAsyncCalled).isFalse
      assertThat(holder.mDidAcquireStateHandler).isTrue
    }
  }

  @Test
  fun testInsertOusideRange() {
    prepareLoadedBinder()
    val newRenderInfo = ComponentRenderInfo.create().component(mock(Component::class.java)).build()
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    recyclerBinder.insertItemAt(rangeTotal + 1, newRenderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val holder = checkNotNull(holdersForComponents[newRenderInfo.component])

    assertThat(holder.isTreeValid).isFalse
    assertThat(holder.mLayoutSyncCalled).isFalse
    assertThat(holder.mLayoutAsyncCalled).isFalse
    assertThat(holder.mDidAcquireStateHandler).isFalse
  }

  @Test
  fun testRemoveItem() {
    val components = prepareLoadedBinder()
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    recyclerBinder.removeItemAt(rangeTotal + 1)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  @Test
  fun testRemoveFromRange() {
    val components = prepareLoadedBinder()
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    recyclerBinder.removeItemAt(rangeTotal)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val holder = holdersForComponents[components[rangeTotal].component]

    val holderMovedInRange =
        checkNotNull(holdersForComponents[components[rangeTotal + 1].component])
    assertThat(holderMovedInRange.isTreeValid).isTrue
    assertThat(holderMovedInRange.mLayoutSyncCalled).isFalse
    assertThat(holderMovedInRange.mLayoutAsyncCalled).isTrue
    assertThat(holderMovedInRange.mDidAcquireStateHandler).isFalse
  }

  @Test
  fun testRemoveRange() {
    val components = prepareLoadedBinder()
    val rangeTotal = (RANGE_SIZE + (RANGE_RATIO * RANGE_SIZE)).toInt()

    recyclerBinder.removeRangeAt(0, RANGE_SIZE)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // The elements that are now in the range get their layout computed
    for (i in rangeTotal + 1..rangeTotal + RANGE_SIZE) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isTrue
      assertThat(holder.mLayoutSyncCalled).isFalse
      assertThat(holder.mLayoutAsyncCalled).isTrue
      assertThat(holder.mDidAcquireStateHandler).isFalse
    }
  }

  @Test
  fun testUpdate() {
    val components = prepareLoadedBinder()

    val holder = checkNotNull(holdersForComponents[components[0].component])
    assertThat(holder.isTreeValid).isTrue
    holder.mTreeValid = false
    assertThat(holder.isTreeValid).isFalse

    val newRenderInfo = ComponentRenderInfo.create().component(mock(Component::class.java)).build()
    recyclerBinder.updateItemAt(0, newRenderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(newRenderInfo).isEqualTo(holder.renderInfo)
    assertThat(holder.isTreeValid).isTrue
  }

  @Test
  fun testUpdateRange() {
    val components = prepareLoadedBinder()

    for (i in 0 until RANGE_SIZE) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isTrue
      holder.mTreeValid = false
      assertThat(holder.isTreeValid).isFalse
    }

    val newInfos = ArrayList<RenderInfo>()
    for (i in 0 until RANGE_SIZE) {
      newInfos.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }
    recyclerBinder.updateRangeAt(0, newInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until RANGE_SIZE) {
      val holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(newInfos[i]).isEqualTo(holder.renderInfo)
      assertThat(holder.isTreeValid).isTrue
    }
  }

  @Test
  fun testInsertMixedContentWithSingleViewCreator() {
    val viewItems = listOf(3, 6, 7, 11)
    prepareMixedLoadedBinder(30, HashSet(viewItems), ViewCreatorProvider { VIEW_CREATOR_1 })

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(1)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(1)

    val obtainedViewCreator =
        recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.keys.iterator().next()
    assertThat(obtainedViewCreator).isEqualTo(VIEW_CREATOR_1)
    assertThat(
        recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.indexOfValue(
            obtainedViewCreator,
        ),
    )
        .isGreaterThanOrEqualTo(0)
  }

  @Test
  fun testInsertMixedContentWithMultiViewCreator() {
    val viewItems = listOf(3, 6, 7, 11)
    prepareMixedLoadedBinder(
        30,
        HashSet(viewItems),
        ViewCreatorProvider {
          // Different ViewCreator instances for each view item.
          object : ViewCreator<View> {
            override fun createView(c: Context, parent: ViewGroup?): View {
              return View(c)
            }
          }
        },
    )

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(4)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(4)

    for (obtainedViewCreator in
        recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.keys) {
      assertThat(
          recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.indexOfValue(
              obtainedViewCreator,
          ),
      )
          .isGreaterThanOrEqualTo(0)
    }
  }

  @Test
  fun testInsertMixedContentFollowedByDelete() {
    recyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )

    recyclerBinder.insertItemAt(
        1,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(1)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(1)

    recyclerBinder.insertItemAt(
        2,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_2)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(2)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(2)

    recyclerBinder.removeItemAt(1)
    recyclerBinder.removeItemAt(1)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(2)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(2)
  }

  @Test
  fun testInsertMixedContentFollowedByUpdate() {
    recyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.insertItemAt(
        1,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .build(),
    )
    recyclerBinder.insertItemAt(
        2,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_2)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(2)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(2)

    recyclerBinder.updateItemAt(
        1,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_2)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(2)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(2)

    recyclerBinder.updateItemAt(
        2,
        ViewRenderInfo.create()
            .viewCreator(VIEW_CREATOR_3)
            .viewBinder(SimpleViewBinder<View>())
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(3)
    assertThat(recyclerBinder.renderInfoViewCreatorController.viewTypeToViewCreator.size())
        .isEqualTo(3)
  }

  @Test
  fun testCustomViewTypeEnabledViewTypeProvided() {
    val recyclerBinder = recyclerBinderBuilder.enableCustomViewType(5).build(componentContext)

    recyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.insertItemAt(
        1,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .customViewType(10)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.renderInfoViewCreatorController.viewCreatorToViewType.size)
        .isEqualTo(1)
  }

  @Test(expected = IllegalStateException::class)
  fun testCustomViewTypeEnabledViewTypeNotProvided() {
    val recyclerBinder = recyclerBinderBuilder.enableCustomViewType(4).build(componentContext)

    recyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.insertItemAt(
        1,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  @Test(expected = IllegalStateException::class)
  fun testCustomViewTypeNotEnabledViewTypeProvided() {
    val recyclerBinder = recyclerBinderBuilder.build(componentContext)

    recyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.insertItemAt(
        1,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .customViewType(10)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  @Test(expected = IllegalStateException::class)
  fun testCustomViewTypeEnabledComponentViewTypeSameAsCustomViewType() {
    val recyclerBinder = recyclerBinderBuilder.enableCustomViewType(2).build(componentContext)

    recyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.insertItemAt(
        1,
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .customViewType(2)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  @Test
  fun testShouldAlwaysUpdateLayoutHandler() {
    val layoutHandlerBase = mock(RunnableHandler::class.java)
    val layoutHandler1 = mock(RunnableHandler::class.java)
    val layoutHandler2 = mock(RunnableHandler::class.java)
    val layoutHandlerN = mock(RunnableHandler::class.java)
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .layoutHandlerFactory(
                        object : LayoutHandlerFactory {
                          override fun createLayoutCalculationHandler(
                              renderInfo: RenderInfo,
                          ): RunnableHandler? {
                            val handlerType = renderInfo.getCustomAttribute("handlerType")
                            return when (handlerType as? Int) {
                              null -> layoutHandlerBase
                              1 -> layoutHandler1
                              2 -> layoutHandler2
                              else -> layoutHandlerN
                            }
                          }

                          override fun shouldUpdateLayoutHandler(
                              previousRenderInfo: RenderInfo,
                              newRenderInfo: RenderInfo,
                          ): Boolean = true
                        },
                    )
                    .build(),
            )
            .build(componentContext)
    val component0 = mock(Component::class.java)
    val component1 = mock(Component::class.java)
    val component2 = mock(Component::class.java)
    recyclerBinder.insertItemAt(0, ComponentRenderInfo.create().component(component0).build())
    recyclerBinder.insertItemAt(
        1,
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute("handlerType", 1)
            .build(),
    )
    recyclerBinder.insertItemAt(
        2,
        ComponentRenderInfo.create()
            .component(component2)
            .customAttribute("handlerType", 2)
            .build(),
    )

    assertThat(checkNotNull(holdersForComponents[component0]).mLayoutHandler)
        .isSameAs(layoutHandlerBase)
    assertThat(checkNotNull(holdersForComponents[component1]).mLayoutHandler)
        .isSameAs(layoutHandler1)
    assertThat(checkNotNull(holdersForComponents[component2]).mLayoutHandler)
        .isSameAs(layoutHandler2)

    recyclerBinder.updateItemAt(1, ComponentRenderInfo.create().component(component1).build())

    assertThat(checkNotNull(holdersForComponents[component1]).mLayoutHandler)
        .isSameAs(layoutHandlerBase)

    recyclerBinder.updateItemAt(
        2,
        ComponentRenderInfo.create()
            .component(component2)
            .customAttribute("handlerType", 10)
            .build(),
    )

    assertThat(checkNotNull(holdersForComponents[component2]).mLayoutHandler)
        .isSameAs(layoutHandlerN)
  }

  @Test
  fun testShouldNeverUpdateLayoutHandler() {
    val layoutHandler1 = mock(RunnableHandler::class.java)
    val layoutHandler2 = mock(RunnableHandler::class.java)
    val layoutHandlerN = mock(RunnableHandler::class.java)
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .layoutHandlerFactory(
                        object : LayoutHandlerFactory {
                          override fun createLayoutCalculationHandler(
                              renderInfo: RenderInfo,
                          ): RunnableHandler? {
                            val handlerType = renderInfo.getCustomAttribute("handlerType")
                            return when (handlerType as? Int) {
                              null -> null
                              1 -> layoutHandler1
                              2 -> layoutHandler2
                              else -> layoutHandlerN
                            }
                          }

                          override fun shouldUpdateLayoutHandler(
                              previousRenderInfo: RenderInfo,
                              newRenderInfo: RenderInfo,
                          ): Boolean = false
                        },
                    )
                    .build(),
            )
            .build(componentContext)
    val component0 = mock(Component::class.java)
    val component1 = mock(Component::class.java)
    val component2 = mock(Component::class.java)
    recyclerBinder.insertItemAt(0, ComponentRenderInfo.create().component(component0).build())
    recyclerBinder.insertItemAt(
        1,
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute("handlerType", 1)
            .build(),
    )
    recyclerBinder.insertItemAt(
        2,
        ComponentRenderInfo.create()
            .component(component2)
            .customAttribute("handlerType", 2)
            .build(),
    )

    recyclerBinder.updateItemAt(1, ComponentRenderInfo.create().component(component1).build())

    assertThat(checkNotNull(holdersForComponents[component1]).mLayoutHandler)
        .isSameAs(layoutHandler1)

    recyclerBinder.updateItemAt(
        2,
        ComponentRenderInfo.create()
            .component(component2)
            .customAttribute("handlerType", 10)
            .build(),
    )

    assertThat(checkNotNull(holdersForComponents[component2]).mLayoutHandler)
        .isSameAs(layoutHandler2)
  }

  @Test
  fun testShouldUpdateOnlyFromFirstToSecondLayoutHandler() {
    val layoutHandler1 = mock(RunnableHandler::class.java)
    val layoutHandler2 = mock(RunnableHandler::class.java)
    val layoutHandlerN = mock(RunnableHandler::class.java)
    val recyclerBinder =
        recyclerBinderBuilder
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .layoutHandlerFactory(
                        object : LayoutHandlerFactory {
                          override fun createLayoutCalculationHandler(
                              renderInfo: RenderInfo,
                          ): RunnableHandler? {
                            val handlerType = renderInfo.getCustomAttribute("handlerType")
                            return when (handlerType as? Int) {
                              null -> null
                              1 -> layoutHandler1
                              2 -> layoutHandler2
                              else -> layoutHandlerN
                            }
                          }

                          override fun shouldUpdateLayoutHandler(
                              previousRenderInfo: RenderInfo,
                              newRenderInfo: RenderInfo,
                          ): Boolean {
                            val previousHandlerType =
                                previousRenderInfo.getCustomAttribute("handlerType")
                            val newHandlerType = newRenderInfo.getCustomAttribute("handlerType")
                            return previousHandlerType != null &&
                                newHandlerType != null &&
                                previousHandlerType == 1 &&
                                newHandlerType == 2
                          }
                        },
                    )
                    .build(),
            )
            .build(componentContext)
    val component0 = mock(Component::class.java)
    val component1 = mock(Component::class.java)
    recyclerBinder.insertItemAt(0, ComponentRenderInfo.create().component(component0).build())
    recyclerBinder.insertItemAt(
        1,
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute("handlerType", 1)
            .build(),
    )

    assertThat(checkNotNull(holdersForComponents[component1]).mLayoutHandler)
        .isSameAs(layoutHandler1)
    recyclerBinder.updateItemAt(
        1,
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute("handlerType", 2)
            .build(),
    )
    assertThat(checkNotNull(holdersForComponents[component1]).mLayoutHandler)
        .isSameAs(layoutHandler2)

    recyclerBinder.updateItemAt(
        1,
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute("handlerType", 1)
            .build(),
    )
    assertThat(checkNotNull(holdersForComponents[component1]).mLayoutHandler)
        .isSameAs(layoutHandler2)
  }

  @Test
  fun testViewBinderBindAndUnbind() {
    val view = View(componentContext.androidContext)
    val recyclerView = RecyclerView(componentContext.androidContext)
    val viewBinder: ViewBinder<View> = mock(ViewBinder::class.java) as ViewBinder<View>
    val viewCreator =
        object : ViewCreator<View> {
          override fun createView(c: Context, parent: ViewGroup?): View {
            return view
          }
        }

    recyclerBinder.insertItemAt(
        0,
        ViewRenderInfo.create().viewBinder(viewBinder).viewCreator(viewCreator).build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    recyclerBinder.mount(recyclerView)

    val vh =
        recyclerView.adapter!!.onCreateViewHolder(
            FrameLayout(componentContext.androidContext),
            RenderInfoViewCreatorController.DEFAULT_COMPONENT_VIEW_TYPE + 1,
        )

    recyclerView.adapter!!.onBindViewHolder(vh, 0)
    verify(viewBinder).bind(view)
    verify(viewBinder, never()).unbind(view)

    recyclerView.adapter!!.onViewRecycled(vh)
    verify(viewBinder, times(1)).bind(view)
    verify(viewBinder).unbind(view)
  }

  @Test
  fun testGetItemCount() {
    for (i in 0 until 100) {
      assertThat(recyclerBinder.getItemCount()).isEqualTo(i)
      recyclerBinder.insertItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  @Test
  fun testAllComponentsRangeInitialized() {
    prepareLoadedBinder()
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull
  }

  @Test
  fun testMixedContentFirstItemIsViewRangeInitialized() {
    prepareMixedLoadedBinder(3, HashSet(listOf(0)), ViewCreatorProvider { VIEW_CREATOR_1 })
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull
  }

  @Test
  fun testAllViewsInsertItemRangeInitialized() {
    prepareMixedLoadedBinder(3, HashSet(listOf(0, 1, 2)), ViewCreatorProvider { VIEW_CREATOR_1 })
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    recyclerBinder.insertItemAt(
        1,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull
  }

  @Test
  fun testAllViewsUpdateItemRangeInitialized() {
    prepareMixedLoadedBinder(3, HashSet(listOf(0, 1, 2)), ViewCreatorProvider { VIEW_CREATOR_1 })
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    recyclerBinder.updateItemAt(
        1,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull
  }

  @Test
  fun testAllViewsInsertMultiItemRangeNotInitialized() {
    prepareMixedLoadedBinder(3, HashSet(listOf(0, 1, 2)), ViewCreatorProvider { VIEW_CREATOR_1 })
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    val renderInfos = ArrayList<RenderInfo>()
    renderInfos.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    renderInfos.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull
  }

  @Test
  fun testAllViewsUpdateMultiItemRangeNotInitialized() {
    prepareMixedLoadedBinder(3, HashSet(listOf(0, 1, 2)), ViewCreatorProvider { VIEW_CREATOR_1 })
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    val renderInfos = ArrayList<RenderInfo>()
    renderInfos.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    renderInfos.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    recyclerBinder.updateRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull
  }

  @Test
  fun testAllViewsInsertComponentMeasureAfterRangeInitialized() {
    for (i in 0 until 3) {
      recyclerBinder.insertItemAt(
          i,
          ViewRenderInfo.create()
              .viewBinder(SimpleViewBinder<View>())
              .viewCreator(VIEW_CREATOR_1)
              .build(),
      )
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    recyclerBinder.insertItemAt(
        1,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)

    recyclerBinder.measure(size, widthSpec, heightSpec, null)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull
  }

  private fun prepareMixedLoadedBinder(
      adapterSize: Int,
      viewItems: Set<Int>,
      viewCreatorProvider: ViewCreatorProvider,
  ): List<RenderInfo> {
    val renderInfos = ArrayList<RenderInfo>()

    for (i in 0 until adapterSize) {
      val renderInfo: RenderInfo =
          if (viewItems.contains(i)) {
            ViewRenderInfo.create()
                .viewBinder(SimpleViewBinder<View>())
                .viewCreator(viewCreatorProvider.get())
                .build()
          } else {
            ComponentRenderInfo.create().component(mock(Component::class.java)).build()
          }
      renderInfos.add(renderInfo)
      recyclerBinder.insertItemAt(i, renderInfos[i])
    }
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)

    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    return renderInfos
  }

  @Test
  fun testCircularRecyclerItemCount() {
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 10) {
      renderInfos.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }

    circularRecyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    Assert.assertEquals(Integer.MAX_VALUE, circularRecyclerBinder.getItemCount())
  }

  @Test
  fun testCircularRecyclerItemCountWithOneItem() {
    circularRecyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    Assert.assertEquals(Integer.MAX_VALUE, circularRecyclerBinder.getItemCount())
  }

  @Test
  fun testCircularRecyclerItemCOuntWithZeroItems() {
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    Assert.assertEquals(0, circularRecyclerBinder.getItemCount())
  }

  @Test
  fun testCircularRecyclerItemFirstVisible() {
    val recyclerView = mock(RecyclerView::class.java)
    whenever(circularLayoutInfo.getLayoutManager())
        .thenReturn(mock(RecyclerView.LayoutManager::class.java))

    circularRecyclerBinder.mount(recyclerView)

    verify(recyclerView).scrollToPosition(Integer.MAX_VALUE / 2)
  }

  @Test
  fun testCircularRecyclerItemFirstVisibleWithScrollToIndex() {
    val recyclerView = mock(RecyclerView::class.java)
    whenever(circularLayoutInfo.getLayoutManager())
        .thenReturn(mock(RecyclerView.LayoutManager::class.java))

    circularRecyclerBinder.scrollToPosition(1)
    circularRecyclerBinder.mount(recyclerView)

    verify(recyclerView).scrollToPosition(Integer.MAX_VALUE / 2 + 1)
  }

  @Test
  fun testCircularRecyclerItemFirstVisibleWithScrollToIndexAndBinder() {
    val initialPosition = 1
    val binderCount = 10
    val recyclerView = mock(RecyclerView::class.java)
    prepareLoadedBinder(circularRecyclerBinder, binderCount)
    whenever(circularLayoutInfo.getLayoutManager())
        .thenReturn(mock(RecyclerView.LayoutManager::class.java))

    circularRecyclerBinder.scrollToPosition(initialPosition)
    circularRecyclerBinder.mount(recyclerView)
    circularRecyclerBinder.unmount(recyclerView)
    circularRecyclerBinder.mount(recyclerView)
    val expectedIndex =
        Integer.MAX_VALUE / 2 - Integer.MAX_VALUE / 2 % binderCount + initialPosition
    verify(recyclerView, times(2)).scrollToPosition(expectedIndex)
  }

  @Test
  fun testCircularScrollRestoration() {
    val initialPosition = 1
    val binderCount = 10
    val expectedFirstMountIndex =
        Integer.MAX_VALUE / 2 - Integer.MAX_VALUE / 2 % binderCount + initialPosition
    val newRangePosition = expectedFirstMountIndex + 20
    val recyclerView = mock(RecyclerView::class.java)
    whenever(circularLayoutInfo.getLayoutManager())
        .thenReturn(mock(RecyclerView.LayoutManager::class.java))
    prepareLoadedBinder(circularRecyclerBinder, binderCount)

    circularRecyclerBinder.scrollToPosition(initialPosition)
    circularRecyclerBinder.mount(recyclerView)
    // Simulate user scrolling by putting the binder into a new visible range.
    circularRecyclerBinder.onNewVisibleRange(newRangePosition, newRangePosition)
    circularRecyclerBinder.unmount(recyclerView)
    circularRecyclerBinder.mount(recyclerView)

    val captor = ArgumentCaptor.forClass(Int::class.java)
    verify(recyclerView, times(2)).scrollToPosition(captor.capture() as Int)

    val captured = captor.allValues
    Assert.assertEquals(expectedFirstMountIndex, captured[0] as Int)
    Assert.assertEquals(newRangePosition, captured[1] as Int)
  }

  @Test
  fun testCircularRecyclerInitRange() {
    val components = prepareLoadedBinder(circularRecyclerBinder, 10)
    val holder = checkNotNull(holdersForComponents[components[0].component])
    assertThat(holder.isTreeValid).isTrue
    assertThat(holder.mLayoutSyncCalled).isTrue
  }

  @Test
  fun testCircularRecyclerMeasure() {
    val components = prepareLoadedBinder(circularRecyclerBinder, 10)

    val widthSpec = makeSizeSpec(100, EXACTLY)
    val heightSpec = makeSizeSpec(200, EXACTLY)

    circularRecyclerBinder.measure(Size(), widthSpec, heightSpec, null)

    var holder = checkNotNull(holdersForComponents[components[0].component])
    assertThat(holder.isTreeValid).isTrue
    assertThat(holder.mLayoutSyncCalled).isTrue

    for (i in 1 until 10) {
      holder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(holder.isTreeValid).isTrue
      assertThat(holder.mLayoutAsyncCalled).isTrue
    }
  }

  @Test
  fun testCircularRecyclerMeasureExact() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .build(componentContext)

    val components = ArrayList<RenderInfo>()
    for (i in 0 until 10) {
      val component =
          object : InlineLayoutSpec() {
            override fun onCreateLayout(c: ComponentContext): Component {
              return SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
            }
          }

      components.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, components)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val rv = RecyclerView(getApplicationContext())
    recyclerBinder.mount(rv)
    rv.measure(makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY))
    recyclerBinder.setSize(200, 200)
    rv.layout(0, 0, 200, 200)

    assertThat(rv.childCount).isGreaterThan(0)
    for (i in 0 until rv.childCount) {
      val lv = rv.getChildAt(i) as LithoView
      assertThat(lv.measuredWidth).isEqualTo(100)
      assertThat(lv.measuredHeight).isEqualTo(200)
    }
  }

  @Test
  fun testCircularRecyclerMeasureAtMost() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(
                object : LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false) {
                  override fun getChildWidthSpec(widthSpec: Int, renderInfo: RenderInfo): Int {
                    return SizeSpec.makeSizeSpec(SizeSpec.getSize(widthSpec), AT_MOST)
                  }
                },
            )
            .build(componentContext)

    val components = ArrayList<RenderInfo>()
    for (i in 0 until 10) {
      val component =
          object : InlineLayoutSpec() {
            override fun onCreateLayout(c: ComponentContext): Component {
              return SimpleMountSpecTester(StyleCompat.widthPercent(50f).heightPercent(25f).build())
            }
          }

      components.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, components)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val rv = RecyclerView(getApplicationContext())
    recyclerBinder.mount(rv)
    rv.measure(makeSizeSpec(100, EXACTLY), makeSizeSpec(100, EXACTLY))
    recyclerBinder.setSize(100, 100)
    rv.layout(0, 0, 100, 100)

    assertThat(rv.childCount).isGreaterThan(0)
    for (i in 0 until rv.childCount) {
      val lv = rv.getChildAt(i) as LithoView
      assertThat(lv.measuredWidth).isEqualTo(50)
      assertThat(lv.measuredHeight).isEqualTo(100)
    }
  }

  @Test
  fun testUpdateItemAtDoesNotNotifyItemChangedExceptWhenUpdatingViews() {
    val adapter = FakeRecyclerAdapter()
    val observer = mock(AdapterDataObserver::class.java)
    adapter.registerAdapterDataObserver(observer)
    val recyclerBinder = createRecyclerBinderWithMockAdapter(adapter)

    val components = ArrayList<RenderInfo>()
    for (i in 0 until 10) {
      val component =
          object : InlineLayoutSpec() {
            override fun onCreateLayout(c: ComponentContext): Component {
              return SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
            }
          }

      components.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, components)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val rv = mock(RecyclerView::class.java)
    recyclerBinder.mount(rv)

    recyclerBinder.updateItemAt(
        0,
        SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build()),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(observer, never()).onItemRangeChanged(anyInt(), anyInt())

    recyclerBinder.updateItemAt(
        0,
        ViewRenderInfo.create()
            .viewCreator(VIEW_CREATOR_1)
            .viewBinder(SimpleViewBinder<View>())
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    verify(observer, times(1)).onItemRangeChanged(0, 1, null)
  }

  @Test
  fun testUpdateRangeAtDoesNotNotifyItemChangedExceptWhenUpdatingViews() {
    val adapter = FakeRecyclerAdapter()
    val observer = mock(AdapterDataObserver::class.java)
    adapter.registerAdapterDataObserver(observer)
    val recyclerBinder = createRecyclerBinderWithMockAdapter(adapter)

    val NUM_ITEMS = 10
    val components = ArrayList<RenderInfo>()
    for (i in 0 until NUM_ITEMS) {
      val component =
          object : InlineLayoutSpec() {
            override fun onCreateLayout(c: ComponentContext): Component {
              return SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
            }
          }

      components.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, components)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val rv = mock(RecyclerView::class.java)
    recyclerBinder.mount(rv)

    val newComponents = ArrayList<RenderInfo>()
    for (i in 0 until NUM_ITEMS) {
      val component =
          object : InlineLayoutSpec() {
            override fun onCreateLayout(c: ComponentContext): Component {
              return SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
            }
          }
      newComponents.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.updateRangeAt(0, newComponents)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(observer, never()).onItemRangeChanged(anyInt(), anyInt())

    val newViews = ArrayList<RenderInfo>()
    for (i in 0 until NUM_ITEMS) {
      newViews.add(
          ViewRenderInfo.create()
              .viewCreator(VIEW_CREATOR_1)
              .viewBinder(SimpleViewBinder<View>())
              .build(),
      )
    }

    recyclerBinder.updateRangeAt(0, newViews)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until NUM_ITEMS) {
      verify(observer, times(1)).onItemRangeChanged(i, 1, null)
    }
  }

  @Test
  fun testOnNewWorkingRange() {
    val components = prepareLoadedBinder()
    val firstVisibleIndex = 40
    val lastVisibleIndex = 82
    val rangeSize = maxOf(RANGE_SIZE, lastVisibleIndex - firstVisibleIndex)
    val layoutRangeSize = (rangeSize * RANGE_RATIO).toInt()
    val rangeTotal = rangeSize + layoutRangeSize

    recyclerBinder.onNewWorkingRange(
        firstVisibleIndex,
        lastVisibleIndex,
        firstVisibleIndex + 1,
        lastVisibleIndex - 1,
    )

    var componentTreeHolder: TestComponentTreeHolder?
    for (i in components.indices) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])
      assertThat(componentTreeHolder).isNotNull

      if (i >= firstVisibleIndex - layoutRangeSize && i <= firstVisibleIndex + rangeTotal) {
        assertThat(componentTreeHolder.mCheckWorkingRangeCalled).isTrue
      } else {
        assertThat(componentTreeHolder.mCheckWorkingRangeCalled).isFalse
      }
    }
  }

  @Test
  fun testInsertAsyncWithComponentWarmer() {
    val recyclerBinder = spy(
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext),
    )
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())

    val renderInfoWarmer = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    val componentWarmer = ComponentWarmer(recyclerBinder)
    componentWarmer.prepare("tag", renderInfoWarmer, null)

    val renderInfo =
        ComponentRenderInfo.create()
            .component(component)
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag")
            .build()

    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    verify(recyclerBinder).applyReadyBatches()
  }

  @Test
  fun testInsertAsync_staleAccessibilityLayoutStaysPendingWhenFixDisabled() {
    for (initialAccessibility in listOf(false, true)) {
      val result = insertWarmedLayoutAfterAccessibilityChange(
          enableLivenessFix = false,
          initialAccessibility = initialAccessibility,
      )

      assertThat(result.itemCount).isZero()
      assertThat(result.pendingBatches).isNotNull()
    }
  }

  @Test
  fun testInsertAsync_staleAccessibilityLayoutRelayoutsWhenFixEnabled() {
    for (initialAccessibility in listOf(false, true)) {
      val result = insertWarmedLayoutAfterAccessibilityChange(
          enableLivenessFix = true,
          initialAccessibility = initialAccessibility,
      )

      assertThat(result.itemCount).isEqualTo(1)
      assertThat(result.pendingBatches).isNull()
    }
  }

  @Test
  fun testInsertAsync_accessibilityRelayoutIsNotRestartedWhileInFlight() {
    val result = insertWarmedLayoutAfterAccessibilityChange(
        enableLivenessFix = true,
        initialAccessibility = false,
        repeatMeasureBeforeLayoutCompletes = true,
    )

    assertThat(result.itemCount).isEqualTo(1)
    assertThat(result.pendingBatches).isNull()
  }

  @Test
  fun testInsertAsync_widthRemeasureStaysPendingWhenFixDisabled() {
    val result = insertFreshThenWarmedLayoutWithWidthChange(enableLivenessFix = false)

    assertThat(result.itemCount).isZero()
    assertThat(result.pendingBatches).isNotNull()
  }

  @Test
  fun testInsertAsync_widthRemeasureDrainsBatchWhenFixEnabled() {
    val result = insertFreshThenWarmedLayoutWithWidthChange(enableLivenessFix = true)

    assertThat(result.itemCount).isEqualTo(2)
    assertThat(result.pendingBatches).isNull()
  }

  @Test
  fun testInsertAsync_emptyLayoutCompletesLast_staysPendingWhenFixDisabled() {
    val result = insertNormalThenEmptyLayout(enableLivenessFix = false)

    assertThat(result.itemCount).isZero()
    assertThat(result.pendingBatches).isNotNull()
  }

  @Test
  fun testInsertAsync_emptyLayoutCompletesLast_drainsBatchWhenFixEnabled() {
    val result = insertNormalThenEmptyLayout(enableLivenessFix = true)

    assertThat(result.itemCount).isEqualTo(2)
    assertThat(result.pendingBatches).isNull()
  }

  @Test
  fun testInsertAsync_emptyLayoutPromotionDispatchesCapturedListener() {
    lateinit var pendingHolder: ComponentTreeHolder
    val holderFactory =
        RecyclerBinder.ComponentTreeHolderFactory {
            renderInfo,
            layoutHandler,
            measureListenerFactory,
            componentsConfiguration,
            lifecycleProvider ->
          ComponentTreeHolder.create(componentsConfiguration)
              .renderInfo(renderInfo)
              .layoutHandler(layoutHandler)
              .componentTreeMeasureListenerFactory(measureListenerFactory)
              .lithoVisibilityEventsController(lifecycleProvider)
              .build()
              .also { pendingHolder = it }
        }
    val recyclerBinder =
        RecyclerBinder.Builder()
            .componentTreeHolderFactory(holderFactory)
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .enableAsyncInsertLivenessFix(true)
                    .build(),
            )
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)

    try {
      ShadowLooper.pauseMainLooper()
      recyclerBinder.measure(
          Size(),
          makeSizeSpec(1000, EXACTLY),
          makeSizeSpec(1000, EXACTLY),
          null,
      )
      recyclerBinder.insertItemAtAsync(0, ComponentRenderInfo.createEmpty())
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

      val tree = checkNotNull(pendingHolder.componentTree)
      val delegate = checkNotNull(tree.newLayoutStateReadyListener)
      tree.newLayoutStateReadyListener =
          object : ComponentTree.NewLayoutStateReadyListener {
            override val dispatchOnPromotedLayout: Boolean
              get() {
                tree.newLayoutStateReadyListener = null
                return true
              }

            override fun onNewLayoutStateReady(componentTree: ComponentTree) {
              delegate.onNewLayoutStateReady(componentTree)
            }
          }

      runToEndOfTasks()
      ShadowLooper.runUiThreadTasks()

      assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
      assertThat(recyclerBinder.getPendingAsyncBatchesDebugInfo()).isNull()
    } finally {
      ShadowLooper.unPauseMainLooper()
    }
  }

  @Test
  fun testInsertAsync_listenerDoesNotReturnAfterAppliedHolderRebuild() {
    val recyclerBinder = createLayoutBeforeInsertBinder(enableLivenessFix = true)
    recyclerBinder.measure(
        Size(),
        makeSizeSpec(1000, EXACTLY),
        makeSizeSpec(1000, EXACTLY),
        null,
    )
    recyclerBinder.insertItemAtAsync(0, createTestComponentRenderInfo())
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(checkNotNull(holder.componentTree).newLayoutStateReadyListener).isNull()

    holder.releaseTree()
    holder.computeLayoutSync(
        componentContext,
        makeSizeSpec(1000, EXACTLY),
        makeSizeSpec(1000, EXACTLY),
        null,
    )

    assertThat(checkNotNull(holder.componentTree).newLayoutStateReadyListener).isNull()
  }

  private fun insertWarmedLayoutAfterAccessibilityChange(
      enableLivenessFix: Boolean,
      initialAccessibility: Boolean,
      repeatMeasureBeforeLayoutCompletes: Boolean = false,
  ): AsyncInsertLivenessResult {
    val previousAccessibility =
        AccessibilityUtils.isAccessibilityEnabled(getApplicationContext<Context>())
    try {
      setAccessibilityEnabled(initialAccessibility)
      val recyclerBinder = createLayoutBeforeInsertBinder(
          enableLivenessFix,
          wrapContent = repeatMeasureBeforeLayoutCompletes,
      )
      recyclerBinder.measure(
          Size(),
          makeSizeSpec(1000, EXACTLY),
          makeSizeSpec(1000, EXACTLY),
          null,
      )
      val component = createTestComponentRenderInfo().component
      val renderInfo = ComponentRenderInfo.create().component(component).build()
      val warmer = ComponentWarmer(recyclerBinder)
      warmer.prepare("stale-accessibility", renderInfo, null)
      val warmedHolder = checkNotNull(warmer.cache["stale-accessibility"])
      assertThat(warmedHolder.hasCompletedLatestLayout()).isTrue()
      setAccessibilityEnabled(!initialAccessibility)
      assertThat(warmedHolder.hasCompletedLatestLayout()).isFalse()

      try {
        ShadowLooper.pauseMainLooper()
        recyclerBinder.insertItemAtAsync(
            0,
            ComponentRenderInfo.create()
                .component(component)
                .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "stale-accessibility")
                .build(),
        )
        recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
        if (repeatMeasureBeforeLayoutCompletes) {
          val tree = checkNotNull(warmedHolder.componentTree)
          val forcedRootId = checkNotNull(tree.root).instanceId
          recyclerBinder.measure(
              Size(),
              makeSizeSpec(1000, EXACTLY),
              makeSizeSpec(1000, EXACTLY),
              null,
          )
          assertThat(checkNotNull(tree.root).instanceId).isEqualTo(forcedRootId)
        }
        runToEndOfTasks()
        ShadowLooper.runUiThreadTasks()
        val completedLatestLayout = warmedHolder.hasCompletedLatestLayout()
        assertThat(completedLatestLayout)
            .withFailMessage(
                "completed=%s itemCount=%s pending=%s",
                completedLatestLayout,
                recyclerBinder.getItemCount(),
                recyclerBinder.getPendingAsyncBatchesDebugInfo(),
            )
            .isEqualTo(enableLivenessFix)

        return AsyncInsertLivenessResult(
            itemCount = recyclerBinder.getItemCount(),
            pendingBatches = recyclerBinder.getPendingAsyncBatchesDebugInfo(),
        )
      } finally {
        ShadowLooper.unPauseMainLooper()
      }
    } finally {
      setAccessibilityEnabled(previousAccessibility)
    }
  }

  private fun insertFreshThenWarmedLayoutWithWidthChange(
      enableLivenessFix: Boolean,
  ): AsyncInsertLivenessResult {
    val recyclerBinder = createLayoutBeforeInsertBinder(enableLivenessFix)
    recyclerBinder.measure(
        Size(),
        makeSizeSpec(1000, EXACTLY),
        makeSizeSpec(1000, EXACTLY),
        null,
    )
    val warmed = createTestComponentRenderInfo() as ComponentRenderInfo
    val warmer = ComponentWarmer(recyclerBinder)
    warmer.prepare("width-change", warmed, null)
    val warmedHolder = checkNotNull(warmer.cache["width-change"])

    try {
      ShadowLooper.pauseMainLooper()
      recyclerBinder.insertItemAtAsync(0, createTestComponentRenderInfo())
      recyclerBinder.insertItemAtAsync(
          1,
          ComponentRenderInfo.create()
              .component(warmed.component)
              .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "width-change")
              .build(),
      )
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
      assertThat(checkNotNull(warmedHolder.componentTree).newLayoutStateReadyListener != null)
          .isEqualTo(enableLivenessFix)
      recyclerBinder.measure(
          Size(),
          makeSizeSpec(1100, EXACTLY),
          makeSizeSpec(1000, EXACTLY),
          null,
      )
      runToEndOfTasks()
      ShadowLooper.runUiThreadTasks()

      return AsyncInsertLivenessResult(
          itemCount = recyclerBinder.getItemCount(),
          pendingBatches = recyclerBinder.getPendingAsyncBatchesDebugInfo(),
      )
    } finally {
      ShadowLooper.unPauseMainLooper()
    }
  }

  private fun setAccessibilityEnabled(enabled: Boolean) {
    val manager =
        getApplicationContext<Context>().getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
    val shadowManager = Shadows.shadowOf(manager)
    shadowManager.setEnabled(enabled)
    shadowManager.setTouchExplorationEnabled(enabled)
    AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()
  }

  private fun insertNormalThenEmptyLayout(enableLivenessFix: Boolean): AsyncInsertLivenessResult {
    val recyclerBinder = createLayoutBeforeInsertBinder(enableLivenessFix)
    val normal = createTestComponentRenderInfo()
    val empty = ComponentRenderInfo.createEmpty()
    try {
      ShadowLooper.pauseMainLooper()
      recyclerBinder.measure(
          Size(),
          makeSizeSpec(1000, EXACTLY),
          makeSizeSpec(1000, EXACTLY),
          null,
      )
      recyclerBinder.insertItemAtAsync(0, normal)
      recyclerBinder.insertItemAtAsync(1, empty)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

      val normalCompleted = "{insert pos=0 name=${normal.name} completed=true"
      var steps = 0
      while (recyclerBinder.getPendingAsyncBatchesDebugInfo()?.contains(normalCompleted) != true) {
        check(steps++ < 10) { "The normal insert layout was never completed" }
        runOneTask()
      }
      assertThat(recyclerBinder.getPendingAsyncBatchesDebugInfo())
          .contains("{insert pos=1 name=${empty.name} completed=false")

      // The normal layout wakes the binder while the empty layout is still pending.
      ShadowLooper.runMainLooperOneTask()
      assertThat(recyclerBinder.getItemCount()).isZero()
      assertThat(recyclerBinder.getPendingAsyncBatchesDebugInfo())
          .contains(
              "insert pos=0 name=${normal.name} completed=true treeValid=true " +
                  "readyListener=$enableLivenessFix",
          )

      val emptyCompleted = "{insert pos=1 name=${empty.name} completed=true"
      steps = 0
      while (
          recyclerBinder.getItemCount() == 0 &&
              recyclerBinder.getPendingAsyncBatchesDebugInfo()?.contains(emptyCompleted) != true
      ) {
        check(steps++ < 10) { "The empty insert layout was never completed" }
        runOneTask()
      }
      ShadowLooper.runUiThreadTasks()

      return AsyncInsertLivenessResult(
          itemCount = recyclerBinder.getItemCount(),
          pendingBatches = recyclerBinder.getPendingAsyncBatchesDebugInfo(),
      )
    } finally {
      ShadowLooper.unPauseMainLooper()
    }
  }

  private fun createLayoutBeforeInsertBinder(
      enableLivenessFix: Boolean,
      wrapContent: Boolean = false,
  ): RecyclerBinder {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .rangeRatio(RANGE_RATIO)
                    .wrapContent(wrapContent)
                    .enableAsyncInsertLivenessFix(enableLivenessFix)
                    .build(),
            )
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    return recyclerBinder
  }

  private data class AsyncInsertLivenessResult(
      val itemCount: Int,
      val pendingBatches: String?,
  )

  private class NewLayoutStateReadyListener : ComponentTree.NewLayoutStateReadyListener {
    var timesCalled = 0

    override fun onNewLayoutStateReady(componentTree: ComponentTree) {
      timesCalled++
    }
  }

  @Test
  fun testInsertAsyncOnMainThread() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue

    runToEndOfTasks()

    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue
  }

  @Test
  fun testInsertAsyncOnBackgroundThread() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runOnBackgroundThreadSync {
      recyclerBinder.insertItemAtAsync(0, renderInfo)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue
  }

  @Test
  fun testMultipleInsertAsyncs() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runOnBackgroundThreadSync {
      recyclerBinder.insertItemAtAsync(0, renderInfo)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val component2 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo2 = ComponentRenderInfo.create().component(component2).build()

    runOnBackgroundThreadSync {
      recyclerBinder.insertItemAtAsync(0, renderInfo2)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue

    val holder2 = recyclerBinder.getComponentTreeHolderAt(1)
    assertThat(holder2.renderInfo.component.isEquivalentTo(component2)).isTrue
    assertThat(holder2.hasCompletedLatestLayout()).isTrue
    assertThat(holder2.isTreeValid).isTrue
  }

  @Test
  fun testInsertAsyncBeforeInitialMeasure() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    runOnBackgroundThreadSync {
      recyclerBinder.insertItemAtAsync(0, renderInfo)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue
  }

  @Test
  fun testInsertAsyncWithViewRenderInfo() {
    val renderInfo =
        ViewRenderInfo.create()
            .viewBinder(
                object : ViewBinder<ProgressBar> {
                  override fun prepare() = Unit

                  override fun bind(view: ProgressBar) {
                    view.progress = 50
                  }

                  override fun unbind(view: ProgressBar) {
                    view.progress = 0
                  }
                },
            )
            .viewCreator(
                object : ViewCreator<ProgressBar> {
                  override fun createView(c: Context, parent: ViewGroup?): ProgressBar {
                    return ProgressBar(c)
                  }
                },
            )
            .build()

    val recyclerBinder = RecyclerBinder.Builder().build(componentContext)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    val recyclerView = RecyclerView(componentContext.androidContext)
    recyclerBinder.mount(recyclerView)

    recyclerView.measure(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
    recyclerView.layout(0, 0, 1000, 1000)

    assertThat(recyclerView.childCount).isEqualTo(1)
    assertThat(recyclerView.getChildAt(0)).isInstanceOf(ProgressBar::class.java)
  }

  @Test
  fun testInsertRangeAsync() {
    val NUM_TO_INSERT = 5
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runOnBackgroundThreadSync {
      recyclerBinder.insertRangeAtAsync(0, renderInfos)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    for (i in 0 until NUM_TO_INSERT) {
      val holder = recyclerBinder.getComponentTreeHolderAt(i)
      assertThat(holder.renderInfo.component.isEquivalentTo(components[i])).isTrue
      assertThat(holder.hasCompletedLatestLayout()).isTrue
      assertThat(holder.isTreeValid).isTrue
    }
  }

  @Test
  fun testInsertRangeAsyncBeforeInitialMeasure() {
    val NUM_TO_INSERT = 5
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    runOnBackgroundThreadSync {
      recyclerBinder.insertRangeAtAsync(0, renderInfos)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)

    for (i in 0 until NUM_TO_INSERT) {
      val holder = recyclerBinder.getComponentTreeHolderAt(i)
      assertThat(holder.renderInfo.component.isEquivalentTo(components[i])).isTrue
      assertThat(holder.hasCompletedLatestLayout()).isTrue
      assertThat(holder.isTreeValid).isTrue
    }
  }

  @Test
  fun testInsertAsync_AsyncMode() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue
  }

  @Test
  fun testPendingAsyncBatchesDebugInfo_describesInsertWaitingForMeasure() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    assertThat(recyclerBinder.getPendingAsyncBatchesDebugInfo()).isNull()

    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getPendingAsyncBatchesDebugInfo())
        .contains("batches=1")
        .contains("measured=false")
        .contains("recyclerView=null")
        .contains("items=0")
        .contains("firstBatch=[policy=LAYOUT_BEFORE_INSERT ready=false ops=1")
        .contains("stillPending=true")
        .contains(
            "{insert pos=0 name=${renderInfo.name} completed=false treeValid=false hasTree=false}",
        )

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getPendingAsyncBatchesDebugInfo()).isNull()
  }

  @Test
  fun testPendingAsyncBatchesDebugInfo_describesReadyButUnappliedBatch() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val normal = createTestComponentRenderInfo()
    val empty = ComponentRenderInfo.createEmpty()
    try {
      ShadowLooper.pauseMainLooper()
      recyclerBinder.measure(
          Size(),
          makeSizeSpec(1000, EXACTLY),
          makeSizeSpec(1000, EXACTLY),
          null,
      )
      recyclerBinder.insertItemAtAsync(0, normal)
      recyclerBinder.insertItemAtAsync(1, empty)
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

      val normalCompleted = "{insert pos=0 name=${normal.name} completed=true"
      var steps = 0
      while (recyclerBinder.getPendingAsyncBatchesDebugInfo()?.contains(normalCompleted) != true) {
        check(steps++ < 10) { "The normal insert layout was never completed" }
        runOneTask()
      }

      // The normal layout wakes the binder while the empty layout is still pending.
      ShadowLooper.runMainLooperOneTask()
      assertThat(recyclerBinder.getItemCount()).isZero()

      val emptyCompleted = "{insert pos=1 name=${empty.name} completed=true"
      steps = 0
      while (recyclerBinder.getPendingAsyncBatchesDebugInfo()?.contains(emptyCompleted) != true) {
        check(steps++ < 10) { "The empty insert layout was never completed" }
        runOneTask()
      }
      ShadowLooper.runUiThreadTasks()

      assertThat(recyclerBinder.getItemCount()).isZero()
      assertThat(recyclerBinder.getPendingAsyncBatchesDebugInfo())
          .contains(
              "batches=1",
              "items=0",
              "firstBatch=[policy=LAYOUT_BEFORE_INSERT ready=true ops=2",
              "stillPending=true",
          )
          .contains("insert pos=0 name=${normal.name} completed=true treeValid=true")
          .contains("insert pos=1 name=${empty.name} completed=true treeValid=true")
    } finally {
      ShadowLooper.unPauseMainLooper()
    }
  }

  @Test
  fun testDetachStateUpdaterOnTreeReleaseReachesHolders() {
    val holderConfigurations = mutableListOf<ComponentsConfiguration>()
    val holderFactory =
        RecyclerBinder.ComponentTreeHolderFactory {
            renderInfo,
            layoutHandler,
            measureListenerFactory,
            componentsConfiguration,
            lifecycleProvider ->
          holderConfigurations.add(componentsConfiguration)
          ComponentTreeHolder.create(componentsConfiguration)
              .renderInfo(renderInfo)
              .layoutHandler(layoutHandler)
              .componentTreeMeasureListenerFactory(measureListenerFactory)
              .lithoVisibilityEventsController(lifecycleProvider)
              .build()
        }
    val recyclerBinder =
        RecyclerBinder.Builder()
            .componentTreeHolderFactory(holderFactory)
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .componentsConfiguration(
                        ComponentsConfiguration.create()
                            .detachStateUpdaterOnTreeRelease(true)
                            .build(),
                    )
                    .build(),
            )
            .build(componentContext)

    recyclerBinder.insertItemAt(0, createTestComponentRenderInfo())
    recyclerBinder.insertItemAt(1, createTestComponentRenderInfo())

    assertThat(holderConfigurations).hasSize(2)
    assertThat(holderConfigurations).allMatch { it.detachStateUpdaterOnTreeRelease }
  }

  @Test
  fun testPendingAsyncBatchesDebugInfo_doesNotHoldBinderLockWhileInspectingHolder() {
    lateinit var pendingHolder: ComponentTreeHolder
    val holderFactory =
        RecyclerBinder.ComponentTreeHolderFactory {
            renderInfo,
            layoutHandler,
            measureListenerFactory,
            componentsConfiguration,
            lifecycleProvider ->
          ComponentTreeHolder.create(componentsConfiguration)
              .renderInfo(renderInfo)
              .layoutHandler(layoutHandler)
              .componentTreeMeasureListenerFactory(measureListenerFactory)
              .lithoVisibilityEventsController(lifecycleProvider)
              .build()
              .also { pendingHolder = it }
        }
    val recyclerBinder =
        RecyclerBinder.Builder()
            .componentTreeHolderFactory(holderFactory)
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.IMMEDIATE)
    val failure = AtomicReference<Throwable?>()
    val backgroundDone = CountDownLatch(1)
    val releaseDone = CountDownLatch(1)

    try {
      ShadowLooper.pauseMainLooper()
      recyclerBinder.insertItemAtAsync(0, createTestComponentRenderInfo())
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
      pendingHolder.computeLayoutSync(
          componentContext,
          makeSizeSpec(1000, EXACTLY),
          makeSizeSpec(1000, EXACTLY),
          null,
      )
      val tree = checkNotNull(pendingHolder.componentTree)
      val backgroundReader = Thread(
          {
            try {
              recyclerBinder.getPendingAsyncBatchesDebugInfo()
            } catch (throwable: Throwable) {
              failure.compareAndSet(null, throwable)
            } finally {
              backgroundDone.countDown()
            }
          },
          "pending-batch-debug-reader",
      )
          .apply { isDaemon = true }
      tree.addOnReleaseListener {
        backgroundReader.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (
            backgroundReader.state != Thread.State.BLOCKED &&
                backgroundReader.isAlive &&
                System.nanoTime() < deadline
        ) {
          Thread.yield()
        }
        check(backgroundReader.state == Thread.State.BLOCKED) {
          "Background diagnostics did not block on the holder monitor"
        }
        recyclerBinder.getPendingAsyncBatchesDebugInfo()
      }

      com.facebook.litho.ThreadUtils.setMainThreadOverride(
          com.facebook.litho.ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE,
      )
      Thread(
          {
            try {
              pendingHolder.releaseTree()
            } catch (throwable: Throwable) {
              failure.compareAndSet(null, throwable)
            } finally {
              releaseDone.countDown()
            }
          },
          "pending-holder-release",
      )
          .apply { isDaemon = true }
          .start()

      assertThat(releaseDone.await(5, TimeUnit.SECONDS)).isTrue
      assertThat(backgroundDone.await(5, TimeUnit.SECONDS)).isTrue
      assertThat(failure.get()).isNull()
    } finally {
      com.facebook.litho.ThreadUtils.setMainThreadOverride(
          com.facebook.litho.ThreadUtils.OVERRIDE_DISABLED,
      )
      ShadowLooper.unPauseMainLooper()
    }
  }

  @Test
  fun testInsertAsync_PolicyChangeMode() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)

    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)

    val secondComponent = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val secondRenderInfo = ComponentRenderInfo.create().component(secondComponent).build()
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.IMMEDIATE)
    recyclerBinder.insertItemAtAsync(1, secondRenderInfo)

    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue

    val thirdComponent = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val thirdRenderInfo = ComponentRenderInfo.create().component(thirdComponent).build()
    recyclerBinder.insertItemAtAsync(2, thirdRenderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    assertThat(recyclerBinder.getItemCount()).isEqualTo(3)
  }

  @Test
  fun testMultipleInsertAsyncs_AsyncMode() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val component2 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo2 = ComponentRenderInfo.create().component(component2).build()

    recyclerBinder.insertItemAtAsync(0, renderInfo2)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue

    val holder2 = recyclerBinder.getComponentTreeHolderAt(1)
    assertThat(holder2.renderInfo.component.isEquivalentTo(component2)).isTrue
    assertThat(holder2.hasCompletedLatestLayout()).isTrue
    assertThat(holder2.isTreeValid).isTrue
  }

  @Test
  fun testInsertAsyncBeforeInitialMeasure_AsyncMode() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue
  }

  @Test
  fun testInsertRangeAsync_AsyncMode() {
    val NUM_TO_INSERT = 5
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    for (i in 0 until NUM_TO_INSERT) {
      val holder = recyclerBinder.getComponentTreeHolderAt(i)
      assertThat(holder.renderInfo.component.isEquivalentTo(components[i])).isTrue
      assertThat(holder.hasCompletedLatestLayout()).isTrue
      assertThat(holder.isTreeValid).isTrue
    }
  }

  @Test
  fun testInsertRangeAsyncBeforeInitialMeasure_AsyncMode() {
    val NUM_TO_INSERT = 5
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)

    for (i in 0 until NUM_TO_INSERT) {
      val holder = recyclerBinder.getComponentTreeHolderAt(i)
      assertThat(holder.renderInfo.component.isEquivalentTo(components[i])).isTrue
      assertThat(holder.hasCompletedLatestLayout()).isTrue
      assertThat(holder.isTreeValid).isTrue
    }
  }

  @Test
  fun testInsertRangeAsyncBeforeInitialMeasureRangeIsLargerThanMeasure_AsyncMode() {
    val NUM_TO_INSERT = 5
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(600).heightPx(600).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    // compute one layout to ensure batching behavior remains
    runOneTask()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    // finish computing all layouts - batch should now be applied
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    for (i in 0 until NUM_TO_INSERT) {
      val holder = recyclerBinder.getComponentTreeHolderAt(i)
      assertThat(holder.renderInfo.component.isEquivalentTo(components[i])).isTrue
      assertThat(holder.hasCompletedLatestLayout()).isTrue
      assertThat(holder.isTreeValid).isTrue
    }
  }

  @Test
  fun testInsertsDispatchedInBatch_AsyncMode() {
    val NUM_TO_INSERT = 5
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    // complete first layout
    runOneTask()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    // complete second layout
    runOneTask()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()

    // complete the rest of the layouts
    for (i in 2 until NUM_TO_INSERT) {
      runToEndOfTasks()
    }

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()
  }

  @Test
  fun testInsertAsyncWithSizeChangeBeforeCompletion() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    recyclerBinder.measure(Size(), makeSizeSpec(500, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue
    assertHasCompatibleLayout(
        recyclerBinder,
        0,
        makeSizeSpec(500, EXACTLY),
        makeSizeSpec(0, UNSPECIFIED),
    )
  }

  @Test
  fun testInsertAsyncWithSizeChangeBeforeBatchClosed() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)

    recyclerBinder.measure(Size(), makeSizeSpec(500, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo.component.isEquivalentTo(component)).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
    assertThat(holder.isTreeValid).isTrue
    assertHasCompatibleLayout(
        recyclerBinder,
        0,
        makeSizeSpec(500, EXACTLY),
        makeSizeSpec(0, UNSPECIFIED),
    )
  }

  @Test(expected = RuntimeException::class)
  fun testThrowsIfSyncInsertAfterAsyncInsert() {
    recyclerBinder.insertItemAtAsync(0, createTestComponentRenderInfo())
    recyclerBinder.insertItemAt(1, createTestComponentRenderInfo())
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  @Test(expected = RuntimeException::class)
  fun testThrowsIfSyncInsertAfterAsyncInsertRange() {
    val firstInsert = ArrayList<RenderInfo>()
    val secondInsert = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      firstInsert.add(createTestComponentRenderInfo())
      secondInsert.add(createTestComponentRenderInfo())
    }

    recyclerBinder.insertRangeAtAsync(0, firstInsert)
    recyclerBinder.insertRangeAt(firstInsert.size, secondInsert)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  @Test
  fun testRemoveAsync() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(1)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()

    recyclerBinder.removeItemAtAsync(0)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNotNull()
  }

  @Test
  fun testRemoveAsyncMixedWithInserts() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 4) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfos[0])
    recyclerBinder.insertItemAtAsync(1, renderInfos[1])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2)

    recyclerBinder.insertItemAtAsync(0, renderInfos[2])
    recyclerBinder.removeItemAtAsync(0)
    recyclerBinder.insertItemAtAsync(2, renderInfos[3])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(3)
    assertComponentAtEquals(recyclerBinder, 0, components[0])
    assertComponentAtEquals(recyclerBinder, 1, components[1])
    assertComponentAtEquals(recyclerBinder, 2, components[3])
  }

  @Test
  fun testMoveAsync() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 4) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(4)

    recyclerBinder.moveItemAsync(0, 2)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(4)
    assertComponentAtEquals(recyclerBinder, 0, components[1])
    assertComponentAtEquals(recyclerBinder, 1, components[2])
    assertComponentAtEquals(recyclerBinder, 2, components[0])
    assertComponentAtEquals(recyclerBinder, 3, components[3])
  }

  @Test
  fun testMoveAsyncMixedWithInserts() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 4) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfos[0])
    recyclerBinder.insertItemAtAsync(1, renderInfos[1])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2)

    recyclerBinder.insertItemAtAsync(0, renderInfos[2])
    recyclerBinder.moveItemAsync(0, 1)
    recyclerBinder.insertItemAtAsync(2, renderInfos[3])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(4)
    assertComponentAtEquals(recyclerBinder, 0, components[0])
    assertComponentAtEquals(recyclerBinder, 1, components[2])
    assertComponentAtEquals(recyclerBinder, 2, components[3])
    assertComponentAtEquals(recyclerBinder, 3, components[1])
  }

  @Test
  fun testUpdateAsyncOnInsertedItem() {
    val adapter = FakeRecyclerAdapter()
    val observer = mock(AdapterDataObserver::class.java)
    adapter.registerAdapterDataObserver(observer)
    val recyclerBinder = createRecyclerBinderWithMockAdapter(adapter)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    val newRenderInfo =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()
    recyclerBinder.updateItemAtAsync(0, newRenderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(observer, never()).onItemRangeChanged(anyInt(), eq(1), isNull())

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo).isEqualTo(newRenderInfo)
    assertThat(holder.isTreeValid).isTrue

    runToEndOfTasks()
  }

  @Test
  fun testUpdateAsyncOnNonInsertedItem() {
    val adapter = FakeRecyclerAdapter()
    val observer = mock(AdapterDataObserver::class.java)
    adapter.registerAdapterDataObserver(observer)
    val recyclerBinder = createRecyclerBinderWithMockAdapter(adapter)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)

    val newRenderInfo =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()
    recyclerBinder.updateItemAtAsync(0, newRenderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    verify(observer, never()).onItemRangeChanged(anyInt(), eq(1), isNull())

    runToEndOfTasks()

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo).isEqualTo(newRenderInfo)
    assertThat(holder.isTreeValid).isTrue
    assertThat(holder.hasCompletedLatestLayout()).isTrue
  }

  @Test
  fun testUpdateAsyncMixedWithInserts() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 4) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfos[0])
    recyclerBinder.insertItemAtAsync(1, renderInfos[1])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2)

    val newRenderInfo0 =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()
    val newRenderInfo1 =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()

    recyclerBinder.insertItemAtAsync(0, renderInfos[2])
    recyclerBinder.updateItemAtAsync(0, newRenderInfo0)
    recyclerBinder.insertItemAtAsync(2, renderInfos[3])
    recyclerBinder.updateItemAtAsync(1, newRenderInfo1)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(4)
    assertComponentAtEquals(recyclerBinder, 0, newRenderInfo0.component)
    assertComponentAtEquals(recyclerBinder, 1, newRenderInfo1.component)
    assertComponentAtEquals(recyclerBinder, 2, components[3])
    assertComponentAtEquals(recyclerBinder, 3, components[1])
  }

  @Test
  fun testUpdateAsyncMixedWithOtherOperations() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 7) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfos[0])
    recyclerBinder.insertItemAtAsync(1, renderInfos[1])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2)

    val newRenderInfo0 =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()
    val newRenderInfo1 =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()
    val newRenderInfo2 =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()

    recyclerBinder.insertItemAtAsync(0, renderInfos[2])
    recyclerBinder.updateItemAtAsync(0, newRenderInfo0)
    recyclerBinder.removeItemAtAsync(2)
    recyclerBinder.insertItemAtAsync(1, renderInfos[3])
    recyclerBinder.moveItemAsync(1, 2)
    recyclerBinder.updateItemAtAsync(1, newRenderInfo1)
    recyclerBinder.insertRangeAtAsync(0, renderInfos.subList(4, renderInfos.size))
    recyclerBinder.updateItemAtAsync(2, newRenderInfo2)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(6)
    assertComponentAtEquals(recyclerBinder, 0, components[4])
    assertComponentAtEquals(recyclerBinder, 1, components[5])
    assertComponentAtEquals(recyclerBinder, 2, newRenderInfo2.component)
    assertComponentAtEquals(recyclerBinder, 3, newRenderInfo0.component)
    assertComponentAtEquals(recyclerBinder, 4, newRenderInfo1.component)
    assertComponentAtEquals(recyclerBinder, 5, components[3])
  }

  @Test
  fun testUpdateAsyncOnInsertedViewFromComponent() {
    val adapter = FakeRecyclerAdapter()
    val observer = mock(AdapterDataObserver::class.java)
    adapter.registerAdapterDataObserver(observer)
    val recyclerBinder = createRecyclerBinderWithMockAdapter(adapter)
    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val renderInfo = ComponentRenderInfo.create().component(component).build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    val newRenderInfo =
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .build()
    recyclerBinder.updateItemAtAsync(0, newRenderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(observer).onItemRangeChanged(0, 1, null)

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo).isEqualTo(newRenderInfo)
    runToEndOfTasks()
  }

  @Test
  fun testUpdateAsyncOnInsertedViewToComponent() {
    val adapter = FakeRecyclerAdapter()
    val observer = mock(AdapterDataObserver::class.java)
    adapter.registerAdapterDataObserver(observer)
    val recyclerBinder = createRecyclerBinderWithMockAdapter(adapter)
    val renderInfo =
        ViewRenderInfo.create()
            .viewBinder(SimpleViewBinder<View>())
            .viewCreator(VIEW_CREATOR_1)
            .build()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAtAsync(0, renderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    val newRenderInfo =
        ComponentRenderInfo.create()
            .component(SimpleMountSpecTester(StyleCompat.widthPx(50).heightPx(50).build()))
            .build()
    recyclerBinder.updateItemAtAsync(0, newRenderInfo)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    verify(observer).onItemRangeChanged(0, 1, null)

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderInfo).isEqualTo(newRenderInfo)
    assertThat(holder.isTreeValid).isTrue

    runToEndOfTasks()
  }

  @Test
  fun testUpdateRangeAtAsync() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 7) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertRangeAtAsync(0, renderInfos.subList(0, 5))
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(5)

    val newComponents = ArrayList<Component>()
    val newRenderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 4) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      newComponents.add(component)
      newRenderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertItemAtAsync(0, renderInfos[5])
    recyclerBinder.updateRangeAtAsync(0, newRenderInfos)
    recyclerBinder.insertItemAtAsync(0, renderInfos[6])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(7)
    assertComponentAtEquals(recyclerBinder, 0, components[6])
    assertComponentAtEquals(recyclerBinder, 1, newComponents[0])
    assertComponentAtEquals(recyclerBinder, 2, newComponents[1])
    assertComponentAtEquals(recyclerBinder, 3, newComponents[2])
    assertComponentAtEquals(recyclerBinder, 4, newComponents[3])
    assertComponentAtEquals(recyclerBinder, 5, components[3])
    assertComponentAtEquals(recyclerBinder, 6, components[4])
  }

  @Test
  fun testRemoveRangeAtAsync() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 7) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertRangeAtAsync(0, renderInfos.subList(0, 5))
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(5)

    recyclerBinder.insertItemAtAsync(0, renderInfos[5])
    recyclerBinder.removeRangeAtAsync(0, 3)
    recyclerBinder.insertItemAtAsync(0, renderInfos[6])
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(4)
    assertComponentAtEquals(recyclerBinder, 0, components[6])
    assertComponentAtEquals(recyclerBinder, 1, components[2])
    assertComponentAtEquals(recyclerBinder, 2, components[3])
    assertComponentAtEquals(recyclerBinder, 3, components[4])
  }

  @Test
  fun testInsertAsyncOutOfOrderBeforeMeasure() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 7) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos.subList(0, 3))
    recyclerBinder.insertRangeAtAsync(0, renderInfos.subList(3, renderInfos.size))
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(7)

    assertComponentAtEquals(recyclerBinder, 0, components[3])
    assertComponentAtEquals(recyclerBinder, 1, components[4])
    assertComponentAtEquals(recyclerBinder, 2, components[5])
    assertComponentAtEquals(recyclerBinder, 3, components[6])
    assertComponentAtEquals(recyclerBinder, 4, components[0])
    assertComponentAtEquals(recyclerBinder, 5, components[1])
    assertComponentAtEquals(recyclerBinder, 6, components[2])
  }

  @Test
  fun testInsertAsyncAndMutationsBeforeMeasure() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 7) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    val updatedIndex3 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.removeRangeAtAsync(1, 3)
    recyclerBinder.updateItemAtAsync(
        3,
        ComponentRenderInfo.create().component(updatedIndex3).build(),
    )
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(4)

    assertComponentAtEquals(recyclerBinder, 0, components[0])
    assertComponentAtEquals(recyclerBinder, 1, components[4])
    assertComponentAtEquals(recyclerBinder, 2, components[5])
    assertComponentAtEquals(recyclerBinder, 3, updatedIndex3)
  }

  @Test
  fun testClearAsync() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 7) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.clearAsync()
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
  }

  @Test
  fun testClearAsyncBeforeMeasure() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 7) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.clearAsync()
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runToEndOfTasks()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
  }

  @Test
  fun testRemoveItemsAsyncBeforeMeasure() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 6) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos.subList(0, 3))
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    recyclerBinder.insertRangeAtAsync(0, renderInfos.subList(3, 6))
    recyclerBinder.removeRangeAtAsync(0, 3)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    recyclerBinder.measure(Size(), makeSizeSpec(100, EXACTLY), makeSizeSpec(100, EXACTLY), null)
    runToEndOfTasks()

    assertThat(recyclerBinder.getRenderInfoAt(0)).isEqualTo(renderInfos[0])
    assertThat(recyclerBinder.getRenderInfoAt(1)).isEqualTo(renderInfos[1])
    assertThat(recyclerBinder.getRenderInfoAt(2)).isEqualTo(renderInfos[2])

    val component0 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val component1 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    val component2 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())

    recyclerBinder.updateItemAtAsync(0, ComponentRenderInfo.create().component(component0).build())
    recyclerBinder.updateItemAtAsync(1, ComponentRenderInfo.create().component(component1).build())
    recyclerBinder.updateItemAtAsync(2, ComponentRenderInfo.create().component(component2).build())
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(3)
    assertThat(recyclerBinder.getRenderInfoAt(0).component.isEquivalentTo(component0)).isTrue()
    assertThat(recyclerBinder.getRenderInfoAt(1).component.isEquivalentTo(component1)).isTrue()
    assertThat(recyclerBinder.getRenderInfoAt(2).component.isEquivalentTo(component2)).isTrue()
  }

  @Test
  fun testRemoveAllItemsAsyncBeforeMeasure() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 6) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    recyclerBinder.removeRangeAtAsync(0, renderInfos.size)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runToEndOfTasks()

    recyclerBinder.measure(Size(), makeSizeSpec(100, EXACTLY), makeSizeSpec(100, EXACTLY), null)
    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
  }

  @Test
  fun testRenderStateWithNotifyItemRenderCompleteAt() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val recyclerView = mock(RecyclerView::class.java)
    recyclerBinder.mount(recyclerView)

    val component = SimpleMountSpecTester()
    val renderCompleteEventHandler =
        mock(EventHandler::class.java) as EventHandler<RenderCompleteEvent>?
    val renderInfo =
        ComponentRenderInfo.create()
            .component(component)
            .renderCompleteHandler(renderCompleteEventHandler)
            .build()

    recyclerBinder.insertItemAt(0, renderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderState).isEqualTo(ComponentTreeHolder.RENDER_UNINITIALIZED)

    recyclerBinder.notifyItemRenderCompleteAt(0, 0)
    verify(recyclerView).postOnAnimation(any(RenderCompleteRunnable::class.java))

    assertThat(holder.renderState).isEqualTo(ComponentTreeHolder.RENDER_DRAWN)
  }

  @Test
  fun testRenderCompleteEventHandlerWithCustomItemView() {
    // We introduced the `RecyclerBinderAdapterDelegate` for IG, which allows us to be able to
    // customize the root view of each item.
    val rootView = FrameLayout(componentContext.androidContext)
    val layoutParams =
        RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.MATCH_PARENT,
        )
    rootView.layoutParams = layoutParams

    val testViewHolder = spy(TestViewHolder(componentContext, rootView))
    val lithoView = LithoView(componentContext)
    whenever(testViewHolder.lithoView).thenReturn(lithoView)

    val delegate: RecyclerBinderAdapterDelegate<RecyclerBinderViewHolder> =
        mock(RecyclerBinderAdapterDelegate::class.java)
            as RecyclerBinderAdapterDelegate<RecyclerBinderViewHolder>
    whenever(delegate.onCreateViewHolder(anyOrNull(), anyInt())).thenReturn(testViewHolder)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .setAdapterDelegate(delegate)
            .build(componentContext)
    val recyclerView = RecyclerView(componentContext.androidContext)
    recyclerBinder.mount(recyclerView)

    val component = SimpleMountSpecTester()
    val renderCompleteEventHandler =
        mock(EventHandler::class.java) as EventHandler<RenderCompleteEvent>?
    val renderInfo =
        ComponentRenderInfo.create()
            .component(component)
            .renderCompleteHandler(renderCompleteEventHandler)
            .build()

    recyclerBinder.insertItemAt(0, renderInfo)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(holder.renderState).isEqualTo(ComponentTreeHolder.RENDER_UNINITIALIZED)

    // To trigger an event of render complete manually
    recyclerView.adapter?.onBindViewHolder(testViewHolder, 0)
    recyclerView.layout(0, 0, 100, 100)
    testViewHolder.lithoView?.draw(mock(Canvas::class.java))

    assertThat(holder.renderState).isEqualTo(ComponentTreeHolder.RENDER_DRAWN)
  }

  private fun createRecyclerBinderWithMockAdapter(
      adapterMock: RecyclerView.Adapter<RecyclerView.ViewHolder>,
  ): RecyclerBinder {
    return RecyclerBinder.Builder()
        .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
        .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
        .overrideInternalAdapter(adapterMock)
        .build(componentContext)
  }

  private fun prepareLoadedBinder(): MutableList<ComponentRenderInfo> {
    return prepareLoadedBinder(recyclerBinder, 100)
  }

  private fun prepareLoadedBinder(
      binder: RecyclerBinder,
      count: Int,
  ): MutableList<ComponentRenderInfo> {
    val components = ArrayList<ComponentRenderInfo>()
    for (i in 0 until count) {
      components.add(ComponentRenderInfo.create().component(mock(Component::class.java)).build())
    }
    binder.insertRangeAt(0, components as List<RenderInfo>)
    binder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until count) {
      Assert.assertNotNull(holdersForComponents[components[i].component])
    }

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)

    binder.measure(size, widthSpec, heightSpec, null)

    return components
  }

  private fun makeIndexSticky(components: MutableList<ComponentRenderInfo>, i: Int) {
    components[i] =
        ComponentRenderInfo.create().component(mock(Component::class.java)).isSticky(true).build()
    recyclerBinder.removeItemAt(i)
    recyclerBinder.insertItemAt(i, components[i])
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
  }

  private fun createTestComponentRenderInfo(): RenderInfo {
    return ComponentRenderInfo.create()
        .component(SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build()))
        .build()
  }

  private fun validateRangeWithRetainMaximumRangeStrategy(
      components: List<ComponentRenderInfo>,
      rangeStart: Int,
      rangeEnd: Int,
      lowestRangeStart: Int,
      highestRangeEnd: Int,
  ) {
    var newRangeStart = rangeStart
    var newRangeEnd = rangeEnd
    val rangeDescription = ", in range: $rangeStart-$rangeEnd"
    if (rangeStart >= lowestRangeStart && rangeEnd <= highestRangeEnd) {
      newRangeStart = lowestRangeStart
      newRangeEnd = highestRangeEnd
    }

    val rangeSize = newRangeEnd - newRangeStart
    val rangeTotal = (rangeSize + (RANGE_RATIO * rangeSize)).toInt()

    var componentTreeHolder: TestComponentTreeHolder?
    for (i in components.indices) {
      componentTreeHolder = checkNotNull(holdersForComponents[components[i].component])

      if (i >= newRangeStart - (RANGE_RATIO * rangeSize) && i <= newRangeStart + rangeTotal) {
        assertThat(componentTreeHolder.isTreeValid)
            .describedAs("isTreeValid expected True: index: $i$rangeDescription")
            .isTrue
        assertThat(componentTreeHolder.mLayoutAsyncCalled)
            .describedAs("layoutAsyncCalled expected True index: $i$rangeDescription")
            .isTrue
        assertThat(componentTreeHolder.mLayoutSyncCalled)
            .describedAs("layoutSyncCalled expected True index: $i$rangeDescription")
            .isFalse
      } else {
        assertThat(componentTreeHolder!!.isTreeValid)
            .describedAs("isTreeValid expected False: index: $i$rangeDescription")
            .isFalse
        assertThat(componentTreeHolder.mLayoutAsyncCalled)
            .describedAs("layoutAsyncCalled expected False index: $i$rangeDescription")
            .isFalse
        assertThat(componentTreeHolder.mLayoutSyncCalled)
            .describedAs("layoutSyncCalled expected False index: $i$rangeDescription")
            .isFalse
      }
    }
  }

  private fun interface ViewCreatorProvider {
    fun get(): ViewCreator<*>
  }

  internal class NoOpChangeSetCompleteCallback : ChangeSetCompleteCallback {
    override fun onDataBound() = Unit

    override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) = Unit
  }

  private class FakeRecyclerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      throw UnsupportedOperationException("Not implemented")
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

    override fun getItemCount(): Int = 0
  }

  private class LinearLayoutManagerWithPadding(context: Context) :
      LinearLayoutManager(context), NeedsBgPaddingInfo {

    private val mBgPaddingInfo = Rect()

    override fun setBgPaddingInfo(paddingInfo: Rect) {
      mBgPaddingInfo.set(paddingInfo)
    }

    fun getBgPaddingInfo(): Rect = mBgPaddingInfo
  }

  private class ComputingLayoutRecyclerView(context: Context) : LithoRecyclerView(context) {
    private var mIsComputingLayout = false

    override fun isComputingLayout(): Boolean = mIsComputingLayout

    fun setComputingLayout(computingLayout: Boolean) {
      mIsComputingLayout = computingLayout
    }
  }

  /*
   * ViewHolder that provides custom root view for testing.
   */
  private class TestViewHolder(context: ComponentContext, itemView: View) :
      RecyclerBinderViewHolder(itemView) {

    private val mRootView: ViewGroup = itemView as ViewGroup

    init {
      val lithoView = LithoView(context)
      mRootView.addView(lithoView)
    }

    override val lithoView: LithoView?
      get() = mRootView.getChildAt(0) as? LithoView

    override fun setLithoViewLayoutParams(
        lithoView: LithoView?,
        width: Int,
        height: Int,
        widthSpec: Int,
        heightSpec: Int,
        isFullSpan: Boolean,
    ) {
      // no ops
    }
  }

  companion object {
    @JvmField
    val NO_OP_CHANGE_SET_COMPLETE_CALLBACK: ChangeSetCompleteCallback =
        NoOpChangeSetCompleteCallback()

    private const val RANGE_RATIO = 2.0f
    private const val RANGE_SIZE = 3

    private val VIEW_CREATOR_1 =
        object : ViewCreator<View> {
          override fun createView(c: Context, parent: ViewGroup?): View {
            return View(c)
          }
        }

    private val VIEW_CREATOR_2 =
        object : ViewCreator<ProgressBar> {
          override fun createView(c: Context, parent: ViewGroup?): ProgressBar {
            return ProgressBar(c)
          }
        }

    private val VIEW_CREATOR_3 =
        object : ViewCreator<TextView> {
          override fun createView(c: Context, parent: ViewGroup?): TextView {
            return TextView(c)
          }
        }

    private const val SCROLL_RESTORATION_VIEW_POSITION = 1
    private const val SCROLL_RESTORATION_RECYCLER_VIEW_SIZE = 30
    private const val SCROLL_RESTORATION_PADDING_EDGE = 20
    private const val SCROLL_RESTORATION_ITEM_EDGE = 10

    @JvmStatic
    fun assertHasCompatibleLayout(
        recyclerBinder: RecyclerBinder,
        position: Int,
        widthSpec: Int,
        heightSpec: Int,
    ) {
      val tree = recyclerBinder.getComponentAt(position)
      assertThat(tree).isNotNull
      assertThat(tree!!.hasCompatibleLayout(widthSpec, heightSpec)).isTrue
    }

    private fun assertComponentAtEquals(
        recyclerBinder: RecyclerBinder,
        position: Int,
        component: Component,
    ) {
      val holder = recyclerBinder.getComponentTreeHolderAt(position)
      assertThat(holder).isNotNull
      assertThat(holder!!.renderInfo.rendersComponent()).isTrue
      assertThat(holder.renderInfo.component).isSameAs(component)
    }

    private fun runOnBackgroundThreadSync(runnable: Runnable) {
      val latch = CountDownLatch(1)

      Thread {
        runnable.run()
        latch.countDown()
      }
          .start()

      ThreadTestingUtils.failIfInterrupted {
        assertThat(latch.await(5000, TimeUnit.MILLISECONDS)).isTrue
      }
    }
  }

  @Test
  fun testOnDataBound() {
    val changeSetCompleteCallback1 = mock(ChangeSetCompleteCallback::class.java)
    val changeSetCompleteCallback2 = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos1 = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos1.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAt(0, renderInfos1)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback1)

    verify(changeSetCompleteCallback1).onDataBound()
    reset(changeSetCompleteCallback1)

    recyclerBinder.measure(Size(), makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY), null)

    recyclerBinder.insertRangeAt(renderInfos1.size, renderInfos2)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback2)

    verify(changeSetCompleteCallback1, never()).onDataBound()
    verify(changeSetCompleteCallback2).onDataBound()
  }

  @Test
  fun testOnDataBoundInsertAsync() {
    val changeSetCompleteCallback1 = mock(ChangeSetCompleteCallback::class.java)
    val changeSetCompleteCallback2 = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos1 = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos1.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component).build())
    }

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.insertRangeAtAsync(0, renderInfos1)
          recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback1)
        },
    )

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()
    verify(changeSetCompleteCallback1, never()).onDataBound()

    recyclerBinder.measure(Size(), makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY), null)

    runToEndOfTasks()

    verify(changeSetCompleteCallback1).onDataBound()
    reset(changeSetCompleteCallback1)

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.insertRangeAtAsync(renderInfos1.size, renderInfos2)
          recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback2)
        },
    )

    verify(changeSetCompleteCallback2, never()).onDataBound()

    runToEndOfTasks()

    verify(changeSetCompleteCallback1, never()).onDataBound()
    verify(changeSetCompleteCallback2).onDataBound()
  }

  @Test
  fun testOnDataBoundInsertAsync_AsyncMode() {
    val changeSetCompleteCallback1 = mock(ChangeSetCompleteCallback::class.java)
    val changeSetCompleteCallback2 = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val renderInfos1 = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos1.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos1)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback1)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)
    assertThat(recyclerBinder.getRangeCalculationResult()).isNull()
    verify(changeSetCompleteCallback1, never()).onDataBound()

    recyclerBinder.measure(Size(), makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY), null)

    verify(changeSetCompleteCallback1, never()).onDataBound()

    runToEndOfTasks()

    verify(changeSetCompleteCallback1).onDataBound()
    reset(changeSetCompleteCallback1)

    recyclerBinder.insertRangeAtAsync(renderInfos1.size, renderInfos2)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback2)

    verify(changeSetCompleteCallback2, never()).onDataBound()

    runToEndOfTasks()

    verify(changeSetCompleteCallback1, never()).onDataBound()
    verify(changeSetCompleteCallback2).onDataBound()
  }

  @Test
  fun testOnDataBoundInsertAsyncLessThanViewport_AsyncMode() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    recyclerBinder.setCommitPolicy(RecyclerBinder.CommitPolicy.LAYOUT_BEFORE_INSERT)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback)

    verify(changeSetCompleteCallback, never()).onDataBound()

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    runToEndOfTasks()

    verify(changeSetCompleteCallback).onDataBound()
  }

  @Test
  fun testOnDataRenderedWithMountAfterInsert() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    // Mount view after insertions
    val recyclerView = LithoRecyclerView(getApplicationContext())
    recyclerBinder.mount(recyclerView)

    // Simulate calling ViewGroup#dispatchDraw(Canvas).
    recyclerView.dispatchDraw(mock(Canvas::class.java))

    verify(changeSetCompleteCallback).onDataRendered(eq(true), anyLong())
  }

  @Test
  fun testOnDataRenderedWithMountAfterInsertAsync() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()

    // Mount view after insertions
    val recyclerView = LithoRecyclerView(getApplicationContext())
    recyclerBinder.mount(recyclerView)

    // Simulate calling ViewGroup#dispatchDraw(Canvas).
    recyclerView.dispatchDraw(mock(Canvas::class.java))

    verify(changeSetCompleteCallback).onDataRendered(eq(true), anyLong())
  }

  @Test
  fun testOnDataRenderedWithMountUnMountBeforeInsert() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    // mount() and unmount() are called prior to data insertions.
    val recyclerView = mock(LithoRecyclerView::class.java)
    recyclerBinder.mount(recyclerView)
    recyclerBinder.unmount(recyclerView)

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    verify(changeSetCompleteCallback).onDataRendered(eq(false), anyLong())
  }

  @Test
  fun testOnDataRenderedWithNoPendingUpdate() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    val recyclerView = mock(LithoRecyclerView::class.java)
    whenever(recyclerView.hasPendingAdapterUpdates()).thenReturn(false)
    whenever(recyclerView.isAttachedToWindow).thenReturn(true)
    whenever(recyclerView.windowVisibility).thenReturn(View.VISIBLE)
    whenever(recyclerView.alpha).thenReturn(1f)
    whenever(recyclerView.visibility).thenReturn(View.VISIBLE)
    whenever(recyclerView.getGlobalVisibleRect(any(Rect::class.java))).thenReturn(true)
    recyclerBinder.mount(recyclerView)

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    verify(changeSetCompleteCallback).onDataRendered(eq(true), anyLong())
  }

  @Test
  fun testOnDataRenderedWithViewDetachedFromWindow() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    val recyclerView = mock(LithoRecyclerView::class.java)
    whenever(recyclerView.hasPendingAdapterUpdates()).thenReturn(true)
    whenever(recyclerView.isAttachedToWindow).thenReturn(false)
    whenever(recyclerView.windowVisibility).thenReturn(View.VISIBLE)
    whenever(recyclerView.alpha).thenReturn(1f)
    whenever(recyclerView.visibility).thenReturn(View.VISIBLE)
    whenever(recyclerView.getGlobalVisibleRect(any(Rect::class.java))).thenReturn(true)
    recyclerBinder.mount(recyclerView)

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    verify(changeSetCompleteCallback).onDataRendered(eq(true), anyLong())
  }

  @Test
  fun testOnDataRenderedWithViewVisibilityIsGone() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    val recyclerView = mock(LithoRecyclerView::class.java)
    whenever(recyclerView.hasPendingAdapterUpdates()).thenReturn(true)
    whenever(recyclerView.isAttachedToWindow).thenReturn(true)
    whenever(recyclerView.windowVisibility).thenReturn(View.GONE)
    whenever(recyclerView.alpha).thenReturn(1f)
    whenever(recyclerView.visibility).thenReturn(View.GONE)
    whenever(recyclerView.getGlobalVisibleRect(any(Rect::class.java))).thenReturn(false)
    recyclerBinder.mount(recyclerView)

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    verify(changeSetCompleteCallback).onDataRendered(eq(true), anyLong())
  }

  @Test
  fun testOnDataRenderedWithMultipleUpdates() {
    val changeSetCompleteCallback1 = mock(ChangeSetCompleteCallback::class.java)
    val changeSetCompleteCallback2 = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos1 = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos1.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAt(0, renderInfos1)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback1)
    verify(changeSetCompleteCallback1, never()).onDataRendered(eq(true), anyLong())

    recyclerBinder.insertRangeAt(0, renderInfos2)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback2)
    verify(changeSetCompleteCallback2, never()).onDataRendered(eq(true), anyLong())

    // Mount view after insertions
    val recyclerView = LithoRecyclerView(getApplicationContext())
    recyclerBinder.mount(recyclerView)

    // Simulate calling ViewGroup#dispatchDraw(Canvas).
    recyclerView.dispatchDraw(mock(Canvas::class.java))

    verify(changeSetCompleteCallback1).onDataRendered(eq(true), anyLong())
    verify(changeSetCompleteCallback2).onDataRendered(eq(true), anyLong())
  }

  @Test
  fun testOnDataRenderedWithMultipleAsyncUpdates() {
    val changeSetCompleteCallback1 = mock(ChangeSetCompleteCallback::class.java)
    val changeSetCompleteCallback2 = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos1 = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos1.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos1)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback1)
    verify(changeSetCompleteCallback1, never()).onDataRendered(eq(true), anyLong())

    recyclerBinder.insertRangeAtAsync(0, renderInfos2)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback2)
    verify(changeSetCompleteCallback2, never()).onDataRendered(eq(true), anyLong())

    // Mount view after insertions
    val recyclerView = LithoRecyclerView(getApplicationContext())

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(2 * 5)

    recyclerBinder.mount(recyclerView)

    // Simulate calling ViewGroup#dispatchDraw(Canvas).
    recyclerView.dispatchDraw(mock(Canvas::class.java))

    verify(changeSetCompleteCallback1).onDataRendered(eq(true), anyLong())
    verify(changeSetCompleteCallback2).onDataRendered(eq(true), anyLong())
  }

  @Test
  fun testOnDataRenderedWithNoChanges() {
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    val recyclerView = mock(LithoRecyclerView::class.java)
    whenever(recyclerView.hasPendingAdapterUpdates()).thenReturn(true)
    recyclerBinder.mount(recyclerView)

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    verify(changeSetCompleteCallback).onDataRendered(eq(true), anyLong())

    reset(changeSetCompleteCallback)

    // Call notifyChangeSetComplete with no actual data change.
    recyclerBinder.notifyChangeSetComplete(false, changeSetCompleteCallback)

    verify(changeSetCompleteCallback).onDataRendered(eq(true), anyLong())
    verify(recyclerView, never()).postOnAnimation(recyclerBinder.remeasureRunnable)
  }

  @Test
  fun testChangeSetCompleteCallbacksIsNotEmptyWithInsertBeforeMount() {
    val recyclerView = mock(RecyclerView::class.java)
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    // Mount view after insertions
    recyclerBinder.mount(recyclerView)

    assertThat(recyclerBinder.dataRenderedCallbacks).isNotEmpty()
  }

  @Test
  fun testChangeSetCompleteCallbacksIsNotEmptyWithAsyncInsertBeforeMount() {
    val recyclerView = mock(RecyclerView::class.java)
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runToEndOfTasks()

    // Mount view after insertions
    recyclerBinder.mount(recyclerView)

    assertThat(recyclerBinder.dataRenderedCallbacks).isNotEmpty()
  }

  @Test
  fun testDataRenderedCallbacksIsEmptyWithInsertAfterMount() {
    val recyclerView = mock(RecyclerView::class.java)
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)

    // Mount view before insertions
    recyclerBinder.mount(recyclerView)

    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)

    assertThat(recyclerBinder.dataRenderedCallbacks).isEmpty()
  }

  @Test
  fun testDataRenderedCallbacksIsEmptyWithAsyncInsertAfterMount() {
    val recyclerView = mock(RecyclerView::class.java)
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)

    // Mount view before insertions
    recyclerBinder.mount(recyclerView)

    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, changeSetCompleteCallback)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    assertThat(recyclerBinder.dataRenderedCallbacks).isEmpty()
  }

  @Test
  fun testAdditionalPostDispatchDrawListenerOnDispatchDraw() {
    val mockDispatchDrawListener = mock(PostDispatchDrawListener::class.java)
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .addAdditionalPostDispatchDrawListeners(ImmutableList.of(mockDispatchDrawListener))
            .build(componentContext)

    val recyclerView = LithoRecyclerView(getApplicationContext())
    recyclerBinder.mount(recyclerView)

    recyclerView.dispatchDraw(mock(Canvas::class.java))

    verify(mockDispatchDrawListener).postDispatchDraw(anyInt())
  }

  // Async init range tests.
  @Test
  fun testInitRangeAsync() {
    val recyclerBinder = recyclerBinderForAsyncInitRangeBuilder.build(componentContext)

    val components = ArrayList<RenderInfo>()

    for (i in 0 until 30) {
      val component =
          object : InlineLayoutSpec() {
            override fun onCreateLayout(c: ComponentContext): Component {
              return SimpleMountSpecTester(StyleCompat.widthPx(0).heightPx(0).build())
            }
          }

      val renderInfo = ComponentRenderInfo.create().component(component).build()
      components.add(renderInfo)
    }

    recyclerBinder.insertRangeAt(0, components)

    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    for (i in 0 until 30) {
      assertThat(holdersForComponents[components[i].component]).isNotNull()
    }

    val size = Size()
    val widthSpec = makeSizeSpec(100, AT_MOST)
    val heightSpec = makeSizeSpec(100, EXACTLY)

    whenever(layoutInfo.getChildHeightSpec(anyInt(), anyOrNull()))
        .thenReturn(SizeSpec.makeSizeSpec(0, SizeSpec.EXACTLY))
    whenever(layoutInfo.getChildWidthSpec(anyInt(), anyOrNull()))
        .thenReturn(SizeSpec.makeSizeSpec(0, SizeSpec.EXACTLY))
    whenever(layoutInfo.approximateRangeSize(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(0)

    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    assertThat(checkNotNull(holdersForComponents[components[0].component]).mLayoutSyncCalled)
        .isTrue()

    runToEndOfTasks()

    assertThat(checkNotNull(holdersForComponents[components[1].component]).mLayoutAsyncCalled)
        .isTrue()
    assertThat(checkNotNull(holdersForComponents[components[2].component]).mLayoutAsyncCalled)
        .isFalse()
  }

  @Test
  fun testDataRenderedCallbacksAreNotTriggered() {
    val reporter = TestComponentsReporter()
    ComponentsReporter.provide(reporter)
    val changeSetCompleteCallback = mock(ChangeSetCompleteCallback::class.java)
    val componentContext = ComponentContext(getApplicationContext())
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    for (i in 0 until 40) {
      recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)
    }

    val recyclerView = mock(LithoRecyclerView::class.java)
    whenever(recyclerView.hasPendingAdapterUpdates()).thenReturn(true)
    whenever(recyclerView.isAttachedToWindow).thenReturn(true)
    whenever(recyclerView.windowVisibility).thenReturn(View.VISIBLE)
    whenever(recyclerView.alpha).thenReturn(1f)
    whenever(recyclerView.visibility).thenReturn(View.VISIBLE)
    whenever(recyclerView.getGlobalVisibleRect(any(Rect::class.java))).thenReturn(true)
    recyclerBinder.mount(recyclerView)

    recyclerBinder.notifyChangeSetComplete(true, changeSetCompleteCallback)
    assertThat(reporter.hasMessageType(LogLevel.ERROR)).isTrue()
    assertThat(recyclerBinder.dataRenderedCallbacks).isEmpty()
  }

  @Test(expected = IllegalStateException::class)
  fun testAsyncOperationsFromMultipleThreadsCrashes() {
    val isDebugMode = LithoDebugConfigurations.isDebugModeEnabled

    // Manually override this to cause change set thread checks
    LithoDebugConfigurations.isDebugModeEnabled = true

    try {
      val recyclerBinder =
          RecyclerBinder.Builder()
              .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
              .build(componentContext)
      val component1 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      val component2 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      val renderInfo1 = ComponentRenderInfo.create().component(component1).build()
      val renderInfo2 = ComponentRenderInfo.create().component(component2).build()

      recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

      val latch = CountDownLatch(1)

      Thread(
          Runnable {
            try {
              recyclerBinder.insertItemAtAsync(0, renderInfo2)
            } finally {
              latch.countDown()
            }
          },
      )
          .start()

      assertThat(latch.await(5000, TimeUnit.MILLISECONDS)).isTrue()

      recyclerBinder.insertItemAtAsync(0, renderInfo1)
    } finally {
      LithoDebugConfigurations.isDebugModeEnabled = isDebugMode
    }
  }

  @Test
  fun testHScrollAsyncModeInsertFirstOnMainThread() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().hScrollAsyncMode(true).rangeRatio(10f).build(),
            )
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      val component2 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component2).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    recyclerBinder.insertRangeAtAsync(5, renderInfos2)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // Measure on main means we don't pre-compute all the layouts
    recyclerBinder.measure(Size(), makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY), null)

    assertThat(recyclerBinder.getItemCount()).isEqualTo(10)

    // First is completed for range calculation
    assertThat(recyclerBinder.getComponentTreeHolderAt(0).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(1).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(2).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(3).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(4).hasCompletedLatestLayout()).isFalse()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(10)

    assertThat(recyclerBinder.getComponentTreeHolderAt(5).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(6).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(7).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(8).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(9).hasCompletedLatestLayout()).isTrue()
  }

  @Test
  fun testHScrollAsyncModeInsertFirstOnBGThread() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().hScrollAsyncMode(true).rangeRatio(10f).build(),
            )
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      val component2 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component2).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    recyclerBinder.insertRangeAtAsync(5, renderInfos2)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.measure(
              Size(),
              makeSizeSpec(200, EXACTLY),
              makeSizeSpec(200, EXACTLY),
              null,
          )
        },
    )

    ShadowLooper.runUiThreadTasks()

    // Measure on background means we fill the viewport
    assertThat(recyclerBinder.getItemCount()).isEqualTo(10)

    assertThat(recyclerBinder.getComponentTreeHolderAt(0).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(1).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(2).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(3).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(4).hasCompletedLatestLayout()).isFalse()

    runToEndOfTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(10)

    assertThat(recyclerBinder.getComponentTreeHolderAt(5).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(6).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(7).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(8).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(9).hasCompletedLatestLayout()).isTrue()
  }

  @Test
  fun testHScrollAsyncModeInsertBeforeBatchApplied() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().hScrollAsyncMode(true).rangeRatio(10f).build(),
            )
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.insertRangeAtAsync(0, renderInfos)
          recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

          recyclerBinder.measure(
              Size(),
              makeSizeSpec(200, EXACTLY),
              makeSizeSpec(200, EXACTLY),
              null,
          )
        },
    )

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)

    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(5)

    assertThat(recyclerBinder.getComponentTreeHolderAt(0).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(1).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(2).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(3).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(4).hasCompletedLatestLayout()).isFalse()

    runToEndOfTasks()

    assertThat(recyclerBinder.getComponentTreeHolderAt(2).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(3).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(4).hasCompletedLatestLayout()).isTrue()
  }

  @Test
  fun testHScrollAsyncModeDoesNotFillViewportTwice() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().hScrollAsyncMode(true).rangeRatio(10f).build(),
            )
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    val renderInfos2 = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      val component2 = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
      renderInfos2.add(ComponentRenderInfo.create().component(component2).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.measure(
              Size(),
              makeSizeSpec(200, EXACTLY),
              makeSizeSpec(200, EXACTLY),
              null,
          )
        },
    )

    ShadowLooper.runUiThreadTasks()

    // Measure on background means we fill the viewport
    assertThat(recyclerBinder.getItemCount()).isEqualTo(5)

    assertThat(recyclerBinder.getComponentTreeHolderAt(0).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(1).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(2).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(3).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(4).hasCompletedLatestLayout()).isFalse()

    runToEndOfTasks()

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.measure(
              Size(),
              makeSizeSpec(300, EXACTLY),
              makeSizeSpec(300, EXACTLY),
              null,
          )
        },
    )

    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getComponentTreeHolderAt(1).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(1).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(2).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(3).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(4).hasCompletedLatestLayout()).isFalse()
  }

  @Test
  fun testNoEagerLayoutCalculationWithHScrollAsyncModeOff() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().hScrollAsyncMode(false).rangeRatio(10f).build(),
            )
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.measure(
              Size(),
              makeSizeSpec(200, EXACTLY),
              makeSizeSpec(200, EXACTLY),
              null,
          )
        },
    )

    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(5)

    assertThat(recyclerBinder.getComponentTreeHolderAt(0).hasCompletedLatestLayout()).isTrue()
    assertThat(recyclerBinder.getComponentTreeHolderAt(1).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(2).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(3).hasCompletedLatestLayout()).isFalse()
    assertThat(recyclerBinder.getComponentTreeHolderAt(4).hasCompletedLatestLayout()).isFalse()
  }

  @Test
  fun testAsyncBatchesAppliedAfterMeasureWithEarlyExit() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(10f).build())
            .build(componentContext)
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.measure(
              Size(),
              makeSizeSpec(200, EXACTLY),
              makeSizeSpec(200, EXACTLY),
              null,
          )

          // Second measure will early exit
          recyclerBinder.measure(
              Size(),
              makeSizeSpec(200, EXACTLY),
              makeSizeSpec(200, EXACTLY),
              null,
          )
        },
    )

    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(5)
  }

  @Test
  fun testDoNotApplyReadyBatchesWhileRecyclerViewIsInScrollOrLayout() {
    ShadowLooper.pauseMainLooper()

    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(
                LinearLayoutInfo(
                    componentContext.androidContext,
                    OrientationHelper.VERTICAL,
                    false,
                ),
            )
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(10f).build())
            .build(componentContext)
    val recyclerView = mock(RecyclerView::class.java)
    whenever(recyclerView.isComputingLayout()).thenReturn(true)
    recyclerBinder.mount(recyclerView)

    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.measure(Size(), makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY), null)
    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runToEndOfTasks()

    // Run for a bit -- runTasks causes the test to hang because of how ShadowLooper is implemented
    for (i in 0 until 10) {
      ShadowLooper.runMainLooperOneTask()
    }

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)

    whenever(recyclerView.isComputingLayout()).thenReturn(false)

    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(5)
  }

  @Test
  fun testApplyReadyBatchesInfiniteLoop() {
    expectedException.expect(RuntimeException::class.java)
    expectedException.expectMessage(
        "Too many retries -- RecyclerView is stuck in layout. Batch size: 1, isSubAdapter: false," +
            " isAttachedToWindow: false",
    )

    ShadowLooper.pauseMainLooper()

    val recyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(
                LinearLayoutInfo(
                    componentContext.androidContext,
                    OrientationHelper.VERTICAL,
                    false,
                ),
            )
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(10f).build())
            .build(componentContext)
    val recyclerView = mock(RecyclerView::class.java)
    whenever(recyclerView.isComputingLayout()).thenReturn(true)
    recyclerBinder.mount(recyclerView)

    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 5) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.measure(Size(), makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY), null)
    recyclerBinder.insertRangeAtAsync(0, renderInfos)
    recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    runToEndOfTasks()

    for (i in 0 until 10000) {
      ShadowLooper.runMainLooperOneTask()
    }
  }

  @Test
  fun testApplyReadyBatchesMultipleTimesInSameFrameDoesNotTriggerRetryException() {
    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .build(componentContext)
    val recyclerView = ComputingLayoutRecyclerView(componentContext.androidContext)

    recyclerBinder.mount(recyclerView)
    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerView.setComputingLayout(true)

    ShadowLooper.pauseMainLooper()

    for (i in 0 until RecyclerBinder.APPLY_READY_BATCHES_RETRY_LIMIT + 1) {
      recyclerBinder.insertItemAtAsync(
          0,
          ComponentRenderInfo.create().component(SimpleMountSpecTester()).build(),
      )
      recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }

    recyclerView.setComputingLayout(false)

    ShadowLooper.idleMainLooper()

    assertThat(recyclerBinder.getItemCount())
        .isEqualTo(RecyclerBinder.APPLY_READY_BATCHES_RETRY_LIMIT + 1)
  }

  @Test
  fun tesLayoutAsyncInRegisterAsyncInsertWhenRemesureIsNotNeeded() {
    val NUM_TO_INSERT = 5

    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.insertRangeAtAsync(0, renderInfos)
          recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
        },
    )

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)

    for (i in 0 until NUM_TO_INSERT) {
      val holder = checkNotNull(holdersForComponents[renderInfos[i].component])
      assertThat(holder.mLayoutAsyncCalled).isTrue()
    }
  }

  @Test
  fun testNoLayoutAsyncInRegisterAsyncInsertWhenRemesureIsNeeded() {
    val NUM_TO_INSERT = 5

    val components = ArrayList<Component>()
    val renderInfos = ArrayList<RenderInfo>()
    for (i in 0 until NUM_TO_INSERT) {
      val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
      components.add(component)
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }

    @Suppress("UNCHECKED_CAST")
    recyclerBinder.measure(
        Size(),
        makeSizeSpec(1000, AT_MOST),
        makeSizeSpec(1000, EXACTLY),
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    runOnBackgroundThreadSync(
        Runnable {
          recyclerBinder.insertRangeAtAsync(0, renderInfos)
          recyclerBinder.notifyChangeSetCompleteAsync(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
        },
    )

    assertThat(recyclerBinder.getItemCount()).isEqualTo(0)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(recyclerBinder.getItemCount()).isEqualTo(NUM_TO_INSERT)

    for (i in 0 until NUM_TO_INSERT) {
      val holder = checkNotNull(holdersForComponents[renderInfos[i].component])
      assertThat(holder.mLayoutAsyncCalled).isEqualTo(false)
    }
  }

  @Test
  fun binder_changeViewPort_onAttachedAndOnDetachedAreCalled() {
    val childHeightPx = 20
    val widthPx = 200
    val heightPx = 200

    recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val rv = mock(RecyclerView::class.java)
    recyclerBinder.mount(rv)

    val renderInfos = ArrayList<RenderInfo>()
    val steps = ArrayList<String>()
    for (i in 0 until 200) {
      val component =
          Wrapper.create(componentContext)
              .delegate(AttachDetachTester(steps, "root:$i", null, null, null, null))
              .heightPx(childHeightPx)
              .build()
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(widthPx, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(heightPx, SizeSpec.EXACTLY)
    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    val rangeSize = heightPx / childHeightPx
    val rangeStart = 0
    val rangeTotal = (rangeSize + (RANGE_RATIO * rangeSize)).toInt()

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    val expectedSteps = ArrayList<String>()
    for (i in rangeStart..rangeStart + rangeTotal) {
      expectedSteps.add("root:$i:${AttachDetachTester.ON_ATTACHED}")
    }
    assertThat(steps)
        .describedAs("Should call @OnAttached method for components in visible range")
        .isEqualTo(expectedSteps)

    steps.clear()

    // Change viewport and check @OnDetached methods are called for components out of layout range.
    val newRangeStart = 100
    val newRangeEnd = newRangeStart + rangeSize
    recyclerBinder.onNewVisibleRange(newRangeStart, newRangeEnd)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    expectedSteps.clear()
    for (i in rangeStart..rangeStart + rangeTotal) {
      expectedSteps.add("root:$i:${AttachDetachTester.ON_DETACHED}")
    }
    val offset = (RANGE_RATIO * rangeSize).toInt()
    for (i in newRangeStart - offset..newRangeEnd + offset) {
      expectedSteps.add("root:$i:${AttachDetachTester.ON_ATTACHED}")
    }
    assertThat(steps)
        .describedAs(
            "Should call @OnDetached for components not in visible range and call @OnAttached" +
                " method for components in visible range",
        )
        .isEqualTo(expectedSteps)
  }

  @Test
  fun binder_detach_onDetachedIsCalled() {
    val childHeightPx = 20
    val widthPx = 200
    val heightPx = 200

    recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val rv = mock(RecyclerView::class.java)
    recyclerBinder.mount(rv)

    val renderInfos = ArrayList<RenderInfo>()
    val steps = ArrayList<String>()
    for (i in 0 until 200) {
      val component =
          Wrapper.create(componentContext)
              .delegate(AttachDetachTester(steps, "root:$i", null, null, null, null))
              .heightPx(childHeightPx)
              .build()
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(widthPx, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(heightPx, SizeSpec.EXACTLY)
    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    steps.clear()

    recyclerBinder.detach()

    val rangeSize = heightPx / childHeightPx
    val rangeStart = 0
    val rangeTotal = (rangeSize + (RANGE_RATIO * rangeSize)).toInt()
    val expectedSteps = ArrayList<String>()
    for (i in rangeStart..rangeStart + rangeTotal) {
      expectedSteps.add("root:$i:${AttachDetachTester.ON_DETACHED}")
    }
    assertThat(steps)
        .describedAs("Should call @OnDetached for components when binder is detached")
        .isEqualTo(expectedSteps)
  }

  @Test
  fun binder_removeItem_onDetachedIsCalled() {
    val childHeightPx = 20
    val widthPx = 200
    val heightPx = 200

    recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val rv = mock(RecyclerView::class.java)
    recyclerBinder.mount(rv)

    val renderInfos = ArrayList<RenderInfo>()
    val steps = ArrayList<String>()
    for (i in 0 until 200) {
      val component =
          Wrapper.create(componentContext)
              .delegate(AttachDetachTester(steps, "root:$i", null, null, null, null))
              .heightPx(childHeightPx)
              .build()
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(widthPx, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(heightPx, SizeSpec.EXACTLY)
    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    steps.clear()

    // Remove item at index 0.
    recyclerBinder.removeItemAt(0)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(steps)
        .describedAs("Should call @OnDetached for component when it's removed")
        .isEqualTo(listOf("root:0:${AttachDetachTester.ON_DETACHED}"))
  }

  @Test
  fun binder_removeRange_onDetachedIsCalled() {
    val childHeightPx = 20
    val widthPx = 200
    val heightPx = 200

    recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val rv = mock(RecyclerView::class.java)
    recyclerBinder.mount(rv)

    val renderInfos = ArrayList<RenderInfo>()
    val steps = ArrayList<String>()
    for (i in 0 until 200) {
      val component =
          Wrapper.create(componentContext)
              .delegate(AttachDetachTester(steps, "root:$i", null, null, null, null))
              .heightPx(childHeightPx)
              .build()
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    val widthSpec = SizeSpec.makeSizeSpec(widthPx, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(heightPx, SizeSpec.EXACTLY)
    recyclerBinder.measure(size, widthSpec, heightSpec, null)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    steps.clear()

    // Remove first 5 items.
    val removeItemCount = 5
    recyclerBinder.removeRangeAt(0, removeItemCount)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    val expectedSteps = ArrayList<String>()
    for (i in 0 until removeItemCount) {
      expectedSteps.add("root:$i:${AttachDetachTester.ON_DETACHED}")
    }
    assertThat(steps)
        .describedAs("Should call @OnDetached for components when they're removed")
        .isEqualTo(expectedSteps)
  }

  @Test
  fun binder_replaceAll_onDetachedIsCalled() {
    val childHeightPx = 20
    val widthPx = 200
    val heightPx = 200

    recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .build(componentContext)
    val rv = mock(RecyclerView::class.java)
    recyclerBinder.mount(rv)

    val renderInfos = ArrayList<RenderInfo>()
    val steps = ArrayList<String>()
    for (i in 0 until 200) {
      val component =
          Wrapper.create(componentContext)
              .delegate(AttachDetachTester(steps, "root:$i", null, null, null, null))
              .heightPx(childHeightPx)
              .build()
      renderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.insertRangeAt(0, renderInfos)
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val outSize = Size()
    val widthSpec = SizeSpec.makeSizeSpec(widthPx, SizeSpec.EXACTLY)
    val heightSpec = SizeSpec.makeSizeSpec(heightPx, SizeSpec.EXACTLY)
    recyclerBinder.measure(outSize, widthSpec, heightSpec, null)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    steps.clear()

    // Replace all items.
    val newRenderInfos = ArrayList<RenderInfo>()
    for (i in 0 until 200) {
      val component =
          Wrapper.create(componentContext)
              .delegate(AttachDetachTester(steps, "newRoot:$i", null, null, null, null))
              .heightPx(childHeightPx)
              .build()
      newRenderInfos.add(ComponentRenderInfo.create().component(component).build())
    }
    recyclerBinder.replaceAll(newRenderInfos)

    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    val rangeSize = heightPx / childHeightPx
    val rangeStart = 0
    val rangeTotal = (rangeSize + (RANGE_RATIO * rangeSize)).toInt()
    val expectedSteps = ArrayList<String>()
    for (i in rangeStart..rangeStart + rangeTotal) {
      expectedSteps.add("root:$i:${AttachDetachTester.ON_DETACHED}")
    }
    assertThat(steps)
        .describedAs("Should call @OnDetached for components when they're replaced")
        .isEqualTo(expectedSteps)
  }

  @Test
  fun testSetBgPaddingInfo() {
    val layoutManager = LinearLayoutManagerWithPadding(componentContext.androidContext)
    val layoutInfo = LinearLayoutInfo(layoutManager)

    recyclerBinder = RecyclerBinder.Builder().layoutInfo(layoutInfo).build(componentContext)
    val rv = mock(RecyclerView::class.java)
    whenever(rv.paddingLeft).thenReturn(10)
    whenever(rv.paddingRight).thenReturn(10)
    whenever(rv.paddingTop).thenReturn(10)
    whenever(rv.paddingBottom).thenReturn(10)

    recyclerBinder.mount(rv)

    assertThat(layoutManager.getBgPaddingInfo()).isEqualTo(Rect(10, 10, 10, 10))
  }

  @Test
  fun testIncrementalMountDisabledWhenParentIncrementalMountIsDisabled() {
    val parentContext = ComponentContext(componentContext.androidContext)
    val parent =
        ComponentTree.create(componentContext, SimpleMountSpecTester())
            .incrementalMount(false)
            .build()

    assertThat(ComponentContext.isIncrementalMountEnabled(parent.context)).isFalse()

    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .build(parent.context)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAt(0, SimpleMountSpecTester())
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val childTree = recyclerBinder.getComponentAt(0)
    assertThat(childTree).isNotNull()
    assertThat(childTree!!.isIncrementalMountEnabled).isFalse()
  }

  @Test
  fun testIncrementalMountEnabledWhenParentIncrementalMountIsEnabled() {
    val parentContext = ComponentContext(componentContext.androidContext)
    val parent =
        ComponentTree.create(componentContext, SimpleMountSpecTester())
            .incrementalMount(true)
            .build()

    assertThat(ComponentContext.isIncrementalMountEnabled(parent.context)).isTrue()

    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(LinearLayoutInfo(componentContext, OrientationHelper.HORIZONTAL, false))
            .build(parent.context)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAt(0, SimpleMountSpecTester())
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val childTree = recyclerBinder.getComponentAt(0)
    assertThat(childTree).isNotNull()
    assertThat(childTree!!.isIncrementalMountEnabled).isTrue()
  }

  @Test
  fun testTurnOnStableId() {
    val parentContext = ComponentContext(componentContext.androidContext)
    val parent = ComponentTree.create(componentContext, SimpleMountSpecTester()).build()

    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().enableStableIds(true).build())
            .build(parent.context)

    assertThat(recyclerBinder.internalAdapter.hasStableIds()).isEqualTo(true)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAt(0, SimpleMountSpecTester())
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // the item id should be equal to the stable id generated by ComponentTreeHolder
    val holder = recyclerBinder.getComponentTreeHolderAt(0)
    assertThat(recyclerBinder.internalAdapter.getItemId(0)).isEqualTo(holder.id.toLong())
  }

  @Test
  fun testTurnOnStableIdAndCircular() {
    val parentContext = ComponentContext(componentContext.androidContext)
    val parent = ComponentTree.create(componentContext, SimpleMountSpecTester()).build()

    val recyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().enableStableIds(true).isCircular(true).build(),
            )
            .build(parent.context)

    assertThat(recyclerBinder.internalAdapter.hasStableIds()).isEqualTo(false)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)
    recyclerBinder.insertItemAt(0, SimpleMountSpecTester())
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    // the item id should be equal to `RecyclerView.NO_ID`
    assertThat(recyclerBinder.internalAdapter.getItemId(0)).isEqualTo(RecyclerView.NO_ID)
  }

  @Test
  fun testParentWidthHeightPercent() {
    val parentContext = ComponentContext(componentContext.androidContext)
    val parent = ComponentTree.create(componentContext, SimpleMountSpecTester()).build()

    val recyclerBinder =
        RecyclerBinder.Builder()
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(parent.context)

    val component = SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build())
    recyclerBinder.insertItemAt(
        0,
        ComponentRenderInfo.create()
            .component(component)
            .parentWidthPercent(50f)
            .parentHeightPercent(30f)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    recyclerBinder.measure(Size(), makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY), null)

    val componentTreeHolder = checkNotNull(holdersForComponents[component])
    assertThat(componentTreeHolder.mChildWidth).isEqualTo(500)
    assertThat(componentTreeHolder.mChildHeight).isEqualTo(300)

    // to verify we would compute the new size based on the parent width and height
    recyclerBinder.measure(Size(), makeSizeSpec(1080, EXACTLY), makeSizeSpec(1900, EXACTLY), null)
    assertThat(componentTreeHolder.mChildWidth).isEqualTo(540)
    assertThat(componentTreeHolder.mChildHeight).isEqualTo(570)

    // to verify we would ignore invalid inputs, which should be a value between [0-100]
    recyclerBinder.updateItemAt(
        0,
        ComponentRenderInfo.create()
            .component(component)
            .parentWidthPercent(-1f) // -1 is not valid
            .parentHeightPercent(10f)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    recyclerBinder.measure(Size(), makeSizeSpec(1080, EXACTLY), makeSizeSpec(1900, EXACTLY), null)
    assertThat(componentTreeHolder.mChildWidth).isEqualTo(1080)
    assertThat(componentTreeHolder.mChildHeight).isEqualTo(190)

    recyclerBinder.updateItemAt(
        0,
        ComponentRenderInfo.create()
            .component(component)
            .parentWidthPercent(110f) // 110 is not valid
            .parentHeightPercent(50f)
            .build(),
    )
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    recyclerBinder.measure(Size(), makeSizeSpec(1080, EXACTLY), makeSizeSpec(1900, EXACTLY), null)
    assertThat(componentTreeHolder.mChildWidth).isEqualTo(1080)
    assertThat(componentTreeHolder.mChildHeight).isEqualTo(950)
  }

  // region isGridLayoutInfo / AT_MOST cross-axis sizing fix

  @Test
  fun `isGridLayoutInfo returns true for GridLayoutInfo and StaggeredGridLayoutInfo only`() {
    assertThat(RecyclerBinder.isGridLayoutInfo(GridLayoutInfo(componentContext.androidContext, 2)))
        .isTrue
    assertThat(
        RecyclerBinder.isGridLayoutInfo(
            StaggeredGridLayoutInfo(
                2,
                OrientationHelper.VERTICAL,
                false,
                StaggeredGridLayoutManager.GAP_HANDLING_NONE,
            ),
        ),
    )
        .isTrue
    assertThat(
        RecyclerBinder.isGridLayoutInfo(
            LinearLayoutInfo(componentContext.androidContext, OrientationHelper.VERTICAL, false),
        ),
    )
        .isFalse
  }

  @Test
  fun `vertical GridLayoutInfo measures full width under AT_MOST cross-axis constraint`() {
    // Reproduces the bug where a 2-column grid under AT_MOST(200) would return width=100
    // (a single item's width) instead of 200 (the full available width).
    val spanCount = 2
    val availableWidth = 200
    val gridRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(GridLayoutInfo(componentContext.androidContext, spanCount))
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(componentContext)

    for (i in 0 until 4) {
      gridRecyclerBinder.insertItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    gridRecyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    @Suppress("UNCHECKED_CAST")
    gridRecyclerBinder.measure(
        size,
        makeSizeSpec(availableWidth, AT_MOST),
        makeSizeSpec(1000, EXACTLY),
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    // Should report the full available width, not the width of a single column (100).
    assertThat(size.width).isEqualTo(availableWidth)
  }

  @Test
  fun `vertical StaggeredGridLayoutInfo measures full width under AT_MOST cross-axis constraint`() {
    val spanCount = 2
    val availableWidth = 200
    val staggeredRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(
                StaggeredGridLayoutInfo(
                    spanCount,
                    OrientationHelper.VERTICAL,
                    false,
                    StaggeredGridLayoutManager.GAP_HANDLING_NONE,
                ),
            )
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(componentContext)

    for (i in 0 until 4) {
      staggeredRecyclerBinder.insertItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    staggeredRecyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    @Suppress("UNCHECKED_CAST")
    staggeredRecyclerBinder.measure(
        size,
        makeSizeSpec(availableWidth, AT_MOST),
        makeSizeSpec(1000, EXACTLY),
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    // Should report the full available width, not the width of a single column (100).
    assertThat(size.width).isEqualTo(availableWidth)
  }

  @Test
  fun `horizontal GridLayoutInfo measures full height under AT_MOST cross-axis constraint`() {
    val spanCount = 2
    val availableHeight = 200
    val gridRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(
                GridLayoutInfo(
                    componentContext.androidContext,
                    spanCount,
                    OrientationHelper.HORIZONTAL,
                ),
            )
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(componentContext)

    for (i in 0 until 4) {
      gridRecyclerBinder.insertItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    gridRecyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    @Suppress("UNCHECKED_CAST")
    gridRecyclerBinder.measure(
        size,
        makeSizeSpec(1000, EXACTLY),
        makeSizeSpec(availableHeight, AT_MOST),
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    // Should report the full available height, not the height of a single row (100).
    assertThat(size.height).isEqualTo(availableHeight)
  }

  @Test
  fun `horizontal StaggeredGridLayoutInfo measures full height under AT_MOST cross-axis constraint`() {
    val spanCount = 2
    val availableHeight = 200
    val staggeredRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(
                StaggeredGridLayoutInfo(
                    spanCount,
                    OrientationHelper.HORIZONTAL,
                    false,
                    StaggeredGridLayoutManager.GAP_HANDLING_NONE,
                ),
            )
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(componentContext)

    for (i in 0 until 4) {
      staggeredRecyclerBinder.insertItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    staggeredRecyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    @Suppress("UNCHECKED_CAST")
    staggeredRecyclerBinder.measure(
        size,
        makeSizeSpec(1000, EXACTLY),
        makeSizeSpec(availableHeight, AT_MOST),
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    // Should report the full available height, not the height of a single row (100).
    assertThat(size.height).isEqualTo(availableHeight)
  }

  @Test
  fun `grid measures full cross-axis size under AT_MOST even with empty list`() {
    // Verifies that when sizeForMeasure is null (no items measured yet), the grid still
    // reports getSize(parentSpec) rather than falling through to 0.
    val availableWidth = 200
    val gridRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(GridLayoutInfo(componentContext.androidContext, 2))
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(componentContext)

    // Intentionally insert no items — sizeForMeasure will be null.
    gridRecyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    @Suppress("UNCHECKED_CAST")
    gridRecyclerBinder.measure(
        size,
        makeSizeSpec(availableWidth, AT_MOST),
        makeSizeSpec(1000, EXACTLY),
        mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>,
    )

    assertThat(size.width).isEqualTo(availableWidth)
  }

  @Test
  fun `vertical grid measures correctly under EXACTLY cross-axis constraint`() {
    // Baseline: EXACTLY mode was never broken, but document it explicitly so the full
    // spec-mode matrix (EXACTLY / AT_MOST) is covered and regressions are caught.
    val availableWidth = 200
    val gridRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(GridLayoutInfo(componentContext.androidContext, 2))
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(componentContext)

    for (i in 0 until 4) {
      gridRecyclerBinder.insertItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    gridRecyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    gridRecyclerBinder.measure(
        size,
        makeSizeSpec(availableWidth, EXACTLY),
        makeSizeSpec(1000, EXACTLY),
        null,
    )

    assertThat(size.width).isEqualTo(availableWidth)
  }

  @Test
  fun `horizontal grid measures correctly under EXACTLY cross-axis constraint`() {
    // Baseline: EXACTLY mode was never broken, but document it explicitly so the full
    // spec-mode matrix (EXACTLY / AT_MOST) is covered and regressions are caught.
    val availableHeight = 200
    val gridRecyclerBinder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(
                GridLayoutInfo(componentContext.androidContext, 2, OrientationHelper.HORIZONTAL),
            )
            .componentTreeHolderFactory(componentTreeHolderFactory)
            .build(componentContext)

    for (i in 0 until 4) {
      gridRecyclerBinder.insertItemAt(
          i,
          ComponentRenderInfo.create().component(mock(Component::class.java)).build(),
      )
    }
    gridRecyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK)

    val size = Size()
    gridRecyclerBinder.measure(
        size,
        makeSizeSpec(1000, EXACTLY),
        makeSizeSpec(availableHeight, EXACTLY),
        null,
    )

    assertThat(size.height).isEqualTo(availableHeight)
  }

  // endregion
}
