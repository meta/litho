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

import com.facebook.litho.Component
import com.facebook.litho.EmptyComponent
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.Text
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Tests for [Collection]'s children */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionIdTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private fun emptyComponent(): EmptyComponent = EmptyComponent()

  private fun textComponent(): Component = Text.create(lithoTestRule.context).text("Hello").build()

  private fun LazyCollectionChildren.getIds(): List<Any?> = collectionChildren.map { it.id }

  @Test
  fun `test generated ids are unique`() {
    val ids =
        LazyCollectionChildren()
            .apply {
              add(null)
              add(emptyComponent())
              add(id = null, component = emptyComponent())
              add(deps = arrayOf()) { emptyComponent() }
            }
            .getIds()

    assertThat(ids).hasSameSizeAs(ids.distinct())
  }

  @Test
  fun `test generated ids are stable across recreations`() {
    val ids1 =
        LazyCollectionChildren()
            .apply {
              add(null)
              add(emptyComponent())
              add(id = null, component = emptyComponent())
              add(deps = arrayOf()) { emptyComponent() }
            }
            .getIds()

    val ids2 =
        LazyCollectionChildren()
            .apply {
              add(null)
              add(emptyComponent())
              add(id = null, component = emptyComponent())
              add(deps = arrayOf()) { emptyComponent() }
            }
            .getIds()

    assertThat(ids1).hasSameElementsAs(ids2)
  }

  @Test
  fun `test generated ids for same component type are stable`() {
    val ids1 =
        LazyCollectionChildren()
            .apply {
              add(textComponent())
              add(textComponent())
            }
            .getIds()

    val ids2 =
        LazyCollectionChildren()
            .apply {
              add(textComponent())
              // Insert new content. Should not affect ids of existing children
              add(emptyComponent())
              add(id = 1, component = textComponent())
              // End new content.
              add(textComponent())
            }
            .getIds()

    assertThat(ids2.first()).isEqualTo(ids1.first())
    assertThat(ids2.last()).isEqualTo(ids1.last())
  }

  @Test
  fun `hasGeneratedIds is false when every child has an explicit id`() {
    val children =
        LazyCollectionChildren().apply {
          add(id = 1, component = textComponent())
          add(id = 2, deps = arrayOf()) { emptyComponent() }
        }

    assertThat(children.hasGeneratedIds).isFalse()
  }

  @Test
  fun `hasGeneratedIds is true when a component child has no id`() {
    val children =
        LazyCollectionChildren().apply {
          add(id = 1, component = textComponent())
          add(textComponent())
        }

    assertThat(children.hasGeneratedIds).isTrue()
  }

  @Test
  fun `hasGeneratedIds is true when a lambda child has no id`() {
    val children =
        LazyCollectionChildren().apply {
          add(id = 1, component = textComponent())
          add(deps = arrayOf()) { emptyComponent() }
        }

    assertThat(children.hasGeneratedIds).isTrue()
  }

  @Test
  fun `hasGeneratedIds ignores null components that are not added`() {
    val children =
        LazyCollectionChildren().apply {
          add(id = 1, component = textComponent())
          add(null)
        }

    assertThat(children.collectionChildren).hasSize(1)
    assertThat(children.hasGeneratedIds).isFalse()
  }
}
