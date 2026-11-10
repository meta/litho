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

package com.facebook.litho.visibility

import android.view.View
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.CommonProps
import com.facebook.litho.ComponentContext
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.binders.onBindViewWithDescription
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.eventHandler
import com.facebook.rendercore.visibility.FocusedVisibleEvent
import com.facebook.rendercore.visibility.FullImpressionVisibleEvent
import com.facebook.rendercore.visibility.InvisibleEvent
import com.facebook.rendercore.visibility.UnfocusedVisibleEvent
import com.facebook.rendercore.visibility.VisibilityChangedEvent
import com.facebook.rendercore.visibility.VisibleEvent

/**
 * The visibility of a Component, mirroring [View.VISIBLE] and [View.INVISIBLE].
 *
 * There is no equivalent of [View.GONE]: to remove a Component from the layout entirely, omit it
 * from its parent instead.
 */
@JvmInline
value class Visibility private constructor(val value: Int) {

  fun toViewVisibility(): Int {
    return when (this) {
      Visible -> View.VISIBLE
      Invisible -> View.INVISIBLE
      else -> throw IllegalArgumentException("Unknown visibility value: $this")
    }
  }

  companion object {
    val Visible: Visibility = Visibility(View.VISIBLE)
    val Invisible: Visibility = Visibility(View.INVISIBLE)
  }
}

/** Enums for [VisibilityStyleItem]. */
@PublishedApi
internal enum class VisibilityField : StyleItemField {
  ON_VISIBLE,
  ON_INVISIBLE,
  ON_FOCUSED,
  ON_UNFOCUSED,
  ON_FULL_IMPRESSION,
  ON_VISIBILITY_CHANGED,
  VISIBILITY,
}

/** Enums for [VisibilityFloatStyleItem]. */
@PublishedApi
internal enum class VisibilityFloatField : StyleItemField {
  VISIBLE_HEIGHT_RATIO,
  VISIBLE_WIDTH_RATIO,
}

@PublishedApi
@DataClassGenerate
internal data class VisibilityStyleItem(
    override val field: VisibilityField,
    override val value: Any?,
    val tag: (() -> String)? = null,
) : StyleItem<Any?> {
  @Suppress("UNCHECKED_CAST")
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    if (LithoDebugConfigurations.isVisibilityCallbackCommonPropTrackerEnabled) {
      val handlerField =
          when (field) {
            VisibilityField.ON_VISIBLE -> CommonProps.VISIBILITY_HANDLER_VISIBLE
            VisibilityField.ON_INVISIBLE -> CommonProps.VISIBILITY_HANDLER_INVISIBLE
            VisibilityField.ON_FOCUSED -> CommonProps.VISIBILITY_HANDLER_FOCUSED
            VisibilityField.ON_UNFOCUSED -> CommonProps.VISIBILITY_HANDLER_UNFOCUSED
            VisibilityField.ON_FULL_IMPRESSION -> CommonProps.VISIBILITY_HANDLER_FULL_IMPRESSION
            VisibilityField.ON_VISIBILITY_CHANGED ->
                CommonProps.VISIBILITY_HANDLER_VISIBILITY_CHANGED
            VisibilityField.VISIBILITY -> -1
          }
      if (handlerField != -1 && commonProps.isVisibilityHandlerSet(handlerField)) {
        DebugInfoReporter.report("MultipleVisibilityCallbacks") {
          this["source"] = "Style"
          this["component"] = context.componentScope?.simpleName
          this["field"] = field.name
        }
      }
    }
    when (field) {
      VisibilityField.ON_VISIBLE ->
          commonProps.visibleHandler(eventHandler(value as (VisibleEvent) -> Unit, tag, context))
      VisibilityField.ON_INVISIBLE ->
          commonProps.invisibleHandler(
              eventHandler(value as (InvisibleEvent) -> Unit, tag, context),
          )
      VisibilityField.ON_FOCUSED ->
          commonProps.focusedHandler(
              eventHandler(value as (FocusedVisibleEvent) -> Unit, tag, context),
          )
      VisibilityField.ON_UNFOCUSED ->
          commonProps.unfocusedHandler(
              eventHandler(value as (UnfocusedVisibleEvent) -> Unit, tag, context),
          )
      VisibilityField.ON_FULL_IMPRESSION ->
          commonProps.fullImpressionHandler(
              eventHandler(value as (FullImpressionVisibleEvent) -> Unit, tag, context),
          )
      VisibilityField.ON_VISIBILITY_CHANGED,
      ->
          commonProps.visibilityChangedHandler(
              eventHandler(value as (VisibilityChangedEvent) -> Unit, tag, context),
          )
      VisibilityField.VISIBILITY ->
          if (value is Visibility) {
            commonProps.setVisibility(value)
          }
    }
  }
}

