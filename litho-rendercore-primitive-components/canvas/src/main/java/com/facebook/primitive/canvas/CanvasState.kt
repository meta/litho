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

import android.annotation.SuppressLint
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.VisibleForTesting
import androidx.collection.LruCache
import androidx.core.util.Pools
import com.facebook.primitive.canvas.model.CanvasFill
import com.facebook.primitive.canvas.model.CanvasGradientShading
import com.facebook.primitive.canvas.model.CanvasLayer
import com.facebook.primitive.canvas.model.CanvasPathModel
import com.facebook.primitive.canvas.model.CanvasSolidColorShading
import com.facebook.primitive.canvas.model.CanvasStroke
import com.facebook.primitive.canvas.model.CanvasTransformModel

/**
 * The state of the Prmitive canvas component.
 *
 * Android [Paint] is a "heavy" object that is expensive to create and destroy. This class is
 * responsible for caching fill, stroke and layer Paint objects and reusing them for all drawing
 * commands. The Paint objects are updated only when necessary.
 *
 * Similarly [Matrix] and [Path] are expensive to create and destroy. [Matrix] objects are often
 * updated after they're created. [Path] objects on the other hand are updated less often. In order
 * to improve performance [Matrix] objects will be pooled and [Path] objects will be cached.
 *
 * In order to get the best performance, on instance of this class should be a component-scoped
 * object that survives state updates. In Litho, create it with
 * [useCached](https://fblitho.com/docs/state-and-side-effects/use-cached/).
 *
 * @param matrixPoolSize The Matrix pool max size
 * @param pathCacheSize The minimum Path cache size. The cache grows to fit all the paths of the
 *   model being drawn, and shrinks back when a model with fewer paths is drawn.
 */
