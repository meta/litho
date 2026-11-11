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

@file:JvmName("ComponentRenderer")

package com.facebook.litho

import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.state.StateReadRecorder

/**
 * Wrapper around the Component-specific render implementation.
 *
 * @param resolveContext The ResolveContext for the Component being rendered.
 * @param render The Component-specific render implementation.
 * @return The RenderResult from the Component-specific render implementation.
 * @see Component.resolve
 * @see KComponent.render
 * @see PrimitiveComponent.render
 * @see SpecGeneratedComponent.render
 */
internal inline fun <T> ScopedComponentInfo.runInRecorderScope(
    resolveContext: ResolveContext,
    crossinline render: () -> RenderResult<T>,
): RenderResult<T> {
  var result: RenderResult<T>? = null
  val stateReads =
      StateReadRecorder.record(resolveContext.treeId, attribution("resolve", component)) {
        result = render()
      }
  context.scopedComponentInfo.stateReads = stateReads
  return checkNotNull(result)
}

/**
 * Calls [SpecGeneratedComponent.render] inside [ScopedComponentInfo.runInRecorderScope].
 *
 * [runInRecorderScope] is a Kotlin `inline` function, so calls from Kotlin elide the lambda.
 * [SpecGeneratedComponent.resolve] is Java, where the lambda is *not* inlined and allocates an
 * anonymous class on every call. This extension exists so the call site stays in Kotlin and the
 * `inline` works as expected.
 */
internal fun SpecGeneratedComponent.renderWithRecorder(
    resolveContext: ResolveContext,
    scopedComponentInfo: ScopedComponentInfo,
    widthSpec: Int,
    heightSpec: Int,
): RenderResult<Component?> {
  val context = scopedComponentInfo.context
  return scopedComponentInfo.runInRecorderScope(resolveContext) {
    render(resolveContext, context, widthSpec, heightSpec)
  }
}

internal fun attribution(phase: String, component: Component): String? =
    if (LithoDebugConfigurations.isDebugModeEnabled) "$phase:${component.simpleName}" else phase
