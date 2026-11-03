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
import java.util.Objects
import java.util.concurrent.atomic.AtomicReference

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
    /** The late-restore generation of [scope] this slot was created in. */
    val generation: Int,
) {
  /** The saver of the latest render. */
  @Volatile var saver: Saver<*> = saver

  /** Whether the entry's initial value was restored from [scope]. */
  @Volatile var restored: Boolean = false

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
 * When this component leaves the committed tree, its provider is removed and its value is not
 * retained by this hook. A lifecycle or navigation host that replaces the whole ComponentTree can
 * retain the tree's registry explicitly so active values survive into the replacement tree.
 *
 * The [saver] must encode the value into a type supported by Android [android.os.Bundle]. The
 * default saver stores Bundle-compatible values directly. The factory and saver may run on a Litho
 * resolve thread and must not access Views or other main-thread-only objects.
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
  val registry = LithoSaveableStateRegistryTreeProp.value
  val globalKey = context.globalKey
  val hookLocalKey = requireCurrentHookKey()
  val component = context.componentScope
  val storageKey = lazy {
    saveableStateKey(
        globalKey = globalKey,
        hookLocalKey = hookLocalKey,
        explicitKey = key,
        componentName = component?.simpleName ?: "unknown component",
    )
  }
  val restoreGeneration =
      if (registry?.hasLateRestoredValues() == true) registry.restoreGeneration(storageKey.value)
      else 0
  // State and effects are already identified by the component's global key and the hook's
  // position, so only the explicit key can change the storage key of a live hook.
  val state =
      useStateWithDeps(key, restoreGeneration) {
        val restored = registry?.restoredValue(storageKey.value)
        if (restored == null) initializer() else saver.restore(restored.value)
      }
  // Saves read the committed value, so they don't depend on read tracking or on which State the
  // provider captured.
  val stateProvider = (context.lithoTree ?: error("LithoTree is null")).stateProvider
  val save: () -> Any? = { saver.save(stateProvider.getValue(state)) }
  val latestProvider = useState { AtomicReference(save) }.value

  useEffect(saver, stateProvider) {
    latestProvider.set(save)
    null
  }

  useEffect(registry, key) {
    registry
        ?.registerProvider(storageKey.value) { latestProvider.get().invoke() }
        ?.let { registration -> onCleanup(registration::close) }
  }

  return state
}

@JvmField
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
val LithoSaveableStateRegistryTreeProp: TreeProp<LithoSaveableStateRegistry?> = treePropOf { null }

private object AutoSaver : Saver<Any?> {
  override fun save(value: Any?): Any? = value

  override fun restore(value: Any?): Any? = value
}

@Suppress("UNCHECKED_CAST") private fun <T> autoSaver(): Saver<T> = AutoSaver as Saver<T>
