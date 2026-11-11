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

import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.mutableScatterSetOf
import com.facebook.litho.Component
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.DeferredLithoLayoutResult
import com.facebook.litho.DiffNode
import com.facebook.litho.LithoLayoutContext
import com.facebook.litho.LithoLayoutResult
import com.facebook.litho.LithoNode
import com.facebook.litho.LithoYogaLayoutFunction
import com.facebook.litho.Resolver
import com.facebook.litho.YogaLayoutOutput.Companion.getYogaNode
import com.facebook.litho.layout.BoxConstraints
import com.facebook.litho.layout.BoxLayoutProps
import com.facebook.litho.state.StateId
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.LayoutContext
import com.facebook.rendercore.SizeConstraints
import com.facebook.yoga.YogaConstants
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaNode

/**
 * Lays out a `Layout`'s child that is neither a `Layout` nor a leaf it lays out itself: a
 * container, or a deferred, null or externally measured leaf, as a Yoga tree of its own: one tree,
 * laid out again in place when it's measured within another box, as Yoga does with any node it
 * measures twice. Built, it hands that tree over; discarded, it drops it.
 *
 * A tree holding content resolved under its constraints drops that content before it's laid out in
 * a new box: laid out again in place, the content would keep what it resolved to under an earlier
 * box whose spec is compatible, where it must resolve under the constraints it's measured with.
 * Content `Component.measure` sized resolves alike under any constraints: it is measured again.
 */
