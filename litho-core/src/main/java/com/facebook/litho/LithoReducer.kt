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
import androidx.collection.MutableIntList
import androidx.collection.MutableLongIntMap
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.mutableScatterSetOf
import androidx.core.view.ViewCompat
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.LithoLayoutData.Companion.NO_Z_INDEX
import com.facebook.litho.animation.StructuralStyleTransitionId
import com.facebook.litho.animation.StyleTransitionData
import com.facebook.litho.animation.StyleTransitionId
import com.facebook.litho.animation.StyleTransitionTarget
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.state.StateId
import com.facebook.litho.stats.LithoStats
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LayoutCache
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.MountState
import com.facebook.rendercore.PoolScope
import com.facebook.rendercore.RenderState
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.incrementalmount.IncrementalMountOutput
import com.facebook.rendercore.incrementalmount.IncrementalMountRenderCoreExtension
import com.facebook.rendercore.visibility.VisibilityEventCallbackData
import com.facebook.rendercore.visibility.VisibilityOutput
import kotlin.math.max
import kotlin.math.min

internal object LithoReducer {

  private const val DUPLICATE_TRANSITION_IDS = "LayoutState:DuplicateTransitionIds"

  /**
   * Minimum scope count (LithoNode count) for a subtree to be cache-eligible. Counts ALL nodes
   * traversed in the subtree's resolve, including non-mounting containers like Column / Row.
   * Correlates with skipped resolve+measure work — a deep narrow subtree (e.g. NestedColumn x 8 + 1
   * Text leaf = 9 scopes, only 1 mountable output) is still worth caching.
   *
   * Below this threshold, cache write + lookup + splice overhead exceeds the resolve savings.
   * Empirically: wide-shallow Text leaves (~2 scopes each) regressed ~7% at the unbounded
   * threshold. Threshold of 3 excludes single- leaf subtrees while keeping NestedColumn-style deep
   * narrow trees eligible. Tunable; revisit with prod hit-rate data.
   */
  private const val MIN_SUBTREE_SCOPES = 3

  // region public methods
  @JvmStatic
  fun reduce(
      lsc: LithoLayoutContext,
      resolveResult: ResolveResult,
      version: Int,
      treeId: Int,
      treeState: TreeState,
      layoutCache: LayoutCache,
      reductionState: ReductionState,
  ): LayoutState {

    setSizeAfterMeasureAndCollectResults(reductionState, lsc)

    if (reductionState.mountableOutputs.isEmpty()) {
      addRootHostRenderTreeNode(reductionState)
    }

    return LayoutState(
        resolveResult,
        version,
        treeState,
        treeId,
        lsc.isAccessibilityEnabled,
        layoutCache.toCacheData(),
        reductionState.toLite(),
        lsc.reflowTargetSizes,
    )
  }

  private fun setSizeAfterMeasureAndCollectResults(
      reductionState: ReductionState,
      lithoLayoutContext: LithoLayoutContext,
  ) {
    if (lithoLayoutContext.isFutureReleased) {
      return
    }

    if (ComponentsConfiguration.enableSubtreeCaching) {
      reductionState.outputParentIndices = MutableIntList()
    }

    check(reductionState.mountableOutputs.isEmpty()) {
      """
      Attempting to collect results on an already populated ReductionState.
      | Root: ${reductionState.componentRootName}"""
          .trimIndent()
    }

    val context: ComponentContext = reductionState.componentContext
    val widthSpec: Int = reductionState.widthSpec
    val heightSpec: Int = reductionState.heightSpec
    val root: LayoutResult? = reductionState.rootLayoutResult
    val rootWidth: Int = root?.width ?: 0
    val rootHeight: Int = root?.height ?: 0

    reductionState.width =
        when (SizeSpec.getMode(widthSpec)) {
          SizeSpec.EXACTLY -> SizeSpec.getSize(widthSpec)
          SizeSpec.AT_MOST -> max(0, min(rootWidth, SizeSpec.getSize(widthSpec)))
          SizeSpec.UNSPECIFIED -> rootWidth
          else -> reductionState.width
        }
    reductionState.height =
        when (SizeSpec.getMode(heightSpec)) {
          SizeSpec.EXACTLY -> SizeSpec.getSize(heightSpec)
          SizeSpec.AT_MOST -> max(0, min(rootHeight, SizeSpec.getSize(heightSpec)))
          SizeSpec.UNSPECIFIED -> rootHeight
          else -> reductionState.height
        }

    if (root == null) {
      return
    }

    var parent: RenderTreeNode? = null
    var hierarchy: DebugHierarchy.Node? = null
    if (context.lithoConfiguration.componentsConfig.shouldAddHostViewForRootComponent) {
      hierarchy = if (root is LithoLayoutResult) root.node.getDebugHierarchy() else null
      addRootHostRenderTreeNode(reductionState, root, hierarchy)
      parent = reductionState.mountableOutputs[0]
    }

    trace({ "reduce.collectResults" }) {
      try {
        collectResults(
            parentContext = context,
            result = root,
            reductionState = reductionState,
            lithoLayoutContext = lithoLayoutContext,
            x = 0,
            y = 0,
            parent = parent,
            parentHierarchy = hierarchy,
        )
      } catch (e: Exception) {
        throw ComponentUtils.wrapWithMetadata(context, e)
      }
    }

    // Finalize host bounds BEFORE sorting — expandHostToContainChild mutates
    // IMO bounds rects, which the sort comparators read.
    if (ComponentsConfiguration.useFinalizeForIMOBounds) {
      finalizeIncrementalMountBounds(reductionState)
    }

    trace({ "reduce.sortMountableOutputs" }) {
      sortTops(reductionState)
      sortBottoms(reductionState)
    }
  }

  private fun addRootHostRenderTreeNode(
      reductionState: ReductionState,
      result: LayoutResult? = null,
      hierarchy: DebugHierarchy.Node? = null,
  ) {
    val width: Int = result?.width ?: 0
    val height: Int = result?.height ?: 0
    val debugNode: DebugHierarchy.Node? = hierarchy?.mutateType(OutputUnitType.HOST)
    val rootHostRenderUnit =
        createRootHostRenderUnit(reductionState.componentContext, reductionState.id)

    val node: RenderTreeNode = create(
        unit = rootHostRenderUnit,
        bounds = Rect(0, 0, width, height),
        padding = null,
        layoutData =
            LithoLayoutData(
                width = width,
                height = height,
                zIndex = NO_Z_INDEX,
                expandedTouchBounds = null,
                layoutData = null,
                isSizeDependant = true,
                debugHierarchy = debugNode,
            ),
        poolScope = reductionState.poolScopeFor(rootHostRenderUnit),
    )

    addRenderTreeNode(
        reductionState = reductionState,
        node = node,
        result = result,
        unit = rootHostRenderUnit,
        type = OutputUnitType.HOST,
    )
  }

  private fun createRootHostRenderUnit(
      context: ComponentContext,
      layoutStateId: Int,
  ): MountSpecLithoRenderUnit {
    val component = HostComponent.create(context)
    val globalKey: GlobalKey = ComponentKeyUtils.generateGlobalKey(context, null, component)
    val c: ComponentContext = ComponentContext.withComponentScope(context, component, globalKey)
    return MountSpecLithoRenderUnit.create(
        id = MountState.ROOT_HOST_ID,
        component = component,
        commonDynamicProps = null,
        context = c,
        nodeInfo = null,
        flags = 0,
        importantForAccessibility = ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        updateState = MountSpecLithoRenderUnit.STATE_DIRTY,
        contentVersion = layoutStateId,
        debugKey = LithoNodeUtils.getDebugKey("root-host", OutputUnitType.HOST),
    )
  }

  private fun createAnimatableItem(
      unit: LithoRenderUnit,
      absoluteBounds: Rect,
      @OutputUnitType outputType: Int,
      transitionId: TransitionId? = null,
      bounds: Rect = absoluteBounds,
  ): AnimatableItem =
      LithoAnimtableItem(unit.id, absoluteBounds, outputType, unit.nodeInfo, transitionId, bounds)

  private fun createDiffNode(tail: ScopedComponentInfo, parent: DiffNode? = null): DiffNode {
    val diffNode = DefaultDiffNode(
        tail.component,
        tail.context.globalKey,
        tail,
    )
    parent?.addChild(diffNode)
    return diffNode
  }

  // endregion

  /**
   * Acquires a new layout output for the internal node and its associated component. It returns
   * null if there's no component associated with the node as the mount pass only cares about nodes
   * that will potentially mount content into the component host.
   */
  private fun createContentRenderTreeNode(
      result: LayoutResult,
      node: LithoNode,
      bounds: Rect,
      padding: Rect?,
      reductionState: ReductionState,
      expandedTouchBounds: Rect?,
      parent: RenderTreeNode? = null,
      debugHierarchyNode: DebugHierarchy.Node? = null,
  ): RenderTreeNode? {

    if (
        Component.isLayoutSpec(node.tailComponent) ||
            (result !is LithoLayoutResult) ||
            result.measureHadExceptions
    ) {
      // back out when dealing with Layout Specs or if there was an error during measure
      return null
    }

    val unit: LithoRenderUnit = result.contentRenderUnit ?: return null
    val layoutData: Any? = result.layoutData
    val debugNode: DebugHierarchy.Node? = debugHierarchyNode?.mutateType(OutputUnitType.CONTENT)
    return createRenderTreeNode(
        unit = unit,
        bounds =
            Rect(
                bounds.left + result.adjustedLeft(),
                bounds.top + result.adjustedTop(),
                bounds.right + result.adjustedRight(),
                bounds.bottom + result.adjustedBottom(),
            ),
        padding,
        reductionState = reductionState,
        isSizeDependant =
            if (node.tailComponent is SpecGeneratedComponent) {
              (node.tailComponent as SpecGeneratedComponent).isMountSizeDependent
            } else {
              false
            },
        expandedTouchBounds = expandedTouchBounds,
        zIndex =
            if (result.hostRenderUnit != null) {
              result.node.zIndex
            } else {
              NO_Z_INDEX
            },
        layoutData = layoutData,
        parent = parent,
        debugHierarchyNode = debugNode,
    )
  }

