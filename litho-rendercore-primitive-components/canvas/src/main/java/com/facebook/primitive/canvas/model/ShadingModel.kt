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

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ColorInt
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.geometry.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

sealed interface CanvasShadingModel

/**
 * A definition for a solid color shading.
 *
 * @property color The color (including alpha)
 */
@DataClassGenerate
data class CanvasSolidColorShading(@param:ColorInt val color: Int) : CanvasShadingModel

/**
 * A definition for a gradient shading.
 *
 * @property gradient The gradient definition
 */
@DataClassGenerate
data class CanvasGradientShading(val gradient: CanvasGradientModel) : CanvasShadingModel

sealed interface CanvasGradientModel {
  /**
   * The shader for this gradient. A degenerate gradient ([isDegenerate]) gets a shader that paints
   * its [CanvasGradient.fallbackColor], so this never builds a shader Android rejects at draw time.
   */
  fun toShader(): Shader

  /**
   * Whether this gradient paints one color: Android or Skia builds no shader from it, or Skia
   * paints it in one color. That is fewer than two stops with finite positions, a non-finite or
   * nearly zero size, a non-finite position or transform, or radial circles Skia rejects.
   */
  fun isDegenerate(): Boolean
}

/**
 * A definition for a linear gradient that varies along the line defined by the provided starting
 * and ending points.
 *
 * @property gradient The colors and positions of the gradient
 * @property startPoint The start of the gradient line
 * @property endPoint The end of the gradient line
 * @property tileMode The tiling mode
 * @property transform An optional shader-local transform: the gradient is defined in its own space
 *   and mapped to the drawing's coordinate space by this transform
 */
@DataClassGenerate
data class CanvasLinearGradient
@JvmOverloads
constructor(
    val gradient: CanvasGradient,
    val startPoint: Point,
    val endPoint: Point,
    val tileMode: Shader.TileMode,
    val transform: CanvasTransformModel? = null,
) : CanvasGradientModel {
  override fun toShader(): Shader {
    val stops = gradient.validStops()
    val localMatrix = transform?.toLocalMatrix()
    if (stops == null || localMatrix?.isUsable() == false || hasDegenerateGeometry()) {
      return solidShader(gradient.fallbackColor())
    }
    return LinearGradient(
        startPoint.x,
        startPoint.y,
        endPoint.x,
        endPoint.y,
        stops.colors,
        stops.positions,
        tileMode,
    )
        .withLocalMatrix(localMatrix)
  }

  override fun isDegenerate(): Boolean =
      gradient.validStops() == null ||
          transform?.toLocalMatrix()?.isUsable() == false ||
          hasDegenerateGeometry()

  private fun hasDegenerateGeometry(): Boolean {
    if (!startPoint.isFinite() || !endPoint.isFinite()) {
      return true
    }
    // Skia rejects a gradient vector whose length overflows a float, and paints a nearly zero one
    // in its last color.
    val length = hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y)
    if (!length.isFinite() || length <= DEGENERATE_THRESHOLD) {
      return true
    }
    return !unitMapIsFinite(length, startPoint.x, startPoint.y, endPoint.x, endPoint.y)
  }
}

/**
 * A definition for a radial gradient given the center and radius.
 *
 * When [startCenter] is set, the gradient is two-point conical: it interpolates from the circle at
 * [startCenter] with [startRadius] to the circle at [center] with [radius] (the SVG focal point
 * model). A start circle that is not inside the end circle draws a cone, as in SVG 2: it is not
 * clamped into the end circle, and the area outside the cone is not painted. Two-point conical
 * gradients need API 31; on older versions they fall back to a simple radial gradient at [center],
 * ignoring the start circle as upstream react-native-svg does on Android, and report a warning once
 * per process.
 *
 * @property gradient The colors and positions of the gradient
 * @property center The center of the radius
 * @property radius The radius of the circle for this gradient. A radius that is not a positive
 *   finite number makes the gradient degenerate (it paints its last color, as SVG does for
 *   `r="0"`), and so do the circles Skia builds no shader for: two whose radii are both nearly zero
 *   (at most 1/4096), or nearly equal with nearly the same center
 * @property tileMode The tiling mode
 * @property transform An optional shader-local transform: the gradient is defined in its own space
 *   and mapped to the drawing's coordinate space by this transform
 * @property startCenter The center of the start circle of a two-point conical gradient
 * @property startRadius The radius of the start circle; only used with [startCenter]. Negative and
 *   non-finite values are treated as 0
 */
