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

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.facebook.litho.AccessibilityUtils.isAccessibilityEnabled
import com.facebook.litho.ComponentsSystrace.traceWithArgs
import com.facebook.litho.LayoutUtils.measurePendingSubtrees
import com.facebook.litho.LayoutUtils.measureTree
import com.facebook.litho.animation.ReflowGeneration
import com.facebook.litho.animation.isReflowStateId
import com.facebook.litho.debug.DebugOverlay
import com.facebook.litho.debug.DebugOverlay.Companion.updateLayoutHistory
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.debug.LithoDebugEventAttributes.IsFirstLayout
import com.facebook.litho.state.DirtyScopeIndex
import com.facebook.litho.state.StateId
import com.facebook.litho.state.getDirtyScopes
import com.facebook.litho.state.hasDirtyScopes
import com.facebook.litho.state.withScope
import com.facebook.litho.stats.LithoStats
import com.facebook.litho.transition.toMutableData
import com.facebook.rendercore.LayoutCache
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventDispatcher

class LayoutTreeFuture(
    private val resolveResult: ResolveResult,
    private val currentLayoutState: LayoutState?,
    private val diffTreeRoot: DiffNode?,
    private val widthSpec: Int,
    private val heightSpec: Int,
    override val treeId: Int,
    private val treeState: TreeState,
    private val version: Int,
    @RenderSource private val source: Int,
    private val debugBreadcrumb: String? = null,
) : TreeFuture<LayoutState>(treeId, false) {

  override fun getDescription(): String = DESCRIPTION

  override fun getVersion(): Int = version

  override fun calculate(): LayoutState {
    val sizeConstraints = SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec)

    return DebugEventDispatcher.trace(
        type = LithoDebugEvent.ComponentTreeLayout,
        renderStateId = { treeId.toString() },
        attributesAccumulator = { attrs ->
          attrs[LithoDebugEventAttributes.Root] = resolveResult.component.fullyQualifiedName
          attrs[DebugEventAttribute.SizeConstraints] = sizeConstraints.toString()
          attrs[DebugEventAttribute.Version] = version
          attrs[DebugEventAttribute.Source] = LayoutState.layoutSourceToString(source)
          if (debugBreadcrumb != null) {
            attrs[LithoDebugEventAttributes.Breadcrumb] = debugBreadcrumb
          }
          // Enhanced phase logging attributes
          attrs[LithoDebugEventAttributes.LayoutVersion] = version
          attrs[LithoDebugEventAttributes.ResolveVersion] = resolveResult.version
          attrs[IsFirstLayout] = currentLayoutState == null
        },
    ) { traceScope ->
      val layoutState = layout(
          resolveResult,
          sizeConstraints,
          version,
          treeId,
          treeState,
          currentLayoutState,
          diffTreeRoot,
          this,
          discardCallbacks,
      )

      // Add post-layout attributes via TraceScope
      addPostLayoutAttributes(traceScope, layoutState)

      layoutState
    }
  }

  private fun addPostLayoutAttributes(
      traceScope: DebugEventDispatcher.TraceScope?,
      layoutState: LayoutState,
  ) {
    if (traceScope == null) return

    val currentState = currentLayoutState

    // Reused: layout result is identical to previous
    val reused =
        currentState != null &&
            currentState.root == layoutState.root &&
            currentState.sizeConstraints == layoutState.sizeConstraints

    // BoundsChanged: dimensions differ from previous layout
    val boundsChanged =
        currentState != null &&
            (currentState.width != layoutState.width || currentState.height != layoutState.height)

    traceScope.attribute(LithoDebugEventAttributes.Reused, reused)
    traceScope.attribute(LithoDebugEventAttributes.BoundsChanged, boundsChanged)
  }

  override fun resumeCalculation(partialResult: LayoutState?): LayoutState {
    throw UnsupportedOperationException("LayoutTreeFuture cannot be resumed.")
  }

  override fun isEquivalentTo(that: TreeFuture<*>?): Boolean {
    if (that !is LayoutTreeFuture) {
      return false
    }
    return widthSpec == that.widthSpec &&
        heightSpec == that.heightSpec &&
        resolveResult == that.resolveResult
  }

  companion object {
    /** The future's attribution, mirroring `ResolveTreeFuture.DESCRIPTION`. */
    const val DESCRIPTION: String = "layout"

    /**
     * [updatedStates] plus the states a committed resolve newer than [currentLayoutState]'s
     * updated. A resolve whose layout was dropped leaves its updates out of every later resolve's
     * [ResolveResult.updatedStates].
     */
    private fun withUpdatesNotLaidOut(
        updatedStates: Set<StateId>,
        treeState: TreeState,
        currentLayoutState: LayoutState?,
    ): Set<StateId> {
      if (currentLayoutState == null) return updatedStates
      val notLaidOut = treeState.statesUpdatedAfter(currentLayoutState.resolveResult.version)
      return when {
        notLaidOut.isEmpty() -> updatedStates
        updatedStates.isEmpty() -> notLaidOut
        else -> updatedStates + notLaidOut
      }
    }

    /** Function to calculate a new layout. */
    fun layout(
        resolveResult: ResolveResult,
        sizeConstraints: SizeConstraints,
        version: Int,
        treeId: Int,
        treeState: TreeState,
        currentLayoutState: LayoutState?,
        diffTreeRoot: DiffNode?,
        future: TreeFuture<*>?,
        discardCallbacks: DiscardCallbacks? = null,
    ): LayoutState {
      val c = resolveResult.context
      val node = resolveResult.node

      val dirtyStates =
          if (c.isReadTrackingEnabled) {
            withUpdatesNotLaidOut(resolveResult.updatedStates, treeState, currentLayoutState)
          } else {
            emptySet()
          }
      val scopesToUpdate =
          DirtyScopeIndex.of(getDirtyScopes(dirtyStates, currentLayoutState?.stateReads))
      val willSkipLayout =
          c.isReadTrackingEnabled &&
              scopesToUpdate.isEmpty() &&
              currentLayoutState != null &&
              resolveResult.node == currentLayoutState.resolveResult.node &&
              sizeConstraints == currentLayoutState.sizeConstraints

      if (willSkipLayout) {
        treeState.applyStateUpdatesEarly(
            c,
            currentLayoutState.rootComponent,
            currentLayoutState.root,
            true,
        )
        treeState.markAllStateInUse(isLayoutState = true)
        return currentLayoutState.copyWith(resolveResult, treeState, version)
      }

      LithoStats.incrementLayoutCount()

      return traceWithArgs(
          { "layoutTree:${resolveResult.component.simpleName}" },
          { scope ->
            scope.attribute("treeId", treeId)
            scope.attribute("rootId", resolveResult.component.instanceId)
            scope.attribute("sizeConstraints", sizeConstraints)
          },
      ) {
        val renderPhaseCache = resolveResult.consumeCache()
        val layoutCache =
            if (currentLayoutState != null) {
              LayoutCache(currentLayoutState.layoutCacheData)
            } else {
              LayoutCache()
            }
        val lsc = LithoLayoutContext(
            treeId,
            MeasuredResultCache(renderPhaseCache),
            c,
            treeState,
            version,
            resolveResult.component.instanceId,
            isAccessibilityEnabled(
                c.androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as AccessibilityManager,
            ),
            layoutCache,
            diffTreeRoot,
            future,
            scopesToUpdate,
            discardCallbacks,
        )

        // An animation frame = nothing to re-measure (resolve reconciled to the same node under
        // unchanged constraints) and every layout-affecting update is a reflow tick. Constraints
        // must match too: a re-measure (rotation, IME, split screen) reuses the same ResolveResult,
        // and riding the animation would skip the capture that corrects it. One AnimationController
        // frame coalesces every value into one batch, so a reflow tick can arrive alongside an
        // update that moves a natural size; a bind-only value (an alpha fade) has no layout reader
        // and is ignored. Both main and background layouts read the published generation directly
        // (live leaves, no rebuild).
        if (c.isReadTrackingEnabled) {
          val reflowView = c.lithoTree?.reflowProvider?.reflowView ?: ReflowGeneration.EMPTY
          lsc.reflowReadView = reflowView
          if (
              !reflowView.isEmpty &&
                  currentLayoutState != null &&
                  resolveResult.node === currentLayoutState.resolveResult.node &&
                  sizeConstraints == currentLayoutState.sizeConstraints &&
                  !hasDirtyScopes(
                      updatedStates = dirtyStates,
                      stateReads = currentLayoutState.stateReads,
                      shouldSkip = StateId::isReflowStateId,
                  )
          ) {
            lsc.reflowFrameUpdates = resolveResult.updatedStates
          }
        }

        val prevContext = c.calculationStateContext
        val stateProvider = c.stateProvider ?: error("State provider is null in layout")

        try {

          c.setLithoLayoutContext(lsc)
          val layoutState: LayoutState
          stateProvider.withScope(treeState) {
            val root = measureTree(lsc, c.androidContext, node, sizeConstraints)

            val reductionState = ReductionState(
                componentContext = c,
                sizeConstraints = sizeConstraints,
                currentLayoutState = currentLayoutState,
                rootLayoutResult = root,
                rootX = lsc.rootOffset.x,
                rootY = lsc.rootOffset.y,
                attachables = resolveResult.outputs?.let { ArrayList(it.attachables) },
                transitionData = resolveResult.outputs?.transitionData?.toMutableData(),
            )
            if (root != null) {
              try {
                measurePendingSubtrees(
                    parentContext = c,
                    lithoLayoutContext = lsc,
                    reductionState = reductionState,
                    result = root,
                    xInParent = reductionState.rootX,
                    yInParent = reductionState.rootY,
                )
              } catch (e: Exception) {
                throw ComponentUtils.wrapWithMetadata(c, e)
              }
            }

            layoutState =
                LithoReducer.reduce(
                    lsc,
                    resolveResult,
                    version,
                    treeId,
                    treeState,
                    layoutCache,
                    reductionState,
                )
            layoutState.discardCallbacks = discardCallbacks

            root?.releaseLayoutPhaseData()
          }
          layoutState
        } finally {
          c.calculationStateContext = prevContext
          lsc.release()

          LithoStats.incrementComponentCalculateLayoutCount()

          if (ThreadUtils.isMainThread) {
            LithoStats.incrementComponentCalculateLayoutOnUICount()
          }

          if (DebugOverlay.isEnabled) {
            updateLayoutHistory(treeId)
          }
        }
      }
    }
  }
}