  private fun createRenderTreeNode(
      unit: LithoRenderUnit,
      bounds: Rect,
      padding: Rect?,
      reductionState: ReductionState,
      isSizeDependant: Boolean,
      expandedTouchBounds: Rect?,
      zIndex: Int? = NO_Z_INDEX,
      layoutData: Any? = null,
      parent: RenderTreeNode? = null,
      debugHierarchyNode: DebugHierarchy.Node? = null,
  ): RenderTreeNode {

    val hostTranslationX: Int = parent?.absoluteX ?: 0
    val hostTranslationY: Int = parent?.absoluteY ?: 0
    val l: Int = bounds.left - hostTranslationX
    val t: Int = bounds.top - hostTranslationY
    val r: Int = l + bounds.width()
    val b: Int = t + bounds.height()
    val resolvedBounds = Rect(l, t, r, b)

    return create(
        unit = unit,
        bounds = resolvedBounds,
        padding = padding,
        layoutData =
            LithoLayoutData(
                width = resolvedBounds.width(),
                height = resolvedBounds.height(),
                expandedTouchBounds = expandedTouchBounds,
                zIndex = zIndex,
                isSizeDependant = isSizeDependant,
                layoutData = layoutData,
                debugHierarchy = debugHierarchyNode,
            ),
        parent = parent,
        poolScope = reductionState.poolScopeFor(unit),
    )
  }

  /**
   * With the global flag every pooled content uses the tree's scope. A tree that opts in through
   * its configuration scopes only the content that asks for it.
   */
  private fun ReductionState.poolScopeFor(unit: RenderUnit<*>): PoolScope =
      if (
          ComponentsConfiguration.customPoolScopesEnabled ||
              unit.contentAllocator.usesCustomPoolScope
      ) {
        poolScope
      } else {
        PoolScope.None
      }

  /**
   * Acquires a [VisibilityOutput] object and computes the bounds for it using the information
   * stored in the [LithoNode].
   */
  private fun createVisibilityOutput(
      node: LithoNode,
      bounds: Rect,
      renderTreeNode: RenderTreeNode?,
  ): VisibilityOutput {

    val onVisible =
        node.onVisible?.let {
          VisibilityEventCallbackData(
              widthRatio = node.visibleWidthRatio,
              heightRatio = node.visibleHeightRatio,
              callback = it,
          )
        }

    val onFocusedVisible =
        node.onFocusedVisible?.let {
          VisibilityEventCallbackData(
              widthRatio = node.visibleWidthRatio,
              heightRatio = node.visibleHeightRatio,
              callback = it,
          )
        }
    val onUnfocusedVisible =
        node.onUnfocusedVisible?.let {
          VisibilityEventCallbackData(
              widthRatio = node.visibleWidthRatio,
              heightRatio = node.visibleHeightRatio,
              callback = it,
          )
        }
    val onFullImpression =
        node.onFullImpression?.let {
          VisibilityEventCallbackData(
              widthRatio = node.visibleWidthRatio,
              heightRatio = node.visibleHeightRatio,
              callback = it,
          )
        }
    val onInvisible =
        node.onInvisible?.let {
          VisibilityEventCallbackData(
              widthRatio = node.visibleWidthRatio,
              heightRatio = node.visibleHeightRatio,
              callback = it,
          )
        }
    val onVisibilityChange =
        node.onVisibilityChanged?.let {
          VisibilityEventCallbackData(
              widthRatio = node.visibleWidthRatio,
              heightRatio = node.visibleHeightRatio,
              callback = it,
          )
        }
    val component: Component = node.tailComponent
    val ownerName = node.headComponent.simpleName

    return VisibilityOutput(
        id = node.tailComponentKey,
        key =
            if (ownerName != component.simpleName) "$ownerName(${component.simpleName})"
            else component.simpleName,
        bounds = Rect(bounds),
        hasMountableContent = renderTreeNode != null,
        renderUnitId = renderTreeNode?.renderUnit?.id ?: 0,
        visibleWidthRatio = node.visibleWidthRatio,
        visibleHeightRatio = node.visibleHeightRatio,
        tag = node.visibilityOutputTag,
        componentClassName = component.javaClass.name,
        onVisible = onVisible,
        onInvisible = onInvisible,
        onFocusedVisible = onFocusedVisible,
        onUnfocusedVisible = onUnfocusedVisible,
        onFullImpression = onFullImpression,
        onVisibilityChange = onVisibilityChange,
    )
  }

  private fun createTestOutput(
      node: LithoNode,
      bounds: Rect,
      renderUnit: LithoRenderUnit? = null,
  ): TestOutput {
    val output = TestOutput()
    output.testKey = checkNotNull(node.testKey)
    output.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    if (renderUnit != null) {
      output.layoutOutputId = renderUnit.id
    }
    return output
  }

