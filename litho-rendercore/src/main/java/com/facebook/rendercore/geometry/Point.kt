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

import android.annotation.SuppressLint
import com.facebook.rendercore.utils.pack2Floats

@SuppressLint("HexColorValueUsage")
fun Point(x: Float, y: Float): Point {
  return Point(pack2Floats(x, y))
}

@SuppressLint("HexColorValueUsage")
fun Position(x: Float, y: Float): Point {
  return Point(pack2Floats(x, y))
}

@SuppressLint("HexColorValueUsage")
@JvmInline
value class Point internal constructor(@PublishedApi internal val packedValue: Long) {
  val x: Float
    get() {
      return Float.fromBits(packedValue.shr(32).toInt())
    }

  val y: Float
    get() {
      return Float.fromBits(packedValue.and(0xFFFFFFFF).toInt())
    }

  operator fun minus(other: Point): Point = Point(x - other.x, y - other.y)

  operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)

  operator fun times(operand: Float): Point = Point(x * operand, y * operand)

  operator fun div(operand: Float): Point = Point(x / operand, y / operand)

  override fun toString(): String = "Point{x=${x}, y=${y}}"

  companion object {
    /**
     * An Point with { x = 0, y = 0 }
     *
     * This can be used to represent the origin of a coordinate space.
     */
    val Zero: Point = Point(0.0f, 0.0f)
  }
}
