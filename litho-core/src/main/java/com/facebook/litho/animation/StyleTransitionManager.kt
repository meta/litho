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

import com.facebook.litho.OutputUnitType

internal fun interface StyleTransitionContentResolver {
  fun getContents(plan: StyleTransitionAnimationPlan): List<Any>
}

internal class StyleTransitionManager {
  private val frameAnimation = FrameDrivenAnimation(::onFrame)
  private val runningAnimations = LinkedHashMap<StyleTransitionAnimationKey, RunningAnimation>()
  private var pendingPlans: List<StyleTransitionAnimationPlan> = emptyList()

  fun setup(
      previousTargets: List<StyleTransitionTarget>,
      nextTargets: List<StyleTransitionTarget>,
  ) {
    pendingPlans =
        StyleTransitionPlanner.createPlans(previousTargets, nextTargets, runningAnimations.keys)
  }

  fun run(contentResolver: StyleTransitionContentResolver) {
    var plannedKeys: MutableSet<StyleTransitionAnimationKey>? = null
    for (plan in pendingPlans) {
      var keys = plannedKeys
      if (keys == null) {
        keys = HashSet()
        plannedKeys = keys
      }
      keys.add(plan.key)
      val contents = contentResolver.getContents(plan)
      if (contents.isEmpty()) {
        runningAnimations.remove(plan.key)
        continue
      }
      val previousAnimation = runningAnimations[plan.key]
      if (previousAnimation != null && previousAnimation.shouldKeepRunningFor(plan)) {
        val currentValue = previousAnimation.currentValue()
        previousAnimation.plan = plan
        previousAnimation.contents = contents
        setProperty(plan, contents, currentValue)
        continue
      }
      val currentValue = previousAnimation?.currentValue() ?: plan.initialValue

      if (currentValue == plan.targetValue) {
        setProperty(plan, contents, plan.targetValue)
        runningAnimations.remove(plan.key)
        continue
      }

      val currentVelocity = previousAnimation?.currentVelocity()
      runningAnimations.remove(plan.key)
      setProperty(plan, contents, currentValue)
      runningAnimations[plan.key] =
          RunningAnimation(
              plan = plan,
              contents = contents,
              animator =
                  when (plan.property) {
                    StyleTransitionProperty.X,
                    StyleTransitionProperty.Y,
                    StyleTransitionProperty.WIDTH,
                    StyleTransitionProperty.HEIGHT ->
                        (plan.animationSpec as? FiniteAnimationSpec<Float>)
                            ?.withDefaultVisibilityThreshold(0.5f) ?: plan.animationSpec
                    else -> plan.animationSpec
                  }.createAnimator(
                      currentValue,
                      plan.targetValue,
                      FloatTypeConverter,
                      currentVelocity,
                  ),
              initialValue = currentValue,
              // When a mid-flight animation is replaced (a genuine retarget), carry its frame clock
              // forward so the replacement's first tick already reflects the elapsed inter-frame
              // time instead of re-emitting the start value for one frame.
              startTimeNanos = previousAnimation?.lastFrameTimeNanos() ?: Long.MIN_VALUE,
          )
    }

    val iterator = runningAnimations.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (plannedKeys?.contains(entry.key) != true) {
        // A dropped x/y animation leaves a stale translation on the view (e.g. a reflow relayout
        // takes over positioning mid-transform); zero it so the node sits at its layout position.
        // Only x/y — alpha/scale hold a meaningful settled value that must not be reset here.
        val property = entry.value.plan.property
        if (property == StyleTransitionProperty.X || property == StyleTransitionProperty.Y) {
          for (content in entry.value.contents) {
            resetProperty(content, property)
          }
        }
        iterator.remove()
      }
    }

