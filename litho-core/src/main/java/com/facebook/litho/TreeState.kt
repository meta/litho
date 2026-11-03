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

import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.facebook.infer.annotation.ThreadConfined
import com.facebook.litho.Component.RenderData
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.effects.EffectHandler
import com.facebook.litho.internal.HookKey as RenderDataHookKey
import com.facebook.litho.state.ComponentState
import com.facebook.litho.state.StateId
import com.facebook.rendercore.annotations.UIState

class TreeState
private constructor(
    val resolveState: StateHandler,
    val layoutState: StateHandler,
    @UIState val mountInfo: TreeMountInfo,
    @UIState private val renderState: RenderState,
    val effectsHandler: EffectHandler,
    private val eventTriggersContainer: EventTriggersContainer,
    @get:VisibleForTesting val eventHandlersController: EventHandlersController,
) {

  /**
   * The active [InitialStateScope] for this TreeState, if any. Set by
   * [InitialStateScope.forResolve]/[InitialStateScope.forLayout].
   */
  @JvmField internal var scope: InitialStateScope? = null

  init {
    if (resolveState.initialState === layoutState.initialState) {
      error("The same InitialState cannot be used for both resolve and layout states")
    }
  }

  @ThreadConfined(ThreadConfined.UI) private var _unconsumedMountUpdates: Set<StateId> = emptySet()

  /**
   * The states each committed resolve updated, oldest first, until a layout of that resolve or a
   * newer one commits. Replaced, not mutated, so a copied [TreeState] shares it.
   */
  @Volatile private var resolveUpdates: List<ResolveUpdates> = emptyList()

  private class ResolveUpdates(val resolveVersion: Int, val states: Set<StateId>)

  /**
   * The restored values this render's [useSaveableState] hooks took on their first render. Their
   * registries drop them when this render commits. Only such renders create it.
   */
  @Volatile private var saveableClaims: ArrayList<SaveableClaim>? = null

  private class SaveableClaim(val slot: SaveableStateSlot, val tree: LithoTree)

  /**
   * The registry that adopted this tree's values, until the tree's next render commits into this
   * TreeState. Only a tree built with the TreeState of a tree that saved values sets it.
   */
  @Volatile private var saveableAdoption: LithoSaveableStateRegistry? = null

  /**
   * This class represents whether this Litho tree has been mounted before. The usage is a bit
   * convoluted and will need to be cleaned out properly in the future.
   */
  class TreeMountInfo {
    @JvmField @Volatile var hasMounted: Boolean = false

    @JvmField @Volatile var isFirstMount: Boolean = false
  }

  @JvmOverloads
  constructor(
      fromState: TreeState? = null,
      effectsHandler: EffectHandler? = null,
  ) : this(
      resolveState = StateHandler(fromState?.resolveState),
      layoutState = StateHandler(fromState?.layoutState),
      mountInfo = fromState?.mountInfo ?: TreeMountInfo(),
      renderState = RenderState(fromState?.renderState),
      effectsHandler = effectsHandler ?: fromState?.effectsHandler ?: EffectHandler(),
      eventTriggersContainer = fromState?.eventTriggersContainer ?: EventTriggersContainer(),
      eventHandlersController = fromState?.eventHandlersController ?: EventHandlersController(),
  ) {
    fromState?.let { resolveUpdates = it.resolveUpdates }
  }

  constructor(
      initialResolveState: InitialState,
      initialLayoutState: InitialState,
  ) : this(
      resolveState = StateHandler(initialResolveState),
      layoutState = StateHandler(initialLayoutState),
      mountInfo = TreeMountInfo(),
      renderState = RenderState(),
      effectsHandler = EffectHandler(),
      eventTriggersContainer = EventTriggersContainer(),
      eventHandlersController = EventHandlersController(),
  )

  private fun getStateHandler(isLayoutState: Boolean): StateHandler {
    return if (isLayoutState) {
      layoutState
    } else {
      resolveState
    }
  }

  internal fun registerResolveState() {
    resolveState.initialState.registerStateHandler(resolveState)
  }

  internal fun registerLayoutState() {
    layoutState.initialState.registerStateHandler(layoutState)
  }

  internal fun unregisterResolveInitialState() {
    resolveState.initialState.unregisterStateHandler(resolveState)
  }

  internal fun unregisterLayoutInitialState() {
    layoutState.initialState.unregisterStateHandler(layoutState)
  }

  /**
   * @param resolveVersion the committed resolve's version. When tracking, it is recorded with the
   *   states the resolve updated, for [statesUpdatedAfter].
   */
  fun commitResolveState(
      localTreeState: TreeState,
      trackForPromotion: Boolean = false,
      resolveVersion: Int = UNSET_VERSION,
  ) {
    resolveState.commit(localTreeState.resolveState)
    if (trackForPromotion) {
      val updated = localTreeState.resolveState.getStateIdsForPendingUpdates()
      if (updated.isNotEmpty()) {
        resolveState.addToStateIdsToPromote(updated)
        if (resolveVersion != UNSET_VERSION) {
          recordResolveUpdates(updated, resolveVersion)
        }
      }
    }
    localTreeState.consumeSaveableClaims()
    consumeSaveableAdoption()
    localTreeState.scope?.close()
  }

  /**
   * @param resolveVersion the version of the resolve the committed layout came from. Every update
   *   recorded at or below it is laid out, so it is dropped from [statesUpdatedAfter].
   */
  fun commitLayoutState(
      localTreeState: TreeState,
      trackForPromotion: Boolean = false,
      resolveVersion: Int = UNSET_VERSION,
  ) {
    layoutState.commit(localTreeState.layoutState)
    if (resolveVersion != UNSET_VERSION) {
      dropResolveUpdatesUpTo(resolveVersion)
    }
    if (trackForPromotion) {
      val updated = localTreeState.layoutState.getStateIdsForPendingUpdates()
      if (updated.isNotEmpty()) {
        layoutState.addToStateIdsToPromote(updated)
      }
    }
    localTreeState.consumeSaveableClaims()
    consumeSaveableAdoption()
    localTreeState.scope?.close()
  }

  @Synchronized
  internal fun recordSaveableClaim(slot: SaveableStateSlot, tree: LithoTree) {
    val claims = saveableClaims ?: ArrayList<SaveableClaim>(2).also { saveableClaims = it }
    claims += SaveableClaim(slot, tree)
  }

  /** Consumes the claims once: the UI tree state commits the same render again. */
  private fun consumeSaveableClaims() {
    if (saveableClaims == null) return
    val claims = synchronized(this) { saveableClaims.also { saveableClaims = null } } ?: return
    claims.forEach { claim -> claim.slot.scope?.onClaimCommitted(claim.slot, claim.tree) }
  }

  /** Has [registry] decide which of its copies to drop when the next render commits into this. */
  internal fun adoptSaveableValuesAtNextCommit(registry: LithoSaveableStateRegistry) {
    saveableAdoption = registry
  }

  private fun consumeSaveableAdoption() {
    val registry = saveableAdoption ?: return
    saveableAdoption = null
    registry.onAdoptedTreeCommitted(this)
  }

  /** The states a committed resolve newer than [resolveVersion] updated. */
  internal fun statesUpdatedAfter(resolveVersion: Int): Set<StateId> {
    var single: Set<StateId>? = null
    var union: HashSet<StateId>? = null
    for (updates in resolveUpdates) {
      if (updates.resolveVersion > resolveVersion) {
        when {
          union != null -> union.addAll(updates.states)
          single == null -> single = updates.states
          else -> union = HashSet(single).apply { addAll(updates.states) }
        }
      }
    }
    return union ?: single ?: emptySet()
  }

  @Synchronized
  private fun recordResolveUpdates(updated: Set<StateId>, resolveVersion: Int) {
    resolveUpdates = resolveUpdates + ResolveUpdates(resolveVersion, updated)
  }

  /**
   * Forgets every recorded version. A tree seeded with this [TreeState] numbers its resolves from
   * zero again, so versions recorded by the previous tree mean nothing to it.
   */
  @Synchronized
  internal fun clearResolveUpdates() {
    resolveUpdates = emptyList()
  }

  @Synchronized
  private fun dropResolveUpdatesUpTo(resolveVersion: Int) {
    val updates = resolveUpdates
    if (updates.none { it.resolveVersion <= resolveVersion }) return
    resolveUpdates = updates.filter { it.resolveVersion > resolveVersion }
  }

  fun commit() {
    resolveState.commit()
    layoutState.commit()
  }

  /** Visits the [useSaveableState] entries of both handlers, each under its handler's lock. */
  internal fun forEachSaveableEntry(block: (slot: SaveableStateSlot, value: Any?) -> Unit) {
    resolveState.forEachSaveableEntry(block)
    layoutState.forEachSaveableEntry(block)
  }

  fun queueStateUpdate(
      key: StateId,
      stateUpdate: StateContainer.StateUpdate,
      isLazyStateUpdate: Boolean,
      isLayoutState: Boolean,
  ) {
    val stateHandler = getStateHandler(isLayoutState)
    stateHandler.queueStateUpdate(key, stateUpdate, isLazyStateUpdate)
  }

  fun queueHookStateUpdate(stateId: StateId, updater: HookUpdater, isLayoutState: Boolean) {
    getStateHandler(isLayoutState).queueHookStateUpdate(stateId, updater)
  }

  fun applyLazyStateUpdatesForContainer(
      key: StateId,
      container: StateContainer,
      isNestedTree: Boolean,
  ): StateContainer {
    return getStateHandler(isNestedTree).applyLazyStateUpdatesForContainer(key, container)
  }

  fun hasUncommittedUpdates(): Boolean {
    return resolveState.hasUncommittedUpdates() || layoutState.hasUncommittedUpdates()
  }

  val isEmpty: Boolean
    get() = resolveState.isEmpty && layoutState.isEmpty

  fun applyStateUpdatesEarly(
      context: ComponentContext,
      component: Component?,
      prevTreeRootNode: LithoNode?,
      isLayoutState: Boolean,
  ) {
    getStateHandler(isLayoutState).applyStateUpdatesEarly(context, component, prevTreeRootNode)
  }

  val keysForPendingStateUpdates: Set<GlobalKey>
    get() {
      return HashSet<GlobalKey>().apply {
        addAll(getKeysForPendingStateUpdates(resolveState))
        addAll(getKeysForPendingStateUpdates(layoutState))
      }
    }

  val keysForAppliedStateUpdates: Set<GlobalKey>
    get() {
      return HashSet<GlobalKey>().apply {
        addAll(resolveState.keysForAppliedUpdates)
        addAll(layoutState.keysForAppliedUpdates)
      }
    }

  fun getStateIdsForPendingStateUpdates(): Set<StateId> {
    return buildSet {
      addAll(resolveState.getStateIdsForPendingUpdates())
      addAll(layoutState.getStateIdsForPendingUpdates())
    }
  }

  fun getStateIdsForPendingResolveStateUpdates(): Set<StateId> {
    return resolveState.getStateIdsForPendingUpdates()
  }

  fun getStateIdsForPendingLayoutStateUpdates(): Set<StateId> {
    return layoutState.getStateIdsForPendingUpdates()
  }

  fun addState(key: GlobalKey, state: ComponentState<*>, isLayoutState: Boolean) {
    getStateHandler(isLayoutState).addState(key, state)
  }

  fun markStateInUse(key: GlobalKey, isLayoutState: Boolean) {
    getStateHandler(isLayoutState).markStateInUse(key)
  }

  fun markAllStateInUse(isLayoutState: Boolean) {
    getStateHandler(isLayoutState).markAllStateInUse()
  }

  fun getState(key: GlobalKey, isLayoutState: Boolean): ComponentState<out StateContainer>? {
    return getStateHandler(isLayoutState).getState(key)
  }

  fun createOrGetState(
      scopedContext: ComponentContext,
      component: Component,
      key: GlobalKey,
  ): ComponentState<out StateContainer> {
    return getStateHandler(scopedContext.isContextForLayout)
        .createOrGetState(scopedContext, component, key)
  }

  fun createOrGetEventDispatchInfoForComponent(
      scopedContext: ComponentContext,
      component: Component,
      key: GlobalKey,
  ): EventDispatchInfo {
    return checkNotNull(
        getStateHandler(scopedContext.isContextForLayout)
            .createOrGetState(scopedContext, component, key)
            .eventDispatchInfo,
    )
  }

  fun removePendingStateUpdate(key: StateId, isLayoutState: Boolean) {
    getStateHandler(isLayoutState).removePendingStateUpdate(key)
  }

  fun <T> canSkipStateUpdate(stateId: StateId, newValue: T, isLayoutState: Boolean): Boolean {
    return canSkipStateUpdate<T>(
        updater = { newValue },
        stateId = stateId,
        isLayoutState = isLayoutState,
    )
  }

  fun <T> canSkipStateUpdate(updater: (T) -> T, stateId: StateId, isLayoutState: Boolean): Boolean {
    val stateHandler = getStateHandler(isLayoutState)
    val committedState = stateHandler.getState(stateId.globalKey)
    if (committedState != null) {
      val committedStateWithUpdatesApplied = stateHandler.getKStateWithUpdates(stateId.globalKey)
      if (committedStateWithUpdatesApplied != null) {
        val hookKey = stateId.hookGlobalKey.hookKey as HookKey
        val committedUpdatedCachedValue =
            committedStateWithUpdatesApplied.getByHookKey(hookKey)
                // A hook pruned after this State was captured can no longer be updated.
                ?: return true
        val committedUpdatedValue: T = committedUpdatedCachedValue?.value as T
        val newValueAfterUpdate = updater.invoke(committedUpdatedValue)
        return if (committedUpdatedValue == null && newValueAfterUpdate == null) {
          true
        } else {
          committedUpdatedValue != null && committedUpdatedValue == newValueAfterUpdate
        }
      }
    }
    return false
  }

  val pendingStateUpdateTransitions: List<Transition>
    get() {
      val updateStateTransitions: MutableList<Transition> = ArrayList()

      resolveState.pendingStateUpdateTransitions?.values?.let { transitions ->
        for (pendingTransitions in transitions) {
          updateStateTransitions.addAll(pendingTransitions)
        }
      }

      layoutState.pendingStateUpdateTransitions?.values?.let { transitions ->
        for (pendingTransitions in transitions) {
          updateStateTransitions.addAll(pendingTransitions)
        }
      }
      return updateStateTransitions
    }

  fun putCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      cachedValue: Any?,
      isLayoutState: Boolean,
  ) {
    getStateHandler(isLayoutState).putCachedValue(cacheKey, cachedValueInputs, cachedValue)
  }

  fun getCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      isLayoutState: Boolean,
  ): Any? {
    return getStateHandler(isLayoutState).getCachedValue(cacheKey, cachedValueInputs)
  }

  fun <T> createOrGetInitialHookStateByHookKey(
      key: GlobalKey,
      hookKey: HookKey,
      deps: Array<*>,
      initializer: HookInitializer<T>,
      isLayoutState: Boolean,
      componentName: String,
  ): ComponentState<KStateContainer> {
    return getStateHandler(isLayoutState)
        .initialState
        .getOrCreateInitialKStateByHookKey(
            key,
            hookKey,
            deps,
            initializer,
            componentName,
        )
  }

  fun <T> peekOrSeedInitialHookStateByHookKey(
      key: GlobalKey,
      hookKey: HookKey,
      deps: Array<*>,
      initializer: HookInitializer<T>,
      isLayoutState: Boolean,
      componentName: String,
  ): CachedValue {
    return getStateHandler(isLayoutState)
        .initialState
        .peekOrSeedInitialKStateByHookKey(
            key,
            hookKey,
            deps,
            initializer,
            componentName,
        )
  }

  internal fun getPreviousLayoutStateId(): Int = renderState.getPreviousLayoutStateId()

  internal fun getPreviousRenderData(hookKey: RenderDataHookKey): RenderData? {
    return renderState.getPreviousRenderData(hookKey)
  }

  fun recordRenderData(layoutState: LayoutState) {
    renderState.recordRenderData(layoutState)
  }

  fun getEventTrigger(triggerKey: Any): EventTrigger<*>? {
    synchronized(eventTriggersContainer) {
      return eventTriggersContainer.getEventTrigger(triggerKey)
    }
  }

  fun getEventTrigger(handle: Handle, methodId: Int): EventTrigger<*>? {
    synchronized(eventTriggersContainer) {
      return eventTriggersContainer.getEventTrigger(handle, methodId)
    }
  }

  fun clearEventHandlersAndTriggers() {
    synchronized(eventTriggersContainer) { eventTriggersContainer.clear() }
    eventHandlersController.clear()
  }

  fun clearUnusedTriggerHandlers() {
    synchronized(eventTriggersContainer) { eventTriggersContainer.clear() }
  }

  fun updateEventDispatchers(toUpdate: List<ScopedComponentInfo>) {
    toUpdate.forEach { scope ->
      val context = scope.context
      val key = context.nullableGlobalKey
      val component = context.componentScope

      if (component is SpecGeneratedComponent) {
        component.recordEventTrigger(context, eventTriggersContainer)
      }

      val nonNullKey = requireNotNull(key)
      val info =
          resolveState.getState(nonNullKey)?.eventDispatchInfo
              ?: layoutState.getState(nonNullKey)?.eventDispatchInfo
              ?: return@forEach

      info.componentContext = context
      info.hasEventDispatcher = context.componentScope as HasEventDispatcher
    }
  }

  /** Computes the effective updates for promotion by combining both resolve and layout handlers. */
  fun getUpdatesForPromotion(localTreeState: TreeState): Map<StateId, Int> {
    val resolveEffective = computeEffectiveForHandler(localTreeState.resolveState, resolveState)
    val layoutEffective = computeEffectiveForHandler(localTreeState.layoutState, layoutState)

    if (resolveEffective.isEmpty()) return layoutEffective
    if (layoutEffective.isEmpty()) return resolveEffective

    // Union (merge counts defensively)
    return buildMap {
      putAll(resolveEffective)
      for ((id, count) in layoutEffective) {
        put(id, (get(id) ?: 0) + count)
      }
    }
  }

  private fun computeEffectiveForHandler(
      localHandler: StateHandler,
      committedHandler: StateHandler,
  ): Map<StateId, Int> {
    val toPromote = localHandler.getStateIdsToPromoteUnsafe()
    val pending = localHandler.getStateIdsForPendingUpdates()
    if (toPromote == null && pending.isEmpty()) return emptyMap()

    val snapshot = buildMap {
      toPromote?.let { putAll(it) }
      for (id in pending) {
        put(id, (get(id) ?: 0) + 1)
      }
    }
    return committedHandler.computeEffective(snapshot)
  }

  /** Subtracts promoted counts from both resolve and layout handlers. */
  fun subtractPromotedUpdates(promoted: Map<StateId, Int>) {
    if (promoted.isNotEmpty()) {
      resolveState.subtractPromotedUpdates(promoted)
      layoutState.subtractPromotedUpdates(promoted)
    }
  }

  @UiThread
  fun addMountUpdates(updates: Set<StateId>) {
    _unconsumedMountUpdates =
        when {
          _unconsumedMountUpdates.isEmpty() -> updates
          updates.isEmpty() -> _unconsumedMountUpdates
          else -> _unconsumedMountUpdates + updates
        }
  }

  @UiThread
  fun consumeMountUpdates(): Set<StateId> {
    val updates = _unconsumedMountUpdates
    _unconsumedMountUpdates = emptySet()
    return updates
  }

  companion object {
    /** No resolve version: the commit records nothing for [statesUpdatedAfter]. */
    internal const val UNSET_VERSION: Int = Int.MIN_VALUE

    private fun getKeysForPendingStateUpdates(stateHandler: StateHandler): Set<GlobalKey> {
      return stateHandler.keysForPendingUpdates
    }

    /**
     * Creates the [TreeState] for a layout-only pass (same resolve, new size specs), continuing
     * from [previousLayout] so layout-scoped state created since [resolveSnapshot] carries over.
     *
     * The [RenderState] comes from [resolveSnapshot]: the previous layout's render data is written
     * on the main thread at mount, while the resolve snapshot's is never written after it is
     * published, so only the latter is safe to copy off the main thread.
     */
    internal fun forLayoutOnlyPass(
        previousLayout: TreeState,
        resolveSnapshot: TreeState?,
    ): TreeState {
      if (!ComponentsConfiguration.layoutOnlyPassUsesResolveRenderState) {
        return TreeState(previousLayout)
      }
      return TreeState(
          resolveState = StateHandler(previousLayout.resolveState),
          layoutState = StateHandler(previousLayout.layoutState),
          mountInfo = previousLayout.mountInfo,
          renderState = RenderState(resolveSnapshot?.renderState),
          effectsHandler = previousLayout.effectsHandler,
          eventTriggersContainer = previousLayout.eventTriggersContainer,
          eventHandlersController = previousLayout.eventHandlersController,
      )
    }
  }
}

