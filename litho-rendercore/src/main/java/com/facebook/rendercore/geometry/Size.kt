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

package com.facebook.rendercore.geometry

import android.graphics.Rect
import com.facebook.rendercore.utils.UnspecifiedPackedFloats
import com.facebook.rendercore.utils.pack2Floats
import com.facebook.rendercore.utils.toStringAsFixed
import com.facebook.rendercore.utils.unpackAbsFloat1
import com.facebook.rendercore.utils.unpackAbsFloat2
import com.facebook.rendercore.utils.unpackFloat1
import com.facebook.rendercore.utils.unpackFloat2
import kotlin.math.max
import kotlin.math.min

/** Constructs a [Size] from the given width and height */
fun Size(width: Float, height: Float) = Size(pack2Floats(width, height))

/**
 * Holds a 2D floating-point size.
 *
 * You can think of this as an [Offset] from the origin.
 *
 * To create a [Size], call the top-level function that accepts a width/height pair of dimensions:
 * ```
 * val size = Size(width, height)
 * ```
 *
 * The primary constructor of [Size] is intended to be used with the [packedValue] property to allow
 * storing sizes in arrays or collections of primitives without boxing.
 *
 * @param packedValue [Long] value encoding the [width] and [height] components of the [Size].
 *   Encoded values can be obtained by using the [packedValue] property of existing [Size]
 *   instances.
 */
@JvmInline
value class Size(val packedValue: Long) {

  val width: Float
    get() = unpackFloat1(packedValue)

  val height: Float
    get() = unpackFloat2(packedValue)

  operator fun component1(): Float = width

  operator fun component2(): Float = height

  /** Returns a copy of this Size instance optionally overriding the width or height parameter */
  fun copy(
      width: Float = unpackFloat1(packedValue),
      height: Float = unpackFloat2(packedValue),
  ): Size = Size(pack2Floats(width, height))

  companion object {
    /** An empty size, one with a zero width and a zero height. */
    val Zero: Size = Size(0x0L)

    /**
     * A size whose [width] and [height] are unspecified. This is a sentinel value used to
     * initialize a non-null parameter. Access to width or height on an unspecified size is not
     * allowed.
     */
    val Unspecified: Size = Size(UnspecifiedPackedFloats)
  }

  /**
   * Whether this size encloses a non-zero area.
   *
   * Negative areas are considered empty.
   */
  fun isValid(): Boolean {
    return isUnspecified or (width <= 0f) or (height <= 0f)
  }

  /**
   * Multiplication operator.
   *
   * Returns a [Size] whose dimensions are the dimensions of the left-hand-side operand (a [Size])
   * multiplied by the scalar right-hand-side operand (a [Float]).
   */
  operator fun times(operand: Float): Size =
      Size(pack2Floats(unpackFloat1(packedValue) * operand, unpackFloat2(packedValue) * operand))

  /**
   * Division operator.
   *
   * Returns a [Size] whose dimensions are the dimensions of the left-hand-side operand (a [Size])
   * divided by the scalar right-hand-side operand (a [Float]).
   */
  operator fun div(operand: Float): Size =
      Size(pack2Floats(unpackFloat1(packedValue) / operand, unpackFloat2(packedValue) / operand))

  /** The lesser of the magnitudes of the [width] and the [height]. */
  val minDimension: Float
    get() = min(unpackAbsFloat1(packedValue), unpackAbsFloat2(packedValue))

  /** The greater of the magnitudes of the [width] and the [height]. */
  val maxDimension: Float
    get() = max(unpackAbsFloat1(packedValue), unpackAbsFloat2(packedValue))

  override fun toString(): String =
      if (isSpecified) {
        "Size(${width.toStringAsFixed(1)}, ${height.toStringAsFixed(1)})"
      } else {
        // In this case reading the width or height properties will throw, and they don't
        // contain meaningful values as strings anyway.
        "Size.Unspecified"
      }
}

/** `false` when this is [Size.Unspecified]. */
val Size.isSpecified: Boolean
  get() = packedValue != 0x7fc00000_7fc00000L // NaN_NaN, see UnspecifiedPackedFloats

/** `true` when this is [Size.Unspecified]. */
val Size.isUnspecified: Boolean
  get() = packedValue == 0x7fc00000_7fc00000L // NaN_NaN, see UnspecifiedPackedFloats

/**
 * If this [Size] [isSpecified] then this is returned, otherwise [block] is executed and its result
 * is returned.
 */
inline fun Size.takeOrElse(block: () -> Size): Size = if (isSpecified) this else block()

/** Returns a [Size] with [size]'s [Size.width] and [Size.height] multiplied by [this] */
operator fun Int.times(size: Size): Size = size * this.toFloat()

/** Returns a [Size] with [size]'s [Size.width] and [Size.height] multiplied by [this] */
operator fun Double.times(size: Size): Size = size * this.toFloat()

/** Returns a [Size] with [size]'s [Size.width] and [Size.height] multiplied by [this] */
operator fun Float.times(size: Size): Size = size * this

/** Convert a [Size] to a [Rect]. */
fun Size.toRect(): Rect = Rect(0, 0, this.width.toInt(), this.height.toInt())

/** Returns the [Point] of the center of the rect from the point of [0, 0] with this [Size]. */
val Size.center: Point
  get() = Point(unpackFloat1(packedValue) / 2f, unpackFloat2(packedValue) / 2f)
