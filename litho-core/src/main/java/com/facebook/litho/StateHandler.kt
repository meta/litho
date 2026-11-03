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

import androidx.annotation.VisibleForTesting
import com.facebook.infer.annotation.ThreadSafe
import com.facebook.litho.SpecGeneratedComponent.TransitionContainer
import com.facebook.litho.StateContainer.StateUpdate
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.state.ComponentState
import com.facebook.litho.state.StateId
import com.facebook.litho.stats.LithoStats
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute.Description
import com.facebook.rendercore.debug.DebugEventAttribute.Name
import com.facebook.rendercore.debug.DebugEventAttribute.Source
import com.facebook.rendercore.debug.DebugEventDispatcher
import com.facebook.rendercore.transitions.TransitionUtils
import com.facebook.rendercore.utils.areObjectsEquivalent
import javax.annotation.concurrent.GuardedBy

/** Holds information about the current State of the components in a Component Tree. */
class StateHandler {

  constructor(initialState: InitialState, stateHandler: StateHandler? = null) {
    this.initialState = initialState
    if (stateHandler != null) {
      stateHandler.copyPendingUpdates(this)
      copyCurrentState(stateHandler.state)
      copyPendingStateTransitions(stateHandler.pendingStateUpdateTransitions)
      stateHandler.cachedValues?.let { synchronized(stateHandler) { cachedValues = HashMap(it) } }
      synchronized(stateHandler) {
        stateHandler._stateIdsToPromote?.let {
          if (it.isNotEmpty()) getOrCreateStateIdsToPromote().putAll(it)
        }
      }
    }
  }

  constructor(
      stateHandler: StateHandler? = null,
  ) : this(
      initialState = stateHandler?.initialState ?: InitialState(),
      stateHandler = stateHandler,
  )

  /**
   * List of state updates that will be applied during the next layout pass. Lazily initialized to
   * avoid allocating empty HashMaps for the majority of StateHandler instances that never receive
   * state updates.
   */
  @GuardedBy("this")
  private var _pendingStateUpdates: MutableMap<StateId, MutableList<StateUpdate>>? = null

  /** List of lazy state updates. Lazily initialized. */
  @GuardedBy("this")
  private var _pendingLazyStateUpdates: MutableMap<StateId, MutableList<StateUpdate>>? = null

  /**
   * List of transitions from state update that will be applied on next mount. Lazily initialized.
   */
  @GuardedBy("this")
  private var _pendingStateUpdateTransitions: MutableMap<StateId, List<Transition>>? = null

  /**
   * List of transitions from state update that have been applied on next mount. Lazily initialized.
   */
  @GuardedBy("this")
  private var _appliedStateUpdates: MutableMap<StateId, List<StateUpdate>>? = null

  /**
   * Maps a component key to a component object that retains the current state values for that key.
   * Lazily initialized.
   */
  @GuardedBy("this")
  private var _state: MutableMap<GlobalKey, ComponentState<out StateContainer>>? = null

  /**
   * Contains all keys of components that were present in the current ComponentTree and therefore
   * their StateContainer needs to be kept around. Lazily initialized.
   */
  @GuardedBy("this") private var neededState: MutableSet<GlobalKey>? = null

  /** Map of all cached values that are stored for the current ComponentTree. */
  @GuardedBy("this") private var cachedValues: MutableMap<HookGlobalKey, CacheValue>? = null

  // These are both lists of (globalKey, updateMethod) pairs, where globalKey is the global key
  // of the component the update applies to. Lazily initialized.
  @GuardedBy("this")
  private var pendingHookUpdates: MutableMap<GlobalKey, MutableList<HookUpdateData>>? = null
  private var appliedHookUpdates: MutableMap<GlobalKey, List<HookUpdateData>>? = null

  /**
   * Count-based multiset of committed-but-not-promoted state updates. Copied from source at
   * construction; on committed handler, updated at commit and subtracted at promotion. Lazily
   * initialized.
   */
  @GuardedBy("this") private var _stateIdsToPromote: MutableMap<StateId, Int>? = null

