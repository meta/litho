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

package com.facebook.litho.widget.collection

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/**
 * Removing a row whose view was recycled cleans up the effects it kept exactly once, even when that
 * cleanup scrolls the list in the middle of the data change. Each test runs on both collections.
 */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class CollectionRecycledRowCleanupTest {

  @get:Rule val legacy = rule(newCollection = false)
  @get:Rule val new = rule(newCollection = true)

  @Test
  fun `legacy - removing a recycled row whose cleanup scrolls the list doesn't crash`() {
    removingARecycledRowWhoseCleanupScrollsTheListDoesNotCrash(legacy)
  }

  @Test
  fun `new - removing a recycled row whose cleanup scrolls the list doesn't crash`() {
    removingARecycledRowWhoseCleanupScrollsTheListDoesNotCrash(new)
  }

  @Test
  fun `legacy - removing a recycled row near the end whose cleanup scrolls up doesn't crash`() {
    removingARecycledRowNearTheEndWhoseCleanupScrollsUpDoesNotCrash(legacy)
  }

  @Test
  fun `new - removing a recycled row near the end whose cleanup scrolls up doesn't crash`() {
    removingARecycledRowNearTheEndWhoseCleanupScrollsUpDoesNotCrash(new)
  }

  @Test
  fun `legacy - removing a recycled row near the end whose cleanup scrolls down doesn't crash`() {
    removingARecycledRowNearTheEndWhoseCleanupScrollsDownDoesNotCrash(legacy)
  }

  @Test
  fun `new - removing a recycled row near the end whose cleanup scrolls down doesn't crash`() {
    removingARecycledRowNearTheEndWhoseCleanupScrollsDownDoesNotCrash(new)
  }

  // New infra only: Sections cleans this row, as its range pass uses the old visible positions on
  // the shorter list.
  @Test
  fun `new - removing the rows above a recycled row near the viewport keeps its effects`() {
    // |before_0| ... |before_185|tracked|after_0| ... |after_12|
    // Scrolled so the 4th row below the tracked row is the first visible: its view is recycled and
    // it stays in range. Then 180 rows above it are removed, so the list shrinks far below the old
    // first visible position.
    val events = mutableListOf<String>()
    val view =
        new.render(widthPx = ROW_HEIGHT_PX, heightPx = ROW_HEIGHT_PX * VIEWPORT_ROWS) {
          Rows(
              indexesBefore = indexes(186),
              indexesAfter = indexes(13),
              withTrackedRow = true,
              events,
              onCleanupScroll = {},
          )
        }
    idle(new)
    val list = recyclerViews(view.lithoView).first()
    new.act(view) { list.scrollBy(0, 190 * ROW_HEIGHT_PX) }
    idle(new)
    assertThat(list.findViewHolderForAdapterPosition(186))
        .describedAs("the tracked row's view was recycled")
        .isNull()

    view.setRoot(
        Rows(
            indexesBefore = indexes(6),
            indexesAfter = indexes(13),
            withTrackedRow = true,
            events,
            onCleanupScroll = {},
        ),
    )
    idle(new)

    assertThat(events).containsExactly("attach")
    view.release()
    idle(new)
  }

  private fun removingARecycledRowWhoseCleanupScrollsTheListDoesNotCrash(rule: LithoTestRule) {
    for (cleanupScrollRows in listOf(1, 3, -1)) {
      // |tracked|filler_0| ... |filler_199|   tracked recycled, 6 rows above the viewport
      val events = removeRecycledRow(
          rule,
          trackedIndex = 0,
          scrollToIndex = NEAR_ROWS,
          cleanupScrollRows = cleanupScrollRows,
      )

      assertThat(events)
          .describedAs("scroll $cleanupScrollRows rows")
          .containsExactly("attach", "cleanup")
    }
  }

  private fun removingARecycledRowNearTheEndWhoseCleanupScrollsUpDoesNotCrash(rule: LithoTestRule) {
    for (cleanupScrollRows in listOf(-11, -20)) {
      // |filler_0| ... |tracked| ... |filler_198|[ last 4 rows on screen ]
      // Scrolling up moves the last rows out of the range while the list shrinks by one.
      val events = removeRecycledRow(
          rule,
          trackedIndex = ROW_COUNT - 10,
          scrollToIndex = ROW_COUNT - VIEWPORT_ROWS,
          cleanupScrollRows = cleanupScrollRows,
      )

      assertThat(events)
          .describedAs("scroll $cleanupScrollRows rows")
          .containsExactly("attach", "cleanup")
    }
  }

  private fun removingARecycledRowNearTheEndWhoseCleanupScrollsDownDoesNotCrash(
      rule: LithoTestRule,
  ) {
    for (cleanupScrollRows in listOf(5, 10)) {
      // |filler_0| ... |tracked| ... [ 4 rows on screen ] |filler_..| |filler_198|
      // Scrolling down reaches the end while the list shrinks by one.
      val events = removeRecycledRow(
          rule,
          trackedIndex = ROW_COUNT - 14,
          scrollToIndex = ROW_COUNT - 8,
          cleanupScrollRows = cleanupScrollRows,
      )

      assertThat(events)
          .describedAs("scroll $cleanupScrollRows rows")
          .containsExactly("attach", "cleanup")
    }
  }

  /**
   * Renders [ROW_COUNT] rows with a tracked row at [trackedIndex], scrolls so that [scrollToIndex]
   * is the first visible row (the tracked row's view is recycled), then removes the tracked row.
   * Its effect cleanup scrolls the list by [cleanupScrollRows]. Returns the tracked row's effect
   * events.
   */
  private fun removeRecycledRow(
      rule: LithoTestRule,
      trackedIndex: Int,
      scrollToIndex: Int,
      cleanupScrollRows: Int,
  ): List<String> {
    val events = mutableListOf<String>()
    var recyclerView: RecyclerView? = null
    val onCleanupScroll = { recyclerView?.scrollBy(0, cleanupScrollRows * ROW_HEIGHT_PX) ?: Unit }
    val view =
        rule.render(widthPx = ROW_HEIGHT_PX, heightPx = ROW_HEIGHT_PX * VIEWPORT_ROWS) {
          Rows(
              indexesBefore = indexes(trackedIndex),
              indexesAfter = indexes(ROW_COUNT - trackedIndex - 1),
              withTrackedRow = true,
              events,
              onCleanupScroll,
          )
        }
    idle(rule)
    val list = recyclerViews(view.lithoView).first()
    recyclerView = list
    rule.act(view) { list.scrollBy(0, scrollToIndex * ROW_HEIGHT_PX) }
    idle(rule)
    assertThat(list.findViewHolderForAdapterPosition(trackedIndex))
        .describedAs("the tracked row's view was recycled")
        .isNull()

    view.setRoot(
        Rows(
            indexesBefore = indexes(trackedIndex),
            indexesAfter = indexes(ROW_COUNT - trackedIndex - 1),
            withTrackedRow = false,
            events,
            onCleanupScroll,
        ),
    )
    idle(rule)
    rule.act(view) { list.scrollBy(0, ROW_HEIGHT_PX) }
    idle(rule)

    val result = events.toList()
    view.release()
    idle(rule)
    return result
  }

  /** [indexesBefore] and [indexesAfter] name the filler rows above and below the tracked row. */
  private class Rows(
      private val indexesBefore: List<Int>,
      private val indexesAfter: List<Int>,
      private val withTrackedRow: Boolean,
      private val events: MutableList<String>,
      private val onCleanupScroll: () -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height((ROW_HEIGHT_PX * VIEWPORT_ROWS).px)) {
          children(items = indexesBefore, id = { index -> "before_$index" }) { Filler() }
          if (withTrackedRow) {
            child(id = "tracked", component = ScrollingCleanupRow(events, onCleanupScroll))
          }
          children(items = indexesAfter, id = { index -> "after_$index" }) { Filler() }
        }
  }

  private class ScrollingCleanupRow(
      private val events: MutableList<String>,
      private val onCleanupScroll: () -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(Unit) {
        events.add("attach")
        onCleanup {
          events.add("cleanup")
          onCleanupScroll()
        }
      }
      return Column(style = Style.width(ROW_HEIGHT_PX.px).height(ROW_HEIGHT_PX.px))
    }
  }

  private class Filler : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.width(ROW_HEIGHT_PX.px).height(ROW_HEIGHT_PX.px))
  }

  private fun recyclerViews(root: View): List<RecyclerView> {
    val found = mutableListOf<RecyclerView>()
    val queue = ArrayDeque<View>().apply { add(root) }
    while (queue.isNotEmpty()) {
      val view = queue.removeFirst()
      if (view is RecyclerView) {
        found += view
      }
      if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
          queue.add(view.getChildAt(i))
        }
      }
    }
    return found
  }

  private fun idle(rule: LithoTestRule) {
    repeat(3) {
      ShadowLooper.idleMainLooper()
      rule.idle()
    }
  }

  companion object {
    private const val ROW_HEIGHT_PX = 100
    private const val VIEWPORT_ROWS = 4
    private const val NEAR_ROWS = 6
    private const val ROW_COUNT = 201

    private fun indexes(count: Int): List<Int> = (0 until count).toList()

    private fun rule(newCollection: Boolean): LithoTestRule = LithoTestRule(
        componentsConfiguration =
            ComponentsConfiguration.defaultInstance.copy(enableNewCollection = newCollection),
    )
  }
}
