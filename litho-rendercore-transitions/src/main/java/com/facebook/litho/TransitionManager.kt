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

import android.util.Log
import androidx.annotation.IntDef
import androidx.collection.SparseArrayCompat
import com.facebook.litho.Transition.ComponentTargetType
import com.facebook.litho.Transition.PropertyTargetType
import com.facebook.litho.Transition.TransitionUnit
import com.facebook.litho.animation.AnimatedProperties
import com.facebook.litho.animation.AnimatedProperty
import com.facebook.litho.animation.AnimatedPropertyNode
import com.facebook.litho.animation.AnimationBinding
import com.facebook.litho.animation.AnimationBindingListener
import com.facebook.litho.animation.ParallelBinding
import com.facebook.litho.animation.PropertyAnimation
import com.facebook.litho.animation.PropertyHandle
import com.facebook.litho.animation.Resolver
import com.facebook.rendercore.transitions.TransitionsExtensionInput
import com.meta.systrace.Systracer

/**
 * Handles animating transitions defined by ComponentSpec's onCreateTransition code.
 *
 * USAGE FROM MOUNTSTATE
 *
 * Unique per MountState instance. Called from MountState on mount calls to process the transition
 * keys and handles which transitions to run and when.
 *
 * This class is tightly coupled to MountState. When creating new animations, the expected usage of
 * this class is: 1. [.setupTransitions] is called with the current and next [ ]. 2. [.isAnimating]
 * and [.isDisappearing] can be called to determine what is/will be animating 3. MountState updates
 * the mount content for changing content.
 * 4. [.runTransitions] is called to restore initial states for the transition and run the new
 *    animations.
 *
 * Additionally, any time the [MountState] is re-used for a different component tree (e.g. because
 * it was recycled in a RecyclerView), [.reset] should be called to stop running all existing
 * animations.
 *
 * TECHNICAL DETAILS
 * - Transition keys are 1-1 mapped to AnimationState - An [AnimationState] has many [ ]s (one for
 *   each property) - A [PropertyState] can have up to one animation.
 *
 * An [AnimationState] keeps track of the current mount content object, as well as the state of all
 * animating properties ([PropertyState]s). A [PropertyState] keeps track of a
 * [AnimatedPropertyNode], which has the current value of that property in the animation, and up to
 * one animation and end value. A reverse mapping from animation to property(s) being animated is
 * tracked in [.animationsToPropertyHandles].
 *
 * Combined, these mean that at any point in time, we're able to tell what animation is animating
 * what property(s). Knowing this, we can properly resolve conflicting animations (animations on the
 * same property of the same mount content).
 *
 * Another important note: sometimes we need to keep values set on properties before or after
 * animations.
 *
 * Examples include an appearFrom value for an animation that starts later in a sequence of
 * animations (in that case, the appearFrom value must be immediately applied even if the animation
 * isn't starting until later), and keeping disappearTo values even after an animation has completed
 * (e.g., consider animating alpha and X position: if the alpha animation finishes first, we still
 * need to keep the final value until we can remove the animating content).
 *
 * As such, our rule is that we should have a [PropertyState] on the corresponding [ ] for any
 * property that has a value no necessarily reflected by the most up to date [LayoutOutput] for that
 * transition key in the most recent [ ]. Put another way, animation doesn't always imply movement,
 * but a temporary change from a canonical [LayoutOutput].
 */
