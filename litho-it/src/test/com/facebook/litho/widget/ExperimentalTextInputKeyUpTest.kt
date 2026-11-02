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

package com.facebook.litho.widget

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.kotlin.widget.TextInput
import com.facebook.litho.testing.LithoTestRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Primitive TextInput behaviour that intentionally differs from legacy TextInput. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ExperimentalTextInputKeyUpTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveTextInput = false

  @Before
  fun setUp() {
    previousUsePrimitiveTextInput = ComponentsConfiguration.usePrimitiveTextInput
    ComponentsConfiguration.usePrimitiveTextInput = true
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveTextInput = previousUsePrimitiveTextInput
  }

  @Test
  fun `hardware Enter runs the editor action unless a KeyUp handler consumes it`() {
    assertThat(editorActionsOnEnter(onKeyUp = null)).isEqualTo(1)
    assertThat(editorActionsOnEnter(onKeyUp = { false })).isEqualTo(1)
    assertThat(editorActionsOnEnter(onKeyUp = { true })).isEqualTo(0)
  }

  private fun editorActionsOnEnter(onKeyUp: ((KeyUpEvent) -> Boolean)?): Int {
    var editorActions = 0
    val component =
        object : KComponent() {
          override fun ComponentScope.render(): Component = TextInput(
              initialText = "",
              onKeyUp = onKeyUp,
              onEditorAction = {
                editorActions++
                true
              },
          )
        }
    val editText = checkNotNull(
        findEditText(
            lithoTestRule.render(widthPx = 500, heightPx = 200) { component }.lithoView,
        ),
    )

    // TextView also runs the editor action on key down; count only what the key up does.
    editText.onKeyDown(
        KeyEvent.KEYCODE_ENTER,
        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
    )
    val afterKeyDown = editorActions
    editText.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    return editorActions - afterKeyDown
  }

  private fun findEditText(view: View): EditText? {
    if (view is EditText) {
      return view
    }
    if (view !is ViewGroup) {
      return null
    }
    return (0 until view.childCount).firstNotNullOfOrNull { findEditText(view.getChildAt(it)) }
  }
}
