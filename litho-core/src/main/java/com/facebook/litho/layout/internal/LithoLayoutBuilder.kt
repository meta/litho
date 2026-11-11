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

package com.facebook.litho.layout.internal

import androidx.collection.ScatterSet
import com.facebook.litho.BoxLayoutPropsRecorder
import com.facebook.litho.DeferredLithoNode
import com.facebook.litho.DiffNode
import com.facebook.litho.LithoLayoutContext
import com.facebook.litho.LithoLayoutResult
import com.facebook.litho.LithoNode
import com.facebook.litho.LithoYogaMeasureFunction
import com.facebook.litho.NullNode
import com.facebook.litho.layout.BoxConstraints
import com.facebook.litho.layout.BoxLayoutProps
import com.facebook.litho.state.StateId
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.LayoutContext

/**
 * Lays one node out for one pass, within the boxes its parent resolves for it: measured any number
 * of times, then either built once into its result or discarded if nothing placed it. After either,
 * it can't be measured or built, and discarding it again does nothing.
 */
internal sealed interface LithoLayoutBuilder {

  /** The box props its node declares, which its parent resolves into the boxes it's laid out in. */
  val boxProps: BoxLayoutProps

  /**
   * Its border-box size within [box]. A `Layout` that fails measures 0 x 0 inside its padding and
   * border, and its failure rides on its result, reported only if the result reaches the final
   * tree. A Yoga tree reports a failure as it's measured.
   */
  fun measure(box: BoxConstraints): IntSize

  /**
   * Its result within [box], one it was measured in. The result carries the state read by what it
   * built; what it made for its measures but didn't build is [unbuiltReads].
   */
  fun build(box: BoxConstraints): LithoLayoutResult

  /**
   * Once built, the state read by what it made for its measures but didn't build, or null: what it
   * measured to may depend on them, so its parent's result carries them. A builder that makes one
   * result for all its measures, a leaf's or a `Layout`'s, has none.
   */
  val unbuiltReads: ScatterSet<StateId>?

  /**
   * Nothing placed it: the state read by what it made, or null if it read none or reads aren't
   * tracked. It never reaches the final tree, so its parent's result carries them.
   */
  fun discard(): ScatterSet<StateId>?
}

/**
 * [this] node's builder for the pass, as a `Layout`'s child; [previousDiffNode] is its diff node
 * from the last pass:
 * - a `Layout`'s own;
 * - a leaf's, measured with no Yoga node;
 * - a Yoga tree, for anything else.
 */
@Suppress("OPT_IN_USAGE")
internal fun LithoNode.newLayoutBuilder(
    context: LayoutContext<LithoLayoutContext>,
    previousDiffNode: DiffNode?,
): LithoLayoutBuilder =
    when {
      layoutFunction != null -> DefaultLayoutBuilder(context, this, previousDiffNode)
      isLeaf() -> LeafLayoutBuilder(context, this, previousDiffNode)
      else -> YogaTreeBuilder(context, this, previousDiffNode)
    }

/**
 * Whether it's a leaf whose content Litho measures, or that has none: no children, and none of a
 * deferred node's resolution or a null node's absence, which only a Yoga tree handles.
 */
private fun LithoNode.isLeaf(): Boolean {
  if (childCount != 0 || this is DeferredLithoNode || this is NullNode) {
    return false
  }
  val measureFunction = yogaMeasureFunction
  return measureFunction == null || measureFunction is LithoYogaMeasureFunction
}

/**
 * [node]'s box props, replayed into a recorder rather than onto a Yoga node, with the state they
 * read handed to [onReads]. A padding is recorded on the node, as Yoga records one it carries.
 */
internal inline fun recordBoxPropsOf(
    context: LayoutContext<LithoLayoutContext>,
    node: LithoNode,
    onReads: (ScatterSet<StateId>) -> Unit,
): BoxLayoutProps {
  val props = BoxLayoutProps()
  val recorder = BoxLayoutPropsRecorder(props, recordsOwnEdges = true)
  val reads = node.copyLayoutPropsInto(context, recorder)
  if (reads.isNotEmpty()) {
    onReads(reads)
  }
  node.isPaddingSet = recorder.isPaddingSet
  return props
}
