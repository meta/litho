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
import androidx.annotation.VisibleForTesting
import androidx.collection.LongObjectMap
import androidx.collection.LongSet
import androidx.collection.MutableIntList
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import androidx.collection.ScatterSet
import com.facebook.litho.EndToEndTestingExtension.EndToEndTestingExtensionInput
import com.facebook.litho.Transition.RootBoundsTransition
import com.facebook.litho.animation.StyleTransitionTarget
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.state.StateId
import com.facebook.litho.transition.TransitionData
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.LayoutCacheData
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderTree
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.SizeConstraints.Helper.getHeightSpec
import com.facebook.rendercore.SizeConstraints.Helper.getWidthSpec
import com.facebook.rendercore.incrementalmount.IncrementalMountExtensionInput
import com.facebook.rendercore.incrementalmount.IncrementalMountOutput
import com.facebook.rendercore.transitions.TransitionsExtensionInput
import com.facebook.rendercore.visibility.VisibilityBoundsTransformer
import com.facebook.rendercore.visibility.VisibilityExtensionInput
import com.facebook.rendercore.visibility.VisibilityOutput
import com.meta.systrace.Systracer
import java.util.concurrent.atomic.AtomicInteger

/**
 * The main role of [LayoutState] is to hold the output of layout calculation. This includes
 * mountable outputs and visibility outputs. A centerpiece of the class is
 * [LithoReducer#setSizeAfterMeasureAndCollectResults(ComponentContext, LithoLayoutContext, LayoutState)]
 * which prepares the before-mentioned outputs based on the provided [LithoNode] for later use in
 * [MountState].
 *
 * @property componentTreeId the id of the [ComponentTree] that generated this [LayoutState]
 *
 * This needs to be accessible to statically mock the class in tests.
 */
