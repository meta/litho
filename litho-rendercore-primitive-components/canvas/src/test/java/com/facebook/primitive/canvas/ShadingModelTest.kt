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
import android.graphics.Shader
import com.facebook.primitive.canvas.model.CanvasGradient
import com.facebook.primitive.canvas.model.CanvasGradientModel
import com.facebook.primitive.canvas.model.CanvasLinearGradient
import com.facebook.primitive.canvas.model.CanvasRadialGradient
import com.facebook.primitive.canvas.model.CanvasRotate
import com.facebook.primitive.canvas.model.CanvasScale
import com.facebook.primitive.canvas.model.CanvasSkew
import com.facebook.primitive.canvas.model.CanvasTransform
import com.facebook.primitive.canvas.model.CanvasTransformChildModel
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.geometry.Point
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ShadingModelTest {

  private val redToBlue = CanvasGradient(intArrayOf(Color.RED, Color.BLUE), floatArrayOf(0f, 1f))

  @Test
  fun `the shader-local transform maps the gradient into the drawing`() {
    // A 0..1 gradient scaled to 0..100: the left edge is red, the right edge blue.
    val gradient = CanvasLinearGradient(
        redToBlue,
        Point(0f, 0f),
        Point(1f, 0f),
        Shader.TileMode.CLAMP,
        CanvasTransform(100f, 0f, 0f, 1f, 0f, 0f, emptyList()),
    )

    val bitmap = draw(gradient.toShader())

    assertThat(Color.red(bitmap.getPixel(1, 50))).isGreaterThan(240)
    assertThat(Color.blue(bitmap.getPixel(98, 50))).isGreaterThan(240)
  }

  @Test
  fun `a start circle outside the end circle draws an unclamped cone`() {
    val gradient = CanvasRadialGradient(
        redToBlue,
        Point(50f, 50f),
        10f,
        Shader.TileMode.CLAMP,
        startCenter = Point(90f, 50f),
    )

    val bitmap = draw(gradient.toShader())

    // Behind the start point, away from the end circle, the cone paints nothing.
    assertThat(Color.alpha(bitmap.getPixel(98, 50))).isEqualTo(0)
    assertThat(Color.alpha(bitmap.getPixel(50, 50))).isEqualTo(255)
  }

  @Test
  fun `a rotating transform turns a horizontal gradient vertical`() {
    val gradient = CanvasLinearGradient(
        redToBlue,
        Point(0f, 0f),
        Point(100f, 0f),
        Shader.TileMode.CLAMP,
        transform(CanvasRotate(90f, Point(50f, 50f))),
    )

    val bitmap = draw(gradient.toShader())

    assertThat(Color.red(bitmap.getPixel(50, 1))).isGreaterThan(240)
    assertThat(Color.blue(bitmap.getPixel(50, 98))).isGreaterThan(240)
    assertThat(bitmap.getPixel(1, 50)).isEqualTo(bitmap.getPixel(98, 50))
  }

  @Test
  fun `a scaling transform stretches a radial gradient into an ellipse`() {
    val gradient = CanvasRadialGradient(
        redToBlue,
        Point(50f, 50f),
        20f,
        Shader.TileMode.CLAMP,
        transform(CanvasScale(2f, 1f, Point(50f, 50f))),
    )

    val bitmap = draw(gradient.toShader())

    // 30 px right of the centre is inside the 40 x 20 ellipse; 30 px below is past it.
    assertThat(Color.red(bitmap.getPixel(80, 50))).isGreaterThan(Color.red(bitmap.getPixel(50, 80)))
    assertThat(bitmap.getPixel(50, 80)).isEqualTo(Color.BLUE)
  }

  @Test
  fun `a focal point inside the circle starts the gradient there`() {
    val gradient = CanvasRadialGradient(
        redToBlue,
        Point(50f, 50f),
        40f,
        Shader.TileMode.CLAMP,
        startCenter = Point(30f, 50f),
    )

    val bitmap = draw(gradient.toShader())

    assertThat(Color.red(bitmap.getPixel(30, 50))).isGreaterThan(240)
    // The ramp is shorter towards the near edge: at equal distances from the focal point, the
    // left side is closer to the end color.
    assertThat(Color.blue(bitmap.getPixel(20, 50)))
        .isGreaterThan(Color.blue(bitmap.getPixel(40, 50)))
    assertThat(bitmap.getPixel(50, 95)).isEqualTo(Color.BLUE)
  }

  @Test
  fun `the start circle is painted with the first color`() {
    val gradient = CanvasRadialGradient(
        redToBlue,
        Point(50f, 50f),
        40f,
        Shader.TileMode.CLAMP,
        startCenter = Point(50f, 50f),
        startRadius = 20f,
    )

    val bitmap = draw(gradient.toShader())

    assertThat(bitmap.getPixel(60, 50)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(75, 50)).isNotEqualTo(Color.RED)
  }

  @Test
  fun `a negative start radius draws as zero`() {
    fun conical(startRadius: Float) = CanvasRadialGradient(
        redToBlue,
        Point(50f, 50f),
        40f,
        Shader.TileMode.CLAMP,
        startCenter = Point(40f, 50f),
        startRadius = startRadius,
    )

    assertThat(draw(conical(-5f).toShader()).sameAs(draw(conical(0f).toShader()))).isTrue()
  }

  @Test
  fun `a radius that is not positive and finite paints the last color`() {
    for (radius in floatArrayOf(0f, -5f, Float.NaN, Float.POSITIVE_INFINITY)) {
      for (startCenter in listOf(null, Point(50f, 50f))) {
        val gradient = CanvasRadialGradient(
            redToBlue,
            Point(50f, 50f),
            radius,
            Shader.TileMode.CLAMP,
            startCenter = startCenter,
            startRadius = Float.NaN,
        )

        val bitmap = draw(gradient.toShader())

        assertThat(bitmap.getPixel(50, 50)).describedAs("radius $radius").isEqualTo(Color.BLUE)
        assertThat(bitmap.getPixel(1, 98)).describedAs("radius $radius").isEqualTo(Color.BLUE)
      }
    }
  }

  @Test
  fun `a nearly zero conical gradient paints the last color`() {
    // Skia returns no shader for two nearly zero circles whose centres are slightly apart.
    val gradient = CanvasRadialGradient(
        redToBlue,
        Point(50f, 50f),
        1e-4f,
        Shader.TileMode.CLAMP,
        startCenter = Point(50f + 1e-4f, 50f),
    )

    val bitmap = draw(gradient.toShader())

    assertThat(bitmap.getPixel(10, 10)).isEqualTo(Color.BLUE)
  }

  @Test
  fun `nearly concentric circles of nearly equal radius paint the last color`() {
    // Skia treats the circles as concentric and equal, and builds no shader.
    val gradient = CanvasRadialGradient(
        redToBlue,
        Point(50f, 50f),
        10f,
        Shader.TileMode.CLAMP,
        startCenter = Point(50f + 1e-4f, 50f),
        startRadius = 10f,
    )

    val bitmap = draw(gradient.toShader())

    assertThat(bitmap.getPixel(50, 50)).isEqualTo(Color.BLUE)
  }

  @Test
  fun `non-finite centers paint the last color`() {
    for (startCenter in listOf(null, Point(Float.NaN, 50f))) {
      val center = if (startCenter == null) Point(Float.POSITIVE_INFINITY, 50f) else Point(50f, 50f)
      val gradient = CanvasRadialGradient(
          redToBlue,
          center,
          10f,
          Shader.TileMode.CLAMP,
          startCenter = startCenter,
      )

      val bitmap = draw(gradient.toShader())

      assertThat(bitmap.getPixel(50, 50)).isEqualTo(Color.BLUE)
    }
  }

  @Test
  fun `random and extreme gradients never throw while drawing`() {
    drawRandomGradients(seed = 1, count = 4000)
  }

  @Test
  fun `stops are cut to the shorter array, clamped and kept monotonic`() {
    val gradient = CanvasGradient(
        intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK),
        floatArrayOf(0.5f, Float.NaN, 0.2f),
    )
    val linear =
        CanvasLinearGradient(gradient, Point(0f, 0f), Point(100f, 0f), Shader.TileMode.CLAMP)

    val bitmap = draw(linear.toShader())

    assertThat(linear.isDegenerate()).isFalse()
    // Red up to 0.5, then blue (0.2 raised to 0.5); green has no position and black none at all.
    assertThat(bitmap.getPixel(10, 50)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(90, 50)).isEqualTo(Color.BLUE)
  }

  @Test
  fun `fewer than two valid stops paint the last valid one`() {
    for ((gradient, expected) in
        listOf(
            CanvasGradient(intArrayOf(), null) to Color.TRANSPARENT,
            CanvasGradient(intArrayOf(Color.RED), null) to Color.RED,
            CanvasGradient(intArrayOf(Color.RED, Color.BLUE), floatArrayOf(0f, Float.NaN)) to
                Color.RED,
            CanvasGradient(intArrayOf(Color.RED, Color.BLUE), floatArrayOf(0f)) to Color.RED,
        )) {
      val linear =
          CanvasLinearGradient(gradient, Point(0f, 0f), Point(100f, 0f), Shader.TileMode.CLAMP)

      assertThat(linear.isDegenerate()).isTrue()
      assertThat(draw(linear.toShader()).getPixel(50, 50)).isEqualTo(expected)
    }
  }

  private fun drawRandomGradients(seed: Int, count: Int) {
    val random = Random(seed)
    val extremes = floatArrayOf(
        0f,
        -0f,
        1f,
        -1f,
        0.5f,
        10f,
        50f,
        1e-4f,
        1f / 4096f,
        1e-38f,
        -1e-38f,
        1e38f,
        -1e38f,
        Float.MAX_VALUE,
        -Float.MAX_VALUE,
        Float.MIN_VALUE,
        Float.NaN,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
    )
    fun value(): Float =
        if (random.nextInt(3) == 0) random.nextFloat() * 200f - 100f
        else extremes[random.nextInt(extremes.size)]
    fun point() = Point(value(), value())
    fun gradient(): CanvasGradient {
      val colors = IntArray(random.nextInt(5)) { random.nextInt() }
      val positions =
          when (random.nextInt(3)) {
            0 -> null
            1 -> FloatArray(colors.size) { value() }
            else -> FloatArray(random.nextInt(6)) { value() }
          }
      return CanvasGradient(colors, positions)
    }
    fun transform() =
        if (random.nextBoolean()) null
        else CanvasTransform(value(), value(), value(), value(), value(), value(), emptyList())

    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    repeat(count) {
      val start = point()
      val models: List<CanvasGradientModel> = listOf(
          CanvasLinearGradient(gradient(), start, point(), Shader.TileMode.CLAMP, transform()),
          CanvasRadialGradient(gradient(), point(), value(), Shader.TileMode.CLAMP, transform()),
          CanvasRadialGradient(
              gradient(),
              start,
              value(),
              Shader.TileMode.CLAMP,
              transform(),
              startCenter = if (random.nextBoolean()) start else point(),
              startRadius = value(),
          ),
      )
      for (model in models) {
        try {
          canvas.drawRect(0f, 0f, 8f, 8f, Paint().apply { shader = model.toShader() })
        } catch (e: RuntimeException) {
          throw AssertionError("Drawing $model threw", e)
        }
      }
    }
  }

  @Test
  fun `the transform and the start circle are part of the model's equality`() {
    val plain = CanvasRadialGradient(redToBlue, Point(50f, 50f), 40f, Shader.TileMode.CLAMP)

    assertThat(plain.copy(transform = transform(CanvasScale(2f, 1f, Point(0f, 0f)))))
        .isNotEqualTo(plain)
    assertThat(plain.copy(startCenter = Point(40f, 50f))).isNotEqualTo(plain)
    assertThat(plain.copy(startCenter = Point(40f, 50f), startRadius = 5f))
        .isNotEqualTo(plain.copy(startCenter = Point(40f, 50f)))
  }

  @Test
  @Config(sdk = [30])
  fun `before API 31 a conical gradient falls back to a simple radial gradient once reported`() {
    val reports = mutableListOf<String>()
    ErrorReporter.provide { level, _, message, _, _, _ ->
      assertThat(level).isEqualTo(LogLevel.WARNING)
      reports.add(message)
    }
    val simple = CanvasRadialGradient(redToBlue, Point(50f, 50f), 40f, Shader.TileMode.CLAMP)
    val conical = simple.copy(startCenter = Point(30f, 50f), startRadius = 5f)

    val fallback = draw(conical.toShader())
    draw(conical.toShader())

    assertThat(fallback.sameAs(draw(simple.toShader()))).isTrue()
    assertThat(reports).hasSize(1)
    CanvasReferenceImages.assertMatchesReference("shading_gradients_api30", cases())
  }

  @Test
  fun `gradient transforms and focal points match the reference image`() {
    val bitmap = cases()

    // The unrotated linear gradient runs left to right; rotated 90 degrees it runs top to bottom.
    assertThat(Color.red(bitmap.getPixel(4, 30))).isGreaterThan(240)
    assertThat(Color.blue(bitmap.getPixel(56, 30))).isGreaterThan(240)
    assertThat(Color.red(bitmap.getPixel(210, 4))).isGreaterThan(240)
    assertThat(Color.blue(bitmap.getPixel(210, 56))).isGreaterThan(240)
    // With the focal point outside the circle, the far side of the cone is not painted.
    assertThat(bitmap.getPixel(172, 124)).isEqualTo(Color.WHITE)
    CanvasReferenceImages.assertMatchesReference("shading_gradients", bitmap)
  }

  /**
   * Cells of 60 x 60. Row 1: a linear gradient rotated 0, 30, 45 and 90 degrees. Row 2: a linear
   * gradient skewed; a radial gradient plain, scaled into an ellipse and scaled then rotated. Row
   * 3: two-point conical gradients with the focal point inside the circle, on its edge, outside it,
   * and a start circle of radius 8.
   */
  private fun cases(): Bitmap {
    val ramp = CanvasGradient(
        intArrayOf(Color.RED, Color.YELLOW, Color.BLUE),
        floatArrayOf(0f, 0.5f, 1f),
    )
    fun linear(vararg children: CanvasTransformChildModel) = CanvasLinearGradient(
        ramp,
        Point(10f, 30f),
        Point(50f, 30f),
        Shader.TileMode.CLAMP,
        transform(*children),
    )
    fun radial(vararg children: CanvasTransformChildModel) = CanvasRadialGradient(
        ramp,
        Point(30f, 30f),
        15f,
        Shader.TileMode.CLAMP,
        if (children.isEmpty()) null else transform(*children),
    )
    fun conical(focal: Point, startRadius: Float = 0f) = CanvasRadialGradient(
        ramp,
        Point(30f, 30f),
        20f,
        Shader.TileMode.CLAMP,
        startCenter = focal,
        startRadius = startRadius,
    )
    val center = Point(30f, 30f)
    val cells: List<CanvasGradientModel> = listOf(
        linear(),
        linear(CanvasRotate(30f, center)),
        linear(CanvasRotate(45f, center)),
        linear(CanvasRotate(90f, center)),
        linear(CanvasSkew(0.5f, 0f, center)),
        radial(),
        radial(CanvasScale(1.8f, 1f, center)),
        radial(CanvasScale(1.8f, 1f, center), CanvasRotate(45f, center)),
        conical(Point(22f, 24f)),
        conical(Point(50f, 30f)),
        conical(Point(56f, 30f)),
        conical(Point(30f, 30f), startRadius = 8f),
    )
    val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    cells.forEachIndexed { i, gradient ->
      canvas.save()
      canvas.translate(60f * (i % 4), 60f * (i / 4))
      canvas.drawRect(2f, 2f, 58f, 58f, Paint().apply { shader = gradient.toShader() })
      canvas.restore()
    }
    return bitmap
  }

  private fun transform(vararg children: CanvasTransformChildModel) =
      CanvasTransform(1f, 0f, 0f, 1f, 0f, 0f, children.toList())

  private fun draw(shader: Shader): Bitmap {
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawRect(0f, 0f, 100f, 100f, Paint().apply { this.shader = shader })
    return bitmap
  }
}
