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

import androidx.collection.LongObjectMap
import androidx.collection.LongSet
import androidx.collection.MutableIntList
import androidx.collection.MutableLongIntMap
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.animation.StyleTransitionTarget
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.state.StateId
import com.facebook.litho.transition.MutableTransitionData
import com.facebook.litho.transition.TransitionData
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.PoolScope
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.SizeConstraints.Helper.getHeightSpec
import com.facebook.rendercore.SizeConstraints.Helper.getWidthSpec
import com.facebook.rendercore.incrementalmount.IncrementalMountOutput
import com.facebook.rendercore.visibility.VisibilityOutput

/**
 * A data structure that holds all the information needed to perform a reduction pass, which is
 * going to be transferred to [LayoutState] in the end.
 */
@DataClassGenerate(toString = Mode.OMIT, equalsHashCode = Mode.KEEP)
internal data class ReductionState(
    val componentContext: ComponentContext,
    val sizeConstraints: SizeConstraints,
    val currentLayoutState: LayoutState?,
    val rootX: Int,
    val rootY: Int,
    val rootLayoutResult: LayoutResult? = null,
    val id: Int = LayoutState.idGenerator.getAndIncrement(),
    val previousLayoutStateId: Int =
        currentLayoutState?.id ?: LayoutState.NO_PREVIOUS_LAYOUT_STATE_ID,
    val mountableOutputs: MutableList<RenderTreeNode> = ArrayList(8),
    val componentRootName: String = componentContext.componentScope?.simpleName ?: "",
    val widthSpec: Int = getWidthSpec(sizeConstraints),
    val heightSpec: Int = getHeightSpec(sizeConstraints),
    val visibilityOutputs: MutableList<VisibilityOutput> = ArrayList(8),
    val testOutputs: MutableList<TestOutput>? =
        if (ComponentsConfiguration.isEndToEndTestRun) ArrayList(8) else null,
    val componentScopes: MutableList<ScopedComponentInfo> = ArrayList(),
    var componentHandles: MutableScatterSet<Handle>? = null,
    val duplicatedTransitionIds: MutableScatterSet<TransitionId> = MutableScatterSet(),
    var transitionIdMapping: MutableMap<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>>? =
        null,
    val mountableOutputTops: ArrayList<IncrementalMountOutput> = ArrayList(),
    val mountableOutputBottoms: ArrayList<IncrementalMountOutput> = ArrayList(),
    // Left null until a value is actually inserted.
    var incrementalMountOutputs: MutableLongObjectMap<IncrementalMountOutput>? = null,
    // Parallel insertion-ordered list of incremental mount outputs (MutableLongObjectMap doesn't
    // preserve insertion order the way LinkedHashMap does, so we keep a side list).
    var incrementalMountOutputsList: ArrayList<IncrementalMountOutput>? = null,
    var renderUnitIdsWhichHostRenderTrees: MutableLongSet? = null,
    var dynamicValueOutputs: MutableLongObjectMap<DynamicValueOutput>? = null,
    val animatableItems: MutableLongObjectMap<AnimatableItem> = MutableLongObjectMap(8),
    val outputsIdToPositionMap: MutableLongIntMap = MutableLongIntMap(8),
    var width: Int = 0,
    var height: Int = 0,
    var rootNode: LithoNode? = null,
    var diffTreeRoot: DiffNode? = null,
    var currentTransitionId: TransitionId? = currentLayoutState?.currentTransitionId,
    var currentLayoutOutputAffinityGroup: OutputUnitsAffinityGroup<AnimatableItem>? =
        currentLayoutState?.currentLayoutOutputAffinityGroup,
    var hasComponentsExcludedFromIncrementalMount: Boolean = false,
    var attachables: MutableList<Attachable>? = null,
    var resolveEffects: MutableList<Attachable>? = null,
    var discardKeys: MutableSet<Any>? = null,
    var transitionData: MutableTransitionData? = null,
    var styleTransitionTargets: MutableList<StyleTransitionTarget>? = null,
    var workingRangeContainer: WorkingRangeContainer? = null,
    var stateReads: MutableMap<StateId, MutableScatterSet<GlobalKey>>? = null,
    // Hoisted from per-output createContentRenderTreeNode — same value for every output in a single
    // reduce pass. Read by per-output and root-host paths in LithoReducer to avoid a TreeProps map
    // lookup on each call.
    val poolScope: PoolScope = componentContext.getTreeProp(PoolScopeTreeProp),
    var subtreeSnapshots: MutableScatterMap<LithoNode, SubtreeSnapshot>? = null,
    var outputParentIndices: MutableIntList? = null,
    // Incremented every time `collectResults` enters the [DeferredLithoLayoutResult] branch
    // (including the unresolved-deferred early-return path — over-counts conservatively). Captured
    // at each subtree-root's entry; the post-recurse delta tells [shouldRecordSubtreeSnapshot]
    // whether this subtree's recording window crossed a deferred. See that function's KDoc for the
    // structural reason such snapshots cannot be spliced. Per-pass state — fresh ReductionState
    // per reduce — so an exception mid-recursion that aborts the pass is not a leak.
    var deferredEnterCount: Int = 0,
    // How many `Layout`s `collectResults` is inside. Under one, a subtree is never spliced: a
    // splice replays only Spec components' state reads, and a `Layout`'s children carry their own.
    var customLayoutDepth: Int = 0,
)

/**
 * Read-only and lighter version of [ReductionState] used by [LayoutState].
 *
 * This class also strips off heavy and unneeded props from ReductionState to avoid unnecessary
 * memory consumption.
 */