  // Lazy initializer helpers to avoid allocating empty maps when they are never used.
  private fun getOrCreatePendingStateUpdates(): MutableMap<StateId, MutableList<StateUpdate>> =
      _pendingStateUpdates
          ?: HashMap<StateId, MutableList<StateUpdate>>(INITIAL_MAP_CAPACITY).also {
            _pendingStateUpdates = it
          }

  private fun getOrCreatePendingLazyStateUpdates(): MutableMap<StateId, MutableList<StateUpdate>> =
      _pendingLazyStateUpdates
          ?: HashMap<StateId, MutableList<StateUpdate>>(INITIAL_MAP_CAPACITY).also {
            _pendingLazyStateUpdates = it
          }

  private fun getOrCreatePendingStateUpdateTransitions(): MutableMap<StateId, List<Transition>> =
      _pendingStateUpdateTransitions
          ?: HashMap<StateId, List<Transition>>().also { _pendingStateUpdateTransitions = it }

  private fun getOrCreateAppliedStateUpdates(): MutableMap<StateId, List<StateUpdate>> =
      _appliedStateUpdates
          ?: HashMap<StateId, List<StateUpdate>>(INITIAL_MAP_CAPACITY).also {
            _appliedStateUpdates = it
          }

  private fun getOrCreatePendingHookUpdates(): MutableMap<GlobalKey, MutableList<HookUpdateData>> =
      pendingHookUpdates
          ?: HashMap<GlobalKey, MutableList<HookUpdateData>>().also { pendingHookUpdates = it }

  private fun getOrCreateAppliedHookUpdates(): MutableMap<GlobalKey, List<HookUpdateData>> =
      appliedHookUpdates
          ?: HashMap<GlobalKey, List<HookUpdateData>>().also { appliedHookUpdates = it }

  private fun getOrCreateStateIdsToPromote(): MutableMap<StateId, Int> =
      _stateIdsToPromote ?: HashMap<StateId, Int>().also { _stateIdsToPromote = it }

  private fun getOrCreateState(): MutableMap<GlobalKey, ComponentState<out StateContainer>> =
      _state ?: HashMap<GlobalKey, ComponentState<out StateContainer>>().also { _state = it }

  private fun getOrCreateNeededState(): MutableSet<GlobalKey> =
      neededState ?: HashSet<GlobalKey>().also { neededState = it }

  var initialState: InitialState
    private set

  @get:Synchronized
  val isEmpty: Boolean
    get() = _state.isNullOrEmpty()

  /**
   * @return whether this StateHandler has updates that haven't been committed to the
   *   source-of-truth StateHandler on the ComponentTree.
   */
  @Synchronized
  fun hasUncommittedUpdates(): Boolean =
      // Because we immediately apply Kotlin state updates at the  beginning of layout, we need to
      // also check applied state updates to see if this StateHandler has uncommitted updates.
      _pendingStateUpdates?.isNotEmpty() == true ||
          pendingHookUpdates?.isNotEmpty() == true ||
          appliedHookUpdates?.isNotEmpty() == true

  /**
   * Adds a state update to the list of the state updates that will be applied for the given
   * component key during the next layout pass.
   *
   * @param key the global key of the component
   * @param stateUpdate the state update to apply to the component
   * @param isLazyStateUpdate the flag to indicate if it's a lazy state update
   */
  @Synchronized
  fun queueStateUpdate(key: StateId, stateUpdate: StateUpdate, isLazyStateUpdate: Boolean) {
    addStateUpdateForKey(key, stateUpdate, getOrCreatePendingStateUpdates())
    if (isLazyStateUpdate) {
      addStateUpdateForKey(key, stateUpdate, getOrCreatePendingLazyStateUpdates())
    }
  }

  @Synchronized
  fun markStateInUse(key: GlobalKey) {
    getOrCreateNeededState().add(key)
  }

  @Synchronized
  fun markAllStateInUse() {
    val state = _state
    if (state != null && state.isNotEmpty()) {
      getOrCreateNeededState().addAll(state.keys)
    }
  }

  /**
   * StateContainer in this StateHandler should be accessed using this method as it will also ensure
   * that the state is marked as needed
   */
  fun getState(key: GlobalKey): ComponentState<out StateContainer>? = _state?.get(key)