    pendingPlans = emptyList()
    if (runningAnimations.isNotEmpty()) {
      AnimationController.register(frameAnimation)
    } else {
      AnimationController.unregister(frameAnimation)
    }
  }

  fun reset() {
    AnimationController.unregister(frameAnimation)
    runningAnimations.clear()
    pendingPlans = emptyList()
  }

  /**
   * Resets [content]'s declared properties to their defaults for a node that is leaving, and
   * reconciles the running animations that target [renderUnitId], so a recycled host keeps no stale
   * transform. Only called for a node that is no longer a current target — a live node updated in
   * place is skipped by the caller ([StyleTransitionsExtension.onUnbindItem]), since resetting its
   * settled or constant declared property (e.g. a non-animating alpha) would wipe it with nothing
   * to restore it.
   *
   * A running animation is preserved when [pendingPlans] still holds its key (a same-pass migration
   * to new content that [run] re-binds); its [content] is only detached so a stray frame cannot
   * write to the recycled view. Otherwise it is a true removal and is cancelled.
   */
  fun resetContent(
      renderUnitId: Long,
      content: Any,
      previousTarget: StyleTransitionTarget?,
      disappearingTarget: StyleTransitionTarget?,
  ) {
    resetDeclaredProperties(content, previousTarget)
    resetDeclaredProperties(content, disappearingTarget)

    if (runningAnimations.isEmpty()) {
      return
    }
    val iterator = runningAnimations.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      val animation = entry.value
      if (animation.plan.renderUnitId != renderUnitId) {
        continue
      }
      animation.detachContent(content)
      resetProperty(content, animation.plan.property)
      if (!hasPendingPlanForKey(entry.key)) {
        iterator.remove()
      }
    }
  }

  fun hasRunningAnimationForRenderUnit(renderUnitId: Long): Boolean =
      runningAnimations.values.any { it.plan.renderUnitId == renderUnitId }

  private fun resetDeclaredProperties(content: Any, target: StyleTransitionTarget?) {
    val changes = target?.data?.spec?.changes ?: return
    for ((property, spec) in changes) {
      if (spec != null) {
        resetProperty(content, property)
      }
    }
  }

  private fun resetProperty(content: Any, property: StyleTransitionProperty) {
    try {
      property.animatedProperty.reset(content)
    } catch (e: UnsupportedOperationException) {
      // Some mount content does not support every animated property.
    }
  }

  private fun hasPendingPlanForKey(key: StyleTransitionAnimationKey): Boolean {
    for (plan in pendingPlans) {
      if (plan.key == key) {
        return true
      }
    }
    return false
  }

  private fun onFrame(frameTimeNanos: Long): Boolean {
    if (runningAnimations.isEmpty()) {
      return false
    }

    val snapshot = runningAnimations.entries.toTypedArray()
    for ((key, animation) in snapshot) {
      val value = animation.valueAt(frameTimeNanos)
      setProperty(animation.plan, animation.contents, value)
      if (animation.isFinished()) {
        setProperty(animation.plan, animation.contents, animation.plan.targetValue)
        runningAnimations.remove(key)
      }
    }

    return runningAnimations.isNotEmpty()
  }

  private class RunningAnimation(
      var plan: StyleTransitionAnimationPlan,
      var contents: List<Any>,
      private val animator: Animator<Float>,
      initialValue: Float,
      startTimeNanos: Long = Long.MIN_VALUE,
  ) {
    init {
      require(animator.durationNanos >= 0L) {
        "Style transitions require an animator with a finite duration."
      }
    }

    private var startTimeNanos = startTimeNanos
    private var lastPlayTimeNanos = 0L
    // Seeded from the animator's actual start value, which differs from plan.initialValue when this
    // replaces a mid-flight animation, so currentValue() is correct even before the first tick.
    private var lastValue = initialValue

    fun valueAt(frameTimeNanos: Long): Float {
      // Establish the clock on the first tick. Also re-baseline if a carried-over start time would
      // land in the future relative to this frame, which would otherwise yield a negative play
      // time.
      if (startTimeNanos == Long.MIN_VALUE || startTimeNanos > frameTimeNanos) {
        startTimeNanos = frameTimeNanos
      }
      lastPlayTimeNanos =
          if (AnimationController.durationScale == 0f) animator.durationNanos
          else frameTimeNanos - startTimeNanos
      lastValue = animator.getValue(lastPlayTimeNanos)
      return lastValue
    }

    fun currentValue(): Float = lastValue

    fun currentVelocity(): AnimationVector =
        FloatTypeConverter.newVector().also { animator.getVelocity(lastPlayTimeNanos, it) }

    fun isFinished(): Boolean = animator.isFinished(lastPlayTimeNanos)

    /** Frame time of the most recent tick, or [Long.MIN_VALUE] if this has never been ticked. */
    fun lastFrameTimeNanos(): Long =
        if (startTimeNanos == Long.MIN_VALUE) Long.MIN_VALUE else startTimeNanos + lastPlayTimeNanos

    // The key this is stored under already pins the property, so only the target and motion matter.
    fun shouldKeepRunningFor(plan: StyleTransitionAnimationPlan): Boolean =
        this.plan.targetValue == plan.targetValue && this.plan.animationSpec == plan.animationSpec

    /**
     * Drops [content] from the driven set (identity match) so a recycled view is no longer written.
     */
    fun detachContent(content: Any) {
      val current = contents
      if (current.isEmpty()) return
      // The content resolver yields at most one element per render unit, so this is the usual path.
      if (current.size == 1) {
        if (current[0] === content) contents = emptyList()
        return
      }
      // Larger lists: build the trimmed copy lazily, only once a match is found, so an absent
      // content allocates nothing.
      var next: ArrayList<Any>? = null
      for (i in current.indices) {
        if (current[i] === content) {
          if (next == null) {
            next = ArrayList(current.size - 1)
            for (j in 0 until i) next.add(current[j])
          }
        } else {
          next?.add(current[i])
        }
      }
      next?.let { contents = it }
    }
  }

  private fun setProperty(
      plan: StyleTransitionAnimationPlan,
      contents: List<Any>,
      value: Float,
  ) {
    for (content in contents) {
      try {
        plan.property.animatedProperty.set(content, value)
      } catch (e: UnsupportedOperationException) {
        // Some mount content does not support every animated property.
      }
    }
  }
}