class TransitionManager(
    private val onAnimationCompleteListener: OnAnimationCompleteListener<*>?,
    private val debugTag: String?,
    private val tracer: Systracer,
) {
  private val animationInconsistencyDebugger: AnimationInconsistencyDebugger? =
      AnimationInconsistencyDebugger.Factory.get()

  /**
   * Whether a piece of content identified by a transition key is appearing, disappearing, or just
   * possibly changing some properties.
   */
  @IntDef(
      value =
          [
              ChangeType.APPEARED,
              ChangeType.CHANGED,
              ChangeType.DISAPPEARED,
              ChangeType.UNSET,
          ],
  )
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class ChangeType {
    companion object {
      const val UNSET: Int = -1
      const val APPEARED: Int = 0
      const val CHANGED: Int = 1
      const val DISAPPEARED: Int = 2
    }
  }

  /** A listener that will be invoked when a mount content has stopped animating. */
  interface OnAnimationCompleteListener<T> {
    fun onAnimationComplete(transitionId: TransitionId)

    fun onAnimationUnitComplete(propertyHandle: PropertyHandle, data: T?)
  }

  /** The animation state of a single property (e.g. X, Y, ALPHA) on a piece of mount content. */
  internal class PropertyState(
      mountContentGroup: OutputUnitsAffinityGroup<Any>?,
      property: AnimatedProperty,
  ) {
    /**
     * The [AnimatedPropertyNode] for this property: it contains the current animated value and a
     * way to set a new value.
     */
    var animatedPropertyNode: AnimatedPropertyNode

    /** The animation, if any, that is currently running on this property. */
    var animation: AnimationBinding? = null

    /** If there's an [.animation], the target value it's animating to. */
    var targetValue: Float? = null

    /** The last mounted value of this property. */
    var lastMountedValue: Float? = null

    /** How many animations are waiting to finish for this property. */
    var numPendingAnimations: Int = 0

    init {
      this.animatedPropertyNode = AnimatedPropertyNode(mountContentGroup, property)
    }
  }

  /**
   * Animation state of a given mount content. Holds everything we currently know about an animating
   * transition key, such as whether it's appearing, disappearing, or changing, as well as
   * information about any animating properties on this mount content.
   */
  internal class AnimationState {
    /**
     * The states for all the properties of this mount content that have an animated value (e.g. a
     * value that isn't necessarily their mounted value).
     */
    val propertyStates: MutableMap<AnimatedProperty, PropertyState> =
        HashMap<AnimatedProperty, PropertyState>()

    /**
     * The current mount content for this animation state, if it's mounted, null otherwise. This
     * mount content can change over time.
     */
    var mountContentGroup: OutputUnitsAffinityGroup<Any>? = null

    /**
     * Whether the last [TransitionsExtensionInput] diff that had this content in it showed the
     * content appearing, disappearing or changing.
     */
    var changeType: Int = ChangeType.UNSET

    /** While calculating animations, the current (before) LayoutOutput. */
    var currentLayoutOutputsGroup: OutputUnitsAffinityGroup<AnimatableItem>? = null

    /** While calculating animations, the next (after) LayoutOutput. */
    var nextLayoutOutputsGroup: OutputUnitsAffinityGroup<AnimatableItem>? = null

    /**
     * Whether this transition key was seen in the last transition, either in the current or next
     * [TransitionsExtensionInput].
     */
    var seenInLastTransition: Boolean = false

    /**
     * If this animation is running but the layout changed and it appeared/diappeared without an
     * equivalent Transition specified, we need to interrupt this animation.
     */
    var shouldFinishUndeclaredAnimation: Boolean = false

    var hasDisappearingAnimation: Boolean = false
  }

  private val animationsToPropertyHandles:
      MutableMap<AnimationBinding, MutableList<PropertyHandle>> =
      HashMap<AnimationBinding, MutableList<PropertyHandle>>()
  private val animationStates = TransitionIdMap<AnimationState>()
  private val traceNames = SparseArrayCompat<String>()
  private val initialStatesToRestore: MutableMap<PropertyHandle, Float> =
      HashMap<PropertyHandle, Float>()
  private val runningRootAnimations = ArrayList<AnimationBinding>()
  private val animationBindingListener = TransitionsAnimationBindingListener()
  private val rootAnimationListener = RootAnimationListener()
  private val resolver = TransitionsResolver()
  private var rootAnimationToRun: AnimationBinding? = null

  fun setupTransitions(
      currentInput: TransitionsExtensionInput?,
      nextInput: TransitionsExtensionInput,
      rootTransition: Transition,
  ) {
    val nextTransitionIds = nextInput.transitionIdMapping
    val currentTransitionIds = currentInput?.transitionIdMapping
    setupTransitions(currentTransitionIds, nextTransitionIds, rootTransition)
  }

  /**
   * Creates (but doesn't start) the animations for the next transition based on the current and
   * next layout states.
   *
   * After this is called, MountState can use [.isAnimating] and [.isDisappearing] to check whether
   * certain mount content will animate, commit the layout changes, and then call [.runTransitions]
   * to restore the initial states and run the animations.
   */
  fun setupTransitions(
      currentTransitionIds: Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>>?,
      nextTransitionIds: Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>>?,
      rootTransition: Transition,
  ) {
    if (debugTag != null) {
      Log.d(debugTag, "=== SetupTransitions ===")
    }

    tracer.beginSection("TransitionManager.setupTransition")

    for (animationState in animationStates.values()) {
      animationState.seenInLastTransition = false
    }

    if (currentTransitionIds == null) {
      if (nextTransitionIds == null) {
        // Both are null, nothing to transition
        tracer.endSection()
        return
      }
      for (nextTransitionId in nextTransitionIds.entries) {
        val transitionId = nextTransitionId.key

        val nextLayoutOutputsGroup = nextTransitionId.value
        recordLayoutOutputsGroupDiff(transitionId, null, nextLayoutOutputsGroup)
      }
    } else if (nextTransitionIds != null) {
      val seenInNewLayout = HashSet<TransitionId>()

      for (transitionId in nextTransitionIds.keys) {
        val isAutogenerated = transitionId.type == TransitionId.Type.AUTOGENERATED

        val nextLayoutOutputsGroup = nextTransitionIds[transitionId]
        val currentLayoutOutputsGroup = currentTransitionIds[transitionId]

        if (nextLayoutOutputsGroup != null) {
          seenInNewLayout.add(transitionId)
        } else if (isAutogenerated) {
          // Only appearing animation would be possible, but there is no way to declare appearing
          // animation for autogenerated ids
          continue
        }

        recordLayoutOutputsGroupDiff(
            transitionId,
            currentLayoutOutputsGroup,
            nextLayoutOutputsGroup,
        )
      }

      for (transitionId in currentTransitionIds.keys) {
        if (seenInNewLayout.contains(transitionId)) {
          // We either already processed this id or it's autogenerated and is not present in the
          // new layout, thus only disappearing animation would be possible, but there is no way to
          // declare disappearing animation for autogenerated ids
          continue
        }
        recordLayoutOutputsGroupDiff(transitionId, currentTransitionIds[transitionId], null)
      }
    }

    createTransitionAnimations(rootTransition)

    // If we recorded any mount content diffs that didn't result in an animation being created for
    // that transition id, clean them up now.
    cleanupNonAnimatingAnimationStates()

    tracer.endSection()
  }

  /**
   * This method will check for running transitions which do not exist after a layout change.
   * Therefore, they need to be interrupted and "finished".
   */
  // TODO: This is only catching changes in appeared/disappeared items. We need to investigate items
  //       which change without a change transition declared. Also the flag should probably belong
  //       to the properties and not to the AnimationState.
  fun finishUndeclaredTransitions() {
    if (removeStateToRestoreIfAnimationIsCleanedUp) {
      val toRemove: MutableSet<TransitionId> = HashSet<TransitionId>()
      for (transitionId in ArrayList(animationStates.ids())) {
        val animationState =
            animationStates.get(transitionId)
                ?: throw RuntimeException(
                    "AnimationState should not be null for transition id: $transitionId",
                )
        if (animationState.shouldFinishUndeclaredAnimation) {
          animationState.shouldFinishUndeclaredAnimation = false

          for (propertyState in ArrayList<PropertyState>(animationState.propertyStates.values)) {
            val animationBinding = propertyState.animation
            if (animationBinding != null) {
              animationBinding.stop()
              animationBindingListener.finishAnimation(
                  animationBinding,
                  AnimationCleanupTrigger.UNDECLARED_TRANSITIONS,
              )
            }
          }

          toRemove.add(transitionId)
        }
      }

      removeStatesToRestoreForTransitionIds(toRemove)
    } else {
      for (animationState in ArrayList(animationStates.values())) {
        if (animationState.shouldFinishUndeclaredAnimation) {
          animationState.shouldFinishUndeclaredAnimation = false

          for (propertyState in ArrayList<PropertyState>(animationState.propertyStates.values)) {
            val animationBinding = propertyState.animation
            if (animationBinding != null) {
              animationBinding.stop()
              animationBindingListener.finishAnimation(
                  animationBinding,
                  AnimationCleanupTrigger.UNDECLARED_TRANSITIONS,
              )
            }
          }
        }
      }
    }
  }

  private fun removeStatesToRestoreForTransitionIds(toRemove: Set<TransitionId>) {
    if (toRemove.isNotEmpty()) {
      for (propertyHandle in ArrayList(initialStatesToRestore.keys)) {
        val transitionId = propertyHandle.transitionId
        if (toRemove.contains(transitionId)) {
          initialStatesToRestore.remove(propertyHandle)
        }
      }
    }
  }

  /**
   * Called after [.setupTransitions] has been called and the new layout has been mounted. This
   * restores the state of the previous layout for content that will animate and then starts the
   * corresponding animations.
   */
  fun runTransitions() {
    tracer.beginSection("runTransitions")

    restoreInitialStates()

    if (debugTag != null) {
      debugLogStartingAnimations()
    }

    val animation = rootAnimationToRun
    if (animation != null) {
      animation.addListener(rootAnimationListener)
      animation.start(resolver)
      rootAnimationToRun = null
    }

    tracer.endSection()
  }

  /**
   * Sets the mount content for a given key. This is used to initially set mount content, but also
   * to set content when content is incrementally mounted during an animation.
   */
  fun setMountContent(
      transitionId: TransitionId,
      mountContentGroup: OutputUnitsAffinityGroup<Any>?,
  ) {
    val animationState = animationStates.get(transitionId)
    if (animationState != null) {
      setMountContentInner(transitionId, animationState, mountContentGroup)
    }
  }

  /**
   * After transitions have been setup with [.setupTransitions], returns whether the given key will
   * be/is animating.
   */
  fun isAnimating(transitionId: TransitionId): Boolean {
    return animationStates.contains(transitionId)
  }

  /**
   * After transitions have been setup with [.setupTransitions], returns whether the given key is
   * disappearing.
   */
  fun isDisappearing(transitionId: TransitionId): Boolean {
    val animationState = animationStates.get(transitionId) ?: return false
    return animationState.changeType == ChangeType.DISAPPEARED &&
        animationState.hasDisappearingAnimation
  }

  /** To be called when a MountState is recycled for a new component tree. Clears all animations. */
  fun reset() {
    if (animationInconsistencyDebugger != null) {
      animationInconsistencyDebugger.reset(animationStates)
    }

    if (clearInitialStatesOnReset) {
      initialStatesToRestore.clear()
    }

    for (transitionId in animationStates.ids()) {
      val animationState =
          animationStates.get(transitionId)
              ?: throw RuntimeException(
                  "AnimationState should not be null for transition id: $transitionId",
              )
      setMountContentInner(transitionId, animationState, null)
      clearLayoutOutputs(animationState)
    }
    animationStates.clear()

    traceNames.clear()

    // Clear these so that stopping animations below doesn't cause us to trigger any useless
    // cleanup.
    animationsToPropertyHandles.clear()

    // Calling stop will cause the animation to be removed from the set, so iterate in reverse
    // order.
    for (i in runningRootAnimations.indices.reversed()) {
      runningRootAnimations[i].stop()
    }
    runningRootAnimations.clear()

    rootAnimationToRun = null
  }

  /**
   * Called to record the current/next content for a transition key.
   *
   * @param currentLayoutOutputsGroup the current group of LayoutOutputs for this key, or null if
   *   the key is appearing
   * @param nextLayoutOutputsGroup the new group of LayoutOutput for this key, or null if the key is
   *   disappearing
   */
  private fun recordLayoutOutputsGroupDiff(
      transitionId: TransitionId,
      currentLayoutOutputsGroup: OutputUnitsAffinityGroup<AnimatableItem>?,
      nextLayoutOutputsGroup: OutputUnitsAffinityGroup<AnimatableItem>?,
  ) {
    var animationState = animationStates.get(transitionId)
    if (animationState == null) {
      animationState = AnimationState()
      animationStates.put(transitionId, animationState)
      if (animationInconsistencyDebugger != null) {
        animationInconsistencyDebugger.trackAnimationStateCreated(transitionId)
      }
    }

    if (currentLayoutOutputsGroup == null && nextLayoutOutputsGroup == null) {
      throw RuntimeException("Both current and next LayoutOutput groups were null!")
    }

    if (currentLayoutOutputsGroup == null && nextLayoutOutputsGroup != null) {
      animationState.changeType = ChangeType.APPEARED
    } else if (currentLayoutOutputsGroup != null && nextLayoutOutputsGroup != null) {
      animationState.changeType = ChangeType.CHANGED
    } else {
      if (
          (animationState.changeType == ChangeType.APPEARED ||
              animationState.changeType == ChangeType.CHANGED) &&
              !animationState.hasDisappearingAnimation
      ) {
        animationState.shouldFinishUndeclaredAnimation = true
      }
      animationState.changeType = ChangeType.DISAPPEARED
    }

    animationState.currentLayoutOutputsGroup = currentLayoutOutputsGroup
    animationState.nextLayoutOutputsGroup = nextLayoutOutputsGroup

    recordLastMountedValues(animationState)

    animationState.seenInLastTransition = true

    if (debugTag != null) {
      Log.d(
          debugTag,
          "Saw transition id $transitionId which is ${changeTypeToString(animationState.changeType)}",
      )
    }
  }

  private fun recordLastMountedValues(animationState: AnimationState) {
    val animatableItem = animationState.nextLayoutOutputsGroup?.getMostSignificantUnit()
    // The values for all members of the group should be the same, thus we'll be collected from the
    // most significant one
    for (property in animationState.propertyStates.keys) {
      val propertyState =
          animationState.propertyStates.get(property)
              ?: throw RuntimeException(
                  "PropertyState should not be null for property: ${property.getName()}",
              )
      if (animatableItem == null) {
        propertyState.lastMountedValue = null
      } else {
        propertyState.lastMountedValue = property.get(animatableItem)
      }
    }
  }

  private fun createTransitionAnimations(rootTransition: Transition) {
    rootAnimationToRun = createAnimationsForTransition(rootTransition)
  }

  private fun createAnimationsForTransition(transition: Transition): AnimationBinding? {
    if (transition is TransitionUnit) {
      return createAnimationsForTransitionUnit(transition)
    } else if (transition is TransitionSet) {
      return createAnimationsForTransitionSet(transition)
    } else {
      throw RuntimeException("Unhandled Transition type: $transition")
    }
  }

  private fun createAnimationsForTransitionSet(transitionSet: TransitionSet): AnimationBinding? {
    val children = transitionSet.children
    val createdAnimations = ArrayList<AnimationBinding>()
    for (i in 0 until children.size) {
      val animation = createAnimationsForTransition(children[i])
      if (animation != null) {
        createdAnimations.add(animation)
      }
    }

    if (createdAnimations.isEmpty()) {
      return null
    }

    return transitionSet.createAnimation(createdAnimations)
  }

  @Suppress("SENSELESS_COMPARISON", "UNCHECKED_CAST")
  private fun createAnimationsForTransitionUnit(transition: TransitionUnit): AnimationBinding? {
    val animationTarget = transition.animationTarget
    val createdAnimations = ArrayList<AnimationBinding>()
    when (animationTarget.componentTarget.componentTargetType) {
      ComponentTargetType.ALL,
      ComponentTargetType.AUTO_LAYOUT -> {
        createAnimationsForTransitionUnitAllKeys(transition, createdAnimations)
      }
      ComponentTargetType.LOCAL_KEY -> {
        val key =
            animationTarget.componentTarget.componentTargetExtraData as String?
                ?: throw RuntimeException(
                    "Key should not be null for LOCAL_KEY component target type",
                )
        val ownerKey =
            transition.ownerKey
                ?: throw RuntimeException(
                    "Owner key should not be null for LOCAL_KEY component target type",
                )
        val transitionId = animationStates.getScopedId(ownerKey, key)
        createAnimationsForTransitionUnit(transition, transitionId, createdAnimations)
      }
      ComponentTargetType.LOCAL_KEY_SET -> {
        val keys =
            animationTarget.componentTarget.componentTargetExtraData as Array<String>?
                ?: throw RuntimeException(
                    "Keys should not be null for LOCAL_KEY_SET component target type",
                )
        val localKeySetOwnerKey =
            transition.ownerKey
                ?: throw RuntimeException(
                    "Owner key should not be null for LOCAL_KEY_SET component target type",
                )
        for (j in keys.indices) {
          if (keys[j] == null) {
            continue
          }
          val transitionId = animationStates.getScopedId(localKeySetOwnerKey, keys[j])
          if (transitionId != null) {
            createAnimationsForTransitionUnit(transition, transitionId, createdAnimations)
          }
        }
      }
      ComponentTargetType.GLOBAL_KEY -> {
        val key =
            animationTarget.componentTarget.componentTargetExtraData as String?
                ?: throw RuntimeException(
                    "Key should not be null for GLOBAL_KEY component target type",
                )
        val transitionId = animationStates.getGlobalId(key)
        createAnimationsForTransitionUnit(transition, transitionId, createdAnimations)
      }
      ComponentTargetType.GLOBAL_KEY_SET -> {
        val keys =
            animationTarget.componentTarget.componentTargetExtraData as Array<String>?
                ?: throw RuntimeException(
                    "Keys should not be null for GLOBAL_KEY_SET component target type",
                )
        for (j in keys.indices) {
          if (keys[j] == null) {
            continue
          }
          val transitionId = animationStates.getGlobalId(keys[j])
          if (transitionId != null) {
            createAnimationsForTransitionUnit(transition, transitionId, createdAnimations)
          }
        }
      }
    }

    if (createdAnimations.isEmpty()) {
      return null
    }

    if (createdAnimations.size == 1) {
      return createdAnimations[0]
    }

    return ParallelBinding(0, createdAnimations)
  }

  private fun createAnimationsForTransitionUnitAllKeys(
      transition: TransitionUnit,
      outList: ArrayList<AnimationBinding>,
  ) {
    for (transitionId in animationStates.ids()) {
      val animationState =
          animationStates.get(transitionId)
              ?: throw RuntimeException(
                  "AnimationState should not be null for transition id: $transitionId",
              )
      if (!animationState.seenInLastTransition) {
        continue
      }
      createAnimationsForTransitionUnit(
          transition,
          transitionId,
          outList,
      )
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun createAnimationsForTransitionUnit(
      transition: TransitionUnit,
      transitionId: TransitionId?,
      outList: ArrayList<AnimationBinding>,
  ) {
    if (transitionId == null) {
      return
    }
    val animationTarget = transition.animationTarget
    when (animationTarget.propertyTarget.propertyTargetType) {
      PropertyTargetType.AUTO_LAYOUT -> {
        for (i in 0 until AnimatedProperties.AUTO_LAYOUT_PROPERTIES.size) {
          val createdAnimation = maybeCreateAnimation(
              transition,
              transitionId,
              AnimatedProperties.AUTO_LAYOUT_PROPERTIES[i],
          )
          if (createdAnimation != null) {
            outList.add(createdAnimation)
          }
        }
      }
      PropertyTargetType.SET -> {
        val properties =
            animationTarget.propertyTarget.propertyTargetExtraData as Array<AnimatedProperty>?
                ?: throw RuntimeException(
                    "Properties should not be null for SET property target type",
                )
        for (i in 0 until properties.size) {
          val createdAnimation = maybeCreateAnimation(transition, transitionId, properties[i])
          if (createdAnimation != null) {
            outList.add(createdAnimation)
          }
        }
      }
      PropertyTargetType.SINGLE -> {
        val propertyExtra =
            animationTarget.propertyTarget.propertyTargetExtraData as AnimatedProperty?
                ?: throw RuntimeException(
                    "propertyExtra should not be null for SINGLE property target type",
                )
        val createdAnimation = maybeCreateAnimation(transition, transitionId, propertyExtra)
        if (createdAnimation != null) {
          outList.add(createdAnimation)
        }
      }
    }
  }

  private fun maybeCreateAnimation(
      transition: TransitionUnit,
      transitionId: TransitionId,
      property: AnimatedProperty,
  ): AnimationBinding? {
    val animationState = animationStates.get(transitionId)

    if (debugTag != null) {
      Log.d(
          debugTag,
          "Calculating transitions for $transitionId#${property.getName()}:",
      )
    }

    if (
        animationState == null ||
            (animationState.currentLayoutOutputsGroup == null &&
                animationState.nextLayoutOutputsGroup == null)
    ) {
      if (debugTag != null) {
        Log.d(debugTag, " - this transitionId was not seen in the before/after layout state")
      }
      return null
    }

    animationState.hasDisappearingAnimation =
        transition.hasDisappearAnimation() || animationState.hasDisappearingAnimation
    val changeType = animationState.changeType
    val changeTypeString: String = changeTypeToString(animationState.changeType)
    if (
        (changeType == ChangeType.APPEARED && !transition.hasAppearAnimation()) ||
            (changeType == ChangeType.DISAPPEARED && !transition.hasDisappearAnimation())
    ) {
      // Interrupt running transitions after a layout change, without the new changeType defined.
      animationState.shouldFinishUndeclaredAnimation = true

      if (debugTag != null) {
        Log.d(debugTag, " - did not find matching transition for change type $changeTypeString")
      }
      return null
    }

    val existingState = animationState.propertyStates.get(property)
    val propertyHandle = PropertyHandle(transitionId, property)
    val startValue: Float
    if (existingState != null) {
      startValue = existingState.animatedPropertyNode.value
    } else {
      if (animationState.changeType != ChangeType.APPEARED) {
        val currentGroup =
            animationState.currentLayoutOutputsGroup
                ?: throw RuntimeException(
                    "currentLayoutOutputsGroup should not be null for non-APPEARED change type",
                )
        startValue = property.get(requireNotNull(currentGroup.getMostSignificantUnit()))
      } else {
        val appearFrom =
            transition.appearFrom
                ?: throw RuntimeException("appearFrom should not be null for APPEARED change type")
        startValue = appearFrom.resolve(resolver, propertyHandle)
      }
    }

    val endValue: Float
    if (animationState.changeType != ChangeType.DISAPPEARED) {
      val nextGroup =
          animationState.nextLayoutOutputsGroup
              ?: throw RuntimeException(
                  "nextLayoutOutputsGroup should not be null for non-DISAPPEARED change type",
              )
      endValue = property.get(requireNotNull(nextGroup.getMostSignificantUnit()))
    } else {
      val disappearTo =
          transition.disappearTo
              ?: throw RuntimeException(
                  "disappearTo should not be null for DISAPPEARED change type",
              )
      endValue = disappearTo.resolve(resolver, propertyHandle)
    }

    // Don't replace new animations in two cases: 1) we're already animating that property to
    // the same end value or 2) the start and end values are already the same
    if (existingState != null && existingState.targetValue != null) {
      if (endValue == existingState.targetValue) {
        if (debugTag != null) {
          Log.d(debugTag, " - property is already animating to this end value: $endValue")
        }
        return null
      }
    } else if (startValue == endValue) {
      if (debugTag != null) {
        Log.d(
            debugTag,
            " - the start and end values were the same: $startValue = $endValue",
        )
      }
      return null
    }

    if (debugTag != null) {
      Log.d(debugTag, " - created animation (start=$startValue, end=$endValue)")
    }

    val animation = transition.createAnimation(propertyHandle, endValue)
    if (animation == null) {
      return null
    }
    animation.addListener(animationBindingListener)
    // We add this transition handler to the binding
    animation.setTag(transition.transitionEndHandler)

    var propertyState = existingState
    if (propertyState == null) {
      propertyState = PropertyState(animationState.mountContentGroup, property)
      animationState.propertyStates.put(property, propertyState)
    }
    propertyState.animatedPropertyNode.value = startValue
    propertyState.numPendingAnimations++

    // Currently, all supported animations can only animate one property at a time, but we think
    // this will change in the future so we maintain a set here.
    val animatedPropertyHandles = ArrayList<PropertyHandle>()
    animatedPropertyHandles.add(propertyHandle)
    animationsToPropertyHandles.put(animation, animatedPropertyHandles)
    initialStatesToRestore.put(propertyHandle, startValue)

    if (!transition.traceName.isNullOrEmpty()) {
      traceNames.put(animation.hashCode(), transition.traceName)
    }

    return animation
  }

  private fun restoreInitialStates() {
    var lastPropertyHandle: PropertyHandle? = null
    try {
      for (propertyHandle in initialStatesToRestore.keys) {
        lastPropertyHandle = propertyHandle
        val value =
            initialStatesToRestore.get(propertyHandle)
                ?: throw RuntimeException(
                    "Initial state value should not be null for property handle: $propertyHandle",
                )
        val transitionId = propertyHandle.transitionId
        val animationState =
            animationStates.get(transitionId)
                ?: throw RuntimeException(
                    "AnimationState should not be null for transition id: $transitionId",
                )

        val mountGroup = animationState.mountContentGroup
        if (mountGroup != null) {
          setPropertyValue(
              propertyHandle.property,
              value,
              mountGroup,
          )
        }
      }
    } catch (e: Exception) {
      throw InconsistentInitialStateRestorationException(lastPropertyHandle, e)
    }

    initialStatesToRestore.clear()
  }

  private inner class InconsistentInitialStateRestorationException(
      private val lastPropHandle: PropertyHandle?,
      cause: Exception?,
  ) : RuntimeException(cause) {
    override val message: String?
      get() {
        val message = StringBuilder()
        message
            .append("Inconsistent initial state restoration:\n")
            .append("- animationStates (")
            .append(animationStates.ids().size)
            .append("):\n")
            .append("   - ids: ")
            .append(animationStates.ids())
            .append("\n- initialStatesToRestore (")
            .append(initialStatesToRestore.size)
            .append("):\n")

        for (propertyHandle in initialStatesToRestore.keys) {
          val value = initialStatesToRestore.get(propertyHandle)
          val transitionid = propertyHandle.transitionId
          val isCrashingOne =
              lastPropHandle != null && lastPropHandle.transitionId.equals(transitionid)
          val propertyName = propertyHandle.property.getName()

          message
              .append("   - propertyHandle[transitionId=")
              .append(transitionid)
              .append(", property=")
              .append(propertyName)
              .append("]")
              .append(if (isCrashingOne) "[crashing] " else " ")
              .append(value)
              .append("\n")
        }

        if (animationInconsistencyDebugger != null) {
          message.append("\n - Animation Inconsistency Debugger data: \n")
          message.append(animationInconsistencyDebugger.getReadableStatus())
        }

        return message.toString()
      }
  }

  private fun setMountContentInner(
      transitionId: TransitionId,
      animationState: AnimationState,
      newMountContentGroup: OutputUnitsAffinityGroup<Any>?,
  ) {
    // If the mount content changes, this means this transition key will be rendered with a
    // different mount content (View or Drawable) than it was during the last mount, so we need to
    // migrate animation state from the old mount content to the new one.
    val mountContentGroup = animationState.mountContentGroup
    if (
        (mountContentGroup == null && newMountContentGroup == null) ||
            (mountContentGroup != null && mountContentGroup.equals(newMountContentGroup))
    ) {
      return
    }

    if (debugTag != null) {
      Log.d(debugTag, "Setting mount content for $transitionId to $newMountContentGroup")
    }

    val animatingProperties = animationState.propertyStates
    val currentMountGroup = animationState.mountContentGroup
    if (currentMountGroup != null) {
      for (animatedProperty in animatingProperties.keys) {
        resetProperty(animatedProperty, currentMountGroup)
      }
    }

    for (propertyState in animatingProperties.values) {
      propertyState.animatedPropertyNode.setMountContentGroup(newMountContentGroup)
    }
    animationState.mountContentGroup = newMountContentGroup
  }

  /**
   * Removes any AnimationStates that were created in [.recordLayoutOutputsGroupDiff] but never
   * resulted in an animation being created.
   */
  private fun cleanupNonAnimatingAnimationStates() {
    val toRemove: MutableSet<TransitionId> = HashSet<TransitionId>()

    for (transitionId in animationStates.ids()) {
      val animationState =
          animationStates.get(transitionId)
              ?: throw RuntimeException(
                  "AnimationState should not be null for transition id: $transitionId",
              )
      if (animationState.propertyStates.isEmpty()) {
        setMountContentInner(transitionId, animationState, null)
        clearLayoutOutputs(animationState)

        toRemove.add(transitionId)
      }
    }

    for (transitionId in toRemove) {
      removeAnimationState(transitionId, AnimationCleanupTrigger.NON_ANIMATING_CLEANUP)
    }

    if (removeStateToRestoreIfAnimationIsCleanedUp) {
      removeStatesToRestoreForTransitionIds(toRemove)
    }
  }

  private fun trackAnimationStateRemoval(
      transitionId: TransitionId,
      animationFinishTrigger: AnimationCleanupTrigger,
      animationState: AnimationState?,
  ) {
    if (animationInconsistencyDebugger != null) {
      animationInconsistencyDebugger.trackAnimationStateRemoved(
          transitionId,
          animationFinishTrigger,
          animationState,
      )
    }
  }

  private fun debugLogStartingAnimations() {
    if (debugTag == null) {
      throw RuntimeException("Trying to debug log animations without debug flag set!")
    }

    Log.d(debugTag, "Starting animations:")

    // TODO(t20726089): Restore introspection of animations
  }

  private inner class TransitionsAnimationBindingListener : AnimationBindingListener {
    private val tempPropertyAnimations = ArrayList<PropertyAnimation>()

    override fun onScheduledToStartLater(binding: AnimationBinding) {
      updateAnimationStates(binding)
    }

    override fun onWillStart(binding: AnimationBinding) {
      updateAnimationStates(binding)

      val traceName = traceNames.get(binding.hashCode())
      if (!traceName.isNullOrEmpty()) {
        tracer.beginAsyncSection(traceName, binding.hashCode())
      }
    }

    override fun onFinish(binding: AnimationBinding) {
      val keys = animationsToPropertyHandles.get(binding)
      if (keys != null && onAnimationCompleteListener != null) {
        // We loop through all the properties that were animated by this animation
        for (propertyHandle in keys) {
          @Suppress("UNCHECKED_CAST", "KotlinNullableVariableCastToNonnullType")
          (onAnimationCompleteListener as OnAnimationCompleteListener<Any?>)
              .onAnimationUnitComplete(propertyHandle, binding.getTag())
        }
      }
      finishAnimation(binding, AnimationCleanupTrigger.FINISHED_TO_CONCLUSION)
    }

    override fun onCanceledBeforeStart(binding: AnimationBinding) {
      finishAnimation(binding, AnimationCleanupTrigger.CANCELED_BEFORE_START)
    }

    override fun shouldStart(binding: AnimationBinding): Boolean {
      binding.collectTransitioningProperties(tempPropertyAnimations)

      var shouldStart = true

      // Make sure that all animating properties will animate to a valid position
      for (i in 0 until tempPropertyAnimations.size) {
        val propertyAnimation = tempPropertyAnimations[i]
        val transitionId = propertyAnimation.transitionId
        val animationState = animationStates.get(transitionId)
        val propertyState =
            if (animationState != null) {
              animationState.propertyStates.get(propertyAnimation.property)
            } else {
              null
            }

        if (debugTag != null) {
          Log.d(
              debugTag,
              "Trying to start animation on $transitionId#${propertyAnimation.property.getName()} to ${propertyAnimation.targetValue}:",
          )
        }

        if (propertyState == null) {
          if (debugTag != null) {
            Log.d(
                debugTag,
                " - Canceling animation, transitionId not found in the AnimationState. It has been probably cancelled already.",
            )
          }

          shouldStart = false
        } else if (
            shouldStart &&
                propertyState.lastMountedValue != null &&
                propertyState.lastMountedValue != propertyAnimation.targetValue
        ) {
          if (debugTag != null) {
            Log.d(
                debugTag,
                " - Canceling animation, last mounted value does not equal animation target: ${propertyState.lastMountedValue} != ${propertyAnimation.targetValue}",
            )
          }

          shouldStart = false
        }
      }

      tempPropertyAnimations.clear()
      return shouldStart
    }

    fun updateAnimationStates(binding: AnimationBinding) {
      binding.collectTransitioningProperties(tempPropertyAnimations)

      for (i in 0 until tempPropertyAnimations.size) {
        val propertyAnimation = tempPropertyAnimations[i]
        val transitionId = propertyAnimation.transitionId
        val animationState = animationStates.get(transitionId)
        if (animationState == null) {
          // This can happen when unmounting all items on running animations.
          continue
        }
        val propertyState =
            animationState.propertyStates.get(propertyAnimation.property)
                ?: throw RuntimeException(
                    "PropertyState should not be null for property: ${propertyAnimation.property.getName()}",
                )

        propertyState.targetValue = propertyAnimation.targetValue
        propertyState.animation = binding
      }

      tempPropertyAnimations.clear()
    }

    fun finishAnimation(
        binding: AnimationBinding,
        animationFinishTrigger: AnimationCleanupTrigger,
    ) {
      val keys = animationsToPropertyHandles.remove(binding)
      if (keys == null) {
        return
      }

      // When an animation finishes, we want to go through all the mount contents it was animating
      // and see if it was the last active animation. If it was, we know that item is no longer
      // animating and we can release the animation state.
      for (i in 0 until keys.size) {
        val propertyHandle = keys[i]
        val transitionId = propertyHandle.transitionId
        val animationState =
            animationStates.get(transitionId)
                ?: throw RuntimeException(
                    "AnimationState should not be null for transition id: $transitionId",
                )
        val property = propertyHandle.property
        val isDisappearAnimation = animationState.changeType == ChangeType.DISAPPEARED

        // Disappearing animations are treated differently because we want to keep their animated
        // value up until the point that all animations have finished and we can remove the
        // disappearing content (disappearing items disappear to a value that is based on a provided
        // disappearTo value and not a LayoutOutput, so we can't regenerate it).
        //
        // For non-disappearing content, we know the end value is already reflected by the
        // LayoutOutput we transitioned to, so we don't need to persist an animated value.
        val didFinish: Boolean
        if (isDisappearAnimation) {
          val propertyState =
              animationState.propertyStates.get(property)
                  ?: throw RuntimeException(
                      "Some animation bookkeeping is wrong: tried to remove an animation from the list of active animations, but it wasn't there.",
                  )

          propertyState.numPendingAnimations--
          didFinish = areAllDisappearingAnimationsFinished(animationState)
          val mountGroup = animationState.mountContentGroup
          if (didFinish && mountGroup != null) {
            for (animatedProperty in animationState.propertyStates.keys) {
              resetProperty(animatedProperty, mountGroup)
            }
          }
        } else {
          val propertyState =
              animationState.propertyStates.get(property)
                  ?: throw RuntimeException(
                      "Some animation bookkeeping is wrong: tried to remove an animation from the list of active animations, but it wasn't there.",
                  )

          propertyState.numPendingAnimations--
          if (propertyState.numPendingAnimations > 0) {
            didFinish = false
          } else {
            animationState.propertyStates.remove(property)
            didFinish = animationState.propertyStates.isEmpty()

            val mountGroup = animationState.mountContentGroup
            val nextGroup = animationState.nextLayoutOutputsGroup
            if (mountGroup != null && nextGroup != null) {
              val value: Float = getPropertyValue(property, nextGroup)
              setPropertyValue(property, value, mountGroup)
            }
          }
        }

        if (didFinish) {
          if (debugTag != null) {
            Log.d(debugTag, "Finished all animations for transition id $transitionId")
          }
          onAnimationCompleteListener?.onAnimationComplete(transitionId)
          removeAnimationState(transitionId, animationFinishTrigger)
          clearLayoutOutputs(animationState)
        }
      }

      val traceName = traceNames.get(binding.hashCode())
      if (!traceName.isNullOrEmpty()) {
        tracer.endAsyncSection(traceName, binding.hashCode())
        traceNames.remove(binding.hashCode())
      }
    }

    fun areAllDisappearingAnimationsFinished(animationState: AnimationState): Boolean {
      if (animationState.changeType != ChangeType.DISAPPEARED) {
        throw RuntimeException("This should only be checked for disappearing animations")
      }
      for (propertyState in animationState.propertyStates.values) {
        if (propertyState.numPendingAnimations > 0) {
          return false
        }
      }
      return true
    }
  }

  private fun removeAnimationState(
      transitionId: TransitionId,
      animationFinishTrigger: AnimationCleanupTrigger,
  ) {
    val animationState = animationStates.get(transitionId)
    animationStates.remove(transitionId)
    trackAnimationStateRemoval(transitionId, animationFinishTrigger, animationState)
  }

  private inner class TransitionsResolver : Resolver {
    override fun getCurrentState(propertyHandle: PropertyHandle): Float {
      val animatedProperty = propertyHandle.property
      val transitionId = propertyHandle.transitionId
      val animationState =
          animationStates.get(transitionId)
              ?: throw RuntimeException(
                  "AnimationState should not be null for transition id: $transitionId",
              )
      val propertyState = animationState.propertyStates.get(animatedProperty)

      // Use the current animating value if it exists...
      if (propertyState != null) {
        return propertyState.animatedPropertyNode.value
      }

      // ...otherwise, if it's a property not being animated (e.g., the width when content appears
      // from a width offset), get the property from the LayoutOutput.
      val layoutOutputGroupToCheck =
          if (animationState.changeType == ChangeType.APPEARED)
              animationState.nextLayoutOutputsGroup
          else animationState.currentLayoutOutputsGroup
      if (layoutOutputGroupToCheck == null) {
        throw RuntimeException("Both LayoutOutputs were null!")
      }

      return animatedProperty.get(requireNotNull(layoutOutputGroupToCheck.getMostSignificantUnit()))
    }

    override fun getAnimatedPropertyNode(propertyHandle: PropertyHandle): AnimatedPropertyNode {
      val transitionId = propertyHandle.transitionId
      val state =
          animationStates.get(transitionId)
              ?: throw RuntimeException(
                  "Animation state not found for transition id: $transitionId",
              )
      val propertyState =
          state.propertyStates.get(propertyHandle.property)
              ?: throw RuntimeException(
                  "Property state not found for property: ${propertyHandle.property.getName()}",
              )
      return propertyState.animatedPropertyNode
    }
  }

  private inner class RootAnimationListener : AnimationBindingListener {
    override fun onScheduledToStartLater(binding: AnimationBinding) = Unit

    override fun onWillStart(binding: AnimationBinding) {
      runningRootAnimations.add(binding)
    }

    override fun onFinish(binding: AnimationBinding) {
      runningRootAnimations.remove(binding)
    }

    override fun onCanceledBeforeStart(binding: AnimationBinding) {
      runningRootAnimations.remove(binding)
    }

    override fun shouldStart(binding: AnimationBinding): Boolean {
      return true
    }
  }

  internal enum class AnimationCleanupTrigger {
    CANCELED_BEFORE_START,
    FINISHED_TO_CONCLUSION,
    NON_ANIMATING_CLEANUP,
    UNDECLARED_TRANSITIONS,
    RESET,
  }

  companion object {
    /**
     * Whether we should remove the [PropertyState] in the
     * [ ][TransitionManager.initialStatesToRestore] for transitions that are cleaned up by
     * [ ][TransitionManager.finishUndeclaredTransitions] or
     * [ ][TransitionManager.cleanupNonAnimatingAnimationStates].
     *
     * There is a suspicion that this is leading to crashes where we try to restore an animation
     * state, but there is no longer an animation state for it.
     */
    private var removeStateToRestoreIfAnimationIsCleanedUp = false

    @JvmStatic
    fun enableInitialStateCleanupAfterAnimationCleanup(enabled: Boolean) {
      removeStateToRestoreIfAnimationIsCleanedUp = enabled
    }

    private var clearInitialStatesOnReset = false

    @JvmStatic
    fun setClearInitialStatesOnReset(enabled: Boolean) {
      clearInitialStatesOnReset = enabled
    }

    @JvmStatic
    fun getRootTransition(allTransitions: List<Transition>): Transition? {
      if (allTransitions.isEmpty()) {
        return null
      }

      if (allTransitions.size == 1) {
        return allTransitions[0]
      }

      return ParallelTransitionSet(allTransitions)
    }

    internal fun changeTypeToString(changeType: Int): String {
      when (changeType) {
        ChangeType.APPEARED -> {
          return "APPEARED"
        }
        ChangeType.CHANGED -> {
          return "CHANGED"
        }
        ChangeType.DISAPPEARED -> {
          return "DISAPPEARED"
        }
        ChangeType.UNSET -> {
          return "UNSET"
        }
        else -> {
          throw RuntimeException("Unknown changeType: $changeType")
        }
      }
    }

    private fun clearLayoutOutputs(animationState: AnimationState) {
      if (animationState.currentLayoutOutputsGroup != null) {
        animationState.currentLayoutOutputsGroup = null
      }
      if (animationState.nextLayoutOutputsGroup != null) {
        animationState.nextLayoutOutputsGroup = null
      }
    }

    private fun getPropertyValue(
        property: AnimatedProperty,
        mountContentGroup: OutputUnitsAffinityGroup<AnimatableItem>,
    ): Float {
      return property.get(requireNotNull(mountContentGroup.getMostSignificantUnit()))
    }

    private fun setPropertyValue(
        property: AnimatedProperty,
        value: Float,
        mountContentGroup: OutputUnitsAffinityGroup<Any>,
    ) {
      for (i in 0 until mountContentGroup.size()) {
        property.set(requireNotNull(mountContentGroup.getAt(i)), value)
      }
    }

    private fun resetProperty(
        property: AnimatedProperty,
        mountContentGroup: OutputUnitsAffinityGroup<Any>,
    ) {
      for (i in 0 until mountContentGroup.size()) {
        property.reset(requireNotNull(mountContentGroup.getAt(i)))
      }
    }
  }
}
