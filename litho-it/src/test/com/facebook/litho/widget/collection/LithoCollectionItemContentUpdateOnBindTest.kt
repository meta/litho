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

import androidx.recyclerview.widget.LinearLayoutManager
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.RunnableHandlerProvider
import com.facebook.litho.core.height
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.LinearLayoutInfo
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * What happens when a collection item whose component changed (a diff UPDATE) is bound again.
 *
 * Without [ComponentsConfiguration.useCommittedLayoutOnCollectionBind], [LithoCollectionItem]
 * resolves and lays out the new component synchronously inside onBindView (the main thread), even
 * though a layout for the previous component is committed at the same constraints and an async
 * render of the new one is already queued. With the flag, bind shows the committed layout and the
 * new component is rendered in the background.
 *
 * Async render work goes through a [FakeRunnableHandler] that only runs when drained, so
 * "synchronous" means the component's render() ran inside the onBindView call itself.
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LithoCollectionItemContentUpdateOnBindTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private val handler = FakeRunnableHandler()
  private val handlerProvider =
      object : RunnableHandlerProvider {
        override fun getRunnableHandler(): RunnableHandler = handler
      }
  private val counter = RenderCounter()
  private val layoutInfo by lazy {
    LinearLayoutInfo(LinearLayoutManager(lithoTestRule.context.androidContext))
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = false
  }

  @Test
  fun `bind without a content change reuses the prepared layout`() {
    val item = createItem(CountingRow(LABEL_A, HEIGHT_A, counter))
    val scope = createScope(item)
    item.prepareAsync(childConstraints(scope, item))
    handler.drain()

    val view = newView()
    with(item) { scope.onBindView(view) }

    assertThat(counter.countFor(LABEL_A)).isEqualTo(1)
    assertThat(view.layoutState?.height).isEqualTo(HEIGHT_A)
  }

  @Test
  fun `flag off - bind after a content-only update renders the new component synchronously`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = false
    val (item, scope, view) = boundItemShowingA()
    updateToB(item, scope)

    val bRendersDuringBind = rendersOfBDuring { with(item) { scope.onBindView(view) } }

    assertThat(bRendersDuringBind)
        .describedAs(
            "the issue: B is resolved and laid out inside onBindView even though A's layout is " +
                "committed at the same constraints and B is already queued",
        )
        .isEqualTo(1)
  }

  @Test
  fun `bind after a content-only update keeps the committed layout and renders in the background`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val (item, scope, view) = boundItemShowingA()
    updateToB(item, scope)

    val bRendersDuringBind = rendersOfBDuring { with(item) { scope.onBindView(view) } }

    assertThat(bRendersDuringBind).describedAs("B must not render inside onBindView").isEqualTo(0)
    assertThat(view.layoutState?.height)
        .describedAs("A is shown until B commits")
        .isEqualTo(HEIGHT_A)
    drainAndIdle()
    assertShowsB(item, view)
  }

  @Test
  fun `bind to a new view after a content-only update keeps the committed layout`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val (item, scope, oldView) = boundItemShowingA()
    item.onViewRecycled(oldView)
    updateToB(item, scope)

    val newView = newView()
    val bRendersDuringBind = rendersOfBDuring { with(item) { scope.onBindView(newView) } }

    assertThat(bRendersDuringBind).describedAs("B must not render inside onBindView").isEqualTo(0)
    assertThat(newView.layoutState?.height)
        .describedAs("A is shown until B commits")
        .isEqualTo(HEIGHT_A)
    drainAndIdle()
    assertShowsB(item, newView)
  }

  @Test
  fun `bind after an update that was never prepared renders the new component in the background`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val (item, scope, view) = boundItemShowingA()
    // No prepareAsync: e.g. the item is out of the prepared range, or the changeset swapped the
    // component before the range was recomputed. Bind itself must queue B.
    item.renderInfo = renderInfoOf(CountingRow(LABEL_B, HEIGHT_B, counter))

    val bRendersDuringBind = rendersOfBDuring { with(item) { scope.onBindView(view) } }

    assertThat(bRendersDuringBind).isEqualTo(0)
    assertThat(handler.pendingCount).describedAs("bind must queue B itself").isGreaterThan(0)
    drainAndIdle()
    assertShowsB(item, view)
  }

  @Test
  fun `bind re-renders the new component when its queued render never committed`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val (item, scope, view) = boundItemShowingA()
    // The renderer has B as its requested root, but the queued work never commits (e.g. an
    // interrupted resolve). A is still the committed layout and nothing is queued.
    updateToB(item, scope)
    handler.dropAll()

    val bRendersDuringBind = rendersOfBDuring { with(item) { scope.onBindView(view) } }

    assertThat(bRendersDuringBind).isEqualTo(0)
    drainAndIdle()
    assertThat(view.layoutState?.height)
        .describedAs("the row is stuck on A: bind showed A and queued nothing for B")
        .isEqualTo(HEIGHT_B)
    assertShowsB(item, view)
  }

  @Test
  fun `bind with different constraints than the committed layout renders synchronously`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val (item, _, view) = boundItemShowingA()
    item.renderInfo = renderInfoOf(CountingRow(LABEL_B, HEIGHT_B, counter))
    val narrowerScope = createScope(item, collectionWidth = NARROW_WIDTH)

    val bRendersDuringBind = rendersOfBDuring { with(item) { narrowerScope.onBindView(view) } }

    assertThat(bRendersDuringBind).isEqualTo(1)
    assertThat(view.layoutState?.height).isEqualTo(HEIGHT_B)
  }

  @Test
  fun `bind to a second view during a change animation renders synchronously`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val (item, scope, _) = boundItemShowingA()
    updateToB(item, scope)

    // The first view is still the item's bound view, as when RecyclerView cross-fades to a new one.
    val secondView = newView()
    val bRendersDuringBind = rendersOfBDuring { with(item) { scope.onBindView(secondView) } }

    assertThat(bRendersDuringBind).isEqualTo(1)
    assertThat(secondView.layoutState?.height).isEqualTo(HEIGHT_B)
  }

  @Test
  fun `bind in a dynamically sized collection renders synchronously`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val (item, scope, view) = boundItemShowingA()
    updateToB(item, scope)
    val dynamicScope = createScope(item, crossAxisWrapMode = CrossAxisWrapMode.Dynamic)

    val bRendersDuringBind = rendersOfBDuring { with(item) { dynamicScope.onBindView(view) } }

    assertThat(bRendersDuringBind).isEqualTo(1)
  }

  @Test
  fun `bound rows render first only when the collection owns the handler`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    for (prioritizeBoundRows in listOf(true, false)) {
      handler.dropAll()
      val (item, scope, view) = boundItemShowingA(prioritizeBoundRows = prioritizeBoundRows)
      val other = createItem(CountingRow("other-$prioritizeBoundRows", HEIGHT_A, counter))
      other.prepareAsync(childConstraints(scope, other))
      item.renderInfo = renderInfoOf(CountingRow(LABEL_B, HEIGHT_B, counter))
      val bRendersBefore = counter.countFor(LABEL_B)
      with(item) { scope.onBindView(view) }

      handler.runOne()

      val bRendered = counter.countFor(LABEL_B) - bRendersBefore
      if (prioritizeBoundRows) {
        assertThat(bRendered).describedAs("B's resolve runs before other queued work").isEqualTo(1)
        handler.runOne()
        ShadowLooper.idleMainLooper()
        assertThat(view.layoutState?.height)
            .describedAs("B's layout also runs before other queued work")
            .isEqualTo(HEIGHT_B)
      } else {
        assertThat(bRendered)
            .describedAs("on a shared handler B waits behind earlier work")
            .isEqualTo(0)
      }
      handler.drain()
      counter.reset(LABEL_B)
    }
  }

  @Test
  fun `bind after recycle does not attach the outdated component's effects`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val log = mutableListOf<String>()
    val (item, scope, oldView) = boundItemShowingA(effectLog = log)
    item.onViewRecycled(oldView)
    item.renderInfo = renderInfoOf(CountingRow(LABEL_B, HEIGHT_B, counter, log))
    item.prepareAsync(childConstraints(scope, item))

    val newView = newView()
    with(item) { scope.onBindView(newView) }
    drainAndIdle()

    assertThat(newView.layoutState?.height).isEqualTo(HEIGHT_B)
    assertThat(log).containsExactly("attach:A", "detach:A", "attach:B")
  }

  @Test
  fun `bind after a rolled-back change attaches the effects again`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val log = mutableListOf<String>()
    val (item, scope, oldView) = boundItemShowingA(effectLog = log)
    item.onViewRecycled(oldView)
    item.renderInfo = renderInfoOf(CountingRow(LABEL_B, HEIGHT_B, counter, log))
    item.prepareAsync(childConstraints(scope, item))
    val newView = newView()
    // Scrolls back in: shows A's layout without its effects while B is queued.
    with(item) { scope.onBindView(newView) }

    // Rolled back to A before the background work runs, then rebound for the update.
    item.renderInfo = renderInfoOf(CountingRow(LABEL_A, HEIGHT_A, counter, log))
    item.prepareAsync(childConstraints(scope, item))
    with(item) { scope.onBindView(newView) }
    drainAndIdle()

    assertThat(newView.layoutState?.height).isEqualTo(HEIGHT_A)
    assertThat(log)
        .describedAs("A is on screen, so its effects must be attached")
        .containsExactly("attach:A", "detach:A", "attach:A")
  }

  @Test
  fun `bind after recycle attaches the effects of an unchanged row`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val log = mutableListOf<String>()
    val (item, scope, oldView) = boundItemShowingA(effectLog = log)
    item.onViewRecycled(oldView)

    val newView = newView()
    with(item) { scope.onBindView(newView) }
    drainAndIdle()

    assertThat(newView.layoutState?.height).isEqualTo(HEIGHT_A)
    assertThat(log).containsExactly("attach:A", "detach:A", "attach:A")
  }

  @Test
  fun `bind with no committed layout renders synchronously`() {
    ComponentsConfiguration.useCommittedLayoutOnCollectionBind = true
    val item = createItem(CountingRow(LABEL_A, HEIGHT_A, counter))
    val scope = createScope(item)
    val view = newView()

    with(item) { scope.onBindView(view) }

    assertThat(counter.countFor(LABEL_A)).isEqualTo(1)
    assertThat(handler.pendingCount).isEqualTo(0)
    assertThat(view.layoutState?.height).isEqualTo(HEIGHT_A)
  }

  /** Item prepared in the background with component A and bound to a view that shows A. */
  private fun boundItemShowingA(
      prioritizeBoundRows: Boolean = false,
      effectLog: MutableList<String>? = null,
  ): Triple<LithoCollectionItem, CollectionLayoutScope, LithoRenderTreeView> {
    val item = createItem(
        CountingRow(LABEL_A, HEIGHT_A, counter, effectLog),
        prioritizeBoundRows = prioritizeBoundRows,
    )
    val scope = createScope(item)
    val view = newView()
    item.prepareAsync(childConstraints(scope, item))
    handler.drain()
    with(item) { scope.onBindView(view) }
    assertThat(view.layoutState?.height).isEqualTo(HEIGHT_A)
    return Triple(item, scope, view)
  }

  /** A diff UPDATE: same item, new non-equivalent component, range re-prepare queues it. */
  private fun updateToB(item: LithoCollectionItem, scope: CollectionLayoutScope) {
    item.renderInfo = renderInfoOf(CountingRow(LABEL_B, HEIGHT_B, counter))
    item.prepareAsync(childConstraints(scope, item))
    assertThat(handler.pendingCount).describedAs("B must be queued before bind").isGreaterThan(0)
  }

  private fun rendersOfBDuring(block: () -> Unit): Int {
    val before = counter.countFor(LABEL_B)
    block()
    return counter.countFor(LABEL_B) - before
  }

  private fun drainAndIdle() {
    handler.drain()
    ShadowLooper.idleMainLooper()
  }

  private fun assertShowsB(item: LithoCollectionItem, view: LithoRenderTreeView) {
    assertThat(counter.countFor(LABEL_B)).describedAs("B renders exactly once").isEqualTo(1)
    assertThat(item.size.height).describedAs("committed layout is B").isEqualTo(HEIGHT_B)
    assertThat(view.layoutState?.height).describedAs("view shows B").isEqualTo(HEIGHT_B)
  }

  private fun createItem(
      component: Component,
      prioritizeBoundRows: Boolean = false,
  ): LithoCollectionItem = LithoCollectionItem(
      lithoTestRule.context,
      renderInfo = renderInfoOf(component),
      runnableHandlerProvider = handlerProvider,
      prioritizeBoundRows = prioritizeBoundRows,
  )

  private fun createScope(
      item: LithoCollectionItem,
      collectionWidth: Int = COLLECTION_WIDTH,
      crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
  ): CollectionLayoutScope = CollectionLayoutScope(
      layoutInfo = layoutInfo,
      collectionConstraints = SizeConstraints.exact(collectionWidth, COLLECTION_HEIGHT),
      collectionSize = IntSize(collectionWidth, COLLECTION_HEIGHT),
      collectionItems = listOf(item),
      isVertical = true,
      wrapInMainAxis = false,
      crossAxisWrapMode = crossAxisWrapMode,
      firstVisiblePosition = 0,
      lastVisiblePosition = 0,
  )

  private fun childConstraints(
      scope: CollectionLayoutScope,
      item: LithoCollectionItem,
  ): SizeConstraints = with(scope) { getChildSizeConstraints(item) }

  private fun renderInfoOf(component: Component) =
      ComponentRenderInfo.create().component(component).build()

  private fun newView() = LithoRenderTreeView(lithoTestRule.context.androidContext)

  private class RenderCounter {
    private val counts = mutableMapOf<String, Int>()

    fun increment(label: String) {
      counts[label] = countFor(label) + 1
    }

    fun countFor(label: String): Int = counts[label] ?: 0

    fun reset(label: String) {
      counts.remove(label)
    }
  }

  private class CountingRow(
      private val label: String,
      private val heightPx: Int,
      private val counter: RenderCounter,
      private val effectLog: MutableList<String>? = null,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      counter.increment(label)
      if (effectLog != null) {
        useEffect(Unit) {
          effectLog.add("attach:$label")
          onCleanup { effectLog.add("detach:$label") }
        }
      }
      return Column(style = Style.height(heightPx.px))
    }
  }

  private class FakeRunnableHandler : RunnableHandler {
    private val queue = ArrayDeque<Runnable>()

    val pendingCount: Int
      get() = queue.size

    override fun isTracing(): Boolean = false

    override fun post(runnable: Runnable, tag: String) {
      queue.addLast(runnable)
    }

    override fun postAtFront(runnable: Runnable, tag: String) {
      queue.addFirst(runnable)
    }

    override fun remove(runnable: Runnable) {
      queue.removeAll { it === runnable }
    }

    fun dropAll() {
      queue.clear()
    }

    fun runOne() {
      queue.removeFirstOrNull()?.run()
    }

    fun drain() {
      var iterations = 0
      while (queue.isNotEmpty()) {
        check(iterations++ < MAX_DRAIN_ITERATIONS) { "FakeRunnableHandler did not settle" }
        queue.removeFirst().run()
      }
    }
  }

  private companion object {
    const val LABEL_A = "A"
    const val LABEL_B = "B"
    const val HEIGHT_A = 50
    const val HEIGHT_B = 70
    const val COLLECTION_WIDTH = 400
    const val NARROW_WIDTH = 300
    const val COLLECTION_HEIGHT = 800
    const val MAX_DRAIN_ITERATIONS = 100
  }
}