  /**
   * Collects layout outputs and release the layout tree. The layout outputs hold necessary
   * information to be used by [MountState] to mount components into a [ComponentHost].
   *
   * Whenever a component has view content (view tags, click handler, etc), a new host 'marker' is
   * added for it. The mount pass will use the markers to decide which host should be used for each
   * layout output. The root node unconditionally generates a layout output corresponding to the
   * root host.
   *
   * The order of layout outputs follows a depth-first traversal in the tree to ensure the hosts
   * will be created at the right order when mounting. The host markers will be define which host
   * each mounted artifacts will be attached to.
   *
   * At this stage all the [LithoNode] for which we have LayoutOutputs that can be recycled will
   * have a DiffNode associated. If the CachedMeasures are valid we'll try to recycle both the host
   * and the contents (including background/foreground). In all other cases instead we'll only try
   * to re-use the hosts. In some cases the host's structure might change between two updates even
   * if the component is of the same type. This can happen for example when a click listener is
   * added. To avoid trying to re-use the wrong host type we explicitly check that after all the
   * children for a subtree have been added (this is when the actual host type is resolved). If the
   * host type changed compared to the one in the DiffNode we need to refresh the ids for the whole
   * subtree in order to ensure that the MountState will unmount the subtree and mount it again on
   * the correct host.
   *
   * ## Pipeline shape (per-LithoNode invocation)
   *
   * The function processes one LithoNode at a time. For each node, it executes this pipeline:
   * ```
   * 1. Look up the cached `SubtreeSnapshot` for this node (`cachedSubtreeSnapshot`)
   * 2. Setup: tail/context, diffNode, hostRenderUnit, transition state save
   * 3. Accumulate layout-result state reads
   * 4. Steps 1-2 of baseline output emission: host -> content RTNs
   *    (host insertion mutates `parentRenderTreeNode` so descendants attach to it)
   * 5. Stamp diff-node metadata + accumulate effects/attachables
   * 6. SNAPSHOT descendant START boundaries (mountable, vis, test, scope, attach)
   * 7. Either splice cached descendants OR recurse via `collectChildResults`
   * 8. SNAPSHOT descendant END boundaries
   * 9. Steps 3-5 of baseline output emission via `addPostChildrenOutputs`:
   *    border -> visibility -> test outputs
   * 10. Append root's own Spec scopes/handles
   * 11. If eligible, record this subtree's descendant-only range to `reductionState.subtreeSnapshots`
   * 12. Restore transition state (current id + affinity group) for the caller
   * ```
   *
   * The descendant snapshots in (6)/(8) bracket the splice/recurse block — they capture exactly
   * what got added by descendants (NOT root's pre-children or post-children contributions). This
   * lets [SubtreeSnapshot] store descendant-only ranges that the next pass can splice verbatim
   * without "strip root contributions from front vs end" arithmetic. See [SubtreeSnapshot] for the
   * data layout, and [spliceFromSnapshot] for how the splice consumes these ranges.
   *
   * @param parentContext the parent component context
   * @param result InternalNode to process.
   * @param reductionState the ReductionState currently operating.
   * @param parent the parent render tree node.
   * @param parentDiffNode whether this method also populates the diff tree and assigns the root
   * @param parentHierarchy The parent hierarchy linked list or null.
   */
  private fun collectResults(
      parentContext: ComponentContext,
      result: LayoutResult,
      reductionState: ReductionState,
      lithoLayoutContext: LithoLayoutContext,
      x: Int,
      y: Int,
      parent: RenderTreeNode? = null,
      parentDiffNode: DiffNode? = null,
      parentHierarchy: DebugHierarchy.Node? = null,
  ) {
    if (
        lithoLayoutContext.isFutureReleased ||
            result !is LithoLayoutResult ||
            result.measureHadExceptions
    ) {
      // Exit early if the layout future as been released or if this result had exceptions.
      return
    }

    val node: LithoNode = result.node
    val hierarchy: DebugHierarchy.Node? = node.getDebugHierarchy(parentHierarchy)

    if (result is DeferredLithoLayoutResult) {
      // Mark every walk through a deferred so each enclosing subtree-root can detect whether
      // its recording window crossed a deferred boundary. See [ReductionState] field doc and
      // [shouldRecordSubtreeSnapshot] for the splice-correctness contract this enforces.
      reductionState.deferredEnterCount += 1

      val size: Int = node.componentCount
      val immediateParentContext: ComponentContext =
          if (size == 1) {
            parentContext
          } else {
            node.getComponentContextAt(1)
          }

      val actualResult: LithoLayoutResult = result.result ?: return

      if (node.componentCount > 1) {
        for (i in 1 until node.componentCount) {
          val scope: ScopedComponentInfo = node.getComponentInfoAt(i)
          if (scope.component is SpecGeneratedComponent) {
            reductionState.componentScopes.add(scope)
          }
        }
      }

      try {
        collectResults(
            parentContext = immediateParentContext,
            result = actualResult,
            reductionState = reductionState,
            lithoLayoutContext = lithoLayoutContext,
            x = x + result.getXForChildAtIndex(0), // Account for position of the holder node.
            y = y + result.getYForChildAtIndex(0), // Account for position of the holder node.
            parent = parent,
            parentDiffNode = parentDiffNode,
            parentHierarchy = hierarchy,
        )
      } catch (e: Exception) {
        throw ComponentUtils.wrapWithMetadata(immediateParentContext, e)
      }
      return
    }

    // Splice eligibility: caching on, prev preserved this LithoNode (REUSE), descendants have
    // an external anchor, and the node doesn't emit a host (host identity is late-bound and may
    // differ across passes). On a hit, also require per-direct-child sizes to match snapshot —
    // a resized direct child may re-layout non-uniformly inside (its own justify/align rules
    // plus flex-grow cascades), beyond what the per-child position offset can correct.
    val cachedSubtreeSnapshot: SubtreeSnapshot? =
        if (
            ComponentsConfiguration.enableSubtreeCaching &&
                result.isCachedLayout &&
                parent != null &&
                result.hostRenderUnit == null &&
                // Not at or below a `Layout`, nor holding one: a splice replays only Spec
                // components' state reads, and a `Layout`'s children carry their own.
                reductionState.customLayoutDepth == 0 &&
                !result.subtreeHasCustomLayout
        ) {
          // Counter semantics: increment ONLY when a recorded snapshot exists but the gate
          // rejects it (true Tier 1 rejection). Nodes never recorded as subtree roots are
          // ineligible from the start and are not counted as misses.
          val snapshot = reductionState.currentLayoutState?.subtreeSnapshots?.get(node)
          if (snapshot != null && !directChildSizesMatch(snapshot, result)) {
            LithoStats.count(LithoStats.subtreeCacheGateRejectCount)
            null
          } else if (snapshot != null && !externalParentMatches(snapshot, parent)) {
            // The splice copies prev's parent-relative descendant bounds and feeds them
            // into a fresh RTN whose `absoluteX = current_parent.absoluteX + bounds.left`.
            // That arithmetic is correct only when the external parent's absolute position
            // is unchanged across passes; a mismatch silently corrupts RTN.absoluteX/Y,
            // which propagates to IMO bounds (built via `getAbsoluteBounds()`), then to
            // incremental-mount diff decisions, and ultimately to mount-state desync
            // (e.g. ComponentHost.viewMountItems out of sync with actual View children).
            LithoStats.count(LithoStats.subtreeCacheGateRejectCount)
            null
          } else if (snapshot != null && snapshot.containsDeferredDescendant) {
            // Symmetric defense to the recording-time gate in [shouldRecordSubtreeSnapshot]: a
            // snapshot whose recording window crossed a deferred descendant cannot be safely
            // spliced because [DeferredLithoLayoutResult.actualResult] is computed at measure
            // time and may differ across passes. In steady state new snapshots never carry this
            // flag (recording rejects them), but stale snapshots from a carry-forward path or
            // from a future code path that bypasses the recording gate must still be rejected
            // here so the gate is load-bearing rather than purely advisory.
            LithoStats.count(LithoStats.subtreeCacheGateRejectCount)
            LithoStats.count(LithoStats.subtreeCacheDeferredSkippedCount)
            null
          } else {
            snapshot
          }
        } else null
    if (ComponentsConfiguration.enableSubtreeCaching) {
      LithoStats.count(LithoStats.subtreeCacheOutputsTraversedCount)
    }

    val tail: ScopedComponentInfo = node.tailScopedComponentInfo
    val context: ComponentContext = tail.context
    maybeReportSubMinimumTouchTarget(node, result, context)
    val diffNode = createDiffNode(tail, parentDiffNode)
    if (parentDiffNode == null) {
      reductionState.diffTreeRoot = diffNode
    }

    // Captured BEFORE the root's own scopes are added so [collectSubtreeStateReads] can filter
    // [ReductionState.stateReads] down to just this subtree's globalKeys (root + descendants).
    // The descendant-only `*Start`/`*End` snapshots below bracket only the splice block.
    val subtreeScopeStartIndex = reductionState.componentScopes.size

    // Captured here, compared to the post-recurse value below to detect whether ANY descendant
    // walked through a deferred branch. If it grew, this subtree's snapshot would include outputs
    // produced by a deferred `actualResult` that may differ on the next pass — replaying them via
    // splice would diverge from baseline. [shouldRecordSubtreeSnapshot] uses the comparison to
    // skip recording in that case.
    val deferredEnterStartCount = reductionState.deferredEnterCount

    // Mutable: when this node emits a host RTN below (step 1), descendants attach to that host
    // instead of to our caller's `parent`. Reassigned at most once, after host insertion.
    var parentRenderTreeNode: RenderTreeNode? = parent
    val hostRenderUnit: LithoRenderUnit? =
        if (parentRenderTreeNode == null /* isRoot */) {
          LithoNodeUtils.createRootHostRenderUnit(result.node, reductionState.id)
        } else {
          result.hostRenderUnit
        }
    val needsHostView: Boolean = (hostRenderUnit != null)

    // Transition state save/restore. The current TransitionId + affinity group are tree-walking
    // state — this node's RTNs need its own transitionId, but our parent's state must be
    // preserved for siblings processed AFTER us. Captured here, written back at the end of the
    // function. This is the recursive analogue of a thread-local stack push/pop.
    val parentTransitionId: TransitionId? = reductionState.currentTransitionId
    val parentLayoutOutputAffinityGroup: OutputUnitsAffinityGroup<AnimatableItem>? =
        reductionState.currentLayoutOutputAffinityGroup
    val styleTransitionData = node.styleTransitionData
    reductionState.currentTransitionId = node.transitionId
    reductionState.currentLayoutOutputAffinityGroup =
        if (reductionState.currentTransitionId != null || styleTransitionData != null)
            OutputUnitsAffinityGroup()
        else null

    // Track which (state, owner-key) pairs this layout result reads. State updates use these
    // pairs to invalidate exactly the affected subtrees on the next pass.
    val ownerGlobalKey = context.nullableGlobalKey
    val stateReads = result.stateReads
    if (ownerGlobalKey != null && stateReads != null) {
      val accumulatedStateReads = reductionState.getOrCreateStateReads()
      stateReads.forEach { stateIdentifier ->
        accumulatedStateReads
            .getOrPut(stateIdentifier) { mutableScatterSetOf() }
            .add(ownerGlobalKey)
      }
    }

    val bounds = Rect(x, y, x + result.width, y + result.height)
    val padding =
        if (
            result.paddingLeft != 0 ||
                result.paddingTop != 0 ||
                result.paddingRight != 0 ||
                result.paddingBottom != 0
        ) {
          Rect(result.paddingLeft, result.paddingTop, result.paddingRight, result.paddingBottom)
        } else {
          null
        }

    // 1. Insert a host LayoutOutput if we have some interactive content to be attached to.
    if (hostRenderUnit != null) {
      val hostLayoutPosition = addHostRenderTreeNode(
          hostRenderUnit = hostRenderUnit,
          bounds = bounds,
          padding = if (!node.willMountView) padding else null,
          parent = parentRenderTreeNode,
          result = result,
          node = node,
          reductionState = reductionState,
          diffNode = diffNode,
          hierarchy = hierarchy,
      )
      styleTransitionData?.let {
        addStyleTransitionTarget(
            reductionState,
            createStyleTransitionTarget(
                node = node,
                data = it,
                reductionState = reductionState,
                hostLayoutPosition = hostLayoutPosition,
            ),
        )
      }
      addCurrentAffinityGroupToTransitionMapping(reductionState)

      parentRenderTreeNode = reductionState.mountableOutputs[hostLayoutPosition]
    }

    // Generate the RenderTreeNode for the given node.
    val contentRenderTreeNode: RenderTreeNode? = createContentRenderTreeNode(
        result = result,
        node = node,
        bounds = bounds,
        padding = if (node.willMountView) padding else null,
        reductionState = reductionState,
        expandedTouchBounds = result.expandedTouchBounds,
        parent = parentRenderTreeNode,
        debugHierarchyNode = hierarchy,
    )

    // 2. Now add the MountSpec (either View or Drawable) to the outputs.
    contentRenderTreeNode?.let { treeNode ->
      val contentRenderUnit: LithoRenderUnit = treeNode.renderUnit as LithoRenderUnit

      addRenderTreeNode(
          reductionState = reductionState,
          node = treeNode,
          result = result,
          unit = contentRenderUnit,
          type = OutputUnitType.CONTENT,
          transitionId = if (!needsHostView) reductionState.currentTransitionId else null,
          parent = parentRenderTreeNode,
      )

      diffNode.contentOutput = contentRenderUnit
    }

    stampDiffNodeMetadata(diffNode, result)
    accumulateLayoutResultAttachables(reductionState, result.effects)

    // Descendant range — START. Captures buffer sizes immediately before the splice/recurse
    // block. Together with the END snapshot (after the block), this delimits exactly what
    // descendants contributed to each buffer. Everything outside this range belongs to root
    // (pre-children: host/bg/content/attachables; post-children: border/fg/vis/test/scopes).
    val descendantMountableOutputStart = reductionState.mountableOutputs.size
    val descendantVisibilityOutputStart = reductionState.visibilityOutputs.size
    val descendantTestOutputStart = reductionState.testOutputs?.size ?: 0
    val descendantComponentScopeStart = reductionState.componentScopes.size
    val descendantAttachableStart = reductionState.attachables?.size ?: 0

    // Per-direct-child range starts in the CURRENT pass's mountableOutputs. The next pass's
    // splice uses these to slice the snapshot into per-child ranges so it can apply per-child
    // position offsets independently. Either populated by `collectChildResults` during recursion
    // (baseline) or derived from the snapshot's prev-pass starts via a constant offset (splice).
    val perChildRangeStarts: IntArray? =
        if (ComponentsConfiguration.enableSubtreeCaching && parent != null && result.childCount > 0)
            IntArray(result.childCount)
        else null

    if (cachedSubtreeSnapshot != null && parentRenderTreeNode != null) {
      // Invariant: spliced subtrees are deferred-free by construction — both the recording-time
      // gate in [shouldRecordSubtreeSnapshot] and the splice-time gate above reject snapshots
      // with [SubtreeSnapshot.containsDeferredDescendant], so this block never enters the
      // [DeferredLithoLayoutResult] branch of [collectResults] and [deferredEnterCount] does
      // not need to be incremented for spliced descendants. Removing either gate without
      // restoring the increment here would silently break the contract.
      perChildRangeStarts?.let {
        translateChildRangeStartsForSplice(
            into = it,
            snapshot = cachedSubtreeSnapshot,
            currentDescendantStart = descendantMountableOutputStart,
        )
      }
      spliceFromSnapshot(
          cachedSubtreeSnapshot,
          reductionState,
          x,
          y,
          parentRenderTreeNode,
          diffNode,
          result,
      )
      LithoStats.count(LithoStats.subtreeCacheHitCount)
    } else {
      collectChildResults(
          result = result,
          parentContext = context,
          parentRenderTreeNode = parentRenderTreeNode,
          parentDiffNode = diffNode,
          parentX = x,
          parentY = y,
          reductionState = reductionState,
          lithoLayoutContext = lithoLayoutContext,
          hierarchy = hierarchy,
          perChildRangeStarts = perChildRangeStarts,
      )
    }

    // Descendant range — END. Closes the range opened above. The cache write block stores
    // [start, end) into [SubtreeSnapshot] so the next pass's splice copies these positions
    // verbatim.
    val descendantMountableOutputEnd = reductionState.mountableOutputs.size
    val descendantVisibilityOutputEnd = reductionState.visibilityOutputs.size
    val descendantTestOutputEnd = reductionState.testOutputs?.size ?: 0
    val descendantComponentScopeEnd = reductionState.componentScopes.size
    val descendantAttachableEnd = reductionState.attachables?.size ?: 0

    addPostChildrenOutputs(
        result = result,
        node = node,
        bounds = bounds,
        parentRenderTreeNode = parentRenderTreeNode,
        contentRenderTreeNode = contentRenderTreeNode,
        needsHostView = needsHostView,
        diffNode = diffNode,
        reductionState = reductionState,
        hierarchy = hierarchy,
    )
    addRootSpecScopesAndHandles(node, reductionState)

    val accumulatedSubtreeScopeCount = reductionState.componentScopes.size - subtreeScopeStartIndex
    val containsDeferredDescendant = reductionState.deferredEnterCount > deferredEnterStartCount
    if (containsDeferredDescendant && ComponentsConfiguration.enableSubtreeCaching) {
      // Tick before delegating to shouldRecordSubtreeSnapshot so the counter reflects the
      // deferred-driven skip even when the subtree would also have been rejected for another
      // reason (host view, threshold). Pairs with the splice-time tick in the gate above.
      LithoStats.count(LithoStats.subtreeCacheDeferredSkippedCount)
    }
    if (
        shouldRecordSubtreeSnapshot(
            isSubtreeCacheHit = cachedSubtreeSnapshot != null,
            isCachedLayoutResult = result.isCachedLayout,
            hasParentRenderTreeNode = parent != null,
            accumulatedSubtreeScopeCount = accumulatedSubtreeScopeCount,
            needsHostView = needsHostView,
            containsDeferredDescendant = containsDeferredDescendant,
        )
    ) {
      val cache =
          reductionState.subtreeSnapshots
              ?: MutableScatterMap<LithoNode, SubtreeSnapshot>().also {
                reductionState.subtreeSnapshots = it
              }
      // Reuse prev's pre-filtered map on a cache-hit (the dominant per-hit cost for trees with
      // many state-reading components is the scope-walk + filter); compute fresh on a miss.
      val stateReads =
          cachedSubtreeSnapshot?.stateReads
              ?: collectSubtreeStateReads(reductionState, subtreeScopeStartIndex)
      // perChildRangeStarts non-null implies result.childCount > 0 (allocated together upstream).
      perChildRangeStarts?.let { childRangeStart ->
        val childCount = result.childCount
        val childPrevX = IntArray(childCount)
        val childPrevY = IntArray(childCount)
        val childPrevWidth = IntArray(childCount)
        val childPrevHeight = IntArray(childCount)
        for (i in 0 until childCount) {
          val child = result.getChildAt(i)
          childPrevX[i] = result.getXForChildAtIndex(i)
          childPrevY[i] = result.getYForChildAtIndex(i)
          childPrevWidth[i] = child.width
          childPrevHeight[i] = child.height
        }
        cache[node] =
            SubtreeSnapshot(
                mountableOutputStart = descendantMountableOutputStart,
                mountableOutputEnd = descendantMountableOutputEnd,
                visibilityOutputStart = descendantVisibilityOutputStart,
                visibilityOutputEnd = descendantVisibilityOutputEnd,
                testOutputStart = descendantTestOutputStart,
                testOutputEnd = descendantTestOutputEnd,
                componentScopeStart = descendantComponentScopeStart,
                componentScopeEnd = descendantComponentScopeEnd,
                attachableStart = descendantAttachableStart,
                attachableEnd = descendantAttachableEnd,
                rootX = x,
                rootY = y,
                childCount = childCount,
                childPrevX = childPrevX,
                childPrevY = childPrevY,
                childPrevWidth = childPrevWidth,
                childPrevHeight = childPrevHeight,
                childRangeStart = childRangeStart,
                diffNode = diffNode,
                stateReads = stateReads,
                externalParentAbsoluteX = parent?.absoluteX ?: 0,
                externalParentAbsoluteY = parent?.absoluteY ?: 0,
                containsDeferredDescendant = containsDeferredDescendant,
            )
      }
    }

    // Flush this node's collected outputs into the transition mapping, then restore the parent's
    // transition state (saved at function entry) so siblings see the correct context.
    addCurrentAffinityGroupToTransitionMapping(reductionState)
    reductionState.currentTransitionId = parentTransitionId
    reductionState.currentLayoutOutputAffinityGroup = parentLayoutOutputAffinityGroup
  }

