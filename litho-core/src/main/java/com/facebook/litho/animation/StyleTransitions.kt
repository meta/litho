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

@file:Suppress("MatchingDeclarationName")
@file:OptIn(com.facebook.litho.annotations.ExperimentalLithoApi::class)

package com.facebook.litho.animation

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.AnimatableItem
import com.facebook.litho.CommonProps
import com.facebook.litho.ComponentContext
import com.facebook.litho.OutputUnitsAffinityGroup
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.litho.animation.StyleTransitionProperty.ALPHA
import com.facebook.litho.animation.StyleTransitionProperty.HEIGHT
import com.facebook.litho.animation.StyleTransitionProperty.ROTATION
import com.facebook.litho.animation.StyleTransitionProperty.ROTATION_X
import com.facebook.litho.animation.StyleTransitionProperty.ROTATION_Y
import com.facebook.litho.animation.StyleTransitionProperty.SCALE
import com.facebook.litho.animation.StyleTransitionProperty.WIDTH
import com.facebook.litho.animation.StyleTransitionProperty.X
import com.facebook.litho.animation.StyleTransitionProperty.Y
import com.facebook.litho.annotations.ExperimentalLithoApi

/**
 * Adds a diff-based transition to the host represented by this [Style].
 *
 * `Style.transition` declares which mounted host properties should animate when their resolved
 * values change across Litho layout states. Litho reads the previous mounted value or any in-flight
 * animated value as the animation start, reads the current layout/mount output as the target, and
 * drives the selected properties with the supplied [AnimationSpec]s. Callers do not provide
 * explicit `from` or `to` values.
 *
 * This API is intended for visual host changes that should not drive progressive Yoga relayout. In
 * particular, [StyleTransitionScope.bounds] animates mount-space `x`, `y`, `width`, and `height`
 * after the new layout has already been calculated. Siblings are laid out at their final positions
 * immediately while the transitioned host visually moves or resizes to that final state. Use
 * state-based animation APIs such as `useAnimatedDimen` when parent or sibling layout should reflow
 * every frame.
 *
 * Keyless `Style.transition` uses Litho's structural identity for the same host across renders.
 * When a component needs semantic identity, such as a list item moving across structure, provide
 * that identity through normal Litho component identity mechanisms like `key(...)` rather than
 * through the transition itself. `Style.transition` cannot be combined with legacy
 * `Style.transitionKey(...)` on the same component.
 *
 * Multiple `Style.transition` entries on the same [Style] compose. If the same property is declared
 * more than once, the later declaration wins.
 *
 * Example:
 * ```
 * Row(
 *     style =
 *         Style.width(if (expanded) 200.dp else 120.dp)
 *             .alpha(if (enabled) 1f else 0.4f)
 *             .transition {
 *               bounds = spring()
 *               alpha = tween(duration = 120.ms)
 *             }
 * )
 * ```
 */
@ExperimentalLithoApi
fun Style.transition(block: StyleTransitionScope.() -> Unit): Style =
    this + TransitionStyleItem(StyleTransitionScope().apply(block).build())

/** Transition recipe for style properties. */
internal class StyleTransitionSpec
constructor(
    /** Declared properties; a `null` clears that property from the transitions composed before. */
    internal val changes: Map<StyleTransitionProperty, FiniteAnimationSpec<Float>?>,
    /**
     * True when `bounds(..., reflow = true)` or `size(..., reflow = true)` put the host in reflow
     * mode. In reflow mode a position change caused by another host's reflow is left to that
     * relayout rather than animated visually.
     */
    internal val reflowMode: Boolean = false,
) {

  /**
   * True when a size change drives a real per-frame Yoga relayout (siblings/nested content reflow
   * to the interpolated size) rather than a visual-only mount animation: the host is in reflow mode
   * and still animates its width or height.
   */
  internal val shouldLayout: Boolean
    get() = reflowMode && (changes[WIDTH] != null || changes[HEIGHT] != null)

  internal operator fun plus(other: StyleTransitionSpec): StyleTransitionSpec = StyleTransitionSpec(
      changes = changes + other.changes,
      reflowMode = reflowMode || other.reflowMode,
  )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StyleTransitionSpec) return false
    return changes == other.changes && reflowMode == other.reflowMode
  }

  override fun hashCode(): Int = 31 * changes.hashCode() + reflowMode.hashCode()

  companion object {
    internal val Empty = StyleTransitionSpec(emptyMap())
  }
}

