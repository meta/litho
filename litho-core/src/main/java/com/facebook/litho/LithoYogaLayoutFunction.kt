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

import android.graphics.Point
import android.graphics.Rect
import android.util.Pair
import androidx.collection.MutableScatterMap
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.ComponentsSystrace.traceWithArgs
import com.facebook.litho.YogaLayoutOutput.Companion.getYogaNode
import com.facebook.litho.YogaLayoutOutput.Companion.layoutDirection
import com.facebook.litho.YogaLayoutOutput.Companion.yogaLayoutOutput
import com.facebook.litho.animation.ReflowReadView
import com.facebook.litho.animation.reflowStateId
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.drawable.BorderColorDrawable
import com.facebook.litho.layout.BoxConstraints
import com.facebook.litho.layout.BoxLayoutProps
import com.facebook.litho.layout.IntEdges
import com.facebook.litho.layout.LayoutDirection
import com.facebook.litho.layout.internal.DefaultLayoutBuilder
import com.facebook.litho.state.StateReadRecorder
import com.facebook.rendercore.FastMath
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.LayoutCache
import com.facebook.rendercore.LayoutContext
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.MeasureResult
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import com.facebook.rendercore.utils.MeasureSpecUtils
import com.facebook.rendercore.utils.hasEquivalentFields
import com.facebook.yoga.YogaConstants
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaDisplay
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaMeasureFunction
import com.facebook.yoga.YogaMeasureMode
import com.facebook.yoga.YogaMeasureOutput
import com.facebook.yoga.YogaNode
import com.facebook.yoga.YogaNodeType
import com.facebook.yoga.YogaUnit
import com.facebook.yoga.YogaValue

/** Layout function for LithoNodes that lay out their children via Flexbox. */
internal object LithoYogaLayoutFunction {

  private val AUTO: YogaValue = checkNotNull(YogaValue.parse("auto"))

  /** Calculate the layout for a component using Yoga. */
  fun calculateLayout(
      context: LayoutContext<LithoLayoutContext>,
      sizeConstraints: SizeConstraints,
      lithoNode: LithoNode,
  ): LithoLayoutResult {

    applyOverridesRecursive(lithoNode)

    // Reflow collection is scoped to this invocation: a deferred node re-enters this function on
    // the SAME LithoLayoutContext (see Layout.measureDeferredNode), and each pass must pin and
    // capture only the nodes its own build walk collected — otherwise the nested pass re-captures
    // the outer tree's already-pinned nodes and records the pinned size as their natural.
    val lsc = context.renderContext
    val enclosingReflowResults = lsc?.reflowResults
    lsc?.reflowResults = null
    try {
      return calculateLayoutForYogaRoot(context, sizeConstraints, lithoNode)
    } finally {
      lsc?.reflowResults = enclosingReflowResults
    }
  }

  /**
   * A `Layout`'s child's tree, not laid out yet. It's a copy of the last pass's tree when the
   * layout cache has one, never the cached tree itself, which belongs to the committed layout that
   * other passes and the main thread read. Its root carries no box props, which the `Layout`
   * resolves into the boxes it's laid out in. It isn't written to the cache as it's made: its
   * builder writes it once it's built, or when it drops a tree holding content measured at render.
   */
  internal fun newTree(
      context: LayoutContext<LithoLayoutContext>,
      node: LithoNode,
      previousDiffNode: DiffNode?,
  ): LithoLayoutResult =
      trace({ "layout.buildYogaTree:${node.headComponent.simpleName}" }) {
        buildYogaTree(
            context = context,
            currentNode = node,
            isLayoutChild = true,
            inContainer = true,
            rootDiffNode = previousDiffNode,
            savesIntoCache = false,
        )
      }

  /** [tree], placed: the `Layout`s in it built, then the tree written to the layout cache. */
  internal fun buildTree(
      context: LayoutContext<LithoLayoutContext>,
      tree: LithoLayoutResult,
  ): LithoLayoutResult {
    if (tree.subtreeHasCustomLayout) buildLayoutsIn(tree)
    saveTreeIntoCache(context, tree)
    return tree
  }

  /**
   * Builds each `Layout` in [result]'s Yoga tree, just laid out, through the builder its result
   * holds, in the box Yoga settled: its size, and Yoga's padding and border. Its result takes the
   * output its builder builds, placed where Yoga placed it.
   */
  private fun buildLayoutsIn(result: LithoLayoutResult) {
    forEachCustomLayoutIn(result) { layout ->
      val builder =
          checkNotNull(layout.defaultLayoutBuilder) {
            "${layout.node.headComponent.simpleName}, a `Layout` in a Yoga tree, has no builder"
          }
      builder.buildInYogaTree(layout, settledBoxOf(layout))
    }
  }

  /**
   * Runs [action] on the result of each `Layout` in [result]'s Yoga tree, not below one: a
   * `Layout`'s own children are its builder's.
   */
  internal fun forEachCustomLayoutIn(
      result: LithoLayoutResult,
      action: (LithoLayoutResult) -> Unit,
  ) {
    if (!result.subtreeHasCustomLayout) {
      return
    }
    if (result.node.layoutFunction != null) {
      action(result)
      return
    }
    for (i in 0 until result.childCount) {
      forEachCustomLayoutIn(result.getChildAt(i), action)
    }
  }

  private fun settledBoxOf(result: LithoLayoutResult): BoxConstraints = BoxConstraints(
      margins = IntEdges.None,
      padding =
          edgesOf(result.paddingLeft, result.paddingTop, result.paddingRight, result.paddingBottom),
      border =
          edgesOf(result.borderLeft, result.borderTop, result.borderRight, result.borderBottom),
      borderBox = SizeConstraints.exact(result.width, result.height),
  )

  private fun edgesOf(left: Int, top: Int, right: Int, bottom: Int): IntEdges =
      if (left == 0 && top == 0 && right == 0 && bottom == 0) IntEdges.None
      else IntEdges(left, top, right, bottom)

  /**
   * Writes [result]'s tree to the layout cache, as [buildYogaTree] writes a tree it builds. A
   * `Layout` is never cached, and the trees it placed are written by whatever laid them out.
   */
  internal fun saveTreeIntoCache(
      context: LayoutContext<LithoLayoutContext>,
      result: LithoLayoutResult,
  ) {
    if (result.node.layoutFunction != null) {
      return
    }
    saveLithoLayoutResultIntoCache(context, result.node, result, skipTransitionCapture = true)
    for (i in 0 until result.childCount) {
      saveTreeIntoCache(context, result.getChildAt(i))
    }
  }

  private fun calculateLayoutForYogaRoot(
      context: LayoutContext<LithoLayoutContext>,
      sizeConstraints: SizeConstraints,
      lithoNode: LithoNode,
  ): LithoLayoutResult {
    val layoutResult: LithoLayoutResult =
        trace({ "layout.buildYogaTree:${lithoNode.headComponent.simpleName}" }) {
          buildYogaTree(context = context, currentNode = lithoNode)
        }
    val yogaRoot: YogaNode = layoutResult.getYogaNode()

    val widthSpec = sizeConstraints.toWidthSpec()
    val heightSpec = sizeConstraints.toHeightSpec()

    if (lithoNode.layoutDirection == LayoutDirection.RTL) {
      yogaRoot.setDirection(YogaDirection.RTL)
    }

    applyRootSizeConstraints(layoutResult, sizeConstraints)

    val width: Float =
        if (SizeSpec.getMode(widthSpec) == SizeSpec.UNSPECIFIED) {
          YogaConstants.UNDEFINED
        } else {
          SizeSpec.getSize(widthSpec).toFloat()
        }
    val height: Float =
        if (SizeSpec.getMode(heightSpec) == SizeSpec.UNSPECIFIED) {
          YogaConstants.UNDEFINED
        } else {
          SizeSpec.getSize(heightSpec).toFloat()
        }

    layoutResult.setSizeSpec(widthSpec, heightSpec)

    trace({ "layout.yogaCalculateLayout:${lithoNode.headComponent.simpleName}" }) {
      yogaRoot.calculateLayout(width, height)

      // Non-null only when the walk collected a reflow node that did not drive this pass.
      val lsc = context.renderContext
      val reflowResults = lsc?.reflowResults
      if (reflowResults != null) {
        var pinned = false
        val reflowView = lsc.reflowReadView
        for (i in reflowResults.indices) {
          if (pinAndCaptureReflowNode(reflowResults[i], reflowView, lsc)) pinned = true
        }
        if (pinned) {
          yogaRoot.calculateLayout(width, height)
        }
      }

      if (layoutResult.subtreeHasCustomLayout) buildLayoutsIn(layoutResult)

      context.renderContext?.rootOffset =
          Point(
              yogaRoot.layoutX.toInt(),
              yogaRoot.layoutY.toInt(),
          )
    }

    return layoutResult
  }

  /**
   * Pins a single reflow node so a real commit never flashes the destination, and records its
   * natural size in [LithoLayoutContext.reflowTargetSizes] for mount-time retarget reconciliation.
   * Returns true if its Yoga node was pinned (so the caller re-runs the layout):
   * - new destination (natural != current target): pin each changed axis to the node's current
   *   on-screen size;
   * - still animating toward an unchanged destination (unrelated relayout, or before the first
   *   frame): pin to the current interpolated value so it does not jump to the target and back;
   * - settled (not animating, natural == target): leave it at its natural size.
   *
   * The natural size is recorded for EVERY collected node (not only changed ones), reusing the same
   * [LithoLayoutContext.reflowTargetSizes] map, so the main-thread mount can reconcile the retarget
   * decision against the live store: a background walk may observe a stale target here yet still
   * commit as the newest layout, so the walk-time read is only a pinning hint, not the decision.
   *
   * The "current on-screen size" comes from the reflow read view, keyed by `tailComponentKey`, so
   * it is stable across a sibling insert/remove — unlike `DiffNode.lastMeasured*`, which is aligned
   * by tree position and would hand this node its former neighbor's size after a structural change.
   */
  private fun pinAndCaptureReflowNode(
      result: LithoLayoutResult,
      reflowView: ReflowReadView,
      lsc: LithoLayoutContext,
  ): Boolean {
    val naturalWidth = result.width
    val naturalHeight = result.height
    val naturalSize = IntSize(naturalWidth, naturalHeight)
    val key = result.node.tailComponentKey
    val targetSizes =
        lsc.reflowTargetSizes
            ?: MutableScatterMap<GlobalKey, IntSize>(2).also { lsc.reflowTargetSizes = it }
    targetSizes[key] = naturalSize
    if (reflowView.targetSize(key) != naturalSize) {
      // currentSize is null only on the first-ever commit (no value seeded yet): nothing to pin
      // against, so let it lay out at its natural size.
      val currentSize = reflowView.currentSize(key) ?: return false
      val yogaNode = result.getYogaNode()
      var pinned = false
      if (currentSize.width != naturalWidth) {
        yogaNode.setWidth(currentSize.width.toFloat())
        pinned = true
      }
      if (currentSize.height != naturalHeight) {
        yogaNode.setHeight(currentSize.height.toFloat())
        pinned = true
      }
      return pinned
    } else {
      val sizeToPin = reflowView.sizeToPin(key) ?: return false
      val yogaNode = result.getYogaNode()
      yogaNode.setWidth(sizeToPin.width.toFloat())
      yogaNode.setHeight(sizeToPin.height.toFloat())
      return true
    }
  }

