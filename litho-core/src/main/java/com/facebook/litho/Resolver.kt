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

import androidx.annotation.IntDef
import androidx.annotation.VisibleForTesting
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.mutableScatterSetOf
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.layout.LayoutDirection
import com.facebook.litho.state.DirtyScopeIndex
import com.facebook.litho.state.StateId
import com.facebook.litho.transition.MutableTransitionData
import com.facebook.litho.transition.TransitionData
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventDispatcher.trace
import com.facebook.rendercore.utils.MeasureSpecUtils
import kotlin.math.max

object Resolver {

  private val MEASURE_SPEC_UNSPECIFIED: Int = MeasureSpecUtils.unspecified()

  @JvmStatic
  fun resolveTree(
      resolveContext: ResolveContext,
      c: ComponentContext,
      component: Component,
  ): LithoNode? {
    val currentResult = resolveContext.currentResolveResult
    val current: LithoNode? = currentResult?.node
    val treeState = resolveContext.treeState
    val isReconcilable: Boolean =
        current != null &&
            isReconcilable(
                c,
                component,
                treeState,
                current,
                currentAccessibilityEnabled = resolveContext.isAccessibilityEnabled,
                previousAccessibilityEnabled = currentResult.isAccessibilityEnabled,
            )

    try {
      treeState.applyStateUpdatesEarly(c, component, current, false)
    } catch (ex: Exception) {
      ComponentUtils.handleWithHierarchy(c, component, ex)
      return null
    }

    if (!isReconcilable) {
      val node = resolve(resolveContext, c, component)
      if (node == null) {
        resolveContext.markLayoutUninterruptible()
        return null
      }
      if (resolveContext.isResolveInterrupted) {
        return node
      }
      node.applyParentDependentCommonProps(
          resolveContext,
          LayoutDirection.fromContext(c.androidContext),
      )
      resolveContext.markLayoutUninterruptible()
      return node
    }

    val globalKeyToReuse: GlobalKey = checkNotNull(current).headComponentContext.globalKey
    return reconcile(resolveContext, c, current, component, globalKeyToReuse)
  }

  @JvmStatic
  fun resolve(
      resolveContext: ResolveContext,
      parent: ComponentContext,
      component: Component,
  ): LithoNode? = resolveImpl(
      resolveContext = resolveContext,
      parent = parent,
      parentWidthSpec = MEASURE_SPEC_UNSPECIFIED,
      parentHeightSpec = MEASURE_SPEC_UNSPECIFIED,
      component = component,
  )

  @JvmStatic
  fun resolveWithGlobalKey(
      resolveContext: ResolveContext,
      parent: ComponentContext,
      component: Component,
      globalKeyToReuse: GlobalKey?,
  ): LithoNode? {
    val prev = parent.renderStateContext
    parent.renderStateContext = resolveContext
    try {
      return resolveImpl(
          resolveContext = resolveContext,
          parent = parent,
          parentWidthSpec = MEASURE_SPEC_UNSPECIFIED,
          parentHeightSpec = MEASURE_SPEC_UNSPECIFIED,
          component = component,
          globalKeyToReuse = globalKeyToReuse,
      )
    } finally {
      parent.renderStateContext = prev
    }
  }

