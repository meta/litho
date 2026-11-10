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

package com.facebook.litho.layout

import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.layout.internal.DefaultLayoutBuilder
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.ResourceResolver
import com.facebook.rendercore.SizeConstraints

/**
 * Positions the children of a container.
 *
 * Yoga can express a row and a column. It cannot express a pager, where each page sits at a stride
 * the container computes; or a split pane, where a divider the user drags decides the sizes; or a
 * collapsing header, where one child's position depends on another's scroll offset. A layout
 * function is how a container says where its children go.
 *
 * Children remain part of the parent's Litho tree. They are not a nested tree, so incremental
 * mount, visibility and state all continue to work across them without the container forwarding
 * anything by hand.
 *
 * Sealed, so the only implementations are the arity shapes below. A container picks one by how many
 * parameters its lambda declares, and the constructor it matches says how many children it may
 * name, so handing a two-child function to a three-child container fails to compile.
 *
 * The intrinsic-size questions all have answers here, each of which runs the layout function and
 * reads the size back. Override one when the container knows its answer more cheaply — a pager is
 * one page wide however many pages it holds — which needs an object rather than a lambda. An
 * override receives the real children, and an [IntrinsicMeasureScope], which cannot measure them.
 * Litho doesn't ask these questions yet, so an override changes no layout today.
 */
@ExperimentalLithoApi
sealed interface LayoutFunction {

  /**
   * Measure the children, then say how big this container is and where each child goes.
   *
   * @param measurables the children, to measure and then place
   * @param constraints what the parent will allow this container to occupy
   * @throws IllegalStateException if it returns without calling [MeasureScope.layout], since the
   *   container would then have no size and its children nowhere to go
   */
  fun MeasureScope.measure(measurables: List<Measurable>, constraints: SizeConstraints)

  /** The widest this container would like to be at [height], ignoring any upper bound. */
  fun IntrinsicMeasureScope.maxIntrinsicWidth(
      measurables: List<Measurable>,
      height: Int,
  ): Int = measureForIntrinsic(
      this@LayoutFunction,
      measurables,
      atMostConstraints(SizeConstraints.Infinity, height),
  )
      .width

  /**
   * The narrowest this container can be at [height] without its content breaking out of it.
   *
   * This is what a container contributes to the CSS Flexbox §4.5 automatic minimum, so a `Row` that
   * squeezes it cannot take it below this. It's reserved for intrinsic sizing: Litho doesn't call
   * it, or the other intrinsic functions, yet, so overriding one changes no layout today.
   */
  fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<Measurable>, height: Int): Int =
      measureForIntrinsic(this@LayoutFunction, measurables, atMostConstraints(0, height)).width

  /** The tallest this container would like to be at [width], ignoring any upper bound. */
  fun IntrinsicMeasureScope.maxIntrinsicHeight(
      measurables: List<Measurable>,
      width: Int,
  ): Int = measureForIntrinsic(
      this@LayoutFunction,
      measurables,
      atMostConstraints(width, SizeConstraints.Infinity),
  )
      .height

  /** The shortest this container can be at [width]. The other axis of [minIntrinsicWidth]. */
  fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<Measurable>, width: Int): Int =
      measureForIntrinsic(this@LayoutFunction, measurables, atMostConstraints(width, 0)).height
}

/**
 * A layout function for a container whose child count is not known until runtime.
 *
 * ```kotlin
 * Layout(pages.map { Page(it).key(it.id) }) { measurables, constraints -> … }
 * ```
 */
@ExperimentalLithoApi
fun interface LayoutFunctionN : LayoutFunction {
  override fun MeasureScope.measure(measurables: List<Measurable>, constraints: SizeConstraints)
}

/**
 * A layout function for a container with exactly one child, which it receives by name.
 *
 * ```kotlin
 * Layout(icon) { child, constraints ->
 *   val placeable = child.measure(constraints.copy(minWidth = 0, minHeight = 0))
 *   val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
 *   layout(width, placeable.height) {
 *     placeable.place(x = (width - placeable.measuredWidth) / 2, y = 0)
 *   }
 * }
 * ```
 *
 * Naming the children beats indexing a list for them: `measurables[0]` is a lookup that can be
 * wrong, `child` cannot be.
 */