  private fun isYogaTreeCopyable(item: LayoutCache.CacheItem?): Boolean {
    val cached = item?.layoutResult as? LithoLayoutResult ?: return false
    return !cached.subtreeHasCustomLayout && cached.measureOutput is YogaLayoutOutput
  }

  private fun buildYogaTreeFromCache(
      context: LayoutContext<LithoLayoutContext>,
      cachedResult: LithoLayoutResult,
      diffNode: DiffNode?,
      skipTransitionCapture: Boolean = false,
      inContainer: Boolean = false,
      savesIntoCache: Boolean = true,
  ): LithoLayoutResult = cloneCachedSubtree(
      context = context,
      cachedResult = cachedResult,
      // The root is the only node whose clone no parent supplies.
      clonedYogaNode = cachedResult.getYogaNode().cloneWithChildren(),
      currentDiffNode = diffNode,
      skipTransitionCapture = skipTransitionCapture,
      inContainer = inContainer,
      savesIntoCache = savesIntoCache,
  )

  /**
   * Rebuilds a subtree from the result a previous pass cached for it.
   *
   * A cached result belongs to the pass that produced it and cannot be handed out again, so every
   * [LithoLayoutResult] is copied and every [YogaNode] cloned alongside it.
   *
   * The clone for this node arrives as [clonedYogaNode] rather than being taken here, because
   * `cloneWithChildren` is a deep clone: cloning per call would re-clone every descendant at every
   * level and turn a linear walk quadratic. Each level instead hands its children the clones it
   * already made.
   *
   * [inContainer]: the subtree is a `Layout`'s child, or inside one. [savesIntoCache]: written to
   * the layout cache as it's copied, or left to [buildTree].
   */
  private fun cloneCachedSubtree(
      context: LayoutContext<LithoLayoutContext>,
      cachedResult: LithoLayoutResult,
      clonedYogaNode: YogaNode,
      currentDiffNode: DiffNode?,
      skipTransitionCapture: Boolean = false,
      inContainer: Boolean = false,
      savesIntoCache: Boolean = true,
  ): LithoLayoutResult {
    val node: LithoNode = cachedResult.node
    val result: LithoLayoutResult = copyLayoutResult(cachedResult, node, clonedYogaNode)
    clonedYogaNode.data = Pair<Any, Any>(context, result)
    // The copy drops the resolved content but keeps Yoga's cached measurements: under the same
    // constraints Yoga wouldn't measure it again, and the content would be resolved only at its
    // final size. Dirty it so it's resolved under the constraints it's measured with. Under a
    // `Layout`, content `Component.measure` sized is kept instead, with Yoga's measurement of it: a
    // fresh render takes the size and the layout that call cached, which a relayout without a
    // render no longer has. Kept, it is placed as it is, so only content holding no deferred
    // content and no `Layout`, which a pass lays out again, is. Outside a `Layout`, a deferred node
    // is dirtied only with `remeasureDeferredNodesInCachedSubtree` on.
    if (inContainer && node is DeferredLithoNode) {
      val content = cachedResult.actualDeferredNodeResult
      if (
          !Component.isLayoutSpecWithSizeSpec(node.tailComponent) &&
              content != null &&
              !content.subtreeHasCustomLayout &&
              !holdsDeferredContent(content)
      ) {
        result.actualDeferredNodeResult =
            copyForPass(context, content, content.getYogaNode().cloneWithChildren())
      } else {
        clonedYogaNode.dirty()
      }
    } else if (
        ComponentsConfiguration.remeasureDeferredNodesInCachedSubtree && node is DeferredLithoNode
    ) {
      clonedYogaNode.dirty()
    }

    // Set the DiffNode on the cloned result so the measure cache in measureLithoNode can use
    // its cached specs/sizes when Yoga re-measures with changed constraints. Unlike applyDiffNode
    // (used in the non-cached path), this does not run shouldComponentUpdate; only a deferred node
    // may be dirtied, above.
    //
    // Type validation and isReleased checks are handled by findDiffNode, which resolved
    // currentDiffNode before this function was called. If the type didn't match or the context
    // was released, currentDiffNode is null and the entire subtree gets no DiffNode assignments.
    if (currentDiffNode != null) {
      result.diffNode = currentDiffNode
    }

    if (savesIntoCache) {
      saveLithoLayoutResultIntoCache(context, node, result, skipTransitionCapture)
    }
    // Walk children by index without type validation. This is safe because cloneCachedSubtree is
    // only reached from buildYogaTreeFromCache, which requires !maybeDirty and an identity cache
    // hit — guaranteeing the component tree structure is identical to the DiffNode tree.
    for (i in 0 until cachedResult.childCount) {
      val childDiffNode: DiffNode? = currentDiffNode?.takeIf { i < it.childCount }?.getChildAt(i)

      val child: LithoLayoutResult = cloneCachedSubtree(
          context = context,
          cachedResult = cachedResult.getChildAt(i),
          clonedYogaNode = clonedYogaNode.getChildAt(i),
          currentDiffNode = childDiffNode,
          skipTransitionCapture = skipTransitionCapture,
          inContainer = inContainer,
          savesIntoCache = savesIntoCache,
      )
      result.addChild(child)
    }
    return result
  }

  /**
   * A copy of [content], content a copied holder keeps, for this pass, its results copied and its
   * Yoga nodes cloned as [cloneCachedSubtree] copies the rest. [content] is the committed layout's,
   * which other passes and the main thread read, and released, while this pass writes its results
   * as it defines their bounds, and measures a leaf Yoga grew again through its Yoga node. It holds
   * no deferred content, which is why it is kept, so the copy walks plain results.
   */
  private fun copyForPass(
      context: LayoutContext<LithoLayoutContext>,
      content: LithoLayoutResult,
      clonedYogaNode: YogaNode,
  ): LithoLayoutResult {
    val result = copyLayoutResult(content, content.node, clonedYogaNode)
    clonedYogaNode.data = Pair<Any, Any>(context, result)
    for (i in 0 until content.childCount) {
      result.addChild(copyForPass(context, content.getChildAt(i), clonedYogaNode.getChildAt(i)))
    }
    return result
  }

  internal enum class DeferredContent {
    NONE,
    DEFERRED,
    MEASURED,
  }

  /**
   * What [result]'s subtree holds, in one walk: content `Component.measure` sized when it was
   * rendered, else any deferred node's, else neither. A fresh render takes the size that call
   * cached; a layout without that render sizes the content under the constraints Yoga gives it.
   */
  internal fun deferredContentOf(result: LithoLayoutResult): DeferredContent {
    var found = DeferredContent.NONE
    if (result is DeferredLithoLayoutResult) {
      if (!Component.isLayoutSpecWithSizeSpec(result.node.tailComponent)) {
        return DeferredContent.MEASURED
      }
      found = DeferredContent.DEFERRED
    }
    for (i in 0 until result.childCount) {
      val below = deferredContentOf(result.getChildAt(i))
      if (below == DeferredContent.MEASURED) return below
      if (below == DeferredContent.DEFERRED) found = below
    }
    return found
  }

  /** Whether [result] or a result below it is a deferred node's. */
  private fun holdsDeferredContent(result: LithoLayoutResult): Boolean {
    if (result is DeferredLithoLayoutResult) {
      return true
    }
    for (i in 0 until result.childCount) {
      if (holdsDeferredContent(result.getChildAt(i))) {
        return true
      }
    }
    return false
  }

  /**
   * Saves a LithoLayoutResult into the LayoutCache, using the node itself and its ID as keys.
   *
   * [skipTransitionCapture] skips registering the node for a style transition that animates its
   * layout. A `Layout`'s children are laid out as trees of their own, which the transition does not
   * capture, so a node under one is skipped whether it is built afresh or copied from the cache.
   */
  private fun saveLithoLayoutResultIntoCache(
      context: LayoutContext<LithoLayoutContext>,
      node: LithoNode,
      result: LithoLayoutResult,
      skipTransitionCapture: Boolean = false,
  ) {
    val layoutCache: LayoutCache = context.layoutCache
    // TODO(T163437982): Refactor this to build the cache after layout calculation
    val cacheItem = LayoutCache.CacheItem(result, -1, -1)
    layoutCache.put(node, cacheItem)
    layoutCache.put(node.id.toLong(), cacheItem)

    if (!skipTransitionCapture && node.styleTransitionData?.spec?.shouldLayout == true) {
      context.renderContext?.prepareReflowNode(node, result)
    }
  }

  /**
   * Prepares a `Style.transition { bounds(reflow = true) }` node for this layout pass: subscribes
   * it to its animated size, then either applies this frame's interpolated size or enqueues the
   * node for post-layout capture.
   *
   * Runs from [saveLithoLayoutResultIntoCache], after the node's props have been written, so a size
   * this pass forces is applied on top of the node's declared one.
   */
  private fun LithoLayoutContext.prepareReflowNode(node: LithoNode, result: LithoLayoutResult) {
    val key = node.tailComponentKey
    val stateId = reflowStateId(reflowTreeId, key)

    // Subscribe to the reflow value's StateId so an animation frame re-runs this node's layout. The
    // value is minted on the main-thread commit, not here, and this must be recorded every layout:
    // LayoutState.stateReads is rebuilt from each result's state reads each pass.
    result.addStateRead(stateId)

    if (isReflowAnimationFrameFor(stateId)) {
      // This node's own value drove the pass: apply the interpolated size so siblings and nested
      // content reflow with it, and skip the capture. The read view holds a live reference to the
      // AnimatedValue, so this is the current frame's value. It is null when the animation ended
      // while this layout was in flight, which leaves nothing to apply on top of whatever size the
      // node arrived with — so fall through and capture instead.
      val size = reflowReadView.currentSizeIfRunning(key)
      if (size != null) {
        val yogaNode = result.getYogaNode()
        yogaNode.setWidth(size.width.toFloat())
        yogaNode.setHeight(size.height.toFloat())
        return
      }
    }

    // The node may have been served from the layout cache — directly, or cloned as part of an
    // ancestor's subtree — still carrying a size an earlier pass forced onto it, from an animation
    // frame or from the post-layout pin. Undo that, or the capture below records it as natural.
    result.restoreDeclaredSize()

    // Enqueue for pin-and-capture once the tree has laid out. Every reflow node that did not drive
    // this pass is collected, changed or not, so the mount reconcile sees the natural size for all.
    (reflowResults ?: ArrayList<LithoLayoutResult>(2).also { reflowResults = it }).add(result)
  }

