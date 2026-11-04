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

package com.facebook.litho.core

import androidx.annotation.AttrRes
import androidx.annotation.FloatRange
import androidx.annotation.StyleRes
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.CommonProps
import com.facebook.litho.ComponentContext
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.px
import com.facebook.yoga.YogaEdge

/** Enums for [CoreDimenStyleItem]. */
@PublishedApi
internal enum class CoreDimenField : StyleItemField {
  WIDTH,
  WIDTH_AUTO,
  HEIGHT,
  HEIGHT_AUTO,
  MIN_WIDTH,
  MAX_WIDTH,
  MIN_HEIGHT,
  MAX_HEIGHT,
  PADDING_START,
  PADDING_TOP,
  PADDING_END,
  PADDING_BOTTOM,
  PADDING_LEFT,
  PADDING_RIGHT,
  PADDING_HORIZONTAL,
  PADDING_VERTICAL,
  PADDING_ALL,
  MARGIN_START,
  MARGIN_TOP,
  MARGIN_END,
  MARGIN_BOTTOM,
  MARGIN_LEFT,
  MARGIN_RIGHT,
  MARGIN_HORIZONTAL,
  MARGIN_VERTICAL,
  MARGIN_ALL,
}

/** Enums for [CoreFloatStyleItem]. */
@PublishedApi
internal enum class CoreFloatField : StyleItemField {
  WIDTH_PERCENT,
  HEIGHT_PERCENT,
  MIN_WIDTH_PERCENT,
  MAX_WIDTH_PERCENT,
  MIN_HEIGHT_PERCENT,
  MAX_HEIGHT_PERCENT,
  MARGIN_ALL_PERCENT,
  MARGIN_START_PERCENT,
  MARGIN_TOP_PERCENT,
  MARGIN_END_PERCENT,
  MARGIN_BOTTOM_PERCENT,
  MARGIN_LEFT_PERCENT,
  MARGIN_RIGHT_PERCENT,
  MARGIN_HORIZONTAL_PERCENT,
  MARGIN_VERTICAL_PERCENT,
  PADDING_ALL_PERCENT,
  PADDING_START_PERCENT,
  PADDING_TOP_PERCENT,
  PADDING_END_PERCENT,
  PADDING_BOTTOM_PERCENT,
  PADDING_LEFT_PERCENT,
  PADDING_RIGHT_PERCENT,
  PADDING_HORIZONTAL_PERCENT,
  PADDING_VERTICAL_PERCENT,
}