  /**
   * Reports an interactive component (one with a click/long-click/touch/intercept-touch handler)
   * whose laid-out size is below [ComponentsConfiguration.minTouchTargetSizeDp], so the opportunity
   * for [ComponentsConfiguration.useAutoTouchExpansion] can be measured.
   *
   * Modeled as a logic-violation policy: [ComponentsConfiguration.LogicViolationPolicy.NONE]
   * (default, a cheap static read) is a no-op so the per-node cost is zero when off; any other
   * policy logs via [DebugInfoReporter]. A sub-minimum touch target is never fatal, so we never
   * crash.
   */
  private fun maybeReportSubMinimumTouchTarget(
      node: LithoNode,
      result: LithoLayoutResult,
      context: ComponentContext,
  ) {
    if (
        ComponentsConfiguration.minTouchTargetViolationPolicy ==
            ComponentsConfiguration.LogicViolationPolicy.NONE
    ) {
      return
    }
    if (node.nodeInfo?.hasTouchEventHandlers() != true) {
      return
    }
    val minPx =
        context.resourceResolver.dipsToPixels(
            ComponentsConfiguration.minTouchTargetSizeDp.toFloat(),
        )
    val width = result.width
    val height = result.height
    if (width >= minPx && height >= minPx) {
      return
    }
    DebugInfoReporter.report(
        category = "TouchTargetTooSmall",
        renderStateId = context.lithoTree?.id ?: RenderState.NO_ID,
    ) {
      this["component"] = node.tailComponent.simpleName
      this["width"] = width
      this["height"] = height
      this["minTouchTargetSizeDp"] = ComponentsConfiguration.minTouchTargetSizeDp
    }
  }

  /**
   * Recurse into each child layout result, accumulating descendant outputs into [reductionState].
   * Bounds are translated by the parent's offset before recursing. Errors are wrapped with the
   * parent's component context for better diagnostics.
   */
  private fun collectChildResults(
      result: LithoLayoutResult,
      parentContext: ComponentContext,
      parentRenderTreeNode: RenderTreeNode?,
      parentDiffNode: DiffNode?,
      parentX: Int,
      parentY: Int,
      reductionState: ReductionState,
      lithoLayoutContext: LithoLayoutContext,
      hierarchy: DebugHierarchy.Node?,
      // When non-null, populated with `mountableOutputs.size` immediately before each direct
      // child's `collectResults` invocation. Used by subtree-cache recording to capture per-child
      // range boundaries so the next pass's splice can apply per-child position offsets.
      perChildRangeStarts: IntArray? = null,
  ) {
    val isCustomLayout = result.node.layoutFunction != null
    if (isCustomLayout) {
      reductionState.customLayoutDepth++
    }
    try {
      for (childIndex in 0 until result.childCount) {
        perChildRangeStarts?.set(childIndex, reductionState.mountableOutputs.size)
        val child = result.getChildAt(childIndex)
        try {
          collectResults(
              parentContext = parentContext,
              result = child,
              reductionState = reductionState,
              lithoLayoutContext = lithoLayoutContext,
              x = parentX + result.getXForChildAtIndex(childIndex),
              y = parentY + result.getYForChildAtIndex(childIndex),
              parent = parentRenderTreeNode,
              parentDiffNode = parentDiffNode,
              parentHierarchy = hierarchy,
          )
        } catch (exception: Exception) {
          throw ComponentUtils.wrapWithMetadata(parentContext, exception)
        }
      }
    } finally {
      if (isCustomLayout) {
        reductionState.customLayoutDepth--
      }
    }
  }

  /**
   * Copy this layout pass's measurement and layout-data outputs onto the [DiffNode] for the current
   * LithoNode. Used by next-pass shouldUpdate decisions and for debug introspection.
   */
  private fun stampDiffNodeMetadata(diffNode: DiffNode, result: LithoLayoutResult) {
    diffNode.lastWidthSpec = result.widthSpec
    diffNode.lastHeightSpec = result.heightSpec
    diffNode.layoutDirection = result.node.layoutDirection
    diffNode.lastMeasuredWidth = result.contentWidth
    diffNode.lastMeasuredHeight = result.contentHeight
    diffNode.layoutData = result.layoutData
    diffNode.effects = result.effects
    diffNode.stateReads = result.stateReads
    diffNode.primitive = result.node.primitive
    diffNode.delegate = result.delegate
  }

  /**
   * Append this layout result's [Attachable] effects (e.g., `useEffect`) into the global
   * accumulator on [ReductionState]. The list is lazily allocated on first use.
   */
  private fun accumulateLayoutResultAttachables(
      reductionState: ReductionState,
      attachables: List<Attachable>?,
  ) {
    if (attachables.isNullOrEmpty()) return
    val accumulator =
        reductionState.attachables
            ?: ArrayList<Attachable>(attachables.size).also { reductionState.attachables = it }
    accumulator.addAll(attachables)
  }

