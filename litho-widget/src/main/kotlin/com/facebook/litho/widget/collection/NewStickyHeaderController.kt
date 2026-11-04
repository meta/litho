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

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.ComponentContext
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.widget.LayoutInfo
import com.facebook.litho.widget.SectionsRecyclerView

/**
 * A controller that manages sticky headers in a RecyclerView by tracking scroll events and
 * maintaining a floating header view that stays visible at the top when scrolling past sticky
 * items.
 *
 * This controller uses a linked list structure to efficiently track sticky header positions and
 * handles header transitions, stacking animations, and visibility management.
 *
 * @param layoutInfo Provides information about visible item positions in the RecyclerView
 * @param adapter The adapter containing collection items, used to retrieve sticky header content
 */
class NewStickyHeaderController(
    private val layoutInfo: LayoutInfo,
    private val adapter: CollectionPrimitiveViewAdapter,
) : RecyclerView.OnScrollListener() {

  private var sectionsRecyclerView: SectionsRecyclerView? = null
  private var layoutManager: RecyclerView.LayoutManager? = null
  private var headPosition: StickyPosition? = null
  private var previousStickyHeaderPosition: Int? = null
  private var stickyHeaderItem: CollectionItem<*>? = null

  override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {

    val sectionsRecyclerView = checkNotNull(sectionsRecyclerView) { RECYCLER_NOT_INITIALIZED }
    val stickyHeaderView =
        checkNotNull(sectionsRecyclerView.stickyHeaderView) { STICKY_HEADER_NOT_INITIALIZED }
    val layoutManager = checkNotNull(layoutManager) { LAYOUTMANAGER_NOT_INITIALIZED }

    val firstVisiblePosition = layoutInfo.findFirstVisibleItemPosition()
    if (firstVisiblePosition == RecyclerView.NO_POSITION) {
      // Exit early if no visible items
      return
    }

    // Find current sticky header using linked list navigation
    val stickyHeaderPosition = findTargetStickyItem(firstVisiblePosition)

    // Reset previous translation if sticky header changed
    updateStickyItemIfNeeded(stickyHeaderPosition?.position) { newItem ->
      resetPreviousTranslation(stickyHeaderView)

      if (newItem != null) {
        // Prepare the sticky header ahead of time before being mounted
        warmStickyHeader(recyclerView.context, stickyHeaderView, newItem)
      }
    }

    // No sticky header is shown
    if (stickyHeaderPosition == null) {
      hideStickyHeader()
      resetStickHeader(stickyHeaderView)
      return
    }

    val stickyView = layoutManager.findViewByPosition(stickyHeaderPosition.position)
    // Determine if the floating sticky header should be displayed based on two conditions:
    // 1) The target sticky item is positioned above the first visible item in the viewport, OR
    // 2) The target sticky item view has been detached/recycled due to being outside the viewport
    // bounds
    val shouldShowStickyHeaderView =
        (stickyView == null) || (stickyView.top < (recyclerView.top - recyclerView.paddingTop))
    if (shouldShowStickyHeaderView) {

      var isStickyHeaderInitialized = true
      if (
          sectionsRecyclerView.isStickyHeaderHidden ||
              (stickyHeaderPosition.position != previousStickyHeaderPosition)
      ) {
        isStickyHeaderInitialized = initStickyHeader(stickyHeaderView)
        if (isStickyHeaderInitialized) {
          sectionsRecyclerView.measureStickyHeader(recyclerView.width)
          sectionsRecyclerView.layoutStickyHeader()
        }
      }
      if (isStickyHeaderInitialized) {
        showStickyHeader()
      } else {
        hideStickyHeader()
      }
      previousStickyHeaderPosition = stickyHeaderPosition.position
    }

    if (!sectionsRecyclerView.isStickyHeaderHidden) {
      // When two sticky header items are in the viewport, check if we need to translate the
      // floating view to handle with the stacking scenario.
      val nextStickyHeaderPosition = stickyHeaderPosition.next
      val lastVisiblePosition = layoutInfo.findLastVisibleItemPosition()

      // Check if there's a next sticky header and it's visible in the viewport
      if (
          nextStickyHeaderPosition != null &&
              lastVisiblePosition >= nextStickyHeaderPosition.position
      ) {
        val nextStickyHeaderItem =
            layoutManager.findViewByPosition(nextStickyHeaderPosition.position)

        // Only proceed if the next sticky header view is actually rendered
        if (nextStickyHeaderItem != null) {
          // Calculate the top position of the next sticky item relative to RecyclerView
          val topOfNextStickyItem = nextStickyHeaderItem.top - recyclerView.paddingTop
          // Get the bottom position of the current floating sticky header
          val bottomOfCurrentStickyItem = stickyHeaderView.bottom

          // Check if the next sticky header is overlapping with the current one
          if (topOfNextStickyItem < bottomOfCurrentStickyItem) {
            // push floating sticky header up when next one approaches
            val translationY = (topOfNextStickyItem - bottomOfCurrentStickyItem)
            setStickyHeaderVerticalOffset(stickyHeaderView, translationY)
            return
          }
        }
      }
      // Reset any previous translation if no stacking is needed
      resetPreviousTranslation(stickyHeaderView)
    }
  }

  private fun setupStickyHeaderView(sectionsRecyclerView: SectionsRecyclerView) {
    if (sectionsRecyclerView.stickyHeaderView != null) return

    sectionsRecyclerView.stickyHeaderView =
        LithoRenderTreeView(sectionsRecyclerView.context, null).apply {
          layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
          visibility = View.GONE
        }
  }

  private fun initStickyHeader(stickyHeaderView: View): Boolean {
    val item = stickyHeaderItem as? LithoCollectionItem ?: return false
    val view = stickyHeaderView as? LithoRenderTreeView ?: return false
    adapter.latestCommittedData?.createLayoutScope()?.let { scope ->
      with(item) { scope.onBindView(view) }
    }
    return true
  }

  private fun updateStickyItemIfNeeded(
      position: Int?,
      updateCallback: (CollectionItem<*>?) -> Unit,
  ): Boolean {

    // Check if sticky header needs updating due to position change or content change
    val item = position?.let { adapter.findItemByPosition(it) }

    val shouldUpdate =
        when {
          // Position changed
          (previousStickyHeaderPosition != position) -> true
          // Same position but different content
          (item != null && item.renderInfo !== stickyHeaderItem?.renderInfo) -> true
          // No update needed
          else -> false
        }

    if (shouldUpdate) {
      updateCallback(item)
      return true
    }

    return false
  }

  private fun warmStickyHeader(
      context: Context,
      stickyHeaderView: View,
      item: CollectionItem<*>,
  ) {
    // Clean up for the previous sticky header view
    resetStickHeader(stickyHeaderView)

    val newStickyHeaderItem = LithoCollectionItem(
        componentContext = ComponentContext(context),
        renderInfo = item.renderInfo,
    )
        .also { newItem -> this.stickyHeaderItem = newItem }

    adapter.latestCommittedData?.createLayoutScope()?.let { scope ->
      // In situations where the RecyclerView hasn't had a chance to measure items that are
      // far from the current viewport (e.g., items beyond the visible range or prefetch distance),
      // we need to manually measure the sticky header item here to ensure proper layout
      // calculations before it becomes visible as a floating header.
      val constraints = scope.getChildSizeConstraints(newStickyHeaderItem)
      newStickyHeaderItem.prepareAsync(constraints)
    }
  }

  private fun resetStickHeader(stickyHeaderView: View) {
    val item = stickyHeaderItem as? LithoCollectionItem ?: return
    if (stickyHeaderView is LithoRenderTreeView) {
      item.onViewRecycled(stickyHeaderView)
    }
    // The copy is never used again, and a state handle it handed out must not keep the row alive.
    item.releaseRenderer()
  }

  private fun setStickyHeaderVerticalOffset(stickyHeaderView: View, verticalOffset: Int) {
    stickyHeaderView.translationY = verticalOffset.toFloat()
  }

  private fun resetPreviousTranslation(stickyHeaderView: View) {
    stickyHeaderView.translationY = 0f
  }

  // Traverse linked list to find the sticky header position that should be active
  private fun findTargetStickyItem(firstVisiblePosition: Int): StickyPosition? {
    var lastValidPosition: StickyPosition? = null
    var cursor: StickyPosition? = headPosition
    while (cursor != null) {
      if (cursor.position <= firstVisiblePosition) {
        lastValidPosition = cursor
      } else {
        break
      }
      cursor = cursor.next
    }
    return lastValidPosition
  }

  fun init(stickyHeaderRecyclerView: SectionsRecyclerView) {
    if (this.sectionsRecyclerView != null) {
      throw RuntimeException(RECYCLER_ALREADY_INITIALIZED)
    }

    this.sectionsRecyclerView = stickyHeaderRecyclerView
    setupStickyHeaderView(stickyHeaderRecyclerView)
    layoutManager = stickyHeaderRecyclerView.recyclerView.layoutManager
    if (layoutManager == null) {
      throw RuntimeException(LAYOUTMANAGER_NOT_INITIALIZED)
    }

    stickyHeaderRecyclerView.recyclerView.addOnScrollListener(this)
  }

  fun reset() {
    val sectionsRecyclerView = checkNotNull(sectionsRecyclerView) { RECYCLER_NOT_INITIALIZED }
    sectionsRecyclerView.recyclerView.removeOnScrollListener(this)
    sectionsRecyclerView.stickyHeaderView?.let { resetStickHeader(it) }
    sectionsRecyclerView.stickyHeaderView = null
    layoutManager = null
    this.sectionsRecyclerView = null
    headPosition = null
    previousStickyHeaderPosition = null
    stickyHeaderItem = null
  }

  fun scanStickyIndices(items: List<CollectionItem<*>>) {
    // Initialize variables to track the previous item and head of the linked list
    var prevItem: StickyPosition? = null
    var headItem: StickyPosition? = null

    // Iterate through all adapter items with their indices
    items.forEachIndexed { index, item ->
      // Check if the current item should be sticky
      if (item.renderInfo.isSticky) {
        // Create a new StickyPosition node with current index
        val currentItem = StickyPosition(position = index)
        // Link the previous item to point forward to current item
        prevItem?.next = currentItem

        // Set the head of the linked list to the first sticky item found
        if (headItem == null) {
          headItem = currentItem
        }
        // Update prevItem to current for next iteration
        prevItem = currentItem
      }
    }
    // Update the property with the head of the linked list
    headPosition = headItem
  }

  /**
   * Updates the sticky header when the adapter data changes.
   *
   * This method is called by the adapter data observer when items are inserted, removed, or
   * changed. It refreshes the current sticky header by re-warming and re-initializing it with
   * updated data, then remeasures and relayouts the sticky header view if initialization succeeds.
   */
  fun updateStickyHeaderIfNeeded() {
    if (headPosition == null) {
      // No sticky item is left, so the copy of the last one is never shown again.
      hideStickyHeader()
      sectionsRecyclerView?.stickyHeaderView?.let { resetStickHeader(it) }
      stickyHeaderItem = null
      previousStickyHeaderPosition = null
      return
    }
    val stickyHeaderPosition =
        findTargetStickyItem(layoutInfo.findFirstVisibleItemPosition())?.position

    updateStickyItemIfNeeded(stickyHeaderPosition) { newItem ->
      val recyclerView = checkNotNull(sectionsRecyclerView) { RECYCLER_NOT_INITIALIZED }
      val stickyHeaderView =
          checkNotNull(recyclerView.stickyHeaderView) { STICKY_HEADER_NOT_INITIALIZED }

      resetPreviousTranslation(stickyHeaderView)

      if (newItem != null) {
        warmStickyHeader(recyclerView.context, stickyHeaderView, newItem)
        val isStickyHeaderInitialized = initStickyHeader(stickyHeaderView)
        if (isStickyHeaderInitialized) {
          recyclerView.measureStickyHeader(recyclerView.width)
          recyclerView.layoutStickyHeader()
        }
      }
    }
  }

  // region: Public APIs

  fun showStickyHeader() {
    sectionsRecyclerView?.stickyHeaderView?.visibility = View.VISIBLE
  }

  fun hideStickyHeader() {
    sectionsRecyclerView?.stickyHeaderView?.visibility = View.GONE
  }

  // endregion

  internal fun onItemsChanged() {
    scanStickyIndices(adapter.getItems())
    updateStickyHeaderIfNeeded()
  }

  private class StickyPosition(
      val position: Int,
      var next: StickyPosition? = null,
  )

  companion object {
    const val RECYCLER_ALREADY_INITIALIZED: String =
        "SectionsRecyclerView has already been initialized but never reset."
    const val RECYCLER_NOT_INITIALIZED: String = "SectionsRecyclerView has not been set yet."
    const val STICKY_HEADER_NOT_INITIALIZED: String = "Sticky header view has not been set yet."
    const val LAYOUTMANAGER_NOT_INITIALIZED: String =
        "LayoutManager of RecyclerView is not initialized yet."
  }
}
