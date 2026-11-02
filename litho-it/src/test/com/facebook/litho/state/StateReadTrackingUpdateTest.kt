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

package com.facebook.litho.state

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class StateReadTrackingUpdateTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `resolve result stores updated root component when resolve exits early`() {
    // This test verifies that when resolve exits early due to read tracking,
    // the ResolveResult stores the NEW root component, not the old one.
    //
    // The early exit happens when:
    // 1. Read tracking is enabled
    // 2. scopesToUpdate is empty (no component reads the updated state)
    // 3. hasUncommittedUpdates() is true
    // 4. isReconcilable returns true (components are equivalent)
    //
    // The fix ensures that `component = component` is set in the copy(),
    // storing the new component passed to resolve, not the old one.

    // A component that has state which is NOT read during render.
    // When state is updated, no component is "dirty" so resolve can exit early.
    class TestRootComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val state = useState { 0 }
        return Column(style = Style.viewTag("test").onClick { state.update { it + 1 } }) {
          child(Text("Hello"))
        }
      }
    }

    // Initial render
    val testView = lithoTestRule.render { TestRootComponent() }

    // Get initial resolve result
    val initialResolveResult = checkNotNull(testView.committedLayoutState).resolveResult
    assertThat(initialResolveResult.outputs?.stateReads).isNull()

    // Triggers a state update that skips resolve
    lithoTestRule.act(testView) { clickOnTag("test") }

    val updatedResolveResult = checkNotNull(testView.committedLayoutState).resolveResult

    // Verify the ResolveResult contains a NEW root component, not the old one
    assertThat(updatedResolveResult.outputs?.stateReads).isNull()
    assertThat(updatedResolveResult.node).isSameAs(initialResolveResult.node)
    assertThat(updatedResolveResult.component).isNotSameAs(initialResolveResult.component)
  }
}