  /**
   * Emit the subtree root's post-children outputs: border, visibility, and test outputs. Called
   * after the splice/recursion block has populated descendant outputs.
   *
   * Each phase is independently optional and runs against the same [bounds]/[parentRenderTreeNode]
   * shared with steps 1-2 (host/content) emitted before the splice block.
   */
  private fun addPostChildrenOutputs(
      result: LithoLayoutResult,
      node: LithoNode,
      bounds: Rect,
      parentRenderTreeNode: RenderTreeNode?,
      contentRenderTreeNode: RenderTreeNode?,
      needsHostView: Boolean,
      diffNode: DiffNode,
      reductionState: ReductionState,
      hierarchy: DebugHierarchy.Node?,
  ) {
    // 3. Border.
    result.borderRenderUnit?.let { borderRenderUnit ->
      val borderRenderTreeNode = addDrawableRenderTreeNode(
          unit = borderRenderUnit,
          bounds = bounds,
          parent = parentRenderTreeNode,
          result = result,
          reductionState = reductionState,
          expandedTouchBounds = result.expandedTouchBounds,
          hierarchy = hierarchy,
          type = OutputUnitType.BORDER,
          matchHostBoundsTransitions = needsHostView,
      )
      diffNode.borderOutput = borderRenderTreeNode.renderUnit as LithoRenderUnit
    }

    // 4. VisibilityOutput when this node has visibility-event handlers.
    if (node.hasVisibilityHandlers()) {
      val visibilityOutput = createVisibilityOutput(
          node = node,
          bounds = bounds,
          renderTreeNode =
              contentRenderTreeNode ?: if (needsHostView) parentRenderTreeNode else null,
      )
      reductionState.visibilityOutputs.add(visibilityOutput)
      diffNode.visibilityOutput = visibilityOutput
    }

    // 5. TestOutput when running under an end-to-end testing environment AND a testKey is set.
    if (reductionState.testOutputs != null && !node.testKey.isNullOrEmpty()) {
      val testOutput = createTestOutput(
          node = node,
          bounds = bounds,
          renderUnit = contentRenderTreeNode?.renderUnit as? LithoRenderUnit,
      )
      reductionState.testOutputs.add(testOutput)
    }
  }

  /**
   * Append the subtree root's own [SpecGeneratedComponent] scopes (and any component handles) to
   * the reduction state. This gives the next pass's [TreeState.updateEventDispatchers] enough info
   * to rebind event triggers, and lets [collectSubtreeStateReads] derive the subtree's globalKey
   * set from the [ReductionState.componentScopes] slice.
   */
  private fun addRootSpecScopesAndHandles(node: LithoNode, reductionState: ReductionState) {
    for (componentIndex in 0 until node.componentCount) {
      val component = node.getComponentAt(componentIndex)
      if (component is SpecGeneratedComponent) {
        reductionState.componentScopes.add(
            node.getComponentContextAt(componentIndex).scopedComponentInfo,
        )
      }
      if (component.hasHandle()) {
        component.handle?.let { handle -> reductionState.getOrCreateComponentHandles().add(handle) }
      }
    }
  }

  /**
   * Decide whether the current subtree should have its [SubtreeSnapshot] (re)recorded in the cache.
   *
   * - **Cache hits** are always re-recorded so the next pass can splice from this LayoutState's
   *   buffer positions (which differ from prev's). They bypass the size threshold because they
   *   already passed it when first cached.
   * - **Fresh subtrees** are recorded only when they meet the [MIN_SUBTREE_SCOPES] threshold —
   *   below that, splice overhead exceeds resolve savings (e.g. wide-shallow Text leaves with ~2
   *   scopes never pay for themselves).
   * - The root LithoNode (no [hasParentRenderTreeNode]) is never cached because the splice path
   *   needs an external parent to anchor descendants.
   * - Nodes that emit a host view ([needsHostView]) are never cached because their descendants' RTN
   *   bounds are relative to the node's own host — the splice path cannot produce correct bounds
   *   when the external parent differs from the recording pass's internal host.
   * - Subtrees containing a deferred descendant ([containsDeferredDescendant]) are never cached.
   *   The recording-pass snapshot bakes in a deferred subtree whose shape is not guaranteed to
   *   match the next pass's measure-time `actualResult` — `copyLayoutResult` nulls
   *   `actualDeferredNodeResult` on Yoga cache hit so `measureDeferredNode` re-resolves, and that
   *   re-resolution can produce a different mountable-output shape. The splice-time gate
   *   ([containsDeferredDescendant] check on the snapshot itself) is the load-bearing rejection;
   *   this recording-time skip avoids storing snapshots that could never be safely spliced.
   */
  private fun shouldRecordSubtreeSnapshot(
      isSubtreeCacheHit: Boolean,
      isCachedLayoutResult: Boolean,
      hasParentRenderTreeNode: Boolean,
      accumulatedSubtreeScopeCount: Int,
      needsHostView: Boolean,
      containsDeferredDescendant: Boolean,
  ): Boolean {
    if (!ComponentsConfiguration.enableSubtreeCaching) return false
    if (!hasParentRenderTreeNode) return false
    if (needsHostView) return false
    if (containsDeferredDescendant) return false
    if (isSubtreeCacheHit) return true
    return !isCachedLayoutResult && accumulatedSubtreeScopeCount >= MIN_SUBTREE_SCOPES
  }

  /**
   * Pre-filter the current pass's accumulated [ReductionState.stateReads] down to just the keys
   * that belong to this subtree's component scopes. Storing this map on [SubtreeSnapshot] lets the
   * next pass's splice replay subtree-local reads without scanning every state update against every
   * component key. Returns null if no reads belong to this subtree.
   *
   * Walks the FULL subtree's scope range (`[subtreeScopeStartIndex, currentSize)`) — both the
   * cached subtree root's own scopes and its descendants' — because either can read state.
   *
   * Note: only Spec component scopes appear in [ReductionState.componentScopes] (via
   * [addRootSpecScopesAndHandles]), so this filter captures Spec-keyed layout-phase reads only.
   * KComponent useState reads flow through the resolve-phase tracker and don't appear here.
   */
  @androidx.annotation.VisibleForTesting
  internal fun collectSubtreeStateReads(
      reductionState: ReductionState,
      subtreeScopeStartIndex: Int,
  ): MutableMap<StateId, MutableScatterSet<GlobalKey>>? {
    val scopeEnd = reductionState.componentScopes.size
    if (scopeEnd <= subtreeScopeStartIndex) return null
    val currentReads = reductionState.stateReads ?: return null
    if (currentReads.isEmpty()) return null

    val subtreeKeys = MutableScatterSet<GlobalKey>(scopeEnd - subtreeScopeStartIndex)
    for (scopeIndex in subtreeScopeStartIndex until scopeEnd) {
      val globalKey = reductionState.componentScopes[scopeIndex].context.nullableGlobalKey
      if (globalKey != null) {
        subtreeKeys.add(globalKey)
      }
    }
    var subtreeStateReads: MutableMap<StateId, MutableScatterSet<GlobalKey>>? = null
    currentReads.forEach { (stateId, keys) ->
      keys.forEach { globalKey ->
        if (globalKey in subtreeKeys) {
          val targetMap =
              subtreeStateReads
                  ?: mutableMapOf<StateId, MutableScatterSet<GlobalKey>>().also { newMap ->
                    subtreeStateReads = newMap
                  }
          targetMap.getOrPut(stateId) { mutableScatterSetOf() }.add(globalKey)
        }
      }
    }
    return subtreeStateReads
  }

