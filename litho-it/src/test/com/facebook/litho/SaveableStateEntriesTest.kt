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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.state.ComponentState
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.utils.NoDeps
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
class SaveableStateEntriesTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val registry = LithoSaveableStateRegistry()
  private val saver = Saver<Any?>(save = { it }, restore = { it })
  private val firstKey = StringGlobalKey("first")
  private val secondKey = StringGlobalKey("second")

  @Test
  fun `a tree state walks only entries whose single dep is a saveable slot`() {
    val treeState = TreeState()
    treeState.putEntries(
        firstKey,
        isLayoutState = false,
        arrayOf(slot("resolve")) to "resolve value",
        NoDeps to "plain value",
        arrayOf(slot("paired"), "other dep") to "paired value",
    )
    treeState.putEntries(secondKey, isLayoutState = true, arrayOf(slot("layout")) to "layout value")

    assertThat(treeState.saveableEntries())
        .containsExactlyInAnyOrder("resolve" to "resolve value", "layout" to "layout value")
  }

  @Test
  fun `a copied tree state walks the same entries`() {
    val original = TreeState()
    original.putEntries(firstKey, isLayoutState = false, arrayOf(slot("resolve")) to 1)
    original.putEntries(secondKey, isLayoutState = true, arrayOf(slot("layout")) to 2)

    assertThat(TreeState(original).saveableEntries())
        .containsExactlyInAnyOrderElementsOf(original.saveableEntries())
        .hasSize(2)
  }

  @Test
  fun `a commit that drops a component stops walking its entries`() {
    val committed = TreeState()
    committed.putEntries(firstKey, isLayoutState = false, arrayOf(slot("dropped")) to 1)
    committed.putEntries(secondKey, isLayoutState = false, arrayOf(slot("kept")) to 2)
    val local = TreeState(committed)
    local.markStateInUse(secondKey, isLayoutState = false)

    committed.commitResolveState(local)

    assertThat(committed.saveableEntries()).containsExactly("kept" to 2)
  }

  @Test
  fun `slots are equal only with the same explicit key, registry and generation`() {
    val slot = SaveableStateSlot("key", registry, "storage", saver, generation = 0)
    val withNewSaver =
        SaveableStateSlot("key", registry, "storage", Saver<Any?>({ it }, { it }), generation = 0)

    assertThat(slot).isEqualTo(withNewSaver).hasSameHashCodeAs(withNewSaver)
    assertThat(slot).isNotEqualTo(SaveableStateSlot("other", registry, "storage", saver, 0))
    assertThat(slot)
        .isNotEqualTo(SaveableStateSlot("key", LithoSaveableStateRegistry(), "storage", saver, 0))
    assertThat(slot).isNotEqualTo(SaveableStateSlot("key", registry, "storage", saver, 1))
    assertThat(SaveableStateSlot(null, null, null, saver, 0))
        .isEqualTo(SaveableStateSlot(null, null, null, withNewSaver.saver, 0))
  }

  @Test
  fun `a renderer is bound to a host only between bind and unbind`() {
    val renderer = LithoRenderer(context)
    assertThat(renderer.isBoundToHost).isFalse()

    renderer.bindTo(LithoRenderTreeView(context))
    assertThat(renderer.isBoundToHost).isTrue()

    renderer.unbind()
    assertThat(renderer.isBoundToHost).isFalse()
  }

  @Test
  fun `a component tree is always bound to its host`() {
    val tree = ComponentTree.create(ComponentContext(context)).build()
    val provider = checkNotNull(tree.context.lithoTree).treeStateProvider
    assertThat(provider.isBoundToHost).isTrue()
    assertThat(provider.committedTreeState).isSameAs(tree.committedTreeState)
  }

  private fun slot(storageKey: String): SaveableStateSlot =
      SaveableStateSlot(null, registry, storageKey, saver, generation = 0)

  private fun TreeState.putEntries(
      globalKey: GlobalKey,
      isLayoutState: Boolean,
      vararg entries: Pair<Array<*>, Any?>,
  ) {
    var container: KStateContainer? = null
    entries.forEachIndexed { index, (deps, value) ->
      val hookKey = HookKey.create(callSiteId = index + 1, index = 0, parent = null)
      container = KStateContainer.withNewStateByHookKey(container, hookKey, value, deps)
    }
    addState(globalKey, ComponentState(value = checkNotNull(container)), isLayoutState)
  }

  private fun TreeState.saveableEntries(): List<Pair<String?, Any?>> = buildList {
    forEachSaveableEntry { slot, value -> add(slot.storageKey to value) }
  }
}
