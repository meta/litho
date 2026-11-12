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
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.LayoutManagerOverrideParams
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.UNSPECIFIED
import com.facebook.litho.SizeSpec.getSize
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.widget.LayoutInfo.RenderInfoCollection
import com.facebook.rendercore.SizeConstraints
import kotlin.math.ceil
import kotlin.math.max

class GridLayoutInfo(private val gridLayoutManager: GridLayoutManager) : LayoutInfo {

  private var renderInfoCollection: RenderInfoCollection? = null
  val sizeConstraintsCache: MutableMap<Any, SizeConstraints> = mutableMapOf()

  init {
    gridLayoutManager.spanSizeLookup = GridSpanSizeLookup()
  }

  @JvmOverloads
  constructor(
      context: Context?,
      spanCount: Int,
      orientation: Int = OrientationHelper.VERTICAL,
      reverseLayout: Boolean = false,
      allowGridMeasuresOverride: Boolean = false,
  ) : this(
      if (allowGridMeasuresOverride)
          GridLayoutManager(context, spanCount, orientation, reverseLayout)
      else LithoGridLayoutManager(context, spanCount, orientation, reverseLayout),
  )

  override fun getScrollDirection(): Int = gridLayoutManager.orientation

  override fun findFirstVisibleItemPosition(): Int =
      gridLayoutManager.findFirstVisibleItemPosition()

  override fun findLastVisibleItemPosition(): Int = gridLayoutManager.findLastVisibleItemPosition()

  override fun findFirstFullyVisibleItemPosition(): Int =
      gridLayoutManager.findFirstCompletelyVisibleItemPosition()

  override fun findLastFullyVisibleItemPosition(): Int =
      gridLayoutManager.findLastCompletelyVisibleItemPosition()

  override fun getItemCount(): Int = gridLayoutManager.getItemCount()

  override fun getLayoutManager(): RecyclerView.LayoutManager = gridLayoutManager

  override fun setRenderInfoCollection(renderInfoCollection: RenderInfoCollection?) {
    this.renderInfoCollection = renderInfoCollection
  }

  override fun scrollToPositionWithOffset(position: Int, offset: Int) {
    gridLayoutManager.scrollToPositionWithOffset(position, offset)
  }

  override fun approximateRangeSize(
      firstMeasuredItemWidth: Int,
      firstMeasuredItemHeight: Int,
      recyclerMeasuredWidth: Int,
      recyclerMeasuredHeight: Int,
  ): Int {
    val spanCount = gridLayoutManager.spanCount
    when (gridLayoutManager.orientation) {
      GridLayoutManager.HORIZONTAL -> {
        val colCount = linesPerViewport(recyclerMeasuredWidth, firstMeasuredItemWidth)

        return colCount * spanCount
      }
      else -> {
        val rowCount = linesPerViewport(recyclerMeasuredHeight, firstMeasuredItemHeight)

        return rowCount * spanCount
      }
    }
  }

  /**
   * @param widthSpec the widthSpec used to measure the parent [Recycler].
   * @return widthSpec of a child that is of span size 1
   */
  override fun getChildWidthSpec(widthSpec: Int, renderInfo: RenderInfo): Int {
    when (gridLayoutManager.orientation) {
      GridLayoutManager.HORIZONTAL -> return makeSizeSpec(0, UNSPECIFIED)
      else -> {
        val overrideWidth = renderInfo.getCustomAttribute(OVERRIDE_SIZE) as Int?
        if (overrideWidth != null) {
          return makeSizeSpec(overrideWidth, EXACTLY)
        }

        if (renderInfo.isFullSpan) {
          return makeSizeSpec(getSize(widthSpec), EXACTLY)
        }

        val spanCount = gridLayoutManager.spanCount
        val spanSize = renderInfo.spanSize

        return makeSizeSpec(spanSize * ((getSize(widthSpec)) / spanCount), EXACTLY)
      }
    }
  }

  /**
   * @param heightSpec the heightSpec used to measure the parent [Recycler].
   * @return heightSpec of a child that is of span size 1
   */
  override fun getChildHeightSpec(heightSpec: Int, renderInfo: RenderInfo): Int {
    when (gridLayoutManager.orientation) {
      GridLayoutManager.HORIZONTAL -> {
        val overrideHeight = renderInfo.getCustomAttribute(OVERRIDE_SIZE) as Int?
        if (overrideHeight != null) {
          return makeSizeSpec(overrideHeight, EXACTLY)
        }

        if (renderInfo.isFullSpan) {
          return makeSizeSpec(getSize(heightSpec), EXACTLY)
        }

        val spanCount = gridLayoutManager.spanCount
        val spanSize = renderInfo.spanSize

        return makeSizeSpec(spanSize * (getSize(heightSpec) / spanCount), EXACTLY)
      }
      else -> return makeSizeSpec(0, UNSPECIFIED)
    }
  }

