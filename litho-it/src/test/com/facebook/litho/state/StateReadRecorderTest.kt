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

import com.facebook.litho.HookGlobalKey
import com.facebook.litho.RenderScope
import com.facebook.litho.StringGlobalKey
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventBus
import com.facebook.rendercore.debug.DebugEventSubscriber
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Test

class StateReadRecorderTest {

  @Test
  fun canRecordStateReads() {
    val state1 = newState(1)
    val state2 = newState(2)
    val reads =
        StateReadRecorder.record(TREE_ID) {
          StateReadRecorder.read(state1)
          StateReadRecorder.read(state2)
        }
    assertThat(reads.asSet()).containsOnly(state1, state2)
  }

  @Test
  fun supportsReentrantStateRecordsFromSameTree() {
    val state1 = newState(1)
    val state2 = newState(2)
    val state3 = newState(3)
    val reads =
        StateReadRecorder.record(TREE_ID) {
          StateReadRecorder.read(state1)
          StateReadRecorder.record(TREE_ID) {
            StateReadRecorder.read(state2)
            StateReadRecorder.read(state3)
          }
        }
    assertThat(reads.asSet()).containsOnly(state1, state2, state3)
  }

  @Test
  fun supportsNestedStateRecordsFromDifferentTree() {
    val throwable = catchThrowable {
      StateReadRecorder.record(TREE_ID) { StateReadRecorder.record(TREE_ID + 1) {} }
    }
    assertThat(throwable).isNull()
  }

  @Test
  fun canRecordSequentiallyFromDifferentTrees() {
    val state1 = newState(1, treeId = 10)
    val state2 = newState(2, treeId = 20)
    val state3 = newState(3, treeId = 30)
    val read1 = StateReadRecorder.record(10) { StateReadRecorder.read(state1) }
    val read2 = StateReadRecorder.record(20) { StateReadRecorder.read(state2) }
    val read3 = StateReadRecorder.record(30) { StateReadRecorder.read(state3) }
    assertThat(read1.asSet()).containsOnly(state1)
    assertThat(read2.asSet()).containsOnly(state2)
    assertThat(read3.asSet()).containsOnly(state3)
  }

  @Test
  fun canReadStateWithoutRecorder() {
    val state1 = newState(1)
    assertThat(catchThrowable { StateReadRecorder.read(state1) }).isNull()
  }

  @Test
  fun untrackedOmitsReadsAndAllowsIndependentRecording() {
    for (preserveTreeContext in listOf(true, false)) {
      val before = newState(1)
      val sampled = newState(2)
      val inner = newState(3)
      val after = newState(4)
      val reads =
          StateReadRecorder.record(TREE_ID) {
            StateReadRecorder.read(before)
            val innerReads =
                StateReadRecorder.untracked(preserveTreeContext = preserveTreeContext) {
                  assertThat(StateReadRecorder.read(sampled)).isTrue()
                  val result = StateReadRecorder.record(TREE_ID) { StateReadRecorder.read(inner) }
                  StateReadRecorder.read(sampled)
                  result
                }
            assertThat(innerReads.asSet()).containsOnly(inner)
            StateReadRecorder.read(after)
          }
      assertThat(reads.asSet())
          .describedAs("preserveTreeContext=$preserveTreeContext")
          .containsOnly(before, after)
    }
  }

  @Test
  fun restoresContextAfterNestedUntrackedThrows() {
    for (preserveTreeContext in listOf(true, false)) {
      val before = newState(1)
      val sampled = newState(2)
      val after = newState(3)
      val failure = IllegalStateException("read failed")
      val reads =
          StateReadRecorder.record(TREE_ID) {
            StateReadRecorder.read(before)
            StateReadRecorder.untracked(preserveTreeContext = preserveTreeContext) {
              val throwable = catchThrowable {
                StateReadRecorder.untracked(preserveTreeContext = !preserveTreeContext) {
                  StateReadRecorder.record(TREE_ID + 1) { throw failure }
                }
              }
              assertThat(throwable).isSameAs(failure)
              assertThat(StateReadRecorder.read(sampled)).isTrue()
            }
            val throwable = catchThrowable {
              StateReadRecorder.untracked(preserveTreeContext = preserveTreeContext) {
                throw failure
              }
            }
            assertThat(throwable).isSameAs(failure)
            StateReadRecorder.read(after)
          }
      assertThat(reads.asSet())
          .describedAs("preserveTreeContext=$preserveTreeContext")
          .containsOnly(before, after)
    }
  }

  @Test
  fun untrackedCanPreserveCrossTreePolicy() {
    val currentConfig = ComponentsConfiguration.defaultInstance
    try {
      for (policy in ComponentsConfiguration.LogicViolationPolicy.entries) {
        ComponentsConfiguration.defaultInstance =
            currentConfig.copy(crossTreeStateReadPolicy = policy)
        val before = newState(1)
        val crossTree = newState(2, treeId = TREE_ID + 1)
        val after = newState(3)
        val reads =
            StateReadRecorder.record(TREE_ID) {
              StateReadRecorder.read(before)
              if (policy == ComponentsConfiguration.LogicViolationPolicy.CRASH) {
                assertThatThrownBy {
                      StateReadRecorder.untracked(preserveTreeContext = true) {
                        StateReadRecorder.read(crossTree)
                      }
                    }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Reader tree: $TREE_ID")
              } else {
                assertThat(
                    StateReadRecorder.untracked(preserveTreeContext = true) {
                      StateReadRecorder.read(crossTree)
                    },
                )
                    .isFalse()
              }
              StateReadRecorder.read(after)
            }
        assertThat(reads.asSet()).describedAs("policy=$policy").containsOnly(before, after)
      }
    } finally {
      ComponentsConfiguration.defaultInstance = currentConfig
    }
  }

