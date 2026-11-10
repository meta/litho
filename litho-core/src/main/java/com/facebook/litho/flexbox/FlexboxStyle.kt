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

package com.facebook.litho.flexbox

import android.graphics.Path
import android.graphics.PathDashPathEffect
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.Border
import com.facebook.litho.CommonProps
import com.facebook.litho.ComponentContext
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.litho.toLayoutDirection
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.ResourceResolver
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaPositionType

/** Enums for [FlexboxDimenStyleItem]. */
@PublishedApi
internal enum class FlexboxDimenField : StyleItemField {
  FLEX_BASIS,
  POSITION_ALL,
  POSITION_START,
  POSITION_TOP,
  POSITION_END,
  POSITION_BOTTOM,
  POSITION_LEFT,
  POSITION_RIGHT,
  POSITION_HORIZONTAL,
  POSITION_VERTICAL,
  GAP_COLUMN,
  GAP_ROW,
  GAP_ALL,
}

/** Enums for [FloatStyleItem]. */
@PublishedApi
internal enum class FlexboxFloatField : StyleItemField {
  FLEX,
  FLEX_GROW,
  FLEX_SHRINK,
  FLEX_BASIS_PERCENT,
  ASPECT_RATIO,
  POSITION_ALL_PERCENT,
  POSITION_START_PERCENT,
  POSITION_TOP_PERCENT,
  POSITION_END_PERCENT,
  POSITION_BOTTOM_PERCENT,
  POSITION_LEFT_PERCENT,
  POSITION_RIGHT_PERCENT,
  POSITION_HORIZONTAL_PERCENT,
  POSITION_VERTICAL_PERCENT,
}

/** Enums for [FlexboxObjectStyleItem]. */
@PublishedApi
internal enum class FlexboxObjectField : StyleItemField {
  ALIGN_SELF,
  BORDER,
  BORDERv2,
  LAYOUT_DIRECTION,
  MARGIN_AUTO,
  POSITION_TYPE,
  IS_REFERENCE_BASELINE,
  USE_HEIGHT_AS_BASELINE,
}

