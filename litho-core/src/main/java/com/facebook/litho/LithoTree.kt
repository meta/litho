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

import com.facebook.infer.annotation.ThreadConfined
import com.facebook.litho.animation.ReflowAnimationStore
import com.facebook.litho.animation.ReflowProvider
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.state.StateProviderImpl
import com.facebook.litho.state.TreeStateProvider
import com.facebook.litho.state.UiStateReadRecordsProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal typealias LithoRendererLifecycleProvider = LithoTreeLifecycleProvider

/** Represents a pointer to the Tree that a ComponentContext is attached to */
class LithoTree
internal constructor(
    val id: Int,
    /** The source of this tree's committed state, which saveable-state registries save from. */
    internal val treeStateProvider: TreeStateProvider,
    @field:ThreadConfined(ThreadConfined.ANY) val stateUpdater: StateUpdater,
    @field:ThreadConfined(ThreadConfined.UI) val rootHost: MountedViewReference,
    val errorComponentReceiver: ErrorComponentReceiver,
    val treeLifecycle: LithoRendererLifecycleProvider,
    internal val uiStateReadRecordsProvider: UiStateReadRecordsProvider,
    val isReadTrackingEnabled: Boolean =
        ComponentsConfiguration.defaultInstance.enableStateReadTracking,
) {

  @field:ThreadConfined(ThreadConfined.ANY)
  val stateProvider: com.facebook.litho.state.StateProvider =
      StateProviderImpl(id, isReadTrackingEnabled, treeStateProvider)

  /** This tree's reflow surface, held as the abstraction; see [ReflowProvider]. */
  @JvmField internal val reflowProvider: ReflowProvider = ReflowAnimationStore(this)

  // Used to lazily store a CoroutineScope, if coroutine helper methods are used.
  @JvmField val internalScopeRef: AtomicReference<Any> = AtomicReference<Any>()

  companion object {

    private val IdGenerator = AtomicInteger(0)

    @JvmOverloads
    fun create(
        componentTree: ComponentTree,
        stateUpdater: StateUpdater,
        isReadTrackingEnabled: Boolean =
            ComponentsConfiguration.defaultInstance.enableStateReadTracking,
    ): LithoTree = LithoTree(
        id = componentTree.id,
        treeStateProvider = componentTree,
        stateUpdater = stateUpdater,
        rootHost = componentTree,
        errorComponentReceiver = componentTree,
        treeLifecycle = componentTree,
        uiStateReadRecordsProvider = componentTree,
        isReadTrackingEnabled = isReadTrackingEnabled,
    )

    @JvmStatic
    fun generateComponentTreeId(): Int {
      return IdGenerator.getAndIncrement()
    }
  }
}
