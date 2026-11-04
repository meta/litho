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
import androidx.annotation.VisibleForTesting
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.rendercore.extensions.RenderCoreExtension

@DataClassGenerate
data class MountInput(
    @JvmField val renderTree: RenderTree,
    @JvmField val continuationValues: ContinuationValues,
) {
  val width: Int = renderTree.width
  val height: Int = renderTree.height
}

/**
 * Result from laying out and reducing a [ResolveResult]. A [RenderResult] from a previous
 * computation will make the next computation of a new [RenderResult] more efficient with internal
 * caching.
 */
class RenderResult<State, RenderContext>(
    val mountInput: MountInput,
    val nodeTree: Node<RenderContext>,
    val layoutCacheData: LayoutCacheData,
    val state: State?,
) {
  val renderTree: RenderTree = mountInput.renderTree

  companion object {

    @Deprecated(message = "Use the render variant that accepts SizeConstraints")
    @JvmStatic
    fun <State, RenderContext> render(
        context: Context,
        result: ResolveResult<Node<RenderContext>, State>,
        renderContext: RenderContext?,
        extensions: Array<RenderCoreExtension<*, *>>?,
        previousResult: RenderResult<State, RenderContext>?,
        layoutVersion: Int,
        widthSpec: Int,
        heightSpec: Int,
    ): RenderResult<State, RenderContext> {
      return render(
          context,
          result,
          renderContext,
          extensions,
          previousResult,
          layoutVersion,
          SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec),
      )
    }

    @JvmStatic
    fun <State, RenderContext> render(
        context: Context,
        result: ResolveResult<Node<RenderContext>, State>,
        renderContext: RenderContext?,
        extensions: Array<RenderCoreExtension<*, *>>?,
        previousResult: RenderResult<State, RenderContext>?,
        layoutVersion: Int,
        sizeConstraints: SizeConstraints,
    ): RenderResult<State, RenderContext> {
      RenderCoreSystrace.beginSection("RC Create Tree")
      val renderResult: RenderResult<State, RenderContext> =
          if (
              previousResult != null &&
                  shouldReuseResult(result.resolvedNode, sizeConstraints, previousResult)
          ) {
            RenderResult(
                previousResult.mountInput,
                result.resolvedNode,
                previousResult.layoutCacheData,
                result.resolvedState,
            )
          } else {
            val layoutContext: LayoutContext<RenderContext> = createLayoutContext(
                previousResult,
                renderContext,
                context,
                layoutVersion,
                extensions,
            )
            try {
              layout(layoutContext, result.resolvedNode, result.resolvedState, sizeConstraints)
            } finally {
              layoutContext.clearCache()
            }
          }
      RenderCoreSystrace.endSection()
      return renderResult
    }

    @JvmStatic
    fun <RenderContext> createLayoutContext(
        previousResult: RenderResult<*, *>?,
        renderContext: RenderContext?,
        context: Context,
        layoutVersion: Int,
        extensions: Array<RenderCoreExtension<*, *>>?,
    ): LayoutContext<RenderContext> {
      val layoutCache = buildCache(previousResult?.layoutCacheData)
      return LayoutContext<RenderContext>(
          context,
          renderContext,
          layoutVersion,
          layoutCache,
          extensions,
      )
    }

    @Deprecated(message = "Use layout function that accepts SizeConstraints")
    @JvmStatic
    fun <State, RenderContext> layout(
        layoutContext: LayoutContext<RenderContext>,
        node: Node<RenderContext>,
        state: State?,
        widthSpec: Int,
        heightSpec: Int,
    ): RenderResult<State, RenderContext> {
      return layout(
          layoutContext,
          node,
          state,
          SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec),
      )
    }

    @JvmStatic
    fun <State, RenderContext> layout(
        layoutContext: LayoutContext<RenderContext>,
        node: Node<RenderContext>,
        state: State?,
        sizeConstraints: SizeConstraints,
    ): RenderResult<State, RenderContext> {
      RenderCoreSystrace.beginSection("RC Layout")
      val layoutResult = node.calculateLayout(layoutContext, sizeConstraints)
      RenderCoreSystrace.endSection()
      RenderCoreSystrace.beginSection("RC Reduce")
      val renderResult: RenderResult<State, RenderContext> =
          create(layoutContext, node, layoutResult, sizeConstraints, state)
      RenderCoreSystrace.endSection()
      return renderResult
    }

    @JvmStatic
    fun <State, RenderContext> create(
        c: LayoutContext<RenderContext>,
        node: Node<RenderContext>,
        layoutResult: LayoutResult,
        sizeConstraints: SizeConstraints,
        state: State?,
    ): RenderResult<State, RenderContext> {
      val renderTree = Reducer.getReducedTree(c, layoutResult, sizeConstraints, c.layoutVersion)
      return RenderResult(
          MountInput(renderTree, ContinuationValues.EMPTY),
          node,
          c.layoutCache.toCacheData(),
          state,
      )
    }

    @JvmStatic
    fun <RenderContext> createMountInput(
        c: LayoutContext<RenderContext>,
        node: Node<RenderContext>,
        sizeConstraints: SizeConstraints,
    ): MountInput {
      val layoutResult = node.calculateLayout(c, sizeConstraints)
      return MountInput(
          Reducer.getReducedTree(c, layoutResult, sizeConstraints, c.layoutVersion),
          ContinuationValues.EMPTY,
      )
    }

    @JvmStatic
    fun <RenderContext> createMountInput(
        c: LayoutContext<RenderContext>,
        node: Node<RenderContext>,
        widthSpec: Int,
        heightSpec: Int,
    ): MountInput {
      return createMountInput(c, node, SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec))
    }

    @JvmStatic
    fun <RenderContext> createRootMountInput(
        c: LayoutContext<RenderContext>,
        node: Node<RenderContext>,
        sizeConstraints: SizeConstraints,
    ): MountInput {
      val layoutResult = node.calculateLayout(c, sizeConstraints)
      return MountInput(
          Reducer.getReducedTree(c, layoutResult, sizeConstraints, c.layoutVersion),
          ContinuationValues(c.layoutCache.continuationValues),
      )
    }

    @JvmStatic
    fun <State, RenderContext> shouldReuseResult(
        node: Node<RenderContext>,
        sizeConstraints: SizeConstraints,
        previousResult: RenderResult<State, RenderContext>?,
    ): Boolean {
      if (previousResult == null) {
        return false
      }
      val prevRenderTree = previousResult.renderTree
      return (node === previousResult.nodeTree &&
          sizeConstraints.areCompatible(
              prevRenderTree.sizeConstraints,
              IntSize(prevRenderTree.width, prevRenderTree.height),
          ))
    }

    @VisibleForTesting
    @JvmStatic
    fun buildCache(previousData: LayoutCacheData?): LayoutCache {
      return if (previousData != null) LayoutCache(previousData) else LayoutCache(null)
    }
  }
}