/** Common style item for all dimen styles. See note on [FlexboxDimenField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class FlexboxDimenStyleItem(
    override val field: FlexboxDimenField,
    override val value: Dimen,
) : StyleItem<Dimen> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    val pixelValue = value.toPixels(context.resourceResolver)
    when (field) {
      FlexboxDimenField.FLEX_BASIS -> commonProps.flexBasisPx(pixelValue)
      FlexboxDimenField.POSITION_ALL -> commonProps.positionPx(YogaEdge.ALL, pixelValue)
      FlexboxDimenField.POSITION_START -> commonProps.positionPx(YogaEdge.START, pixelValue)
      FlexboxDimenField.POSITION_END -> commonProps.positionPx(YogaEdge.END, pixelValue)
      FlexboxDimenField.POSITION_TOP -> commonProps.positionPx(YogaEdge.TOP, pixelValue)
      FlexboxDimenField.POSITION_BOTTOM -> commonProps.positionPx(YogaEdge.BOTTOM, pixelValue)
      FlexboxDimenField.POSITION_LEFT -> commonProps.positionPx(YogaEdge.LEFT, pixelValue)
      FlexboxDimenField.POSITION_RIGHT -> commonProps.positionPx(YogaEdge.RIGHT, pixelValue)
      FlexboxDimenField.POSITION_HORIZONTAL ->
          commonProps.positionPx(YogaEdge.HORIZONTAL, pixelValue)
      FlexboxDimenField.POSITION_VERTICAL -> commonProps.positionPx(YogaEdge.VERTICAL, pixelValue)
      FlexboxDimenField.GAP_COLUMN -> commonProps.gap(YogaGutter.COLUMN, pixelValue)
      FlexboxDimenField.GAP_ROW -> commonProps.gap(YogaGutter.ROW, pixelValue)
      FlexboxDimenField.GAP_ALL -> commonProps.gap(YogaGutter.ALL, pixelValue)
    }
  }
}

/** Common style item for all float styles. See note on [FlexboxFloatField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class FloatStyleItem(
    override val field: FlexboxFloatField,
    override val value: Float,
) : StyleItem<Float> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      FlexboxFloatField.FLEX -> commonProps.flex(value)
      FlexboxFloatField.FLEX_GROW -> commonProps.flexGrow(value)
      FlexboxFloatField.FLEX_SHRINK -> commonProps.flexShrink(value)
      FlexboxFloatField.FLEX_BASIS_PERCENT -> commonProps.flexBasisPercent(value)
      FlexboxFloatField.ASPECT_RATIO -> commonProps.aspectRatio(value)
      FlexboxFloatField.POSITION_ALL_PERCENT -> commonProps.positionPercent(YogaEdge.ALL, value)
      FlexboxFloatField.POSITION_START_PERCENT -> commonProps.positionPercent(YogaEdge.START, value)
      FlexboxFloatField.POSITION_TOP_PERCENT -> commonProps.positionPercent(YogaEdge.TOP, value)
      FlexboxFloatField.POSITION_END_PERCENT -> commonProps.positionPercent(YogaEdge.END, value)
      FlexboxFloatField.POSITION_BOTTOM_PERCENT ->
          commonProps.positionPercent(YogaEdge.BOTTOM, value)
      FlexboxFloatField.POSITION_LEFT_PERCENT -> commonProps.positionPercent(YogaEdge.LEFT, value)
      FlexboxFloatField.POSITION_RIGHT_PERCENT -> commonProps.positionPercent(YogaEdge.RIGHT, value)
      FlexboxFloatField.POSITION_HORIZONTAL_PERCENT ->
          commonProps.positionPercent(YogaEdge.HORIZONTAL, value)
      FlexboxFloatField.POSITION_VERTICAL_PERCENT ->
          commonProps.positionPercent(YogaEdge.VERTICAL, value)
    }
  }
}

/** Common style item for all object styles. See note on [FlexboxObjectField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class FlexboxObjectStyleItem(
    override val field: FlexboxObjectField,
    override val value: Any?,
) : StyleItem<Any?> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      FlexboxObjectField.ALIGN_SELF -> value?.let { commonProps.alignSelf(it as YogaAlign) }
      FlexboxObjectField.BORDER -> commonProps.border(value as Border?)
      FlexboxObjectField.BORDERv2 -> {
        val config = value as BorderConfiguration
        commonProps.border(config.toBorder(context))
      }
      FlexboxObjectField.LAYOUT_DIRECTION ->
          commonProps.layoutDirection((value as YogaDirection).toLayoutDirection())
      FlexboxObjectField.POSITION_TYPE ->
          value?.let { commonProps.positionType(it as YogaPositionType) }
      FlexboxObjectField.MARGIN_AUTO -> value?.let { commonProps.marginAuto(it as YogaEdge) }
      FlexboxObjectField.IS_REFERENCE_BASELINE ->
          value?.let { commonProps.isReferenceBaseline(it as Boolean) }
      FlexboxObjectField.USE_HEIGHT_AS_BASELINE ->
          value?.let { commonProps.useHeightAsBaseline(it as Boolean) }
    }
  }
}

/**
 * Flex allows you to define how this component should take up space within its parent. It's
 * comprised of the following properties:
 *
 * **flex-grow**: This component should take up remaining space in its parent. If multiple children
 * of the parent have a flex-grow set, the extra space is divided up based on proportions of
 * flex-grow values, i.e. a child with flex-grow of 2 will get twice as much of the space as its
 * sibling with flex-grow of 1.
 *
 * **flex-shrink**: This component should shrink if necessary. Similar to flex-grow, the value
 * determines the proportion of space *taken* from each child. Setting a flex-shink of 0 means the
 * child won't shrink.
 *
 * **flex-basis**: Defines the default size of the component before extra space is distributed. If
 * omitted, the measured size of the content (or the width/height styles) will be used instead.
 *
 * **flex-basis-percent**: see **flex-basis**. Defines the default size as a percentage of its
 * parent's size. Values must be nonnegative and may be greater than 100.
 * - See https://css-tricks.com/snippets/css/a-guide-to-flexbox/ for more documentation on flexbox
 *   properties.
 * - See https://yogalayout.dev/ for a web-based playground for trying out flexbox layouts.
 *
 * Defaults: flex-grow = 0, flex-shrink = 1, flex-basis = null, flex-basis-percent = null
 */