  @JvmStatic
  fun resolveImpl(
      resolveContext: ResolveContext,
      parent: ComponentContext,
      parentWidthSpec: Int,
      parentHeightSpec: Int,
      component: Component,
      resolveDeferredNode: Boolean = false,
      globalKeyToReuse: GlobalKey? = null,
      treePropsToReuse: TreePropContainer? = null,
  ): LithoNode? {

    return ComponentsSystrace.trace({ "resolve:${component.simpleName}" }) {
      val lithoNode = trace(
          LithoDebugEvent.ComponentResolved,
          { resolveContext.treeId.toString() },
          { attributes -> attributes[DebugEventAttribute.Name] = component.fullyQualifiedName },
      ) {
        val isDeferredNode: Boolean = Component.willDeferResolution(component)
        val hasCachedNode: Boolean = Component.hasCachedNode(resolveContext, component)
        val commonProps: CommonProps?

        // 1. Consume the layout created in `willrender`.
        val cached = component.consumeLayoutCreatedInWillRender(resolveContext, parent)

        // 2. Return immediately if cached layout is available.
        if (cached != null) {
          return@trace cached
        }

        ComponentsSystrace.trace({ "resolve.createNode:${component.simpleName}" }) {
          val c: ComponentContext
          val scopedComponentInfo: ScopedComponentInfo
          val node: LithoNode
          val shouldDeferResolution = (isDeferredNode || hasCachedNode) && !resolveDeferredNode

          try {
            // 5. Get or create the scoped context component.
            c =
                if (hasCachedNode) {
                  val cache: MeasuredResultCache = resolveContext.cache
                  checkNotNull(cache.getCachedNode(component)).headComponentContext
                } else {
                  createScopedContext(
                      resolveContext,
                      parent,
                      component,
                      globalKeyToReuse,
                      treePropsToReuse,
                  )
                }
            scopedComponentInfo = c.scopedComponentInfo

            // 6. Resolve the component into an InternalNode tree.
            val resolveResult: ComponentResolveResult =
                if (shouldDeferResolution) {
                  component.resolveDeferred(resolveContext, c, parent)
                } else {
                  component.resolve(
                      resolveContext,
                      scopedComponentInfo,
                      parentWidthSpec,
                      parentHeightSpec,
                  )
                }

            val n = resolveResult.lithoNode
            commonProps = resolveResult.commonProps

            // 7. If the layout is null then return immediately.
            if (n == null) {
              return@trace null
            }

            node = n
          } catch (e: Exception) {
            ComponentUtils.handleWithHierarchy(parent, component, e)
            return@trace null
          }

          ComponentsSystrace.trace({ "resolve.afterCreateNode:${component.simpleName}" }) {
            // 8. Set the measure function
            // Set measure func on the root node of the generated tree so that the mount calls
            // use
            // those (see Controller.mountNodeTree()). Handle the case where the component
            // simply
            // delegates its layout creation to another component, i.e. the root node belongs
            // to
            // another component.
            if (node.componentCount == 0) {
              val isMountSpecWithMeasure =
                  component.canMeasure() && Component.isMountSpec(component)
              if (isMountSpecWithMeasure || shouldDeferResolution) {
                node.setMeasureFunction(Component.sMeasureFunction)
                node.setMinContentMeasureFunction(Component.sMinContentMeasureFunction)
              }
            }

            // 9. Add the component to the InternalNode.
            scopedComponentInfo.commonProps = commonProps
            node.appendComponent(scopedComponentInfo)

            // 10. Copy the common props
            //    Skip if resolving a component that renders with constraints because
            //    common props were copied in the previous layout pass.
            val specComponent = component as? SpecGeneratedComponent
            if (node !is NullNode) { // only if NOT a NullNode
              val props = commonProps
              if (props != null && !(isDeferredNode && resolveDeferredNode)) {
                props.copyInto(c, node)
              }
            }

            // 11. Create and add transition to this component's InternalNode.
            if (c.areTransitionsEnabled()) {
              if (specComponent != null && specComponent.needsPreviousRenderData()) {
                node.addComponentNeedingPreviousRenderData(scopedComponentInfo)
              } else {
                try {
                  val transition = specComponent?.createTransition(c)
                  if (transition != null) {
                    node.addTransition(transition)
                  }
                } catch (e: Exception) {
                  ComponentUtils.handleWithHierarchy(parent, component, e)
                }
              }
            }

            // 12. Add attachable components
            if (specComponent != null && specComponent.hasAttachDetachCallback()) {
              node.addAttachable(
                  LayoutSpecAttachable(c.globalKey, specComponent, scopedComponentInfo),
              )
            }

            // 13. Add working ranges to the InternalNode.
            scopedComponentInfo.addWorkingRangeToNode(node)

            /* 14. Add custom binders - the custom binders should be added to the RenderUnit
            as soon as they are created. For Primitives, this happens during "prepare".
            However, for MountSpecs the common props are only initialized later, and some
            needed LithoNode for tail components is also filled later, and this is why we
            are moving the addition a bit below. */
            val props = commonProps
            if (props != null) {
              node.addViewCustomBinders(props.viewBinders)
              node.addCustomBinders(props.mountBinders)
              node.addHostViewCustomBinder(props.hostViewBinders)
            }
          }
          node
        }
      }

      lithoNode
    }
  }

  @JvmStatic
  fun resumeResolvingTree(resolveContext: ResolveContext, root: LithoNode): LithoNode {
    root.unresolvedComponents?.let { unresolved ->
      val context = root.tailComponentContext
      for (component in unresolved) {
        root.child(resolveContext, context, component)
      }
      unresolved.clear()
    }

    for (i in 0 until root.childCount) {
      resumeResolvingTree(resolveContext, root.getChildAt(i))
    }

    root.applyParentDependentCommonProps(
        context = resolveContext,
        parentLayoutDirection =
            LayoutDirection.fromContext(root.headComponentContext.androidContext),
    )

    return root
  }

