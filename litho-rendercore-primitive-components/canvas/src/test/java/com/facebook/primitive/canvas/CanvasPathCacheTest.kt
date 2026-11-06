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
import com.facebook.primitive.canvas.model.CanvasNodeModel
import com.facebook.primitive.canvas.model.CanvasPath
import com.facebook.primitive.canvas.model.CanvasPathAdd
import com.facebook.primitive.canvas.model.CanvasPathChildModel
import com.facebook.primitive.canvas.model.CanvasPathCircle
import com.facebook.primitive.canvas.model.CanvasPathModel
import com.facebook.primitive.canvas.model.CanvasPathRect
import com.facebook.primitive.canvas.model.CanvasSolidColorShading
import com.facebook.primitive.canvas.model.CanvasTransform
import com.facebook.primitive.canvas.model.countCachedPaths
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
class CanvasPathCacheTest {

  private val canvas = Canvas(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))

  @Test
  fun `a model with more paths than the default cache size reuses all its paths`() {
    val state = CanvasState()
    val paths = squares(count = 15)
    val model = fillModel(state, paths)

    model.draw(canvas)
    val firstDrawPaths = paths.map { state.getOrCreatePath(it) }
    model.draw(canvas)

    assertThat(state.pathCacheMaxSize).isEqualTo(15)
    paths.forEachIndexed { i, path ->
      assertThat(state.getOrCreatePath(path)).isSameAs(firstDrawPaths[i])
    }
  }

  @Test
  fun `an equal model reuses the cached paths`() {
    val state = CanvasState()
    val paths = squares(count = 15)
    fillModel(state, paths).draw(canvas)
    val firstDrawPaths = paths.map { state.getOrCreatePath(it) }

    val equalPaths = squares(count = 15)
    fillModel(state, equalPaths).draw(canvas)

    equalPaths.forEachIndexed { i, path ->
      assertThat(path).isNotSameAs(paths[i])
      assertThat(state.getOrCreatePath(path)).isSameAs(firstDrawPaths[i])
    }
  }

  @Test
  fun `the cache shrinks to the configured size and keeps the paths of the last drawn model`() {
    val state = CanvasState(pathCacheSize = 12)
    fillModel(state, squares(count = 30)).draw(canvas)
    assertThat(state.pathCacheMaxSize).isEqualTo(30)

    val smallModelPaths = squares(count = 3, firstX = 50f)
    fillModel(state, smallModelPaths).draw(canvas)
    val smallModelAndroidPaths = smallModelPaths.map { state.getOrCreatePath(it) }
    fillModel(state, smallModelPaths).draw(canvas)

    assertThat(state.pathCacheMaxSize).isEqualTo(12)
    smallModelPaths.forEachIndexed { i, path ->
      assertThat(state.getOrCreatePath(path)).isSameAs(smallModelAndroidPaths[i])
    }
  }

  @Test
  fun `path add sources count towards the cache size`() {
    val state = CanvasState()
    val sources = squares(count = 8)
    val paths = sources.map { source ->
      CanvasPath(
          fillRule = FillRule.EvenOdd,
          children = listOf(CanvasPathAdd(src = source, transformModel = CanvasTransform.IDENTITY)),
      )
    }

    fillModel(state, paths).draw(canvas)

    assertThat(state.pathCacheMaxSize).isEqualTo(16)
  }

  @Test
  fun `nodes drawn without a model can size the cache with countCachedPaths`() {
    val state = CanvasState()
    val paths = squares(count = 15)
    val nodes = fills(paths)
    val pathCount = countCachedPaths(nodes)

    state.ensurePathCacheCapacity(pathCount)
    nodes.forEach { it.draw(canvas, state) }
    state.trimPathCache(pathCount)
    val firstDrawPaths = paths.map { state.getOrCreatePath(it) }

    assertThat(pathCount).isEqualTo(15)
    assertThat(state.pathCacheMaxSize).isEqualTo(15)
    paths.forEachIndexed { i, path ->
      assertThat(state.getOrCreatePath(path)).isSameAs(firstDrawPaths[i])
    }
  }

  @Test
  fun `paths evicted by another model are re-created with the same pixels`() {
    val state = CanvasState(pathCacheSize = 2)
    val grid = gridModel(state, Color.RED, cornerRadius = 4f)
    val firstPath = gridPaths(cornerRadius = 4f).first()

    val first = render(grid)
    val firstAndroidPath = state.getOrCreatePath(firstPath)
    render(gridModel(state, Color.BLUE, cornerRadius = 0f))
    render(gridModel(state, Color.BLUE, cornerRadius = 8f))
    assertThat(state.getOrCreatePath(firstPath)).isNotSameAs(firstAndroidPath)
    val afterEviction = render(grid)
    val fresh = render(gridModel(CanvasState(), Color.RED, cornerRadius = 4f))

    assertThat(afterEviction.sameAs(first)).isTrue()
    assertThat(fresh.sameAs(first)).isTrue()
    CanvasReferenceImages.assertMatchesReference("canvas_path_cache_grid", first)
  }

  /**
   * 30 cells of 20 x 20, each a rounded square with a hole: a dot added from one shared source, so
   * every path also depends on a cached [CanvasPathAdd] source.
   */
  private fun gridPaths(cornerRadius: Float): List<CanvasPathModel> {
    val dot = CanvasPath(
        fillRule = FillRule.NonZero,
        children = listOf<CanvasPathChildModel>(CanvasPathCircle(Point(0f, 0f), 3f)),
    )
    return (0 until 30).map { i ->
      val x = 20f * (i % 6)
      val y = 20f * (i / 6)
      CanvasPath(
          fillRule = FillRule.EvenOdd,
          children =
              listOf(
                  CanvasPathRect(Point(x + 2f, y + 2f), Size(16f, 16f), cornerRadius),
                  CanvasPathAdd(
                      src = dot,
                      transformModel =
                          CanvasTransform(1f, 0f, 0f, 1f, x + 10f, y + 10f, emptyList()),
                  ),
              ),
      )
    }
  }

  private fun gridModel(
      state: CanvasState,
      color: Int,
      cornerRadius: Float,
  ): CanvasModel = CanvasModel(
      canvasState = state,
      children =
          gridPaths(cornerRadius).map { path ->
            CanvasFill(
                shape = path,
                shading = CanvasSolidColorShading(color),
                blendingMode = DEFAULT_BLENDING_MODE,
                shadow = null,
            )
          },
  )

  private fun render(model: CanvasModel): Bitmap {
    val bitmap = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    model.draw(canvas)
    return bitmap
  }

  private fun squares(count: Int, firstX: Float = 0f): List<CanvasPathModel> =
      (0 until count).map { i ->
        CanvasPath(
            fillRule = FillRule.NonZero,
            children =
                listOf<CanvasPathChildModel>(
                    CanvasPathRect(
                        topLeft = Point(firstX + i, 0f),
                        size = Size(1f, 1f),
                        cornerRadiusX = 0f,
                    ),
                ),
        )
      }

  private fun fillModel(
      state: CanvasState,
      paths: List<CanvasPathModel>,
  ): CanvasModel = CanvasModel(canvasState = state, children = fills(paths))

  private fun fills(paths: List<CanvasPathModel>): List<CanvasNodeModel> = paths.map { path ->
    CanvasFill(
        shape = path,
        shading = CanvasSolidColorShading(Color.RED),
        blendingMode = DEFAULT_BLENDING_MODE,
        shadow = null,
    )
  }
}
