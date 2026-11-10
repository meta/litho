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

package com.facebook.litho.accessibility

import android.view.View
import androidx.annotation.IntDef
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.AccessibilityRole.AccessibilityRoleType
import com.facebook.litho.CommonProps
import com.facebook.litho.ComponentContext
import com.facebook.litho.FocusOrderModel
import com.facebook.litho.OnInitializeAccessibilityEventEvent
import com.facebook.litho.OnInitializeAccessibilityNodeInfoEvent
import com.facebook.litho.OnPopulateAccessibilityEventEvent
import com.facebook.litho.OnPopulateAccessibilityNodeEvent
import com.facebook.litho.OnRequestSendAccessibilityEventEvent
import com.facebook.litho.PerformAccessibilityActionEvent
import com.facebook.litho.PerformActionForVirtualViewEvent
import com.facebook.litho.SendAccessibilityEventEvent
import com.facebook.litho.SendAccessibilityEventUncheckedEvent
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.litho.VirtualViewKeyboardFocusChangedEvent
import com.facebook.litho.annotations.ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_AUTO
import com.facebook.litho.annotations.ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_NO
import com.facebook.litho.annotations.ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
import com.facebook.litho.annotations.ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_YES
import com.facebook.litho.eventHandler
import com.facebook.litho.eventHandlerWithReturn

/** Enums for [AccessibilityStyleItem]. */
@PublishedApi
internal enum class AccessibilityField : StyleItemField {
  ACCESSIBILITY_HEADING,
  ACCESSIBILITY_ROLE,
  ACCESSIBILITY_ROLE_DESCRIPTION,
  CONTENT_DESCRIPTION,
  FOCUS_ORDER,
  IMPORTANT_FOR_ACCESSIBILITY,
  ON_INITIALIZE_ACCESSIBILITY_EVENT,
  ON_INITIALIZE_ACCESSIBILITY_NODE_INFO,
  ON_POPULATE_ACCESSIBILITY_EVENT,
  ON_POPULATE_ACCESSIBILITY_NODE,
  ON_REQUEST_SEND_ACCESSIBILITY_EVENT,
  PERFORM_ACCESSIBILITY_ACTION,
  SEND_ACCESSIBILITY_EVENT,
  SEND_ACCESSIBILITY_EVENT_UNCHECKED,
  ON_PERFORM_ACTION_FOR_VIRTUAL_VIEW,
  ON_VIRTUAL_VIEW_KEYBOARD_FOCUS_CHANGED,
  SCREEN_READER_FOCUSABLE,
  MIN_DURATION_BETWEEN_CONTENT_CHANGES,
  LABELED_BY,
  PANE_TITLE,
  LIVE_REGION,
  REQUEST_INITIAL_ACCESSIBILITY_FOCUS,
  STATE_DESCRIPTION,
  CONTAINER_TITLE,
}

