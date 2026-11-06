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

package com.facebook.samples.litho.documentation.customlayout

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.facebook.litho.Component
import com.facebook.litho.SizeSpec
import com.facebook.litho.Style
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.layout.LayoutDirection
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.yoga.YogaDirection
import java.io.File
import kotlin.math.roundToInt
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each custom layout example at a narrow (180 dp) or wide (296 dp) width, left to right or
 * right to left, on white with an 8 dp margin, and compares it pixel by pixel with its golden PNG
 * in `sample/src/test/resources/customlayout/screenshots/`. The Custom Layout guide shows these
 * PNGs, so a sample whose rendering changes fails here until its goldens are recorded again.
 *
 * To record, set the system property `litho.customLayout.recordScreenshots` to the absolute path of
 * that directory in your checkout, for example
 * `-Dlitho.customLayout.recordScreenshots=/path/to/litho/sample/src/test/resources/customlayout/screenshots`:
 * each test then writes its PNG there instead of comparing. It takes a path, not a flag, because a
 * test can't find the source tree from the directory it runs in.
 *
 * It runs only under Buck, where pixel equality is proven, and is skipped under Gradle: Buck's test
 * rule sets `com.facebook.litho.is_oss` to `false`, and Gradle doesn't.
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "xhdpi")
class CustomLayoutScreenshotTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `action bar, wide`() = assertMatchesGolden(
      "custom-layout-action-bar-wide.png",
      296,
      LayoutDirection.LTR,
      ActionBarLayoutExample(),
  )

  @Test
  fun `action bar, narrow`() = assertMatchesGolden(
      "custom-layout-action-bar-narrow.png",
      180,
      LayoutDirection.LTR,
      ActionBarLayoutExample(),
  )

  @Test
  fun `facepile, wide`() = assertMatchesGolden(
      "custom-layout-facepile-wide.png",
      296,
      LayoutDirection.LTR,
      FacepileLayoutExample(),
  )

  @Test
  fun `facepile, narrow`() = assertMatchesGolden(
      "custom-layout-facepile-narrow.png",
      180,
      LayoutDirection.LTR,
      FacepileLayoutExample(),
  )

  @Test
  fun `facepile, narrow, right to left`() = assertMatchesGolden(
      "custom-layout-facepile-narrow-rtl.png",
      180,
      LayoutDirection.RTL,
      FacepileLayoutExample(),
  )

  @Test
  fun `aligned column, left to right`() = assertMatchesGolden(
      "custom-layout-aligned-column-ltr.png",
      296,
      LayoutDirection.LTR,
      AlignedColumnLayoutExample(),
  )

  @Test
  fun `aligned column, right to left`() = assertMatchesGolden(
      "custom-layout-aligned-column-rtl.png",
      296,
      LayoutDirection.RTL,
      AlignedColumnLayoutExample(),
  )

  @Test
  fun `flow row, narrow`() = assertMatchesGolden(
      "custom-layout-flow-row-narrow.png",
      180,
      LayoutDirection.LTR,
      FlowRowLayoutExample(),
  )

  @Test
  fun `flow row, wide`() = assertMatchesGolden(
      "custom-layout-flow-row-wide.png",
      296,
      LayoutDirection.LTR,
      FlowRowLayoutExample(),
  )

  @Test
  fun `equal split, narrow`() = assertMatchesGolden(
      "custom-layout-equal-split-narrow.png",
      180,
      LayoutDirection.LTR,
      EqualSplitLayoutExample(),
  )

  @Test
  fun `equal split, wide`() = assertMatchesGolden(
      "custom-layout-equal-split-wide.png",
      296,
      LayoutDirection.LTR,
      EqualSplitLayoutExample(),
  )

  @Test
  fun `badge overlay, wide`() = assertMatchesGolden(
      "custom-layout-badge-overlay.png",
      296,
      LayoutDirection.LTR,
      BadgeOverlayLayoutExample(),
  )

  /**
   * Renders [sample] exactly [widthDp] wide in [direction], on white with an 8 dp margin, and
   * compares it with the golden [fileName], or records it there in record mode.
   */
  private fun assertMatchesGolden(
      fileName: String,
      widthDp: Int,
      direction: LayoutDirection,
      sample: Component,
  ) {
    assumeTrue(
        "Screenshots are compared only under Buck",
        System.getProperty("com.facebook.litho.is_oss") == "false",
    )
    val density = lithoTestRule.context.resources.displayMetrics.density
    val testView =
        lithoTestRule.render(
            widthSpec = SizeSpec.makeSizeSpec((widthDp * density).roundToInt(), SizeSpec.EXACTLY),
            heightSpec = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
        ) {
          Column(
              style =
                  Style.layoutDirection(
                      if (direction.isRTL) YogaDirection.RTL else YogaDirection.LTR,
                  ),
          ) {
            child(sample)
          }
        }
    val view = testView.lithoView
    val marginPx = (8 * density).roundToInt()
    val actual =
        Bitmap.createBitmap(
            view.width + 2 * marginPx,
            view.height + 2 * marginPx,
            Bitmap.Config.ARGB_8888,
        )
    Canvas(actual).apply {
      drawColor(Color.WHITE)
      translate(marginPx.toFloat(), marginPx.toFloat())
      view.draw(this)
    }

    val recordDirectory = System.getProperty("litho.customLayout.recordScreenshots")
    if (recordDirectory != null) {
      File(recordDirectory, fileName).outputStream().use { output ->
        actual.compress(Bitmap.CompressFormat.PNG, 100, output)
      }
      return
    }

    val golden =
        checkNotNull(javaClass.getResourceAsStream("/customlayout/screenshots/$fileName")) {
              "No golden for $fileName: record it with -Dlitho.customLayout.recordScreenshots"
            }
            .use { input -> BitmapFactory.decodeStream(input) }
    assertThat(actual.width to actual.height)
        .describedAs("$fileName size")
        .isEqualTo(golden.width to golden.height)
    val actualPixels = IntArray(actual.width * actual.height)
    val goldenPixels = IntArray(actualPixels.size)
    actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
    golden.getPixels(goldenPixels, 0, golden.width, 0, 0, golden.width, golden.height)
    val differing = actualPixels.indices.count { i -> actualPixels[i] != goldenPixels[i] }
    assertThat(differing)
        .describedAs(
            "%s: %d of %d pixels (%.2f%%) differ from the golden",
            fileName,
            differing,
            actualPixels.size,
            100.0 * differing / actualPixels.size,
        )
        .isZero()
  }
}
