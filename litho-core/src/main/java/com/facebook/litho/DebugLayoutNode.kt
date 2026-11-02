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

package com.facebook.litho

import android.graphics.drawable.Drawable
import android.util.SparseArray
import com.facebook.litho.layout.BoxEdge
import com.facebook.litho.layout.BoxLayoutProps
import com.facebook.litho.layout.BoxLength
import com.facebook.litho.layout.IntEdges
import com.facebook.litho.layout.LayoutDirection
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaFlexDirection
import com.facebook.yoga.YogaJustify
import com.facebook.yoga.YogaNode
import com.facebook.yoga.YogaPositionType
import com.facebook.yoga.YogaUnit
import com.facebook.yoga.YogaValue
import com.facebook.yoga.YogaWrap

/**
 * A DebugLayoutNode is a wrapper around InternalNode which allows debug tools to inspect and mutate
 * internal nodes without making InternalNode a public class. This class should never be used in
 * production and only for building debug tools.
 */
class DebugLayoutNode internal constructor(private val result: LithoLayoutResult) {

  private val node: LithoNode = result.node

  /**
   * The Yoga node Yoga laid it out with, which holds its style: its own, or a `Layout`'s in a Yoga
   * tree. Null for a `Layout` laid out on its own or under a `Layout`, and for a `Layout`'s leaf.
   */
  private val yogaNode: YogaNode?
    get() =
        when (val output = result.measureOutput) {
          is YogaLayoutOutput -> output.yogaNode
          is DefaultMeasureOutput -> output.yogaNode
          is LeafLayoutOutput -> null
        }

  /**
   * Its flexbox style, and its box style when it has no [boxProps]; with no Yoga node, Yoga's
   * defaults.
   */
  private val styleNode: YogaNode
    get() = yogaNode ?: defaultStyle

  /**
   * Its box style, which a `Layout`'s builder records off its node: a `Layout`'s child, or a root
   * `Layout`. What it doesn't declare is Yoga's default: its root's Yoga node holds the box it was
   * laid out in, not its style.
   */
  private val boxProps: BoxLayoutProps?
    get() = result.childBoxProps

  val foreground: Drawable?
    get() = node.foreground

  val background: Drawable?
    get() = node.background

  fun hasViewOutput(): Boolean = LithoNode.hasViewOutput(result.node)

  val rotation: Float
    get() = node.nodeInfo?.rotation ?: 0f

  val alpha: Float
    get() = node.nodeInfo?.alpha ?: 1f

  val scale: Float
    get() = node.nodeInfo?.scale ?: 1f

  val importantForAccessibility: Int
    get() = node.importantForAccessibility

  val focusable: Boolean
    get() = node.nodeInfo?.focusState == NodeInfo.FOCUS_SET_TRUE

  val screenReaderFocusable: Boolean
    get() = node.nodeInfo?.screenReaderFocusState == NodeInfo.SCREEN_READER_FOCUS_SET_TRUE

  val requestInitialAccessibilityFocus: Boolean
    get() =
        node.nodeInfo?.initialFocusState == NodeInfo.REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_TRUE

  val contentDescription: CharSequence?
    get() = node.nodeInfo?.contentDescription

  val layoutDirection: YogaDirection
    get() = styleNode.styleDirection

  val flexDirection: YogaFlexDirection
    get() = styleNode.flexDirection

  val justifyContent: YogaJustify
    get() = styleNode.justifyContent

  val alignItems: YogaAlign
    get() = styleNode.alignItems

  val alignSelf: YogaAlign
    get() = styleNode.alignSelf

  val alignContent: YogaAlign
    get() = styleNode.alignContent

  val positionType: YogaPositionType
    get() = styleNode.positionType

  val wrap: YogaWrap
    get() = styleNode.wrap

  val flexGrow: Float
    get() = styleNode.flexGrow

  val flexShrink: Float
    get() = styleNode.flexShrink

  val flexBasis: YogaValue
    get() = styleNode.flexBasis

  val width: YogaValue
    get() = boxProps?.run { width.toYogaValue() ?: defaultStyle.width } ?: styleNode.width

  val minWidth: YogaValue
    get() = boxProps?.run { minWidth.toYogaValue() ?: defaultStyle.minWidth } ?: styleNode.minWidth

  val maxWidth: YogaValue
    get() = boxProps?.run { maxWidth.toYogaValue() ?: defaultStyle.maxWidth } ?: styleNode.maxWidth

  val height: YogaValue
    get() = boxProps?.run { height.toYogaValue() ?: defaultStyle.height } ?: styleNode.height

  val minHeight: YogaValue
    get() =
        boxProps?.run { minHeight.toYogaValue() ?: defaultStyle.minHeight } ?: styleNode.minHeight

  val maxHeight: YogaValue
    get() =
        boxProps?.run { maxHeight.toYogaValue() ?: defaultStyle.maxHeight } ?: styleNode.maxHeight

  val aspectRatio: Float
    get() =
        boxProps?.run { aspectRatio.takeUnless { it.isNaN() } ?: defaultStyle.aspectRatio }
            ?: styleNode.aspectRatio

  fun getMargin(edge: YogaEdge): YogaValue =
      boxProps?.run { margin[edge.toBoxEdge()].toYogaValue() ?: defaultStyle.getMargin(edge) }
          ?: styleNode.getMargin(edge)

