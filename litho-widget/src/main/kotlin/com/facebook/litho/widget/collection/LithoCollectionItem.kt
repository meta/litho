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

import android.content.Context
import androidx.annotation.UiThread
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.LithoRenderer
import com.facebook.litho.LithoSaveableStateRegistry
import com.facebook.litho.LithoSaveableStateRegistryTreeProp
import com.facebook.litho.LithoTree
import com.facebook.litho.LithoVisibilityEventsController
import com.facebook.litho.TreePropContainer
import com.facebook.litho.componentsConfig
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.RunnableHandlerProvider
import com.facebook.litho.widget.ComponentTreeHolder
import com.facebook.litho.widget.RenderInfo
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.meta.rangemanager.PrepareScope

/** A [CollectionItem] that renders a [LithoRenderTreeView]. */
class LithoCollectionItem(
    componentContext: ComponentContext,
    id: Int = LithoTree.generateComponentTreeId(),
    viewType: Int = DEFAULT_COMPONENT_VIEW_TYPE,
    renderInfo: RenderInfo,
    runnableHandlerProvider: RunnableHandlerProvider = ComponentTree.DefaultLayoutHandlerProvider(),
    private val onChildMeasured: ((LithoCollectionItem, IntSize) -> Unit) = { _, _ -> },
    // Lets bound rows post their background render ahead of other work. Only for a handler the
    // collection owns; on a shared layout thread it would jump ahead of other trees.
    private val prioritizeBoundRows: Boolean = false,
    // The collection's registries for its rows' saveable state. An item outside a collection gets
    // its own, which save nothing.
    private val saveableStateRegistries: CollectionSaveableStateRegistries =
        CollectionSaveableStateRegistries(),
) : CollectionItem<LithoRenderTreeView>(id, viewType, renderInfo) {

  // Capture values from componentContext for deferred LithoRenderer creation.
  // LithoRenderer is the heaviest per-item allocation: it eagerly creates TreeState (with 2
  // StateHandlers, each with up to 9 HashMaps), LithoTree, RenderUnitIdGenerator, and more.
  // Lazy init defers this entire cascade until the item is actually measured, prepared, or bound,
  // saving thousands of object allocations for items outside the viewport + preparation range.
  private val _androidContext: Context = componentContext.androidContext
  private val _componentsConfig: ComponentsConfiguration = componentContext.componentsConfig
  private val _treePropContainer: TreePropContainer? = componentContext.treePropContainerCopy
  private val _runnableHandlerProvider: RunnableHandlerProvider = runnableHandlerProvider
  private val _parentTreePropContainer: TreePropContainer? =
      componentContext.parentTreePropContainer
  private val _visibilityController: LithoVisibilityEventsController? =
      componentContext.lithoVisibilityEventsController
  private val parentSaveableStateRegistry: LithoSaveableStateRegistry? =
      _treePropContainer?.get(LithoSaveableStateRegistryTreeProp)

  /**
   * The key read from [renderInfo] when the renderer took its registry; it stays the item's key.
   */
  @Volatile private var registryKey: RowKey? = null

  /** The key last read from a [renderInfo], read again only when [renderInfo] changes. */
  @Volatile private var lastReadKey: RowKey? = null

  /**
   * The collection's registry for this item's key, so items rooted at the same component don't
   * share saveable-state keys. Taken with the renderer, once the collection has set [renderInfo].
   */
  @Volatile private var saveableStateRegistry: LithoSaveableStateRegistry? = null

  /** The generation of [saveableStateRegistry] that the renderer's layout may have read. */
  @Volatile private var renderedGeneration: Int = 0

  private val renderer: LithoRenderer
    get() =
        _renderer ?: synchronized(this) { _renderer ?: createRenderer().also { _renderer = it } }

  /** Returns the renderer only if it has already been created, without creating it. */
  private val rendererOrNull: LithoRenderer?
    get() = _renderer

  /**
   * The item's renderer, created on first use and dropped by [releaseRenderer].
   *
   * Creating it lazily avoids the cost rather than moving it: a renderer is the heaviest per-item
   * allocation (a TreeState with its state handlers, a LithoTree, a RenderUnitIdGenerator), and
   * most items of a long list are never prepared or bound, so they never create one. It is a
   * nullable field rather than a `lazy` so [releaseRenderer] can drop it; an item used again after
   * that creates a new one.
   */
  @Volatile private var _renderer: LithoRenderer? = null

  private fun createRenderer(): LithoRenderer {
    parentSaveableStateRegistry?.let { parent ->
      // A bare `renderInfo` here is the constructor argument, which inserted items replace later.
      val key = readKey(this.renderInfo)
      registryKey = key
      val registry = saveableStateRegistries.registryFor(parent, key.key)
      _treePropContainer?.put(LithoSaveableStateRegistryTreeProp, registry)
      saveableStateRegistry = registry
      renderedGeneration = registry.generation
    }
    return LithoRenderer(
        context = _androidContext,
        id = id,
        componentsConfig = _componentsConfig,
        treePropContainer = _treePropContainer,
        runnableHandlerProvider = _runnableHandlerProvider,
        parentTreePropContainer = _parentTreePropContainer,
        visibilityController = _visibilityController,
    )
  }

  /**
   * Track the currently bound view to handle RecyclerView change animations correctly. During
   * change animations, the same LithoCollectionItem may be bound to a NEW view while the OLD view
   * is still animating. When the old view's animation completes, onViewRecycled() is called with
   * the old view, but we should only clean up if it's the view we're currently managing.
   */
  private var currentBoundView: LithoRenderTreeView? = null

  override val size: IntSize
    get() =
        rendererOrNull?.currentLayoutState?.let { IntSize(it.width, it.height) } ?: IntSize.Invalid

  override val sizeConstraints: SizeConstraints?
    get() = rendererOrNull?.sizeConstraints

  override fun measure(sizeConstraints: SizeConstraints, result: IntArray?) {
    val layoutState = renderer.renderSync(renderInfo.component, sizeConstraints)
    result?.let {
      result[0] = layoutState.width
      result[1] = layoutState.height
    }
  }

  override fun PrepareScope.prepare() {
    this@prepare as ItemSizeConstraintsProviderScope
    renderer.renderSync(
        root = renderInfo.component,
        constraints = getChildSizeConstraints(this@LithoCollectionItem),
    )
  }

  override fun prepareAsync(sizeConstraints: SizeConstraints) {
    // Early-exit guard: Skip redundant preparation if we already have a layout state
    // with matching constraints and component. This prevents the excessive prepareAsync
    // calls observed during rapid scrolling from reaching the expensive render pipeline, saving
    // significant CPU cycles.
    val existing = rendererOrNull
    if (existing != null) {
      val currentLayoutState = existing.currentLayoutState
      if (
          currentLayoutState != null &&
              currentLayoutState.resolveResult.component === renderInfo.component &&
              existing.sizeConstraints == sizeConstraints
      ) {
        return
      }
    }

    renderer.render(renderInfo.component, sizeConstraints)
  }

  override fun ItemSizeConstraintsProviderScope.onBindView(view: LithoRenderTreeView) {
    val previousView = currentBoundView
    // Bound again before it was recycled, so it is still in use.
    releaseWhenRecycled = false

    // A change animation binds this item to a new view while the old one is still animating out.
    // onViewRecycled skips the old view later, as it is no longer the bound one, so the old view is
    // cleaned here. The effects stay attached: the item is still in the list and binds right below.
    if (previousView != null && previousView !== view) {
      rendererOrNull?.detachFromHost()
      previousView.clean()
    }

    // Track the view we're binding to
    currentBoundView = view

    // Restored values that arrived after this row rendered, before the collection was first shown,
    // are read only by a new render, so the layout from before them is dropped.
    saveableStateRegistry?.generation?.let { generation ->
      if (generation != renderedGeneration) {
        renderedGeneration = generation
        renderer.cleanup()
      }
    }
    renderer.bindTo(view)
    saveableStateRegistry?.onTreeShown()
    if (previousView == null) saveableStateRegistry?.let(saveableStateRegistries::onItemBound)
    // Getting size constraints from the scope is the safest way to ensure rendering the correct
    // size constraints, like in the following scenarios:
    // 1) We missed chances to prepare content ahead of time
    // 2) The collection size has changed in the dynamic size mode
    val constraints = getChildSizeConstraints(this@LithoCollectionItem)
    // A different previous view means a change animation is cross-fading to this view, which must
    // not start from stale content. Dynamic sizing needs the real size from onChildMeasured below.
    val keptCommittedLayout =
        ComponentsConfiguration.useCommittedLayoutOnCollectionBind &&
            !isDynamicSize &&
            (previousView == null || previousView === view) &&
            renderer.renderAsyncKeepingCommittedLayout(
                renderInfo.component,
                constraints,
                postAtFront = prioritizeBoundRows,
            )
    if (!keptCommittedLayout) {
      renderer.renderSyncWithCompatibilityChecks(renderInfo.component, constraints)
    }
    saveableStateRegistry?.onBoundRowRendered()
    onChildMeasured(this@LithoCollectionItem, size)
  }

  override fun onViewRecycled(view: LithoRenderTreeView) {
    if (view !== currentBoundView) {
      // During RecyclerView change animations, the same LithoCollectionItem
      // can be bound to a NEW view while the OLD view is still animating. When the old
      // view's animation completes, onViewRecycled() is called with the old view, but we're
      // now managing a different view. We should ONLY clean up views we're currently managing.
      return
    }

    // This is the view we're currently managing - clean it up. An unbound item is no longer saved,
    // so its values are retained until it binds again.
    //
    // Its effects stay attached: the item is still in the list, usually near the screen. They are
    // cleaned when a range pass finds it outside the range with no view (unprepare), or when
    // it is released. A sticky item is the exception: its floating header is a separate item that
    // renders the same component, so it would run the same effects a second time.
    saveableStateRegistry?.retainActiveValues()
    rendererOrNull?.detachFromHost()
    if (renderInfo.isSticky) {
      rendererOrNull?.cleanupDetachedEffects()
    }
    view.clean()
    currentBoundView = null
    saveableStateRegistry?.let(saveableStateRegistries::onItemUnbound)
    if (releaseWhenRecycled) {
      releaseRenderer()
    }
  }

  override fun PrepareScope.unprepare() {
    rendererOrNull?.cleanup()
    // A cached view keeps the effects until it is recycled and another range pass exits the item.
    rendererOrNull?.cleanupDetachedEffects()
  }

  /**
   * Releases the item's renderer and everything it holds: its components and their hook state. A
   * state handle that outlives the item, e.g. one a pooled view still holds, then can't keep them
   * alive. While a view is still bound, e.g. a removed row animating out, the renderer is released
   * once that view is recycled. The item renders again from scratch, with a new renderer, if it is
   * used after this.
   */
  @UiThread
  fun releaseRenderer() {
    if (currentBoundView != null) {
      releaseWhenRecycled = true
      return
    }
    releaseWhenRecycled = false
    val released = synchronized(this) { _renderer.also { _renderer = null } } ?: return
    released.release()
  }

  /** Whether [releaseRenderer] was called while a view was bound. Main thread only. */
  private var releaseWhenRecycled: Boolean = false

  /** The key of this item's registry in [CollectionSaveableStateRegistries]. */
  internal val saveableStateKey: String?
    get() = (registryKey ?: readKey(renderInfo)).key

  private fun readKey(info: RenderInfo): RowKey =
      lastReadKey?.takeIf { it.renderInfo === info }
          ?: RowKey(info, ComponentTreeHolder.stableIdSaveableStateKey(info)).also {
            lastReadKey = it
          }

  private class RowKey(val renderInfo: RenderInfo, val key: String?)

  companion object {
    const val DEFAULT_COMPONENT_VIEW_TYPE: Int = 0
  }
}
