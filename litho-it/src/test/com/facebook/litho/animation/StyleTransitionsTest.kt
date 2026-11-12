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
import android.graphics.Rect
import android.view.View
import com.facebook.litho.AnimatableItem
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.OutputUnitType
import com.facebook.litho.OutputUnitsAffinityGroup
import com.facebook.litho.StateCaller
import com.facebook.litho.Style
import com.facebook.litho.Transition.TransitionKeyType
import com.facebook.litho.TransitionId
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.width
import com.facebook.litho.key
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.stats.LithoStats
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TransitionTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.transition.transitionKey
import com.facebook.litho.useState
import com.facebook.litho.view.alpha
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.onClick
import com.facebook.litho.view.scale
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.TestAnimationsComponent
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@LooperMode(LooperMode.Mode.LEGACY)
@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
class StyleTransitionsTest {
  @JvmField @Rule val lithoTestRule = LithoTestRule()
  @JvmField @Rule val transitionTestRule = TransitionTestRule()

  private lateinit var activityController: ActivityController<Activity>

  @Before
  fun setUp() {
    activityController = Robolectric.buildActivity(Activity::class.java, Intent())
  }

  @Test
  fun subtreeCaching_preservesStyleTransitionTargets() {
    val previousCaching = ComponentsConfiguration.enableSubtreeCaching
    try {
      ComponentsConfiguration.enableSubtreeCaching = true
      val testView = lithoTestRule.render { CachedStyleTransitionRoot() }
      lithoTestRule.idle()
      val initial = checkNotNull(testView.committedLayoutState).styleTransitionTargets
      assertThat(initial).hasSize(1)

      repeat(3) {
        LithoStats.resetAllCounters()
        lithoTestRule.act(testView) { clickOnText("revision-$it") }
        val updated = checkNotNull(testView.committedLayoutState).styleTransitionTargets

        assertThat(LithoStats.subtreeCacheHitCount.get()).isGreaterThan(0)
        assertThat(updated.map { it.styleTransitionId to it.data })
            .isEqualTo(initial.map { it.styleTransitionId to it.data })
        assertThat(updated.map { it.animatableItem?.getId() })
            .isEqualTo(initial.map { it.animatableItem?.getId() })
        assertThat(updated.map { it.animatableItem?.getAbsoluteBounds() })
            .isEqualTo(initial.map { it.animatableItem?.getAbsoluteBounds() })
      }
    } finally {
      ComponentsConfiguration.enableSubtreeCaching = previousCaching
    }
  }

  @Test
  fun styleTransition_boundsExpandsToMountSpaceProperties() {
    val boundsSpec = tween<Float>(120.ms)

    val spec = transitionSpec { bounds = boundsSpec }

    assertThat(spec.changes)
        .containsEntry(StyleTransitionProperty.X, boundsSpec)
        .containsEntry(StyleTransitionProperty.Y, boundsSpec)
        .containsEntry(StyleTransitionProperty.WIDTH, boundsSpec)
        .containsEntry(StyleTransitionProperty.HEIGHT, boundsSpec)
  }

  @Test
  fun styleTransition_boundsReflow_setsLayoutAffecting() {
    val boundsSpec = tween<Float>(120.ms)

    val reflow = transitionSpec { bounds(boundsSpec, reflow = true) }
    val visualFn = transitionSpec { bounds(boundsSpec) }
    val visualProp = transitionSpec { bounds = boundsSpec }

    // reflow = true opts the size change into layout-affecting reflow; the visual forms do not.
    assertThat(reflow.shouldLayout).isTrue()
    assertThat(visualFn.shouldLayout).isFalse()
    assertThat(visualProp.shouldLayout).isFalse()
    // Both forms still expand to the same mount-space properties.
    assertThat(reflow.changes)
        .containsEntry(StyleTransitionProperty.WIDTH, boundsSpec)
        .containsEntry(StyleTransitionProperty.HEIGHT, boundsSpec)
  }

  @Test
  fun styleTransition_perAxisAndGroupPropertiesMapToTheirAxes() {
    val axisSpec = tween<Float>(120.ms)

    assertThat(transitionSpec { x = axisSpec }.changes).containsOnlyKeys(StyleTransitionProperty.X)
    assertThat(transitionSpec { y = axisSpec }.changes).containsOnlyKeys(StyleTransitionProperty.Y)
    assertThat(transitionSpec { width = axisSpec }.changes)
        .containsOnlyKeys(StyleTransitionProperty.WIDTH)
    assertThat(transitionSpec { height = axisSpec }.changes)
        .containsOnlyKeys(StyleTransitionProperty.HEIGHT)
    assertThat(transitionSpec { position = axisSpec }.changes)
        .containsOnlyKeys(StyleTransitionProperty.X, StyleTransitionProperty.Y)
    assertThat(transitionSpec { size = axisSpec }.changes)
        .containsOnlyKeys(StyleTransitionProperty.WIDTH, StyleTransitionProperty.HEIGHT)
  }