internal class ReductionStateLite(
    val sizeConstraints: SizeConstraints,
    val rootLayoutResult: LayoutResult?,
    val id: Int,
    val previousLayoutStateId: Int,
    val mountableOutputs: List<RenderTreeNode>,
    val visibilityOutputs: List<VisibilityOutput>,
    val testOutputs: List<TestOutput>?,
    val componentScopes: List<ScopedComponentInfo>,
    val componentHandles: ScatterSet<Handle>?,
    val transitionIdMapping: Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>>?,
    val mountableOutputTops: List<IncrementalMountOutput>,
    val mountableOutputBottoms: List<IncrementalMountOutput>,
    val incrementalMountOutputs: LongObjectMap<IncrementalMountOutput>?,
    val incrementalMountOutputsList: List<IncrementalMountOutput>?,
    val renderUnitIdsWhichHostRenderTrees: LongSet?,
    val dynamicValueOutputs: LongObjectMap<DynamicValueOutput>?,
    val animatableItems: MutableLongObjectMap<AnimatableItem>,
    val outputsIdToPositionMap: MutableLongIntMap,
    val width: Int,
    val height: Int,
    val rootNode: LithoNode?,
    val diffTreeRoot: DiffNode?,
    val currentTransitionId: TransitionId?,
    val currentLayoutOutputAffinityGroup: OutputUnitsAffinityGroup<AnimatableItem>?,
    val hasComponentsExcludedFromIncrementalMount: Boolean,
    val attachables: List<Attachable>?,
    val resolveEffects: List<Attachable>?,
    val discardKeys: Set<Any>?,
    val transitionData: TransitionData?,
    val styleTransitionTargets: List<StyleTransitionTarget>?,
    val workingRangeContainer: WorkingRangeContainer?,
    val stateReads: Map<StateId, ScatterSet<GlobalKey>>?,
    val subtreeSnapshots: MutableScatterMap<LithoNode, SubtreeSnapshot>?,
    val outputParentIndices: MutableIntList?,
)

internal fun ReductionState.getOrCreateComponentHandles(): MutableScatterSet<Handle> =
    componentHandles ?: MutableScatterSet<Handle>().also { componentHandles = it }

internal fun ReductionState.getOrCreateTransitionIdMapping():
    MutableMap<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> =
    transitionIdMapping
        ?: LinkedHashMap<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>>().also {
          transitionIdMapping = it
        }

internal fun ReductionState.getOrCreateIncrementalMountOutputs():
    MutableLongObjectMap<IncrementalMountOutput> =
    incrementalMountOutputs
        ?: MutableLongObjectMap<IncrementalMountOutput>(8).also { incrementalMountOutputs = it }

internal fun ReductionState.getOrCreateIncrementalMountOutputsList():
    ArrayList<IncrementalMountOutput> =
    incrementalMountOutputsList
        ?: ArrayList<IncrementalMountOutput>(8).also { incrementalMountOutputsList = it }

internal fun ReductionState.getOrCreateRenderUnitIdsWhichHostRenderTrees(): MutableLongSet =
    renderUnitIdsWhichHostRenderTrees
        ?: MutableLongSet(4).also { renderUnitIdsWhichHostRenderTrees = it }

internal fun ReductionState.getOrCreateDynamicValueOutputs():
    MutableLongObjectMap<DynamicValueOutput> =
    dynamicValueOutputs
        ?: MutableLongObjectMap<DynamicValueOutput>(8).also { dynamicValueOutputs = it }

internal fun ReductionState.getOrCreateStateReads():
    MutableMap<StateId, MutableScatterSet<GlobalKey>> =
    stateReads ?: mutableMapOf<StateId, MutableScatterSet<GlobalKey>>().also { stateReads = it }

internal fun ReductionState.toLite(): ReductionStateLite = ReductionStateLite(
    sizeConstraints = sizeConstraints,
    rootLayoutResult = rootLayoutResult,
    id = id,
    previousLayoutStateId = previousLayoutStateId,
    mountableOutputs = mountableOutputs,
    visibilityOutputs = visibilityOutputs,
    testOutputs = testOutputs,
    componentScopes = componentScopes,
    componentHandles = componentHandles,
    transitionIdMapping = transitionIdMapping,
    mountableOutputTops = mountableOutputTops,
    mountableOutputBottoms = mountableOutputBottoms,
    incrementalMountOutputs = incrementalMountOutputs,
    incrementalMountOutputsList = incrementalMountOutputsList,
    renderUnitIdsWhichHostRenderTrees = renderUnitIdsWhichHostRenderTrees,
    dynamicValueOutputs = dynamicValueOutputs,
    animatableItems = animatableItems,
    outputsIdToPositionMap = outputsIdToPositionMap,
    width = width,
    height = height,
    rootNode = rootNode,
    diffTreeRoot = diffTreeRoot,
    currentTransitionId = currentTransitionId,
    currentLayoutOutputAffinityGroup = currentLayoutOutputAffinityGroup,
    hasComponentsExcludedFromIncrementalMount = hasComponentsExcludedFromIncrementalMount,
    attachables = attachables,
    resolveEffects = resolveEffects,
    discardKeys = discardKeys,
    transitionData = transitionData,
    styleTransitionTargets = styleTransitionTargets,
    workingRangeContainer = workingRangeContainer,
    stateReads = stateReads,
    subtreeSnapshots = subtreeSnapshots,
    outputParentIndices = outputParentIndices,
)