internal class YogaTreeBuilder(
    private val context: LayoutContext<LithoLayoutContext>,
    private val node: LithoNode,
    private val previousDiffNode: DiffNode?,
) : LithoLayoutBuilder {

  private var tree: LithoLayoutResult? = null
  private var laidOutBox: BoxConstraints? = null
  private var isDone = false

  // What its tree holds, found when it's made: the node's subtree is the same in every box. Null
  // until it has made its tree.
  private var deferredContent: LithoYogaLayoutFunction.DeferredContent? = null

  private val holdsDeferredContent: Boolean
    get() =
        deferredContent != null && deferredContent != LithoYogaLayoutFunction.DeferredContent.NONE

  private val holdsMeasuredContent: Boolean
    get() = deferredContent == LithoYogaLayoutFunction.DeferredContent.MEASURED

  // What its tree read before it was laid out again in another box, and what a dropped tree and the
  // `Layout`s in it read: what it measured to may depend on them.
  private var stateReads: MutableScatterSet<StateId>? = null

  override val unbuiltReads: ScatterSet<StateId>?
    get() = if (isDone) stateReads else null

  /** Recorded as its tree is made, which its parent asks for before it can measure it. */
  override val boxProps: BoxLayoutProps
    get() {
      check(!isDone) {
        "${node.headComponent.simpleName} box props read after it was built or discarded"
      }
      return checkNotNull(getOrCreateTree().childBoxProps)
    }

  override fun measure(box: BoxConstraints): IntSize {
    check(!isDone) { "${node.headComponent.simpleName} measured after it was built or discarded" }
    val tree = treeIn(box)
    return IntSize(tree.width, tree.height)
  }

  override fun build(box: BoxConstraints): LithoLayoutResult {
    check(!isDone) { "${node.headComponent.simpleName} built twice, or after it was discarded" }
    val tree = treeIn(box)
    isDone = true
    this.tree = null
    return LithoYogaLayoutFunction.buildTree(context, tree)
  }

  override fun discard(): ScatterSet<StateId>? {
    if (isDone) return null
    isDone = true
    tree?.let { dropped ->
      tree = null
      drop(dropped)
    }
    return stateReads.also { stateReads = null }
  }

  /**
   * Drops [tree], adding its reads to its own. Only a tree holding content `Component.measure`
   * sized when it was rendered is written to the layout cache: measuring it used up that size,
   * which a later pass laying the child out again would otherwise measure afresh. Not one that read
   * state: its reads are carried by another result, so an update dirties that one and not this
   * tree, which the cache would then hand back as it was.
   */
  private fun drop(tree: LithoLayoutResult) {
    discardLayoutsIn(tree)
    val readsState = addStateReadsOf(tree)
    if (holdsMeasuredContent && !readsState) {
      LithoYogaLayoutFunction.saveTreeIntoCache(context, tree)
    }
    tree.releaseLayoutPhaseData()
  }

  /** Its tree, made the first time it's asked for. */
  private fun getOrCreateTree(): LithoLayoutResult {
    tree?.let {
      return it
    }
    val tree = LithoYogaLayoutFunction.newTree(context, node, previousDiffNode)
    deferredContent = LithoYogaLayoutFunction.deferredContentOf(tree)
    this.tree = tree
    return tree
  }

  /**
   * Its tree, laid out within [box] unless it already is. A box that differs only in its margins
   * lays nothing out again: they place the tree, and don't shape it.
   */
  private fun treeIn(box: BoxConstraints): LithoLayoutResult {
    val tree = getOrCreateTree()
    tree.resolvedMargins = box.margins
    val lastBox = laidOutBox
    if (lastBox == null || !lastBox.shapesAlike(box)) {
      if (lastBox != null) {
        if (holdsDeferredContent) releaseDeferredContentIn(tree)
        dirtyLayoutsIn(tree)
      }
      layOutTree(tree, box)
    }
    laidOutBox = box
    return tree
  }

  private fun BoxConstraints.shapesAlike(other: BoxConstraints): Boolean =
      padding == other.padding && border == other.border && borderBox == other.borderBox

  /**
   * Lays [tree] out within [box], in place: its border box within [BoxConstraints.borderBox], with
   * the padding and border its node doesn't carry.
   */
  private fun layOutTree(tree: LithoLayoutResult, box: BoxConstraints) {
    // TODO: content that throws reports it as it's measured, as in any Yoga tree; hold it on its
    // result instead, until the tree is built.
    trace({ "layout.yogaCalculateLayout:${tree.node.headComponent.simpleName}" }) {
      tree.getYogaNode().layOutWithin(box)
    }
  }

  /**
   * Adds the state read anywhere in [result]'s tree to its own, and whether there was any. A
   * deferred holder's content hangs off it, not under it as a child, and its size is its content's:
   * the content's reads count too, including those made while it was rendered.
   */
  private fun addStateReadsOf(result: LithoLayoutResult): Boolean {
    var read = false
    result.stateReads?.let { reads ->
      if (reads.isNotEmpty()) {
        getOrCreateStateReads().addAll(reads)
        read = true
      }
    }
    for (i in 0 until result.childCount) {
      if (addStateReadsOf(result.getChildAt(i))) read = true
    }
    (result as? DeferredLithoLayoutResult)?.result?.let { content ->
      if (addStateReadsOf(content)) read = true
      if (addRenderReadsOf(content.node)) read = true
    }
    return read
  }

  /**
   * Adds what [tree] read to its own, then marks each deferred holder in it for Yoga to measure
   * again, dropping the content it resolved under its constraints, so it resolves under the box
   * it's laid out in next. A holder whose content is kept is marked too: Yoga can hand back a size
   * it measured within an earlier box without measuring the node again, but a holder holds only its
   * latest content, so that size would be laid out with content resolved or measured under another
   * box.
   */
  private fun releaseDeferredContentIn(tree: LithoLayoutResult) {
    addStateReadsOf(tree)
    forEachDeferredHolderIn(tree) { holder ->
      if (Component.isLayoutSpecWithSizeSpec(holder.node.tailComponent)) {
        holder.result?.releaseLayoutPhaseData()
        holder.actualDeferredNodeResult = null
      }
      holder.getYogaNode().dirty()
    }
  }

  /** Runs [action] on each deferred holder in [result]'s Yoga tree, not in a holder's content. */
  private fun forEachDeferredHolderIn(
      result: LithoLayoutResult,
      action: (DeferredLithoLayoutResult) -> Unit,
  ) {
    if (result is DeferredLithoLayoutResult) {
      action(result)
      return
    }
    for (i in 0 until result.childCount) {
      forEachDeferredHolderIn(result.getChildAt(i), action)
    }
  }

  /**
   * Marks each `Layout` in [result]'s tree for Yoga to measure again, so the tree laid out again in
   * a new box matches a fresh layout in that box, whose `Layout`s run under its constraints. Yoga
   * would otherwise keep a size it measured within the earlier box when that size still fits.
   * Within one Yoga pass, a `Layout` built at exactly the size it declared keeps that run, as a
   * fresh pass does (see `DefaultLayoutBuilder.declareUnder`).
   */
  private fun dirtyLayoutsIn(result: LithoLayoutResult) {
    LithoYogaLayoutFunction.forEachCustomLayoutIn(result) { layout -> layout.getYogaNode().dirty() }
  }

  /**
   * Discards each `Layout` in [result]'s tree through the builder its result holds, adding what it
   * ran and measured read to its own. They aren't counted for [drop]'s cache rule: a tree holding a
   * `Layout` is never copied from the cache whole.
   */
  private fun discardLayoutsIn(result: LithoLayoutResult) {
    LithoYogaLayoutFunction.forEachCustomLayoutIn(result) { layout ->
      addReads(layout.defaultLayoutBuilder?.discard())
      layout.defaultLayoutBuilder = null
    }
  }

  private fun addReads(reads: ScatterSet<StateId>?) {
    if (reads != null && reads.isNotEmpty()) getOrCreateStateReads().addAll(reads)
  }

  private fun getOrCreateStateReads(): MutableScatterSet<StateId> =
      stateReads
          ?: mutableScatterSetOf<StateId>().also { stateReadSet -> stateReads = stateReadSet }

  private fun addRenderReadsOf(node: LithoNode): Boolean {
    var read = false
    for (i in 0 until node.childCount) {
      if (addRenderReadsOf(node.getChildAt(i))) read = true
    }
    Resolver.forEachStateReadOn(node) { state, _ ->
      getOrCreateStateReads().add(state)
      read = true
    }
    return read
  }
}

