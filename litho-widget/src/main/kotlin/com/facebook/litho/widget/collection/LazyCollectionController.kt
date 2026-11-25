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
import androidx.annotation.UiThread
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import com.facebook.litho.ComponentScope
import com.facebook.litho.ResourcesScope
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.sections.SectionTree
import com.facebook.litho.useState
import com.facebook.litho.widget.RecyclerEventsController
import com.facebook.litho.widget.SmoothScrollAlignmentType
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.px

/** A hook to create a cached [LazyCollectionController] to use with a [LazyCollection]. */
@Hook
fun ComponentScope.useLazyCollectionController(): LazyCollectionController {
  return useState { LazyCollectionController() }.value
}

/**
 * A controller that can be set on a [LazyCollection] to trigger external events. Most calls should
 * be made on the main thread e.g. within a `Style.onClick { }` callback.
 */
class LazyCollectionController {

  /**
   * Latest [setRefreshing] value stashed while the controller was unbound; applied + cleared on
   * bind.
   */
  private var pendingRefreshing: Boolean? = null

  /**
   * Latest [setRecyclerUpdate] listener. Retained for the controller's lifetime (NOT cleared after
   * first apply) and re-applied on every [recyclerEventsController] (re)assignment, so the callback
   * survives subsequent binds — e.g. when a retained `ComponentTree` is rehosted onto a new
   * `LithoView` and the underlying `RecyclerView` is re-mounted, or when the controller is rebound
   * to a fresh [RecyclerEventsController]. Pass an empty lambda to [setRecyclerUpdate] to detach.
   */
  private var recyclerUpdateListener: ((RecyclerView?) -> Unit)? = null

  internal var recyclerEventsController: RecyclerEventsController? = null
    set(value) {
      if (field !== value) {
        field?.setOnRecyclerUpdateListener(null)
      }
      field = value
      if (value != null) {
        pendingRefreshing?.let { pending ->
          if (pending) value.showRefreshing() else value.clearRefreshing()
          pendingRefreshing = null
        }
        recyclerUpdateListener?.let { listener -> value.setOnRecyclerUpdateListener(listener) }
      }
    }

  internal var scrollerDelegate: ScrollerDelegate? = null

  /**
   * Request a callback when a [RecyclerView] is bound or unbound. The listener is retained for the
   * controller's lifetime and auto-re-attached on every underlying-controller (re)bind, so it keeps
   * firing across rebinds — including when a retained `ComponentTree` is rehosted onto a new
   * `LithoView` and the `RecyclerView` is re-mounted (tab-switch back, rotation). Callers no longer
   * need to re-register from their own mount/lifecycle hook. Pass an empty lambda to detach.
   */
  fun setRecyclerUpdate(listener: (RecyclerView?) -> Unit) {
    recyclerUpdateListener = listener
    recyclerEventsController?.setOnRecyclerUpdateListener(listener)
  }

  /**
   * Retrieve a reference to the RecyclerView. This should be avoided if possible, but may be
   * necessary e.g. for custom animations.
   */
  val recyclerView: RecyclerView?
    @UiThread get() = recyclerEventsController?.recyclerView

  /** Tells if the refresh indicator is currently shown. Reflects the pending value when unbound. */
  val isRefreshing: Boolean
    @UiThread get() = recyclerEventsController?.isRefreshing ?: (pendingRefreshing == true)

  /**
   * A reference to the [SnapHelper] that is currently attached to the [RecyclerView]. If this
   * LazyCollection isn't mounted or doesn't have an associated SnapHelper, returns null.
   */
  val snapHelper: SnapHelper?
    @UiThread get() = recyclerEventsController?.snapHelper

  /**
   * Toggle the refresh indicator. Not needed inside [LazyCollection]'s `onPullToRefresh` callback —
   * triggered automatically. Calls made before the controller binds are stashed (latest-wins) and
   * applied once on bind.
   */
  @UiThread
  fun setRefreshing(isRefreshing: Boolean) {
    val controller = recyclerEventsController
    if (controller != null) {
      if (isRefreshing) controller.showRefreshing() else controller.clearRefreshing()
      pendingRefreshing = null
    } else {
      pendingRefreshing = isRefreshing
    }
  }

  /**
   * Scroll the [LazyCollection] by a given number of pixels. The Scroll is instant. For an animated
   * scroll use [smoothScrollBy].
   *
   * @param dx Pixel distance to scroll along the x axis
   * @param dy Pixel distance to scroll along the y axis
   */
  @UiThread
  fun scrollBy(@Px dx: Int, @Px dy: Int) {
    recyclerEventsController?.recyclerView?.scrollBy(dx, dy)
  }

  /**
   * Perform and animated scroll on the [LazyCollection] by a given number of pixels. For an
   * instantaneous scroll use [scrollBy].
   *
   * @param dx Pixel distance to scroll along the x axis
   * @param dy Pixel distance to scroll along the y axis
   */
  @UiThread
  fun smoothScrollBy(@Px dx: Int, @Px dy: Int) {
    recyclerEventsController?.recyclerView?.smoothScrollBy(dx, dy)
  }

