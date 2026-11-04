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

import android.graphics.drawable.Drawable
import android.os.Build
import android.util.SparseArray
import androidx.core.util.isNotEmpty
import androidx.core.view.ViewCompat
import com.facebook.litho.Component.MountType
import com.facebook.litho.DynamicPropsManager.Companion.hasCustomDynamicProps
import com.facebook.litho.MountSpecLithoRenderUnit.UpdateState
import com.facebook.litho.annotations.ImportantForAccessibility
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.drawable.BorderColorDrawable
import com.facebook.litho.host.HostViewAttributesCleanupBinder
import com.facebook.litho.utils.VersionedAndroidApis
import com.facebook.rendercore.BinderKey
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.RenderUnit.DelegateBinder
import com.facebook.rendercore.primitives.Primitive
import com.facebook.rendercore.primitives.PrimitiveRenderUnit
import com.facebook.rendercore.transitions.TransitionUtils

object LithoNodeUtils {

  /**
   * Creates a [LithoRenderUnit] for the content output iff the result mounts content. Its content
   * version is its diff base's when layout found the two equal, else [layoutStateId]. A result
   * copied from the layout cache ([isCachedLayout]) was never compared with its diff base.
   */
  @JvmStatic
  fun createContentRenderUnit(
      node: LithoNode,
      areCachedMeasuresValid: Boolean,
      layoutStateId: Int,
      isCachedLayout: Boolean,
      diffNode: DiffNode? = null,
  ): LithoRenderUnit? {
    val component: Component = node.tailComponent

    // We need to merge dynamic props from all scoped component infos in order to cover cases where
    // a non-SpecGeneratedComponent such as Primitive is wrapped in a Wrapper. If we don't merge
    // then DynamicCommonProps added through the Wrapper will be lost.
    val commonDynamicProps: SparseArray<DynamicValue<*>>? =
        mergeCommonDynamicProps(node.scopedComponentInfos, component.hasCustomDynamicProps())

    if (component.mountType == MountType.NONE) {
      return null
    }

    val componentKey = node.tailComponentKey
    val context: ComponentContext = node.tailComponentContext
    var previousId: Long = -1

    diffNode?.contentOutput?.let { contentOutput -> previousId = contentOutput.id }

    val id: Long = context.calculateLayoutOutputId(componentKey, OutputUnitType.CONTENT)

    val additionalBinders: MutableMap<BinderKey, DelegateBinder<Any, Any, Any>> = HashMap()
    if (node.primitive == null) {
      node.customBindersForMountSpec?.let { additionalBinders.putAll(it) }
    }
    if (node.primitive == null && component.mountType == MountType.VIEW) {
      node.customViewBindersForMountSpec?.let { additionalBinders.putAll(it) }
    }

    @UpdateState
    val updateState =
        if (previousId != id) {
          MountSpecLithoRenderUnit.STATE_UNKNOWN
        } else if (areCachedMeasuresValid) {
          MountSpecLithoRenderUnit.STATE_UPDATED
        } else {
          MountSpecLithoRenderUnit.STATE_DIRTY
        }
    val diffBase = diffNode?.contentOutput as? MountSpecLithoRenderUnit
    return createRenderUnit(
        id = id,
        component = component,
        commonDynamicProps = commonDynamicProps,
        context = context,
        node = node,
        importantForAccessibility = node.importantForAccessibility,
        updateState = updateState,
        contentVersion =
            if (
                updateState == MountSpecLithoRenderUnit.STATE_UPDATED &&
                    !isCachedLayout &&
                    diffBase != null
            ) {
              diffBase.contentVersion
            } else {
              layoutStateId
            },
        duplicateParentState = node.isDuplicateParentStateEnabled,
        hasHostView = node.needsHostView(),
        isMountViewSpec = node.willMountView,
        customDelegateBindersForMountSpec = additionalBinders,
        debugKey = getLithoNodeDebugKey(node, OutputUnitType.CONTENT),
        viewAttributes =
            if (node.needsHostView() || node.primitive != null || !node.willMountView) {
              null
            } else {
              createViewAttributesForBinder(
                  lithoNode = node,
                  component = component,
                  willMountView = true,
                  importantForAccessibility = node.importantForAccessibility,
              )
            },
    )
  }

