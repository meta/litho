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

import androidx.annotation.RestrictTo
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.state.StateReadRecorder
import java.util.Objects

/** Converts a value to and from a representation supported by Android saved state. */
@ExperimentalLithoApi
interface Saver<T> {
  fun save(value: T): Any?

  fun restore(value: Any?): T
}

/** Creates a [Saver] from its save and restore operations. */
@ExperimentalLithoApi
fun <T> Saver(save: (T) -> Any?, restore: (Any?) -> T): Saver<T> =
    object : Saver<T> {
      override fun save(value: T): Any? = save.invoke(value)

      override fun restore(value: Any?): T = restore.invoke(value)
    }

/**
 * The only dep of a [useSaveableState] state entry, so it moves with the entry through every
 * [TreeState] copy and leaves with it.
 *
 * Equal slots keep the entry's value: a new explicit key or a newer [generation] initializes it
 * again, a new [saver] does not.
 */
internal class SaveableStateSlot(
    val explicitKey: String?,
    /** The registry the hook saves into, or null when there is none. */
    val scope: LithoSaveableStateRegistry?,
    /** Built once, and only when there is a [scope]. */
    val storageKey: String?,
    saver: Saver<*>,
    /** The [LithoSaveableStateRegistry.generation] of [scope] this slot was created in. */
    val generation: Int,
) {
  /** The saver of the latest render. */
  @Volatile var saver: Saver<*> = saver

  /** Whether the entry's initial value was restored from [scope]. */
  @Volatile var restored: Boolean = false

  /** False once a late restore or a close of [scope] makes the hook read its registry again. */
  val isCurrent: Boolean
    get() = scope == null || scope.generation == generation

  /**
   * The value restored from [scope] if there is one, marking this slot [restored], else [fallback].
   */
  fun <T> initialValue(fallback: () -> T): T {
    val restoredValue = storageKey?.let { scope?.restoredValue(it) } ?: return fallback()
    restored = true
    @Suppress("UNCHECKED_CAST")
    return (saver as Saver<T>).restore(restoredValue.value)
  }

  @Suppress("UNCHECKED_CAST") fun save(value: Any?): Any? = (saver as Saver<Any?>).save(value)

  override fun equals(other: Any?): Boolean =
      other is SaveableStateSlot &&
          other.explicitKey == explicitKey &&
          other.scope === scope &&
          other.generation == generation

  override fun hashCode(): Int =
      Objects.hash(explicitKey, System.identityHashCode(scope), generation)
}

/**
 * Declares component state that can be restored by a lifecycle-backed host.
 *
 * The owning registry, component identity, hook call site, and optional [key] identify the value.
 * The [initializer] runs when no value has been retained or restored for that identity. Values
 * captured by [initializer] are initial inputs rather than observable dependencies; use [key] to
 * represent a different logical state owner.
 *
 * When this component leaves the committed tree, its value is no longer saved. A lifecycle or
 * navigation host that replaces the whole ComponentTree can retain the tree's registry explicitly
 * so active values survive into the replacement tree.
 *
 * Saving reads the last committed value, so an update that has not committed yet may not be saved.
 * A restored value is taken from saved state when the render that read it commits, so a component
 * that commits and leaves before it is shown starts from its [initializer] when it comes back.
 *
 * The hook finds its registry and storage key on its first render and keeps them. A different
 * registry above it is picked up only on its next render after the first one closes. So a
 * [TreeState] given to a tree with another registry while the tree it came from is still alive is
 * saved by the new registry only once that tree is released and the new one renders again.
 *
 * The [saver] must encode the value into a type supported by Android [android.os.Bundle]. The
 * default saver stores Bundle-compatible values directly. A value the saver can't turn into a
 * supported type fails on the hook's first render under an open registry. The factory and saver may
 * run on a Litho resolve thread and must not access Views or other main-thread-only objects. A save
 * uses the saver of the latest render and may run on any thread.
 *
 * A root ComponentTree created with a [androidx.savedstate.SavedStateRegistryOwner] saves its
 * values under its root component's class and manual key:
 * - Two root trees with the same identity on screen at the same time are not saved; give them
 *   distinct manual keys with `key(...) { ... }`.
 * - A tree that replaces another of its identity, such as a new tree set on the same view, starts
 *   from initial values and is saved from then on.
 * - A tree built with [ComponentTree.Builder.withSaveableStateKey] is saved under that key instead,
 *   and the next tree with the key takes its values over, even while the screen is visible. Only
 *   the 32 most recently released keys keep their values.
 *
 * Nested ComponentTrees save inside their parent tree.
 *
 * Without an owning registry, this behaves like [useState]: the value lasts for the current
 * ComponentTree and is not restored after it is released.
 */
