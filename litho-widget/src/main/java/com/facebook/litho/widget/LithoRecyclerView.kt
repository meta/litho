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
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RestrictTo
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.BaseMountingView
import com.facebook.rendercore.extensions.VisibilityChangeReason

/** Extension of [RecyclerView] that allows to add more features needed for [Recycler] */
open class LithoRecyclerView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    RecyclerView(context, attrs, defStyle), HasPostDispatchDrawListener {

  /**
   * Listener that will be called before RecyclerView and LayoutManager layout is called.
   *
   * This is generally not recommended to be used unless you really know what you're doing because
   * it will be called on every layout pass. Other callbacks like onDataBound and onDataRendered may
   * be more appropriate.
   */
  fun interface OnBeforeLayoutListener {
    fun onBeforeLayout(recyclerView: RecyclerView)
  }

  /**
   * Listener that will be called after RecyclerView and LayoutManager layout is called.
   *
   * This is generally not recommended to be used unless you really know what you're doing because
   * it will be called on every layout pass. Other callbacks like onDataBound and onDataRendered may
   * be more appropriate. It is a good time to invoke code which needs to respond to child sizing
   * changes.
   */
  fun interface OnAfterLayoutListener {
    fun onAfterLayout(recyclerView: RecyclerView)
  }

  private var leftFadingEnabled = true
  private var rightFadingEnabled = true
  private var topFadingEnabled = true
  private var bottomFadingEnabled = true
  private var touchInterceptor: TouchInterceptor? = null
  private var postDispatchDrawListeners: MutableList<PostDispatchDrawListener>? = null
  private var onBeforeLayoutListener: OnBeforeLayoutListener? = null
  private var onAfterLayoutListener: OnAfterLayoutListener? = null

  /**
   * Whether a child this list keeps on screen only to animate it out is treated as gone: its
   * components get their invisible events as soon as it starts leaving, and nothing inside it
   * reports visible until it is back in the list or removed. It still mounts what comes into view.
   */
  @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  @set:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  var pausesChildrenAnimatingOut: Boolean = false
    set(value) {
      if (field == value) {
        return
      }
      field = value
      if (value) {
        updateLeavingChildren()
      } else {
        finishAllLeavingChildren()
      }
    }

  /** Children animating out, each with the Litho views it started leaving, in start order. */
  private val leavingChildren = HashMap<View, MutableList<BaseMountingView>>()

  // RecyclerView also lays out outside onLayout (e.g. from scrolls), so check before every draw.
  private val updateLeavingChildrenBeforeDraw = ViewTreeObserver.OnPreDrawListener {
    updateLeavingChildren()
    true
  }

  init {
    setTag(com.facebook.rendercore.R.id.rc_pooling_container, true)
  }

  /**
   * Set TouchInterceptor that will be used in [onInterceptTouchEvent] to determine how touch events
   * should be intercepted by this [RecyclerView]
   */
  open fun setTouchInterceptor(touchInterceptor: TouchInterceptor?) {
    this.touchInterceptor = touchInterceptor
  }

  fun setOnBeforeLayoutListener(onBeforeLayoutListener: OnBeforeLayoutListener?) {
    this.onBeforeLayoutListener = onBeforeLayoutListener
  }