  /**
   * Rewrites the declared width and height onto this result's Yoga node, undoing a size an earlier
   * pass forced onto a node this one is reusing.
   *
   * Point and percent are rewritten unconditionally — Yoga's setters already no-op, dirty
   * propagation included, when the value is unchanged, so reading the current value back to compare
   * would only cost a JNI round trip and a [YogaValue] allocation per axis. An undeclared dimension
   * has to check first: it can only be handed back as auto, and Yoga compares unit as well as
   * value, so rewriting an already-undefined dimension would dirty the node and propagate on every
   * pass.
   */
  private fun LithoLayoutResult.restoreDeclaredSize() {
    val yogaNode = getYogaNode()
    val declaredWidth = yogaLayoutOutput.widthFromStyle
    when (declaredWidth.unit) {
      YogaUnit.POINT -> yogaNode.setWidth(declaredWidth.value)
      YogaUnit.PERCENT -> yogaNode.setWidthPercent(declaredWidth.value)
      else -> if (!yogaNode.width.isUndeclared()) yogaNode.setWidthAuto()
    }
    val declaredHeight = yogaLayoutOutput.heightFromStyle
    when (declaredHeight.unit) {
      YogaUnit.POINT -> yogaNode.setHeight(declaredHeight.value)
      YogaUnit.PERCENT -> yogaNode.setHeightPercent(declaredHeight.value)
      else -> if (!yogaNode.height.isUndeclared()) yogaNode.setHeightAuto()
    }
  }

  /**
   * Whether this Yoga dimension carries no size of its own. Yoga defaults a dimension to undefined
   * and `widthAuto()`/`heightAuto()` records no style metadata, so an undeclared dimension reads as
   * either; the two are interchangeable in layout, where every dimension check pairs `isAuto()`
   * with `isUndefined()`.
   */
  private fun YogaValue.isUndeclared(): Boolean =
      unit == YogaUnit.AUTO || unit == YogaUnit.UNDEFINED

  /**
   * Builds the YogaNode tree from this tree of LithoNodes while building the LayoutResult tree and
   * setting it in the data of the corresponding YogaNodes. [savesIntoCache]: each node is written
   * to the layout cache as it's built, or left to [buildTree].
   */
  private fun buildYogaTree(
      context: LayoutContext<LithoLayoutContext>,
      currentNode: LithoNode,
      parentNode: YogaNode? = null,
      isLayoutChild: Boolean = false,
      inContainer: Boolean = false,
      rootDiffNode: DiffNode? = null,
      savesIntoCache: Boolean = true,
  ): LithoLayoutResult {

    val renderContext: LithoLayoutContext? = context.renderContext
    checkNotNull(renderContext) { "RenderContext cannot be null when building YogaTree." }

    val scopesToUpdate = renderContext.scopesToUpdate
    val tailKey = currentNode.tailComponentKey
    val isDirty = tailKey in scopesToUpdate

    val layoutCache: LayoutCache = context.layoutCache
    var cacheItem: LayoutCache.CacheItem? = layoutCache[currentNode]
    val hasReflowFrameUpdateInDeferredSubtree =
        renderContext.reflowFrameUpdates != null &&
            currentNode is DeferredLithoNode &&
            renderContext.hasReflowFrameUpdateIn(
                // A Component.measure-backed holder does not own the resolved subtree's key.
                (cacheItem?.layoutResult as? LithoLayoutResult)
                    ?.actualDeferredNodeResult
                    ?.node
                    ?.tailComponentKey ?: currentNode.cachedNode?.tailComponentKey ?: tailKey,
            )
    val maybeDirty =
        isDirty || hasReflowFrameUpdateInDeferredSubtree || scopesToUpdate.hasDescendant(tailKey)

    var layoutResult: LithoLayoutResult? = null
    var yogaNode: YogaNode? = null

    // Resolve the DiffNode once — used by both the cached and non-cached paths. A `Layout`'s child
    // is a root of its own, so its container hands it the one it had.
    val diffNode: DiffNode? =
        if (isLayoutChild) validDiffNode(renderContext, currentNode, rootDiffNode)
        else findDiffNode(renderContext, currentNode, parentNode)

    // A `Layout`'s result holds the layouts its function placed, which no Yoga node beneath it
    // does, so it is never cached, and a cached subtree holding one is never copied whole. Any
    // other node is reused from its own last layout, so in the role it had then: a `Layout`'s child
    // with its box props on its result, a flexbox child with them on its node.
    // A `Layout`'s leaf child is cached without a Yoga node, which no Yoga tree can copy.
    val isCustomLayout = currentNode.layoutFunction != null
    val usesCache = !isCustomLayout
    if (!usesCache || !isYogaTreeCopyable(cacheItem)) {
      cacheItem = null
    }
    if (cacheItem != null && !maybeDirty) {
      val cachedLayoutResult: LayoutResult = cacheItem.layoutResult
      return trace({ "layout.buildYogaTreeFromCache:${currentNode.headComponent.simpleName}" }) {
        // We can fully reuse the Yoga tree.
        buildYogaTreeFromCache(
            context,
            cachedLayoutResult as LithoLayoutResult,
            diffNode,
            skipTransitionCapture = inContainer,
            inContainer = inContainer,
            savesIntoCache = savesIntoCache,
        )
            .also { copy -> renderContext.addWholeSubtreeCopy(copy) }
      }
    }

    cacheItem =
        if (usesCache) {
          layoutCache.get<LayoutCache.CacheItem>(currentNode.id.toLong())?.takeIf {
            (it.layoutResult as? LithoLayoutResult)?.measureOutput is YogaLayoutOutput
          }
        } else {
          null
        }
    if (cacheItem != null && (!isDirty || hasReflowFrameUpdateInDeferredSubtree)) {
      val cachedLayoutResult = cacheItem.layoutResult as LithoLayoutResult

      // We can partially reuse the Yoga tree.
      val clonedNode: YogaNode = cachedLayoutResult.getYogaNode().cloneWithoutChildren()
      yogaNode = clonedNode
      layoutResult = copyLayoutResult(cachedLayoutResult, currentNode, clonedNode)
      if (hasReflowFrameUpdateInDeferredSubtree) {
        // Keep the resolved subtree so the frame reruns Yoga without resolving the boundary.
        layoutResult.actualDeferredNodeResult = cachedLayoutResult.actualDeferredNodeResult
        clonedNode.dirty()
      } else if (currentNode is DeferredLithoNode && maybeDirty) {
        // A deferred node is a Yoga leaf, so a dirty scope in its resolved subtree cannot dirty it
        // the way a new child dirties a container. Left clean, Yoga keeps the stale measurement and
        // the subtree is re-resolved at that exact size, so it can never grow or shrink.
        clonedNode.dirty()
      }
    }

    if (layoutResult == null) {
      val writer: YogaLayoutProps = currentNode.createYogaNodeWriter()
      // A `Layout`'s child keeps its box props off its node: its container applies them.
      val childBoxProps = if (isLayoutChild) BoxLayoutProps() else null
      val props: LayoutProps =
          if (childBoxProps != null) {
            val isDeferred = currentNode is DeferredLithoNode
            ContainerChildLayoutProps(
                box = BoxLayoutPropsRecorder(childBoxProps, recordsOwnEdges = !isDeferred),
                content = writer,
                isDeferred = isDeferred,
            )
          } else {
            writer
          }

      // Transfer the layout props to the YogaNode.
      if (currentNode is DeferredLithoNode) {
        currentNode.writeDeferredNodePropsToYogaNode(
            context,
            writer as DeferredNodeYogaLayoutProps,
            props,
        )
      } else if (currentNode !is NullNode) {
        currentNode.writeNodePropsToYogaNode(context, writer, props)
      }
      yogaNode = writer.node

      // Ideally, the layout data should be created when measure is called on the mount spec or
      // primitive component. However, because of the current implementation of mount specs and the
      // way Yoga works, measure may not be called. A MountSpec may require inter-stage props, so it
      // is necessary to have a non-null InterStagePropsContainer even if the values are
      // uninitialised. Otherwise, this will lead to NPEs.
      //
      // This should get cleaned up once the implementation is general enough for
      // PrimitiveComponents.
      val layoutData = newInterStagePropsOf(currentNode)

      layoutResult =
          currentNode.createLayoutResult(
              measureOutput =
                  YogaLayoutOutput(
                      yogaNode = yogaNode,
                      widthFromStyle = writer.widthFromStyle,
                      heightFromStyle = writer.heightFromStyle,
                      minWidthFromStyle = writer.minWidthFromStyle,
                      maxWidthFromStyle = writer.maxWidthFromStyle,
                      minHeightFromStyle = writer.minHeightFromStyle,
                      maxHeightFromStyle = writer.maxHeightFromStyle,
                  ),
          )
      layoutResult.layoutData = layoutData
      writer.stateReads?.let(layoutResult::addStateReads)
      layoutResult.childBoxProps = childBoxProps
    }

    checkNotNull(yogaNode) { "YogaNode cannot be null when building YogaTree." }

    yogaNode.data = Pair(context, layoutResult)
    if (diffNode != null) {
      applyDiffNode(renderContext, currentNode, yogaNode, diffNode, isDirty)
    }

    // A `Layout` is a leaf to Yoga, laid out by its builder, which its result holds until it's
    // built, or the tree is dropped: Yoga's measure callback measures it, and [buildLayoutsIn]
    // builds it in the box Yoga settled. It contributes no automatic minimum, as no leaf does by
    // default. Setting a measure function makes it a text node to Yoga, whose size is rounded up; a
    // `Layout` is rounded as its builder rounds, to the nearest pixel, so it's built in the size it
    // measured.
    if (currentNode.layoutFunction != null) {
      layoutResult.subtreeHasCustomLayout = true
      layoutResult.defaultLayoutBuilder = DefaultLayoutBuilder(context, currentNode, diffNode)
      yogaNode.setMeasureFunction(DefaultLayoutMeasureFunction)
      yogaNode.setNodeType(YogaNodeType.DEFAULT)
      yogaNode.setMinContentWidth(0f)
      yogaNode.setMinContentHeight(0f)
      return layoutResult
    }

    if (savesIntoCache) {
      saveLithoLayoutResultIntoCache(
          context,
          currentNode,
          layoutResult,
          skipTransitionCapture = inContainer,
      )
    }

    for (i in 0 until currentNode.childCount) {
      val childLayoutResult: LithoLayoutResult = buildYogaTree(
          context = context,
          currentNode = currentNode.getChildAt(i),
          parentNode = yogaNode,
          inContainer = inContainer,
          savesIntoCache = savesIntoCache,
      )

      yogaNode.addChildAt(childLayoutResult.getYogaNode(), yogaNode.childCount)
      layoutResult.addChild(childLayoutResult)
      if (childLayoutResult.subtreeHasCustomLayout) {
        layoutResult.subtreeHasCustomLayout = true
      }
    }

    return layoutResult
  }