  @JvmStatic
  fun createScopedContext(
      resolveContext: ResolveContext,
      parent: ComponentContext,
      component: Component,
      globalKeyToReuse: GlobalKey? = null,
      treePropsToReuse: TreePropContainer? = null,
  ): ComponentContext {

    val globalKey: GlobalKey =
        globalKeyToReuse
            ?: ComponentKeyUtils.generateGlobalKey(parent, parent.componentScope, component)
    val c: ComponentContext = ComponentContext.withComponentScope(parent, component, globalKey)

    // Set latest state and the TreeProps which will be passed to the descendants of the component.
    if (component is SpecGeneratedComponent) {
      if (component.hasState()) {
        c.scopedComponentInfo.state =
            resolveContext.treeState.createOrGetState(c, component, globalKey)
      }

      // Note: state must be set (via ScopedComponentInfo.setStateContainer) before invoking
      // getTreePropsForChildren as @OnCreateTreeProps can depend on @State
      val ancestor: TreePropContainer? = parent.treePropContainer
      c.parentTreePropContainer = ancestor
      val descendants = treePropsToReuse ?: component.getTreePropContainerForChildren(c, ancestor)
      c.treePropContainer = descendants
    }

    if (LithoDebugConfigurations.isDebugModeEnabled) {
      DebugComponent.applyOverrides(c, component, c.globalKey)
    }
    return c
  }

  @JvmStatic
  @JvmOverloads
  @JvmName("applyTransitionsAndUseEffectEntriesToNode")
  internal fun applyTransitionsAndUseEffectEntriesToNode(
      transitionData: TransitionData? = null,
      useEffectEntries: List<Attachable>? = null,
      node: LithoNode,
      resolveEffects: List<Attachable>? = null,
      discardKey: Any? = null,
  ) {
    if (transitionData != null) {
      node.addTransitionData(transitionData)
    }
    if (useEffectEntries != null) {
      for (attachable in useEffectEntries) {
        node.addAttachable(attachable)
      }
    }
    if (resolveEffects != null) {
      for (effect in resolveEffects) {
        node.addResolveEffect(effect)
      }
    }
    if (discardKey != null) {
      node.addDiscardKey(discardKey)
    }
  }

  @JvmStatic
  fun isReconcilable(
      c: ComponentContext,
      nextRootComponent: Component,
      treeState: TreeState,
      currentLayoutResult: LithoNode? = null,
      currentAccessibilityEnabled: Boolean = false,
      previousAccessibilityEnabled: Boolean? = null,
  ): Boolean {

    if (currentLayoutResult == null) {
      return false
    }

    if (!treeState.hasUncommittedUpdates()) {
      return false
    }

    // If the accessibility state has changed since the current layout was resolved,
    // we cannot reconcile because needsHostView decisions depend on isAccessibilityEnabled.
    // Reusing nodes resolved with a different accessibility state would produce a tree
    // with incorrect host view assignments.
    if (
        previousAccessibilityEnabled != null &&
            previousAccessibilityEnabled != currentAccessibilityEnabled
    ) {
      return false
    }

    val currentRootComponent: Component = currentLayoutResult.headComponent
    if (nextRootComponent.key != currentRootComponent.key) {
      return false
    }

    return if (!ComponentUtils.isSameComponentType(currentRootComponent, nextRootComponent)) {
      false
    } else {
      ComponentUtils.isEquivalent(currentRootComponent, nextRootComponent)
    }
  }

  @JvmStatic
  fun collectOutputs(node: LithoNode? = null): Outputs? {
    if (node == null) {
      return null
    }
    val collectedAttachables: MutableList<Attachable> = ArrayList()
    val collectedTransitionData = MutableTransitionData()
    val collectedStateReads = mutableMapOf<StateId, MutableScatterSet<GlobalKey>>()
    val collectedResolveEffects: MutableList<Attachable> = ArrayList()
    val collectedDiscardKeys: MutableSet<Any> = HashSet()
    collectOutputs(
        node,
        collectedAttachables,
        collectedTransitionData,
        collectedStateReads,
        collectedResolveEffects,
        collectedDiscardKeys,
    )
    return if (
        collectedAttachables.isEmpty() &&
            collectedTransitionData.isEmpty() &&
            collectedStateReads.isEmpty() &&
            collectedResolveEffects.isEmpty() &&
            collectedDiscardKeys.isEmpty()
    ) {
      null
    } else {
      Outputs(
          collectedAttachables,
          collectedTransitionData,
          collectedStateReads,
          collectedResolveEffects,
          collectedDiscardKeys,
      )
    }
  }

