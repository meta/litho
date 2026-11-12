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

package com.facebook.litho.surface

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.AOSPLithoVisibilityEventsController
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.LithoSaveableStateRegistry
import com.facebook.litho.LithoView
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState
import com.facebook.litho.TreePropContainer
import com.facebook.litho.TreeState
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.extensions.VisibilityChangeReason

/**
 * Per-(owner, key) [ViewModel] that owns a Litho [ComponentTree] across the owner's lifetime,
 * including configuration changes. Callers reach this VM via the extension-function API in
 * [LithoSurface] — direct construction is internal.
 *
 * ## Acquisition
 *
 * Two paths:
 * - **Single-surface owners**: call [setLithoContent], [getLithoView], or [bindLithoViewTo] as
 *   extension methods on the receiver. The default-keyed VM is created or reused under the hood.
 * - **Multi-surface owners and containers**: declare a property via the `lithoViewModel(...)`
 *   delegate (fragment / activity) or call `owner.lithoViewModel(...)` as a function (container).
 *   Each call returns this `LithoViewModel`; surface operations are methods on the returned handle.
 *
 * ## Lifetimes
 *
 * The VM is stored in the owner's [androidx.lifecycle.ViewModelStore]. It survives configuration
 * changes (rotation, multi-window resize, etc.); it is cleared by the platform on real teardown
 * (`onCleared`).
 *
 * Within the VM's lifetime, the [ComponentTree] is built and released *per instance lifecycle*:
 * - Built eagerly on the first [setLithoContent] call after construction or after instance
 *   teardown.
 * - Snapshot via [ComponentTree.acquireTreeState] and released when the providing instance owner
 *   reaches `onDestroy`.
 * - Rebuilt on the next [setLithoContent] call (against the new instance owner's
 *   [Context] + [LifecycleOwner]) and seeded with the saved [TreeState] so descendant `useState`,
 *   `@State` containers, and other state survive rotation.
 *
 * ## View binding
 *
 * Within a view-cycle, exactly one [LithoView] is bound — either created lazily by [getLithoView]
 * or supplied by the caller via [bindLithoViewTo]. Calling either method after the other has set
 * the view-cycle's binding throws. The bound view is unbound when the view-cycle owner reaches
 * `onDestroy`.
 *
 * ## Tree props
 *
 * The instance lifecycle owner is wired as the tree's [androidx.lifecycle.LifecycleOwner] tree
 * prop, so descendants using `useLiveData` and lifecycle-aware `useFlow` subscribe correctly. One
 * [LithoSaveableStateRegistry] is retained across tree recreation and snapshotted through Android
 * saved state so descendants using `useSaveableState` can restore after process recreation.
 *
 * ## Side resources
 *
 * Cleanup actions registered via [onSurfaceClose] run in [onCleared] (real teardown), not on
 * rotation. Use them for paired side resources whose lifetime should match the surface — e.g.
 * `GraphQLDataSource.close()`, coroutine `Job.cancel()`.
 */