  /** Creates a [LithoRenderUnit] for the host output iff the result needs a host view. */
  @JvmStatic
  fun createHostRenderUnit(node: LithoNode, layoutStateId: Int): LithoRenderUnit? {
    if (!node.needsHostView()) {
      return null
    }

    val context = node.tailComponentContext
    val hostComponent: HostComponent = HostComponent.create(context)

    // We need to pass common dynamic props to the host component, as they only could be applied to
    // views, so we'll need to set them up, when binding HostComponent to ComponentHost. At the same
    // time, we don't remove them from the current component, as we may calculate multiple
    // LayoutStates using same Components
    val commonDynamicProps: SparseArray<DynamicValue<*>>? =
        mergeCommonDynamicProps(node.scopedComponentInfos)
    hostComponent.commonDynamicProps = commonDynamicProps

    val id: Long = context.calculateLayoutOutputId(node.tailComponentKey, OutputUnitType.HOST)

    val additionalBinders =
        node.customHostViewBinders +
            if (node.needsHostView()) {
              node.customViewBindersForMountSpec
            } else {
              null
            }

    return createRenderUnit(
        id = id,
        component = hostComponent,
        commonDynamicProps = commonDynamicProps,
        context = context,
        node = node,
        importantForAccessibility = node.importantForAccessibility,
        updateState = MountSpecLithoRenderUnit.STATE_UNKNOWN,
        contentVersion = layoutStateId,
        duplicateParentState = node.isHostDuplicateParentStateEnabled,
        duplicateChildrenStates = node.isDuplicateChildrenStatesEnabled,
        isMountViewSpec = true,
        customDelegateBindersForMountSpec = additionalBinders,
        debugKey = getLithoNodeDebugKey(node, OutputUnitType.HOST),
        viewAttributes =
            createViewAttributesForBinder(
                lithoNode = node,
                component = hostComponent,
                willMountView = true,
                importantForAccessibility = node.importantForAccessibility,
            ),
    )
  }

  /** Creates a [LithoRenderUnit] for the root host */
  @JvmStatic
  fun createRootHostRenderUnit(node: LithoNode, layoutStateId: Int): LithoRenderUnit {
    val context = node.tailComponentContext
    val hostComponent: HostComponent = HostComponent.create(context)

    // We need to pass common dynamic props to the host component, as they only could be applied to
    // views, so we'll need to set them up, when binding HostComponent to ComponentHost. At the same
    // time, we don't remove them from the current component, as we may calculate multiple
    // LayoutStates using same Components
    val commonDynamicProps: SparseArray<DynamicValue<*>>? =
        mergeCommonDynamicProps(node.scopedComponentInfos)
    hostComponent.commonDynamicProps = commonDynamicProps

    val additionalBinders =
        node.customHostViewBinders +
            if (node.willMountView) {
              null
            } else {
              node.customViewBindersForMountSpec
            }

    return createRenderUnit(
        id = MountState.ROOT_HOST_ID, // The root host (LithoView) always has ID 0
        component = hostComponent,
        commonDynamicProps = commonDynamicProps,
        context = context,
        node = node,
        importantForAccessibility = node.importantForAccessibility,
        updateState =
            MountSpecLithoRenderUnit.STATE_DIRTY, // set as dirty to skip shouldComponentUpdate()
        contentVersion = layoutStateId,
        duplicateParentState = node.isHostDuplicateParentStateEnabled,
        duplicateChildrenStates = node.isDuplicateChildrenStatesEnabled,
        isMountViewSpec = true,
        customDelegateBindersForMountSpec = additionalBinders,
        debugKey = getLithoNodeDebugKey(node, OutputUnitType.HOST),
        viewAttributes =
            createViewAttributesForBinder(
                lithoNode = node,
                component = hostComponent,
                willMountView = true,
                importantForAccessibility = node.importantForAccessibility,
            ),
    )
  }