  /**
   * [diffNode], handed over by a `Layout` for its child, if it is for the same kind of component:
   * the check [findDiffNode] applies to one it looks up.
   */
  internal fun validDiffNode(
      renderContext: LithoLayoutContext,
      currentNode: LithoNode,
      diffNode: DiffNode?,
  ): DiffNode? {
    if (diffNode == null || renderContext.isReleased) {
      return null
    }
    return diffNode.takeIf {
      ComponentUtils.isSameComponentType(currentNode.tailComponent, it.component) ||
          Component.isLayoutSpecWithSizeSpec(currentNode.tailComponent)
    }
  }

  /**
   * Applies the root size constraints (such as min/max/exact width and height) to the YogaNode tree
   * before layout calculation. This ensures that the root node respects the provided size
   * constraints during layout.
   *
   * @param layoutResult The root layout result containing the YogaNode to constrain.
   * @param sizeConstraints The size constraints to apply to the root node.
   */
  private fun applyRootSizeConstraints(
      layoutResult: LithoLayoutResult,
      sizeConstraints: SizeConstraints,
  ) {
    val yogaNode: YogaNode = layoutResult.getYogaNode()
    val output = layoutResult.yogaLayoutOutput

    // Reset to original style values
    // For new nodes: no-op since current == style.
    // For cached nodes: restore values that may have been modified by a previous layout.

    // Width
    if (output.widthFromStyle != yogaNode.width) {
      when (output.widthFromStyle.unit) {
        YogaUnit.PERCENT -> yogaNode.setWidthPercent(output.widthFromStyle.value)
        YogaUnit.POINT -> yogaNode.setWidth(output.widthFromStyle.value)
        else -> yogaNode.setWidthAuto()
      }
    }

    // Height
    if (output.heightFromStyle != yogaNode.height) {
      when (output.heightFromStyle.unit) {
        YogaUnit.PERCENT -> yogaNode.setHeightPercent(output.heightFromStyle.value)
        YogaUnit.POINT -> yogaNode.setHeight(output.heightFromStyle.value)
        else -> yogaNode.setHeightAuto()
      }
    }

    // Min Width
    if (output.minWidthFromStyle != yogaNode.minWidth) {
      when (output.minWidthFromStyle.unit) {
        YogaUnit.PERCENT -> yogaNode.setMinWidthPercent(output.minWidthFromStyle.value)
        YogaUnit.POINT -> yogaNode.setMinWidth(output.minWidthFromStyle.value)
        else -> yogaNode.setMinWidth(AUTO.value)
      }
    }

    // Max Width
    if (output.maxWidthFromStyle != yogaNode.maxWidth) {
      when (output.maxWidthFromStyle.unit) {
        YogaUnit.PERCENT -> yogaNode.setMaxWidthPercent(output.maxWidthFromStyle.value)
        YogaUnit.POINT -> yogaNode.setMaxWidth(output.maxWidthFromStyle.value)
        else -> yogaNode.setMaxWidth(AUTO.value)
      }
    }

    // Min Height
    if (output.minHeightFromStyle != yogaNode.minHeight) {
      when (output.minHeightFromStyle.unit) {
        YogaUnit.PERCENT -> yogaNode.setMinHeightPercent(output.minHeightFromStyle.value)
        YogaUnit.POINT -> yogaNode.setMinHeight(output.minHeightFromStyle.value)
        else -> yogaNode.setMinHeight(AUTO.value)
      }
    }

    // Max Height
    if (output.maxHeightFromStyle != yogaNode.maxHeight) {
      when (output.maxHeightFromStyle.unit) {
        YogaUnit.PERCENT -> yogaNode.setMaxHeightPercent(output.maxHeightFromStyle.value)
        YogaUnit.POINT -> yogaNode.setMaxHeight(output.maxHeightFromStyle.value)
        else -> yogaNode.setMaxHeight(AUTO.value)
      }
    }

    // Apply root size constraints
    // Note: Yoga doesn't have the concept of AT_MOST constraints like Android's MeasureSpec.
    // We convert AT_MOST to max width/height constraints to bound the layout.
    if (YogaConstants.isUndefined(yogaNode.width.value)) {
      if (sizeConstraints.hasExactWidth) {
        // Width is specified exactly, so set it directly.
        yogaNode.setWidth(sizeConstraints.maxWidth.toFloat())
      } else if (
          sizeConstraints.minWidth > 0 || sizeConstraints.maxWidth != SizeConstraints.Infinity
      ) {
        // Width has an upper bound.
        val maxWidth = sizeConstraints.maxWidth.toFloat()
        if (maxWidth < SizeConstraints.Infinity) {
          val yogaMaxWidth = yogaNode.maxWidth
          if (YogaConstants.isUndefined(yogaMaxWidth.value)) {
            // No existing max width constraint, so apply the size constraint.
            yogaNode.setMaxWidth(maxWidth)
          } else {
            // Leave as it is to let Yoga resolve the constraints
          }
        }

        // Width has a lower bound.
        val minWidth = sizeConstraints.minWidth.toFloat()
        if (minWidth > 0) {
          val yogaMinWidth = yogaNode.minWidth
          if (YogaConstants.isUndefined(yogaMinWidth.value)) {
            // No existing min width constraint, so apply the size constraint.
            yogaNode.setMinWidth(minWidth)
          } else {
            // Leave as it is to let Yoga resolve the constraints
          }
        }
      } else {
        // Width is unbounded, so set it to auto.
        yogaNode.setWidthAuto()
      }
    }

    if (YogaConstants.isUndefined(yogaNode.height.value)) {
      if (sizeConstraints.hasExactHeight) {
        // Height is specified exactly, so set it directly.
        yogaNode.setHeight(sizeConstraints.maxHeight.toFloat())
      } else if (
          sizeConstraints.minHeight > 0 || sizeConstraints.maxHeight != SizeConstraints.Infinity
      ) {
        // Height has an upper bound.
        val maxHeight = sizeConstraints.maxHeight.toFloat()
        if (maxHeight < SizeConstraints.Infinity) {
          val yogaMaxHeight = yogaNode.maxHeight
          if (YogaConstants.isUndefined(yogaMaxHeight.value)) {
            // No existing max height constraint, so apply the size constraint.
            yogaNode.setMaxHeight(maxHeight)
          } else {
            // Leave as it is to let Yoga resolve the constraints
          }
        }

        // Height has a lower bound.
        val minHeight = sizeConstraints.minHeight.toFloat()
        if (minHeight > 0) {
          val yogaMinHeight = yogaNode.minHeight
          if (YogaConstants.isUndefined(yogaMinHeight.value)) {
            // No existing min height constraint, so apply the size constraint.
            yogaNode.setMinHeight(minHeight)
          } else {
            // Leave as it is to let Yoga resolve the constraints
          }
        }
      } else {
        // Height is unbounded, so set it to auto.
        yogaNode.setHeightAuto()
      }
    }
  }

  internal fun applyOverridesRecursive(node: LithoNode) {
    if (LithoDebugConfigurations.isDebugModeEnabled) {
      DebugComponent.applyOverrides(node.tailComponentContext, node)
      for (i in 0 until node.childCount) {
        applyOverridesRecursive(node.getChildAt(i))
      }
    }
  }

  /**
   * Finds the DiffNode corresponding to [currentNode] by looking it up in the parent's DiffNode
   * (for children) or from the layout context's diff tree root (for the root node).
   *
   * Returns null if the context is released, no DiffNode is found at the expected position, or the
   * component type doesn't match (unless it's a child deferred node whose type is expected to
   * differ). When null is returned due to type mismatch, the entire subtree should be treated as
   * having no DiffNode — child indices are no longer reliable.
   *
   * Called once in [buildYogaTree] and the result is passed to both [buildYogaTreeFromCache]
   * (cached path) and [applyDiffNode] (non-cached path).
   */
  private fun findDiffNode(
      renderContext: LithoLayoutContext,
      currentNode: LithoNode,
      parentYogaNode: YogaNode?,
  ): DiffNode? {
    if (renderContext.isReleased) {
      return null
    }
    val diffNode: DiffNode? =
        if (parentYogaNode == null) {
          rootDiffNode(renderContext, currentNode)
        } else {
          // Child: look up from the parent's already-resolved DiffNode
          val parentResult = getLayoutResultFromYogaNode(parentYogaNode)
          val parentDiffNode = parentResult.diffNode ?: return null
          val index = parentResult.node.getChildIndex(currentNode)
          if (index != -1 && index < parentDiffNode.childCount) {
            parentDiffNode.getChildAt(index)
          } else {
            null
          }
        }
    if (diffNode == null) {
      return null
    }
    // Validate that the component type matches. Allow type mismatch for child deferred nodes
    // (LayoutSpecWithSizeSpec) because the DiffNode tree makes them transparent — the DiffNode
    // at this position may have the resolved component's type rather than the placeholder's type.
    val component = currentNode.tailComponent
    val isChildDeferredNode =
        parentYogaNode != null && Component.isLayoutSpecWithSizeSpec(component)
    return if (
        ComponentUtils.isSameComponentType(component, diffNode.component) || isChildDeferredNode
    ) {
      diffNode
    } else {
      null
    }
  }

  /** A new inter-stage props container for [node]'s tail component, if it's spec-generated. */
  internal fun newInterStagePropsOf(node: LithoNode): InterStagePropsContainer? =
      (node.tailComponent as? SpecGeneratedComponent)?.createInterStagePropsContainer()

