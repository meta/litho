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

import android.view.View
import androidx.annotation.UiThread
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.animation.AnimationController
import com.facebook.litho.state.StateId
import com.facebook.litho.state.TreeStateProvider
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.thread.utils.ThreadUtils.assertMainThread

/**
 * The [NestedLithoTree] should be used to render Litho components in a container, say a Primitive
 * like a ScrollView or when embedded Litho hierarchies in other RC framework hierarchies.
 *
 * The [NestedLithoTree] lets the client to manually request resolutions, and layouts. It also lets
 * the client intercept state updates so that updates can be synchronised with their render
 * lifecycle.
 */
object NestedLithoTree {

  fun resolve(
      id: Int,
      context: ComponentContext,
      root: Component,
      treeProps: TreePropContainer?,
      state: TreeState,
      current: ResolveResult?,
      version: Int,
  ): ResolveResult {
    return if (
        current == null ||
            !ComponentUtils.isEquivalent(root, current.component) ||
            state.keysForPendingStateUpdates.isNotEmpty() ||
            treeProps != current.context.treePropContainer
    ) {
      // Register here; unregister happens later in NestedLithoTreeState.commitAndRunEffects()
      // after the result has been committed. Unlike ComponentTree (which commits inside the same
      // scope), NestedLithoTree's commit is deferred until after layout and mount.
      //
      // The register/unregister is needed to clear the InitialState cache between resolve cycles.
      // Without clearing, a removed-then-recreated component can inherit stale initial state:
      //
      //   1. Resolve: component P created → InitialState caches valueA for keyP
      //   2. Commit: treeState updated with valueA
      //   3. Resolve: component P removed (conditional render changed)
      //   4. Commit: StateHandler cleans up keyP from neededState
      //   5. Resolve: new component at position P created
      //      → StateHandler: no value (cleaned in step 4)
      //      → InitialState cache: still has valueA (never cleared!)
      //      → Returns stale valueA instead of running the new initializer
      //
      // With register/unregister spanning resolve → commit, the cache is cleared after step 2
      // and step 4, so step 5 correctly runs the initializer.
      val resolveScope = InitialStateScope.forResolve(state)
      try {
        ResolveTreeFuture.resolve(
            context,
            root,
            state,
            version,
            id,
            current,
            "nested-resolve",
            null, // tree future is null; effectively cannot be cancelled
        )
      } catch (e: Throwable) {
        resolveScope.close()
        throw e
      }
    } else {
      current
    }
  }

  fun layout(
      result: ResolveResult,
      sizeConstraints: SizeConstraints,
      current: LayoutState?,
      version: Int,
  ): LayoutState {
    val layoutState =
        if (result != current?.resolveResult || sizeConstraints != current.sizeConstraints) {
          val treeState = TreeState(result.treeState)
          // Register here; unregister happens in NestedLithoTreeState.commitAndRunEffects().
          val layoutScope = InitialStateScope.forLayout(treeState)
          try {
            LayoutTreeFuture.layout(
                result,
                sizeConstraints,
                version,
                checkNotNull(result.context.lithoTree).id,
                treeState,
                current,
                current?.diffTree,
                null, // tree future is null; task cannot be cancelled
            )
          } catch (e: Throwable) {
            layoutScope.close()
            throw e
          }
        } else {
          current
        }

    layoutState.toRenderTree()

    return layoutState
  }

  fun LayoutState.commit() {
    AnimationController.bindContext(componentContext.androidContext)
    val components = consumeComponentScopes()
    val stateUpdater = checkNotNull(componentContext.stateUpdater)
    val mountedView = checkNotNull(componentContext.lithoTree).rootHost

    if (components != null) {
      treeState.updateEventDispatchers(components)
    }
    // clear state updates
    treeState.commit()

    // bind handles
    componentHandles?.forEach { handle ->
      handle.setStateUpdaterAndRootViewReference(stateUpdater, mountedView)
    }
  }

  fun LayoutState.cleanup() {
    treeState.effectsHandler.onCleanup()
    treeState.clearEventHandlersAndTriggers()
  }

  fun TreeState.enqueue(updates: List<PendingStateUpdate>): TreeState {
    for (update in updates) {
      this.enqueue(update)
    }
    return this
  }

  fun createLithoTree(
      id: Int,
      stateUpdater: NestedStateUpdater,
      mountedViewReference: NestedMountedViewReference,
      errorComponentReceiver: ErrorComponentReceiver,
      treeLifecycleProvider: NestedLithoTreeLifecycleProvider,
  ): LithoTree = LithoTree(
      id = id,
      treeStateProvider = stateUpdater,
      stateUpdater = stateUpdater,
      rootHost = mountedViewReference,
      errorComponentReceiver = errorComponentReceiver,
      treeLifecycle = treeLifecycleProvider,
      uiStateReadRecordsProvider = {
        val host = mountedViewReference.mountedView
        check(host is BaseMountingView) {
          "Trying to get UI state read records without a set host"
        }
        host.uiStateReadRecords
      },
  )

