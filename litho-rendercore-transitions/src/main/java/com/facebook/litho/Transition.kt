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

import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import com.facebook.infer.annotation.ThreadSafe
import com.facebook.litho.animation.AnimatedProperties
import com.facebook.litho.animation.AnimatedProperty
import com.facebook.litho.animation.AnimatedPropertyNode
import com.facebook.litho.animation.AnimationBinding
import com.facebook.litho.animation.FloatValue
import com.facebook.litho.animation.PropertyAnimation
import com.facebook.litho.animation.PropertyHandle
import com.facebook.litho.animation.Resolver
import com.facebook.litho.animation.RuntimeValue
import com.facebook.litho.animation.SpringTransition
import com.facebook.litho.animation.TimingTransition
import com.facebook.litho.animation.TransitionAnimationBinding
import com.facebook.litho.dataflow.springs.SpringConfig
import com.facebook.rendercore.Function
import com.facebook.rendercore.transitions.TransitionsExtensionInput

/**
 * Defines how a property on a component should animate as it changes, allowing you to optionally
 * define appear-from values for appear animations and disappear-to values for disappear animations.
 *
 * TODO(t20719329): Better documentation for Transition class
 *
 * Note: This abstract class has no instance methods, abstract or otherwise. It's a marker class so
 * that [TransitionSet]s and [TransitionUnit]s can be composed with each other. It's abstract
 * because before Java 8, static methods on interfaces are not allowed.
 */
abstract class Transition {
  enum class TransitionKeyType {
    GLOBAL,
    LOCAL,
  }

  /** The type of a [ComponentTarget]. */
  enum class ComponentTargetType {
    /**
     * Targets all components with transition keys that are changing in this transition. Expected
     * extra data: none.
     */
    ALL,

    /** Targets one local transition key. Expected extra data: String, a transition key. */
    LOCAL_KEY,

    /** Targets a set of local transition keys. Expected extra data: String[] of transition keys. */
    LOCAL_KEY_SET,

    /** Targets one global transition key. Expected extra data: String, a transition key. */
    GLOBAL_KEY,

    /**
     * Targets a set of global transition keys. Expected extra data: String[] of transition keys.
     */
    GLOBAL_KEY_SET,

    /**
     * Used for automatic bounds transition. Targets all components in the Component tree which
     * bounds are changing in this transition. Expected extra data: none.
     */
    AUTO_LAYOUT,
  }

  /** The type of a [PropertyTarget]. */
  enum class PropertyTargetType {
    /** Targets a set of properties. Expected extra data: AnimatedProperty[] of properties. */
    SET,

    /** Targets a single property. Expected extra data: AnimatedProperty, a single property. */
    SINGLE,

    /**
     * Used for automatic bounds transition. Targets properties related to bounds which are defined
     * in [AnimatedProperties.AUTO_LAYOUT_PROPERTIES]. Expected extra data: none.
     */
    AUTO_LAYOUT,
  }

  /** Specifies what components and properties a Transition should target. */
  class AnimationTarget
  internal constructor(
      @JvmField val componentTarget: ComponentTarget,
      @JvmField val propertyTarget: PropertyTarget,
  )

  /** Specifies the component(s) a Transition should target. */
  class ComponentTarget
  internal constructor(
      @JvmField val componentTargetType: ComponentTargetType,
      @JvmField val componentTargetExtraData: Any?,
  )

  /** Specifies the property(s) a Transition should target. */
  class PropertyTarget
  internal constructor(
      @JvmField val propertyTargetType: PropertyTargetType,
      @JvmField val propertyTargetExtraData: Any?,
  )

  /**
   * Class that knows how to create a [TransitionAnimationBinding] given a [ ]. This can be used to
   * customize the type of animation using [ ][TransitionUnitsBuilder.animator].
   */
  fun interface TransitionAnimator {
    /**
     * @return a [TransitionAnimationBinding] for the given [PropertyAnimation] that will animate
     *   the change in value on this property.
     */
    fun createAnimation(propertyAnimation: PropertyAnimation): TransitionAnimationBinding
  }