fun Style.flex(
    grow: Float? = null,
    shrink: Float? = null,
    basis: Dimen? = null,
    @FloatRange(from = 0.0) basisPercent: Float? = null,
): Style =
    this +
        grow?.let { FloatStyleItem(FlexboxFloatField.FLEX_GROW, it) } +
        shrink?.let { FloatStyleItem(FlexboxFloatField.FLEX_SHRINK, it) } +
        basis?.let { FlexboxDimenStyleItem(FlexboxDimenField.FLEX_BASIS, it) } +
        basisPercent?.let { FloatStyleItem(FlexboxFloatField.FLEX_BASIS_PERCENT, it) }

/**
 * Defines how a child should be aligned with a Row or Column, overriding the parent's align-items
 * property for this child.
 * - See https://css-tricks.com/snippets/css/a-guide-to-flexbox/ for more documentation on flexbox
 *   properties.
 * - See https://yogalayout.dev/ for a web-based playground for trying out flexbox layouts.
 */
fun Style.alignSelf(align: YogaAlign): Style =
    this + FlexboxObjectStyleItem(FlexboxObjectField.ALIGN_SELF, align)

/**
 * Defines an aspect ratio for this component, meaning the ratio of width to height. This means if
 * aspectRatio is set to 2 and width is calculated to be 50px, then height will be 100px.
 *
 * Note: This property is not part of the flexbox standard.
 */
fun Style.aspectRatio(aspectRatio: Float): Style =
    this + FloatStyleItem(FlexboxFloatField.ASPECT_RATIO, aspectRatio)

/**
 * Used in conjunction with [positionType] to define how a component should be positioned in its
 * parent.
 *
 * For positionType of ABSOLUTE: the values specified here will define how inset the child is from
 * the same edge on its parent. E.g. for `position(0.px, 0.px, 0.px, 0.px)`, it will be the full
 * size of the parent (no insets). For `position(0.px, 10.px, 0.px, 10.px)`, the child will be the
 * full width of the parent, but inset by 10px on the top and bottom.
 *
 * For positionType of RELATIVE: the values specified here will define how the child is positioned
 * relative to where that edge would have normally been positioned.
 *
 * See https://yogalayout.dev/ for a web-based playground for trying out flexbox layouts.
 */
fun Style.position(
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
        all?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_ALL, it) } +
        horizontal?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_HORIZONTAL, it) } +
        vertical?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_VERTICAL, it) } +
        start?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_START, it) } +
        top?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_TOP, it) } +
        end?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_END, it) } +
        bottom?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_BOTTOM, it) } +
        left?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_LEFT, it) } +
        right?.let { FlexboxDimenStyleItem(FlexboxDimenField.POSITION_RIGHT, it) }

/**
 * Used in conjunction with [positionType] to define how a component should be positioned in its
 * parent, with percentages of the containing block's size. Negative values and values greater than
 * 100 are allowed. Null leaves an edge unspecified.
 */