  @Test
  fun styleTransition_groupGetters_readTheSpecOnlyWhenEveryAxisSharesIt() {
    val groupSpec = spring<Float>()
    val heightSpec = tween<Float>(120.ms)

    transitionSpec {
      bounds = groupSpec
      assertThat(bounds).isSameAs(groupSpec)

      // Height now differs, so the groups covering it read null; position is still uniform.
      height = heightSpec
      assertThat(bounds).isNull()
      assertThat(size).isNull()
      assertThat(position).isSameAs(groupSpec)
      assertThat(height).isSameAs(heightSpec)
    }
    transitionSpec {
      // An axis left unset means the group is not declared as a whole.
      x = groupSpec
      assertThat(position).isNull()
    }
  }

  @Test
  fun styleTransition_onlySizeAndBoundsSetTheReflowMode() {
    val sizeSpec = spring<Float>()
    val heightSpec = tween<Float>(120.ms)

    val reflowRefined = transitionSpec {
      size(sizeSpec, reflow = true)
      height = heightSpec
      position = heightSpec
    }
    val reflowCleared = transitionSpec {
      size(sizeSpec, reflow = true)
      size = sizeSpec
    }

    // Per-axis and position setters refine a spec without leaving reflow.
    assertThat(reflowRefined.shouldLayout).isTrue()
    assertThat(reflowRefined.changes)
        .containsEntry(StyleTransitionProperty.WIDTH, sizeSpec)
        .containsEntry(StyleTransitionProperty.HEIGHT, heightSpec)
    // The size property is the visual group form, so it clears reflow like the bounds property.
    assertThat(reflowCleared.shouldLayout).isFalse()
    assertThat(transitionSpec { size(sizeSpec) }.shouldLayout).isFalse()
  }

