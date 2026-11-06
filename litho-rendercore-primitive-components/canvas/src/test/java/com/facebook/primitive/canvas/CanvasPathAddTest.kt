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
import android.graphics.RectF
import com.facebook.primitive.canvas.model.CanvasFill
import com.facebook.primitive.canvas.model.CanvasModel
import com.facebook.primitive.canvas.model.CanvasPath
import com.facebook.primitive.canvas.model.CanvasPathAdd
import com.facebook.primitive.canvas.model.CanvasPathModel
import com.facebook.primitive.canvas.model.CanvasPathRect
import com.facebook.primitive.canvas.model.CanvasRotate
import com.facebook.primitive.canvas.model.CanvasScale
import com.facebook.primitive.canvas.model.CanvasSolidColorShading
import com.facebook.primitive.canvas.model.CanvasTransform
import com.facebook.primitive.canvas.model.CanvasTranslate
import com.facebook.primitive.utils.types.DEFAULT_BLENDING_MODE
import com.facebook.primitive.utils.types.FillRule
import com.facebook.rendercore.geometry.Point
import com.facebook.rendercore.geometry.Size
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasPathAddTest {

  private val square: CanvasPathModel = CanvasPath(
      fillRule = FillRule.NonZero,
      children =
          listOf(
              CanvasPathRect(
                  topLeft = Point(0f, 0f),
                  size = Size(10f, 10f),
                  cornerRadius = 0f,
              ),
          ),
  )

  private val translatedSquare = CanvasPathAdd(
      src = square,
      transformModel =
          CanvasTransform(
              a = 1f,
              b = 0f,
              c = 0f,
              d = 1f,
              tx = 0f,
              ty = 0f,
              children = listOf(CanvasTranslate(dx = 20f, dy = 0f)),
          ),
  )

  @Test
  fun `adding the same source twice applies the transform to each copy once`() {
    val state = CanvasState()
    val path =
        state.getOrCreatePath(
            CanvasPath(
                fillRule = FillRule.NonZero,
                children = listOf(translatedSquare, translatedSquare),
            ),
        )

    assertThat(path.bounds()).isEqualTo(RectF(20f, 0f, 30f, 10f))
  }

  @Test
  fun `paths sharing a cached source get the same geometry`() {
    val state = CanvasState()
    val first =
        state.getOrCreatePath(
            CanvasPath(fillRule = FillRule.NonZero, children = listOf(translatedSquare)),
        )
    val second =
        state.getOrCreatePath(
            CanvasPath(fillRule = FillRule.EvenOdd, children = listOf(translatedSquare)),
        )

    assertThat(first.bounds()).isEqualTo(RectF(20f, 0f, 30f, 10f))
    assertThat(second.bounds()).isEqualTo(first.bounds())
    assertThat(state.getOrCreatePath(square).bounds()).isEqualTo(RectF(0f, 0f, 10f, 10f))
  }

  @Test
  fun `repeated draws render identical pixels`() {
    val state = CanvasState()
    val modelA = fillModel(state, FillRule.NonZero)
    val modelB = fillModel(state, FillRule.EvenOdd)

    val draws = listOf(modelA, modelB, modelA, modelB).map { it.render() }

    val expected = draws.first()
    assertThat(expected.getPixel(25, 5)).isEqualTo(Color.RED)
    assertThat(expected.getPixel(5, 5)).isEqualTo(Color.TRANSPARENT)
    assertThat(expected.getPixel(45, 5)).isEqualTo(Color.TRANSPARENT)
    draws.forEach { bitmap -> assertThat(bitmap.sameAs(expected)).isTrue() }
  }

  @Test
  fun `transformed path adds match the reference image`() {
    // Cells of 40 x 40: a source added twice with the same transform, another path sharing that
    // cached source, the source scaled and rotated, and the first path drawn again.
    val centered = CanvasPathAdd(square, translation(15f, 15f))
    val rotated = CanvasPathAdd(
        square,
        CanvasTransform(
            a = 1f,
            b = 0f,
            c = 0f,
            d = 1f,
            tx = 0f,
            ty = 0f,
            children =
                listOf(
                    CanvasScale(1.5f, 1.5f, Point(5f, 5f)),
                    CanvasRotate(45f, Point(5f, 5f)),
                    CanvasTranslate(dx = 15f, dy = 15f),
                ),
        ),
    )
    val state = CanvasState()
    val twice = model(state, Color.RED, FillRule.NonZero, centered, centered)
    val shared = model(state, Color.BLUE, FillRule.EvenOdd, centered)
    val scaledAndRotated = model(state, Color.GREEN, FillRule.NonZero, rotated)

    val bitmap = Bitmap.createBitmap(160, 40, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    listOf(twice, shared, scaledAndRotated, twice).forEachIndexed { i, model ->
      canvas.save()
      canvas.translate(40f * i, 0f)
      model.draw(canvas)
      canvas.restore()
    }

    assertThat(bitmap.getPixel(20, 20)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(28, 28)).isEqualTo(Color.WHITE)
    assertThat(bitmap.getPixel(60, 20)).isEqualTo(Color.BLUE)
    assertThat(bitmap.getPixel(68, 28)).isEqualTo(Color.WHITE)
    assertThat(bitmap.getPixel(140, 20)).isEqualTo(Color.RED)
    CanvasReferenceImages.assertMatchesReference("canvas_path_add_transforms", bitmap)
  }

  private fun translation(dx: Float, dy: Float) = CanvasTransform(
      a = 1f,
      b = 0f,
      c = 0f,
      d = 1f,
      tx = 0f,
      ty = 0f,
      children = listOf(CanvasTranslate(dx = dx, dy = dy)),
  )

  private fun model(
      state: CanvasState,
      color: Int,
      fillRule: FillRule,
      vararg adds: CanvasPathAdd,
  ): CanvasModel = CanvasModel(
      canvasState = state,
      children =
          listOf(
              CanvasFill(
                  shape = CanvasPath(fillRule = fillRule, children = adds.toList()),
                  shading = CanvasSolidColorShading(color),
                  blendingMode = DEFAULT_BLENDING_MODE,
                  shadow = null,
              ),
          ),
  )

  private fun fillModel(state: CanvasState, fillRule: FillRule): CanvasModel = CanvasModel(
      canvasState = state,
      children =
          listOf(
              CanvasFill(
                  shape = CanvasPath(fillRule = fillRule, children = listOf(translatedSquare)),
                  shading = CanvasSolidColorShading(Color.RED),
                  blendingMode = DEFAULT_BLENDING_MODE,
                  shadow = null,
              ),
          ),
  )

  private fun CanvasModel.render(): Bitmap {
    val bitmap = Bitmap.createBitmap(60, 10, Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    return bitmap
  }

  private fun android.graphics.Path.bounds(): RectF = RectF().also { computeBounds(it, true) }
}