  @Synchronized
  fun createOrGetState(
      scopedContext: ComponentContext,
      component: Component,
      key: GlobalKey,
  ): ComponentState<out StateContainer> {
    val current: ComponentState<out StateContainer>? = synchronized(this) { _state?.get(key) }

    return if (current != null) {
      getOrCreateNeededState().add(key)
      current
    } else {
      val state =
          initialState.createOrGetState(
              component,
              scopedContext,
              key,
          )
      this.addState(key, state)
      state
    }
  }

  private fun applyStateUpdates(key: StateId, newStateContainer: StateContainer) {
    // If there are no state updates pending for this component, simply store its current state.
    val stateUpdatesForKey = synchronized(this) { _pendingStateUpdates?.get(key) } ?: return

    var transitionsFromStateUpdate: MutableList<Transition>? = null
    val asTransitionContainer = newStateContainer as? TransitionContainer

    for (update in stateUpdatesForKey) {
      if (asTransitionContainer != null) {
        val transition = asTransitionContainer.applyStateUpdateWithTransition(update)
        if (transition != null) {
          if (transitionsFromStateUpdate == null) {
            transitionsFromStateUpdate = ArrayList()
          }
          TransitionUtils.setOwnerKey(transition, key.globalKey)
          transitionsFromStateUpdate.add(transition)
        }
      } else {
        newStateContainer.applyStateUpdate(update)
      }
    }

    LithoStats.incrementComponentAppliedStateUpdateCountBy(stateUpdatesForKey.size.toLong())

    synchronized(this) {
      _pendingLazyStateUpdates?.remove(key)
      getOrCreateAppliedStateUpdates()[key] = stateUpdatesForKey // add to applied

      if (!transitionsFromStateUpdate.isNullOrEmpty()) {
        getOrCreatePendingStateUpdateTransitions()[key] = transitionsFromStateUpdate
      }
    }
  }

  @ThreadSafe(enableChecks = false)
  @Synchronized
  fun applyStateUpdatesEarly(
      context: ComponentContext,
      component: Component?,
      prevTreeRootNode: LithoNode?,
  ) {
    val pendingUpdates = _pendingStateUpdates
    if (pendingUpdates != null) {
      for ((key, _) in pendingUpdates) {
        try {
          val current: ComponentState<StateContainer> =
              (_state?.get(key.globalKey)
                  ?: initialState.getInitialStateForComponent(key.globalKey))
                  as ComponentState<StateContainer>? ?: continue

          val currentValue: StateContainer = current.value

          val newValue = currentValue.clone()
          getOrCreateState()[key.globalKey] = current.copy(value = newValue)
          applyStateUpdates(key, newValue)
        } catch (ex: Exception) {

          // Remove pending state update from ComponentTree's state handler since we don't want to
          // process this pending state update again. If we don't remove it and someone is using
          // setRoot in onError api then we can end up in an infinite loop
          context.removePendingStateUpdate(key.globalKey, context.isContextForLayout)
          if (prevTreeRootNode != null) {
            handleExceptionDuringApplyStateUpdate(key, prevTreeRootNode, ex)
          } else {
            ComponentUtils.handleWithHierarchy(context, component, ex)
          }
        }
      }
      _pendingStateUpdates = null
    }

    runHooks()
  }

  @Synchronized
  fun removePendingStateUpdate(key: StateId) {
    _pendingStateUpdates?.remove(key)
  }

  /**
   * Maps a component key to a component object that retains the current state values for that key.
   *
   * @param key the key of component
   * @param state the state value that needs to be retained
   */
  @Synchronized
  fun addState(key: GlobalKey, state: ComponentState<*>) {
    getOrCreateNeededState().add(key)
    getOrCreateState()[key] = state
  }

  fun applyLazyStateUpdatesForContainer(key: StateId, container: StateContainer): StateContainer {
    val stateUpdatesForKey = synchronized(this) { _pendingLazyStateUpdates?.get(key) }

    if (stateUpdatesForKey.isNullOrEmpty()) {
      return container
    }

    val containerWithUpdatesApplied = container.clone()
    for (update in stateUpdatesForKey) {
      containerWithUpdatesApplied.applyStateUpdate(update)
    }

    return containerWithUpdatesApplied
  }

