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

package com.facebook.litho.layout.internal

import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.mutableScatterSetOf
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.CustomLayoutShadowValidation
import com.facebook.litho.DefaultMeasureOutput
import com.facebook.litho.DeferredLithoNode
import com.facebook.litho.DiffNode
import com.facebook.litho.GlobalKey
import com.facebook.litho.LithoLayoutContext
import com.facebook.litho.LithoLayoutResult
import com.facebook.litho.LithoNode
import com.facebook.litho.LithoYogaLayoutFunction
import com.facebook.litho.NO_POSITIONS
import com.facebook.litho.YogaLayoutOutput.Companion.yogaLayoutOutput
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.isReadTrackingEnabled
import com.facebook.litho.layout.BoxConstraints
import com.facebook.litho.layout.BoxLayoutProps
import com.facebook.litho.layout.LayoutDirection
import com.facebook.litho.layout.LayoutProps
import com.facebook.litho.layout.Measurable
import com.facebook.litho.layout.MeasureScope
import com.facebook.litho.layout.NO_SIZE_DECLARED
import com.facebook.litho.layout.Placeable
import com.facebook.litho.layout.PlacementScope
import com.facebook.litho.layout.axisSize
import com.facebook.litho.layout.borderBoxSizeAround
import com.facebook.litho.layout.contentSpec
import com.facebook.litho.layout.readLayoutProps
import com.facebook.litho.layout.resolveBoxConstraints
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.stats.LithoStats
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.LayoutContext
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import com.facebook.yoga.YogaMeasureOutput

/**
 * Lays out a `Layout` for one pass. Each measure runs its layout function, unless its latest run
 * already fits the box; its build places from a run that fits the box it's built in. It holds what
 * the pass makes of its children, their builders and placeables, so a `Measurable` is only data.
 *
 * Its parent decides each box, and the two kinds of parent decide them differently:
 * ```
 *                 parent is a `Layout`                 parent is Yoga
 *   measured      measure(box): the first time the     measureUnder(room): each time Yoga's
 *                 parent's function measures it        measure callback asks, within the
 *                 under each set of constraints        room Yoga offers
 *   built         build(box): the box of the           buildInYogaTree(result, box): its
 *                 placeable the parent's function      final size, which Yoga may not have
 *                 placed, one it was measured in       measured: a stretch is decided after
 *                                                      measuring
 * ```
 *
 * Laid out on its own, as the root or as deferred content, `CustomLayoutFunction.Container`
 * resolves its box with `resolveRootBox` and builds it with [buildRoot].
 */