  /**
   * Copy the cached subtree's descendant outputs (RTNs, IMOs, AnimatableItems, scopes,
   * visibility/test outputs, attachables, DiffNode children, state-read replay) into the current
   * [reductionState]. The current pass's baseline is responsible for the subtree root's own
   * contributions (host/bg/content before this call; border/fg/vis/test/scopes after); the
   * snapshot's flat ranges strictly cover descendant positions in [prev]'s arrays.
   *
   * Per-direct-child offsets: each direct child of the subtree root may shift independently between
   * passes (justify-end / center / wrap reflow). The splice gate has already verified that no
   * direct child resized (`directChildSizesMatch`), so each child's interior layout is unchanged —
   * only its position relative to the subtree root may have shifted. The splice walks each child's
   * range with that child's specific offset, applied to descendants whose RTN parent is the subtree
   * root's external parent. Deeper descendants use shared local bounds (relative to a spliced
   * sibling, which already has its offset applied).
   */
  private fun spliceFromSnapshot(
      snapshot: SubtreeSnapshot,
      reductionState: ReductionState,
      x: Int,
      y: Int,
      parent: RenderTreeNode,
      parentDiffNode: DiffNode?,
      result: LithoLayoutResult,
  ) {
    val prev = checkNotNull(reductionState.currentLayoutState)
    val rootDx = x - snapshot.rootX
    val rootDy = y - snapshot.rootY
    val count = snapshot.mountableOutputEnd - snapshot.mountableOutputStart
    val basePosition = reductionState.mountableOutputs.size
    val prevOutputParentIndices = checkNotNull(prev.outputParentIndices)

    val freshRenderTreeNodes = arrayOfNulls<RenderTreeNode>(count)

    // Per-child offset = root absolute shift + child's local shift within the subtree root. The
    // root shift covers cases where the subtree root itself moved (sibling-driven Y shift); the
    // local shift covers cases where children re-flowed inside an unchanged-position root
    // (justify-end / wrap reflow when the root resized). Descendants attached to the EXTERNAL
    // parent get this offset applied; deeper descendants inherit transitively via their parent.
    for (childIdx in 0 until snapshot.childCount) {
      val childRangeStart = snapshot.childRangeStart[childIdx]
      val childRangeEnd =
          if (childIdx + 1 < snapshot.childCount) snapshot.childRangeStart[childIdx + 1]
          else snapshot.mountableOutputEnd
      val childOffsetX =
          rootDx + result.getXForChildAtIndex(childIdx) - snapshot.childPrevX[childIdx]
      val childOffsetY =
          rootDy + result.getYForChildAtIndex(childIdx) - snapshot.childPrevY[childIdx]
      val childPositionStable = childOffsetX == 0 && childOffsetY == 0
      for (i in childRangeStart until childRangeEnd) {
        spliceOneOutput(
            indexInRange = i - snapshot.mountableOutputStart,
            snapshot = snapshot,
            previousLayoutState = prev,
            reductionState = reductionState,
            externalParentRenderTreeNode = parent,
            freshRenderTreeNodes = freshRenderTreeNodes,
            previousOutputParentIndices = prevOutputParentIndices,
            basePosition = basePosition,
            offsetX = childOffsetX,
            offsetY = childOffsetY,
            positionStable = childPositionStable,
        )
      }
    }

    // Scopes and handles
    for (i in snapshot.componentScopeStart until snapshot.componentScopeEnd) {
      val scope = prev.componentScopesInternal[i]
      reductionState.componentScopes.add(scope)
      if (scope.component.hasHandle()) {
        scope.component.handle?.let { reductionState.getOrCreateComponentHandles().add(it) }
      }
    }

    // DiffNode — graft prev root's CHILDREN onto current root. The current root's diffNode
    // was already created by baseline (createDiffNode). We add the prev root's children
    // (not the prev root itself) to avoid inserting an extra tree level.
    if (parentDiffNode != null) {
      val prevDiffNode = snapshot.diffNode
      for (i in 0 until prevDiffNode.childCount) {
        prevDiffNode.getChildAt(i)?.let { parentDiffNode.addChild(it) }
      }
    }

    // StateReads — replay the cached subtree's layout-phase state-read attributions into the
    // current pass's accumulator so the resulting LayoutState carries the full read graph for
    // the next pass's [LayoutTreeFuture.willSkipLayout] check. Layout-phase reads come from
    // [LithoLayoutResult.stateReads] (Spec MountSpec / Primitive measure-time reads); KComponent
    // useState reads flow through the resolve-phase tracker independently, so this map only
    // contains the layout-phase attributions and is typically empty in modern KComponent code.
    snapshot.stateReads?.forEach { (stateId, keys) ->
      val target =
          reductionState.getOrCreateStateReads().getOrPut(stateId) { mutableScatterSetOf() }
      keys.forEach { key -> target.add(key) }
    }

    // Attachables — replay descendant layout-phase effects (Primitive `LayoutScope.useEffect`).
    // Resolve-phase attachables (KComponent/LayoutSpec useEffect, @OnAttached) are pre-populated
    // into ReductionState before reduction starts and are unaffected by the splice. Only
    // layout-phase effects accumulated per-node via `accumulateLayoutResultAttachables` during the
    // previous pass need replaying here. Reuse by reference because EffectHandler diffs by
    // uniqueId, so identity stability is required.
    if (snapshot.attachableEnd > snapshot.attachableStart) {
      val prevAttachables = prev.attachables
      if (prevAttachables != null) {
        val accumulator =
            reductionState.attachables
                ?: ArrayList<Attachable>(snapshot.attachableEnd - snapshot.attachableStart).also {
                  reductionState.attachables = it
                }
        for (i in snapshot.attachableStart until snapshot.attachableEnd) {
          accumulator.add(prevAttachables[i])
        }
      }
    }

    // Transitions: intentionally not propagated
    // Every cached LithoNode still has an AUTOGENERATED TransitionId (computed from its
    // globalKey in LithoNodeUtils.createTransitionId), but those are framework-internal and
    // unreachable from user `Transition.create(<key>)` DSL — TransitionManager skips them on
    // appear/disappear (see TransitionManager.kt's AUTOGENERATED branches), so dropping them
    // here is safe.
    //
    // User-declared (GLOBAL/SCOPED) TransitionIds — the kind that actually drive animations —
    // cannot reach this code path: `Style.transitionKey(...)` forces `needsHostViewForTransition`,
    // which sets a non-null hostRenderUnit, which fails the splice gate at the top of
    // collectResults. So cached subtrees by construction contain only AUTOGENERATED ids.

    // Visibility / test outputs are offset using the SUBTREE ROOT's position delta only — not
    // per-direct-child. This is a known limitation: when direct children shift independently
    // (e.g. wrap reflow), per-child vis output bounds may be off by the per-child delta. For
    // rendering correctness this doesn't matter (RTN bounds drive draw); for visibility-event
    // accuracy it can. If we later see vis-event regressions we'll add per-child vis range
    // tracking analogous to `childRangeStart` for mountableOutputs.
    val rootPositionStable = rootDx == 0 && rootDy == 0
    if (rootPositionStable) {
      for (i in snapshot.visibilityOutputStart until snapshot.visibilityOutputEnd) {
        reductionState.visibilityOutputs.add(prev.visibilityOutputs[i])
      }
    } else {
      for (i in snapshot.visibilityOutputStart until snapshot.visibilityOutputEnd) {
        val prevVo = prev.visibilityOutputs[i]
        reductionState.visibilityOutputs.add(
            VisibilityOutput(
                id = prevVo.id,
                key = prevVo.key,
                bounds =
                    Rect(
                        prevVo.bounds.left + rootDx,
                        prevVo.bounds.top + rootDy,
                        prevVo.bounds.right + rootDx,
                        prevVo.bounds.bottom + rootDy,
                    ),
                hasMountableContent = prevVo.hasMountableContent,
                renderUnitId = prevVo.renderUnitId,
                visibleHeightRatio = prevVo.visibleHeightRatio,
                visibleWidthRatio = prevVo.visibleWidthRatio,
                tag = prevVo.tag,
                componentClassName = prevVo.componentClassName,
                onVisible = prevVo.onVisible,
                onInvisible = prevVo.onInvisible,
                onFocusedVisible = prevVo.onFocusedVisible,
                onUnfocusedVisible = prevVo.onUnfocusedVisible,
                onFullImpression = prevVo.onFullImpression,
                onVisibilityChange = prevVo.onVisibilityChange,
            ),
        )
      }
    }

    // Test outputs — reuse by reference when position unchanged; otherwise re-build with offset.
    if (snapshot.testOutputEnd > snapshot.testOutputStart) {
      val prevTests = prev.testOutputs
      val curTests = reductionState.testOutputs
      if (prevTests != null && curTests != null) {
        if (rootPositionStable) {
          for (i in snapshot.testOutputStart until snapshot.testOutputEnd) {
            curTests.add(prevTests[i])
          }
        } else {
          for (i in snapshot.testOutputStart until snapshot.testOutputEnd) {
            val prevTo = prevTests[i]
            val to = TestOutput()
            to.testKey = prevTo.testKey
            to.layoutOutputId = prevTo.layoutOutputId
            to.setBounds(
                prevTo.bounds.left + rootDx,
                prevTo.bounds.top + rootDy,
                prevTo.bounds.right + rootDx,
                prevTo.bounds.bottom + rootDy,
            )
            curTests.add(to)
          }
        }
      }
    }

    LithoStats.count(LithoStats.subtreeCacheOutputsSplicedCount, count.toLong())
  }

  /**
   * Splice gate: matching child sizes mean each child's interior layout is unchanged (Yoga's layout
   * cache held), so per-child position offsets are sufficient. A mismatch — typically `flex-grow`
   * cascading from a parent size change — falls back to baseline since the child's interior may
   * have re-laid-out non-uniformly.
   */
  private fun directChildSizesMatch(
      snapshot: SubtreeSnapshot,
      result: LithoLayoutResult,
  ): Boolean {
    if (snapshot.childCount != result.childCount) return false
    for (i in 0 until snapshot.childCount) {
      val child = result.getChildAt(i)
      if (
          child.width != snapshot.childPrevWidth[i] || child.height != snapshot.childPrevHeight[i]
      ) {
        return false
      }
    }
    return true
  }

  /**
   * Splice gate: the descendant bounds we copy are relative to the recording-pass external parent.
   * The splice arithmetic `new_absoluteX = current_parent.absoluteX + (prev_bounds.left + rootDx)`
   * is correct only when the external parent's absolute position is unchanged — `rootDx` already
   * absorbs the parent shift via the layout-result-tree accumulation in `x`, so adding it to
   * parent-relative `prev_bounds.left` would double-count the shift.
   *
   * Triggered in production when a sibling above a hosted wrapper grows: the wrapper itself is
   * REUSE'd by reconcile (props unchanged), but Yoga shifts its absolute position.
   *
   * Identity-change scenarios (parent gained/lost a host, RU id differs) cannot reach this gate
   * under current Litho semantics: they require the host-introducing LithoNode's props or scope to
   * change, which marks it dirty, which cascades RECREATE to all descendants — fresh LithoNodes
   * miss the snapshot map (keyed by LithoNode identity) before the gate even runs. So no RU id
   * check is needed today; if Litho ever permits ancestor `needsHostView()` flips without dirtiness
   * propagation (e.g. a global flag), the gate will need that check restored.
   */
  @androidx.annotation.VisibleForTesting
  internal fun externalParentMatches(
      snapshot: SubtreeSnapshot,
      parent: RenderTreeNode,
  ): Boolean =
      snapshot.externalParentAbsoluteX == parent.absoluteX &&
          snapshot.externalParentAbsoluteY == parent.absoluteY

  /**
   * Convert snapshot's prev-pass per-child range starts into current-pass coordinates by adding a
   * constant delta (the gap between this pass's and prev pass's mountableOutputs offsets). Used on
   * the splice path so the recording site can store the same per-child layout the baseline path
   * would have populated via [collectChildResults].
   */
  private fun translateChildRangeStartsForSplice(
      into: IntArray,
      snapshot: SubtreeSnapshot,
      currentDescendantStart: Int,
  ) {
    val baseDelta = currentDescendantStart - snapshot.mountableOutputStart
    for (i in 0 until snapshot.childCount) {
      into[i] = snapshot.childRangeStart[i] + baseDelta
    }
  }