@PublishedApi
@DataClassGenerate
internal data class AccessibilityStyleItem(
    override val field: AccessibilityField,
    override val value: Any?,
) : StyleItem<Any?> {
  @Suppress("UNCHECKED_CAST")
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      AccessibilityField.ACCESSIBILITY_HEADING -> commonProps.accessibilityHeading(value as Boolean)
      AccessibilityField.ACCESSIBILITY_ROLE -> commonProps.accessibilityRole(value as String)
      AccessibilityField.ACCESSIBILITY_ROLE_DESCRIPTION ->
          commonProps.accessibilityRoleDescription(value as CharSequence)
      AccessibilityField.CONTENT_DESCRIPTION ->
          commonProps.contentDescription(value as CharSequence)
      AccessibilityField.FOCUS_ORDER -> commonProps.focusOrder(value as FocusOrderModel)
      AccessibilityField.IMPORTANT_FOR_ACCESSIBILITY ->
          commonProps.importantForAccessibility(value as Int)
      AccessibilityField.ON_INITIALIZE_ACCESSIBILITY_EVENT ->
          commonProps.onInitializeAccessibilityEventHandler(
              eventHandler(value as (OnInitializeAccessibilityEventEvent) -> Unit, null, context),
          )
      AccessibilityField.ON_INITIALIZE_ACCESSIBILITY_NODE_INFO ->
          commonProps.onInitializeAccessibilityNodeInfoHandler(
              eventHandler(
                  value as (OnInitializeAccessibilityNodeInfoEvent) -> Unit,
                  null,
                  context,
              ),
          )
      AccessibilityField.ON_POPULATE_ACCESSIBILITY_EVENT ->
          commonProps.onPopulateAccessibilityEventHandler(
              eventHandler(value as (OnPopulateAccessibilityEventEvent) -> Unit, null, context),
          )
      AccessibilityField.ON_POPULATE_ACCESSIBILITY_NODE ->
          commonProps.onPopulateAccessibilityNodeHandler(
              eventHandler(value as (OnPopulateAccessibilityNodeEvent) -> Unit, null, context),
          )
      AccessibilityField.ON_REQUEST_SEND_ACCESSIBILITY_EVENT -> {
        // The value is always the non-null lambda passed to the Style function.
        @Suppress("KotlinNullableVariableCastToNonnullType", "UNCHECKED_CAST")
        val handler = value as (OnRequestSendAccessibilityEventEvent) -> Boolean
        commonProps.onRequestSendAccessibilityEventHandler(eventHandlerWithReturn(handler, context))
      }
      AccessibilityField.PERFORM_ACCESSIBILITY_ACTION -> {
        @Suppress("KotlinNullableVariableCastToNonnullType", "UNCHECKED_CAST")
        val handler = value as (PerformAccessibilityActionEvent) -> Boolean
        commonProps.performAccessibilityActionHandler(eventHandlerWithReturn(handler, context))
      }
      AccessibilityField.SEND_ACCESSIBILITY_EVENT ->
          commonProps.sendAccessibilityEventHandler(
              eventHandler(value as (SendAccessibilityEventEvent) -> Unit, null, context),
          )
      AccessibilityField.SEND_ACCESSIBILITY_EVENT_UNCHECKED ->
          commonProps.sendAccessibilityEventUncheckedHandler(
              eventHandler(value as (SendAccessibilityEventUncheckedEvent) -> Unit, null, context),
          )
      AccessibilityField.ON_PERFORM_ACTION_FOR_VIRTUAL_VIEW -> {
        @Suppress("KotlinNullableVariableCastToNonnullType", "UNCHECKED_CAST")
        val handler = value as (PerformActionForVirtualViewEvent) -> Boolean
        commonProps.onPerformActionForVirtualViewHandler(eventHandlerWithReturn(handler, context))
      }
      AccessibilityField.ON_VIRTUAL_VIEW_KEYBOARD_FOCUS_CHANGED ->
          commonProps.onVirtualViewKeyboardFocusChangedHandler(
              eventHandler(value as (VirtualViewKeyboardFocusChangedEvent) -> Unit, null, context),
          )
      AccessibilityField.SCREEN_READER_FOCUSABLE ->
          commonProps.screenReaderFocusable(value as Boolean)
      AccessibilityField.MIN_DURATION_BETWEEN_CONTENT_CHANGES ->
          commonProps.setMinDurationBetweenContentChangesMillis(value as Long)
      AccessibilityField.LABELED_BY -> commonProps.setLabeledBy(value)
      AccessibilityField.PANE_TITLE -> commonProps.setAccessibilityPaneTitle(value as CharSequence)
      AccessibilityField.LIVE_REGION -> commonProps.setLiveRegion(value as Int)
      AccessibilityField.REQUEST_INITIAL_ACCESSIBILITY_FOCUS ->
          commonProps.requestInitialAccessibilityFocus(value as Boolean)
      AccessibilityField.STATE_DESCRIPTION -> commonProps.stateDescription(value as CharSequence?)
      AccessibilityField.CONTAINER_TITLE -> commonProps.containerTitle(value as CharSequence?)
    }
  }
}

/**
 * Whether the view is a heading for a section of content for accessibility purposes.
 *
 * Note: Since this attribute is available only on API 19 and above, calling this method on lower
 * APIs will have no effect.
 *
 * See [android.view.View.setAccessibilityHeading].
 */