  /** Creates a [LithoRenderUnit] for the border output iff the result has borders. */
  @JvmStatic
  fun createBorderRenderUnit(
      node: LithoNode,
      border: BorderColorDrawable,
      width: Int,
      height: Int,
      layoutStateId: Int,
      diffNode: DiffNode? = null,
  ): LithoRenderUnit = createDrawableRenderUnit(
      node,
      border,
      width,
      height,
      OutputUnitType.BORDER,
      layoutStateId,
      diffNode,
  )

  /**
   * Common method to create the [LithoRenderUnit] for backgrounds, foregrounds, and border. The
   * method uses the {@param outputType} to decide between the options. This method will call the
   * shouldUpdate, and [SpecGeneratedComponent.onBoundsDefined] for the [DrawableComponent].
   */
  @JvmStatic
  fun createDrawableRenderUnit(
      node: LithoNode,
      drawable: Drawable,
      width: Int,
      height: Int,
      @OutputUnitType outputType: Int,
      layoutStateId: Int,
      diffNode: DiffNode? = null,
  ): LithoRenderUnit {
    val component: DrawableComponent = DrawableComponent.create(drawable, width, height)
    val context: ComponentContext = node.tailComponentContext
    val componentKey = node.tailComponentKey
    val recycle: LithoRenderUnit? =
        if (diffNode != null) {
          when (outputType) {
            OutputUnitType.BORDER -> diffNode.borderOutput
            OutputUnitType.CONTENT,
            OutputUnitType.HOST ->
                throw IllegalArgumentException("OutputUnitType $outputType not supported")
            else -> throw IllegalArgumentException("OutputUnitType $outputType not supported")
          }
        } else {
          null
        }
    val isCachedOutputUpdated: Boolean =
        if (recycle != null) {
          try {
            !component.shouldComponentUpdate(null, recycle.component, null, component)
          } catch (e: Exception) {
            ComponentUtils.handleWithHierarchy(context, component, e)
            false
          }
        } else {
          false
        }
    val previousId: Long = recycle?.id ?: -1
    val id: Long = context.calculateLayoutOutputId(componentKey, outputType)

    @UpdateState
    val updateState =
        if (previousId != id) {
          MountSpecLithoRenderUnit.STATE_UNKNOWN
        } else if (isCachedOutputUpdated) {
          MountSpecLithoRenderUnit.STATE_UPDATED
        } else {
          MountSpecLithoRenderUnit.STATE_DIRTY
        }
    // updateState comes from comparing with recycle itself, so a border found equal keeps recycle's
    // version.
    val diffBase = recycle as? MountSpecLithoRenderUnit
    return createRenderUnit(
        id = id,
        component = component,
        /* Drawables don't bind dynamic props */
        context = context,
        node = node,
        importantForAccessibility = ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
        updateState = updateState,
        contentVersion =
            if (updateState == MountSpecLithoRenderUnit.STATE_UPDATED && diffBase != null) {
              diffBase.contentVersion
            } else {
              layoutStateId
            },
        duplicateParentState = node.isDuplicateParentStateEnabled,
        hasHostView = node.needsHostView(),
        debugKey = getLithoNodeDebugKey(node, outputType),
        viewAttributes = null,
    )
  }