/** Common style item for all core dimen styles. See note on [CoreDimenField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class CoreDimenStyleItem(
    override val field: CoreDimenField,
    override val value: Dimen,
) : StyleItem<Dimen> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    val pixelValue = value.toPixels(context.resourceResolver)
    when (field) {
      CoreDimenField.WIDTH -> commonProps.widthPx(pixelValue)
      CoreDimenField.HEIGHT -> commonProps.heightPx(pixelValue)
      CoreDimenField.MIN_WIDTH -> commonProps.minWidthPx(pixelValue)
      CoreDimenField.MAX_WIDTH -> commonProps.maxWidthPx(pixelValue)
      CoreDimenField.MIN_HEIGHT -> commonProps.minHeightPx(pixelValue)
      CoreDimenField.MAX_HEIGHT -> commonProps.maxHeightPx(pixelValue)
      CoreDimenField.PADDING_START -> commonProps.paddingPx(YogaEdge.START, pixelValue)
      CoreDimenField.PADDING_TOP -> commonProps.paddingPx(YogaEdge.TOP, pixelValue)
      CoreDimenField.PADDING_END -> commonProps.paddingPx(YogaEdge.END, pixelValue)
      CoreDimenField.PADDING_BOTTOM -> commonProps.paddingPx(YogaEdge.BOTTOM, pixelValue)
      CoreDimenField.PADDING_LEFT -> commonProps.paddingPx(YogaEdge.LEFT, pixelValue)
      CoreDimenField.PADDING_RIGHT -> commonProps.paddingPx(YogaEdge.RIGHT, pixelValue)
      CoreDimenField.PADDING_HORIZONTAL -> commonProps.paddingPx(YogaEdge.HORIZONTAL, pixelValue)
      CoreDimenField.PADDING_VERTICAL -> commonProps.paddingPx(YogaEdge.VERTICAL, pixelValue)
      CoreDimenField.PADDING_ALL -> commonProps.paddingPx(YogaEdge.ALL, pixelValue)
      CoreDimenField.MARGIN_START -> commonProps.marginPx(YogaEdge.START, pixelValue)
      CoreDimenField.MARGIN_TOP -> commonProps.marginPx(YogaEdge.TOP, pixelValue)
      CoreDimenField.MARGIN_END -> commonProps.marginPx(YogaEdge.END, pixelValue)
      CoreDimenField.MARGIN_BOTTOM -> commonProps.marginPx(YogaEdge.BOTTOM, pixelValue)
      CoreDimenField.MARGIN_LEFT -> commonProps.marginPx(YogaEdge.LEFT, pixelValue)
      CoreDimenField.MARGIN_RIGHT -> commonProps.marginPx(YogaEdge.RIGHT, pixelValue)
      CoreDimenField.MARGIN_HORIZONTAL -> commonProps.marginPx(YogaEdge.HORIZONTAL, pixelValue)
      CoreDimenField.MARGIN_VERTICAL -> commonProps.marginPx(YogaEdge.VERTICAL, pixelValue)
      CoreDimenField.MARGIN_ALL -> commonProps.marginPx(YogaEdge.ALL, pixelValue)
      CoreDimenField.WIDTH_AUTO -> commonProps.widthAuto()
      CoreDimenField.HEIGHT_AUTO -> commonProps.heightAuto()
    }
  }
}

/** Common style item for all core float styles. See note on [CoreDimenField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class CoreFloatStyleItem(
    override val field: CoreFloatField,
    override val value: Float,
) : StyleItem<Float> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      CoreFloatField.WIDTH_PERCENT -> commonProps.widthPercent(value)
      CoreFloatField.HEIGHT_PERCENT -> commonProps.heightPercent(value)
      CoreFloatField.MIN_WIDTH_PERCENT -> commonProps.minWidthPercent(value)
      CoreFloatField.MAX_WIDTH_PERCENT -> commonProps.maxWidthPercent(value)
      CoreFloatField.MIN_HEIGHT_PERCENT -> commonProps.minHeightPercent(value)
      CoreFloatField.MAX_HEIGHT_PERCENT -> commonProps.maxHeightPercent(value)
      CoreFloatField.MARGIN_ALL_PERCENT -> commonProps.marginPercent(YogaEdge.ALL, value)
      CoreFloatField.MARGIN_START_PERCENT -> commonProps.marginPercent(YogaEdge.START, value)
      CoreFloatField.MARGIN_TOP_PERCENT -> commonProps.marginPercent(YogaEdge.TOP, value)
      CoreFloatField.MARGIN_END_PERCENT -> commonProps.marginPercent(YogaEdge.END, value)
      CoreFloatField.MARGIN_BOTTOM_PERCENT -> commonProps.marginPercent(YogaEdge.BOTTOM, value)
      CoreFloatField.MARGIN_LEFT_PERCENT -> commonProps.marginPercent(YogaEdge.LEFT, value)
      CoreFloatField.MARGIN_RIGHT_PERCENT -> commonProps.marginPercent(YogaEdge.RIGHT, value)
      CoreFloatField.MARGIN_HORIZONTAL_PERCENT ->
          commonProps.marginPercent(YogaEdge.HORIZONTAL, value)
      CoreFloatField.MARGIN_VERTICAL_PERCENT -> commonProps.marginPercent(YogaEdge.VERTICAL, value)
      CoreFloatField.PADDING_ALL_PERCENT -> commonProps.paddingPercent(YogaEdge.ALL, value)
      CoreFloatField.PADDING_START_PERCENT -> commonProps.paddingPercent(YogaEdge.START, value)
      CoreFloatField.PADDING_TOP_PERCENT -> commonProps.paddingPercent(YogaEdge.TOP, value)
      CoreFloatField.PADDING_END_PERCENT -> commonProps.paddingPercent(YogaEdge.END, value)
      CoreFloatField.PADDING_BOTTOM_PERCENT -> commonProps.paddingPercent(YogaEdge.BOTTOM, value)
      CoreFloatField.PADDING_LEFT_PERCENT -> commonProps.paddingPercent(YogaEdge.LEFT, value)
      CoreFloatField.PADDING_RIGHT_PERCENT -> commonProps.paddingPercent(YogaEdge.RIGHT, value)
      CoreFloatField.PADDING_HORIZONTAL_PERCENT ->
          commonProps.paddingPercent(YogaEdge.HORIZONTAL, value)
      CoreFloatField.PADDING_VERTICAL_PERCENT ->
          commonProps.paddingPercent(YogaEdge.VERTICAL, value)
    }
  }
}

/** Enum for [AndroidStyleItem]. */
private enum class AndroidStyleField : StyleItemField {
  ANDROID_STYLE,
}