fun Style.positionPercent(
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
        all?.let { FloatStyleItem(FlexboxFloatField.POSITION_ALL_PERCENT, it) } +
        horizontal?.let { FloatStyleItem(FlexboxFloatField.POSITION_HORIZONTAL_PERCENT, it) } +
        vertical?.let { FloatStyleItem(FlexboxFloatField.POSITION_VERTICAL_PERCENT, it) } +
        start?.let { FloatStyleItem(FlexboxFloatField.POSITION_START_PERCENT, it) } +
        top?.let { FloatStyleItem(FlexboxFloatField.POSITION_TOP_PERCENT, it) } +
        end?.let { FloatStyleItem(FlexboxFloatField.POSITION_END_PERCENT, it) } +
        bottom?.let { FloatStyleItem(FlexboxFloatField.POSITION_BOTTOM_PERCENT, it) } +
        left?.let { FloatStyleItem(FlexboxFloatField.POSITION_LEFT_PERCENT, it) } +
        right?.let { FloatStyleItem(FlexboxFloatField.POSITION_RIGHT_PERCENT, it) }

/** See docs in [position]. */
fun Style.positionType(positionType: YogaPositionType): Style =
    this + FlexboxObjectStyleItem(FlexboxObjectField.POSITION_TYPE, positionType)

/**
 * Describes how a [Border] should be drawn around this component. Setting this property will cause
 * the Component to be represented as a View at mount time if it wasn't going to already.
 */
fun Style.border(border: Border?): Style =
    this + FlexboxObjectStyleItem(FlexboxObjectField.BORDER, border)

/**
 * Applies a uniform border with the same width and color on all edges. This is a simplified API for
 * the most common border use case.
 *
 * Example:
 * ```
 * Style.border(width = 2.dp, color = Color.Blue)
 * Style.border(width = 4.dp, color = Color.Red, radius = 8.dp)
 * Style.border(
 *   width = 4.dp,
 *   color = Color.Red,
 *   effect = BorderEffect.dashed(floatArrayOf(10f, 5f), phase = 0f)
 * )
 * ```
 *
 * @param width the width to apply to all edges
 * @param color the color to apply to all edges
 * @param radius optional corner radius to apply to all corners
 * @param effect optional border effect to apply to the entire border
 */
fun Style.border(
    width: Dimen,
    @ColorInt color: Int,
    radius: Dimen? = null,
    effect: BorderEffect? = null,
): Style =
    this +
        FlexboxObjectStyleItem(
            FlexboxObjectField.BORDERv2,
            BorderConfiguration(
                allWidth = width,
                allColor = color,
                allRadius = radius,
                effect = effect,
            ),
        )

/**
 * Applies a border using a DSL-style builder for complex configurations.
 *
 * This API provides a declarative way to configure borders with different properties per edge,
 * custom corner radii, and effects.
 *
 * Example:
 * ```
 * Style.border {
 *   all(width = 5.dp, color = Color.Blue, effect = BorderEffect.dashed(floatArrayOf(10f, 5f)))
 *   cornerRadius(all = 10.dp)
 *
 *   top(color = Color.Red)
 *   bottom(color = Color.Green)
 *
 *   cornerRadius(topLeft = 15.dp, topRight = 15.dp)
 * }
 * ```
 *
 * @param block the configuration block
 */
fun Style.border(block: BorderConfigScope.() -> Unit): Style {
  val scope = BorderConfigScope()
  scope.block()
  return this + FlexboxObjectStyleItem(FlexboxObjectField.BORDERv2, scope.build())
}

/**
 * Describes the RTL/LTR direction of component. Determines whether {@link YogaEdge#START} and
 * {@link YogaEdge#END} will resolve to the left or right side, among other things. INHERIT
 * indicates this setting will be inherited from this component's parent. Setting this property will
 * cause the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * <p>Default: {@link YogaDirection#INHERIT}
 */
fun Style.layoutDirection(layoutDirection: YogaDirection): Style =
    this + FlexboxObjectStyleItem(FlexboxObjectField.LAYOUT_DIRECTION, layoutDirection)

/**
 * Sets margin value for specified edge to auto. The item will extend the margin for this edge to
 * occupy the extra space in the parent, depending on the direction (Row or Column).
 */
fun Style.marginAuto(edge: YogaEdge): Style =
    this + FlexboxObjectStyleItem(FlexboxObjectField.MARGIN_AUTO, edge)

fun Style.isReferenceBaseline(isReferenceBaseline: Boolean): Style =
    this + FlexboxObjectStyleItem(FlexboxObjectField.IS_REFERENCE_BASELINE, isReferenceBaseline)

fun Style.useHeightAsBaseline(useHeightAsBaseline: Boolean): Style =
    this + FlexboxObjectStyleItem(FlexboxObjectField.USE_HEIGHT_AS_BASELINE, useHeightAsBaseline)

/**
 * Stores properties that are used to customise an effect that is applied to the border.
 *
 * @see BorderEffect.path
 * @see BorderEffect.dashed
 * @see BorderEffect.discrete
 */
class BorderEffect {

  // shared properties
  var phase: Float? = null

  // dash properties
  var intervals: FloatArray? = null

  // path properties
  var pathShape: Path? = null
  var pathAdvance: Float? = null
  var pathStyle: PathDashPathEffect.Style? = null

  // discrete effect properties
  var segmentLength: Float? = null
  var deviation: Float? = null

  private constructor(intervals: FloatArray, phase: Float) {
    this.intervals = intervals
    this.phase = phase
  }

  private constructor(
      pathShape: Path,
      pathAdvance: Float,
      pathPhase: Float,
      pathStyle: PathDashPathEffect.Style,
  ) {
    this.pathShape = pathShape
    this.pathAdvance = pathAdvance
    this.phase = pathPhase
    this.pathStyle = pathStyle
  }

  private constructor(segmentLength: Float, deviation: Float) {
    this.segmentLength = segmentLength
    this.deviation = deviation
  }

  fun apply(builder: Border.Builder) {
    // apply dashed border effect.
    intervals?.let { builder.dashEffect(it, phase ?: 0f) }

    // apply path border effect.
    if (this.pathShape != null) {
      builder.pathDashEffect(pathShape, pathAdvance ?: 0f, phase ?: 0f, pathStyle)
    }

    // apply discrete path border effect.
    if (this.segmentLength != null) {
      builder.discreteEffect(segmentLength ?: 0f, deviation ?: 0f)
    }
  }

  companion object {

    /**
     * Applies a path dash effect to the border
     *
     * @see Border.Builder.pathDashEffect
     */
    fun path(
        shape: Path,
        advance: Float,
        phase: Float,
        style: PathDashPathEffect.Style,
    ): BorderEffect {
      return BorderEffect(shape, advance, phase, style)
    }

    /**
     * Applies a dash effect to the border.
     *
     * @see Border.Builder.dashEffect
     */
    fun dashed(intervals: FloatArray, phase: Float): BorderEffect {
      return BorderEffect(intervals, phase)
    }

    /**
     * Applies a discrete effect to the border.
     *
     * @see Border.Builder.discreteEffect
     */
    fun discrete(segmentLength: Float, deviation: Float): BorderEffect {
      return BorderEffect(segmentLength, deviation)
    }
  }
}

/**
 * Configuration for a border with unresolved Dimen values. This will be resolved to pixels in
 * applyCommonProps.
 */
@PublishedApi
@DataClassGenerate
internal data class BorderConfiguration(
    // Edge properties
    val allWidth: Dimen? = null,
    val allColor: Int? = null,
    val startEdge: BorderEdgeConfig? = null,
    val endEdge: BorderEdgeConfig? = null,
    val topEdge: BorderEdgeConfig? = null,
    val bottomEdge: BorderEdgeConfig? = null,
    val leftEdge: BorderEdgeConfig? = null,
    val rightEdge: BorderEdgeConfig? = null,

    // Radius properties
    val allRadius: Dimen? = null,
    val topLeftRadius: Dimen? = null,
    val topRightRadius: Dimen? = null,
    val bottomLeftRadius: Dimen? = null,
    val bottomRightRadius: Dimen? = null,

    // Effect
    val effect: BorderEffect? = null,
) {
  fun toBorder(context: ComponentContext): Border {
    val resolver = context.resourceResolver
    val border = Border.create(context)

    // Apply all edge properties
    allWidth?.let { border.widthPx(YogaEdge.ALL, it.toPixels(resolver)) }
    allColor?.let { border.color(YogaEdge.ALL, it) }

    // Apply group edges
    startEdge?.applyEdge(YogaEdge.START, border, resolver)
    endEdge?.applyEdge(YogaEdge.END, border, resolver)

    // Apply individual edges
    topEdge?.applyEdge(YogaEdge.TOP, border, resolver)
    bottomEdge?.applyEdge(YogaEdge.BOTTOM, border, resolver)
    leftEdge?.applyEdge(YogaEdge.LEFT, border, resolver)
    rightEdge?.applyEdge(YogaEdge.RIGHT, border, resolver)

    // Apply radius
    allRadius?.let { border.radiusPx(it.toPixels(resolver)) }
    topLeftRadius?.let { border.radiusPx(Border.Corner.TOP_LEFT, it.toPixels(resolver)) }
    topRightRadius?.let { border.radiusPx(Border.Corner.TOP_RIGHT, it.toPixels(resolver)) }
    bottomLeftRadius?.let { border.radiusPx(Border.Corner.BOTTOM_LEFT, it.toPixels(resolver)) }
    bottomRightRadius?.let { border.radiusPx(Border.Corner.BOTTOM_RIGHT, it.toPixels(resolver)) }

    // Apply effect
    effect?.apply(border)

    return border.build()
  }
}

/** Edge configuration with unresolved Dimen */
@PublishedApi
@DataClassGenerate
internal data class BorderEdgeConfig(
    @Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
    @ColorInt
    val color: Int? = null,
    val width: Dimen? = null,
) {
  fun applyEdge(edge: YogaEdge, builder: Border.Builder, resolver: ResourceResolver) {
    color?.let { builder.color(edge, it) }
    width?.let { builder.widthPx(edge, it.toPixels(resolver)) }
  }
}

/** DSL scope for building border configurations. */
class BorderConfigScope {
  private var allWidth: Dimen? = null
  private var allColor: Int? = null
  private var allRadius: Dimen? = null

  private var startEdge: BorderEdgeConfig? = null
  private var endEdge: BorderEdgeConfig? = null
  private var topEdge: BorderEdgeConfig? = null
  private var bottomEdge: BorderEdgeConfig? = null
  private var leftEdge: BorderEdgeConfig? = null
  private var rightEdge: BorderEdgeConfig? = null

  private var topLeftRadius: Dimen? = null
  private var topRightRadius: Dimen? = null
  private var bottomLeftRadius: Dimen? = null
  private var bottomRightRadius: Dimen? = null

  private var borderEffect: BorderEffect? = null

  /** Configures all edges with the same properties. */
  fun all(@ColorInt color: Int? = null, width: Dimen? = null, effect: BorderEffect? = null) {
    if (color != null) allColor = color
    if (width != null) allWidth = width
    if (effect != null) borderEffect = effect
  }

  /**
   * Configures corner radii. Use `all` to set all corners, or individual parameters to customize
   * specific corners.
   */
  fun cornerRadius(
      all: Dimen? = null,
      topLeft: Dimen? = null,
      topRight: Dimen? = null,
      bottomLeft: Dimen? = null,
      bottomRight: Dimen? = null,
  ) {
    if (all != null) allRadius = all
    if (topLeft != null) this.topLeftRadius = topLeft
    if (topRight != null) this.topRightRadius = topRight
    if (bottomLeft != null) this.bottomLeftRadius = bottomLeft
    if (bottomRight != null) this.bottomRightRadius = bottomRight
  }

  /** Configures the top edge. */
  fun top(@ColorInt color: Int? = null, width: Dimen? = null) {
    if (color != null || width != null) {
      topEdge = BorderEdgeConfig(color = color, width = width)
    }
  }

  /** Configures the bottom edge. */
  fun bottom(@ColorInt color: Int? = null, width: Dimen? = null) {
    if (color != null || width != null) {
      bottomEdge = BorderEdgeConfig(color = color, width = width)
    }
  }

  /** Configures the left edge. */
  fun left(@ColorInt color: Int? = null, width: Dimen? = null) {
    if (color != null || width != null) {
      leftEdge = BorderEdgeConfig(color = color, width = width)
    }
  }

  /** Configures the right edge. */
  fun right(@ColorInt color: Int? = null, width: Dimen? = null) {
    if (color != null || width != null) {
      rightEdge = BorderEdgeConfig(color = color, width = width)
    }
  }

  /** Configures the start edge (LTR: left, RTL: right). */
  fun start(@ColorInt color: Int? = null, width: Dimen? = null) {
    if (color != null || width != null) {
      startEdge = BorderEdgeConfig(color = color, width = width)
    }
  }

  /** Configures the end edge (LTR: right, RTL: left). */
  fun end(@ColorInt color: Int? = null, width: Dimen? = null) {
    if (color != null || width != null) {
      endEdge = BorderEdgeConfig(color = color, width = width)
    }
  }

  @PublishedApi
  internal fun build(): BorderConfiguration {
    return BorderConfiguration(
        allWidth = allWidth,
        allColor = allColor,
        startEdge = startEdge,
        endEdge = endEdge,
        topEdge = topEdge,
        bottomEdge = bottomEdge,
        leftEdge = leftEdge,
        rightEdge = rightEdge,
        allRadius = allRadius,
        topLeftRadius = topLeftRadius,
        topRightRadius = topRightRadius,
        bottomLeftRadius = bottomLeftRadius,
        bottomRightRadius = bottomRightRadius,
        effect = borderEffect,
    )
  }
}
