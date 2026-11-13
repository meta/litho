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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/** Today's [ScrollBarDecoration] drawing behavior, recorded so later touch work can see it. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScrollBarDecorationTest {

  private lateinit var recyclerView: RCRecyclerView

  @Before
  fun setUp() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    recyclerView = RCRecyclerView(activity)
    recyclerView.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
    recyclerView.adapter = FixedAdapter(itemSize = 200, itemCount = 10)
    val parent = FrameLayout(activity)
    parent.addView(recyclerView, FrameLayout.LayoutParams(1000, 400))
    activity.setContentView(parent, ViewGroup.LayoutParams(1000, 400))
    // Drain the posted traversal without moving the clock.
    shadowOf(Looper.getMainLooper()).idle()
    assertThat(recyclerView.childCount).isGreaterThan(0)
  }

  @Test
  fun scrollBar_alphaZero_drawsNothing() {
    val decoration = scrollBarDecoration(flashOnFirstDraw = false)
    recyclerView.addItemDecoration(decoration)
    val canvas = RecordingCanvas()

    recyclerView.draw(canvas)

    assertThat(canvas.roundRectDraws).isEqualTo(0)
    assertThat(decoration.scrollBarAlpha).isEqualTo(0f)
  }

  @Test
  fun scrollBar_alphaOne_drawsBarAtFullAlpha() {
    val decoration = scrollBarDecoration(flashOnFirstDraw = false)
    decoration.scrollBarAlpha = 1f
    recyclerView.addItemDecoration(decoration)
    val canvas = RecordingCanvas()

    recyclerView.draw(canvas)

    assertThat(canvas.roundRectDraws).isGreaterThan(0)
    assertThat(canvas.maxRoundRectAlpha).isEqualTo(255)
  }

  @Test
  fun scrollBar_noScrollableRange_drawsNothingEvenAtFullAlpha() {
    val singleItemActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val singleParent = FrameLayout(singleItemActivity)
    val singleView = RCRecyclerView(singleItemActivity)
    singleView.layoutManager =
        LinearLayoutManager(singleItemActivity, RecyclerView.HORIZONTAL, false)
    singleView.adapter = FixedAdapter(itemSize = 200, itemCount = 1)
    val decoration = scrollBarDecoration(flashOnFirstDraw = false)
    decoration.scrollBarAlpha = 1f
    singleView.addItemDecoration(decoration)
    singleParent.addView(singleView, FrameLayout.LayoutParams(1000, 400))
    singleItemActivity.setContentView(singleParent, ViewGroup.LayoutParams(1000, 400))
    // Drain the posted traversal without moving the clock.
    shadowOf(Looper.getMainLooper()).idle()
    val canvas = RecordingCanvas()

    singleView.draw(canvas)

    // range == extent: there is no scroll available, so the decoration returns
    // before drawing.
    assertThat(canvas.roundRectDraws).isEqualTo(0)
  }

  @Test
  fun scrollBar_firstDrawListenerFiresExactlyOnce() {
    val decoration = scrollBarDecoration(flashOnFirstDraw = false)
    var firstDrawCount = 0
    decoration.setOnFirstDrawListener { firstDrawCount++ }
    recyclerView.addItemDecoration(decoration)

    recyclerView.draw(RecordingCanvas())
    recyclerView.draw(RecordingCanvas())

    assertThat(firstDrawCount).isEqualTo(1)
  }

  @Test
  fun scrollBar_isEquivalent_matchesIdenticalConfigurationOnly() {
    val shadow = ScrollBarDecoration.Shadow(0f, 0f, 0f, 0f, Color.BLACK)
    val decoration = scrollBarDecoration(flashOnFirstDraw = false)
    assertThat(
        ScrollBarDecoration.isEquivalent(
            decoration,
            Color.GRAY,
            Color.TRANSPARENT,
            6,
            0,
            false,
            Rect(),
            false,
            ScrollBarDecoration.Shadow(0f, 0f, 0f, 0f, Color.BLACK),
        ),
    )
        .isTrue()
    assertThat(
        ScrollBarDecoration.isEquivalent(
            decoration,
            Color.GRAY,
            Color.TRANSPARENT,
            8,
            0,
            false,
            Rect(),
            false,
            shadow,
        ),
    )
        .isFalse()
  }

  @Test
  fun scrollBar_flashOnFirstDrawStartsAtFullAlpha() {
    val decoration = scrollBarDecoration(flashOnFirstDraw = true)
    assertThat(decoration.scrollBarAlpha).isEqualTo(1f)
  }

  private fun scrollBarDecoration(
      flashOnFirstDraw: Boolean,
  ): ScrollBarDecoration = ScrollBarDecoration(
      Color.GRAY,
      Color.TRANSPARENT,
      6,
      0,
      flashOnFirstDraw,
      Rect(),
      false,
      ScrollBarDecoration.Shadow(0f, 0f, 0f, 0f, Color.BLACK),
  )

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

  private class RecordingCanvas : Canvas(createBitmap(1000, 400)) {
    var roundRectDraws = 0
    var maxRoundRectAlpha = -1

    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
      roundRectDraws++
      if (paint.alpha > maxRoundRectAlpha) maxRoundRectAlpha = paint.alpha
      super.drawRoundRect(rect, rx, ry, paint)
    }
  }
}
