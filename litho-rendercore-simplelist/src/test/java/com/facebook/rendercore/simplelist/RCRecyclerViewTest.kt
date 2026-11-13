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

package com.facebook.rendercore.simplelist

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Today's [RCRecyclerView] touch handling, nested scrolling and focus scrolling, recorded so later
 * touch work can see what changed.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RCRecyclerViewTest {

  private lateinit var recyclerView: RCRecyclerView
  private lateinit var layoutManager: RecordingLayoutManager
  private lateinit var child: View

  private lateinit var activity: Activity
  private lateinit var scrollParent: RecordingParent
  private lateinit var nestedList: RCRecyclerView
  private val scrollStates = ArrayList<Int>()

  @Before
  fun setUp() {
    val context: Context = RuntimeEnvironment.getApplication()
    recyclerView = RCRecyclerView(context)
    layoutManager = RecordingLayoutManager(context)
    recyclerView.layoutManager = layoutManager
    child = View(context)

    activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    scrollParent = RecordingParent(activity)
    nestedList = RCRecyclerView(activity)
    nestedList.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
    nestedList.adapter = FixedAdapter(itemSize = 200, itemCount = 10)
    val listener =
        object : RecyclerView.OnScrollListener() {
          override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            scrollStates.add(newState)
          }
        }
    nestedList.addOnScrollListener(listener)
    nestedList.isNestedScrollingEnabled = true
    scrollParent.addView(nestedList, FrameLayout.LayoutParams(1000, 400))
    activity.setContentView(scrollParent, ViewGroup.LayoutParams(1000, 400))
    // Drain the posted traversal without moving the clock.
    shadowOf(Looper.getMainLooper()).idle()
    assertThat(nestedList.width).isEqualTo(1000)
    assertThat(nestedList.height).isEqualTo(400)
    assertThat(nestedList.childCount).isGreaterThan(0)
    scrollStates.clear()
    scrollParent.calls.clear()
  }

  @Test
  fun requestChildRectangleOnScreen_scrollEnabled_delegatesToLayoutManager() {
    val scrolled = recyclerView.requestChildRectangleOnScreen(child, Rect(0, 0, 10, 10), true)

    assertThat(layoutManager.requestCount).isEqualTo(1)
    assertThat(scrolled).isTrue()
  }

  @Test
  fun requestChildRectangleOnScreen_scrollDisabled_doesNotScroll() {
    recyclerView.setIsScrollEnabled(false)

    val scrolled = recyclerView.requestChildRectangleOnScreen(child, Rect(0, 0, 10, 10), true)

    assertThat(layoutManager.requestCount).isEqualTo(0)
    assertThat(scrolled).isFalse()
  }

  @Test
  fun scrollDisabled_touchDispatchIsNotHandled_scrollReEnabledDispatchIsHandledAgain() {
    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
    try {
      assertThat(nestedList.dispatchTouchEvent(down)).isTrue()
    } finally {
      down.recycle()
    }

    nestedList.setIsScrollEnabled(false)
    val downWhileDisabled = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
    try {
      // onTouchEvent short-circuits on mIsScrollEnabled, so super is never consulted.
      assertThat(nestedList.dispatchTouchEvent(downWhileDisabled)).isFalse()
    } finally {
      downWhileDisabled.recycle()
    }

    nestedList.setIsScrollEnabled(true)
    val downAfterReEnable = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
    try {
      assertThat(nestedList.dispatchTouchEvent(downAfterReEnable)).isTrue()
    } finally {
      downAfterReEnable.recycle()
    }
  }

  @Test
  fun startNestedScroll_markerChildAcceptedAndDisablesScroll_stopRestoresIt() {
    val recordingManager = RecordingLayoutManager(activity)
    val localView = RCRecyclerView(activity)
    localView.layoutManager = recordingManager
    val marker = MarkerTarget(activity)
    val child = View(activity)

    assertThat(
        localView.onStartNestedScroll(
            child,
            marker,
            ViewCompat.SCROLL_AXIS_VERTICAL,
            ViewCompat.TYPE_TOUCH,
        ),
    )
        .isTrue()
    // While the marker child owns the nested scroll, the list does not scroll for focus.
    assertThat(localView.requestChildRectangleOnScreen(child, Rect(0, 0, 10, 10), true)).isFalse()
    assertThat(recordingManager.requestCount).isEqualTo(0)

    localView.onStopNestedScroll(marker, ViewCompat.TYPE_TOUCH)
    assertThat(localView.requestChildRectangleOnScreen(child, Rect(0, 0, 10, 10), true)).isTrue()
    assertThat(recordingManager.requestCount).isEqualTo(1)
  }

  @Test
  fun startNestedScroll_nonMarkerTargetRejectedAndScrollStaysEnabled() {
    assertThat(nestedList.scrollState).isEqualTo(RecyclerView.SCROLL_STATE_IDLE)

    // It is the target, not the child, that must implement the nested-scrolling child interface.
    val accepted =
        nestedList.onStartNestedScroll(
            View(activity),
            View(activity),
            ViewCompat.SCROLL_AXIS_VERTICAL,
            ViewCompat.TYPE_TOUCH,
        )
    assertThat(accepted).isFalse()
    // Rejection leaves scrolling enabled: the list still takes a DOWN dispatch.
    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
    try {
      assertThat(nestedList.dispatchTouchEvent(down)).isTrue()
    } finally {
      down.recycle()
    }
  }

  @Test
  fun onNestedScroll_notifiesDraggingPerCallAndOverridesScrollState() {
    val marker = MarkerTarget(activity)
    nestedList.startNestedScroll(ViewCompat.SCROLL_AXIS_HORIZONTAL, ViewCompat.TYPE_TOUCH)

    nestedList.onNestedScroll(
        marker,
        0,
        0,
        100,
        0,
        ViewCompat.TYPE_TOUCH,
    )
    nestedList.onNestedScroll(
        marker,
        0,
        0,
        100,
        0,
        ViewCompat.TYPE_TOUCH,
    )

    // Today the DRAGGING notification is sent on every onNestedScroll call, not once per drag
    // session. A later change may notify once per drag instead; this records today's count.
    assertThat(scrollStates)
        .containsExactly(
            RecyclerView.SCROLL_STATE_DRAGGING,
            RecyclerView.SCROLL_STATE_DRAGGING,
        )
    assertThat(nestedList.scrollState).isEqualTo(RecyclerView.SCROLL_STATE_DRAGGING)
    assertThat(nestedList.computeHorizontalScrollOffset()).isEqualTo(200)
  }

  @Test
  fun onStopNestedScroll_notifiesIdleOnceAndClearsDraggingOverride() {
    val marker = MarkerTarget(activity)
    nestedList.startNestedScroll(ViewCompat.SCROLL_AXIS_HORIZONTAL, ViewCompat.TYPE_TOUCH)
    nestedList.onNestedScroll(marker, 0, 0, 100, 0, ViewCompat.TYPE_TOUCH)
    assertThat(nestedList.scrollState).isEqualTo(RecyclerView.SCROLL_STATE_DRAGGING)
    scrollStates.clear()

    nestedList.onStopNestedScroll(marker, ViewCompat.TYPE_TOUCH)
    assertThat(scrollStates).containsExactly(RecyclerView.SCROLL_STATE_IDLE)
    assertThat(nestedList.scrollState).isEqualTo(RecyclerView.SCROLL_STATE_IDLE)

    // A second stop, with no dragging session in progress, notifies nothing.
    nestedList.onStopNestedScroll(marker, ViewCompat.TYPE_TOUCH)
    assertThat(scrollStates).containsExactly(RecyclerView.SCROLL_STATE_IDLE)
  }

  @Test
  fun onNestedScroll_parentSeesSingleReport_zeroOwnDispatchDropped() {
    val marker = MarkerTarget(activity)
    nestedList.startNestedScroll(ViewCompat.SCROLL_AXIS_HORIZONTAL, ViewCompat.TYPE_TOUCH)
    scrollParent.calls.clear()

    nestedList.onNestedScroll(
        marker,
        0,
        0,
        100,
        0,
        ViewCompat.TYPE_TOUCH,
    )

    // The child's own scroll is reported to the parent with the amount actually
    // moved as consumed, and nothing left unconsumed.
    assertThat(scrollParent.calls).hasSize(1)
    val scrollByDispatch = scrollParent.calls.single()
    assertThat(scrollByDispatch.name).isEqualTo("onNestedScroll")
    assertThat(scrollByDispatch.dxConsumed).isEqualTo(100)
    assertThat(scrollByDispatch.dyConsumed).isEqualTo(0)
    assertThat(scrollByDispatch.dxUnconsumed).isEqualTo(0)
    assertThat(scrollByDispatch.dyUnconsumed).isEqualTo(0)

    // RCRecyclerView then calls dispatchNestedScroll(0, xConsumed, 0, yConsumed) where
    // xConsumed/yConsumed come from getScrollX()/getScrollY(), which a RecyclerView leaves at
    // zero. That all-zero dispatch is dropped by the nested-scrolling child helper before it
    // reaches the parent, so the parent gets no second call. A later change to how the list
    // reports nested scrolling may add one; do not treat this single call as that contract.
    assertThat(nestedList.computeHorizontalScrollOffset()).isEqualTo(100)
  }

  private class RecordingLayoutManager(context: Context) :
      LinearLayoutManager(context, HORIZONTAL, false) {
    var requestCount = 0

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
    ): Boolean {
      requestCount++
      return true
    }
  }

  private class MarkerTarget(context: Context) :
      View(context), RCRecyclerView.RCRecyclerViewNestedScrollingChild

  private class FixedAdapter(private val itemSize: Int, private val itemCount: Int) :
      RecyclerView.Adapter<ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
      val view = View(parent.context)
      view.layoutParams = RecyclerView.LayoutParams(itemSize, ViewGroup.LayoutParams.MATCH_PARENT)
      return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {}

    override fun getItemCount(): Int = itemCount
  }

  private class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

  private data class NestedCall(
      val name: String,
      val dxConsumed: Int,
      val dyConsumed: Int,
      val dxUnconsumed: Int,
      val dyUnconsumed: Int,
  )

  private class RecordingParent(context: Context) : FrameLayout(context), NestedScrollingParent3 {
    val calls = ArrayList<NestedCall>()
    private val helper = NestedScrollingParentHelper(this)

    override fun onStartNestedScroll(
        child: View,
        target: View,
        axes: Int,
        type: Int,
    ): Boolean = true

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
      helper.onNestedScrollAccepted(child, target, axes, type)
    }

    override fun onStopNestedScroll(target: View, type: Int) {
      helper.onStopNestedScroll(target, type)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
    ) {
      calls.add(NestedCall("onNestedScroll", dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed))
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
    ) {
      onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))
    }

    override fun onNestedPreScroll(
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int,
    ) {
      // This parent consumes nothing; the child keeps the whole movement.
    }

    override fun getNestedScrollAxes(): Int = helper.nestedScrollAxes
  }
}
