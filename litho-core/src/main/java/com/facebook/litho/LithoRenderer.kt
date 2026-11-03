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

package com.facebook.litho

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.annotation.AnyThread
import androidx.annotation.GuardedBy
import androidx.annotation.UiThread
import com.facebook.litho.NestedLithoTree.cleanup
import com.facebook.litho.NestedLithoTree.commit
import com.facebook.litho.NestedLithoTree.enqueue
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.RunnableHandlerProvider
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.effects.EffectContext
import com.facebook.litho.state.StateId
import com.facebook.litho.state.TreeStateProvider
import com.facebook.rendercore.Host
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.RunnableHandler.DefaultHandler
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.utils.equals
import java.util.concurrent.atomic.AtomicReference

/** A renderer for Litho components that handles RESOLVE and LAYOUT. */
class LithoRenderer(
    val context: Context,
    val id: Int = LithoTree.generateComponentTreeId(),
    val componentsConfig: ComponentsConfiguration = ComponentsConfiguration.defaultInstance,
    val renderUnitIdGenerator: RenderUnitIdGenerator = RenderUnitIdGenerator(id),
    val treeState: TreeState? = TreeState(),
    val parentTreePropContainer: TreePropContainer? = TreePropContainer(),
    val treePropContainer: TreePropContainer? = TreePropContainer(),
    runnableHandlerProvider: RunnableHandlerProvider = ComponentTree.DefaultLayoutHandlerProvider(),
    val visibilityController: LithoVisibilityEventsController? = null,
    /* this is only set by the containers for child renderers */
    val synchronizer: UpdateSynchronizer? = null,
) :
    TreeStateProvider,
    StateUpdater,
    StateUpdateRequester,
    RootHostProvider,
    ErrorComponentReceiver {

  // todo Rename this class to LithoTreeContext
  private val lithoConfiguration: LithoConfiguration = LithoConfiguration(
      context = context,
      componentsConfig = componentsConfig,
      renderUnitIdGenerator = renderUnitIdGenerator,
  )
  private val combinedTreePropContainer: TreePropContainer? =
      mergeTreePropContainersIfNeeded(parentTreePropContainer, treePropContainer)
  private val lithoTree: LithoTree = LithoTree(
      id = id,
      treeStateProvider = this,
      stateUpdater = this,
      rootHost = this,
      errorComponentReceiver = this,
      treeLifecycle = NoOpLifecycleProvider(),
      uiStateReadRecordsProvider = {
        val host =
            rootHost as? BaseMountingView
                ?: error("Trying to get UI state read records without a set host")
        host.uiStateReadRecords
      },
      isReadTrackingEnabled = componentsConfig.enableStateReadTracking,
  )
  private val errorComponentRef = AtomicReference<Component?>(null)
  private val renderHandler = runnableHandlerProvider.getRunnableHandler()

  init {
    treeState?.clearResolveUpdates()
  }

  /** The root component that this renderer is responsible for rendering. */
  @GuardedBy("this") @Volatile private var root: Component = EmptyComponent()
  /** The size constraints used for layout calculations. */
  @GuardedBy("this")
  @Volatile
  var sizeConstraints: SizeConstraints? = null
    private set

  @GuardedBy("this")
  @Volatile
  private var currentLifecycle: LithoRendererLifecycle = LithoRendererLifecycle.INITIALIZED

  private val resolveFutureLock = Any()
  @GuardedBy("resolveFutureLock")
  private val resolveTreeFutures = arrayListOf<LithoResolveTreeFuture>()

  private val layoutFutureLock = Any()
  @GuardedBy("layoutFutureLock")
  private val layoutTreeFutures = arrayListOf<LithoLayoutTreeFuture>()

  private val runnableLock = Any()
  @GuardedBy("runnableLock") private var currentLayoutRunnable: DoLayoutRunnable? = null
  @GuardedBy("runnableLock") private var currentResolveRunnable: DoResolveRunnable? = null
  @GuardedBy("runnableLock") private var currentRebindRunnable: Runnable? = null

  @GuardedBy("this") private var nextResolveVersionCounter: Int = 0
  @GuardedBy("this") private var nextLayoutVersionCounter: Int = 0

  @GuardedBy("this") @Volatile private var currentResolveResult: ResolveResult? = null
  @GuardedBy("this") @Volatile var currentLayoutState: LayoutState? = null

  private var rootHost: Host? = null

  // This is written to only by the main thread with the lock held, read from the main thread with
  // no lock held, or read from any other thread with the lock held.
  private var uiThreadLayoutState: LayoutState? = null

  // Whether [uiThreadLayoutState] was bound without attaching its effects. Main thread only.
  private var uiThreadLayoutStateEffectsSkipped: Boolean = false

  // region Public APIs

  /**
   * Initiates an asynchronous resolution process for a component.
   *
   * @param root The root component to resolve.
   */
  fun resolve(root: Component) {
    if (checkIfCanSkipResolveAndUpdateInputs(root)) {
      return
    }

    resolveAsync(RenderMode.SetRoot)
  }

  /**
   * Performs an asynchronous layout calculation with the given constraints. Uses the current
   * resolve result if available, otherwise performs an async resolve first.
   *
   * @param constraints The size constraints to use for layout.
   */
  fun layout(constraints: SizeConstraints) {
    if (checkIfCanSkipLayoutAndUpdateInputs(constraints)) {
      return
    }

    if (currentResolveResult != null) {
      layoutAsync(RenderMode.SetConstraints)
    } else {
      resolveAsync(RenderMode.SetConstraints)
    }
  }

  /**
   * Performs a synchronous layout calculation with the given constraints. Uses the current resolve
   * result if available, otherwise performs an inline resolve.
   *
   * @param constraints The size constraints to use for layout.
   * @return The resulting layout state.
   */
  fun layoutSync(constraints: SizeConstraints): LayoutState {

    val resolveInputs =
        captureResolveInputs(RenderMode.SetConstraintsSync, constraints = constraints)

    val resolveResult = resolveInputs.currentResolveResult ?: run { resolveSync(resolveInputs) }

    val layoutInputs = deriveFromResolveInputs(resolveInputs, resolveResult)

    return layoutSync(layoutInputs)
  }

  /**
   * Performs an asynchronous resolution and layout in one call, which is a non-blocking operation.
   *
   * @param root The root component to render.
   * @param constraints The size constraints to use.
   */
  fun render(root: Component, constraints: SizeConstraints) {
    if (checkIfCanSkipRenderAndUpdateInputs(newRoot = root, newConstraints = constraints)) {
      return
    }

    resolveAsync(RenderMode.SetRoot)
  }

  /**
   * Performs a synchronous resolution and layout in one call. This is a blocking operation that
   * completes the entire rendering pipeline.
   *
   * @param root The root component to render.
   * @param constraints The size constraints to use.
   * @return The resulting layout state.
   */
  fun renderSync(root: Component, constraints: SizeConstraints): LayoutState {

    val resolveInputs = captureResolveInputs(RenderMode.SetRootSync, root, constraints)

    val resolveResult = resolveSync(resolveInputs)

    val layoutInputs = deriveFromResolveInputs(resolveInputs, resolveResult)

    return layoutSync(layoutInputs)
  }

  /** Cleans up any pending resolve or layout operations and resets the renderer state. */
  @AnyThread
  fun cleanup() {
    synchronized(runnableLock) {
      currentResolveRunnable?.let { runnable ->
        renderHandler.remove(runnable)
        currentResolveRunnable = null
      }
    }
    synchronized(runnableLock) {
      currentLayoutRunnable?.let { runnable ->
        renderHandler.remove(runnable)
        currentLayoutRunnable = null
      }
    }
    synchronized(runnableLock) {
      currentRebindRunnable?.let { runnable ->
        uiThreadHandler.remove(runnable)
        currentRebindRunnable = null
      }
    }
    synchronized(this) {
      currentLifecycle = LithoRendererLifecycle.INITIALIZED
      currentResolveResult = null
      currentLayoutState = null
      root = EmptyComponent()
      sizeConstraints = null
      errorComponentRef.set(null)
    }
  }

  @UiThread
  fun renderSyncWithCompatibilityChecks(root: Component, sizeConstraint: SizeConstraints) {
    if (this.sizeConstraints != null && sizeConstraint != this.sizeConstraints) {
      DebugInfoReporter.report(category = "Collection") {
        this["event"] = "InvalidSizeConstraints"
        this["originalConstraints"] = "${this@LithoRenderer.sizeConstraints}"
        this["newConstraints"] = "$sizeConstraint"
        this["component"] = root.simpleName
      }
    }
    rebind(renderSync(root, sizeConstraint))
  }

  /**
   * Shows the last committed layout if it was computed for [constraints] and renders [root] in the
   * background, unless that layout is already for [root]. Returns false when nothing compatible can
   * be shown, in which case the caller must render synchronously.
   *
   * @param postAtFront posts the background render ahead of other queued work. Only safe on a
   *   handler the caller owns: on a shared layout thread it would jump ahead of other trees.
   */
  @UiThread
  fun renderAsyncKeepingCommittedLayout(
      root: Component,
      constraints: SizeConstraints,
      postAtFront: Boolean = false,
  ): Boolean {
    val committed = currentLayoutState
    if (committed == null || committed.sizeConstraints != constraints) {
      return false
    }
    val committedIsCurrent = ComponentUtils.isEquivalent(committed.rootComponent, root)
    // A layout for an older root is only shown until [root] commits, so its effects aren't
    // attached:
    // they'd run for a component that is being replaced, and ones with unchanged deps would outlive
    // it.
    rebind(committed, runEffects = committedIsCurrent)
    if (uiThreadLayoutState?.sizeConstraints != constraints) {
      return false
    }
    // The root went back to the shown layout's (e.g. a change rolled back before it rendered), so
    // that layout won't be rebound and its skipped effects have to be attached here.
    if (
        committedIsCurrent && committed === uiThreadLayoutState && uiThreadLayoutStateEffectsSkipped
    ) {
      attachSkippedEffects(committed)
    }
    val inputsChanged = !checkIfCanSkipRenderAndUpdateInputs(root, constraints)
    if (inputsChanged || !committedIsCurrent) {
      // Always re-post: a previously queued render may never commit.
      resolveAsync(RenderMode.SetRoot, atFront = postAtFront)
    }
    return true
  }

  @UiThread
  fun bindTo(host: Host) {
    rootHost = host
    synchronized(this) { currentLifecycle = LithoRendererLifecycle.STARTED }
  }

  @UiThread
  fun unbind() {
    rootHost = null
    synchronized(this) {
      currentLifecycle = LithoRendererLifecycle.PAUSED
      uiThreadLayoutState?.cleanup()
      uiThreadLayoutState = null
      uiThreadLayoutStateEffectsSkipped = false
    }
  }

  // endregion

  @UiThread
  @Synchronized
  private fun attachSkippedEffects(layoutState: LayoutState) {
    uiThreadLayoutStateEffectsSkipped = false
    val effectContext = EffectContext(id, componentsConfig.enableStateReadTracking, emptySet())
    with(layoutState.treeState.effectsHandler) {
          effectContext.updateEffects(layoutState.attachables)
        }
        .run()
  }

  private fun commitAndRunEffects(layoutState: LayoutState, updatedStates: Set<StateId>) {
    val effectContext = EffectContext(id, componentsConfig.enableStateReadTracking, updatedStates)
    var effectRunnable: Runnable? = null
    try {
      with(layoutState.treeState.effectsHandler) {
        effectRunnable = effectContext.updateEffects(layoutState.attachables)
      }
      layoutState.commit()
    } finally {
      effectRunnable?.run()
    }
  }

  /**
   * Rebinds the layout state to the UI by promoting it to the UI thread and invoking the view
   * binder. This method ensures the layout state is properly committed and then triggers the view
   * binding process.
   *
   * @param layoutState The layout state to rebind to the UI
   */
  @UiThread private fun rebind(layoutState: LayoutState) = rebind(layoutState, runEffects = true)

  @UiThread
  @Synchronized
  private fun rebind(layoutState: LayoutState, runEffects: Boolean) {
    if (layoutState === uiThreadLayoutState) return

    val previousUiThreadLayoutState = uiThreadLayoutState
    if (
        LayoutState.isNullOrEmpty(previousUiThreadLayoutState) &&
            LayoutState.isNullOrEmpty(layoutState)
    ) {
      return
    }
    if (
        previousUiThreadLayoutState != null &&
            layoutState.version <= previousUiThreadLayoutState.version
    ) {
      return
    }
    if (rootHost is LithoRenderTreeView) {
      uiThreadLayoutState = layoutState
      uiThreadLayoutStateEffectsSkipped = !runEffects

      // Compute effective updates
      val mainState = treeState
      val effectiveSnapshot =
          if (componentsConfig.enableStateReadTracking) {
            mainState?.getUpdatesForPromotion(layoutState.treeState) ?: emptyMap()
          } else {
            emptyMap()
          }
      val updatedStates = effectiveSnapshot.keys

      if (runEffects) {
        commitAndRunEffects(layoutState, updatedStates)
      } else {
        layoutState.commit()
      }

      // Carry forward unconsumed mount updates from previous layout
      val previousUnconsumed =
          previousUiThreadLayoutState?.treeState?.consumeMountUpdates() ?: emptySet()
      layoutState.treeState.addMountUpdates(updatedStates + previousUnconsumed)

      // Subtract from main TreeState
      if (effectiveSnapshot.isNotEmpty()) {
        mainState?.subtractPromotedUpdates(effectiveSnapshot)
      }

      // Ensure the layout state is bound to the view if rootHost is available.
      (rootHost as LithoRenderTreeView).setLayoutState(layoutState, layoutState.treeState)
    }
  }

  /**
   * Determines whether a render operation can be skipped based on whether the root component or
   * size constraints have changed. Updates the internal state with new values if they differ.
   *
   * @param newRoot The new root component to compare against the current root
   * @param newConstraints The new size constraints to compare against current constraints
   * @return true if the render can be skipped (no changes detected), false if a render is needed
   */
  @Synchronized
  private fun checkIfCanSkipRenderAndUpdateInputs(
      newRoot: Component,
      newConstraints: SizeConstraints,
  ): Boolean {
    val canSkip =
        ComponentUtils.isEquivalent(this.root, newRoot) && this.sizeConstraints == newConstraints
    if (!canSkip) {
      this.root = newRoot
      this.sizeConstraints = newConstraints
    }
    return canSkip
  }

  /**
   * Determines whether a resolve operation can be skipped based on whether the root component has
   * changed. Updates the internal state with the new root if it differs.
   *
   * @param newRoot The new root component to compare against the current root
   * @return true if the resolve can be skipped (no changes detected), false if a resolve is needed
   */
  @Synchronized
  private fun checkIfCanSkipResolveAndUpdateInputs(newRoot: Component): Boolean {
    val canSkip = ComponentUtils.isEquivalent(this.root, newRoot)
    if (!canSkip) {
      this.root = newRoot
    }
    return canSkip
  }

  /**
   * Determines whether a layout operation can be skipped based on whether the size constraints have
   * changed. Updates the internal state with the new constraints if they differ.
   *
   * @param newConstraints The new size constraints to compare against current constraints
   * @return true if the layout can be skipped (no changes detected), false if a layout is needed
   */
  @Synchronized
  private fun checkIfCanSkipLayoutAndUpdateInputs(newConstraints: SizeConstraints): Boolean {
    val canSkip = this.sizeConstraints == newConstraints
    if (!canSkip) {
      this.sizeConstraints = newConstraints
    }
    return canSkip
  }

  /** Creates a new [ComponentContext] for the resolution. */
  private fun createComponentContext(
      treePropContainer: TreePropContainer?,
  ): ComponentContext = ComponentContext(
      context,
      treePropContainer,
      lithoConfiguration,
      lithoTree,
      StringGlobalKey(ROOT_KEY),
      visibilityController,
      null,
      parentTreePropContainer,
  )

  /** Performs a synchronous state update. */
  private fun updateStateSync() {
    val resolveInputs = captureResolveInputs(RenderMode.StateUpdateSync)

    val resolveResult = resolveSync(resolveInputs)

    val layoutInputs = deriveFromResolveInputs(resolveInputs, resolveResult)

    layoutSync(layoutInputs)
  }

  /** Performs an asynchronous state update. */
  private fun updateState() {
    resolveAsync(RenderMode.StateUpdate)
  }

  /** Applies any pending state updates that have been enqueued. */
  private fun applyPendingUpdates(isAsync: Boolean) {
    if (isAsync) {
      updateState()
    } else {
      updateStateSync()
    }
  }

  /** Initiates a resolve for the given component in an async way. */
  private fun resolveAsync(renderMode: RenderMode, atFront: Boolean = false) {
    synchronized(runnableLock) {
      // clear pending resolve runnable
      currentResolveRunnable?.let { runnable ->
        renderHandler.remove(runnable)
        currentResolveRunnable = null
      }

      val runnable = DoResolveRunnable(renderMode, atFront).also { currentResolveRunnable = it }
      var tag = EMPTY_STRING
      if (renderHandler.isTracing()) {
        tag = "doResolve ${root.simpleName}"
      }
      if (atFront) {
        renderHandler.postAtFront(runnable, tag)
      } else {
        renderHandler.post(runnable, tag)
      }
    }
  }

  /** Initiates a resolve for the given component in a sync way. */
  private fun resolveSync(resolveInputs: ResolveInputs): ResolveResult {
    val resolveResult = requestResolve(resolveInputs)
    return checkNotNull(resolveResult) { "We should always have a result for sync resolve!" }
  }

  /** Requests a component resolution with a new version number. */
  private fun requestResolve(resolveInputs: ResolveInputs): ResolveResult? {
    val isSameRoot =
        (resolveInputs.currentResolveResult != null) &&
            ComponentUtils.isEquivalent(
                resolveInputs.rootComponent,
                resolveInputs.currentResolveResult.component,
            )
    val noPendingStateUpdate = resolveInputs.localTreeState.keysForPendingStateUpdates.isEmpty()

    // The current root and tree-props are the same as the committed resolved result, and there is
    // no pending state update. Therefore, there is no need to calculate the resolved result again,
    // and we can proceed straight to layout.
    //
    // Comparing two tree-prop containers walks the map and runs arbitrary value equals, so it has
    // to stay behind the cheaper checks instead of being hoisted into its own val.
    if (
        isSameRoot &&
            noPendingStateUpdate &&
            equals(
                resolveInputs.treePropContainer,
                resolveInputs.currentResolveResult?.context?.treePropContainer,
            )
    ) {
      return resolveInputs.currentResolveResult
    }
    return resolve(resolveInputs, MAX_RESOLVE_TIMES)
  }

  /** Initiates an async layout with the latest resolve result and size constraints. */
  private fun layoutAsync(renderMode: RenderMode, atFront: Boolean = false) {
    synchronized(runnableLock) {
      // clear pending layout runnable
      currentLayoutRunnable?.let { runnable ->
        renderHandler.remove(runnable)
        currentLayoutRunnable = null
      }
      val runnable = DoLayoutRunnable(renderMode).also { currentLayoutRunnable = it }
      var tag = EMPTY_STRING
      if (renderHandler.isTracing()) {
        tag = "doLayout ${root.simpleName}"
      }
      if (atFront) {
        renderHandler.postAtFront(runnable, tag)
      } else {
        renderHandler.post(runnable, tag)
      }
    }
  }

  /** Initiates a layout for the given resolve result in a sync way. */
  private fun layoutSync(layoutInputs: LayoutInputs): LayoutState {
    val layoutState = requestLayout(layoutInputs)
    return checkNotNull(layoutState) { "We should always have a result for sync layout!" }
  }

  /** Initiates a layout with a new version number. */
  private fun requestLayout(layoutInputs: LayoutInputs): LayoutState? {
    layoutInputs.currentResolveResult ?: return null
    layoutInputs.constraints ?: return null

    val isSameResolveResult =
        (layoutInputs.currentResolveResult == layoutInputs.currentLayoutState?.resolveResult)
    val areSizeConstraintsCompatible =
        (layoutInputs.constraints == layoutInputs.currentLayoutState?.sizeConstraints)
    if (isSameResolveResult && areSizeConstraintsCompatible) {
      // If the resolve result and size constraints are the same as the committed layout state, we
      // can reuse the committed layout state.
      return layoutInputs.currentLayoutState
    }

    return layout(layoutInputs)
  }

  /** Performs a resolve future for the given component. */
  private fun resolve(resolveInputs: ResolveInputs, retryTimes: Int): ResolveResult? {
    val componentContext = createComponentContext(resolveInputs.treePropContainer)
    val initialStateScope = InitialStateScope.forResolve(resolveInputs.localTreeState)
    val future = LithoResolveTreeFuture(
        componentContext = componentContext,
        treeId = id,
        component = resolveInputs.rootComponent,
        treeState = resolveInputs.localTreeState,
        previousResult = resolveInputs.currentResolveResult,
        version = resolveInputs.resolveVersion,
        useCancellableFutures = resolveInputs.renderMode.isAsync(),
    )

    val treeFutureResult =
        TreeFuture.trackRunAndCommit(
            future,
            resolveTreeFutures,
            resolveInputs.renderMode.toRenderSource(),
            resolveFutureLock,
            null,
            initialStateScope,
        ) { result ->
          commitResolveResult(result)
        }

    return if (treeFutureResult.result != null) {
      treeFutureResult.result
    } else {
      // To check if this is the latest resolve request because we don't want to leave render in
      // flight.
      val isLatestRequest: Boolean =
          synchronized(this) { (resolveInputs.resolveVersion == (nextResolveVersionCounter - 1)) }

      val isWaitingButInterrupted = (TreeFuture.FutureState.WAITING == treeFutureResult.state)
      return if (isWaitingButInterrupted && isLatestRequest && retryTimes > 0) {
        resolve(resolveInputs, retryTimes - 1)
      } else {
        null
      }
    }
  }

  /** Commits the given resolve result if it is newer than the committed resolve result. */
  @Synchronized
  private fun commitResolveResult(resolveResult: ResolveResult) {
    val committedResolveResult = currentResolveResult
    if (committedResolveResult == null || committedResolveResult.version < resolveResult.version) {
      currentResolveResult = resolveResult
      treeState?.commitResolveState(
          resolveResult.treeState,
          trackForPromotion = componentsConfig.enableStateReadTracking,
          resolveVersion = resolveResult.version,
      )
    }
  }

  /** Performs a layout future for the given resolve result. */
  private fun layout(layoutInputs: LayoutInputs): LayoutState? {
    val resolveResult = requireNotNull(layoutInputs.currentResolveResult)
    val constraints = requireNotNull(layoutInputs.constraints)
    val initialStateScope = InitialStateScope.forLayout(layoutInputs.localTreeState)
    val future = LithoLayoutTreeFuture(
        treeId = id,
        version = layoutInputs.layoutVersion,
        resolveResult = resolveResult,
        sizeConstraints = constraints,
        previousLayoutState = layoutInputs.currentLayoutState,
        treeState = layoutInputs.localTreeState,
    )

    val treeFutureResult =
        TreeFuture.trackRunAndCommit(
            future,
            layoutTreeFutures,
            layoutInputs.renderMode.toRenderSource(),
            layoutFutureLock,
            null,
            initialStateScope,
        ) { layoutState ->
          layoutState.toRenderTree()

          if (commitLayoutState(layoutState)) {
            synchronized(runnableLock) {
              currentRebindRunnable?.let { runnable ->
                uiThreadHandler.remove(runnable)
                currentRebindRunnable = null
              }
              val runnable = Runnable { rebind(layoutState) }
              currentRebindRunnable = runnable
              uiThreadHandler.post(runnable, TAG_STATE_UPDATE)
            }
          }
        }

    return treeFutureResult.result
  }

  /**
   * Commits the given layout state if it is newer than the committed layout state and if the
   * constraints are still compatible.
   */
  @Synchronized
  private fun commitLayoutState(layoutState: LayoutState): Boolean {
    val currentVersion = currentLayoutState?.version ?: com.facebook.rendercore.RenderState.UNSET
    if (
        (layoutState.version > currentVersion) &&
            (layoutState.sizeConstraints == sizeConstraints) &&
            (layoutState.resolveResult == currentResolveResult)
    ) {
      currentLayoutState = layoutState
      treeState?.commitLayoutState(
          layoutState.treeState,
          trackForPromotion = componentsConfig.enableStateReadTracking,
          resolveVersion = layoutState.resolveResult.version,
      )
      return true
    }
    return false
  }

  /**
   * Merges the source tree properties with the destination tree properties container. Creates a new
   * TreePropContainer that contains all properties from the destination container and adds any
   * additional properties from the source container.
   *
   * @param destination The destination tree properties container to merge into, can be null
   * @param source The source tree properties to merge, can be null
   * @return A new TreePropContainer containing merged properties from destination and source
   *   containers, or the source container if no merging is needed
   */
  private fun mergeTreePropContainersIfNeeded(
      destination: TreePropContainer?,
      source: TreePropContainer?,
  ): TreePropContainer? {
    val treePropContainer = TreePropContainer.copy(destination) ?: TreePropContainer()
    if (source != null) {
      treePropContainer.putAll(source)
    }
    return if (treePropContainer != source) {
      treePropContainer
    } else {
      source
    }
  }

  override val uiTreeState: TreeState?
    @Synchronized get() = treeState

  override val isBoundToHost: Boolean
    @Synchronized get() = rootHost != null

  // region StateUpdater
  override var isFirstMount: Boolean
    get() = treeState?.mountInfo?.isFirstMount == true
    set(value) {
      treeState?.mountInfo?.isFirstMount = value
    }

  override fun updateStateSync(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    request(
        PendingStateUpdate(
            stateId = StateId.forSpec(id, globalKey),
            updater = stateUpdate,
            isLayoutState = isLayoutState,
            isAsync = false,
            attribution = attribution,
        ),
    )
  }

  override fun updateStateAsync(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    request(
        PendingStateUpdate(
            stateId = StateId.forSpec(id, globalKey),
            updater = stateUpdate,
            isLayoutState = isLayoutState,
            isAsync = true,
            attribution = attribution,
        ),
    )
  }

  override fun updateStateLazy(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      isLayoutState: Boolean,
  ) {
    request(
        PendingStateUpdate(
            stateId = StateId.forSpec(id, globalKey),
            updater = stateUpdate,
            isLayoutState = isLayoutState,
            isAsync = false,
            isLazy = true,
        ),
    )
  }

  override fun updateHookStateAsync(
      stateId: StateId,
      updateBlock: HookUpdater,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    request(
        PendingStateUpdate(
            stateId = stateId,
            updater = updateBlock,
            isLayoutState = isLayoutState,
            isAsync = true,
            attribution = attribution,
        ),
    )
  }

  override fun updateHookStateSync(
      stateId: StateId,
      updateBlock: HookUpdater,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    request(
        PendingStateUpdate(
            stateId = stateId,
            updater = updateBlock,
            isLayoutState = isLayoutState,
            isAsync = false,
            attribution = attribution,
        ),
    )
  }

  @Synchronized
  override fun applyLazyStateUpdatesForContainer(
      globalKey: GlobalKey,
      container: StateContainer,
      isLayoutState: Boolean,
  ): StateContainer {
    return treeState?.applyLazyStateUpdatesForContainer(
        StateId.forSpec(id, globalKey),
        container,
        isLayoutState,
    ) ?: container
  }

  @Synchronized
  override fun getCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      isLayoutState: Boolean,
  ): Any? {
    return treeState?.getCachedValue(cacheKey, cachedValueInputs, isLayoutState)
  }

  @Synchronized
  override fun putCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      cachedValue: Any?,
      isLayoutState: Boolean,
  ) {
    treeState?.putCachedValue(cacheKey, cachedValueInputs, cachedValue, isLayoutState)
  }

  override fun removePendingStateUpdate(key: GlobalKey, isLayoutState: Boolean) {
    throw UnsupportedOperationException(
        """
        |This API should not be invoked. Nested Litho Tree updates will
        |be cleared when nested layout state is committed.
        """
            .trimMargin(),
    )
  }

  override fun <T> canSkipStateUpdate(
      stateId: StateId,
      newValue: T?,
      isLayoutState: Boolean,
  ): Boolean {
    return treeState?.canSkipStateUpdate(stateId, newValue, isLayoutState) == true
  }

  override fun <T> canSkipStateUpdate(
      newValueFunction: (T) -> T,
      stateId: StateId,
      isLayoutState: Boolean,
  ): Boolean {
    return treeState?.canSkipStateUpdate(
        newValueFunction,
        stateId,
        isLayoutState,
    ) == true
  }

  override fun getEventTrigger(key: Any): EventTrigger<*>? {
    return treeState?.getEventTrigger(triggerKey = key)
  }

  override fun getEventTrigger(handle: Handle, id: Int): EventTrigger<*>? {
    return treeState?.getEventTrigger(handle = handle, methodId = id)
  }

  // endregion

  // region StateUpdateRequester
  override fun request(update: PendingStateUpdate) {
    if (currentLifecycle != LithoRendererLifecycle.STARTED) {
      // When the current view is out of the preparation range, we don't want to apply any state
      // updates.
      return
    }
    synchronized(this) { treeState?.enqueue(update) }

    if (update.isLazy) {
      // todo: figure out how to handle lazy updates properly
      return
    }

    if (synchronizer != null) {
      synchronizer.request(update.isAsync)
    } else {
      applyPendingUpdates(update.isAsync)
    }
  }

  // endregion

  // region ErrorComponentReceiver
  override fun onErrorComponent(component: Component?) {
    errorComponentRef.compareAndSet(null, component)
  }

  // endregion

  // region RootHostProvider
  override val mountedView: View?
    get() = rootHost

  // endregion

  /**
   * A runnable that executes the resolve operation on a background thread. This class encapsulates
   * the parameters needed for component resolution and delegates to the doResolve method when
   * executed.
   *
   * @param renderMode The mode that determines if the operation is synchronous or asynchronous
   */
  private inner class DoResolveRunnable(
      private val renderMode: RenderMode,
      private val atFront: Boolean = false,
  ) : ThreadTracingRunnable() {

    override fun tracedRun() {
      val result = requestResolve(captureResolveInputs(renderMode))
      if (result != null) {
        // Resolve operation may be interrupted by cancellation or errors.
        // Verify the result is valid before proceeding to the layout phase.
        layoutAsync(renderMode, atFront)
      }
    }
  }

  /**
   * A runnable that executes the layout operation on a background thread, using the latest resolve
   * result and size constraints.
   *
   * @param renderMode The mode that determines if the operation is synchronous or asynchronous
   */
  private inner class DoLayoutRunnable(
      private val renderMode: RenderMode,
  ) : ThreadTracingRunnable() {

    override fun tracedRun() {
      requestLayout(captureLayoutInputs(renderMode))
    }
  }

  /** Captures all inputs for Resolve. */
  private fun captureResolveInputs(
      renderMode: RenderMode,
      rootComponent: Component? = null,
      constraints: SizeConstraints? = null,
  ): ResolveInputs {
    return synchronized(this) {
      rootComponent?.let { root = it }
      constraints?.let { sizeConstraints = it }
      ResolveInputs(
          resolveVersion = nextResolveVersionCounter++,
          layoutVersion = nextLayoutVersionCounter++,
          rootComponent = errorComponentRef.getAndSet(null) ?: root,
          constraints = constraints ?: sizeConstraints,
          localTreeState = TreeState(treeState),
          currentResolveResult = currentResolveResult,
          treePropContainer = combinedTreePropContainer,
          renderMode = renderMode,
      )
    }
  }

  /** Captures all inputs for Layout. */
  private fun captureLayoutInputs(renderMode: RenderMode): LayoutInputs {
    return synchronized(this) {
      val resolveResult = currentResolveResult
      val layoutState = currentLayoutState
      LayoutInputs(
          layoutVersion = nextLayoutVersionCounter++,
          currentResolveResult = resolveResult,
          currentLayoutState = layoutState,
          // Same resolve, new constraints: continue from the last committed layout, which holds
          // layout-scoped state created since the resolve snapshot was taken.
          localTreeState =
              if (layoutState != null && layoutState.resolveResult === resolveResult) {
                TreeState.forLayoutOnlyPass(layoutState.treeState, resolveResult?.treeState)
              } else {
                TreeState(resolveResult?.treeState)
              },
          constraints = sizeConstraints,
          renderMode = renderMode,
      )
    }
  }

  /** Derive layout inputs from a resolve inputs. */
  private fun deriveFromResolveInputs(
      resolveInputs: ResolveInputs,
      resolveResult: ResolveResult,
  ): LayoutInputs {
    return synchronized(this) {
      LayoutInputs(
          layoutVersion = resolveInputs.layoutVersion,
          currentResolveResult = resolveResult,
          currentLayoutState = currentLayoutState,
          localTreeState = TreeState(resolveResult.treeState),
          constraints = resolveInputs.constraints ?: sizeConstraints,
          renderMode = resolveInputs.renderMode,
      )
    }
  }

  /** A class that captures all inputs for Resolve. */
  private class ResolveInputs(
      val resolveVersion: Int,
      val layoutVersion: Int,
      val rootComponent: Component,
      val constraints: SizeConstraints?,
      val localTreeState: TreeState,
      val currentResolveResult: ResolveResult?,
      val treePropContainer: TreePropContainer?,
      val renderMode: RenderMode,
  )

  /** A class that captures all inputs for Layout. */
  private class LayoutInputs(
      val layoutVersion: Int,
      val currentResolveResult: ResolveResult?,
      val currentLayoutState: LayoutState?,
      val localTreeState: TreeState,
      val constraints: SizeConstraints?,
      val renderMode: RenderMode,
  )

  companion object {
    private val uiThreadHandler: RunnableHandler = DefaultHandler(Looper.getMainLooper())

    // The max number of times we will retry a failed resolve request.
    private const val MAX_RESOLVE_TIMES: Int = 2

    private const val EMPTY_STRING: String = ""
    private const val ROOT_KEY: String = "TreeRoot"
    private const val TAG_STATE_UPDATE: String = "StateUpdate"
  }
}