/**
 * Defines the host properties animated by [Style.transition].
 *
 * Each property is optional. Set a property to an [AnimationSpec] to animate changes for that
 * property, or set it to `null` in a later composed transition to remove a previous declaration.
 *
 * Bounds animate per axis through [x], [y], [width], and [height], or as groups through [position],
 * [size], and [bounds]. Whether a size change reflows siblings is a mode of the whole host, set
 * only by [size] and [bounds]: their functions take `reflow`, and their properties are visual. The
 * per-axis properties and [position] refine an axis's spec without changing that mode, so
 * `size(spring(), reflow = true); height = tween()` still reflows, with height on the tween.
 *
 * Reading a group property returns its spec only when every axis it covers shares that spec, and
 * `null` otherwise: after `bounds = spring(); height = tween()`, [bounds] and [size] read `null`
 * while [position] still reads the spring.
 */
@ExperimentalLithoApi
class StyleTransitionScope internal constructor() {
  private val changes = linkedMapOf<StyleTransitionProperty, FiniteAnimationSpec<Float>?>()
  private var reflowMode: Boolean = false

  /** Animates the host's mount-space x position. */
  var x: FiniteAnimationSpec<Float>?
    get() = changes[X]
    set(value) {
      setChange(value, X)
    }

  /** Animates the host's mount-space y position. */
  var y: FiniteAnimationSpec<Float>?
    get() = changes[Y]
    set(value) {
      setChange(value, Y)
    }

  /** Animates the host's mount-space width. */
  var width: FiniteAnimationSpec<Float>?
    get() = changes[WIDTH]
    set(value) {
      setChange(value, WIDTH)
    }

  /** Animates the host's mount-space height. */
  var height: FiniteAnimationSpec<Float>?
    get() = changes[HEIGHT]
    set(value) {
      setChange(value, HEIGHT)
    }

  /** Animates the host's mount-space position: `x` and `y`. */
  var position: FiniteAnimationSpec<Float>?
    get() = sharedChange(X, Y)
    set(value) {
      setChange(value, X, Y)
    }

  /**
   * Animates the host's mount-space size: `width` and `height`.
   *
   * A visual mount animation, like the [bounds] property: layout is already final while the size
   * animates. Use the [size] function with `reflow = true` when siblings should follow every frame.
   */
  var size: FiniteAnimationSpec<Float>?
    get() = sharedChange(WIDTH, HEIGHT)
    set(value) {
      setChange(value, WIDTH, HEIGHT)
      reflowMode = false
    }

  /**
   * Animates the host's size with [spec], like the [size] property.
   *
   * When [reflow] is true, the size change drives a real per-frame Yoga relayout, as described for
   * the [bounds] function. When [reflow] is false this is identical to setting the [size] property.
   * Either way the host's own position is not animated; declare [position] too, or use [bounds].
   */
  fun size(spec: FiniteAnimationSpec<Float>, reflow: Boolean = false) {
    setChange(spec, WIDTH, HEIGHT)
    reflowMode = reflow
  }

  /**
   * Animates mount-space host bounds: `x`, `y`, `width`, and `height`.
   *
   * Bounds changes are visual mount animations. Litho still computes the next Yoga layout
   * synchronously, and sibling layout is already in its final state while the host bounds animate.
   * This is useful for smooth movement, expansion, shrink, and reorder effects without per-frame
   * layout. Use state-based animated dimensions when the layout itself should change every frame.
   */
  var bounds: FiniteAnimationSpec<Float>?
    get() = sharedChange(X, Y, WIDTH, HEIGHT)
    set(value) {
      setChange(value, X, Y, WIDTH, HEIGHT)
      reflowMode = false
    }