@DataClassGenerate
internal data class AndroidStyleItem(
    @AttrRes val defStyleAttr: Int,
    @StyleRes val defStyleRes: Int,
) : StyleItem<Int> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    commonProps.setStyle(defStyleAttr, defStyleRes)
  }

  override val value: Int = defStyleRes
  override val field: StyleItemField = AndroidStyleField.ANDROID_STYLE
}

/**
 * Applies the default styled attributes from an Android style/theme resource to this component's
 * common view and layout properties — the `R.styleable.ComponentLayout` set, e.g. `background`,
 * `foreground`, `padding`, `margin`, `minWidth`/`minHeight`, `contentDescription` and
 * `importantForAccessibility`. This is the [Style] equivalent of the legacy Spec builder's
 * `create(c, defStyleAttr, defStyleRes)` overload.
 *
 * Component-specific styled attributes (for example a progress bar's
 * `android:indeterminateDrawable`) are not applied here; a component that needs them must read them
 * itself. Explicit attributes set elsewhere in the [Style] chain take precedence over values coming
 * from the resolved style.
 *
 * Passing 0 for both [defStyleAttr] and [defStyleRes] is a no-op that returns this [Style]
 * unchanged.
 *
 * @param defStyleAttr An attribute in the current theme referencing a style resource that supplies
 *   defaults, or 0 to skip.
 * @param defStyleRes A style resource supplying defaults, used when [defStyleAttr] is 0 or is not
 *   found in the theme; pass 0 to skip.
 */
fun Style.androidStyle(@AttrRes defStyleAttr: Int = 0, @StyleRes defStyleRes: Int = 0): Style =
    if (defStyleAttr == 0 && defStyleRes == 0) this
    else this + AndroidStyleItem(defStyleAttr, defStyleRes)

/**
 * Returns this Style if it's not null, otherwise returns the default empty Style singleton.
 *
 * This extension function provides a safe way to handle nullable Style objects by ensuring a
 * non-null Style is always returned, making it easier to chain style operations without null
 * checks.
 *
 * @return this Style if not null, or Style.Companion (the empty Style singleton) if null
 * @receiver Style? A nullable Style instance
 */
fun Style?.orDefault(): Style = this ?: Style

/** Sets a specific preferred width for this component when its parent lays it out. */
fun Style.width(width: Dimen): Style = this + CoreDimenStyleItem(CoreDimenField.WIDTH, width)

/** Sets a specific preferred height for this component when its parent lays it out. */
fun Style.height(height: Dimen): Style = this + CoreDimenStyleItem(CoreDimenField.HEIGHT, height)

/** auto width */
fun Style.widthAuto(): Style = this + CoreDimenStyleItem(CoreDimenField.WIDTH_AUTO, 0.px)

/** auto height */
fun Style.heightAuto(): Style = this + CoreDimenStyleItem(CoreDimenField.HEIGHT_AUTO, 0.px)

/** Sets both width and height to the same value. */
fun Style.size(size: Dimen): Style = width(size).height(size)

/** Sets a specific preferred percent width for this component when its parent lays it out. */
fun Style.widthPercent(@FloatRange(from = 0.0) widthPercent: Float): Style =
    this + CoreFloatStyleItem(CoreFloatField.WIDTH_PERCENT, widthPercent)

