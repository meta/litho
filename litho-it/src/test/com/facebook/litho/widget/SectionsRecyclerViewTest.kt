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

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SectionsRecyclerViewTest {

  private val sectionsRecyclerView =
      SectionsRecyclerView(getApplicationContext(), RecyclerView(getApplicationContext()))

  // SwipeRefreshLayout adds its spinner first, then SectionsRecyclerView adds the RecyclerView.
  private val spinner: View = sectionsRecyclerView.getChildAt(0)

  @Test
  fun `bringing the last child to the front again requests no layout`() {
    sectionsRecyclerView.skipsRedundantBringToFront = true
    sectionsRecyclerView.bringChildToFront(spinner)
    layOut()

    sectionsRecyclerView.bringChildToFront(spinner)

    assertThat(sectionsRecyclerView.isLayoutRequested).isFalse
    assertThat(lastChild()).isSameAs(spinner)
  }

  @Test
  fun `bringing another child to the front reorders and requests a layout`() {
    sectionsRecyclerView.skipsRedundantBringToFront = true
    layOut()

    sectionsRecyclerView.bringChildToFront(spinner)

    assertThat(sectionsRecyclerView.isLayoutRequested).isTrue
    assertThat(lastChild()).isSameAs(spinner)
  }

  @Test
  fun `by default, bringing the last child to the front still requests a layout`() {
    sectionsRecyclerView.bringChildToFront(spinner)
    layOut()

    sectionsRecyclerView.bringChildToFront(spinner)

    assertThat(sectionsRecyclerView.isLayoutRequested).isTrue
  }

  @Test
  fun `setting the lazy collection marker attaches a new animator instead of the marker`() {
    sectionsRecyclerView.setItemAnimator(LazyCollectionItemAnimator.Default)

    val attached = sectionsRecyclerView.recyclerView.itemAnimator
    assertThat(attached).isInstanceOf(LazyCollectionItemAnimator::class.java)
    assertThat(attached).isNotSameAs(LazyCollectionItemAnimator.Default)
  }

  @Test
  fun `every bind with the marker attaches a different animator`() {
    sectionsRecyclerView.setItemAnimator(LazyCollectionItemAnimator.Default)
    val first = sectionsRecyclerView.recyclerView.itemAnimator
    sectionsRecyclerView.resetItemAnimator()

    sectionsRecyclerView.setItemAnimator(LazyCollectionItemAnimator.Default)
    val second = sectionsRecyclerView.recyclerView.itemAnimator

    assertThat(second).isNotSameAs(first)
  }

  @Test
  fun `resetting after the marker restores the previous animator`() {
    val previous = sectionsRecyclerView.recyclerView.itemAnimator

    sectionsRecyclerView.setItemAnimator(LazyCollectionItemAnimator.Default)
    sectionsRecyclerView.resetItemAnimator()

    assertThat(sectionsRecyclerView.recyclerView.itemAnimator).isSameAs(previous)
  }

  private fun layOut() {
    sectionsRecyclerView.measure(exactly(100), exactly(100))
    sectionsRecyclerView.layout(0, 0, 100, 100)
    assertThat(sectionsRecyclerView.isLayoutRequested).isFalse
  }

  private fun lastChild(): View =
      sectionsRecyclerView.getChildAt(sectionsRecyclerView.childCount - 1)
}
