// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.facebook.rendercore.simplelist

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RCRecyclerViewTest {

  private lateinit var recyclerView: RCRecyclerView
  private lateinit var layoutManager: RecordingLayoutManager
  private lateinit var child: View

  @Before
  fun setup() {
    val context: Context = RuntimeEnvironment.getApplication()
    recyclerView = RCRecyclerView(context)
    layoutManager = RecordingLayoutManager(context)
    recyclerView.layoutManager = layoutManager
    child = View(context)
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
}