  private fun collectOutputs(
      node: LithoNode,
      collectedAttachables: MutableList<Attachable>,
      collectedTransitionData: MutableTransitionData,
      collectedStateReads: MutableMap<StateId, MutableScatterSet<GlobalKey>>,
      collectedResolveEffects: MutableList<Attachable>,
      collectedDiscardKeys: MutableSet<Any>,
  ) {

    // TODO(T143986616): optimise traversal for reused nodes
    for (i in 0 until node.childCount) {
      collectOutputs(
          node.getChildAt(i),
          collectedAttachables,
          collectedTransitionData,
          collectedStateReads,
          collectedResolveEffects,
          collectedDiscardKeys,
      )
    }

    // We'll deduplicate attachables in the EffectHandler.
    node.attachables?.let { attachables -> collectedAttachables.addAll(attachables) }
    // And resolve effects in CommittedEffects.
    node.resolveEffects?.let { effects -> collectedResolveEffects.addAll(effects) }
    node.discardKeys?.let { keys -> collectedDiscardKeys.addAll(keys) }

    val c: ComponentContext = node.tailComponentContext
    if (c.areTransitionsEnabled() && node !is DeferredLithoNode) {
      // collect transitions
      node.transitionData?.let { transitionData -> collectedTransitionData.add(transitionData) }
    }
    forEachStateReadOn(node) { s, reader ->
      collectedStateReads.getOrPut(s) { mutableScatterSetOf() }.add(reader)
    }
  }

  /** Runs [action] on each state a component of [node] read while it rendered, with its reader. */
  internal inline fun forEachStateReadOn(node: LithoNode, action: (StateId, GlobalKey) -> Unit) {
    for (info in node.scopedComponentInfos) {
      info.stateReads?.forEach { s -> action(s, info.context.globalKey) }
    }
  }

  private fun reconcile(
      resolveContext: ResolveContext,
      c: ComponentContext,
      node: LithoNode,
      next: Component,
      nextKey: GlobalKey,
  ): LithoNode? {
    return reconcile(
        resolveContext = resolveContext,
        parentContext = c,
        current = node,
        next = next,
        nextKey = nextKey,
    )
  }

  /**
   * Internal method to **try** and reconcile the {@param current} LithoNode with a new
   * [ComponentContext] and an updated head [Component].
   *
   * @param resolveContext The RenderStateContext.
   * @param parentContext The ComponentContext.
   * @param current The current LithoNode which should be updated.
   * @param next The updated component to be used to reconcile this LithoNode.
   * @return A new updated LithoNode.
   */
  private fun reconcile(
      resolveContext: ResolveContext,
      parentContext: ComponentContext,
      current: LithoNode,
      next: Component,
      nextKey: GlobalKey,
      parent: LithoNode? = null,
  ): LithoNode? {
    val mode: Int = getReconciliationMode(current, resolveContext.scopesToUpdate)
    val layout: LithoNode?
    ComponentsSystrace.traceWithArgs(
        { "resolve.reconcile:${next.simpleName}" },
        { scope ->
          scope.attribute(
              "mode",
              when (mode) {
                ReconciliationMode.REUSE -> "reuse"
                ReconciliationMode.RECONCILE -> "reconcile"
                ReconciliationMode.RECREATE -> "recreate"
                else -> "unknown"
              },
          )
        },
    ) {
      when (mode) {
        ReconciliationMode.REUSE -> {
          commitToLayoutStateRecursively(resolveContext.treeState, current)
          layout = current
        }
        ReconciliationMode.RECONCILE -> {
          layout = reconcile(resolveContext, current, next)
        }
        ReconciliationMode.RECREATE -> {
          layout = resolveWithGlobalKey(resolveContext, parentContext, next, nextKey)
          if (layout != null) {
            if (parent == null) {
              layout.applyParentDependentCommonProps(
                  context = resolveContext,
                  parentLayoutDirection = LayoutDirection.fromContext(parentContext.androidContext),
              )
            } else {
              layout.applyParentDependentCommonProps(
                  context = resolveContext,
                  parentLayoutDirection = parent.layoutDirection,
                  parentImportantForAccessibility = parent.importantForAccessibility,
                  parentEnabledState = parent.nodeInfo?.enabledState ?: NodeInfo.ENABLED_UNSET,
                  parentDuplicatesParentState = parent.isDuplicateParentStateEnabled,
              )
            }
          }
        }
        else -> throw IllegalArgumentException("$mode is not a valid ReconciliationMode")
      }
    }
    return layout
  }

