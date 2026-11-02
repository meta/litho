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

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.rendercore.text.RCTextView
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Primitive Text passes its `clipToBounds` prop to the text view, which clips when it's set. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextPrimitiveClipToBoundsTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveText = false

  @Before
  fun setUp() {
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    ComponentsConfiguration.usePrimitiveText = true
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
  }

  @Test
  fun `text clips by default, like legacy Text`() {
    assertThat(clipsWhenDrawn(clipToBounds = null)).isTrue()
  }

  @Test
  fun `text does not clip with clipToBounds off`() {
    assertThat(clipsWhenDrawn(clipToBounds = false)).isFalse()
  }

  private fun clipsWhenDrawn(clipToBounds: Boolean?): Boolean {
    val lithoView =
        lithoTestRule
            .render(widthPx = 200, heightPx = 100) {
              Text.create(context)
                  .text(TEXT)
                  .apply { clipToBounds?.let { clipToBounds(it) } }
                  .build()
            }
            .lithoView
    val textView = checkNotNull(findRCTextView(lithoView))
    val canvas: Canvas = mock()
    textView.draw(canvas)
    return mockingDetails(canvas).invocations.any { it.method.name == "clipRect" }
  }

  private fun findRCTextView(view: View): RCTextView? {
    if (view is RCTextView) {
      return view
    }
    if (view !is ViewGroup) {
      return null
    }
    return (0 until view.childCount).firstNotNullOfOrNull { findRCTextView(view.getChildAt(it)) }
  }

  private companion object {
    const val TEXT = "Hello"
  }
}
