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

import android.graphics.Rect
import androidx.annotation.Px
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.layout.BoxLayoutProps
import com.facebook.litho.layout.IntEdges
import com.facebook.litho.layout.internal.DefaultLayoutBuilder
import com.facebook.litho.state.StateId
import com.facebook.rendercore.LayoutResult
import com.facebook.yoga.YogaMeasureOutput

/**
 * This is the default implementation of a [LayoutResult] for Litho. Its [measureOutput] is the
 * geometry its engine built; the result itself is the record of its pass: how its content was
 * measured, what it was reused from, and what it mounts.
 */
open class LithoLayoutResult(
    val context: ComponentContext,
    open val node: LithoNode,
    measureOutput: LithoMeasureOutput,
) : LayoutResult {

  /**
   * The geometry its engine built. A `Layout` in a Yoga tree is laid out by Yoga, then takes the
   * output its builder builds in the box Yoga settled.
   */
  var measureOutput: LithoMeasureOutput = measureOutput
    internal set

  private val children: MutableList<LithoLayoutResult> = ArrayList()

  /** The specs it was last measured or sized under, which its DiffNode keeps for the next pass. */
  var widthSpec: Int = DiffNode.UNSPECIFIED
    private set

  var heightSpec: Int = DiffNode.UNSPECIFIED
    private set

  /** What measuring produced that mounts: a Primitive's delegate. */
  var delegate: LayoutResult? = null
    internal set

  /**
   * Whether measuring its content or its `@OnBoundsDefined` threw, or its deferred content failed
   * to resolve.
   */
  var measureHadExceptions: Boolean = false
    internal set

  /** Whether its content's measure ran this pass, even if it took the size from its DiffNode. */
  var wasMeasured: Boolean = false
    internal set

  /**
   * The size its content last measured to, inside its padding and border, packed with
   * `YogaMeasureOutput`; `Long.MIN_VALUE` until it is measured.
   */
  var lastMeasuredSize: Long = Long.MIN_VALUE
    internal set

  val contentWidth: Int
    get() = YogaMeasureOutput.getWidth(lastMeasuredSize).toInt()

  val contentHeight: Int
    get() = YogaMeasureOutput.getHeight(lastMeasuredSize).toInt()

  var diffNode: DiffNode? = null
    internal set

  var isCachedLayout: Boolean = false
    internal set

  /** Whether its content's measurement was taken from its DiffNode. */
  var isDiffedLayout: Boolean = false
    internal set

  /** Whether its content's last-pass measurements, on its DiffNode, may be reused. */
  var cachedMeasuresValid: Boolean = false
    internal set

  /** Whether a MountSpec's `@OnBoundsDefined` outputs are in [layoutData]. */
  internal var boundsDefined: Boolean = false

  internal var actualDeferredNodeResult: LithoLayoutResult? = null

  /**
   * The [useOnDiscard] callbacks of the renders of the tree resolved for
   * [actualDeferredNodeResult].
   */
  internal var nestedDiscardCallbacks: DiscardCallbacks? = null

  private var _stateReads: MutableScatterSet<StateId>? = null
  internal val stateReads: ScatterSet<StateId>?
    get() = _stateReads

  internal fun addStateReads(stateIds: ScatterSet<StateId>) {
    if (stateIds.isEmpty()) return
    val stateReads =
        _stateReads ?: MutableScatterSet<StateId>(stateIds.size).also { _stateReads = it }
    stateReads.addAll(stateIds)
  }

  /** Adds [stateIds], taking the set itself when this result has none: the caller gives it up. */
  internal fun adoptStateReads(stateIds: MutableScatterSet<StateId>) {
    if (_stateReads == null) _stateReads = stateIds else addStateReads(stateIds)
  }

  internal fun addStateRead(stateId: StateId) {
    val stateReads = _stateReads ?: MutableScatterSet<StateId>(1).also { _stateReads = it }
    stateReads.add(stateId)
  }

  /** What a `Layout`'s function or placement threw, reported only if it reaches the final tree. */
  internal var failure: Exception? = null

  /**
   * The box props a `Layout`'s builder resolved this result's box from, kept off its node: a
   * `Layout`'s child, or a root `Layout`. Null for any other result: Yoga resolves its box from its
   * node.
   */
  internal var childBoxProps: BoxLayoutProps? = null

  /** The margins a `Layout`'s child, or a root `Layout`, was resolved with; null for any other. */
  internal var resolvedMargins: IntEdges? = null

  /**
   * The builder a `Layout` in a Yoga tree holds on its result until the tree is laid out and it's
   * built, or the tree is dropped: then it's discarded, or kept for the next tree its builder
   * makes. Null after either, and for any other result.
   */
  internal var defaultLayoutBuilder: DefaultLayoutBuilder? = null

  internal fun setSizeSpec(widthSpec: Int, heightSpec: Int) {
    this.widthSpec = widthSpec
    this.heightSpec = heightSpec
  }

  /** Records that its content measured [contentWidth] x [contentHeight] under these specs. */
  internal fun recordMeasurement(
      widthSpec: Int,
      heightSpec: Int,
      contentWidth: Int,
      contentHeight: Int,
  ) {
    this.widthSpec = widthSpec
    this.heightSpec = heightSpec
    lastMeasuredSize = YogaMeasureOutput.make(contentWidth, contentHeight)
  }

  /**
   * Takes [source]'s record as a copy from the layout cache, for a result just created; its
   * geometry is the engine's to copy. Its measurements are valid for this pass, and what belongs to
   * a pass that ran is reset. The bounds and the state reads are copied, since concurrent layouts
   * can still reach the source, and added to this result's own; the rest is shared.
   */
  internal fun copyCachedFrom(source: LithoLayoutResult) {
    widthSpec = source.widthSpec
    heightSpec = source.heightSpec
    lastMeasuredSize = source.lastMeasuredSize
    isCachedLayout = true
    isDiffedLayout = false
    cachedMeasuresValid = true
    wasMeasured = false
    measureHadExceptions = false
    diffNode = source.diffNode
    layoutData = source.layoutData
    delegate = source.delegate
    boundsDefined = source.boundsDefined
    contentRenderUnit = source.contentRenderUnit
    hostRenderUnit = source.hostRenderUnit
    borderRenderUnit = source.borderRenderUnit
    effects = source.effects
    adjustedBounds.set(source.adjustedBounds)
    source.stateReads?.let(::addStateReads)
    childBoxProps = source.childBoxProps
    // Not carried: no deferred node's content is cached; only a `Layout`'s result fails, and it's
    // never cached; and the builder that builds a copy sets its margins.
  }

  val childCount: Int
    get() = children.size

  val touchExpansionTop: Int
    get() = node.touchExpansionTop()

  val touchExpansionBottom: Int
    get() = node.touchExpansionBottom()

  val touchExpansionLeft: Int
    get() = node.touchExpansionLeft(isDirectionRtl())

  val touchExpansionRight: Int
    get() = node.touchExpansionRight(isDirectionRtl())

  val expandedTouchBounds: Rect?
    get() {
      // Manual, developer-set per-edge touch expansion. The per-edge getters already return 0 when
      // the node has no touch expansion set or has no attached touch event handler (see
      // LithoNode.shouldApplyTouchExpansion).
      val hasManualExpansion = node.hasTouchExpansion()
      var left = if (hasManualExpansion) touchExpansionLeft else 0
      var top = if (hasManualExpansion) touchExpansionTop else 0
      var right = if (hasManualExpansion) touchExpansionRight else 0
      var bottom = if (hasManualExpansion) touchExpansionBottom else 0

      // Grow an interactive component's touch target to the configured minimum, augmenting any
      // manual expansion. Only the touch area changes, not the visual bounds.
      if (
          ComponentsConfiguration.useAutoTouchExpansion &&
              node.nodeInfo?.hasTouchEventHandlers() == true
      ) {
        val minPx =
            context.resourceResolver.dipsToPixels(
                ComponentsConfiguration.minTouchTargetSizeDp.toFloat(),
            )
        val deficitX = minPx - (width + left + right)
        if (deficitX > 0) {
          val extraLeft = deficitX / 2
          left += extraLeft
          right += deficitX - extraLeft
        }
        val deficitY = minPx - (height + top + bottom)
        if (deficitY > 0) {
          val extraTop = deficitY / 2
          top += extraTop
          bottom += deficitY - extraTop
        }
      }

      return if (left == 0 && top == 0 && right == 0 && bottom == 0) {
        null
      } else {
        Rect(left, top, right, bottom)
      }
    }

  var contentRenderUnit: LithoRenderUnit? = null
    internal set

  var hostRenderUnit: LithoRenderUnit? = null
    internal set

  var borderRenderUnit: LithoRenderUnit? = null
    internal set

  var effects: List<Attachable>? = null
    internal set

  @get:Px
  override val width: Int
    get() = measureOutput.width

  @get:Px
  override val height: Int
    get() = measureOutput.height

  @get:Px
  override val paddingTop: Int
    get() = measureOutput.paddingTop

  @get:Px
  override val paddingRight: Int
    get() = measureOutput.paddingRight

  @get:Px
  override val paddingBottom: Int
    get() = measureOutput.paddingBottom

  @get:Px
  override val paddingLeft: Int
    get() = measureOutput.paddingLeft

  override val renderUnit: LithoRenderUnit?
    get() = null // Unimplemented.

  override val childrenCount: Int
    get() = children.size

  override fun getChildAt(index: Int): LithoLayoutResult = children[index]

  override fun getXForChildAtIndex(index: Int): Int = measureOutput.childX(index)

  override fun getYForChildAtIndex(index: Int): Int = measureOutput.childY(index)

  /** What its content's measure and `@OnBoundsDefined` produced that mounts. */
  override var layoutData: Any? = null
    internal set

  val borderTop: Int
    get() = measureOutput.borderTop

  val borderRight: Int
    get() = measureOutput.borderRight

  val borderBottom: Int
    get() = measureOutput.borderBottom

  val borderLeft: Int
    get() = measureOutput.borderLeft

  /**
   * To avoid redundant calculations in [LithoYogaLayoutFunction.adjustRenderUnitBounds], we save
   * the adjustments in a Rect that is initialised during layout, specifically inside
   * [LithoYogaLayoutFunction.onBoundsDefined].
   */
  internal val adjustedBounds: Rect = Rect()

  fun adjustedLeft(): Int = adjustedBounds.left

  fun adjustedTop(): Int = adjustedBounds.top

  fun adjustedRight(): Int = adjustedBounds.right

  fun adjustedBottom(): Int = adjustedBounds.bottom

  /**
   * Since layout data like the layout context and the diff node are not required after layout
   * calculation they can be released to free up memory.
   */
  open fun releaseLayoutPhaseData() {
    diffNode = null
    measureOutput.releaseLayoutContext()
    for (i in 0 until childCount) {
      getChildAt(i).releaseLayoutPhaseData()
    }
  }

  /**
   * Called once for each result in a pass's final tree, after layout and before the tree is
   * reduced, for work only a result in that tree may do. Most results have none.
   */
  internal fun onFinalized() {
    // What a `Layout`'s function or placement threw reaches its error boundary only now, so a
    // failure in a child nothing placed is never reported.
    val thrown = failure ?: return
    failure = null
    ComponentUtils.handle(node.tailComponentContext, thrown)
  }

  /** Whether this result is a `Layout`'s, or holds one below it. */
  internal var subtreeHasCustomLayout: Boolean = false

  fun addChild(child: LithoLayoutResult) {
    children.add(child)
  }

  private fun isDirectionRtl(): Boolean = measureOutput.layoutDirection.isRTL
}
