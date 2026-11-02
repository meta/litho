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

package com.facebook.litho.examples

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.assertj.LithoAssertions
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.Text
import com.facebook.litho.widget.collection.LazyList
import com.facebook.rendercore.stringRes
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Examples of LithoTestRuleList usage */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LithoTestRuleListExampleTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `test child is present in LazyList`() {
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        return LazyList {
          for (i in 1..10) {
            child(id = i, component = Text("child$i"))
          }
        }
      }
    }

    val testView =
        lithoTestRule.render(widthPx = exactly(100), heightPx = exactly(100)) { TestComponent() }

    assertThat(testView).isNotNull()

    val listComponent = testView.findCollectionComponent()
    assertThat(listComponent).isNotNull()

    assertThat(listComponent?.itemCount ?: return).isEqualTo(10)
    assertThat(listComponent.getItemAtIndex(0).component)
        .hasFieldOrPropertyWithValue("text", "child1")

    assertThat(listComponent.getItemAtIndex(listComponent.lastVisibleIndex).component)
        .hasFieldOrPropertyWithValue("text", "child3")

    assertThat(
        listComponent.items.slice(
            listComponent.firstVisibleIndex..listComponent.lastVisibleIndex,
        ),
    )
        .hasSize(3)

    val component = listComponent.findFirstItem(Text::class)?.component
    assertThat(component).isNotNull()
    assertThat(component).hasFieldOrPropertyWithValue("text", "child1")
  }

  @Test
  fun `test child is present in LazyList with multiple component types`() {

    @DataClassGenerate data class Item(val id: Int, val name: String, val quantity: Int)

    val items = listOf(
        Item(1, "Flour", 2),
        Item(2, "Cinnamon", 3),
        Item(3, "Salt", 100),
        Item(4, "Butter", 1),
    )

    class ItemComponent(val model: Item) : KComponent() {
      override fun ComponentScope.render(): Component? {
        return Text("${model.name}: ${model.quantity}")
      }
    }

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component? {
        return LazyList() {
          child(Text("Item List"))
          for (item in items) {
            child(id = item.id, component = ItemComponent(item))
          }
        }
      }
    }

    val testView =
        lithoTestRule.render(widthPx = exactly(100), heightPx = exactly(100)) { TestComponent() }

    assertThat(testView.findViewWithTextOrNull("Flour: 2")).isNotNull()
    assertThat(testView.findAllComponents(Text::class))
        // this should return empty for now.
        .isEmpty()

    val listComponent = testView.findCollectionComponent()
    assertThat(listComponent).isNotNull()

    val component = listComponent?.findFirstItem(ItemComponent::class)?.component ?: return
    assertThat(component).hasFieldOrPropertyWithValue("model", items[0])

    val foundComponents = listComponent.findItems(ItemComponent::class)
    assertThat(foundComponents).hasSize(4)
  }

  @Test
  fun `test chained assertions`() {

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component = LazyList {
        children(items = (0..4), id = { it }) { Text("$it") }
      }
    }

    val testView =
        lithoTestRule.render(widthPx = exactly(100), heightPx = exactly(100)) { TestComponent() }

    val listComponent = testView.findCollectionComponent()
    assertThat(listComponent).isNotNull
    listComponent ?: return

    val testComponentScope = ComponentScope(context = lithoTestRule.context)
    val zeroTextComponent = with(testComponentScope) { Text("0") }
    val fourTextComponent = with(testComponentScope) { Text("4") }
    val helloTextComponent = with(testComponentScope) { Text("Hello") }
    val fullComponentList =
        with(testComponentScope) { arrayOf(Text("0"), Text("1"), Text("2"), Text("3")) }

    LithoAssertions.assertThat(listComponent)
        .onFirstChild { it.isVisible }
        .onLastChild { it.component?.isEquivalentTo(fourTextComponent) == true }
        .onChild(withIndex = 2) { it.isVisible }
        .onChild(withId = 3) { !it.isFullyVisible }
        .hasSize(5)
        .onChildren(matching = { it.isSticky }) { it.isEmpty() }
        .containsComponents(zeroTextComponent)
        .doesNotContainComponents(helloTextComponent)
        .containsExactlyComponents(*fullComponentList)
        .hasVisibleText("0")
        .doesNotHaveVisibleText("cheeseburger")

    LithoAssertions.assertThat(listComponent.firstItem).isVisible().isFullyVisible()

    LithoAssertions.assertThat(listComponent.firstVisibleItem)
        .isVisible()
        .isFullyVisible()
        .hasIndex(0)

    val secondChild = listComponent.getItemAtIndex(2)
    LithoAssertions.assertThat(secondChild)
        .isVisible()
        .isNotFullyVisible()
        .isEqualTo(listComponent.lastVisibleItem)

    LithoAssertions.assertThat(listComponent.lastItem).isNotVisible().isNotFullyVisible()
  }

  @Test
  fun `test res visible and non-visible text`() {
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component = LazyList {
        child(id = "resTextChild", deps = emptyArray()) {
          Text(text = stringRes(android.R.string.selectAll))
        }
      }
    }

    val testView =
        lithoTestRule.render(widthPx = exactly(100), heightPx = exactly(100)) { TestComponent() }

    val listComponent = testView.findCollectionComponent()
    assertThat(listComponent).isNotNull

    LithoAssertions.assertThat(listComponent)
        .onFirstChild { it.isVisible }
        .hasVisibleText(android.R.string.selectAll)
        .doesNotHaveVisibleText(android.R.string.cancel)
  }
}