  override fun createViewportFiller(
      measuredWidth: Int,
      measuredHeight: Int,
  ): LayoutInfo.ViewportFiller? = ViewportFiller(
      measuredWidth,
      measuredHeight,
      getScrollDirection(),
      gridLayoutManager.spanCount,
  )

  override fun computeWrappedHeight(
      maxHeight: Int,
      componentTreeHolders: List<ComponentTreeHolder>,
  ): Int {
    val itemCount = componentTreeHolders.size
    val spanCount = gridLayoutManager.spanCount

    var measuredHeight = 0

    when (gridLayoutManager.orientation) {
      GridLayoutManager.VERTICAL -> {
        for (i in (0..<itemCount) step spanCount) {
          val holder = componentTreeHolders[i]
          val firstRowItemHeight = holder.measuredHeight

          measuredHeight += firstRowItemHeight
          measuredHeight += LayoutInfoUtils.getTopDecorationHeight(gridLayoutManager, i)
          measuredHeight += LayoutInfoUtils.getBottomDecorationHeight(gridLayoutManager, i)

          if (measuredHeight > maxHeight) {
            measuredHeight = maxHeight
            break
          }
        }
        return measuredHeight
      }
      GridLayoutManager.HORIZONTAL ->
          error("This method should only be called when orientation is vertical")
      else -> error("This method should only be called when orientation is vertical")
    }
  }

  private inner class GridSpanSizeLookup : GridLayoutManager.SpanSizeLookup() {
    override fun getSpanSize(position: Int): Int {
      val renderInfo = renderInfoCollection?.getRenderInfoAt(position) ?: return 1
      return if (renderInfo.isFullSpan) gridLayoutManager.spanCount else renderInfo.spanSize
    }
  }

  private class LithoGridLayoutManager(
      context: Context?,
      spanCount: Int,
      orientation: Int,
      reverseLayout: Boolean,
  ) : GridLayoutManager(context, spanCount, orientation, reverseLayout) {
    override fun generateLayoutParams(lp: ViewGroup.LayoutParams?): RecyclerView.LayoutParams? {
      return if (lp is RecyclerViewLayoutManagerOverrideParams) {
        LayoutParams(lp)
      } else {
        super.generateLayoutParams(lp)
      }
    }

    class LayoutParams(source: RecyclerViewLayoutManagerOverrideParams) :
        GridLayoutManager.LayoutParams(source), LayoutManagerOverrideParams {
      override val widthMeasureSpec = source.widthMeasureSpec
      override val heightMeasureSpec = source.heightMeasureSpec
    }
  }

  internal class ViewportFiller(
      private val width: Int,
      private val height: Int,
      private val orientation: Int,
      private val spanCount: Int,
  ) : LayoutInfo.ViewportFiller {
    private var lastFill = 0
    private var maxFill = 0
    private var indexOfSpan = 0

    override fun wantsMore(): Boolean {
      val target = if (orientation == OrientationHelper.VERTICAL) height else width
      return maxFill < target
    }

    override fun add(renderInfo: RenderInfo, width: Int, height: Int) {
      maxFill =
          max(
              maxFill,
              (lastFill + (if (orientation == OrientationHelper.VERTICAL) height else width)),
          )

      if (renderInfo.isFullSpan) {
        indexOfSpan = 0
        lastFill = maxFill
      } else {
        indexOfSpan += renderInfo.spanSize

        if (indexOfSpan == spanCount) {
          // Reset the index after exceeding the span.
          indexOfSpan = 0
          lastFill = maxFill
        }
      }
    }

    override fun getFill(): Int = maxFill
  }

  companion object {
    // A CUSTOM LAYOUTINFO param to override the size of an item in the grid. Since GridLayoutInfo
    // does not support item decorations offsets on the non scrolling side natively,
    // this can be useful to manually compute the size of an item after such decorations are taken
    // into account.
    const val OVERRIDE_SIZE: String = "OVERRIDE_SIZE"

    // Same as LinearLayoutInfo's upper bound; 0 px divides to infinity and overflows the span math.
    private const val ZERO_SIZE_ITEM_LINES: Int = 10

    private fun linesPerViewport(viewportSize: Int, itemSize: Int): Int {
      if (itemSize <= 0 && ComponentsConfiguration.saneGridRangeEstimateForZeroSizeItems) {
        return ZERO_SIZE_ITEM_LINES
      }
      return ceil(viewportSize.toDouble() / itemSize.toDouble()).toInt()
    }
  }
}
