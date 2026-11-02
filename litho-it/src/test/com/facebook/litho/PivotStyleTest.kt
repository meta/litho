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

import android.annotation.TargetApi
import android.os.Build
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.pivotPercent
import com.facebook.litho.view.viewTag
import com.facebook.litho.view.wrapInView
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PivotStyleTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Test
  fun testPivotApplied() {
    val testLithoView = lithoTestRule.render {
      ComponentWithOptionalPivot(pivotXPercent = 75f, pivotYPercent = 75f)
    }

    with(testLithoView.findViewWithTag("test_view")) {
      assertThat(pivotX).isEqualTo(75f)
      assertThat(pivotY).isEqualTo(75f)
    }

    testLithoView.setRoot(
        ComponentWithOptionalPivot(
            pivotXPercent = 75f,
            pivotYPercent = 75f,
            width = 300.px,
            height = 200.px,
        ),
    )

    with(testLithoView.findViewWithTag("test_view")) {
      assertThat(pivotX).isEqualTo(225f)
      assertThat(pivotY).isEqualTo(150f)
    }
  }

  @TargetApi(Build.VERSION_CODES.P)
  @Test
  fun testPivotReset() {
    val testLithoView = lithoTestRule.render {
      ComponentWithOptionalPivot(pivotXPercent = 75f, pivotYPercent = 75f)
    }

    with(testLithoView.findViewWithTag("test_view")) {
      assertThat(pivotX).isEqualTo(75f)
      assertThat(pivotY).isEqualTo(75f)
      assertThat(isPivotSet).isEqualTo(true)
    }

    testLithoView.setRoot(ComponentWithOptionalPivot(hasPivot = false))

    with(testLithoView.findViewWithTag("test_view")) {
      // 0f indicates unset
      assertThat(pivotX).isEqualTo(0f)
      assertThat(pivotY).isEqualTo(0f)
      assertThat(isPivotSet).isEqualTo(false)
    }

    testLithoView.setRoot(
        ComponentWithOptionalPivot(hasPivot = false, width = 300.px, height = 200.px),
    )

    with(testLithoView.findViewWithTag("test_view")) { ->
      assertThat(pivotX).isEqualTo(0f)
      assertThat(pivotY).isEqualTo(0f)
      assertThat(isPivotSet).isEqualTo(false)
    }
  }

  @Config(sdk = [Build.VERSION_CODES.O_MR1])
  @Test
  fun testPivotResetPreAPI28() {
    val testLithoView = lithoTestRule.render {
      ComponentWithOptionalPivot(pivotXPercent = 75f, pivotYPercent = 75f)
    }

    with(testLithoView.findViewWithTag("test_view")) {
      assertThat(pivotX).isEqualTo(75f)
      assertThat(pivotY).isEqualTo(75f)
    }

    testLithoView.setRoot(ComponentWithOptionalPivot(hasPivot = false))

    with(testLithoView.findViewWithTag("test_view")) {
      assertThat(pivotX).isEqualTo(50f)
      assertThat(pivotY).isEqualTo(50f)
    }

    testLithoView.setRoot(
        ComponentWithOptionalPivot(hasPivot = false, width = 300.px, height = 200.px),
    )

    with(testLithoView.findViewWithTag("test_view")) { ->
      assertThat(pivotX).isEqualTo(150f)
      assertThat(pivotY).isEqualTo(100f)
    }
  }

  private class ComponentWithOptionalPivot(
      private val pivotXPercent: Float = 50f,
      private val pivotYPercent: Float = 50f,
      private val width: Dimen = 100.px,
      private val height: Dimen = 100.px,
      private val hasPivot: Boolean = true,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val style =
          Style.width(width).height(height).viewTag("test_view") +
              if (hasPivot) {
                Style.pivotPercent(pivotXPercent, pivotYPercent)
              } else {
                null
              }

      return Row(style = Style.wrapInView().width(1000.px).height(1000.px)) {
        child(Row(style = style) { child(Text("test")) })
      }
    }
  }
}
