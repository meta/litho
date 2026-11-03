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

import androidx.annotation.Px
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.facebook.litho.ComponentContext
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.PreAllocationHandler
import com.facebook.litho.sections.widget.GridLayoutInfoFactory
import com.facebook.litho.sections.widget.GridRecyclerConfiguration
import com.facebook.litho.sections.widget.LinearLayoutInfoFactory
import com.facebook.litho.sections.widget.ListRecyclerConfiguration
import com.facebook.litho.sections.widget.RecyclerBinderConfiguration
import com.facebook.litho.sections.widget.RecyclerConfiguration
import com.facebook.litho.sections.widget.StaggeredGridLayoutInfoFactory
import com.facebook.litho.sections.widget.StaggeredGridRecyclerConfiguration
import com.facebook.litho.widget.RecyclerBinderConfig
import com.facebook.litho.widget.SnapUtil

/**
 * Provide layout configuration options to a [Collection]
 *
 * @param orientation @see [RecyclerView.Orientation]
 * @param reverse Reverse item traversal and layout
 *   order @see [LinearLayoutManager#setReverseLayout]
 * @param rangeRatio @see [RecyclerBinderConfig.rangeRatio]
 */
abstract class CollectionLayout(
    componentContext: ComponentContext,
    @RecyclerView.Orientation orientation: Int,
    reverse: Boolean,
    rangeRatio: Float? = null,
    estimatedViewportCount: Int? = null,
    useBackgroundChangeSets: Boolean = false,
    isIncrementalMountEnabled: Boolean =
        componentContext.lithoConfiguration.componentsConfig.incrementalMountEnabled,
    mainAxisWrapContent: Boolean = false,
    crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
    preAllocationHandler: PreAllocationHandler?,
    isCircular: Boolean,
    enableStableIds: Boolean,
) {
  internal abstract fun createRecyclerConfigurationBuilder(): RecyclerConfiguration.Builder

  val recyclerConfiguration: RecyclerConfiguration =
      createRecyclerConfigurationBuilder()
          .orientation(orientation)
          .reverseLayout(reverse)
          .recyclerBinderConfiguration(
              RecyclerBinderConfiguration.create()
                  .recyclerBinderConfig(
                      RecyclerBinderConfig(
                          componentsConfiguration =
                              ComponentsConfiguration.create(
                                      componentContext.lithoConfiguration.componentsConfig,
                                  )
                                  .withPreAllocationHandler(preAllocationHandler)
                                  .incrementalMountEnabled(isIncrementalMountEnabled)
                                  .build(),
                          rangeRatio = rangeRatio ?: RecyclerBinderConfig.DEFAULT_RANGE_RATIO,
                          estimatedViewportCount = estimatedViewportCount,
                          wrapContent = mainAxisWrapContent,
                          crossAxisWrapMode = crossAxisWrapMode,
                          isCircular = isCircular,
                          enableStableIds = enableStableIds,
                      ),
                  )
                  .useBackgroundChangeSets(useBackgroundChangeSets)
                  .build(),
          )
          .build()

  val isVertical: Boolean = orientation == RecyclerView.VERTICAL
}

/** Provide [CollectionLayout]s that can be applied to [Collection]'s `layout` parameter. */
@PublishedApi
internal object CollectionLayouts {

  /**
   * Provide layout configuration options for a linear [Collection].
   *
   * @param orientation @see CollectionLayout
   * @param snapMode @see CollectionLayout
   * @param snapToStartOffset Attempt to offset the child by this number of pixels from the start of
   *   the Collection when snapMode = SNAP_TO_START.
   * @param reverse @see CollectionLayout
   * @param rangeRatio @see [RecyclerBinderConfig.rangeRatio]
   * @param estimatedViewportCount @see [RecyclerBinderConfig.estimatedViewportCount], it's
   *   deprecated and only used for migration purpose.
   * @param crossAxisWrapMode Specify how the [Collection] will wrap its contents across the main
   *   axis.
   * @param mainAxisWrapContent If set, the size of the [Collection] along the main axis will match
   *   the size of its children
   * @param preAllocationHandler - if set, it will attempt to preallocate the mount content after
   *   the hierarchy is resolved. It will only do it if the root ComponentTree has set a
   *   preallocation handler.
   */
  fun Linear(
      componentContext: ComponentContext,
      @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
      @SnapUtil.SnapMode snapMode: Int = SnapUtil.SNAP_NONE,
      @Px snapToStartOffset: Int = 0,
      reverse: Boolean = false,
      rangeRatio: Float? = null,
      estimatedViewportCount: Int? = null,
      useBackgroundChangeSets: Boolean = false,
      isIncrementalMountEnabled: Boolean =
          componentContext.lithoConfiguration.componentsConfig.incrementalMountEnabled,
      crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
      mainAxisWrapContent: Boolean = false,
      preAllocationHandler: PreAllocationHandler?,
      isCircular: Boolean,
      enableStableIds: Boolean,
      linearLayoutInfoFactory: LinearLayoutInfoFactory?,
      snapHelper: SnapHelper?,
  ): CollectionLayout =
      object :
          CollectionLayout(
              componentContext = componentContext,
              orientation = orientation,
              reverse = reverse,
              rangeRatio = rangeRatio,
              estimatedViewportCount = estimatedViewportCount,
              useBackgroundChangeSets = useBackgroundChangeSets,
              isIncrementalMountEnabled = isIncrementalMountEnabled,
              mainAxisWrapContent = mainAxisWrapContent,
              crossAxisWrapMode = crossAxisWrapMode,
              preAllocationHandler = preAllocationHandler,
              isCircular = isCircular,
              enableStableIds = enableStableIds,
          ) {
        override fun createRecyclerConfigurationBuilder(): RecyclerConfiguration.Builder =
            ListRecyclerConfiguration.create()
                .snapMode(snapMode)
                .linearLayoutInfoFactory(linearLayoutInfoFactory)
                .snapToStartOffset(snapToStartOffset)
                .also {
                  if (snapHelper != null) {
                    it.snapHelper(snapHelper)
                  }
                }
      }