  class TransitionUnit
  internal constructor(
      val animationTarget: AnimationTarget,
      private val transitionAnimator: TransitionAnimator,
      val appearFrom: RuntimeValue?,
      val disappearTo: RuntimeValue?,
      val traceName: String?,
      val transitionEndHandler: Function<Void?>?,
  ) : Transition() {
    var ownerKey: Any? = null

    fun hasAppearAnimation(): Boolean {
      return appearFrom != null
    }

    fun hasDisappearAnimation(): Boolean {
      return disappearTo != null
    }

    fun createAnimation(propertyHandle: PropertyHandle, targetValue: Float): AnimationBinding? {
      val propertyAnimation = PropertyAnimation(propertyHandle, targetValue)
      return transitionAnimator.createAnimation(propertyAnimation)
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN", "UNCHECKED_CAST")
    fun targets(transitionId: TransitionId): Boolean {
      val targetType = animationTarget.componentTarget.componentTargetType
      if (targetType == ComponentTargetType.ALL || targetType == ComponentTargetType.AUTO_LAYOUT) {
        return true
      }
      val id = transitionId
      when (targetType) {
        ComponentTargetType.LOCAL_KEY -> {
          if (!equals(ownerKey, id.extraData)) {
            return false
          }
          return equals(
              id.reference,
              animationTarget.componentTarget.componentTargetExtraData,
          )
        }
        ComponentTargetType.GLOBAL_KEY -> {
          return equals(
              id.reference,
              animationTarget.componentTarget.componentTargetExtraData,
          )
        }
        ComponentTargetType.LOCAL_KEY_SET -> {
          if (!equals(ownerKey, id.extraData)) {
            return false
          }
          if (id.reference == null) {
            return false
          }
          return arrayContains(
              animationTarget.componentTarget.componentTargetExtraData as Array<String?>,
              id.reference,
          )
        }
        ComponentTargetType.GLOBAL_KEY_SET -> {
          if (id.reference == null) {
            return false
          }
          return arrayContains(
              animationTarget.componentTarget.componentTargetExtraData as Array<String?>,
              id.reference,
          )
        }
        else -> {
          throw RuntimeException("Didn't handle type: $targetType")
        }
      }
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN", "UNCHECKED_CAST")
    fun targetsProperty(property: AnimatedProperty?): Boolean {
      when (animationTarget.propertyTarget.propertyTargetType) {
        PropertyTargetType.AUTO_LAYOUT -> {
          return arrayContains(
              AnimatedProperties.AUTO_LAYOUT_PROPERTIES,
              property,
          )
        }
        PropertyTargetType.SET -> {
          return arrayContains(
              animationTarget.propertyTarget.propertyTargetExtraData as Array<AnimatedProperty?>,
              property,
          )
        }
        PropertyTargetType.SINGLE -> {
          return property == animationTarget.propertyTarget.propertyTargetExtraData
        }
        else -> {
          throw RuntimeException(
              "Didn't handle type: ${animationTarget.propertyTarget.propertyTargetExtraData}",
          )
        }
      }
    }
  }

  /**
   * Transition builder that allows targeting arbitrary keys and properties. By default CHANGE
   * animation is supported and it is possible to add support for APPEARING/DISAPPEARING animations
   * by providing start/end values for given property with [.appearFrom]/[ ][.disappearTo]. Default
   * animator is [SpringTransition] but that can be customized by providing other animator with
   * [.animator].
   */
  class TransitionUnitsBuilder : BaseTransitionUnitsBuilder {
    internal constructor(componentTarget: ComponentTarget) {
      this.componentTarget = componentTarget
    }

    internal constructor(
        componentTargetType: ComponentTargetType,
        componentTargetExtraData: Any?,
    ) {
      componentTarget = ComponentTarget(componentTargetType, componentTargetExtraData)
    }

    /**
     * Adds a given property to animate. This also puts the Builder in a state to configure the
     * animation of this property using [.animator], [.appearFrom], and [ ][.disappearTo].
     *
     * @param property the property to animate
     */
    fun animate(property: AnimatedProperty?): TransitionUnitsBuilder {
      maybeCommitCurrentBuilder()
      propertyTarget = PropertyTarget(PropertyTargetType.SINGLE, property)
      return this
    }

    /**
     * Adds a set of properties to animate. This also puts the Builder in a state to configure the
     * animation of these properties using [.animator]. To specify appearFrom/disappearTo values,
     * address properties individually using [.animate].
     *
     * @param properties the properties to animate
     */
    fun animate(vararg properties: AnimatedProperty?): TransitionUnitsBuilder {
      maybeCommitCurrentBuilder()
      propertyTarget = PropertyTarget(PropertyTargetType.SET, properties)
      return this
    }

    /**
     * Adds a target of properties to animate. This also puts the Builder in a state to configure
     * the animation of these properties using [.animator]. To specify appearFrom/disappearTo
     * values, address properties individually using [.animate].
     *
     * @param propertyTarget the target properties to animate
     */
    fun animate(propertyTarget: PropertyTarget?): TransitionUnitsBuilder {
      maybeCommitCurrentBuilder()
      this.propertyTarget = propertyTarget
      return this
    }

    /**
     * Use to define the [TransitionAnimator] that drives the animation. The default is a spring.
     */
    fun animator(animator: TransitionAnimator): TransitionUnitsBuilder {
      transitionAnimator = animator
      return this
    }

    /**
     * Add a transition end handler that would get a callback whenever the transition finishes.
     *
     * @param transitionEndHandler
     * @return
     */
    fun transitionEndHandler(transitionEndHandler: Function<Void?>?): TransitionUnitsBuilder {
      this.transitionEndHandler = transitionEndHandler
      return this
    }

    /**
     * Define where appear animations should start from.
     *
     * @see FloatValue
     * @see DimensionValue
     */
    fun appearFrom(value: RuntimeValue?): TransitionUnitsBuilder {
      val target = propertyTarget
      if (target == null || target.propertyTargetType != PropertyTargetType.SINGLE) {
        throw RuntimeException(
            "Must specify a single property using #animate() before specifying an appearFrom " +
                "value!",
        )
      }
      this.appearFrom = value
      return this
    }

    fun traceName(name: String?): TransitionUnitsBuilder {
      this.traceName = name
      return this
    }

    /**
     * Define where disappear animations should end at.
     *
     * @see FloatValue
     * @see DimensionValue
     */
    fun disappearTo(value: RuntimeValue?): TransitionUnitsBuilder {
      val target = propertyTarget
      if (target == null || target.propertyTargetType != PropertyTargetType.SINGLE) {
        throw RuntimeException(
            "Must specify a single property using #animate() before specifying an disappearTo " +
                "value!",
        )
      }
      this.disappearTo = value
      return this
    }

    /** Define a constant value where appear animations should start from. */
    fun appearFrom(value: Float): TransitionUnitsBuilder {
      return appearFrom(FloatValue(value))
    }

    /** Define a constant value where disappear animations should end at. */
    fun disappearTo(value: Float): TransitionUnitsBuilder {
      return disappearTo(FloatValue(value))
    }
  }

  /**
   * Transition builder that targets every component in the component tree whose bounds have been
   * changed. Default animator is [SpringTransition] but that can be customized by providing other
   * animator with [.animator].
   */
  class AutoBoundsTransitionBuilder internal constructor() : BaseTransitionUnitsBuilder() {
    init {
      componentTarget = ComponentTarget(ComponentTargetType.AUTO_LAYOUT, null)
      propertyTarget = PropertyTarget(PropertyTargetType.AUTO_LAYOUT, null)
    }

    /**
     * Use to define the [TransitionAnimator] that drives the animation. The default is a spring.
     */
    fun animator(animator: TransitionAnimator): AutoBoundsTransitionBuilder {
      transitionAnimator = animator
      return this
    }

    /**
     * Add a transition end handler that would get a callback whenever the transition finishes.
     *
     * @param transitionEndHandler
     * @return
     */
    fun transitionEndHandler(transitionEndHandler: Function<Void?>?): AutoBoundsTransitionBuilder {
      this.transitionEndHandler = transitionEndHandler
      return this
    }
  }

  abstract class BaseTransitionUnitsBuilder : Transition() {
    var builtTransitions: ArrayList<TransitionUnit> = ArrayList()
    var componentTarget: ComponentTarget? = null
    var propertyTarget: PropertyTarget? = null
    var transitionAnimator: TransitionAnimator = DEFAULT_ANIMATOR
    var appearFrom: RuntimeValue? = null
    var disappearTo: RuntimeValue? = null
    var traceName: String? = null
    var transitionEndHandler: Function<Void?>? = null

    fun maybeCommitCurrentBuilder() {
      val propertyTarget = propertyTarget ?: return
      val componentTarget =
          requireNotNull(componentTarget) { "componentTarget must be set before committing" }
      builtTransitions.add(
          TransitionUnit(
              AnimationTarget(componentTarget, propertyTarget),
              transitionAnimator,
              appearFrom,
              disappearTo,
              traceName,
              transitionEndHandler,
          ),
      )
      this.propertyTarget = null
      transitionAnimator = DEFAULT_ANIMATOR
      appearFrom = null
      disappearTo = null
      traceName = null
      transitionEndHandler = null
    }

    val transitionUnits: ArrayList<TransitionUnit>
      get() {
        maybeCommitCurrentBuilder()
        return builtTransitions
      }
  }

  /** Creates spring-driven animations. */
  class SpringTransitionAnimator : TransitionAnimator {
    val springConfig: SpringConfig

    /**
     * Create spring-driven animator with given tension and friction values. Spring physics
     * implementation is taken from Rebound library and we recommend to use demo provided at
     * [https://facebookarchive.github.io/rebound](https://facebookarchive.github.io/rebound/) to
     * have a better sense of how friction and tension values work together.
     */
    constructor(tension: Double, friction: Double) {
      springConfig = SpringConfig(tension, friction)
    }

    /**
     * Create spring-driven animator with given [SpringConfig]. Spring physics implementation is
     * taken from Rebound library and we recommend to use demo provided at
     * [https://facebookarchive.github.io/rebound](https://facebookarchive.github.io/rebound/) to
     * have a better sense of how friction and tension values work together.
     */
    constructor(springConfig: SpringConfig) {
      this.springConfig = springConfig
    }

    override fun createAnimation(propertyAnimation: PropertyAnimation): TransitionAnimationBinding {
      return SpringTransition(propertyAnimation, springConfig)
    }
  }

  /** Creates timing-driven animations with the given duration. */
  class TimingTransitionAnimator
  /** Create timing animator with accelerate decelerate interpolation. */
  @JvmOverloads
  constructor(val durationMs: Int, val interpolator: Interpolator? = DEFAULT_INTERPOLATOR) :
      TransitionAnimator {
    /** Create timing animator with custom Android interpolator. */
    override fun createAnimation(propertyAnimation: PropertyAnimation): TransitionAnimationBinding {
      return TimingTransition(durationMs, propertyAnimation, interpolator)
    }
  }

  /**
   * Separate resolver for root component to extract the start value of appear animation of its *
   * width/height that we will set in root host onMeasure.
   */
  private class RootItemResolver(
      private val transitionsExtensionInput: TransitionsExtensionInput,
      private val animatedProperty: AnimatedProperty,
  ) : Resolver {
    override fun getCurrentState(propertyHandle: PropertyHandle): Float {
      val animatableRootItem = transitionsExtensionInput.animatableRootItem
      checkNotNull(animatableRootItem) { "AnimatableRootItem cannot be null" }
      return animatedProperty.get(animatableRootItem)
    }

    override fun getAnimatedPropertyNode(propertyHandle: PropertyHandle): AnimatedPropertyNode {
      throw UnsupportedOperationException()
    }
  }

  /**
   * Contains information about whether root component has bounds transition and if so whether it
   * defines appear animation as well. The latter is useful to extract from which value we should
   * animate from so that in root Host on measure we can set initial value.
   */
  class RootBoundsTransition
  @JvmOverloads
  constructor(
      @JvmField var hasTransition: Boolean = false,
      @JvmField var appearTransition: TransitionUnit? = null,
  )

  companion object {
    /**
     * Spring-driven animator with values of [SpringConfig.DEFAULT_TENSION] and
     * [ ][SpringConfig.DEFAULT_FRICTION] that has overshooting behavior. Spring physics
     * implementation is taken from Rebound library and we recommend to use demo provided at
     * [https://facebookarchive.github.io/rebound](https://facebookarchive.github.io/rebound/) to
     * have a better sense of how friction and tension values work together.
     */
    @JvmField
    val SPRING_WITH_OVERSHOOT: TransitionAnimator =
        SpringTransitionAnimator(SpringConfig.defaultConfig)

    /**
     * Spring-driven animator that can be used as alternative to [.SPRING_WITH_OVERSHOOT] if
     * overshoot is not desired. Spring physics implementation is taken from Rebound library and we
     * recommend to use demo provided at
     * [https://facebookarchive.github.io/rebound](https://facebookarchive.github.io/rebound/) to
     * have a better sense of how friction and tension values work together.
     */
    @JvmField
    val SPRING_WITHOUT_OVERSHOOT: TransitionAnimator =
        SpringTransitionAnimator(SpringConfig.noOvershootConfig)

    /**
     * The default TransitionKeyType, assigned to component and transitions when user does not
     * specify what TransitionKeyType to use
     */
    @JvmField val DEFAULT_TRANSITION_KEY_TYPE: TransitionKeyType = TransitionKeyType.LOCAL

    private val DEFAULT_ANIMATOR: TransitionAnimator = SPRING_WITH_OVERSHOOT
    private val DEFAULT_INTERPOLATOR: Interpolator = AccelerateDecelerateInterpolator()

    /** Creates a Transition for the component with the given transition key. */
    @JvmStatic
    fun create(key: String?): TransitionUnitsBuilder {
      return create(DEFAULT_TRANSITION_KEY_TYPE, key)
    }

    /** Creates a Transition for the components with the given transition keys. */
    @JvmStatic
    fun create(vararg keys: String?): TransitionUnitsBuilder {
      return create(DEFAULT_TRANSITION_KEY_TYPE, *keys)
    }

    /** Creates a Transition for the component with the given transition key of the given type. */
    @JvmStatic
    fun create(type: TransitionKeyType?, key: String?): TransitionUnitsBuilder {
      return TransitionUnitsBuilder(getComponentTargetTypeForTransitionKeyType(type, true), key)
    }

    /** Creates a Transition for the components with the given transition keys of the given type. */
    @JvmStatic
    fun create(type: TransitionKeyType?, vararg keys: String?): TransitionUnitsBuilder {
      return TransitionUnitsBuilder(getComponentTargetTypeForTransitionKeyType(type, false), keys)
    }

    private fun getComponentTargetTypeForTransitionKeyType(
        transitionKeyType: TransitionKeyType?,
        isSingleKey: Boolean,
    ): ComponentTargetType {
      return when (transitionKeyType) {
        TransitionKeyType.GLOBAL -> {
          if (isSingleKey) {
            ComponentTargetType.GLOBAL_KEY
          } else {
            ComponentTargetType.GLOBAL_KEY_SET
          }
        }
        TransitionKeyType.LOCAL -> {
          if (isSingleKey) {
            ComponentTargetType.LOCAL_KEY
          } else {
            ComponentTargetType.LOCAL_KEY_SET
          }
        }
        else -> {
          throw RuntimeException("Unhandled TransitionKeyType $transitionKeyType")
        }
      }
    }

    /** Creates a Transition for the components targeted by the given [ComponentTarget]. */
    @JvmStatic
    fun create(target: ComponentTarget): TransitionUnitsBuilder {
      return TransitionUnitsBuilder(target.componentTargetType, target.componentTargetExtraData)
    }

    /**
     * Creates an Automatic Bounds Transition that targets every component in the component tree
     * whose bounds have been changed.
     */
    @JvmStatic
    fun allLayout(): AutoBoundsTransitionBuilder {
      return AutoBoundsTransitionBuilder()
    }

    /** Creates a set of [Transition]s that will run in parallel. */
    @JvmStatic
    @ThreadSafe(enableChecks = false)
    fun <T : Transition> parallel(vararg transitions: T?): TransitionSet {
      return ParallelTransitionSet(*transitions)
    }

    /** Creates a set of [Transition]s that will run in parallel but starting on a stagger. */
    @JvmStatic
    fun <T : Transition> stagger(staggerMs: Int, vararg transitions: T?): TransitionSet {
      return ParallelTransitionSet(staggerMs, *transitions)
    }

    /** Creates a sequence of [Transition]s that will run one after another. */
    @JvmStatic
    fun <T : Transition> sequence(vararg transitions: T?): TransitionSet {
      return SequenceTransitionSet(*transitions)
    }

    /** Creates a delayed [Transition]s that will run after a delay. */
    @JvmStatic
    fun <T : Transition> delay(delayMs: Int, transition: T): Transition {
      return DelayTransitionSet(delayMs, transition)
    }

    /**
     * Creates a [SpringTransition] with the given tension and friction. Spring physics
     * implementation is taken from Rebound library and we recommend to use demo provided at
     * [https://facebookarchive.github.io/rebound](https://facebookarchive.github.io/rebound/) to
     * have a better sense of how friction and tension values work together.
     */
    @JvmStatic
    fun springWithConfig(tension: Double, friction: Double): TransitionAnimator {
      return SpringTransitionAnimator(tension, friction)
    }

    /** Creates a [TimingTransition] with the given duration. */
    @JvmStatic
    fun timing(durationMs: Int): TransitionAnimator {
      return TimingTransitionAnimator(durationMs)
    }

    /** Creates a [TimingTransition] with the given duration and [Interpolator]. */
    @JvmStatic
    fun timing(durationMs: Int, interpolator: Interpolator?): TransitionAnimator {
      return TimingTransitionAnimator(durationMs, interpolator)
    }

    private fun arrayContains(array: Array<out Any?>, value: Any?): Boolean {
      for (i in array.indices) {
        if (array[i] === value) {
          return true
        }
      }
      return false
    }

    @JvmStatic
    fun getRootAppearFromValue(
        transition: TransitionUnit,
        transitionsExtensionInput: TransitionsExtensionInput,
        property: AnimatedProperty,
    ): Float {
      val resolver = RootItemResolver(transitionsExtensionInput, property)
      val rootTransitionId =
          checkNotNull(transitionsExtensionInput.rootTransitionId) {
            "Root transition ID cannot be null"
          }
      val appearFrom = checkNotNull(transition.appearFrom) { "AppearFrom cannot be null" }
      return appearFrom.resolve(resolver, PropertyHandle(rootTransitionId, property))
    }

    /** @return `true` iff a and b are equal. */
    @JvmStatic
    fun equals(a: Any?, b: Any?): Boolean {
      if (a === b) {
        return true
      }

      if (a == null || b == null) {
        return false
      }

      return a == b
    }
  }
}