  /**
   * Removes a list of state updates that have been applied from the pending state updates list and
   * updates the map of current components with the given components.
   *
   * @param stateHandler state handler that was used to apply state updates in a layout pass
   */
  fun commit(stateHandler: StateHandler) {
    stateHandler.appliedStateUpdates?.let { clearStateUpdates(it) }
    clearUnusedState(stateHandler)
    copyCurrentState(stateHandler.state)
    copyPendingStateTransitions(stateHandler.pendingStateUpdateTransitions)
    stateHandler.appliedHookUpdates?.let { commitHookState(it) }
  }

  fun commit() {
    synchronized(this) {
      _appliedStateUpdates?.let { clearStateUpdates(it) }
      clearUnusedState(this)
      appliedHookUpdates?.let { commitHookState(it) }
      _appliedStateUpdates = null
      appliedHookUpdates = null
    }
  }

  @get:Synchronized
  val keysForPendingUpdates: Set<GlobalKey>
    get() =
        HashSet<GlobalKey>().apply {
          _appliedStateUpdates?.keys?.mapTo(this) { it.globalKey }
          _pendingStateUpdates?.keys?.mapTo(this) { it.globalKey }
          pendingHookUpdates?.keys?.let { addAll(it) }
          appliedHookUpdates?.keys?.let { addAll(it) }
        }

  @get:Synchronized
  val keysForAppliedUpdates: Set<GlobalKey>
    get() =
        HashSet<GlobalKey>().apply {
          _appliedStateUpdates?.keys?.mapTo(this) { it.globalKey }
          appliedHookUpdates?.keys?.let { addAll(it) }
        }

  /**
   * Cached result of [getStateIdsForPendingUpdates]. No invalidation or synchronization is needed
   * because this method is only called on copied (in-flight) StateHandlers that are never mutated
   * after construction. The committed StateHandler receives mutations but never serves this cache.
   */
  @Volatile private var _cachedPendingStateIds: Set<StateId>? = null

  fun getStateIdsForPendingUpdates(): Set<StateId> {
    return _cachedPendingStateIds
        ?: buildSet {
          _appliedStateUpdates?.keys?.let { addAll(it) }
          _pendingStateUpdates?.keys?.let { addAll(it) }
          pendingHookUpdates?.forEach { (_, v) -> v.mapTo(this) { it.stateId } }
          appliedHookUpdates?.forEach { (_, v) -> v.mapTo(this) { it.stateId } }
        }
            .also { _cachedPendingStateIds = it }
  }

  /**
   * Returns the _stateIdsToPromote map without copying.
   *
   * This method is safe to call on StateHandlers of in-flight operations (e.g TreeState of
   * ResolveResult), but not on persistent StateHandlers (e.g committed TreeState of the
   * ComponentTree).
   */
  internal fun getStateIdsToPromoteUnsafe(): Map<StateId, Int>? = _stateIdsToPromote

  /** Adds the given stateIds to the promotion multiset, incrementing each by 1. */
  @Synchronized
  fun addToStateIdsToPromote(stateIds: Set<StateId>) {
    val map = getOrCreateStateIdsToPromote()
    for (id in stateIds) {
      map[id] = (map[id] ?: 0) + 1
    }
  }

  /** Subtracts the promoted counts. Removes entries that reach zero or below. */
  @Synchronized
  fun subtractPromotedUpdates(promoted: Map<StateId, Int>) {
    val map = _stateIdsToPromote ?: return
    for ((id, count) in promoted) {
      val current = map[id] ?: continue
      val remaining = current - count
      if (remaining <= 0) map.remove(id) else map[id] = remaining
    }
  }

  /** Computes the effective snapshot by taking min(localCount, committedCount) per stateId. */
  @Synchronized
  fun computeEffective(localSnapshot: Map<StateId, Int>): Map<StateId, Int> {
    if (localSnapshot.isEmpty()) {
      return emptyMap()
    }
    val map = _stateIdsToPromote ?: return emptyMap()
    return buildMap {
      for ((id, localCount) in localSnapshot) {
        val committedCount = map[id] ?: continue
        put(id, minOf(localCount, committedCount))
      }
    }
  }

