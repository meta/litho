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
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.ComponentsSystrace.traceWithArgs
import com.facebook.litho.debug.DebugOverlay
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.debug.LithoDebugEventAttributes.Attribution
import com.facebook.litho.debug.LithoDebugEventAttributes.InitialResolve
import com.facebook.litho.debug.LithoDebugEventAttributes.Reason
import com.facebook.litho.debug.LithoDebugEventAttributes.Root
import com.facebook.litho.state.DirtyScopeIndex
import com.facebook.litho.state.getDirtyScopes
import com.facebook.litho.state.withScope
import com.facebook.litho.stats.LithoStats
import com.facebook.rendercore.debug.DebugEventAttribute.Source
import com.facebook.rendercore.debug.DebugEventAttribute.Version
import com.facebook.rendercore.debug.DebugEventDispatcher

class ResolveTreeFuture(
    private val componentContext: ComponentContext,
    private val component: Component,
    private val treeState: TreeState,
    private val currentResolveResult: ResolveResult?,
    private val resolveVersion: Int,
    useCancellableFutures: Boolean,
    private val componentTreeId: Int,
    private val extraAttribution: String?,
    private val source: Int,
    private val debugBreadcrumb: String? = null,
) : TreeFuture<ResolveResult>(componentTreeId, useCancellableFutures) {

  override fun calculate(): ResolveResult {
    return DebugEventDispatcher.trace(
        LithoDebugEvent.ComponentTreeResolve,
        { componentTreeId.toString() },
        { attributes -> attributes.createDebugAttributes() },
    ) { traceScope ->
      val resolveResult = resolve(
          componentContext,
          component,
          treeState,
          resolveVersion,
          componentTreeId,
          currentResolveResult,
          extraAttribution,
          this,
          discardCallbacks,
      )

      // Add post-resolve attributes to the trace via TraceScope
      addPostResolveAttributes(traceScope, resolveResult)

      resolveResult
    }
  }

  override fun getVersion(): Int = resolveVersion

  override fun getDescription(): String = DESCRIPTION

  override fun resumeCalculation(partialResult: ResolveResult?): ResolveResult {
    return DebugEventDispatcher.trace(
        LithoDebugEvent.ComponentTreeResume,
        { componentTreeId.toString() },
        { attributes -> attributes.createDebugAttributes() },
    ) {
      resume(checkNotNull(partialResult), componentTreeId, extraAttribution)
    }
  }

  private fun MutableMap<String, Any?>.createDebugAttributes() {
    val attributes = this
    attributes[Root] = component.fullyQualifiedName
    attributes[Version] = resolveVersion
    attributes[Source] = LayoutState.layoutSourceToString(source)
    attributes[Attribution] = extraAttribution
    if (debugBreadcrumb != null) {
      attributes[LithoDebugEventAttributes.Breadcrumb] = debugBreadcrumb
    }
    // Enhanced phase logging attributes
    attributes[LithoDebugEventAttributes.ResolveVersion] = resolveVersion
    attributes[InitialResolve] = currentResolveResult == null
    attributes[Reason] =
        when (source) {
          RenderSource.SET_ROOT_SYNC,
          RenderSource.SET_ROOT_ASYNC -> "SET_ROOT"
          RenderSource.UPDATE_STATE_SYNC,
          RenderSource.UPDATE_STATE_ASYNC -> "STATE"
          RenderSource.SET_SIZE_SPEC_SYNC,
          RenderSource.SET_SIZE_SPEC_ASYNC -> "SET_SIZE_SPEC"
          RenderSource.MEASURE_SET_SIZE_SPEC,
          RenderSource.MEASURE_SET_SIZE_SPEC_ASYNC -> "MEASURE"
          else -> "UNKNOWN"
        }
  }

  private fun addPostResolveAttributes(
      traceScope: DebugEventDispatcher.TraceScope?,
      resolveResult: ResolveResult,
  ) {
    if (traceScope == null) return

    // Collect component names for logging (top 5)
    val componentNames = mutableListOf<String>()
    componentNames.add(component.simpleName ?: "Unknown")
    resolveResult.node?.scopedComponentInfos?.take(4)?.forEach { info ->
      info.component.simpleName.let { name -> componentNames.add(name) }
    }

    // globalKeysChanged: true if state update caused key changes
    val globalKeysChanged =
        when (source) {
          RenderSource.UPDATE_STATE_SYNC,
          RenderSource.UPDATE_STATE_ASYNC -> resolveResult.updatedStates.isNotEmpty()
          else -> false
        }

    val componentCount = resolveResult.node?.scopedComponentInfos?.size ?: 0

    traceScope.attribute(LithoDebugEventAttributes.GlobalKeysChanged, globalKeysChanged)
    traceScope.attribute(LithoDebugEventAttributes.ComponentCount, componentCount)
    traceScope.attribute(LithoDebugEventAttributes.Components, componentNames.joinToString(","))
  }

  override fun isEquivalentTo(that: TreeFuture<*>?): Boolean {
    if (that !is ResolveTreeFuture) {
      return false
    }
    if (component.instanceId != that.component.instanceId) {
      return false
    }
    if (componentContext.treePropContainer !== that.componentContext.treePropContainer) {
      return false
    }

    return true
  }

  internal interface ExecutionListener {

    fun onPreExecution(version: Int)

    fun onPostExecution(version: Int, released: Boolean)
  }

  companion object {

    const val DESCRIPTION: String = "resolve"

    /** Function which resolves a new RenderResult. */
    @JvmStatic
    fun resolve(
        context: ComponentContext,
        component: Component,
        state: TreeState,
        version: Int,
        componentTreeId: Int,
        currentResolveResult: ResolveResult?,
        extraAttribution: String?,
        future: TreeFuture<*>?,
        discardCallbacks: DiscardCallbacks? = null,
    ): ResolveResult {
      val updatedStates = state.getStateIdsForPendingStateUpdates()
      val scopesToUpdate =
          DirtyScopeIndex.of(
              getDirtyScopes(updatedStates, currentResolveResult?.outputs?.stateReads),
          )
      val currentAccessibilityEnabled =
          AccessibilityUtils.isAccessibilityEnabled(
              context.androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
                  as AccessibilityManager,
          )
      val willSkipResolve =
          scopesToUpdate.isEmpty() &&
              currentResolveResult != null &&
              Resolver.isReconcilable(
                  context,
                  component,
                  state,
                  currentResolveResult.node,
                  currentAccessibilityEnabled = currentAccessibilityEnabled,
                  previousAccessibilityEnabled = currentResolveResult.isAccessibilityEnabled,
              ) &&
              context.treePropContainer == currentResolveResult.context.treePropContainer

      if (willSkipResolve) {
        state.applyStateUpdatesEarly(context, component, currentResolveResult.node, false)
        state.markAllStateInUse(isLayoutState = false)
        return currentResolveResult.copy(
            treeState = state,
            component = component,
            updatedStates = updatedStates,
            version = version,
            discardCallbacks = null,
        )
      }

      LithoStats.incrementResolveCount()

      return traceWithArgs(
          { "resolveTree:${component.simpleName}" },
          { scope ->
            scope.attribute("treeId", componentTreeId)
            scope.attribute("rootId", component.instanceId)
            if (extraAttribution != null) {
              scope.attribute("extraAttribution", extraAttribution)
            }
          },
      ) {
        val rsc = ResolveContext(
            componentTreeId,
            MeasuredResultCache(),
            state,
            version,
            component.instanceId,
            currentAccessibilityEnabled,
            future,
            currentResolveResult,
            false,
            scopesToUpdate,
            discardCallbacks = discardCallbacks,
        )
        val previousStateContext = context.calculationStateContext
        val stateProvider = context.stateProvider ?: error("State provider is null in resolve")
        val node: LithoNode?
        try {
          context.renderStateContext = rsc
          stateProvider.withScope(state) { node = Resolver.resolveTree(rsc, context, component) }
        } finally {
          context.calculationStateContext = previousStateContext
        }
        val outputs: Resolver.Outputs?
        if (rsc.isResolveInterrupted) {
          outputs = null
        } else {
          outputs = Resolver.collectOutputs(node)
          rsc.cache.freezeCache()
        }
        if (DebugOverlay.isEnabled) {
          DebugOverlay.updateResolveHistory(componentTreeId)
        }
        ResolveResult(
            node = node,
            context = context,
            component = component,
            cache = rsc.cache,
            treeState = state,
            isPartialResult = rsc.isResolveInterrupted,
            version = version,
            outputs = outputs,
            contextForResuming = if (rsc.isResolveInterrupted) rsc else null,
            updatedStates = updatedStates,
            isAccessibilityEnabled = rsc.isAccessibilityEnabled,
            discardCallbacks = discardCallbacks,
        )
      }
    }

    @JvmStatic
    fun resume(
        partialResult: ResolveResult,
        componentTreeId: Int,
        extraAttribution: String?,
    ): ResolveResult {
      LithoStats.incrementResumeCount()
      val context = partialResult.context
      val component = partialResult.component
      val resolveVersion = partialResult.version
      check(partialResult.isPartialResult) { "Cannot resume a non-partial result" }
      checkNotNull(partialResult.node) { "Cannot resume a partial result with a null node" }
      checkNotNull(partialResult.contextForResuming) {
        "RenderStateContext cannot be null during resume"
      }

      return traceWithArgs(
          { "resumeResolveTree:${component.simpleName}" },
          { scope ->
            scope.attribute("treeId", componentTreeId)
            scope.attribute("rootId", component.instanceId)
            if (extraAttribution != null) {
              scope.attribute("extraAttribution", extraAttribution)
            }
          },
      ) {
        val previousStateContext = context.calculationStateContext
        val stateProvider = context.stateProvider ?: error("State provider is null in resolve")
        val node: LithoNode
        try {
          context.renderStateContext = partialResult.contextForResuming
          stateProvider.withScope(partialResult.treeState) {
            node =
                Resolver.resumeResolvingTree(
                    partialResult.contextForResuming,
                    partialResult.node,
                )
          }
        } finally {
          context.calculationStateContext = previousStateContext
        }
        val outputs = Resolver.collectOutputs(node)
        partialResult.contextForResuming.cache.freezeCache()
        ResolveResult(
            node = node,
            context = context,
            component = partialResult.component,
            cache = partialResult.consumeCache(),
            treeState = partialResult.treeState,
            isPartialResult = false,
            version = resolveVersion,
            outputs = outputs,
            contextForResuming = null,
            updatedStates = partialResult.updatedStates,
            isAccessibilityEnabled = partialResult.isAccessibilityEnabled,
            discardCallbacks = partialResult.discardCallbacks,
        )
      }
    }
  }
}