/**
 * An [AutoCloseable] scope that pairs [TreeState] register/unregister lifecycle into a single
 * object. Creating a scope registers the [TreeState]'s [InitialState] handler; closing it
 * unregisters. Close is idempotent—safe to call multiple times.
 *
 * Use the factory methods [forResolve] and [forLayout] to create instances. The scope is typically
 * managed by [TreeFuture.trackRunAndCommit] for [ComponentTree] and [LithoRenderer] call sites, or
 * explicitly via try/finally for [NestedLithoTree].
 */
class InitialStateScope
private constructor(
    private val treeState: TreeState,
    private val phase: Phase,
) : AutoCloseable {
  enum class Phase {
    RESOLVE,
    LAYOUT,
  }

  @Volatile private var closed = false

  /** Unregisters the [InitialState] handler. Idempotent: subsequent calls are no-ops. */
  override fun close() {
    if (closed) return
    closed = true
    when (phase) {
      Phase.RESOLVE -> treeState.unregisterResolveInitialState()
      Phase.LAYOUT -> treeState.unregisterLayoutInitialState()
    }
  }

  companion object {
    /** Registers the resolve-phase [InitialState] handler and returns a scope to unregister it. */
    fun forResolve(treeState: TreeState): InitialStateScope {
      treeState.registerResolveState()
      return InitialStateScope(treeState, Phase.RESOLVE).also { treeState.scope = it }
    }

    /** Registers the layout-phase [InitialState] handler and returns a scope to unregister it. */
    fun forLayout(treeState: TreeState): InitialStateScope {
      treeState.registerLayoutState()
      return InitialStateScope(treeState, Phase.LAYOUT).also { treeState.scope = it }
    }
  }
}
