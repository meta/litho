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

@file:OptIn(ExperimentalLithoApi::class)

package com.facebook.litho

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.state.ComponentState

/**
 * A tree whose committed state holds one `useSaveableState` value under [key] in [registry], as
 * after the hook's first render committed, without rendering. [close] releases the tree, so it must
 * run on the main thread.
 */
internal class LiveValue(
    registry: LithoSaveableStateRegistry,
    key: String,
    value: Any?,
    saver: Saver<Any?> = IdentitySaver,
) : AutoCloseable {
  private val tree =
      ComponentTree.create(ComponentContext(ApplicationProvider.getApplicationContext<Context>()))
          .build()
  private val slot = SaveableStateSlot(null, registry, key, saver, registry.generation)

  init {
    set(value)
    registry.addSource(checkNotNull(tree.context.lithoTree))
  }

  fun set(value: Any?) {
    checkNotNull(tree.committedTreeState)
        .addState(
            GLOBAL_KEY,
            ComponentState(
                KStateContainer.withNewStateByHookKey(null, HOOK_KEY, value, arrayOf(slot)),
            ),
            isLayoutState = false,
        )
  }

  override fun close() {
    tree.release()
  }

  private companion object {
    val GLOBAL_KEY: GlobalKey = StringGlobalKey("live value")
    val HOOK_KEY: HookKey = HookKey.create(callSiteId = 1, index = 0, parent = null)
  }
}

private val IdentitySaver: Saver<Any?> = Saver(save = { it }, restore = { it })

/** A registry snapshot holding [values] by key. Use [childKey] for a child registry's snapshot. */
internal fun savedState(vararg values: Pair<String, Any?>): Bundle =
    Bundle().apply { values.forEach { (key, value) -> putBundle(key, bundleOf("value" to value)) } }

/** The key a parent registry saves the snapshot of its child created with [key] under. */
internal fun childKey(key: String): String = "litho-child-registry:$key"
