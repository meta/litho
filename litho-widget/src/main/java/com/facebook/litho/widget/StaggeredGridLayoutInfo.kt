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
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.facebook.litho.LayoutManagerOverrideParams
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.UNSPECIFIED
import com.facebook.litho.SizeSpec.getSize
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.widget.LayoutInfo.RenderInfoCollection
import com.facebook.litho.widget.StaggeredGridLayoutHelper.findFirstFullyVisibleItemPosition
import com.facebook.litho.widget.StaggeredGridLayoutHelper.findFirstVisibleItemPosition
import com.facebook.litho.widget.StaggeredGridLayoutHelper.findLastFullyVisibleItemPosition
import com.facebook.litho.widget.StaggeredGridLayoutHelper.findLastVisibleItemPosition
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.max

/**
 * An implementation for [LayoutInfo] to implement staggered grids with a
 * [StaggeredGridLayoutManager].
 */
open class StaggeredGridLayoutInfo
@JvmOverloads
constructor(
    spanCount: Int,
    orientation: Int,
    reverseLayout: Boolean,
    gapStrategy: Int,
    eagerlyClearsSpanAssignmentsOnUpdates: Boolean = false,
    invalidatesItemDecorationsOnUpdates: Boolean = false,
) : LayoutInfo {

  private val staggeredGridLayoutManager:
      StaggeredGridLayoutManager = LithoStaggeredGridLayoutManager(
      spanCount,
      orientation,
      eagerlyClearsSpanAssignmentsOnUpdates,
      invalidatesItemDecorationsOnUpdates,
  )

  init {
    staggeredGridLayoutManager.setReverseLayout(reverseLayout)
    staggeredGridLayoutManager.setGapStrategy(gapStrategy)
  }

  override fun getScrollDirection(): Int = staggeredGridLayoutManager.orientation

  override fun getLayoutManager(): RecyclerView.LayoutManager = staggeredGridLayoutManager

  override fun setRenderInfoCollection(renderInfoCollection: RenderInfoCollection?) {
    // no op
  }

  override fun scrollToPositionWithOffset(position: Int, offset: Int) {
    staggeredGridLayoutManager.scrollToPositionWithOffset(position, offset)
  }

  override fun approximateRangeSize(
      firstMeasuredItemWidth: Int,
      firstMeasuredItemHeight: Int,
      recyclerMeasuredWidth: Int,
      recyclerMeasuredHeight: Int,
  ): Int {
    val spanCount = staggeredGridLayoutManager.spanCount
    when (staggeredGridLayoutManager.orientation) {
      StaggeredGridLayoutManager.HORIZONTAL -> {
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
    when (staggeredGridLayoutManager.orientation) {
      StaggeredGridLayoutManager.HORIZONTAL -> return makeSizeSpec(0, UNSPECIFIED)
      else -> {
        val overrideWidth = renderInfo.getCustomAttribute(OVERRIDE_SIZE) as Int?
        if (overrideWidth != null) {
          return makeSizeSpec(overrideWidth, EXACTLY)
        }

        val spanCount = staggeredGridLayoutManager.spanCount
        val spanSize = if (renderInfo.isFullSpan) staggeredGridLayoutManager.spanCount else 1

        return makeSizeSpec(spanSize * ((getSize(widthSpec)) / spanCount), EXACTLY)
      }
    }
  }

  /**
   * @param heightSpec the heightSpec used to measure the parent [Recycler].
   * @return heightSpec of a child that is of span size 1
   */
  override fun getChildHeightSpec(heightSpec: Int, renderInfo: RenderInfo): Int {
    when (staggeredGridLayoutManager.orientation) {
      StaggeredGridLayoutManager.HORIZONTAL -> {
        val overrideHeight = renderInfo.getCustomAttribute(OVERRIDE_SIZE) as Int?
        if (overrideHeight != null) {
          return makeSizeSpec(overrideHeight, EXACTLY)
        }

        val spanCount = staggeredGridLayoutManager.spanCount
        val spanSize = if (renderInfo.isFullSpan) staggeredGridLayoutManager.spanCount else 1

        return makeSizeSpec(spanSize * (getSize(heightSpec) / spanCount), EXACTLY)
      }
      else -> return makeSizeSpec(0, UNSPECIFIED)
    }
  }

  override fun createViewportFiller(
      measuredWidth: Int,
      measuredHeight: Int,
  ): LayoutInfo.ViewportFiller = ViewportFiller(
      measuredWidth,
      measuredHeight,
      getScrollDirection(),
      staggeredGridLayoutManager.spanCount,
  )

  override fun findFirstVisibleItemPosition(): Int =
      findFirstVisibleItemPosition(staggeredGridLayoutManager)

  override fun findLastVisibleItemPosition(): Int =
      findLastVisibleItemPosition(staggeredGridLayoutManager)

  override fun findFirstFullyVisibleItemPosition(): Int =
      findFirstFullyVisibleItemPosition(staggeredGridLayoutManager)

  override fun findLastFullyVisibleItemPosition(): Int =
      findLastFullyVisibleItemPosition(staggeredGridLayoutManager)

  override fun getItemCount(): Int = staggeredGridLayoutManager.getItemCount()

  override fun computeWrappedHeight(
      maxHeight: Int,
      componentTreeHolders: List<ComponentTreeHolder>,
  ): Int {
    val itemCount = componentTreeHolders.size
    val spanCount = staggeredGridLayoutManager.spanCount

    var measuredHeight = 0

    when (staggeredGridLayoutManager.orientation) {
      StaggeredGridLayoutManager.VERTICAL -> {
        for (i in 0..<itemCount) {
          measuredHeight +=
              LayoutInfoUtils.getMaxHeightInRow(i, i + spanCount, componentTreeHolders)
          measuredHeight += LayoutInfoUtils.getTopDecorationHeight(staggeredGridLayoutManager, i)
          measuredHeight += LayoutInfoUtils.getBottomDecorationHeight(staggeredGridLayoutManager, i)

          if (measuredHeight > maxHeight) {
            measuredHeight = maxHeight
            break
          }
        }
        return measuredHeight
      }
      StaggeredGridLayoutManager.HORIZONTAL ->
          error("This method should only be called when orientation is vertical")
      else -> error("This method should only be called when orientation is vertical")
    }
  }

  private class LithoStaggeredGridLayoutManager(
      spanCount: Int,
      orientation: Int,
      private val eagerlyClearsSpanAssignmentsOnUpdates: Boolean,
      private val invalidatesItemDecorationsOnUpdates: Boolean,
  ) : StaggeredGridLayoutManager(spanCount, orientation) {
    // We hold this staggered grid result to avoid unnecessary int[] creations.
    private var _staggeredGridResult: IntArray? = null

    // Pairs with mInvalidatesItemDecorationsOnUpdates to store the RecyclerView requiring
    // invalidation, since the RecyclerView isn't available as a member.
    private var recyclerViewToInvalidateItemDecorations = WeakReference<RecyclerView>(null)

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams?): RecyclerView.LayoutParams? {
      return if (lp is RecyclerViewLayoutManagerOverrideParams) {
        LayoutParams(lp)
      } else {
        super.generateLayoutParams(lp)
      }
    }

    override fun onAttachedToWindow(view: RecyclerView) {
      super.onAttachedToWindow(view)
      val layoutManager = this
      // Set a custom accessibility delegate that scrolls the focused item into
      // a comfortable position when TalkBack focuses on items near the edges.
      // StaggeredGridLayoutManager lacks proper accessibility scrolling support,
      // so we ensure the focused item is fully visible with room for adjacent
      // items, enabling continuous swipe navigation.
      view.setAccessibilityDelegateCompat(
          object : RecyclerViewAccessibilityDelegate(view) {
            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                event: AccessibilityEvent,
            ): Boolean {
              if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                val position = view.getChildAdapterPosition(child)
                if (position != RecyclerView.NO_POSITION) {
                  // If the focused child is near the trailing or leading edge of
                  // the RecyclerView's scroll direction, scroll it to a comfortable
                  // offset so adjacent items are revealed for the next swipe. Uses
                  // instant scrollToPositionWithOffset to avoid animation race
                  // conditions.
                  val isVertical = layoutManager.orientation == VERTICAL
                  val viewSize = if (isVertical) view.height else view.width
                  val childStart = if (isVertical) child.top else child.left
                  val childEnd = if (isVertical) child.bottom else child.right
                  val childSize = if (isVertical) child.height else child.width
                  val edgePadding = viewSize / 4
                  if (childEnd >= viewSize - edgePadding) {
                    layoutManager.scrollToPositionWithOffset(position, edgePadding)
                  } else if (childStart <= edgePadding) {
                    layoutManager.scrollToPositionWithOffset(
                        position,
                        viewSize - childSize - edgePadding,
                    )
                  }
                }
              }
              return super.onRequestSendAccessibilityEvent(host, child, event)
            }
          },
      )
    }

    override fun onItemsRemoved(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
      invalidateSpanAssignmentsEagerlyIfNeeded(recyclerView)
      prepareToInvalidateItemDecorationsIfNeeded(recyclerView)

      super.onItemsRemoved(recyclerView, positionStart, itemCount)
    }

    override fun onItemsAdded(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
      invalidateSpanAssignmentsEagerlyIfNeeded(recyclerView)
      prepareToInvalidateItemDecorationsIfNeeded(recyclerView)

      super.onItemsAdded(recyclerView, positionStart, itemCount)
    }

    override fun onItemsMoved(recyclerView: RecyclerView, from: Int, to: Int, itemCount: Int) {
      invalidateSpanAssignmentsEagerlyIfNeeded(recyclerView)
      prepareToInvalidateItemDecorationsIfNeeded(recyclerView)

      super.onItemsMoved(recyclerView, from, to, itemCount)
    }

    override fun onItemsUpdated(
        recyclerView: RecyclerView,
        positionStart: Int,
        itemCount: Int,
        payload: Any?,
    ) {
      invalidateSpanAssignmentsEagerlyIfNeeded(recyclerView)
      prepareToInvalidateItemDecorationsIfNeeded(recyclerView)

      super.onItemsUpdated(recyclerView, positionStart, itemCount, payload)
    }

    override fun onLayoutCompleted(recyclerViewState: RecyclerView.State?) {
      super.onLayoutCompleted(recyclerViewState)

      val recyclerViewToInvalidateItemDecorations = recyclerViewToInvalidateItemDecorations.get()
      if (recyclerViewToInvalidateItemDecorations != null) {
        // Post to ensure we're not in a layout pass (otherwise we'll get an exception for calling
        // this directly inside the layout completion).
        recyclerViewToInvalidateItemDecorations
            .getHandler()
            .postAtFrontOfQueue(
                object : Runnable {
                  override fun run() {
                    if (recyclerViewToInvalidateItemDecorations.isComputingLayout) {
                      return
                    }

                    recyclerViewToInvalidateItemDecorations.invalidateItemDecorations()
                  }
                },
            )
        this.recyclerViewToInvalidateItemDecorations.clear()
      }
    }

    fun prepareToInvalidateItemDecorationsIfNeeded(recyclerView: RecyclerView?) {
      // When an item decorator is called for a staggered layout, the params for span info (index,
      // full width) are retrieved from the view. However, the params may not have been updated yet
      // to reflect the latest ordering and resultant layout changes. As a result, the span indices
      // can be inaccurate and result in broken layouts. Enabling this param  works around (perhaps
      // inefficiently) by invalidating the item decorations on the next successful layout. Then,
      // the values will be updated and the decorations will be applied correctly.
      if (invalidatesItemDecorationsOnUpdates) {
        // The layout completion callback will be invoked on the layout info, but without a
        // reference to the recycler view, so we need to store it ourselves temporarily.
        recyclerViewToInvalidateItemDecorations = WeakReference(recyclerView)
      }
    }

    fun invalidateSpanAssignmentsEagerlyIfNeeded(recyclerView: RecyclerView) {
      // StaggeredGridLayoutManager uses full spans to limit the extent it needs to reflow the rest
      // of a grid layout (it stops after bumping into the next full span). However, there seem to
      // be logical errors with this (or non-trivial bugs in our usage), so this param invalidates
      // the span assignment cache, which avoids the logical errors / crashes. In general, this is
      // likely terrible for performance (unbenchmarked), so it should be enabled with caution.
      if (eagerlyClearsSpanAssignmentsOnUpdates) {
        this.onItemsChanged(recyclerView)
      }
    }

    class LayoutParams(source: RecyclerViewLayoutManagerOverrideParams) :
        StaggeredGridLayoutManager.LayoutParams(source), LayoutManagerOverrideParams {
      override val widthMeasureSpec: Int
      override val heightMeasureSpec: Int

      init {
        isFullSpan = source.isFullSpan
        this.widthMeasureSpec = source.widthMeasureSpec
        this.heightMeasureSpec = source.heightMeasureSpec
      }
    }

    override fun findLastCompletelyVisibleItemPositions(into: IntArray?): IntArray? =
        super.findLastCompletelyVisibleItemPositions(getStaggeredGridResult(into))

    override fun findFirstCompletelyVisibleItemPositions(into: IntArray?): IntArray? =
        super.findLastCompletelyVisibleItemPositions(getStaggeredGridResult(into))

    override fun findLastVisibleItemPositions(into: IntArray?): IntArray? =
        super.findLastVisibleItemPositions(getStaggeredGridResult(into))

    override fun findFirstVisibleItemPositions(into: IntArray?): IntArray? =
        super.findFirstVisibleItemPositions(getStaggeredGridResult(into))

    fun getStaggeredGridResult(into: IntArray?): IntArray {
      var into = into
      if (into == null) {
        into = _staggeredGridResult ?: IntArray(spanCount).also { _staggeredGridResult = it }
      }
      return into
    }
  }

  internal class ViewportFiller(
      private val width: Int,
      private val height: Int,
      private val orientation: Int,
      private val spanCount: Int,
  ) : LayoutInfo.ViewportFiller {
    private var indexOfSpan = 0
    private val fills: IntArray = IntArray(spanCount)
    private var maxFill = 0

    override fun wantsMore(): Boolean {
      val target = if (orientation == OrientationHelper.VERTICAL) height else width
      return maxFill < target
    }

    override fun add(renderInfo: RenderInfo, width: Int, height: Int) {
      fills[indexOfSpan] += if (orientation == OrientationHelper.VERTICAL) height else width
      maxFill = max(maxFill, fills[indexOfSpan])

      if (++indexOfSpan == spanCount) {
        // Reset the index after exceeding the span.
        indexOfSpan = 0
      }
    }

    override fun getFill(): Int = maxFill
  }

  companion object {
    // A custom LayoutInfo param to override the size of an item in the grid. Since
    // StaggeredGridLayoutInfo does not support item decorations offsets on the non scrolling side
    // natively, this can be useful to manually compute the size of an item after such decorations
    // are
    // taken into account.
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
