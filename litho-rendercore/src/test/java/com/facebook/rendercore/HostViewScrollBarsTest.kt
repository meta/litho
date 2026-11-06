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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A theme can turn on scrollbars for every view (for example `android:scrollbars` and
 * `android:fadeScrollbars` on the activity theme). These attributes reproduce that for a single
 * view.
 */
@RunWith(RobolectricTestRunner::class)
class HostViewScrollBarsTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun alwaysOnScrollBars(): AttributeSet =
      Robolectric.buildAttributeSet()
          .addAttribute(android.R.attr.scrollbars, "vertical|horizontal")
          .addAttribute(android.R.attr.fadeScrollbars, "false")
          .build()

  @Test
  fun `host view keeps scrollbars requested by its attributes`() {
    val hostView = HostView(context, alwaysOnScrollBars())

    assertThat(hostView.isVerticalScrollBarEnabled).isTrue
    assertThat(hostView.isHorizontalScrollBarEnabled).isTrue
  }

  @Test
  fun `host view with always-on scrollbars invalidates itself while drawing`() {
    val hostView = HostView(context, alwaysOnScrollBars())

    drawOnce(hostView)

    assertThat(hostView.isDirty).isTrue
  }

  @Test
  fun `plain view with always-on scrollbars does not invalidate itself while drawing`() {
    val view = View(context, alwaysOnScrollBars()).apply { setWillNotDraw(false) }

    drawOnce(view)

    assertThat(view.isDirty).isFalse
  }

  @Test
  fun `host view without scrollbars does not invalidate itself while drawing`() {
    val hostView = HostView(context)

    drawOnce(hostView)

    assertThat(hostView.isDirty).isFalse
  }

  private fun drawOnce(view: View) {
    val size = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
    view.measure(size, size)
    view.layout(0, 0, SIZE, SIZE)
    view.draw(Canvas(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)))
  }

  private companion object {
    const val SIZE = 100
  }
}
