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

package com.facebook.litho.widget.zoomable

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.InterceptTouchEvent
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.TouchEvent
import com.facebook.litho.Wrapper
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.binders.onBindViewWithDescription
import com.facebook.litho.useCached
import com.facebook.litho.view.onInterceptTouch
import com.facebook.litho.view.onTouch
import com.facebook.rendercore.zoomable.ZoomableViewBaseController

/**
 * Pinch to zoom on [child]: while the fingers zoom it, a scaled copy of it is drawn over the
 * window, and when they lift the copy springs back. [child] keeps its size and stays in this tree.
 *
 * A `SurfaceView` in [child] scales and moves with the copy too on Android 15 and 16; other
 * versions aren't verified. The copy is drawn only inside the window [child] is in, so in a dialog
 * or a popup it is clipped to that window.
 *
 * No container is added: the touch handlers and binders go on the view [child] renders to, and an
 * [OverlayZoomController] is created for it only when a second finger lands on it. When that view
 * is a host, it takes the gesture from its content at the second finger, so content with its own
 * pinch or two-finger gestures, such as a map or an image viewer, stops getting them. A view that
 * isn't a host can't intercept touches, so a pinch on a part of it that handles touches doesn't
 * zoom. The view also keeps the touches nobody handles, so an ancestor's click handler doesn't see
 * those, unless [keepUnhandledTouches] is false: then those touches go on to the ancestors, and an
 * ancestor that handles them passes a pinch to [zoomableTouchGestureListener]. While zooming, the
 * enclosing LithoView keeps all its content mounted.
 */
@ExperimentalLithoApi
class Zoomable(
    private val zoomableTouchGestureListener: ZoomableTouchGestureListener? = null,
    private val zoomableEventListener: ZoomableViewBaseController.Listener? = null,
    private val keepUnhandledTouches: Boolean = true,
    private val style: Style = Style,
    private val child: () -> Component,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val zoomTarget = useCached { ZoomTarget() }
    return Wrapper(
        style =
            style +
                Style.onTouch(zoomTarget.onTouch)
                    .onInterceptTouch(true, zoomTarget.onInterceptTouch)
                    // Separate binders, so a new listener from a render doesn't end a zoom.
                    .onBindViewWithDescription({ "zoomable-target" }, zoomTarget) { view ->
                      zoomTarget.bindTo(view)
                      onUnbind { zoomTarget.unbind() }
                    }
                    .onBindViewWithDescription(
                        { "zoomable-listeners" },
                        zoomTarget,
                        zoomableTouchGestureListener,
                        zoomableEventListener,
                        keepUnhandledTouches,
                    ) { _ ->
                      zoomTarget.setListeners(
                          zoomableTouchGestureListener,
                          zoomableEventListener,
                          keepUnhandledTouches,
                      )
                      onUnbind { zoomTarget.clearListeners() }
                    },
    ) {
      child()
    }
  }
}

/**
 * The view a [Zoomable] zooms. It answers touches the way an idle [OverlayZoomController] would,
 * and creates one when a second finger lands on the view.
 */
@OptIn(ExperimentalLithoApi::class)
internal class ZoomTarget : ZoomableTouchListener {

  private var view: View? = null
  private var controller: OverlayZoomController? = null
  private var gestureListener: ZoomableTouchGestureListener? = null
  private var eventListener: ZoomableViewBaseController.Listener? = null
  private var keepsUnhandledTouches = true
  private val visibleRect = Rect()
  private val rootOnScreen = IntArray(2)

  /** A listener shared with other views gets the events, and passes them on to this target. */
  val onTouch: (TouchEvent) -> Boolean = { touchEvent ->
    val motionEvent = touchEvent.motionEvent
    gestureListener?.onTouch(motionEvent) ?: onTouchEvent(motionEvent)
  }

  val onInterceptTouch: (InterceptTouchEvent) -> Boolean = { interceptEvent ->
    val motionEvent = interceptEvent.motionEvent
    val existing = controller
    when {
      existing != null -> existing.onInterceptTouchEvent(motionEvent)
      motionEvent.isSecondFingerDown() && view?.containsRaw(motionEvent) == true ->
          createController()?.onInterceptTouchEvent(motionEvent) == true
      else -> false
    }
  }

