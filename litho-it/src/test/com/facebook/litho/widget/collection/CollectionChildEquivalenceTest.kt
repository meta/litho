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

import android.graphics.Color
import com.facebook.litho.ClickEvent
import com.facebook.litho.ComponentScope
import com.facebook.litho.Style
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.onClick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Tests for [Collection]'s children */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionChildEquivalenceTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `test children with null fields are equivalent`() {
    val next = CollectionChild("", null)
    val previous = CollectionChild("", null)

    assertThat(isChildEquivalent(previous, next)).isTrue
  }

  @Test
  fun `test children with equal deps are equivalent`() {
    val next = CollectionChild("", null, deps = arrayOf("A"))
    val previous = CollectionChild("", null, deps = arrayOf("A"))

    assertThat(isChildEquivalent(previous, next)).isTrue
  }

  @Test
  fun `test children with unequal deps are not equivalent`() {
    val next = CollectionChild("", null, deps = arrayOf("A"))
    val previous = CollectionChild("", null, deps = arrayOf("B"))

    assertThat(isChildEquivalent(previous, next)).isFalse
  }

  @Test
  fun `test isChildEquivalent checks props`() {
    with(ComponentScope(lithoTestRule.context)) {
      assertThat(
          isChildEquivalent(
              CollectionChild("", Text("Test")),
              CollectionChild("", Text("Test")),
          ),
      )
          .isTrue

      assertThat(
          isChildEquivalent(
              CollectionChild("", Text("Test")),
              CollectionChild("", Text("Production")),
          ),
      )
          .isFalse
    }
  }

  @Test
  fun `test isChildEquivalent checks nested props`() {
    with(ComponentScope(lithoTestRule.context)) {
      assertThat(
          isChildEquivalent(
              CollectionChild("", Row { child(Text("Test")) }),
              CollectionChild("", Row { child(Text("Test")) }),
          ),
      )
          .isTrue

      assertThat(
          isChildEquivalent(
              CollectionChild("", Row { child(Text("Test")) }),
              CollectionChild("", Row { child(Text("Production")) }),
          ),
      )
          .isFalse
    }
  }

  @Test
  fun `test isChildEquivalent checks lambdas`() {
    with(ComponentScope(lithoTestRule.context)) {
      val onClick = { _: ClickEvent -> }
      assertThat(
          isChildEquivalent(
              CollectionChild("", Text("Test", style = Style.onClick(action = onClick))),
              CollectionChild("", Text("Test", style = Style.onClick(action = onClick))),
          ),
      )
          .isTrue

      assertThat(
          isChildEquivalent(
              CollectionChild("", Text("", style = Style.onClick {})),
              CollectionChild("", Text("", style = Style.onClick {})),
          ),
      )
          .isFalse
    }
  }

  @Test
  fun `test isChildEquivalent checks nested lambdas`() {
    with(ComponentScope(lithoTestRule.context)) {
      val onClick = { _: ClickEvent -> }
      assertThat(
          isChildEquivalent(
              CollectionChild(
                  "",
                  Row { child(Text("Test", style = Style.onClick(action = onClick))) },
              ),
              CollectionChild(
                  "",
                  Row { child(Text("Test", style = Style.onClick(action = onClick))) },
              ),
          ),
      )
          .isTrue

      assertThat(
          isChildEquivalent(
              CollectionChild("", Row { child(Text("Test", style = Style.onClick {})) }),
              CollectionChild("", Row { child(Text("Test", style = Style.onClick {})) }),
          ),
      )
          .isFalse
    }
  }

  @Test
  fun `test isChildEquivalent checks render info fields`() {
    with(ComponentScope(lithoTestRule.context)) {
      val base = CollectionChild("", Text("Test"))
      val withDeps = CollectionChild("", Text("Test"), deps = arrayOf("A"))
      val changes: Map<String, (CollectionChild) -> CollectionChild> = mapOf(
          "isSticky" to { it.copy(isSticky = true) },
          "isFullSpan" to { it.copy(isFullSpan = true) },
          "spanSize" to { it.copy(spanSize = 2) },
          "parentWidthPercent" to { it.copy(parentWidthPercent = 50f) },
          "parentHeightPercent" to { it.copy(parentHeightPercent = 50f) },
          "customAttributes" to { it.copy(customAttributes = mapOf("key" to "value")) },
      )

      SoftAssertions.assertSoftly { softly ->
        changes.forEach { (field, change) ->
          softly
              .assertThat(isChildEquivalent(base, change(base)))
              .describedAs("a change to only $field")
              .isFalse
          softly
              .assertThat(isChildEquivalent(withDeps, change(withDeps)))
              .describedAs("a change to only $field, with deps")
              .isFalse
        }
      }
      assertThat(
          isChildEquivalent(
              base.copy(customAttributes = mapOf("key" to "value")),
              CollectionChild("", Text("Test"), customAttributes = mapOf("key" to "value")),
          ),
      )
          .isTrue
    }
  }

  @Test
  fun `test isChildEquivalent checks common props`() {
    with(ComponentScope(lithoTestRule.context)) {
      assertThat(
          isChildEquivalent(
              CollectionChild("", Text("Test", style = Style.backgroundColor(Color.RED))),
              CollectionChild("", Text("Test", style = Style.backgroundColor(Color.RED))),
          ),
      )
          .isTrue

      assertThat(
          isChildEquivalent(
              CollectionChild("", Text("Test", style = Style.backgroundColor(Color.RED))),
              CollectionChild("", Text("Test", style = Style.backgroundColor(Color.BLUE))),
          ),
      )
          .isFalse
    }
  }

  @Test
  fun `test isChildEquivalent checks nested common props`() {
    with(ComponentScope(lithoTestRule.context)) {
      assertThat(
          isChildEquivalent(
              CollectionChild(
                  "",
                  Row { child(Text("Test", style = Style.backgroundColor(Color.RED))) },
              ),
              CollectionChild(
                  "",
                  Row { child(Text("Test", style = Style.backgroundColor(Color.RED))) },
              ),
          ),
      )
          .isTrue

      assertThat(
          isChildEquivalent(
              CollectionChild(
                  "",
                  Row { child(Text("Test", style = Style.backgroundColor(Color.RED))) },
              ),
              CollectionChild(
                  "",
                  Row { child(Text("Test", style = Style.backgroundColor(Color.BLUE))) },
              ),
          ),
      )
          .isFalse
    }
  }
}
