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

import android.widget.EditText
import com.facebook.litho.Handle
import com.facebook.litho.config.ComponentsConfiguration
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

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextInputPrimitiveOptOutTest {

  @get:Rule val lithoTestRule = LithoTestRule()
  private var savedUsePrimitiveTextInput = false

  @Before
  fun setUp() {
    savedUsePrimitiveTextInput = ComponentsConfiguration.usePrimitiveTextInput
    ComponentsConfiguration.usePrimitiveTextInput = true
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveTextInput = savedUsePrimitiveTextInput
  }

  @Test
  fun `opting out mounts the legacy input and routes text triggers to it`() {
    val handle = Handle()
    val view = lithoTestRule.render {
      TextInput.create(context).allowPrimitive(false).handle(handle).build()
    }
    val editText = view.lithoView.getChildAt(0) as EditText
    assertThat(editText)
        .isExactlyInstanceOf(TextInputComponentSpec.EditTextWithEventHandlers::class.java)

    TextInput.setText(lithoTestRule.context, handle, "draft")

    assertThat(editText.text.toString()).isEqualTo("draft")
    assertThat(TextInput.getText(lithoTestRule.context, handle)?.toString()).isEqualTo("draft")
  }

  @Test
  fun `the default mounts the primitive input and keeps triggers on that implementation`() {
    val handle = Handle()
    val view = lithoTestRule.render {
      TextInput.create(context).handle(handle).build()
    }
    val editText = view.lithoView.getChildAt(0) as EditText
    assertThat(editText).isExactlyInstanceOf(EditTextWithEventHandlers::class.java)

    ComponentsConfiguration.usePrimitiveTextInput = false
    TextInput.setText(lithoTestRule.context, handle, "draft")

    assertThat(editText.text.toString()).isEqualTo("draft")
    assertThat(TextInput.getText(lithoTestRule.context, handle)?.toString()).isEqualTo("draft")
  }
}
