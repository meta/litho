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

package com.facebook.litho.zoomable

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.facebook.litho.Component
import com.facebook.litho.ComponentHost
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.LithoView
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.onClick
import com.facebook.litho.view.onTouch
import com.facebook.litho.widget.SolidColor
import com.facebook.litho.widget.zoomable.Zoomable
import com.facebook.litho.widget.zoomable.ZoomableTouchGestureListener
import com.facebook.rendercore.px
import com.facebook.rendercore.zoomable.ZoomableViewBaseController
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
// API 27 has no MotionEvent.getRawX(Int); 33 is a recent one.
@Config(sdk = [27, 33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomableTest {

  private var clicks = 0
  private var zoomStarts = 0
  private var zoomEnds = 0

  private val listener =
      object : ZoomableViewBaseController.Listener {
        override fun onZoomStarted() {
          zoomStarts++
        }

        override fun onZoomFinished() {
          zoomEnds++
        }
      }

  private fun content(): Component =
      Column(style = Style.onClick { clicks++ }) {
        child(SolidColor(Color.RED, style = Style.width(300.px).height(300.px)))
      }

  private class Screen(val activity: Activity, val lithoView: LithoView) {
    val decor: ViewGroup = activity.window.decorView as ViewGroup
    val host: View = lithoView.getChildAt(0)
    val origin: IntArray = IntArray(2).also { host.getLocationInWindow(it) }

    fun x(dx: Int): Float = (origin[0] + dx).toFloat()

    fun y(dy: Int): Float = (origin[1] + dy).toFloat()
  }

  private fun root(
      zoomableEventListener: ZoomableViewBaseController.Listener = listener,
      content: () -> Component = ::content,
  ): Component =
      Column(style = Style.backgroundColor(Color.WHITE).width(400.px).height(800.px)) {
        child(SolidColor(Color.GRAY, style = Style.width(400.px).height(50.px)))
        child(Zoomable(zoomableEventListener = zoomableEventListener, child = content))
      }

  /** Zoomable content that handles no touch, inside an ancestor with [ancestorStyle]. */
  private fun rootWithAncestor(
      ancestorStyle: Style,
      keepUnhandledTouches: Boolean,
      gestureListener: ZoomableTouchGestureListener? = null,
  ): Component =
      Column(style = Style.backgroundColor(Color.WHITE).width(400.px).height(800.px)) {
        child(SolidColor(Color.GRAY, style = Style.width(400.px).height(50.px)))
        child(
            Column(style = ancestorStyle) {
              child(
                  Zoomable(
                      zoomableTouchGestureListener = gestureListener,
                      zoomableEventListener = listener,
                      keepUnhandledTouches = keepUnhandledTouches,
                  ) {
                    SolidColor(Color.RED, style = Style.width(300.px).height(300.px))
                  },
              )
            },
        )
      }

  private fun setUp(content: () -> Component = ::content): Screen =
      setUpRoot(root(content = content))

  private fun setUpRoot(rootComponent: Component): Screen {
    // The spring that animates the copy back runs on frame callbacks: give them a frame's delay so
    // advancing the clock steps it.
    ShadowChoreographer.setPostFrameCallbackDelay(16)
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val lithoView = LithoView.create(activity, rootComponent)
    activity.setContentView(lithoView)
    val decor = activity.window.decorView
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
    )
    decor.layout(0, 0, 400, 800)
    idle()
    return Screen(activity, lithoView)
  }

  @Test
  fun theContentIsMountedInTheOuterTreeWithoutANestedRenderTreeView() {
    val screen = setUp()
    assertThat(findRenderTreeView(screen.lithoView)).isNull()
    assertThat(screen.host.parent).isSameAs(screen.lithoView)
  }

  @Test
  fun theZoomableAddsNoHostView() {
    val screen = setUp()
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val unwrapped =
        LithoView.create(
            activity,
            Column(style = Style.backgroundColor(Color.WHITE).width(400.px).height(800.px)) {
              child(SolidColor(Color.GRAY, style = Style.width(400.px).height(50.px)))
              child(content())
            },
        )
    unwrapped.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
    )
    unwrapped.layout(0, 0, 400, 800)
    idle()
    assertThat(countHosts(screen.lithoView)).isEqualTo(countHosts(unwrapped))
  }

  @Test
  fun withoutKeepingUnhandledTouchesATapReachesTheAncestor() {
    var ancestorClicks = 0
    val screen = setUpRoot(
        rootWithAncestor(
            ancestorStyle = Style.onClick { ancestorClicks++ },
            keepUnhandledTouches = false,
        ),
    )
    tap(screen)
    assertThat(ancestorClicks).isEqualTo(1)
  }

  @Test
  fun keepingUnhandledTouchesKeepsATapFromTheAncestor() {
    var ancestorClicks = 0
    val screen = setUpRoot(
        rootWithAncestor(
            ancestorStyle = Style.onClick { ancestorClicks++ },
            keepUnhandledTouches = true,
        ),
    )
    tap(screen)
    assertThat(ancestorClicks).isEqualTo(0)
  }

  @Test
  fun withoutKeepingUnhandledTouchesAPinchTheAncestorPassesOnZooms() {
    val gestureListener = ZoomableTouchGestureListener()
    val screen = setUpRoot(
        rootWithAncestor(
            // As a pager does: takes the gesture and passes its events to the shared listener.
            ancestorStyle =
                Style.onTouch { touchEvent ->
                  gestureListener.onTouch(touchEvent.motionEvent)
                  true
                },
            keepUnhandledTouches = false,
            gestureListener = gestureListener,
        ),
    )
    val end = pinchOut(screen)
    assertThat(zoomStarts).isEqualTo(1)
    dispatch(screen, end, MotionEvent.ACTION_UP, screen.x(-150) to screen.y(150))
    shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
    assertThat(zoomEnds).isEqualTo(1)
  }

  @Test
  fun aTapStillReachesTheContent() {
    val screen = setUp()
    val t = SystemClock.uptimeMillis()
    dispatch(screen, t, MotionEvent.ACTION_DOWN, screen.x(50) to screen.y(50))
    dispatch(screen, t + 10, MotionEvent.ACTION_UP, screen.x(50) to screen.y(50))
    assertThat(clicks).isEqualTo(1)
  }

  @Test
  fun aPinchDrawsAZoomedCopyOverTheWindowAndLiftingRestoresTheContent() {
    val screen = setUp()
    // Right of the 300x300 content, inside the host: white before the zoom.
    assertThat(pixel(screen, screen.x(350), screen.y(150))).isEqualTo(Color.WHITE)
    assertThat(pixel(screen, screen.x(150), screen.y(150))).isEqualTo(Color.RED)

    val t = SystemClock.uptimeMillis()
    // Fingers 200px apart around the content's center, wider than the scale detector's minimum
    // span, then spreading.
    dispatch(screen, t, MotionEvent.ACTION_DOWN, screen.x(50) to screen.y(150))
    dispatch(
        screen,
        t + 10,
        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        screen.x(50) to screen.y(150),
        screen.x(250) to screen.y(150),
    )
    for (step in 1..10) {
      val spread = 40f * step
      dispatch(
          screen,
          t + 10 + step * 16L,
          MotionEvent.ACTION_MOVE,
          screen.x(50) - spread / 2 to screen.y(150),
          screen.x(250) + spread / 2 to screen.y(150),
      )
    }

    assertThat(zoomStarts).isEqualTo(1)
    assertThat(screen.host.isHiddenForZoom).describedAs("original hidden").isTrue()
    assertThat(screen.lithoView.hasTransientState()).describedAs("everything mounted").isTrue()
    assertThat(pixel(screen, screen.x(350), screen.y(150)))
        .describedAs("the zoomed copy reaches past the content")
        .isEqualTo(Color.RED)

    val end = t + 200
    dispatch(
        screen,
        end,
        MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        screen.x(-150) to screen.y(150),
        screen.x(450) to screen.y(150),
    )
    dispatch(screen, end + 10, MotionEvent.ACTION_UP, screen.x(-150) to screen.y(150))
    shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)

    assertThat(zoomEnds).isEqualTo(1)
    assertThat(screen.host.isHiddenForZoom).describedAs("original shown again").isFalse()
    assertThat(screen.lithoView.hasTransientState()).isFalse()
    assertThat(pixel(screen, screen.x(350), screen.y(150))).isEqualTo(Color.WHITE)
    assertThat(pixel(screen, screen.x(150), screen.y(150))).isEqualTo(Color.RED)
    assertThat(clicks).describedAs("the pinch did not click the content").isEqualTo(0)
  }

  @Test
  fun contentThatDoesNotHandleTouchStillZooms() {
    val screen = setUp { SolidColor(Color.RED, style = Style.width(300.px).height(300.px)) }
    pinchOut(screen)
    assertThat(zoomStarts).isEqualTo(1)
    assertThat(screen.host.isHiddenForZoom).isTrue()
  }

  @Test
  fun aNewListenerFromARenderDuringTheZoomKeepsTheZoomAndHearsItsEnd() {
    val screen = setUp()
    val end = pinchOut(screen)
    var newListenerEnds = 0
    screen.lithoView.setComponent(
        root(
            zoomableEventListener =
                object : ZoomableViewBaseController.Listener {
                  override fun onZoomStarted() = Unit

                  override fun onZoomFinished() {
                    newListenerEnds++
                  }
                },
        ),
    )
    idle()
    assertThat(screen.host.isHiddenForZoom).describedAs("still zooming").isTrue()
    assertThat(zoomEnds).isEqualTo(0)

    dispatch(screen, end, MotionEvent.ACTION_UP, screen.x(-150) to screen.y(150))
    shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
    assertThat(newListenerEnds).isEqualTo(1)
    assertThat(screen.host.isHiddenForZoom).isFalse()
  }

  @Test
  fun removingTheZoomableMidZoomEndsTheZoomOnce() {
    val screen = setUp()
    pinchOut(screen)
    val host = screen.host
    screen.lithoView.setComponent(Column(style = Style.width(400.px).height(800.px)) {})
    shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
    assertThat(zoomEnds).isEqualTo(1)
    assertThat(host.isHiddenForZoom).isFalse()
    assertThat(screen.lithoView.hasTransientState()).isFalse()
  }

  @Test
  fun removingTheZoomableWhileTheCopySpringsBackEndsTheZoomOnce() {
    val screen = setUp()
    val end = pinchOut(screen)
    dispatch(screen, end, MotionEvent.ACTION_UP, screen.x(-150) to screen.y(150))
    val host = screen.host
    screen.lithoView.setComponent(Column(style = Style.width(400.px).height(800.px)) {})
    shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
    assertThat(zoomEnds).isEqualTo(1)
    assertThat(host.isHiddenForZoom).isFalse()
    assertThat(screen.lithoView.hasTransientState()).isFalse()
  }

  @Test
  fun detachingTheWholeLithoViewMidZoomReleasesItsTransientState() {
    val screen = setUp()
    pinchOut(screen)
    assertThat(screen.lithoView.hasTransientState()).isTrue()
    // Never attached again: the release posted during the detach must still run.
    (screen.lithoView.parent as ViewGroup).removeView(screen.lithoView)
    idle()
    assertThat(zoomEnds).isEqualTo(1)
    assertThat(screen.host.isHiddenForZoom).isFalse()
    assertThat(screen.lithoView.hasTransientState()).isFalse()
  }

  @Test
  fun unmountingARootZoomableMidZoomReleasesItsEventListenerWhileTheTreeLives() {
    val screen = setUpRoot(zoomableRoot(listener))
    pinchOut(screen)
    val released = renderNewListener(screen)
    val tree = checkNotNull(screen.lithoView.componentTree)
    // The LithoView stays attached, so the root's binders unbind while it still zooms.
    screen.lithoView.componentTree = null
    idle()
    tree.setRootSync(zoomableRoot(listener))
    assertThat(collected(released)).isTrue()
  }

  /** A [Zoomable] as the root, so it binds to the LithoView itself. */
  private fun zoomableRoot(
      zoomableEventListener: ZoomableViewBaseController.Listener,
  ): Component = Zoomable(
      zoomableEventListener = zoomableEventListener,
      style = Style.backgroundColor(Color.WHITE).width(400.px).height(800.px),
  ) {
    Column { child(content()) }
  }

  /** Renders a new event listener into the root [Zoomable]; returns a weak reference to it. */
  private fun renderNewListener(screen: Screen): WeakReference<*> {
    val eventListener =
        object : ZoomableViewBaseController.Listener {
          override fun onZoomStarted() = Unit

          override fun onZoomFinished() = Unit
        }
    checkNotNull(screen.lithoView.componentTree).setRootSync(zoomableRoot(eventListener))
    idle()
    return WeakReference(eventListener)
  }

  private fun collected(ref: WeakReference<*>): Boolean {
    repeat(20) {
      if (ref.get() == null) return true
      System.gc()
      @Suppress("UNUSED_VARIABLE") val pressure = ByteArray(1 shl 20)
      Thread.sleep(10)
    }
    return ref.get() == null
  }

  private fun tap(screen: Screen) {
    val time = SystemClock.uptimeMillis()
    dispatch(screen, time, MotionEvent.ACTION_DOWN, screen.x(50) to screen.y(50))
    dispatch(screen, time + 10, MotionEvent.ACTION_UP, screen.x(50) to screen.y(50))
  }

  /** Pinches out around the content's center; returns a time after the last move. */
  private fun pinchOut(screen: Screen): Long {
    val t = SystemClock.uptimeMillis()
    dispatch(screen, t, MotionEvent.ACTION_DOWN, screen.x(50) to screen.y(150))
    dispatch(
        screen,
        t + 10,
        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        screen.x(50) to screen.y(150),
        screen.x(250) to screen.y(150),
    )
    for (step in 1..10) {
      val spread = 40f * step
      dispatch(
          screen,
          t + 10 + step * 16L,
          MotionEvent.ACTION_MOVE,
          screen.x(50) - spread / 2 to screen.y(150),
          screen.x(250) + spread / 2 to screen.y(150),
      )
    }
    return t + 200
  }

  private fun dispatch(screen: Screen, time: Long, action: Int, vararg points: Pair<Float, Float>) {
    val properties =
        Array(points.size) { i ->
          MotionEvent.PointerProperties().apply {
            id = i
            toolType = MotionEvent.TOOL_TYPE_FINGER
          }
        }
    val coords =
        Array(points.size) { i ->
          MotionEvent.PointerCoords().apply {
            x = points[i].first
            y = points[i].second
            pressure = 1f
            size = 1f
          }
        }
    val event =
        MotionEvent.obtain(
            time,
            time,
            action,
            points.size,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    screen.decor.dispatchTouchEvent(event)
    event.recycle()
    idle()
  }

  private fun pixel(screen: Screen, x: Float, y: Float): Int {
    val bitmap = createBitmap(screen.decor.width, screen.decor.height)
    screen.decor.draw(Canvas(bitmap))
    return bitmap[x.toInt(), y.toInt()]
  }

  private fun findRenderTreeView(view: View): View? {
    if (view is LithoRenderTreeView) return view
    if (view !is ViewGroup) return null
    for (i in 0 until view.childCount) findRenderTreeView(view.getChildAt(i))?.let {
      return it
    }
    return null
  }

  private fun countHosts(view: View): Int {
    var hosts = if (view is ComponentHost) 1 else 0
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        hosts += countHosts(view.getChildAt(i))
      }
    }
    return hosts
  }

  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }
}
