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
import com.facebook.litho.Resolver.resolveDeferred
import com.facebook.litho.debug.LithoDebugEvent.ComponentRendered
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.debug.DebugEventAttribute.Name
import com.facebook.rendercore.debug.DebugEventDispatcher.trace
import com.facebook.rendercore.incrementalmount.ExcludeFromIncrementalMountBinder
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.Primitive
import com.facebook.rendercore.utils.hasEquivalentFields

/**
 * Base class for Kotlin primitive components. This class encapsulates some of the Mount Spec APIs.
 * All Kotlin primitive components must extend this class.
 */
abstract class PrimitiveComponent : Component() {

  final override fun resolve(
      resolveContext: ResolveContext,
      scopedComponentInfo: ScopedComponentInfo,
      parentWidthSpec: Int,
      parentHeightSpec: Int,
  ): ComponentResolveResult {

    val node = LithoNode()
    var commonProps: CommonProps? = null

    val c = scopedComponentInfo.context
    val renderResult: RenderResult<LithoPrimitive> = trace(
        type = ComponentRendered,
        renderStateId = { resolveContext.treeId.toString() },
        attributesAccumulator = { accumulator ->
          accumulator[Name] = fullyQualifiedName
          accumulator[LithoDebugEventAttributes.ResolveVersion] = resolveContext.layoutVersion
          val parentName = Component.parentNameFromKey(c.nullableGlobalKey)
          if (parentName != null) {
            accumulator["parentComponentName"] = parentName
          }
        },
    ) {
      ComponentsSystrace.trace({ "render:$simpleName" }) {
        scopedComponentInfo.runInRecorderScope(resolveContext) {
          val scope = PrimitiveComponentScope(c)
          val result = scope.withResolveContext(resolveContext) { scope.render() }
          if (scope.shouldExcludeFromIncrementalMount) {
            node.primitiveExcludesFromIncrementalMount = true
            result.primitive.renderUnit.addAttachBinder(
                RenderUnit.DelegateBinder.createDelegateBinder(
                    result.primitive.renderUnit,
                    ExcludeFromIncrementalMountBinder.INSTANCE,
                ),
            )
          }
          RenderResult(
              result,
              scope.transitionData,
              scope.useEffectEntries,
              scope.resolveEffects,
              scope.discardKey,
          )
        }
      }
    }
    val lithoPrimitive = renderResult.value
    node.primitive = lithoPrimitive.primitive
    if (lithoPrimitive.style != null) {
      commonProps = CommonProps()
      lithoPrimitive.style.applyCommonProps(c, commonProps)
    }

    Resolver.applyTransitionsAndUseEffectEntriesToNode(
        renderResult.transitionData,
        renderResult.useEffectEntries,
        node,
        renderResult.resolveEffects,
        renderResult.discardKey,
    )

    return ComponentResolveResult(node, commonProps)
  }

  override fun resolveDeferred(
      calculationContext: CalculationContext,
      componentContext: ComponentContext,
      parentContext: ComponentContext,
  ): ComponentResolveResult {
    return this.resolveDeferred(calculationContext, componentContext, parentContext, null)
  }

  /** This function must return [LithoPrimitive] which are immutable. */
  abstract fun PrimitiveComponentScope.render(): LithoPrimitive

  final override fun getMountType(): MountType = MountType.PRIMITIVE

  final override fun canMeasure(): Boolean = true

  /**
   * Compare this component to a different one to check if they are equivalent. This is used to be
   * able to skip rendering a component again.
   */
  final override fun isEquivalentProps(other: Component?): Boolean {
    if (this === other) {
      return true
    }
    if (other == null || javaClass != other.javaClass) {
      return false
    }
    if (instanceId == other.instanceId) {
      return true
    }
    if (!hasEquivalentFields(this, other)) {
      return false
    }

    return true
  }

  // All other Component lifecycle methods are final and no-op here as they shouldn't be overridden.

  final override fun isEquivalentTo(other: Component?): Boolean = super.isEquivalentTo(other)

  final override fun getSimpleName(): String = super.getSimpleName()

  final override fun isPureRender(): Boolean = true

  final override fun implementsShouldUpdate(): Boolean = true

  final override fun makeShallowCopy(): Component = super.makeShallowCopy()

  final override fun onCreateMountContent(context: Context): Any =
      super.onCreateMountContent(context)

  final override fun shouldUpdate(
      previous: Component,
      prevStateContainer: StateContainer?,
      next: Component,
      nextStateContainer: StateContainer?,
  ): Boolean = super.shouldUpdate(previous, prevStateContainer, next, nextStateContainer)

  final override fun isEqualivalentTreePropContainer(
      current: ComponentContext,
      next: ComponentContext,
  ): Boolean {
    return super.isEqualivalentTreePropContainer(current, next)
  }
}

/**
 * A class that represents a [Primitive] with [Style] that should be applied to the
 * [PrimitiveComponent].
 */
class LithoPrimitive(val primitive: Primitive, val style: Style?) {
  constructor(
      layoutBehavior: LayoutBehavior,
      mountBehavior: MountBehavior<*>,
      style: Style?,
  ) : this(Primitive(layoutBehavior, mountBehavior), style)
}