  /**
   * Internal method to reconcile the {@param current} LithoNode with a new [ComponentContext] and
   * an updated head [Component] and a [ReconciliationMode].
   *
   * @param current The current LithoNode which should be updated.
   * @param next The updated component to be used to reconcile this LithoNode.
   * @return A new updated LithoNode.
   */
  private fun reconcile(
      resolveContext: ResolveContext,
      current: LithoNode,
      next: Component,
  ): LithoNode {
    return ComponentsSystrace.trace({ "reconcile:${next.simpleName}" }) {
      // 2. Shallow copy this layout.
      val layout: LithoNode = current.clone()
      layout.children = ArrayList(current.childCount)
      commitToLayoutState(resolveContext.treeState, current)

      // 3. Iterate over children.
      val parentContext: ComponentContext = layout.tailComponentContext
      val count = current.childCount
      for (i in 0 until count) {
        val child: LithoNode = current.getChildAt(i)

        // 3.1 Get the head component of the child layout.
        val index = max(0, child.componentCount - 1)
        val component: Component = child.getComponentAt(index)
        val key: GlobalKey = child.getComponentContextAt(index).globalKey

        // 3.2 Reconcile child layout.
        val copy: LithoNode? =
            reconcile(resolveContext, parentContext, child, component, key, current)

        // 3.3 Add the child to the cloned yoga node
        layout.child(copy)
      }

      layout
    }
  }

  /** Notify TreeState to keep state containers for the case where we reuse resolve results. */
  @JvmStatic
  fun commitToLayoutStateRecursively(treeState: TreeState, node: LithoNode) {
    val count: Int = node.childCount
    commitToLayoutState(treeState, node)
    for (i in 0 until count) {
      commitToLayoutStateRecursively(treeState, node.getChildAt(i))
    }
  }

  @JvmStatic
  fun commitToLayoutState(treeState: TreeState, node: LithoNode) {
    val scopedComponentInfos: List<ScopedComponentInfo> = node.scopedComponentInfos
    for (info in scopedComponentInfos) {
      info.commitToLayoutState(treeState)
    }
  }

  /**
   * Returns the a [ReconciliationMode] mode which directs the reconciling process to branch to
   * either recreate the entire subtree, copy the entire subtree or continue to recursively
   * reconcile the subtree.
   */
  @JvmStatic
  @VisibleForTesting
  @ReconciliationMode
  fun getReconciliationMode(
      current: LithoNode,
      scopesToUpdate: DirtyScopeIndex,
  ): Int {
    val components = current.scopedComponentInfos

    // 1.0 check early exit conditions
    if (current is DeferredLithoNode) {
      return ReconciliationMode.RECREATE
    }

    // 1.1 Check if any component has mutations
    for (element in components) {
      val key = element.context.nullableGlobalKey
      if (key != null && key in scopesToUpdate) {
        return ReconciliationMode.RECREATE
      }
    }

    // 2.0 Check if any descendants have mutations
    val rootKey = current.headComponentKey
    return if (scopesToUpdate.hasDescendant(rootKey)) {
      ReconciliationMode.RECONCILE
    } else {
      ReconciliationMode.REUSE
    }
  }

  @IntDef(ReconciliationMode.REUSE, ReconciliationMode.RECONCILE, ReconciliationMode.RECREATE)
  internal annotation class ReconciliationMode {
    companion object {
      const val RECONCILE: Int = 1
      const val RECREATE: Int = 2
      const val REUSE: Int = 3
    }
  }

  class Outputs
  internal constructor(
      @JvmField val attachables: List<Attachable>,
      @JvmField internal val transitionData: TransitionData,
      @JvmField internal val stateReads: Map<StateId, ScatterSet<GlobalKey>>,
      @JvmField internal val resolveEffects: List<Attachable> = emptyList(),
      /** The keys of the [useOnDiscard] callbacks of the renders whose output this is. */
      @JvmField internal val discardKeys: Set<Any> = emptySet(),
  )

  fun Component.resolveDeferred(
      calculationContext: CalculationContext,
      componentContext: ComponentContext,
      parentContext: ComponentContext,
      commonProps: CommonProps?,
  ): ComponentResolveResult {
    val node = DeferredLithoNode(
        componentContext.treePropContainer,
        calculationContext.cache.getCachedNode(this),
        parentContext,
    )

    return ComponentResolveResult(node, commonProps)
  }
}