@ExperimentalLithoApi
fun interface LayoutFunction1 : LayoutFunction {

  fun MeasureScope.measure(child: Measurable, constraints: SizeConstraints)

  override fun MeasureScope.measure(measurables: List<Measurable>, constraints: SizeConstraints) {
    measurables.requireArity(1)
    measure(measurables[0], constraints)
  }
}

/** A layout function for a container with exactly two children, which it receives by name. */
@ExperimentalLithoApi
fun interface LayoutFunction2 : LayoutFunction {

  fun MeasureScope.measure(
      child1: Measurable,
      child2: Measurable,
      constraints: SizeConstraints,
  )

  override fun MeasureScope.measure(measurables: List<Measurable>, constraints: SizeConstraints) {
    measurables.requireArity(2)
    measure(measurables[0], measurables[1], constraints)
  }
}

/** A layout function for a container with exactly three children, which it receives by name. */
@ExperimentalLithoApi
fun interface LayoutFunction3 : LayoutFunction {

  fun MeasureScope.measure(
      child1: Measurable,
      child2: Measurable,
      child3: Measurable,
      constraints: SizeConstraints,
  )

  override fun MeasureScope.measure(measurables: List<Measurable>, constraints: SizeConstraints) {
    measurables.requireArity(3)
    measure(measurables[0], measurables[1], measurables[2], constraints)
  }
}

/**
 * Refuses a child list that does not match the arity the layout function names.
 *
 * The `Layout` constructors pair a container's child count with the one shape that fits it, and a
 * child rendering to nothing still holds its slot, so a well-formed call site cannot get here. It
 * takes a `Component` whose `resolve` produces no node at all — and naming that beats the
 * `IndexOutOfBoundsException` reading past the end would raise from inside the layout pass.
 */
@Suppress("OPT_IN_USAGE")
private fun List<Measurable>.requireArity(expected: Int) {
  check(size == expected) {
    "A layout function naming $expected children was handed $size. The constructors pair the two, " +
        "so one of the declared children resolved to no node at all."
  }
}

/**
 * Marks the scopes a layout function runs in: inside a placement block only [PlacementScope]'s
 * members resolve implicitly, so measuring a child or declaring a size there does not compile.
 */
@ExperimentalLithoApi @DslMarker annotation class LayoutScopeMarker

/**
 * What a layout function can do whether it is answering an intrinsic question or measuring: read
 * its environment. Receiver for a [LayoutFunction]'s intrinsic overrides, which answer a number:
 * they may not measure, declare a size or place. [MeasureScope] adds measuring and declaring.
 *
 * Nothing in Litho asks a layout function's intrinsic overrides yet: a `Layout`'s builder runs
 * [LayoutFunction.measure].
 *
 * Sealed: only Litho provides one.
 */
@ExperimentalLithoApi
@LayoutScopeMarker
sealed class IntrinsicMeasureScope(
    /** The container's resource resolver, which [toPixels] converts through. */
    internal val resourceResolver: ResourceResolver,
    /** The direction the container is laid out in. */
    val layoutDirection: LayoutDirection,
) {

  /** This dimension in pixels, as the container's resources resolve it: `16.dp.toPixels()`. */
  fun Dimen.toPixels(): Int = toPixels(resourceResolver)

  /**
   * Runs [function] under [constraints] and reports the size it asked for: the default answer to
   * each of its intrinsic questions.
   *
   * Only the size is read; the placements are discarded unevaluated. The children it measures are
   * laid out as in any other run, so an honest answer costs a measure pass, which is what an
   * override exists to avoid.
   *
   * @throws IllegalStateException if the layout function returns without calling
   *   [MeasureScope.layout], since there is then no size to report
   */
  internal fun measureForIntrinsic(
      function: LayoutFunction,
      measurables: List<Measurable>,
      constraints: SizeConstraints,
  ): IntSize {
    val scope = MeasureScope(resourceResolver, layoutDirection)
    with(function) { scope.measure(measurables, constraints) }
    // Without this the omission is answered rather than reported: the scope still holds its
    // initial 0x0, and an intrinsic of zero is a size Yoga will act on.
    checkNotNull(scope.placement) { NO_SIZE_DECLARED }
    return IntSize(scope.width, scope.height)
  }
}

