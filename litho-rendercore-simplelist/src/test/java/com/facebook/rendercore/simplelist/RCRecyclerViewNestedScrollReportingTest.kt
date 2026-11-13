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
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Tests for how [RCRecyclerView] reports a nested scroll to its parent.
 *
 * The switch starts off: the list moves inside the nested scroll that nestedScrollBy runs (which
 * uses the non-touch type) and leaves the caller's totals untouched. The single test pins that
 * exactly, with the switch explicitly off, before the fix changes the switch-on behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RCRecyclerViewNestedScrollReportingTest {

  private lateinit var rig: Rig

  @Before
  fun setUp() {
    rig =
        buildRig(
            LinearLayoutManager(
                Robolectric.buildActivity(Activity::class.java).setup().get(),
                RecyclerView.HORIZONTAL,
                false,
            ),
            IntArray(10) { 200 },
            false,
        )
  }

  private class Rig(
      val parent: RecordingParent,
      val view: RCRecyclerView,
      val marker: MarkerTarget,
      var scrolledSumX: Int = 0,
      var scrolledSumY: Int = 0,
  )

  private fun buildRig(
      manager: RecyclerView.LayoutManager,
      sizes: IntArray,
      vertical: Boolean,
  ): Rig {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val parent = RecordingParent(activity)
    val view = RCRecyclerView(activity)
    view.layoutManager = manager
    view.adapter = TestAdapter(sizes, vertical)
    view.isNestedScrollingEnabled = true
    val marker = MarkerTarget(activity)
    val rig = Rig(parent, view, marker)
    view.addOnScrollListener(
        object : RecyclerView.OnScrollListener() {
          override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            rig.scrolledSumX += dx
            rig.scrolledSumY += dy
          }
        },
    )
    parent.addView(view, FrameLayout.LayoutParams(1000, 400))
    activity.setContentView(parent, ViewGroup.LayoutParams(1000, 400))
    idle(100)
    assertThat(view.childCount).isGreaterThan(0)
    parent.calls.clear()
    return rig
  }

  private fun idle(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  }

  // The child announces its round to the list as its parent. It does not open
  // the list's own round with its parents: in production no such round is
  // open when a child drives, and the list opens its own round per step.
  private fun Rig.beginChildRound(axes: Int, type: Int) {
    view.onNestedScrollAccepted(view.getChildAt(0), marker, axes, type)
    parent.calls.clear()
    scrolledSumX = 0
    scrolledSumY = 0
  }

  private fun Rig.scrollChildLeft(position: Int): Int {
    val child = view.layoutManager!!.findViewByPosition(position)!!
    return child.left
  }

  @Test
  fun switchOff_reportsThroughInnerRoundAndLeavesCallerTotalsUntouched() {
    rig.view.setReportMovedScrollDistance(false)
    rig.beginChildRound(ViewCompat.SCROLL_AXIS_HORIZONTAL, ViewCompat.TYPE_TOUCH)
    val consumed = IntArray(2)
    val leftBefore = rig.scrollChildLeft(0)

    rig.view.onNestedScroll(
        rig.marker,
        0,
        0,
        100,
        0,
        ViewCompat.TYPE_TOUCH,
        consumed,
    )

    // Today the list moves inside nestedScrollBy, whose round uses the
    // non-touch type; its own report carries zeros and never reaches the
    // parent, so the parent hears the step once and the caller learns nothing.
    assertThat(consumed).containsExactly(0, 0)
    assertThat(rig.parent.calls)
        .containsExactly(
            NestedCall("start", ViewCompat.TYPE_NON_TOUCH),
            NestedCall("pre", ViewCompat.TYPE_NON_TOUCH, 0, 0, 100, 0),
            NestedCall("post", ViewCompat.TYPE_NON_TOUCH, 100, 0, 0, 0),
            NestedCall("stop", ViewCompat.TYPE_NON_TOUCH),
        )
    assertThat(leftBefore - rig.scrollChildLeft(0)).isEqualTo(100)
  }

  private class MarkerTarget(context: Context) :
      View(context), RCRecyclerView.RCRecyclerViewNestedScrollingChild

  private class TestAdapter(private val sizes: IntArray, private val vertical: Boolean) :
      RecyclerView.Adapter<ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
      val view = View(parent.context)
      view.layoutParams =
          if (vertical) {
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sizes[viewType])
          } else {
            RecyclerView.LayoutParams(sizes[viewType], ViewGroup.LayoutParams.MATCH_PARENT)
          }
      return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {}

    override fun getItemCount(): Int = sizes.size

    override fun getItemViewType(position: Int): Int = position % sizes.size
  }

  private class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

  private data class NestedCall(
      val name: String,
      val type: Int,
      val dxConsumed: Int = 0,
      val dyConsumed: Int = 0,
      val dxUnconsumed: Int = 0,
      val dyUnconsumed: Int = 0,
      val tookDx: Int = 0,
      val tookDy: Int = 0,
  )

  private open class RecordingParent(context: Context) :
      FrameLayout(context), NestedScrollingParent3 {
    val calls = ArrayList<NestedCall>()
    var preTakeX: Int = 0
    var preTakeY: Int = 0
    var postTakeX: Int = 0
    var postTakeY: Int = 0
    private val helper = NestedScrollingParentHelper(this)

    private fun take(want: Int, total: Int): Int {
      if (total >= 0) {
        return minOf(want, total)
      }
      return -minOf(want, -total)
    }

    override fun onStartNestedScroll(
        child: View,
        target: View,
        axes: Int,
        type: Int,
    ): Boolean {
      calls.add(NestedCall("start", type))
      return true
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
      helper.onNestedScrollAccepted(child, target, axes, type)
    }

    override fun onStopNestedScroll(target: View, type: Int) {
      calls.add(NestedCall("stop", type))
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
      val tookX = take(postTakeX, dxUnconsumed)
      val tookY = take(postTakeY, dyUnconsumed)
      calls.add(
          NestedCall(
              "post",
              type,
              dxConsumed,
              dyConsumed,
              dxUnconsumed,
              dyUnconsumed,
              tookX,
              tookY,
          ),
      )
      consumed[0] += tookX
      consumed[1] += tookY
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
      val tookX = take(preTakeX, dx)
      val tookY = take(preTakeY, dy)
      calls.add(NestedCall("pre", type, 0, 0, dx, dy, tookX, tookY))
      consumed[0] += tookX
      consumed[1] += tookY
    }

    override fun getNestedScrollAxes(): Int = helper.nestedScrollAxes
  }
}