/**
 * Lays this Yoga root out within [box], the room its container resolved from the child's own box
 * props, which its node does not carry: only [BoxConstraints.borderBox] and the resolved padding
 * and border are written here.
 *
 * **An upper bound is not a size.** With no size or maximum of its own, Yoga stretches a root to a
 * definite owner dimension. So a bound is written as the root's maximum, which puts Yoga on its
 * fit-to-content path, and only then handed on as the owner dimension; an unbounded axis is handed
 * on as undefined.
 */
internal fun YogaNode.layOutWithin(box: BoxConstraints) {
  // Each SizeConstraints read decodes the packed value again, so it's read once per bound.
  val room = box.borderBox
  val minWidth = room.minWidth
  val maxWidth = room.maxWidth
  val minHeight = room.minHeight
  val maxHeight = room.maxHeight
  val ownerWidth = if (maxWidth != SizeConstraints.Infinity) maxWidth.toFloat() else Float.NaN
  val ownerHeight = if (maxHeight != SizeConstraints.Infinity) maxHeight.toFloat() else Float.NaN
  if (minWidth == maxWidth) {
    setWidth(maxWidth.toFloat())
    setMinWidth(YogaConstants.UNDEFINED)
    setMaxWidth(YogaConstants.UNDEFINED)
  } else {
    setWidthAuto()
    setMinWidth(if (minWidth > 0) minWidth.toFloat() else YogaConstants.UNDEFINED)
    setMaxWidth(ownerWidth)
  }
  if (minHeight == maxHeight) {
    setHeight(maxHeight.toFloat())
    setMinHeight(YogaConstants.UNDEFINED)
    setMaxHeight(YogaConstants.UNDEFINED)
  } else {
    setHeightAuto()
    setMinHeight(if (minHeight > 0) minHeight.toFloat() else YogaConstants.UNDEFINED)
    setMaxHeight(ownerHeight)
  }
  setPadding(YogaEdge.LEFT, box.padding.left.toFloat())
  setPadding(YogaEdge.TOP, box.padding.top.toFloat())
  setPadding(YogaEdge.RIGHT, box.padding.right.toFloat())
  setPadding(YogaEdge.BOTTOM, box.padding.bottom.toFloat())
  setBorder(YogaEdge.LEFT, box.border.left.toFloat())
  setBorder(YogaEdge.TOP, box.border.top.toFloat())
  setBorder(YogaEdge.RIGHT, box.border.right.toFloat())
  setBorder(YogaEdge.BOTTOM, box.border.bottom.toFloat())
  calculateLayout(ownerWidth, ownerHeight)
}