class LayoutState
internal constructor(
    val resolveResult: ResolveResult,
    val version: Int,
    val treeState: TreeState,
    val componentTreeId: Int,
    val isAccessibilityEnabled: Boolean,
    val layoutCacheData: LayoutCacheData?,
    private val reductionState: ReductionStateLite,
    /**
     * Natural sizes for every collected reflow node this commit (changed and unchanged), keyed by
     * [LithoNode.transitionGlobalKey]; [StyleTransitionsExtension] reconciles each against the live
     * store target at mount. Null on every layout with no collected reflow node.
     */
    internal val reflowTargetSizes: ScatterMap<GlobalKey, IntSize>? = null,
) :
    IncrementalMountExtensionInput,
    VisibilityExtensionInput,
    TransitionsExtensionInput,
    EndToEndTestingExtensionInput,
    PotentiallyPartialResult,
    DynamicPropsExtensionInput {

  internal val transitionData: TransitionData?
    get() = reductionState.transitionData

  @get:JvmName("getStateReads")
  internal val stateReads: Map<StateId, ScatterSet<GlobalKey>>?
    get() = reductionState.stateReads

  val sizeConstraints: SizeConstraints
    get() = reductionState.sizeConstraints

  val root: LithoNode?
    get() = reductionState.rootNode

  val diffTree: DiffNode?
    get() = reductionState.diffTreeRoot

  val mountableOutputs: List<RenderTreeNode>
    get() = reductionState.mountableOutputs

  val width: Int
    get() = reductionState.width

  val height: Int
    get() = reductionState.height

  /** Id of this [LayoutState]. */
  val id: Int
    get() = reductionState.id

  /** Id of the [LayoutState] that was compared to when calculating this [LayoutState]. */
  val previousLayoutStateId: Int
    get() = reductionState.previousLayoutStateId

  val currentTransitionId: TransitionId?
    get() = reductionState.currentTransitionId

  val attachables: List<Attachable>?
    get() = reductionState.attachables

  /** The [useResolveEffect]s of the trees resolved while laying this out. */
  internal val resolveEffects: List<Attachable>?
    get() = reductionState.resolveEffects

  /** The keys of the [useOnDiscard] callbacks of the renders whose output is in this layout. */
  internal val discardKeys: Set<Any>?
    get() = reductionState.discardKeys

  /** The [useOnDiscard] callbacks of the renders that laid this out; null for a reused layout. */
  internal var discardCallbacks: DiscardCallbacks? = null

  internal val subtreeSnapshots: MutableScatterMap<LithoNode, SubtreeSnapshot>?
    get() = reductionState.subtreeSnapshots

  internal val outputParentIndices: MutableIntList?
    get() = reductionState.outputParentIndices

  internal val testOutputs: List<TestOutput>?
    get() = reductionState.testOutputs

  // Distinct from the private `componentScopes` var below, which gets nulled by
  // [consumeComponentScopes]. Splice path needs always-available access to prev pass's scopes.
  internal val componentScopesInternal: List<ScopedComponentInfo>
    get() = reductionState.componentScopes

  internal val animatableItems: MutableLongObjectMap<AnimatableItem>
    get() = reductionState.animatableItems

  internal val styleTransitionTargets: List<StyleTransitionTarget>
    get() = reductionState.styleTransitionTargets ?: emptyList()

  /** Whether or not there are components marked as 'ExcludeFromIncrementalMount'. */
  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  val hasComponentsExcludedFromIncrementalMount: Boolean
    get() = reductionState.hasComponentsExcludedFromIncrementalMount

  val currentLayoutOutputAffinityGroup: OutputUnitsAffinityGroup<AnimatableItem>?
    get() = reductionState.currentLayoutOutputAffinityGroup

  override val tracer: Systracer = ComponentsSystrace.systrace
  override val rootTransitionId: TransitionId? =
      LithoNodeUtils.createTransitionId(resolveResult.node)
  /** Gets a mapping from transition ids to a group of LayoutOutput. */
  override val transitionIdMapping: Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>>?
    get() = reductionState.transitionIdMapping

  override val visibilityOutputs: List<VisibilityOutput>
    get() = reductionState.visibilityOutputs

  override val renderUnitIdsWhichHostRenderTrees: LongSet?
    get() = reductionState.renderUnitIdsWhichHostRenderTrees

  val rootComponent: Component
    get() = resolveResult.component

  val componentHandles: ScatterSet<Handle>?
    get() = reductionState.componentHandles

  val isEmpty: Boolean
    get() = resolveResult.component is EmptyComponent

  val isActivityValid: Boolean
    get() = ContextUtils.getValidActivityForContext(resolveResult.context.androidContext) != null

  val visibilityOutputCount: Int
    get() = visibilityOutputs.size

  val componentContext: ComponentContext
    get() = resolveResult.context

  override val testOutputCount: Int
    get() = reductionState.testOutputs?.size ?: 0

  override val dynamicValueOutputs: LongObjectMap<DynamicValueOutput>?
    get() = reductionState.dynamicValueOutputs

  override val isPartialResult: Boolean = false

  override val animatableRootItem: AnimatableItem?
    get() = reductionState.animatableItems[MountState.ROOT_HOST_ID]

  override val treeId: Int
    get() = componentTreeId

  override val transitions: List<Transition>?
    get() = transitionData?.transitions

  override val isIncrementalMountEnabled: Boolean
    get() = ComponentContext.isIncrementalMountEnabled(resolveResult.context)

  override val rootName: String
    get() = resolveResult.component.simpleName

  override val visibilityBoundsTransformer: VisibilityBoundsTransformer?
    get() = componentContext.lithoConfiguration.componentsConfig.visibilityBoundsTransformer

  override val isProcessingVisibilityOutputsEnabled: Boolean
    get() = shouldProcessVisibilityOutputs

  override val useEmptyVisibleRectOnDetach: Boolean
    get() = componentContext.lithoConfiguration.componentsConfig.useEmptyVisibleRectOnDetach

  private var cachedRenderTree: RenderTree? = null // memoized RenderTree
  // TODO(t66287929): Remove isCommitted from LayoutState by matching RenderState logic around
  //  Futures.
  private var isCommitted = false
  // needed to be var as it's being updated in setShouldProcessVisibilityOutputs
  private var shouldProcessVisibilityOutputs = false
  // needed to be var as it's being reset via consumeScopedSpecComponentInfos
  private var componentScopes: List<ScopedComponentInfo>? = reductionState.componentScopes

  // needed to be var as a previously evaluated reference is set(restored) in LithoViewTestHelper
  var rootLayoutResult: LayoutResult? = reductionState.rootLayoutResult
    internal set

  // needed to be var as it's being updated in setInitialRootBoundsForAnimation
  var rootWidthAnimation: RootBoundsTransition? = null
    private set

  // needed to be var as it's being updated in setInitialRootBoundsForAnimation
  var rootHeightAnimation: RootBoundsTransition? = null
    private set

  fun consumeComponentScopes(): List<ScopedComponentInfo>? {
    val scopedSpecComponentInfos = componentScopes
    this.componentScopes = null
    return scopedSpecComponentInfos
  }

  fun toRenderTree(): RenderTree {
    cachedRenderTree?.let {
      return it
    }
    val root = mountableOutputs[0]
    check(root.renderUnit.id == MountState.ROOT_HOST_ID) {
      "Root render unit has invalid id ${root.renderUnit.id}"
    }
    val flatList = Array(mountableOutputs.size) { i -> mountableOutputs[i] }
    val renderTree =
        RenderTree.create(
            root,
            flatList,
            reductionState.outputsIdToPositionMap,
            sizeConstraints.encodedValue,
            componentTreeId,
            null,
            null,
            null,
        )
    cachedRenderTree = renderTree
    return renderTree
  }

  fun isCompatibleSpec(widthSpec: Int, heightSpec: Int): Boolean {
    val widthIsCompatible =
        MeasureComparisonUtils.isMeasureSpecCompatible(
            getWidthSpec(sizeConstraints),
            widthSpec,
            width,
        )
    val heightIsCompatible =
        MeasureComparisonUtils.isMeasureSpecCompatible(
            getHeightSpec(sizeConstraints),
            heightSpec,
            height,
        )
    return widthIsCompatible && heightIsCompatible
  }

  fun isCompatibleComponentAndSpec(componentId: Int, widthSpec: Int, heightSpec: Int): Boolean =
      resolveResult.component.instanceId == componentId && isCompatibleSpec(widthSpec, heightSpec)

  fun isCompatibleSize(width: Int, height: Int): Boolean =
      this.width == width && this.height == height

  fun isForComponentId(componentId: Int): Boolean =
      resolveResult.component.instanceId == componentId

  override fun getMountableOutputCount(): Int = mountableOutputs.size

  override fun getIncrementalMountOutputCount(): Int =
      reductionState.incrementalMountOutputs?.size ?: 0

  override fun getMountableOutputAt(position: Int): RenderTreeNode = mountableOutputs[position]

  override fun getIncrementalMountOutputForId(id: Long): IncrementalMountOutput? =
      reductionState.incrementalMountOutputs?.get(id)

  override fun getIncrementalMountOutputs(): Collection<IncrementalMountOutput>? =
      reductionState.incrementalMountOutputsList

  override fun getAnimatableItem(id: Long): AnimatableItem? = reductionState.animatableItems[id]

  override fun getOutputsOrderedByTopBounds(): List<IncrementalMountOutput> =
      reductionState.mountableOutputTops

  override fun getOutputsOrderedByBottomBounds(): List<IncrementalMountOutput> =
      reductionState.mountableOutputBottoms

  fun getVisibilityOutputAt(index: Int): VisibilityOutput = visibilityOutputs[index]

  override fun getTestOutputAt(position: Int): TestOutput? =
      reductionState.testOutputs?.get(position)

  fun getWidthSpec(): Int = getWidthSpec(sizeConstraints)

  fun getHeightSpec(): Int = getHeightSpec(sizeConstraints)

  /**
   * @return the position of the [LithoRenderUnit] with id layoutOutputId in the [LayoutState] list
   *   of outputs or -1 if no [LithoRenderUnit] with that id exists in the [LayoutState]
   */
  override fun getPositionForId(id: Long): Int =
      reductionState.outputsIdToPositionMap.getOrDefault(id, -1)

  override fun renderUnitWithIdHostsRenderTrees(id: Long): Boolean =
      reductionState.renderUnitIdsWhichHostRenderTrees?.contains(id) == true

  /** Gets a group of LayoutOutput given transition key */
  override fun getAnimatableItemForTransitionId(
      transitionId: TransitionId,
  ): OutputUnitsAffinityGroup<AnimatableItem>? =
      reductionState.transitionIdMapping?.get(transitionId)

  override fun setInitialRootBoundsForAnimation(
      rootWidth: RootBoundsTransition?,
      rootHeight: RootBoundsTransition?,
  ) {
    rootWidthAnimation = rootWidth
    rootHeightAnimation = rootHeight
  }

  override fun getMountTimeTransitions(
      previousInput: TransitionsExtensionInput?,
  ): List<Transition>? {
    val transitionData = transitionData
    previousInput as LayoutState?
    var mountTimeTransitions: MutableList<Transition>? = null
    val mountedLayoutStateId = previousInput?.id ?: NO_PREVIOUS_LAYOUT_STATE_ID
    if (transitionData != null && !transitionData.isEmpty()) {
      mountTimeTransitions = ArrayList()
      val canUseOptimisticTransitions = previousLayoutStateId == mountedLayoutStateId
      if (canUseOptimisticTransitions) {
        val optimisticTransitions = transitionData.optimisticTransitions
        if (optimisticTransitions != null) mountTimeTransitions.addAll(optimisticTransitions)
      }
      val previousTreeState = previousInput?.treeState
      val transitionCreators = transitionData.transitionCreators
      if (transitionCreators != null) {
        for (creator in transitionCreators) {
          if (creator.supportsOptimisticTransitions && canUseOptimisticTransitions) continue
          val previousRenderData = previousTreeState?.getPreviousRenderData(creator.identityKey)
          val transition = creator.createTransition(previousRenderData)
          if (transition != null) mountTimeTransitions.add(transition)
        }
      }
    }
    // Use the current resolved TreeState here rather than the previous one
    val updateStateTransitions = treeState.pendingStateUpdateTransitions
    if (updateStateTransitions.isNotEmpty()) {
      if (mountTimeTransitions == null) {
        mountTimeTransitions = ArrayList()
      }
      mountTimeTransitions.addAll(updateStateTransitions)
    }
    return mountTimeTransitions
  }

  @JvmName("recordRenderData")
  internal fun recordRenderData() {
    treeState.recordRenderData(this)
  }

  /** Debug-only: return a string representation of this LayoutState and its LayoutOutputs. */
  fun dumpAsString(): String {
    if (
        !LithoDebugConfigurations.isDebugModeEnabled && !ComponentsConfiguration.isEndToEndTestRun
    ) {
      throw RuntimeException(
          "LayoutState#dumpAsString() should only be called in debug mode or from e2e tests!",
      )
    }
    var res =
        """LayoutState w/ ${getMountableOutputCount()} mountable outputs, root: ${resolveResult.component}
"""
    for (i in 0 until getMountableOutputCount()) {
      val node = getMountableOutputAt(i)
      val renderUnit = LithoRenderUnit.getRenderUnit(node)
      res +=
          """  [$i] id: ${node.renderUnit.id}, host: ${node.parent?.renderUnit?.id ?: -1}, component: ${renderUnit.component.simpleName}
"""
    }
    return res
  }

  fun checkWorkingRangeAndDispatch(
      position: Int,
      firstVisibleIndex: Int,
      lastVisibleIndex: Int,
      firstFullyVisibleIndex: Int,
      lastFullyVisibleIndex: Int,
      stateHandler: WorkingRangeStatusHandler,
  ) {
    val workingRangeContainer = reductionState.workingRangeContainer ?: return
    workingRangeContainer.checkWorkingRangeAndDispatch(
        position,
        firstVisibleIndex,
        lastVisibleIndex,
        firstFullyVisibleIndex,
        lastFullyVisibleIndex,
        stateHandler,
    )
  }

  fun dispatchOnExitRangeIfNeeded(stateHandler: WorkingRangeStatusHandler) {
    reductionState.workingRangeContainer?.dispatchOnExitedRangeIfNeeded(stateHandler)
  }

  override fun needsToRerunTransitions(): Boolean {
    val stateUpdater = resolveResult.context.stateUpdater
    return stateUpdater?.isFirstMount == true
  }

  override fun setNeedsToRerunTransitions(needsToRerunTransitions: Boolean) {
    val stateUpdater = resolveResult.context.stateUpdater
    if (stateUpdater != null) {
      stateUpdater.isFirstMount = needsToRerunTransitions
    }
  }

  fun isCommitted(): Boolean = isCommitted

  fun markCommitted() {
    isCommitted = true
  }

  fun setShouldProcessVisibilityOutputs(value: Boolean) {
    shouldProcessVisibilityOutputs = value
  }

  fun getRenderTreeNode(output: IncrementalMountOutput): RenderTreeNode =
      getMountableOutputAt(output.index)

  fun copyWith(
      updatedResolveResult: ResolveResult,
      treeState: TreeState,
      newVersion: Int,
  ): LayoutState {
    return LayoutState(
        updatedResolveResult,
        newVersion,
        treeState,
        componentTreeId,
        isAccessibilityEnabled,
        layoutCacheData,
        reductionState,
    )
        .also {
          // Reuse the cached RenderTree since all inputs to toRenderTree() are identical
          // (shared reductionState, same componentTreeId). This enables MountState to detect
          // the tree is unchanged via reference equality, skipping the full O(N) mount traversal.
          it.cachedRenderTree = cachedRenderTree
        }
  }

  fun findBoundsForHandle(handle: Handle): Rect? {
    val root = rootLayoutResult as? LithoLayoutResult ?: return null
    return findBoundsInTree(root, handle, 0, 0)
  }

  private fun findBoundsInTree(root: LithoLayoutResult, handle: Handle, x: Int, y: Int): Rect? {
    val node = root.node

    for (i in 0 until node.componentCount) {
      val component = node.getComponentAt(i)
      if (component.hasHandle() && component.handle === handle) {
        return if (root.contentRenderUnit != null) {
          Rect(
              x + root.adjustedLeft(),
              y + root.adjustedTop(),
              x + root.width + root.adjustedRight(),
              y + root.height + root.adjustedBottom(),
          )
        } else { // Default bounds
          Rect(x, y, x + root.width, y + root.height)
        }
      }
    }

    if (root.isDeferredLayoutResult) {
      val actualRoot = (root as DeferredLithoLayoutResult).result ?: return null
      return findBoundsInTree(
          actualRoot,
          handle,
          x + root.getXForChildAtIndex(0),
          y + root.getYForChildAtIndex(0),
      )
    }

    for (i in 0 until root.childCount) {
      val child = root.getChildAt(i)
      val found = findBoundsInTree(
          child,
          handle,
          x + root.getXForChildAtIndex(i),
          y + root.getYForChildAtIndex(i),
      )
      if (found != null) return found
    }

    return null
  }

  companion object {
    @JvmStatic
    fun isFromSyncLayout(@RenderSource source: Int): Boolean =
        when (source) {
          RenderSource.MEASURE_SET_SIZE_SPEC,
          RenderSource.SET_ROOT_SYNC,
          RenderSource.UPDATE_STATE_SYNC,
          RenderSource.SET_SIZE_SPEC_SYNC -> true
          else -> false
        }

    @get:JvmStatic val idGenerator: AtomicInteger = AtomicInteger(1)

    const val NO_PREVIOUS_LAYOUT_STATE_ID: Int = -1

    @JvmStatic
    fun layoutSourceToString(@RenderSource source: Int): String =
        when (source) {
          RenderSource.SET_ROOT_SYNC -> "setRootSync"
          RenderSource.SET_SIZE_SPEC_SYNC -> "setSizeSpecSync"
          RenderSource.UPDATE_STATE_SYNC -> "updateStateSync"
          RenderSource.SET_ROOT_ASYNC -> "setRootAsync"
          RenderSource.SET_SIZE_SPEC_ASYNC -> "setSizeSpecAsync"
          RenderSource.UPDATE_STATE_ASYNC -> "updateStateAsync"
          RenderSource.MEASURE_SET_SIZE_SPEC -> "measure_setSizeSpecSync"
          RenderSource.MEASURE_SET_SIZE_SPEC_ASYNC -> "measure_setSizeSpecAsync"
          RenderSource.TEST -> "test"
          RenderSource.NONE -> "none"
          else -> throw RuntimeException("Unknown calculate layout source: $source")
        }

    @JvmStatic
    fun isNullOrEmpty(layoutState: LayoutState?): Boolean =
        layoutState == null || layoutState.isEmpty
  }
}
