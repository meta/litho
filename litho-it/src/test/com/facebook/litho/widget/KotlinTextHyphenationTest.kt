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

import android.text.Layout
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests that the Kotlin [Text] builder forwards `hyphenationFrequency` to the component. */
@RunWith(LithoTestRunner::class)
class KotlinTextHyphenationTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `forwards hyphenationFrequency`() {
    var text: Text? = null
    lithoTestRule.render {
      Text("Monochrome", hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_FULL).also {
        text = it
      }
    }

    assertThat(text?.hyphenationFrequency).isEqualTo(Layout.HYPHENATION_FREQUENCY_FULL)
  }

  @Test
  fun `explicit default matches omitted hyphenationFrequency`() {
    var explicitDefault: Text? = null
    var omitted: Text? = null
    lithoTestRule.render {
      Text(
          "Monochrome",
          hyphenationFrequency = TextStylesHelper.DEFAULT_HYPHENATION_FREQUENCY,
      )
          .also { explicitDefault = it }
    }
    lithoTestRule.render { Text("Monochrome").also { omitted = it } }

    assertThat(explicitDefault?.hyphenationFrequency)
        .isEqualTo(TextStylesHelper.DEFAULT_HYPHENATION_FREQUENCY)
    assertThat(explicitDefault?.hyphenationFrequency).isEqualTo(omitted?.hyphenationFrequency)
  }

  @Test
  fun `keeps the default hyphenationFrequency when it is not set`() {
    var text: Text? = null
    lithoTestRule.render { Text("Monochrome").also { text = it } }

    assertThat(text?.hyphenationFrequency).isEqualTo(TextStylesHelper.DEFAULT_HYPHENATION_FREQUENCY)
  }
}
