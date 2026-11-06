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

package com.facebook.primitive.canvas.model

import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.RectF
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.primitive.canvas.CanvasState
import com.facebook.primitive.canvas.addArc
import com.facebook.primitive.utils.types.FillRule
import com.facebook.rendercore.geometry.Point
import com.facebook.rendercore.geometry.Size

sealed interface CanvasPathModel : CanvasShape {
  fun toAndroidPath(state: CanvasState): Path
}

sealed interface CanvasPathChildModel {
  fun applyTo(androidPath: Path, state: CanvasState)
}

/**
 * The number of [CanvasState] path cache entries used to create this path: the path itself and the
 * sources of its [CanvasPathAdd] children.
 */
internal fun CanvasPathModel.cachedPathCount(): Int =
    when (this) {
      is CanvasPath -> countCachedPaths()
    }

/**
 * A definition for a path which contains a mathematical description of shapes or lines to be drawn
 * on a Canvas.
 *
 * @property fillRule The path's fill type. Defines how "inside" is computed.
 * @property children child path definitions that will be added or applied on the path in the same
 *   order as they're specified
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPath(
    private val fillRule: FillRule,
    private val children: List<CanvasPathChildModel>,
) : CanvasPathModel {
  // Paths are cache keys, hashed on every draw. The model is immutable, so the deep hash of the
  // children is computed once.
  private val cachedHashCode: Int = 31 * fillRule.hashCode() + children.hashCode()

  override fun toAndroidPath(state: CanvasState): Path {
    return Path().apply {
      fillRule.applyTo(this)
      for (i in children.indices) {
        children[i].applyTo(this, state)
      }
    }
  }

  override fun hashCode(): Int = cachedHashCode

  internal fun countCachedPaths(): Int {
    var count = 1
    for (i in children.indices) {
      val child = children[i]
      if (child is CanvasPathAdd) {
        count += child.countCachedPaths()
      }
    }
    return count
  }
}

/**
 * A definition for a child path which sets the beginning of the next contour to the point (x,y).
 *
 * @property point The start point of a new contour
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathMoveTo(private val point: Point) : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.moveTo(point.x, point.y)
  }
}

/**
 * A definition for a child path which adds a line from the last point to the specified point (x,y).
 * If no [CanvasPathMoveTo] children has been added to this Path, then the first point is
 * automatically set to (0,0).
 *
 * @property point The end point of a line
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathLineTo(private val point: Point) : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.lineTo(point.x, point.y)
  }
}

/**
 * A definition for a child path which adds a quadratic bezier from the last point, approaching
 * control point (controlPoint.x,controlPoint.y), and ending at (endPoint.x,endPoint.y). If no
 * [CanvasPathMoveTo] children has been added to this Path, then the first point is automatically
 * set to (0,0).
 *
 * @property controlPoint The control point on a quadratic curve
 * @property endPoint The end point on a quadratic curve
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathQuadTo(private val controlPoint: Point, private val endPoint: Point) :
    CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.quadTo(controlPoint.x, controlPoint.y, endPoint.x, endPoint.y)
  }
}

/**
 * A definition for a child path which adds a cubic bezier from the last point, approaching control
 * points (controlPoint1.x,controlPoint1.y) and (controlPoint2.x,controlPoint2.y), and ending at
 * (endPoint.x,endPoint.y). If no [CanvasPathMoveTo] children has been added to this Path, then the
 * first point is automatically set to (0,0).
 *
 * @property controlPoint1 The 1st control point on a cubic curve
 * @property controlPoint2 The 2nd control point on a cubic curve
 * @property endPoint The end point on a cubic curve
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathCubicTo(
    private val controlPoint1: Point,
    private val controlPoint2: Point,
    private val endPoint: Point,
) : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.cubicTo(
        controlPoint1.x,
        controlPoint1.y,
        controlPoint2.x,
        controlPoint2.y,
        endPoint.x,
        endPoint.y,
    )
  }
}

/**
 * A definition for a child path which adds the specified arc to the path as a new contour.
 *
 * @property center The center of the arc
 * @property radius The radius of the arc
 * @property startDegrees The angle to the starting point of the arc
 * @property endDegrees The angle to the end point of the arc
 * @property clockwise true to make a clockwise arc; false to make a counterclockwise arc
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathArc(
    private val center: Point,
    private val radius: Float,
    private val startDegrees: Float,
    private val endDegrees: Float,
    private val clockwise: Boolean,
) : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.addArc(center, radius, startDegrees, endDegrees, clockwise)
  }
}

/**
 * A definition for a child path which adds a closed (optionally) round-rectangle contour.
 *
 * @property topLeft The coordinates of the top left point of the rectangle
 * @property size The size of the rectangle
 * @property cornerRadius The radius of the rounded corners
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathRect(
    private val topLeft: Point,
    private val size: Size,
    private val cornerRadius: Float,
) : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.addRoundRect(
        RectF(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height),
        cornerRadius,
        cornerRadius,
        Path.Direction.CW,
    )
  }
}

/**
 * A definition for a child path which adds closed ellipse contour to the contour.
 *
 * @property topLeft The coordinates of the top left point of the ellipse
 * @property size The size of the ellipse
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathEllipse(private val topLeft: Point, private val size: Size) :
    CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.addOval(
        RectF(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height),
        Path.Direction.CW,
    )
  }
}

/**
 * A definition for a child path which adds a closed circle contour to the contour.
 *
 * @property center The center of the circle
 * @property radius The radius of the circle
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathCircle(private val center: Point, private val radius: Float) :
    CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.addCircle(center.x, center.y, radius, Path.Direction.CW)
  }
}

/**
 * A definition for a child path which closes the current contour. If the current point is not equal
 * to the first point of the contour, a line segment is automatically added.
 */
object CanvasPathClose : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    androidPath.close()
  }
}

/**
 * A definition for a child path which adds a copy of src path to the current path, transformed by
 * transformModel.
 *
 * @property src The path to add as a new contour to the current path
 * @property transformModel The transform that will be applied to the src before adding it to the
 *   current path
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathAdd(
    private val src: CanvasPathModel,
    private val transformModel: CanvasTransformModel,
) : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    val srcPath = state.getOrCreatePath(src)
    state.getMatrix(transformModel) { matrix -> androidPath.addPath(srcPath, matrix) }
  }

  internal fun countCachedPaths(): Int = src.cachedPathCount()
}