  /**
   * Splice a single descendant output from [prev] into [reductionState]. Walks one descendant
   * position at a time: builds the [RenderTreeNode], picks-or-builds the [IncrementalMountOutput]
   * (reusing prev's instance when [positionStable] for zero-alloc), propagates `excludeFromIM` up
   * the new ancestor chain, picks-or-builds the [AnimatableItem], updates the position map and
   * parent index, and registers accessibility / child-LithoView / dynamic-value bookkeeping.
   *
   * Each loop iteration is independent given the shared invariants — only [freshNodes] is mutated
   * across iterations (so child outputs can find their just-spliced parent).
   */
  private fun spliceOneOutput(
      indexInRange: Int,
      snapshot: SubtreeSnapshot,
      previousLayoutState: LayoutState,
      reductionState: ReductionState,
      externalParentRenderTreeNode: RenderTreeNode,
      freshRenderTreeNodes: Array<RenderTreeNode?>,
      previousOutputParentIndices: MutableIntList,
      basePosition: Int,
      offsetX: Int,
      offsetY: Int,
      positionStable: Boolean,
  ) {
    val previousPosition = snapshot.mountableOutputStart + indexInRange
    val previousRenderTreeNode = previousLayoutState.mountableOutputs[previousPosition]
    val previousParentIndex = previousOutputParentIndices[previousPosition]
    val parentIsExternal = previousParentIndex < snapshot.mountableOutputStart

    val newParentRenderTreeNode: RenderTreeNode =
        if (parentIsExternal) externalParentRenderTreeNode
        else freshRenderTreeNodes[previousParentIndex - snapshot.mountableOutputStart]!!

    // Allocate a fresh RenderTreeNode (parent reference differs from previous pass). Bounds are
    // reused by reference when [positionStable] OR the parent is internal (its bounds were
    // already offset). Otherwise the descendant is anchored to the new external ancestor and
    // needs a freshly-allocated offset Rect.
    val newRenderTreeNodeBounds: Rect =
        if (!positionStable && parentIsExternal) {
          Rect(
              previousRenderTreeNode.bounds.left + offsetX,
              previousRenderTreeNode.bounds.top + offsetY,
              previousRenderTreeNode.bounds.right + offsetX,
              previousRenderTreeNode.bounds.bottom + offsetY,
          )
        } else {
          previousRenderTreeNode.bounds
        }
    val newRenderTreeNode = RenderTreeNode(
        newParentRenderTreeNode,
        previousRenderTreeNode.renderUnit,
        previousRenderTreeNode.layoutData,
        newRenderTreeNodeBounds,
        previousRenderTreeNode.resolvedPadding,
        newParentRenderTreeNode.childrenCount,
        previousRenderTreeNode.poolScope,
        isFastPathEligible = true,
    )
    freshRenderTreeNodes[indexInRange] = newRenderTreeNode
    newParentRenderTreeNode.child(newRenderTreeNode)

    val renderUnitId = newRenderTreeNode.renderUnit.id
    val newPosition = reductionState.mountableOutputs.size
    reductionState.mountableOutputs.add(newRenderTreeNode)

    val lithoRenderUnit = newRenderTreeNode.renderUnit as LithoRenderUnit
    val component = lithoRenderUnit.component
    val excludesFromIncrementalMount = lithoRenderUnit.excludesFromIncrementalMount

    // Always construct a fresh IMO. Reusing the prev LayoutState's instance leaves
    // [IncrementalMountOutput.hostOutput] pointing at a discarded ancestor when
    // [LithoNode.needsHostView] flips between passes — mount diffs then misplace views.
    val incrementalMountOutput: IncrementalMountOutput = IncrementalMountOutput(
        renderUnitId,
        newPosition,
        newRenderTreeNode.getAbsoluteBounds(Rect()),
        excludesFromIncrementalMount,
        // Same rationale as addRenderTreeNode: don't read renderUnit.debugKey in prod -- its
        // getter falls back to globalKey.toString() (a full structural-key rebuild) per reused
        // render unit on every fast-path reduce. Only materialize it in debug builds.
        if (LithoDebugConfigurations.isDebugModeEnabled) newRenderTreeNode.renderUnit.debugKey
        else null,
        reductionState.incrementalMountOutputs?.get(newParentRenderTreeNode.renderUnit.id),
        newRenderTreeNode.renderUnit.contentAllocator.getPoolKey(),
        newParentRenderTreeNode.renderUnit.id,
    )
    if (excludesFromIncrementalMount) {
      reductionState.hasComponentsExcludedFromIncrementalMount = true
    }
    reductionState.getOrCreateIncrementalMountOutputs()[renderUnitId] = incrementalMountOutput
    reductionState.getOrCreateIncrementalMountOutputsList().add(incrementalMountOutput)
    reductionState.mountableOutputTops.add(incrementalMountOutput)
    reductionState.mountableOutputBottoms.add(incrementalMountOutput)

    // Always construct a fresh AnimatableItem. TransitionsExtension treats AIs as per-pass bounds
    // snapshots — sharing instances across passes leaves transitions reading stale bounds. Carry
    // forward the prev item's transitionId when available so user-declared transitions continue
    // to track the same group.
    val previousAnimatableItem = previousLayoutState.animatableItems.get(renderUnitId)
    val outputType =
        previousAnimatableItem?.getOutputType() ?: RenderUnitIdGenerator.decodeType(renderUnitId)
    reductionState.animatableItems.put(
        renderUnitId,
        createAnimatableItem(
            lithoRenderUnit,
            newRenderTreeNode.getAbsoluteBounds(Rect()),
            outputType,
            previousAnimatableItem?.getTransitionId(),
            newRenderTreeNode.bounds,
        ),
    )

    addLayoutOutputIdToPositionsMap(
        reductionState.outputsIdToPositionMap,
        lithoRenderUnit,
        newPosition,
    )
    reductionState.outputParentIndices?.add(
        if (parentIsExternal)
            reductionState.outputsIdToPositionMap.getOrDefault(
                externalParentRenderTreeNode.renderUnit.id,
                -1,
            )
        else basePosition + (previousParentIndex - snapshot.mountableOutputStart),
    )

    // Accessibility virtual-views: when a spec component declares extra a11y nodes, mark its
    // host so the mount-time a11y delegate enables virtual-view dispatch.
    if (
        component is SpecGeneratedComponent &&
            component.implementsExtraAccessibilityNodes() &&
            lithoRenderUnit.isAccessible
    ) {
      (LithoRenderUnit.getRenderUnit(newParentRenderTreeNode).component as? HostComponent)
          ?.setImplementsVirtualViews()
    }

    if (
        (component is SpecGeneratedComponent && component.hasChildLithoViews()) ||
            newRenderTreeNode.renderUnit.doesMountRenderTreeHosts()
    ) {
      reductionState.getOrCreateRenderUnitIdsWhichHostRenderTrees().add(renderUnitId)
    }

    lithoRenderUnit.commonDynamicProps?.let { commonDynamicProps ->
      reductionState.getOrCreateDynamicValueOutputs()[renderUnitId] =
          DynamicValueOutput(
              component = lithoRenderUnit.component,
              scopedContext = lithoRenderUnit.componentContext,
              commonDynamicProps = commonDynamicProps,
          )
    }
  }

  private fun addDrawableRenderTreeNode(
      unit: LithoRenderUnit,
      bounds: Rect,
      parent: RenderTreeNode? = null,
      result: LayoutResult,
      reductionState: ReductionState,
      expandedTouchBounds: Rect?,
      hierarchy: DebugHierarchy.Node? = null,
      @OutputUnitType type: Int,
      matchHostBoundsTransitions: Boolean,
  ): RenderTreeNode {

    val debugNode: DebugHierarchy.Node? = hierarchy?.mutateType(type)
    val renderTreeNode: RenderTreeNode = createRenderTreeNode(
        unit = unit,
        bounds = bounds,
        padding = null,
        reductionState = reductionState,
        isSizeDependant = true,
        expandedTouchBounds = expandedTouchBounds,
        parent = parent,
        debugHierarchyNode = debugNode,
    )
    val drawableRenderUnit: LithoRenderUnit = renderTreeNode.renderUnit as LithoRenderUnit

    addRenderTreeNode(
        reductionState = reductionState,
        node = renderTreeNode,
        result = result,
        unit = drawableRenderUnit,
        type = type,
        transitionId =
            if (!matchHostBoundsTransitions) reductionState.currentTransitionId else null,
        parent = parent,
    )

    return renderTreeNode
  }

  private fun addLayoutOutputIdToPositionsMap(
      outputsIdToPositionMap: MutableLongIntMap,
      unit: LithoRenderUnit,
      position: Int,
  ) = outputsIdToPositionMap.put(unit.id, position)

  private fun maybeAddLayoutOutputToAffinityGroup(
      group: OutputUnitsAffinityGroup<AnimatableItem>? = null,
      @OutputUnitType outputType: Int,
      animatableItem: AnimatableItem,
  ) = group?.add(outputType, animatableItem)

  private fun createStyleTransitionTarget(
      node: LithoNode,
      data: StyleTransitionData,
      reductionState: ReductionState,
      hostLayoutPosition: Int,
  ): StyleTransitionTarget {
    val hostRenderTreeNode = reductionState.mountableOutputs[hostLayoutPosition]
    val hostAnimatableItem =
        checkNotNull(reductionState.animatableItems[hostRenderTreeNode.renderUnit.id]) {
          "Style.transition host output should have an animatable item."
        }
    val outputGroup = OutputUnitsAffinityGroup<AnimatableItem>()
    outputGroup.add(hostAnimatableItem.getOutputType(), hostAnimatableItem)
    return StyleTransitionTarget(
        data = data,
        styleTransitionId = createStyleTransitionId(node),
        outputGroup = outputGroup,
    )
  }

  private fun createStyleTransitionId(
      node: LithoNode,
  ): StyleTransitionId = StructuralStyleTransitionId(node.transitionGlobalKey)

  private fun addStyleTransitionTarget(
      reductionState: ReductionState,
      target: StyleTransitionTarget,
  ) {
    val targets =
        reductionState.styleTransitionTargets
            ?: ArrayList<StyleTransitionTarget>().also {
              reductionState.styleTransitionTargets = it
            }
    val existing = targets.firstOrNull { it.styleTransitionId == target.styleTransitionId }
    check(existing == null) {
      "Duplicate Style.transition structural id '${target.styleTransitionId}' found in the same " +
          "Litho tree. This is an internal id error."
    }
    targets.add(target)
  }

  private fun addCurrentAffinityGroupToTransitionMapping(reductionState: ReductionState) {
    val group: OutputUnitsAffinityGroup<AnimatableItem>? =
        reductionState.currentLayoutOutputAffinityGroup
    if (group == null || group.isEmpty) {
      return
    }

    val transitionId = reductionState.currentTransitionId ?: return

    if (transitionId.type == TransitionId.Type.AUTOGENERATED) {
      // Check if the duplications of this key has been found before, if so, just ignore it
      if (!reductionState.duplicatedTransitionIds.contains(transitionId)) {
        val transitionIdMap = reductionState.getOrCreateTransitionIdMapping()
        if (transitionIdMap.put(transitionId, group) != null) {
          // Already seen component with the same generated transition key, remove it from the
          // mapping and ignore in the future
          transitionIdMap.remove(transitionId)
          reductionState.duplicatedTransitionIds.add(transitionId)
        }
      }
    } else {
      val transitionIdMap = reductionState.getOrCreateTransitionIdMapping()
      if (transitionIdMap.put(transitionId, group) != null) {
        // Already seen component with the same manually set transition key
        // Changed from FATAL to ERROR to prevent crashes during DebugInfoReporter migration
        ErrorReporter.report(
            LogLevel.ERROR,
            DUPLICATE_TRANSITION_IDS,
            """The transitionId '$transitionId' is defined multiple times in the same layout. TransitionIDs must be unique.
                  Tree:
                  ${ComponentUtils.treeToString(reductionState.rootNode)}
                  """
                .trimIndent(),
        )
      }
    }
    reductionState.currentLayoutOutputAffinityGroup = null
    reductionState.currentTransitionId = null
  }