@DataClassGenerate
data class CanvasRadialGradient
@JvmOverloads
constructor(
    val gradient: CanvasGradient,
    val center: Point,
    val radius: Float,
    val tileMode: Shader.TileMode,
    val transform: CanvasTransformModel? = null,
    val startCenter: Point? = null,
    val startRadius: Float = 0f,
) : CanvasGradientModel {
  override fun toShader(): Shader {
    val stops = gradient.validStops()
    val localMatrix = transform?.toLocalMatrix()
    if (stops == null || localMatrix?.isUsable() == false || hasDegenerateGeometry()) {
      return solidShader(gradient.fallbackColor())
    }
    val start = startCenter
    val shader =
        if (start == null) {
          RadialGradient(center.x, center.y, radius, stops.colors, stops.positions, tileMode)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          RadialGradient(
              start.x,
              start.y,
              fromRadius(),
              center.x,
              center.y,
              radius,
              LongArray(stops.colors.size) { Color.pack(stops.colors[it]) },
              stops.positions,
              tileMode,
          )
        } else {
          reportConicalFallback()
          RadialGradient(center.x, center.y, radius, stops.colors, stops.positions, tileMode)
        }
    return shader.withLocalMatrix(localMatrix)
  }

  override fun isDegenerate(): Boolean =
      gradient.validStops() == null ||
          transform?.toLocalMatrix()?.isUsable() == false ||
          hasDegenerateGeometry()

  private fun hasDegenerateGeometry(): Boolean {
    if (!radius.isFinite() || radius <= 0f || !center.isFinite()) {
      return true
    }
    val start = startCenter
    if (start == null) {
      // Skia paints a simple radial gradient with a nearly zero radius in its last color.
      return radius <= DEGENERATE_THRESHOLD || !unitMapIsFinite(radius, center.x, center.y)
    }
    if (!start.isFinite()) {
      return true
    }
    val fromRadius = fromRadius()
    if (radius <= NEARLY_ZERO_RADIUS && fromRadius <= NEARLY_ZERO_RADIUS) {
      return true
    }
    val distance = hypot(start.x - center.x, start.y - center.y)
    if (!distance.isFinite()) {
      return true
    }
    if (distance <= NEARLY_ZERO_RADIUS) {
      // Skia treats circles this close as concentric, and builds nothing for two equal ones.
      return abs(radius - fromRadius) <= NEARLY_ZERO_RADIUS ||
          !unitMapIsFinite(max(radius, fromRadius), center.x, center.y, start.x, start.y)
    }
    // Skia maps the centers to (0, 0) and (1, 0) and scales the radii by that map.
    return !unitMapIsFinite(distance, center.x, center.y, start.x, start.y, radius, fromRadius)
  }

  private fun fromRadius(): Float =
      if (startRadius.isFinite()) startRadius.coerceAtLeast(0f) else 0f

  private companion object {
    /** Skia's `SK_ScalarNearlyZero`. */
    const val NEARLY_ZERO_RADIUS: Float = 1f / 4096f

    @Volatile private var conicalFallbackReported = false

    fun reportConicalFallback() {
      if (conicalFallbackReported) {
        return
      }
      conicalFallbackReported = true
      ErrorReporter.report(
          LogLevel.WARNING,
          "com.facebook.primitive.canvas.model.CanvasRadialGradient",
          "Two-point conical gradients need API 31; drawing a simple radial gradient instead",
      )
    }
  }
}

/** The largest scale whose square is a float with room to spare. */
private const val MAX_UNIT_MAP_SCALE: Float = 1e18f

/** Skia's `kDegenerateThreshold`: a nearly zero gradient length or radius. */
private const val DEGENERATE_THRESHOLD: Float = 1f / (1 shl 15)

