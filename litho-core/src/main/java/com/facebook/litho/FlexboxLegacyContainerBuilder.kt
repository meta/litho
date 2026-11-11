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

@file:Suppress("DEPRECATION")

package com.facebook.litho

import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.layout.flexbox.KColumn
import com.facebook.litho.layout.flexbox.KRow
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.dp
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaJustify
import com.facebook.yoga.YogaWrap

/**
 * Base for the LEGACY [Row.Builder] / [Column.Builder]. It keeps the exact
 * `Component.ContainerBuilder` fluent surface (so every existing `Row.create(c)...build()` call
 * site — Java or Kotlin — compiles unchanged and so `Component.ContainerBuilder<?>` handles keep
 * working). Common-prop setters are inherited unchanged and populate the [CommonProps] of the
 * backing [scratch] component, exactly as they always have.
 *
 * [build] is gated by [ComponentsConfiguration.useKComponentForLegacyRowColumn], snapshotted when
 * the builder is created:
 * - when ON, it constructs a [KRow] / [KColumn] that carries those [CommonProps], so the container
 *   resolves and compares its props the way the Spec-generated containers do;
 * - when OFF (default), it returns the backing [scratch] legacy SpecGenerated `Row`/`Column`.
 *
 * The [Component.Builder] machinery requires a [SpecGeneratedComponent] as its backing component;
 * it doubles as the [scratch] for the flag-OFF path.
 */
abstract class FlexboxLegacyContainerBuilder<T : Component.ContainerBuilder<T>>
protected constructor(
    private val context: ComponentContext,
    defStyleAttr: Int,
    defStyleRes: Int,
    private val scratch: SpecGeneratedComponent,
    private val customSimpleName: String?,
    private val buildKComponent: Boolean =
        ComponentsConfiguration.defaultInstance.useKComponentForLegacyRowColumn,
) : Component.ContainerBuilder<T>(context, defStyleAttr, defStyleRes, scratch) {

  protected val children: MutableList<Component> = ArrayList()

  // Container props flow into the KRow/KColumn constructor (flag ON) or onto the legacy scratch
  // Row/Column (flag OFF).
  protected var alignContentValue: YogaAlign? = null
  protected var alignItemsValue: YogaAlign? = null
  protected var justifyContentValue: YogaJustify? = null
  protected var wrapValue: YogaWrap? = null
  protected var reverseValue: Boolean = false
  private var gapValue: Dimen? = null
  protected var gapPxValue: Int? = null

  // Applied to the built KRow/KColumn (flag ON).
  private var keyValue: String? = null
  private var handleValue: Handle? = null
  private var kotlinStyleValue: Style = Style

  private inline fun self(block: () -> Unit): T {
    block()
    return getThis()
  }

  /**
   * Entry point for [Component.Builder.kotlinStyle]: applies [other] to the backing props. The
   * KRow/KColumn also keeps it as its [Style] so equivalence compares the items, such as binders,
   * that [CommonProps] equivalence ignores.
   */
  internal fun mergeKotlinStyle(other: Style) {
    other.applyCommonProps(context, scratch.getOrCreateCommonProps())
    if (buildKComponent) {
      kotlinStyleValue += other
    }
  }

  // region Container props ---------------------------------------------------

  override fun child(child: Component?): T = self { if (child != null) children.add(child) }

  override fun child(child: Component.Builder<*>?): T = self {
    if (child != null) children.add(child.build())
  }

  override fun children(children: List<Component>): T = self { this.children.addAll(children) }

  override fun alignContent(alignContent: YogaAlign?): T = self {
    alignContentValue = alignContent
  }

  override fun alignItems(alignItems: YogaAlign?): T = self { alignItemsValue = alignItems }

  override fun justifyContent(justifyContent: YogaJustify?): T = self {
    justifyContentValue = justifyContent
  }

  override fun wrap(wrap: YogaWrap?): T = self { wrapValue = wrap }

  override fun reverse(reverse: Boolean): T = self { reverseValue = reverse }

  override fun gapPx(gutter: YogaGutter, px: Int): T = self {
    require(gutter == YogaGutter.ALL) {
      "Only YogaGutter.ALL is supported on the legacy builder. Use the Kotlin DSL for per-axis gap."
    }
    gapValue = px.px
    gapPxValue = px
  }

  override fun gapDip(gutter: YogaGutter, dip: Float): T = self {
    require(gutter == YogaGutter.ALL) {
      "Only YogaGutter.ALL is supported on the legacy builder. Use the Kotlin DSL for per-axis gap."
    }
    gapValue = dip.dp
    gapPxValue = mResourceResolver.dipsToPixels(dip)
  }

  // endregion

  // region Identity ----------------------------------------------------------

  override fun key(key: String?): T =
      if (buildKComponent) self { keyValue = key ?: "null" } else super.key(key)

  override fun handle(handle: Handle?): T =
      if (buildKComponent) self { handleValue = handle } else super.handle(handle)

  // endregion

  override fun setComponent(component: Component) {
    // The backing component is fixed for the lifetime of this compatibility builder.
  }

  /** Applies the pending key/handle to the freshly-built [component]. */
  private fun finishBuild(component: Component): Component {
    keyValue?.let { setKeyForComponentInternal(component, it) }
    handleValue?.let { setHandleForComponentInternal(component, it) }
    return component
  }

  /** Whether [build] should return a KRow/KColumn instead of a legacy SpecGenerated Row/Column. */
  protected fun isKComponentEnabled(): Boolean = buildKComponent

  /** Flag-OFF path: finish the legacy component whose props were populated by the setters. */
  protected fun buildLegacy(): Component {
    val component = scratch
    applyContainerProps(component)
    return finishBuild(component)
  }

  /** Sets the container props ([alignContentValue] etc.) onto the concrete legacy [component]. */
  protected abstract fun applyContainerProps(component: SpecGeneratedComponent)

  protected fun buildKRow(): Component = finishBuild(
      KRow(
          alignContent = alignContentValue,
          alignItems = alignItemsValue,
          justifyContent = justifyContentValue,
          wrap = wrapValue,
          isReversed = reverseValue,
          gap = gapValue,
          children = children,
          style = kotlinStyleValue,
          customSimpleName = customSimpleName,
      )
          .also { it.builderCommonProps = scratch.commonProps },
  )

  protected fun buildKColumn(): Component = finishBuild(
      KColumn(
          alignContent = alignContentValue,
          alignItems = alignItemsValue,
          justifyContent = justifyContentValue,
          wrap = wrapValue,
          isReversed = reverseValue,
          gap = gapValue,
          children = children,
          style = kotlinStyleValue,
          customSimpleName = customSimpleName,
      )
          .also { it.builderCommonProps = scratch.commonProps },
  )
}
