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

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.CommonProps
import com.facebook.litho.ComponentContext
import com.facebook.litho.LithoNode
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.litho.annotations.ExperimentalLithoApi

/**
 * A value a child carries for whichever `Layout` reads it: an angle, an alignment, a column.
 * Unrelated to `com.facebook.litho.LayoutProps`, the Yoga props a component sets on its own node.
 *
 * The class is the key. A layout declares a class for each value it reads, and a setter named for
 * its layout:
 * ```kotlin
 * private data class RadialPosition(val angle: Float, val radius: Dimen) : LayoutProps
 *
 * fun Style.radialPosition(angle: Float, radius: Dimen): Style =
 *     layoutProps(RadialPosition(angle, radius))
 *
 * // In the layout function
 * val position = child[RadialPosition::class] ?: RadialPosition(0f, 0.px)
 * ```
 *
 * Values are never merged. A later value replaces an earlier one whole, and an outer component's
 * replaces the one it wraps, as every other style prop does. Values that change independently
 * belong in classes of their own, so each is replaced on its own; values that must stay consistent
 * belong together in one class, so they are replaced together.
 *
 * Implement it on a final class: a data class, an enum, or a `@JvmInline value class` for a single
 * number. A value is stored and read under the type named at the call, so a setter or reader for a
 * sealed type names the sealed type, not one of its cases. A generic class is not a key: `Foo<A>`
 * and `Foo<B>` are one class at runtime.
 *
 * Values take part in `CommonProps` equivalence. Prefer immutable types with stable, meaningful
 * `equals` semantics, such as data classes. Lambdas, arrays, and plain classes compare by identity
 * unless deliberately wrapped; mutable values can also hide a change from equivalence. Identity is
 * appropriate only when it is part of the value's contract, as it is for reactive handles whose
 * updates are tracked separately.
 */
@ExperimentalLithoApi interface LayoutProps

/**
 * One field per [LayoutProps] class, equal for the same class. Tools such as the style editor key
 * items by field, so values of different classes must not share one.
 */
private class LayoutPropsField(private val type: Class<*>) : StyleItemField {
  override fun equals(other: Any?): Boolean = other is LayoutPropsField && other.type == type

  override fun hashCode(): Int = type.hashCode()

  override fun toString(): String = "LAYOUT_VALUE(${type.name})"
}

@DataClassGenerate
@Suppress("OPT_IN_USAGE")
internal data class LayoutPropsStyleItem<T : LayoutProps>(
    val type: Class<T>,
    override val value: T,
) : StyleItem<T> {
  // A getter: only tools read the field, so setting a value allocates nothing but this item.
  override val field: StyleItemField
    get() = LayoutPropsField(type)

  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    commonProps.setValueForParentLayout(type, value)
  }
}

/**
 * Sets [value] on this component under [type], for the `Layout` it is a child of to read. Layout
 * authors wrap this in a setter named for their layout, so callers name the layout and value they
 * address. [value] follows the immutability and equality contract documented by [LayoutProps].
 */
@ExperimentalLithoApi
fun <T : LayoutProps> Style.layoutProps(type: Class<T>, value: T): Style =
    this + LayoutPropsStyleItem(type, value)

/** Sets [value] on this component under [T]. */
@ExperimentalLithoApi
inline fun <reified T : LayoutProps> Style.layoutProps(value: T): Style =
    layoutProps(T::class.java, value)

/**
 * Reads the value stored under [type] on this node, or null.
 *
 * Head first, so the outermost component that set one wins, as it does for a node's other layout
 * props, which are applied tail first, each overwriting the last.
 */
@Suppress("OPT_IN_USAGE")
internal fun <T : LayoutProps> LithoNode.readLayoutProps(type: Class<T>): T? {
  for (i in componentCount - 1 downTo 0) {
    getCommonPropsAt(i)?.getValueForParentLayout(type)?.let {
      return it
    }
  }
  return null
}
