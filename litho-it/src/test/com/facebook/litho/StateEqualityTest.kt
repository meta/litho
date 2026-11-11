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

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/** Unit tests for equals of [State]. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class StateEqualityTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `same state with same value is equal`() {
    val stateBox = AtomicReference<State<Int>>()

    val testView = lithoTestRule.render {
      StateBoxComponent(initialState1 = 1, initialState2 = 1, stateBox1 = stateBox)
    }

    val firstState = stateBox.get()
    stateBox.set(null)

    // initial states changing just to bust cache
    testView.setRoot(StateBoxComponent(initialState1 = 2, initialState2 = 2, stateBox1 = stateBox))
    val secondState = stateBox.get()

    assertThat(firstState).isEqualTo(secondState)
  }

  @Test
  fun `different state with same value is not equal`() {
    val stateBox1 = AtomicReference<State<Int>>()
    val stateBox2 = AtomicReference<State<Int>>()

    lithoTestRule.render {
      StateBoxComponent(
          initialState1 = 1,
          initialState2 = 1,
          stateBox1 = stateBox1,
          stateBox2 = stateBox2,
      )
    }

    assertThat(stateBox1.get()).isNotEqualTo(stateBox2.get())
  }

  @Test
  fun `same state with different keys is not equal`() {
    val stateBox1 = AtomicReference<State<Int>>()
    val stateBox2 = AtomicReference<State<Int>>()
    lithoTestRule.render {
      Column {
        child(StateBoxComponent(initialState1 = 1, initialState2 = 1, stateBox1 = stateBox1))
        child(StateBoxComponent(initialState1 = 1, initialState2 = 1, stateBox1 = stateBox2))
      }
    }

    assertThat(stateBox1.get()).isNotEqualTo(stateBox2.get())
  }

  @DataClassGenerate private data class DataClassDemo(val i: Int)

  private class StateBoxComponent<T>(
      private val initialState1: T,
      private val initialState2: T,
      private val stateBox1: AtomicReference<State<T>>? = null,
      private val stateBox2: AtomicReference<State<T>>? = null,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val state1 = useState { initialState1 }
      val state2 = useState { initialState2 }

      stateBox1?.set(state1)
      stateBox2?.set(state2)

      return Column()
    }
  }
}
