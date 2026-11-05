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

import androidx.annotation.VisibleForTesting
import androidx.collection.ScatterSet
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.layout.internal.newLayoutBuilder
import com.facebook.litho.layout.resolveBoxConstraints
import com.facebook.litho.state.DirtyScopeIndex
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.stats.LithoStats
import com.facebook.rendercore.LayoutCache
import com.facebook.rendercore.LayoutContext
import com.facebook.rendercore.SizeConstraints

/**
 * Checks a `Layout` child's layout that reuses part of an earlier pass against the layout it would
 * get fresh.
 *
 * With [ComponentsConfiguration.customLayoutShadowValidation] on, a `Layout` child whose layout
 * holds a subtree copied whole from the last pass's layout cache calls [check]. That lays the child
 * out again **cold and isolated**: in a layout context of its own, with an empty layout cache, no
 * DiffNode, no state reads recorded, no counters counted, and errors rethrown rather than reported.
 * It is isolated except that deferred content resolves against the pass's tree state:
 * `LayoutUtils.measureDeferredNode` applies pending state updates early and reads the live
 * `TreeState`. It then compares the two layouts' geometry: sizes, and the positions of every
 * descendant that is not deferred content; deferred content is compared by its holder's size only.
 * A difference throws [Mismatch], naming the node, so a stale reuse fails the test that exercises
 * it rather than drawing the wrong frame.
 *
 * Errors rethrown means a subtree whose measure always throws, under an error boundary that the
 * pass reports it to, makes every check of it a [Mismatch].
 *
 * Not run in a reflow animation frame: the cold layout has no reflow inputs.
 *
 * Off by default; for tests and debug builds.
 */
internal object CustomLayoutShadowValidation {

  private val running = ThreadLocal<Boolean>()

  /**
   * Whether a cold layout for a check is running on this thread. The thread-local is read only with
   * shadow validation on.
   */
  val isRunning: Boolean
    get() = ComponentsConfiguration.customLayoutShadowValidation && running.get() == true

  /** Test only: alters the cold layout's geometry before the comparison. */
  @VisibleForTesting @JvmStatic var testOnlyAlterExpected: ((Geometry) -> Geometry)? = null

  /** A layout's size, and each child's position and geometry, in order. */
  data class Geometry(val width: Int, val height: Int, val children: List<Placed>)

  data class Placed(val x: Int, val y: Int, val geometry: Geometry)

  /** Thrown when a reused layout differs from the layout the same child gets fresh. */
  class Mismatch(message: String, cause: Throwable? = null) : AssertionError(message, cause)

  /**
   * With shadow validation on, checks a child whose layout holds a subtree copied whole from the
   * last pass, at its root or anywhere below.
   */
  fun checkReused(
      context: LayoutContext<LithoLayoutContext>,
      node: LithoNode,
      constraints: SizeConstraints,
      result: LithoLayoutResult,
  ) {
    if (!ComponentsConfiguration.customLayoutShadowValidation) {
      return
    }
    val copies = context.renderContext?.wholeSubtreeCopies ?: return
    if (holdsWholeSubtreeCopy(result, copies)) {
      check(context, node, constraints, result)
    }
  }

  private fun holdsWholeSubtreeCopy(
      result: LithoLayoutResult,
      copies: ScatterSet<LithoLayoutResult>,
  ): Boolean {
    if (result in copies) {
      return true
    }
    for (i in 0 until result.childCount) {
      if (holdsWholeSubtreeCopy(result.getChildAt(i), copies)) {
        return true
      }
    }
    return false
  }

  /**
   * Checks [used], the layout of [node] under [constraints] that this pass took from an earlier
   * one, against a cold layout of [node] under the same constraints.
   */
  fun check(
      context: LayoutContext<LithoLayoutContext>,
      node: LithoNode,
      constraints: SizeConstraints,
      used: LithoLayoutResult,
  ) {
    if (!ComponentsConfiguration.customLayoutShadowValidation || isRunning) {
      return
    }
    val pass = context.renderContext ?: return
    // An animation frame forces interpolated sizes onto reflow nodes, which the cold layout, with
    // no reflow inputs, would not reproduce.
    if (
        pass.reflowFrameUpdates != null ||
            LithoYogaLayoutFunction.deferredContentOf(used) ==
                LithoYogaLayoutFunction.DeferredContent.MEASURED
    ) {
      return
    }
    LithoStats.count(LithoStats.customLayoutShadowCheckCount)
    val expected = coldLayoutOf(context, pass, node, constraints)
    val actual = geometryOf(used)
    val compared = testOnlyAlterExpected?.invoke(expected) ?: expected
    if (compared != actual) {
      throw Mismatch(
          "${node.headComponent.simpleName}'s layout reused from the last pass under " +
              "$constraints differs from a fresh one: fresh $compared, reused $actual",
      )
    }
  }

  private fun coldLayoutOf(
      context: LayoutContext<LithoLayoutContext>,
      pass: LithoLayoutContext,
      node: LithoNode,
      constraints: SizeConstraints,
  ): Geometry {
    val componentContext = node.tailComponentContext
    val cold = LithoLayoutContext(
        treeId = pass.treeId,
        cache = MeasuredResultCache(),
        rootContext = componentContext,
        treeState = pass.treeState,
        layoutVersion = pass.layoutVersion,
        rootComponentId = pass.rootComponentId,
        isAccessibilityEnabled = pass.isAccessibilityEnabled,
        layoutCache = LayoutCache(),
        currentDiffTree = null,
        layoutStateFuture = null,
        scopesToUpdate = DirtyScopeIndex.EMPTY,
    )
    val coldContext = LayoutContext(context.androidContext, cold, 0, cold.layoutCache, null)
    val previous = componentContext.calculationStateContext
    running.set(true)
    try {
      componentContext.setLithoLayoutContext(cold)
      return LithoStats.suppressed {
        StateReadRecorder.untracked(preserveTreeContext = false) {
          val builder = node.newLayoutBuilder(coldContext, previousDiffNode = null)
          val box = resolveBoxConstraints(constraints, builder.boxProps, node.layoutDirection.isRTL)
          builder.measure(box)
          geometryOf(builder.build(box))
        }
      }
    } catch (e: Mismatch) {
      throw e
    } catch (e: Exception) {
      throw Mismatch(
          "${node.headComponent.simpleName} threw laid out fresh under $constraints, where the " +
              "reused layout did not",
          e,
      )
    } finally {
      componentContext.calculationStateContext = previous
      running.remove()
      cold.release()
    }
  }

  fun geometryOf(result: LithoLayoutResult): Geometry = Geometry(
      width = result.width,
      height = result.height,
      children =
          List(result.childCount) { i ->
            Placed(
                result.getXForChildAtIndex(i),
                result.getYForChildAtIndex(i),
                geometryOf(result.getChildAt(i)),
            )
          },
  )
}
