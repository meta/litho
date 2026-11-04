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

import android.graphics.Point
import android.util.Pair
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import com.facebook.litho.animation.ReflowGeneration
import com.facebook.litho.animation.ReflowReadView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.state.DirtyScopeIndex
import com.facebook.litho.state.StateId
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.LayoutCache

/**
 * Wraps objects which should only be available for the duration of a LayoutState, to access them in
 * other classes such as ComponentContext during layout state calculation. When the layout
 * calculation finishes, the LayoutState reference is nullified. Using a wrapper instead of passing
 * the instances directly helps with clearing out the reference from all objects that hold on to it,
 * without having to keep track of all these objects to clear out the references.
 */
class LithoLayoutContext
@JvmOverloads
constructor(
    override val treeId: Int,
    override val cache: MeasuredResultCache,
    rootContext: ComponentContext?,
    treeState: TreeState?,
    override val layoutVersion: Int,
    override val rootComponentId: Int,
    override val isAccessibilityEnabled: Boolean,
    val layoutCache: LayoutCache,
    currentDiffTree: DiffNode?,
    layoutStateFuture: TreeFuture<*>?,
    override val scopesToUpdate: DirtyScopeIndex = DirtyScopeIndex.EMPTY,
    override val discardCallbacks: DiscardCallbacks? = null,
) : CalculationContext {

  private var _treeState: TreeState? = treeState
  private var _future: TreeFuture<*>? = layoutStateFuture
  private var _rootContext: ComponentContext? = rootContext
  private var _currentDiffTree: DiffNode? = currentDiffTree

  private var _eventHandlers: MutableList<Pair<GlobalKey, EventHandler<*>>>? = null
  private var _currentDiffNodeForDeferredNode: DiffNode? = null

  var rootOffset: Point = Point(0, 0)

  /**
   * The state updates that triggered this layout, or null when the pass has something to re-measure
   * and so cannot be an animation frame for any node. Read through [isReflowAnimationFrameFor].
   */
  internal var reflowFrameUpdates: Set<StateId>? = null

  /**
   * Whether this pass is an animation frame for the reflow node owning [stateId]. Per node, not per
   * pass — a node whose natural size moved because a different value ticked must still capture it.
   */
  internal fun isReflowAnimationFrameFor(stateId: StateId): Boolean =
      reflowFrameUpdates?.contains(stateId) == true

  /**
   * Natural sizes captured for every reflow node this commit (changed and unchanged), keyed by
   * [LithoNode.transitionGlobalKey]; the commit itself is pinned to the previous/in-flight size.
   * [StyleTransitionsExtension] reconciles each against the live store target at mount, so a
   * background walk that read a stale target cannot make the wrong retarget decision. Null when the
   * tree declares no reflow node, or when every reflow node was driving its own animation frame.
   */
  var reflowTargetSizes: MutableScatterMap<GlobalKey, IntSize>? = null

  /**
   * The tree's reflow read view, captured once at the start of this pass. All reflow reads during
   * the layout walk go through this stable reference, never the store directly.
   */
  internal var reflowReadView: ReflowReadView = ReflowGeneration.EMPTY

  /**
   * The roots of the subtrees this pass copied whole from the last pass's layout cache, kept only
   * with shadow validation on, which checks the layouts holding them.
   */
  internal var wholeSubtreeCopies: MutableScatterSet<LithoLayoutResult>? = null

  /** Adds [copy] to [wholeSubtreeCopies], with shadow validation on. */
  internal fun addWholeSubtreeCopy(copy: LithoLayoutResult) {
    if (ComponentsConfiguration.customLayoutShadowValidation) {
      (wholeSubtreeCopies
              ?: MutableScatterSet<LithoLayoutResult>().also { wholeSubtreeCopies = it })
          .add(copy)
    }
  }

  /**
   * The reflow-declaring layout results collected during the current Yoga root's build walk, so the
   * post-layout capture iterates only these instead of recursing the whole result tree.
   *
   * Scoped to one `LithoYogaLayoutFunction.calculateLayout` invocation, not to this context: a
   * deferred node re-enters that function on this same context, and each pass must only pin and
   * capture the nodes its own walk collected.
   */
  internal var reflowResults: MutableList<LithoLayoutResult>? = null

  internal fun inheritReflowInputsFrom(parent: LithoLayoutContext) {
    reflowReadView = parent.reflowReadView
    reflowFrameUpdates = parent.reflowFrameUpdates
  }

  internal fun mergeReflowTargetSizesFrom(child: LithoLayoutContext) {
    val childTargetSizes = child.reflowTargetSizes ?: return
    val targetSizes = reflowTargetSizes
    if (targetSizes == null) {
      reflowTargetSizes = childTargetSizes
      return
    }
    childTargetSizes.forEach { key, size -> targetSizes[key] = size }
  }

  internal fun hasReflowFrameUpdateIn(scope: GlobalKey): Boolean =
      reflowFrameUpdates != null && scopesToUpdate.containsOrHasDescendant(scope)

  /**
   * The tree id reflow [com.facebook.litho.state.StateId]s are built from: the tree's own id, which
   * is what `ReflowAnimationStore` mints values with, so a layout read matches their invalidation.
   */
  internal val reflowTreeId: Int
    get() = _rootContext?.lithoTree?.id ?: treeId

  override val treeFuture: TreeFuture<*>?
    get() {
      return _future
    }

  override val isFutureReleased: Boolean
    get() {
      val future = _future
      return future != null && future.isReleased
    }

  override val treeState: TreeState
    get() = checkNotNull(_treeState)

  override val eventHandlers: List<Pair<GlobalKey, EventHandler<*>>>?
    get() = _eventHandlers

  override fun recordEventHandler(globalKey: GlobalKey, eventHandler: EventHandler<*>) {
    (_eventHandlers ?: ArrayList()).apply {
      _eventHandlers = this
      add(Pair(globalKey, eventHandler))
    }
  }

  val rootComponentContext: ComponentContext?
    get() = _rootContext

  val currentDiffTree: DiffNode?
    get() = _currentDiffTree

  var isReleased: Boolean = false
    private set

  fun setDiffNodeForDeferredNode(diff: DiffNode?) {
    _currentDiffNodeForDeferredNode = diff
  }

  fun hasDiffNodeSetForDeferredNode(): Boolean {
    return _currentDiffNodeForDeferredNode != null
  }

  fun consumeDiffNodeForDeferredNode(): DiffNode? {
    return _currentDiffNodeForDeferredNode.apply { _currentDiffNodeForDeferredNode = null }
  }

  fun release() {
    _treeState = null
    _future = null
    _currentDiffTree = null
    _rootContext = null
    reflowTargetSizes = null
    reflowReadView = ReflowGeneration.EMPTY
    reflowResults = null
    wholeSubtreeCopies = null
    reflowFrameUpdates = null
    isReleased = true
  }
}
