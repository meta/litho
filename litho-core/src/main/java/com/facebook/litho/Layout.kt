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

import com.facebook.litho.Resolver.resolveDeferred
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.layout.LayoutFunction
import com.facebook.litho.layout.LayoutFunction1
import com.facebook.litho.layout.LayoutFunction2
import com.facebook.litho.layout.LayoutFunction3
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.LayoutProps
import com.facebook.rendercore.annotations.AutoDeps
import com.facebook.rendercore.annotations.Deps
import com.facebook.rendercore.annotations.DepsFrom
import com.facebook.rendercore.utils.areObjectsEquivalent

/**
 * A container component that owns the children it is given.
 *
 * A `Row` or a `Column` hands its children to flexbox. A `Layout` positions them itself: its layout
 * function measures the children, gives the container a size and places each child. Use it for
 * layouts flexbox cannot express, such as a pager whose pages sit at a computed stride, a split
 * pane sized by a dragged divider, or a header that collapses as another child scrolls.
 *
 * It is an ordinary component, returned from `render`:
 * ```kotlin
 * class MyRow(private val header: Component, private val body: Component) : KComponent() {
 *   override fun ComponentScope.render(): Component =
 *       Layout(header, body) { header, body, constraints -> … }
 * }
 * ```
 *
 * Pass a list for children that come from data. The one-, two- and three-child forms build the same
 * container and let a fixed set of children be named at the call site:
 * ```kotlin
 * Layout(items.map { Page(it).key(it.id) }) { measurables, constraints -> … }   // N children
 * Layout(avatar, name, timestamp) { avatar, name, timestamp, constraints -> … } // three, named
 * ```
 *
 * A layout that needs per-child values — an angle, an alignment, a column — reads them with
 * `measurable.layoutProps`, as [LayoutProps] classes its children set in their own `Style` with
 * `layoutProps`.
 *
 * **A container is unchanged only when its layout function and its `deps` are.** The Litho compiler
 * plugin fills in `deps` from what the layout lambda captures, so a lambda written at the call site
 * needs nothing more:
 * ```kotlin
 * Layout(header, body) { header, body, constraints -> … spacing … } // deps = arrayOf(spacing)
 * ```
 *
 * With `deps`, inferred or passed, the container is unchanged when the function is of the same
 * class and the deps are equivalent. An inferred set can be empty, for a lambda that captures
 * nothing or only values the plugin ignores, and `deps = emptyArray()` says the function depends on
 * nothing; either way the class alone decides. A layout function that isn't a lambda written at the
 * call site, such as an `object`, has no closure for the plugin to read, so its `deps` keep their
 * default and the function is compared with `equals`. So does every call compiled without the
 * plugin, where a lambda that captures values is a new instance and never unchanged.
 *
 * A child that renders nothing keeps its slot as an empty child that measures 0x0, so every child
 * has the index it was declared at and a function naming its children always gets them all.
 *
 * Give children a stable `key` when they come from data. Without one, a child's state is keyed by
 * its position among its same-type siblings, so inserting at the front hands the second child the
 * first child's state. That shows up as a video resuming at the wrong timestamp rather than as
 * something that looks like a bug.
 *
 * Children resolve into the parent's Litho tree rather than a nested one, so hook identity, prop
 * reconciliation, incremental mount and visibility keep working across them without forwarding
 * anything by hand.
 *
 * In a flexbox parent, a `Layout` is rounded to whole pixels as a `Column` is, to the nearest. A
 * component that measures its own content, such as one rendering with `RenderWithConstraints` or a
 * size spec, is rounded as text is, never narrower than it measured. So a `Layout` that replaces
 * one can come out 1 px narrower when its parent gives it a fractional width.
 *
 * A `Layout` mounts no content, so it gets a host of its own only when its `style` needs one.
 * `onClick`, `viewTag`, `focusable`, a shadow and a transition name each create a host, as on any
 * other component. Intercepting touch, cooperating with a nested scroll, or transforming children
 * per frame still needs whatever mechanism you use today.
 *
 * A container's `style` is what it tells its own parent, and the props the framework applies to any
 * node: size, padding, margin, background, and so on. Its padding and border are the one way it
 * reaches the children: the layout function works inside them, so they narrow the constraints it
 * receives. Anything else that configures the arrangement — spacing, alignment — the layout
 * function captures, or the component returning this `Layout` takes as a prop. A [LayoutProps] set
 * in it is read by this container's parent, not by this container.
 */
