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

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.Resolver.Outputs
import com.facebook.litho.state.StateId
import java.util.concurrent.atomic.AtomicReference

@DataClassGenerate(toString = Mode.OMIT, equalsHashCode = Mode.KEEP)
data class ResolveResult(
    @JvmField val node: LithoNode?,
    @JvmField val context: ComponentContext,
    @JvmField val component: Component,
    private val cache: MeasuredResultCache,
    @JvmField val treeState: TreeState,
    override val isPartialResult: Boolean,
    @JvmField val version: Int,
    @JvmField val outputs: Outputs?,
    @JvmField val contextForResuming: ResolveContext?,
    @JvmField val updatedStates: Set<StateId>,
    @JvmField val isAccessibilityEnabled: Boolean = false,
    /** The [useOnDiscard] callbacks of the renders that resolved this; null for a reused result. */
    @JvmField internal val discardCallbacks: DiscardCallbacks? = null,
) : PotentiallyPartialResult {

  private val cacheRef: AtomicReference<MeasuredResultCache> = AtomicReference(cache)

  fun consumeCache(): MeasuredResultCache = cacheRef.getAndSet(MeasuredResultCache.EMPTY)
}