  fun setOnAfterLayoutListener(onAfterLayoutListener: OnAfterLayoutListener?) {
    this.onAfterLayoutListener = onAfterLayoutListener
  }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    val touchInterceptor = touchInterceptor ?: return super.onInterceptTouchEvent(ev)
    val result = touchInterceptor.onInterceptTouchEvent(this, ev)
    return when (result) {
      TouchInterceptor.Result.INTERCEPT_TOUCH_EVENT -> true
      TouchInterceptor.Result.IGNORE_TOUCH_EVENT -> false
      TouchInterceptor.Result.CALL_SUPER -> super.onInterceptTouchEvent(ev)
    }
  }

  override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
    return super.dispatchKeyEvent(event)
  }

  public override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)
    postDispatchDrawListeners?.forEach { it.postDispatchDraw(childCount) }
  }

  override fun registerPostDispatchDrawListener(listener: PostDispatchDrawListener) {
    val listeners =
        postDispatchDrawListeners
            ?: ArrayList<PostDispatchDrawListener>().also { postDispatchDrawListeners = it }
    listeners.add(listener)
  }

  override fun unregisterPostDispatchDrawListener(listener: PostDispatchDrawListener) {
    postDispatchDrawListeners?.remove(listener)
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    onBeforeLayoutListener?.onBeforeLayout(this)
    super.onLayout(changed, l, t, r, b)
    updateLeavingChildren()
    onAfterLayoutListener?.onAfterLayout(this)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnPreDrawListener(updateLeavingChildrenBeforeDraw)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    viewTreeObserver.removeOnPreDrawListener(updateLeavingChildrenBeforeDraw)
    finishAllLeavingChildren()
  }

  override fun onChildDetachedFromWindow(child: View) {
    super.onChildDetachedFromWindow(child)
    finishLeaving(child)
  }

  /**
   * RecyclerView keeps a child it is animating out as a child its layout manager doesn't list, so
   * those are the leaving children. One count comparison when nothing animates out.
   */
  private fun updateLeavingChildren() {
    // A detached list may never be attached again, and then nothing would finish a pause.
    if (!pausesChildrenAnimatingOut || !isAttachedToWindow) {
      return
    }
    val layoutManager = layoutManager
    if (layoutManager == null) {
      finishAllLeavingChildren()
      return
    }
    if (layoutManager.childCount == childCount && leavingChildren.isEmpty()) {
      return
    }
    val listed = HashSet<View>(layoutManager.childCount)
    for (i in 0 until layoutManager.childCount) {
      layoutManager.getChildAt(i)?.let { listed.add(it) }
    }
    for (child in leavingChildren.keys.toList()) {
      if (child.parent !== this) {
        finishLeaving(child)
      } else if (child in listed) {
        // Back in the list: it was laid out while paused, so nothing has reported it visible yet.
        finishLeaving(child).forEach {
          it.notifyVisibleBoundsChanged(VisibilityChangeReason.ANIMATION)
        }
      }
    }
    // Starting fires invisible events, which can end animations and remove children.
    val children = List(childCount) { getChildAt(it) }
    for (child in children) {
      if (child.parent === this && child !in listed && child !in leavingChildren) {
        startLeaving(child)
      }
    }
  }

  // Recorded before each start: a start fires invisible events, one of them can end this child's
  // animation, and finishing it finishes only what was recorded.
  private fun startLeaving(child: View) {
    val started = ArrayList<BaseMountingView>()
    leavingChildren[child] = started
    for (mountingView in outermostMountingViewsIn(child)) {
      if (leavingChildren[child] !== started) {
        return
      }
      started.add(mountingView)
      mountingView.startLeavingParent()
    }
  }

  private fun finishLeaving(child: View): List<BaseMountingView> {
    val started = leavingChildren.remove(child) ?: return emptyList()
    started.forEach { it.finishLeavingParent() }
    return started
  }

  private fun finishAllLeavingChildren() {
    for (child in leavingChildren.keys.toList()) {
      finishLeaving(child)
    }
  }

  fun setLeftFadingEnabled(leftFadingEnabled: Boolean) {
    this.leftFadingEnabled = leftFadingEnabled
  }

  fun setRightFadingEnabled(rightFadingEnabled: Boolean) {
    this.rightFadingEnabled = rightFadingEnabled
  }

  fun setTopFadingEnabled(topFadingEnabled: Boolean) {
    this.topFadingEnabled = topFadingEnabled
  }

  fun setBottomFadingEnabled(bottomFadingEnabled: Boolean) {
    this.bottomFadingEnabled = bottomFadingEnabled
  }

  public override fun getLeftFadingEdgeStrength(): Float {
    return if (leftFadingEnabled) {
      super.getLeftFadingEdgeStrength()
    } else {
      0f
    }
  }

  public override fun getRightFadingEdgeStrength(): Float {
    return if (rightFadingEnabled) {
      super.getRightFadingEdgeStrength()
    } else {
      0f
    }
  }

  public override fun getTopFadingEdgeStrength(): Float {
    return if (topFadingEnabled) {
      super.getTopFadingEdgeStrength()
    } else {
      0f
    }
  }

  public override fun getBottomFadingEdgeStrength(): Float {
    return if (bottomFadingEnabled) {
      super.getBottomFadingEdgeStrength()
    } else {
      0f
    }
  }

  /** Allows to override [onInterceptTouchEvent] behavior */
  fun interface TouchInterceptor {
    enum class Result {
      /** Return true without calling `super.onInterceptTouchEvent()` */
      INTERCEPT_TOUCH_EVENT,
      /** Return false without calling `super.onInterceptTouchEvent()` */
      IGNORE_TOUCH_EVENT,
      /** Returns `super.onInterceptTouchEvent()` */
      CALL_SUPER,
    }

    /** Called from [.onInterceptTouchEvent] to determine how touch events should be intercepted */
    fun onInterceptTouchEvent(recyclerView: RecyclerView, ev: MotionEvent): Result
  }

  private companion object {
    /** The outermost views hosting Litho content in [view]; everything inside them follows them. */
    fun outermostMountingViewsIn(view: View): List<BaseMountingView> {
      val mountingViews = ArrayList<BaseMountingView>()
      val toVisit = ArrayDeque<View>().apply { add(view) }
      while (toVisit.isNotEmpty()) {
        val next = toVisit.removeFirst()
        if (next is BaseMountingView) {
          mountingViews.add(next)
        } else if (next is ViewGroup) {
          for (i in 0 until next.childCount) {
            toVisit.add(next.getChildAt(i))
          }
        }
      }
      return mountingViews
    }
  }
}
