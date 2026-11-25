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

package com.facebook.litho.widget.collection

import androidx.recyclerview.widget.LinearLayoutManager
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.State
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/** [LazyListChildrenScope.childGroup] on the Sections-backed engine. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class LazyListChildGroupTest : LazyListChildGroupTestCases(enableNewCollection = false)

abstract class LazyListChildGroupTestCases(enableNewCollection: Boolean) {

  @get:Rule
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableNewCollection = enableNewCollection,
          enableLazyListChildGroups = true,
      ),
  )

  @Test
  fun `rows of a group are placed where it is declared, with ids scoped to the group`() {
    val ids = renderAndGetIds {
      LazyList {
        child(id = "top", component = Text("top"))
        childGroup(id = "group") {
          child(id = "a", component = Text("a"))
          child(id = "b", component = Text("b"))
        }
        child(id = "bottom", component = Text("bottom"))
      }
    }

    assertThat(ids)
        .containsExactly(
            "top",
            ChildGroupRowId("group", "a"),
            ChildGroupRowId("group", "b"),
            "bottom",
        )
  }

  @Test
  fun `the same row id in two groups does not collide`() {
    val ids = renderAndGetIds {
      LazyList {
        childGroup(id = "first") { child(id = "row", component = Text("first")) }
        childGroup(id = "second") { child(id = "row", component = Text("second")) }
      }
    }

    assertThat(ids)
        .containsExactly(ChildGroupRowId("first", "row"), ChildGroupRowId("second", "row"))
  }

  @Test
  fun `a nested group's rows take the innermost group's id`() {
    val ids = renderAndGetIds {
      LazyList {
        childGroup(id = "outer") {
          child(id = "before", component = Text("before"))
          childGroup(id = "inner") { child(id = "row", component = Text("row")) }
          child(id = "after", component = Text("after"))
        }
      }
    }

    assertThat(ids)
        .containsExactly(
            ChildGroupRowId("outer", "before"),
            ChildGroupRowId("inner", "row"),
            ChildGroupRowId("outer", "after"),
        )
  }

  @Test
  fun `a group id used twice in one list keeps both groups apart`() {
    val ids = renderAndGetIds {
      LazyList {
        childGroup(id = "group") { child(id = "a", component = Text("a")) }
        childGroup(id = "outer") {
          childGroup(id = "group") { child(id = "b", component = Text("b")) }
        }
      }
    }

    assertThat(ids)
        .containsExactly(
            ChildGroupRowId("group", "a"),
            ChildGroupRowId(DuplicateChildGroupId("group", 1), "b"),
        )
  }

  @Test
  fun `a state update in a group re-renders the list but not its owner`() {
    val ownerRenders = AtomicInteger()
    lateinit var expanded: State<Boolean>

    class Owner : KComponent() {
      // Packaged lint predates childGroup; its body runs synchronously during the list's render.
      @Suppress("KComponentHookInDeferredLambda")
      override fun ComponentScope.render(): Component {
        ownerRenders.incrementAndGet()
        return Column {
          child(
              LazyList {
                childGroup(id = "group") {
                  val isExpanded = useState { false }
                  expanded = isExpanded
                  child(id = "title", component = Text("title"))
                  if (isExpanded.value) {
                    child(id = "body", component = Text("body"))
                  }
                }
              },
          )
        }
      }
    }

    val testLithoView = lithoTestRule.render(widthPx = 500, heightPx = 1000) { Owner() }
    lithoTestRule.idle()
    lithoTestRule.act(testLithoView) { expanded.update(true) }
    lithoTestRule.idle()

    assertThat(testLithoView.findCollectionComponent()?.items?.map { it.id })
        .containsExactly(ChildGroupRowId("group", "title"), ChildGroupRowId("group", "body"))
    assertThat(ownerRenders.get()).isEqualTo(1)
  }

  @Test
  fun `groups added by the same helper keep separate state`() {
    val counts = mutableMapOf<String, State<Int>>()

    class Owner : KComponent() {
      override fun ComponentScope.render(): Component = LazyList {
        counterGroup("first", counts)
        counterGroup("second", counts)
      }
    }

    val testLithoView = lithoTestRule.render(widthPx = 500, heightPx = 1000) { Owner() }
    lithoTestRule.idle()
    lithoTestRule.act(testLithoView) { counts.getValue("first").update { it + 1 } }
    lithoTestRule.idle()

    assertThat(counts.getValue("first").value).isEqualTo(1)
    assertThat(counts.getValue("second").value).isEqualTo(0)
    assertThat(testLithoView.findCollectionComponent()?.items?.map { it.id })
        .containsExactly(ChildGroupRowId("first", "count:1"), ChildGroupRowId("second", "count:0"))
  }

  @Test
  fun `scrollToIdInGroup scrolls to the row inside that group`() {
    val controller = LazyCollectionController()

    class Owner : KComponent() {
      override fun ComponentScope.render(): Component =
          LazyList(lazyCollectionController = controller) {
            (0..50).forEach { group ->
              childGroup(id = group) { child(id = 0, component = Text("$group")) }
            }
          }
    }

    val testLithoView = lithoTestRule.render(widthPx = 100, heightPx = 100) { Owner() }
    lithoTestRule.idle()
    lithoTestRule.act(testLithoView) { controller.scrollToIdInGroup(25, 0) }
    testLithoView.setRoot(Owner())
    lithoTestRule.idle()

    val layoutManager = controller.recyclerView?.layoutManager as LinearLayoutManager
    assertThat(layoutManager.findFirstVisibleItemPosition()).isEqualTo(25)
  }

  // Packaged lint cannot see this helper's separate synchronous childGroup render scope.
  @Suppress("KComponentHookCallsite")
  private fun LazyListChildrenScope.counterGroup(
      id: String,
      counts: MutableMap<String, State<Int>>,
  ) {
    childGroup(id = id) {
      val count = useState { 0 }
      counts[id] = count
      child(id = "count:${count.value}", component = Text("${count.value}"))
    }
  }

  private fun renderAndGetIds(content: ComponentScope.() -> Component): List<Any?>? {
    class Owner : KComponent() {
      override fun ComponentScope.render(): Component = content()
    }
    val testLithoView = lithoTestRule.render(widthPx = 500, heightPx = 1000) { Owner() }
    lithoTestRule.idle()
    return testLithoView.findCollectionComponent()?.items?.map { it.id }
  }
}
