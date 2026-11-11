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

import androidx.annotation.UiThread
import androidx.core.view.isGone
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.ComponentsSystrace
import com.facebook.litho.ThreadUtils
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.debug.CollectionRangeTrigger
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.widget.LayoutInfo
import com.facebook.litho.widget.RecyclerRangeTraverser
import com.facebook.litho.widget.ViewportInfo
import com.facebook.litho.widget.ViewportInfo.ViewportChanged
import com.facebook.litho.widget.ViewportManager
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventDispatcher
import kotlin.math.max
import kotlin.math.min

/**
 * This class is responsible for preparing the items in the collection. It will prepare the items
 * that are in the viewport and the items that are in the range of the viewport.
 */
@ExperimentalLithoApi
class CollectionPreparationManager
@PublishedApi
internal constructor(
    private val layoutInfo: LayoutInfo,
    private val adapter: CollectionPrimitiveViewAdapter,
) {

  /**
   * The estimated item count in the viewport, which is used to determine the number of items that
   * should be rendered.
   */
  private var estimatedItemsInViewPort: Int = UNSET
  private var mountedView: RecyclerView? = null
  private var rangeRatio: Float? = null
  private var onEnterRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit)? = null
  private var onExitRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit)? = null
  private var postUpdateViewportAttempts = 0

  // A new binding may have different layout constraints for items the adapter already tracks.
  private var prepareAllInRange = true

  @Volatile private var lastEmittedRangeStart: Int = Int.MIN_VALUE
  @Volatile private var lastEmittedRangeEnd: Int = Int.MIN_VALUE

  /**
   * Indicates whether an approximate range size has been calculated for the viewport. This property
   * is used to determine if the collection has estimated how many items can fit in the visible
   * area, which is essential for efficient range computation.
   *
   * @return true if the estimated items in viewport has been set, false otherwise
   */
  val hasApproximateRangeSize: Boolean
    get() = estimatedItemsInViewPort != UNSET

  private val viewportManager: ViewportManager = ViewportManager(
      currentFirstVisiblePosition = RecyclerView.NO_POSITION,
      currentLastVisiblePosition = RecyclerView.NO_POSITION,
      layoutInfo = layoutInfo,
  )
  private val updateViewportRunnable =
      object : Runnable {
        override fun run() {
          val mountedView = mountedView
          if (mountedView == null || !mountedView.hasPendingAdapterUpdates()) {
            if (viewportManager.shouldUpdate()) {
              viewportManager.onViewportChanged(ViewportInfo.State.DATA_CHANGES)
            }
            postUpdateViewportAttempts = 0
            return
          }

          // If the view gets detached, we might still have pending updates.
          // If the view's visibility is GONE, layout won't happen until it becomes visible. We
          // have to exit here, otherwise we keep posting this runnable to the next frame until it
          // becomes visible.
          if (!mountedView.isAttachedToWindow || mountedView.isGone) {
            postUpdateViewportAttempts = 0
            return
          }

          if (postUpdateViewportAttempts >= POST_UPDATE_VIEWPORT_AND_COMPUTE_RANGE_MAX_ATTEMPTS) {
            postUpdateViewportAttempts = 0
            if (viewportManager.shouldUpdate()) {
              viewportManager.onViewportChanged(ViewportInfo.State.DATA_CHANGES)
            }
            return
          }

          // If we have pending updates, wait until the sync operations are finished and try again
          // in the next frame.
          postUpdateViewportAttempts++
          mountedView.postOnAnimation(this)
        }
      }
  private val viewportChangedListener: ViewportChanged =
      object : ViewportChanged {
        override fun viewportChanged(
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
            firstFullyVisibleIndex: Int,
            lastFullyVisibleIndex: Int,
            state: Int,
        ) {
          viewportManager.resetShouldUpdate()
          maybePostUpdateViewportAndComputeRange(firstVisibleIndex, lastVisibleIndex)
        }
      }
  private val rangeTraverser: RecyclerRangeTraverser
  private val isBound
    get() = rangeRatio != null && onEnterRange != null && onExitRange != null

  init {
    val layoutManager = layoutInfo.getLayoutManager()
    val stackFromEnd =
        if (layoutManager is LinearLayoutManager) {
          layoutManager.stackFromEnd
        } else {
          false
        }
    rangeTraverser =
        if (stackFromEnd) {
          RecyclerRangeTraverser.Companion.BACKWARD_TRAVERSER
        } else {
          RecyclerRangeTraverser.Companion.FORWARD_TRAVERSER
        }
  }

  @UiThread
  fun bind(
      view: RecyclerView,
      rangeRatio: Float,
      onEnterRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit)? = null,
      onExitRange: (CollectionLayoutScope.(CollectionItem<*>) -> Unit)? = null,
  ) {
    ThreadUtils.assertMainThread()
    this.mountedView = view
    this.rangeRatio = rangeRatio
    this.onEnterRange = onEnterRange
    this.onExitRange = onExitRange
    prepareAllInRange = true

    view.addOnScrollListener(viewportManager.scrollListener)
    viewportManager.addViewportChangedListener(viewportChangedListener)
  }

  @UiThread
  fun unbind(view: RecyclerView) {
    ThreadUtils.assertMainThread()
    view.removeOnScrollListener(viewportManager.scrollListener)
    viewportManager.removeViewportChangedListener(viewportChangedListener)
    mountedView = null
    rangeRatio = null
    onEnterRange = null
    onExitRange = null
    postUpdateViewportAttempts = 0
  }

  fun addViewportChangedListener(viewportChangedListener: ViewportChanged?) {
    viewportManager.addViewportChangedListener(viewportChangedListener)
  }

  fun removeViewportChangedListener(viewportChangedListener: ViewportChanged?) {
    viewportManager.removeViewportChangedListener(viewportChangedListener)
  }

  /**
   * Attempts to update the viewport and compute the range of items that should be prepared. This
   * method checks if the viewport needs updating and posts a runnable to handle the update. It also
   * triggers computation of which items should enter or exit the preparation range.
   *
   * @param firstVisibleIndex The index of the first visible item in the viewport
   * @param lastVisibleIndex The index of the last visible item in the viewport
   */
  @UiThread
  fun maybePostUpdateViewportAndComputeRange(
      firstVisibleIndex: Int = layoutInfo.findFirstVisibleItemPosition(),
      lastVisibleIndex: Int = layoutInfo.findLastVisibleItemPosition(),
      trigger: CollectionRangeTrigger = CollectionRangeTrigger.SCROLL,
  ) {
    mountedView?.let { recyclerView ->
      if (viewportManager.shouldUpdate()) {
        recyclerView.removeCallbacks(updateViewportRunnable)
        recyclerView.postOnAnimation(updateViewportRunnable)
      }
    }
    computeRange(firstVisibleIndex, lastVisibleIndex, trigger = trigger)
  }

  /** Prepares new or updated in-range items and exits tracked items no longer in range. */
  @UiThread
  private fun computeRange(
      firstVisibleIndex: Int,
      lastVisibleIndex: Int,
      traverser: RecyclerRangeTraverser = rangeTraverser,
      trigger: CollectionRangeTrigger = CollectionRangeTrigger.SCROLL,
  ) {
    if (!isBound) {
      return
    }

    if (estimatedItemsInViewPort == UNSET) {
      return
    }

    val rangeRatio: Float = checkNotNull(rangeRatio)
    val firstVisibleToUse: Int = max(firstVisibleIndex, 0)
    val lastVisibleToUse: Int = max(lastVisibleIndex, 0)
    val rangeSize: Int = max(estimatedItemsInViewPort, lastVisibleToUse - firstVisibleToUse)
    val totalSize = adapter.getItems().size
    val rangeStart: Int
    val rangeEnd: Int
    if (adapter.isCircular) {
      rangeStart = 0
      rangeEnd = totalSize
    } else {
      rangeStart = max(0, firstVisibleToUse - (rangeSize * rangeRatio).toInt())
      rangeEnd =
          min(totalSize - 1, firstVisibleToUse + rangeSize + (rangeSize * rangeRatio).toInt())
    }

    val layoutScope = adapter.latestCommittedData?.createLayoutScope() ?: return

    ComponentsSystrace.trace({ "computeRange[$rangeStart-$rangeEnd]" }) {
      val enteredItems = adapter.enteredItems
      val refreshItems = prepareAllInRange || trigger == CollectionRangeTrigger.DATA_CHANGE
      prepareAllInRange = false
      val endExclusive = min(rangeEnd + 1, totalSize)
      val itemsInRange =
          layoutScope.collectionItems.subList(min(rangeStart, totalSize), endExclusive)
      val processor =
          object : RecyclerRangeTraverser.Processor {
            override fun process(index: Int): Boolean {
              val item = layoutScope.collectionItems[index]
              val newlyEntered = enteredItems.add(item)
              return if (newlyEntered || refreshItems) {
                layoutScope.enter(item)
              } else {
                isBound
              }
            }
          }
      traverser.traverse(rangeStart, endExclusive, firstVisibleToUse, lastVisibleToUse, processor)
      // A data change runs this pass before RecyclerView hears of the change: its visible positions
      // are the old ones, so after a large removal a row next to the viewport looks out of range,
      // and a cleanup that scrolls would scroll the old item count. The pass posted for the change,
      // once RecyclerView has applied it, cleans them.
      if (trigger != CollectionRangeTrigger.DATA_CHANGE && isBound) {
        val outsideRange = enteredItems.filter { item -> itemsInRange.none { it === item } }
        outsideRange.forEach { item -> enteredItems.remove(item) }
        for ((index, item) in outsideRange.withIndex()) {
          // An earlier cleanup may scroll back and enter this item in another range pass.
          if (item !in enteredItems && !layoutScope.exit(item)) {
            // Unbinding interrupts exits; the adapter must keep them for the next manager.
            enteredItems.addAll(outsideRange.subList(index, outsideRange.size))
            break
          }
        }
      }
    }

    dispatchRangeComputed(
        firstVisibleToUse,
        lastVisibleToUse,
        rangeStart,
        rangeEnd,
        totalSize,
        trigger,
    )
  }

  private fun CollectionLayoutScope.enter(item: CollectionItem<*>): Boolean {
    if (!isBound) {
      return false
    }
    checkNotNull(onEnterRange).invoke(this, item)
    return true
  }

  private fun CollectionLayoutScope.exit(item: CollectionItem<*>): Boolean {
    if (!isBound) {
      return false
    }
    checkNotNull(onExitRange).invoke(this, item)
    return true
  }

  /**
   * Estimates the number of items that can fit in the viewport based on measuring a sample item.
   * This calculation is performed only once when estimatedItemsInViewPort is unset and helps
   * determine how many items should be prepared for rendering to optimize performance.
   *
   * @param collectionSize The size of the collection container (width and height)
   * @param childSize The size of a sample child item used for estimation (width and height)
   */
  fun estimateItemsInViewPort(collectionSize: IntSize, childSize: IntSize) {
    if (estimatedItemsInViewPort == UNSET) {
      ComponentsSystrace.trace({ "estimateItemsInViewPort" }) {
        estimatedItemsInViewPort =
            max(
                layoutInfo.approximateRangeSize(
                    childSize.width,
                    childSize.height,
                    collectionSize.width,
                    collectionSize.height,
                ),
                1,
            )
      }
    }
  }

  /**
   * Prepares items currently visible in the viewport by measuring them and filling the viewport.
   * This method uses a ViewportFiller to determine which items should be measured and prepared for
   * rendering based on the available space in the collection's viewport.
   *
   * @param items The list of collection items that may need to be prepared for rendering
   */
  fun CollectionLayoutScope.prepareItemsInViewport(
      items: List<CollectionItem<*>>,
      collectionSize: IntSize = this.collectionSize,
  ) {

    val filler: LayoutInfo.ViewportFiller? =
        layoutInfo.createViewportFiller(
            collectionConstraints.maxWidth,
            collectionConstraints.maxHeight,
        )

    if (filler != null) {
      ComponentsSystrace.trace({ "prepareItemsInViewport" }) {
        // RACE CONDITION WARNING: We are preparing items on a background thread(most likely)
        // while the UI thread may simultaneously remove children from the RecyclerView. This
        // creates a race condition where the layout manager attempts to find views that no longer
        // exist, resulting in crashes. The cached position helps mitigate this by avoiding direct
        // RecyclerView queries during preparation.
        var index = max(0, firstVisiblePosition)
        while (filler.wantsMore() && index < items.size) {
          val item = items[index]
          val constraints = getChildSizeConstraints(item, collectionSize)
          val size = getMeasuredSize(item, constraints)
          filler.add(item.renderInfo, size.width, size.height)
          index++
        }
      }
    }
  }

  internal fun onItemsChanged() {
    // Notify the viewport manager that the adapter data has changed so it can recalculate visible
    // ranges
    viewportManager.setShouldUpdate(true)
    maybePostUpdateViewportAndComputeRange(trigger = CollectionRangeTrigger.DATA_CHANGE)
  }

  private fun dispatchRangeComputed(
      firstVisible: Int,
      lastVisible: Int,
      rangeStart: Int,
      rangeEnd: Int,
      totalSize: Int,
      trigger: CollectionRangeTrigger,
  ) {
    if (rangeStart == lastEmittedRangeStart && rangeEnd == lastEmittedRangeEnd) {
      return
    }
    val currentRangeRatio = rangeRatio ?: return

    DebugEventDispatcher.dispatch(
        type = LithoDebugEvent.CollectionRangeComputed,
        renderStateId = DebugEvent.NoId,
    ) { attrs ->
      lastEmittedRangeStart = rangeStart
      lastEmittedRangeEnd = rangeEnd

      val visibleCount =
          if (firstVisible < 0 || lastVisible < 0) 0 else lastVisible - firstVisible + 1
      val clampedStart = max(0, rangeStart)
      val clampedEnd = min(totalSize - 1, rangeEnd)
      val preparedCount = if (clampedEnd >= clampedStart) clampedEnd - clampedStart + 1 else 0

      attrs[LithoDebugEventAttributes.IsLegacyInfra] = false
      attrs[LithoDebugEventAttributes.Trigger] = trigger.name
      attrs[LithoDebugEventAttributes.FirstVisiblePosition] = firstVisible
      attrs[LithoDebugEventAttributes.LastVisiblePosition] = lastVisible
      attrs[LithoDebugEventAttributes.VisibleCount] = visibleCount
      attrs[LithoDebugEventAttributes.RangeStart] = clampedStart
      attrs[LithoDebugEventAttributes.RangeEnd] = clampedEnd
      attrs[LithoDebugEventAttributes.PreparedCount] = preparedCount
      attrs[LithoDebugEventAttributes.TotalItemCount] = totalSize
      attrs[LithoDebugEventAttributes.RangeRatio] = currentRangeRatio
      // These features only exist in the legacy RecyclerBinder infra (via RecyclerBinderConfig).
      // The new collection infra does not support them, so the values are intentionally hardcoded.
      attrs[LithoDebugEventAttributes.HasPreparationWarming] = false
      attrs[LithoDebugEventAttributes.RecyclerViewItemPrefetch] = false
      attrs[LithoDebugEventAttributes.ItemViewCacheSize] = 0
      attrs[LithoDebugEventAttributes.RecyclingStrategy] = "DEFAULT"
    }
  }

  companion object {
    private const val UNSET: Int = -1
    private const val POST_UPDATE_VIEWPORT_AND_COMPUTE_RANGE_MAX_ATTEMPTS: Int = 3
  }
}
