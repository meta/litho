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

package com.facebook.primitive.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.facebook.primitive.canvas.model.CanvasFill
import com.facebook.primitive.canvas.model.CanvasModel
import com.facebook.primitive.canvas.model.CanvasPath
import com.facebook.primitive.canvas.model.CanvasPathRect
import com.facebook.primitive.canvas.model.CanvasSolidColorShading
import com.facebook.primitive.canvas.model.CanvasStroke
import com.facebook.primitive.utils.types.DEFAULT_BLENDING_MODE
import com.facebook.primitive.utils.types.FillRule
import com.facebook.primitive.utils.types.LineCap
import com.facebook.primitive.utils.types.LineJoin
import com.facebook.rendercore.geometry.Point
import com.facebook.rendercore.geometry.Size
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasPathRectTest {

  @Test
  fun `the vertical corner radius defaults to the horizontal one`() {
    assertThat(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 4f))
        .isEqualTo(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 4f, 4f))
  }

  @Test
  fun `a different vertical corner radius draws elliptical corners`() {
    // (3, 1) is inside a 10 x 2 elliptical corner and outside a circular corner of radius 10.
    val elliptical = draw(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 10f, 2f))
    val circular = draw(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 10f))

    assertThat(elliptical.getPixel(3, 1)).isEqualTo(Color.RED)
    assertThat(circular.getPixel(3, 1)).isEqualTo(Color.TRANSPARENT)
  }

  @Test
  fun `rects differing only in the vertical corner radius are cached as different paths`() {
    val state = CanvasState()
    val elliptical = path(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 10f, 2f))
    val circular = path(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 10f))

    assertThat(elliptical).isNotEqualTo(circular)
    assertThat(state.getOrCreatePath(elliptical)).isNotSameAs(state.getOrCreatePath(circular))
  }

  @Test
  fun `radii larger than the rect are scaled down together keeping their ratio`() {
    // As Path.addRoundRect does: 100 is 10x half the height, so both radii are divided by 10.
    // Callers that need SVG's per-axis clamp (rx <= width / 2, ry <= height / 2) clamp first.
    val clamped = draw(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 10f, 100f))
    val scaled = draw(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 1f, 10f))

    assertThat(clamped.sameAs(scaled)).isTrue()
    // A 1 px wide corner: only the corner pixel is partly covered.
    assertThat(Color.alpha(clamped.getPixel(0, 0))).isLessThan(0xFF)
    assertThat(clamped.getPixel(2, 2)).isEqualTo(Color.RED)
  }

  @Test
  fun `a zero horizontal corner radius draws square corners`() {
    val bitmap = draw(CanvasPathRect(Point(0f, 0f), Size(40f, 20f), 0f, 8f))

    assertThat(bitmap.getPixel(0, 0)).isEqualTo(Color.RED)
  }

  @Test
  fun `elliptical corners match the reference image`() {
    // Columns: (rx, ry) = (20, 5), (5, 20), (20, 20), (10, 100) scaled down, (0, 10). Rows: filled
    // red, stroked blue 3 px wide.
    val radii = listOf(20f to 5f, 5f to 20f, 20f to 20f, 10f to 100f, 0f to 10f)
    val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val children = radii.flatMapIndexed { i, (rx, ry) ->
      listOf(
          CanvasFill(
              shape = path(CanvasPathRect(Point(60f * i + 5f, 5f), Size(50f, 40f), rx, ry)),
              shading = CanvasSolidColorShading(Color.RED),
              blendingMode = DEFAULT_BLENDING_MODE,
              shadow = null,
          ),
          CanvasStroke(
              shape = path(CanvasPathRect(Point(60f * i + 5f, 55f), Size(50f, 40f), rx, ry)),
              shading = CanvasSolidColorShading(Color.BLUE),
              blendingMode = DEFAULT_BLENDING_MODE,
              shadow = null,
              lineWidth = 3f,
              lineCap = LineCap.Butt,
              lineJoin = LineJoin.Miter,
              miterLimit = 4f,
              dashLengths = null,
              dashPhase = 0f,
          ),
      )
    }
    CanvasModel(CanvasState(), children).draw(canvas)

    // The first column: (6, 6) is outside the 20 x 5 corner, the centre is filled; the stroke runs
    // along the top edge and leaves the inside empty.
    assertThat(bitmap.getPixel(6, 6)).isEqualTo(Color.WHITE)
    assertThat(bitmap.getPixel(30, 25)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(30, 55)).isEqualTo(Color.BLUE)
    assertThat(bitmap.getPixel(30, 75)).isEqualTo(Color.WHITE)
    CanvasReferenceImages.assertMatchesReference("canvas_path_rect_elliptical_corners", bitmap)
  }

  private fun path(rect: CanvasPathRect) =
      CanvasPath(fillRule = FillRule.NonZero, children = listOf(rect))

  private fun draw(rect: CanvasPathRect): Bitmap {
    val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
    CanvasModel(
        CanvasState(),
        listOf(
            CanvasFill(
                shape = CanvasPath(fillRule = FillRule.NonZero, children = listOf(rect)),
                shading = CanvasSolidColorShading(Color.RED),
                blendingMode = DEFAULT_BLENDING_MODE,
                shadow = null,
            ),
        ),
    )
        .draw(Canvas(bitmap))
    return bitmap
  }
}