  /**
   * [root]'s DiffNode from the last pass, laid out on its own, unchecked (see [validDiffNode]): the
   * diff tree's root, or the one set aside for a deferred node's content.
   */
  internal fun rootDiffNode(renderContext: LithoLayoutContext, root: LithoNode): DiffNode? =
      when {
        renderContext.isReleased -> null
        Component.isLayoutSpecWithSizeSpec(root.headComponent) &&
            renderContext.hasDiffNodeSetForDeferredNode() ->
            renderContext.consumeDiffNodeForDeferredNode()
        else -> renderContext.currentDiffTree
      }

  /**
   * Applies the given [DiffNode] to the layout result at [currentYogaNode], performing equivalence
   * checks and copying inter-stage props for equivalent components.
   *
   * The DiffNode should already be type-validated by [findDiffNode] before calling this method.
   */
  private fun applyDiffNode(
      current: LithoLayoutContext,
      currentNode: LithoNode,
      currentYogaNode: YogaNode,
      diff: DiffNode,
      isDirty: Boolean,
  ) {
    val result: LithoLayoutResult = getLayoutResultFromYogaNode(currentYogaNode)
    if (applyDiffNodeTo(current, currentNode, result, diff, isDirty)) {
      currentYogaNode.dirty()
    }
  }

  /**
   * [applyDiffNode] for [result]: whether the last pass's measurement may be reused, and its
   * inter-stage props. Returns whether a MountSpec that measures can't reuse it: its Yoga node, if
   * it has one, must then be dirtied.
   */
  internal fun applyDiffNodeTo(
      current: LithoLayoutContext,
      currentNode: LithoNode,
      result: LithoLayoutResult,
      diff: DiffNode,
      isDirty: Boolean,
  ): Boolean {
    val component: Component = currentNode.tailComponent

    result.diffNode = diff

    return trace({ "layout.shouldRemeasure:${currentNode.headComponent.simpleName}" }) {
      val haveEquivalentProps =
          if (currentNode.primitive != null) {
            allNotNull(currentNode.primitive?.layoutBehavior, diff.primitive?.layoutBehavior) {
                layoutBehavior1,
                layoutBehavior2 ->
              !isDirty && layoutBehavior1.isEquivalentTo(layoutBehavior2)
            } == true
          } else {
            // For MountSpecs, skip the isDirty flag check and rely solely on shouldComponentUpdate
            // to preserve backward-compatible update behavior.
            (!LayoutUtils.shouldComponentUpdate(currentNode, diff)).also { equivalent ->
              if (equivalent && isDirty) {
                DebugInfoReporter.report(
                    "LayoutEquivalenceMismatch",
                    renderStateId = current.treeId,
                ) {
                  put(DebugEventAttribute.Name, component.fullyQualifiedName)
                  put("shouldComponentUpdate", false)
                  put("isDirty", true)
                }
              }
            }
          }
      // A layout may depend on its direction (Text resolves alignment from it), and it isn't part
      // of
      // the props, so a measurement from the other direction can't be reused.
      val isEquivalent = haveEquivalentProps && diff.layoutDirection == currentNode.layoutDirection
      val isMountSpecWithMeasure = component.canMeasure() && Component.isMountSpec(component)
      result.cachedMeasuresValid = isEquivalent

      if (currentNode.primitive != null) {
        result.layoutData = diff.layoutData
      } else if (isEquivalent) {
        val scopedComponentInfo = currentNode.tailScopedComponentInfo
        val diffNodeScopedComponentInfo = checkNotNull(diff.scopedComponentInfo)
        if (component is SpecGeneratedComponent) {
          component.copyInterStageImpl(
              result.layoutData as InterStagePropsContainer?,
              diff.layoutData as InterStagePropsContainer?,
          )
          component.copyPrepareInterStageImpl(
              scopedComponentInfo.prepareInterStagePropsContainer,
              diffNodeScopedComponentInfo.prepareInterStagePropsContainer,
          )
        }
      }
      isMountSpecWithMeasure && !isEquivalent
    }
  }

  fun measure(
      context: LayoutContext<LithoLayoutContext>,
      layoutResult: LithoLayoutResult,
      widthSpec: Int,
      heightSpec: Int,
  ): MeasureResult {
    val renderContext =
        requireNotNull(context.renderContext) { "render context should not be null" }

    var size: MeasureResult
    layoutResult.wasMeasured = true
    if (renderContext.isFutureReleased) {

      // If the layout is released, skip measurement.
      size = MeasureResult.error()
    } else {
      val component = layoutResult.node.tailComponent
      traceWithArgs(
          { "measure:${component.simpleName}" },
          { scope ->
            scope.attribute("widthSpec", SizeSpec.toString(widthSpec))
            scope.attribute("heightSpec", SizeSpec.toString(heightSpec))
            scope.attribute("componentId", component.instanceId)
          },
      ) {
        try {
          size =
              if (layoutResult is DeferredLithoLayoutResult) {
                measureDeferredNode(context, widthSpec, heightSpec, layoutResult)
              } else {
                measureLithoNode(context, widthSpec, heightSpec, layoutResult)
              }

          check(!(size.width < 0 || size.height < 0)) {
            ("MeasureOutput not set, Component is: $component " +
                "WidthSpec: ${MeasureSpecUtils.getMeasureSpecDescription(widthSpec)} " +
                "HeightSpec: ${MeasureSpecUtils.getMeasureSpecDescription(heightSpec)} " +
                "Measured width : ${size.width} " +
                "Measured Height: ${size.height}")
          }
        } catch (e: Exception) {

          // Handle the exception.
          ComponentUtils.handle(layoutResult.node.tailComponentContext, e)

          // If the exception is handled, return a zero size to continue layout.
          size = MeasureResult.error()
        }
      }
    }

    // If the size of a cached layout has changed, clear size-dependent render units.
    if (
        layoutResult.isCachedLayout &&
            (layoutResult.contentWidth != size.width || layoutResult.contentHeight != size.height)
    ) {
      layoutResult.borderRenderUnit = null
    }
    layoutResult.recordMeasurement(widthSpec, heightSpec, size.width, size.height)

    layoutResult.measureHadExceptions = size.hadExceptions
    return size
  }

  fun onBoundsDefined(
      layoutResult: LithoLayoutResult,
      xInParent: Int,
      yInParent: Int,
      layoutStateId: Int,
  ) {
    val context = layoutResult.node.tailComponentContext
    val component = layoutResult.node.tailComponent
    val hasLayoutSizeChanged: Boolean

    // Since `measure` is called without padding and border sizes, save the size without padding and
    // borders to align with layout diffing (DiffNode), which relies directly on the last measured
    // size.
    val newContentWidth =
        (layoutResult.width -
                layoutResult.paddingRight -
                layoutResult.paddingLeft -
                layoutResult.borderRight -
                layoutResult.borderLeft)
            .coerceAtLeast(0)
    val newContentHeight =
        (layoutResult.height -
                layoutResult.paddingTop -
                layoutResult.paddingBottom -
                layoutResult.borderTop -
                layoutResult.borderBottom)
            .coerceAtLeast(0)
    if (Component.isMountSpec(component) && component is SpecGeneratedComponent) {

      hasLayoutSizeChanged =
          if (
              (layoutResult.lastMeasuredSize != Long.MIN_VALUE) &&
                  !layoutResult.wasMeasured &&
                  layoutResult.isCachedLayout &&
                  layoutResult.boundsDefined
          ) {
            // Two scenarios would skip measurement and fall into this case:
            // 1. A cached result from Yoga (which may also contain a fixed size).
            // 2. A fixed-size result without measurement whose size might change.
            (newContentWidth != layoutResult.contentWidth) ||
                (newContentHeight != layoutResult.contentHeight)
          } else {
            true
          }

      if (hasLayoutSizeChanged) {

        // We should only invoke `onBoundsDefined` if the layout is not cached or its size has
        // changed. Note: MountSpec is always treated as having changed size once `onMeasure` is
        // invoked, regardless of whether the size changed.
        trace({ "layout.onBoundsDefined:${component.getSimpleName()}" }) {
          val layoutData: InterStagePropsContainer?
          // If the LayoutResult was cached but the size has changed, then inter-stage props
          // container (layout data) could be mutated when @OnBoundsDefined is invoked. To avoid
          // that, create a new inter-stage props container (layout data) and copy over the current
          // values.
          if (layoutResult.isCachedLayout || layoutResult.isDiffedLayout) {
            layoutData = component.createInterStagePropsContainer()
            if (layoutData != null && layoutResult.layoutData != null) {
              component.copyInterStageImpl(
                  layoutData,
                  layoutResult.layoutData as InterStagePropsContainer?,
              )
            }
          } else {
            layoutData = layoutResult.layoutData as InterStagePropsContainer?
          }
          try {
            component.onBoundsDefined(
                context,
                SpecGeneratedComponentLayout(
                    x = xInParent,
                    y = yInParent,
                    measureOutput = layoutResult.measureOutput,
                    paddingSet = layoutResult.node.isPaddingSet,
                    backgroundDrawable = layoutResult.node.background,
                ),
                layoutData,
            )
          } catch (e: Exception) {
            ComponentUtils.handleWithHierarchy(context, component, e)
            layoutResult.measureHadExceptions = true
          }

          // If the layout data has changed, the content render unit should be recreated.
          if (!hasEquivalentFields(layoutResult.layoutData, layoutData)) {
            layoutResult.contentRenderUnit = null
            layoutResult.layoutData = layoutData
          }
        }
      }
      layoutResult.boundsDefined = true
      if (!layoutResult.wasMeasured) {
        layoutResult.recordMeasurement(
            MeasureSpecUtils.exactly(newContentWidth),
            MeasureSpecUtils.exactly(newContentHeight),
            newContentWidth,
            newContentHeight,
        )
      }
    } else if (Component.isPrimitive(component)) {

      hasLayoutSizeChanged =
          newContentWidth != layoutResult.contentWidth ||
              newContentHeight != layoutResult.contentHeight

      if (layoutResult.delegate == null || hasLayoutSizeChanged) {

        // Check whether to run measure for a Primitive that was skipped due to a fixed size.
        val layoutContext =
            when (val output = layoutResult.measureOutput) {
              is YogaLayoutOutput -> getLayoutContextFromYogaNode(output.yogaNode)
              is LeafLayoutOutput -> checkNotNull(output.layoutContext)
              is DefaultMeasureOutput -> error("A Layout's result is never a Primitive's")
            }
        measure(
            layoutContext,
            layoutResult,
            MeasureSpecUtils.exactly(newContentWidth),
            MeasureSpecUtils.exactly(newContentHeight),
        )
      }
    } else {
      hasLayoutSizeChanged =
          (layoutResult.lastMeasuredSize == Long.MIN_VALUE) ||
              (layoutResult.isCachedLayout &&
                  (layoutResult.contentWidth != newContentWidth ||
                      layoutResult.contentHeight != newContentHeight))
      if (hasLayoutSizeChanged) {
        layoutResult.lastMeasuredSize = YogaMeasureOutput.make(newContentWidth, newContentHeight)
      }
    }

    // Reuse or recreate additional outputs. Outputs are recreated if the size has changed
    if (layoutResult.contentRenderUnit == null) {
      layoutResult.contentRenderUnit =
          LithoNodeUtils.createContentRenderUnit(
              node = layoutResult.node,
              areCachedMeasuresValid = layoutResult.cachedMeasuresValid,
              layoutStateId = layoutStateId,
              isCachedLayout = layoutResult.isCachedLayout,
              diffNode = layoutResult.diffNode,
          )
      adjustRenderUnitBounds(layoutResult)
    } else if (!Component.isPrimitive(component)) {
      // A content unit reused from the last pass kept the bounds it was inset by then, but the
      // padding can change with the size (a percentage of the width). A Primitive whose size
      // changed is measured again, which makes a new unit.
      adjustRenderUnitBounds(layoutResult)
    }
    if (layoutResult.hostRenderUnit == null) {
      layoutResult.hostRenderUnit =
          LithoNodeUtils.createHostRenderUnit(layoutResult.node, layoutStateId)
    }
    if (
        layoutResult.shouldDrawBorders() &&
            (layoutResult.borderRenderUnit == null || hasLayoutSizeChanged)
    ) {
      layoutResult.borderRenderUnit =
          LithoNodeUtils.createBorderRenderUnit(
              layoutResult.node,
              createBorderColorDrawable(layoutResult),
              layoutResult.width,
              layoutResult.height,
              layoutStateId,
              layoutResult.diffNode,
          )
    }
  }

