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

import com.facebook.litho.LifecycleStep.StepInfo
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.LayoutWithSizeSpecLifecycleTester
import com.facebook.rendercore.dp
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/**
 * Correctness tests for the transient/ownership KStateContainer optimisation: fork the container
 * once per render pass, then mutate in place.
 *
 * Only cases that are about the fork/in-place mechanics itself belong here. General hook semantics
 * belong in the ordinary state suites.
 */
@Suppress("LegacyLooperMode")
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class HookTransientStateTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun useState_update_visibleAndPersistsAcrossReRender() {
    lateinit var stateRef: AtomicReference<State<Int>>

    class C : KComponent() {
      override fun ComponentScope.render(): Component {
        enterHook(0)
        val s = useState { 1 }
        exitHook()
        stateRef = AtomicReference(s)
        return Row(style = Style.width(10.dp).height(10.dp))
      }
    }

    val view = lithoTestRule.render { C() }
    assertThat(stateRef.get().value).isEqualTo(1)

    lithoTestRule.act(view) { stateRef.get().update(5) }
    assertThat(stateRef.get().value).isEqualTo(5)

    // Re-render with a fresh (non-equivalent) root instance; state must persist.
    lithoTestRule.act(view) { view.setRoot(C()) }
    assertThat(stateRef.get().value).isEqualTo(5)
  }

  @Test
  fun manyHooks_seededCorrectly_andIndependentUpdates() {
    val captured = mutableListOf<State<Int>>()

    class C(private val n: Int) : KComponent() {
      override fun ComponentScope.render(): Component {
        captured.clear()
        repeat(n) { i ->
          enterHook(0)
          val s = useState { 100 + i }
          exitHook()
          captured.add(s)
        }
        return Row(style = Style.width(10.dp).height(10.dp))
      }
    }

    val view = lithoTestRule.render { C(20) }
    captured.forEachIndexed { i, s -> assertThat(s.value).isEqualTo(100 + i) }

    // Update one hook in the middle; only it changes, the rest stay intact (in-place correctness).
    lithoTestRule.act(view) { captured[10].update(999) }
    captured.forEachIndexed { i, s ->
      assertThat(s.value).isEqualTo(if (i == 10) 999 else 100 + i)
    }
  }

  @Test
  fun variableHookCount_acrossRenders_valuesStayConsistent() {
    val captured = mutableListOf<State<Int>>()

    class C(private val n: Int) : KComponent() {
      override fun ComponentScope.render(): Component {
        captured.clear()
        repeat(n) { i ->
          enterHook(0)
          val s = useState { i }
          exitHook()
          captured.add(s)
        }
        return Row(style = Style.width(10.dp).height(10.dp))
      }
    }

    val view = lithoTestRule.render { C(5) }
    assertThat(captured.map { it.value }).isEqualTo(listOf(0, 1, 2, 3, 4))

    // Grow the hook set (seeds new entries into an owned container).
    lithoTestRule.act(view) { view.setRoot(C(9)) }
    assertThat(captured.map { it.value }).isEqualTo(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8))

    // Shrink then grow again — retained entries must keep their values.
    lithoTestRule.act(view) { view.setRoot(C(3)) }
    assertThat(captured.map { it.value }).isEqualTo(listOf(0, 1, 2))
    lithoTestRule.act(view) { view.setRoot(C(7)) }
    assertThat(captured.map { it.value }).isEqualTo(listOf(0, 1, 2, 3, 4, 5, 6))
  }

  /**
   * A nested tree resolves in the layout phase, so its hooks land in a different state handler, and
   * it can be resolved more than once for different size specs. Ownership is per (component, render
   * pass) - a fresh [ComponentScope] each time - so neither sibling components nor repeat
   * resolutions may see each other's in-place mutations.
   */
  @Test
  fun nestedTree_hookStateIsScopedPerComponent() {
    val captured = mutableMapOf<String, State<Int>>()

    class Leaf(private val tag: String, private val seed: Int) : KComponent() {
      override fun ComponentScope.render(): Component {
        enterHook(0)
        val state = useState { seed }
        exitHook()
        captured[tag] = state
        return Row(style = Style.width(10.dp).height(10.dp))
      }
    }

    class Body : KComponent() {
      override fun ComponentScope.render(): Component = Column {
        child(Leaf("a", 1))
        child(Leaf("b", 2))
      }
    }

    val view = lithoTestRule.render {
      LayoutWithSizeSpecLifecycleTester.create(context)
          .steps(ArrayList<StepInfo>())
          .body(Body())
          .build()
    }

    assertThat(captured["a"]?.value).isEqualTo(1)
    assertThat(captured["b"]?.value).isEqualTo(2)

    // Updating one leaf inside the nested tree must not disturb its sibling.
    lithoTestRule.act(view) { captured.getValue("a").update(99) }
    assertThat(captured["a"]?.value).isEqualTo(99)
    assertThat(captured["b"]?.value).isEqualTo(2)

    // Re-resolving the nested tree keeps both, i.e. no fork was left owned across passes.
    lithoTestRule.act(view) {
      view.setRoot(
          LayoutWithSizeSpecLifecycleTester.create(lithoTestRule.context)
              .steps(ArrayList<StepInfo>())
              .body(Body())
              .build(),
      )
    }
    assertThat(captured["a"]?.value).isEqualTo(99)
    assertThat(captured["b"]?.value).isEqualTo(2)
  }
}