fun Style.accessibilityHeading(isAccessibilityHeading: Boolean): Style =
    this + AccessibilityStyleItem(AccessibilityField.ACCESSIBILITY_HEADING, isAccessibilityHeading)

/**
 * The Android Talkback "role" this component has. This will be read out when the view is visited in
 * Talkback mode. See [AccessibilityRoleType] for possible roles.
 */
fun Style.accessibilityRole(@AccessibilityRoleType accessibilityRole: String?): Style =
    this +
        accessibilityRole?.let { AccessibilityStyleItem(AccessibilityField.ACCESSIBILITY_ROLE, it) }

/**
 * The description for this Component's [accessibilityRole]. This will be read out when the view is
 * visited in Talkback mode.
 */
fun Style.accessibilityRoleDescription(accessibilityRoleDescription: CharSequence?): Style =
    this +
        accessibilityRoleDescription?.let {
          AccessibilityStyleItem(AccessibilityField.ACCESSIBILITY_ROLE_DESCRIPTION, it)
        }

/** Sets the state description of the component associated to this [Style]. */
fun Style.stateDescription(description: CharSequence?): Style =
    this + description?.let { AccessibilityStyleItem(AccessibilityField.STATE_DESCRIPTION, it) }

/**
 * A description of the contents of this Component for accessibility.
 *
 * See [android.view.View.setContentDescription].
 */
fun Style.contentDescription(contentDescription: CharSequence?): Style =
    this +
        contentDescription?.let {
          AccessibilityStyleItem(AccessibilityField.CONTENT_DESCRIPTION, it)
        }

fun Style.focusOrder(focusOrderModel: FocusOrderModel): Style =
    this + AccessibilityStyleItem(AccessibilityField.FOCUS_ORDER, focusOrderModel)

/**
 * Sets whether this Component is "important for accessibility". If it is, it fires accessibility
 * events and is reported to accessibility services that query the screen. The value for this
 * property can be one of the values in [ImportantForAccessibility].
 *
 * See [android.view.View.setImportantForAccessibility].
 */
fun Style.importantForAccessibility(importantForAccessibility: ImportantForAccessibility): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.IMPORTANT_FOR_ACCESSIBILITY,
            importantForAccessibility.asInt,
        )

/**
 * Initializes an [AccessibilityEvent] with information about the the host View which dispatched the
 * event.
 *
 * See [android.view.View.AccessibilityDelegateCompat#onInitializeAccessibilityEvent].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onInitializeAccessibilityEvent(
    noinline onInitializeAccessibilityEventHandler: (OnInitializeAccessibilityEventEvent) -> Unit,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.ON_INITIALIZE_ACCESSIBILITY_EVENT,
            onInitializeAccessibilityEventHandler,
        )

/**
 * Gives a chance to the host View to populate the accessibility event with its text content.
 *
 * See [android.view.View.AccessibilityDelegateCompat#onPopulateAccessibilityEvent].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onPopulateAccessibilityEvent(
    noinline onPopulateAccessibilityEventHandler: (OnPopulateAccessibilityEventEvent) -> Unit,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.ON_POPULATE_ACCESSIBILITY_EVENT,
            onPopulateAccessibilityEventHandler,
        )

/** Gives a chance to the component to implement its own accessibility support. */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onPopulateAccessibilityNode(
    noinline onPopulateAccessibilityNodeHandler: (OnPopulateAccessibilityNodeEvent) -> Unit,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.ON_POPULATE_ACCESSIBILITY_NODE,
            onPopulateAccessibilityNodeHandler,
        )

