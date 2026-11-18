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
import android.graphics.Rect
import android.view.View
import com.facebook.litho.AnimatableItem
import com.facebook.litho.OutputUnitType
import com.facebook.litho.OutputUnitsAffinityGroup
import com.facebook.litho.TransitionId
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.ms
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.LooperMode

/**
 * Tests for how [StyleTransitionManager] reconciles a running diff-based animation with a new
 * layout/mount — specifically the per-frame value continuity across a keyed remount or a mid-flight
 * retarget. Frames are driven deterministically via [LithoAnimationTestRule].
 */
@LooperMode(LooperMode.Mode.LEGACY)
@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
class StyleTransitionInterruptionTest {

  @JvmField @Rule val animationRule = LithoAnimationTestRule()

  private lateinit var activityController: ActivityController<Activity>

  @Before
  fun setUp() {
    activityController = Robolectric.buildActivity(Activity::class.java, Intent())
  }

  @Test
  fun remountWithUnchangedTargetKeepsRunningAnimation() {
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_remount_preserve")
    val spec = CountingAnimatorSpec(tween(duration = 1_000.ms, easing = Easing.Linear))
    val firstView = View(activityController.get()).apply { alpha = 1f }

    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
                ),
            ),
    )
    manager.run(StyleTransitionContentResolver { listOf(firstView) })

    // Start the animation, then advance 100ms in so the fade is mid-flight (alpha 0.92 for a linear
    // 1f -> 0.2f tween over 1000ms).
    animationRule.runNextFrame()
    animationRule.runNextFrame(100.ms)
    val midValue = firstView.alpha
    assertThat(spec.animatorsCreated).isEqualTo(1)
    assertThat(midValue).isEqualTo(0.92f)

    // Remount the keyed content (new view) with the SAME target and motion, as a keyed remount
    // would. The newly mounted content carries the static target alpha until the animation writes.
    val secondView = View(activityController.get()).apply { alpha = 0.2f }
    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 3, alpha = 0.2f, isAlphaSet = true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 4, alpha = 0.2f, isAlphaSet = true),
                ),
            ),
    )
    manager.run(StyleTransitionContentResolver { listOf(secondView) })

    // The animation is preserved: no new animator is created, and the in-flight value is written
    // straight onto the new content instead of flashing to the static target.
    assertThat(spec.animatorsCreated).isEqualTo(1)
    assertThat(secondView.alpha).isEqualTo(midValue)

    // It keeps running on its ORIGINAL clock onto the new content: at an elapsed 500ms it sits on
    // the original trajectory's value (0.6), not a freshly restarted ramp from 0.92.
    animationRule.runNextFrame(400.ms)
    assertThat(secondView.alpha).isEqualTo(0.6f)
  }

  @Test
  fun retargetMidFlightAdvancesOnTheFirstFrame() {
    val manager = StyleTransitionManager()
    val styleTransitionId = StructuralStyleTransitionId("manager_retarget_no_freeze")
    val spec = tween<Float>(duration = 1_000.ms, easing = Easing.Linear)
    val view = View(activityController.get()).apply { alpha = 1f }

    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 1, alpha = 1f, isAlphaSet = true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 2, alpha = 0.2f, isAlphaSet = true),
                ),
            ),
    )
    manager.run(StyleTransitionContentResolver { listOf(view) })

    animationRule.runNextFrame()
    animationRule.runNextFrame(100.ms)
    val valueAtInterrupt = view.alpha
    assertThat(valueAtInterrupt).isEqualTo(0.92f)

    // Retarget back toward 1f while mid-flight (a different target, so the animation is rebuilt).
    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 3, alpha = 0.2f, isAlphaSet = true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    transitionId = styleTransitionId,
                    spec = transitionSpec { alpha = spec },
                    animatableItem = animatableItem(id = 4, alpha = 1f, isAlphaSet = true),
                ),
            ),
    )
    manager.run(StyleTransitionContentResolver { listOf(view) })
    // The in-flight value is carried over as the new start value.
    assertThat(view.alpha).isEqualTo(valueAtInterrupt)

    // The single next frame must already move toward the new target rather than re-emitting the
    // start value for a frame.
    animationRule.runNextFrame()
    assertThat(view.alpha).isGreaterThan(valueAtInterrupt)
  }

  @Test
  fun retargetingMultipleAnimationsPassesOwnedVelocitySnapshots() {
    val manager = StyleTransitionManager()
    val firstId = StructuralStyleTransitionId("first_velocity_snapshot")
    val secondId = StructuralStyleTransitionId("second_velocity_snapshot")
    val spec = RetainingAnimatorSpec(tween(duration = 1_000.ms, easing = Easing.Linear))
    val firstView = View(activityController.get()).apply { alpha = 1f }
    val secondView = View(activityController.get()).apply { alpha = 0f }

    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    firstId,
                    transitionSpec { alpha = spec },
                    animatableItem(1, 1f, true),
                ),
                styleTransitionTarget(
                    secondId,
                    transitionSpec { alpha = spec },
                    animatableItem(3, 0f, true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    firstId,
                    transitionSpec { alpha = spec },
                    animatableItem(2, 0.2f, true),
                ),
                styleTransitionTarget(
                    secondId,
                    transitionSpec { alpha = spec },
                    animatableItem(4, 0.8f, true),
                ),
            ),
    )
    manager.run(
        StyleTransitionContentResolver { plan ->
          listOf(if (plan.renderUnitId == 2L) firstView else secondView)
        },
    )
    animationRule.runNextFrame()
    animationRule.runNextFrame(100.ms)

    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    firstId,
                    transitionSpec { alpha = spec },
                    animatableItem(2, 0.2f, true),
                ),
                styleTransitionTarget(
                    secondId,
                    transitionSpec { alpha = spec },
                    animatableItem(4, 0.8f, true),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    firstId,
                    transitionSpec { alpha = spec },
                    animatableItem(5, 1f, true),
                ),
                styleTransitionTarget(
                    secondId,
                    transitionSpec { alpha = spec },
                    animatableItem(6, 0f, true),
                ),
            ),
    )
    manager.run(
        StyleTransitionContentResolver { plan ->
          listOf(if (plan.renderUnitId == 5L) firstView else secondView)
        },
    )

    assertThat(spec.initialVelocities).hasSize(2)
    assertThat(spec.initialVelocities[0]).isNotSameAs(spec.initialVelocities[1])
    assertThat(spec.initialVelocities[0][0]).isLessThan(0f)
    assertThat(spec.initialVelocities[1][0]).isGreaterThan(0f)
  }

  @Test
  fun pixelPropertiesSettleAtPixelToleranceWhileAlphaKeepsItsFloatTolerance() {
    val manager = StyleTransitionManager()
    val id = StructuralStyleTransitionId("pixel_tolerance")
    val spec = transitionSpec {
      x = spring()
      alpha = spring()
    }
    val view = View(activityController.get())
    manager.setup(
        previousTargets =
            listOf(
                styleTransitionTarget(
                    id,
                    spec,
                    animatableItem(1, 1f, true, Rect(0, 0, 10, 10)),
                ),
            ),
        nextTargets =
            listOf(
                styleTransitionTarget(
                    id,
                    spec,
                    animatableItem(2, 0.96f, true, Rect(1, 0, 11, 10)),
                ),
            ),
    )
    manager.run(StyleTransitionContentResolver { listOf(view) })
    animationRule.runNextFrame()
    animationRule.runNextFrame(150.ms)
    assertThat(view.x).isEqualTo(1f)
    assertThat(view.alpha).isGreaterThan(0.96f).isLessThan(1f)
  }

  @Test
  fun continuingAnimationsReconcileWithLinearKeyComparisons() {
    val manager = StyleTransitionManager()
    val identities = List(1_000) { CountingIdentity(it) }
    val spec = transitionSpec { alpha = tween(duration = 1_000.ms) }
    val previous = ArrayList<StyleTransitionTarget>()
    val next = ArrayList<StyleTransitionTarget>()
    for ((index, identity) in identities.withIndex()) {
      val id = index.toLong()
      val transitionId = StructuralStyleTransitionId(identity)
      previous.add(styleTransitionTarget(transitionId, spec, animatableItem(id, 1f, true)))
      val item = animatableItem(id, 0f, true)
      next.add(styleTransitionTarget(transitionId, spec, item))
    }
    val view = View(activityController.get())
    manager.setup(previous, next, committedItems = mock())
    manager.run(StyleTransitionContentResolver { listOf(view) })
    assertThat(manager.hasRunningAnimationForRenderUnit(0)).isTrue()

    for (identity in identities) identity.comparisons = 0
    manager.setup(next, next, committedItems = mock())
    assertThat(identities.sumOf { it.comparisons }).isLessThan(identities.size * 2)
    manager.reset()
  }

  private class CountingIdentity(private val id: Int) {
    var comparisons = 0

    override fun hashCode(): Int = id

    override fun equals(other: Any?): Boolean {
      comparisons++
      return other is CountingIdentity && id == other.id
    }
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

  private fun animatableItem(
      id: Long,
      alpha: Float = 1f,
      isAlphaSet: Boolean = false,
      bounds: Rect = Rect(0, 0, 10, 10),
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

  /** Delegates to a real [AnimationSpec] while counting how many animators it creates. */
  private class CountingAnimatorSpec(private val delegate: AnimationSpec<Float>) :
      FiniteAnimationSpec<Float> {
    var animatorsCreated: Int = 0

    override fun createAnimator(
        initialValue: Float,
        targetValue: Float,
        typeConverter: TypeConverter<Float>,
        initialVelocity: AnimationVector?,
    ): Animator<Float> {
      animatorsCreated++
      return delegate.createAnimator(initialValue, targetValue, typeConverter, initialVelocity)
    }
  }

  private class RetainingAnimatorSpec(private val delegate: AnimationSpec<Float>) :
      FiniteAnimationSpec<Float> {
    val initialVelocities = mutableListOf<AnimationVector>()

    override fun createAnimator(
        initialValue: Float,
        targetValue: Float,
        typeConverter: TypeConverter<Float>,
        initialVelocity: AnimationVector?,
    ): Animator<Float> {
      if (initialVelocity != null) initialVelocities.add(initialVelocity)
      return delegate.createAnimator(initialValue, targetValue, typeConverter, initialVelocity)
    }
  }
}