@PublishedApi
@DataClassGenerate
internal data class VisibilityFloatStyleItem(
    override val field: VisibilityFloatField,
    override val value: Float,
) : StyleItem<Float> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      VisibilityFloatField.VISIBLE_HEIGHT_RATIO -> commonProps.visibleHeightRatio(value)
      VisibilityFloatField.VISIBLE_WIDTH_RATIO -> commonProps.visibleWidthRatio(value)
    }
  }
}

/**
 * Registers a callback to be called when a Component becomes visible on screen.
 *
 * @param visibleWidthRatio Ratio of the Component width that must be visible before the callback is
 *   triggered. Default is 0f (any part visible triggers callback).
 * @param visibleHeightRatio Ratio of the Component height that must be visible before the callback
 *   is triggered. Default is 0f (any part visible triggers callback).
 * @param callback Callback to be invoked when the visibility conditions are met.
 */
fun Style.onVisible(
    visibleWidthRatio: Float = 0f,
    visibleHeightRatio: Float = 0f,
    callback: (VisibleEvent) -> Unit,
): Style {
  var style: Style = this + VisibilityStyleItem(VisibilityField.ON_VISIBLE, callback)
  if (visibleWidthRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_WIDTH_RATIO, visibleWidthRatio)
  }
  if (visibleHeightRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_HEIGHT_RATIO, visibleHeightRatio)
  }
  return style
}

/**
 * Registers a callback to be called when a Component becomes visible on screen, with a tag for
 * identification.
 *
 * @param visibleWidthRatio Ratio of the Component width that must be visible before the callback is
 *   triggered. Default is 0f (any part visible triggers callback).
 * @param visibleHeightRatio Ratio of the Component height that must be visible before the callback
 *   is triggered. Default is 0f (any part visible triggers callback).
 * @param tag A tag supplier for identifying this visibility handler.
 * @param callback Callback to be invoked when the visibility conditions are met.
 */
@Deprecated("This will be removed soon")
fun Style.onVisibleWithTag(
    visibleWidthRatio: Float = 0f,
    visibleHeightRatio: Float = 0f,
    tag: (() -> String)?,
    callback: (VisibleEvent) -> Unit,
): Style {
  var style: Style = this + VisibilityStyleItem(VisibilityField.ON_VISIBLE, callback, tag)
  if (visibleWidthRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_WIDTH_RATIO, visibleWidthRatio)
  }
  if (visibleHeightRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_HEIGHT_RATIO, visibleHeightRatio)
  }
  return style
}

/**
 * Registers a callback to be called when a Component becomes fully invisible (e.g. scrolled off or
 * unmounted).
 *
 * @param visibleWidthRatio Ratio of the Component width for the invisibility callback to be
 *   dispatched. Default is 0f.
 * @param visibleHeightRatio Ratio of the Component height for the invisibility callback to be
 *   dispatched. Default is 0f.
 * @param callback Callback to be invoked when the component becomes invisible.
 */
fun Style.onInvisible(
    visibleWidthRatio: Float = 0f,
    visibleHeightRatio: Float = 0f,
    callback: (InvisibleEvent) -> Unit,
): Style {
  var style: Style = this + VisibilityStyleItem(VisibilityField.ON_INVISIBLE, callback)
  if (visibleWidthRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_WIDTH_RATIO, visibleWidthRatio)
  }
  if (visibleHeightRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_HEIGHT_RATIO, visibleHeightRatio)
  }
  return style
}

/**
 * Registers a callback to be called when either the Component occupies at least half of the
 * viewport, or, if the Component is smaller than half the viewport, when it is fully visible.
 *
 * @param visibleWidthRatio Ratio of the Component width for the focused callback to be dispatched.
 *   Default is 0f.
 * @param visibleHeightRatio Ratio of the Component height for the focused callback to be
 *   dispatched. Default is 0f.
 * @param callback Callback to be invoked when the component becomes focused visible.
 */
