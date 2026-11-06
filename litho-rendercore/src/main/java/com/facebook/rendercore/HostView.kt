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

package com.facebook.rendercore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.children

private const val INITIAL_MOUNT_ITEMS_SIZE = 8

/** A ViewGroup that can be used as a host for subtrees in a RenderCore tree. */
open class HostView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    Host(context, attrs) {

  private val _dispatchDraw = InterleavedDispatchDraw()
  private val interceptTouchEventHandlers: MutableList<InterceptTouchHandler> by lazy {
    mutableListOf()
  }

  private var mountItems: Array<MountItem?> = arrayOfNulls(INITIAL_MOUNT_ITEMS_SIZE)
  private var _childDrawingOrder = IntArray(0)
  private var isChildDrawingOrderDirty = false
  private var inLayout = false

  private var onInterceptTouchEventHandler: InterceptTouchHandler? = null
  private var scrapMountItemsArray: Array<MountItem?>? = null
  private var viewTag: Any? = null
  private var viewTags: SparseArray<Any?>? = null

  // use different name to avoid name collision with View API
  private var _foreground: Drawable? = null

  init {
    setWillNotDraw(false)
    isChildrenDrawingOrderEnabled = true
    // A host never scrolls its own content. Scrollbars a theme turns on for every view would
    // invalidate the host on each draw, because verifyDrawable accepts the scrollbar drawable.
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
  }

  override fun mount(index: Int, mountItem: MountItem) {
    checkNotVisitingMountItems()
    if (mountItem.renderUnit.renderType == RenderType.DRAWABLE) {
      mountDrawable(mountItem)
    } else {
      mountView(mountItem)
    }
    ensureSize(index)
    mountItems[index] = mountItem
    mountItem.host = this
  }

  private fun ensureSize(index: Int) {
    if (index >= mountItems.size) {
      var newLength = mountItems.size * 2
      while (index >= newLength) {
        newLength *= 2
      }
      val tmp = arrayOfNulls<MountItem>(newLength)
      System.arraycopy(mountItems, 0, tmp, 0, mountItems.size)
      mountItems = tmp
    }
  }

  override fun unmount(mountItem: MountItem) {
    val index = findItemIndex(mountItem)
    unmount(index, mountItem)
    mountItem.host = null
  }

  private fun findItemIndex(item: MountItem): Int {
    val mountItemsIndex = findItemIndexInArray(item, mountItems)
    if (mountItemsIndex > -1) {
      return mountItemsIndex
    }
    if (scrapMountItemsArray != null) {
      val scrapItemsIndex = findItemIndexInArray(item, scrapMountItemsArray)
      if (scrapItemsIndex > -1) {
        return scrapItemsIndex
      }
    }
    throw IllegalStateException(
        """
        Mount item was not found in the list of mounted items.
        Item to remove: ${item.renderTreeNode.generateDebugString(null)}
        Mounted items: ${getDescriptionOfMountedItems(mountItems)}
        Scraped items: ${getDescriptionOfMountedItems(scrapMountItemsArray)}
        """
            .trimIndent(),
    )
  }

  override fun unmount(index: Int, mountItem: MountItem) {
    checkNotVisitingMountItems()
    if (mountItem.renderUnit.renderType == RenderType.DRAWABLE) {
      unmountDrawable(mountItem)
    } else {
      unmountView(mountItem)
      isChildDrawingOrderDirty = true
    }
    MountUtils.removeItem(index, mountItems, scrapMountItemsArray)
    releaseScrapDataStructuresIfNeeded()
  }

  override val mountItemCount: Int
    get() {
      var size = 0
      for (i in mountItems.indices) {
        if (mountItems[i] != null) {
          size++
        }
      }
      return size
    }

  override fun getMountItemAt(index: Int): MountItem =
      mountItems[index] ?: throw IllegalArgumentException("No MountItem exists at position $index")