  /**
   * Scroll the [LazyCollection] so that the child at the given index is fully visible. For an
   * animated scroll use [smoothScrollToIndex].
   *
   * @param index The index of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   */
  @UiThread
  fun scrollToIndex(index: Int, @Px offset: Int = 0) {
    scrollerDelegate?.scrollToIndex(index, offset)
  }

  /**
   * Perform an animated scroll on the [LazyCollection] so that the child at the given index is
   * fully visible. For an instantaneous scroll use [scrollToIndex].
   *
   * @param index The index of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   * @param smoothScrollAlignmentType Attempt to position the child based on this alignment type.
   */
  @UiThread
  fun smoothScrollToIndex(
      index: Int,
      @Px offset: Int = 0,
      smoothScrollAlignmentType: SmoothScrollAlignmentType = SmoothScrollAlignmentType.DEFAULT,
  ) {
    scrollerDelegate?.smoothScrollToIndex(index, offset, smoothScrollAlignmentType)
  }

  /**
   * Scroll the [LazyCollection] so that the child with the given id is fully visible. For an
   * animated scroll use [smoothScrollToId].
   *
   * @param id The id of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   */
  @UiThread
  fun scrollToId(
      id: Any,
      @Px offset: Int = 0,
  ) {
    scrollerDelegate?.scrollToId(id, offset)
  }

  /**
   * Perform an animated scroll on the [LazyCollection] so that the child with the given id is fully
   * visible. For an animated scroll use [smoothScrollToId].
   *
   * @param id The id of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   * @param smoothScrollAlignmentType Attempt to position the child based on this alignment type.
   */
  @UiThread
  fun smoothScrollToId(
      id: Any,
      @Px offset: Int = 0,
      smoothScrollAlignmentType: SmoothScrollAlignmentType = SmoothScrollAlignmentType.DEFAULT,
  ) {
    scrollerDelegate?.smoothScrollToId(id, offset, smoothScrollAlignmentType)
  }

  /**
   * Scroll the [LazyCollection] so that the child declared with [id] inside the child group
   * [groupId] is fully visible. Not an overload of [scrollToId]: `scrollToId(groupId, 9)` would
   * resolve to its `offset` parameter.
   *
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   */
  @UiThread
  fun scrollToIdInGroup(groupId: Any, id: Any, @Px offset: Int = 0) {
    scrollToId(ChildGroupRowId(groupId, id), offset)
  }
}

sealed interface ScrollerDelegate {

  /**
   * Perform an animated scroll on the [LazyCollection] so that the child with the given id is fully
   * visible. For an animated scroll use [smoothScrollToId].
   *
   * @param id The id of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   * @param smoothScrollAlignmentType Attempt to position the child based on this alignment type.
   */
  fun smoothScrollToId(
      id: Any,
      @Px offset: Int = 0,
      smoothScrollAlignmentType: SmoothScrollAlignmentType = SmoothScrollAlignmentType.DEFAULT,
  )

  /**
   * Perform an animated scroll on the [LazyCollection] so that the child at the given index is
   * fully visible. For an instantaneous scroll use [scrollToIndex].
   *
   * @param index The index of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   * @param smoothScrollAlignmentType Attempt to position the child based on this alignment type.
   */
  fun smoothScrollToIndex(
      index: Int,
      @Px offset: Int = 0,
      smoothScrollAlignmentType: SmoothScrollAlignmentType = SmoothScrollAlignmentType.DEFAULT,
  )

  /**
   * Scroll the [LazyCollection] so that the child with the given id is fully visible. For an
   * animated scroll use [smoothScrollToId].
   *
   * @param id The id of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   */
  fun scrollToId(
      id: Any,
      @Px offset: Int = 0,
  )

  /**
   * Scroll the [LazyCollection] so that the child at the given index is fully visible. For an
   * animated scroll use [smoothScrollToIndex].
   *
   * @param index The index of the child to scroll to
   * @param offset Attempt to offset the child by this number of pixels from the start of the
   *   Collection.
   */
  fun scrollToIndex(index: Int, @Px offset: Int = 0)
}

class SectionTreeScroller(private val sectionTree: SectionTree) : ScrollerDelegate {

  override fun smoothScrollToId(
      id: Any,
      offset: Int,
      smoothScrollAlignmentType: SmoothScrollAlignmentType,
  ) {
    sectionTree.requestSmoothFocusOnRoot(id, offset, smoothScrollAlignmentType)
  }

  override fun smoothScrollToIndex(
      index: Int,
      offset: Int,
      smoothScrollAlignmentType: SmoothScrollAlignmentType,
  ) {
    sectionTree.requestSmoothFocusOnRoot(index, offset, smoothScrollAlignmentType)
  }

