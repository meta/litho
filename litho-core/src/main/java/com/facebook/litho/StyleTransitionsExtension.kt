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
import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableScatterMap
import com.facebook.litho.StyleTransitionsExtension.StyleTransitionsExtensionState
import com.facebook.litho.animation.CompositeIntSizeSpec
import com.facebook.litho.animation.FiniteAnimationSpec
import com.facebook.litho.animation.StructuralStyleTransitionId
import com.facebook.litho.animation.StyleTransitionContentResolver
import com.facebook.litho.animation.StyleTransitionManager
import com.facebook.litho.animation.StyleTransitionProperty
import com.facebook.litho.animation.StyleTransitionTarget
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.MountExtension
import com.facebook.rendercore.extensions.OnItemCallbacks
import kotlin.math.roundToInt

internal object StyleTransitionsExtension :
    MountExtension<LayoutState, StyleTransitionsExtensionState>(),
    OnItemCallbacks<StyleTransitionsExtensionState> {

  override fun createState(): StyleTransitionsExtensionState = StyleTransitionsExtensionState()

  override fun beforeMount(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      input: LayoutState?,
      localVisibleRect: Rect?,
  ) {
    val state = extensionState.state
    val lithoTree = input?.resolveResult?.context?.lithoTree
    val previousTree = state.currentInput?.resolveResult?.context?.lithoTree
    // On a tree swap, stop the outgoing tree's reflow promptly — its store lives on that tree.
    if (previousTree !== lithoTree) previousTree?.reflowProvider?.release()
    val previousInput =
        if (input != null && lithoTree != null && previousTree === lithoTree) {
          state.currentInput
        } else {
          null
        }
    state.previousInput = previousInput
    state.currentInput = input
    state.previousTargets = targetsByRenderUnitId(previousInput)
    state.currentTargets = targetsByRenderUnitId(input)
    // The manager decides x/y suppression per node from the committed size deltas, so no tree-wide
    // reflow signal is passed here (see StyleTransitionPlanner).
    state.manager.setup(
        previousInput?.styleTransitionTargets ?: emptyList(),
        input?.styleTransitionTargets ?: emptyList(),
        input?.animatableItems,
    )
    updateReflowAnimations(input, lithoTree)
  }

  /**
   * Reconciles the tree's [ReflowProvider] with the mounted layout in two steps: (1) when reflow
   * nodes enter or leave, one batched [ReflowProvider.rebuildKeySet] (mint missing seeded, drop
   * departed) that lazily mints the store on first use — the preflight skips this when the key set
   * is unchanged, so a steady-state scroll allocates nothing; (2) a per-node leaf reconcile of each
   * captured natural size in [LayoutState.reflowTargetSizes] against the provider's LIVE target,
   * retargeting only on a genuine difference (the authoritative main-thread decision; a background
   * walk may have observed a stale target). A commit where every reflow node was driving its own
   * animation frame carries no captures, so step (2) finds nothing to reconcile.
   */
  private fun updateReflowAnimations(input: LayoutState?, lithoTree: LithoTree?) {
    input ?: return
    val provider = lithoTree?.reflowProvider ?: return

    // Preflight (no allocation, no store creation): count valid reflow keys and whether they match
    // the current generation. isEmpty is true while no store exists, so an opacity- or
    // transform-only transition never mints one.
    var liveCount = 0
    var keySetUnchanged = !provider.isEmpty
    forEachReflowTarget(input) { key, _ ->
      liveCount++
      if (provider.isEmpty || key !in provider) keySetUnchanged = false
    }
    keySetUnchanged = keySetUnchanged && liveCount == provider.size

    if (provider.isEmpty && liveCount == 0) return

    // A released tree can still receive a trailing commit; drop any store and bail. Checked after
    // the early-out above so a tree with no reflow node never pays for the synchronized read.
    if (lithoTree.treeLifecycle.isReleased) {
      provider.release()
      return
    }

    // Structural change → one batched rebuild.
    if (!keySetUnchanged) {
      val liveSeeds = MutableScatterMap<GlobalKey, IntSize>(liveCount)
      forEachReflowTarget(input) { key, target -> liveSeeds[key] = committedSize(target) }
      provider.rebuildKeySet(liveSeeds)
    }

    // Reconcile each captured natural against the live target — a leaf update, no rebuild.
    val naturalTargetSizes = input.reflowTargetSizes ?: return
    forEachReflowTarget(input) { key, target ->
      val natural = naturalTargetSizes[key] ?: return@forEachReflowTarget
      if (natural != provider.targetSizeOf(key)) {
        val spec = boundsSpec(target) ?: return@forEachReflowTarget
        provider.retarget(key, natural, committedSize(target), spec)
      }
    }
  }

  /**
   * Iterates the valid reflow targets (declared `shouldLayout`, keyed, with an animatable item).
   */
  private inline fun forEachReflowTarget(
      input: LayoutState,
      block: (key: GlobalKey, target: StyleTransitionTarget) -> Unit,
  ) {
    for (target in input.styleTransitionTargets) {
      if (!target.data.spec.shouldLayout) continue
      val key =
          (target.styleTransitionId as? StructuralStyleTransitionId)?.nodeKey as? GlobalKey
              ?: continue
      if (target.animatableItem == null) continue
      block(key, target)
    }
  }

  /**
   * This node's key-stable committed size — the previous size the layout capture pinned to, which
   * doubles as the animation start on a captured change. The item is guaranteed present by
   * [forEachReflowTarget].
   */
  private fun committedSize(target: StyleTransitionTarget): IntSize {
    val item = checkNotNull(target.animatableItem)
    return IntSize(
        StyleTransitionProperty.WIDTH.animatedProperty[item].roundToInt(),
        StyleTransitionProperty.HEIGHT.animatedProperty[item].roundToInt(),
    )
  }

  private fun boundsSpec(target: StyleTransitionTarget): FiniteAnimationSpec<IntSize>? {
    val changes = target.data.spec.changes
    val width = changes[StyleTransitionProperty.WIDTH]
    val height = changes[StyleTransitionProperty.HEIGHT]
    if (width == null && height == null) return null
    // Compose the per-axis Float specs into one IntSize animation — each axis runs at its real
    // Float type (a keyframes per axis is valid), with no reinterpretation cast.
    return CompositeIntSizeSpec(width ?: height!!, height ?: width!!)
  }

  override fun afterMount(extensionState: ExtensionState<StyleTransitionsExtensionState>) {
    val state = extensionState.state
    state.manager.run(
        StyleTransitionContentResolver { plan ->
          listOfNotNull(getContentById(extensionState, plan.renderUnitId))
        },
    )
    // Disappearing/mount-locked content can unbind after this pass, once previousInput is cleared;
    // keep the previous targets around so the unbind can still resolve declared properties. Mirrors
    // DynamicPropsExtension's disappearingHostInput handling.
    state.disappearingTargets = state.previousTargets
    state.previousInput = null
    state.previousTargets = null
  }

  override fun onUnmount(extensionState: ExtensionState<StyleTransitionsExtensionState>) {
    val state = extensionState.state
    state.manager.reset()
    state.currentInput?.resolveResult?.context?.lithoTree?.reflowProvider?.release()
    state.currentInput = null
    state.previousInput = null
    state.currentTargets = null
    state.previousTargets = null
    state.disappearingTargets = null
  }

  override fun onUnbindItem(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ) {
    val state = extensionState.state
    val renderUnitId = renderUnit.id
    // A live node keeps its committed properties, including a transition settled before mount.
    if (state.currentInput?.animatableItems?.get(renderUnitId) != null) {
      return
    }
    val previousTarget = state.previousTargets?.get(renderUnitId)
    val disappearingTarget = state.disappearingTargets?.get(renderUnitId)
    if (
        previousTarget == null &&
            disappearingTarget == null &&
            !state.manager.hasRunningAnimationForRenderUnit(renderUnitId)
    ) {
      return
    }
    state.manager.resetContent(renderUnitId, content, previousTarget, disappearingTarget)
  }

  override fun shouldUpdateItem(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      previousRenderUnit: RenderUnit<*>,
      previousLayoutData: Any?,
      nextRenderUnit: RenderUnit<*>,
      nextLayoutData: Any?,
  ): Boolean {
    val state = extensionState.state
    return state.currentTargets?.contains(nextRenderUnit.id) == true ||
        state.previousTargets?.contains(previousRenderUnit.id) == true ||
        state.manager.hasRunningAnimationForRenderUnit(previousRenderUnit.id) ||
        state.manager.hasRunningAnimationForRenderUnit(nextRenderUnit.id)
  }

  override fun beforeMountItem(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      renderTreeNode: RenderTreeNode,
      index: Int,
  ): Unit = Unit

  override fun onMountItem(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ): Unit = Unit

  override fun onBindItem(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ): Unit = Unit

  override fun onUnmountItem(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ): Unit = Unit

  override fun onBoundsAppliedToItem(
      extensionState: ExtensionState<StyleTransitionsExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
      changed: Boolean,
  ): Unit = Unit

  private fun targetsByRenderUnitId(
      layoutState: LayoutState?,
  ): LongObjectMap<StyleTransitionTarget>? {
    val targets = layoutState?.styleTransitionTargets
    if (targets.isNullOrEmpty()) {
      return null
    }
    val map = MutableLongObjectMap<StyleTransitionTarget>(targets.size)
    for (target in targets) {
      val id = target.animatableItem?.getId() ?: continue
      map.put(id, target)
    }
    return map
  }

  internal class StyleTransitionsExtensionState {
    val manager: StyleTransitionManager = StyleTransitionManager()
    var currentInput: LayoutState? = null
    var previousInput: LayoutState? = null
    var currentTargets: LongObjectMap<StyleTransitionTarget>? = null
    var previousTargets: LongObjectMap<StyleTransitionTarget>? = null
    var disappearingTargets: LongObjectMap<StyleTransitionTarget>? = null
  }
}
