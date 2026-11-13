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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.facebook.primitive.canvas.model.CanvasFill
import com.facebook.primitive.canvas.model.CanvasFont
import com.facebook.primitive.canvas.model.CanvasGradient
import com.facebook.primitive.canvas.model.CanvasGradientShading
import com.facebook.primitive.canvas.model.CanvasLinearGradient
import com.facebook.primitive.canvas.model.CanvasModel
import com.facebook.primitive.canvas.model.CanvasNodeModel
import com.facebook.primitive.canvas.model.CanvasPath
import com.facebook.primitive.canvas.model.CanvasPathText
import com.facebook.primitive.canvas.model.CanvasSolidColorShading
import com.facebook.primitive.canvas.model.CanvasStroke
import com.facebook.primitive.canvas.model.CanvasText
import com.facebook.primitive.canvas.model.CanvasTransform
import com.facebook.primitive.utils.types.DEFAULT_BLENDING_MODE
import com.facebook.primitive.utils.types.FillRule
import com.facebook.primitive.utils.types.LineCap
import com.facebook.primitive.utils.types.LineJoin
import com.facebook.rendercore.geometry.Point
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasTextTest {

  private val font = CanvasFont(Typeface.MONOSPACE, 40f)

  @Test
  fun `a text child adds the glyph outlines under its transform`() {
    val path = CanvasPath(FillRule.NonZero, listOf(CanvasPathText("H", font, translate(10f, 50f))))
    val bounds = RectF()
    CanvasState().getOrCreatePath(path).computeBounds(bounds, true)

    val expected = RectF()
    textPath("H", font).apply { offset(10f, 50f) }.computeBounds(expected, true)
    assertThat(bounds).isEqualTo(expected)
    // The glyph sits on the baseline at y = 50, starting near x = 10.
    assertThat(bounds.bottom).isEqualTo(50f)
    assertThat(bounds.top).isLessThan(30f)
    assertThat(bounds.left).isBetween(10f, 20f)
  }

  @Test
  fun `several text children make one path, filled with its fill rule`() {
    val children = listOf(
        CanvasPathText("A", font, translate(0f, 40f)),
        CanvasPathText("B", font, translate(30f, 40f)),
    )
    val bitmap = draw(fill(CanvasPath(FillRule.NonZero, children), Color.RED))

    assertThat(countColor(bitmap, Color.RED, 0, 30)).isGreaterThan(0)
    assertThat(countColor(bitmap, Color.RED, 30, 60)).isGreaterThan(0)
  }

  @Test
  fun `text outlines scale exactly with their transform`() {
    val small = CanvasState().getOrCreatePath(path("g", CanvasFont(Typeface.MONOSPACE, 10f)))
    val large = CanvasState().getOrCreatePath(path("g", CanvasFont(Typeface.MONOSPACE, 40f)))
    val smallBounds = RectF().also { small.computeBounds(it, true) }
    val largeBounds = RectF().also { large.computeBounds(it, true) }

    assertThat(largeBounds.width()).isCloseTo(smallBounds.width() * 4f, within(0.01f))
    assertThat(largeBounds.height()).isCloseTo(smallBounds.height() * 4f, within(0.01f))
  }

  @Test
  fun `malformed variation settings draw the default instance`() {
    val malformed = CanvasFont(Typeface.MONOSPACE, 40f, variationSettings = "weight 700")
    val path = CanvasState().getOrCreatePath(path("g", malformed))
    val reference = CanvasState().getOrCreatePath(path("g", font))
    val bounds = RectF().also { path.computeBounds(it, true) }
    val referenceBounds = RectF().also { reference.computeBounds(it, true) }

    assertThat(bounds).isEqualTo(referenceBounds)
  }

  @Test
  fun `paths with different text, font or transform are cached separately`() {
    val state = CanvasState()
    val a = path("a", font)
    val b = path("b", font)
    val bold = path("a", CanvasFont(Typeface.DEFAULT_BOLD, 40f))

    assertThat(path("a", font)).isEqualTo(a)
    assertThat(state.getOrCreatePath(a)).isSameAs(state.getOrCreatePath(path("a", font)))
    assertThat(state.getOrCreatePath(a)).isNotSameAs(state.getOrCreatePath(b))
    assertThat(state.getOrCreatePath(a)).isNotSameAs(state.getOrCreatePath(bold))
  }

  @Test
  fun `a text node draws the text with its shading under its transform`() {
    val bitmap = draw(
        CanvasText(
            "H",
            font,
            translate(10f, 50f),
            CanvasSolidColorShading(Color.BLUE),
            DEFAULT_BLENDING_MODE,
        ),
    )

    assertThat(countColor(bitmap, Color.BLUE, 0, 60)).isGreaterThan(0)
    // Nothing below the baseline: "H" has no descender.
    assertThat(countColor(bitmap, Color.BLUE, 0, 60, top = 52)).isEqualTo(0)
  }

  @Test
  fun `consecutive text nodes keep their own font and shading`() {
    val red = CanvasText(
        "I",
        font,
        translate(5f, 50f),
        CanvasSolidColorShading(Color.RED),
        DEFAULT_BLENDING_MODE,
    )
    val blue = CanvasText(
        "I",
        CanvasFont(Typeface.MONOSPACE, 20f),
        translate(35f, 50f),
        CanvasSolidColorShading(Color.BLUE),
        DEFAULT_BLENDING_MODE,
    )
    val bitmap = draw(red, blue)
    val redTop = firstRow(bitmap, Color.RED)
    val blueTop = firstRow(bitmap, Color.BLUE)

    // Half the size: the blue "I" rises half as far above the shared baseline.
    assertThat(50 - blueTop).isCloseTo((50 - redTop) / 2, within(2))
  }

  @Test
  fun `text matches the reference image`() {
    // Rows: outlines filled with a gradient; outlines stroked with dashes; the same text drawn
    // natively with a translucent color, rotated 10 degrees.
    val gradient = CanvasGradientShading(
        CanvasLinearGradient(
            CanvasGradient(intArrayOf(Color.RED, Color.BLUE), null),
            Point(10f, 0f),
            Point(190f, 0f),
            Shader.TileMode.CLAMP,
            CanvasTransform.IDENTITY,
        ),
    )
    val text = "Text 123"
    val outlines = CanvasPath(
        FillRule.NonZero,
        text.mapIndexed { i, c ->
          CanvasPathText(c.toString(), font, translate(10f + 24f * i, 40f))
        },
    )
    val stroked = CanvasPath(
        FillRule.NonZero,
        text.mapIndexed { i, c ->
          CanvasPathText(c.toString(), font, translate(10f + 24f * i, 90f))
        },
    )
    val native = text.mapIndexed { i, c ->
      CanvasText(
          c.toString(),
          font,
          CanvasTransform(0.985f, 0.174f, -0.174f, 0.985f, 10f + 24f * i, 130f, emptyList()),
          CanvasSolidColorShading(0x8000A000.toInt()),
          DEFAULT_BLENDING_MODE,
      )
    }
    val bitmap = Bitmap.createBitmap(220, 160, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    CanvasModel(
        CanvasState(),
        listOf(
            CanvasFill(outlines, gradient, DEFAULT_BLENDING_MODE, null),
            CanvasStroke(
                shape = stroked,
                shading = CanvasSolidColorShading(Color.BLACK),
                blendingMode = DEFAULT_BLENDING_MODE,
                shadow = null,
                lineWidth = 1.5f,
                lineCap = LineCap.Butt,
                lineJoin = LineJoin.Miter,
                miterLimit = 4f,
                dashLengths = floatArrayOf(3f, 2f),
                dashPhase = 0f,
            ),
        ) + native,
    )
        .draw(canvas)

    assertThat(countColor(bitmap, Color.WHITE, 0, 220, top = 0, bottom = 160)).isLessThan(220 * 160)
    CanvasReferenceImages.assertMatchesReference("canvas_text", bitmap)
  }

  private fun within(value: Float) = org.assertj.core.data.Offset.offset(value)

  private fun within(value: Int) = org.assertj.core.data.Offset.offset(value)

  private fun translate(x: Float, y: Float) = CanvasTransform(1f, 0f, 0f, 1f, x, y, emptyList())

  private fun path(text: String, font: CanvasFont) =
      CanvasPath(FillRule.NonZero, listOf(CanvasPathText(text, font, CanvasTransform.IDENTITY)))

  private fun textPath(text: String, font: CanvasFont): Path {
    val paint = Paint()
    paint.typeface = font.typeface
    paint.textSize = font.size
    paint.isLinearText = true
    paint.isSubpixelText = true
    paint.hinting = Paint.HINTING_OFF
    return Path().also { paint.getTextPath(text, 0, text.length, 0f, 0f, it) }
  }

  private fun fill(path: CanvasPath, color: Int) =
      CanvasFill(path, CanvasSolidColorShading(color), DEFAULT_BLENDING_MODE, null)

  private fun draw(vararg nodes: CanvasNodeModel): Bitmap {
    val bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
    CanvasModel(CanvasState(), nodes.toList()).draw(Canvas(bitmap))
    return bitmap
  }

  private fun countColor(
      bitmap: Bitmap,
      color: Int,
      left: Int,
      right: Int,
      top: Int = 0,
      bottom: Int = bitmap.height,
  ): Int {
    var count = 0
    for (y in top until bottom) {
      for (x in left until minOf(right, bitmap.width)) {
        if (bitmap.getPixel(x, y) == color) {
          count++
        }
      }
    }
    return count
  }

  private fun firstRow(bitmap: Bitmap, color: Int): Int =
      (0 until bitmap.height).first { y ->
        (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) == color }
      }
}