/** Sets a specific preferred percent height for this component when its parent lays it out. */
fun Style.heightPercent(@FloatRange(from = 0.0) heightPercent: Float): Style =
    this + CoreFloatStyleItem(CoreFloatField.HEIGHT_PERCENT, heightPercent)

/** Sets a preferred minimum width for this component when its parent lays it out. */
fun Style.minWidth(minWidth: Dimen): Style =
    this + CoreDimenStyleItem(CoreDimenField.MIN_WIDTH, minWidth)

/** Sets a preferred maximum width for this component when its parent lays it out. */
fun Style.maxWidth(maxWidth: Dimen): Style =
    this + CoreDimenStyleItem(CoreDimenField.MAX_WIDTH, maxWidth)

/** Sets minimum and maximum width constraints. */
fun Style.widthIn(min: Dimen, max: Dimen): Style = minWidth(min).maxWidth(max)

/** Sets a preferred minimum height for this component when its parent lays it out. */
fun Style.minHeight(minHeight: Dimen): Style =
    this + CoreDimenStyleItem(CoreDimenField.MIN_HEIGHT, minHeight)

/** Sets a preferred maximum height for this component when its parent lays it out. */
fun Style.maxHeight(maxHeight: Dimen): Style =
    this + CoreDimenStyleItem(CoreDimenField.MAX_HEIGHT, maxHeight)

/** Sets minimum and maximum height constraints. */
fun Style.heightIn(min: Dimen, max: Dimen): Style = minHeight(min).maxHeight(max)

/** Sets a specific minimum percent width for this component when its parent lays it out. */
fun Style.minWidthPercent(@FloatRange(from = 0.0) minWidthPercent: Float): Style =
    this + CoreFloatStyleItem(CoreFloatField.MIN_WIDTH_PERCENT, minWidthPercent)

/** Sets a specific maximum percent width for this component when its parent lays it out. */
fun Style.maxWidthPercent(@FloatRange(from = 0.0) maxWidthPercent: Float): Style =
    this + CoreFloatStyleItem(CoreFloatField.MAX_WIDTH_PERCENT, maxWidthPercent)

/** Sets a specific minimum percent height for this component when its parent lays it out. */
fun Style.minHeightPercent(@FloatRange(from = 0.0) minHeightPercent: Float): Style =
    this + CoreFloatStyleItem(CoreFloatField.MIN_HEIGHT_PERCENT, minHeightPercent)

/** Sets a specific maximum percent height for this component when its parent lays it out. */
fun Style.maxHeightPercent(@FloatRange(from = 0.0) maxHeightPercent: Float): Style =
    this + CoreFloatStyleItem(CoreFloatField.MAX_HEIGHT_PERCENT, maxHeightPercent)

/** Defines padding on the component on a per-edge basis. */
fun Style.padding(
    all: Dimen? = null,
    horizontal: Dimen? = null,
    vertical: Dimen? = null,
    start: Dimen? = null,
    top: Dimen? = null,
    end: Dimen? = null,
    bottom: Dimen? = null,
    left: Dimen? = null,
    right: Dimen? = null,
): Style =
    this +
        all?.let { CoreDimenStyleItem(CoreDimenField.PADDING_ALL, it) } +
        horizontal?.let { CoreDimenStyleItem(CoreDimenField.PADDING_HORIZONTAL, it) } +
        vertical?.let { CoreDimenStyleItem(CoreDimenField.PADDING_VERTICAL, it) } +
        start?.let { CoreDimenStyleItem(CoreDimenField.PADDING_START, it) } +
        top?.let { CoreDimenStyleItem(CoreDimenField.PADDING_TOP, it) } +
        end?.let { CoreDimenStyleItem(CoreDimenField.PADDING_END, it) } +
        bottom?.let { CoreDimenStyleItem(CoreDimenField.PADDING_BOTTOM, it) } +
        left?.let { CoreDimenStyleItem(CoreDimenField.PADDING_LEFT, it) } +
        right?.let { CoreDimenStyleItem(CoreDimenField.PADDING_RIGHT, it) }

