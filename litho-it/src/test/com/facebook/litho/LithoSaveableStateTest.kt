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

import android.annotation.SuppressLint
import android.os.Bundle
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalLithoApi::class)
@SuppressLint("KComponentSyncUpdateDetector")
@RunWith(LithoTestRunner::class)
class LithoSaveableStateTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  private val exactSize = SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY)

  @Test
  fun `state is forgotten after its component leaves the tree`() {
    val first = AtomicReference<State<Int>>()
    val restored = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val testLithoView = render(createTree(registry, CapturingStateComponent(onState = first::set)))

    first.get().updateSync(4)
    testLithoView.setRoot(EmptyComponent())
    testLithoView.setRoot(CapturingStateComponent(onState = restored::set))

    assertThat(restored.get().value).isEqualTo(0)
    testLithoView.release()
    registry.close()
  }

  @Test
  fun `two declarations of the same type remain independent`() {
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val testLithoView = render(createTree(registry, TwoStateComponent(first::set, second::set)))

    first.get().updateSync(3)

    assertThat(first.get().value).isEqualTo(3)
    assertThat(second.get().value).isEqualTo(0)
    testLithoView.release()
    registry.close()
  }

  @Test
  fun `two component instances retain independent state`() {
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val testLithoView = render(
        createTree(
            registry,
            TwoComponentInstances(first::set, second::set),
        ),
    )

    first.get().updateSync(5)

    assertThat(first.get().value).isEqualTo(5)
    assertThat(second.get().value).isEqualTo(0)
    testLithoView.release()
    registry.close()
  }

  @Test
  fun `changing an explicit key initializes different logical state`() {
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val testLithoView = render(
        createTree(
            registry,
            CapturingStateComponent(key = "first", onState = first::set),
        ),
    )
    first.get().updateSync(8)

    testLithoView.setRoot(CapturingStateComponent(key = "second", onState = second::set))

    assertThat(second.get().value).isEqualTo(0)
    testLithoView.release()
    registry.close()
  }

  @Test
  fun `unhosted tree keeps saveable state for the tree lifetime`() {
    val state = AtomicReference<State<Int>>()
    val tree = ComponentTree.create(lithoTestRule.context).build()
    val sizeSpec = SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY)

    tree.setRootAndSizeSpecSync(CapturingStateComponent(onState = state::set), sizeSpec, sizeSpec)
    state.get().updateSync(4)

    assertThat(state.get().value).isEqualTo(4)
    tree.release()
  }

  @Test
  fun `structural and legacy global keys produce the same persistent identity`() {
    val rootTypeId = Component.getOrCreateId(PersistentRootComponent::class.java)
    val childTypeId = Component.getOrCreateId(PersistentChildComponent::class.java)
    val structuralKey = DefaultGlobalKey(
        key = childTypeId,
        position = 2,
        parent = DefaultGlobalKey(key = rootTypeId, position = 0, parent = null),
    )
    val legacyKey = StringGlobalKey("$rootTypeId,$childTypeId!2")
    val hookKey =
        HookKey.create(
            callSiteId = 4,
            index = 1,
            parent = HookKey.create(callSiteId = 2, index = 0, parent = null, intern = false),
            intern = false,
        )

    val structural = saveableStateKey(structuralKey, hookKey, "photo/7#!", "child")
    val legacy = saveableStateKey(legacyKey, hookKey, "photo/7#!", "child")

    assertThat(structural).isEqualTo(legacy)
    assertThat(structural).contains(PersistentRootComponent::class.java.name)
    assertThat(structural).contains(PersistentChildComponent::class.java.name)
  }

  @Test
  fun `persistent identity does not collide when runtime global key hashes collide`() {
    val firstTypeId = Component.getOrCreateId(PersistentFirstComponent::class.java)
    val secondTypeId = Component.getOrCreateId(PersistentSecondComponent::class.java)
    val parent = ManualGlobalKey(key = "\$root", position = 0, parent = null)
    val typeIdDifference = secondTypeId - firstTypeId
    check(typeIdDifference > 0)
    val first = DefaultGlobalKey(
        key = firstTypeId,
        position = 31 * typeIdDifference,
        parent = parent,
    )
    val second = DefaultGlobalKey(key = secondTypeId, position = 0, parent = parent)
    val hookKey = HookKey.create(callSiteId = 0, index = 0, parent = null, intern = false)

    assertThat(first.hashCode()).isEqualTo(second.hashCode())
    assertThat(saveableStateKey(first, hookKey, null, "first"))
        .isNotEqualTo(saveableStateKey(second, hookKey, null, "second"))
  }

  @Test
  fun `unknown numeric legacy type id fails instead of persisting an unstable identity`() {
    val hookKey = HookKey.create(callSiteId = 0, index = 0, parent = null, intern = false)

    assertThatThrownBy {
          saveableStateKey(
              globalKey = StringGlobalKey(Int.MAX_VALUE.toString()),
              hookLocalKey = hookKey,
              explicitKey = null,
              componentName = "legacy component",
          )
        }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("cannot derive a persistent identity")
        .hasMessageContaining("explicit Litho component key")
  }

  @Test
  fun `custom saver restores a non-Bundle value`() {
    val saver = Saver<Filter>(save = Filter::name, restore = { Filter(checkNotNull(it) as String) })
    val first = AtomicReference<State<Filter>>()
    val registry = LithoSaveableStateRegistry()
    val firstTree = createTree(registry, CustomStateComponent(saver, first::set))
    val firstView = render(firstTree)
    first.get().updateSync(Filter("favorites"))
    firstView.release()
    val snapshot = registry.snapshot()
    registry.close()

    val restored = AtomicReference<State<Filter>>()
    val restoredRegistry = LithoSaveableStateRegistry(snapshot)
    val restoredTree = createTree(restoredRegistry, CustomStateComponent(saver, restored::set))
    val restoredView = render(restoredTree)

    assertThat(restored.get().value).isEqualTo(Filter("favorites"))
    restoredView.release()
    restoredRegistry.close()
  }

  /**
   * The saver's Bundle looks like a registry snapshot with a child and like a saved value entry,
   * but is restored as saved.
   */
  @Test
  fun `custom saver restores a value it saves as a Bundle`() {
    val saver = Saver<Filter>(
        save = { savedState(childKey("filter") to it.name).apply { putString("value", it.name) } },
        restore = { value ->
          Filter(checkNotNull((value as Bundle).getBundle(childKey("filter"))?.getString("value")))
        },
    )
    val first = AtomicReference<State<Filter>>()
    val registry = LithoSaveableStateRegistry()
    val firstView = render(createTree(registry, CustomStateComponent(saver, first::set)))
    first.get().updateSync(Filter("favorites"))
    val snapshot = registry.snapshot()
    firstView.release()
    registry.close()

    val carried = LithoSaveableStateRegistry(LithoSaveableStateRegistry(snapshot).snapshot())
    val restored = AtomicReference<State<Filter>>()
    val restoredView = render(createTree(carried, CustomStateComponent(saver, restored::set)))

    assertThat(restored.get().value).isEqualTo(Filter("favorites"))
    restoredView.release()
    carried.close()
  }

  @Test
  fun `an inline saver from the latest render is used by the next save`() {
    val registry = LithoSaveableStateRegistry()
    val saveCalls = AtomicInteger()
    val tree = createTree(registry, InlineSaverComponent(version = 1, saveCalls = saveCalls))
    val view = render(tree)
    // The first render checks that the saver's output can be saved.
    assertThat(saveCalls).hasValue(1)

    view.setRoot(InlineSaverComponent(version = 2, saveCalls = saveCalls))

    assertThat(saveCalls).hasValue(1)
    assertThat(registry.restoredValueForTest()).isEqualTo("2:0")
    assertThat(saveCalls).hasValue(2)
    view.release()
    registry.close()
  }

  @Test
  fun `changing only the explicit key saves under the new key`() {
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val view =
        render(createTree(registry, CapturingStateComponent(key = "first", onState = first::set)))
    first.get().updateSync(8)

    view.setRoot(CapturingStateComponent(key = "second", onState = second::set))
    second.get().updateSync(3)
    val snapshot = registry.snapshot()
    view.release()
    registry.close()

    val restored = AtomicReference<State<Int>>()
    val restoredRegistry = LithoSaveableStateRegistry(snapshot)
    val restoredView = render(
        createTree(
            restoredRegistry,
            CapturingStateComponent(key = "second", onState = restored::set),
        ),
    )
    assertThat(snapshot.keySet()).hasSize(1)
    assertThat(restored.get().value).isEqualTo(3)
    restoredView.release()
    restoredRegistry.close()
  }

  @Test
  fun `retained nested registry survives tree replacement`() {
    val state = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val tree = createTree(registry, CapturingStateComponent(onState = state::set))
    val view = render(tree)
    state.get().updateSync(6)
    assertThat(tree.acquireRetainedSaveableStateRegistryForTreeReplacement()).isSameAs(registry)
    assertThatThrownBy { tree.acquireRetainedSaveableStateRegistryForTreeReplacement() }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("already been transferred")

    view.release()

    assertThat(LithoSaveableStateRegistry(registry.snapshot()).restoredValueForTest()).isEqualTo(6)
    registry.close()
  }

  /**
   * A list may release a row's tree while it lays out, inside its host tree's render. The row's
   * values are read for retention there, and must not count as reads of the host tree.
   */
  @Test
  fun `a tree released during another tree's render retains its latest value`() {
    val config = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        config.copy(crossTreeStateReadPolicy = ComponentsConfiguration.LogicViolationPolicy.CRASH)
    try {
      val state = AtomicReference<State<Int>>()
      val registry = LithoSaveableStateRegistry()
      // With detached state ids, the saver reads the state itself, not the value a render captured.
      val tree =
          ComponentTree.create(lithoTestRule.context, CapturingStateComponent(onState = state::set))
              .withRetainedSaveableStateRegistryForTreeReplacement(registry)
              .componentsConfiguration(config.copy(detachStateIdFromValue = true))
              .build()
      val view = render(tree)
      state.get().updateSync(6)
      val otherTree = ComponentTree.create(lithoTestRule.context).build()

      StateReadRecorder.record(otherTree.id) { view.release() }

      assertThat(LithoSaveableStateRegistry(registry.snapshot()).restoredValueForTest())
          .isEqualTo(6)
      registry.close()
    } finally {
      ComponentsConfiguration.defaultInstance = config
    }
  }

  @Test
  fun `retained registry transfer is restricted to the main thread`() {
    val tree = ComponentTree.create(lithoTestRule.context).build()
    val acquire = FutureTask {
      runCatching { tree.acquireRetainedSaveableStateRegistryForTreeReplacement() }
          .exceptionOrNull()
    }

    Thread(acquire).start()

    assertThat(acquire.get(5, TimeUnit.SECONDS))
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("must run on the main thread")
    tree.release()
  }

  /**
   * ```text
   * First process scope                Recreated process scope
   * -------------------                -----------------------
   * ComponentTree A                    ComponentTree B
   * Saveable state = 7  -- Bundle -->  Restored saveable state = 7
   * ```
   */
  @Test
  fun `snapshot restores state into a new tree`() {
    val firstRegistry = LithoSaveableStateRegistry()
    val firstState = AtomicReference<State<Int>>()
    val firstTree = createTree(firstRegistry, CapturingStateComponent(onState = firstState::set))
    val firstView = render(firstTree)
    firstState.get().updateSync(7)
    val savedState = firstRegistry.snapshot()

    firstView.release()
    firstRegistry.close()

    val restoredRegistry = LithoSaveableStateRegistry(savedState)
    val restoredState = AtomicReference<State<Int>>()
    val restoredTree =
        createTree(restoredRegistry, CapturingStateComponent(onState = restoredState::set))
    val restoredView = render(restoredTree)

    assertThat(restoredState.get().value).isEqualTo(7)
    restoredView.release()
    restoredRegistry.close()
  }

  /**
   * ```text
   * Parent registry                  Restored parent registry
   *  |- child "first" = 11  Bundle   |- child "first" = 11
   *  `- child "second" = 22 ------>  `- child "second" = 22
   * ```
   */
  @Test
  fun `nested trees restore independent state from the parent snapshot`() {
    val parentRegistry = LithoSaveableStateRegistry()
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val firstTree = createNestedTree(parentRegistry, "first", first::set)
    val secondTree = createNestedTree(parentRegistry, "second", second::set)
    val firstView = render(firstTree)
    val secondView = render(secondTree)

    first.get().updateSync(11)
    second.get().updateSync(22)
    val snapshot = parentRegistry.snapshot()

    firstView.release()
    secondView.release()
    parentRegistry.close()

    val restoredParentRegistry = LithoSaveableStateRegistry(snapshot)
    val restoredFirst = AtomicReference<State<Int>>()
    val restoredSecond = AtomicReference<State<Int>>()
    val restoredFirstView =
        render(createNestedTree(restoredParentRegistry, "first", restoredFirst::set))
    val restoredSecondView =
        render(createNestedTree(restoredParentRegistry, "second", restoredSecond::set))

    assertThat(restoredFirst.get().value).isEqualTo(11)
    assertThat(restoredSecond.get().value).isEqualTo(22)
    restoredFirstView.release()
    restoredSecondView.release()
    restoredParentRegistry.close()
  }

  @Test
  fun `releasing a nested tree removes its state from the parent snapshot`() {
    val parentRegistry = LithoSaveableStateRegistry()
    val state = AtomicReference<State<Int>>()
    val childView = render(createNestedTree(parentRegistry, "child", state::set))
    state.get().updateSync(9)

    childView.release()

    assertThat(parentRegistry.snapshot().isEmpty).isTrue()
    parentRegistry.close()
  }

  @Test
  fun `nested tree without a stable identity keeps its values locally`() {
    val parentRegistry = LithoSaveableStateRegistry()
    val childRegistry = parentRegistry.createChildRegistry(key = null)

    val live = LiveValue(childRegistry, "counter", 1)
    childRegistry.retainActiveValues()

    assertThat(childRegistry.snapshot().isEmpty).isFalse()
    assertThat(parentRegistry.snapshot().isEmpty).isTrue()
    live.close()
    childRegistry.close()
    parentRegistry.close()
  }

  /**
   * ```text
   * Keyless nested row             RecyclerBinder
   * ------------------             --------------
   * Render under a parent registry
   *                                Row leaves the range
   *                                Acquire the row's registry
   *                                Release the row's tree
   * Retain values locally, no crash
   * ```
   */
  @Test
  fun `releasing a keyless nested tree after acquiring its registry does not throw`() {
    val parentRegistry = LithoSaveableStateRegistry()
    val state = AtomicReference<State<Int>>()
    val tree = createNestedTree(parentRegistry, key = null, onState = state::set)
    val view = render(tree)
    state.get().updateSync(5)
    val childRegistry = checkNotNull(tree.acquireRetainedSaveableStateRegistryForTreeReplacement())

    view.release()

    assertThat(parentRegistry.snapshot().isEmpty).isTrue()
    childRegistry.close()
    parentRegistry.close()
  }

  @Test
  fun `sibling nested registries with the same key are not saved`() {
    val parentRegistry = LithoSaveableStateRegistry()
    val firstChild = parentRegistry.createChildRegistry("shared")
    val first = LiveValue(firstChild, "counter", 1)
    val secondChild = parentRegistry.createChildRegistry("shared")

    val second = LiveValue(secondChild, "counter", 2)
    val restoredParent = LithoSaveableStateRegistry(parentRegistry.snapshot())
    val restoredChild = restoredParent.createChildRegistry("shared")

    assertThat(restoredChild.restoredValue("counter")).isNull()
    first.close()
    second.close()
    firstChild.close()
    secondChild.close()
    assertThat(parentRegistry.snapshot().isEmpty).isTrue()
    restoredChild.close()
    restoredParent.close()
    parentRegistry.close()
  }

  @Test
  fun `a second nested registry with the same key restores nothing`() {
    val parentRegistry =
        LithoSaveableStateRegistry(savedState(childKey("shared") to savedState("counter" to 41)))
    val firstChild = parentRegistry.createChildRegistry("shared")
    val secondChild = parentRegistry.createChildRegistry("shared")

    assertThat(firstChild.restoredValue("counter")?.value).isEqualTo(41)
    assertThat(secondChild.restoredValue("counter")).isNull()
    assertThat(parentRegistry.snapshot().isEmpty).isTrue()
    firstChild.close()
    secondChild.close()
    parentRegistry.close()
  }

  @Test
  fun `a nested registry can take a key over after its holder closes`() {
    val parentRegistry = LithoSaveableStateRegistry()
    val firstChild = parentRegistry.createChildRegistry("shared")
    val first = LiveValue(firstChild, "counter", 1)

    firstChild.close()
    val secondChild = parentRegistry.createChildRegistry("shared")
    val second = LiveValue(secondChild, "counter", 2)
    val restoredParent = LithoSaveableStateRegistry(parentRegistry.snapshot())
    val restoredChild = restoredParent.createChildRegistry("shared")

    assertThat(restoredChild.restoredValue("counter")?.value).isEqualTo(2)
    first.close()
    second.close()
    secondChild.close()
    restoredChild.close()
    restoredParent.close()
    parentRegistry.close()
  }

  @Test
  fun `acquired nested registry survives tree replacement and remains in parent snapshot`() {
    val parentRegistry = LithoSaveableStateRegistry()
    val first = AtomicReference<State<Int>>()
    val firstTree = createNestedTree(parentRegistry, "child", first::set)
    val firstView = render(firstTree)
    first.get().updateSync(13)
    val childRegistry =
        checkNotNull(firstTree.acquireRetainedSaveableStateRegistryForTreeReplacement())

    firstView.release()

    val restored = AtomicReference<State<Int>>()
    val replacementTree = createTree(
        registry = childRegistry,
        component = CapturingStateComponent(onState = restored::set),
    )
    val replacementView = render(replacementTree)

    assertThat(restored.get().value).isEqualTo(13)
    assertThat(parentRegistry.snapshot().isEmpty).isFalse()
    replacementView.release()
    childRegistry.close()
    assertThat(parentRegistry.snapshot().isEmpty).isTrue()
    parentRegistry.close()
  }

  @Test
  fun `a saveable hook keeps one state entry and adds no effect`() {
    val state = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val tree = createTree(registry, CapturingStateComponent(onState = state::set))
    val view = render(tree)
    state.get().updateSync(3)

    val containers =
        checkNotNull(tree.committedTreeState).resolveState.state.orEmpty().values.map {
          it.value as KStateContainer
        }
    assertThat(containers.map { it.states.size }).containsExactly(1)
    assertThat(checkNotNull(tree.getCommittedLayoutState()).attachables.orEmpty()).isEmpty()
    assertThat(registry.restoredValueForTest()).isEqualTo(3)
    view.release()
    registry.close()
  }

  /**
   * ```text
   * Save (first 3, second 4)
   * Restore with only first shown, save; twice    second's 4 is carried through both saves
   * Restore with both shown                       second restores 4
   * ```
   */
  @Test
  fun `restored values no hook takes are saved again`() {
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val view = render(createTree(registry, ToggleComponent(true, first::set, second::set)))
    first.get().updateSync(3)
    second.get().updateSync(4)
    var snapshot = registry.snapshot()
    view.release()
    registry.close()

    repeat(2) {
      val next = LithoSaveableStateRegistry(snapshot)
      val nextView = render(createTree(next, ToggleComponent(false, first::set, second::set)))
      assertThat(first.get().value).isEqualTo(3)
      snapshot = next.snapshot()
      nextView.release()
      next.close()
    }

    val restoredRegistry = LithoSaveableStateRegistry(snapshot)
    val restoredView =
        render(createTree(restoredRegistry, ToggleComponent(true, first::set, second::set)))
    assertThat(first.get().value to second.get().value).isEqualTo(3 to 4)
    restoredView.release()
    restoredRegistry.close()
  }

  /**
   * ```text
   * Restore 5 for the component, which renders and commits it   5
   * The component leaves                                        nothing is saved for it
   * It comes back                                               0, from its initializer
   * ```
   */
  @Test
  fun `a restored value is no longer saved once its component took it and left`() {
    val registry = LithoSaveableStateRegistry(savedValue(5))
    val state = AtomicReference<State<Int>>()
    val view = render(createTree(registry, CapturingStateComponent(onState = state::set)))
    assertThat(state.get().value).isEqualTo(5)

    view.setRoot(EmptyComponent())

    assertThat(registry.snapshot().isEmpty).isTrue()
    view.setRoot(CapturingStateComponent(onState = state::set))
    assertThat(state.get().value).isEqualTo(0)
    view.release()
    registry.close()
  }

  @Test
  fun `a default-saver value is restored as it was saved`() {
    val first = AtomicReference<State<ArrayList<String>>>()
    val registry = LithoSaveableStateRegistry()
    val firstView = render(createTree(registry, ListStateComponent(first::set)))
    first.get().updateSync(arrayListOf("a", "b"))
    val snapshot = registry.snapshot()
    firstView.release()
    registry.close()

    val restored = AtomicReference<State<ArrayList<String>>>()
    val restoredRegistry = LithoSaveableStateRegistry(snapshot)
    val restoredView = render(createTree(restoredRegistry, ListStateComponent(restored::set)))

    assertThat(restored.get().value).isInstanceOf(ArrayList::class.java).containsExactly("a", "b")
    restoredView.release()
    restoredRegistry.close()
  }

  @Test
  fun `a custom saver decodes its restored value once, on the first render`() {
    val restoreCalls = AtomicInteger()
    val saver = Saver<Filter>(
        save = Filter::name,
        restore = { value ->
          restoreCalls.incrementAndGet()
          Filter(checkNotNull(value) as String)
        },
    )
    val saved = AtomicReference<State<Filter>>()
    val registry = LithoSaveableStateRegistry()
    val savedView = render(createTree(registry, CustomStateComponent(saver, saved::set)))
    saved.get().updateSync(Filter("favorites"))
    val restoredRegistry = LithoSaveableStateRegistry(registry.snapshot())
    savedView.release()
    registry.close()
    assertThat(restoreCalls).hasValue(0)

    val state = AtomicReference<State<Filter>>()
    val view = render(createTree(restoredRegistry, CustomStateComponent(saver, state::set)))
    assertThat(state.get().value).isEqualTo(Filter("favorites"))
    assertThat(restoreCalls).hasValue(1)
    view.setRoot(CustomStateComponent(saver, state::set))

    assertThat(state.get().value).isEqualTo(Filter("favorites"))
    assertThat(restoreCalls).hasValue(1)
    view.release()
    restoredRegistry.close()
  }

  @Test
  fun `a hosted tree that never uses saveable state gets no saveable bookkeeping`() {
    val registry = LithoSaveableStateRegistry()
    val tree = createTree(registry, PlainStateComponent())
    val view = render(tree)

    val walked = ArrayList<Any?>()
    checkNotNull(tree.committedTreeState).forEachSaveableEntry { _, value -> walked += value }
    assertThat(walked).isEmpty()
    assertThat(registry.hasSources).isFalse()
    assertThat(registry.holdsState()).isFalse()
    view.release()
    registry.close()
  }

  /**
   * ```text
   * Parent restores child = 9
   * Lay out the nested tree, never shown, and release it   its value 9 stays in the parent
   * The next nested tree with the key                      restores 9
   * ```
   */
  @Test
  fun `a nested tree released before it is shown leaves its restored values in the parent`() {
    val parentRegistry = LithoSaveableStateRegistry(savedChild("child", 9))
    val hidden = AtomicReference<State<Int>>()
    val hiddenTree = createNestedTree(parentRegistry, "child", hidden::set)
    hiddenTree.setRootAndSizeSpecSync(
        CapturingStateComponent(onState = hidden::set),
        exactSize,
        exactSize,
    )
    assertThat(hidden.get().value).isEqualTo(9)
    hiddenTree.release()

    val shown = AtomicReference<State<Int>>()
    val shownView = render(createNestedTree(parentRegistry, "child", shown::set))
    assertThat(shown.get().value).isEqualTo(9)
    shownView.release()
    parentRegistry.close()
  }

  /**
   * ```text
   * Parent restores child = 9, under a holder key in the nested tree
   * Lay out the nested tree, never shown, and release it   its value 9 stays in the parent
   * The next nested tree with the key                      restores 9
   * ```
   */
  @Test
  fun `holder values of a nested tree released before it is shown stay in the parent`() {
    val parentRegistry = LithoSaveableStateRegistry(savedChild("child", 9) { HolderContent(it) })
    val hidden = AtomicReference<State<Int>>()
    val hiddenTree = createNestedTree(parentRegistry, "child", root = HolderContent(hidden::set))
    hiddenTree.setRootAndSizeSpecSync(HolderContent(hidden::set), exactSize, exactSize)
    assertThat(hidden.get().value).isEqualTo(9)
    hiddenTree.release()

    val shown = AtomicReference<State<Int>>()
    val shownView =
        render(createNestedTree(parentRegistry, "child", root = HolderContent(shown::set)))
    assertThat(shown.get().value).isEqualTo(9)
    shownView.release()
    parentRegistry.close()
  }

  /**
   * As a RecyclerBinder row laid out in range but never attached, then parked and rebuilt in range:
   * ```text
   * Parent restores row = 9
   * Lay out the row, park its TreeState and registry, release it
   * Rebuild it with them, not attached          the parent saves the row (9)
   * Set it to 10, still not attached            the parent saves 10
   * ```
   */
  @Test
  fun `a parked nested tree rebuilt before it is attached is saved`() {
    val parentRegistry = LithoSaveableStateRegistry(savedChild("row", 9))
    val first = AtomicReference<State<Int>>()
    val firstTree = createNestedTree(parentRegistry, "row", first::set)
    firstTree.setRootAndSizeSpecSync(
        CapturingStateComponent(onState = first::set),
        exactSize,
        exactSize,
    )
    val parked = firstTree.acquireTreeState()
    val rowRegistry =
        checkNotNull(firstTree.acquireRetainedSaveableStateRegistryForTreeReplacement())
    rowRegistry.setSavedInParent(false)
    firstTree.release()

    rowRegistry.setSavedInParent(true)
    val second = AtomicReference<State<Int>>()
    val secondTree =
        ComponentTree.create(
                contextWith(parentRegistry),
                CapturingStateComponent(onState = second::set),
            )
            .treeState(parked)
            .withRetainedSaveableStateRegistryForTreeReplacement(rowRegistry)
            .build()
    secondTree.setRootAndSizeSpecSync(
        CapturingStateComponent(onState = second::set),
        exactSize,
        exactSize,
    )

    assertThat(second.get().value).isEqualTo(9)
    assertThat(childValue(parentRegistry.snapshot(), "row")).isEqualTo(9)
    second.get().updateSync(10)
    assertThat(childValue(parentRegistry.snapshot(), "row")).isEqualTo(10)
    secondTree.release()
    rowRegistry.close()
    parentRegistry.close()
  }

  /**
   * ```text
   * Tree A: (3, 7); its TreeState is kept and A is released, its registry retained
   * Tree B takes both and first renders without the second value   a save keeps 7
   * The second value comes back                                     7
   * ```
   */
  @Test
  fun `a value the first render of a replacement tree leaves out is kept`() {
    val registry = LithoSaveableStateRegistry()
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val firstTree = createTree(registry, ToggleComponent(true, first::set, second::set))
    val firstView = render(firstTree)
    first.get().updateSync(3)
    second.get().updateSync(7)
    val carried = firstTree.acquireTreeState()
    firstView.release()

    val view =
        render(createTree(registry, ToggleComponent(false, first::set, second::set), carried))

    assertThat(savedValues(registry.snapshot())).containsExactlyInAnyOrder(3, 7)
    view.setRoot(ToggleComponent(true, first::set, second::set))
    assertThat(second.get().value).isEqualTo(7)
    view.release()
    registry.close()
  }

  /**
   * ```text
   * Tree A: 7; tree B takes its TreeState and retained registry, and shows 7
   * The value leaves, then comes back     0, not the kept 7
   * ```
   */
  @Test
  fun `a value that leaves a replacement tree is not restored when it comes back`() {
    val registry = LithoSaveableStateRegistry()
    val state = AtomicReference<State<Int>>()
    val firstTree = createTree(registry, ToggleComponent(true, {}, state::set))
    val firstView = render(firstTree)
    state.get().updateSync(7)
    val carried = firstTree.acquireTreeState()
    firstView.release()

    val view = render(createTree(registry, ToggleComponent(true, {}, state::set), carried))
    assertThat(state.get().value).isEqualTo(7)
    view.setRoot(ToggleComponent(false, {}, state::set))
    view.setRoot(ToggleComponent(true, {}, state::set))

    assertThat(state.get().value).isEqualTo(0)
    view.release()
    registry.close()
  }

  @Test
  fun `two trees saving one hook in a registry save neither and report it`() {
    ShadowLog.clear()
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val registry = LithoSaveableStateRegistry()
    val firstView = render(createTree(registry, CapturingStateComponent(onState = first::set)))
    val secondView = render(createTree(registry, CapturingStateComponent(onState = second::set)))
    first.get().updateSync(1)
    second.get().updateSync(2)

    assertThat(registry.snapshot().isEmpty).isTrue()
    assertThat(registry.snapshot().isEmpty).isTrue()
    assertThat(
        ShadowLog.getLogs().count {
          it.msg.orEmpty().startsWith("LithoSaveableState:DuplicateKey")
        },
    )
        .isEqualTo(1)
    assertThat(first.get().value to second.get().value).isEqualTo(1 to 2)
    firstView.release()
    secondView.release()
    registry.close()
  }

  /**
   * ```text
   * Two trees share a registry; save (first 3, second 4)
   * Lay out both, not shown, with a new registry           (0, 0)
   * The registry adopts the save                          a save now has (3, 4)
   * Both are shown                                        (3, 4), and both are saved
   * ```
   */
  @Test
  fun `a late restore reaches every tree that rendered before it`() {
    val source = LithoSaveableStateRegistry()
    val sourceFirst = AtomicReference<State<Int>>()
    val sourceSecond = AtomicReference<State<Int>>()
    val sourceFirstView =
        render(createTree(source, CapturingStateComponent(onState = sourceFirst::set)))
    val sourceSecondView = render(createTree(source, OtherStateComponent(sourceSecond::set)))
    sourceFirst.get().updateSync(3)
    sourceSecond.get().updateSync(4)
    val saved = source.snapshot()
    sourceFirstView.release()
    sourceSecondView.release()
    source.close()

    val registry = LithoSaveableStateRegistry()
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    val firstTree = createTree(registry, CapturingStateComponent(onState = first::set))
    val secondTree = createTree(registry, OtherStateComponent(second::set))
    firstTree.setRootAndSizeSpecSync(
        CapturingStateComponent(onState = first::set),
        exactSize,
        exactSize,
    )
    secondTree.setRootAndSizeSpecSync(OtherStateComponent(second::set), exactSize, exactSize)
    assertThat(first.get().value to second.get().value).isEqualTo(0 to 0)

    registry.adoptLateRestoredValues(saved)
    assertThat(savedValues(registry.snapshot()))
        .describedAs("before shown")
        .containsExactlyInAnyOrder(3, 4)
    val firstView = render(firstTree)
    val secondView = render(secondTree)

    assertThat(first.get().value to second.get().value).isEqualTo(3 to 4)
    assertThat(savedValues(registry.snapshot())).containsExactlyInAnyOrder(3, 4)
    firstView.release()
    secondView.release()
    registry.close()
  }

  /**
   * ```text
   * A tree lays out (0, 0), not shown, and its second value is set to 5
   * The registry adopts a late save holding only the first value, 7
   * Save before the tree renders again                 (7, 5)
   * ```
   */
  @Test
  fun `a save after a late restore keeps the values the late save lacks`() {
    val late = savedValue(7) { ToggleComponent(showSecond = false, onFirst = it, onSecond = {}) }
    val registry = LithoSaveableStateRegistry()
    val second = AtomicReference<State<Int>>()
    val root = ToggleComponent(true, {}, second::set)
    val tree = createTree(registry, root)
    tree.setRootAndSizeSpecSync(root, exactSize, exactSize)
    second.get().updateSync(5)

    registry.adoptLateRestoredValues(late)

    assertThat(savedValues(registry.snapshot())).containsExactlyInAnyOrder(7, 5)
    tree.release()
    registry.close()
  }

  @Test
  fun `an unsupported default-saver value fails on the first render`() {
    listOf(Any(), arrayOf<Any>(Any())).forEach { value ->
      val registry = LithoSaveableStateRegistry()
      val tree = createTree(registry, UnsupportedStateComponent(value))

      val failure = runCatching {
        tree.setRootAndSizeSpecSync(UnsupportedStateComponent(value), exactSize, exactSize)
      }
          .exceptionOrNull()

      val rejection =
          generateSequence(failure) { it.cause }
              .firstOrNull {
                it is IllegalArgumentException && "unsupported" in it.message.orEmpty()
              }
      assertThat(rejection)
          .describedAs("failure: %s", failure)
          .isNotNull()
          .hasMessageContaining(value.javaClass.name)
          .hasMessageContaining("Saver")
      tree.release()
      registry.close()
    }
  }

  @Test
  fun `a custom saver that returns an unsupported value fails on the first render`() {
    val saver = Saver<Filter>(save = { Any() }, restore = { Filter("all") })
    val registry = LithoSaveableStateRegistry()
    val tree = createTree(registry, CustomStateComponent(saver) {})

    val failure = runCatching {
      tree.setRootAndSizeSpecSync(CustomStateComponent(saver) {}, exactSize, exactSize)
    }
        .exceptionOrNull()

    val rejection =
        generateSequence(failure) { it.cause }
            .firstOrNull { it is IllegalArgumentException && "unsupported" in it.message.orEmpty() }
    assertThat(rejection)
        .describedAs("failure: %s", failure)
        .isNotNull()
        .hasMessageContaining(Any::class.java.name)
    tree.release()
    registry.close()
  }

  @Test
  fun `a hook that renders again after its registry closes doesn't check its value`() {
    val state = AtomicReference<State<Any>>()
    val registry = LithoSaveableStateRegistry()
    val tree = createTree(registry, AnyStateComponent(onState = state::set))
    tree.setRootAndSizeSpecSync(AnyStateComponent(onState = state::set), exactSize, exactSize)
    val unsupported = Any()
    state.get().updateSync(unsupported)

    registry.close()
    tree.setRootAndSizeSpecSync(
        AnyStateComponent(revision = 1, onState = state::set),
        exactSize,
        exactSize,
    )

    assertThat(state.get().value).isSameAs(unsupported)
    tree.release()
  }

  /**
   * ```text
   * Background thread                         Main thread
   * -----------------                         -----------
   * First render reads the restored 7
   *                                           Save: 7
   *                                           Render another root, which commits
   * The first render does not commit
   *                                           Render the component again: 7
   * ```
   */
  @Test
  fun `a superseded first render does not consume its restored value`() {
    val registry =
        LithoSaveableStateRegistry(savedValue(7) { BlockingStateComponent(onState = it) })
    val tree = createTree(registry, PlainStateComponent())
    tree.setRootAndSizeSpecSync(PlainStateComponent(), exactSize, exactSize)
    val started = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val firstRenderValue = AtomicInteger(-1)
    val failure = AtomicReference<Throwable>()

    val background = thread {
      try {
        tree.setRootAndSizeSpecSync(
            BlockingStateComponent(started, proceed, onValue = firstRenderValue::set),
            exactSize,
            exactSize,
        )
      } catch (throwable: Throwable) {
        failure.set(throwable)
      }
    }
    check(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    val savedDuringFirstRender = registry.restoredValueForTest()
    tree.setRootAndSizeSpecSync(PlainStateComponent(revision = 1), exactSize, exactSize)
    proceed.countDown()
    background.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
    failure.get()?.let { throw it }

    val state = AtomicReference<State<Int>>()
    tree.setRootAndSizeSpecSync(BlockingStateComponent(onState = state::set), exactSize, exactSize)
    assertThat(firstRenderValue).hasValue(7)
    assertThat(savedDuringFirstRender).isEqualTo(7)
    assertThat(state.get().value).isEqualTo(7)
    tree.release()
    registry.close()
  }

  /**
   * ```text
   * Background thread                         Main thread
   * -----------------                         -----------
   * First render reads the restored 3
   *                                           The registry adopts 7, late
   * The first render commits
   *                                           Show the tree: 7
   * ```
   */
  @Test
  fun `a first render that read a value before a late restore commits without dropping it`() {
    val registry =
        LithoSaveableStateRegistry(savedValue(3) { BlockingStateComponent(onState = it) })
    val tree = createTree(registry, PlainStateComponent())
    tree.setRootAndSizeSpecSync(PlainStateComponent(), exactSize, exactSize)
    val started = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val firstRenderValue = AtomicInteger(-1)
    val state = AtomicReference<State<Int>>()
    val failure = AtomicReference<Throwable>()

    val background = thread {
      try {
        tree.setRootAndSizeSpecSync(
            BlockingStateComponent(started, proceed, state::set, firstRenderValue::set),
            exactSize,
            exactSize,
        )
      } catch (throwable: Throwable) {
        failure.set(throwable)
      }
    }
    check(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    registry.adoptLateRestoredValues(savedValue(7) { BlockingStateComponent(onState = it) })
    proceed.countDown()
    background.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
    failure.get()?.let { throw it }
    assertThat(firstRenderValue).hasValue(3)

    val view = render(tree)
    assertThat(state.get().value).isEqualTo(7)
    view.release()
    registry.close()
  }

  private fun createTree(
      registry: LithoSaveableStateRegistry,
      component: Component,
      treeState: TreeState? = null,
  ): ComponentTree =
      ComponentTree.create(lithoTestRule.context, component)
          .withRetainedSaveableStateRegistryForTreeReplacement(registry)
          .treeState(treeState)
          .build()

  private fun createNestedTree(
      parentRegistry: LithoSaveableStateRegistry,
      key: String?,
      onState: (State<Int>) -> Unit = {},
      root: Component = CapturingStateComponent(onState = onState),
  ): ComponentTree {
    val treeProps =
        TreePropContainer().apply {
          put(LithoSaveableStateRegistryTreeProp, parentRegistry)
        }
    return ComponentTree.create(
            ComponentContext(lithoTestRule.context.androidContext, treePropContainer = treeProps),
            root,
        )
        .apply { key?.let(::withNestedTreeSaveableStateKey) }
        .build()
  }

  private fun contextWith(
      parentRegistry: LithoSaveableStateRegistry,
  ): ComponentContext = ComponentContext(
      lithoTestRule.context.androidContext,
      treePropContainer =
          TreePropContainer().apply { put(LithoSaveableStateRegistryTreeProp, parentRegistry) },
  )

  /** A saved registry in which the [root] component's value is [value]. */
  private fun savedValue(
      value: Int,
      root: ((State<Int>) -> Unit) -> Component = { CapturingStateComponent(onState = it) },
  ): Bundle {
    val source = LithoSaveableStateRegistry()
    val state = AtomicReference<State<Int>>()
    val view = render(createTree(source, root(state::set)))
    state.get().updateSync(value)
    val snapshot = source.snapshot()
    view.release()
    source.close()
    return snapshot
  }

  private fun savedValues(snapshot: Bundle): List<Any?> {
    val restored = LithoSaveableStateRegistry(snapshot)
    return snapshot.keySet().map { restored.restoredValue(it)?.value }
  }

  /** A saved parent registry whose child registry [key] holds the [root] component's [value]. */
  private fun savedChild(
      key: String,
      value: Int,
      root: ((State<Int>) -> Unit) -> Component = { CapturingStateComponent(onState = it) },
  ): Bundle {
    val source = LithoSaveableStateRegistry()
    val state = AtomicReference<State<Int>>()
    val view = render(createNestedTree(source, key, root = root(state::set)))
    state.get().updateSync(value)
    val snapshot = source.snapshot()
    view.release()
    source.close()
    return snapshot
  }

  /** The single value [snapshot] saves for its child registry [key]. */
  private fun childValue(snapshot: Bundle, key: String): Any? {
    val child = LithoSaveableStateRegistry(snapshot).createChildRegistry(key)
    return child.restoredValue(child.snapshot().keySet().single())?.value
  }

  private fun render(tree: ComponentTree) =
      lithoTestRule.createTestLithoView(componentTree = tree).attachToWindow().measure().layout()

  private class CapturingStateComponent(
      private val key: String? = null,
      private val onState: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useSaveableState(key = key) { 0 }
      onState(state)
      return EmptyComponent()
    }
  }

  private class TwoStateComponent(
      private val onFirst: (State<Int>) -> Unit,
      private val onSecond: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val first = useSaveableState { 0 }
      val second = useSaveableState { 0 }
      onFirst(first)
      onSecond(second)
      return EmptyComponent()
    }
  }

  private class TwoComponentInstances(
      private val onFirst: (State<Int>) -> Unit,
      private val onSecond: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Row {
      child(CapturingStateComponent(onState = onFirst))
      child(CapturingStateComponent(onState = onSecond))
    }
  }

  private class CustomStateComponent(
      private val saver: Saver<Filter>,
      private val onState: (State<Filter>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useSaveableState(saver = saver) { Filter("all") }
      onState(state)
      return EmptyComponent()
    }
  }

  private class OtherStateComponent(private val onState: (State<Int>) -> Unit) : KComponent() {
    override fun ComponentScope.render(): Component {
      onState(useSaveableState { 0 })
      return EmptyComponent()
    }
  }

  private class ListStateComponent(private val onState: (State<ArrayList<String>>) -> Unit) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      onState(useSaveableState { arrayListOf<String>() })
      return EmptyComponent()
    }
  }

  /** A saveable value, and another in a child shown only when [showSecond]. */
  private class ToggleComponent(
      private val showSecond: Boolean,
      private val onFirst: (State<Int>) -> Unit,
      private val onSecond: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      onFirst(useSaveableState { 0 })
      return if (showSecond) CapturingStateComponent(onState = onSecond) else EmptyComponent()
    }
  }

  /** A saveable value rendered under a [useSaveableStateHolder] key. */
  private class HolderContent(private val onState: (State<Int>) -> Unit) : KComponent() {
    override fun ComponentScope.render(): Component =
        useSaveableStateHolder().SaveableStateProvider("a") {
          CapturingStateComponent(onState = onState)
        }
  }

  private class PlainStateComponent(private val revision: Int = 0) : KComponent() {
    override fun ComponentScope.render(): Component {
      useState { revision }
      return EmptyComponent()
    }
  }

  private class UnsupportedStateComponent(private val value: Any) : KComponent() {
    override fun ComponentScope.render(): Component {
      useSaveableState { value }
      return EmptyComponent()
    }
  }

  private class AnyStateComponent(
      private val revision: Int = 0,
      private val onState: (State<Any>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      onState(useSaveableState<Any> { "initial $revision" })
      return EmptyComponent()
    }
  }

  /** Signals [started] in its render, then waits for [proceed], when given them. */
  private class BlockingStateComponent(
      private val started: CountDownLatch? = null,
      private val proceed: CountDownLatch? = null,
      private val onState: (State<Int>) -> Unit = {},
      private val onValue: (Int) -> Unit = {},
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useSaveableState { 0 }
      onState(state)
      onValue(state.value)
      if (started != null && proceed != null) {
        started.countDown()
        check(proceed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      }
      return EmptyComponent()
    }
  }

  private class InlineSaverComponent(
      private val version: Int,
      private val saveCalls: AtomicInteger,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      useSaveableState(
          saver =
              Saver(
                  save = { value ->
                    saveCalls.incrementAndGet()
                    "$version:$value"
                  },
                  restore = { value -> (value as String).substringAfter(':').toInt() },
              ),
      ) {
        0
      }
      return EmptyComponent()
    }
  }

  private class PersistentRootComponent : KComponent() {
    override fun ComponentScope.render(): Component = EmptyComponent()
  }

  private class PersistentChildComponent : KComponent() {
    override fun ComponentScope.render(): Component = EmptyComponent()
  }

  private class PersistentFirstComponent : KComponent() {
    override fun ComponentScope.render(): Component = EmptyComponent()
  }

  private class PersistentSecondComponent : KComponent() {
    override fun ComponentScope.render(): Component = EmptyComponent()
  }

  private data class Filter(val name: String)

  private fun LithoSaveableStateRegistry.restoredValueForTest(): Any? {
    val snapshot = snapshot()
    val restored = LithoSaveableStateRegistry(snapshot)
    val value = restored.restoredValue(snapshot.keySet().single())?.value
    restored.close()
    return value
  }

  private companion object {
    const val TIMEOUT_SECONDS: Long = 10
  }
}
