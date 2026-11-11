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

package com.facebook.litho.animation

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import com.facebook.litho.ComponentTree
import com.facebook.litho.GlobalKey
import com.facebook.litho.LayoutTreeFuture
import com.facebook.litho.LithoTree
import com.facebook.litho.StateCaller
import com.facebook.litho.Style
import com.facebook.litho.TreeFuture
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.key
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.TestAnimationsComponent
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.LooperMode

/**
 * End-to-end coverage for read-based reflow (`Style.transition { bounds(spec, reflow = true) }`).
 * The convergence tests double as the termination guard: if an animation frame were misclassified
 * as a real commit the value would retarget to its own output every frame and `idle()` would never
 * return.
 */
@LooperMode(LooperMode.Mode.LEGACY)
@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
class StyleReflowTest {

  // Same-frame invalidation off, so animation frames go through the deferred path.
  @JvmField
  @Rule
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableAnimationFrameInvalidation = false,
      ),
  )

  // Same feature with same-frame invalidation on, so animation frames go through the in-frame
  // flushAnimationLayout path instead of the deferred one — exercises the marker under that path.
  @JvmField
  @Rule
  val inFrameTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableAnimationFrameInvalidation = true,
      ),
  )

  private lateinit var activityController: ActivityController<Activity>

  @Before
  fun setUp() {
    activityController = Robolectric.buildActivity(Activity::class.java, Intent())
  }

  @Test
  fun reflowBounds_convergesToTarget_andReflowsSibling() {
    val stateCaller = StateCaller()
    val component = growComponent(stateCaller, collapsedHeight = 100, expandedHeight = 250)

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()

    val grow = testLithoView.findViewWithTag(GROW_TAG)
    val sibling = testLithoView.findViewWithTag(SIBLING_TAG)

    // Baseline: 100-tall child, sibling laid out directly below it.
    assertThat(grow.height).isEqualTo(100)
    assertThat(sibling.top).isEqualTo(100)

    stateCaller.update()
    // Drives the reflow animation to completion. If an animation-frame relayout were misclassified
    // as a real commit, the value would retarget to its own interpolated output and this would
    // hang.
    lithoTestRule.idle()

    // Converged: child at its new 250 height and the sibling reflowed to y=250.
    assertThat(grow.height).isEqualTo(250)
    assertThat(sibling.top).isEqualTo(250)
  }

  @Test
  fun reflowBounds_shrink_convergesAndReflowsSibling() {
    val stateCaller = StateCaller()
    // state = true collapses (250 -> 100): the reflow must also drive a shrink and pull the sibling
    // up to y=100.
    val component = growComponent(stateCaller, collapsedHeight = 250, expandedHeight = 100)

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()

    val grow = testLithoView.findViewWithTag(GROW_TAG)
    val sibling = testLithoView.findViewWithTag(SIBLING_TAG)

    assertThat(grow.height).isEqualTo(250)
    assertThat(sibling.top).isEqualTo(250)

    stateCaller.update()
    lithoTestRule.idle()

    assertThat(grow.height).isEqualTo(100)
    assertThat(sibling.top).isEqualTo(100)
  }

  @Test
  fun reflowSize_withBothAxesCleared_laysOutAtNaturalSize() {
    val stateCaller = StateCaller()
    val component =
        growComponent(stateCaller, collapsedHeight = 100, expandedHeight = 250) {
          size(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
          width = null
          height = null
        }

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()

    val grow = testLithoView.findViewWithTag(GROW_TAG)
    val sibling = testLithoView.findViewWithTag(SIBLING_TAG)
    assertThat(grow.height).isEqualTo(100)

    stateCaller.update()
    lithoTestRule.idle()

    // No size spec is left to reflow, so nothing may pin the node at its old size.
    assertThat(grow.height).isEqualTo(250)
    assertThat(sibling.top).isEqualTo(250)
  }

  @Test
  fun reflowBounds_growThenShrinkWithStructuralChange_converges() {
    val stateCaller = StateCaller()
    // state=false -> collapsed (100, no banner); state=true -> expanded (250, banner present). The
    // banner is a structural insert/remove co-occurring with the reflow size change; the round trip
    // must still converge (and not hang).
    val component =
        TestAnimationsComponent(stateCaller, null) { state ->
          Column(style = Style.width(100.dp)) {
            if (state) {
              child(
                  key("banner") {
                    Row(style = Style.width(100.dp).height(80.dp).backgroundColor(Color.YELLOW))
                  },
              )
            }
            child(
                key("grow") {
                  Row(
                      style =
                          Style.width(100.dp)
                              .height(if (state) 250.dp else 100.dp)
                              .backgroundColor(Color.RED)
                              .transition {
                                bounds(
                                    tween(duration = 144.ms, easing = Easing.Linear),
                                    reflow = true,
                                )
                              }
                              .viewTag(GROW_TAG),
                  )
                },
            )
          }
        }

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val grow = testLithoView.findViewWithTag(GROW_TAG)

    // Expand: banner appears, grow -> 250.
    stateCaller.update()
    lithoTestRule.idle()
    assertThat(grow.height).isEqualTo(250)

    // Collapse: banner leaves, grow -> 100. Converges to the collapsed size.
    stateCaller.update()
    lithoTestRule.idle()
    assertThat(grow.height).isEqualTo(100)
  }

  @Test
  fun reflowBounds_staleWalkTarget_reconciledAtMount_toCommittedTree() {
    // The retarget DECISION is made at mount against the live store target, not from the walk-time
    // read, so a target that has diverged from what the committed tree wants is always reconciled.
    // We
    // force that divergence directly on the published generation (a concurrent layout could move it
    // between another layout's walk and its mount) and confirm a real relayout of a tree that still
    // wants 100 pulls the store back to 100, never leaving it stuck at the stale target.
    val stateCaller = StateCaller()
    val testLithoView = lithoTestRule.render { constantReflowTogglingSibling(stateCaller) }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()

    val grow = testLithoView.findViewWithTag(GROW_TAG)
    assertThat(grow.height).isEqualTo(100)

    val lithoTree = checkNotNull(testLithoView.lithoView.componentTree?.context?.lithoTree)
    val reflowKey = singleReflowKey(lithoTree)

    // Push the live target away from what the committed tree wants, on the shared published entry.
    checkNotNull((lithoTree.reflowProvider.reflowView as ReflowGeneration).entry(reflowKey))
        .targetSize = IntSize(100, 999)

    // A real relayout that leaves the reflow node's natural size at 100 (only the sibling changes).
    stateCaller.update()
    lithoTestRule.idle()

    // Reconciled at mount to the committed tree's natural size, not left stuck at the stale 999.
    assertThat(checkNotNull(lithoTree.reflowProvider.reflowView).targetSize(reflowKey))
        .isEqualTo(IntSize(100, 100))
    assertThat(grow.height).isEqualTo(100)
  }

  @Test
  fun reflowBounds_targetMovesBetweenWalkAndMount_reconciledToCommittedTree() {
    // The true walk/mount interleaving, made deterministic without threads. `onPostExecution` fires
    // after a layout's Yoga walk (which reads the OLD target and takes the unchanged branch,
    // recording
    // its natural) and BEFORE that layout commits/mounts — exactly the window a concurrent retarget
    // could land in. We move the target there and confirm mount reconciliation pulls the store back
    // to
    // this layout's recorded natural, rather than leaving it at the moved target.
    val stateCaller = StateCaller()
    val testLithoView = lithoTestRule.render { constantReflowTogglingSibling(stateCaller) }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()

    val grow = testLithoView.findViewWithTag(GROW_TAG)
    assertThat(grow.height).isEqualTo(100)

    val componentTree = checkNotNull(testLithoView.lithoView.componentTree)
    val lithoTree = checkNotNull(componentTree.context.lithoTree)
    val reflowKey = singleReflowKey(lithoTree)
    // Guards against a vacuous pass: without the seam firing, the target stays 100 — the same value
    // the final assertion expects — so the test would pass even if the interleaving never happened.
    var seamFired = false

    componentTree.setFutureExecutionListener(
        object : TreeFuture.FutureExecutionListener {
          override fun onPreExecution(
              version: Int,
              futureExecutionType: TreeFuture.FutureExecutionType,
              attribution: String,
          ) = Unit

          override fun onPostExecution(version: Int, released: Boolean, attribution: String) {
            // After the layout walk (old target read, unchanged branch), before its commit/mount.
            if (attribution == LayoutTreeFuture.DESCRIPTION) {
              componentTree.setFutureExecutionListener(null)
              checkNotNull(
                  (lithoTree.reflowProvider.reflowView as ReflowGeneration).entry(reflowKey),
              )
                  .targetSize = IntSize(100, 999)
              seamFired = true
            }
          }
        },
    )

    // A real relayout that keeps grow's natural at 100; the listener moves the target mid-flight.
    stateCaller.update()
    lithoTestRule.idle()

    assertThat(seamFired)
        .describedAs("walk/mount seam must have fired, else the reconciliation is untested")
        .isTrue()
    // Mount reconciliation restored this layout's natural, not the target injected after its walk.
    assertThat(checkNotNull(lithoTree.reflowProvider.reflowView).targetSize(reflowKey))
        .isEqualTo(IntSize(100, 100))
    assertThat(grow.height).isEqualTo(100)
  }

  @Test
  fun deferredReflowBounds_targetMovesBetweenWalkAndMount_reconciledToCommittedTree() {
    val stateCaller = StateCaller()
    val testLithoView = lithoTestRule.render {
      RenderWithConstraints { constantReflowTogglingSibling(stateCaller) }
    }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()

    val grow = testLithoView.findViewWithTag(GROW_TAG)
    assertThat(grow.height).isEqualTo(100)

    val componentTree = checkNotNull(testLithoView.lithoView.componentTree)
    val lithoTree = checkNotNull(componentTree.context.lithoTree)
    val reflowKey = singleReflowKey(lithoTree)
    var seamFired = false

    componentTree.setFutureExecutionListener(
        object : TreeFuture.FutureExecutionListener {
          override fun onPreExecution(
              version: Int,
              futureExecutionType: TreeFuture.FutureExecutionType,
              attribution: String,
          ) = Unit

          override fun onPostExecution(version: Int, released: Boolean, attribution: String) {
            if (attribution == LayoutTreeFuture.DESCRIPTION) {
              componentTree.setFutureExecutionListener(null)
              checkNotNull(
                  (lithoTree.reflowProvider.reflowView as ReflowGeneration).entry(reflowKey),
              )
                  .targetSize = IntSize(100, 999)
              seamFired = true
            }
          }
        },
    )

    stateCaller.update()
    lithoTestRule.idle()

    assertThat(seamFired).isTrue()
    assertThat(checkNotNull(lithoTree.reflowProvider.reflowView).targetSize(reflowKey))
        .isEqualTo(IntSize(100, 100))
    assertThat(grow.height).isEqualTo(100)
  }

  @Test
  fun reflowBounds_inFramePath_convergesAndReflowsSibling() {
    val stateCaller = StateCaller()
    val component = growComponent(stateCaller, collapsedHeight = 100, expandedHeight = 250)

    val testLithoView = inFrameTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()

    val grow = testLithoView.findViewWithTag(GROW_TAG)
    val sibling = testLithoView.findViewWithTag(SIBLING_TAG)

    assertThat(grow.height).isEqualTo(100)
    assertThat(sibling.top).isEqualTo(100)

    stateCaller.update()
    inFrameTestRule.idle()

    assertThat(grow.height).isEqualTo(250)
    assertThat(sibling.top).isEqualTo(250)
  }

  @Test
  fun sameLithoView_treeSwap_rebindsReflowStoreToNewTree() {
    val firstView = lithoTestRule.render {
      growComponent(StateCaller(), collapsedHeight = 100, expandedHeight = 250)
    }
    val firstTree = checkNotNull(firstView.lithoView.componentTree)
    val firstLithoTree = checkNotNull(firstTree.context.lithoTree)
    assertThat(checkNotNull(firstLithoTree.reflowProvider.reflowView))
        .isNotSameAs(ReflowGeneration.EMPTY)

    val secondTree = ComponentTree.create(firstTree.context).build()
    secondTree.root = growComponent(StateCaller(), collapsedHeight = 100, expandedHeight = 250)
    firstView.useComponentTree(secondTree).measure().layout()

    val secondLithoTree = checkNotNull(secondTree.context.lithoTree)
    assertThat(firstLithoTree.reflowProvider.reflowView).isSameAs(ReflowGeneration.EMPTY)
    assertThat(checkNotNull(secondLithoTree.reflowProvider.reflowView))
        .isNotSameAs(ReflowGeneration.EMPTY)
  }

  @Test
  fun nullWithoutUnmount_thenTreeRelease_clearsReflowStore() {
    val testView = lithoTestRule.render {
      growComponent(StateCaller(), collapsedHeight = 100, expandedHeight = 250)
    }
    val rawLithoView = testView.lithoView
    val tree = checkNotNull(rawLithoView.componentTree)
    val lithoTree = checkNotNull(tree.context.lithoTree)
    assertThat(checkNotNull(lithoTree.reflowProvider.reflowView))
        .isNotSameAs(ReflowGeneration.EMPTY)

    rawLithoView.setComponentTree(null, false)
    assertThat(checkNotNull(lithoTree.reflowProvider.reflowView))
        .isNotSameAs(ReflowGeneration.EMPTY)

    tree.release()
    assertThat(lithoTree.reflowProvider.reflowView).isSameAs(ReflowGeneration.EMPTY)
  }

  private fun growComponent(
      stateCaller: StateCaller,
      collapsedHeight: Int,
      expandedHeight: Int,
      transition: StyleTransitionScope.() -> Unit = {
        bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
      },
  ) =
      TestAnimationsComponent(stateCaller, null) { state ->
        Column(style = Style.width(100.dp)) {
          child(
              Row(
                  style =
                      Style.width(100.dp)
                          .height(if (state) expandedHeight.dp else collapsedHeight.dp)
                          .backgroundColor(Color.RED)
                          .transition(transition)
                          .viewTag(GROW_TAG),
              ),
          )
          child(
              Row(
                  style =
                      Style.width(100.dp)
                          .height(100.dp)
                          .backgroundColor(Color.BLUE)
                          .viewTag(SIBLING_TAG),
              ),
          )
        }
      }

  /**
   * A reflow node whose natural height is a constant 100 regardless of [stateCaller] state, next to
   * an unrelated sibling that toggles with state — so `stateCaller.update()` forces a real
   * (non-animation-frame) relayout that keeps the reflow node wanting 100.
   */
  private fun constantReflowTogglingSibling(stateCaller: StateCaller) =
      TestAnimationsComponent(stateCaller, null) { state ->
        Column(style = Style.width(100.dp)) {
          child(
              Row(
                  style =
                      Style.width(100.dp)
                          .height(100.dp)
                          .backgroundColor(Color.RED)
                          .transition {
                            bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                          }
                          .viewTag(GROW_TAG),
              ),
          )
          child(
              Row(
                  style =
                      Style.width(100.dp)
                          .height(if (state) 50.dp else 100.dp)
                          .backgroundColor(Color.BLUE)
                          .viewTag(SIBLING_TAG),
              ),
          )
        }
      }

  private fun singleReflowKey(lithoTree: LithoTree): GlobalKey {
    var key: GlobalKey? = null
    (checkNotNull(lithoTree.reflowProvider.reflowView) as ReflowGeneration).forEachEntry { k, _ ->
      key = k
    }
    return checkNotNull(key)
  }

  companion object {
    private const val GROW_TAG = "reflow_grow"
    private const val SIBLING_TAG = "reflow_sibling"
  }
}
