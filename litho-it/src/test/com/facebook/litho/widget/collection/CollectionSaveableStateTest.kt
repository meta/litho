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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.LithoSaveableStateRegistry
import com.facebook.litho.LithoSaveableStateRegistryTreeProp
import com.facebook.litho.LithoView
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.TreePropContainer
import com.facebook.litho.TreeState
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.key
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useSaveableState
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.LinearLayoutInfo
import com.facebook.litho.widget.LithoRecyclerView.OnAfterLayoutListener
import com.facebook.litho.widget.RecyclerBinder
import com.facebook.litho.widget.RenderInfo
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.px
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionSaveableStateTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  // Attached views keep their trees reachable, as a window does; the owner holds trees weakly.
  private val views = mutableListOf<TestLithoView>()

  @Test
  fun `rows rooted at the same component render without throwing`() {
    val rows = RowRecorder()

    render(TestSavedStateOwner(), SaveableList(listOf(0, 1, 2), rows))

    assertThat(rows.states.keys).containsExactlyInAnyOrder(0, 1, 2)
  }

  @Test
  fun `row values restore per row after recreation`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    render(owner, SaveableList(listOf(0, 1, 2), rows))
    rows.update(0, 10)
    rows.update(1, 11)

    val restored = RowRecorder()
    recreate(owner, SaveableList(listOf(0, 1, 2), restored))

    assertThat(restored.value(0)).isEqualTo(10)
    assertThat(restored.value(1)).isEqualTo(11)
    assertThat(restored.value(2)).isEqualTo(0)
  }

  /**
   * Row 0 is set to 5, then scrolled far away, so its renderer drops its layout and the value can
   * only come from the row's registry. A save taken while the row is away leaves it out, since it
   * was shown and then recycled, so restoring from that save gives 0. Scrolled back, the row shows
   * 5, and a save taken then includes it again, so restoring from that save gives 5.
   */
  @Test
  fun `a row scrolled away keeps its value and is saved again once it is shown`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val ids = (0 until ROW_COUNT).toList()
    val view = render(owner, SaveableList(ids, rows))
    rows.update(0, 5)
    val recyclerView = recyclerView(view)
    val adapter = recyclerView.adapter as CollectionPrimitiveViewAdapter
    val firstRow = adapter.getItems().first() as LithoCollectionItem

    lithoTestRule.act(view) { recyclerView.scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }

    // Unprepared: its renderer has dropped its layout.
    assertThat(firstRow.size).isEqualTo(IntSize.Invalid)
    val savedWhileAway = owner.save()

    lithoTestRule.act(view) { recyclerView.scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }

    assertThat(rows.value(0)).isEqualTo(5)
    val savedWhileShown = owner.save()
    owner.destroy()
    val restoredFromAway = RowRecorder()
    render(TestSavedStateOwner(savedWhileAway), SaveableList(ids, restoredFromAway))
    assertThat(restoredFromAway.value(0)).isEqualTo(0)
    val restoredFromShown = RowRecorder()
    render(TestSavedStateOwner(savedWhileShown), SaveableList(ids, restoredFromShown))
    assertThat(restoredFromShown.value(0)).isEqualTo(5)
  }

  @Test
  fun `a recycled row keeps its value and is saved again once it is bound`() {
    val parent = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val item = LithoCollectionItem(
        contextWith(parent),
        renderInfo = rowRenderInfo(0, rows, id = 0),
        saveableStateRegistries = CollectionSaveableStateRegistries(COLLECTION_KEY),
    )
    val scope = layoutScope(item)
    val view = LithoRenderTreeView(lithoTestRule.context.androidContext)
    with(item) { scope.onBindView(view) }
    rows.update(0, 5)

    item.onViewRecycled(view)
    with(item) { scope.unprepare() }

    assertThat(measuredValue(LithoSaveableStateRegistry(parent.snapshot()))).isEqualTo(0)
    with(item) { scope.onBindView(view) }
    assertThat(rows.value(0)).isEqualTo(5)
    assertThat(measuredValue(LithoSaveableStateRegistry(parent.snapshot()))).isEqualTo(5)
  }

  @Test
  fun `a row rendered ahead but never bound keeps its restored value through another save`() {
    val parent = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val registries = CollectionSaveableStateRegistries(COLLECTION_KEY)
    bind(rowItem(parent, registries, rows, 0))
    rows.update(0, 5)
    val restoredParent = LithoSaveableStateRegistry(parent.snapshot())
    val restoredRows = RowRecorder()
    val restoredRegistries = CollectionSaveableStateRegistries(COLLECTION_KEY)

    // Row 1 is bound, so the list saves its rows itself. Row 0 renders as rows ahead of the
    // viewport do: it reads its value but is never bound.
    bind(rowItem(restoredParent, restoredRegistries, restoredRows, 1))
    rowItem(restoredParent, restoredRegistries, restoredRows, 0).measure(ROW_CONSTRAINTS, null)

    assertThat(restoredRows.value(0)).isEqualTo(5)
    assertThat(measuredValue(LithoSaveableStateRegistry(restoredParent.snapshot()))).isEqualTo(5)
  }

  @Test
  fun `rows built after the collection is detached are not saved until it is attached`() {
    val parent = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val registries = CollectionSaveableStateRegistries(COLLECTION_KEY)

    // The collection leaves the tree before a layout still in flight builds its rows.
    registries.detach()
    bind(rowItem(parent, registries, rows, 0))
    rows.update(0, 5)
    assertThat(measuredValue(LithoSaveableStateRegistry(parent.snapshot()))).isEqualTo(0)

    registries.attach()
    assertThat(measuredValue(LithoSaveableStateRegistry(parent.snapshot()))).isEqualTo(5)
  }

  @Test
  fun `a row stays saved while another of its items is bound`() {
    val parent = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val registries = CollectionSaveableStateRegistries(COLLECTION_KEY)
    bind(rowItem(parent, registries, rows, 0))
    val otherItem = LithoCollectionItem(
        contextWith(parent),
        renderInfo =
            ComponentRenderInfo.create()
                .customAttribute(RecyclerBinder.ID_CUSTOM_ATTR_KEY, 0)
                .component(PlainRow())
                .build(),
        saveableStateRegistries = registries,
    )
    val otherView = bind(otherItem)
    rows.update(0, 5)

    otherItem.onViewRecycled(otherView)

    assertThat(measuredValue(LithoSaveableStateRegistry(parent.snapshot()))).isEqualTo(5)
  }

  @Test
  fun `a removed row that was shown keeps its value but is not saved`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, SaveableList(listOf(0, 1, 2), rows))
    rows.update(0, 10)
    rows.update(1, 11)
    val removedRowRegistry = rows.registry(1)

    view.setRoot(SaveableList(listOf(0, 2), rows))
    lithoTestRule.idle()

    assertThat(removedRowRegistry.snapshot().isEmpty).isFalse
    val restored = RowRecorder()
    recreate(owner, SaveableList(listOf(0, 1, 2), restored))
    assertThat(restored.value(0)).isEqualTo(10)
    assertThat(restored.value(1)).isEqualTo(0)
  }

  @Test
  fun `a row filtered out and back keeps its value and is saved again`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, SaveableList(listOf(0, 1, 2), rows))
    rows.update(1, 11)

    view.setRoot(SaveableList(listOf(0, 2), rows))
    lithoTestRule.idle()
    view.setRoot(SaveableList(listOf(0, 1, 2), rows))
    lithoTestRule.idle()

    assertThat(rows.value(1)).isEqualTo(11)
    val restored = RowRecorder()
    recreate(owner, SaveableList(listOf(0, 1, 2), restored))
    assertThat(restored.value(1)).isEqualTo(11)
  }

  @Test
  fun `rows stay saved while incremental mount has unmounted the collection`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, ListBelowHeader(rows))
    rows.update(0, 5)
    val recyclerView = (view.findViewWithTag(LIST_TAG) as SectionsRecyclerView).recyclerView

    lithoTestRule.act(view) {
      view.lithoView.mountComponent(Rect(0, 0, WIDTH_PX, HEADER_HEIGHT_PX / 2), true)
    }

    assertThat(recyclerView.adapter).isNull()
    val restored = RowRecorder()
    recreate(owner, ListBelowHeader(restored))
    assertThat(restored.value(0)).isEqualTo(5)
  }

  @Test
  fun `a removed row without saveable state leaves no registry behind`() {
    val rows = RowRecorder()
    val view = render(TestSavedStateOwner(), StatelessList(listOf(0, 1, 2), rows))
    val removedRowRegistry = rows.registry(1)

    view.setRoot(StatelessList(listOf(0, 2), rows))
    lithoTestRule.idle()
    view.setRoot(StatelessList(listOf(0, 1, 2), rows))
    lithoTestRule.idle()

    assertThat(rows.registry(1)).isNotSameAs(removedRowRegistry)
  }

  @Test
  fun `removing the collection stops saving its rows`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, OptionalList(show = true, rows))
    rows.update(0, 10)

    view.setRoot(OptionalList(show = false, rows))
    lithoTestRule.idle()

    val restored = RowRecorder()
    recreate(owner, OptionalList(show = true, restored))
    assertThat(restored.value(0)).isEqualTo(0)
  }

  @Test
  fun `rows of a collection shown again are saved`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, OptionalList(show = true, rows))
    view.setRoot(OptionalList(show = false, rows))
    lithoTestRule.idle()

    view.setRoot(OptionalList(show = true, rows))
    lithoTestRule.idle()
    rows.update(0, 4)

    val restored = RowRecorder()
    recreate(owner, OptionalList(show = true, restored))
    assertThat(restored.value(0)).isEqualTo(4)
  }

  @Test
  fun `rows stay saved after their tree is replaced with its state`() {
    val registry = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val ids = listOf(0, 1, 2)
    val view = render(registry, SaveableList(ids, rows))
    rows.update(0, 5)

    // As LithoViewModel does on rotation: the replacement tree takes the state and the registry.
    val treeState = view.componentTree.acquireTreeState()
    view.release()
    render(registry, SaveableList(ids, rows), treeState)
    rows.update(0, 8)

    val restored = RowRecorder()
    render(LithoSaveableStateRegistry(registry.snapshot()), SaveableList(ids, restored))
    assertThat(restored.value(0)).isEqualTo(8)
  }

  @Test
  fun `removing the collection after a tree replacement drops rows that were scrolled away`() {
    val registry = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val ids = (0 until ROW_COUNT).toList()
    val view = render(registry, OptionalList(show = true, rows, ids))
    rows.update(0, 5)
    val recyclerView = recyclerView(view)
    lithoTestRule.act(view) { recyclerView.scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
    val treeState = view.componentTree.acquireTreeState()
    view.release()
    val replacement = render(registry, OptionalList(show = true, rows, ids), treeState)

    replacement.setRoot(OptionalList(show = false, rows, ids))
    lithoTestRule.idle()

    assertThat(registry.snapshot().isEmpty).isTrue
  }

  @Test
  fun `rows of a nested list stay saved after the outer row is recycled`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, OuterList(rows))
    rows.update(0, 5)
    val recyclerView = recyclerView(view)

    lithoTestRule.act(view) { recyclerView.scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
    lithoTestRule.act(view) { recyclerView.scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }
    rows.update(0, 8)

    val restored = RowRecorder()
    recreate(owner, OuterList(restored))
    assertThat(restored.value(0)).isEqualTo(8)
  }

  @Test
  fun `rows restore and stay saved when the list resolves again before it mounts`() {
    val ids = listOf(0, 1, 2)
    val previousRegistry = LithoSaveableStateRegistry()
    val previousRows = RowRecorder()
    render(previousRegistry, SaveableList(ids, previousRows))
    previousRows.update(0, 10)
    val registry = LithoSaveableStateRegistry(previousRegistry.snapshot())
    val rows = RowRecorder()
    val tree = tree(registry, SaveableList(ids, rows))

    // Both layouts finish before the main thread commits either, so the second resolve still diffs
    // against no data and creates new items for the same ids.
    tree.setRootAndSizeSpecAsync(
        SaveableList(ids, rows, revision = 1),
        exactly(WIDTH_PX),
        exactly(LIST_HEIGHT_PX),
    )
    lithoTestRule.finishAsyncUpdates()
    tree.setRootAndSizeSpecAsync(
        SaveableList(ids, rows, revision = 2),
        exactly(WIDTH_PX),
        exactly(LIST_HEIGHT_PX),
    )
    lithoTestRule.finishAsyncUpdates()
    show(tree)

    assertThat(rows.value(0)).isEqualTo(10)
    rows.update(0, 12)
    val restored = RowRecorder()
    render(LithoSaveableStateRegistry(registry.snapshot()), SaveableList(ids, restored))
    assertThat(restored.value(0)).isEqualTo(12)
  }

  @Test
  fun `items built for a row before it commits share the row's registry`() {
    val registry = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val tree = tree(registry, SaveableList(listOf(0), rows))

    tree.setRootAndSizeSpecAsync(
        SaveableList(listOf(0), rows, revision = 1),
        exactly(WIDTH_PX),
        exactly(LIST_HEIGHT_PX),
    )
    lithoTestRule.finishAsyncUpdates()
    tree.setRootAndSizeSpecAsync(
        SaveableList(listOf(0), rows, revision = 2),
        exactly(WIDTH_PX),
        exactly(LIST_HEIGHT_PX),
    )
    lithoTestRule.finishAsyncUpdates()
    show(tree)

    assertThat(rows.itemCount(0)).isGreaterThan(1)
    assertThat(rows.distinctRegistries(0)).isEqualTo(1)
  }

  @Test
  fun `two committed rows with the same id render but are not saved`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    render(owner, RowsWithIds(listOf(7, 7), rows))
    rows.update(0, 3)
    rows.update(1, 4)

    // The rows can't be told apart, so each has a registry of its own that is not saved.
    assertThat(rows.registry(0)).isNotSameAs(rows.registry(1))
    assertThat(rows.value(0)).isEqualTo(3)
    assertThat(rows.value(1)).isEqualTo(4)
    val restored = RowRecorder()
    recreate(owner, RowsWithIds(listOf(7, 7), restored))
    assertThat(restored.value(0)).isEqualTo(0)
    assertThat(restored.value(1)).isEqualTo(0)
  }

  @Test
  fun `a row whose id another row had stays unsaved after that row is removed`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, RowsWithIds(listOf(0, 1, 1), rows, listOf(null, "a", "b")))
    rows.update(0, 10)
    rows.update(1, 11)
    rows.update(2, 12)

    view.setRoot(RowsWithIds(listOf(0, 1), rows, listOf(null, "a")))
    lithoTestRule.idle()
    rows.update(1, 13)

    // As when two child registries read one key, the id stays unsaved in this collection.
    val restored = RowRecorder()
    recreate(owner, RowsWithIds(listOf(0, 1), restored, listOf(null, "a")))
    assertThat(restored.value(0)).isEqualTo(10)
    assertThat(restored.value(1)).isEqualTo(0)
  }

  @Test
  fun `a key only uncommitted items read keeps its saved value`() {
    val source = LithoSaveableStateRegistry()
    source.createChildRegistry(COLLECTION_KEY).createChildRegistry(ROW_KEY).registerProvider(
        "value",
    ) {
      5
    }
    val parent = LithoSaveableStateRegistry(source.snapshot())
    val registries = CollectionSaveableStateRegistries(COLLECTION_KEY)
    assertThat(registries.registryFor(parent, ROW_KEY).restoredValue("value")?.value).isEqualTo(5)

    registries.detach()
    registries.attach()

    val restoredRow =
        LithoSaveableStateRegistry(parent.snapshot())
            .createChildRegistry(COLLECTION_KEY)
            .createChildRegistry(ROW_KEY)
    assertThat(restoredRow.restoredValue("value")?.value).isEqualTo(5)
  }

  @Test
  fun `a row stays saved while its sticky copy is shown`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val ids = (0 until ROW_COUNT).toList()
    val view = render(owner, SaveableList(ids, rows, stickyId = 0))
    rows.update(0, 6)
    val recyclerView = recyclerView(view)

    lithoTestRule.act(view) { recyclerView.scrollBy(0, ROW_HEIGHT_PX / 2) }

    val sectionsRecyclerView = recyclerView.parent as SectionsRecyclerView
    assertThat(sectionsRecyclerView.stickyHeaderView?.visibility).isEqualTo(View.VISIBLE)
    val restored = RowRecorder()
    recreate(owner, SaveableList(ids, restored, stickyId = 0))
    assertThat(restored.value(0)).isEqualTo(6)
  }

  @Test
  fun `rows without an id or component key are not saved and do not throw`() {
    val parent = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val items =
        List(2) { index ->
          LithoCollectionItem(
              contextWith(parent),
              renderInfo = rowRenderInfo(index, rows, id = null),
          )
        }
    val scope = layoutScope(*items.toTypedArray())

    items.forEach { item ->
      with(item) { scope.onBindView(LithoRenderTreeView(lithoTestRule.context.androidContext)) }
    }
    rows.update(0, 3)

    assertThat(rows.value(0)).isEqualTo(3)
    assertThat(parent.snapshot().isEmpty).isTrue
  }

  @Test
  fun `rows without an explicit id are saved under their generated ids`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    render(owner, UnkeyedList(rows))
    rows.update(0, 3)
    rows.update(1, 4)

    // Generated ids contain process-local type ids, so this only holds within one process.
    val restored = RowRecorder()
    recreate(owner, UnkeyedList(restored))
    assertThat(restored.value(0)).isEqualTo(3)
    assertThat(restored.value(1)).isEqualTo(4)
  }

  @Test
  fun `a row whose id has no stable encoding does not take the value of the row it replaced`() {
    val rows = RowRecorder()
    val view = render(TestSavedStateOwner(), DataIdList(RowId("a"), index = 0, rows))
    rows.update(0, 5)
    val recyclerView = recyclerView(view)
    lithoTestRule.act(view) { recyclerView.scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }

    // Rows "a" and "b" have the same root, so only their ids tell them apart.
    view.setRoot(DataIdList(RowId("b"), index = 1, rows))
    lithoTestRule.idle()
    lithoTestRule.act(view) { recyclerView.scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }

    assertThat(rows.value(1)).isEqualTo(0)
  }

  @Test
  fun `a row whose id has no stable encoding is not saved`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    render(owner, DataIdList(RowId("a"), index = 0, rows))
    rows.update(0, 5)

    val restored = RowRecorder()
    recreate(owner, DataIdList(RowId("a"), index = 0, restored))
    assertThat(restored.value(0)).isEqualTo(0)
  }

  @Test
  fun `a row does not restore the value of an earlier row with the same id`() {
    val rows = RowRecorder()
    val rowCount = 2 * ROW_COUNT
    val ids = listOf(SHARED_ID) + (0 until rowCount - 2) + listOf(SHARED_ID)
    val view = render(TestSavedStateOwner(), RowsWithIds(ids, rows))
    rows.update(0, 5)
    val recyclerView = recyclerView(view)

    // The first row is recycled, which retains its value, before the last row renders.
    lithoTestRule.act(view) { recyclerView.scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
    assertThat(rows.states).doesNotContainKey(rowCount - 1)
    lithoTestRule.act(view) { recyclerView.scrollBy(0, rowCount * ROW_HEIGHT_PX) }

    assertThat(rows.value(rowCount - 1)).isEqualTo(0)
  }

  @Test
  fun `rows with the same id do not restore the value saved for that id`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    render(owner, RowsWithIds(listOf(SHARED_ID, 1), rows))
    rows.update(0, 5)
    rows.update(1, 6)

    val restoredOwner = TestSavedStateOwner(owner.save())
    owner.destroy()
    val restored = RowRecorder()
    render(restoredOwner, RowsWithIds(listOf(SHARED_ID, SHARED_ID, 1), restored))

    assertThat(restored.value(0)).isEqualTo(0)
    assertThat(restored.value(1)).isEqualTo(0)
    assertThat(restored.value(2)).isEqualTo(6)
    // The value saved for the id is dropped, so a list with one row for it doesn't get it either.
    val restoredAgain = RowRecorder()
    recreate(restoredOwner, RowsWithIds(listOf(SHARED_ID, 1), restoredAgain))
    assertThat(restoredAgain.value(0)).isEqualTo(0)
    assertThat(restoredAgain.value(1)).isEqualTo(6)
  }

  @Test
  fun `rows stay saved when a list replaces another with the same ids in one layout`() {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, KeyedList(generation = 0, rows))
    rows.update(0, 3)

    // The new list's rows render in the background, before the old list's effects are cleaned up.
    view.setRootAsync(KeyedList(generation = 1, rows))
    lithoTestRule.idle()
    rows.update(0, 4)

    val restored = RowRecorder()
    recreate(owner, KeyedList(generation = 1, restored))
    assertThat(restored.value(0)).isEqualTo(4)
  }

  @Test
  fun `two lists with the same row ids save their rows apart`() {
    val owner = TestSavedStateOwner()
    val first = RowRecorder()
    val second = RowRecorder()
    render(owner, TwoLists(first, second))
    first.update(0, 3)
    second.update(0, 4)

    val restoredFirst = RowRecorder()
    val restoredSecond = RowRecorder()
    recreate(owner, TwoLists(restoredFirst, restoredSecond))
    assertThat(restoredFirst.value(0)).isEqualTo(3)
    assertThat(restoredSecond.value(0)).isEqualTo(4)
  }

  @Test
  fun `removing a list drops the saved values of rows that did not render since the restore`() {
    val ids = (0 until ROW_COUNT).toList()
    val restored = RowRecorder()
    val (_, view) =
        restoreWithFarRowUpdated(restored) { rows -> OptionalList(show = true, rows, ids) }
    assertThat(restored.states).doesNotContainKey(FAR_ROW)

    view.setRoot(OptionalList(show = false, restored, ids))
    lithoTestRule.idle()
    view.setRoot(OptionalList(show = true, restored, ids))
    lithoTestRule.idle()
    val recyclerView = recyclerView(view)
    lithoTestRule.act(view) { recyclerView.scrollBy(0, FAR_ROW * ROW_HEIGHT_PX) }

    assertThat(restored.value(FAR_ROW)).isEqualTo(0)
  }

  @Test
  fun `a row removed and added again keeps its restored value if it did not render since then`() {
    val ids = (0 until ROW_COUNT).toList()
    val restored = RowRecorder()
    val (_, view) = restoreWithFarRowUpdated(restored) { rows -> SaveableList(ids, rows) }

    view.setRoot(SaveableList(ids - FAR_ROW, restored))
    lithoTestRule.idle()
    view.setRoot(SaveableList(ids, restored))
    lithoTestRule.idle()
    val recyclerView = recyclerView(view)
    lithoTestRule.act(view) { recyclerView.scrollBy(0, FAR_ROW * ROW_HEIGHT_PX) }

    assertThat(restored.value(FAR_ROW)).isEqualTo(7)
  }

  @Test
  fun `a removed row that did not render since the restore stays saved`() {
    val ids = (0 until ROW_COUNT).toList()
    val (owner, view) = restoreWithFarRowUpdated(RowRecorder()) { rows -> SaveableList(ids, rows) }

    view.setRoot(SaveableList(ids - FAR_ROW, RowRecorder()))
    lithoTestRule.idle()

    val restoredAgain = RowRecorder()
    val restoredView = recreate(owner, SaveableList(ids, restoredAgain))
    val recyclerView = recyclerView(restoredView)
    lithoTestRule.act(restoredView) { recyclerView.scrollBy(0, FAR_ROW * ROW_HEIGHT_PX) }
    assertThat(restoredAgain.value(FAR_ROW)).isEqualTo(7)
  }

  @Test
  fun `committing new data without new rows reads no row keys`() {
    val parent = LithoSaveableStateRegistry()
    val rows = RowRecorder()
    val committedData = LatestCommittedData(
        LinearLayoutInfo(LinearLayoutManager(lithoTestRule.context.androidContext)),
        CollectionLayoutConfig(),
    )
    val keyReads = AtomicInteger()
    val items =
        List(ROW_COUNT) { index ->
          LithoCollectionItem(
              contextWith(parent),
              renderInfo =
                  KeyReadCountingRenderInfo(rowRenderInfo(index, rows, id = index), keyReads),
              saveableStateRegistries = committedData.saveableStateRegistries,
          )
        }
    val data = ArrayList<CollectionChild>()
    committedData.commit(Changeset(CollectionUpdateOperation(null, data), items))
    items.first().measure(ROW_CONSTRAINTS, null)
    keyReads.set(0)

    // As when the collection's parent renders again with an equal list.
    committedData.commit(Changeset(CollectionUpdateOperation(data, ArrayList()), null))

    assertThat(keyReads.get()).isZero
  }

  @Test
  fun `a list whose rows never read saveable state leaves the restored values to another tree`() {
    val ids = (0 until ROW_COUNT).toList()
    val session = TestSavedStateOwner()
    val rows = RowRecorder()
    render(session, Screen(ids, rows))
    rows.update(0, 7)
    val owner = TestSavedStateOwner(session.save())
    session.destroy()

    // Two of its rows share an id, its rows are recycled and bound again, and it is removed.
    val unread = render(owner, Screen(listOf(SHARED_ID, SHARED_ID) + ids, rows = null))
    val recyclerView = recyclerView(unread)
    lithoTestRule.act(unread) { recyclerView.scrollBy(0, ROW_COUNT * ROW_HEIGHT_PX) }
    lithoTestRule.act(unread) { recyclerView.scrollBy(0, -ROW_COUNT * ROW_HEIGHT_PX) }
    unread.setRoot(Screen(ids = null, rows = null))
    lithoTestRule.idle()

    val restored = RowRecorder()
    render(owner, Screen(ids, restored))
    assertThat(restored.value(0)).isEqualTo(7)
  }

  /**
   * ```text
   * Before process death: row 0 = 10, row 1 = 11
   * Build and render the tree off the main thread   Rows render 0: Android's values not taken yet
   * Show it on the main thread                      Rows render 10 and 11 before they mount
   * Save and restore again                          Rows restore 10 and 11
   * ```
   */
  @Test
  fun `rows rendered off the main thread before a late restore show their restored values`() {
    val ids = listOf(0, 1, 2)
    val owner = TestSavedStateOwner(savedRows(ids))
    val rows = RowRecorder()

    val tree = renderOffMainThread(owner, SaveableList(ids, rows))
    assertThat(rows.value(0)).isEqualTo(0)
    show(tree)

    assertThat(ids.map(rows::value)).containsExactly(10, 11, 0)
    val restored = RowRecorder()
    recreate(owner, SaveableList(ids, restored))
    assertThat(ids.map(restored::value)).containsExactly(10, 11, 0)
  }

  /**
   * ```text
   * Before process death: row 0 = 10, row 1 = 11; the owner is not restored yet
   * Render the tree on the main thread    The list commits its rows, which render 0
   * Android restores, then show the tree  The committed rows render 10 and 11 before they mount
   * Save and restore again                Rows restore 10 and 11
   * ```
   */
  @Test
  fun `rows committed before a late restore show their restored values`() {
    val ids = listOf(0, 1, 2)
    val owner = TestSavedStateOwner(savedRows(ids), restoreNow = false)
    val rows = RowRecorder()
    val root = SaveableList(ids, rows)
    val tree = tree(owner, root)
    tree.setRootAndSizeSpecSync(root, exactly(WIDTH_PX), exactly(LIST_HEIGHT_PX))
    assertThat(rows.value(0)).isEqualTo(0)

    owner.restore()
    show(tree)

    assertThat(ids.map(rows::value)).containsExactly(10, 11, 0)
    val restored = RowRecorder()
    recreate(owner, SaveableList(ids, restored))
    assertThat(ids.map(restored::value)).containsExactly(10, 11, 0)
  }

  @Test
  fun `a list saving its scroll position restores its first visible row and offset after recreation`() {
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS))
    scrollBy(view, 10 * ROW_HEIGHT_PX + 30)
    assertThat(shownRowAndOffset(view)).isEqualTo(10 to -30)

    val restored = recreate(owner, ScrollList(SCROLL_IDS))

    assertThat(shownRowAndOffset(restored)).isEqualTo(10 to -30)
  }

  @Test
  fun `a restored scroll position waits for rows that load after recreation`() {
    listOf(false, true).forEach { async ->
      val owner = TestSavedStateOwner()
      val view = render(owner, ScrollList(SCROLL_IDS))
      scrollBy(view, 10 * ROW_HEIGHT_PX + 30)

      val restored = recreate(owner, ScrollList(ids = null))
      assertThat(scrollListRecyclerView(restored).adapter?.itemCount).isEqualTo(0)
      if (async) {
        restored.componentTree.setRootAndSizeSpecAsync(
            ScrollList(SCROLL_IDS),
            exactly(WIDTH_PX),
            exactly(LIST_HEIGHT_PX),
        )
        lithoTestRule.finishAsyncUpdates()
      } else {
        restored.setRoot(ScrollList(SCROLL_IDS))
      }
      lithoTestRule.idle()

      assertThat(shownRowAndOffset(restored)).describedAs("async=$async").isEqualTo(10 to -30)
    }
  }

  @Test
  fun `a restored scroll position past the last row scrolls to the last row`() {
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS))
    scrollBy(view, ROW_COUNT * ROW_HEIGHT_PX)
    assertThat(shownRowAndOffset(view).first).isGreaterThan(30)

    val restored = recreate(owner, ScrollList(NEW_IDS.take(30)))

    shownRowAndOffset(restored)
    val recyclerView = scrollListRecyclerView(restored)
    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
    assertThat(layoutManager.findLastCompletelyVisibleItemPosition()).isEqualTo(29)
    assertThat(recyclerView.canScrollVertically(1)).isFalse
  }

  @Test
  fun `a list not saving its scroll position starts at the top after recreation`() {
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS, save = false))
    scrollBy(view, 10 * ROW_HEIGHT_PX + 30)
    assertThat(shownRowAndOffset(view)).isEqualTo(10 to -30)

    // Restored into a list that saves, so a position saved by the first list would be restored.
    val restored = recreate(owner, ScrollList(SCROLL_IDS))

    assertThat(shownRowAndOffset(restored)).isEqualTo(0 to 0)
    scrollBy(restored, 5 * ROW_HEIGHT_PX)
    assertThat(shownRowAndOffset(restored)).isEqualTo(5 to 0)
  }

  @Test
  fun `a restored scroll position is applied only once`() {
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS))
    scrollBy(view, 10 * ROW_HEIGHT_PX + 30)
    val restoredOwner = TestSavedStateOwner(owner.save())
    owner.destroy()
    val restored = render(restoredOwner, ScrollList(SCROLL_IDS))
    assertThat(shownRowAndOffset(restored)).isEqualTo(10 to -30)

    scrollBy(restored, 20 * ROW_HEIGHT_PX)
    restored.setRoot(ScrollList(SCROLL_IDS + ROW_COUNT))
    lithoTestRule.idle()
    assertThat(shownRowAndOffset(restored)).isEqualTo(30 to -30)
    unmountListAndMountAgain(restored)
    assertThat(shownRowAndOffset(restored)).isEqualTo(30 to -30)

    val restoredAgain = recreate(restoredOwner, ScrollList(SCROLL_IDS + ROW_COUNT))
    assertThat(shownRowAndOffset(restoredAgain)).isEqualTo(30 to -30)
  }

  @Test
  fun `a scroll that keeps the same rows visible restores its last pixel`() {
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS))
    scrollBy(view, 10 * ROW_HEIGHT_PX + 30)
    scrollBy(view, 10)
    assertThat(currentRowAndOffset(view)).isEqualTo(10 to -40)

    val restored = recreate(owner, ScrollList(SCROLL_IDS))

    assertThat(shownRowAndOffset(restored)).isEqualTo(10 to -40)
  }

  @Test
  fun `a list scrolled in small steps restores the offset it stopped at`() {
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS))
    val recyclerView = scrollListRecyclerView(view)
    lithoTestRule.act(view) { repeat(103) { recyclerView.scrollBy(0, 10) } }
    assertThat(currentRowAndOffset(view)).isEqualTo(10 to -30)

    val restored = recreate(owner, ScrollList(SCROLL_IDS))

    assertThat(shownRowAndOffset(restored)).isEqualTo(10 to -30)
  }

  @Test
  fun `a jump by the list's controller that keeps the same rows visible restores its last pixel`() {
    var callerLayouts = 0
    // Without and with an onAfterLayout of the list's own, which is still called.
    listOf(null, OnAfterLayoutListener { callerLayouts++ }).forEach { onAfterLayout ->
      val owner = TestSavedStateOwner()
      val controller = LazyCollectionController()
      val view = render(
          owner,
          ScrollList(SCROLL_IDS, controller = controller, onAfterLayout = onAfterLayout),
      )
      scrollBy(view, 10 * ROW_HEIGHT_PX + 30)
      lithoTestRule.act(view) { controller.scrollToIndex(10, -40) }
      assertThat(shownRowAndOffset(view)).isEqualTo(10 to -40)

      val restored = recreate(owner, ScrollList(SCROLL_IDS))

      assertThat(shownRowAndOffset(restored))
          .describedAs("with onAfterLayout: ${onAfterLayout != null}")
          .isEqualTo(10 to -40)
    }
    assertThat(callerLayouts).isGreaterThan(0)
  }

  /**
   * Row 15 has focus, so when row 12 shrinks by 20px the list keeps row 15 in place and moves the
   * rows above it down: row 10 goes from -30 to -10 without a scroll. In a real window the list
   * applies the change without calling its `onLayout`, so its after-layout listener doesn't run.
   */
  @LooperMode(LooperMode.Mode.PAUSED)
  @Test
  fun `a data change that moves the first visible row without scrolling restores its new offset`() {
    val owner = TestSavedStateOwner()
    val view = renderInWindow(owner, ScrollList(SCROLL_IDS))
    scrollBy(view, 10 * ROW_HEIGHT_PX + 30)
    val row15 = checkNotNull(scrollListRecyclerView(view).layoutManager?.findViewByPosition(15))
    lithoTestRule.act(view) {
      row15.isFocusableInTouchMode = true
      row15.requestFocus()
    }
    val rowLengths = mapOf(12 to ROW_HEIGHT_PX - 20)

    view.setRoot(ScrollList(SCROLL_IDS, rowLengths = rowLengths))
    idleThroughNextFrame()

    assertThat(currentRowAndOffset(view)).isEqualTo(10 to -10)
    val restored = recreate(owner, ScrollList(SCROLL_IDS, rowLengths = rowLengths))
    assertThat(shownRowAndOffset(restored)).isEqualTo(10 to -10)
  }

  @Test
  fun `a scrolled reverse list restores its first visible row and offset`() {
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS, reverse = true))
    // Row 0 is at the bottom, so the later rows are up; row 10 is cut by 30px at the bottom.
    scrollBy(view, -(10 * ROW_HEIGHT_PX + 30))
    val rowTop = LIST_HEIGHT_PX - HEADER_HEIGHT_PX + 30 - ROW_HEIGHT_PX
    assertThat(shownRowAndOffset(view)).isEqualTo(10 to rowTop)

    val restored = recreate(owner, ScrollList(SCROLL_IDS, reverse = true))

    assertThat(shownRowAndOffset(restored)).isEqualTo(10 to rowTop)
  }

  @Test
  fun `a scrolled horizontal RTL list restores its first visible row and offset`() {
    val applicationInfo = lithoTestRule.context.androidContext.applicationInfo
    applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
    RuntimeEnvironment.setQualifiers("+ldrtl")
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS, orientation = RecyclerView.HORIZONTAL))
    // Row 0 is at the right, so the later rows are to the left; row 10 is cut by 30px at the right.
    lithoTestRule.act(view) {
      scrollListRecyclerView(view).scrollBy(-(10 * ROW_HEIGHT_PX + 30), 0)
    }
    assertThat(shownRowAndOffset(view)).isEqualTo(10 to WIDTH_PX + 30 - ROW_HEIGHT_PX)

    val restored = recreate(owner, ScrollList(SCROLL_IDS, orientation = RecyclerView.HORIZONTAL))

    assertThat(shownRowAndOffset(restored)).isEqualTo(10 to WIDTH_PX + 30 - ROW_HEIGHT_PX)
  }

  @Test
  fun `a scrolled list with padding restores its first visible row and offset in every direction`() {
    val applicationInfo = lithoTestRule.context.androidContext.applicationInfo
    applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
    RuntimeEnvironment.setQualifiers("+ldrtl")
    listOf(RecyclerView.VERTICAL, RecyclerView.HORIZONTAL).forEach { orientation ->
      listOf(false, true).forEach { reverse ->
        val list = {
          ScrollList(SCROLL_IDS, orientation = orientation, reverse = reverse, padded = true)
        }
        val horizontal = orientation == RecyclerView.HORIZONTAL
        // A horizontal list lays out from the right in a right-to-left layout, unless reversed.
        val distance = (10 * ROW_HEIGHT_PX + 30) * if (reverse != horizontal) -1 else 1
        val owner = TestSavedStateOwner()
        val view = render(owner, list())
        lithoTestRule.act(view) {
          val recyclerView = scrollListRecyclerView(view)
          if (horizontal) recyclerView.scrollBy(distance, 0) else recyclerView.scrollBy(0, distance)
        }
        val scrolled = shownRowAndOffset(view)
        assertThat(scrolled.first).isEqualTo(10)

        val restored = recreate(owner, list())

        assertThat(shownRowAndOffset(restored))
            .describedAs("orientation=$orientation, reverse=$reverse")
            .isEqualTo(scrolled)
      }
    }
  }

  @Test
  fun `a circular list that saves its scroll position starts where a list that does not save starts`() {
    val notSaving =
        render(TestSavedStateOwner(), ScrollList(SCROLL_IDS, save = false, circular = true))
    val owner = TestSavedStateOwner()
    val view = render(owner, ScrollList(SCROLL_IDS, circular = true))
    scrollBy(view, 2 * ROW_HEIGHT_PX + 30)

    val restored = recreate(owner, ScrollList(SCROLL_IDS, circular = true))

    assertThat(shownRowAndOffset(restored)).isEqualTo(shownRowAndOffset(notSaving))
  }

  /**
   * ```text
   * Before process death: the list shows row 10 at -30; the owner is not restored yet
   * Render the tree on the main thread    The list renders at the top
   * Android restores, then show the tree  The list shows row 10 at -30
   * Scroll by 15 rows, save and restore   The list shows row 25 at -30
   * ```
   */
  @Test
  fun `a list rendered before a late restore shows its restored scroll position and saves later scrolls`() {
    val session = TestSavedStateOwner()
    val sessionView = render(session, ScrollList(SCROLL_IDS))
    scrollBy(sessionView, 10 * ROW_HEIGHT_PX + 30)
    val owner = TestSavedStateOwner(session.save(), restoreNow = false)
    session.destroy()
    val root = ScrollList(SCROLL_IDS)
    val tree = tree(owner, root)
    tree.setRootAndSizeSpecSync(root, exactly(WIDTH_PX), exactly(LIST_HEIGHT_PX))

    owner.restore()
    val view = show(tree)

    assertThat(shownRowAndOffset(view)).isEqualTo(10 to -30)
    scrollBy(view, 15 * ROW_HEIGHT_PX)
    assertThat(shownRowAndOffset(view)).isEqualTo(25 to -30)
    val restored = recreate(owner, ScrollList(SCROLL_IDS))
    assertThat(shownRowAndOffset(restored)).isEqualTo(25 to -30)
  }

  /** Saves a [SaveableList] of [ids] with rows 0 and 1 set to 10 and 11. */
  private fun savedRows(ids: List<Int>): Bundle {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    render(owner, SaveableList(ids, rows))
    rows.update(0, 10)
    rows.update(1, 11)
    return owner.save().also { owner.destroy() }
  }

  /** Builds and lays out a tree on a background thread, with no main-thread call into it. */
  private fun renderOffMainThread(owner: SavedStateRegistryOwner, root: Component): ComponentTree {
    val built = AtomicReference<ComponentTree>()
    val failure = AtomicReference<Throwable>()
    thread {
      try {
        built.set(
            tree(owner, root).apply {
              setRootAndSizeSpecSync(root, exactly(WIDTH_PX), exactly(LIST_HEIGHT_PX))
            },
        )
      } catch (throwable: Throwable) {
        failure.set(throwable)
      }
    }
        .join()
    failure.get()?.let { throw it }
    return built.get()
  }

  /**
   * Renders [root], sets [FAR_ROW] to 7 and saves while it is scrolled into view, and renders
   * [root] again from that save, recording its rows in [restored]. Returns the restored owner and
   * view.
   */
  private fun restoreWithFarRowUpdated(
      restored: RowRecorder,
      root: (RowRecorder) -> Component,
  ): Pair<TestSavedStateOwner, TestLithoView> {
    val owner = TestSavedStateOwner()
    val rows = RowRecorder()
    val view = render(owner, root(rows))
    val recyclerView = recyclerView(view)
    lithoTestRule.act(view) { recyclerView.scrollBy(0, FAR_ROW * ROW_HEIGHT_PX) }
    rows.update(FAR_ROW, 7)
    val savedState = owner.save()
    owner.destroy()
    val restoredOwner = TestSavedStateOwner(savedState)
    return restoredOwner to render(restoredOwner, root(restored))
  }

  private fun render(owner: SavedStateRegistryOwner, root: Component): TestLithoView =
      show(tree(owner, root))

  private fun tree(owner: SavedStateRegistryOwner, root: Component): ComponentTree =
      ComponentTree.create(lithoTestRule.context.androidContext, owner, root)
          .componentsConfiguration(NEW_COLLECTION)
          .build()

  private fun render(
      registry: LithoSaveableStateRegistry,
      root: Component,
      treeState: TreeState? = null,
  ): TestLithoView = show(tree(registry, root, treeState))

  private fun tree(
      registry: LithoSaveableStateRegistry,
      root: Component,
      treeState: TreeState? = null,
  ): ComponentTree =
      ComponentTree.create(lithoTestRule.context, root)
          .withRetainedSaveableStateRegistryForTreeReplacement(registry)
          .componentsConfiguration(NEW_COLLECTION)
          .treeState(treeState)
          .build()

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

  /**
   * Renders [root] into a real Activity window, where the list applies a data change in a layout of
   * its own, without calling its `onLayout`, as it does on a device.
   */
  private fun renderInWindow(owner: SavedStateRegistryOwner, root: Component): TestLithoView {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    lithoTestRule.useContext(ComponentContext(activity))
    val view =
        lithoTestRule.createTestLithoView(
            componentTree = tree(owner, root),
            widthPx = WIDTH_PX,
            heightPx = LIST_HEIGHT_PX,
        )
    activity
        .findViewById<ViewGroup>(android.R.id.content)
        .addView(view.lithoView, FrameLayout.LayoutParams(WIDTH_PX, LIST_HEIGHT_PX))
    view.measure().layout()
    idleThroughNextFrame()
    views += view
    return view
  }

  /** Runs the paused main looper past the next frame, in which a real window lays out. */
  private fun idleThroughNextFrame() {
    shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
    lithoTestRule.idle()
  }

  /** Saves [owner], destroys it, and renders [root] under an owner restored from that save. */
  private fun recreate(owner: TestSavedStateOwner, root: Component): TestLithoView {
    val savedState = owner.save()
    owner.destroy()
    return render(TestSavedStateOwner(savedState), root)
  }

  private fun recyclerView(view: TestLithoView): RecyclerView =
      ((view.findViewWithTag(LIST_TAG) as LithoView).getChildAt(0) as SectionsRecyclerView)
          .recyclerView

  private fun scrollListRecyclerView(view: TestLithoView): RecyclerView =
      (view.findViewWithTag(SCROLL_LIST_TAG) as SectionsRecyclerView).recyclerView

  private fun scrollBy(view: TestLithoView, dy: Int) {
    val recyclerView = scrollListRecyclerView(view)
    lithoTestRule.act(view) { recyclerView.scrollBy(0, dy) }
  }

  /**
   * Lays the [ScrollList] out again, which applies a scroll it was asked for since it last laid
   * out, and returns its [currentRowAndOffset].
   */
  private fun shownRowAndOffset(view: TestLithoView): Pair<Int, Int?> {
    val recyclerView = scrollListRecyclerView(view)
    recyclerView.measure(exactly(recyclerView.width), exactly(recyclerView.height))
    recyclerView.layout(
        recyclerView.left,
        recyclerView.top,
        recyclerView.right,
        recyclerView.bottom,
    )
    lithoTestRule.idle()
    return currentRowAndOffset(view)
  }

  /** The [ScrollList]'s first visible row and that row's top, or left in a horizontal list. */
  private fun currentRowAndOffset(view: TestLithoView): Pair<Int, Int?> {
    val layoutManager = scrollListRecyclerView(view).layoutManager as LinearLayoutManager
    val row = layoutManager.findFirstVisibleItemPosition()
    val rowView = layoutManager.findViewByPosition(row)
    return row to
        if (layoutManager.orientation == RecyclerView.HORIZONTAL) rowView?.left else rowView?.top
  }

  /** Unmounts the [ScrollList] with incremental mount, then mounts it again. */
  private fun unmountListAndMountAgain(view: TestLithoView) {
    val recyclerView = scrollListRecyclerView(view)
    lithoTestRule.act(view) {
      view.lithoView.mountComponent(Rect(0, 0, WIDTH_PX, HEADER_HEIGHT_PX / 2), true)
    }
    assertThat(recyclerView.adapter).isNull()
    lithoTestRule.act(view) {
      view.lithoView.mountComponent(Rect(0, 0, WIDTH_PX, LIST_HEIGHT_PX), true)
    }
  }

  private fun contextWith(
      registry: LithoSaveableStateRegistry,
  ): ComponentContext = ComponentContext(
      lithoTestRule.context.androidContext,
      treePropContainer =
          TreePropContainer().apply { put(LithoSaveableStateRegistryTreeProp, registry) },
  )

  private fun rowItem(
      parent: LithoSaveableStateRegistry,
      registries: CollectionSaveableStateRegistries,
      rows: RowRecorder,
      index: Int,
  ) = LithoCollectionItem(
      contextWith(parent),
      renderInfo = rowRenderInfo(index, rows, id = index),
      saveableStateRegistries = registries,
  )

  private fun bind(item: LithoCollectionItem): LithoRenderTreeView {
    val view = LithoRenderTreeView(lithoTestRule.context.androidContext)
    with(item) { layoutScope(item).onBindView(view) }
    return view
  }

  /** The value row 0 of a list in [parent] renders with when it is measured but not bound. */
  private fun measuredValue(parent: LithoSaveableStateRegistry): Int {
    val rows = RowRecorder()
    rowItem(parent, CollectionSaveableStateRegistries(COLLECTION_KEY), rows, 0)
        .measure(ROW_CONSTRAINTS, null)
    return rows.value(0)
  }

  private fun rowRenderInfo(index: Int, rows: RowRecorder, id: Int?) =
      ComponentRenderInfo.create()
          .apply { id?.let { customAttribute(RecyclerBinder.ID_CUSTOM_ATTR_KEY, it) } }
          .component(SaveableRow(index, rows))
          .build()

  private fun layoutScope(vararg items: LithoCollectionItem) = CollectionLayoutScope(
      layoutInfo = LinearLayoutInfo(LinearLayoutManager(lithoTestRule.context.androidContext)),
      collectionConstraints = SizeConstraints.exact(WIDTH_PX, LIST_HEIGHT_PX),
      collectionSize = IntSize(WIDTH_PX, LIST_HEIGHT_PX),
      collectionItems = items.toList(),
      isVertical = true,
      wrapInMainAxis = false,
      crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
      firstVisiblePosition = 0,
      lastVisiblePosition = items.size - 1,
  )

  /**
   * The latest state and registry each row rendered with, and every distinct state and registry it
   * rendered with in any item. A row without a registry is skipped.
   */
  private class RowRecorder {
    val states = ConcurrentHashMap<Int, State<Int>>()
    private val registries = ConcurrentHashMap<Int, LithoSaveableStateRegistry>()
    private val allStates = ConcurrentHashMap<Int, MutableSet<State<Int>>>()
    private val allRegistries = ConcurrentHashMap<Int, MutableSet<LithoSaveableStateRegistry>>()

    fun record(index: Int, state: State<Int>, registry: LithoSaveableStateRegistry?) {
      registry ?: return
      states[index] = state
      registries[index] = registry
      allStates.getOrPut(index) { identitySet() }.add(state)
      allRegistries.getOrPut(index) { identitySet() }.add(registry)
    }

    fun value(index: Int): Int = checkNotNull(states[index]).value

    @SuppressLint("KComponentSyncUpdateDetector")
    fun update(index: Int, value: Int) = checkNotNull(states[index]).updateSync(value)

    fun recordRegistry(index: Int, registry: LithoSaveableStateRegistry?) {
      registry?.let { registries[index] = it }
    }

    fun registry(index: Int): LithoSaveableStateRegistry = checkNotNull(registries[index])

    /** How many items rendered the row: each has its own tree, so its own state. */
    fun itemCount(index: Int): Int = allStates[index]?.size ?: 0

    fun distinctRegistries(index: Int): Int = allRegistries[index]?.size ?: 0

    private fun <T> identitySet(): MutableSet<T> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
  }

  private class SaveableList(
      private val ids: List<Int>,
      private val rows: RowRecorder,
      private val stickyId: Int? = null,
      // Tells otherwise equal roots apart, so setting one resolves again.
      private val revision: Int = 0,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px).viewTag(LIST_TAG)) {
          ids.forEach { id -> child(SaveableRow(id, rows), id = id, isSticky = id == stickyId) }
        }
  }

  /**
   * A list of [ids] that saves its scroll position if [save], below a header; no rows if null. Its
   * rows are [ROW_HEIGHT_PX] long along its [orientation], except those [rowLengths] gives a
   * length. If [padded], it has a different padding on each side, which its rows scroll into.
   */
  private class ScrollList(
      private val ids: List<Int>?,
      private val save: Boolean = true,
      private val orientation: Int = RecyclerView.VERTICAL,
      private val reverse: Boolean = false,
      private val circular: Boolean = false,
      private val padded: Boolean = false,
      private val controller: LazyCollectionController? = null,
      private val onAfterLayout: OnAfterLayoutListener? = null,
      private val rowLengths: Map<Int, Int> = emptyMap(),
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(Text(text = "header", style = Style.height(HEADER_HEIGHT_PX.px)))
      child(
          LazyList(
              orientation = orientation,
              reverse = reverse,
              clipToPadding = if (padded) false else null,
              startPadding = if (padded) 10.px else null,
              endPadding = if (padded) 20.px else null,
              topPadding = if (padded) 30.px else null,
              bottomPadding = if (padded) 40.px else null,
              lazyCollectionController = controller,
              isCircular = circular,
              onAfterLayout = onAfterLayout,
              saveScrollPosition = save,
              style = Style.height((LIST_HEIGHT_PX - HEADER_HEIGHT_PX).px).viewTag(SCROLL_LIST_TAG),
          ) {
            ids?.forEach { id ->
              val length = (rowLengths[id] ?: ROW_HEIGHT_PX).px
              val rowStyle =
                  if (orientation == RecyclerView.HORIZONTAL) Style.width(length)
                  else Style.height(length)
              child(id = id, component = Text(text = "row_$id", style = rowStyle))
            }
          },
      )
    }
  }

  /** A list whose rows record their registry but use no saveable state. */
  private class StatelessList(private val ids: List<Int>, private val rows: RowRecorder) :
      KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px).viewTag(LIST_TAG)) {
          ids.forEach { id -> child(StatelessRow(id, rows), id = id) }
        }
  }

  /** [SaveableList] of rows 0 to 2, below a header. */
  private class ListBelowHeader(private val rows: RowRecorder) : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(Text(text = "header", style = Style.height(HEADER_HEIGHT_PX.px)))
      child(
          LazyList(style = Style.height((LIST_HEIGHT_PX / 2).px).viewTag(LIST_TAG)) {
            (0..2).forEach { id -> child(SaveableRow(id, rows), id = id) }
          },
      )
    }
  }

  /** A list of rows with the given ids, each row saving under its own [stateKeys] entry if any. */
  private class RowsWithIds(
      private val ids: List<Int>,
      private val rows: RowRecorder,
      private val stateKeys: List<String?> = ids.map { null },
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px).viewTag(LIST_TAG)) {
          ids.forEachIndexed { index, id ->
            child(SaveableRow(index, rows, stateKeys[index]), id = id)
          }
        }
  }

  /** A list whose first row has [firstId] and a keyed root; the other rows save nothing. */
  private class DataIdList(
      private val firstId: RowId,
      private val index: Int,
      private val rows: RowRecorder,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px).viewTag(LIST_TAG)) {
          child(id = firstId, component = key("first") { SaveableRow(index, rows) })
          (1 until ROW_COUNT).forEach { id ->
            child(
                id = id,
                component = Text(text = "row_$id", style = Style.height(ROW_HEIGHT_PX.px)),
            )
          }
        }
  }

  private data class RowId(val name: String)

  /** [SaveableList] under a manual key that changes with [generation]. */
  private class KeyedList(private val generation: Int, private val rows: RowRecorder) :
      KComponent() {
    override fun ComponentScope.render(): Component =
        key("list-$generation") { SaveableList(KEYED_LIST_IDS, rows) }
  }

  /** Two lists in one tree, both with rows 0 and 1. */
  @SuppressLint("KComponentUncachedAllocationDetector")
  private class TwoLists(private val first: RowRecorder, private val second: RowRecorder) :
      KComponent() {
    override fun ComponentScope.render(): Component = Column {
      listOf(first, second).forEach { rows ->
        child(
            LazyList(style = Style.height((LIST_HEIGHT_PX / 2).px)) {
              (0..1).forEach { id -> child(SaveableRow(id, rows), id = id) }
            },
        )
      }
    }
  }

  /** Counts the reads of the row id, which saveable-state keys are made from. */
  private class KeyReadCountingRenderInfo(
      private val renderInfo: RenderInfo,
      private val keyReads: AtomicInteger,
  ) : RenderInfo by renderInfo {
    override fun getCustomAttribute(key: String): Any? {
      if (key == RecyclerBinder.ID_CUSTOM_ATTR_KEY) keyReads.incrementAndGet()
      return renderInfo.getCustomAttribute(key)
    }
  }

  /** Rows without ids, so the collection generates them. */
  @SuppressLint("CollectionChildWithoutIdInLoopDetector")
  private class UnkeyedList(private val rows: RowRecorder) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px)) {
          (0..2).forEach { index -> child(SaveableRow(index, rows)) }
        }
  }

  /** A list whose first row is a horizontal list of saveable rows. */
  private class OuterList(private val rows: RowRecorder) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.height(LIST_HEIGHT_PX.px).viewTag(LIST_TAG)) {
          child(id = 0, component = InnerList(rows))
          (1 until ROW_COUNT).forEach { id ->
            child(
                id = id,
                component = Text(text = "row_$id", style = Style.height(ROW_HEIGHT_PX.px)),
            )
          }
        }
  }

  private class InnerList(private val rows: RowRecorder) : KComponent() {
    override fun ComponentScope.render(): Component = LazyList(
        orientation = RecyclerView.HORIZONTAL,
        style = Style.height(ROW_HEIGHT_PX.px),
    ) {
      (0..2).forEach { id -> child(SaveableRow(id, rows), id = id) }
    }
  }

  /** A root shared by trees under one owner. Its rows read saveable state only with [rows]. */
  private class Screen(private val ids: List<Int>?, private val rows: RowRecorder?) : KComponent() {
    override fun ComponentScope.render(): Component {
      ids ?: return EmptyComponent()
      return LazyList(style = Style.height(LIST_HEIGHT_PX.px).viewTag(LIST_TAG)) {
        ids.forEachIndexed { index, id ->
          child(
              id = id,
              component =
                  rows?.let { SaveableRow(index, it) }
                      ?: Text(text = "row_$index", style = Style.height(ROW_HEIGHT_PX.px)),
          )
        }
      }
    }
  }

  private class OptionalList(
      private val show: Boolean,
      private val rows: RowRecorder,
      private val ids: List<Int> = listOf(0, 1),
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        if (show) SaveableList(ids, rows) else EmptyComponent()
  }

  private class StatelessRow(private val index: Int, private val rows: RowRecorder) : KComponent() {
    override fun ComponentScope.render(): Component {
      rows.recordRegistry(index, LithoSaveableStateRegistryTreeProp.value)
      return Text(text = "row_$index", style = Style.height(ROW_HEIGHT_PX.px))
    }
  }

  private class PlainRow : KComponent() {
    override fun ComponentScope.render(): Component =
        Text(text = "plain", style = Style.height(ROW_HEIGHT_PX.px))
  }

  private class SaveableRow(
      private val index: Int,
      private val rows: RowRecorder,
      private val stateKey: String? = null,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      rows.record(
          index,
          useSaveableState(key = stateKey) { 0 },
          LithoSaveableStateRegistryTreeProp.value,
      )
      return Text(text = "row_$index", style = Style.height(ROW_HEIGHT_PX.px))
    }
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
    const val LIST_TAG: String = "list"
    const val ROW_KEY: String = "row"
    const val SCROLL_LIST_TAG: String = "scroll_list"
    const val COLLECTION_KEY: String = "list"
    const val SHARED_ID: Int = 100
    const val FAR_ROW: Int = 50
    const val WIDTH_PX: Int = 100
    const val ROW_HEIGHT_PX: Int = 100
    const val LIST_HEIGHT_PX: Int = 1000
    const val HEADER_HEIGHT_PX: Int = 100
    const val ROW_COUNT: Int = 60
    val KEYED_LIST_IDS: List<Int> = listOf(0, 1, 2)
    val SCROLL_IDS: List<Int> = List(ROW_COUNT) { it }
    /** Ids no row of [SCROLL_IDS] has. */
    val NEW_IDS: List<Int> = List(ROW_COUNT) { 1000 + it }
    val ROW_CONSTRAINTS: SizeConstraints = SizeConstraints.exact(WIDTH_PX, ROW_HEIGHT_PX)
    val NEW_COLLECTION: ComponentsConfiguration =
        ComponentsConfiguration.defaultInstance.copy(enableNewCollection = true)
  }
}