/** A no-op implementation of [LithoTreeLifecycleProvider] that does nothing. */
private class NoOpLifecycleProvider : LithoTreeLifecycleProvider {

  override val isReleased: Boolean
    get() = false

  override fun addOnReleaseListener(listener: LithoTreeLifecycleProvider.OnReleaseListener) {
    // no ops
  }

  override fun removeOnReleaseListener(listener: LithoTreeLifecycleProvider.OnReleaseListener) {
    // no ops
  }
}

@JvmInline
value class LithoRendererLifecycle(val value: Int) {
  companion object {
    val INITIALIZED: LithoRendererLifecycle = LithoRendererLifecycle(0)
    val STARTED: LithoRendererLifecycle = LithoRendererLifecycle(1)
    val PAUSED: LithoRendererLifecycle = LithoRendererLifecycle(2)
  }
}

fun interface UpdateSynchronizer {
  fun request(isAsync: Boolean)
}

/** A class that represents the trigger type of the render call. */
@JvmInline
value class RenderMode private constructor(val value: Int) {

  /** Returns true if the given source is an asynchronous operation. */
  fun isAsync(): Boolean {
    return this == SetRoot || this == SetConstraints || this == StateUpdate || this == Measure
  }

  fun toRenderSource(): Int {
    return when (this) {
      SetRootSync -> RenderSource.SET_ROOT_SYNC
      SetRoot -> RenderSource.SET_ROOT_ASYNC
      SetConstraintsSync -> RenderSource.SET_SIZE_SPEC_SYNC
      SetConstraints -> RenderSource.SET_SIZE_SPEC_ASYNC
      StateUpdateSync -> RenderSource.UPDATE_STATE_SYNC
      StateUpdate -> RenderSource.UPDATE_STATE_ASYNC
      MeasureSync -> RenderSource.MEASURE_SET_SIZE_SPEC
      Measure -> RenderSource.MEASURE_SET_SIZE_SPEC_ASYNC
      else -> throw IllegalArgumentException("Unknown render mode: $this")
    }
  }

  companion object {
    val SetRootSync: RenderMode = RenderMode(0)
    val SetRoot: RenderMode = RenderMode(1)
    val SetConstraintsSync: RenderMode = RenderMode(2)
    val SetConstraints: RenderMode = RenderMode(3)
    val StateUpdateSync: RenderMode = RenderMode(4)
    val StateUpdate: RenderMode = RenderMode(5)
    val MeasureSync: RenderMode = RenderMode(6)
    val Measure: RenderMode = RenderMode(7)
  }
}