  private fun clearStateUpdates(appliedStateUpdates: Map<StateId, List<StateUpdate>>) {
    synchronized(this) {
      if (_pendingStateUpdates.isNullOrEmpty()) {
        return
      }
    }

    for ((appliedStateUpdateKey, appliedStateUpdatesForKey) in appliedStateUpdates) {
      val (pendingStateUpdatesForKey, pendingLazyStateUpdatesForKey) =
          synchronized(this) {
            Pair(
                _pendingStateUpdates?.get(appliedStateUpdateKey),
                _pendingLazyStateUpdates?.get(appliedStateUpdateKey),
            )
          }

      if (pendingStateUpdatesForKey == null) {
        continue
      }

      if (pendingStateUpdatesForKey.size == appliedStateUpdatesForKey.size) {
        synchronized(this) {
          _pendingStateUpdates?.remove(appliedStateUpdateKey)
          _pendingLazyStateUpdates?.remove(appliedStateUpdateKey)
        }
      } else {
        pendingStateUpdatesForKey.removeAll(appliedStateUpdatesForKey)
        pendingLazyStateUpdatesForKey?.removeAll(appliedStateUpdatesForKey)
      }
    }
  }

  @get:Synchronized
  val state: Map<GlobalKey, ComponentState<out StateContainer>>?
    get() = _state

  /**
   * Visits the [useSaveableState] entries of this handler. [block] runs under this handler's lock,
   * so it should only collect.
   */
  @Synchronized
  internal fun forEachSaveableEntry(block: (slot: SaveableStateSlot, value: Any?) -> Unit) {
    _state?.forEach { (_, componentState) ->
      (componentState.value as? KStateContainer)?.states?.forEach { entry ->
        val deps = entry.deps
        if (deps.size == 1) (deps[0] as? SaveableStateSlot)?.let { block(it, entry.value) }
      }
    }
  }

  @get:Synchronized
  val pendingStateUpdates: Map<StateId, MutableList<StateUpdate>>?
    get() = _pendingStateUpdates

  @get:Synchronized
  val pendingLazyStateUpdates: Map<StateId, MutableList<StateUpdate>>?
    get() = _pendingLazyStateUpdates

  @get:Synchronized
  val pendingStateUpdateTransitions: Map<StateId, List<Transition>>?
    get() = _pendingStateUpdateTransitions

  @get:Synchronized
  @get:VisibleForTesting
  val appliedStateUpdates: Map<StateId, List<StateUpdate>>?
    get() = _appliedStateUpdates

  @Synchronized
  fun getCachedValue(cacheKey: HookGlobalKey, cachedValueInputs: Any): Any? {
    val cacheValue = cachedValues?.get(cacheKey) ?: return null

    if (areObjectsEquivalent(cacheValue.inputs, cachedValueInputs)) {
      if (cacheValue.inputsHash != cachedValueInputs.hashCode()) {
        // Log mutable input detection
        DebugEventDispatcher.dispatch(LithoDebugEvent.DebugInfo, DebugEvent.NoId, LogLevel.ERROR) {
            attribute ->
          attribute[Name] = "StateHandler:MutableTypeUsedAsCachedValueDep"
          attribute[Description] = "Unexpected mutable value used as CachedValue dep"
          attribute[Source] = cachedValueInputs.javaClass.name
        }
        return null
      }
      return cacheValue.value
    }
    return null
  }

  @Synchronized
  fun putCachedValue(cacheKey: HookGlobalKey, cachedValueInputs: Any, cachedValue: Any?) {
    if (cachedValue == null) {
      cachedValues?.remove(cacheKey)
      return
    }
    if (cachedValues == null) {
      cachedValues = LinkedHashMap()
    }
    val value = CacheValue(cachedValueInputs, cachedValueInputs.hashCode(), cachedValue)
    cachedValues?.put(cacheKey, value)
  }

