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

package com.facebook.litho.drawable

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * A comparable drawable that fills the circle `Shape.Circle` clips to: the circle of diameter
 * `min(width, height)` centered in its bounds.
 */
@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
class ComparableCircleDrawable private constructor(@ColorInt val color: Int) :
    Drawable(), ComparableDrawable {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
  private val circleBounds = Rect()
  private var drawableAlpha = 0xFF
  private var tintList: ColorStateList? = null
  private var tintMode: PorterDuff.Mode? = PorterDuff.Mode.SRC_IN
  private var tintFilter: PorterDuffColorFilter? = null

  override fun draw(canvas: Canvas) {
    val colorFilter = paint.colorFilter
    if (colorFilter == null) {
      paint.colorFilter = tintFilter
    }
    val circle = inscribedSquare(bounds, circleBounds)
    try {
      canvas.drawCircle(circle.exactCenterX(), circle.exactCenterY(), circle.width() / 2f, paint)
    } finally {
      paint.colorFilter = colorFilter
    }
  }

  override fun getOutline(outline: Outline) {
    outline.setOval(inscribedSquare(bounds, circleBounds))
    outline.alpha = paint.alpha / 255f
  }

  override fun setAlpha(alpha: Int) {
    if (alpha == drawableAlpha) {
      return
    }
    drawableAlpha = alpha
    // Scales the color's own alpha, as ColorDrawable and GradientDrawable do.
    paint.alpha = Color.alpha(color) * (alpha + (alpha ushr 7)) ushr 8
    invalidateSelf()
  }

  override fun getAlpha(): Int = drawableAlpha

  override fun setColorFilter(colorFilter: ColorFilter?) {
    paint.colorFilter = colorFilter
    invalidateSelf()
  }

  override fun getColorFilter(): ColorFilter? = paint.colorFilter

  override fun setTintList(tint: ColorStateList?) {
    tintList = tint
    updateTintFilter(state)
    invalidateSelf()
  }

  override fun setTintMode(tintMode: PorterDuff.Mode?) {
    this.tintMode = tintMode
    updateTintFilter(state)
    invalidateSelf()
  }

  override fun isStateful(): Boolean = tintList?.isStateful == true || super.isStateful()

  override fun onStateChange(state: IntArray): Boolean {
    if (tintList == null || tintMode == null) {
      return false
    }
    updateTintFilter(state)
    invalidateSelf()
    return true
  }

  private fun updateTintFilter(state: IntArray) {
    val tint = tintList
    val mode = tintMode
    tintFilter =
        if (tint != null && mode != null) {
          PorterDuffColorFilter(tint.getColorForState(state, tint.defaultColor), mode)
        } else {
          null
        }
  }

  @Deprecated("Deprecated in Java")
  override fun getOpacity(): Int =
      if (paint.alpha == 0) PixelFormat.TRANSPARENT else PixelFormat.TRANSLUCENT

  override fun isEquivalentTo(other: ComparableDrawable): Boolean {
    return equals(other)
  }

  /**
   * Compares [color] and the effective alpha only, not tint or color filter, like
   * [ComparableColorDrawable] and [ComparableGradientDrawable].
   */
  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }
    if (other !is ComparableCircleDrawable) {
      return false
    }
    return color == other.color && paint.alpha == other.paint.alpha
  }

  override fun hashCode(): Int = 31 * color + paint.alpha

  companion object {

    @JvmStatic
    fun create(@ColorInt color: Int): ComparableCircleDrawable = ComparableCircleDrawable(color)
  }
}

/**
 * Sets [out] to the square of side `min(width, height)` centered in [box], the square
 * `Shape.Circle` is inscribed in, and returns it. [out] may be [box].
 */
internal fun inscribedSquare(box: Rect, out: Rect): Rect {
  val size = minOf(box.width(), box.height())
  val left = box.left + (box.width() - size) / 2
  val top = box.top + (box.height() - size) / 2
  out.set(left, top, left + size, top + size)
  return out
}
