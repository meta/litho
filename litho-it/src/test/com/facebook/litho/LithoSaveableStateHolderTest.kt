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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.VerticalScroll
import com.facebook.litho.widget.collection.CollectionInfraOverride
import com.facebook.litho.widget.collection.LazyList
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalLithoApi::class)
@SuppressLint("KComponentSyncUpdateDetector")
@RunWith(LithoTestRunner::class)
class LithoSaveableStateHolderTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `a covered destination restores its saveable values and resets useState`() {
    val nav = TestNavigation()
    val view = render(createTree(TestSavedStateOwner(), NavHost(nav)))
    nav.entry("a").saveableState.updateSync(5)
    nav.entry("a").state.updateSync(5)

    nav.push("b")
    assertThat(nav.entry("b").saveableState.value).isEqualTo(0)
    nav.pop()

    assertThat(nav.entry("a").saveableState.value).isEqualTo(5)
    assertThat(nav.entry("a").state.value).isEqualTo(0)
    view.release()
  }

  @Test
  fun `a popped destination starts fresh when it is pushed again`() {
    val nav = TestNavigation()
    val view = render(createTree(TestSavedStateOwner(), NavHost(nav)))
    nav.push("b")
    nav.entry("b").saveableState.updateSync(3)

    nav.pop()
    nav.push("b")

    assertThat(nav.entry("b").saveableState.value).isEqualTo(0)
    view.release()
  }

  /**
   * ```text
   * a = 5, push b, b = 9 ── process death ──> b restores 9, pop, a restores 5
   * ```
   */
  @Test
  fun `covered destinations survive process death`() {
    val firstOwner = TestSavedStateOwner()
    val first = TestNavigation()
    render(createTree(firstOwner, NavHost(first)))
    first.entry("a").saveableState.updateSync(5)
    first.push("b")
    first.entry("b").saveableState.updateSync(9)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restored = TestNavigation()
    val view = render(createTree(TestSavedStateOwner(savedState), NavHost(restored)))
    assertThat(restored.top).isEqualTo("b")
    assertThat(restored.entry("b").saveableState.value).isEqualTo(9)
    restored.pop()

    assertThat(restored.entry("a").saveableState.value).isEqualTo(5)
    view.release()
  }

  @Test
  fun `a popped destination is not saved`() {
    val firstOwner = TestSavedStateOwner()
    val first = TestNavigation()
    render(createTree(firstOwner, NavHost(first)))
    first.push("b")
    first.entry("b").saveableState.updateSync(9)
    first.pop()
    first.push("b")
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restored = TestNavigation()
    val view = render(createTree(TestSavedStateOwner(savedState), NavHost(restored)))

    assertThat(restored.entry("b").saveableState.value).isEqualTo(0)
    view.release()
  }

  @Test
  fun `two holders with the same destination keys keep separate values`() {
    val left = TestNavigation()
    val right = TestNavigation()
    val view = render(createTree(TestSavedStateOwner(), TwoNavHosts(NavHost(left), NavHost(right))))
    left.entry("a").saveableState.updateSync(1)
    right.entry("a").saveableState.updateSync(2)

    left.push("b")
    right.push("b")
    left.pop()
    right.pop()

    assertThat(left.entry("a").saveableState.value).isEqualTo(1)
    assertThat(right.entry("a").saveableState.value).isEqualTo(2)
    view.release()
  }

  @Test
  fun `removing the holder's component drops the values it kept`() {
    val first = TestNavigation()
    val view = render(createTree(TestSavedStateOwner(), NavHost(first)))
    first.entry("a").saveableState.updateSync(5)
    first.push("b")

    view.setRoot(EmptyComponent())
    val next = TestNavigation()
    view.setRoot(NavHost(next))

    assertThat(next.top).isEqualTo("a")
    assertThat(next.entry("a").saveableState.value).isEqualTo(0)
    view.release()
  }

  @Test
  fun `without an owner, covered destinations restore while the tree lives`() {
    val nav = TestNavigation()
    val tree = ComponentTree.create(lithoTestRule.context, NavHost(nav)).build()
    val view = render(tree)
    nav.entry("a").saveableState.updateSync(5)

    nav.push("b")
    nav.pop()

    assertThat(nav.entry("a").saveableState.value).isEqualTo(5)
    view.release()
  }

  @Test
  fun `a covered destination keeps values inside a vertical scroll`() {
    assertCoveredDestinationKeepsValues { destination -> VerticalScroll(child = destination) }
  }

  @Test
  fun `a covered destination keeps values inside a lazy list`() {
    assertCoveredDestinationKeepsValues { destination ->
      LazyList(style = Style.height(100.px).width(100.px)) {
        child(id = "row", component = destination)
      }
    }
  }

  @Test
  fun `a covered destination keeps values inside a legacy lazy list`() {
    assertCoveredDestinationKeepsValues { destination ->
      LazyList(
          style = Style.height(100.px).width(100.px),
          infraOverride = CollectionInfraOverride.FORCE_LEGACY,
      ) {
        child(id = "row", component = destination)
      }
    }
  }

  private fun assertCoveredDestinationKeepsValues(wrap: ComponentScope.(Component) -> Component) {
    val nav = TestNavigation()
    val view =
        lithoTestRule
            .createTestLithoView(
                componentTree = createTree(TestSavedStateOwner(), NavHost(nav, wrap)),
                widthPx = VIEW_SIZE_PX,
                heightPx = VIEW_SIZE_PX,
            )
            .attachToWindow()
            .measure()
            .layout()
    nav.entry("a").saveableState.updateSync(5)
    lithoTestRule.idle()
    assertThat(nav.entry("a").saveableState.value).describedAs("before push").isEqualTo(5)

    nav.push("b")
    lithoTestRule.idle()
    nav.pop()

    assertThat(nav.entry("a").saveableState.value).isEqualTo(5)
    view.release()
  }

  @Test
  fun `a covered destination keeps its nested registries`() {
    val holder = LithoSaveableStateHolder(LithoSaveableStateRegistry())
    val entry = holder.registryFor("a")
    val entryValue = LiveValue(entry, "counter", 3)
    val nested = entry.createChildRegistry("item")
    val nestedValue = LiveValue(nested, "scroll", 42)

    holder.retain("a")
    entryValue.close()
    nestedValue.close()
    nested.close()

    assertThat(entry.restoredValue("counter")?.value).isEqualTo(3)
    assertThat(entry.createChildRegistry("item").restoredValue("scroll")?.value).isEqualTo(42)
    holder.close()
  }

  /** Drives a [NavHost] the way a navigation host does. */
  private class TestNavigation {
    lateinit var holder: LithoSaveableStateHolder
    lateinit var backStack: State<List<String>>
    private val entries = mutableMapOf<String, EntryStates>()

    val top: String
      get() = backStack.value.last()

    fun entry(id: String): EntryStates = checkNotNull(entries[id])

    fun onEntry(id: String, states: EntryStates) {
      entries[id] = states
    }

    fun push(id: String) {
      holder.retain(top)
      backStack.updateSync { entries -> entries + id }
    }

    fun pop() {
      val popped = top
      backStack.updateSync { entries -> entries.dropLast(1) }
      holder.removeState(popped)
    }
  }

  /** A destination's values from [useSaveableState] and from [useState]. */
  private class EntryStates(val saveableState: State<Int>, val state: State<Int>)

  private class NavHost(
      private val nav: TestNavigation,
      private val wrap: ComponentScope.(Component) -> Component = { it },
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val holder = useSaveableStateHolder()
      val backStack = useSaveableState(saver = BackStackSaver) { listOf("a") }
      nav.holder = holder
      nav.backStack = backStack
      val top = backStack.value.last()
      return holder.SaveableStateProvider(top) { wrap(Destination(top, nav)) }
    }
  }

  private class Destination(private val id: String, private val nav: TestNavigation) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      nav.onEntry(
          id,
          EntryStates(saveableState = useSaveableState { 0 }, state = useState { 0 }),
      )
      return EmptyComponent()
    }
  }

  private class TwoNavHosts(private val left: Component, private val right: Component) :
      KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(left)
      child(right)
    }
  }

  private fun createTree(owner: SavedStateRegistryOwner, root: Component): ComponentTree =
      ComponentTree.create(lithoTestRule.context.androidContext, owner, root).build()

  private fun render(tree: ComponentTree): TestLithoView =
      lithoTestRule.createTestLithoView(componentTree = tree).attachToWindow().measure().layout()

  private class TestSavedStateOwner(restoredState: Bundle? = null) : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    init {
      controller.performAttach()
      controller.performRestore(restoredState)
      lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun save(): Bundle = Bundle().also(controller::performSave)

    fun destroy() {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
  }

  private companion object {
    const val VIEW_SIZE_PX: Int = 100
    val BackStackSaver: Saver<List<String>> = Saver(
        save = { ArrayList(it) },
        restore = { value ->
          @Suppress("UNCHECKED_CAST") (value as ArrayList<String>).toList()
        },
    )
  }
}