  /**
   * Copies the information from the given map of state updates into the map of pending state
   * updates.
   */
  @Synchronized
  private fun copyPendingUpdates(stateHandler: StateHandler) {
    _pendingStateUpdates?.let { source ->
      if (source.isNotEmpty()) {
        source.mapValuesTo(stateHandler.getOrCreatePendingStateUpdates()) { (_, v) ->
          v.toMutableList()
        }
      }
    }
    _appliedStateUpdates?.let { source ->
      if (source.isNotEmpty()) {
        source.mapValuesTo(stateHandler.getOrCreateAppliedStateUpdates()) { (_, v) -> v }
      }
    }
    appliedHookUpdates?.let { source ->
      if (source.isNotEmpty()) {
        source.mapValuesTo(stateHandler.getOrCreateAppliedHookUpdates()) { (_, v) -> v }
      }
    }
    pendingHookUpdates?.let { source ->
      if (source.isNotEmpty()) {
        source.mapValuesTo(stateHandler.getOrCreatePendingHookUpdates()) { (_, v) ->
          v.toMutableList()
        }
      }
    }
    _pendingLazyStateUpdates?.let { source ->
      if (source.isNotEmpty()) {
        source.mapValuesTo(stateHandler.getOrCreatePendingLazyStateUpdates()) { (_, v) ->
          v.toMutableList()
        }
      }
    }
  }

  /**
   * Copies the list of given state containers into the map that holds the current state containers
   * of components.
   */
  private fun copyCurrentState(state: Map<GlobalKey, ComponentState<out StateContainer>>?) {
    synchronized(this) {
      _state = if (state.isNullOrEmpty()) null else HashMap(state)
    }
  }

  private fun copyPendingStateTransitions(
      pendingStateUpdateTransitions: Map<StateId, List<Transition>>?,
  ) {
    if (pendingStateUpdateTransitions.isNullOrEmpty()) {
      return
    }

    synchronized(this) {
      getOrCreatePendingStateUpdateTransitions().putAll(pendingStateUpdateTransitions)
    }
  }

  //
  // Hooks - see KState.kt
  //

  /**
   * Registers the given block to be run before the next layout calculation to update hook state.
   */
  @Synchronized
  fun queueHookStateUpdate(stateId: StateId, updater: HookUpdater) {
    val hookUpdatesMap = getOrCreatePendingHookUpdates()
    var hookUpdaters = hookUpdatesMap[stateId.globalKey]
    if (hookUpdaters == null) {
      hookUpdaters = ArrayList()
      hookUpdatesMap[stateId.globalKey] = hookUpdaters
    }

    hookUpdaters.add(HookUpdateData(stateId, updater))
  }

  @get:VisibleForTesting
  val pendingHookUpdatesCount: Int
    get() = pendingHookUpdates?.asSequence()?.sumOf { it.value.size } ?: 0

  /**
   * Called when creating a new StateHandler for a layout calculation. It copies the source of truth
   * state, and then the current list of HookUpdater blocks that need to be applied. Unlike normal
   * state, these blocks are run immediately to update this StateHandlers hook state before we start
   * creating components.
   *
   * @param other the ComponentTree's source-of-truth StateHandler where pending state updates are
   *   collected
   */
  private fun runHooks() {
    val pending = pendingHookUpdates ?: return
    for ((key, value) in pending) {
      val current = _state?.get(key) as ComponentState<KStateContainer>?
      val stateContainer = current?.value
      /* currentState could be null if the state is removed from the StateHandler before the update runs */
      if (stateContainer is KStateContainer) {
        var kStateContainer: KStateContainer = stateContainer

        for (update in value) {
          kStateContainer = update.updater.getUpdatedStateContainer(kStateContainer)
        }

        getOrCreateState()[key] = current.copy(value = kStateContainer)
      }
    }
    getOrCreateAppliedHookUpdates().putAll(pending)
    pendingHookUpdates = null
  }

  /**
   * Gets a state container with all applied updates for the given key without committing the
   * updates to a state handler.
   */
  fun getKStateWithUpdates(globalKey: GlobalKey): KStateContainer? {
    val stateContainer: StateContainer?
    val updates: List<HookUpdateData>?
    synchronized(this) {
      stateContainer = _state?.get(globalKey)?.value ?: return null
      updates = pendingHookUpdates?.get(globalKey)?.let { ArrayList(it) }
    }

    if (updates == null) {
      return stateContainer as KStateContainer
    }

    var updatedState = stateContainer as KStateContainer
    for (update in updates) {
      updatedState = update.updater.getUpdatedStateContainer(updatedState)
    }

    return updatedState
  }

