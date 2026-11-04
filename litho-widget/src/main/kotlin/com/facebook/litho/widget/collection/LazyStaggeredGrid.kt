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

import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.Handle
import com.facebook.litho.LithoStartupLogger
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.config.PreAllocationHandler
import com.facebook.litho.sections.widget.StaggeredGridLayoutInfoFactory
import com.facebook.litho.widget.LithoRecyclerView
import com.facebook.litho.widget.LithoRecyclerView.OnAfterLayoutListener
import com.facebook.litho.widget.LithoRecyclerView.OnBeforeLayoutListener
import com.facebook.litho.widget.SnapUtil
import com.facebook.rendercore.Dimen

/**
 * A scrollable collection of components arranged in a staggered grid.
 *
 * Note: unlike [LazyList] and [LazyGrid], staggered grids do not support snapping. The underlying
 * StaggeredGridRecyclerConfiguration always returns a null SnapHelper (getSnapHelper) and reports
 * SNAP_NONE (getSnapMode); its builder's snapMode() setter throws UnsupportedOperationException. So
 * a `snapToStartOffset` param would be silently ignored and is intentionally omitted. (The
 * `snapMode` param here is likewise not forwarded for the same reason.) See T280764285.
 */
@Suppress("FunctionName")
inline fun ResourcesScope.LazyStaggeredGrid(
    @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
    @SnapUtil.SnapMode snapMode: Int = SnapUtil.SNAP_NONE,
    reverse: Boolean = false,
    spans: Int = 2,
    gapStrategy: Int = StaggeredGridLayoutManager.GAP_HANDLING_NONE,
    itemAnimator: RecyclerView.ItemAnimator? = CollectionRecyclerSpec.itemAnimator,
    itemDecoration: RecyclerView.ItemDecoration? = null,
    clipToPadding: Boolean? = null,
    clipChildren: Boolean? = null,
    startPadding: Dimen? = null,
    endPadding: Dimen? = null,
    topPadding: Dimen? = null,
    bottomPadding: Dimen? = null,
    nestedScrollingEnabled: Boolean? = null,
    scrollBarStyle: Int? = null,
    recyclerViewId: Int? = null,
    overScrollMode: Int? = null,
    @ColorInt refreshProgressBarBackgroundColor: Int? = null,
    @ColorInt refreshProgressBarColor: Int? = null,
    touchInterceptor: LithoRecyclerView.TouchInterceptor? = null,
    itemTouchListener: RecyclerView.OnItemTouchListener? = null,
    sectionTreeTag: String? = null,
    startupLogger: LithoStartupLogger? = null,
    style: Style = Style,
    noinline onViewportChanged:
        ((
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
            totalCount: Int,
            firstFullyVisibleIndex: Int,
            lastFullyVisibleIndex: Int,
        ) -> Unit)? =
        null,
    noinline onDataBound: OnDataBound? = null,
    handle: Handle? = null,
    noinline onPullToRefresh: (() -> Unit)? = null,
    onNearEnd: OnNearCallback? = null,
    onScrollListener: RecyclerView.OnScrollListener? = null,
    onScrollListeners: List<RecyclerView.OnScrollListener?>? = null,
    lazyCollectionController: LazyCollectionController? = null,
    noinline onDataRendered:
        ((
            isDataChanged: Boolean,
            isMounted: Boolean,
            monoTimestampMs: Long,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
        ) -> Unit)? =
        null,
    rangeRatio: Float? = null,
    useBackgroundChangeSets: Boolean = false,
    isIncrementalMountEnabled: Boolean =
        context.lithoConfiguration.componentsConfig.incrementalMountEnabled,
    alwaysDetectDuplicates: Boolean = false,
    isLeftFadingEnabled: Boolean = true,
    isRightFadingEnabled: Boolean = true,
    isTopFadingEnabled: Boolean = true,
    isBottomFadingEnabled: Boolean = true,
    fadingEdgeLength: Dimen? = null,
    preAllocationHandler: PreAllocationHandler? =
        context.lithoConfiguration.componentsConfig.preAllocationHandler,
    shouldExcludeFromIncrementalMount: Boolean = false,
    staggeredGridlayoutInfoFactory: StaggeredGridLayoutInfoFactory? = null,
    enableStableIds: Boolean =
        context.lithoConfiguration.componentsConfig.useStableIdsInRecyclerBinder,
    onBeforeLayout: OnBeforeLayoutListener? = null,
    onAfterLayout: OnAfterLayoutListener? = null,
    /** Selects which collection infra backs this grid. Defaults to the global config. */
    infraOverride: CollectionInfraOverride = CollectionInfraOverride.DEFAULT,
    crossinline init: LazyGridScope.() -> Unit,
): Component {
  val lazyStaggeredGridScope = LazyGridScope(context).apply { init() }
  return LazyCollection(
      layout =
          CollectionLayouts.StaggeredGrid(
              componentContext = context,
              orientation = orientation,
              reverse = reverse,
              rangeRatio = rangeRatio,
              useBackgroundChangeSets = useBackgroundChangeSets,
              isIncrementalMountEnabled = isIncrementalMountEnabled,
              preAllocationHandler = preAllocationHandler,
              staggeredGridlayoutInfoFactory = staggeredGridlayoutInfoFactory,
              spans = spans,
              gapStrategy = gapStrategy,
              enableStableIds = enableStableIds,
          ),
      itemAnimator,
      itemDecoration,
      clipToPadding,
      clipChildren,
      startPadding,
      endPadding,
      topPadding,
      bottomPadding,
      nestedScrollingEnabled,
      scrollBarStyle,
      recyclerViewId,
      overScrollMode,
      refreshProgressBarBackgroundColor,
      refreshProgressBarColor,
      touchInterceptor,
      itemTouchListener,
      sectionTreeTag,
      startupLogger,
      style,
      onViewportChanged,
      handle,
      onPullToRefresh,
      onNearEnd,
      onScrollListener,
      onScrollListeners,
      lazyCollectionController,
      onDataRendered,
      onDataBound,
      alwaysDetectDuplicates,
      isLeftFadingEnabled,
      isRightFadingEnabled,
      isTopFadingEnabled,
      isBottomFadingEnabled,
      fadingEdgeLength,
      shouldExcludeFromIncrementalMount,
      onBeforeLayout,
      onAfterLayout,
      infraOverride = infraOverride,
      lazyCollectionChildren = lazyStaggeredGridScope.children,
  )
}