/**
 * Defines padding on the component on a per-edge basis as a nonnegative percentage of the
 * container's width. Values greater than 100 are allowed. Null leaves an edge unspecified. See
 * [https://www.yogalayout.dev/docs/styling/margin-padding-border](https://www.yogalayout.dev/docs/styling/margin-padding-border)
 * for more information.
 */
fun Style.paddingPercent(
    @FloatRange(from = 0.0) all: Float? = null,
    @FloatRange(from = 0.0) horizontal: Float? = null,
    @FloatRange(from = 0.0) vertical: Float? = null,
    @FloatRange(from = 0.0) start: Float? = null,
    @FloatRange(from = 0.0) top: Float? = null,
    @FloatRange(from = 0.0) end: Float? = null,
    @FloatRange(from = 0.0) bottom: Float? = null,
    @FloatRange(from = 0.0) left: Float? = null,
    @FloatRange(from = 0.0) right: Float? = null,
): Style =
    this +
        all?.let { CoreFloatStyleItem(CoreFloatField.PADDING_ALL_PERCENT, it) } +
        horizontal?.let { CoreFloatStyleItem(CoreFloatField.PADDING_HORIZONTAL_PERCENT, it) } +
        vertical?.let { CoreFloatStyleItem(CoreFloatField.PADDING_VERTICAL_PERCENT, it) } +
        start?.let { CoreFloatStyleItem(CoreFloatField.PADDING_START_PERCENT, it) } +
        top?.let { CoreFloatStyleItem(CoreFloatField.PADDING_TOP_PERCENT, it) } +
        end?.let { CoreFloatStyleItem(CoreFloatField.PADDING_END_PERCENT, it) } +
        bottom?.let { CoreFloatStyleItem(CoreFloatField.PADDING_BOTTOM_PERCENT, it) } +
        left?.let { CoreFloatStyleItem(CoreFloatField.PADDING_LEFT_PERCENT, it) } +
        right?.let { CoreFloatStyleItem(CoreFloatField.PADDING_RIGHT_PERCENT, it) }

/** Defines margin around the component on a per-edge basis. */
fun Style.margin(
    all: Dimen? = null,
    horizontal: Dimen? = null,
    vertical: Dimen? = null,
    start: Dimen? = null,
    top: Dimen? = null,
    end: Dimen? = null,
    bottom: Dimen? = null,
    left: Dimen? = null,
    right: Dimen? = null,
): Style =
    this +
        all?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_ALL, it) } +
        horizontal?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_HORIZONTAL, it) } +
        vertical?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_VERTICAL, it) } +
        start?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_START, it) } +
        top?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_TOP, it) } +
        end?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_END, it) } +
        bottom?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_BOTTOM, it) } +
        left?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_LEFT, it) } +
        right?.let { CoreDimenStyleItem(CoreDimenField.MARGIN_RIGHT, it) }

/**
 * Defines margin around the component on a per-edge basis as a percentage of the container's width.
 * Negative values and values greater than 100 are allowed. Null leaves an edge unspecified. See
 * [https://www.yogalayout.dev/docs/styling/margin-padding-border](https://www.yogalayout.dev/docs/styling/margin-padding-border)
 * for more information.
 */
fun Style.marginPercent(
    all: Float? = null,
    horizontal: Float? = null,
    vertical: Float? = null,
    start: Float? = null,
    top: Float? = null,
    end: Float? = null,
    bottom: Float? = null,
    left: Float? = null,
    right: Float? = null,
): Style =
    this +
        all?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_ALL_PERCENT, it) } +
        horizontal?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_HORIZONTAL_PERCENT, it) } +
        vertical?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_VERTICAL_PERCENT, it) } +
        start?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_START_PERCENT, it) } +
        top?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_TOP_PERCENT, it) } +
        end?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_END_PERCENT, it) } +
        bottom?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_BOTTOM_PERCENT, it) } +
        left?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_LEFT_PERCENT, it) } +
        right?.let { CoreFloatStyleItem(CoreFloatField.MARGIN_RIGHT_PERCENT, it) }