  override fun scrollToId(id: Any, offset: Int) {
    sectionTree.requestFocusOnRoot(id, offset)
  }

  override fun scrollToIndex(index: Int, offset: Int) {
    sectionTree.requestFocusOnRoot(index, offset)
  }
}

@ExperimentalLithoApi
class RecyclerScroller(private val scroller: CollectionPrimitiveViewScroller) : ScrollerDelegate {

  override fun smoothScrollToId(
      id: Any,
      offset: Int,
      smoothScrollAlignmentType: SmoothScrollAlignmentType,
  ) {
    scroller.startSmoothScrollWithOffset(id, offset, smoothScrollAlignmentType)
  }

  override fun smoothScrollToIndex(
      index: Int,
      offset: Int,
      smoothScrollAlignmentType: SmoothScrollAlignmentType,
  ) {
    scroller.startSmoothScrollWithOffset(index, offset, smoothScrollAlignmentType)
  }

  override fun scrollToId(id: Any, offset: Int) {
    scroller.scrollToPositionWithOffset(id, offset)
  }

  override fun scrollToIndex(index: Int, offset: Int) {
    scroller.scrollToPositionWithOffset(index, offset)
  }
}

/**
 * Scroll the [LazyCollection] by a given number of pixels. The Scroll is instant. For an animated
 * scroll use [smoothScrollBy].
 *
 * @param controller The controller for the [LazyCollection] being scrolled.
 * @param dx Distance to scroll along the x axis
 * @param dy Distance to scroll along the y axis
 */
@UiThread
fun ResourcesScope.scrollBy(controller: LazyCollectionController, dx: Dimen, dy: Dimen) =
    controller.scrollBy(dx.toPixels(), dy.toPixels())

/**
 * Perform and animated scroll on the [LazyCollection] by a given number of pixels. For an
 * instantaneous scroll use [scrollBy].
 *
 * @param controller The controller for the [LazyCollection] being scrolled.
 * @param dx Distance to scroll along the x axis
 * @param dy Distance to scroll along the y axis
 */
@UiThread
fun ResourcesScope.smoothScrollBy(controller: LazyCollectionController, dx: Dimen, dy: Dimen) =
    controller.smoothScrollBy(dx.toPixels(), dy.toPixels())

/**
 * Scroll the [LazyCollection] so that the child at the given index is fully visible. For an
 * animated scroll use [smoothScrollToIndex].
 *
 * @param controller The controller for the [LazyCollection] being scrolled.
 * @param index The index of the child to scroll to
 * @param offset Attempt to offset the child by this distance from the start of the Collection.
 */
@UiThread
fun ResourcesScope.scrollToIndex(
    controller: LazyCollectionController,
    index: Int,
    offset: Dimen = 0.px,
) = controller.scrollToIndex(index, offset.toPixels())

/**
 * Perform an animated scroll on the [LazyCollection] so that the child at the given index is fully
 * visible. For an instantaneous scroll use [scrollToIndex].
 *
 * @param controller The controller for the [LazyCollection] being scrolled.
 * @param index The index of the child to scroll to
 * @param offset Attempt to offset the child by this distance from the start of the Collection.
 * @param smoothScrollAlignmentType Attempt to position the child based on this alignment type.
 */
@UiThread
fun ResourcesScope.smoothScrollToIndex(
    controller: LazyCollectionController,
    index: Int,
    offset: Dimen = 0.px,
    smoothScrollAlignmentType: SmoothScrollAlignmentType = SmoothScrollAlignmentType.DEFAULT,
) = controller.smoothScrollToIndex(index, offset.toPixels(), smoothScrollAlignmentType)

/**
 * Scroll the [LazyCollection] so that the child with the given id is fully visible. For an animated
 * scroll use [smoothScrollToId].
 *
 * @param controller The controller for the [LazyCollection] being scrolled.
 * @param id The id of the child to scroll to
 * @param offset Attempt to offset the child by this distance from the start of the Collection.
 */
@UiThread
fun ResourcesScope.scrollToId(
    controller: LazyCollectionController,
    id: Any,
    offset: Dimen = 0.px,
) = controller.scrollToId(id, offset.toPixels())

/**
 * Perform an animated scroll on the [LazyCollection] so that the child with the given id is fully
 * visible. For an animated scroll use [smoothScrollToId].
 *
 * @param controller The controller for the [LazyCollection] being scrolled.
 * @param id The id of the child to scroll to
 * @param offset Attempt to offset the child this distance from the start of the Collection.
 * @param smoothScrollAlignmentType Attempt to position the child based on this alignment type.
 */
@UiThread
fun ResourcesScope.smoothScrollToId(
    controller: LazyCollectionController,
    id: Any,
    offset: Dimen = 0.px,
    smoothScrollAlignmentType: SmoothScrollAlignmentType = SmoothScrollAlignmentType.DEFAULT,
) = controller.smoothScrollToId(id, offset.toPixels(), smoothScrollAlignmentType)
