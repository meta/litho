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

import androidx.recyclerview.widget.DiffUtil
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionDiffTest {

  val testCases = listOf(
      // Empty Lists
      Pair(emptyList(), emptyList()),

      // Identical Lists
      Pair(listOf(Item(1, "a"), Item(2, "b")), listOf(Item(1, "a"), Item(2, "b"))),

      // Completely Different Lists
      Pair(listOf(Item(1, "a"), Item(2, "b")), listOf(Item(3, "c"), Item(4, "d"))),

      // Single Item Change
      Pair(listOf(Item(1, "a")), listOf(Item(1, "b"))),

      // Multiple Item Changes
      Pair(listOf(Item(1, "a"), Item(2, "b")), listOf(Item(1, "c"), Item(2, "d"))),

      // Reordered Items
      Pair(listOf(Item(1, "a"), Item(2, "b")), listOf(Item(2, "b"), Item(1, "a"))),

      // Single Item Insertion
      Pair(listOf(Item(1, "a")), listOf(Item(1, "a"), Item(2, "b"))),

      // Multiple Items Insertions
      Pair(listOf(Item(1, "a")), listOf(Item(1, "a"), Item(2, "b"), Item(3, "c"))),

      // Single Item Deletion
      Pair(listOf(Item(1, "a"), Item(2, "b")), listOf(Item(1, "a"))),

      // Multiple Items Deletions
      Pair(listOf(Item(1, "a"), Item(2, "b"), Item(3, "c")), listOf(Item(1, "a"))),

      // Mixed Operations
      Pair(listOf(Item(1, "a"), Item(2, "b")), listOf(Item(1, "c"), Item(3, "d"))),
  )

  private fun calculateDiff(oldData: List<Item>, newData: List<Item>): List<CollectionOperation> {
    val itemComparator = { a: Item, b: Item -> a.id == b.id }
    val contentComparator = { a: Item, b: Item -> a.content == b.content }
    val diffCallback = CollectionDiffCallback(oldData, newData, itemComparator, contentComparator)
    val listUpdateCallback = CollectionUpdateOperation(oldData, newData)
    DiffUtil.calculateDiff(diffCallback).dispatchUpdatesTo(listUpdateCallback)
    return listUpdateCallback.operations
  }

  @Test
  fun `test two empty lists end up no change`() {
    val operations = calculateDiff(testCases[0].first, testCases[0].second)
    assertThat(operations).isEmpty()
  }

  @Test
  fun `test two identical lists end up no change`() {
    val operations = calculateDiff(testCases[1].first, testCases[1].second)
    assertThat(operations).isEmpty()
  }

  @Test
  fun `test two different lists end up removing and inserting`() {
    val operations = calculateDiff(testCases[2].first, testCases[2].second)

    assertThat(operations.size).isEqualTo(2)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.DELETE)
      assertThat(operation.index).isEqualTo(0)
      assertThat(operation.count).isEqualTo(2)
    }
    operations[1].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.INSERT)
      assertThat(operation.index).isEqualTo(0)
      assertThat(operation.count).isEqualTo(2)
    }
  }

  @Test
  fun `test single item change ends up updating`() {
    val operations = calculateDiff(testCases[3].first, testCases[3].second)

    assertThat(operations.size).isEqualTo(1)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.UPDATE)
      assertThat(operation.index).isEqualTo(0)
    }
  }

  @Test
  fun `test multiple item changes end up updating range`() {
    val operations = calculateDiff(testCases[4].first, testCases[4].second)

    assertThat(operations.size).isEqualTo(1)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.UPDATE)
      assertThat(operation.index).isEqualTo(0)
      assertThat(operation.count).isEqualTo(2)
    }
  }

  @Test
  fun `test reordered items end up moving`() {
    val operations = calculateDiff(testCases[5].first, testCases[5].second)

    assertThat(operations.size).isEqualTo(1)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.MOVE)
      assertThat(operation.index).isEqualTo(0)
      assertThat(operation.toIndex).isEqualTo(1)
    }
  }

  @Test
  fun `test single item insertion end up inserting an item`() {
    val operations = calculateDiff(testCases[6].first, testCases[6].second)

    assertThat(operations.size).isEqualTo(1)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.INSERT)
      assertThat(operation.index).isEqualTo(1)
      assertThat(operation.count).isEqualTo(1)
    }
  }

  @Test
  fun `test multiple items insertion end up inserting a range`() {
    val operations = calculateDiff(testCases[7].first, testCases[7].second)

    assertThat(operations.size).isEqualTo(1)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.INSERT)
      assertThat(operation.index).isEqualTo(1)
      assertThat(operation.count).isEqualTo(2)
    }
  }

  @Test
  fun `test single item deletion end up removing an item`() {
    val operations = calculateDiff(testCases[8].first, testCases[8].second)

    assertThat(operations.size).isEqualTo(1)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.DELETE)
      assertThat(operation.index).isEqualTo(1)
      assertThat(operation.count).isEqualTo(1)
    }
  }

  @Test
  fun `test multiple items deletion end up removing a range`() {
    val operations = calculateDiff(testCases[9].first, testCases[9].second)

    assertThat(operations.size).isEqualTo(1)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.DELETE)
      assertThat(operation.index).isEqualTo(1)
      assertThat(operation.count).isEqualTo(2)
    }
  }

  @Test
  fun `test mixed operations end up removing, inserting, and updating`() {
    val operations = calculateDiff(testCases[10].first, testCases[10].second)

    assertThat(operations.size).isEqualTo(3)
    operations[0].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.DELETE)
      assertThat(operation.index).isEqualTo(1)
      assertThat(operation.count).isEqualTo(1)
    }
    operations[1].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.INSERT)
      assertThat(operation.index).isEqualTo(1)
      assertThat(operation.count).isEqualTo(1)
    }
    operations[2].let { operation ->
      assertThat(operation.type).isEqualTo(CollectionOperation.Type.UPDATE)
      assertThat(operation.index).isEqualTo(0)
      assertThat(operation.count).isEqualTo(1)
    }
  }

  class Item(val id: Int, val content: String)
}
