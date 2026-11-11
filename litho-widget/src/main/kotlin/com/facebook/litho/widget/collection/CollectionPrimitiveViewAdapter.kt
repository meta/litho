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

import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.annotation.GuardedBy
import androidx.annotation.UiThread
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.widget.RecyclerViewLayoutManagerOverrideParams
import com.facebook.rendercore.thread.utils.ThreadUtils
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import java.util.Collections
import java.util.IdentityHashMap

/**
 * RecyclerView adapter that can be used to render a list of [CollectionItem]s. The adapter will
 * create a [PrimitiveRecyclerViewHolder] for each item and will call [CollectionItem.onBindView]
 * and [CollectionItem.onViewRecycled] on the holder when the item is bound and recycle
 * respectively.
 */
class CollectionPrimitiveViewAdapter @PublishedApi internal constructor() :
    RecyclerView.Adapter<PrimitiveRecyclerViewHolder>() {

  private val lock = Any()
  @GuardedBy("lock") private var items: List<CollectionItem<*>> = ArrayList()

  /** List of observers that will be notified when the adapter's data changes. */
  private val dataObservers: MutableList<CollectionAdapterDataObserver> = ArrayList()

  /**
   * Factory function for creating CollectionItemRootHostHolder instances. This function is called
   * by the adapter when a new view holder needs to be created. Must be set before the adapter
   * starts creating view holders.
   *
   * @param parent The ViewGroup into which the new View will be added after it is bound to an
   *   adapter position
   * @param viewType The view type of the new View, used to distinguish different item types
   * @return A new CollectionItemRootHostHolder instance
   */
  internal var viewHolderCreator:
      ((parent: ViewGroup, viewType: Int) -> CollectionItemRootHostHolder<
              out View,
              out CollectionItem<out View>,
          >)? =
      null

  /**
   * Optional layout data that provides additional configuration and metadata for the collection's
   * layout behavior. This can be used to store layout-specific information such as span counts,
   * orientation, or other layout parameters that influence how items are arranged in the
   * RecyclerView.
   */
  internal var latestCommittedData: LatestCommittedData? = null
    @UiThread
    set(value) {
      ThreadUtils.assertMainThread()
      field = value
    }

  /**
   * Optional callback that is invoked whenever a CollectionItem's associated View is measured.
   *
   * @param view The LithoRenderTreeView associated with the CollectionItem
   * @param item The CollectionItem that was measured
   * @param widthSpec The width specification used for measurement
   * @param heightSpec The height specification used for measurement
   */
  internal var onItemMeasured:
      ((
          view: LithoRenderTreeView,
          item: CollectionItem<View>,
          widthSpec: Int,
          heightSpec: Int,
      ) -> Unit)? =
      null

  /**
   * Indicates whether this adapter should behave as a circular/infinite list. When true, the
   * adapter reports Integer.MAX_VALUE as item count and uses modulo arithmetic to wrap positions
   * around the actual items list.
   */
  internal var isCircular: Boolean = false
    @UiThread
    set(value) {
      ThreadUtils.assertMainThread()
      field = value
      // Set stable IDs
      // 1. Cannot change whether this adapter has stable IDs while the adapter has registered
      // observers.
      // 2. Circular lists don't support stable IDs
      setHasStableIds(!value)
    }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrimitiveRecyclerViewHolder {
    val viewHolderDelegate =
        checkNotNull(viewHolderCreator) {
              "viewHolderCreator must be set before creating the view holders"
            }
            .invoke(parent, viewType)
    @Suppress("UNCHECKED_CAST")
    val viewHolder = PrimitiveRecyclerViewHolder(
        viewHolderDelegate as CollectionItemRootHostHolder<View, CollectionItem<View>>,
    )
    if (viewHolder.itemView is LithoRenderTreeView) {
      (viewHolderDelegate.view as LithoRenderTreeView).onMeasured = { widthSpec, heightSpec ->
        val item = viewHolder.delegate.data as CollectionItem<View>
        onItemMeasured?.invoke(
            viewHolder.delegate.view as LithoRenderTreeView,
            item,
            widthSpec,
            heightSpec,
        )
      }
    }
    return viewHolder
  }

  override fun onBindViewHolder(viewHolder: PrimitiveRecyclerViewHolder, position: Int) {
    // TODO: handle OOB exception
    val normalizedPosition = getNormalizedPosition(position)
    val item = items[normalizedPosition]
    @Suppress("UNCHECKED_CAST")
    viewHolder.delegate.data = item as CollectionItem<View>
    latestCommittedData?.createLayoutScope()?.let { scope ->
      with(item) {
        val layoutParams = scope.generateLayoutParams(item)
        // A row that has never been bound has no params. Give it the new ones now: RecyclerView
        // crashes, instead of returning NO_POSITION, when asked about a row without params.
        if (viewHolder.itemView.layoutParams == null) {
          viewHolder.itemView.layoutParams = layoutParams
        }
        // Swap the params only after binding. RecyclerView finds a row's position through the
        // row's current params, and new params can't do that until RecyclerView updates them after
        // this method returns. Effects that run while the row binds can still find its position.
        scope.onBindView(viewHolder.delegate.view)
        viewHolder.itemView.layoutParams = layoutParams
      }
    }
  }

  /**
   * Items entered into preparation, plus recycled items awaiting a range pass, by identity. Kept
   * across data changes and [CollectionPreparationManager] replacement. Main thread only.
   */
  internal val enteredItems: MutableSet<CollectionItem<*>> =
      Collections.newSetFromMap(IdentityHashMap())

  override fun onViewRecycled(viewHolder: PrimitiveRecyclerViewHolder) {
    viewHolder.delegate.data?.let { item ->
      item.onViewRecycled(viewHolder.delegate.view)
      // A row may have exited while its view was still cached; retry cleanup on the next pass.
      enteredItems.add(item)
    }
    viewHolder.delegate.data = null
    if (viewHolder.itemView is LithoRenderTreeView) {
      (viewHolder.itemView as LithoRenderTreeView).onMeasured = null
    }
  }

  override fun getItemCount(): Int {
    val size = items.size
    // For circular lists, report Integer.MAX_VALUE to simulate infinite items
    return if (isCircular && size > 0) Int.MAX_VALUE else size
  }

  override fun getItemId(position: Int): Long {
    return items[position].id.toLong()
  }

  override fun getItemViewType(position: Int): Int {
    return items[getNormalizedPosition(position)].viewType
  }

  /** Returns the position of the first item with the given id, otherwise, returns -1. */
  @UiThread
  fun findPositionById(id: Any): Int {
    ThreadUtils.assertMainThread()
    return items.indexOfFirst { item ->
      item.renderInfo.getCustomAttribute(CollectionItem.ID_CUSTOM_ATTR_KEY) == id
    }
  }

  /**
   * Finds and returns a CollectionItem at the specified position in the adapter's data set. For
   * circular lists, the position is normalized using modulo arithmetic.
   *
   * @param position The position of the item to retrieve
   * @return The CollectionItem at the specified position, or null if position is invalid
   */
  @UiThread
  fun findItemByPosition(position: Int): CollectionItem<*>? {
    ThreadUtils.assertMainThread()
    val normalizedPosition = getNormalizedPosition(position)
    return items.getOrNull(normalizedPosition)
  }

  /**
   * Normalizes the position for circular lists by wrapping it around the actual items size. For
   * non-circular lists, returns the position as-is.
   */
  @UiThread
  private fun getNormalizedPosition(position: Int): Int {
    return if (isCircular && items.isNotEmpty()) {
      position % items.size
    } else {
      position
    }
  }

  // region update operations

  @UiThread
  fun setItems(newData: List<CollectionItem<*>>) {
    ThreadUtils.assertMainThread()
    if (isCircular) {
      require(items.isEmpty() || newData.isEmpty() || (items.size == newData.size)) {
        "Circular lists do not support insert or delete operations as they would disrupt " +
            "the infinite scrolling behavior and cause index inconsistencies. " +
            "old size[${items.size}] vs new size[${newData.size}]"
      }
    }
    synchronized(lock) { items = newData }
    notifyDataObservers()
  }

  // endregion

  fun getItems(): List<CollectionItem<*>> {
    synchronized(lock) {
      return items
    }
  }

  fun getSnapshotOfItemsAsMutableList(): MutableList<CollectionItem<*>> {
    synchronized(lock) {
      return items.toMutableList()
    }
  }

  /**
   * Adds a data observer to receive notifications when the adapter's data changes.
   *
   * @param observer The observer to be added
   */
  @UiThread
  fun addDataObserver(observer: CollectionAdapterDataObserver) {
    ThreadUtils.assertMainThread()
    dataObservers.add(observer)
  }

  /**
   * Removes a previously added data observer.
   *
   * @param observer The observer to be removed
   */
  @UiThread
  fun removeDataObserver(observer: CollectionAdapterDataObserver) {
    ThreadUtils.assertMainThread()
    dataObservers.remove(observer)
  }

  /** Notifies all registered data observers that the adapter's data set has changed. */
  @UiThread
  private fun notifyDataObservers() {
    for (observer in dataObservers) {
      observer.onItemsChanged()
    }
  }

  companion object {

    /**
     * Generates layout parameters for a RecyclerView item based on the collection's layout data and
     * the item's size constraints.
     *
     * This function determines the appropriate width and height for an item by considering:
     * - The scroll orientation of the RecyclerView (vertical or horizontal)
     * - Whether the item has exact size constraints
     * - Default sizing behavior for the cross-axis (MATCH_PARENT) and scroll-axis (WRAP_CONTENT)
     *
     * @param item The collection item for which to generate layout parameters
     * @return RecyclerViewLayoutManagerOverrideParams containing the calculated layout parameters
     */
    private fun CollectionLayoutScope.generateLayoutParams(
        item: CollectionItem<*>,
    ): RecyclerViewLayoutManagerOverrideParams {
      val constraints = getChildSizeConstraints(item)
      val width =
          if (constraints.hasExactWidth) {
            constraints.maxWidth
          } else if (isVertical) {
            MATCH_PARENT
          } else {
            WRAP_CONTENT
          }
      val height =
          if (constraints.hasExactHeight) {
            constraints.maxHeight
          } else if (isVertical) {
            WRAP_CONTENT
          } else {
            MATCH_PARENT
          }
      return RecyclerViewLayoutManagerOverrideParams(
          width,
          height,
          constraints.toWidthSpec(),
          constraints.toHeightSpec(),
          item.renderInfo.isFullSpan,
      )
    }
  }
}

/**
 * Functional interface for observing data changes in the CollectionPrimitiveViewAdapter.
 * Implementations of this interface can be registered with the adapter to receive notifications
 * when the underlying data set changes.
 */
fun interface CollectionAdapterDataObserver {

  /**
   * Called when the adapter's data set has changed. This method is invoked to notify observers that
   * they should refresh their views or take other appropriate actions in response to the data
   * change.
   */
  fun onItemsChanged()
}
