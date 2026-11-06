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
import com.facebook.primitive.canvas.model.CanvasGroup
import com.facebook.primitive.canvas.model.CanvasNodeModel
import com.facebook.primitive.canvas.model.CanvasPath
import com.facebook.primitive.canvas.model.CanvasPathAdd
import com.facebook.primitive.canvas.model.CanvasPathAddUnion
import com.facebook.primitive.canvas.model.CanvasPathChildModel
import com.facebook.primitive.canvas.model.CanvasPathCircle
import com.facebook.primitive.canvas.model.CanvasPathModel
import com.facebook.primitive.canvas.model.CanvasPathRect
import com.facebook.primitive.canvas.model.CanvasSolidColorShading
import com.facebook.primitive.canvas.model.CanvasTransform
import com.facebook.primitive.canvas.model.CanvasTranslate
import com.facebook.primitive.utils.types.DEFAULT_BLENDING_MODE
import com.facebook.primitive.utils.types.FillRule
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.geometry.Point
import com.facebook.rendercore.geometry.Size
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasPathAddUnionTest {

  /** A 20 x 20 square at the origin. */
  private val square = path(FillRule.NonZero, CanvasPathRect(Point(0f, 0f), Size(20f, 20f), 0f))

  /** A 20 x 20 square with a 10 x 10 hole: two contours filled even-odd. */
  private val ring = path(
      FillRule.EvenOdd,
      CanvasPathRect(Point(0f, 0f), Size(20f, 20f), 0f),
      CanvasPathRect(Point(5f, 5f), Size(10f, 10f), 0f),
  )

  @Test
  fun `the union of overlapping shapes fills their overlap even in an even-odd path`() {
    val union = path(FillRule.EvenOdd, unite(square, 0f, 0f), unite(square, 10f, 10f))
    val added = path(FillRule.EvenOdd, add(square, 0f, 0f), add(square, 10f, 10f))

    val unionBitmap = fill(union)
    val addedBitmap = fill(added)

    assertThat(unionBitmap.getPixel(15, 15)).isEqualTo(Color.RED)
    assertThat(addedBitmap.getPixel(15, 15)).isEqualTo(Color.TRANSPARENT)
    assertThat(unionBitmap.getPixel(5, 5)).isEqualTo(Color.RED)
    assertThat(unionBitmap.getPixel(25, 25)).isEqualTo(Color.RED)
    assertThat(unionBitmap.getPixel(25, 5)).isEqualTo(Color.TRANSPARENT)
  }

  @Test
  fun `each operand keeps its own fill rule`() {
    // The even-odd ring keeps its hole although the united path is non-zero.
    val union = path(FillRule.NonZero, unite(ring, 0f, 0f), unite(square, 20f, 0f))

    val bitmap = fill(union)

    assertThat(bitmap.getPixel(2, 2)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(10, 10)).isEqualTo(Color.TRANSPARENT)
    assertThat(bitmap.getPixel(30, 10)).isEqualTo(Color.RED)
  }

  @Test
  fun `the cached source is not transformed by the union`() {
    val state = CanvasState()
    val union = path(FillRule.NonZero, unite(square, 30f, 0f), unite(square, 30f, 0f))

    val united = state.getOrCreatePath(union)

    assertThat(united.bounds()).isEqualTo(RectF(30f, 0f, 50f, 20f))
    assertThat(state.getOrCreatePath(square).bounds()).isEqualTo(RectF(0f, 0f, 20f, 20f))
  }

  @Test
  fun `a union clips a group to the area either shape fills`() {
    val clip = path(FillRule.NonZero, unite(ring, 0f, 0f), unite(circle(), 30f, 10f))

    val bitmap = draw(clipped(clip))

    assertThat(bitmap.getPixel(2, 2)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(10, 10)).isEqualTo(Color.TRANSPARENT)
    assertThat(bitmap.getPixel(30, 10)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(39, 39)).isEqualTo(Color.TRANSPARENT)
  }

  @Test
  fun `a union the path ops cannot compute appends the operand and is reported once`() {
    val reports = mutableListOf<String>()
    ErrorReporter.provide { level, _, message, _, _, _ ->
      assertThat(level).isEqualTo(LogLevel.ERROR)
      reports.add(message)
    }
    // Robolectric's path ops succeed even for non-finite coordinates, so the host path fails them.
    val appended = mutableListOf<RectF>()
    val host =
        object : android.graphics.Path() {
          override fun op(path: android.graphics.Path, op: Op): Boolean = false

          override fun addPath(src: android.graphics.Path) {
            appended.add(src.bounds())
            super.addPath(src)
          }
        }
    val state = CanvasState()

    unite(square, 30f, 0f).applyTo(host, state)
    unite(square, 30f, 0f).applyTo(host, state)

    assertThat(appended).containsExactly(RectF(30f, 0f, 50f, 20f), RectF(30f, 0f, 50f, 20f))
    assertThat(host.bounds()).isEqualTo(RectF(30f, 0f, 50f, 20f))
    assertThat(reports).hasSize(1)
  }

  @Test
  fun `unions match the reference image`() {
    // Cells of 80 x 80 (40 x 40 at 2x): overlapping squares added to an even-odd path (the overlap
    // is a
    // hole) and united (filled); an even-odd ring united with a square; the ring and a circle
    // united and used as a group clip over a red rect.
    val cells: List<CanvasNodeModel> = listOf(
        fillNode(path(FillRule.EvenOdd, add(square, 0f, 0f), add(square, 5f, 5f))),
        fillNode(path(FillRule.EvenOdd, unite(square, 0f, 0f), unite(square, 5f, 5f))),
        fillNode(path(FillRule.NonZero, unite(ring, 0f, 0f), unite(square, 10f, 10f))),
        clipped(path(FillRule.NonZero, unite(ring, 0f, 0f), unite(circle(), 15f, 10f))),
    )
    val bitmap = Bitmap.createBitmap(320, 80, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    canvas.scale(2f, 2f)
    val state = CanvasState()
    cells.forEachIndexed { i, node ->
      canvas.save()
      canvas.translate(40f * i + 5f, 5f)
      node.draw(canvas, state)
      canvas.restore()
    }

    // The overlap of the two squares, (12, 12) in each cell: a hole when added, filled when united.
    assertThat(bitmap.getPixel(34, 34)).isEqualTo(Color.WHITE)
    assertThat(bitmap.getPixel(114, 34)).isEqualTo(Color.RED)
    CanvasReferenceImages.assertMatchesReference("canvas_path_add_union", bitmap)
  }

  private fun circle(): CanvasPathModel =
      path(FillRule.NonZero, CanvasPathCircle(Point(0f, 0f), 8f))

  private fun path(fillRule: FillRule, vararg children: CanvasPathChildModel) =
      CanvasPath(fillRule = fillRule, children = children.toList())

  private fun unite(src: CanvasPathModel, dx: Float, dy: Float) =
      CanvasPathAddUnion(src, translation(dx, dy))

  private fun add(src: CanvasPathModel, dx: Float, dy: Float) =
      CanvasPathAdd(src, translation(dx, dy))

  private fun translation(dx: Float, dy: Float) =
      CanvasTransform(1f, 0f, 0f, 1f, 0f, 0f, listOf(CanvasTranslate(dx, dy)))

  private fun fillNode(shape: CanvasPathModel): CanvasNodeModel =
      CanvasFill(shape, CanvasSolidColorShading(Color.RED), DEFAULT_BLENDING_MODE, null)

  private fun clipped(clip: CanvasPathModel): CanvasNodeModel = CanvasGroup(
      transform = CanvasTransform.IDENTITY,
      size = Size(40f, 40f),
      clip = clip,
      clipToBounds = false,
      children =
          listOf(
              fillNode(path(FillRule.NonZero, CanvasPathRect(Point(0f, 0f), Size(40f, 40f), 0f))),
          ),
  )

  private fun fill(shape: CanvasPathModel): Bitmap = draw(fillNode(shape))

  private fun draw(node: CanvasNodeModel): Bitmap {
    val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
    node.draw(Canvas(bitmap), CanvasState())
    return bitmap
  }

  private fun android.graphics.Path.bounds(): RectF = RectF().also { computeBounds(it, true) }
}