  override val isCurrentlyInZoom: Boolean
    get() = controller?.zoomableTouchListener?.isCurrentlyInZoom == true

  fun bindTo(view: View) {
    this.view = view
  }

  // Unbinding ends a zoom and tells the event listener before the controller is dropped; the next
  // pinch on a bound view creates a new one. The target outlives its binding while the tree lives,
  // so
  // it drops the view, the controller and the event listener.
  fun unbind() {
    controller?.let { existing ->
      existing.unbind()
      existing.zoomableEventListener = null
    }
    controller = null
    eventListener = null
    view = null
  }

  fun setListeners(
      gestureListener: ZoomableTouchGestureListener?,
      eventListener: ZoomableViewBaseController.Listener?,
      keepsUnhandledTouches: Boolean,
  ) {
    this.gestureListener = gestureListener
    this.eventListener = eventListener
    this.keepsUnhandledTouches = keepsUnhandledTouches
    gestureListener?.addZoomableTouchListener(this)
    controller?.let { existing ->
      existing.zoomableEventListener = eventListener
      existing.keepsUnhandledTouches = keepsUnhandledTouches
    }
  }

  fun clearListeners() {
    gestureListener?.removeZoomableTouchListener(this)
    gestureListener = null
    keepsUnhandledTouches = true
    controller?.keepsUnhandledTouches = true
    // Kept during a zoom, so its end still reaches the listener when the view unbinds.
    if (!isCurrentlyInZoom) {
      eventListener = null
      controller?.zoomableEventListener = null
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val existing = controller
    if (existing != null && existing.zoomableTouchListener.isCurrentlyInZoom) {
      return existing.zoomableTouchListener.onTouchEvent(event)
    }
    val target = view ?: return false
    if (event.pointerCount == 2 && target.canZoom() && target.containsRaw(event)) {
      val zoomController = existing ?: createController() ?: return false
      val handled = zoomController.zoomableTouchListener.onTouchEvent(event)
      if (handled) {
        // The view's own click handling saw the first finger: the pinch must not click it.
        target.cancelOwnTouch(event)
      }
      return handled
    }
    // Keeps the gesture when nobody handles it, so its second finger reaches the view. A view that
    // handles touches itself keeps it anyway, and must still get its own taps.
    return keepsUnhandledTouches &&
        event.actionMasked == MotionEvent.ACTION_DOWN &&
        !target.isClickable &&
        !target.isLongClickable
  }

  private fun createController(): OverlayZoomController? {
    val target = view ?: return null
    if (!target.canZoom()) {
      return null
    }
    return OverlayZoomController(target.context).also { created ->
      created.bindTo(target)
      created.keepsUnhandledTouches = keepsUnhandledTouches
      created.zoomableEventListener = eventListener
      controller = created
    }
  }

  private fun MotionEvent.isSecondFingerDown(): Boolean =
      actionMasked == MotionEvent.ACTION_POINTER_DOWN && pointerCount == 2

  // The copy is drawn in the overlay of the view's root, which must not be the view itself.
  private fun View.canZoom(): Boolean = rootView.let { root -> root is ViewGroup && root !== this }

  private fun View.containsRaw(event: MotionEvent): Boolean {
    if (!getGlobalVisibleRect(visibleRect)) {
      return false
    }
    // The visible rect is in the window's coordinates and raw ones are on the screen.
    rootView.getLocationOnScreen(rootOnScreen)
    visibleRect.offset(rootOnScreen[0], rootOnScreen[1])
    for (i in 0 until event.pointerCount) {
      // MotionEvent.getRawX(Int) is API 29: every pointer is offset from the screen as the first
      // is.
      val rawX = event.getX(i) + event.rawX - event.x
      val rawY = event.getY(i) + event.rawY - event.y
      if (!visibleRect.contains(rawX.toInt(), rawY.toInt())) {
        return false
      }
    }
    return true
  }

  private fun View.cancelOwnTouch(event: MotionEvent) {
    val cancel = MotionEvent.obtain(event)
    cancel.action = MotionEvent.ACTION_CANCEL
    this.onTouchEvent(cancel)
    cancel.recycle()
  }
}