  @Test
  fun styleTransitionPlanner_singleAxis_animatesOnlyThatAxisWhenOthersChange() {
    val transitionId = StructuralStyleTransitionId("globalKey")
    val spec = transitionSpec { height = tween(120.ms) }
    val previousTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = spec,
        animatableItem = animatableItem(id = 1, bounds = Rect(0, 0, 40, 50)),
    )
    // x, y, width and height all change; only the declared height animates.
    val nextTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = spec,
        animatableItem = animatableItem(id = 2, bounds = Rect(10, 20, 70, 120)),
    )

    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(previousTarget),
            nextTargets = listOf(nextTarget),
            runningKeys = emptySet(),
        )

    assertThat(plans.map { it.property }).containsExactly(StyleTransitionProperty.HEIGHT)
    assertThat(plans.single().initialValue).isEqualTo(50f)
    assertThat(plans.single().targetValue).isEqualTo(100f)
  }

  @Test
  fun styleTransition_plusComposesLayoutAffecting() {
    val visual = transitionSpec { bounds(tween(120.ms)) }
    val reflow = transitionSpec { bounds(tween(120.ms), reflow = true) }

    // shouldLayout is OR-composed so a reflow declaration is not lost when combined.
    assertThat((visual + reflow).shouldLayout).isTrue()
    assertThat((reflow + visual).shouldLayout).isTrue()
    assertThat((visual + visual).shouldLayout).isFalse()
  }

  @Test
  fun styleTransition_plusCarriesRemovals() {
    val motion = tween<Float>(120.ms)

    val removed = transitionSpec { height = motion } + transitionSpec { height = null }
    val redeclared = transitionSpec { height = null } + transitionSpec { height = motion }

    assertThat(removed.changes[StyleTransitionProperty.HEIGHT]).isNull()
    assertThat(redeclared.changes).containsEntry(StyleTransitionProperty.HEIGHT, motion)
  }

  @Test
  fun styleTransition_reflowWithEverySizeAxisCleared_doesNotLayout() {
    val motion = tween<Float>(120.ms)

    val cleared = transitionSpec {
      size(motion, reflow = true)
      width = null
      height = null
    }
    val clearedByComposition =
        transitionSpec { size(motion, reflow = true) } +
            transitionSpec {
              width = null
              height = null
            }

    for (spec in listOf(cleared, clearedByComposition)) {
      assertThat(spec.shouldLayout).isFalse()
      assertThat(spec.reflowMode).isTrue()
    }
  }

  @Test
  fun styleTransition_plusComposesAndLastPropertyWins() {
    val firstAlpha = tween<Float>(120.ms)
    val secondAlpha = spring<Float>()
    val scaleSpec = spring<Float>()

    val first = transitionSpec { alpha = firstAlpha }
    val second = transitionSpec {
      alpha = secondAlpha
      scale = scaleSpec
    }

    val combined = first + second

    assertThat(combined.changes[StyleTransitionProperty.ALPHA]).isSameAs(secondAlpha)
    assertThat(combined.changes[StyleTransitionProperty.SCALE]).isSameAs(scaleSpec)
  }

  @Test
  fun styleTransitionPlanner_createsChangePlansForChangedProperties() {
    val transitionId = StructuralStyleTransitionId("globalKey")
    val spec = tween<Float>(120.ms)
    val previousTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds = spec },
        animatableItem =
            animatableItem(
                id = 1,
                bounds = Rect(0, 10, 40, 50),
            ),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds = spec },
        animatableItem =
            animatableItem(
                id = 2,
                bounds = Rect(10, 10, 60, 50),
            ),
    )

    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(previousTarget),
            nextTargets = listOf(nextTarget),
            runningKeys = emptySet(),
        )

    assertThat(plans.map { it.property })
        .containsExactly(StyleTransitionProperty.X, StyleTransitionProperty.WIDTH)
    assertThat(plans.map { it.initialValue }).containsExactly(0f, 40f)
    assertThat(plans.map { it.targetValue }).containsExactly(10f, 50f)
  }

  @Test
  fun styleTransitionPlanner_keepsRunningPropertyPlanWhenTargetValueIsUnchanged() {
    val transitionId = StructuralStyleTransitionId("globalKey")
    val spec = tween<Float>(120.ms)
    val previousTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { alpha = spec },
        animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { alpha = spec },
        animatableItem = animatableItem(id = 2, alpha = 1f, isAlphaSet = true),
    )

    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(previousTarget),
            nextTargets = listOf(nextTarget),
            runningKeys =
                setOf(StyleTransitionAnimationKey(transitionId, StyleTransitionProperty.ALPHA)),
        )

    assertThat(plans).hasSize(1)
    assertThat(plans[0].initialValue).isEqualTo(1f)
    assertThat(plans[0].targetValue).isEqualTo(1f)
  }

  @Test
  fun styleTransitionPlanner_shouldLayout_loneReflowNode_animatesPositionSkipsSize() {
    val transitionId = StructuralStyleTransitionId("globalKey")
    val spec = tween<Float>(120.ms)
    // x 0 -> 10 and width 40 -> 60 on a reflow (layout-affecting) node.
    val previousTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 1, bounds = Rect(0, 10, 40, 50)),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 2, bounds = Rect(10, 10, 70, 50)),
    )

    // No OTHER reflow node is resizing, so this node's position is not relayout-driven — its x/y
    // shift animates as a transform. Its own width/height stay relayout-driven and are not planned.
    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(previousTarget),
            nextTargets = listOf(nextTarget),
            runningKeys = emptySet(),
        )

    assertThat(plans.map { it.property }).containsExactly(StyleTransitionProperty.X)
    assertThat(plans[0].initialValue).isEqualTo(0f)
    assertThat(plans[0].targetValue).isEqualTo(10f)
  }

  @Test
  fun styleTransitionPlanner_shouldLayout_positionDrivenByOtherResizingNode_skipsPositionAxes() {
    val idA = StructuralStyleTransitionId("A")
    val idB = StructuralStyleTransitionId("B")
    val spec = tween<Float>(120.ms)
    // A (above) grows — height 40 -> 100 — and also shifts x 0 -> 10.
    val prevA = styleTransitionTarget(
        transitionId = idA,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 1, bounds = Rect(0, 0, 40, 40)),
    )
    val nextA = styleTransitionTarget(
        transitionId = idA,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 2, bounds = Rect(10, 0, 50, 100)),
    )
    // B (below) keeps its size but is pushed down (y 40 -> 100) by A's growth.
    val prevB = styleTransitionTarget(
        transitionId = idB,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 3, bounds = Rect(0, 40, 40, 80)),
    )
    val nextB = styleTransitionTarget(
        transitionId = idB,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 4, bounds = Rect(0, 100, 40, 140)),
    )

    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(prevA, prevB),
            nextTargets = listOf(nextA, nextB),
            runningKeys = emptySet(),
        )

    // B's position is relayout-driven by A's resize, so B is not planned. A — the resizing node —
    // still animates its own x shift, since no other reflow node drives A's position.
    assertThat(plans.filter { it.key.styleTransitionId == idB }).isEmpty()
    assertThat(plans.filter { it.key.styleTransitionId == idA }.map { it.property })
        .containsExactly(StyleTransitionProperty.X)
  }

  @Test
  fun styleTransitionPlanner_reflowModeWithoutSize_skipsPositionDrivenByOtherResizingNode() {
    val idA = StructuralStyleTransitionId("A")
    val idB = StructuralStyleTransitionId("B")
    val spec = tween<Float>(120.ms)
    val resizing = transitionSpec { bounds(spec, reflow = true) }
    val positionOnly = transitionSpec {
      bounds(spec, reflow = true)
      width = null
      height = null
    }
    val prevA = styleTransitionTarget(
        transitionId = idA,
        spec = resizing,
        animatableItem = animatableItem(id = 1, bounds = Rect(0, 0, 40, 40)),
    )
    val nextA = styleTransitionTarget(
        transitionId = idA,
        spec = resizing,
        animatableItem = animatableItem(id = 2, bounds = Rect(0, 0, 40, 100)),
    )
    val prevB = styleTransitionTarget(
        transitionId = idB,
        spec = positionOnly,
        animatableItem = animatableItem(id = 3, bounds = Rect(0, 40, 40, 80)),
    )
    val nextB = styleTransitionTarget(
        transitionId = idB,
        spec = positionOnly,
        animatableItem = animatableItem(id = 4, bounds = Rect(0, 100, 40, 140)),
    )

    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(prevA, prevB),
            nextTargets = listOf(nextA, nextB),
            runningKeys = emptySet(),
        )

    // B no longer lays out, but A's reflow already moves it frame by frame; a transform on top
    // would double the motion.
    assertThat(positionOnly.shouldLayout).isFalse()
    assertThat(plans.filter { it.key.styleTransitionId == idB }).isEmpty()
  }

  @Test
  fun styleTransitionPlanner_shouldLayout_quiescent_animatesPositionOnly() {
    val transitionId = StructuralStyleTransitionId("globalKey")
    val spec = tween<Float>(120.ms)
    // Position-only change: x 0 -> 10, size unchanged (40x40).
    val previousTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 1, bounds = Rect(0, 10, 40, 50)),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 2, bounds = Rect(10, 10, 50, 50)),
    )

    // No other reflow node is resizing, so a position-only change on a reflow node falls through to
    // the draw-space transform (only X — y is unchanged and w/h are always relayout-driven).
    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(previousTarget),
            nextTargets = listOf(nextTarget),
            runningKeys = emptySet(),
        )

    assertThat(plans.map { it.property }).containsExactly(StyleTransitionProperty.X)
    assertThat(plans[0].initialValue).isEqualTo(0f)
    assertThat(plans[0].targetValue).isEqualTo(10f)
  }

  @Test
  fun styleTransitionPlanner_shouldLayout_neverAnimatesSizeAxes() {
    val transitionId = StructuralStyleTransitionId("globalKey")
    val spec = tween<Float>(120.ms)
    // Size-only change: width 40 -> 60, position unchanged.
    val previousTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 1, bounds = Rect(0, 10, 40, 50)),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = transitionId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 2, bounds = Rect(0, 10, 60, 50)),
    )

    // A reflow node's width/height are driven by the relayout, never the visual manager — a
    // size-only change produces no plans (isolating this from the position-only case).
    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(previousTarget),
            nextTargets = listOf(nextTarget),
            runningKeys = emptySet(),
        )

    assertThat(plans).isEmpty()
  }

  @Test
  fun styleTransitionPlanner_nonLayoutAffecting_animatesBounds_besideResizingReflowNode() {
    val visualId = StructuralStyleTransitionId("visual")
    val reflowId = StructuralStyleTransitionId("reflow")
    val spec = tween<Float>(120.ms)
    // A visual (not layout-affecting) node shifting x 0 -> 10 and widening 40 -> 60.
    val prevVisual = styleTransitionTarget(
        transitionId = visualId,
        spec = transitionSpec { bounds = spec },
        animatableItem = animatableItem(id = 1, bounds = Rect(0, 10, 40, 50)),
    )
    val nextVisual = styleTransitionTarget(
        transitionId = visualId,
        spec = transitionSpec { bounds = spec },
        animatableItem = animatableItem(id = 2, bounds = Rect(10, 10, 60, 50)),
    )
    // A reflow node resizing right alongside it (height 40 -> 100).
    val prevReflow = styleTransitionTarget(
        transitionId = reflowId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 3, bounds = Rect(0, 0, 40, 40)),
    )
    val nextReflow = styleTransitionTarget(
        transitionId = reflowId,
        spec = transitionSpec { bounds(spec, reflow = true) },
        animatableItem = animatableItem(id = 4, bounds = Rect(0, 0, 40, 100)),
    )

    // Suppression only applies to layout-affecting (reflow) nodes; a visual bounds transition
    // animates x/width regardless of a resizing reflow node nearby.
    val plans =
        StyleTransitionPlanner.createPlans(
            previousTargets = listOf(prevVisual, prevReflow),
            nextTargets = listOf(nextVisual, nextReflow),
            runningKeys = emptySet(),
        )

    assertThat(plans.filter { it.key.styleTransitionId == visualId }.map { it.property })
        .containsExactly(StyleTransitionProperty.X, StyleTransitionProperty.WIDTH)
  }

  @Test
  fun styleTransitionManager_cancelsStaleNonExitAnimationWhenNextMountHasNoPlans() {
    ShadowLooper.pauseMainLooper()
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_stale_change")
    val view = View(activityController.get()).apply { alpha = 1f }
    val previousTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = tween(duration = 1_000.ms, easing = Easing.Linear) },
        animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = tween(duration = 1_000.ms, easing = Easing.Linear) },
        animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
    )

    manager.setup(previousTargets = listOf(previousTarget), nextTargets = listOf(nextTarget))
    manager.run(StyleTransitionContentResolver { listOf(view) })

    val removedTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec {},
        animatableItem = animatableItem(id = 3),
    )
    manager.setup(previousTargets = listOf(removedTarget), nextTargets = emptyList())
    manager.run(StyleTransitionContentResolver { emptyList() })
    view.alpha = 0.5f

    ShadowLooper.runUiThreadTasks()

    assertThat(view.alpha).isEqualTo(0.5f)
  }

  @Test
  fun styleTransitionManager_keepsRunningWhenTargetUnchangedAcrossRemount() {
    ShadowLooper.pauseMainLooper()
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_plan_remount_change")
    val animationSpec = CountingAnimationSpec()
    val view = View(activityController.get()).apply { alpha = 1f }
    val previousTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = animationSpec },
        animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = animationSpec },
        animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
    )

    manager.setup(previousTargets = listOf(previousTarget), nextTargets = listOf(nextTarget))
    manager.run(StyleTransitionContentResolver { listOf(view) })
    manager.setup(previousTargets = listOf(nextTarget), nextTargets = listOf(nextTarget))
    manager.run(StyleTransitionContentResolver { listOf(view) })

    assertThat(animationSpec.createAnimatorCalls).isEqualTo(1)
    assertThat(view.alpha).isEqualTo(1f)

    manager.reset()
  }

  @Test
  fun styleTransitionManager_removesAnimationWhenPropertyLeavesNextSpec() {
    ShadowLooper.pauseMainLooper()
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_property_removed")
    val animationSpec = CountingAnimationSpec()
    val view = View(activityController.get()).apply { alpha = 1f }
    val previousTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = animationSpec },
        animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = animationSpec },
        animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
    )
    val targetWithoutAlpha = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec {},
        animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
    )

    manager.setup(previousTargets = listOf(previousTarget), nextTargets = listOf(nextTarget))
    manager.run(contentResolverByRenderUnitId(2L to view))

    manager.setup(
        previousTargets = listOf(nextTarget),
        nextTargets = listOf(targetWithoutAlpha),
    )
    manager.run(contentResolverByRenderUnitId(2L to view))
    view.alpha = 0.2f

    ShadowLooper.runUiThreadTasks()

    assertThat(view.alpha).isEqualTo(0.2f)

    manager.reset()
  }

  @Test
  fun styleTransitionManager_rejectsInfiniteAnimatorFromFiniteSpec() {
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_infinite_animator")
    val spec = transitionSpec { alpha = CountingAnimationSpec(Animator.DURATION_INFINITE) }
    val previousTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = spec,
        animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = spec,
        animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
    )
    manager.setup(previousTargets = listOf(previousTarget), nextTargets = listOf(nextTarget))

    assertThatThrownBy {
          manager.run(StyleTransitionContentResolver { listOf(View(activityController.get())) })
        }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Style transitions require an animator with a finite duration")
  }

  @Test
  fun styleTransitionManager_recreatesAnimatorWhenSpecChangesAcrossRemount() {
    ShadowLooper.pauseMainLooper()
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_spec_change")
    val firstSpec = CountingAnimationSpec()
    val secondSpec = CountingAnimationSpec()
    val view = View(activityController.get()).apply { alpha = 1f }
    val previousTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = firstSpec },
        animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
    )
    val nextTarget = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = firstSpec },
        animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
    )
    val nextTargetWithChangedSpec = styleTransitionTarget(
        transitionId = styleTransitionId,
        spec = transitionSpec { alpha = secondSpec },
        animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
    )

    manager.setup(previousTargets = listOf(previousTarget), nextTargets = listOf(nextTarget))
    manager.run(StyleTransitionContentResolver { listOf(view) })

    manager.setup(
        previousTargets = listOf(nextTarget),
        nextTargets = listOf(nextTargetWithChangedSpec),
    )
    manager.run(StyleTransitionContentResolver { listOf(view) })

    assertThat(firstSpec.createAnimatorCalls).isEqualTo(1)
    assertThat(secondSpec.createAnimatorCalls).isEqualTo(1)
    assertThat(view.alpha).isEqualTo(1f)

    manager.reset()
  }

  @Test
  fun styleTransitionManager_resetCancelsScheduledFrame() {
    ShadowLooper.pauseMainLooper()
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_reset")
    val firstSpec = CountingAnimationSpec()
    val secondSpec = CountingAnimationSpec()
    val view = View(activityController.get()).apply { alpha = 1f }

    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = firstSpec },
                    animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = firstSpec },
                    animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
                ),
            ),
    )
    manager.run(StyleTransitionContentResolver { listOf(view) })

    manager.reset()
    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = secondSpec },
                    animatableItem = animatableItem(id = 3, alpha = 1f, isAlphaSet = true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = secondSpec },
                    animatableItem = animatableItem(id = 4, alpha = 0.2f, isAlphaSet = true),
                ),
            ),
    )
    manager.run(StyleTransitionContentResolver { listOf(view) })

    ShadowLooper.runUiThreadTasks()

    assertThat(firstSpec.valueCalls).isEqualTo(0)
    assertThat(secondSpec.valueCalls).isEqualTo(1)
  }

  @Test
  fun styleTransition_keylessBoundsAnimation_usesStructuralId() {
    val stateCaller = StateCaller()
    val component =
        TestAnimationsComponent(stateCaller, null) { state ->
          Row(
              justifyContent = if (state) YogaJustify.FLEX_START else YogaJustify.FLEX_END,
              alignItems = if (state) YogaAlign.FLEX_START else YogaAlign.FLEX_END,
              style = Style.height(200.dp).width(200.dp),
          ) {
            child(
                Row(
                    style =
                        Style.height(40.dp)
                            .width(40.dp)
                            .backgroundColor(Color.RED)
                            .transition {
                              bounds = tween(duration = 144.ms, easing = Easing.Linear)
                            }
                            .viewTag(KEYLESS_BOUNDS_TAG),
                ),
            )
          }
        }

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val view = testLithoView.findViewWithTag(KEYLESS_BOUNDS_TAG)

    assertThat(view.x).isEqualTo(160f)
    assertThat(view.y).isEqualTo(160f)

    stateCaller.update()
    lithoTestRule.idle()

    assertThat(view.x).isEqualTo(0f)
    assertThat(view.y).isEqualTo(0f)
  }

  @Test
  fun styleTransition_keylessSiblingHosts_fromSameComponentScopeDoNotCollide() {
    val stateCaller = StateCaller()
    val component =
        TestAnimationsComponent(stateCaller, null) { state ->
          Column(style = Style.width(80.dp)) {
            child(keylessSiblingRow(KEYLESS_SIBLING_A, if (state) 0.2f else 1f))
            child(keylessSiblingRow(KEYLESS_SIBLING_B, if (state) 0.4f else 1f))
          }
        }

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val siblingA = testLithoView.findViewWithTag(KEYLESS_SIBLING_A)
    val siblingB = testLithoView.findViewWithTag(KEYLESS_SIBLING_B)

    assertThat(siblingA.alpha).isEqualTo(1f)
    assertThat(siblingB.alpha).isEqualTo(1f)

    stateCaller.update()

    assertThat(siblingA.alpha).isEqualTo(1f)
    assertThat(siblingB.alpha).isEqualTo(1f)

    lithoTestRule.idle()

    assertThat(siblingA.alpha).isEqualTo(0.2f)
    assertThat(siblingB.alpha).isEqualTo(0.4f)
  }

  @Test
  fun styleTransition_propertyChangeRestoresPreviousVisualStateBeforeAnimating() {
    val stateCaller = StateCaller()
    val component =
        TestAnimationsComponent(stateCaller, null) { state ->
          Row(
              style =
                  Style.height(40.dp)
                      .width(40.dp)
                      .alpha(if (state) 0.2f else 1f)
                      .scale(if (state) 0.5f else 1f)
                      .backgroundColor(Color.RED)
                      .transition {
                        alpha = tween(duration = 144.ms, easing = Easing.Linear)
                        scale = tween(duration = 144.ms, easing = Easing.Linear)
                      }
                      .viewTag(PROPERTY_CHANGE_TAG),
          )
        }

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val view = testLithoView.findViewWithTag(PROPERTY_CHANGE_TAG)

    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.scaleX).isEqualTo(1f)

    stateCaller.update()

    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.scaleX).isEqualTo(1f)

    lithoTestRule.idle()

    assertThat(view.alpha).isEqualTo(0.2f)
    assertThat(view.scaleX).isEqualTo(0.5f)
  }

  @Test
  fun styleTransition_propertyChangeWorksWithKComponentStateUpdates() {
    val testLithoView = lithoTestRule.render { PropertyChangeTestComponent() }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val view = testLithoView.findViewWithTag(PROPERTY_CHANGE_TAG)

    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.scaleX).isEqualTo(1f)

    view.performClick()

    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.scaleX).isEqualTo(1f)

    lithoTestRule.idle()

    assertThat(view.alpha).isEqualTo(0.2f)
    assertThat(view.scaleX).isEqualTo(0.5f)
  }

  @Test
  fun styleTransition_multipleKeylessEntriesCompose() {
    val testLithoView = lithoTestRule.render { MultipleKeylessTransitionsTestComponent() }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val view = testLithoView.findViewWithTag(MULTIPLE_KEYLESS_TRANSITIONS_TAG)

    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.scaleX).isEqualTo(1f)

    view.performClick()

    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.scaleX).isEqualTo(1f)

    lithoTestRule.idle()

    assertThat(view.alpha).isEqualTo(0.2f)
    assertThat(view.scaleX).isEqualTo(0.5f)
  }

  @Test
  fun styleTransition_failsFastWhenMixedWithExplicitTransitionKey() {
    assertThatThrownBy { lithoTestRule.render { ExplicitTransitionKeyTestComponent() } }
        .hasMessageContaining("Style.transition cannot be combined with Style.transitionKey")
  }

  @Test
  fun styleTransition_propertyChangeWorksWithReusableStyleMotion() {
    val testLithoView = lithoTestRule.render { ReusableMotionTestComponent() }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val selected = testLithoView.findViewWithTag(REUSABLE_ITEM_A)
    val nextSelected = testLithoView.findViewWithTag(REUSABLE_ITEM_B)

    assertThat(selected.alpha).isEqualTo(1f)
    assertThat(selected.scaleX).isEqualTo(1f)
    assertThat(nextSelected.alpha).isEqualTo(0.72f)
    assertThat(nextSelected.scaleX).isEqualTo(0.96f)

    nextSelected.performClick()

    assertThat(selected.alpha).isEqualTo(1f)
    assertThat(selected.scaleX).isEqualTo(1f)
    assertThat(nextSelected.alpha).isEqualTo(0.72f)
    assertThat(nextSelected.scaleX).isEqualTo(0.96f)

    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
    ShadowLooper.idleMainLooper(70, TimeUnit.MILLISECONDS)

    assertThat(selected.alpha).isBetween(0.72f, 1f)
    assertThat(nextSelected.alpha).isBetween(0.72f, 1f)
    assertThat(selected.scaleX).isBetween(0.96f, 1f)
    assertThat(nextSelected.scaleX).isBetween(0.96f, 1f)
    assertThat(selected.alpha).isNotEqualTo(0.72f)
    assertThat(nextSelected.alpha).isNotEqualTo(1f)
    assertThat(selected.scaleX).isNotEqualTo(0.96f)
    assertThat(nextSelected.scaleX).isNotEqualTo(1f)

    lithoTestRule.idle()

    assertThat(selected.alpha).isEqualTo(0.72f)
    assertThat(selected.scaleX).isEqualTo(0.96f)
    assertThat(nextSelected.alpha).isEqualTo(1f)
    assertThat(nextSelected.scaleX).isEqualTo(1f)
  }

  @Test
  fun styleTransition_componentKey_reordersAcrossStructure() {
    val stateCaller = StateCaller()
    val component =
        TestAnimationsComponent(stateCaller, null) { state ->
          val first = if (state) ITEM_A else ITEM_B
          val second = if (state) ITEM_B else ITEM_A
          Column(style = Style.height(80.dp).width(40.dp)) {
            child(reorderRow(first))
            child(reorderRow(second))
          }
        }

    val testLithoView = lithoTestRule.render { component }
    activityController.get().setContentView(testLithoView.lithoView)
    activityController.resume().visible()
    val itemA = testLithoView.findViewWithTag(ITEM_A)
    val itemB = testLithoView.findViewWithTag(ITEM_B)

    assertThat(itemA.y).isEqualTo(40f)
    assertThat(itemB.y).isEqualTo(0f)

    stateCaller.update()
    lithoTestRule.idle()

    val updatedItemA = testLithoView.findViewWithTag(ITEM_A)
    val updatedItemB = testLithoView.findViewWithTag(ITEM_B)
    assertThat(updatedItemA.y).isEqualTo(0f)
    assertThat(updatedItemB.y).isEqualTo(40f)
  }

  private fun ComponentScope.reorderRow(id: String): Component =
      key(id) {
        Row(
            style =
                Style.height(40.dp)
                    .width(40.dp)
                    .backgroundColor(Color.RED)
                    .transition { bounds = tween(duration = 144.ms, easing = Easing.Linear) }
                    .viewTag(id),
        )
      }

  private fun transitionSpec(block: StyleTransitionScope.() -> Unit): StyleTransitionSpec =
      StyleTransitionScope().apply(block).build()

  private fun styleTransitionTarget(
      transitionId: StyleTransitionId,
      spec: StyleTransitionSpec,
      animatableItem: AnimatableItem,
  ): StyleTransitionTarget {
    val group = OutputUnitsAffinityGroup<AnimatableItem>()
    group.add(OutputUnitType.CONTENT, animatableItem)
    return StyleTransitionTarget(
        data = StyleTransitionData(spec = spec),
        styleTransitionId = transitionId,
        outputGroup = group,
    )
  }

  private fun contentResolverByRenderUnitId(
      vararg contentByRenderUnitId: Pair<Long, Any>,
  ): StyleTransitionContentResolver {
    val map = contentByRenderUnitId.associate { (renderUnitId, content) ->
      renderUnitId to listOf(content)
    }
    return StyleTransitionContentResolver { plan -> map[plan.renderUnitId].orEmpty() }
  }

  private fun animatableItem(
      id: Long,
      bounds: Rect = Rect(0, 0, 10, 10),
      alpha: Float = 1f,
      isAlphaSet: Boolean = false,
  ): AnimatableItem =
      object : AnimatableItem {
        override fun getId(): Long = id

        override fun getTransitionId(): TransitionId? = null

        override fun getOutputType(): Int = OutputUnitType.CONTENT

        override fun getAbsoluteBounds(): Rect = bounds

        override fun getScale(): Float = 1f

        override fun getAlpha(): Float = alpha

        override fun getRotation(): Float = 0f

        override fun getRotationX(): Float = 0f

        override fun getRotationY(): Float = 0f

        override fun isScaleSet(): Boolean = false

        override fun isAlphaSet(): Boolean = isAlphaSet

        override fun isRotationSet(): Boolean = false

        override fun isRotationXSet(): Boolean = false

        override fun isRotationYSet(): Boolean = false
      }

  private fun ComponentScope.keylessSiblingRow(tag: String, alpha: Float): Component = Row(
      style =
          Style.height(40.dp)
              .width(40.dp)
              .alpha(alpha)
              .backgroundColor(Color.RED)
              .transition { this.alpha = tween(duration = 144.ms, easing = Easing.Linear) }
              .viewTag(tag),
  )

  private class PropertyChangeTestComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val toggled = useState { false }
      return Row(
          style =
              Style.height(40.dp)
                  .width(40.dp)
                  .alpha(if (toggled.value) 0.2f else 1f)
                  .scale(if (toggled.value) 0.5f else 1f)
                  .backgroundColor(Color.RED)
                  .transition {
                    alpha = tween(duration = 144.ms, easing = Easing.Linear)
                    scale = tween(duration = 144.ms, easing = Easing.Linear)
                  }
                  .onClick { toggled.update { !it } }
                  .viewTag(PROPERTY_CHANGE_TAG),
      )
    }
  }

  private class MultipleKeylessTransitionsTestComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val toggled = useState { false }
      return Row(
          style =
              Style.height(40.dp)
                  .width(40.dp)
                  .alpha(if (toggled.value) 0.2f else 1f)
                  .scale(if (toggled.value) 0.5f else 1f)
                  .backgroundColor(Color.RED)
                  .transition { alpha = tween(duration = 144.ms, easing = Easing.Linear) }
                  .transition { scale = tween(duration = 144.ms, easing = Easing.Linear) }
                  .onClick { toggled.update { !it } }
                  .viewTag(MULTIPLE_KEYLESS_TRANSITIONS_TAG),
      )
    }
  }

  private class ExplicitTransitionKeyTestComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val toggled = useState { false }
      return Row(
          style =
              Style.height(40.dp)
                  .width(40.dp)
                  .alpha(if (toggled.value) 0.2f else 1f)
                  .backgroundColor(Color.RED)
                  .transitionKey(context, EXPLICIT_TRANSITION_KEY_TAG, TransitionKeyType.GLOBAL)
                  .transition { alpha = tween(duration = 144.ms, easing = Easing.Linear) }
                  .onClick { toggled.update { !it } }
                  .viewTag(EXPLICIT_TRANSITION_KEY_TAG),
      )
    }
  }

  private class ReusableMotionTestComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val selected = useState { REUSABLE_ITEM_A }
      val motion = Style.transition {
        bounds = tween(duration = 220.ms, easing = Easing.FastOutSlowIn)
        alpha = tween(duration = 140.ms)
        scale = spring()
      }
      return Column(style = Style.width(160.dp)) {
        child(
            reusableMotionRow(REUSABLE_ITEM_A, selected.value == REUSABLE_ITEM_A, motion) {
              selected.update(REUSABLE_ITEM_A)
            },
        )
        child(
            reusableMotionRow(REUSABLE_ITEM_B, selected.value == REUSABLE_ITEM_B, motion) {
              selected.update(REUSABLE_ITEM_B)
            },
        )
        child(
            reusableMotionRow(REUSABLE_ITEM_C, selected.value == REUSABLE_ITEM_C, motion) {
              selected.update(REUSABLE_ITEM_C)
            },
        )
      }
    }

    private fun ComponentScope.reusableMotionRow(
        id: String,
        isSelected: Boolean,
        motion: Style,
        onClick: () -> Unit,
    ): Component = Row(
        style =
            Style.width(if (isSelected) 120.dp else 100.dp)
                .height(if (isSelected) 48.dp else 36.dp)
                .margin(bottom = 8.dp)
                .alpha(if (isSelected) 1f else 0.72f)
                .scale(if (isSelected) 1f else 0.96f)
                .backgroundColor(Color.RED)
                .plus(motion)
                .onClick { onClick() }
                .viewTag(id),
    )
  }

  private class CachedStyleTransitionRoot : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(CachedUpdateSink())
      child(
          Column {
            child(
                Column(style = Style.alpha(1f).transition { alpha = tween<Float>(100.ms) }) {
                  repeat(3) { child(Text(text = "endpoint-$it")) }
                },
            )
            child(Column { repeat(3) { child(Text(text = "sibling-$it")) } })
          },
      )
    }
  }

  private class CachedUpdateSink : KComponent() {
    override fun ComponentScope.render(): Component {
      val revision = useState { 0 }
      return Text(
          text = "revision-${revision.value}",
          style = Style.onClick { revision.update { it + 1 } },
      )
    }
  }

  private class CountingAnimationSpec(private val reportedDurationNanos: Long = 0L) :
      FiniteAnimationSpec<Float> {
    var createAnimatorCalls: Int = 0
    var valueCalls: Int = 0

    override fun createAnimator(
        initialValue: Float,
        targetValue: Float,
        typeConverter: TypeConverter<Float>,
        initialVelocity: AnimationVector?,
    ): Animator<Float> {
      createAnimatorCalls++
      return object : Animator<Float> {
        override val durationNanos: Long = reportedDurationNanos

        override fun getValue(playTimeNanos: Long): Float {
          valueCalls++
          return targetValue
        }

        override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
          velocity.reset()
        }
      }
    }
  }

  companion object {
    private const val KEYLESS_BOUNDS_TAG = "keyless_bounds"
    private const val KEYLESS_SIBLING_A = "keyless_sibling_a"
    private const val KEYLESS_SIBLING_B = "keyless_sibling_b"
    private const val PROPERTY_CHANGE_TAG = "property_change"
    private const val MULTIPLE_KEYLESS_TRANSITIONS_TAG = "multiple_keyless_transitions"
    private const val EXPLICIT_TRANSITION_KEY_TAG = "explicit_transition_key"
    private const val REUSABLE_ITEM_A = "reusable_item_a"
    private const val REUSABLE_ITEM_B = "reusable_item_b"
    private const val REUSABLE_ITEM_C = "reusable_item_c"
    private const val ITEM_A = "item_a"
    private const val ITEM_B = "item_b"
  }
}