  /** Generic method to create a [LithoRenderUnit]. */
  @JvmStatic
  fun createRenderUnit(
      id: Long,
      component: Component,
      commonDynamicProps: SparseArray<DynamicValue<*>>? = null,
      context: ComponentContext,
      node: LithoNode,
      importantForAccessibility: Int,
      @UpdateState updateState: Int,
      contentVersion: Int,
      duplicateParentState: Boolean = false,
      duplicateChildrenStates: Boolean = false,
      hasHostView: Boolean = false,
      isMountViewSpec: Boolean = false,
      customDelegateBindersForMountSpec: Map<BinderKey, RenderUnit.DelegateBinder<Any, Any, Any>>? =
          null,
      debugKey: String? = null,
      viewAttributes: ViewAttributes?,
  ): LithoRenderUnit {
    var flags = 0
    val nodeInfo: NodeInfo? = node.nodeInfo

    // View mount specs are able to set their own attributes when they're mounted.
    // Non-view specs (drawable and layout) always transfer their view attributes
    // to their respective hosts.
    // Moreover, if the component mounts a view, then we apply padding to the view itself later on.
    // Otherwise, apply the padding to the bounds of the layout output.
    @Suppress("KotlinConstantConditions")
    val layoutOutputNodeInfo: NodeInfo? =
        if (isMountViewSpec) {
          nodeInfo
        } else {
          if (nodeInfo?.enabledState == NodeInfo.ENABLED_SET_FALSE) {
            flags = flags or LithoRenderUnit.LAYOUT_FLAG_DISABLE_TOUCHABLE
          }
          null
        }
    if (duplicateParentState) {
      flags = flags or LithoRenderUnit.LAYOUT_FLAG_DUPLICATE_PARENT_STATE
    }
    if (duplicateChildrenStates) {
      flags = flags or LithoRenderUnit.LAYOUT_FLAG_DUPLICATE_CHILDREN_STATES
    }
    if (hasHostView) {
      flags = flags or LithoRenderUnit.LAYOUT_FLAG_MATCH_HOST_BOUNDS
    }
    if (nodeInfo?.hasTouchEventHandlers() == true) {
      flags = flags or LithoRenderUnit.LAYOUT_FLAG_HAS_TOUCH_EVENT_HANDLERS
    }

    val primitive: Primitive? = node.primitive
    // TODO(fabiocarballo): Remove with legacy Text; see LAYOUT_FLAG_NEEDS_HOST_VIEW_AS_DRAWABLE.
    if (
        primitive != null && node.willMountView && LithoNode.needsHostViewAsAccessibleDrawable(node)
    ) {
      flags = flags or LithoRenderUnit.LAYOUT_FLAG_NEEDS_HOST_VIEW_AS_DRAWABLE
    }
    if (primitive != null && Component.isPrimitive(component)) {
      @Suppress("UNCHECKED_CAST")
      return PrimitiveLithoRenderUnit.create(
          component,
          commonDynamicProps as SparseArray<DynamicValue<Any?>>?,
          context,
          layoutOutputNodeInfo,
          flags,
          importantForAccessibility,
          primitive.renderUnit as PrimitiveRenderUnit<Any>,
          debugKey,
          node.primitiveExcludesFromIncrementalMount,
      )
    }
    @Suppress("UNCHECKED_CAST")
    val renderUnit: LithoRenderUnit =
        MountSpecLithoRenderUnit.create(
            id,
            component,
            commonDynamicProps as SparseArray<DynamicValue<Any?>>?,
            context,
            layoutOutputNodeInfo,
            flags,
            importantForAccessibility,
            updateState,
            contentVersion,
            debugKey,
        )

    if (customDelegateBindersForMountSpec != null) {
      for (binder in customDelegateBindersForMountSpec.values) {
        renderUnit.addOptionalMountBinder(binder)
      }
    }

    val config = node.headComponentContext.lithoConfiguration.componentsConfig

    if (viewAttributes != null) {
      renderUnit.addOptionalMountBinder(
          ViewAttributesViewBinder.create(
              ViewAttributesViewBinder.Model(
                  nodeInfo = renderUnit.nodeInfo,
                  viewAttributes = viewAttributes,
                  isRootHost = id == MountState.ROOT_HOST_ID,
              ),
          ),
      )
    }

    if (config.isHostViewAttributesCleanUpEnabled && component is HostComponent) {
      renderUnit.addOptionalMountBinder(
          RenderUnit.DelegateBinder.createDelegateBinder(
              model = null,
              binder = HostViewAttributesCleanupBinder(),
          ) as RenderUnit.DelegateBinder<Any?, Any, Any>,
      )
    }

    return renderUnit
  }

