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

package com.facebook.litho.state

import com.facebook.litho.HookKey
import com.facebook.litho.KStateContainer
import com.facebook.litho.State
import com.facebook.litho.ThreadUtils
import com.facebook.litho.TreeState
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.utils.LithoThreadLocal
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * StateProvider is an abstraction used to supply the latest (and most accurate) value of a given
 * [State] in a given scope, when read tracking is enabled.
 *
 * This makes it possible for the same [State] object to return different [State.value] in various
 * scopes, unlocking new possibilities. For example, a state that's read in post-mount will have the
 * correct value without having to recreate that state object when its value gets updated. This
 * works even if the previous phases were completely skipped.
 */
interface StateProvider {
  /**
   * An identifier for the state provider's tree.
   *
   * It is especially useful in a setting where state may be read across tree boundaries. In such
   * case, it may be desirable to adjust the access/tracking logic accordingly.
   */
  val treeId: Int

  /** Whether read tracking is enabled for this state provider. */
  val isReadTrackingEnabled: Boolean

  /**
   * Overrides the current scope with the given [source] and returns that (current) scope.
   *
   * @param [source] [TreeState] that should be used to supply [State] value
   * @return the current scope that was overridden or null if no scope is currently active
   */
  fun getAndSet(source: TreeState?): TreeState?

  /**
   * Retrieves the current value of the given [state] from the active scope.
   *
   * @param [state] state whose value is being requested
   */
  fun <T> getValue(state: State<T>): T
}

@OptIn(ExperimentalContracts::class)
inline fun StateProvider.withScope(source: TreeState, block: () -> Unit) {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  if (!isReadTrackingEnabled) {
    return block()
  }
  val previous = getAndSet(source)
  if (previous != null && ComponentsConfiguration.defaultInstance.logRecursiveStateProviderScope) {
    // Log attempt to enter a new scope while we're already in one
    DebugInfoReporter.report("StateProvider:recursiveScope", renderStateId = treeId) {
      val stacktrace = buildString {
        val traces = Thread.currentThread().getStackTrace()
        val start = 15.coerceAtMost(traces.lastIndex)
        val end = 25.coerceAtMost(traces.size)
        for (i in start..end) appendLine(traces[i])
      }
      put(LithoDebugEventAttributes.Stack, stacktrace)
    }
  }
  try {
    block()
  } finally {
    val exiting = getAndSet(previous)
    check(exiting === source)
  }
}

/**
 * Internal abstraction used to provide the default [TreeState] for use by the [StateProvider].
 *
 * The [TreeState] supplied here is typically used whenever there's no active scope in flight.
 */
internal interface TreeStateProvider {
  /**
   * The default [TreeState] needed by the [StateProvider] when there's no active scope in flight.
   *
   * This value can also be null in some rare cases, which typically indicates that the tree state
   * may have been disposed.
   */
  val committedTreeState: TreeState?
    get() = uiTreeState

  /**
   * The default stable [TreeState] needed by the [StateProvider] as a fallback for UI thread reads
   * when there's no active scope in flight.
   *
   * This value can also be null in some rare cases, which typically indicates that the tree state
   * may have been disposed.
   */
  val uiTreeState: TreeState?

  /**
   * Whether this tree is bound to a host, so saveable-state registries save its values. A tree that
   * is never unbound from its host, like a ComponentTree, always is.
   */
  val isBoundToHost: Boolean
    get() = true
}

internal class StateProviderImpl(
    override val treeId: Int,
    override val isReadTrackingEnabled: Boolean,
    private val treeStateProvider: TreeStateProvider,
) : StateProvider {

  private val currentSource = LithoThreadLocal<TreeState>()

  override fun getAndSet(source: TreeState?): TreeState? {
    val previous = currentSource.get()
    currentSource.set(source)
    return previous
  }

  @Suppress("UNCHECKED_CAST")
  override fun <T> getValue(state: State<T>): T {
    val stateId = state.stateId
    require(stateId.treeId == treeId) {
      "State tree (id=${stateId.treeId}) does not match StateProvider tree (id=$treeId)"
    }
    val componentState =
        when (val source = currentSource.get()) {
          null -> {
            val treeState =
                when {
                  ThreadUtils.isMainThread -> treeStateProvider.uiTreeState
                  else -> treeStateProvider.committedTreeState
                }
            // The component state for a given key may not exist on the main tree state which
            // may imply that the corresponding component was removed from the tree
            treeState?.getState(stateId.globalKey, state.isLayoutContext) ?: return state.fallback
          }
          else ->
              // The component state must be present when accessing the overridden tree state
              // since access requires prior creation
              checkNotNull(source.getState(stateId.globalKey, state.isLayoutContext))
        }
    check(componentState.value is KStateContainer)
    val container = componentState.value
    val hookKey = stateId.hookGlobalKey.hookKey as HookKey
    val cached = container.getByHookKey(hookKey)
    // A State handle captured before its hook was pruned retains its last-known value.
    return if (cached != null) {
      cached.value as T
    } else {
      state.fallback
    }
  }
}