class LithoViewModel
internal constructor(
    /** Human-readable surface name. Used in error messages, logs, instrumentation. */
    val key: String,
    internal val componentsConfiguration: ComponentsConfiguration?,
    internal val treePropsFactory: (() -> TreePropContainer?)?,
    internal val isExplicitlyConfigured: Boolean,
) : ViewModel() {

  // Bound by the factory / extension before any public method is callable. The providers are
  // pull-on-demand so the VM always sees the *current* fragment / activity / view-cycle owner —
  // critical because the VM survives instance and view-cycle teardown but those owners do not.
  private var contextProvider: (() -> Context)? = null
  private var instanceOwnerProvider: (() -> LifecycleOwner)? = null
  private var viewOwnerProvider: (() -> LifecycleOwner)? = null

  // Instance lifecycle currently providing tree wiring. Re-bound by the next setLithoContent()
  // after the prior instance owner reaches onDestroy. Suppress: cleared in instance.onDestroy and
  // in onCleared; the prior observer is detached on rebind so no stale reference outlives the
  // owner.
  @SuppressLint("ViewModelRetainsLifecycleOwner")
  private var instanceLifecycleOwner: LifecycleOwner? = null
  private var instanceLifecycleObserver: DefaultLifecycleObserver? = null

  // Wired against the current instance lifecycle. Rebuilt with the tree.
  private var visibilityController: AOSPLithoVisibilityEventsController? = null

  // The tree itself. Built on first setLithoContent(), released on instance.onDestroy.
  private var componentTree: ComponentTree? = null

  // Snapshot taken on instance.onDestroy. Consumed (nulled) by the next buildTree().
  private var savedTreeState: TreeState? = null

  private var componentSaveableStateRegistry: LithoSaveableStateRegistry? = null
  @SuppressLint("ViewModelRetainsLifecycleOwner")
  private var savedStateRegistryOwner: SavedStateRegistryOwner? = null
  private val saveableStateRegistryKey = "$SAVEABLE_STATE_KEY_PREFIX:$key"

  // Latest content lambda. Retained so a future feature could rebuild after a forced refresh.
  private var latestContent: (() -> Component)? = null

  // Per-view-cycle bound LithoView (mutex with caller-supplied path). Cleared on view-owner
  // onDestroy; also cleared on instance.onDestroy as a defensive sweep. Suppress: not a true
  // static-field leak — the field is cleared on every view-cycle teardown and on onCleared.
  @SuppressLint("StaticFieldLeak") private var cachedView: LithoView? = null
  private var cachedViewSource: ViewSource? = null

  // Suppress: cleared in view.onDestroy and in onCleared; the prior observer is detached on
  // rebind so no stale reference outlives the owner.
  @SuppressLint("ViewModelRetainsLifecycleOwner")
  private var viewLifecycleOwner: LifecycleOwner? = null
  private var viewLifecycleObserver: DefaultLifecycleObserver? = null

  // Cleanup actions registered via onSurfaceClose; run in onCleared.
  private val closeActions = mutableListOf<() -> Unit>()
  private var cleared = false

  private enum class ViewSource {
    GET,
    BIND,
  }

  /**
   * Set or swap the root content. On the first call after construction or after rotation, the VM
   * eagerly builds the [ComponentTree]. Subsequent calls within the same instance lifecycle queue
   * an off-main-thread [ComponentTree.setRootAsync] swap.
   *
   * Across rotation, hook state and `@State` containers survive automatically — the VM snapshots
   * the tree's [TreeState] in the instance owner's `onDestroy` and seeds the rebuilt tree on the
   * next call.
   */
  fun setLithoContent(content: () -> Component) {
    checkNotCleared("setLithoContent")
    val context = currentContext("setLithoContent")
    val instanceOwner = currentInstanceOwner("setLithoContent")
    bindInstanceLifecycle(instanceOwner)
    bindSavedStateRegistry(instanceOwner, context.classLoader)
    latestContent = content
    val tree = componentTree
    if (tree == null) {
      componentTree = buildTree(context, instanceOwner, content())
    } else {
      tree.setRootAsync(content())
    }
  }

  /**
   * Returns the view-cycle-cached [LithoView] — lazily created with the host's tree already
   * attached. Repeated calls within the same view-cycle return the same instance; the cache is
   * invalidated on view-owner `onDestroy`.
   *
   * Mutually exclusive with [bindLithoViewTo] within a view-cycle: calling one after the other
   * throws.
   *
   * If [setLithoContent] has not been called for the surface yet, this method builds the tree with
   * an internal [MissingLithoContentComponent] placeholder so the [LithoView] can still be bound
   * and a visible debug message is rendered. A subsequent [setLithoContent] call swaps the
   * placeholder out via [ComponentTree.setRootAsync].
   */
  fun getLithoView(): LithoView {
    checkNotCleared("getLithoView")
    val context = currentContext("getLithoView")
    val viewOwner = currentViewOwner("getLithoView")
    val tree = ensureTree(context, "getLithoView")
    bindViewLifecycle(viewOwner)
    cachedView?.let { existing ->
      check(cachedViewSource == ViewSource.GET) {
        "[$key] getLithoView() called after bindLithoViewTo() set the LithoView for this " +
            "view-cycle. Call exactly one of bindLithoViewTo/getLithoView per view-cycle."
      }
      return existing
    }
    return LithoView(context)
        .apply { componentTree = tree }
        .also { view ->
          cachedView = view
          cachedViewSource = ViewSource.GET
        }
  }

  /**
   * Attach the host's tree to a caller-supplied [LithoView] — typically one inflated from XML or
   * constructed by hand to set a custom `id`, `layoutParams`, or to be placed inside a programmatic
   * parent layout.
   *
   * Mutually exclusive with [getLithoView] within a view-cycle: calling one after the other throws.
   *
   * If [setLithoContent] has not been called for the surface yet, the tree is built with an
   * internal [MissingLithoContentComponent] placeholder so [view] can still be bound and a visible
   * debug message is rendered. A subsequent [setLithoContent] call swaps the placeholder out via
   * [ComponentTree.setRootAsync].
   */
  fun bindLithoViewTo(view: LithoView) {
    checkNotCleared("bindLithoViewTo")
    val context = currentContext("bindLithoViewTo")
    val viewOwner = currentViewOwner("bindLithoViewTo")
    val tree = ensureTree(context, "bindLithoViewTo")
    bindViewLifecycle(viewOwner)
    check(cachedView == null) {
      "[$key] bindLithoViewTo() called when a LithoView is already bound for this view-cycle " +
          "(prior source: $cachedViewSource). Call exactly one of bindLithoViewTo/getLithoView " +
          "per view-cycle."
    }
    view.componentTree = tree
    cachedView = view
    cachedViewSource = ViewSource.BIND
  }

  // Returns the existing tree if one has been built; otherwise builds a tree with the placeholder
  // component so the caller can still bind a LithoView. latestContent is intentionally left null —
  // it tracks user-supplied content only, so any future forced-refresh path falls back through
  // this same code on the next bind.
  private fun ensureTree(context: Context, operation: String): ComponentTree {
    componentTree?.let {
      return it
    }
    val instanceOwner = currentInstanceOwner(operation)
    bindInstanceLifecycle(instanceOwner)
    bindSavedStateRegistry(instanceOwner, context.classLoader)
    DebugInfoReporter.report(category = "LithoSurface:MissingContent", level = LogLevel.WARNING) {
      this["surfaceKey"] = key
      this["operation"] = operation
    }
    val tree = buildTree(context, instanceOwner, MissingLithoContentComponent(key))
    componentTree = tree
    return tree
  }

  /**
   * Eagerly release the per-view-cycle binding established by [getLithoView] or [bindLithoViewTo].
   * Detaches the [ComponentTree] from the bound [LithoView] and clears the host's cached reference,
   * making the surface available for a fresh bind within the same view-cycle.
   *
   * Intended for callers that swap LithoViews mid-view-cycle (e.g. mounting a new LithoView in
   * response to a state change and discarding the previous one). Without this, a second
   * [bindLithoViewTo]/[getLithoView] call within the same view-cycle throws because the framework
   * normally only clears the binding on view-owner `onDestroy`.
   *
   * No-op if no LithoView is currently bound.
   */
  fun unbind() {
    checkNotCleared("unbind")
    cachedView?.componentTree = null
    cachedView = null
    cachedViewSource = null
  }

  /**
   * Register an [action] to run when this [LithoViewModel] is cleared on real teardown (NOT on
   * rotation — `ViewModelStore` survives configuration changes).
   *
   * Use for paired side resources whose lifetime should match the surface — e.g.
   * `GraphQLDataSource.close()`, coroutine `Job.cancel()`, listener removal. Actions run in
   * registration order, and every action runs even if an earlier one throws. After all actions have
   * run, the first exception is reported as an error through [ErrorReporter] under
   * [CLOSE_ACTION_FAILED_CATEGORY], with later ones attached as suppressed.
   */
  fun onSurfaceClose(action: () -> Unit) {
    checkNotCleared("onSurfaceClose")
    closeActions += action
  }

  /**
   * Drive the surface's visibility hint independently of lifecycle events. Useful for ViewPager-
   * style hosting where a page fragment is attached and resumed but is currently off-screen, or
   * vice versa. Forwards to the surface's [AOSPLithoVisibilityEventsController] so the
   * lifecycle-driven hint isn't dropped (LithoView's own `setVisibilityHint` is suppressed when a
   * controller is wired).
   */
  fun setSurfaceVisibilityHint(isVisible: Boolean) {
    checkNotCleared("setSurfaceVisibilityHint")
    visibilityController?.moveToVisibilityState(
        if (isVisible) LithoVisibilityState.HINT_VISIBLE else LithoVisibilityState.HINT_INVISIBLE,
    )
  }

  override fun onCleared() {
    cleared = true
    componentTree?.release()
    componentTree = null
    cachedView?.componentTree = null
    cachedView = null
    cachedViewSource = null
    visibilityController = null
    detachInstanceLifecycle()
    detachViewLifecycle()
    contextProvider = null
    instanceOwnerProvider = null
    viewOwnerProvider = null
    latestContent = null
    savedTreeState = null
    componentSaveableStateRegistry?.close()
    componentSaveableStateRegistry = null
    val toRun = closeActions.toList()
    closeActions.clear()
    var failure: Throwable? = null
    for (action in toRun) {
      try {
        action()
      } catch (t: Throwable) {
        val first = failure
        if (first == null) failure = t else first.addSuppressed(t)
      }
    }
    failure?.let { cause ->
      ErrorReporter.report(
          LogLevel.ERROR,
          CLOSE_ACTION_FAILED_CATEGORY,
          "[$key] onSurfaceClose action threw during onCleared",
          cause,
      )
    }
  }

  // --------------------------- Internal binding ------------------------------

  /**
   * Register pull-on-demand providers for the current owners. Called by the extension API and the
   * `lithoViewModel` factories. The providers are invoked lazily on each public-method call so the
   * VM always sees the current fragment/activity/view-cycle even after rotation.
   */
  internal fun bind(
      contextProvider: () -> Context,
      instanceOwnerProvider: () -> LifecycleOwner,
      viewOwnerProvider: () -> LifecycleOwner,
  ) {
    this.contextProvider = contextProvider
    this.instanceOwnerProvider = instanceOwnerProvider
    this.viewOwnerProvider = viewOwnerProvider
  }

  // --------------------------- Internal helpers ------------------------------

  private fun currentContext(operation: String): Context {
    val provider =
        checkNotNull(contextProvider) {
          "[$key] $operation() called on a LithoViewModel that has not been bound to an owner. " +
              "This typically means the VM was constructed outside the lithoViewModel(...) factory."
        }
    return provider()
  }

  private fun currentInstanceOwner(operation: String): LifecycleOwner {
    val provider =
        checkNotNull(instanceOwnerProvider) {
          "[$key] $operation() called on a LithoViewModel that has not been bound to an owner. " +
              "This typically means the VM was constructed outside the lithoViewModel(...) factory."
        }
    return provider()
  }

  private fun currentViewOwner(operation: String): LifecycleOwner {
    val provider =
        checkNotNull(viewOwnerProvider) {
          "[$key] $operation() called on a LithoViewModel that has not been bound to a view-cycle " +
              "owner. This typically means the call happened outside Fragment.onCreateView (where " +
              "viewLifecycleOwner is available)."
        }
    return provider()
  }

  private fun checkNotCleared(operation: String) {
    check(!cleared) {
      "[$key] $operation() called after the LithoViewModel was cleared (real teardown). " +
          "The owning surface no longer exists; create a new owner if you need to host this " +
          "surface again."
    }
  }

  private fun bindInstanceLifecycle(owner: LifecycleOwner) {
    if (instanceLifecycleOwner === owner) return
    // Detach the prior observer (if any) before binding the new owner — otherwise the prior
    // observer's onDestroy would fire later and release the tree the new owner is using.
    detachInstanceLifecycle()
    instanceLifecycleOwner = owner
    val observer =
        object : DefaultLifecycleObserver {
          override fun onDestroy(owner: LifecycleOwner) {
            if (instanceLifecycleOwner !== owner) {
              owner.lifecycle.removeObserver(this)
              return
            }
            componentTree?.let { tree ->
              savedTreeState = tree.acquireTreeState()
              tree.release()
            }
            componentTree = null
            visibilityController = null
            cachedView?.componentTree = null
            cachedView = null
            cachedViewSource = null
            unbindSavedStateRegistry(owner)
            instanceLifecycleOwner = null
            instanceLifecycleObserver = null
            owner.lifecycle.removeObserver(this)
          }
        }
    instanceLifecycleObserver = observer
    owner.lifecycle.addObserver(observer)
  }

  private fun detachInstanceLifecycle() {
    val owner = instanceLifecycleOwner
    val observer = instanceLifecycleObserver
    if (owner != null && observer != null) {
      owner.lifecycle.removeObserver(observer)
    }
    unbindSavedStateRegistry(owner)
    instanceLifecycleOwner = null
    instanceLifecycleObserver = null
  }

  private fun bindViewLifecycle(owner: LifecycleOwner) {
    if (viewLifecycleOwner === owner) return
    detachViewLifecycle()
    viewLifecycleOwner = owner
    val observer =
        object : DefaultLifecycleObserver {
          override fun onResume(owner: LifecycleOwner) {
            if (viewLifecycleOwner !== owner) {
              owner.lifecycle.removeObserver(this)
              return
            }
            cachedView?.notifyVisibleBoundsChanged(VisibilityChangeReason.LIFECYCLE)
          }

          override fun onDestroy(owner: LifecycleOwner) {
            if (viewLifecycleOwner !== owner) {
              owner.lifecycle.removeObserver(this)
              return
            }
            cachedView?.componentTree = null
            cachedView = null
            cachedViewSource = null
            viewLifecycleOwner = null
            viewLifecycleObserver = null
            owner.lifecycle.removeObserver(this)
          }
        }
    viewLifecycleObserver = observer
    owner.lifecycle.addObserver(observer)
  }

  private fun detachViewLifecycle() {
    val owner = viewLifecycleOwner
    val observer = viewLifecycleObserver
    if (owner != null && observer != null) {
      owner.lifecycle.removeObserver(observer)
    }
    viewLifecycleOwner = null
    viewLifecycleObserver = null
  }

  private fun buildTree(
      context: Context,
      instanceOwner: LifecycleOwner,
      initial: Component,
  ): ComponentTree {
    val controller = AOSPLithoVisibilityEventsController(instanceOwner)
    visibilityController = controller
    val builder =
        ComponentTree.create(
                ComponentContext(context, treePropContainer = treePropsFactory?.invoke()),
                initial,
            )
            .withLithoVisibilityEventsController(controller)
            .withLifecycleOwnerTreeProp(instanceOwner)
    if (instanceOwner is SavedStateRegistryOwner) {
      builder.withRetainedSaveableStateRegistryForTreeReplacement(
          checkNotNull(componentSaveableStateRegistry) {
            "[$key] Saved-state registry was not bound before building the ComponentTree"
          },
      )
    }
    componentsConfiguration?.let { config -> builder.componentsConfiguration(config) }
    savedTreeState?.let { state ->
      builder.treeState(state)
      savedTreeState = null
    }
    return builder.build()
  }

  private fun bindSavedStateRegistry(
      owner: LifecycleOwner,
      classLoader: ClassLoader,
  ) {
    val registryOwner = owner as? SavedStateRegistryOwner
    if (registryOwner == null) {
      error("[$key] Litho content requires an owner that implements SavedStateRegistryOwner")
    }
    if (savedStateRegistryOwner === registryOwner) return
    check(registryOwner.savedStateRegistry.isRestored) {
      "[$key] Litho content must be set after the owner's saved state is restored. " +
          "For an Activity, call it after super.onCreate()."
    }
    val registry =
        componentSaveableStateRegistry
            ?: LithoSaveableStateRegistry(
                restoredState =
                    registryOwner.savedStateRegistry.consumeRestoredStateForKey(
                        saveableStateRegistryKey,
                    ),
                valueClassLoader = classLoader,
            )
                .also { componentSaveableStateRegistry = it }
    unbindSavedStateRegistry(owner = null)
    registryOwner.savedStateRegistry.registerSavedStateProvider(saveableStateRegistryKey) {
      registry.snapshot()
    }
    savedStateRegistryOwner = registryOwner
  }

  private fun unbindSavedStateRegistry(owner: LifecycleOwner?) {
    val registryOwner = savedStateRegistryOwner ?: return
    if (owner != null && registryOwner !== owner) return
    registryOwner.savedStateRegistry.unregisterSavedStateProvider(saveableStateRegistryKey)
    savedStateRegistryOwner = null
  }

  companion object {
    private const val SAVEABLE_STATE_KEY_PREFIX: String = "litho_saveable_state"

    /** [ErrorReporter] category for a failed [onSurfaceClose] action. */
    const val CLOSE_ACTION_FAILED_CATEGORY: String = "LITHO:SURFACE:CLOSE_ACTION_FAILED"
  }
}

internal class LithoViewModelFactory(
    private val key: String,
    private val componentsConfiguration: ComponentsConfiguration?,
    private val treePropsFactory: (() -> TreePropContainer?)?,
    private val isExplicitlyConfigured: Boolean,
) : ViewModelProvider.Factory {

  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    require(modelClass.isAssignableFrom(LithoViewModel::class.java)) {
      "LithoViewModelFactory can only create LithoViewModel instances, was asked for ${modelClass.name}"
    }
    return LithoViewModel(
        key = key,
        componentsConfiguration = componentsConfiguration,
        treePropsFactory = treePropsFactory,
        isExplicitlyConfigured = isExplicitlyConfigured,
    )
        as T
  }
}
