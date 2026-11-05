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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.facebook.litho.BaseMountingView
import com.facebook.litho.HasLithoViewChildren
import com.facebook.litho.LithoView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventDispatcher.beginTrace
import com.facebook.rendercore.debug.DebugEventDispatcher.dispatch
import com.facebook.rendercore.debug.DebugEventDispatcher.endTrace
import com.facebook.rendercore.debug.DebugEventDispatcher.generateTraceIdentifier

/**
 * Wrapper that encapsulates all the features [Recycler] provides such as sticky header and
 * pull-to-refresh
 */
@SuppressLint("ViewConstructor")
open class SectionsRecyclerView(context: Context, recyclerView: RecyclerView) :
    SwipeRefreshLayout(context), HasLithoViewChildren {

  val recyclerView: RecyclerView
  var stickyHeaderView: View? = null
    set(value) {
      field?.let { oldView ->
        if (value != null) {
          throw IllegalStateException("The sticky header view is already initialized.")
        }
        removeView(oldView)
      } ?: value?.let { addView(it) }
      field = value
    }

  val defaultEdgeEffectFactory: RecyclerView.EdgeEffectFactory
  // TODO: Calling layoutStickyHeader() here may cause a double layout pass per frame
  //  since StickyHeaderControllerImpl::onScroll also triggers layout. Investigate applying the
  //  offset without an extra layout pass (e.g., via translationY), but note that
  //  StickyHeaderControllerImpl continuously overwrites translationY.
  var stickyHeaderTopOffset: Int = 0
    set(value) {
      if (field != value) {
        field = value
        layoutStickyHeader()
      }
    }

  val isStickyHeaderHidden: Boolean
    get() = stickyHeaderView?.isGone == true

  /**
   * Makes [bringChildToFront] skip a child that is already last. `SwipeRefreshLayout` brings its
   * spinner to the front on each touch down at the top of a pull-to-refresh list, and each of those
   * no-op reorders lays out every ancestor again. Off by default. Litho turns it off when it
   * unmounts the list, so a pooled view comes back with it off; new props or a new measure keep it.
   */
  var skipsRedundantBringToFront: Boolean = false

  internal var onKeyboardRefresh: (() -> Unit)? = null

  private var keyboardRefreshPresses: ArrayList<KeyboardRefreshPress>? = null

  private var sectionsRecyclerViewLogger: SectionsRecyclerViewLogger? = null
  private var isFirstLayout = true
  private val traceAttributes: MutableMap<String, Any> = LinkedHashMap()
  private val bounds: Rect = Rect()

  /**
   * Indicates whether [RecyclerView] has been detached. In such case we need to make sure to
   * relayout its children eventually.
   */
  private var hasBeenDetachedFromWindow = false

  /**
   * When we set an ItemAnimator during mount, we want to store the one that was already set on the
   * RecyclerView so that we can reset it during unmount.
   */
  private var detachedItemAnimator: RecyclerView.ItemAnimator? = null

  init {
    val handler: ((Any) -> Unit)? =
        ComponentsConfiguration.defaultInstance.sectionsRecyclerViewOnCreateHandler
    handler?.invoke(this)

    this.recyclerView = recyclerView
    // Get the default edge effect factory (at least the default one in the initialized recycler)
    defaultEdgeEffectFactory = recyclerView.edgeEffectFactory

    // We need to draw first visible item on top of other children to support sticky headers
    recyclerView.setChildDrawingOrderCallback { childCount, i -> childCount - 1 - i }
    // ViewCache doesn't work well with RecyclerBinder which assumes that whenever item comes back
    // to viewport it should be rebound which does not happen with ViewCache. Consider this case:
    // LithoView goes out of screen and it is added to ViewCache, then its ComponentTree is assigned
    // to another LV which means our LithoView's ComponentTree reference is nullified. It comes back
    // to screen and it is not rebound therefore we will see 0 height LithoView which actually
    // happened in multiple product surfaces. Disabling it fixes the issue.
    recyclerView.setItemViewCacheSize(0)
    addView(this.recyclerView)
  }

  fun setSectionsRecyclerViewLogger(lithoViewLogger: SectionsRecyclerViewLogger?) {
    sectionsRecyclerViewLogger = lithoViewLogger
  }

  fun setItemAnimator(itemAnimator: RecyclerView.ItemAnimator?) {
    detachedItemAnimator = recyclerView.itemAnimator
    recyclerView.itemAnimator = LazyCollectionItemAnimator.resolve(itemAnimator)
  }

  fun resetItemAnimator() {
    recyclerView.itemAnimator = detachedItemAnimator
    detachedItemAnimator = null
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    measureStickyHeader(MeasureSpec.getSize(widthMeasureSpec))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    dispatch(
        type = DebugEvent.ViewOnLayout + ":start",
        renderStateId = { "-1" },
        attributesAccumulator = { attrs ->
          attrs[DebugEventAttribute.Id] = hashCode()
          attrs[DebugEventAttribute.Name] = "SectionsRecyclerView"
        },
    )

    val traceId = generateTraceIdentifier(DebugEvent.ViewOnLayout)
    if (traceId != null) {
      bounds.set(left, top, right, bottom)
      traceAttributes.clear()
      traceAttributes[DebugEventAttribute.Id] = hashCode()
      traceAttributes[DebugEventAttribute.Name] = "SectionsRecyclerView"
      traceAttributes[DebugEventAttribute.Bounds] = bounds
      beginTrace(traceId, DebugEvent.ViewOnLayout, "-1", traceAttributes)
    }

    try {
      sectionsRecyclerViewLogger?.onLayoutStarted(isFirstLayout)
      super.onLayout(changed, left, top, right, bottom)
      layoutStickyHeader()
    } finally {
      sectionsRecyclerViewLogger?.onLayoutEnded(isFirstLayout)
      isFirstLayout = false
      if (traceId != null) {
        endTrace(traceId)
      }
    }
  }

  fun measureStickyHeader(parentWidth: Int) {
    val view = stickyHeaderView ?: return
    measureChild(
        view,
        MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
        MeasureSpec.UNSPECIFIED,
    )
  }

  fun layoutStickyHeader() {
    val view = stickyHeaderView ?: return
    if (isStickyHeaderHidden) {
      return
    }

    val stickyHeaderLeft = paddingLeft
    val stickyHeaderTop = paddingTop + stickyHeaderTopOffset
    view.layout(
        stickyHeaderLeft,
        stickyHeaderTop,
        stickyHeaderLeft + view.measuredWidth,
        stickyHeaderTop + view.measuredHeight,
    )
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    keyboardRefreshPresses = null
    hasBeenDetachedFromWindow = true
  }

  fun hasBeenDetachedFromWindow(): Boolean {
    return hasBeenDetachedFromWindow
  }

  fun setHasBeenDetachedFromWindow(hasBeenDetachedFromWindow: Boolean) {
    this.hasBeenDetachedFromWindow = hasBeenDetachedFromWindow
  }

  override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
    super.requestDisallowInterceptTouchEvent(disallowIntercept)

    // SwipeRefreshLayout can ignore this request if nested scrolling is disabled on the child,
    // but it fails to delegate the request up to the parents.
    // This fixes a bug that can cause parents to improperly intercept scroll events from
    // nested recyclers.
    if (parent != null && !isNestedScrollingEnabled) {
      parent.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
  }

  override fun bringChildToFront(child: View) {
    // ViewGroup requests a layout even when the child is already last and nothing moves.
    if (skipsRedundantBringToFront && getChildAt(childCount - 1) === child) {
      return
    }
    super.bringChildToFront(child)
  }

  override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
    if (event == null) {
      return false
    }
    if (consumeKeyboardRefreshPress(event)) {
      return true
    }
    if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_REFRESH) {
      return focusedChild?.dispatchKeyEvent(event) ?: false
    }
    if (super.dispatchKeyEvent(event)) {
      return true
    }
    // A child may have redispatched and claimed this press.
    if (consumeKeyboardRefreshPress(event)) {
      return true
    }

    val refresh = onKeyboardRefresh ?: return false
    if (
        !isEnabled ||
            !recyclerView.isEnabled ||
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount != 0 ||
            event.isCanceled ||
            !isRefreshShortcut(event) ||
            !hasRefreshKeyboardFocus()
    ) {
      return false
    }

    val presses =
        keyboardRefreshPresses
            ?: ArrayList<KeyboardRefreshPress>().also {
              keyboardRefreshPresses = it
            }
    presses.add(KeyboardRefreshPress(event.deviceId, event.keyCode, event.downTime))
    if (!isRefreshing) {
      isRefreshing = true
      refresh()
    }
    return true
  }

  private fun hasRefreshKeyboardFocus(): Boolean {
    val focused = findFocus() ?: return false
    return !focused.onCheckIsTextEditor()
  }

  private fun isRefreshShortcut(event: KeyEvent): Boolean =
      when (event.keyCode) {
        KeyEvent.KEYCODE_R -> event.hasModifiers(KeyEvent.META_CTRL_ON)
        KeyEvent.KEYCODE_F5 ->
            KeyEvent.metaStateHasNoModifiers(event.metaState and KeyEvent.META_FUNCTION_ON.inv())
        KeyEvent.KEYCODE_REFRESH -> true
        else -> false
      }

  private fun consumeKeyboardRefreshPress(event: KeyEvent): Boolean {
    val presses = keyboardRefreshPresses ?: return false
    val index = presses.indexOfFirst {
      it.deviceId == event.deviceId && it.keyCode == event.keyCode
    }
    if (index < 0) {
      return false
    }
    // Chording can change downTime on repeats and UP; only an initial DOWN replaces a stale owner.
    if (
        event.action == KeyEvent.ACTION_UP ||
            (event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0 &&
                (event.isCanceled || presses[index].downTime != event.downTime))
    ) {
      presses.removeAt(index)
      if (presses.isEmpty()) {
        keyboardRefreshPresses = null
      }
      return event.action == KeyEvent.ACTION_UP
    }
    return event.action == KeyEvent.ACTION_DOWN
  }

  private class KeyboardRefreshPress(val deviceId: Int, val keyCode: Int, val downTime: Long)

  @SuppressLint("ClickableViewAccessibility")
  override fun setOnTouchListener(listener: OnTouchListener?) {
    // When setting touch handler for RecyclerSpec we want RecyclerView to handle it.
    recyclerView.setOnTouchListener(listener)
  }

  override fun obtainLithoViewChildren(lithoViews: MutableList<BaseMountingView>) {
    val view = stickyHeaderView as? LithoView ?: return
    lithoViews.add(view)
    val size = recyclerView.childCount
    for (i in 0 until size) {
      val child = recyclerView.getChildAt(i)
      if (child is LithoView) {
        lithoViews.add(child)
      }
    }
  }

  /** Pass to a SectionsRecyclerView to do custom logging. */
  interface SectionsRecyclerViewLogger {
    fun onLayoutStarted(isFirstLayoutAfterRecycle: Boolean)

    fun onLayoutEnded(isFirstLayoutAfterRecycle: Boolean)
  }

  companion object {
    @JvmStatic
    fun getParentRecycler(recyclerView: RecyclerView): SectionsRecyclerView? {
      if (recyclerView.parent is SectionsRecyclerView) {
        return recyclerView.parent as SectionsRecyclerView
      }
      return null
    }
  }
}
