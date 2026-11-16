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

package com.facebook.rendercore

import android.content.Context
import androidx.collection.MutableScatterMap
import com.facebook.rendercore.extensions.RenderCoreExtension

/**
 * A LayoutContext encapsulates all the data needed during a layout pass. It contains - The Android
 * context associated with this layout calculation. - The version of the layout calculation. - The
 * LayoutCache for this layout calculation. Access to the cache is only valid during the execution
 * of the Node's calculateLayout function.
 */
class LayoutContext<RenderContext>(
    val androidContext: Context,
    val renderContext: RenderContext?,
    val layoutVersion: Int,
    layoutCache: LayoutCache?,
    val extensions: Array<RenderCoreExtension<*, *>>?,
) {

  private var extraContextForCurrentNode: Any? = null
  private var previousLayoutData: Any? = null

  /**
   * Whether the current measurement is a min-content query from the flex layout algorithm. Scoped
   * by the measure function — set to true before calling node.calculateLayout() for min-content,
   * restored to the previous value after. Components should check this instead of consuming
   * extraContextForCurrentNode.
   */
  var isMinContentMeasure: Boolean = false

  private var _layoutCache: LayoutCache? = layoutCache

  private var passScopedData: MutableScatterMap<Any, Any>? = null

  val layoutCache: LayoutCache
    get() =
        checkNotNull(_layoutCache) { "Trying to access the LayoutCache from outside a layout call" }

  /**
   * Disposes the layout cache for this context. Only the code that created this [LayoutContext]
   * should call this, typically a root layout invoker after it has copied the cache data it needs.
   *
   * Dropping the reference ensures `clearCache()` propagates immediately to subsequent reads of
   * [layoutCache], so a captured [LayoutContext] can't retain a prior pass's [LayoutCacheData].
   */
  fun clearCache() {
    _layoutCache = null
    passScopedData = null
  }

  /**
   * Stores [value] for [key] for this layout pass, replacing any value stored for [key]. For state
   * the nodes of a pass share that isn't a layout result to reuse in the next pass (those go in
   * [layoutCache]): it lives with this context, isn't carried over, and is dropped by [clearCache].
   */
  fun putPassScopedData(key: Any, value: Any) {
    val data = passScopedData ?: MutableScatterMap<Any, Any>().also { passScopedData = it }
    data[key] = value
  }

  /** The data stored for [key] for this layout pass, if any, see [putPassScopedData]. */
  fun <T : Any> getPassScopedData(key: Any): T? {
    @Suppress("UNCHECKED_CAST")
    return passScopedData?.get(key) as T?
  }

  fun setPreviousLayoutDataForCurrentNode(previousLayoutData: Any?) {
    this.previousLayoutData = previousLayoutData
  }

  fun consumePreviousLayoutDataForCurrentNode(): Any? {
    val data = previousLayoutData
    previousLayoutData = null
    return data
  }

  fun setExtraContextForCurrentNode(extraContext: Any?) {
    extraContextForCurrentNode = extraContext
  }

  fun consumeExtraContextForCurrentNode(): Any? {
    val extraContext = extraContextForCurrentNode
    extraContextForCurrentNode = null
    return extraContext
  }
}