  /**
   * Single bottom-up pass to expand host IMO bounds to contain all children.
   *
   * IMOs are created with hostOutput=null to skip per-child ensureHostBounds during construction.
   * This method processes all outputs in reverse DFS order (children before parents), expanding
   * each host's bounds to contain its direct child. Bottom-up ordering ensures transitive expansion
   * without recursion: by the time a host is processed as a child of its own host, it already has
   * correct (expanded) bounds from all its descendants.
   *
   * Total work: exactly N comparisons for N outputs (one per child-host pair).
   */
  private fun finalizeIncrementalMountBounds(reductionState: ReductionState) {
    val outputs = reductionState.mountableOutputs
    val imOutputs = reductionState.incrementalMountOutputs ?: return
    for (i in outputs.size - 1 downTo 0) {
      val rtn = outputs[i]
      val parentRtn = rtn.parent ?: continue
      val childImo = imOutputs[rtn.renderUnit.id] ?: continue
      val hostImo = imOutputs[parentRtn.renderUnit.id] ?: continue
      IncrementalMountOutput.expandHostToContainChild(childImo, hostImo)
    }
  }

  /**
   * If we have an interactive LayoutSpec or a MountSpec Drawable, we need to insert an
   * HostComponent in the Outputs such as it will be used as a HostView at Mount time. View
   * MountSpec are not allowed.
   *
   * @return The position the HostLayoutOutput was inserted.
   */
  private fun addHostRenderTreeNode(
      hostRenderUnit: LithoRenderUnit,
      bounds: Rect,
      padding: Rect?,
      parent: RenderTreeNode? = null,
      result: LithoLayoutResult,
      node: LithoNode,
      reductionState: ReductionState,
      diffNode: DiffNode? = null,
      hierarchy: DebugHierarchy.Node? = null,
  ): Int {

    // Tripwire mirroring LithoNode.needsHostView(), which is false for View-mounting nodes: the
    // only host that may wrap a View is the root host. That host is created for the node reached
    // without a parent (however many nested size-dependent layouts wrap it), which only happens
    // when shouldAddHostViewForRootComponent is off.
    require(!(node.willMountView && parent != null)) {
      "We shouldn't insert a host as a parent of a View"
    }

    val hostRenderTreeNode: RenderTreeNode = createRenderTreeNode(
        unit = hostRenderUnit,
        bounds = bounds,
        padding = padding,
        reductionState = reductionState,
        isSizeDependant = true,
        parent = parent,
        expandedTouchBounds = result.expandedTouchBounds,
        zIndex = node.zIndex,
        debugHierarchyNode = hierarchy?.mutateType(OutputUnitType.HOST),
    )

    diffNode?.hostOutput = hostRenderUnit

    // The component of the hostLayoutOutput will be set later after all the
    // children got processed.
    addRenderTreeNode(
        reductionState = reductionState,
        node = hostRenderTreeNode,
        result = result,
        unit = hostRenderUnit,
        type = OutputUnitType.HOST,
        transitionId = reductionState.currentTransitionId,
        parent = parent,
    )

    return reductionState.mountableOutputs.size - 1
  }

  private fun sortTops(reductionState: ReductionState) {
    try {
      reductionState.mountableOutputTops.sortWith(
          IncrementalMountRenderCoreExtension.TopsComparator,
      )
    } catch (e: IllegalArgumentException) {
      val size = reductionState.mountableOutputTops.size
      val errorMessage = StringBuilder()
      errorMessage.append(e.message).append("\n")
      errorMessage.append("Error while sorting ReductionState tops. Size: $size").append("\n")
      val rect = Rect()
      for (i in 0 until size) {
        val node: RenderTreeNode = reductionState.mountableOutputs[i]
        errorMessage.append("   Index $i top: ${node.getAbsoluteBounds(rect).top}").append("\n")
      }
      throw IllegalStateException(errorMessage.toString())
    }
  }

  private fun sortBottoms(reductionState: ReductionState) {
    try {
      reductionState.mountableOutputBottoms.sortWith(
          IncrementalMountRenderCoreExtension.BottomsComparator,
      )
    } catch (e: IllegalArgumentException) {
      val size = reductionState.mountableOutputBottoms.size
      val errorMessage = StringBuilder()
      errorMessage.append(e.message).append("\n")
      errorMessage.append("Error while sorting ReductionState bottoms. Size: $size").append("\n")
      val rect = Rect()
      for (i in 0 until size) {
        val node: RenderTreeNode = reductionState.mountableOutputs[i]
        errorMessage
            .append("   Index $i bottom: ${node.getAbsoluteBounds(rect).bottom}")
            .append("\n")
      }
      throw IllegalStateException(errorMessage.toString())
    }
  }

  private fun addRenderTreeNode(
      reductionState: ReductionState,
      node: RenderTreeNode,
      result: LayoutResult? = null,
      unit: LithoRenderUnit,
      @OutputUnitType type: Int,
      transitionId: TransitionId? = null,
      parent: RenderTreeNode? = null,
  ) {

    parent?.child(node)

    val component: Component = unit.component
    if (
        component is SpecGeneratedComponent &&
            component.implementsExtraAccessibilityNodes() &&
            unit.isAccessible &&
            parent != null
    ) {
      val parentUnit: LithoRenderUnit = LithoRenderUnit.getRenderUnit(parent)
      (parentUnit.component as HostComponent).setImplementsVirtualViews()
    }

    val position: Int = reductionState.mountableOutputs.size
    val absoluteBounds: Rect = node.getAbsoluteBounds(Rect())
    val excludesFromIncrementalMount: Boolean = unit.excludesFromIncrementalMount

    val incrementalMountOutput = IncrementalMountOutput(
        node.renderUnit.id,
        position,
        absoluteBounds,
        excludesFromIncrementalMount,
        // IncrementalMountOutput.debugKey is debug-only (nullable). Avoid reading
        // renderUnit.debugKey in prod: with structural GlobalKeys its getter falls back to
        // componentContext.globalKey.toString(), recursively rebuilding the full key string
        // for every render unit on every reduce. Only materialize it when debug mode is on.
        if (LithoDebugConfigurations.isDebugModeEnabled) node.renderUnit.debugKey else null,
        // When batch finalization is enabled, pass null to skip per-child ensureHostBounds.
        // Host bounds are finalized in finalizeIncrementalMountBounds after reduce.
        if (ComponentsConfiguration.useFinalizeForIMOBounds) null
        else if (parent != null) reductionState.incrementalMountOutputs?.get(parent.renderUnit.id)
        else null,
        node.renderUnit.contentAllocator.getPoolKey(),
        parent?.renderUnit?.id ?: IncrementalMountOutput.NO_HOST_ID,
    )
    if (excludesFromIncrementalMount) {
      reductionState.hasComponentsExcludedFromIncrementalMount = true
    }

    val id: Long = node.renderUnit.id
    reductionState.mountableOutputs.add(node)
    reductionState.getOrCreateIncrementalMountOutputs()[id] = incrementalMountOutput
    reductionState.getOrCreateIncrementalMountOutputsList().add(incrementalMountOutput)
    reductionState.mountableOutputTops.add(incrementalMountOutput)
    reductionState.mountableOutputBottoms.add(incrementalMountOutput)

    if (
        (component is SpecGeneratedComponent && component.hasChildLithoViews()) ||
            node.renderUnit.doesMountRenderTreeHosts()
    ) {
      reductionState.getOrCreateRenderUnitIdsWhichHostRenderTrees().add(id)
    }

    if (node.renderUnit is LithoRenderUnit) {
      val lithoRenderUnit: LithoRenderUnit = node.renderUnit as LithoRenderUnit
      lithoRenderUnit.commonDynamicProps?.let { commonDynamicProps ->
        val dynamicValueOutput = DynamicValueOutput(
            component = lithoRenderUnit.component,
            scopedContext = lithoRenderUnit.componentContext,
            commonDynamicProps = commonDynamicProps,
        )
        reductionState.getOrCreateDynamicValueOutputs().put(lithoRenderUnit.id, dynamicValueOutput)
      }
    }

    val animatableItem = createAnimatableItem(
        unit,
        if (parent == null && (reductionState.rootX != 0 || reductionState.rootY != 0)) {
          Rect(
              reductionState.rootX,
              reductionState.rootY,
              reductionState.rootX + absoluteBounds.width(),
              reductionState.rootY + absoluteBounds.height(),
          )
        } else {
          absoluteBounds
        },
        type,
        transitionId,
        bounds = node.bounds,
    )
    reductionState.animatableItems.put(node.renderUnit.id, animatableItem)
    addLayoutOutputIdToPositionsMap(reductionState.outputsIdToPositionMap, unit, position)
    maybeAddLayoutOutputToAffinityGroup(
        reductionState.currentLayoutOutputAffinityGroup,
        type,
        animatableItem,
    )
    // Subtree caching: parent index for parent-chain reconstruction during splice.
    reductionState.outputParentIndices?.add(
        if (parent != null)
            reductionState.outputsIdToPositionMap.getOrDefault(parent.renderUnit.id, -1)
        else -1,
    )
  }

  private fun LithoNode.getDebugHierarchy(
      parentHierarchy: DebugHierarchy.Node? = null,
  ): DebugHierarchy.Node? {
    if (!LithoDebugConfigurations.isDebugHierarchyEnabled) {
      return null
    }
    val infos: List<ScopedComponentInfo> = scopedComponentInfos
    val components: MutableList<Component> = ArrayList(infos.size)
    for (info in infos) {
      components.add(info.component)
    }
    return DebugHierarchy.newNode(parentHierarchy, tailComponent, components)
  }
}