/**
 * A compatibility wrapper for LazyStaggeredGrid that accepts a ComponentContext parameter instead
 * of being an extension function on ResourcesScope. This function is deprecated and should only be
 * used during KComponent migration.
 */
@Deprecated("Only used for KComponent migration.")
@Suppress("FunctionName")
inline fun LazyStaggeredGridCompat(
    context: ComponentContext,
    @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
    @SnapUtil.SnapMode snapMode: Int = SnapUtil.SNAP_NONE,
    reverse: Boolean = false,
    spans: Int = 2,
    gapStrategy: Int = StaggeredGridLayoutManager.GAP_HANDLING_NONE,
    itemAnimator: RecyclerView.ItemAnimator? = CollectionRecyclerSpec.itemAnimator,
    itemDecoration: RecyclerView.ItemDecoration? = null,
    clipToPadding: Boolean? = null,
    clipChildren: Boolean? = null,
    startPadding: Dimen? = null,
    endPadding: Dimen? = null,
    topPadding: Dimen? = null,
    bottomPadding: Dimen? = null,
    nestedScrollingEnabled: Boolean? = null,
    scrollBarStyle: Int? = null,
    recyclerViewId: Int? = null,
    overScrollMode: Int? = null,
    @ColorInt refreshProgressBarBackgroundColor: Int? = null,
    @ColorInt refreshProgressBarColor: Int? = null,
    touchInterceptor: LithoRecyclerView.TouchInterceptor? = null,
    itemTouchListener: RecyclerView.OnItemTouchListener? = null,
    sectionTreeTag: String? = null,
    startupLogger: LithoStartupLogger? = null,
    style: Style = Style,
    noinline onViewportChanged:
        ((
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
            totalCount: Int,
            firstFullyVisibleIndex: Int,
            lastFullyVisibleIndex: Int,
        ) -> Unit)? =
        null,
    noinline onDataBound: OnDataBound? = null,
    handle: Handle? = null,
    noinline onPullToRefresh: (() -> Unit)? = null,
    onNearEnd: OnNearCallback? = null,
    onScrollListener: RecyclerView.OnScrollListener? = null,
    onScrollListeners: List<RecyclerView.OnScrollListener?>? = null,
    lazyCollectionController: LazyCollectionController? = null,
    noinline onDataRendered:
        ((
            isDataChanged: Boolean,
            isMounted: Boolean,
            monoTimestampMs: Long,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
        ) -> Unit)? =
        null,
    rangeRatio: Float? = null,
    useBackgroundChangeSets: Boolean = false,
    isIncrementalMountEnabled: Boolean =
        context.lithoConfiguration.componentsConfig.incrementalMountEnabled,
    alwaysDetectDuplicates: Boolean = false,
    isLeftFadingEnabled: Boolean = true,
    isRightFadingEnabled: Boolean = true,
    isTopFadingEnabled: Boolean = true,
    isBottomFadingEnabled: Boolean = true,
    fadingEdgeLength: Dimen? = null,
    preAllocationHandler: PreAllocationHandler? =
        context.lithoConfiguration.componentsConfig.preAllocationHandler,
    shouldExcludeFromIncrementalMount: Boolean = false,
    staggeredGridlayoutInfoFactory: StaggeredGridLayoutInfoFactory? = null,
    enableStableIds: Boolean =
        context.lithoConfiguration.componentsConfig.useStableIdsInRecyclerBinder,
    /** Selects which collection infra backs this grid. Defaults to the global config. */
    infraOverride: CollectionInfraOverride = CollectionInfraOverride.DEFAULT,
    onBeforeLayout: OnBeforeLayoutListener? = null,
    onAfterLayout: OnAfterLayoutListener? = null,
    crossinline init: LazyGridScope.() -> Unit,
): Component {
  val lazyStaggeredGridScope = LazyGridScope(context).apply { init() }
  return LazyCollection(
      layout =
          CollectionLayouts.StaggeredGrid(
              componentContext = context,
              orientation = orientation,
              reverse = reverse,
              rangeRatio = rangeRatio,
              useBackgroundChangeSets = useBackgroundChangeSets,
              isIncrementalMountEnabled = isIncrementalMountEnabled,
              preAllocationHandler = preAllocationHandler,
              staggeredGridlayoutInfoFactory = staggeredGridlayoutInfoFactory,
              spans = spans,
              gapStrategy = gapStrategy,
              enableStableIds = enableStableIds,
          ),
      itemAnimator,
      itemDecoration,
      clipToPadding,
      clipChildren,
      startPadding,
      endPadding,
      topPadding,
      bottomPadding,
      nestedScrollingEnabled,
      scrollBarStyle,
      recyclerViewId,
      overScrollMode,
      refreshProgressBarBackgroundColor,
      refreshProgressBarColor,
      touchInterceptor,
      itemTouchListener,
      sectionTreeTag,
      startupLogger,
      style,
      onViewportChanged,
      handle,
      onPullToRefresh,
      onNearEnd,
      onScrollListener,
      onScrollListeners,
      lazyCollectionController,
      onDataRendered,
      onDataBound,
      alwaysDetectDuplicates,
      isLeftFadingEnabled,
      isRightFadingEnabled,
      isTopFadingEnabled,
      isBottomFadingEnabled,
      fadingEdgeLength,
      shouldExcludeFromIncrementalMount,
      onBeforeLayout,
      onAfterLayout,
      infraOverride = infraOverride,
      lazyCollectionChildren = lazyStaggeredGridScope.children,
  )
}
