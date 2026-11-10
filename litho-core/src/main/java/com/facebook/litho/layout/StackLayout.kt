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

import android.annotation.SuppressLint
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ContainerDsl
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.Wrapper
import com.facebook.litho.flexbox.positionType
import com.facebook.litho.key
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.view.IntField
import com.facebook.litho.view.IntStyleItem
import com.facebook.litho.view.wrapInView
import com.facebook.rendercore.Dimen
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify
import com.facebook.yoga.YogaPositionType
import com.facebook.yoga.YogaWrap

/**
 * A layout component that stacks its children on top of each other. Each child is positioned
 * absolutely within the container, allowing them to overlap.
 *
 * The first child establishes the container's size and remains in normal flow. Subsequent children
 * are absolutely positioned to overlay on top of the first child.
 *
 * Children are rendered in the order they are added, with later children appearing on top of
 * earlier ones. You can control the stacking order by specifying a custom `zIndex` when adding
 * children.
 *
 * You can provide a `key` when adding children to ensure stable component identity across renders
 * when children are added/removed/reordered.
 *
 * Example usage:
 * ```
 * StackLayout(style = Style.width(200.dp).height(200.dp)) {
 *   // First child establishes the container size
 *   child(Image(drawable = drawableRes(R.drawable.background_image)))
 *
 *   // Second child with custom zIndex to control stacking order
 *   child(component = Badge(count = 5), zIndex = 10)
 *
 *   // Third child overlays on top of the Image but below the Badge
 *   child(
 *     Text(
 *       text = "Overlay Text",
 *       textSize = 16.sp,
 *       style = Style.alignSelf(YogaAlign.CENTER)
 *     )
 *   )
 *   // Third child with custom zIndex to control stacking order
 *   child(component = Badge(count = 5), zIndex = 10)
 * }
 * ```
 *
 * @param alignContent Aligns a flex container's lines when there is extra space in the cross-axis.
 * @param alignItems Aligns children along the cross axis.
 * @param justifyContent Aligns children along the main axis.
 * @param wrap Controls whether children wrap to multiple lines.
 * @param gap Spacing between children.
 * @param style Style to apply to the stack container.
 * @param children Lambda to add child components using [StackContainerScope.child].
 */
@Suppress("FunctionName", "NOTHING_TO_INLINE")
inline fun ResourcesScope.StackLayout(
    alignContent: YogaAlign? = null,
    alignItems: YogaAlign? = null,
    justifyContent: YogaJustify? = null,
    wrap: YogaWrap? = null,
    gap: Dimen? = null,
    style: Style = Style,
    noinline children: StackContainerScope.() -> Unit,
): Component {

  return StackLayoutComponent(
      alignContent = alignContent,
      alignItems = alignItems,
      justifyContent = justifyContent,
      wrap = wrap,
      gap = gap,
      style = style,
      block = children,
  )
}

/**
 * A layout component that stacks its children on top of each other. Each child is positioned
 * absolutely within the container, allowing them to overlap.
 */
class StackLayoutComponent
@PublishedApi
internal constructor(
    private val alignContent: YogaAlign? = null,
    private val alignItems: YogaAlign? = null,
    private val justifyContent: YogaJustify? = null,
    private val wrap: YogaWrap? = null,
    private val gap: Dimen? = null,
    private val style: Style,
    private val block: StackContainerScope.() -> Unit,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val scope = StackContainerScope(context = context)
    scope.block()
    val items = scope.children
    return Column(
        alignContent = alignContent,
        alignItems = alignItems,
        justifyContent = justifyContent,
        wrap = wrap,
        gap = gap,
        style = style + Style.wrapInView(),
    ) {
      items.forEachIndexed { index, item ->
        // The first child remains in normal flow to establish
        // the container's size. Subsequent children are absolutely
        // positioned to overlay on top.
        val itemStyle = if (index == 0) Style else Style.positionType(YogaPositionType.ABSOLUTE)
        // Apply zIndex without wrapInView() — the child component's own View
        // (e.g., from DynamicValues) will inherit the z-ordering. This avoids
        // creating a redundant extra View per child.
        @SuppressLint("KComponentMissingChildInTrailingLambdaDetector")
        val wrapped =
            Wrapper(style = itemStyle + IntStyleItem(IntField.Z_INDEX, item.zIndex)) {
              item.component
            }
        // When a key is provided, apply it to the Wrapper so Litho matches children
        // by key instead of position, preserving state across reorders.
        if (item.key != null) {
          child(key(item.key) { wrapped })
        } else {
          child(wrapped)
        }
      }
    }
  }
}

@ContainerDsl
class StackContainerScope(override val context: ComponentContext) : ResourcesScope {

  internal val children: MutableList<ChildLayoutProps> = mutableListOf()

  /** Adds a Component as a child to the Stack being initialized. */
  fun child(component: Component, zIndex: Int = children.size, key: String? = null) {
    children.add(ChildLayoutProps(component = component, zIndex = zIndex, key = key))
  }
}

@DataClassGenerate
internal data class ChildLayoutProps(
    val component: Component,
    val zIndex: Int,
    val key: String?,
)