  internal fun mergeCommonDynamicProps(
      infos: List<ScopedComponentInfo>,
      hasCustomDynamicProps: Boolean = false,
  ): SparseArray<DynamicValue<*>>? {
    val mergedDynamicProps = SparseArray<DynamicValue<*>>()
    for (info in infos) {
      val commonProps: CommonProps? = info.commonProps
      val commonDynamicProps: SparseArray<DynamicValue<*>> =
          commonProps?.commonDynamicProps ?: continue
      for (i in 0 until commonDynamicProps.size()) {
        val key: Int = commonDynamicProps.keyAt(i)
        commonDynamicProps[key]?.let { commonDynamicProp ->
          mergedDynamicProps.append(key, commonDynamicProp)
        }
      }
    }
    return if (mergedDynamicProps.isNotEmpty() || hasCustomDynamicProps) {
      // In order to bind custom dynamic value we have to provide an empty container to do
      // the mappings for DynamicPropsExtension
      mergedDynamicProps
    } else {
      // Return null to avoid allocating an empty set of dynamic values
      null
    }
  }

  private fun getLithoNodeDebugKey(node: LithoNode, @OutputUnitType outputUnitType: Int): String? =
      // Guard before stringifying: node.tailComponentKey.toString() would otherwise be evaluated
      // eagerly as a call argument even though getDebugKey() returns null when debug mode is off.
      // With structural GlobalKeys, toString() recursively rebuilds the full ancestor-path string,
      // so this would allocate a large transient string per render unit on every layout reduce.
      if (!LithoDebugConfigurations.isDebugModeEnabled) {
        null
      } else {
        getDebugKey(node.tailComponentKey.toString(), outputUnitType)
      }

  @JvmStatic
  fun getDebugKey(componentKey: String?, @OutputUnitType outputUnitType: Int): String? =
      if (!LithoDebugConfigurations.isDebugModeEnabled) {
        null
      } else {
        when (outputUnitType) {
          OutputUnitType.BACKGROUND -> "$componentKey\$background"
          OutputUnitType.BORDER -> "$componentKey\$border"
          OutputUnitType.FOREGROUND -> "$componentKey\$foreground"
          OutputUnitType.CONTENT -> componentKey
          OutputUnitType.HOST -> "$componentKey\$host"
          else -> null
        }
      }

  @JvmStatic
  fun createTransitionId(node: LithoNode?): TransitionId? =
      if (node == null) {
        null
      } else {
        TransitionUtils.createTransitionId(
            node.transitionKey,
            node.transitionKeyType,
            node.transitionOwnerKey,
            node.transitionGlobalKey,
        )
      }

  /**
   * This is used to determine the set of [ViewAttributes] that are to be used in the scope of the
   * ViewAttributesViewBinder.
   */
  @JvmStatic
  internal fun createViewAttributesForBinder(
      lithoNode: LithoNode,
      component: Component,
      willMountView: Boolean,
      @ImportantForAccessibility importantForAccessibility: Int,
  ): ViewAttributes? {
    val nodeInfo = lithoNode.nodeInfo
    if (nodeInfo == null && !willMountView) {
      return null
    }

    val attrs = ViewAttributes()
    attrs.isHostSpec = Component.isHostSpec(component)
    attrs.componentName = component.simpleName
    attrs.importantForAccessibility = importantForAccessibility
    nodeInfo?.copyInto(attrs)

    attrs.background = lithoNode.background
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      VersionedAndroidApis.M.setForeground(attrs, lithoNode.foreground)
    }
    attrs.layoutDirection = lithoNode.layoutDirection
    attrs.layerType = lithoNode.layerType
    attrs.layoutPaint = lithoNode.layerPaint
    if (lithoNode.hasStateListAnimatorResSet()) {
      attrs.stateListAnimatorRes = lithoNode.stateListAnimatorRes
    } else {
      attrs.stateListAnimator = lithoNode.stateListAnimator
    }

    attrs.systemGestureExclusionZones = lithoNode.systemGestureExclusionZones

    return attrs
  }
}
