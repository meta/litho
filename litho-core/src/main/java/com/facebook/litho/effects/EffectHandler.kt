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

package com.facebook.litho.effects

import androidx.annotation.UiThread
import com.facebook.litho.Attachable
import com.facebook.litho.ComponentTree
import com.facebook.litho.annotations.OnAttached
import com.facebook.litho.annotations.OnDetached
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.state.StateId

/**
 * Manages the execution and update of [effects][Attachable] within a [ComponentTree].
 *
 * For Spec Components, this will invoke [OnAttached] when a component is attached to the
 * [ComponentTree] and [OnDetached] when a component is detached from the tree.
 *
 * For Kotlin components, this will handle dispatching callbacks registered with the [useEffect]
 * hook.
 */
@UiThread
interface EffectHandler {

  val effects: Collection<Attachable>?

  /**
   * Updates the set of [effects][Attachable] within the associated [ComponentTree].
   *
   * When this method is invoked, the existing list is diffed with the incoming [effects] to
   * determine the set of actions needed to advance to the new generation of [effects]. Note that
   * identity is determined by [Attachable.uniqueId].
   *
   * Effects to cleanup are determined by the following:
   * - Any existing [Attachable] that is no longer present will be cleaned up
   * - Any [Attachable] whose [Attachable.shouldUpdate] returns `true` will be cleaned up
   * - When state tracking is enabled, any [Attachable] that reads an updated [State] will also be
   *   cleaned up
   *
   * Effects to run are determined by the following:
   * - All new effects will be run
   * - Any [Attachable] present in the new generation, but was previously detached will be run
   *
   * Note that the returned [Runnable] must be called on the main thread.
   *
   * @return a [Runnable] that runs the incoming effects. Implementations should ensure that the
   *   effects are only ran once, even if the runnable is invoked multiple times.
   */
  fun EffectContext.updateEffects(effects: List<Attachable>?): Runnable

  /**
   * Whether any currently-attached effect recorded a read of any of [updatedState].
   *
   * Lets a same-frame fast path cheaply exclude effect-reading values: effects only re-run at
   * commit, so such a value must take the normal (committing) path. Defaults to `false` for
   * handlers without read tracking.
   */
  fun hasReadsFor(updatedState: Set<StateId>): Boolean = false

  /**
   * Removes all associated [Attachable]s and any transient state.
   *
   * The method is typically called when the owner of this handler is being released and needs to
   * cleanup.
   */
  fun onCleanup()

  companion object {
    internal val EmptyRunnable: Runnable = Runnable {}
  }
}

inline fun EffectHandler.updateAndRun(
    context: EffectContext,
    newEffects: List<Attachable>?,
    onUpdate: () -> Unit,
) {
  var runnable: Runnable? = null
  try {
    runnable = context.updateEffects(newEffects)
    onUpdate()
  } finally {
    runnable?.run()
  }
}

class EffectContext
internal constructor(
    val treeId: Int,
    val isReadTrackingEnabled: Boolean,
    val updatedState: Set<StateId>,
)

fun EffectHandler(
    useOptimizedContextualEffectHandler: Boolean =
        ComponentsConfiguration.defaultInstance.useOptimizedContextualEffectHandler,
): EffectHandler =
    when {
      useOptimizedContextualEffectHandler -> OptimizedContextualEffectHandler()
      else -> ContextualEffectHandler()
    }
