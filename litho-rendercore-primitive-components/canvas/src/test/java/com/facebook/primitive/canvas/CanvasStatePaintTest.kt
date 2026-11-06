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
import android.graphics.Shader
import com.facebook.primitive.canvas.model.CanvasFill
import com.facebook.primitive.canvas.model.CanvasGradient
import com.facebook.primitive.canvas.model.CanvasGradientShading
import com.facebook.primitive.canvas.model.CanvasLinearGradient
import com.facebook.primitive.canvas.model.CanvasNodeModel
import com.facebook.primitive.canvas.model.CanvasPath
import com.facebook.primitive.canvas.model.CanvasPathRect
import com.facebook.primitive.canvas.model.CanvasShadingModel
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

/** The fill and stroke paints are shared by every node drawn with one [CanvasState]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasStatePaintTest {

  private val halfRed = CanvasSolidColorShading(0x80FF0000.toInt())
  private val opaqueGradient = CanvasGradientShading(
      CanvasLinearGradient(
          CanvasGradient(intArrayOf(Color.BLUE, Color.BLUE), floatArrayOf(0f, 1f)),
          Point(0f, 0f),
          Point(10f, 0f),
          Shader.TileMode.CLAMP,
          null,
      ),
  )

  @Test
  fun `a gradient fill after a translucent color is not drawn with the color's alpha`() {
    val bitmap = draw(fill(halfRed, x = 0f), fill(opaqueGradient, x = 10f))

    assertThat(Color.alpha(bitmap.getPixel(15, 5))).isEqualTo(0xFF)
    assertThat(Color.alpha(bitmap.getPixel(5, 5))).isEqualTo(0x80)
  }

  @Test
  fun `a gradient stroke after a translucent color is not drawn with the color's alpha`() {
    val bitmap = draw(stroke(halfRed, x = 0f), stroke(opaqueGradient, x = 10f))

    assertThat(Color.alpha(bitmap.getPixel(15, 5))).isEqualTo(0xFF)
    assertThat(Color.alpha(bitmap.getPixel(5, 5))).isEqualTo(0x80)
  }

  @Test
  fun `a gradient keeps the alpha of its own colors`() {
    val translucentGradient = CanvasGradientShading(
        CanvasLinearGradient(
            CanvasGradient(
                intArrayOf(0x800000FF.toInt(), 0x800000FF.toInt()),
                floatArrayOf(0f, 1f),
            ),
            Point(0f, 0f),
            Point(10f, 0f),
            Shader.TileMode.CLAMP,
            null,
        ),
    )

    val bitmap = draw(fill(halfRed, x = 0f), fill(translucentGradient, x = 10f))

    assertThat(Color.alpha(bitmap.getPixel(15, 5))).isEqualTo(0x80)
  }

  @Test
  fun `a translucent color after a gradient keeps its alpha`() {
    val bitmap = draw(fill(opaqueGradient, x = 0f), fill(halfRed, x = 10f))

    assertThat(Color.alpha(bitmap.getPixel(5, 5))).isEqualTo(0xFF)
    assertThat(Color.alpha(bitmap.getPixel(15, 5))).isEqualTo(0x80)
  }

  @Test
  fun `translucent colors and gradients match the reference image`() {
    // On white at 2x, one shared state, four cells: a 50% red square then an opaque blue gradient
    // square, filled and stroked; then the gradient first and the red square second, filled and
    // stroked.
    val bitmap = Bitmap.createBitmap(192, 40, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    canvas.scale(2f, 2f)
    val state = CanvasState()
    listOf(
        listOf(fill(halfRed, x = 0f), fill(opaqueGradient, x = 10f)),
        listOf(stroke(halfRed, x = 0f), stroke(opaqueGradient, x = 10f)),
        listOf(fill(opaqueGradient, x = 0f), fill(halfRed, x = 10f)),
        listOf(stroke(opaqueGradient, x = 0f), stroke(halfRed, x = 10f)),
    )
        .forEachIndexed { i, nodes ->
          canvas.save()
          canvas.translate(24f * i + 2f, 5f)
          nodes.forEach { it.draw(canvas, state) }
          canvas.restore()
        }

    // The first cell: the gradient after the 50% red is opaque blue; the red is 50% over white.
    assertThat(bitmap.getPixel(34, 20)).isEqualTo(Color.BLUE)
    assertThat(Color.green(bitmap.getPixel(14, 20))).isBetween(0x7E, 0x80)
    CanvasReferenceImages.assertMatchesReference("canvas_state_translucent_then_gradient", bitmap)
  }

  private fun draw(vararg nodes: CanvasNodeModel): Bitmap {
    val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val state = CanvasState()
    nodes.forEach { it.draw(canvas, state) }
    return bitmap
  }

  private fun square(x: Float) = CanvasPath(
      fillRule = FillRule.NonZero,
      children = listOf(CanvasPathRect(Point(x, 0f), Size(10f, 10f), 0f)),
  )

  private fun fill(shading: CanvasShadingModel, x: Float) =
      CanvasFill(square(x), shading, DEFAULT_BLENDING_MODE, null)

  /** A 5x5 square stroked 5 wide, which covers a 10x10 square. */
  private fun stroke(shading: CanvasShadingModel, x: Float) = CanvasStroke(
      CanvasPath(
          fillRule = FillRule.NonZero,
          children = listOf(CanvasPathRect(Point(x + 2.5f, 2.5f), Size(5f, 5f), 0f)),
      ),
      shading,
      DEFAULT_BLENDING_MODE,
      null,
      lineWidth = 5f,
      lineCap = LineCap.Butt,
      lineJoin = LineJoin.Miter,
      miterLimit = 4f,
      dashLengths = null,
      dashPhase = 0f,
  )
}