  /**
   * Animates mount-space host bounds with [spec], like the [bounds] property.
   *
   * When [reflow] is true, a size change drives a real per-frame Yoga relayout — siblings and
   * nested content reflow to the interpolated size each frame — which is what content-driven size
   * changes (text expand/collapse, wrap-content) need and the state-based `useAnimatedSize` cannot
   * express (it requires an explicit target size). When [reflow] is false this is identical to
   * setting the [bounds] property: a visual-only mount animation after layout is already final.
   *
   * [reflow] requires state read tracking, enforced at declaration. Width and height animate
   * independently, each from its committed size to its layout-computed natural size; a spec that
   * carries its own values, such as `keyframes`, still applies them between those endpoints.
   */
  fun bounds(spec: FiniteAnimationSpec<Float>, reflow: Boolean = false) {
    setChange(spec, X, Y, WIDTH, HEIGHT)
    reflowMode = reflow
  }

  /** Animates host alpha when the resolved opacity changes. */
  var alpha: FiniteAnimationSpec<Float>?
    get() = changes[ALPHA]
    set(value) {
      setChange(value, ALPHA)
    }

  /** Animates uniform host scale by writing the same animated value to `scaleX` and `scaleY`. */
  var scale: FiniteAnimationSpec<Float>?
    get() = changes[SCALE]
    set(value) {
      setChange(value, SCALE)
    }

  /** Animates host rotation around the Z axis, in degrees. */
  var rotation: FiniteAnimationSpec<Float>?
    get() = changes[ROTATION]
    set(value) {
      setChange(value, ROTATION)
    }

  /** Animates host rotation around the X axis, in degrees. */
  var rotationX: FiniteAnimationSpec<Float>?
    get() = changes[ROTATION_X]
    set(value) {
      setChange(value, ROTATION_X)
    }

  /** Animates host rotation around the Y axis, in degrees. */
  var rotationY: FiniteAnimationSpec<Float>?
    get() = changes[ROTATION_Y]
    set(value) {
      setChange(value, ROTATION_Y)
    }

  internal fun build(): StyleTransitionSpec =
      StyleTransitionSpec(changes = changes.toMap(), reflowMode = reflowMode)

  /** The spec every one of [properties] shares, or null when any differs or is unset. */
  private fun sharedChange(
      vararg properties: StyleTransitionProperty,
  ): FiniteAnimationSpec<Float>? {
    val spec = changes[properties.first()] ?: return null
    return spec.takeIf { properties.all { changes[it] == spec } }
  }

  private fun setChange(
      spec: FiniteAnimationSpec<Float>?,
      vararg properties: StyleTransitionProperty,
  ) {
    for (property in properties) {
      changes[property] = spec
    }
  }
}

internal data class StyleTransitionData(val spec: StyleTransitionSpec) {
  fun plus(other: StyleTransitionData): StyleTransitionData = other.copy(spec = spec + other.spec)
}

internal sealed interface StyleTransitionId

internal data class StructuralStyleTransitionId(val nodeKey: Any) : StyleTransitionId

internal data class StyleTransitionTarget(
    val data: StyleTransitionData,
    val styleTransitionId: StyleTransitionId,
    val outputGroup: OutputUnitsAffinityGroup<AnimatableItem>,
) {
  val animatableItem: AnimatableItem?
    get() = outputGroup.getMostSignificantUnit()
}

@DataClassGenerate internal data class TransitionStyleItemValue(val spec: StyleTransitionSpec)

private enum class TransitionStyleField : StyleItemField {
  TRANSITION,
}

@DataClassGenerate
internal data class TransitionStyleItem(val spec: StyleTransitionSpec) :
    StyleItem<TransitionStyleItemValue> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    commonProps.styleTransition(StyleTransitionData(spec))
  }

  override val value: TransitionStyleItemValue = TransitionStyleItemValue(spec)
  override val field: StyleItemField = TransitionStyleField.TRANSITION
}

internal enum class StyleTransitionProperty(val animatedProperty: AnimatedProperty) {
  X(AnimatedProperties.X),
  Y(AnimatedProperties.Y),
  WIDTH(AnimatedProperties.WIDTH),
  HEIGHT(AnimatedProperties.HEIGHT),
  ALPHA(AnimatedProperties.ALPHA),
  SCALE(AnimatedProperties.SCALE),
  ROTATION(AnimatedProperties.ROTATION),
  ROTATION_X(AnimatedProperties.ROTATION_X),
  ROTATION_Y(AnimatedProperties.ROTATION_Y),
}