/**
 * Called when a child of the host View requests sending an [AccessibilityEvent]. Return `true` to
 * allow the event to propagate, or `false` to block it.
 *
 * See [android.view.View.AccessibilityDelegateCompat#onRequestSendAccessibilityEvent].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onRequestSendAccessibilityEventWithResult(
    noinline onRequestSendAccessibilityEventHandler:
        (OnRequestSendAccessibilityEventEvent) -> Boolean,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.ON_REQUEST_SEND_ACCESSIBILITY_EVENT,
            onRequestSendAccessibilityEventHandler,
        )

/**
 * Called when performing the specified accessibility action on the view. Return `true` when the
 * action was handled, or `false` otherwise.
 *
 * See [android.view.View.AccessibilityDelegateCompat#performAccessibilityAction].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onPerformAccessibilityActionWithResult(
    noinline onPerformAccessibilityActionHandler: (PerformAccessibilityActionEvent) -> Boolean,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.PERFORM_ACCESSIBILITY_ACTION,
            onPerformAccessibilityActionHandler,
        )

/**
 * Called when Sends an accessibility event of the given type. If accessibility is not enabled this
 * method has no effect.
 *
 * See [android.view.View.AccessibilityDelegateCompat#sendAccessibilityEvent].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onSendAccessibilityEvent(
    noinline onSendAccessibilityEventHandler: (SendAccessibilityEventEvent) -> Unit,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.SEND_ACCESSIBILITY_EVENT,
            onSendAccessibilityEventHandler,
        )

/**
 * Sends an accessibility event. This method behaves exactly as sendAccessibilityEvent() but takes
 * as an argument an empty [AccessibilityEvent] and does not perform a check whether accessibility
 * is enabled.
 *
 * See [android.view.View.AccessibilityDelegateCompat#sendAccessibilityEventUnchecked].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onSendAccessibilityEventUnchecked(
    noinline onSendAccessibilityEventUncheckedHandler:
        (SendAccessibilityEventUncheckedEvent) -> Unit,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.SEND_ACCESSIBILITY_EVENT_UNCHECKED,
            onSendAccessibilityEventUncheckedHandler,
        )

/**
 * Called when a virtual view child of the host View has changed keyboard focus and gives an
 * opportunity to the parent (the host) to react (changing visual display, etc.)
 *
 * See [androidx.customview.widget.ExploreByTouchHelper#onVirtualViewKeyboardFocusChanged].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onVirtualViewKeyboardFocusChanged(
    noinline onVirtualViewKeyboardFocusChangedHandler:
        (VirtualViewKeyboardFocusChangedEvent) -> Unit,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.ON_VIRTUAL_VIEW_KEYBOARD_FOCUS_CHANGED,
            onVirtualViewKeyboardFocusChangedHandler,
        )

/**
 * Performs the specified accessibility action on a virtual view child of the host View. Return
 * `true` when the action was handled, or `false` otherwise.
 *
 * See [androidx.customview.widget.ExploreByTouchHelper#onPerformActionForVirtualView].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onPerformActionForVirtualViewWithResult(
    noinline onPerformActionForVirtualViewHandler: (PerformActionForVirtualViewEvent) -> Boolean,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.ON_PERFORM_ACTION_FOR_VIRTUAL_VIEW,
            onPerformActionForVirtualViewHandler,
        )

/**
 * Initializes an [AccessibilityNodeInfoCompat] with information about the host view.
 *
 * See [android.view.View.AccessibilityDelegateCompat#onInitializeAccessibilityNodeInfo].
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onInitializeAccessibilityNodeInfo(
    noinline onInitializeAccessibilityNodeInfoHandler:
        (OnInitializeAccessibilityNodeInfoEvent) -> Unit,
): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.ON_INITIALIZE_ACCESSIBILITY_NODE_INFO,
            onInitializeAccessibilityNodeInfoHandler,
        )

/**
 * Sets whether this View should be a focusable element for screen readers
 *
 * See [android.view.View.setScreenReaderFocusable].
 */
fun Style.screenReaderFocusable(isFocusable: Boolean): Style =
    this + AccessibilityStyleItem(AccessibilityField.SCREEN_READER_FOCUSABLE, isFocusable)

/**
 * Sets the minimum time duration between two content change events, which is used in throttling
 * content change events in accessibility services.
 *
 * See
 * [androidx.core.view.accessibility.AccessibilityNodeInfoCompat.setMinDurationBetweenContentChangesMillis].
 */