@OptIn(ExperimentalLithoApi::class)
internal class DefaultLayoutBuilder(
    private val context: LayoutContext<LithoLayoutContext>,
    private val node: LithoNode,
    previousDiffNode: DiffNode?,
) : LithoLayoutBuilder {

  // Its DiffNode from the last pass, if that was for the same kind of component.
  private val previousDiffNode: DiffNode? =
      context.renderContext?.let { renderContext ->
        LithoYogaLayoutFunction.validDiffNode(renderContext, node, previousDiffNode)
      }
  private var previousChildrenByKey: MutableScatterMap<GlobalKey, DiffNode>? = null

  private val layoutDirection: LayoutDirection =
      if (node.layoutDirection.isRTL) LayoutDirection.RTL else LayoutDirection.LTR

  private val measurables: List<Measurable> =
      List(node.childCount) { index ->
        Measurable(index, owner = this)
      }

  // By child index. Most children are measured under one set of constraints, so their first
  // placeable needs no map.
  private val childBuilders: Array<LithoLayoutBuilder?> = arrayOfNulls(node.childCount)
  private val firstPlaceables: Array<Placeable?> = arrayOfNulls(node.childCount)
  private var morePlaceables: Array<MutableLongObjectMap<Placeable>?>? = null

  // The latest run of its layout function: the content constraints it ran under, and what it
  // declared or what it threw.
  private var declaredUnder: SizeConstraints = SizeConstraints()
  private var declared: MeasureScope? = null
  private var functionFailure: Exception? = null
  private var placementFailure: Exception? = null

  private var stateReads: MutableScatterSet<StateId>? = null

  private val tracksReads = node.tailComponentContext.isReadTrackingEnabled
  private var stage = Stage.MEASURING

  private var recordedBoxProps: BoxLayoutProps? = null

  /** Its node's props, recorded the first time they're asked for, with no Yoga node. */
  override val boxProps: BoxLayoutProps
    get() {
      check(stage == Stage.MEASURING) {
        "${node.headComponent.simpleName} box props read after it was built or discarded"
      }
      return recordedBoxProps ?: recordBoxProps().also { recorded -> recordedBoxProps = recorded }
    }

  override fun measure(box: BoxConstraints): IntSize {
    check(stage == Stage.MEASURING) {
      "${node.headComponent.simpleName} measured after it was built or discarded"
    }
    declareUnder(box.contentConstraints())
    return borderBoxSizeIn(box)
  }

  /** Its size under [room], with no padding or border: as Yoga measures a node's content. */
  internal fun measureUnder(room: SizeConstraints): IntSize {
    check(stage == Stage.MEASURING) {
      "${node.headComponent.simpleName} measured by Yoga after it was built or discarded"
    }
    declareUnder(room)
    val width = declared?.width ?: 0
    val height = declared?.height ?: 0
    return IntSize(
        axisSize(room.hasExactWidth, room.minWidth, room.maxWidth, width, edges = 0),
        axisSize(room.hasExactHeight, room.minHeight, room.maxHeight, height, edges = 0),
    )
  }

  override fun build(box: BoxConstraints): LithoLayoutResult =
      build(box, holderConstraints = null, yogaTreeResult = null)

  /**
   * Builds it as the root, in [box], resolved within [holderConstraints], the constraints its
   * holder gave: its result keeps them as its specs, and its margins, where its holder places it.
   */
  fun buildRoot(box: BoxConstraints, holderConstraints: SizeConstraints): LithoLayoutResult =
      build(box, holderConstraints, yogaTreeResult = null)

  /**
   * Builds it into [result], the one Yoga laid it out as in a Yoga tree, within [box], the box Yoga
   * settled: [result] takes this output in place of Yoga's, at the position Yoga gave it.
   *
   * [box]'s padding is Yoga's, rounded per edge, while Yoga measured it with its float padding. A
   * percent padding can then give content constraints a pixel off the ones it measured under, and
   * its function runs once more.
   */
  fun buildInYogaTree(result: LithoLayoutResult, box: BoxConstraints) {
    build(box, holderConstraints = null, yogaTreeResult = result)
  }

  private fun build(
      box: BoxConstraints,
      holderConstraints: SizeConstraints?,
      yogaTreeResult: LithoLayoutResult?,
  ): LithoLayoutResult {
    check(stage == Stage.MEASURING) {
      "${node.headComponent.simpleName} built twice, or after it was discarded"
    }
    stage = Stage.DONE
    declareUnder(box.contentConstraints())
    val size = borderBoxSizeIn(box)
    val placementScope = placeChildren()
    val positions = newPositions(placementScope)
    val output = DefaultMeasureOutput(
        size.width,
        size.height,
        box.padding,
        box.border,
        layoutDirection,
        positions = positions,
        yogaNode = yogaTreeResult?.yogaLayoutOutput?.yogaNode,
    )
    val result =
        if (yogaTreeResult == null) {
          node.createLayoutResult(output).also { result ->
            result.diffNode = previousDiffNode
            result.resolvedMargins = box.margins
            result.childBoxProps = recordedBoxProps
          }
        } else {
          yogaTreeResult.defaultLayoutBuilder = null
          yogaTreeResult.measureOutput = output
          yogaTreeResult
        }
    // The constraints its function last ran under, which a run reused for an exact box at the size
    // it declared stands for; laid out on its own, the ones its holder gave, which deferred content
    // is reused by.
    val specs = holderConstraints ?: declaredUnder
    result.setSizeSpec(specs.toWidthSpec(), specs.toHeightSpec())
    val contentWidth = (size.width - box.edgesAcross).coerceAtLeast(0)
    val contentHeight = (size.height - box.edgesDown).coerceAtLeast(0)
    result.lastMeasuredSize = YogaMeasureOutput.make(contentWidth, contentHeight)
    buildChildrenInto(result, box, contentWidth, placementScope, positions)
    return result
  }

  // Its own result carries all it read: what its children made but it didn't build is its own.
  override val unbuiltReads: ScatterSet<StateId>?
    get() = null

  /**
   * Runs the placement of its latest run, and discards each child it didn't place: such a child
   * never reaches the final tree, so this `Layout` carries its reads. Null if there's no run to
   * place from, or the placement threw.
   */
  private fun placeChildren(): PlacementScope? {
    val declared = declared
    var placementScope: PlacementScope? = null
    if (declared != null) {
      val scope = PlacementScope(
          node.tailComponentContext.resourceResolver,
          node.childCount,
          declared.width,
          layoutDirection,
          owner = this,
      )
      placementFailure =
          recordingReads("layout placement") {
            trace({ "layout.placement:${node.headComponent.simpleName}" }) {
              checkNotNull(declared.placement)(scope)
            }
          }
      if (placementFailure == null) placementScope = scope
    }
    for (i in 0 until node.childCount) {
      if (placementScope?.placedWith?.get(i) == null) {
        childBuilders[i]?.discard()?.let(::addReads)
      }
    }
    return placementScope
  }

  /** Room for where each child [placementScope] placed goes, filled as it's built. */
  private fun newPositions(placementScope: PlacementScope?): IntArray {
    val placedCount = placementScope?.placedCount ?: 0
    return if (placedCount == 0) NO_POSITIONS else IntArray(placedCount * 2)
  }

  /**
   * Adds to [result] each child [placementScope] placed, built, with its position in [positions],
   * and what this `Layout`'s function and placement threw and read.
   */
  private fun buildChildrenInto(
      result: LithoLayoutResult,
      box: BoxConstraints,
      contentWidth: Int,
      placementScope: PlacementScope?,
      positions: IntArray,
  ) {
    result.failure = functionFailure ?: placementFailure
    // The reducer and the layout cache treat a subtree holding a `Layout` apart.
    result.subtreeHasCustomLayout = true
    if (placementScope != null) {
      buildPlacedChildrenInto(result, box, contentWidth, placementScope, positions)
    }
    // Last: building its children adds what they made but didn't build.
    stateReads?.let { reads ->
      stateReads = null
      result.adoptStateReads(reads)
    }
  }

  private fun buildPlacedChildrenInto(
      result: LithoLayoutResult,
      box: BoxConstraints,
      contentWidth: Int,
      placementScope: PlacementScope,
      positions: IntArray,
  ) {
    val left = box.padding.left + box.border.left
    val top = box.padding.top + box.border.top
    // Where a child's border box sits across; down is the same, with no start:
    //
    //   |border+padding|<-------------------- content width -------------------->|
    //   |   = left     |<-- start -->|<------------- declared width ------------>|
    //   |              |             |<-- x -->|<- offsetX ->|<- margin ->|child
    //
    // start is 0 left to right; right to left, the declared width ends at the content's right
    // edge. x is where the placement put the child's placeable. offsetX centers what the child
    // measured within its placeable's width, which differ when the measured size didn't fit the
    // child's constraints. Its border box starts after its left margin.
    val start = if (layoutDirection.isRTL) contentWidth - checkNotNull(declared).width else 0
    for (order in 0 until placementScope.placedCount) {
      val index = placementScope.placedOrder[order]
      val placeable = checkNotNull(placementScope.placedWith[index])
      val slot = result.childCount * 2
      positions[slot] =
          left +
              start +
              placementScope.placements[index * 2] +
              placeable.offsetX +
              placeable.marginLeft
      positions[slot + 1] =
          top + placementScope.placements[index * 2 + 1] + placeable.offsetY + placeable.marginTop
      result.addChild(buildChildFor(index, placeable))
    }
  }

  override fun discard(): ScatterSet<StateId>? {
    if (stage == Stage.DONE) return null
    stage = Stage.DONE
    for (builder in childBuilders) builder?.discard()?.let(::addReads)
    return stateReads.also { stateReads = null }
  }

  /** [child] measured under [constraints]; measured again under them, the same placeable. */
  internal fun measureChildUnder(child: Measurable, constraints: SizeConstraints): Placeable {
    // Per builder: a nested Layout's function, running inside this one's, can still measure one of
    // this one's children through a Measurable it was handed, and that measurement is this one's.
    check(stage == Stage.RUNNING_FUNCTION) {
      "${node.headComponent.simpleName}'s children can only be measured by its layout function, " +
          "while it runs: not from its placement block, and not through a Measurable kept from " +
          "an earlier pass."
    }
    placeableUnder(child.index, constraints)?.let { placeable ->
      LithoStats.count(LithoStats.customLayoutMeasurementReusedCount)
      return placeable
    }
    return newPlaceableUnder(child.index, constraints).also { placeable ->
      remember(child.index, placeable)
    }
  }

  /**
   * Child [index] measured under [constraints] by its builder, which its first measure makes.
   * Untracked: the child's reads land on its own result if it's built, and on this `Layout`'s
   * through [LithoLayoutBuilder.discard] if it isn't.
   */
  private fun newPlaceableUnder(index: Int, constraints: SizeConstraints): Placeable {
    val child = node.getChildAt(index)
    var builder = childBuilders[index]
    if (builder == null) {
      builder = child.newLayoutBuilder(context, previousDiffNodeOf(index))
      childBuilders[index] = builder
    }
    LithoStats.count(LithoStats.customLayoutChildLayoutCount)
    if (builder is DefaultLayoutBuilder) {
      LithoStats.count(LithoStats.customLayoutNestedContainerCount)
    }
    // Untracked: the first box asked for makes the child's tree, which records its own reads, and
    // the child's measure records its own.
    var measuredBox: BoxConstraints? = null
    val size = StateReadRecorder.untracked {
      val boxConstraints = boxConstraintsUnder(index, constraints)
      measuredBox = boxConstraints
      trace({ "layout.measureChild:${child.headComponent.simpleName}" }) {
        builder.measure(boxConstraints)
      }
    }
    val box = checkNotNull(measuredBox)
    val margins = box.margins
    return Placeable(
        measurables[index],
        constraints,
        IntSize(
            (size.width + margins.horizontal).coerceAtLeast(0),
            (size.height + margins.vertical).coerceAtLeast(0),
        ),
        box,
    )
  }

  /** Child [index]'s result, built by its builder within the box [placeable] measured it in. */
  private fun buildChildFor(index: Int, placeable: Placeable): LithoLayoutResult {
    val builder = checkNotNull(childBuilders[index])
    val box = placeable.box
    val constraints = placeable.constraints
    val result = StateReadRecorder.untracked { builder.build(box) }
    builder.unbuiltReads?.let(::addReads)
    // A `Layout` child's own children were checked as it built them.
    if (builder !is DefaultLayoutBuilder) {
      CustomLayoutShadowValidation.checkReused(context, node.getChildAt(index), constraints, result)
    }
    return result
  }

  /**
   * Child [index]'s DiffNode from the last pass. Only the children it placed have one, so it's
   * found by the child's key rather than by its index, which a `Layout` that places some children
   * and not others would hand to the wrong sibling. The DiffNode tree is transparent to a deferred
   * child: its place holds its content's DiffNode, keyed under the holder's key.
   */
  private fun previousDiffNodeOf(index: Int): DiffNode? {
    val parent = previousDiffNode ?: return null
    val child = node.getChildAt(index)
    val key = child.tailComponentKey
    val deferred = child is DeferredLithoNode
    fun isFor(diff: DiffNode): Boolean =
        diff.componentGlobalKey == key || (deferred && diff.componentGlobalKey.isDescendantOf(key))
    val atIndex = if (index < parent.childCount) parent.getChildAt(index) else null
    if (atIndex != null && isFor(atIndex)) {
      return atIndex
    }
    val keyed = previousChildrenByKey(parent)[key] ?: return null
    return if (deferred || keyed.componentGlobalKey == key) keyed else null
  }

  /**
   * [parent]'s children by key, made once: each under its own key, then under each key above it up
   * to this `Layout`'s, where a deferred child's content's DiffNode is found. A key above keeps the
   * first child in index order, and never takes the place of a child's own key.
   */
  private fun previousChildrenByKey(parent: DiffNode): MutableScatterMap<GlobalKey, DiffNode> {
    previousChildrenByKey?.let { map ->
      return map
    }
    val map = MutableScatterMap<GlobalKey, DiffNode>(parent.childCount)
    for (i in 0 until parent.childCount) {
      parent.getChildAt(i)?.let { child -> map[child.componentGlobalKey] = child }
    }
    val ownKey = node.tailComponentKey
    for (i in 0 until parent.childCount) {
      val child = parent.getChildAt(i) ?: continue
      var ancestorKey = child.componentGlobalKey.parent
      while (ancestorKey != null && ancestorKey != ownKey) {
        if (ancestorKey !in map) map[ancestorKey] = child
        ancestorKey = ancestorKey.parent
      }
    }
    previousChildrenByKey = map
    return map
  }

  /** The [LayoutProps] of [type] child [index] carries, or null. */
  internal fun <T : LayoutProps> layoutPropsOf(index: Int, type: Class<T>): T? =
      node.getChildAt(index).readLayoutProps(type)

  /** The box child [index]'s own props resolve to within [constraints]. */
  private fun boxConstraintsUnder(
      index: Int,
      constraints: SizeConstraints,
  ): BoxConstraints = resolveBoxConstraints(
      constraints,
      checkNotNull(childBuilders[index]).boxProps,
      node.getChildAt(index).layoutDirection.isRTL,
  )

  /** Its node's box props; the state they read is its own. */
  private fun recordBoxProps(): BoxLayoutProps = recordBoxPropsOf(context, node, ::addReads)

  private fun placeableUnder(index: Int, constraints: SizeConstraints): Placeable? {
    val first = firstPlaceables[index] ?: return null
    if (first.constraints == constraints) {
      return first
    }
    return morePlaceables?.get(index)?.get(constraints.encodedValue)
  }

  private fun remember(index: Int, placeable: Placeable) {
    if (firstPlaceables[index] == null) {
      firstPlaceables[index] = placeable
      return
    }
    val more =
        morePlaceables
            ?: arrayOfNulls<MutableLongObjectMap<Placeable>>(node.childCount).also {
                placeablesByChild ->
              morePlaceables = placeablesByChild
            }
    val placeables =
        more[index]
            ?: MutableLongObjectMap<Placeable>(2).also { placeables -> more[index] = placeables }
    placeables[placeable.constraints.encodedValue] = placeable
  }

  /**
   * Runs its layout function under [constraints], its content's, unless the latest run was under
   * them or, when they're exact, declared exactly that size. A failure is final for the pass: the
   * parent already laid out around its 0 x 0, so a later run that succeeds would be placed at a
   * size nothing measured.
   *
   * Only the latest run is kept, and a build usually finds it fits (content widths, no padding;
   * heights are exact and equal throughout):
   * ```
   *   under a `Layout`: its function usually places what it measured last
   *     measure(at most 300)  -> run A, declares 200
   *     measure(exactly 250)  -> run B, declares 250          latest: B
   *     then one of:
   *     build(exactly 250)    -> B ran under those constraints: fits, no run
   *     build(at most 300)    -> B didn't: runs again (a function that places an earlier
   *                              measurement pays one more run; its children don't)
   *
   *   under Yoga: the final box is decided after measuring, and may be a stretch
   *     measure(at most 300)  -> run A, declares 250          latest: A
   *     then one of:
   *     build(exactly 250)    -> A declared exactly 250: fits, no run (Yoga's own rule:
   *                              a size measured is valid at exactly that size)
   *     build(exactly 280)    -> stretched past what A declared: runs again, exactly
   *
   *   keeping one run, not two, costs a run when the build returns to an earlier box
   *     measure(unbounded)    -> run A, declares 250
   *     measure(at most 200)  -> run B, declares 200          latest: B
   *     build(exactly 250)    -> B declared 200: runs again, though A would have fit
   * ```
   */
  private fun declareUnder(constraints: SizeConstraints) {
    // A pass nothing will commit runs no layout function: it measures 0 x 0 and places nothing.
    if (context.renderContext?.isFutureReleased == true) {
      declared = null
      return
    }
    if (functionFailure != null) return
    val latest = declared
    if (
        latest != null &&
            (declaredUnder == constraints ||
                (constraints.hasExactWidth &&
                    constraints.hasExactHeight &&
                    latest.width == constraints.maxWidth &&
                    latest.height == constraints.maxHeight))
    ) {
      return
    }
    val scope = MeasureScope(node.tailComponentContext.resourceResolver, layoutDirection)
    LithoStats.count(LithoStats.customLayoutFunctionRunCount)
    val failure =
        recordingReads("layout function") {
          trace({ "layout.layoutFunction:${node.headComponent.simpleName}" }) {
            val outer = stage
            stage = Stage.RUNNING_FUNCTION
            try {
              with(checkNotNull(node.layoutFunction)) { scope.measure(measurables, constraints) }
            } finally {
              stage = outer
            }
          }
          check(scope.placement != null) { NO_SIZE_DECLARED }
        }
    declaredUnder = constraints
    declared = if (failure == null) scope else null
    functionFailure = failure
  }

  /** Its border-box size in [box], around what its latest run declared: none if it failed. */
  private fun borderBoxSizeIn(box: BoxConstraints): IntSize =
      borderBoxSizeAround(declared?.width ?: 0, declared?.height ?: 0, box)

  /**
   * Runs [block], recording the state it reads as this `Layout`'s; returns what it threw. Reads are
   * kept when it throws too: a failure that depends on state recovers when that state changes.
   */
  private inline fun recordingReads(description: String, block: () -> Unit): Exception? {
    if (!tracksReads) {
      return attempt(block)
    }
    var failure: Exception? = null
    val reads =
        StateReadRecorder.record(requireNotNull(context.renderContext).treeId, description) {
          failure = attempt(block)
        }
    if (reads.isNotEmpty()) addReads(reads)
    return failure
  }

  /** Only an `Exception` is a layout's failure: an `Error` propagates. */
  private inline fun attempt(block: () -> Unit): Exception? =
      try {
        block()
        null
      } catch (failure: Exception) {
        failure
      }

  /**
   * Where it is in its pass. A run of its function, the only time its children can be measured,
   * returns it to where it was: measuring, or done once its build has begun.
   */
  private enum class Stage {
    MEASURING,
    RUNNING_FUNCTION,
    DONE,
  }

  /** Adds [reads] to its own: what it ran read, or what a child it didn't build made read. */
  private fun addReads(reads: ScatterSet<StateId>) {
    if (!tracksReads || reads.isEmpty()) return
    (stateReads
            ?: mutableScatterSetOf<StateId>().also { stateReadSet -> stateReads = stateReadSet })
        .addAll(reads)
  }
}

/**
 * The constraints its layout function runs under: the room inside the padding and border. The
 * parent resolves the box from this `Layout`'s own size props; its function sees only the content.
 * They're measure specs, as Yoga measures a leaf's content under, so a minimum reaches it only on
 * an exact axis, as Yoga's measure specs carry none: a function that hands its constraints to a
 * child would otherwise force that child up to it. [borderBoxSizeAround] applies the minimum to
 * what it declares.
 *
 * ```
 *   +------------- border box, within box.borderBox --------------+
 *   |  +----------------- padding box: inside the border -----------------+  |
 *   |  |  +---------- content box, within what this returns -----------+  |  |
 *   |  |  |                                                            |  |  |
 *   |  |  +------------------------------------------------------------+  |  |
 *   |  +------------------------------------------------------------------+  |
 *   +------------------------------------------------------------------------+
 * ```
 */
private fun BoxConstraints.contentConstraints(): SizeConstraints {
  val room = borderBox
  return SizeConstraints.fromMeasureSpecs(
      contentSpec(room.hasExactWidth, room.hasBoundedWidth, room.maxWidth, edgesAcross),
      contentSpec(room.hasExactHeight, room.hasBoundedHeight, room.maxHeight, edgesDown),
  )
}