  /**
   * Measures a component, reusing the previous measurement from [DiffNode] if the specs match
   * exactly.
   *
   * ## Measure cache behavior
   *
   * The cache reuses the previous result only when `lastWidthSpec == widthSpec && lastHeightSpec ==
   * heightSpec`. This is a strict equality check — any change in the spec value forces
   * re-measurement, even when the result would be identical.
   *
   * This is stricter than necessary. Yoga's internal `YGNodeCanUseCachedMeasurement` implements
   * **spec-compatible** matching: if the previous measured size fits within the new constraint, the
   * cached result is reused.
   *
   * ### When strict equality causes unnecessary re-measurement
   *
   * When a parent's dimension is animated, Yoga passes a new AT_MOST constraint to children every
   * frame. Components whose output doesn't depend on the AT_MOST value (e.g., text that wraps based
   * on width and is far shorter than the height constraint) produce identical results but are
   * re-measured because the specs differ.
   *
   * ### Spec-compatible matching and its edge cases
   *
   * A spec-compatible check would compare the previous *measured size* against the new *constraint*
   * rather than comparing specs directly.
   *
   * Compatibility rules (can skip re-measurement when):
   * - **EXACTLY → EXACTLY**: `oldSize == newSize`
   * - **AT_MOST → AT_MOST**: `measuredSize < min(oldSize, newSize)`
   * - **Any mode change**: never — must re-measure
   *
   * How these rules handle different component behaviors (AT_MOST → AT_MOST):
   * - **Intrinsic size** (e.g., text h=53, AT_MOST 1000→1200): `53 < 1000` → skip. Correct — text
   *   doesn't depend on the constraint.
   * - **Fills constraint** (e.g., scroll h=1000, AT_MOST 1000→1200): `1000 < 1000` is false →
   *   re-measure. Correct — the scroll would grow to 1200.
   * - **Coincidence** (e.g., component naturally h=1000, AT_MOST 1000→1200): `1000 < 1000` is false
   *   → re-measure. Conservative — the result would be the same (h=1000), but we can't distinguish
   *   this from the fills-constraint case without knowing the component's preferred size.
   * - **Fraction of constraint** (e.g., `h = maxHeight / 2` = 500, AT_MOST 1000→1200): `500 < 1000`
   *   → skip. **Wrong** — the result should be 600. No external cache can detect this; such
   *   components should override [Component.shouldAlwaysRemeasure].
   *
   * Key design decisions:
   * - **Strict `<` (not `<=`)**: If `measuredSize == constraintSize`, the component may have been
   *   clamped — it wanted to be larger but was limited by the constraint. A different constraint
   *   could produce a different result.
   * - **No cross-mode transitions** (e.g., EXACTLY → AT_MOST, AT_MOST → EXACTLY): Under EXACTLY,
   *   the component is forced to a specific size. Under AT_MOST, it can choose freely up to the
   *   limit. A component may prefer a completely different size when the mode changes, so the
   *   cached result from one mode is not reliable for a different mode.
   *
   * Edge cases that no external cache can handle:
   * - **Fraction of constraint**: A component that computes its size as a fraction of available
   *   space (e.g., `height = maxHeight / 2`) measures smaller than the constraint but would produce
   *   a different result under a different constraint. Such components should override
   *   [Component.shouldAlwaysRemeasure].
   * - **Layout data depends on constraint**: A component whose measured size is the same but whose
   *   [LithoLayoutResult.layoutData] would differ under new constraints. Rare but possible with
   *   custom primitives.
   */
  private fun measureLithoNode(
      context: LayoutContext<LithoLayoutContext>,
      widthSpec: Int,
      heightSpec: Int,
      lithoLayoutResult: LithoLayoutResult,
  ): MeasureResult {
    val renderContext =
        requireNotNull(context.renderContext) { "render context should not be null" }

    val node: LithoNode = lithoLayoutResult.node
    val component: Component = node.tailComponent
    val componentScopedContext: ComponentContext = node.tailComponentContext
    val diffNode: DiffNode? =
        if (lithoLayoutResult.cachedMeasuresValid) lithoLayoutResult.diffNode else null
    val width: Int
    val height: Int
    val delegate: LayoutResult?
    val layoutData: Any?
    val effects: List<Attachable>?

    // If diff node is set, check if measurements from the previous pass can be reused.
    val specsMatchExactly =
        diffNode?.lastWidthSpec == widthSpec && diffNode.lastHeightSpec == heightSpec
    val specsAreCompatible =
        diffNode != null &&
            isSpecCompatible(diffNode.lastWidthSpec, widthSpec, diffNode.lastMeasuredWidth) &&
            isSpecCompatible(diffNode.lastHeightSpec, heightSpec, diffNode.lastMeasuredHeight)
    if (
        diffNode != null &&
            !shouldAlwaysRemeasure(component) &&
            (specsMatchExactly || specsAreCompatible)
    ) {
      width = diffNode.lastMeasuredWidth
      height = diffNode.lastMeasuredHeight
      layoutData = diffNode.layoutData
      delegate = diffNode.delegate
      lithoLayoutResult.isDiffedLayout = true
      effects = diffNode.effects
      diffNode.stateReads?.let { lithoLayoutResult.addStateReads(it) }

      // Measure the component
    } else {
      trace({ "layout.onMeasure:${component.simpleName}" }) {
        val stateReads =
            StateReadRecorder.record(
                renderContext.treeId,
                attribution("layout", component),
            ) {
              val contentLayout = measureContentLayout(
                  context,
                  lithoLayoutResult,
                  widthSpec,
                  heightSpec,
              )
              width = contentLayout.width
              height = contentLayout.height
              delegate = contentLayout.delegate
              effects = contentLayout.effects
              val newLayoutData = contentLayout.layoutData

              // If layout data has changed, the content render unit should be recreated.
              if (!hasEquivalentFields(lithoLayoutResult.layoutData, newLayoutData)) {
                layoutData = newLayoutData
                lithoLayoutResult.contentRenderUnit = null
              } else {
                layoutData = lithoLayoutResult.layoutData
              }
            }
        lithoLayoutResult.addStateReads(stateReads)
      }
    }
    lithoLayoutResult.delegate = delegate
    lithoLayoutResult.layoutData = layoutData
    lithoLayoutResult.effects = effects
    return MeasureResult(width, height, layoutData)
  }

  /**
   * Pure leaf measurement for [measureLithoNode]. Writes nothing to the [LithoLayoutResult], the
   * diff cache, or state-reads — callers own that.
   */
  private fun measureContentLayout(
      context: LayoutContext<LithoLayoutContext>,
      lithoLayoutResult: LithoLayoutResult,
      widthSpec: Int,
      heightSpec: Int,
  ): LeafMeasureResult {
    val renderContext =
        requireNotNull(context.renderContext) { "render context should not be null" }
    val node: LithoNode = lithoLayoutResult.node
    val component: Component = node.tailComponent
    val componentScopedContext: ComponentContext = node.tailComponentContext
    val primitive = node.primitive

    if (primitive != null) {
      val contextForLayoutScope = LithoExtraContextForLayoutScope(
          componentContext = componentScopedContext,
          layoutDirection = lithoLayoutResult.layoutDirection,
          layoutVersion = renderContext.layoutVersion,
      )
      context.setPreviousLayoutDataForCurrentNode(lithoLayoutResult.layoutData)
      context.setExtraContextForCurrentNode(contextForLayoutScope)

      @Suppress("UNCHECKED_CAST") val layoutContext = context as LayoutContext<Any?>
      val sizeConstraints = SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec)
      val delegate = primitive.calculateLayout(layoutContext, sizeConstraints)
      return LeafMeasureResult(
          width = delegate.width,
          height = delegate.height,
          layoutData = delegate.layoutData,
          delegate = delegate,
          effects = contextForLayoutScope.effects,
      )
    }

    val specComponent = component as SpecGeneratedComponent
    // Not placed yet: it sees where its own engine positioned it, its Yoga node's last position, or
    // the origin under a `Layout`.
    val yogaNode = (lithoLayoutResult.measureOutput as? YogaLayoutOutput)?.yogaNode
    val specLayout = SpecGeneratedComponentLayout(
        x = yogaNode?.layoutX?.toInt() ?: 0,
        y = yogaNode?.layoutY?.toInt() ?: 0,
        measureOutput = lithoLayoutResult.measureOutput,
        paddingSet = lithoLayoutResult.node.isPaddingSet,
        backgroundDrawable = lithoLayoutResult.node.background,
    )

