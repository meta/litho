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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.AOSPLithoVisibilityEventsController
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.KComponent
import com.facebook.litho.LithoSaveableStateRegistry
import com.facebook.litho.LithoSaveableStateRegistryTreeProp
import com.facebook.litho.LithoVisibilityEventsController
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.TreeState
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.eventHandlerWithReturn
import com.facebook.litho.key
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.sections.SectionContext
import com.facebook.litho.sections.common.DataDiffSection
import com.facebook.litho.sections.common.RenderEvent
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.useSaveableState
import com.facebook.litho.useState
import com.facebook.litho.widget.collection.LazyList
import com.facebook.litho.widget.collection.RecyclerCollectionComponent
import com.facebook.rendercore.px
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import java.util.concurrent.ConcurrentHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** `useSaveableState` in the rows of `RecyclerBinder` collections (`enableNewCollection` off). */
@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RecyclerBinderSaveableStateTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  // Attached views keep their trees reachable, as a window does; the owner holds trees weakly.
  private val views = mutableListOf<TestLithoView>()

  @Test
  fun `rows restore after recreation`() {
    listOf(true, false).forEach { controlled ->
      val owner = TestSavedStateOwner()
      val rows = RowRecorder()
      render(owner, Screen(Mode.LAZY, rows), controlled)
      rows.update(3, 3)

      val restored = RowRecorder()
      recreate(owner, Screen(Mode.LAZY, restored), controlled)
      assertThat(restored.value(3)).describedAs("controlled=$controlled").isEqualTo(3)
    }
  }

  @Test
  fun `a list shown again under a lifecycle controller saves its rows`() {
    assertListShownAgainSavesRows(controlled = true)
  }

  @Test
  fun `a list shown again without a lifecycle controller saves its rows`() {
    assertListShownAgainSavesRows(controlled = false)
  }

  @Test
  fun `a list shown again after its removed rows rendered saves its rows`() {
    listOf(true, false).forEach { controlled ->
      RACING_REMOVALS.forEach { removal -> assertListShownAgainSavesRows(controlled, removal) }
    }
  }

  @Test
  fun `a removed list does not restore its rows when shown again after recreation`() {
    listOf(true, false).forEach { controlled ->
      LISTS.forEach { mode ->
        assertThat(valueAfterRemovedListAndRecreation(controlled, removed = mode, shown = mode))
            .describedAs("$mode, controlled=$controlled")
            .isEqualTo(0)
      }
    }
  }

  @Test
  fun `a list removed while its rows render does not restore them after recreation`() {
    listOf(true, false).forEach { controlled ->
      RACING_REMOVALS.forEach { removal ->
        LISTS.forEach { mode ->
          assertThat(valueAfterRemovedListAndRecreation(controlled, mode, mode, removal))
              .describedAs("$mode, $removal, controlled=$controlled")
              .isEqualTo(0)
        }
      }
    }
  }

  @Test
  fun `a list whose rows never read saveable state leaves the restored values to another tree`() {
    listOf(true, false).forEach { controlled ->
      LISTS.forEach { mode ->
        val session = TestSavedStateOwner()
        val rows = RowRecorder()
        render(session, Screen(mode, rows), controlled)
        rows.update(0, 7)
        val savedState = session.save()
        session.destroy()

        val owner = TestSavedStateOwner(savedState)
        val unread = render(owner, Screen(mode, RowRecorder(loaded = false)), controlled)
        lithoTestRule.act(unread) { recyclerView(unread).scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
        lithoTestRule.act(unread) { recyclerView(unread).scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }
        unread.setRoot(Screen(Mode.NONE, RowRecorder()))
        lithoTestRule.idle()

        val restored = RowRecorder()
        render(owner, Screen(mode, restored), controlled)
        assertThat(restored.value(0)).describedAs("$mode, controlled=$controlled").isEqualTo(7)
      }
    }
  }

  @Test
  fun `a removed list does not restore its rows into another list with the same ids`() {
    listOf(true, false).forEach { controlled ->
      LISTS.forEach { mode ->
        val other = LISTS.first { it != mode }
        assertThat(valueAfterRemovedListAndRecreation(controlled, removed = mode, shown = other))
            .describedAs("$mode, controlled=$controlled")
            .isEqualTo(0)
      }
    }
  }

  @Test
  fun `a sections list replacing a lazy list with the same ids in one layout saves its rows`() {
    listOf(true, false).forEach { controlled ->
      val owner = TestSavedStateOwner()
      val rows = RowRecorder()
      val view = render(owner, Screen(Mode.LAZY, rows), controlled)
      rows.update(0, 1)

      view.setRoot(Screen(Mode.SECTIONS, rows))
      lithoTestRule.idle()
      rows.update(0, 2)
      rows.update(2, 3)

      val restored = RowRecorder()
      recreate(owner, Screen(Mode.SECTIONS, restored), controlled)
      assertThat(restored.value(0)).describedAs("id row, controlled=$controlled").isEqualTo(2)
      assertThat(restored.value(2)).describedAs("keyed row, controlled=$controlled").isEqualTo(3)
    }
  }

  @Test
  fun `two lists with the same row ids save their rows apart`() {
    val owner = TestSavedStateOwner()
    val first = RowRecorder()
    val second = RowRecorder()
    render(owner, TwoLists(first, second), controlled = true)
    first.update(0, 3)
    second.update(0, 4)

    val restoredFirst = RowRecorder()
    val restoredSecond = RowRecorder()
    recreate(owner, TwoLists(restoredFirst, restoredSecond), controlled = true)
    assertThat(restoredFirst.value(0)).isEqualTo(3)
    assertThat(restoredSecond.value(0)).isEqualTo(4)
  }

  @Test
  fun `rows with the same id in one list are not saved and leave other lists saved`() {
    val owner = TestSavedStateOwner()
    val first = RowRecorder()
    val second = RowRecorder()
    val sharedIds = listOf(rowId(0), rowId(0))
    render(owner, TwoLists(first, second, sharedIds), controlled = true)
    first.update(0, 3)
    second.update(0, 5)
    assertThat(second.value(1)).isEqualTo(0)

    val restoredFirst = RowRecorder()
    val restoredSecond = RowRecorder()
    recreate(owner, TwoLists(restoredFirst, restoredSecond, sharedIds), controlled = true)
    assertThat(restoredFirst.value(0)).isEqualTo(3)
    assertThat(restoredSecond.value(0)).isEqualTo(0)
    assertThat(restoredSecond.value(1)).isEqualTo(0)
  }

  @Test
  fun `rows of a nested list restore after recreation`() {
    listOf(true, false).forEach { controlled ->
      val owner = TestSavedStateOwner()
      val rows = RowRecorder()
      render(owner, OuterList(rows), controlled)
      rows.update(0, 5)

      val restored = RowRecorder()
      recreate(owner, OuterList(restored), controlled)
      assertThat(restored.value(0)).describedAs("controlled=$controlled").isEqualTo(5)
    }
  }

  @Test
  fun `rows of a nested list stay saved after the outer row is recycled`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, OuterList(rows), controlled = false)
    rows.update(0, 5)
    val registryBeforeRecycling = rows.registry(0)
    val recyclerView = recyclerView(view)

    lithoTestRule.act(view) { recyclerView.scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
    lithoTestRule.act(view) { recyclerView.scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }
    assertThat(rows.registry(0)).isNotSameAs(registryBeforeRecycling)
    assertThat(rows.value(0)).isEqualTo(5)
    rows.update(0, 8)

    val restored = RowRecorder()
    recreate(owner, OuterList(restored), controlled = false)
    assertThat(restored.value(0)).isEqualTo(8)
  }

  @Test
  fun `a row out of the range keeps its value but is saved only once it is back in the range`() {
    listOf(true, false).forEach { controlled ->
      LISTS.forEach { mode ->
        val case = "$mode, controlled=$controlled"
        val owner = TestSavedStateOwner()
        val rows = RowRecorder()
        val view = render(owner, Screen(mode, rows), controlled)
        rows.update(0, 5)
        val registry = rows.registry(0)

        lithoTestRule.act(view) { recyclerView(view).scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
        val savedWhileOut = owner.save()
        lithoTestRule.act(view) { recyclerView(view).scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }

        assertThat(rows.value(0)).describedAs(case).isEqualTo(5)
        assertThat(rows.registry(0)).describedAs(case).isSameAs(registry)
        rows.update(0, 8)
        val savedWhileBack = owner.save()
        owner.destroy()
        val restoredFromOut = RowRecorder()
        render(TestSavedStateOwner(savedWhileOut), Screen(mode, restoredFromOut), controlled)
        assertThat(restoredFromOut.value(0)).describedAs(case).isEqualTo(0)
        val restoredFromBack = RowRecorder()
        render(TestSavedStateOwner(savedWhileBack), Screen(mode, restoredFromBack), controlled)
        assertThat(restoredFromBack.value(0)).describedAs(case).isEqualTo(8)
      }
    }
  }

  /**
   * ```text
   * Row 0 = 5, scrolled out of the range
   * Row 0 changes to leave its value out, and scrolls back   a save keeps 5
   * Row 0 shows its value again                              5
   * ```
   */
  @Test
  fun `a row rebuilt without its value after leaving the range keeps the value`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, FirstValueList(rows, showsFirstValue = true), controlled = false)
    rows.update(0, 5)

    lithoTestRule.act(view) { recyclerView(view).scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
    view.setRoot(FirstValueList(rows, showsFirstValue = false))
    lithoTestRule.idle()
    lithoTestRule.act(view) { recyclerView(view).scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }
    val savedWhileLeftOut = owner.save()
    view.setRoot(FirstValueList(rows, showsFirstValue = true))
    lithoTestRule.idle()

    assertThat(rows.value(0)).isEqualTo(5)
    owner.destroy()
    val restored = RowRecorder()
    render(
        TestSavedStateOwner(savedWhileLeftOut),
        FirstValueList(restored, true),
        controlled = false,
    )
    assertThat(restored.value(0)).isEqualTo(5)
  }

  @Test
  fun `rows in the range but not on screen are saved`() {
    listOf(true, false).forEach { controlled ->
      val owner = TestSavedStateOwner()
      val rows = RowRecorder()
      val view = render(owner, Screen(Mode.LAZY, rows), controlled)
      val layoutManager = checkNotNull(recyclerView(view).layoutManager as? LinearLayoutManager)
      assertThat(layoutManager.findLastVisibleItemPosition()).isLessThan(AHEAD_ROW)
      rows.update(AHEAD_ROW, 6)
      rows.update(0, 5)

      lithoTestRule.act(view) { recyclerView(view).scrollBy(0, (AHEAD_ROW - 5) * ROW_HEIGHT_PX) }
      assertThat(layoutManager.findFirstVisibleItemPosition()).isGreaterThan(0)

      val restored = RowRecorder()
      recreate(owner, Screen(Mode.LAZY, restored), controlled)
      assertThat(restored.value(0)).describedAs("controlled=$controlled").isEqualTo(5)
      assertThat(restored.value(AHEAD_ROW)).describedAs("controlled=$controlled").isEqualTo(6)
    }
  }

  @Test
  fun `a removed row added back with the same id starts fresh`() {
    listOf(true, false).forEach { controlled ->
      val owner = TestSavedStateOwner()
      val rows = RowRecorder()
      val view = render(owner, Screen(Mode.LAZY, rows), controlled)
      (0..2).forEach { rows.update(it, 10 + it) }

      view.setRoot(Screen(Mode.LAZY, rows, ALL_ROWS - 1))
      lithoTestRule.idle()
      val savedWhileRemoved = owner.save()
      view.setRoot(Screen(Mode.LAZY, rows))
      lithoTestRule.idle()

      assertThat((0..2).map(rows::value))
          .describedAs("controlled=$controlled")
          .containsExactly(10, 0, 12)
      owner.destroy()
      val restored = RowRecorder()
      render(TestSavedStateOwner(savedWhileRemoved), Screen(Mode.LAZY, restored), controlled)
      assertThat((0..2).map(restored::value))
          .describedAs("controlled=$controlled")
          .containsExactly(10, 0, 12)
    }
  }

  @Test
  fun `a row removed while out of the range starts fresh when added back`() {
    listOf(true, false).forEach { controlled ->
      val rows = RowRecorder()
      val view = render(TestSavedStateOwner(), Screen(Mode.LAZY, rows), controlled)
      rows.update(0, 5)
      lithoTestRule.act(view) { recyclerView(view).scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }

      view.setRoot(Screen(Mode.LAZY, rows, ALL_ROWS - 0))
      lithoTestRule.idle()
      view.setRoot(Screen(Mode.LAZY, rows))
      lithoTestRule.idle()
      lithoTestRule.act(view) { recyclerView(view).scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }

      assertThat(rows.value(0)).describedAs("controlled=$controlled").isEqualTo(0)
    }
  }

  @Test
  fun `rows stay saved after their tree is replaced with its state under a lifecycle controller`() {
    val owner = TestSavedStateOwner()
    assertRowsStaySavedAfterTreeReplacement(AOSPLithoVisibilityEventsController(owner))
  }

  @Test
  fun `rows stay saved after their tree is replaced with its state without a lifecycle controller`() {
    assertRowsStaySavedAfterTreeReplacement(controller = null)
  }

  @Test
  fun `rows that first read saveable state while their tree is replaced stay saved`() {
    val controller = AOSPLithoVisibilityEventsController(TestSavedStateOwner())
    val registry = LithoSaveableStateRegistry()
    val rows = RowRecorder(loaded = false)
    val view = render(registry, Screen(Mode.LAZY, rows), controller)

    val treeState = view.componentTree.acquireTreeState()
    view.componentTree.release()
    rows.load(0)
    lithoTestRule.idle()
    render(registry, Screen(Mode.LAZY, rows), controller, treeState)
    rows.update(0, 5)

    val restored = RowRecorder()
    render(LithoSaveableStateRegistry(registry.snapshot()), Screen(Mode.LAZY, restored), null)
    assertThat(restored.value(0)).isEqualTo(5)
  }

  @Test
  fun `two binders an app builds from one context save their rows`() {
    val owner = TestSavedStateOwner()
    val first = RowRecorder()
    val second = RowRecorder()
    render(owner, AppBuiltBinders(first, second), controlled = true)
    first.update(0, 3)
    second.update(2, 4)

    val restoredFirst = RowRecorder()
    val restoredSecond = RowRecorder()
    recreate(owner, AppBuiltBinders(restoredFirst, restoredSecond), controlled = true)
    assertThat(restoredFirst.value(0)).isEqualTo(3)
    assertThat(restoredSecond.value(2)).isEqualTo(4)
  }

  /**
   * ```text
   * Before process death: row 0 = 4; the owner is not restored yet
   * Render the tree on the main thread    Row 0's tree renders 0
   * Android restores, then show the tree  Row 0 renders 4 before it mounts
   * Save and restore again                Row 0 restores 4
   * ```
   */
  @Test
  fun `a row rendered before a late restore shows its restored value`() {
    listOf(true, false).forEach { controlled ->
      LISTS.forEach { mode ->
        val case = "$mode, controlled=$controlled"
        val session = TestSavedStateOwner()
        val sessionRows = RowRecorder()
        render(session, Screen(mode, sessionRows), controlled)
        sessionRows.update(0, 4)
        val owner = TestSavedStateOwner(session.save(), restoreNow = false)
        session.destroy()
        val rows = RowRecorder()
        val root = Screen(mode, rows)
        val tree = tree(owner, root, controlled)
        tree.setRootAndSizeSpecSync(root, exactly(WIDTH_PX), exactly(LIST_HEIGHT_PX))
        assertThat(rows.value(0)).describedAs(case).isEqualTo(0)

        owner.restore()
        show(tree)

        assertThat(rows.value(0)).describedAs(case).isEqualTo(4)
        val restored = RowRecorder()
        recreate(owner, Screen(mode, restored), controlled)
        assertThat(restored.value(0)).describedAs(case).isEqualTo(4)
      }
    }
  }

  /** With a [Removal.LOADED_LATER] removal, no row reads saveable state before the list goes. */
  private fun assertListShownAgainSavesRows(controlled: Boolean, removal: Removal = Removal.IDLE) {
    LISTS.forEach { mode ->
      val owner = TestSavedStateOwner()
      val rows = RowRecorder(loaded = removal != Removal.LOADED_LATER)
      val view = render(owner, Screen(mode, rows), controlled)
      if (removal != Removal.LOADED_LATER) rows.update(0, 1)

      removeList(view, rows, removal)
      val shown = RowRecorder()
      view.setRoot(Screen(mode, shown))
      lithoTestRule.idle()
      assertThat(shown.value(0)).describedAs("$mode, $removal").isEqualTo(0)
      shown.update(0, 3)

      val restored = RowRecorder()
      recreate(owner, Screen(mode, restored), controlled)
      assertThat(restored.value(0)).describedAs("$mode, $removal").isEqualTo(3)
    }
  }

  /**
   * Sets row 0 of the [removed] list to 2, removes the list, recreates the owner, then shows the
   * [shown] list and returns the value of its row 0.
   */
  private fun valueAfterRemovedListAndRecreation(
      controlled: Boolean,
      removed: Mode,
      shown: Mode,
      removal: Removal = Removal.IDLE,
  ): Int {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder(loaded = removal != Removal.LOADED_LATER)
    val view = render(owner, Screen(removed, rows), controlled)
    if (removal == Removal.LOADED_LATER) {
      rows.load(0)
      lithoTestRule.idle()
    }
    rows.update(0, 2)
    removeList(view, rows, removal)

    val savedState = owner.save()
    owner.destroy()
    val restored = RowRecorder()
    val restoredView =
        render(TestSavedStateOwner(savedState), Screen(Mode.NONE, restored), controlled)
    restoredView.setRoot(Screen(shown, restored))
    lithoTestRule.idle()
    return restored.value(0)
  }

  private fun removeList(view: TestLithoView, rows: RowRecorder, removal: Removal) {
    if (removal == Removal.SCROLLED) recyclerView(view).scrollBy(0, 20 * ROW_HEIGHT_PX)
    view.setRoot(Screen(Mode.NONE, rows))
    lithoTestRule.idle()
    if (removal == Removal.LOADED_LATER) {
      rows.load(LATE_ROW)
      lithoTestRule.idle()
    }
  }

  private fun assertRowsStaySavedAfterTreeReplacement(
      controller: LithoVisibilityEventsController?,
  ) {
    val registry = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val view = render(registry, Screen(Mode.LAZY, rows), controller)
    rows.update(3, 5)

    // As LithoViewModel does on rotation: the replacement tree takes the state and the registry.
    val treeState = view.componentTree.acquireTreeState()
    view.componentTree.release()
    render(registry, Screen(Mode.LAZY, rows), controller, treeState)
    assertThat(rows.value(3)).isEqualTo(5)
    rows.update(3, 8)

    val restored = RowRecorder()
    render(LithoSaveableStateRegistry(registry.snapshot()), Screen(Mode.LAZY, restored), null)
    assertThat(restored.value(3)).isEqualTo(8)
  }

  private fun render(
      owner: SavedStateRegistryOwner,
      root: Component,
      controlled: Boolean,
  ): TestLithoView = show(tree(owner, root, controlled))

  private fun tree(
      owner: SavedStateRegistryOwner,
      root: Component,
      controlled: Boolean,
  ): ComponentTree {
    val builder =
        ComponentTree.create(lithoTestRule.context.androidContext, owner, root)
            .componentsConfiguration(DEFAULT_COLLECTION)
    if (controlled) {
      builder.withLithoVisibilityEventsController(AOSPLithoVisibilityEventsController(owner))
    }
    return builder.build()
  }

  private fun render(
      registry: LithoSaveableStateRegistry,
      root: Component,
      controller: LithoVisibilityEventsController?,
      treeState: TreeState? = null,
  ): TestLithoView = show(
      ComponentTree.create(lithoTestRule.context, root)
          .withRetainedSaveableStateRegistryForTreeReplacement(registry)
          .withLithoVisibilityEventsController(controller)
          .componentsConfiguration(DEFAULT_COLLECTION)
          .treeState(treeState)
          .build(),
  )

  private fun show(tree: ComponentTree): TestLithoView =
      lithoTestRule
          .createTestLithoView(componentTree = tree, widthPx = WIDTH_PX, heightPx = LIST_HEIGHT_PX)
          .attachToWindow()
          .measure()
          .layout()
          .also { view ->
            lithoTestRule.idle()
            views += view
          }

  /** Saves [owner], destroys it, and renders [root] under an owner restored from that save. */
  private fun recreate(owner: TestSavedStateOwner, root: Component, controlled: Boolean) {
    val savedState = owner.save()
    owner.destroy()
    render(TestSavedStateOwner(savedState), root, controlled)
  }

  private fun recyclerView(view: TestLithoView): RecyclerView =
      checkNotNull(findRecyclerView(view.lithoView))

  private fun findRecyclerView(view: View): RecyclerView? {
    if (view is RecyclerView) return view
    if (view !is ViewGroup) return null
    return (0 until view.childCount).firstNotNullOfOrNull { findRecyclerView(view.getChildAt(it)) }
  }

  private enum class Mode {
    LAZY,
    SECTIONS,
    NONE,
  }

  /** What the rows of a list do while it is removed. */
  private enum class Removal {
    IDLE,
    /** Rows ahead of a scroll are still laid out in the background. */
    SCROLLED,
    /** A row first reads saveable state when it loads, after the list is gone. */
    LOADED_LATER,
  }

  /** A screen whose list of the rows [indexes], if any, is in the same place in every [Mode]. */
  private class Screen(
      private val mode: Mode,
      private val rows: RowRecorder,
      private val indexes: List<Int> = ALL_ROWS,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(Text(text = "picker", style = Style.height(10.px)))
      when (mode) {
        Mode.LAZY -> child(SaveableList(rows, indexes))
        Mode.SECTIONS -> child(SectionsList(rows, indexes))
        Mode.NONE -> Unit
      }
    }
  }

  private class SaveableList(
      private val rows: RowRecorder,
      private val indexes: List<Int>,
      private val ids: List<String> = indexes.map(::rowId),
      private val height: Int = LIST_HEIGHT_PX,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(height.px)) {
          indexes.forEachIndexed { position, index ->
            child(id = ids[position], component = SaveableRow(index, rows))
          }
        }
  }

  /** Every row, where row 0 reads its saveable value only when [showsFirstValue]. */
  private class FirstValueList(
      private val rows: RowRecorder,
      private val showsFirstValue: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px)) {
          children(items = ALL_ROWS, id = { rowId(it) }) { index ->
            SaveableRow(index, rows, showsValue = index != 0 || showsFirstValue)
          }
        }
  }

  /** Rows 0 and 1 of the first list, and of the second list with [secondIds]. */
  private class TwoLists(
      private val first: RowRecorder,
      private val second: RowRecorder,
      private val secondIds: List<String> = listOf(rowId(0), rowId(1)),
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(SaveableList(first, FIRST_TWO_ROWS, height = LIST_HEIGHT_PX / 2))
      child(SaveableList(second, FIRST_TWO_ROWS, secondIds, LIST_HEIGHT_PX / 2))
    }
  }

  /**
   * A Sections list of the same rows: every fourth row has the [RecyclerBinder] id of the matching
   * [SaveableList] row, rows two after those have a component key, and the others have neither.
   */
  @Suppress("DEPRECATION")
  private class SectionsList(private val rows: RowRecorder, private val indexes: List<Int>) :
      KComponent() {
    override fun ComponentScope.render(): Component = RecyclerCollectionComponent(
        section =
            DataDiffSection.create<Int>(SectionContext(context))
                .data(indexes)
                .renderEventHandler(
                    eventHandlerWithReturn { event: RenderEvent<out Int> ->
                      sectionRow(event.model)
                    },
                )
                .build(),
        disablePTR = true,
        style = Style.height(LIST_HEIGHT_PX.px),
    )

    private fun sectionRow(index: Int): RenderInfo {
      val builder = ComponentRenderInfo.create()
      when (index % 4) {
        0 ->
            builder
                .component(SaveableRow(index, rows))
                .customAttribute(RecyclerBinder.ID_CUSTOM_ATTR_KEY, rowId(index))
        2 -> builder.component(key(rowId(index)) { SaveableRow(index, rows) })
        else -> builder.component(SaveableRow(index, rows))
      }
      return builder.build()
    }
  }

  /** A list whose first row is a horizontal list of saveable rows. */
  private class OuterList(private val rows: RowRecorder) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px)) {
          child(id = rowId(0), component = InnerList(rows))
          (1 until ROW_COUNT).forEach { index ->
            child(
                id = rowId(index),
                component = Text(text = "row_$index", style = Style.height(ROW_HEIGHT_PX.px)),
            )
          }
        }
  }

  private class InnerList(private val rows: RowRecorder) : KComponent() {
    override fun ComponentScope.render(): Component = LazyList(
        orientation = RecyclerView.HORIZONTAL,
        style = Style.height(ROW_HEIGHT_PX.px),
    ) {
      (0..2).forEach { index -> child(id = rowId(index), component = SaveableRow(index, rows)) }
    }
  }

  /**
   * Two lists whose binders this component builds from its own context, as app code may; the first
   * has rows 0 and 1, the second rows 2 and 3.
   */
  private class AppBuiltBinders(private val first: RowRecorder, private val second: RowRecorder) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      val binders = useState { List(2) { RecyclerBinder.Builder().build(context) } }.value
      useEffect(Unit) {
        listOf(first, second).forEachIndexed { list, rows ->
          val renderInfos =
              (0..1).map { row ->
                val index = list * 2 + row
                ComponentRenderInfo.create()
                    .component(SaveableRow(index, rows))
                    .customAttribute(RecyclerBinder.ID_CUSTOM_ATTR_KEY, rowId(index))
                    .build()
              }
          binders[list].insertRangeAt(0, renderInfos)
          binders[list].notifyChangeSetComplete(true, null)
        }
        onCleanup { binders.forEach(RecyclerBinder::detach) }
      }
      return Column {
        binders.forEach { binder ->
          child(Recycler(binder = binder, style = Style.height((LIST_HEIGHT_PX / 2).px)))
        }
      }
    }
  }

  private class SaveableRow(
      private val index: Int,
      private val rows: RowRecorder,
      private val showsValue: Boolean = true,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val loaded = useState { rows.loaded }
      rows.recordLoaded(index, loaded)
      if (loaded.value && showsValue) {
        rows.record(index, useSaveableState { 0 }, LithoSaveableStateRegistryTreeProp.value)
      }
      return Text(text = "row_$index", style = Style.height(ROW_HEIGHT_PX.px))
    }
  }

  /**
   * The latest state and registry each row rendered with. Rows read saveable state once loaded,
   * from the start if [loaded].
   */
  private class RowRecorder(val loaded: Boolean = true) {
    private val states = ConcurrentHashMap<Int, State<Int>>()
    private val registries = ConcurrentHashMap<Int, LithoSaveableStateRegistry>()
    private val loadedStates = ConcurrentHashMap<Int, State<Boolean>>()

    fun record(index: Int, state: State<Int>, registry: LithoSaveableStateRegistry?) {
      states[index] = state
      registry?.let { registries[index] = it }
    }

    fun recordLoaded(index: Int, loaded: State<Boolean>) {
      loadedStates[index] = loaded
    }

    @SuppressLint("KComponentSyncUpdateDetector")
    fun load(index: Int) =
        checkNotNull(loadedStates[index]) { "row $index not rendered" }.updateSync(true)

    fun value(index: Int): Int = checkNotNull(states[index]) { "row $index not rendered" }.value

    @SuppressLint("KComponentSyncUpdateDetector")
    fun update(index: Int, value: Int) = checkNotNull(states[index]).updateSync(value)

    fun registry(index: Int): LithoSaveableStateRegistry = checkNotNull(registries[index])
  }

  /**
   * With [restoreNow] false, the owner stays INITIALIZED and unrestored until [restore], as an
   * Activity is before `super.onCreate()` restores it.
   */
  private class TestSavedStateOwner(
      private val restoredState: Bundle? = null,
      restoreNow: Boolean = true,
  ) : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    init {
      controller.performAttach()
      if (restoreNow) restore()
    }

    fun restore() {
      controller.performRestore(restoredState)
      lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun save(): Bundle = Bundle().also(controller::performSave)

    fun destroy() {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
  }

  private companion object {
    const val WIDTH_PX: Int = 100
    const val ROW_HEIGHT_PX: Int = 100
    const val LIST_HEIGHT_PX: Int = 1000
    const val ROW_COUNT: Int = 60
    val ALL_ROWS: List<Int> = List(ROW_COUNT) { it }

    /** A row with a [RecyclerBinder] id in every list, in the first screen. */
    const val LATE_ROW: Int = 4

    /** A row below the first screen that the list renders ahead, within its range. */
    const val AHEAD_ROW: Int = 20
    val DEFAULT_COLLECTION: ComponentsConfiguration =
        ComponentsConfiguration.defaultInstance.copy(enableNewCollection = false)
    val LISTS: List<Mode> = listOf(Mode.LAZY, Mode.SECTIONS)
    val RACING_REMOVALS: List<Removal> = listOf(Removal.SCROLLED, Removal.LOADED_LATER)
    val FIRST_TWO_ROWS: List<Int> = listOf(0, 1)

    private fun rowId(index: Int): String = "row-$index"
  }
}
