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
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/**
 * A row's effects stay attached, and can still update its state, while the row is near the
 * viewport, even when RecyclerView recycles its view. They are cleaned up when the row is far from
 * the viewport, however it got there, or leaves the list. Each test runs on both collections.
 */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class CollectionRowEffectLifetimeTest {

  @get:Rule val legacy = rule(newCollection = false)
  @get:Rule val new = rule(newCollection = true)

  @Test
  fun `legacy - recycling a row's view near the viewport keeps its effects`() {
    recyclingARowsViewNearTheViewportKeepsItsEffects(legacy)
  }

  @Test
  fun `new - recycling a row's view near the viewport keeps its effects`() {
    recyclingARowsViewNearTheViewportKeepsItsEffects(new)
  }

  @Test
  fun `legacy - a row that scrolls far away cleans up its effects`() {
    aRowThatScrollsFarAwayCleansUpItsEffects(legacy)
  }

  @Test
  fun `new - a row that scrolls far away cleans up its effects`() {
    aRowThatScrollsFarAwayCleansUpItsEffects(new)
  }

  @Test
  fun `legacy - removing a row cleans up its effects`() {
    removingARowCleansUpItsEffects(legacy)
  }

  @Test
  fun `new - removing a row cleans up its effects`() {
    removingARowCleansUpItsEffects(new)
  }

  @Test
  fun `legacy - updating a row while its view is recycled keeps effects with unchanged deps`() {
    updatingARowWhileItsViewIsRecycledKeepsEffectsWithUnchangedDeps(legacy)
  }

  @Test
  fun `new - updating a row while its view is recycled keeps effects with unchanged deps`() {
    updatingARowWhileItsViewIsRecycledKeepsEffectsWithUnchangedDeps(new)
  }

  @Test
  fun `legacy - a state update from an effect of a recycled row is applied`() {
    aStateUpdateFromAnEffectOfARecycledRowIsApplied(legacy)
  }

  @Test
  fun `new - a state update from an effect of a recycled row is applied`() {
    aStateUpdateFromAnEffectOfARecycledRowIsApplied(new)
  }

  @Test
  fun `legacy - moving a recycled row far away cleans up its effects`() {
    movingARecycledRowFarAwayCleansUpItsEffects(legacy)
  }

  @Test
  fun `new - moving a recycled row far away cleans up its effects`() {
    movingARecycledRowFarAwayCleansUpItsEffects(new)
  }

  @Test
  fun `legacy - a sticky row has one set of effects while its header floats`() {
    aStickyRowHasOneSetOfEffectsWhileItsHeaderFloats(legacy)
  }

  @Test
  fun `new - a sticky row has one set of effects while its header floats`() {
    aStickyRowHasOneSetOfEffectsWhileItsHeaderFloats(new)
  }

  @Test
  fun `legacy - an effect whose deps change while its row is recycled runs again right away`() {
    anEffectWhoseDepsChangeWhileItsRowIsRecycledRunsAgainRightAway(legacy)
  }

  @Test
  fun `new - an effect whose deps change while its row is recycled runs again right away`() {
    anEffectWhoseDepsChangeWhileItsRowIsRecycledRunsAgainRightAway(new)
  }

  @Test
  fun `legacy - an effect that reads state changed while its row is recycled runs again`() {
    anEffectThatReadsStateChangedWhileItsRowIsRecycledRunsAgain(legacy)
  }

  @Test
  fun `new - an effect that reads state changed while its row is recycled runs again`() {
    anEffectThatReadsStateChangedWhileItsRowIsRecycledRunsAgain(new)
  }

  @Test
  fun `legacy - emptying a recycled row cleans up its effects`() {
    emptyingARecycledRowCleansUpItsEffects(legacy)
  }

  @Test
  fun `new - emptying a recycled row cleans up its effects`() {
    emptyingARecycledRowCleansUpItsEffects(new)
  }

  private fun recyclingARowsViewNearTheViewportKeepsItsEffects(rule: LithoTestRule) {
    val list = TrackedList(rule)
    // Scrolls the tracked row and a few fillers off screen, so RecyclerView recycles the row's
    // view, while the row stays within the prepared range around the viewport.
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()
    list.scrollBy(-ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowIsBound()).describedAs("the tracked row is bound again").isTrue()

    assertThat(list.events).containsExactly("attach")
    list.close()
  }

  private fun aRowThatScrollsFarAwayCleansUpItsEffects(rule: LithoTestRule) {
    val list = TrackedList(rule)
    list.scrollBy(ROW_HEIGHT_PX * (ROW_COUNT - VIEWPORT_ROWS))

    assertThat(list.events).containsExactly("attach", "cleanup")
    list.close()
  }

  private fun removingARowCleansUpItsEffects(rule: LithoTestRule) {
    val list = TrackedList(rule)
    list.removeTrackedRow()

    assertThat(list.events).containsExactly("attach", "cleanup")
    list.close()
  }

  private fun updatingARowWhileItsViewIsRecycledKeepsEffectsWithUnchangedDeps(rule: LithoTestRule) {
    val list = TrackedList(rule)
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()
    list.updateTrackedRow(label = "updated")
    list.scrollBy(-ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowIsBound()).describedAs("the tracked row is bound again").isTrue()

    assertThat(list.renders.last())
        .describedAs("the bound row shows the update")
        .isEqualTo("updated")
    assertThat(list.events).containsExactly("attach")
    list.close()
  }

  private fun aStateUpdateFromAnEffectOfARecycledRowIsApplied(rule: LithoTestRule) {
    val list = TrackedList(rule)
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()

    // scenario: while the row has no view, its effect updates its state, e.g. a subscription emits.
    list.updateTrackedRowStateFromItsEffect()
    list.scrollBy(-ROW_HEIGHT_PX * NEAR_ROWS)

    assertThat(list.trackedRowIsBound()).describedAs("the tracked row is bound again").isTrue()
    assertThat(list.trackedRowCounts.last())
        .describedAs("the bound row shows the update")
        .isEqualTo(1)
    list.close()
  }

  private fun movingARecycledRowFarAwayCleansUpItsEffects(rule: LithoTestRule) {
    // |tracked|filler_0|filler_1| ... |filler_199|          before
    // |filler_0|filler_1| ... |filler_199|tracked|          after the move, far from the viewport
    val list = TrackedList(rule)
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()

    list.moveTrackedRowToTheEnd()

    assertThat(list.events).containsExactly("attach", "cleanup")
    list.close()
  }

  private fun aStickyRowHasOneSetOfEffectsWhileItsHeaderFloats(rule: LithoTestRule) {
    val list = TrackedList(rule, trackedRowIsSticky = true)
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()
    assertThat(list.stickyHeaderIsShown()).describedAs("the header floats").isTrue()

    assertThat(list.attachedEffects).isEqualTo(1)
    list.close()
  }

  private fun anEffectWhoseDepsChangeWhileItsRowIsRecycledRunsAgainRightAway(rule: LithoTestRule) {
    val list = TrackedList(rule)
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()

    // scenario: while the row has no view, its state changes, and an effect depends on it.
    list.updateTrackedRowStateFromItsEffect()

    assertThat(list.countEffects).containsExactly("attach:0", "cleanup:0", "attach:1")
    list.close()
  }

  private fun anEffectThatReadsStateChangedWhileItsRowIsRecycledRunsAgain(rule: LithoTestRule) {
    val list = TrackedList(rule)
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()

    // scenario: while the row has no view, its state changes; an effect without deps reads it.
    list.updateTrackedRowStateFromItsEffect()
    list.scrollBy(-ROW_HEIGHT_PX * NEAR_ROWS)

    assertThat(list.trackedRowIsBound()).describedAs("the tracked row is bound again").isTrue()
    assertThat(list.readEffects).containsExactly("attach:0", "cleanup", "attach:1")
    list.close()
  }

  private fun emptyingARecycledRowCleansUpItsEffects(rule: LithoTestRule) {
    val list = TrackedList(rule)
    list.scrollBy(ROW_HEIGHT_PX * NEAR_ROWS)
    assertThat(list.trackedRowViewWasRecycled)
        .describedAs("the tracked row's view was recycled")
        .isTrue()

    // scenario: while the row has no view, its component is replaced by an empty one.
    list.emptyTrackedRow()

    assertThat(list.events).containsExactly("attach", "cleanup")
    list.close()
  }

  private inner class TrackedList(
      private val rule: LithoTestRule,
      private val trackedRowIsSticky: Boolean = false,
  ) {
    val events = mutableListOf<String>()
    val countEffects = mutableListOf<String>()
    val readEffects = mutableListOf<String>()
    val renders = mutableListOf<String>()
    val trackedRowCounts = mutableListOf<Int>()
    private val trackedRowEffect = TrackedRowEffect()
    private val view: TestLithoView =
        rule.render(widthPx = ROW_HEIGHT_PX, heightPx = ROW_HEIGHT_PX * VIEWPORT_ROWS) {
          rowList(trackedRowLabel = "initial")
        }

    val attachedEffects: Int
      get() = events.count { it == "attach" } - events.count { it == "cleanup" }

    var trackedRowViewWasRecycled = false
      private set

    init {
      idle(rule)
      val recyclerView = recyclerViews(view.lithoView).first()
      val trackedRowView = checkNotNull(recyclerView.findViewHolderForAdapterPosition(0)).itemView
      recyclerView.addRecyclerListener { holder ->
        if (holder.itemView === trackedRowView) {
          trackedRowViewWasRecycled = true
        }
      }
    }

    fun scrollBy(dy: Int) {
      val recyclerView = recyclerViews(view.lithoView).first()
      rule.act(view) { recyclerView.scrollBy(0, dy) }
      idle(rule)
    }

    fun trackedRowIsBound(): Boolean =
        recyclerViews(view.lithoView).first().findViewHolderForAdapterPosition(0) != null

    fun stickyHeaderIsShown(): Boolean {
      val sectionsRecyclerView =
          recyclerViews(view.lithoView).first().parent as SectionsRecyclerView
      return !sectionsRecyclerView.isStickyHeaderHidden
    }

    fun removeTrackedRow() {
      view.setRoot(rowList(trackedRowLabel = null))
      idle(rule)
    }

    fun updateTrackedRow(label: String) {
      view.setRoot(rowList(trackedRowLabel = label))
      idle(rule)
    }

    fun moveTrackedRowToTheEnd() {
      view.setRoot(rowList(trackedRowLabel = "initial", trackedRowIsLast = true))
      idle(rule)
    }

    fun emptyTrackedRow() {
      view.setRoot(
          RowList(
              trackedRow = EmptyComponent(),
              trackedRowIsSticky = trackedRowIsSticky,
              trackedRowIsLast = false,
          ),
      )
      idle(rule)
    }

    fun updateTrackedRowStateFromItsEffect() {
      val updateState =
          checkNotNull(trackedRowEffect.updateState) { "the tracked row's effect is not attached" }
      rule.act(view) { updateState() }
      idle(rule)
    }

    private fun rowList(trackedRowLabel: String?, trackedRowIsLast: Boolean = false) = RowList(
        trackedRow =
            trackedRowLabel?.let { label ->
              TrackedRow(
                  label,
                  events,
                  countEffects,
                  readEffects,
                  renders,
                  trackedRowCounts,
                  trackedRowEffect,
              )
            },
        trackedRowIsSticky = trackedRowIsSticky,
        trackedRowIsLast = trackedRowIsLast,
    )

    fun close() {
      view.release()
      idle(rule)
    }
  }

  /** Fillers with one tracked row, first or last; no tracked row when [trackedRow] is null. */
  private class RowList(
      private val trackedRow: Component?,
      private val trackedRowIsSticky: Boolean,
      private val trackedRowIsLast: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height((ROW_HEIGHT_PX * VIEWPORT_ROWS).px)) {
          if (trackedRow != null && !trackedRowIsLast) {
            child(id = "tracked", isSticky = trackedRowIsSticky, component = trackedRow)
          }
          children(items = FILLER_INDEXES, id = { index -> "filler_$index" }) { Filler() }
          if (trackedRow != null && trackedRowIsLast) {
            child(id = "tracked", isSticky = trackedRowIsSticky, component = trackedRow)
          }
        }
  }

  /** What the tracked row's effect hands out while it is attached. */
  private class TrackedRowEffect {
    var updateState: (() -> Unit)? = null
  }

  private class TrackedRow(
      private val label: String,
      private val events: MutableList<String>,
      private val countEffects: MutableList<String>,
      private val readEffects: MutableList<String>,
      private val renders: MutableList<String>,
      private val counts: MutableList<Int>,
      private val effect: TrackedRowEffect,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val count = useState { 0 }
      renders.add(label)
      counts.add(count.value)
      useEffect(Unit) {
        events.add("attach")
        effect.updateState = { count.update { it + 1 } }
        onCleanup {
          events.add("cleanup")
          effect.updateState = null
        }
      }
      useEffect(Unit) {
        readEffects.add("attach:${count.value}")
        onCleanup { readEffects.add("cleanup") }
      }
      val countValue = count.value
      useEffect(countValue) {
        countEffects.add("attach:$countValue")
        onCleanup { countEffects.add("cleanup:$countValue") }
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
    // Off screen and past RecyclerView's view cache, so the row's view goes to the recycled pool,
    // but well within the prepared range (several viewports).
    private const val NEAR_ROWS = 6
    private const val ROW_COUNT = 200
    private val FILLER_INDEXES = (0 until ROW_COUNT).toList()

    private fun rule(newCollection: Boolean): LithoTestRule = LithoTestRule(
        componentsConfiguration =
            ComponentsConfiguration.defaultInstance.copy(enableNewCollection = newCollection),
    )
  }
}
