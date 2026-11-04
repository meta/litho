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

import com.facebook.rendercore.SizeConstraints

/** A [TreeFuture] that resolves a [Component] into a [ResolveResult]. */
internal class LithoResolveTreeFuture(
    private val componentContext: ComponentContext,
    treeId: Int,
    private val component: Component,
    private val treeState: TreeState,
    private val previousResult: ResolveResult?,
    private val version: Int,
    useCancellableFutures: Boolean,
) : TreeFuture<ResolveResult>(treeId, useCancellableFutures) {

  override fun getVersion(): Int = version

  override fun getDescription(): String = DESCRIPTION

  override fun calculate(): ResolveResult {
    return ResolveTreeFuture.resolve(
        componentContext,
        component,
        treeState,
        version,
        treeId,
        previousResult,
        DESCRIPTION,
        this,
        discardCallbacks,
    )
  }

  override fun resumeCalculation(partialResult: ResolveResult?): ResolveResult {
    return ResolveTreeFuture.resume(requireNotNull(partialResult), treeId, null)
  }

  override fun isEquivalentTo(that: TreeFuture<*>?): Boolean {
    if (that !is LithoResolveTreeFuture) {
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

  companion object {
    private const val DESCRIPTION: String = "LithoResolveTreeFuture"
  }
}

/** A [TreeFuture] that computes a [LayoutState]. */
internal class LithoLayoutTreeFuture(
    treeId: Int,
    private val version: Int,
    private val resolveResult: ResolveResult,
    private val sizeConstraints: SizeConstraints,
    private val previousLayoutState: LayoutState?,
    private val treeState: TreeState,
) : TreeFuture<LayoutState>(treeId, false) {

  override fun getDescription(): String = DESCRIPTION

  override fun getVersion(): Int = version

  override fun calculate(): LayoutState {
    return LayoutTreeFuture.layout(
        resolveResult,
        sizeConstraints,
        version,
        treeId,
        treeState,
        previousLayoutState,
        previousLayoutState?.diffTree,
        this,
        discardCallbacks,
    )
  }

  override fun resumeCalculation(partialResult: LayoutState?): LayoutState =
      throw UnsupportedOperationException("LithoLayoutTreeFuture cannot be resumed.")

  override fun isEquivalentTo(that: TreeFuture<*>?): Boolean {
    if (that !is LithoLayoutTreeFuture) {
      return false
    }
    return (sizeConstraints == that.sizeConstraints) && (resolveResult == that.resolveResult)
  }

  companion object {
    private const val DESCRIPTION: String = "LithoLayoutTreeFuture"
  }
}
