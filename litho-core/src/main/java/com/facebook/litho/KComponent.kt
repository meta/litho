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
import com.facebook.rendercore.debug.DebugEventAttribute.Name
import com.facebook.rendercore.debug.DebugEventDispatcher.trace
import com.facebook.rendercore.utils.hasEquivalentFields

/** Base class for Kotlin Components. */
abstract class KComponent : Component() {
  companion object {

    /** Method that will ensure the KComponent class is loaded. */
    @JvmStatic
    fun preload(): KComponent =
        object : KComponent() {
          override fun ComponentScope.render(): Component? = null
        }
  }

  final override fun resolve(
      resolveContext: ResolveContext,
      scopedComponentInfo: ScopedComponentInfo,
      parentWidthSpec: Int,
      parentHeightSpec: Int,
  ): ComponentResolveResult {

    val c: ComponentContext = scopedComponentInfo.context
    val renderResult: RenderResult<Component?> = trace(
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
          val scope = ComponentScope(c)
          val result = scope.withResolveContext(resolveContext) { render() }
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

    val root = renderResult.value
    val node: LithoNode? =
        if (root != null) {
          Resolver.resolve(resolveContext, c, root)
        } else {
          NullNode()
        }

    if (node != null) {
      Resolver.applyTransitionsAndUseEffectEntriesToNode(
          renderResult.transitionData,
          renderResult.useEffectEntries,
          node,
          renderResult.resolveEffects,
          renderResult.discardKey,
      )
    }

    // KComponent doesn't itself hold any CommonProps so return null
    return ComponentResolveResult(node, null)
  }

  override fun resolveDeferred(
      calculationContext: CalculationContext,
      componentContext: ComponentContext,
      parentContext: ComponentContext,
  ): ComponentResolveResult {
    return this.resolveDeferred(calculationContext, componentContext, parentContext, null)
  }

  abstract fun ComponentScope.render(): Component?

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

  // All other Component lifecycle methods are made final and no-op here as they shouldn't be
  // overridden.

  final override fun isEquivalentTo(other: Component?): Boolean = super.isEquivalentTo(other)

  final override fun canMeasure(): Boolean = false

  final override fun getMountType(): MountType = super.getMountType()

  final override fun getSimpleName(): String = super.getSimpleName()

  final override fun isPureRender(): Boolean = false

  final override fun makeShallowCopy(): Component = super.makeShallowCopy()

  final override fun onCreateMountContent(context: Context): Any =
      super.onCreateMountContent(context)

  final override fun shouldUpdate(
      previous: Component,
      prevStateContainer: StateContainer?,
      next: Component,
      nextStateContainer: StateContainer?,
  ): Boolean = super.shouldUpdate(previous, prevStateContainer, next, nextStateContainer)
}

/**
 * Sets a manual key on the given Component returned in the lambda, e.g.
 *
 * ```
 * key("my_key") { Text(...) }
 * ```
 */
inline fun key(key: String, crossinline componentLambda: () -> Component): Component {
  val component = componentLambda()
  setKeyForComponentInternal(component, key)
  return component
}

/**
 * Sets a handle on the given Component returned in the lambda, e.g.
 *
 * ```
 * handle(Handle()) { Text(...) }
 * ```
 */
inline fun handle(handle: Handle?, crossinline componentLambda: () -> Component): Component {
  val component = componentLambda()
  if (handle != null) {
    setHandleForComponentInternal(component, handle)
  }
  return component
}

/**
 * This is extracted out since we don't want to expose Component.key in the public API and will
 * hopefully change this implementation in the future.
 */
@PublishedApi
internal fun setKeyForComponentInternal(component: Component, key: String) {
  component.key = key
}

/**
 * This is extracted out since we don't want to expose Component.handle in the public API and will
 * hopefully change this implementation in the future.
 */
@PublishedApi
internal fun setHandleForComponentInternal(component: Component, handle: Handle) {
  component.handle = handle
}
