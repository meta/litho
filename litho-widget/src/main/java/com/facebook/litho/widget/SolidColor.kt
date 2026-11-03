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

package com.facebook.litho.widget

import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.useCached
import com.facebook.rendercore.dp
import com.facebook.rendercore.px
import kotlin.math.min

/**
 * A component that renders a solid color.
 *
 * @uidocs
 * @prop color Color to be shown.
 * @prop alpha The alpha of the color, in the range [0.0, 1.0]
 */
class SolidColor(
    @param:ColorInt private val color: Int,
    private val alpha: Float = -1.0f,
    private val style: Style = Style,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val drawable =
        if (ComponentsConfiguration.cacheSolidColorDrawable) {
          useCached(color, alpha) { colorWithAlpha(color, alpha).toDrawable() }
        } else {
          useCached(color, alpha) { colorWithAlpha(color, alpha) }.toDrawable()
        }

    return PrimitiveImage(
        drawable = drawable,
        scaleType = ImageView.ScaleType.FIT_XY,
        style = style,
    )
  }

  companion object {
    @ColorInt
    private fun colorWithAlpha(@ColorInt color: Int, alpha: Float): Int =
        if (alpha >= 0f) {
          val clampedAlpha = min(1.0f, alpha)
          ColorUtils.setAlphaComponent(color, (clampedAlpha * 255f).toInt())
        } else {
          color
        }

    /**
     * Simplified Java-compatible factory method that accepts just color and style. Use this method
     * when calling from Java code that only needs to specify color and style without passing
     * explicit default values for alpha and dimensions.
     *
     * @param color The color to render.
     * @param style The style to apply.
     */
    @JvmStatic
    @SinceKotlin("1000.0") // make unavailable to Kotlin
    fun createWithStyle(@ColorInt color: Int, style: Style): SolidColor {
      return SolidColor(
          color = color,
          style = style,
      )
    }

    /**
     * Java-compatible factory method that accepts pixel values for dimensions. Use this method when
     * calling from Java code with pixel values.
     *
     * @param color The color to render.
     * @param alpha The alpha of the color, in the range [0.0, 1.0]. Use -1.0f to use color's alpha.
     * @param widthPx Width in pixels, or null to not set.
     * @param heightPx Height in pixels, or null to not set.
     * @param style Additional style to apply, or null for no additional style.
     */
    @JvmStatic
    @JvmOverloads
    @SinceKotlin("1000.0") // make unavailable to Kotlin
    fun create(
        @ColorInt color: Int,
        alpha: Float = -1.0f,
        widthPx: Int? = null,
        heightPx: Int? = null,
        style: Style? = null,
    ): SolidColor {
      val dimensionStyle =
          when {
            widthPx != null && heightPx != null -> Style.width(widthPx.px).height(heightPx.px)
            widthPx != null -> Style.width(widthPx.px)
            heightPx != null -> Style.height(heightPx.px)
            else -> null
          }
      val finalStyle =
          when {
            dimensionStyle != null && style != null -> dimensionStyle + style
            dimensionStyle != null -> dimensionStyle
            style != null -> style
            else -> Style
          }
      return SolidColor(
          color = color,
          alpha = alpha,
          style = finalStyle,
      )
    }

    /**
     * Java-compatible factory method that accepts dp values for dimensions. Use this method when
     * calling from Java code with dp values (e.g., from .widthDip() or .heightDip() calls).
     *
     * @param color The color to render.
     * @param alpha The alpha of the color, in the range [0.0, 1.0]. Use -1.0f to use color's alpha.
     * @param widthDp Width in dp, or null to not set.
     * @param heightDp Height in dp, or null to not set.
     * @param style Additional style to apply, or null for no additional style.
     */
    @JvmStatic
    @JvmOverloads
    @SinceKotlin("1000.0") // make unavailable to Kotlin
    fun createDp(
        @ColorInt color: Int,
        alpha: Float = -1.0f,
        widthDp: Float? = null,
        heightDp: Float? = null,
        style: Style? = null,
    ): SolidColor {
      val dimensionStyle =
          when {
            widthDp != null && heightDp != null -> Style.width(widthDp.dp).height(heightDp.dp)
            widthDp != null -> Style.width(widthDp.dp)
            heightDp != null -> Style.height(heightDp.dp)
            else -> null
          }
      val finalStyle =
          when {
            dimensionStyle != null && style != null -> dimensionStyle + style
            dimensionStyle != null -> dimensionStyle
            style != null -> style
            else -> Style
          }
      return SolidColor(
          color = color,
          alpha = alpha,
          style = finalStyle,
      )
    }
  }
}