    // Measure into a fresh layout-data container so a cached layout's mounted data isn't mutated.
    val size = Size(Int.MIN_VALUE, Int.MIN_VALUE)
    val newLayoutData = specComponent.createInterStagePropsContainer()
    specComponent.onMeasure(
        componentScopedContext,
        specLayout,
        widthSpec,
        heightSpec,
        size,
        newLayoutData,
    )
    return LeafMeasureResult(
        width = size.width,
        height = size.height,
        layoutData = newLayoutData,
    )
  }

  /**
   * Companion to [measureContentLayout] for the min-content intrinsic-size probe. The probe bounds
   * exactly one axis to AtMost 0 and leaves the cross axis unbounded; only the bounded axis is read
   * back, so only that one is computed. Writes nothing persistent — the caller owns that.
   */
  private fun measureMinContentLayout(
      context: LayoutContext<LithoLayoutContext>,
      lithoLayoutResult: LithoLayoutResult,
      widthSpec: Int,
      heightSpec: Int,
  ): LeafMeasureResult {
    val renderContext =
        requireNotNull(context.renderContext) { "render context should not be null" }
    val node: LithoNode = lithoLayoutResult.node
    val component: Component = node.tailComponent
    val componentScopedContext: ComponentContext = node.tailComponentContext
    val primitive = node.primitive
    val sizeConstraints = SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec)

    if (primitive != null) {
      val contextForLayoutScope = LithoExtraContextForLayoutScope(
          componentContext = componentScopedContext,
          layoutDirection = lithoLayoutResult.layoutDirection,
          layoutVersion = renderContext.layoutVersion,
      )
      context.setPreviousLayoutDataForCurrentNode(lithoLayoutResult.layoutData)
      context.setExtraContextForCurrentNode(contextForLayoutScope)

      @Suppress("UNCHECKED_CAST") val layoutContext = context as LayoutContext<Any?>
      // Transient probe; scope isMinContentMeasure (restored after) so nested layout detects it.
      val prevMinContent = context.isMinContentMeasure
      context.isMinContentMeasure = true
      try {
        return when {
          SizeSpec.getMode(widthSpec) == SizeSpec.AT_MOST ->
              LeafMeasureResult(
                  width = primitive.calculateMinIntrinsicWidth(layoutContext, sizeConstraints),
                  height = 0,
              )
          SizeSpec.getMode(heightSpec) == SizeSpec.AT_MOST ->
              LeafMeasureResult(
                  width = 0,
                  height = primitive.calculateMinIntrinsicHeight(layoutContext, sizeConstraints),
              )
          else -> throw IllegalArgumentException(minContentAxisError(sizeConstraints))
        }
      } finally {
        context.isMinContentMeasure = prevMinContent
      }
    }

    val specComponent = component as SpecGeneratedComponent
    // Not placed yet: it sees where its own engine positioned it, its Yoga node's last position, or
    // the origin under a `Layout`.
    val yogaNode = (lithoLayoutResult.measureOutput as? YogaLayoutOutput)?.yogaNode
    val specLayout = SpecGeneratedComponentLayout(
        x = yogaNode?.layoutX?.toInt() ?: 0,
        y = yogaNode?.layoutY?.toInt() ?: 0,
        measureOutput = lithoLayoutResult.measureOutput,
        paddingSet = lithoLayoutResult.node.isPaddingSet,
        backgroundDrawable = lithoLayoutResult.node.background,
    )
    return when {
      SizeSpec.getMode(widthSpec) == SizeSpec.AT_MOST ->
          LeafMeasureResult(
              width =
                  specComponent.onMeasureIntrinsicWidth(
                      componentScopedContext,
                      specLayout,
                      widthSpec,
                      heightSpec,
                  ),
              height = 0,
          )
      SizeSpec.getMode(heightSpec) == SizeSpec.AT_MOST ->
          LeafMeasureResult(
              width = 0,
              height =
                  specComponent.onMeasureIntrinsicHeight(
                      componentScopedContext,
                      specLayout,
                      widthSpec,
                      heightSpec,
                  ),
          )
      else -> throw IllegalArgumentException(minContentAxisError(sizeConstraints))
    }
  }

  private fun minContentAxisError(sizeConstraints: SizeConstraints): String =
      "Min-content intrinsic-size probe expects exactly one axis bounded to AtMost: $sizeConstraints"

  private fun measureDeferredNode(
      context: LayoutContext<LithoLayoutContext>,
      widthSpec: Int,
      heightSpec: Int,
      lithoLayoutResult: DeferredLithoLayoutResult,
  ): MeasureResult {
    val component = lithoLayoutResult.node.tailComponent
    val renderContext = checkNotNull(context.renderContext)

    check(!renderContext.isReleased) {
      (component.simpleName +
          ": To measure a component outside of a layout calculation use" +
          " Component#measureMightNotCacheInternalNode.")
    }

    val count = lithoLayoutResult.node.componentCount
    val parentContext: ComponentContext? =
        if (count == 1) {
          val parentFromNode = lithoLayoutResult.node.parentContext
          parentFromNode ?: renderContext.rootComponentContext
        } else {
          lithoLayoutResult.node.getComponentContextAt(1)
        }

    checkNotNull(parentContext) { component.simpleName + ": Null component context during measure" }

    return trace({ "layout.resolveDeferredNode:${component.simpleName}" }) {
      val actualResult =
          LayoutUtils.measure(
              renderContext,
              parentContext,
              lithoLayoutResult,
              widthSpec,
              heightSpec,
          )

      if (actualResult != null) {
        MeasureResult(actualResult.width, actualResult.height, actualResult.layoutData)
      } else {
        MeasureResult(0, 0)
      }
    }
  }

  private fun adjustRenderUnitBounds(lithoLayoutResult: LithoLayoutResult) {
    val renderUnit: LithoRenderUnit = lithoLayoutResult.contentRenderUnit ?: return
    val bounds = Rect()
    if (Component.isPrimitive(renderUnit.component)) {
      if (!LithoRenderUnit.isMountableView(renderUnit)) {
        if (lithoLayoutResult.wasMeasured) {
          bounds.left += (lithoLayoutResult.paddingLeft + lithoLayoutResult.borderLeft)
          bounds.top += (lithoLayoutResult.paddingTop + lithoLayoutResult.borderTop)
          bounds.right -= (lithoLayoutResult.paddingRight + lithoLayoutResult.borderRight)
          bounds.bottom -= (lithoLayoutResult.paddingBottom + lithoLayoutResult.borderBottom)
        } else {
          // For an exact size, the border doesn't need to be adjusted because it's inside the
          // content bounds.
          bounds.left += lithoLayoutResult.paddingLeft
          bounds.top += lithoLayoutResult.paddingTop
          bounds.right -= lithoLayoutResult.paddingRight
          bounds.bottom -= lithoLayoutResult.paddingBottom
        }
      }
    } else if (!LithoRenderUnit.isMountableView(renderUnit)) {
      bounds.left += lithoLayoutResult.paddingLeft
      bounds.top += lithoLayoutResult.paddingTop
      bounds.right -= lithoLayoutResult.paddingRight
      bounds.bottom -= lithoLayoutResult.paddingBottom
    }
    lithoLayoutResult.adjustedBounds.set(bounds)
  }

  // This is used when we need to create a new output other than the YogaNode for layout caching.
  private fun copyLayoutResult(
      layoutResult: LithoLayoutResult,
      lithoNode: LithoNode,
      yogaNode: YogaNode,
  ): LithoLayoutResult =
      lithoNode
          .createLayoutResult(
              measureOutput = layoutResult.yogaLayoutOutput.copy(yogaNode = yogaNode),
          )
          .apply { copyCachedFrom(layoutResult) }

  private fun LithoLayoutResult.shouldDrawBorders(): Boolean =
      node.hasBorderColor() &&
          (borderLeft != 0 || borderTop != 0 || borderRight != 0 || borderBottom != 0)

  private fun createBorderColorDrawable(result: LithoLayoutResult): BorderColorDrawable {
    val node = result.node
    val isRtl = recursivelyResolveLayoutDirection(result) == YogaDirection.RTL
    val borderRadius = node.borderRadius
    val borderColors = node.borderColors
    val leftEdge = if (isRtl) YogaEdge.RIGHT else YogaEdge.LEFT
    val rightEdge = if (isRtl) YogaEdge.LEFT else YogaEdge.RIGHT
    return BorderColorDrawable.Builder()
        .pathEffect(node.borderPathEffect)
        .borderLeftColor(Border.getEdgeColor(borderColors, leftEdge))
        .borderTopColor(Border.getEdgeColor(borderColors, YogaEdge.TOP))
        .borderRightColor(Border.getEdgeColor(borderColors, rightEdge))
        .borderBottomColor(Border.getEdgeColor(borderColors, YogaEdge.BOTTOM))
        .borderLeftWidth(if (isRtl) result.borderRight else result.borderLeft)
        .borderTopWidth(result.borderTop)
        .borderRightWidth(if (isRtl) result.borderLeft else result.borderRight)
        .borderBottomWidth(result.borderBottom)
        .borderRadius(borderRadius)
        .build()
  }

  private fun recursivelyResolveLayoutDirection(result: LithoLayoutResult): YogaDirection {
    if (result.measureOutput !is YogaLayoutOutput) {
      return result.measureOutput.layoutDirection.toYogaDirection()
    }
    val direction = result.getYogaNode().layoutDirection
    check(direction != YogaDirection.INHERIT) {
      "Direction cannot be resolved before layout calculation"
    }
    return direction
  }

  internal fun shouldAlwaysRemeasure(component: Component): Boolean =
      if (component is SpecGeneratedComponent) {
        component.shouldAlwaysRemeasure()
      } else {
        false
      }

  /**
   * Checks whether a single dimension's previous measurement can be reused under a new spec without
   * re-measuring.
   *
   * Compatibility rules:
   * - Same mode required (no cross-mode transitions)
   * - EXACTLY → EXACTLY: sizes must be equal (already caught by strict equality in caller)
   * - AT_MOST → AT_MOST: measuredSize must be strictly less than both old and new constraint
   * - UNSPECIFIED → UNSPECIFIED: always compatible (no constraint to violate)
   *
   * Strict `<` is used intentionally: if measuredSize == constraintSize, the component may have
   * been clamped by the constraint and could produce a different (larger) result under a different
   * constraint.
   */
  internal fun isSpecCompatible(
      oldSpec: Int,
      newSpec: Int,
      measuredSize: Int,
  ): Boolean {
    val oldMode = SizeSpec.getMode(oldSpec)
    val newMode = SizeSpec.getMode(newSpec)
    if (oldMode != newMode) return false
    return when (oldMode) {
      SizeSpec.EXACTLY -> SizeSpec.getSize(oldSpec) == SizeSpec.getSize(newSpec)
      SizeSpec.AT_MOST ->
          measuredSize < SizeSpec.getSize(oldSpec) && measuredSize < SizeSpec.getSize(newSpec)
      SizeSpec.UNSPECIFIED -> true
      else -> false
    }
  }

  @Suppress("UNCHECKED_CAST")
  internal fun getLayoutContextFromYogaNode(yogaNode: YogaNode): LayoutContext<LithoLayoutContext> =
      (yogaNode.data as Pair<*, *>).first as LayoutContext<LithoLayoutContext>

  internal fun getLayoutResultFromYogaNode(yogaNode: YogaNode): LithoLayoutResult =
      (yogaNode.data as Pair<*, *>).second as LithoLayoutResult

  /**
   * Companion to [measure] for the min-content intrinsic-size probe. Opted-in leaf nodes measure
   * via [measureMinContentLayout] (transient — not persisted); deferred or non-opted-in nodes fall
   * back to [measure].
   */
  fun measureMinContentSize(
      context: LayoutContext<LithoLayoutContext>,
      layoutResult: LithoLayoutResult,
      widthSpec: Int,
      heightSpec: Int,
  ): MeasureResult {
    val node = layoutResult.node
    // Only leaves that opted into intrinsic sizing have a min-content path; others re-measure.
    val hasIntrinsicSize =
        node.primitive?.layoutBehavior?.hasIntrinsicSize
            ?: Component.hasIntrinsicSize(node.tailComponent)
    if (node is DeferredLithoNode || !hasIntrinsicSize) {
      return measure(context, layoutResult, widthSpec, heightSpec)
    }

    val renderContext =
        requireNotNull(context.renderContext) { "render context should not be null" }
    if (renderContext.isFutureReleased) {
      return MeasureResult.error()
    }

    val component = node.tailComponent
    var size: MeasureResult
    traceWithArgs(
        { "measureMinContentSize:${component.simpleName}" },
        { scope ->
          scope.attribute("widthSpec", SizeSpec.toString(widthSpec))
          scope.attribute("heightSpec", SizeSpec.toString(heightSpec))
          scope.attribute("componentId", component.instanceId)
        },
    ) {
      try {
        val leaf = measureMinContentLayout(
            context,
            layoutResult,
            widthSpec,
            heightSpec,
        )
        check(!(leaf.width < 0 || leaf.height < 0)) {
          ("MeasureOutput not set, Component is: $component " +
              "WidthSpec: ${MeasureSpecUtils.getMeasureSpecDescription(widthSpec)} " +
              "HeightSpec: ${MeasureSpecUtils.getMeasureSpecDescription(heightSpec)} " +
              "Measured width : ${leaf.width} " +
              "Measured Height: ${leaf.height}")
        }
        size = MeasureResult(leaf.width, leaf.height)
      } catch (e: Exception) {
        ComponentUtils.handle(node.tailComponentContext, e)
        size = MeasureResult.error()
      }
    }
    return size
  }

  private fun LithoNode.createYogaNodeWriter(): YogaLayoutProps {
    return when (this) {
      is DeferredLithoNode -> {
        DeferredNodeYogaLayoutProps(NodeConfig.createYogaNode())
      }
      is NullNode -> {
        NullWriter().apply { node.display = YogaDisplay.NONE }
      }
      else -> {
        YogaLayoutProps(NodeConfig.createYogaNode())
      }
    }
  }

  private fun LithoNode.writeNodePropsToYogaNode(
      context: LayoutContext<LithoLayoutContext>,
      writer: YogaLayoutProps,
      props: LayoutProps = writer,
  ) {
    val node: YogaNode = writer.node

    // Apply the extra layout props
    layoutDirection.let { node.setDirection(it.toYogaDirection()) }
    flexDirection?.let { node.flexDirection = it }
    justifyContent?.let { node.justifyContent = it }
    alignContent?.let { node.alignContent = it }
    alignItems?.let { node.alignItems = it }
    yogaWrap?.let { node.wrap = it }
    if (columnGapPx > 0) node.setGap(YogaGutter.COLUMN, columnGapPx.toFloat())
    if (rowGapPx > 0) node.setGap(YogaGutter.ROW, rowGapPx.toFloat())
    if (allGapPx > 0) node.setGap(YogaGutter.ALL, allGapPx.toFloat())
    yogaMeasureFunction?.let { node.setMeasureFunction(it) }
    yogaMinContentMeasureFunction?.let { node.setMinContentMeasureFunction(it) }
    // CSS Flexbox §4.5 auto-min is opt-in. By default a leaf measurer contributes 0 min-content: a
    // defined static 0 short-circuits Yoga's probe at this node, preserving today's behaviour at no
    // extra measure cost. A Primitive ([LayoutBehavior.hasIntrinsicSize]) or MountSpec
    // (`@MountSpec(hasIntrinsicSize = true)`) opts in to a real measurement. Containers have no
    // measure function, so they are left undefined and Yoga recurses into their children.
    val isLeafMeasurer = yogaMeasureFunction != null || primitive != null
    val hasIntrinsicSize =
        primitive?.layoutBehavior?.hasIntrinsicSize ?: Component.hasIntrinsicSize(tailComponent)
    if (isLeafMeasurer && !hasIntrinsicSize) {
      node.setMinContentWidth(0f)
      node.setMinContentHeight(0f)
    }

    writer.addStateReads(copyLayoutPropsInto(context, props))
    isPaddingSet = writer.isPaddingSet || (props is ContainerChildLayoutProps && props.isPaddingSet)
  }

  private fun DeferredLithoNode.writeDeferredNodePropsToYogaNode(
      context: LayoutContext<LithoLayoutContext>,
      writer: DeferredNodeYogaLayoutProps,
      props: LayoutProps = writer,
  ) {
    writeNodePropsToYogaNode(context, writer, props)
    deferredBorders = writer.borderWidth
    deferredPaddings = writer.padding
    deferredIsPaddingPercentages = writer.isPaddingPercentage
  }
}