  override fun visitMountItemsUnguarded(visitor: MountItemVisitor): Boolean {
    for (item in mountItems) {
      if (item != null && !visitor.visit(item)) return false
    }
    scrapMountItemsArray?.let { scrap ->
      for (item in scrap) {
        if (item != null && !visitor.visit(item)) return false
      }
    }
    return true
  }

  override fun moveItem(item: MountItem?, oldIndex: Int, newIndex: Int) {
    checkNotVisitingMountItems()
    val mountItem: MountItem = item ?: scrapMountItemsArray?.get(oldIndex) ?: return

    val content = mountItem.content
    invalidate()
    if (mountItem.renderUnit.renderType == RenderType.VIEW) {
      isChildDrawingOrderDirty = true
      startTemporaryDetach(content as View)
    }
    ensureSize(newIndex)
    if (mountItems[newIndex] != null) {
      ensureScrapMountItemsArray()
      MountUtils.scrapItemAt(newIndex, mountItems, scrapMountItemsArray)
    }
    MountUtils.moveItem(oldIndex, newIndex, mountItems, scrapMountItemsArray)
    releaseScrapDataStructuresIfNeeded()
    if (mountItem.renderUnit.renderType == RenderType.VIEW) {
      finishTemporaryDetach(content as View)
    }
  }

  /**
   * Sets an InterceptTouchHandler that will be invoked when [HostView#onInterceptTouchEvent] is
   * called.
   *
   * @param interceptTouchEventHandler the handler to be set on this host.
   */
  fun setInterceptTouchEventHandler(interceptTouchEventHandler: InterceptTouchHandler?) {
    onInterceptTouchEventHandler = interceptTouchEventHandler
  }

  /**
   * Registers an InterceptTouchHandler that will be invoked when [HostView#onInterceptTouchEvent]
   * is called.
   *
   * Event would be treated as intercepted if any of the registered handlers, including ones set by
   * previous [setInterceptTouchEventHandler] returns true.
   *
   * @param interceptTouchEventHandler the handler to be registered on this host.
   */
  fun registerTouchEventHandler(interceptTouchEventHandler: InterceptTouchHandler) {
    interceptTouchEventHandlers.add(interceptTouchEventHandler)
  }