@ExperimentalLithoApi
class Layout
internal constructor(
    private val components: List<Component>,
    private val style: Style,
    private val deps: Array<out Any?>,
    private val layoutFunction: LayoutFunction,
) : Component() {

  init {
    // The type keeps a null child out of Kotlin code only: a list built in Java, or reached through
    // an unchecked cast, can still hold one.
    for (i in components.indices) {
      val child: Component? = components[i]
      requireNotNull(child) { "A Layout's children can't be null, but child $i is." }
    }
  }

  override fun resolve(
      resolveContext: ResolveContext,
      scopedComponentInfo: ScopedComponentInfo,
      parentWidthSpec: Int,
      parentHeightSpec: Int,
  ): ComponentResolveResult {
    val c = scopedComponentInfo.context
    val node = LayoutNode()
    for (child in components) {
      // Resolve can be abandoned or handed back to the main thread between any two children, and a
      // container that keeps descending defeats both: a released future's tree is thrown away, and
      // an interrupted one is finished by `Resolver.resumeResolvingTree`, which can only resume the
      // children stashed here.
      if (resolveContext.isFutureReleased) {
        return ComponentResolveResult(null, null)
      }
      if (resolveContext.isResolveInterrupted) {
        node.appendUnresolvedComponent(child)
      } else {
        node.child(resolveContext, c, child)
      }
    }
    node.layoutFunction = layoutFunction
    return ComponentResolveResult(node, createCommonProps(c))
  }

  override fun resolveDeferred(
      calculationContext: CalculationContext,
      componentContext: ComponentContext,
      parentContext: ComponentContext,
  ): ComponentResolveResult =
      this.resolveDeferred(
          calculationContext,
          componentContext,
          parentContext,
          createCommonProps(componentContext),
      )

  private fun createCommonProps(c: ComponentContext): CommonProps? =
      if (style !== Style) CommonProps().apply { style.applyCommonProps(c, this) } else null

  /**
   * Compares the children, the style and the layout function.
   *
   * Inheriting the default would compare nothing. [Component.isEquivalentProps] defers to
   * `ComponentUtils.hasEquivalentFields`, which only looks at fields annotated `@Comparable`, and
   * the Spec API's processor is what generates those onto `@Prop`s. A Kotlin component has none, so
   * every container would be equivalent to every other and a changed child would never reach the
   * screen. `KComponent` overrides this for the same reason.
   *
   * Two renders of one call site share the layout function's class even when what the lambda
   * captured changed, so the class alone would keep a container laid out against a stale value.
   * With [deps], the class and the deps decide: the deps hold everything the function captured.
   * When either side's deps kept their default, `NOT_INFERRED`, nothing says what its function
   * captured, so the function is compared with `equals`: a lambda or a plain class is the same only
   * as itself, an `object` always is, and a `data class` is when its properties are.
   */
  override fun isEquivalentProps(other: Component?): Boolean {
    if (this === other) {
      return true
    }
    if (other !is Layout || javaClass != other.javaClass) {
      return false
    }
    if (instanceId == other.instanceId) {
      return true
    }
    val isSameFunction =
        if (deps === NOT_INFERRED || other.deps === NOT_INFERRED) {
          layoutFunction == other.layoutFunction
        } else {
          layoutFunction.javaClass == other.layoutFunction.javaClass &&
              areObjectsEquivalent(deps, other.deps)
        }
    return isSameFunction &&
        areObjectsEquivalent(components, other.components) &&
        areObjectsEquivalent(style, other.style)
  }

  override fun isPureRender(): Boolean = true
}