  fun getPadding(edge: YogaEdge): YogaValue =
      boxProps?.run { padding[edge.toBoxEdge()].toYogaValue() ?: defaultStyle.getPadding(edge) }
          ?: styleNode.getPadding(edge)

  fun getPosition(edge: YogaEdge): YogaValue = styleNode.getPosition(edge)

  /**
   * With no Yoga node its edges are its output's. So for START and END it answers the resolved
   * edge, where a Yoga node answers its style, which `Border` declares only on physical edges
   * (NaN).
   */
  fun getBorderWidth(edge: YogaEdge): Float =
      yogaNode?.getBorder(edge)
          ?: borderOf(result).at(edge, result.measureOutput.layoutDirection)
          ?: 0f

  val commonDynamicProps: SparseArray<DynamicValue<*>>?
    get() = LithoNodeUtils.mergeCommonDynamicProps(node.scopedComponentInfos)

  val clickHandler: EventHandler<ClickEvent>?
    get() = node.nodeInfo?.clickHandler

  val touchHandler: EventHandler<TouchEvent>?
    get() = node.nodeInfo?.touchHandler

  /** The class name a screen reader announces this node as, e.g. [AccessibilityRole.SWITCH]. */
  val accessibilityRole: String?
    get() = node.nodeInfo?.accessibilityRole

  val accessibilityRoleDescription: CharSequence?
    get() = node.nodeInfo?.accessibilityRoleDescription

  val stateDescription: CharSequence?
    get() = node.nodeInfo?.stateDescription

  /** Null when not declared. */
  val selected: Boolean?
    get() =
        when (node.nodeInfo?.selectedState) {
          NodeInfo.SELECTED_SET_TRUE -> true
          NodeInfo.SELECTED_SET_FALSE -> false
          else -> null
        }

  /** Null when not declared. */
  val enabled: Boolean?
    get() =
        when (node.nodeInfo?.enabledState) {
          NodeInfo.ENABLED_SET_TRUE -> true
          NodeInfo.ENABLED_SET_FALSE -> false
          else -> null
        }

  val accessibilityHeading: Boolean
    get() = node.nodeInfo?.accessibilityHeadingState == NodeInfo.ACCESSIBILITY_HEADING_SET_TRUE

  // The laid-out geometry is read from the result, not its Yoga node: a `Layout`'s output has its
  // own, and a `Layout` child's margins are applied by its container.

  val layoutWidth: Float
    get() = result.width.toFloat()

  val layoutHeight: Float
    get() = result.height.toFloat()

  fun getLayoutMargin(edge: YogaEdge): Float =
      result.resolvedMargins?.at(edge, result.measureOutput.layoutDirection)
          ?: styleNode.getLayoutMargin(edge)

  /** Yoga's own value, unrounded, for a node Yoga laid out; otherwise its output's. */
  fun getLayoutPadding(edge: YogaEdge): Float {
    val output = result.measureOutput
    return yogaNode?.getLayoutPadding(edge)
        ?: IntEdges(
            output.paddingLeft,
            output.paddingTop,
            output.paddingRight,
            output.paddingBottom,
        )
            .at(edge, output.layoutDirection)
        ?: 0f
  }

  fun getLayoutBorderWidth(edge: YogaEdge): Float =
      yogaNode?.getLayoutBorder(edge)
          ?: borderOf(result).at(edge, result.measureOutput.layoutDirection)
          ?: 0f

  /** As Yoga holds it: null when not declared, so Yoga's default answers. */
  private fun BoxLength.toYogaValue(): YogaValue? =
      when (this) {
        is BoxLength.Px -> YogaValue(value, YogaUnit.POINT)
        is BoxLength.Percent -> YogaValue(value, YogaUnit.PERCENT)
        BoxLength.Unset -> null
      }

  private fun YogaEdge.toBoxEdge(): BoxEdge = BoxEdge.valueOf(name)

  private fun borderOf(result: LithoLayoutResult): IntEdges =
      IntEdges(result.borderLeft, result.borderTop, result.borderRight, result.borderBottom)

  private fun IntEdges.at(edge: YogaEdge, direction: LayoutDirection): Float? {
    val isRtl = direction.isRTL
    return when (edge) {
      YogaEdge.LEFT -> left
      YogaEdge.TOP -> top
      YogaEdge.RIGHT -> right
      YogaEdge.BOTTOM -> bottom
      YogaEdge.START -> if (isRtl) right else left
      YogaEdge.END -> if (isRtl) left else right
      else -> null
    }?.toFloat()
  }
}

/**
 * This result's layout as Litho's inspector reads it, from its tail component.
 *
 * Test helper for reading a result's margins or Yoga style without depending on Yoga internals.
 */
fun LithoLayoutResult.debugLayoutNode(): DebugLayoutNode {
  var component = checkNotNull(DebugComponent.getInstance(this, 0, 0))
  while (!component.isLayoutNode) component = component.childComponents.single()
  return checkNotNull(component.layoutNode)
}

/** Yoga's default style, never written: what a result with no Yoga node reads as. */
private val defaultStyle: YogaNode by lazy { NodeConfig.createYogaNode() }