fun Style.minDurationBetweenContentChangesMillis(duration: Long): Style =
    this + AccessibilityStyleItem(AccessibilityField.MIN_DURATION_BETWEEN_CONTENT_CHANGES, duration)

/**
 * Sets the view which serves as the label of the view represented by this info for accessibility
 * purposes.
 *
 * @param viewTag The view tag of the view which serves as the label of the view represented by this
 *
 * See [android.view.accessibility.AccessibilityNodeInfo.setLabeledBy].
 */
fun Style.labeledBy(viewTag: Any): Style =
    this + AccessibilityStyleItem(AccessibilityField.LABELED_BY, viewTag)

/**
 * Visually distinct portion of a window with window-like semantics are considered panes for
 * accessibility purposes. One example is the content view of a large fragment that is replaced. In
 * order for accessibility services to understand a pane's window-like behavior, panes should have
 * descriptive titles. Views with pane titles produce AccessibilityEvent#TYPE_WINDOW_STATE_CHANGEDs
 * when they appear, disappear, or change title.
 *
 * See [androidx.core.view.ViewCompat.setAccessibilityPaneTitle].
 */
fun Style.paneTitle(accessibilityPaneTitle: CharSequence?): Style =
    this + AccessibilityStyleItem(AccessibilityField.PANE_TITLE, accessibilityPaneTitle)

/**
 * Sets the live region mode for this view. This indicates to accessibility services whether they
 * should automatically notify the user about changes to the view's content description or text, or
 * to the content descriptions or text of the view's children (where applicable).
 *
 * See [android.view.View.setAccessibilityLiveRegion].
 */
fun Style.liveRegion(@AccessibilityLiveRegion mode: Int): Style =
    this + AccessibilityStyleItem(AccessibilityField.LIVE_REGION, mode)

/**
 * Sets whether the node has requested initial accessibility focus.
 *
 * See
 * [androidx.core.view.accessibility.AccessibilityNodeInfoCompat.setRequestInitialAccessibilityFocus].
 */
fun Style.requestInitialAccessibilityFocus(requestInitialAccessibilityFocus: Boolean): Style =
    this +
        AccessibilityStyleItem(
            AccessibilityField.REQUEST_INITIAL_ACCESSIBILITY_FOCUS,
            requestInitialAccessibilityFocus,
        )

/**
 * Sets the container title for app-developer-defined container which can be any type of ViewGroup
 * or layout. Container title will be used to group together related controls, similar to HTML
 * fieldset.
 *
 * See [android.view.accessibility.AccessibilityNodeInfo.setContainerTitle].
 */
fun Style.containerTitle(containerTitle: CharSequence?): Style =
    this + AccessibilityStyleItem(AccessibilityField.CONTAINER_TITLE, containerTitle)

/**
 * Enum values for [importantForAccessibility].
 *
 * Note: if you are looking for YES_HIDE_DESCENDANTS, it has been deprecated: prefer to add an
 * intermediate child with `NO_HIDE_DESCENDANTS` instead.
 */
enum class ImportantForAccessibility(val asInt: Int) {
  /** Automatically determine whether a view is important for accessibility. */
  AUTO(IMPORTANT_FOR_ACCESSIBILITY_AUTO),

  /** The view is important for accessibility. */
  YES(IMPORTANT_FOR_ACCESSIBILITY_YES),

  /** The view is not important for accessibility. */
  NO(IMPORTANT_FOR_ACCESSIBILITY_NO),

  /** The view is not important for accessibility, nor are any of its descendant views. */
  NO_HIDE_DESCENDANTS(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

  companion object {
    private val map = ImportantForAccessibility.values().associateBy { it.asInt }

    operator fun get(value: Int): ImportantForAccessibility = map[value] ?: AUTO
  }
}

@IntDef(
    View.ACCESSIBILITY_LIVE_REGION_NONE,
    View.ACCESSIBILITY_LIVE_REGION_POLITE,
    View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE,
)
@Retention(AnnotationRetention.SOURCE)
private annotation class AccessibilityLiveRegion