/**
 * Receiver for [LayoutFunction.measure], and where a pass leaves what it works out: it asks its
 * children as [IntrinsicMeasureScope] does, measures them, and declares.
 *
 * A layout function reports through the scope rather than by returning a value, so everything a
 * pass produces arrives by one route and a later addition needs no new return type.
 */
@ExperimentalLithoApi
@LayoutScopeMarker
class MeasureScope
internal constructor(
    resourceResolver: ResourceResolver,
    layoutDirection: LayoutDirection,
) : IntrinsicMeasureScope(resourceResolver, layoutDirection) {

  /**
   * Measure this child under [constraints] and report the size it took.
   *
   * **May be called more than once per pass.** CSS Flexbox §9.7 resolves flexible lengths by
   * measuring a child at its flex basis, distributing free space, and measuring again at the
   * resolved size, so a flexbox written on this API necessarily measures twice.
   *
   * Measured again under the same constraints in the same pass, a child returns the same
   * [Placeable] and isn't measured again. Only a child whose placeable the container places is
   * built and mounted; one measured and not placed is discarded. Placing a placeable from an
   * earlier measurement than the child's latest may lay that child out again.
   *
   * The child's own size props — width, min and max, aspect ratio, margins, padding and border —
   * are resolved against [constraints] and win over them, so the child may take more or less than
   * they allow. The placeable reports the size it took as [Placeable.measuredWidth] and
   * [Placeable.measuredHeight], and that size kept within [constraints] as [Placeable.width] and
   * [Placeable.height].
   *
   * Not from the placement block: placing only chooses among the measurements already made. Not
   * from an intrinsic override either, which answers a number.
   *
   * @throws IllegalStateException if called while the layout function isn't running: from the
   *   placement block, or on a [Measurable] kept from an earlier pass or used after the function
   *   returned.
   */
  fun Measurable.measure(constraints: SizeConstraints): Placeable =
      owner.measureChildUnder(this, constraints)

  internal var width: Int = 0
    private set

  internal var height: Int = 0
    private set

  /**
   * The placement block, still unevaluated, or null until [layout] has been called.
   *
   * A block rather than coordinates because a position only means something against a size that has
   * settled, and Yoga may still override [width] — a stretching parent routinely does. Coordinates
   * computed during measurement would be wrong in exactly that case. The framework runs the block
   * once the size is final.
   */
  internal var placement: (PlacementScope.() -> Unit)? = null
    private set

  /**
   * Declare this container's size and how its children are positioned.
   *
   * Positioning happens inside the block and nowhere else: `place` exists only on [PlacementScope],
   * so placing a child while still measuring is a compile error rather than a child placed against
   * a size that has not settled.
   *
   * @param width the width this container asks for; the parent may override it
   * @param height the height this container asks for; the parent may override it
   * @param placement runs later, once the final size is known, which may differ from [width] and
   *   [height]. Capture whatever it needs — [Placeable]s, sizes, anything computed while measuring
   *   — and it will see those values. It only places: measuring a child there does not compile.
   * @throws IllegalStateException if called twice, since one of the two sizes would be discarded
   * @throws IllegalArgumentException if [width] or [height] is negative
   */
  fun layout(width: Int, height: Int, placement: PlacementScope.() -> Unit) {
    check(this.placement == null) {
      "layout() has already been called on this container. Calling it twice means one of the two " +
          "sizes, and one of the two placement blocks, is silently discarded."
    }
    require(width >= 0 && height >= 0) {
      "A container cannot be ${width}x$height. Both dimensions must be at least 0."
    }
    this.width = width
    this.height = height
    this.placement = placement
  }
}