  /**
   * Provide layout configuration options for a grid [Collection].
   *
   * @param orientation @see CollectionLayout
   * @param snapMode @see CollectionLayout
   * @param reverse @see CollectionLayout
   * @param rangeRatio @see [RecyclerBinderConfig.rangeRatio]
   * @param estimatedViewportCount @see [RecyclerBinderConfig.estimatedViewportCount], it's
   *   deprecated and only used for migration purpose.
   * @param columns Number of columns in the grid
   * @param preAllocationHandler - if set, it will attempt to preallocate the mount content after
   *   the hierarchy is resolved. It will only do it if the root ComponentTree has set a
   *   preallocation handler.
   */
  fun Grid(
      componentContext: ComponentContext,
      @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
      @SnapUtil.SnapMode snapMode: Int = SnapUtil.SNAP_NONE,
      @Px snapToStartOffset: Int = 0,
      reverse: Boolean = false,
      rangeRatio: Float? = null,
      estimatedViewportCount: Int? = null,
      useBackgroundChangeSets: Boolean = false,
      isIncrementalMountEnabled: Boolean =
          componentContext.lithoConfiguration.componentsConfig.incrementalMountEnabled,
      columns: Int = 2,
      preAllocationHandler: PreAllocationHandler?,
      enableStableIds: Boolean,
      mainAxisWrapContent: Boolean = false,
      gridLayoutInfoFactory: GridLayoutInfoFactory?,
  ): CollectionLayout =
      object :
          CollectionLayout(
              componentContext = componentContext,
              orientation = orientation,
              reverse = reverse,
              rangeRatio = rangeRatio,
              estimatedViewportCount = estimatedViewportCount,
              useBackgroundChangeSets = useBackgroundChangeSets,
              isIncrementalMountEnabled = isIncrementalMountEnabled,
              preAllocationHandler = preAllocationHandler,
              isCircular = false,
              enableStableIds = enableStableIds,
              mainAxisWrapContent = mainAxisWrapContent,
          ) {
        override fun createRecyclerConfigurationBuilder(): RecyclerConfiguration.Builder =
            GridRecyclerConfiguration.create()
                .snapMode(snapMode)
                .snapToStartOffset(snapToStartOffset)
                .gridLayoutInfoFactory(gridLayoutInfoFactory)
                .numColumns(columns)
      }

  /**
   * Provide layout configuration options for a staggered grid [Collection].
   *
   * @param orientation @see CollectionLayout
   * @param snapMode @see CollectionLayout
   * @param reverse @see CollectionLayout
   * @param rangeRatio @see [RecyclerBinderConfig.rangeRatio]
   * @param estimatedViewportCount @see [RecyclerBinderConfig.estimatedViewportCount], it's
   *   deprecated and only used for migration purpose.
   * @param spans Number of spans in the grid
   * @param gapStrategy @see [StaggeredGridLayoutManager#setGapStrategy]
   * @param preAllocationHandler - if set, it will attempt to preallocate the mount content after
   *   the hierarchy is resolved. It will only do it if the root ComponentTree has set a
   *   preallocation handler.
   */
  fun StaggeredGrid(
      componentContext: ComponentContext,
      @RecyclerView.Orientation orientation: Int = RecyclerView.VERTICAL,
      reverse: Boolean = false,
      rangeRatio: Float? = null,
      estimatedViewportCount: Int? = null,
      useBackgroundChangeSets: Boolean = false,
      isIncrementalMountEnabled: Boolean =
          componentContext.lithoConfiguration.componentsConfig.incrementalMountEnabled,
      spans: Int = 2,
      gapStrategy: Int = StaggeredGridLayoutManager.GAP_HANDLING_NONE,
      staggeredGridlayoutInfoFactory: StaggeredGridLayoutInfoFactory?,
      preAllocationHandler: PreAllocationHandler?,
      enableStableIds: Boolean,
  ): CollectionLayout =
      object :
          CollectionLayout(
              componentContext = componentContext,
              orientation = orientation,
              reverse = reverse,
              rangeRatio = rangeRatio,
              estimatedViewportCount = estimatedViewportCount,
              useBackgroundChangeSets = useBackgroundChangeSets,
              isIncrementalMountEnabled = isIncrementalMountEnabled,
              preAllocationHandler = preAllocationHandler,
              isCircular = false,
              enableStableIds = enableStableIds,
          ) {
        override fun createRecyclerConfigurationBuilder(): RecyclerConfiguration.Builder =
            StaggeredGridRecyclerConfiguration.create()
                .numSpans(spans)
                .gapStrategy(gapStrategy)
                .staggeredGridLayoutInfoFactory(staggeredGridlayoutInfoFactory)
      }
}