  @Test
  fun explicitlyClearingTreeContextAllowsIndependentWorkAndRestoresIt() {
    val currentConfig = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        currentConfig.copy(
            crossTreeStateReadPolicy = ComponentsConfiguration.LogicViolationPolicy.CRASH,
        )
    try {
      val before = newState(1)
      val sampled = newState(2)
      val otherTree = newState(3, treeId = TREE_ID + 1)
      val after = newState(4)
      val reads =
          StateReadRecorder.record(TREE_ID) {
            StateReadRecorder.read(before)
            StateReadRecorder.untracked(preserveTreeContext = false) {
              assertThat(StateReadRecorder.read(otherTree)).isTrue()
              StateReadRecorder.untracked(preserveTreeContext = true) {
                assertThat(StateReadRecorder.read(otherTree)).isTrue()
              }
              val innerReads =
                  StateReadRecorder.record(TREE_ID + 1) { StateReadRecorder.read(otherTree) }
              assertThat(innerReads.asSet()).containsOnly(otherTree)
              assertThat(StateReadRecorder.read(sampled)).isTrue()
            }
            StateReadRecorder.untracked(preserveTreeContext = true) {
              assertThatThrownBy { StateReadRecorder.read(otherTree) }
                  .isInstanceOf(IllegalStateException::class.java)
              StateReadRecorder.untracked(preserveTreeContext = false) {
                assertThat(StateReadRecorder.read(otherTree)).isTrue()
              }
              assertThatThrownBy { StateReadRecorder.read(otherTree) }
                  .isInstanceOf(IllegalStateException::class.java)
              StateReadRecorder.read(sampled)
            }
            assertThatThrownBy { StateReadRecorder.read(otherTree) }
                .isInstanceOf(IllegalStateException::class.java)
            StateReadRecorder.read(after)
          }
      assertThat(reads.asSet()).containsOnly(before, after)
    } finally {
      ComponentsConfiguration.defaultInstance = currentConfig
    }
  }

  @Test
  fun throwsWhenReadStateFromDifferentTree() {
    val currentConfig = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        currentConfig.copy(
            crossTreeStateReadPolicy = ComponentsConfiguration.LogicViolationPolicy.CRASH,
        )

    val state1 = newState(1, treeId = 10)
    assertThatThrownBy { StateReadRecorder.record(TREE_ID) { StateReadRecorder.read(state1) } }
        .isInstanceOf(IllegalStateException::class.java)

    ComponentsConfiguration.defaultInstance = currentConfig
  }

  @Test
  fun doesNotRecordCrossTreeReadUnderLogPolicy() {
    val currentConfig = ComponentsConfiguration.defaultInstance
    val descriptions = mutableListOf<Any?>()
    val subscriber =
        object : DebugEventSubscriber(LithoDebugEvent.DebugInfo) {
          override fun onEvent(event: DebugEvent) {
            if (
                event.attributes[DebugEventAttribute.Name] ==
                    "StateReadTracking:ReadFromDifferentTree"
            ) {
              descriptions += event.attributes["reader.description"]
            }
          }
        }
    ComponentsConfiguration.defaultInstance =
        currentConfig.copy(
            crossTreeStateReadPolicy = ComponentsConfiguration.LogicViolationPolicy.LOG,
        )
    DebugEventBus.subscribe(subscriber)
    try {
      val sameTreeState = newState(1, treeId = TREE_ID)
      val crossTreeState = newState(2, treeId = TREE_ID + 1)

      var sameTreeReadResult = false
      var crossTreeReadResult = true
      val reads =
          StateReadRecorder.record(TREE_ID, description = "layout:MyComponent") {
            sameTreeReadResult = StateReadRecorder.read(sameTreeState)
            crossTreeReadResult = StateReadRecorder.read(crossTreeState)
            StateReadRecorder.untracked(preserveTreeContext = true) {
              assertThat(StateReadRecorder.read(crossTreeState)).isFalse()
            }
          }

      assertThat(sameTreeReadResult).isTrue()
      assertThat(crossTreeReadResult).isFalse()
      assertThat(reads.asSet()).containsOnly(sameTreeState)
      assertThat(descriptions).containsExactly("layout:MyComponent", "layout:MyComponent")
    } finally {
      DebugEventBus.unsubscribe(subscriber)
      ComponentsConfiguration.defaultInstance = currentConfig
    }
  }

  @Test
  fun doesNotRecordCrossTreeReadUnderNonePolicy() {
    val currentConfig = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        currentConfig.copy(
            crossTreeStateReadPolicy = ComponentsConfiguration.LogicViolationPolicy.NONE,
        )
    try {
      val crossTreeState = newState(1, treeId = TREE_ID + 1)

      var crossTreeReadResult = true
      val reads =
          StateReadRecorder.record(TREE_ID) {
            crossTreeReadResult = StateReadRecorder.read(crossTreeState)
          }

      assertThat(crossTreeReadResult).isFalse()
      assertThat(reads.asSet()).isEmpty()
    } finally {
      ComponentsConfiguration.defaultInstance = currentConfig
    }
  }

  private fun newState(index: Int, treeId: Int = TREE_ID): StateId {
    return StateId(treeId, HookGlobalKey(StringGlobalKey("globalKey"), index, RenderScope.Resolve))
  }

  companion object {
    private const val TREE_ID = 1
  }
}