/**
 * Receiver for the placement pass.
 *
 * A child that is never placed occupies no space and will not be mounted. Declining to place is
 * therefore how a container drops a child: measuring one to see whether it fits and then leaving it
 * out costs only its measurement, since it isn't built or mounted.
 */
@ExperimentalLithoApi
@LayoutScopeMarker
class PlacementScope
internal constructor(
    /** The container's resource resolver, which [toPixels] converts through. */
    private val resourceResolver: ResourceResolver,
    childCount: Int,
    /** The width the container declared, which a right-to-left placement mirrors against. */
    private val declaredWidth: Int,
    /** The direction the container is laid out in. */
    val layoutDirection: LayoutDirection,
    /** The container placing, whose children's placeables are the only ones it may place. */
    private val owner: DefaultLayoutBuilder,
) {

  internal val placements: IntArray = IntArray(childCount * 2)

  /**
   * The placeable placed for each child, whose box the child is built in. A child with none is left
   * out of the mounted output entirely.
   */
  internal val placedWith: Array<Placeable?> = arrayOfNulls(childCount)

  /** How many children were placed. */
  internal var placedCount: Int = 0
    private set

  /**
   * The index of each child placed, in the order it was placed; the first [placedCount] are set.
   */
  internal val placedOrder: IntArray = IntArray(childCount)

  /** This dimension in pixels, as the container's resources resolve it. */
  fun Dimen.toPixels(): Int = toPixels(resourceResolver)

  /**
   * Position this child at [start] from this container's start edge and [top] from its top, so a
   * right-to-left container mirrors it: from the left edge in left-to-right, and from the right
   * edge in right-to-left, against the width the container ends up with. [start] is measured to the
   * start edge of this placeable's [Placeable.width].
   */
  fun Placeable.placeRelative(start: Int = 0, top: Int = 0) {
    val x = if (layoutDirection.isRTL) declaredWidth - start - width else start
    place(x, top)
  }

  /**
   * Position this child at [x], [y] from the top-left of the width this container declared, inside
   * its padding and border. [x] isn't mirrored in right-to-left: use [placeRelative] for that. The
   * declared width starts at the content box's start edge, so it's the content box's left edge
   * whenever the container ends up with the width it declared; when it doesn't, in right-to-left
   * the declared width is aligned to the right edge.
   *
   * Paint order is placement order: two children that overlap are drawn in the order they were
   * placed in here, the last placed on top, whatever order the container declared them in.
   *
   * @throws IllegalStateException if this child has already been placed, since one of the two
   *   positions would be discarded
   * @throws IllegalArgumentException if this placeable was measured by another container's layout
   *   function
   */
  fun Placeable.place(x: Int = 0, y: Int = 0) {
    val index = measurable.index
    require(measurable.owner === this@PlacementScope.owner) {
      "Cannot place a child of another container. A placeable belongs to the container whose " +
          "layout function measured it."
    }
    check(placedWith[index] == null) {
      "Child $index has already been placed. Placing twice means one of the two positions is " +
          "silently discarded."
    }
    placedWith[index] = this
    placedOrder[placedCount] = index
    placedCount++
    placements[index * 2] = x
    placements[index * 2 + 1] = y
  }
}

private fun atMostConstraints(maxWidth: Int, maxHeight: Int): SizeConstraints =
    SizeConstraints(maxWidth = maxWidth, maxHeight = maxHeight)

/**
 * What a layout function that never called [MeasureScope.layout] is told.
 *
 * Shared by every place a layout function runs, so a container hears the same thing whether it was
 * asked for an intrinsic size, measured by its parent or by Yoga, or built.
 */
internal const val NO_SIZE_DECLARED: String =
    "A layout function must finish by calling layout(width, height) { … }. This one returned " +
        "without declaring a size, so the container has none and its children have nowhere to go."