@ExperimentalLithoApi
@Hook
fun <T> ComponentScope.useSaveableState(
    key: String? = null,
    saver: Saver<T> = autoSaver(),
    initializer: () -> T,
): State<T> {
  val lithoTree = context.lithoTree ?: error("LithoTree is null")
  val hookKey = requireCurrentHookKey()
  val stored = storedHookEntry(hookKey)
  // State is already identified by the component's global key and the hook's position, so only
  // the explicit key can change the storage key of a live hook.
  val slot = (stored?.deps?.singleOrNull() as? SaveableStateSlot)?.takeIf { it.explicitKey == key }
  val value: T =
      if (stored != null && slot != null && slot.isCurrent) {
        if (slot.saver !== saver) slot.saver = saver
        getUpdatedStateByHookKey(hookKey, stored.deps, initializer)
      } else {
        initSaveableState(
            lithoTree,
            hookKey,
            key,
            saver,
            stored?.takeIf { slot != null },
            initializer,
        )
      }
  return hookState(lithoTree, hookKey, value)
}

private fun ComponentScope.storedHookEntry(hookKey: HookKey): CachedValue? =
    (resolveContext.treeState.getState(context.globalKey, context.isContextForLayout)?.value
            as? KStateContainer)
        ?.getByHookKey(hookKey)

/**
 * Gives a hook its slot and value on its first render, with a new explicit key, or after its
 * registry's generation moved on. [live] is the stored entry with the same explicit key, whose
 * value is kept unless its registry has a restored value for it.
 */
private fun <T> ComponentScope.initSaveableState(
    lithoTree: LithoTree,
    hookKey: HookKey,
    explicitKey: String?,
    saver: Saver<T>,
    live: CachedValue?,
    initializer: () -> T,
): T {
  val scope = LithoSaveableStateRegistryTreeProp.value
  val storageKey = scope?.let {
    (live?.deps?.get(0) as SaveableStateSlot?)?.storageKey
        ?: saveableStateKey(
            globalKey = context.globalKey,
            hookLocalKey = hookKey,
            explicitKey = explicitKey,
            componentName = context.componentScope?.simpleName ?: "unknown component",
        )
  }
  val slot = SaveableStateSlot(explicitKey, scope, storageKey, saver, scope?.generation ?: 0)
  scope?.addSource(lithoTree)
  val value: T =
      getUpdatedStateByHookKey(hookKey, arrayOf(slot)) {
        @Suppress("UNCHECKED_CAST")
        slot.initialValue { if (live != null) live.value as T else initializer() }
      }
  // A concurrent resolve may have created the entry this render got, with its own slot.
  val entrySlot = storedHookEntry(hookKey)?.deps?.singleOrNull() as? SaveableStateSlot
  if (entrySlot?.restored == true) {
    resolveContext.treeState.recordSaveableClaim(entrySlot, lithoTree)
  }
  if (scope != null && storageKey != null) {
    scope.requireSavableValue(
        storageKey,
        StateReadRecorder.untracked(preserveTreeContext = false) { saver.save(value) },
    )
  }
  return value
}

@JvmField
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
val LithoSaveableStateRegistryTreeProp: TreeProp<LithoSaveableStateRegistry?> = treePropOf { null }

private object AutoSaver : Saver<Any?> {
  override fun save(value: Any?): Any? = value

  override fun restore(value: Any?): Any? = value
}

@Suppress("UNCHECKED_CAST") private fun <T> autoSaver(): Saver<T> = AutoSaver as Saver<T>