  /**
   * Unregisters an InterceptTouchHandler that was previously registered by
   * [registerTouchEventHandler].
   */
  fun unregisterTouchEventHandler(interceptTouchEventHandler: InterceptTouchHandler) {
    interceptTouchEventHandlers.remove(interceptTouchEventHandler)
  }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    var intercepted: Boolean? = null
    for (handler in interceptTouchEventHandlers) {
      if (handler.onInterceptTouchEvent(this, ev) == true) {
        intercepted = true
      }
    }
    return onInterceptTouchEventHandler?.onInterceptTouchEvent(this, ev)
        ?: intercepted
        ?: super.onInterceptTouchEvent(ev)
  }

  private fun mountView(mountItem: MountItem) {
    val view = mountItem.content as View
    isChildDrawingOrderDirty = true

    // A host has been recycled and is already attached.
    if (view is HostView && view.getParent() === this) {
      finishTemporaryDetach(view)
      view.setVisibility(View.VISIBLE)
      return
    }
    var lp: LayoutParams? = view.layoutParams
    if (lp == null) {
      lp = generateDefaultLayoutParams()
      view.layoutParams = lp
    }
    if (inLayout) {
      super.addViewInLayout(view, -1, view.layoutParams, true)
    } else {
      super.addView(view, -1, view.layoutParams)
    }
  }

  private fun unmountView(mountItem: MountItem) {
    val view = mountItem.content as View
    isChildDrawingOrderDirty = true

    // Sometime a view is not getting it's 'pressed' state reset before unmount, causing that state
    // to not be cleared and carried to next reuse, therefore applying wrong drawable state.
    // Particular case where this might happen is when view is unmounted as soon as click event
    // is triggered.
    if (view.isPressed) {
      view.isPressed = false
    }
    if (inLayout) {
      super.removeViewInLayout(view)
    } else {
      super.removeView(view)
    }
  }

  public override fun dispatchDraw(canvas: Canvas) {
    _dispatchDraw.start(canvas)
    super.dispatchDraw(canvas)

    // Cover the case where the host has no child views, in which case
    // getChildDrawingOrder() will not be called and the draw index will not
    // be incremented. This will also cover the case where drawables must be
    // painted after the last child view in the host.
    if (_dispatchDraw.isRunning) {
      _dispatchDraw.drawNext()
    }
    _dispatchDraw.end()
  }

  override fun getChildDrawingOrder(childCount: Int, i: Int): Int {
    updateChildDrawingOrderIfNeeded()

    // This method is called in very different contexts within a ViewGroup
    // e.g. when handling input events, drawing, etc. We only want to call
    // the draw methods if the InterleavedDispatchDraw is active.
    if (_dispatchDraw.isRunning) {
      _dispatchDraw.drawNext()
    }
    return _childDrawingOrder[i]
  }

  override fun shouldDelayChildPressedState(): Boolean = false

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    var handled = false
    if (isEnabled) {
      // Iterate drawable from last to first to respect drawing order.
      for (i in mountItems.size - 1 downTo 0) {
        val item = mountItems[i]
        if (
            item != null &&
                item.renderUnit.renderType == RenderType.DRAWABLE &&
                item.content is Touchable
        ) {
          val t = item.content
          if (t.shouldHandleTouchEvent(event) && t.onTouchEvent(event, this)) {
            handled = true
            break
          }
        }
      }
    }
    if (!handled) {
      handled = super.onTouchEvent(event)
    }
    return handled
  }

  open fun performLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    inLayout = true
    performLayout(changed, l, t, r, b)
    inLayout = false
  }

  override fun requestLayout() {
    // Don't request a layout if it will be blocked by any parent. Requesting a layout that is
    // then ignored by an ancestor means that this host will remain in a state where it thinks that
    // it has requested layout, and will therefore ignore future layout requests. This will lead to
    // problems if a child (e.g. a ViewPager) requests a layout later on, since the request will be
    // wrongly ignored by this host.
    var parent: ViewParent? = this
    while (parent is HostView) {
      val host = parent
      if (!host.shouldRequestLayout()) {
        return
      }
      parent = parent.getParent()
    }

    super.requestLayout()
  }

  protected fun shouldRequestLayout(): Boolean =
      // Don't bubble during layout.
      !inLayout

  @SuppressLint("MissingSuperCall") override fun verifyDrawable(who: Drawable): Boolean = true

  @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
  public override fun drawableStateChanged() {
    super.drawableStateChanged()
    mountItems.forEach { mountItem ->
      if (mountItem != null && mountItem.renderUnit.renderType == RenderType.DRAWABLE) {
        MountUtils.maybeSetDrawableState(this, mountItem.content as Drawable)
      }
    }
    _foreground?.let { it.state = drawableState }
  }

  override fun jumpDrawablesToCurrentState() {
    super.jumpDrawablesToCurrentState()
    mountItems.forEach { mountItem ->
      if (mountItem != null && mountItem.renderUnit.renderType == RenderType.DRAWABLE) {
        val drawable = mountItem.content as Drawable
        DrawableCompat.jumpToCurrentState(drawable)
      }
    }
    _foreground?.jumpToCurrentState()
  }

  override fun setVisibility(visibility: Int) {
    super.setVisibility(visibility)
    mountItems.forEach { mountItem ->
      if (mountItem != null && mountItem.renderUnit.renderType == RenderType.DRAWABLE) {
        val drawable = mountItem.content as Drawable
        drawable.setVisible(visibility == View.VISIBLE, false)
      }
    }
  }

  /**
   * Sets view tag on this host.
   *
   * @param viewTag the object to set as tag.
   */
  fun setViewTag(viewTag: Any?) {
    this.viewTag = viewTag
  }

  /**
   * Sets view tags on this host.
   *
   * @param viewTags the map containing the tags by id.
   */
  fun setViewTags(viewTags: SparseArray<Any?>) {
    this.viewTags = viewTags
  }

  override fun getTag(): Any? = viewTag ?: super.getTag()

  override fun getTag(key: Int): Any? {
    return viewTags?.get(key) ?: super.getTag(key)
  }

  private fun updateChildDrawingOrderIfNeeded() {
    if (!isChildDrawingOrderDirty) {
      return
    }
    val childCount = childCount
    if (_childDrawingOrder.size < childCount) {
      _childDrawingOrder = IntArray(childCount + 5)
    }
    var index = 0
    val mountItemCount = mountItems.size
    for (i in 0 until mountItemCount) {
      val mountItem = mountItems[i]
      if (mountItem != null && mountItem.renderUnit.renderType == RenderType.VIEW) {
        val child = mountItem.content as View
        _childDrawingOrder[index++] = indexOfChild(child)
      }
    }
    isChildDrawingOrderDirty = false
  }

  private fun ensureScrapMountItemsArray() {
    if (scrapMountItemsArray == null) {
      scrapMountItemsArray = arrayOfNulls(mountItems.size)
    }
  }

  private fun releaseScrapDataStructuresIfNeeded() {
    if (isEmpty(scrapMountItemsArray)) {
      scrapMountItemsArray = null
    }
  }

  private fun mountDrawable(mountItem: MountItem) {
    val drawable = mountItem.content as Drawable
    MountUtils.mountDrawable(this, drawable)
    invalidate(mountItem.renderTreeNode.bounds)
  }

  private fun unmountDrawable(mountItem: MountItem) {
    val drawable = mountItem.content as Drawable
    drawable.callback = null
    invalidate(drawable.bounds)
  }

  /**
   * Encapsulates the logic for drawing a set of views and drawables respecting their drawing order
   * withing the component host i.e. allow interleaved views and drawables to be drawn with the
   * correct z-index.
   */
  private inner class InterleavedDispatchDraw {

    private var canvas: Canvas? = null
    private var drawIndex = 0
    private var itemsToDraw = 0

    fun start(canvas: Canvas) {
      this.canvas = canvas
      drawIndex = 0
      itemsToDraw = mountItems.size
    }

    val isRunning: Boolean
      get() = canvas != null && drawIndex < itemsToDraw

    fun drawNext() {
      if (canvas == null) {
        return
      }
      var i = drawIndex
      val size = mountItems.size
      while (i < size) {
        val mountItem = mountItems[i]
        if (mountItem == null) {
          i++
          continue
        }

        // During a ViewGroup's dispatchDraw() call with children drawing order enabled,
        // getChildDrawingOrder() will be called before each child view is drawn. This
        // method will only draw the drawables "between" the child views and the let
        // the host draw its children as usual. This is why views are skipped here.
        if (mountItem.renderUnit.renderType == RenderType.VIEW) {
          drawIndex = i + 1
          return
        }
        if (!mountItem.isBound) {
          i++
          continue
        }
        val drawable = mountItem.content as? Drawable
        val currentCanvas = canvas
        if (drawable != null && currentCanvas != null) {
          drawable.draw(currentCanvas)
        }
        i++
      }
      drawIndex = itemsToDraw
    }

    fun end() {
      canvas = null
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    _foreground?.setBounds(0, 0, right, bottom)
  }

  override fun draw(canvas: Canvas) {
    super.draw(canvas)
    _foreground?.draw(canvas)
  }

  fun setForegroundCompat(drawable: Drawable?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      MarshmallowHelper.setForeground(this, drawable)
    } else {
      setForegroundLollipop(drawable)
    }
  }

  /**
   * Copied over from FrameLayout#setForeground from API Version 16 with some differences: supports
   * only fill gravity and does not support padded foreground
   */
  private fun setForegroundLollipop(newForeground: Drawable?) {
    if (_foreground !== newForeground) {
      _foreground?.let { foreground ->
        foreground.callback = null
        this.unscheduleDrawable(foreground)
      }
      _foreground = newForeground
      if (newForeground != null) {
        newForeground.callback = this
        if (newForeground.isStateful) {
          newForeground.state = drawableState
        }
      }
      invalidate()
    }
  }

  @VisibleForTesting
  override val descriptionOfMountedItems: String
    get() {
      return buildString {
        append("\nMounted Items")
        append(getDescriptionOfMountedItems(mountItems))
        append("\nScraped Items: ")
        append(getDescriptionOfMountedItems(scrapMountItemsArray))
      }
    }

  private fun getDescriptionOfMountedItems(items: Array<MountItem?>?): String {
    if (items == null) {
      return "<null>"
    }
    val builder = StringBuilder()
    for (i in items.indices) {
      val item = items[i]
      if (item != null) {
        builder
            .append("Item at index: ")
            .append(i)
            .append(" Type: ")
            .append(item.renderUnit.description)
            .append(" Position in parent: ")
            .append(item.renderTreeNode.positionInParent)
            .append("\n")
      } else {
        builder.append("Item at index: ").append(i).append(" item is null").append("\n")
      }
    }
    return builder.toString()
  }

  override fun safelyUnmountAll() {
    checkNotVisitingMountItems()
    safelyUnmountAll(mountItems)
    safelyUnmountAll(scrapMountItemsArray)
  }

  private fun safelyUnmountAll(items: Array<MountItem?>?) {
    if (items != null) {
      for (i in items.indices) {
        val item = items[i]
        if (item != null) {
          if (item.renderUnit.renderType == RenderType.DRAWABLE) {
            unmountDrawable(item)
          } else {
            unmountView(item)
            isChildDrawingOrderDirty = true
          }
        }
      }
    }
  }

  internal object MarshmallowHelper {
    @JvmStatic
    @RequiresApi(api = Build.VERSION_CODES.M)
    fun setForeground(hostView: HostView, newForeground: Drawable?) {
      hostView.foreground = newForeground
    }
  }

  override fun setInLayout() {
    inLayout = true
  }

  override fun unsetInLayout() {
    inLayout = false
  }

  companion object {
    private fun findItemIndexInArray(item: MountItem, array: Array<MountItem?>?): Int {
      return array?.indexOfFirst { it == item } ?: -1
    }

    private fun startTemporaryDetach(view: View) {
      // Cancel any pending clicks.
      view.cancelPendingInputEvents()

      // The HostView's parent will send an ACTION_CANCEL if it's going to receive
      // other motion events for the recycled child.
      ViewCompat.dispatchStartTemporaryDetach(view)
    }

    private fun finishTemporaryDetach(view: View) {
      ViewCompat.dispatchFinishTemporaryDetach(view)
    }

    private fun isEmpty(scrapMountItemsArray: Array<MountItem?>?): Boolean {
      return scrapMountItemsArray?.all { it == null } ?: true
    }

    @JvmStatic
    fun performLayoutOnChildrenIfNecessary(host: HostView) {
      host.children.forEach { child ->
        if (child.isLayoutRequested) {
          // The hosting view doesn't allow children to change sizes dynamically as
          // this would conflict with the component's own layout calculations.
          child.measure(
              MeasureSpec.makeMeasureSpec(child.width, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(child.height, MeasureSpec.EXACTLY),
          )
          child.layout(child.left, child.top, child.right, child.bottom)
        }
        if (child is HostView) {
          performLayoutOnChildrenIfNecessary(child)
        }
      }
    }
  }
}