class CanvasState(
    matrixPoolSize: Int = DEFAULT_MATRIX_POOL_SIZE,
    pathCacheSize: Int = DEFAULT_PATH_CACHE_SIZE,
) {
  // The paint object used for filling shapes
  private var fillPaint: Paint? = null
  private var fillModel: CanvasFill? = null

  // The paint object used for stroking shapes
  private var strokePaint: Paint? = null
  private var strokeModel: CanvasStroke? = null

  // The paint object used for compositing layers
  private var layerPaint: Paint? = null
  private var prevLayerModel: CanvasLayer? = null

  private val matrixPool = Pools.SimplePool<Matrix>(matrixPoolSize)

  // Cached paths are shared between all the drawing commands which use an equal CanvasPathModel,
  // so they must never be mutated after they're created
  private val pathCache = LruCache<CanvasPathModel, Path>(pathCacheSize)
  private val minPathCacheSize = pathCacheSize

  @get:VisibleForTesting
  internal val pathCacheMaxSize: Int
    get() = pathCache.maxSize()

  /**
   * Grows the path cache so that drawing a model with [pathCount] paths never evicts any of its own
   * paths. Call before drawing the model. [CanvasModel.draw] does this; code that draws
   * [com.facebook.primitive.canvas.model.CanvasNodeModel]s directly sizes the cache with
   * [com.facebook.primitive.canvas.model.countCachedPaths].
   */
  fun ensurePathCacheCapacity(pathCount: Int) {
    val targetSize = maxOf(minPathCacheSize, pathCount)
    if (targetSize > pathCache.maxSize()) {
      pathCache.resize(targetSize)
    }
  }

  /**
   * Shrinks the path cache back to what a model with [pathCount] paths needs. Call after drawing
   * the model: its paths are then the most recently used entries, so only paths of previous models
   * are evicted.
   */
  fun trimPathCache(pathCount: Int) {
    val targetSize = maxOf(minPathCacheSize, pathCount)
    if (targetSize < pathCache.maxSize()) {
      pathCache.resize(targetSize)
    }
  }

  internal fun getMatrix(transformModel: CanvasTransformModel, block: (Matrix) -> Unit) {
    val matrix = matrixPool.acquire() ?: Matrix()
    transformModel.applyTo(matrix)
    block(matrix)
    matrix.reset()
    matrixPool.release(matrix)
  }

  @SuppressLint("KotlinPreferElvis")
  internal fun getOrCreatePath(pathModel: CanvasPathModel): Path {
    val fromCache = pathCache.get(pathModel)
    return if (fromCache != null) {
      fromCache
    } else {
      val newPath = pathModel.toAndroidPath(this)
      pathCache.put(pathModel, newPath)
      newPath
    }
  }

  internal fun configureFillPaint(newFillModel: CanvasFill): Paint =
      getOrCreateFillPaint()
          .apply {
            if (newFillModel.shadow != fillModel?.shadow) {
              val shadow = newFillModel.shadow
              if (shadow != null) {
                setShadowLayer(shadow.radius, shadow.dx, shadow.dy, shadow.color)
              } else {
                clearShadowLayer()
              }
            }

            if (newFillModel.blendingMode != fillModel?.blendingMode) {
              newFillModel.blendingMode.applyTo(this)
            }

            if (newFillModel.shading != fillModel?.shading) {
              when (val shading = newFillModel.shading) {
                is CanvasSolidColorShading -> {
                  if (shader != null) {
                    shader = null
                  }
                  if (color != shading.color) {
                    color = shading.color
                  }
                }
                is CanvasGradientShading -> {
                  // The paint's alpha modulates the shader; the gradient colors carry their own.
                  if (alpha != OPAQUE) {
                    alpha = OPAQUE
                  }
                  shader = shading.gradient.toShader()
                }
              }
            }
          }
          .also { fillModel = newFillModel }

  internal fun configureStrokePaint(newStrokeModel: CanvasStroke): Paint =
      getOrCreateStrokePaint()
          .apply {
            if (newStrokeModel.shadow != strokeModel?.shadow) {
              val shadow = newStrokeModel.shadow
              if (shadow != null) {
                setShadowLayer(shadow.radius, shadow.dx, shadow.dy, shadow.color)
              } else {
                clearShadowLayer()
              }
            }

            if (newStrokeModel.blendingMode != strokeModel?.blendingMode) {
              newStrokeModel.blendingMode.applyTo(this)
            }

            if (newStrokeModel.shading != strokeModel?.shading) {
              when (val shading = newStrokeModel.shading) {
                is CanvasSolidColorShading -> {
                  if (shader != null) {
                    shader = null
                  }
                  if (color != shading.color) {
                    color = shading.color
                  }
                }
                is CanvasGradientShading -> {
                  // The paint's alpha modulates the shader; the gradient colors carry their own.
                  if (alpha != OPAQUE) {
                    alpha = OPAQUE
                  }
                  shader = shading.gradient.toShader()
                }
              }
            }

            if (
                newStrokeModel.lineWidth != strokeModel?.lineWidth &&
                    strokeWidth != newStrokeModel.lineWidth
            ) {
              strokeWidth = newStrokeModel.lineWidth
            }

            if (
                newStrokeModel.miterLimit != strokeModel?.miterLimit &&
                    strokeMiter != newStrokeModel.miterLimit
            ) {
              strokeMiter = newStrokeModel.miterLimit
            }

            if (newStrokeModel.lineCap != strokeModel?.lineCap) {
              newStrokeModel.lineCap.applyTo(this)
            }

            if (newStrokeModel.lineJoin != strokeModel?.lineJoin) {
              newStrokeModel.lineJoin.applyTo(this)
            }

            if (newStrokeModel.dashLengths != null) {
              val dashLengths = newStrokeModel.dashLengths
              if (
                  !dashLengths.contentEquals(strokeModel?.dashLengths) ||
                      newStrokeModel.dashPhase != strokeModel?.dashPhase
              ) {
                pathEffect = DashPathEffect(dashLengths, newStrokeModel.dashPhase)
              }
            } else if (pathEffect != null) {
              pathEffect = null
            }
          }
          .also { strokeModel = newStrokeModel }

  internal fun configureLayerPaint(newLayerModel: CanvasLayer): Paint =
      getOrCreateLayerPaint()
          .apply {
            if (newLayerModel.alpha != prevLayerModel?.alpha) {
              val newAlpha = (newLayerModel.alpha * 255f).toInt()
              if (alpha != newAlpha) {
                alpha = newAlpha
              }
            }
            if (newLayerModel.blendingMode != prevLayerModel?.blendingMode) {
              newLayerModel.blendingMode.applyTo(this)
            }
          }
          .also { prevLayerModel = newLayerModel }

  private fun getOrCreateFillPaint(): Paint {
    return fillPaint ?: createPaint().apply { style = Paint.Style.FILL }.also { fillPaint = it }
  }

  private fun getOrCreateStrokePaint(): Paint {
    return strokePaint
        ?: createPaint().apply { style = Paint.Style.STROKE }.also { strokePaint = it }
  }

  private fun getOrCreateLayerPaint(): Paint {
    return layerPaint ?: createPaint().also { layerPaint = it }
  }

  private fun createPaint(): Paint {
    return Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
  }

  companion object {
    const val DEFAULT_MATRIX_POOL_SIZE = 5
    const val DEFAULT_PATH_CACHE_SIZE = 10
    private const val OPAQUE = 255
  }
}
