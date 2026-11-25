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

import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.dp
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * Hooks called on a [ComponentScope] created with a host belong to the host's component. Runs with
 * and without enableLazyListChildGroups: with it off, creating a hosted scope moves its host's hook
 * data into a shared HookState.
 */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(ParameterizedRobolectricTestRunner::class)
class HostedComponentScopeTest(private val enableLazyListChildGroups: Boolean) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "enableLazyListChildGroups={0}")
    fun settings(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @Rule
  @JvmField
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableLazyListChildGroups = enableLazyListChildGroups,
      ),
  )

  @OptIn(ExperimentalLithoApi::class)
  private class HostedScope(host: ComponentScope) : ComponentScope(host)

  @Test
  fun `state of a hook called on a hosted scope is kept by the host component`() {
    val renders = AtomicInteger()
    val renderedCount = AtomicInteger(-1)

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        renders.incrementAndGet()
        val hosted = HostedScope(this)
        val state = hosted.useState { 0 }
        renderedCount.set(state.value)
        return Row(
            style =
                Style.height(100.dp).width(100.dp).viewTag("tag").onClick {
                  state.update { it + 1 }
                },
        )
      }
    }

    val testLithoView = lithoTestRule.render { TestComponent() }
    lithoTestRule.act(testLithoView) { clickOnTag("tag") }
    lithoTestRule.act(testLithoView) { clickOnTag("tag") }

    assertThat(renderedCount.get()).isEqualTo(2)
    assertThat(renders.get()).isEqualTo(3)
  }

  @Test
  fun `hooks on the host and on a hosted scope share one state container`() {
    lateinit var hostState: State<String>
    lateinit var hostedState: State<String>

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        hostState = useState { "host" }
        val hosted = HostedScope(this)
        hostedState = hosted.useState { "hosted" }
        return Row(style = Style.height(100.dp).width(100.dp))
      }
    }

    val testLithoView = lithoTestRule.render { TestComponent() }
    lithoTestRule.act(testLithoView) { hostedState.update("hosted-updated") }
    lithoTestRule.act(testLithoView) { hostState.update("host-updated") }

    assertThat(hostState.value).isEqualTo("host-updated")
    assertThat(hostedState.value).isEqualTo("hosted-updated")
  }

  @Test
  fun `hooks on a hosted scope are keyed under the host's current scope`() {
    val states = mutableMapOf<Int, State<String>>()

    class TestComponent(private val items: List<Int>) : KComponent() {
      override fun ComponentScope.render(): Component? {
        states.clear()
        items.forEach { item ->
          withNewScope(item) {
            val hosted = HostedScope(this)
            states[item] = hosted.useState { "item-$item" }
          }
        }
        return Row(style = Style.height(100.dp).width(100.dp))
      }
    }

    val testLithoView = lithoTestRule.render { TestComponent(items = listOf(1, 2)) }
    lithoTestRule.act(testLithoView) { states.getValue(1).update("updated-1") }
    lithoTestRule.act(testLithoView) {
      testLithoView.setRoot(TestComponent(items = listOf(2, 1)))
    }

    assertThat(states.getValue(1).value).isEqualTo("updated-1")
    assertThat(states.getValue(2).value).isEqualTo("item-2")
  }

  @Test
  fun `effects declared on a hosted scope attach and detach with the host component`() {
    val calls = mutableListOf<String>()

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component? {
        val hosted = HostedScope(this)
        hosted.useEffect(Unit) {
          calls.add("attach")
          onCleanup { calls.add("detach") }
        }
        return null
      }
    }

    lithoTestRule
        .render(widthSpec = exactly(100), heightSpec = exactly(100)) { TestComponent() }
        .detachFromWindow()
        .release()

    assertThat(calls).containsExactly("attach", "detach")
  }

  @Test
  fun `a hosted block skipped for one render starts over and detaches its effect`() {
    lateinit var blockState: State<Int>
    val calls = mutableListOf<String>()

    class TestComponent(private val showBlock: Boolean) : KComponent() {
      override fun ComponentScope.render(): Component {
        if (showBlock) {
          val hosted = HostedScope(this)
          blockState = hosted.useState { 0 }
          hosted.useEffect(Unit) {
            calls.add("attach")
            onCleanup { calls.add("detach") }
          }
        }
        return Row(style = Style.height(100.dp).width(100.dp))
      }
    }

    val testLithoView = lithoTestRule.render { TestComponent(showBlock = true) }
    lithoTestRule.act(testLithoView) { blockState.update(5) }
    assertThat(blockState.value).isEqualTo(5)

    lithoTestRule.act(testLithoView) { testLithoView.setRoot(TestComponent(showBlock = false)) }
    lithoTestRule.act(testLithoView) { testLithoView.setRoot(TestComponent(showBlock = true)) }

    assertThat(blockState.value).isEqualTo(0)
    assertThat(calls).containsExactly("attach", "detach", "attach")
  }
}
