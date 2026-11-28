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

package com.facebook.litho.rangemanager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.UiThread
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.ComponentsSystrace
import com.facebook.litho.LithoView
import com.facebook.litho.LithoVisibilityEventsController
import com.facebook.litho.TreeState
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.thread.utils.ThreadUtils
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import com.meta.rangemanager.PrepareScope
import com.meta.rangemanager.ui.BindableCollectionItem
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A Litho collection item with lazy data fetching and a content key for update detection.
 *
 * ## Usage
 *
 * ```kotlin
 * LithoCollectionItem(
 *   context = context,
 *   dataProvider = { getStoryData(storyId) },
 *   component = { data -> StoryCardComponent(data) },
 *   sizeConstraints = sizeConstraints,
 *   traceTag = "StoryCard",
 *   // Optional: rebuild only when a rendered field changes
 *   contentKey = { data -> data.title to data.likeCount },
 * )
 * ```
 *
 * ## Thread Safety
 * - [prepare] and [unprepare]: Any thread
 * - [bind] and [unbind]: Main thread only
 *
 * ## Release while shown
 * With [keepTreeWhileShown], an item its owner releases while it is bound keeps the tree its view
 * shows, for instance when an asynchronous worker runs a release chosen before the row scrolled
 * back and bound again; releasing the tree then would blank that view. The tree is released on
 * [unbind] instead, as is a tree a [bind] builds after the release. So a consumer that turns this
 * on must unbind every view it binds; a view it leaves bound keeps its tree. Without it,
 * [unprepare] releases the tree whether or not a view shows it.
 *
 * ## Data changes found at bind
 * A [bind] that finds the data changed since the tree last took it may let the view show the older
 * layout while the new one is laid out, as [staleContentAtBind] says. With
 * [StaleContentAtBind.ALLOWED] the new root is set only once the current frame is done: setting it
 * during the bind would not help, as the measure that follows in the same frame would find a root
 * with no layout and lay it out on the main thread anyway.
 *
 * @param T The data type. Supports nullable types for placeholder states.
 * @param context Android context for component creation.
 * @param dataProvider Provides data when called. Invoked lazily during preparation.
 * @param component Creates a Component from the data.
 * @param sizeConstraints Fixed size constraints for layout. Used when [sizeConstraintsProvider] is
 *   null.
 * @param contentKey What [component] depends on in the data, compared with `equals`. Recorded when
 *   the component is built; the component is rebuilt when the current data's key differs, and
 *   otherwise kept (and, with a [sizeConstraintsProvider], laid out at the current data's
 *   constraints). Not the row's identity: a key that stays the same while rendered fields change
 *   keeps a stale component. Defaults to the data itself, which suits value data; data compared by
 *   identity or mutated in place needs an explicit key.
 * @param findLithoView Locates the LithoView within a container view.
 * @param onAfterBind Optional callback invoked after bind completes. Receives the bound LithoView.
 *   Called on the main thread.
 * @param onAfterUnbind Optional callback invoked before unbind clears the view. Receives the
 *   LithoView. Called on the main thread.
 * @param treeConfigurator Optional hook to customize the [ComponentTree.Builder] before the tree is
 *   built. This is the single place to set a custom
 *   [com.facebook.litho.config.ComponentsConfiguration] (via `componentsConfiguration(...)`),
 *   attach a visibility events controller, a lifecycle-owner tree prop, etc. Invoked once per tree
 *   build. When null (default), the tree uses the ComponentContext's default configuration. Kept
 *   generic so this base library stays free of app-layer lifecycle/visibility dependencies.
 * @param keepTreeWhileShown whether a bound item's tree survives [unprepare] until [unbind] (see
 *   "Release while shown"). Off by default, so each consumer turns it on behind its own config.
 * @param visibilityEventsController a parent for the visibility events controllers of this item's
 *   trees. Each tree the item builds gets its own child controller, which forwards the parent's
 *   events and is destroyed when the item releases the tree. That releases what the tree's nested
 *   collections hold, since they only let go of their item trees when their controller is
 *   destroyed. Requires `keepStateAfterRelease = false`, and takes precedence over a controller
 *   [treeConfigurator] sets.
 * @param keepStateAfterRelease whether [unprepare] saves the tree's [TreeState] for the next tree
 *   built for this row to restore. Off is required with a [visibilityEventsController], and suits
 *   any consumer that gives each tree something it releases with the tree: component state can
 *   capture it (a nested collection's binder captures the controller), so a restored state would
 *   point at something already released. Off, the item saves and restores none, clears any an
 *   earlier item saved for the row, and every piece of the row's component state resets when the
 *   row comes back. Don't turn it off while the trees share a visibility events controller that
 *   outlives them, unless you also pass it as [visibilityEventsController]: each new tree would
 *   build a new nested collection on it, and every earlier one keeps its item trees until that
 *   controller is destroyed.
 * @param sizeConstraintsProvider Optional current constraints for a data snapshot. Setting it opts
 *   the item into re-laying out a kept tree when constraints change, and into leaving a shown
 *   tree's size to its LithoView; without it, the item keeps its fixed-constraint behavior. Invoked
 *   on the thread preparing or binding the item, so it must be cheap and thread-safe.
 * @param staleContentAtBind whether a view may show older data while [bind] lays out newer data
 *   (see "Data changes found at bind"). [StaleContentAtBind.NEVER] by default.
 */
class LithoCollectionItem<T>(
    val context: Context,
    val dataProvider: () -> T,
    val component: (T) -> Component,
    val sizeConstraints: SizeConstraints,
    val contentKey: (T) -> Any? = { it },
    val findLithoView: (View) -> LithoView = { view -> view as LithoView },
    val onAfterBind: ((LithoView) -> Unit)? = null,
    val onAfterUnbind: ((LithoView) -> Unit)? = null,
    val debugBreadcrumb: String? = null,
    val treeConfigurator: ((ComponentTree.Builder) -> Unit)? = null,
    /**
     * Row identifier for this item's trace block names. Required so blocks are always attributable
     * to a row type, matching legacy's [com.instagram.ui.framework.litho.LithoBinder] `binderId`.
     */
    val traceTag: String,
    private val keepTreeWhileShown: Boolean = false,
    private val visibilityEventsController: LithoVisibilityEventsController? = null,
    private val keepStateAfterRelease: Boolean = true,
    private val sizeConstraintsProvider: ((T) -> SizeConstraints)? = null,
    private val staleContentAtBind: StaleContentAtBind = StaleContentAtBind.NEVER,
) : BindableCollectionItem {

  init {
    require(visibilityEventsController == null || !keepStateAfterRelease) {
      "visibilityEventsController needs keepStateAfterRelease = false: a restored state would " +
          "bring back nested collections bound to a destroyed controller"
    }
  }

  private val tracePrepare = "litho:row:prepare:$traceTag"
  private val traceBind = "litho:row:bind:$traceTag"
  private val traceUnprepare = "litho:row:unprepare:$traceTag"

  // region Private State

  /** The last built component and the [contentKey] of the data it was built from. */
  private class PreparedContent(val key: Any?, val component: Component)

  private var preparedContent: PreparedContent? = null
  @Volatile private var componentTree: ComponentTree? = null
  @Volatile private var lithoView: LithoView? = null
  private var cachedTreeState: TreeState? = null

  /** The controller this item gave [componentTree], if any. Set and cleared with it. */
  @Volatile private var treeVisibilityEventsController: TreeVisibilityEventsController? = null

  /**
   * Guards the item's state. [prepare] holds it on its thread while it builds the component, and
   * [bind] waits for it, as both need the prepared content. Release bookkeeping on the main thread
   * never waits for it ([checkRelease]).
   */
  private val lock = ReentrantLock()

  /**
   * Set by [unprepare] with [keepTreeWhileShown] and cleared by [prepare]: whether the owner has
   * released this item, so the tree it still has goes on [unbind] and takes no posted update.
   */
  private var isReleasedByOwner = false

  // endregion

  // region BindableCollectionItem Implementation

  /**
   * Prepares the item asynchronously. Fetches data and computes layout off the main thread.
   *
   * Safe to call multiple times; only re-prepares when the [contentKey] changes.
   */
  override fun PrepareScope.prepare() {
    lock.withLock {
      ComponentsSystrace.trace(tracePrepare) {
        isReleasedByOwner = false
        // Restore TreeState from PrepareScope if available (key context is set by infrastructure)
        val restoredState = if (keepStateAfterRelease) getSavedState() as? TreeState else null
        if (restoredState != null) {
          cachedTreeState = restoredState
        }
        // While bound, the row's LithoView owns its size.
        prepareWithDataIfNeeded(sync = false, updateSizeConstraints = lithoView == null)
      }
    }
  }

  /**
   * Releases resources. Safe to call from any thread. With [keepTreeWhileShown], a bound item keeps
   * its tree until [unbind] (see "Release while shown").
   */
  override fun PrepareScope.unprepare() {
    lock.withLock {
      ComponentsSystrace.trace(traceUnprepare) {
        isReleasedByOwner = keepTreeWhileShown
        val tree = componentTree
        val controller = treeVisibilityEventsController
        var isKeptForView = false
        if (tree != null) {
          if (keepStateAfterRelease) {
            // Cache TreeState for later restoration (both locally and in PrepareScope)
            val treeState = tree.acquireTreeState()
            cachedTreeState = treeState
            saveState(treeState)
          } else {
            // An earlier item for this row may have saved state; the next one must not restore it.
            saveState(null)
          }
          isKeptForView = isReleasedByOwner && lithoView != null
          if (isKeptForView) {
            // Rechecked on the main thread, where unbind clears lithoView, so a release racing
            // unbind still releases the tree.
            onMainThread { checkRelease() }
          } else {
            onMainThread { release(tree, controller) }
          }
        }
        if (!isKeptForView) {
          componentTree = null
          treeVisibilityEventsController = null
          preparedContent = null
        }
      }
    }
  }

  /** Binds the component to the view. Must be called on the main thread. */
  @OptIn(AllowsStaleContentOnScreen::class)
  @UiThread
  override fun bind(view: View) {
    ThreadUtils.assertMainThread()
    ComponentsSystrace.trace(traceBind) {
      val targetView = findLithoView(view)
      lithoView = targetView
      lock.withLock {
        // Split so a trace distinguishes a cheap attach from a blocking resolve+layout; legacy
        // treats the equivalent no-preparable state as a reportable error.
        if (componentTree == null) {
          ComponentsSystrace.trace("LithoCollectionItem.bind.rebuildFromScratch") {
            // No tree yet, so there is no measured size to keep.
            prepareWithDataIfNeeded(sync = true, updateSizeConstraints = true)
          }
        } else {
          ComponentsSystrace.trace("LithoCollectionItem.bind.updateIfNeeded") {
            // A view already showing this tree has measured it; a view new to it has not.
            val viewAlreadyShowsTree = targetView.componentTree === componentTree
            when (staleContentAtBind) {
              StaleContentAtBind.NEVER -> {
                prepareWithDataIfNeeded(sync = true, updateSizeConstraints = !viewAlreadyShowsTree)
              }
              StaleContentAtBind.ON_SHOWING_VIEW -> {
                updateKeepingShownLayout(viewAlreadyShowsTree)
              }
              StaleContentAtBind.ALLOWED -> {
                updateAfterFrameIfNeeded(viewAlreadyShowsTree)
              }
            }
          }
        }
        targetView.componentTree = componentTree
      }
      onAfterBind?.invoke(targetView)
    }
  }

  /**
   * Unbinds the component from the view. Must be called on the main thread. Releases the tree if
   * the owner has released this item.
   */
  @UiThread
  override fun unbind() {
    ThreadUtils.assertMainThread()
    lithoView?.let { onAfterUnbind?.invoke(it) }
    if (!keepTreeWhileShown) {
      lithoView?.componentTree = null
      lithoView = null
      return
    }
    // Only if it still shows this item's tree: another item may have taken the view since.
    lithoView?.takeIf { it.componentTree === componentTree }?.componentTree = null
    lithoView = null
    checkRelease()
  }

  // endregion

  // region Private Helpers

  /**
   * For an item its owner released, releases the tree it still has unless its view still shows it.
   * Main thread only, and never waits for [lock].
   */
  @UiThread
  private fun checkRelease() {
    // Only prepare and unprepare hold the lock off the main thread. A prepare takes the item back,
    // so there is nothing to release; an unprepare releases the tree or schedules this check.
    if (!lock.tryLock()) {
      return
    }
    val toRelease =
        try {
          val tree = componentTree
          val isShown = tree != null && lithoView?.componentTree === tree
          // Not released means its owner prepared it again and releases the tree later.
          if (isReleasedByOwner && !isShown) takeTree() else null
        } finally {
          lock.unlock()
        }
    toRelease?.let { (tree, controller) -> release(tree, controller) }
  }

  /** Releases [tree], then destroys [controller], the one this item gave it. Main thread only. */
  @UiThread
  private fun release(tree: ComponentTree, controller: TreeVisibilityEventsController?) {
    try {
      tree.release()
    } finally {
      // A release that threw never unsubscribed the tree, which must not be released again.
      controller?.removeListener(tree)
      controller?.destroy()
    }
  }

  /**
   * Clears the prepared tree and returns it with its controller, for the caller to release. Holds
   * [lock].
   */
  private fun takeTree(): Pair<ComponentTree, TreeVisibilityEventsController?>? {
    val tree = componentTree ?: return null
    val controller = treeVisibilityEventsController
    componentTree = null
    treeVisibilityEventsController = null
    preparedContent = null
    return tree to controller
  }

  /**
   * Lays new data out in the background for a view already showing the tree, which keeps its
   * committed layout until it lands, and now for a view new to it. An earlier bind may have left
   * the latest root laying out in the background, under a key this bind already sees as current:
   * the view new to the tree finishes it, a no-op when it already landed. Holds [lock].
   */
  @UiThread
  private fun updateKeepingShownLayout(viewAlreadyShowsTree: Boolean) {
    if (viewAlreadyShowsTree) {
      prepareWithDataIfNeeded(sync = false, updateSizeConstraints = false)
      return
    }
    prepareWithDataIfNeeded(sync = true, updateSizeConstraints = true)
    componentTree?.let { tree -> tree.setRootSync(tree.root) }
  }

  /**
   * Leaves the tree's committed layout for the view to show and posts the update to after the
   * frame. Unchanged data leaves the tree as a [StaleContentAtBind.NEVER] bind does: a first layout
   * still in the background is waited for by the view's layout, not by this bind. Changed data with
   * nothing laid out at its size yet is laid out now, unless [viewAlreadyShowsTree]: that view
   * already shows a layout and owns the tree's size. The size check ignores which root the layout
   * was built from: the view's measure reuses a layout with the same specs
   * (`performExactSameSpecsCheck`, on by default). Holds [lock], on the main thread.
   */
  @UiThread
  private fun updateAfterFrameIfNeeded(viewAlreadyShowsTree: Boolean) {
    val tree = componentTree ?: return
    val data = dataProvider()
    if (preparedContent?.key == contentKey(data)) {
      return
    }
    if (!viewAlreadyShowsTree) {
      val constraints = sizeConstraintsFor(data)
      if (!tree.hasCompatibleLayout(constraints.toWidthSpec(), constraints.toHeightSpec())) {
        // The view would lay out the older data first, so lay out the latest now.
        prepareWithDataIfNeeded(sync = true, updateSizeConstraints = true)
        tree.setRootSync(tree.root)
        return
      }
    }
    mainHandler.post { updateAfterFrame() }
  }

  /**
   * Lays the latest data out in the background, unless the item was released since. Never waits for
   * [lock], and skips the update while another thread holds it: a [prepare] lays the latest data
   * out itself, and after an [unprepare] the view keeps the committed layout until the next prepare
   * or bind.
   */
  @UiThread
  private fun updateAfterFrame() {
    if (!lock.tryLock()) {
      return
    }
    try {
      if (componentTree == null || isReleasedByOwner) {
        return
      }
      ComponentsSystrace.trace("LithoCollectionItem.updateAfterFrame") {
        prepareWithDataIfNeeded(sync = false, updateSizeConstraints = lithoView == null)
      }
    } finally {
      lock.unlock()
    }
  }

  /** [ComponentTree.release] must run on the main thread. */
  private fun onMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  /**
   * Prepares the current data unless its key matches the built tree's. With a
   * [sizeConstraintsProvider], a matching tree is laid out at the current data's constraints if it
   * isn't already. Holds [lock].
   *
   * [updateSizeConstraints] is false while a LithoView shows this tree: its normal measure pass
   * supplies the authoritative specs. Without a [sizeConstraintsProvider] it is ignored.
   */
  private fun prepareWithDataIfNeeded(sync: Boolean, updateSizeConstraints: Boolean) {
    val data = dataProvider()
    val key = contentKey(data)
    val tree = componentTree
    if (sizeConstraintsProvider == null) {
      // Fixed constraints: every new root is submitted with them, and an unchanged key is a no-op.
      if (tree == null || preparedContent?.key != key) {
        prepareWithData(data, key, sync, updateSizeConstraints = true)
      }
      return
    }
    if (tree == null || preparedContent?.key != key) {
      prepareWithData(data, key, sync, updateSizeConstraints)
    } else if (updateSizeConstraints) {
      layoutIfNeeded(tree, sizeConstraintsFor(data), sync)
    }
  }

  /**
   * Lays [tree] out at [constraints] unless it already is. Keeps the tree's current root, which a
   * state update may have replaced with a copy of the built component.
   */
  private fun layoutIfNeeded(tree: ComponentTree, constraints: SizeConstraints, sync: Boolean) {
    val widthSpec = constraints.toWidthSpec()
    val heightSpec = constraints.toHeightSpec()
    if (tree.hasCompatibleLayout(widthSpec, heightSpec)) {
      return
    }
    if (sync) {
      ComponentsSystrace.trace("LithoCollectionItem.setSizeSpecSync") {
        tree.setSizeSpec(widthSpec, heightSpec)
      }
    } else {
      ComponentsSystrace.trace("LithoCollectionItem.setSizeSpecAsync") {
        tree.setSizeSpecAsync(widthSpec, heightSpec)
      }
    }
  }

  /**
   * Builds the component, creates the ComponentTree if needed, and sets root. Without
   * [updateSizeConstraints], an existing tree keeps its current specs.
   */
  private fun prepareWithData(
      data: T,
      key: Any?,
      sync: Boolean,
      updateSizeConstraints: Boolean,
  ) {
    val builtComponent = component(data)
    preparedContent = PreparedContent(key, builtComponent)

    val existingTree = componentTree
    if (existingTree != null && !updateSizeConstraints) {
      // An existing tree always has specs, so a sync root-only request stays sync.
      if (sync) {
        ComponentsSystrace.trace("LithoCollectionItem.setRootSync") {
          existingTree.setRootSync(builtComponent)
        }
      } else {
        ComponentsSystrace.trace("LithoCollectionItem.setRootAsync") {
          existingTree.setRootAsync(builtComponent)
        }
      }
      return
    }

    if (componentTree == null) {
      val builder = ComponentTree.create(ComponentContext(context)).treeState(cachedTreeState)
      val childController = visibilityEventsController?.let(TreeVisibilityEventsController::childOf)
      var tree: ComponentTree? = null
      try {
        // Callers set componentsConfiguration / lifecycle-owner tree prop here. Null (default)
        // leaves the ComponentContext's default config.
        treeConfigurator?.invoke(builder)
        // After the configurator, so the item's own controller wins over one it sets.
        childController?.let(builder::withLithoVisibilityEventsController)
        tree =
            ComponentsSystrace.trace("LithoCollectionItem.buildComponentTree") {
              builder.build().also { it.setDebugLogsBreadcrumb(debugBreadcrumb) }
            }
      } finally {
        // A tree that was never built would never release its controller.
        if (tree == null) {
          childController?.unsubscribe()
        }
      }
      componentTree = tree
      treeVisibilityEventsController = childController
      // Clear cached state after use to avoid stale references
      cachedTreeState = null
    }

    val constraints = sizeConstraintsFor(data)
    val widthSpec = constraints.toWidthSpec()
    val heightSpec = constraints.toHeightSpec()
    if (sync) {
      ComponentsSystrace.trace("LithoCollectionItem.setRootAndSizeSpecSync") {
        componentTree?.setRootAndSizeSpecSync(builtComponent, widthSpec, heightSpec)
      }
    } else {
      ComponentsSystrace.trace("LithoCollectionItem.setRootAndSizeSpecAsync") {
        componentTree?.setRootAndSizeSpecAsync(builtComponent, widthSpec, heightSpec)
      }
    }
  }

  private fun sizeConstraintsFor(data: T): SizeConstraints =
      sizeConstraintsProvider?.invoke(data) ?: sizeConstraints

  // endregion

  private companion object {
    private val mainHandler = Handler(Looper.getMainLooper())
  }
}

/** Whether a view may show older data while [LithoCollectionItem.bind] lays out newer data. */
enum class StaleContentAtBind {
  /** Never: bind lays the new data out on the main thread. */
  NEVER,

  /**
   * Only on a view already showing the tree, which keeps its committed layout until the new one
   * lands. A view new to the tree lays the new data out on the main thread.
   */
  ON_SHOWING_VIEW,

  /**
   * On any view: it shows the tree's committed layout, and the new one replaces it once laid out. A
   * tree with nothing laid out at the view's size yet lays the new data out on the main thread. A
   * shown item its owner releases before the update runs keeps the committed layout until the next
   * prepare or bind.
   */
  @AllowsStaleContentOnScreen ALLOWED,
}

/**
 * Marks [StaleContentAtBind.ALLOWED]: a visible row can show older data for a frame or more, and
 * change height when the new layout lands. Opt in only for a list where that is acceptable.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "With StaleContentAtBind.ALLOWED a visible row can show older data for a frame or more " +
            "and change height when the new layout lands. Check that this is acceptable for your " +
            "list before opting in.",
)
annotation class AllowsStaleContentOnScreen