/**
 * A wrapper around [YogaNode] that extends [LithoMeasureOutput], which will be used internally by
 * [LithoYogaLayoutFunction].
 */
@DataClassGenerate(toString = Mode.KEEP, equalsHashCode = Mode.KEEP)
internal data class YogaLayoutOutput(
    val yogaNode: YogaNode,
    val widthFromStyle: YogaValue,
    val heightFromStyle: YogaValue,
    val minWidthFromStyle: YogaValue,
    val maxWidthFromStyle: YogaValue,
    val minHeightFromStyle: YogaValue,
    val maxHeightFromStyle: YogaValue,
) : LithoMeasureOutput() {

  override fun childX(index: Int): Int = yogaNode.getChildAt(index).layoutX.toInt()

  override fun childY(index: Int): Int = yogaNode.getChildAt(index).layoutY.toInt()

  override val width: Int
    get() = yogaNode.layoutWidth.toInt()

  override val height: Int
    get() = yogaNode.layoutHeight.toInt()

  override val paddingLeft: Int
    get() = FastMath.round(yogaNode.getLayoutPadding(YogaEdge.LEFT))

  override val paddingTop: Int
    get() = FastMath.round(yogaNode.getLayoutPadding(YogaEdge.TOP))

  override val paddingRight: Int
    get() = FastMath.round(yogaNode.getLayoutPadding(YogaEdge.RIGHT))

  override val paddingBottom: Int
    get() = FastMath.round(yogaNode.getLayoutPadding(YogaEdge.BOTTOM))

  override val borderLeft: Int
    get() = FastMath.round(yogaNode.getLayoutBorder(YogaEdge.LEFT))

  override val borderTop: Int
    get() = FastMath.round(yogaNode.getLayoutBorder(YogaEdge.TOP))

  override val borderRight: Int
    get() = FastMath.round(yogaNode.getLayoutBorder(YogaEdge.RIGHT))

  override val borderBottom: Int
    get() = FastMath.round(yogaNode.getLayoutBorder(YogaEdge.BOTTOM))

  override val layoutDirection: LayoutDirection
    get() = yogaNode.layoutDirection.toLayoutDirection()

  override fun releaseLayoutContext() {
    yogaNode.data = null
  }

  companion object {
    /**
     * The Yoga engine's state for this result, which a Yoga pass built; it throws for an output
     * another engine built.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline val LithoLayoutResult.yogaLayoutOutput: YogaLayoutOutput
      get() = measureOutput as? YogaLayoutOutput ?: notYogaOutput(this)

    @Suppress("NOTHING_TO_INLINE")
    inline fun LithoLayoutResult.getYogaNode(): YogaNode = yogaLayoutOutput.yogaNode

    fun notYogaOutput(result: LithoLayoutResult): Nothing =
        error("${result.node.headComponent.simpleName} has no Yoga layout output")

    val LithoLayoutResult.layoutDirection: LayoutDirection
      get() = measureOutput.layoutDirection
  }
}

/** Raw result of a single leaf measurement. */
private class LeafMeasureResult(
    val width: Int,
    val height: Int,
    val layoutData: Any? = null,
    val delegate: LayoutResult? = null,
    val effects: List<Attachable>? = null,
)

/**
 * Yoga's measure callback for a `Layout` in a Yoga tree: its builder, which its result holds until
 * it's built, measures it within the room Yoga offers its content. A failure measures as if it
 * declared 0 x 0 and is held until it's built, so no exception a layout function throws crosses
 * into Yoga's native code.
 */
internal object DefaultLayoutMeasureFunction : YogaMeasureFunction {

  override fun measure(
      cssNode: YogaNode,
      width: Float,
      widthMode: YogaMeasureMode,
      height: Float,
      heightMode: YogaMeasureMode,
  ): Long {
    val result = LithoYogaLayoutFunction.getLayoutResultFromYogaNode(cssNode)
    val builder =
        checkNotNull(result.defaultLayoutBuilder) {
          "${result.node.headComponent.simpleName} measured by Yoga after it was built or discarded"
        }
    val room =
        SizeConstraints.fromMeasureSpecs(
            widthSpec = SizeSpec.makeSizeSpecFromCssSpec(width, widthMode),
            heightSpec = SizeSpec.makeSizeSpecFromCssSpec(height, heightMode),
        )
    val size = builder.measureUnder(room)
    return YogaMeasureOutput.make(size.width, size.height)
  }
}
