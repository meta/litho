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

import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/**
 * Behavioural tests for unconditional hook-state pruning.
 *
 * Hook sets are varied across renders of the SAME component (stable globalKey) via a manual
 * `enterHook(0)` loop -- each iteration gets a distinct runtime index, mirroring what the compiler
 * plugin emits for hooks in a loop -- so the trailing/dropped-entry pruning paths are exercised
 * directly (not via a component-class swap, which would change the globalKey).
 */
@Suppress("LegacyLooperMode")
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class HookStatePruningTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  /**
   * Renders [count] useState hooks under a single call site (distinct runtime indices). The loop is
   * intentional: it is exactly the variable-hook-count scenario that hook identity enables and that
   * these tests exercise.
   */
  private class LoopComponent(val count: Int, val captured: MutableList<State<Int>>) :
      KComponent() {
    override fun ComponentScope.render(): Component? {
      captured.clear()
      repeat(count) { i ->
        enterHook(0)
        val s = useState { 100 + i }
        exitHook()
        captured.add(s)
      }
      return null
    }
  }

  @Test
  fun prune_dropsTrailingHookAndResetsItOnReInvoke() {
    val captured = mutableListOf<State<Int>>()
    val view = lithoTestRule.render { LoopComponent(2, captured) }
    assertThat(captured.map { it.value }).containsExactly(100, 101)

    // Update the second hook, then re-render with only the first hook -> the second is pruned.
    val second = captured[1]
    lithoTestRule.act(view) { second.update(999) }
    lithoTestRule.act(view) { view.setRoot(LoopComponent(1, captured)) }
    assertThat(captured.map { it.value }).containsExactly(100)

    // Re-render with both hooks again -> the second hook is re-created from its initializer
    // (proving
    // it was dropped); the first hook retains its value.
    lithoTestRule.act(view) { view.setRoot(LoopComponent(2, captured)) }
    assertThat(captured[0].value).isEqualTo(100)
    assertThat(captured[1].value).isEqualTo(101)
  }

  @Test
  fun prune_dropsAllHooksWhenNoneInvoked() {
    val captured = mutableListOf<State<Int>>()
    val view = lithoTestRule.render { LoopComponent(2, captured) }
    lithoTestRule.act(view) { captured[0].update(900) }
    lithoTestRule.act(view) { captured[1].update(901) }

    // Re-render the SAME globalKey invoking ZERO hooks. A re-rendered hookless component never
    // calls
    // markStateInUse, so clearUnusedState reclaims its whole container at commit -- which is why
    // pruneToVisitedIfNeeded can safely early-return when no hooks were visited.
    lithoTestRule.act(view) { view.setRoot(LoopComponent(0, captured)) }

    // Re-render with both hooks -> both must re-initialise (100/101), proving the prior state
    // (900/901) was dropped rather than retained.
    lithoTestRule.act(view) { view.setRoot(LoopComponent(2, captured)) }
    assertThat(captured[0].value).isEqualTo(100)
    assertThat(captured[1].value).isEqualTo(101)
  }

  // NOTE on hook reordering: changing the *visit order* of hooks across renders (with stable call
  // sites) re-initialises the reordered hooks' values. Pruning is neutral with respect to reorder,
  // so there is no pruning-specific test for it here (it would be testing
  // hook-identity reorder semantics, not pruning). The set-based sweep preserves the values of
  // every
  // hook that the normal path retained, which is what the cases below verify.

  @Test
  fun prune_preservesValuesWhenHookSetIsUnchanged() {
    val captured = mutableListOf<State<Int>>()
    val view = lithoTestRule.render { LoopComponent(3, captured) }
    lithoTestRule.act(view) { captured[0].update(1) }
    lithoTestRule.act(view) { captured[1].update(2) }
    lithoTestRule.act(view) { captured[2].update(3) }
    lithoTestRule.act(view) { view.setRoot(LoopComponent(3, captured)) }
    assertThat(captured.map { it.value }).containsExactly(1, 2, 3)
  }

  @Test
  fun prune_updateOnPrunedHookIsNoOpAndDoesNotCrash() {
    val captured = mutableListOf<State<Int>>()
    val view = lithoTestRule.render { LoopComponent(2, captured) }
    val prunedHandle = captured[1]

    // Prune the second hook.
    lithoTestRule.act(view) { view.setRoot(LoopComponent(1, captured)) }

    // Updating the captured (now-pruned) handle must not crash or resurrect the entry.
    lithoTestRule.act(view) { prunedHandle.update(123) }

    // Re-expanding re-creates the hook fresh (no zombie value).
    lithoTestRule.act(view) { view.setRoot(LoopComponent(2, captured)) }
    assertThat(captured[1].value).isEqualTo(101)
  }

  @Test
  fun prune_readOnPrunedHookReturnsFallbackAndDoesNotCrash() {
    val captured = mutableListOf<State<Int>>()
    val view = lithoTestRule.render { LoopComponent(2, captured) }
    val prunedHandle = captured[1]
    val valueBeforePrune = prunedHandle.value

    // Prune the second hook, then read the captured handle (read tracking is on).
    lithoTestRule.act(view) { view.setRoot(LoopComponent(1, captured)) }
    val valueAfterPrune = prunedHandle.value

    // The read falls back to the last-known value instead of crashing on the absent entry.
    assertThat(valueAfterPrune).isEqualTo(valueBeforePrune)
  }
}