/** A shader that paints one color everywhere. */
private fun solidShader(@ColorInt color: Int): Shader =
    LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP)

private fun Point.isFinite(): Boolean = x.isFinite() && y.isFinite()

private fun CanvasTransformModel.toLocalMatrix(): Matrix = Matrix().also { applyTo(it) }

/** Skia builds no gradient shader with a local matrix it cannot invert. */
private fun Matrix.isUsable(): Boolean {
  val values = FloatArray(9)
  getValues(values)
  return values.all { it.isFinite() } && invert(Matrix())
}

/**
 * Skia maps a gradient to a unit space by dividing its coordinates by [unit] (the gradient's
 * length, radius or center distance), and builds no shader when that map, or its square, overflows
 * a float.
 */
private fun unitMapIsFinite(unit: Float, vararg coordinates: Float): Boolean {
  var largest = 0f
  for (coordinate in coordinates) {
    largest = max(largest, abs(coordinate))
  }
  return largest / unit <= MAX_UNIT_MAP_SCALE
}

/** Colors and positions Android's gradient constructors accept. */
internal class ValidStops(@param:ColorInt val colors: IntArray, val positions: FloatArray?)

/**
 * The stops Android and Skia accept: stops whose position is not finite are dropped, positions are
 * clamped to [0, 1] and raised to the previous stop's, the two arrays are cut to the shorter one,
 * and at least two stops must remain. Null when fewer remain.
 */
internal fun CanvasGradient.validStops(): ValidStops? {
  val positions = positions ?: return if (colors.size >= 2) ValidStops(colors, null) else null
  if (colors.size == positions.size && colors.size >= 2 && positions.isValid()) {
    return ValidStops(colors, positions)
  }
  val count = min(colors.size, positions.size)
  val keptColors = IntArray(count)
  val keptPositions = FloatArray(count)
  var kept = 0
  var previous = 0f
  for (i in 0 until count) {
    val position = positions[i]
    if (!position.isFinite()) {
      continue
    }
    previous = position.coerceIn(previous, 1f)
    keptColors[kept] = colors[i]
    keptPositions[kept] = previous
    kept++
  }
  return if (kept >= 2) ValidStops(keptColors.copyOf(kept), keptPositions.copyOf(kept)) else null
}

private fun FloatArray.isValid(): Boolean {
  var previous = 0f
  for (position in this) {
    if (!position.isFinite() || position < previous || position > 1f) {
      return false
    }
    previous = position
  }
  return true
}

/**
 * The color a degenerate gradient paints: its last stop with a finite position (as SVG paints a
 * gradient with `r="0"`), else its last color, else transparent.
 */
@ColorInt
fun CanvasGradient.fallbackColor(): Int {
  val positions = positions ?: return colors.lastOrNull() ?: Color.TRANSPARENT
  for (i in min(colors.size, positions.size) - 1 downTo 0) {
    if (positions[i].isFinite()) {
      return colors[i]
    }
  }
  return colors.lastOrNull() ?: Color.TRANSPARENT
}

private fun Shader.withLocalMatrix(localMatrix: Matrix?): Shader {
  if (localMatrix != null) {
    setLocalMatrix(localMatrix)
  }
  return this
}

/**
 * A definition for a smooth transition between colors for drawing gradients.
 *
 * @property colors The sRGB colors (including alpha) to be distributed along the gradient
 * @property positions The relative positions [0..1] of each corresponding color in the colors
 *   array. If this is null, the the colors are distributed evenly along the gradient
 */
@DataClassGenerate
data class CanvasGradient(@param:ColorInt val colors: IntArray, val positions: FloatArray?) {
  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }
    if (javaClass != other?.javaClass) {
      return false
    }

    other as CanvasGradient

    if (!colors.contentEquals(other.colors)) {
      return false
    }
    if (positions != null) {
      if (other.positions == null) {
        return false
      }
      if (!positions.contentEquals(other.positions)) {
        return false
      }
    } else if (other.positions != null) {
      return false
    }

    return true
  }

  override fun hashCode(): Int {
    var result = colors.contentHashCode()
    result = 31 * result + (positions?.contentHashCode() ?: 0)
    return result
  }
}
