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

/**
 * Tests that LayoutState.copyWith reuses cachedRenderTree correctly, and that subsequent mount
 * operations (both skip and full) work after a cached RenderTree is promoted.
 */
@RunWith(LithoTestRunner::class)
class RenderTreeReuseTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `mount skip reuses RenderTree when unread state is updated`() {
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val counter = useState { 0 } // declared but NOT read
        return Column(style = Style.viewTag("trigger").onClick { counter.update { it + 1 } }) {
          child(Text("Hello"))
        }
      }
    }

    val testView = lithoTestRule.render { TestComponent() }
    val initialLayoutState = checkNotNull(testView.committedLayoutState)
    val initialRenderTree = initialLayoutState.toRenderTree()

    // State update on unread state → layout skip → same RenderTree
    lithoTestRule.act(testView) { clickOnTag("trigger") }

    val updatedLayoutState = checkNotNull(testView.committedLayoutState)
    val updatedRenderTree = updatedLayoutState.toRenderTree()

    // Different LayoutState, but same RenderTree (reused via copyWith)
    assertThat(updatedLayoutState).isNotSameAs(initialLayoutState)
    assertThat(updatedRenderTree).isSameAs(initialRenderTree)
  }

  @Test
  fun `full mount works after a cached RenderTree was promoted`() {
    var showExtra = false

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val counter = useState { 0 } // declared but NOT read
        return Column(style = Style.viewTag("trigger").onClick { counter.update { it + 1 } }) {
          child(Text("Hello"))
          if (showExtra) {
            child(Text("Extra"))
          }
        }
      }
    }

    val testView = lithoTestRule.render { TestComponent() }

    // State update on unread state → layout skip → cached RenderTree promoted
    lithoTestRule.act(testView) { clickOnTag("trigger") }

    val afterSkipRenderTree = checkNotNull(testView.committedLayoutState).toRenderTree()

    // Now setRoot with a structural change → new resolve + layout + mount
    showExtra = true
    testView.setRoot(TestComponent())

    val afterSetRootRenderTree = checkNotNull(testView.committedLayoutState).toRenderTree()

    // New RenderTree because the tree structure changed
    assertThat(afterSetRootRenderTree).isNotSameAs(afterSkipRenderTree)

    // Verify the new tree has more mounted outputs than before
    assertThat(afterSetRootRenderTree.mountableOutputCount)
        .isGreaterThan(afterSkipRenderTree.mountableOutputCount)
  }

  @Test
  fun `read state update produces new RenderTree after cached one`() {
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val counter = useState { 0 }
        return Column(style = Style.viewTag("trigger").onClick { counter.update { it + 1 } }) {
          child(Text("Count: ${counter.value}")) // state IS read
          child(Text("Static"))
        }
      }
    }

    val testView = lithoTestRule.render { TestComponent() }
    val initialRenderTree = checkNotNull(testView.committedLayoutState).toRenderTree()

    // State update on read state → full resolve + layout → new RenderTree
    lithoTestRule.act(testView) { clickOnTag("trigger") }

    val updatedRenderTree = checkNotNull(testView.committedLayoutState).toRenderTree()

    // New RenderTree because resolve/layout re-ran (state is read)
    assertThat(updatedRenderTree).isNotSameAs(initialRenderTree)
  }

  @Test
  fun `multiple consecutive skip-mounts followed by full mount`() {
    var generation = 0

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val counter = useState { 0 } // NOT read
        return Column(style = Style.viewTag("trigger").onClick { counter.update { it + 1 } }) {
          child(Text("Gen $generation"))
        }
      }
    }

    val testView = lithoTestRule.render { TestComponent() }
    val initialRenderTree = checkNotNull(testView.committedLayoutState).toRenderTree()

    // Multiple state updates on unread state — each should reuse the same RenderTree
    repeat(5) {
      lithoTestRule.act(testView) { clickOnTag("trigger") }
      val currentRenderTree = checkNotNull(testView.committedLayoutState).toRenderTree()
      assertThat(currentRenderTree).isSameAs(initialRenderTree)
    }

    // Now do a real structural change via setRoot
    generation = 1
    testView.setRoot(TestComponent())

    val newRenderTree = checkNotNull(testView.committedLayoutState).toRenderTree()

    // New RenderTree from the new resolve + layout
    assertThat(newRenderTree).isNotSameAs(initialRenderTree)
  }
}
