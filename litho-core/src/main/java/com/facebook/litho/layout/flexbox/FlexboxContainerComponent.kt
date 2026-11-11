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

package com.facebook.litho.layout.flexbox

import com.facebook.litho.CalculationContext
import com.facebook.litho.CommonProps
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentResolveResult
import com.facebook.litho.ComponentUtils
import com.facebook.litho.DeferredLithoNode
import com.facebook.litho.LithoNode
import com.facebook.litho.ResolveContext
import com.facebook.litho.ScopedComponentInfo
import com.facebook.litho.Style
import com.facebook.rendercore.Dimen
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaFlexDirection
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaJustify
import com.facebook.yoga.YogaWrap

/**
 * Sealed base class for flexbox container components (Column/Row) that extends [Component] directly
 * instead of the legacy SpecGeneratedComponent.
 *
 * Supports both uniform gaps (via [gap]) and direction-specific gaps (via [mainAxisGap] and
 * [crossAxisGap]).
 *
 * **Gap Parameters:**
 * - [gap]: Uniform gap applied to both main and cross axes (maps to YogaGutter.ALL). Backward
 *   compatible.
 * - [mainAxisGap]: Gap along the main axis (direction of flexDirection). For Row: horizontal
 *   spacing. For Column: vertical spacing. Maps to YogaGutter.COLUMN for Row, YogaGutter.ROW for
 *   Column.
 * - [crossAxisGap]: Gap along the cross axis (perpendicular to flexDirection). For Row: vertical
 *   spacing. For Column: horizontal spacing. Maps to YogaGutter.ROW for Row, YogaGutter.COLUMN for
 *   Column.
 *
 * **When to use each:**
 * - Use [gap] when you need uniform spacing in all directions (most common case).
 * - Use [mainAxisGap] and [crossAxisGap] when you need different spacing along each axis, typically
 *   when wrap=WRAP to control spacing between wrapped rows/columns separately from spacing within a
 *   row/column.
 *
 * If both [gap] and direction-specific gaps are provided, direction-specific gaps take precedence.
 *
 * Gap values are held as [Dimen] and resolved to pixels at layout time, so the construction site
 * does not need a [ComponentContext] or [com.facebook.litho.ResourcesScope] to build a container.
 */
sealed class FlexboxContainerComponent(
    // Share the type identity (and therefore the auto-generated global key) of the classic
    // Spec-generated Column/Row, so migrating a builder Column/Row to this KComponent DSL is
    // key-neutral (visibility ids, @State, transitions survive). A manual key at the callsite still
    // overrides this. Reconciliation/equivalence key on javaClass, not typeId, so this does not
    // cause a KColumn to reconcile against a classic Column.
    typeIdClass: Class<out Component>,
    private val flexDirection: YogaFlexDirection,
    private val alignContent: YogaAlign?,
    private val alignItems: YogaAlign?,
    private val justifyContent: YogaJustify?,
    private val wrap: YogaWrap?,
    private val gap: Dimen?,
    private val mainAxisGap: Dimen?,
    private val crossAxisGap: Dimen?,
    internal val children: List<Component>?,
    private val style: Style,
    private val customSimpleName: String? = null,
) : Component(typeIdClass) {

  /**
   * The [CommonProps] populated by a legacy `Row`/`Column` builder's setters, including its
   * `kotlinStyle`, which is also kept as [style]. When set, resolution returns it instead of
   * applying [style], and equivalence compares it the way the Spec-generated containers compare
   * theirs, in addition to comparing [style].
   */
  internal var builderCommonProps: CommonProps? = null

  override fun getSimpleName(): String =
      customSimpleName
          ?: when (flexDirection) {
            YogaFlexDirection.COLUMN,
            YogaFlexDirection.COLUMN_REVERSE -> "Column"
            else -> "Row"
          }

  override fun resolve(
      resolveContext: ResolveContext,
      scopedComponentInfo: ScopedComponentInfo,
      parentWidthSpec: Int,
      parentHeightSpec: Int,
  ): ComponentResolveResult {
    val c: ComponentContext = scopedComponentInfo.context
    val node = LithoNode()
    node.flexDirection(flexDirection)
    alignItems?.let { node.alignItems(it) }
    alignContent?.let { node.alignContent(it) }
    justifyContent?.let { node.justifyContent(it) }
    wrap?.let { node.wrap(it) }

    // Apply gaps with direction awareness
    // Direction-specific gaps take precedence over uniform gap
    if (mainAxisGap != null || crossAxisGap != null) {
      // For Row: mainAxis is horizontal (YogaGutter.COLUMN), crossAxis is vertical (YogaGutter.ROW)
      // For Column: mainAxis is vertical (YogaGutter.ROW), crossAxis is horizontal
      // (YogaGutter.COLUMN)
      val isRowDirection =
          flexDirection == YogaFlexDirection.ROW || flexDirection == YogaFlexDirection.ROW_REVERSE
      val mainAxisGutter = if (isRowDirection) YogaGutter.COLUMN else YogaGutter.ROW
      val crossAxisGutter = if (isRowDirection) YogaGutter.ROW else YogaGutter.COLUMN

      mainAxisGap?.let { node.setGap(mainAxisGutter, it.toPixels(c.resourceResolver)) }
      crossAxisGap?.let { node.setGap(crossAxisGutter, it.toPixels(c.resourceResolver)) }
    } else {
      // Backward compatibility: apply uniform gap if no direction-specific gaps provided
      gap?.let { node.setGap(YogaGutter.ALL, it.toPixels(c.resourceResolver)) }
    }

    children?.let { children ->
      for (child in children) {
        if (resolveContext.isFutureReleased) {
          return ComponentResolveResult(null, null)
        }
        if (resolveContext.isResolveInterrupted) {
          node.appendUnresolvedComponent(child)
        } else {
          node.child(resolveContext, c, child)
        }
      }
    }

    return ComponentResolveResult(node, resolveCommonProps(c))
  }

  override fun resolveDeferred(
      calculationContext: CalculationContext,
      componentContext: ComponentContext,
      parentContext: ComponentContext,
  ): ComponentResolveResult {
    val node = DeferredLithoNode(
        componentContext.treePropContainer,
        calculationContext.cache.getCachedNode(this),
        parentContext,
    )
    return ComponentResolveResult(node, resolveCommonProps(componentContext))
  }

  private fun resolveCommonProps(c: ComponentContext): CommonProps? =
      builderCommonProps
          ?: if (style !== Style) CommonProps().apply { style.applyCommonProps(c, this) } else null

  override fun isEquivalentProps(other: Component?): Boolean {
    if (this === other) {
      return true
    }
    if (other == null || javaClass != other.javaClass) {
      return false
    }
    if (instanceId == other.instanceId) {
      return true
    }
    other as FlexboxContainerComponent
    if (flexDirection != other.flexDirection) {
      return false
    }
    if (alignContent != other.alignContent) {
      return false
    }
    if (alignItems != other.alignItems) {
      return false
    }
    if (justifyContent != other.justifyContent) {
      return false
    }
    if (wrap != other.wrap) {
      return false
    }
    if (gap != other.gap) {
      return false
    }
    if (mainAxisGap != other.mainAxisGap) {
      return false
    }
    if (crossAxisGap != other.crossAxisGap) {
      return false
    }
    val thisBuilderProps = builderCommonProps
    val otherBuilderProps = other.builderCommonProps
    if (thisBuilderProps !== otherBuilderProps) {
      if (thisBuilderProps == null || !thisBuilderProps.isEquivalentTo(otherBuilderProps)) {
        return false
      }
    }
    if (style != other.style) {
      return false
    }
    val thisChildren = children
    val otherChildren = other.children
    if (thisChildren != null) {
      if (otherChildren == null || thisChildren.size != otherChildren.size) {
        return false
      }
      thisChildren.forEachIndexed { index, child ->
        if (!ComponentUtils.isEquivalent(child, otherChildren[index])) {
          return false
        }
      }
    } else if (otherChildren != null) {
      return false
    }
    return true
  }
}