  fun createComponentContext(
      androidContext: android.content.Context,
      treePropContainer: TreePropContainer?,
      lithoConfiguration: LithoConfiguration,
      lithoTree: LithoTree,
      lithoVisibilityEventsController: LithoVisibilityEventsController? = null,
  ): ComponentContext = ComponentContext(
      androidContext,
      treePropContainer,
      lithoConfiguration,
      lithoTree,
      StringGlobalKey("nested-tree-root"),
      lithoVisibilityEventsController,
      null,
      null,
  )

  fun TreeState.enqueue(update: PendingStateUpdate): TreeState {
    when (update.updater) {
      is HookUpdater -> {
        queueHookStateUpdate(
            stateId = update.stateId,
            updater = update.updater,
            isLayoutState = update.isLayoutState,
        )
      }
      is StateContainer.StateUpdate -> {
        queueStateUpdate(
            key = update.stateId,
            stateUpdate = update.updater,
            update.isLazy,
            isLayoutState = update.isLayoutState,
        )
      }
    }
    return this
  }
}

// region NestedStateUpdater
class NestedStateUpdater
constructor(
    private val treeId: Int,
    private val getState: () -> TreeState?,
    private val updater: StateUpdateRequester,
) : StateUpdater, TreeStateProvider {

  private val treeState: TreeState?
    get() = getState()

  override val uiTreeState: TreeState?
    @Synchronized get() = treeState

  override var isFirstMount: Boolean
    get() = treeState?.mountInfo?.isFirstMount ?: false
    set(value) {
      treeState?.mountInfo?.isFirstMount = value
    }

  override fun updateHookStateAsync(
      stateId: StateId,
      updateBlock: HookUpdater,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    updater.request(
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
    updater.request(
        PendingStateUpdate(
            stateId = stateId,
            updater = updateBlock,
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
    updater.request(
        PendingStateUpdate(
            stateId = StateId.forSpec(treeId, globalKey),
            updater = stateUpdate,
            isLayoutState = isLayoutState,
            isAsync = true,
            attribution = attribution,
        ),
    )
  }

  override fun updateStateSync(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    updater.request(
        PendingStateUpdate(
            stateId = StateId.forSpec(treeId, globalKey),
            updater = stateUpdate,
            isLayoutState = isLayoutState,
            isAsync = false,
            attribution = attribution,
        ),
    )
  }

  override fun updateStateLazy(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      isLayoutState: Boolean,
  ) {
    updater.request(
        PendingStateUpdate(
            stateId = StateId.forSpec(treeId, globalKey),
            updater = stateUpdate,
            isLayoutState = isLayoutState,
            isAsync = false,
            isLazy = true,
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
        StateId.forSpec(treeId, globalKey),
        container,
        isLayoutState,
    ) ?: container
  }

  override fun <T> canSkipStateUpdate(
      stateId: StateId,
      newValue: T?,
      isLayoutState: Boolean,
  ): Boolean {
    return treeState?.canSkipStateUpdate(stateId, newValue, isLayoutState) ?: false
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
    ) ?: false
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

  override fun getEventTrigger(key: Any): EventTrigger<*>? {
    return treeState?.getEventTrigger(triggerKey = key)
  }

  override fun getEventTrigger(handle: Handle, id: Int): EventTrigger<*>? {
    return treeState?.getEventTrigger(handle = handle, methodId = id)
  }
}

// endregion NestedStateUpdater

class NestedMountedViewReference : MountedViewReference {

  private var view: View? = null

  override var mountedView: View?
    @UiThread
    get() {
      return view
    }
    @UiThread
    set(value) {
      view = value
    }
}

// region NestedLithoTreeLifecycleProvider
class NestedLithoTreeLifecycleProvider : LithoTreeLifecycleProvider {

  private val listeners: MutableList<LithoTreeLifecycleProvider.OnReleaseListener> = mutableListOf()

  @Volatile private var _isReleased: Boolean = false

  override val isReleased: Boolean
    get() = _isReleased

  override fun addOnReleaseListener(listener: LithoTreeLifecycleProvider.OnReleaseListener) {
    synchronized(this) {
      if (!isReleased) {
        listeners.add(listener)
      }
    }
  }

  override fun removeOnReleaseListener(listener: LithoTreeLifecycleProvider.OnReleaseListener) {
    synchronized(this) { listeners.remove(listener) }
  }

  @UiThread
  fun release() {
    assertMainThread()
    // Snapshot first: a listener that removes itself must not make the loop skip the next one.
    val toNotify =
        synchronized(this) {
          if (_isReleased) {
            return
          }
          _isReleased = true
          listeners.toList().also { listeners.clear() }
        }
    toNotify.forEach { it.onReleased() }
  }
}

// endregion

fun interface StateUpdateRequester {
  fun request(update: PendingStateUpdate)
}

@DataClassGenerate(toString = Mode.OMIT, equalsHashCode = Mode.KEEP)
data class PendingStateUpdate(
    val stateId: StateId,
    val updater: StateUpdateApplier,
    val isLayoutState: Boolean, // state created during the layout phase
    val isAsync: Boolean,
    val isLazy: Boolean = false,
    val attribution: String? = null,
)