internal object StyleTransitionPlanner {
  fun createPlans(
      previousTargets: List<StyleTransitionTarget>,
      nextTargets: List<StyleTransitionTarget>,
      runningKeys: Set<StyleTransitionAnimationKey>,
  ): List<StyleTransitionAnimationPlan> {
    val previousById = previousTargets.associateBy { it.styleTransitionId }
    val plans = ArrayList<StyleTransitionAnimationPlan>()

    // A node's own size change never moves its own position, so its x/y is relayout-driven only
    // when
    // a DIFFERENT reflow node resized this commit.
    var resizedReflowCount = 0
    var soleResizedReflowId: StyleTransitionId? = null
    for (nextTarget in nextTargets) {
      if (nextTarget.data.spec.shouldLayout && didReflowResize(previousById, nextTarget)) {
        resizedReflowCount++
        soleResizedReflowId = nextTarget.styleTransitionId
      }
    }

    for (nextTarget in nextTargets) {
      val nextItem = nextTarget.animatableItem ?: continue
      val previousTarget = previousById[nextTarget.styleTransitionId]
      if (previousTarget == null) {
        continue
      }

      val previousItem = previousTarget.animatableItem ?: continue
      val reflowMode = nextTarget.data.spec.reflowMode
      val positionRelayoutDriven =
          reflowMode &&
              (resizedReflowCount > 1 ||
                  (resizedReflowCount == 1 && nextTarget.styleTransitionId != soleResizedReflowId))
      for ((property, animationSpec) in nextTarget.data.spec.changes) {
        if (animationSpec == null) {
          continue
        }
        if (reflowMode) {
          // Width/height are always driven by the reflow relayout, not the visual manager.
          if (
              property == StyleTransitionProperty.WIDTH ||
                  property == StyleTransitionProperty.HEIGHT
          ) {
            continue
          }
          // A relayout-driven position is emergent from the per-frame relayout, so skip its x/y
          // transform — it would chase the relayout and strand a stale translation. A shift not
          // driven by the relayout still animates.
          //
          // Known limitation: a shift that is PARTLY structural (e.g. a sibling inserted above)
          // while another reflow node resizes is fully suppressed, so the structural part snaps.
          if (
              positionRelayoutDriven &&
                  (property == StyleTransitionProperty.X || property == StyleTransitionProperty.Y)
          ) {
            continue
          }
        }
        val key = StyleTransitionAnimationKey(nextTarget.styleTransitionId, property)
        val initialValue = property.animatedProperty.get(previousItem)
        val targetValue = property.animatedProperty.get(nextItem)
        if (initialValue == targetValue && !runningKeys.contains(key)) {
          continue
        }
        plans.add(
            StyleTransitionAnimationPlan(
                key = key,
                renderUnitId = nextItem.getId(),
                outputType = nextItem.getOutputType(),
                property = property,
                initialValue = initialValue,
                targetValue = targetValue,
                animationSpec = animationSpec,
            ),
        )
      }
    }

    return plans
  }

  private fun didReflowResize(
      previousById: Map<StyleTransitionId, StyleTransitionTarget>,
      nextTarget: StyleTransitionTarget,
  ): Boolean {
    val nextItem = nextTarget.animatableItem ?: return false
    val previousItem = previousById[nextTarget.styleTransitionId]?.animatableItem ?: return false
    return StyleTransitionProperty.WIDTH.animatedProperty.get(previousItem) !=
        StyleTransitionProperty.WIDTH.animatedProperty.get(nextItem) ||
        StyleTransitionProperty.HEIGHT.animatedProperty.get(previousItem) !=
            StyleTransitionProperty.HEIGHT.animatedProperty.get(nextItem)
  }
}

internal data class StyleTransitionAnimationKey(
    val styleTransitionId: StyleTransitionId,
    val property: StyleTransitionProperty,
)

internal data class StyleTransitionAnimationPlan(
    val key: StyleTransitionAnimationKey,
    val renderUnitId: Long,
    @Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
    @OutputUnitType
    val outputType: Int,
    val property: StyleTransitionProperty,
    val initialValue: Float,
    val targetValue: Float,
    val animationSpec: AnimationSpec<Float>,
)