/**
 * A [Component] that renders its children in a column, extending [Component] directly.
 *
 * For Column:
 * - mainAxisGap controls vertical spacing (along the column)
 * - crossAxisGap controls horizontal spacing (perpendicular to the column)
 */
class KColumn(
    alignContent: YogaAlign? = null,
    alignItems: YogaAlign? = null,
    justifyContent: YogaJustify? = null,
    wrap: YogaWrap? = null,
    isReversed: Boolean = false,
    gap: Dimen? = null,
    mainAxisGap: Dimen? = null,
    crossAxisGap: Dimen? = null,
    children: List<Component>? = null,
    style: Style = Style,
    customSimpleName: String? = null,
) :
    FlexboxContainerComponent(
        typeIdClass = com.facebook.litho.Column::class.java,
        flexDirection =
            if (isReversed) YogaFlexDirection.COLUMN_REVERSE else YogaFlexDirection.COLUMN,
        alignContent = alignContent,
        alignItems = alignItems,
        justifyContent = justifyContent,
        wrap = wrap,
        gap = gap,
        mainAxisGap = mainAxisGap,
        crossAxisGap = crossAxisGap,
        children = children,
        style = style,
        customSimpleName = customSimpleName,
    )

/**
 * A [Component] that renders its children in a row, extending [Component] directly.
 *
 * For Row:
 * - mainAxisGap controls horizontal spacing (along the row)
 * - crossAxisGap controls vertical spacing (perpendicular to the row)
 */
class KRow(
    alignContent: YogaAlign? = null,
    alignItems: YogaAlign? = null,
    justifyContent: YogaJustify? = null,
    wrap: YogaWrap? = null,
    isReversed: Boolean = false,
    gap: Dimen? = null,
    mainAxisGap: Dimen? = null,
    crossAxisGap: Dimen? = null,
    children: List<Component>? = null,
    style: Style = Style,
    customSimpleName: String? = null,
) :
    FlexboxContainerComponent(
        typeIdClass = com.facebook.litho.Row::class.java,
        flexDirection = if (isReversed) YogaFlexDirection.ROW_REVERSE else YogaFlexDirection.ROW,
        alignContent = alignContent,
        alignItems = alignItems,
        justifyContent = justifyContent,
        wrap = wrap,
        gap = gap,
        mainAxisGap = mainAxisGap,
        crossAxisGap = crossAxisGap,
        children = children,
        style = style,
        customSimpleName = customSimpleName,
    )