  /**
   * Called on the ComponentTree's source-of-truth StateHandler when a layout has completed and new
   * state needs to be committed. In this case, we want to remove any pending state updates that
   * this StateHandler applied, while leaving new ones that have accumulated in the interim. We also
   * copy over the new mapping from hook state keys to values.
   */
  private fun commitHookState(appliedHookUpdates: Map<GlobalKey, List<HookUpdateData>>) {
    if (appliedHookUpdates.isEmpty() || pendingHookUpdates.isNullOrEmpty()) {
      return
    }

    for ((globalKey, appliedHookUpdatersForKey) in appliedHookUpdates) {
      val pendingHookUpdatersForKey: MutableList<HookUpdateData>?
      synchronized(this) {
        pendingHookUpdatersForKey = pendingHookUpdates?.get(globalKey)
        if (!pendingHookUpdatersForKey.isNullOrEmpty()) {
          pendingHookUpdatersForKey.removeAll(appliedHookUpdatersForKey)
          if (pendingHookUpdatersForKey.isEmpty()) {
            pendingHookUpdates?.remove(globalKey)
          }
        }
      }
    }
  }

  companion object {
    private const val INITIAL_STATE_UPDATE_LIST_CAPACITY = 4
    private const val INITIAL_MAP_CAPACITY = 4

    private fun addStateUpdateForKey(
        key: StateId,
        stateUpdate: StateUpdate,
        map: MutableMap<StateId, MutableList<StateUpdate>>,
    ) {
      var pendingStateUpdatesForKey = map[key]
      if (pendingStateUpdatesForKey == null) {
        pendingStateUpdatesForKey = createStateUpdatesList()
        map[key] = pendingStateUpdatesForKey
      }

      pendingStateUpdatesForKey.add(stateUpdate)
    }

    private fun handleExceptionDuringApplyStateUpdate(
        key: StateId,
        current: LithoNode,
        exception: Exception,
    ) {
      val scopedComponentInfos = current.scopedComponentInfos
      val targetKey: GlobalKey = key.globalKey
      for (scopedComponentInfo in scopedComponentInfos) {
        val context = scopedComponentInfo.context
        if (context.nullableGlobalKey == targetKey) {
          ComponentUtils.handleWithHierarchy(context, scopedComponentInfo.component, exception)
          break
        }
      }
      for (index in 0 until current.childCount) {
        val childLithoNode = current.getChildAt(index)
        val headKey = childLithoNode.headComponentKey
        if (targetKey.isOrIsDescendantOf(headKey)) {
          handleExceptionDuringApplyStateUpdate(key, childLithoNode, exception)
        }
      }
    }

    private fun createStateUpdatesList(
        copyFrom: List<StateUpdate>? = null,
    ): MutableList<StateUpdate> {
      val list: MutableList<StateUpdate> =
          ArrayList(copyFrom?.size ?: INITIAL_STATE_UPDATE_LIST_CAPACITY)
      if (copyFrom != null) {
        list.addAll(copyFrom)
      }
      return list
    }

    private fun createHookStateUpdatesList(
        copyFrom: List<HookUpdateData>? = null,
    ): MutableList<HookUpdateData> {
      val list: MutableList<HookUpdateData> =
          ArrayList(copyFrom?.size ?: INITIAL_STATE_UPDATE_LIST_CAPACITY)
      if (copyFrom != null) {
        list.addAll(copyFrom)
      }
      return list
    }

    private fun clearUnusedState(currentStateHandler: StateHandler) {
      val state = currentStateHandler._state
      if (state.isNullOrEmpty()) {
        return
      }

      val neededState = currentStateHandler.neededState
      val stateKeys = ArrayList(state.keys)

      for (key in stateKeys) {
        if (neededState == null || key !in neededState) {
          state.remove(key)
        }
      }
    }

    private fun getHookUpdatesCopy(
        copyFrom: Map<GlobalKey, List<HookUpdater>>,
    ): Map<GlobalKey, List<HookUpdater>> {
      val copyInto: MutableMap<GlobalKey, List<HookUpdater>> = HashMap(copyFrom.size)
      for ((key, value) in copyFrom) {
        copyInto[key] = ArrayList(value)
      }
      return copyInto
    }
  }
}

private class HookUpdateData(val stateId: StateId, val updater: HookUpdater)

sealed interface StateUpdateApplier

private class CacheValue(val inputs: Any, val inputsHash: Int, val value: Any)
