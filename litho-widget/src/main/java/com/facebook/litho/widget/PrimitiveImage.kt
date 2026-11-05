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

import android.graphics.drawable.Drawable
import android.widget.ImageView.ScaleType
import com.facebook.litho.DrawableMatrix
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.MatrixDrawable
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.primitives.DrawableAllocator
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.px
import com.facebook.rendercore.utils.exact
import com.facebook.rendercore.utils.withAspectRatio
import kotlin.math.max

/**
 * A component to render a [Drawable].
 *
 * Tip: Use the same instance of the [drawable] to avoid remounting it in subsequent layouts to
 * improve performance.
 *
 * @param drawable The [Drawable] to render.
 * @param scaleType The [ScaleType] to scale or transform the [drawable].
 * @param useIntrinsicSize Whether to use the intrinsic size of the [drawable] for component
 *   measurement.
 */
class PrimitiveImage(
    private val drawable: Drawable?,
    private val scaleType: ScaleType = ScaleType.FIT_CENTER,
    private val useIntrinsicSize: Boolean = true,
    private val style: Style = Style,
) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    return LithoPrimitive(
        layoutBehavior = ImageLayoutBehavior(drawable, scaleType, useIntrinsicSize),
        mountBehavior =
            // start_image_primitive_mount_behavior_example
            MountBehavior(
                DrawableAllocator(poolSize = 30, canPreallocate = true) {
                  MatrixDrawable<Drawable>()
                },
            ) {
              bindWithLayoutData<PrimitiveImageLayoutData>(drawable, scaleType) {
                  content,
                  layoutData ->
                content.mount(drawable, layoutData.matrix)
                content.bind(layoutData.width, layoutData.height)
                onUnbind { content.unmount() }
              }
            }
        // end_image_primitive_mount_behavior_example
        ,
        style = style,
    )
  }

  companion object {
    /**
     * Java-compatible factory method that accepts pixel values for dimensions. Use this method when
     * calling from Java code with pixel values.
     *
     * @param drawable The [Drawable] to render.
     * @param scaleType The [ScaleType] to scale or transform the [drawable].
     * @param useIntrinsicSize Whether to use the intrinsic size of the [drawable].
     * @param widthPx Width in pixels, or null to not set.
     * @param heightPx Height in pixels, or null to not set.
     * @param style Additional style to apply, or null for no additional style.
     */
    @JvmStatic
    @JvmOverloads
    @SinceKotlin("1000.0") // make unavailable to Kotlin
    fun create(
        drawable: Drawable?,
        scaleType: ScaleType = ScaleType.FIT_CENTER,
        useIntrinsicSize: Boolean = true,
        widthPx: Int? = null,
        heightPx: Int? = null,
        style: Style? = null,
    ): PrimitiveImage {
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
      return PrimitiveImage(
          drawable = drawable,
          scaleType = scaleType,
          useIntrinsicSize = useIntrinsicSize,
          style = finalStyle,
      )
    }

    /**
     * Java-compatible factory method that accepts dp values for dimensions. Use this method when
     * calling from Java code with dp values (e.g., from .widthDip() or .heightDip() calls).
     *
     * @param drawable The [Drawable] to render.
     * @param scaleType The [ScaleType] to scale or transform the [drawable].
     * @param useIntrinsicSize Whether to use the intrinsic size of the [drawable].
     * @param widthDp Width in dp, or null to not set.
     * @param heightDp Height in dp, or null to not set.
     * @param style Additional style to apply, or null for no additional style.
     */
    @JvmStatic
    @JvmOverloads
    @SinceKotlin("1000.0") // make unavailable to Kotlin
    fun createDp(
        drawable: Drawable?,
        scaleType: ScaleType = ScaleType.FIT_CENTER,
        useIntrinsicSize: Boolean = true,
        widthDp: Float? = null,
        heightDp: Float? = null,
        style: Style? = null,
    ): PrimitiveImage {
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
      return PrimitiveImage(
          drawable = drawable,
          scaleType = scaleType,
          useIntrinsicSize = useIntrinsicSize,
          style = finalStyle,
      )
    }
  }
}

internal class ImageLayoutBehavior(
    private val drawable: Drawable?,
    private val scaleType: ScaleType,
    private val useIntrinsicSize: Boolean,
) : LayoutBehavior {
  override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {
    val result = calculateImageLayout(drawable, scaleType, useIntrinsicSize, sizeConstraints)
    return PrimitiveLayoutResult(
        width = result.width,
        height = result.height,
        layoutData = result.layoutData,
    )
  }
}

/** Image geometry shared by component and direct View mounting. */
fun calculateImageLayout(
    drawable: Drawable?,
    scaleType: ScaleType,
    useIntrinsicSize: Boolean,
    sizeConstraints: SizeConstraints,
): ImageLayoutResult {
  val exactSize = IntSize.exact(sizeConstraints)
  // Invalid intrinsic dimensions have no natural size, even if one axis is exact.
  if (
      drawable == null ||
          (exactSize == IntSize.Invalid &&
              (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0))
  ) {
    return ImageLayoutResult(0, 0, PrimitiveImageLayoutData(0, 0, null))
  }

  val intrinsicWidth = drawable.intrinsicWidth
  val intrinsicHeight = drawable.intrinsicHeight
  val computedSize =
      if (exactSize != IntSize.Invalid) {
        exactSize
      } else if (!sizeConstraints.hasBoundedWidth && !sizeConstraints.hasBoundedHeight) {
        IntSize(intrinsicWidth, intrinsicHeight)
      } else {
        val aspectRatio = intrinsicWidth.toFloat() / intrinsicHeight.toFloat()
        if (useIntrinsicSize) {
          IntSize.withAspectRatio(sizeConstraints, aspectRatio, intrinsicWidth, intrinsicHeight)
        } else {
          IntSize.withAspectRatio(sizeConstraints, aspectRatio)
        }
      }

  val size = IntSize(max(0, computedSize.width), max(0, computedSize.height))
  val useLayoutSize = scaleType == ScaleType.FIT_XY || intrinsicWidth <= 0 || intrinsicHeight <= 0
  val matrix =
      if (useLayoutSize) null
      else DrawableMatrix.create(drawable, scaleType, size.width, size.height)

  return ImageLayoutResult(
      size.width,
      size.height,
      PrimitiveImageLayoutData(
          if (useLayoutSize) size.width else intrinsicWidth,
          if (useLayoutSize) size.height else intrinsicHeight,
          matrix,
      ),
  )
}

class ImageLayoutResult(val width: Int, val height: Int, val layoutData: PrimitiveImageLayoutData)

/**
 * The layout data required by the [Image] Primitive to mount, and bind the drawable in the host.
 */
class PrimitiveImageLayoutData(val width: Int, val height: Int, val matrix: DrawableMatrix? = null)