/**
 * The default of a `Layout`'s `deps`, kept by a call compiled without the Litho plugin and by one
 * passing a layout function that isn't a lambda written at the call site. It is compared by
 * identity, so it means the deps weren't inferred, never that there are none: an inferred empty set
 * is a new array.
 */
private val NOT_INFERRED: Array<Any?> = arrayOf()

/**
 * A container over a list of children, for a count that is not known until runtime.
 *
 * @param components the children, in the order they are laid out
 * @param style applied to the container itself
 * @param deps the values [layout] depends on, inferred by the Litho compiler plugin from a lambda
 *   written at the call site; `emptyArray()` for none
 * @param layout measures and places the children, receiving them as a list
 */
@ExperimentalLithoApi
@AutoDeps
fun Layout(
    components: List<Component>,
    style: Style = Style,
    @Deps deps: Array<Any?> = NOT_INFERRED,
    @DepsFrom(keepsDefaultIfNotLiteral = true) layout: LayoutFunctionN,
): Layout = Layout(components, style, deps, layoutFunction = layout)

/**
 * A container over one child, which [layout] receives by name rather than by index.
 *
 * @param component1 the only child
 * @param style applied to the container itself
 * @param deps the values [layout] depends on, inferred by the Litho compiler plugin from a lambda
 *   written at the call site; `emptyArray()` for none
 * @param layout measures and places the child
 */
@ExperimentalLithoApi
@AutoDeps
fun Layout(
    component1: Component,
    style: Style = Style,
    @Deps deps: Array<Any?> = NOT_INFERRED,
    @DepsFrom(keepsDefaultIfNotLiteral = true) layout: LayoutFunction1,
): Layout = Layout(listOf(component1), style, deps, layoutFunction = layout)

/**
 * A container over two children, which [layout] receives by name rather than by index.
 *
 * @param component1 the first child
 * @param component2 the second child
 * @param style applied to the container itself
 * @param deps the values [layout] depends on, inferred by the Litho compiler plugin from a lambda
 *   written at the call site; `emptyArray()` for none
 * @param layout measures and places the children
 */
@ExperimentalLithoApi
@AutoDeps
fun Layout(
    component1: Component,
    component2: Component,
    style: Style = Style,
    @Deps deps: Array<Any?> = NOT_INFERRED,
    @DepsFrom(keepsDefaultIfNotLiteral = true) layout: LayoutFunction2,
): Layout = Layout(listOf(component1, component2), style, deps, layoutFunction = layout)

/**
 * A container over three children, which [layout] receives by name rather than by index. Past
 * three, use the list of components.
 *
 * @param component1 the first child
 * @param component2 the second child
 * @param component3 the third child
 * @param style applied to the container itself
 * @param deps the values [layout] depends on, inferred by the Litho compiler plugin from a lambda
 *   written at the call site; `emptyArray()` for none
 * @param layout measures and places the children
 */
@ExperimentalLithoApi
@AutoDeps
fun Layout(
    component1: Component,
    component2: Component,
    component3: Component,
    style: Style = Style,
    @Deps deps: Array<Any?> = NOT_INFERRED,
    @DepsFrom(keepsDefaultIfNotLiteral = true) layout: LayoutFunction3,
): Layout = Layout(listOf(component1, component2, component3), style, deps, layoutFunction = layout)

/**
 * The node of a `Layout`, whose children keep their declared slots: one that resolves to no node
 * holds its slot as an empty node, which lays out at 0x0.
 */
internal class LayoutNode : LithoNode() {

  override fun child(resolveContext: ResolveContext, c: ComponentContext, child: Component?) {
    child(
        child?.let { Resolver.resolve(resolveContext, c, it) }
            ?: Resolver.resolve(resolveContext, c, EmptyComponent()),
    )
  }
}