fun Style.onFocusedVisible(
    visibleWidthRatio: Float = 0f,
    visibleHeightRatio: Float = 0f,
    callback: (FocusedVisibleEvent) -> Unit,
): Style {
  var style: Style = this + VisibilityStyleItem(VisibilityField.ON_FOCUSED, callback)
  if (visibleWidthRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_WIDTH_RATIO, visibleWidthRatio)
  }
  if (visibleHeightRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_HEIGHT_RATIO, visibleHeightRatio)
  }
  return style
}

/**
 * Registers a callback to be called when the Component is no longer focused, i.e. it is not fully
 * visible and does not occupy at least half the viewport.
 *
 * @param visibleWidthRatio Ratio of the Component width for the unfocused callback to be
 *   dispatched. Default is 0f.
 * @param visibleHeightRatio Ratio of the Component height for the unfocused callback to be
 *   dispatched. Default is 0f.
 * @param callback Callback to be invoked when the component becomes unfocused.
 */
fun Style.onUnfocusedVisible(
    visibleWidthRatio: Float = 0f,
    visibleHeightRatio: Float = 0f,
    callback: (UnfocusedVisibleEvent) -> Unit,
): Style {
  var style: Style = this + VisibilityStyleItem(VisibilityField.ON_UNFOCUSED, callback)
  if (visibleWidthRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_WIDTH_RATIO, visibleWidthRatio)
  }
  if (visibleHeightRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_HEIGHT_RATIO, visibleHeightRatio)
  }
  return style
}

/**
 * Registers a callback to be called when all parts of a Component have been made visible at some
 * point, termed a "full impression". A full impression is defined as:
 * - if the Component is smaller than the viewport, when the entire Component is visible
 * - if the Component is bigger than the viewport, when all the edges have passed through the
 *   viewport once
 *
 * @param visibleWidthRatio Ratio of the Component width for the full impression callback to be
 *   dispatched. Default is 0f.
 * @param visibleHeightRatio Ratio of the Component height for the full impression callback to be
 *   dispatched. Default is 0f.
 * @param callback Callback to be invoked when the full impression conditions are met.
 */
fun Style.onFullImpression(
    visibleWidthRatio: Float = 0f,
    visibleHeightRatio: Float = 0f,
    callback: (FullImpressionVisibleEvent) -> Unit,
): Style {
  var style: Style = this + VisibilityStyleItem(VisibilityField.ON_FULL_IMPRESSION, callback)
  if (visibleWidthRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_WIDTH_RATIO, visibleWidthRatio)
  }
  if (visibleHeightRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_HEIGHT_RATIO, visibleHeightRatio)
  }
  return style
}

/**
 * Registers a callback to be called when the visible rect of a Component changes.
 *
 * @param visibleWidthRatio Ratio of the Component width for the visibility changed callback to be
 *   dispatched. Default is 0f.
 * @param visibleHeightRatio Ratio of the Component height for the visibility changed callback to be
 *   dispatched. Default is 0f.
 * @param callback Callback to be invoked when the visible rect changes.
 */
fun Style.onVisibilityChanged(
    visibleWidthRatio: Float = 0f,
    visibleHeightRatio: Float = 0f,
    callback: (VisibilityChangedEvent) -> Unit,
): Style {
  var style: Style = this + VisibilityStyleItem(VisibilityField.ON_VISIBILITY_CHANGED, callback)
  if (visibleWidthRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_WIDTH_RATIO, visibleWidthRatio)
  }
  if (visibleHeightRatio != 0f) {
    style =
        style +
            VisibilityFloatStyleItem(VisibilityFloatField.VISIBLE_HEIGHT_RATIO, visibleHeightRatio)
  }
  return style
}

/**
 * Defines the visibility of the Component. The value can be [Visibility.Visible] or
 * [Visibility.Invisible]; the default is [Visibility.Visible].
 *
 * [Visibility.Invisible] is the same as [View.INVISIBLE]: the Component is not drawn, but still
 * occupies space in the layout.
 *
 * **Note:** Using this API will not change how Visibility Events are dispatched; [onVisible] and/or
 * [onInvisible] will work as if this property was not in use.
 */
@ExperimentalLithoApi
fun Style.visibility(value: Visibility): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "visibility" }, value) { content ->
            val originalVisibility = content.visibility
            content.visibility = value.toViewVisibility()
            onUnbind { content.visibility = originalVisibility }
          }
    } else {
      this + VisibilityStyleItem(VisibilityField.VISIBILITY, value)
    }
