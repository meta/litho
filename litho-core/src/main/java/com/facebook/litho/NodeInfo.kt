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

import android.graphics.Color
import android.util.SparseArray
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.ColorInt
import androidx.annotation.IntDef
import com.facebook.infer.annotation.ThreadConfined
import com.facebook.litho.AccessibilityRole.AccessibilityRoleType
import com.facebook.litho.CollectionsUtils.mergeSparseArrays
import com.facebook.litho.visibility.Visibility
import com.facebook.rendercore.Equivalence
import com.facebook.rendercore.utils.equals
import com.facebook.rendercore.utils.isEquivalentTo

/**
 * NodeInfo holds information that are set to the [LithoNode] and needs to be used while mounting.
 */
@ThreadConfined(ThreadConfined.ANY)
class NodeInfo : Equivalence<NodeInfo> {

  @IntDef(FOCUS_UNSET, FOCUS_SET_TRUE, FOCUS_SET_FALSE)
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class FocusState

  @IntDef(SCREEN_READER_FOCUS_UNSET, SCREEN_READER_FOCUS_SET_TRUE, SCREEN_READER_FOCUS_SET_FALSE)
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class ScreenReaderFocusState

  @IntDef(CLICKABLE_UNSET, CLICKABLE_SET_TRUE, CLICKABLE_SET_FALSE)
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class ClickableState

  @IntDef(ENABLED_UNSET, ENABLED_SET_TRUE, ENABLED_SET_FALSE)
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class EnabledState

  @IntDef(SELECTED_UNSET, SELECTED_SET_TRUE, SELECTED_SET_FALSE)
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class SelectedState

  @IntDef(
      REQUEST_INITIAL_ACCESSIBILITY_FOCUS_UNSET,
      REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_FALSE,
      REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_TRUE,
  )
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class InitialFocusState

  @IntDef(
      ACCESSIBILITY_HEADING_UNSET,
      ACCESSIBILITY_HEADING_SET_TRUE,
      ACCESSIBILITY_HEADING_SET_FALSE,
  )
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class AccessibilityHeadingState

  @IntDef(
      KEYBOARD_NAVIGATION_CLUSTER_UNSET,
      KEYBOARD_NAVIGATION_CLUSTER_SET_TRUE,
      KEYBOARD_NAVIGATION_CLUSTER_SET_FALSE,
  )
  @Retention(AnnotationRetention.SOURCE)
  internal annotation class KeyboardNavigationClusterState

  private var _transitionName: String? = null
  private var _visibility: Visibility? = null
  private var _tooltipText: String? = null
  private var _viewTag: Any? = null
  private var _outlineProvider: ViewOutlineProvider? = null
  private var _renderEffect: LithoRenderEffect? = null
  private var _clickHandler: EventHandler<ClickEvent>? = null
  private var _extraClickListeners: MutableList<com.facebook.litho.view.ClickListenerHolder>? = null
  private var _longClickHandler: EventHandler<LongClickEvent>? = null
  private var _focusChangeHandler: EventHandler<FocusChangedEvent>? = null
  private var _touchHandler: EventHandler<TouchEvent>? = null
  private var _interceptTouchHandler: EventHandler<InterceptTouchEvent>? = null
  private var _viewId: Int = View.NO_ID
  private var _shadowElevation: Float = 0f
  private var _ambientShadowColor: Int = Color.BLACK
  private var _spotShadowColor: Int = Color.BLACK
  private var _clipToOutline: Boolean = false
  private var _clipChildren: Boolean = true
  private var _defaultFocusHighlightEnabled: Boolean = true
  private var _scale: Float = 1f
  private var _alpha: Float = 1f
  private var _rotation: Float = 0f
  private var _rotationX: Float = 0f
  private var _rotationY: Float = 0f

  var transitionName: String?
    get() = _transitionName
    set(value) {
      _transitionName = value
    }

  private var _contentDescription: CharSequence? = null
  private var _stateDescription: CharSequence? = null
  private var _focusOrder: FocusOrderModel? = null
  @AccessibilityRoleType private var _accessibilityRole: String? = null
  private var _accessibilityRoleDescription: CharSequence? = null
  private var _containerTitle: CharSequence? = null
  private var _dispatchPopulateAccessibilityEventHandler:
      EventHandler<DispatchPopulateAccessibilityEventEvent>? =
      null
  private var _onInitializeAccessibilityEventHandler:
      EventHandler<OnInitializeAccessibilityEventEvent>? =
      null
  private var _onPopulateAccessibilityEventHandler:
      EventHandler<OnPopulateAccessibilityEventEvent>? =
      null
  private var _onPopulateAccessibilityNodeHandler: EventHandler<OnPopulateAccessibilityNodeEvent>? =
      null
  private var _onVirtualViewKeyboardFocusChangedHandler:
      EventHandler<VirtualViewKeyboardFocusChangedEvent>? =
      null
  private var _onPerformActionForVirtualViewHandler:
      EventHandler<PerformActionForVirtualViewEvent>? =
      null
  private var _onInitializeAccessibilityNodeInfoHandler:
      EventHandler<OnInitializeAccessibilityNodeInfoEvent>? =
      null
  private var _onRequestSendAccessibilityEventHandler:
      EventHandler<OnRequestSendAccessibilityEventEvent>? =
      null
  private var _performAccessibilityActionHandler: EventHandler<PerformAccessibilityActionEvent>? =
      null
  private var _sendAccessibilityEventHandler: EventHandler<SendAccessibilityEventEvent>? = null
  private var _sendAccessibilityEventUncheckedHandler:
      EventHandler<SendAccessibilityEventUncheckedEvent>? =
      null
  private var _minDurationBetweenContentChangesMillis: Long? = null
  private var _labeledBy: Any? = null
  private var _accessibilityPaneTitle: CharSequence? = null
  private var _liveRegionMode: Int? = null

  var visibility: Visibility?
    get() = _visibility
    internal set(value) {
      flags = flags or PFLAG_VISIBILITY_IS_SET
      _visibility = value
    }

  @FocusState
  var focusState: Int = FOCUS_UNSET
    private set

  @InitialFocusState
  var initialFocusState: Int = REQUEST_INITIAL_ACCESSIBILITY_FOCUS_UNSET
    private set

  @ScreenReaderFocusState
  var screenReaderFocusState: Int = SCREEN_READER_FOCUS_UNSET
    private set

  @ClickableState
  var clickableState: Int = CLICKABLE_UNSET
    private set

  @EnabledState
  var enabledState: Int = ENABLED_UNSET
    private set

  @SelectedState
  var selectedState: Int = SELECTED_UNSET
    private set

  @AccessibilityHeadingState
  var accessibilityHeadingState: Int = ACCESSIBILITY_HEADING_UNSET
    private set

  @KeyboardNavigationClusterState
  var keyboardNavigationClusterState: Int = KEYBOARD_NAVIGATION_CLUSTER_UNSET
    private set

  var flags: Long = 0
    private set

  var contentDescription: CharSequence?
    get() = _contentDescription
    set(contentDescription) {
      flags = flags or PFLAG_CONTENT_DESCRIPTION_IS_SET
      _contentDescription = contentDescription
    }

  var stateDescription: CharSequence?
    get() = _stateDescription
    set(stateDescription) {
      flags = flags or PFLAG_STATE_DESCRIPTION_IS_SET
      _stateDescription = stateDescription
    }

  var tooltipText: String?
    get() = _tooltipText
    set(tooltipText) {
      flags = flags or PFLAG_TOOLTIP_TEXT_IS_SET
      _tooltipText = tooltipText
    }

  fun hasViewId(): Boolean = flags and PFLAG_VIEW_ID_IS_SET != 0L

  var viewId: Int
    get() = _viewId
    set(id) {
      flags = flags or PFLAG_VIEW_ID_IS_SET
      _viewId = id
    }

  var viewTag: Any?
    get() = _viewTag
    set(viewTag) {
      flags = flags or PFLAG_VIEW_TAG_IS_SET
      _viewTag = viewTag
    }

  var shadowElevation: Float
    get() = _shadowElevation
    set(shadowElevation) {
      flags = flags or PFLAG_SHADOW_ELEVATION_IS_SET
      _shadowElevation = shadowElevation
    }

  @get:ColorInt
  var ambientShadowColor: Int
    get() = _ambientShadowColor
    set(color) {
      flags = flags or PFLAG_AMBIENT_SHADOW_COLOR_IS_SET
      _ambientShadowColor = color
    }

  @get:ColorInt
  var spotShadowColor: Int
    get() = _spotShadowColor
    set(color) {
      flags = flags or PFLAG_SPOT_SHADOW_COLOR_IS_SET
      _spotShadowColor = color
    }

  var outlineProvider: ViewOutlineProvider?
    get() = _outlineProvider
    set(outlineProvider) {
      flags = flags or PFLAG_OUTINE_PROVIDER_IS_SET
      _outlineProvider = outlineProvider
    }

  var renderEffect: LithoRenderEffect?
    get() = _renderEffect
    set(renderEffect) {
      flags = flags or PFLAG_RENDER_EFFECT_IS_SET
      _renderEffect = renderEffect
    }

  var clipToOutline: Boolean
    get() = _clipToOutline
    set(clipToOutline) {
      flags = flags or PFLAG_CLIP_TO_OUTLINE_IS_SET
      _clipToOutline = clipToOutline
    }

  var clipChildren: Boolean
    get() = _clipChildren
    set(clipChildren) {
      flags = flags or PFLAG_CLIP_CHILDREN_IS_SET
      _clipChildren = clipChildren
    }

  val isClipChildrenSet: Boolean
    get() = flags and PFLAG_CLIP_CHILDREN_IS_SET != 0L

  var defaultFocusHighlightEnabled: Boolean
    get() = _defaultFocusHighlightEnabled
    set(defaultFocusHighlightEnabled) {
      flags = flags or PFLAG_DEFAULT_FOCUS_HIGHLIGHT_ENABLED_IS_SET
      _defaultFocusHighlightEnabled = defaultFocusHighlightEnabled
    }

  val isDefaultFocusHighlightEnabledSet: Boolean
    get() = flags and PFLAG_DEFAULT_FOCUS_HIGHLIGHT_ENABLED_IS_SET != 0L

  var viewTags: SparseArray<Any>? = null
    private set

  var clickHandler: EventHandler<ClickEvent>?
    get() = _clickHandler
    set(clickHandler) {
      flags = flags or PFLAG_CLICK_HANDLER_IS_SET
      _clickHandler = clickHandler
      // Setting a NEW (non-null) primary clears any extra click listeners that were layered on
      // top of the previous primary — matches the "last `onClick` wins" semantics.
      //
      // A null assignment (e.g., disabled `Style.onClick(enabled = false, …)`) does NOT clear
      // extras — otherwise a chain like `clickListener(KEY) { … }.onClick(enabled = isOn) { … }`
      // would silently drop the telemetry listener when `isOn` is false.
      if (clickHandler != null) {
        _extraClickListeners = null
      }
    }

  internal val extraClickListeners: List<com.facebook.litho.view.ClickListenerHolder>?
    get() = _extraClickListeners

  internal fun addExtraClickListener(holder: com.facebook.litho.view.ClickListenerHolder) {
    val list =
        _extraClickListeners
            ?: ArrayList<com.facebook.litho.view.ClickListenerHolder>(2).also {
              _extraClickListeners = it
            }
    for (i in list.indices) {
      check(list[i].key != holder.key) {
        "Click listener for key 0x${Integer.toHexString(holder.key)} is already registered on this node."
      }
    }
    list.add(holder)
  }

  var longClickHandler: EventHandler<LongClickEvent>?
    get() = _longClickHandler
    set(longClickHandler) {
      flags = flags or PFLAG_LONG_CLICK_HANDLER_IS_SET
      _longClickHandler = longClickHandler
    }

  var focusChangeHandler: EventHandler<FocusChangedEvent>?
    get() = _focusChangeHandler
    set(focusChangedHandler) {
      flags = flags or PFLAG_FOCUS_CHANGE_HANDLER_IS_SET
      _focusChangeHandler = focusChangedHandler
    }

  fun hasFocusChangeHandler(): Boolean = _focusChangeHandler != null

  var touchHandler: EventHandler<TouchEvent>?
    get() = _touchHandler
    set(touchHandler) {
      flags = flags or PFLAG_TOUCH_HANDLER_IS_SET
      _touchHandler = touchHandler
    }

  var interceptTouchHandler: EventHandler<InterceptTouchEvent>?
    get() = _interceptTouchHandler
    set(interceptTouchHandler) {
      flags = flags or PFLAG_INTERCEPT_TOUCH_HANDLER_IS_SET
      _interceptTouchHandler = interceptTouchHandler
    }

  fun hasTouchEventHandlers(): Boolean =
      _clickHandler != null ||
          _longClickHandler != null ||
          _touchHandler != null ||
          _interceptTouchHandler != null

  var focusOrder: FocusOrderModel?
    get() = _focusOrder
    set(focusOrder) {
      flags = flags or PFLAG_FOCUS_ORDER_IS_SET
      _focusOrder = focusOrder
    }

  var containerTitle: CharSequence?
    get() = _containerTitle
    set(containerTitle) {
      flags = flags or PFLAG_CONTAINER_TITLE_IS_SET
      _containerTitle = containerTitle
    }

  fun addViewTag(id: Int, tag: Any) {
    flags = flags or PFLAG_VIEW_TAGS_IS_SET
    if (viewTags == null) {
      viewTags = SparseArray<Any>()
    }
    viewTags?.put(id, tag)
  }

  fun addViewTags(viewTags: SparseArray<Any>?) {
    flags = flags or PFLAG_VIEW_TAGS_IS_SET
    if (this.viewTags == null) {
      this.viewTags = viewTags
    } else {
      this.viewTags = mergeSparseArrays(this.viewTags, viewTags)
    }
  }

  @get:AccessibilityRoleType
  var accessibilityRole: String?
    get() = _accessibilityRole
    set(role) {
      flags = flags or PFLAG_ACCESSIBILITY_ROLE_IS_SET
      _accessibilityRole = role
    }

  var accessibilityRoleDescription: CharSequence?
    get() = _accessibilityRoleDescription
    set(roleDescription) {
      flags = flags or PFLAG_ACCESSIBILITY_ROLE_DESCRIPTION_IS_SET
      _accessibilityRoleDescription = roleDescription
    }

  var dispatchPopulateAccessibilityEventHandler:
      EventHandler<DispatchPopulateAccessibilityEventEvent>?
    get() = _dispatchPopulateAccessibilityEventHandler
    set(dispatchPopulateAccessibilityEventHandler) {
      flags = flags or PFLAG_DISPATCH_POPULATE_ACCESSIBILITY_EVENT_HANDLER_IS_SET
      _dispatchPopulateAccessibilityEventHandler = dispatchPopulateAccessibilityEventHandler
    }

  var onInitializeAccessibilityEventHandler: EventHandler<OnInitializeAccessibilityEventEvent>?
    get() = _onInitializeAccessibilityEventHandler
    set(onInitializeAccessibilityEventHandler) {
      flags = flags or PFLAG_ON_INITIALIZE_ACCESSIBILITY_EVENT_HANDLER_IS_SET
      _onInitializeAccessibilityEventHandler = onInitializeAccessibilityEventHandler
    }

  var onInitializeAccessibilityNodeInfoHandler:
      EventHandler<OnInitializeAccessibilityNodeInfoEvent>?
    get() = _onInitializeAccessibilityNodeInfoHandler
    set(onInitializeAccessibilityNodeInfoHandler) {
      flags = flags or PFLAG_ON_INITIALIZE_ACCESSIBILITY_NODE_INFO_HANDLER_IS_SET
      _onInitializeAccessibilityNodeInfoHandler = onInitializeAccessibilityNodeInfoHandler
    }

  var onPopulateAccessibilityEventHandler: EventHandler<OnPopulateAccessibilityEventEvent>?
    get() = _onPopulateAccessibilityEventHandler
    set(onPopulateAccessibilityEventHandler) {
      flags = flags or PFLAG_ON_POPULATE_ACCESSIBILITY_EVENT_HANDLER_IS_SET
      _onPopulateAccessibilityEventHandler = onPopulateAccessibilityEventHandler
    }

  var onPopulateAccessibilityNodeHandler: EventHandler<OnPopulateAccessibilityNodeEvent>?
    get() = _onPopulateAccessibilityNodeHandler
    set(onPopulateAccessibilityNodeHandler) {
      flags = flags or PFLAG_ON_POPULATE_ACCESSIBILITY_NODE_HANDLER_IS_SET
      _onPopulateAccessibilityNodeHandler = onPopulateAccessibilityNodeHandler
    }

  var onVirtualViewKeyboardFocusChangedHandler: EventHandler<VirtualViewKeyboardFocusChangedEvent>?
    get() = _onVirtualViewKeyboardFocusChangedHandler
    set(onVirtualViewKeyboardFocusChangedHandler) {
      flags = flags or PFLAG_ON_VIRTUAL_VIEW_KEYBOARD_FOCUS_CHANGED_HANDLER_IS_SET
      _onVirtualViewKeyboardFocusChangedHandler = onVirtualViewKeyboardFocusChangedHandler
    }

  var onPerformActionForVirtualViewHandler: EventHandler<PerformActionForVirtualViewEvent>?
    get() = _onPerformActionForVirtualViewHandler
    set(onPerformActionForVirtualViewHandler) {
      flags = flags or PFLAG_ON_PERFORM_ACTION_FOR_VIRTUAL_VIEW_HANDLER_IS_SET
      _onPerformActionForVirtualViewHandler = onPerformActionForVirtualViewHandler
    }

  var onRequestSendAccessibilityEventHandler: EventHandler<OnRequestSendAccessibilityEventEvent>?
    get() = _onRequestSendAccessibilityEventHandler
    set(onRequestSendAccessibilityEventHandler) {
      flags = flags or PFLAG_ON_REQUEST_SEND_ACCESSIBILITY_EVENT_HANDLER_IS_SET
      _onRequestSendAccessibilityEventHandler = onRequestSendAccessibilityEventHandler
    }

  var performAccessibilityActionHandler: EventHandler<PerformAccessibilityActionEvent>?
    get() = _performAccessibilityActionHandler
    set(performAccessibilityActionHandler) {
      flags = flags or PFLAG_PERFORM_ACCESSIBILITY_ACTION_HANDLER_IS_SET
      _performAccessibilityActionHandler = performAccessibilityActionHandler
    }

  var sendAccessibilityEventHandler: EventHandler<SendAccessibilityEventEvent>?
    get() = _sendAccessibilityEventHandler
    set(sendAccessibilityEventHandler) {
      flags = flags or PFLAG_SEND_ACCESSIBILITY_EVENT_HANDLER_IS_SET
      _sendAccessibilityEventHandler = sendAccessibilityEventHandler
    }

  var sendAccessibilityEventUncheckedHandler: EventHandler<SendAccessibilityEventUncheckedEvent>?
    get() = _sendAccessibilityEventUncheckedHandler
    set(sendAccessibilityEventUncheckedHandler) {
      flags = flags or PFLAG_SEND_ACCESSIBILITY_EVENT_UNCHECKED_HANDLER_IS_SET
      _sendAccessibilityEventUncheckedHandler = sendAccessibilityEventUncheckedHandler
    }

  var minDurationBetweenContentChangesMillis: Long?
    get() = _minDurationBetweenContentChangesMillis
    set(duration) {
      flags = flags or PFLAG_MIN_DURATION_BETWEEN_CHANGES_IS_SET
      _minDurationBetweenContentChangesMillis = duration
    }

  var accessibilityPaneTitle: CharSequence?
    get() = _accessibilityPaneTitle
    set(paneTitle) {
      flags = flags or PFLAG_PANE_TITLE_IS_SET
      _accessibilityPaneTitle = paneTitle
    }

  var liveRegionMode: Int?
    get() = _liveRegionMode
    set(mode) {
      flags = flags or PFLAG_LIVE_REGION_IS_SET
      _liveRegionMode = mode
    }

  var labeledBy: Any?
    get() = _labeledBy
    set(viewTag) {
      flags = flags or PFLAG_LABELED_BY_IS_SET
      _labeledBy = viewTag
    }

  fun needsAccessibilityDelegate(): Boolean =
      _onInitializeAccessibilityEventHandler != null ||
          _onInitializeAccessibilityNodeInfoHandler != null ||
          _onPopulateAccessibilityEventHandler != null ||
          _onPopulateAccessibilityNodeHandler != null ||
          _onVirtualViewKeyboardFocusChangedHandler != null ||
          _onPerformActionForVirtualViewHandler != null ||
          _onRequestSendAccessibilityEventHandler != null ||
          _performAccessibilityActionHandler != null ||
          _dispatchPopulateAccessibilityEventHandler != null ||
          _sendAccessibilityEventHandler != null ||
          _sendAccessibilityEventUncheckedHandler != null ||
          _accessibilityRole != null ||
          _accessibilityRoleDescription != null ||
          _focusOrder != null ||
          _labeledBy != null ||
          _minDurationBetweenContentChangesMillis != null ||
          _accessibilityPaneTitle != null ||
          _liveRegionMode != null ||
          // Only what a service would read: a heading set to false, or an empty string, is not.
          accessibilityHeadingState == ACCESSIBILITY_HEADING_SET_TRUE ||
          !_stateDescription.isNullOrEmpty() ||
          !_containerTitle.isNullOrEmpty() ||
          screenReaderFocusState != SCREEN_READER_FOCUS_UNSET ||
          initialFocusState != REQUEST_INITIAL_ACCESSIBILITY_FOCUS_UNSET

  fun setFocusable(isFocusable: Boolean) {
    focusState = if (isFocusable) FOCUS_SET_TRUE else FOCUS_SET_FALSE
  }

  fun setScreenReaderFocusable(isFocusable: Boolean) {
    screenReaderFocusState =
        if (isFocusable) SCREEN_READER_FOCUS_SET_TRUE else SCREEN_READER_FOCUS_SET_FALSE
  }

  fun setInitialFocusState(isFocus: Boolean) {
    initialFocusState =
        if (isFocus) REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_TRUE
        else REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_FALSE
  }

  fun setClickable(isClickable: Boolean) {
    clickableState = if (isClickable) CLICKABLE_SET_TRUE else CLICKABLE_SET_FALSE
  }

  fun setEnabled(isEnabled: Boolean) {
    enabledState = if (isEnabled) ENABLED_SET_TRUE else ENABLED_SET_FALSE
  }

  fun setSelected(isSelected: Boolean) {
    selectedState = if (isSelected) SELECTED_SET_TRUE else SELECTED_SET_FALSE
  }

  fun setAccessibilityHeading(isHeading: Boolean) {
    accessibilityHeadingState =
        if (isHeading) {
          ACCESSIBILITY_HEADING_SET_TRUE
        } else {
          ACCESSIBILITY_HEADING_SET_FALSE
        }
  }

  var scale: Float
    get() = _scale
    set(scale) {
      _scale = scale
      flags =
          if (scale == 1f) {
            flags and PFLAG_SCALE_IS_SET.inv()
          } else {
            flags or PFLAG_SCALE_IS_SET
          }
    }

  val isScaleSet: Boolean
    get() = flags and PFLAG_SCALE_IS_SET != 0L

  var alpha: Float
    get() = _alpha
    set(alpha) {
      _alpha = alpha
      flags =
          if (alpha == 1f) {
            flags and PFLAG_ALPHA_IS_SET.inv()
          } else {
            flags or PFLAG_ALPHA_IS_SET
          }
    }

  val isAlphaSet: Boolean
    get() = flags and PFLAG_ALPHA_IS_SET != 0L

  var rotation: Float
    get() = _rotation
    set(rotation) {
      _rotation = rotation
      flags =
          if (rotation == 0f) {
            flags and PFLAG_ROTATION_IS_SET.inv()
          } else {
            flags or PFLAG_ROTATION_IS_SET
          }
    }

  val isRotationSet: Boolean
    get() = flags and PFLAG_ROTATION_IS_SET != 0L

  var rotationX: Float
    get() = _rotationX
    set(rotationX) {
      _rotationX = rotationX
      flags = flags or PFLAG_ROTATION_X_IS_SET
    }

  val isRotationXSet: Boolean
    get() = flags and PFLAG_ROTATION_X_IS_SET != 0L

  var rotationY: Float
    get() = _rotationY
    set(rotationY) {
      _rotationY = rotationY
      flags = flags or PFLAG_ROTATION_Y_IS_SET
    }

  val isRotationYSet: Boolean
    get() = flags and PFLAG_ROTATION_Y_IS_SET != 0L

  fun setKeyboardNavigationCluster(isKeyboardNavigationCluster: Boolean) {
    keyboardNavigationClusterState =
        if (isKeyboardNavigationCluster) {
          KEYBOARD_NAVIGATION_CLUSTER_SET_TRUE
        } else {
          KEYBOARD_NAVIGATION_CLUSTER_SET_FALSE
        }
  }

  /**
   * Checks if this NodeInfo is equal to the {@param other}
   *
   * @param other the other NodeInfo
   * @return `true` iff this NodeInfo is equal to the {@param other}.
   */
  override fun isEquivalentTo(other: NodeInfo): Boolean {
    if (this === other) {
      return true
    }
    if (this == null || other == null) {
      return false
    }
    if (this.flags != other.flags) {
      return false
    }

    // Non-migrated comparisons
    if (!equals(this.accessibilityRole, other.accessibilityRole)) {
      return false
    }
    if (!equals(this.accessibilityRoleDescription, other.accessibilityRoleDescription)) {
      return false
    }
    if (!equals(this.labeledBy, other.labeledBy)) {
      return false
    }
    if (this.accessibilityHeadingState != other.accessibilityHeadingState) {
      return false
    }
    if (!equals(this.accessibilityPaneTitle, other.accessibilityPaneTitle)) {
      return false
    }
    if (!equals(this.liveRegionMode, other.liveRegionMode)) {
      return false
    }
    if (!equals(this.stateDescription, other.stateDescription)) {
      return false
    }
    if (!equals(this.contentDescription, other.contentDescription)) {
      return false
    }
    if (
        !isEquivalentTo(
            this.dispatchPopulateAccessibilityEventHandler,
            other.dispatchPopulateAccessibilityEventHandler,
        )
    ) {
      return false
    }
    if (this.enabledState != other.enabledState) {
      return false
    }
    if (this.focusState != other.focusState) {
      return false
    }
    if (this.screenReaderFocusState != other.screenReaderFocusState) {
      return false
    }
    if (this.initialFocusState != other.initialFocusState) {
      return false
    }
    if (
        !isEquivalentTo(
            this.onInitializeAccessibilityEventHandler,
            other.onInitializeAccessibilityEventHandler,
        )
    ) {
      return false
    }
    if (
        !isEquivalentTo(
            this.onInitializeAccessibilityNodeInfoHandler,
            other.onInitializeAccessibilityNodeInfoHandler,
        )
    ) {
      return false
    }
    if (
        !isEquivalentTo(
            this.onPopulateAccessibilityEventHandler,
            other.onPopulateAccessibilityEventHandler,
        )
    ) {
      return false
    }
    if (
        !isEquivalentTo(
            this.onPopulateAccessibilityNodeHandler,
            other.onPopulateAccessibilityNodeHandler,
        )
    ) {
      return false
    }
    if (
        !isEquivalentTo(
            this.onRequestSendAccessibilityEventHandler,
            other.onRequestSendAccessibilityEventHandler,
        )
    ) {
      return false
    }
    if (
        !isEquivalentTo(
            this.performAccessibilityActionHandler,
            other.performAccessibilityActionHandler,
        )
    ) {
      return false
    }
    if (this.selectedState != other.selectedState) {
      return false
    }
    if (this.keyboardNavigationClusterState != other.keyboardNavigationClusterState) {
      return false
    }
    if (!isEquivalentTo(this.sendAccessibilityEventHandler, other.sendAccessibilityEventHandler)) {
      return false
    }
    if (
        !isEquivalentTo(
            this.sendAccessibilityEventUncheckedHandler,
            other.sendAccessibilityEventUncheckedHandler,
        )
    ) {
      return false
    }
    if (
        !isEquivalentTo(
            this.onPerformActionForVirtualViewHandler,
            other.onPerformActionForVirtualViewHandler,
        )
    ) {
      return false
    }
    if (
        !isEquivalentTo(
            this.onVirtualViewKeyboardFocusChangedHandler,
            other.onVirtualViewKeyboardFocusChangedHandler,
        )
    ) {
      return false
    }
    if (!equals(this.visibility, other.visibility)) {
      return false
    }
    // By key: useFocusOrder builds new models on every render, with the same keys.
    if (!equals(this.focusOrder?.key, other.focusOrder?.key)) {
      return false
    }
    if (
        !equals(
            this.minDurationBetweenContentChangesMillis,
            other.minDurationBetweenContentChangesMillis,
        )
    ) {
      return false
    }
    if (!equals(this.containerTitle, other.containerTitle)) {
      return false
    }

    if (!equals(tooltipText, other.tooltipText)) return false
    if (!equals(outlineProvider, other.outlineProvider)) return false
    if (!equals(renderEffect, other.renderEffect)) return false
    if (rotation != other.rotation) return false
    if (rotationX != other.rotationX) return false
    if (rotationY != other.rotationY) return false
    if (scale != other.scale) return false
    if (shadowElevation != other.shadowElevation) return false
    if (ambientShadowColor != other.ambientShadowColor) return false
    if (spotShadowColor != other.spotShadowColor) return false
    if (viewId != other.viewId) return false
    if (!equals(viewTag, other.viewTag)) return false
    if (!equals(viewTags, other.viewTags)) return false

    if (!equals(transitionName, other.transitionName)) return false

    if (!isEquivalentTo(clickHandler, other.clickHandler)) return false
    if (!isEquivalentTo(longClickHandler, other.longClickHandler)) return false
    if (!isEquivalentTo(focusChangeHandler, other.focusChangeHandler)) return false
    if (!isEquivalentTo(touchHandler, other.touchHandler)) return false
    if (!isEquivalentTo(interceptTouchHandler, other.interceptTouchHandler)) return false
    // Additive listener identity matters for equivalence — two nodes with the same primary but
    // different additives are not equivalent.
    if (extraClickListeners != other.extraClickListeners) return false

    if (alpha != other.alpha) return false
    if (clipToOutline != other.clipToOutline) return false
    if (clipChildren != other.clipChildren) return false
    if (defaultFocusHighlightEnabled != other.defaultFocusHighlightEnabled) return false

    return true
  }

  fun copyInto(target: NodeInfo) {
    // Non-migrated properties (inline, flag-guarded)
    if (flags and PFLAG_ACCESSIBILITY_ROLE_IS_SET != 0L) {
      target.accessibilityRole = _accessibilityRole
    }
    if (flags and PFLAG_ACCESSIBILITY_ROLE_DESCRIPTION_IS_SET != 0L) {
      target.accessibilityRoleDescription = _accessibilityRoleDescription
    }
    if (flags and PFLAG_DISPATCH_POPULATE_ACCESSIBILITY_EVENT_HANDLER_IS_SET != 0L) {
      target.dispatchPopulateAccessibilityEventHandler = _dispatchPopulateAccessibilityEventHandler
    }
    if (flags and PFLAG_ON_INITIALIZE_ACCESSIBILITY_EVENT_HANDLER_IS_SET != 0L) {
      target.onInitializeAccessibilityEventHandler = _onInitializeAccessibilityEventHandler
    }
    if (flags and PFLAG_ON_INITIALIZE_ACCESSIBILITY_NODE_INFO_HANDLER_IS_SET != 0L) {
      target.onInitializeAccessibilityNodeInfoHandler = _onInitializeAccessibilityNodeInfoHandler
    }
    if (flags and PFLAG_ON_POPULATE_ACCESSIBILITY_EVENT_HANDLER_IS_SET != 0L) {
      target.onPopulateAccessibilityEventHandler = _onPopulateAccessibilityEventHandler
    }
    if (flags and PFLAG_ON_POPULATE_ACCESSIBILITY_NODE_HANDLER_IS_SET != 0L) {
      target.onPopulateAccessibilityNodeHandler = _onPopulateAccessibilityNodeHandler
    }
    if (flags and PFLAG_ON_VIRTUAL_VIEW_KEYBOARD_FOCUS_CHANGED_HANDLER_IS_SET != 0L) {
      target.onVirtualViewKeyboardFocusChangedHandler = _onVirtualViewKeyboardFocusChangedHandler
    }
    if (flags and PFLAG_ON_PERFORM_ACTION_FOR_VIRTUAL_VIEW_HANDLER_IS_SET != 0L) {
      target.onPerformActionForVirtualViewHandler = _onPerformActionForVirtualViewHandler
    }
    if (flags and PFLAG_ON_REQUEST_SEND_ACCESSIBILITY_EVENT_HANDLER_IS_SET != 0L) {
      target.onRequestSendAccessibilityEventHandler = _onRequestSendAccessibilityEventHandler
    }
    if (flags and PFLAG_PERFORM_ACCESSIBILITY_ACTION_HANDLER_IS_SET != 0L) {
      target.performAccessibilityActionHandler = _performAccessibilityActionHandler
    }
    if (flags and PFLAG_SEND_ACCESSIBILITY_EVENT_HANDLER_IS_SET != 0L) {
      target.sendAccessibilityEventHandler = _sendAccessibilityEventHandler
    }
    if (flags and PFLAG_SEND_ACCESSIBILITY_EVENT_UNCHECKED_HANDLER_IS_SET != 0L) {
      target.sendAccessibilityEventUncheckedHandler = _sendAccessibilityEventUncheckedHandler
    }
    if (flags and PFLAG_PANE_TITLE_IS_SET != 0L) {
      target.accessibilityPaneTitle = _accessibilityPaneTitle
    }
    if (flags and PFLAG_LIVE_REGION_IS_SET != 0L) {
      target.liveRegionMode = _liveRegionMode
    }
    if (flags and PFLAG_STATE_DESCRIPTION_IS_SET != 0L) {
      target.stateDescription = _stateDescription
    }
    if (flags and PFLAG_CONTENT_DESCRIPTION_IS_SET != 0L) {
      target.contentDescription = _contentDescription
    }
    if (flags and PFLAG_FOCUS_ORDER_IS_SET != 0L) {
      target.focusOrder = _focusOrder
    }
    if (focusState != FOCUS_UNSET) {
      target.setFocusable(focusState == FOCUS_SET_TRUE)
    }
    if (screenReaderFocusState != SCREEN_READER_FOCUS_UNSET) {
      target.setScreenReaderFocusable(screenReaderFocusState == SCREEN_READER_FOCUS_SET_TRUE)
    }
    if (initialFocusState != REQUEST_INITIAL_ACCESSIBILITY_FOCUS_UNSET) {
      target.setInitialFocusState(initialFocusState == REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_TRUE)
    }
    if (clickableState != CLICKABLE_UNSET) {
      target.setClickable(clickableState == CLICKABLE_SET_TRUE)
    }
    if (enabledState != ENABLED_UNSET) {
      target.setEnabled(enabledState == ENABLED_SET_TRUE)
    }
    if (selectedState != SELECTED_UNSET) {
      target.setSelected(selectedState == SELECTED_SET_TRUE)
    }
    if (accessibilityHeadingState != ACCESSIBILITY_HEADING_UNSET) {
      target.setAccessibilityHeading(accessibilityHeadingState == ACCESSIBILITY_HEADING_SET_TRUE)
    }
    if (keyboardNavigationClusterState != KEYBOARD_NAVIGATION_CLUSTER_UNSET) {
      target.setKeyboardNavigationCluster(
          keyboardNavigationClusterState == KEYBOARD_NAVIGATION_CLUSTER_SET_TRUE,
      )
    }
    if (flags and PFLAG_VISIBILITY_IS_SET != 0L) {
      target.visibility = visibility
    }
    if (flags and PFLAG_LABELED_BY_IS_SET != 0L) {
      target.labeledBy = labeledBy
    }
    if (flags and PFLAG_MIN_DURATION_BETWEEN_CHANGES_IS_SET != 0L) {
      target.minDurationBetweenContentChangesMillis = minDurationBetweenContentChangesMillis
    }
    if (flags and PFLAG_CONTAINER_TITLE_IS_SET != 0L) {
      target.containerTitle = _containerTitle
    }

    // Migrated properties (read from delegated getters, write through target's setters to update
    // target's flags)
    if (flags and PFLAG_CLICK_HANDLER_IS_SET != 0L) {
      target.clickHandler = clickHandler
    }
    // Copy additive click listeners AFTER the primary handler: assigning a non-null primary handler
    // clears any extras on the target, so copying them earlier would be wiped out. Without this, a
    // Wrapper's Style.additionalOnClick is silently dropped when merged into a delegate that
    // already
    // owns a NodeInfo with a click handler.
    //
    // De-dupe by key: this is a merge, so the target may already own an extra listener for a key
    // that the source also registers (e.g. a delegate and its Wrapper both use the same
    // additional-click key). Routing every source listener through addExtraClickListener would trip
    // its duplicate-key check and crash the layout, so skip keys the target already has.
    extraClickListeners?.forEach { holder ->
      if (target.extraClickListeners?.any { it.key == holder.key } != true) {
        target.addExtraClickListener(holder)
      }
    }
    if (flags and PFLAG_LONG_CLICK_HANDLER_IS_SET != 0L) {
      target.longClickHandler = longClickHandler
    }
    if (flags and PFLAG_FOCUS_CHANGE_HANDLER_IS_SET != 0L) {
      target.focusChangeHandler = focusChangeHandler
    }
    if (flags and PFLAG_TOUCH_HANDLER_IS_SET != 0L) {
      target.touchHandler = touchHandler
    }
    if (flags and PFLAG_INTERCEPT_TOUCH_HANDLER_IS_SET != 0L) {
      target.interceptTouchHandler = interceptTouchHandler
    }
    if (flags and PFLAG_TOOLTIP_TEXT_IS_SET != 0L) {
      target.tooltipText = tooltipText
    }
    if (flags and PFLAG_SHADOW_ELEVATION_IS_SET != 0L) {
      target.shadowElevation = shadowElevation
    }
    if (flags and PFLAG_AMBIENT_SHADOW_COLOR_IS_SET != 0L) {
      target.ambientShadowColor = ambientShadowColor
    }
    if (flags and PFLAG_SPOT_SHADOW_COLOR_IS_SET != 0L) {
      target.spotShadowColor = spotShadowColor
    }
    if (flags and PFLAG_OUTINE_PROVIDER_IS_SET != 0L) {
      target.outlineProvider = outlineProvider
    }
    if (flags and PFLAG_RENDER_EFFECT_IS_SET != 0L) {
      target.renderEffect = renderEffect
    }
    if (flags and PFLAG_CLIP_TO_OUTLINE_IS_SET != 0L) {
      target.clipToOutline = clipToOutline
    }
    if (flags and PFLAG_CLIP_CHILDREN_IS_SET != 0L) {
      target.clipChildren = clipChildren
    }
    if (flags and PFLAG_DEFAULT_FOCUS_HIGHLIGHT_ENABLED_IS_SET != 0L) {
      target.defaultFocusHighlightEnabled = defaultFocusHighlightEnabled
    }
    if (hasViewId()) {
      target.viewId = viewId
    }
    if (viewTag != null) {
      target.viewTag = viewTag
    }
    if (viewTags != null) {
      target.addViewTags(viewTags)
    }
    if (transitionName != null) {
      target.transitionName = transitionName
    }
    if (flags and PFLAG_SCALE_IS_SET != 0L) {
      target.scale = scale
    }
    if (flags and PFLAG_ALPHA_IS_SET != 0L) {
      target.alpha = alpha
    }
    if (flags and PFLAG_ROTATION_IS_SET != 0L) {
      target.rotation = rotation
    }
    if (flags and PFLAG_ROTATION_X_IS_SET != 0L) {
      target.rotationX = rotationX
    }
    if (flags and PFLAG_ROTATION_Y_IS_SET != 0L) {
      target.rotationY = rotationY
    }
  }

  fun copyInto(target: ViewAttributes) {
    // Non-migrated properties (inline)
    if (flags and PFLAG_CONTENT_DESCRIPTION_IS_SET != 0L) {
      target.contentDescription = _contentDescription
    }
    if (flags and PFLAG_PANE_TITLE_IS_SET != 0L) {
      target.accessibilityPaneTitle = _accessibilityPaneTitle
    }
    if (flags and PFLAG_LIVE_REGION_IS_SET != 0L) {
      target.liveRegionMode = _liveRegionMode
    }
    if (focusState != FOCUS_UNSET) {
      target.isFocusable = focusState == FOCUS_SET_TRUE
    }
    if (clickableState != CLICKABLE_UNSET) {
      target.isClickable = clickableState == CLICKABLE_SET_TRUE
    }
    if (enabledState != ENABLED_UNSET) {
      target.isEnabled = enabledState == ENABLED_SET_TRUE
    }
    if (selectedState != SELECTED_UNSET) {
      target.isSelected = selectedState == SELECTED_SET_TRUE
    }
    if (keyboardNavigationClusterState != KEYBOARD_NAVIGATION_CLUSTER_UNSET) {
      target.isKeyboardNavigationCluster =
          keyboardNavigationClusterState == KEYBOARD_NAVIGATION_CLUSTER_SET_TRUE
    }
    if (flags and PFLAG_VISIBILITY_IS_SET != 0L) {
      target.visibility = visibility
    }

    tooltipText?.let { target.tooltipText = it }
    if (hasViewId()) {
      target.viewId = viewId
    }
    viewTag?.let { target.viewTag = it }
    viewTags?.let { target.addViewTags(it) }
    transitionName?.let { target.transitionName = it }
    outlineProvider?.let { target.outlineProvider = it }
    renderEffect?.let { target.renderEffect = it }

    clickHandler?.let { target.clickHandler = it }
    longClickHandler?.let { target.longClickHandler = it }
    focusChangeHandler?.let { target.focusChangeHandler = it }
    touchHandler?.let { target.touchHandler = it }
    interceptTouchHandler?.let { target.interceptTouchHandler = it }
    // Copy after clickHandler so the clickHandler setter's "clear additives" side effect doesn't
    // wipe out the additives we just propagated.
    extraClickListeners?.let { target.extraClickListeners = ArrayList(it) }

    if (isScaleSet) target.scale = scale
    if (isAlphaSet) target.alpha = alpha
    if (isRotationSet) target.rotation = rotation
    if (isRotationXSet) target.rotationX = rotationX
    if (isRotationYSet) target.rotationY = rotationY
    if (isClipChildrenSet) target.clipChildren = clipChildren
    if (isDefaultFocusHighlightEnabledSet)
        target.defaultFocusHighlightEnabled = defaultFocusHighlightEnabled
    if (flags and PFLAG_CLIP_TO_OUTLINE_IS_SET != 0L) target.clipToOutline = clipToOutline
    if (flags and PFLAG_SHADOW_ELEVATION_IS_SET != 0L) target.shadowElevation = shadowElevation
    if (flags and PFLAG_AMBIENT_SHADOW_COLOR_IS_SET != 0L)
        target.ambientShadowColor = ambientShadowColor
    if (flags and PFLAG_SPOT_SHADOW_COLOR_IS_SET != 0L) target.spotShadowColor = spotShadowColor
  }

  companion object {
    const val FOCUS_UNSET: Int = 0
    const val FOCUS_SET_TRUE: Int = 1
    const val FOCUS_SET_FALSE: Int = 2
    const val SCREEN_READER_FOCUS_UNSET: Int = 0
    const val SCREEN_READER_FOCUS_SET_TRUE: Int = 1
    const val SCREEN_READER_FOCUS_SET_FALSE: Int = 2
    const val CLICKABLE_UNSET: Int = 0
    const val CLICKABLE_SET_TRUE: Int = 1
    const val CLICKABLE_SET_FALSE: Int = 2
    const val ENABLED_UNSET: Int = 0
    const val ENABLED_SET_TRUE: Int = 1
    const val ENABLED_SET_FALSE: Int = 2
    const val SELECTED_UNSET: Int = 0
    const val SELECTED_SET_TRUE: Int = 1
    const val SELECTED_SET_FALSE: Int = 2
    const val ACCESSIBILITY_HEADING_UNSET: Int = 0
    const val ACCESSIBILITY_HEADING_SET_TRUE: Int = 1
    const val ACCESSIBILITY_HEADING_SET_FALSE: Int = 2
    const val KEYBOARD_NAVIGATION_CLUSTER_UNSET: Int = 0
    const val KEYBOARD_NAVIGATION_CLUSTER_SET_TRUE: Int = 1
    const val KEYBOARD_NAVIGATION_CLUSTER_SET_FALSE: Int = 2
    const val REQUEST_INITIAL_ACCESSIBILITY_FOCUS_UNSET: Int = 0
    const val REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_TRUE: Int = 1
    const val REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_FALSE: Int = 2

    // When this flag is set, contentDescription was explicitly set on this node.
    private const val PFLAG_CONTENT_DESCRIPTION_IS_SET = 1L shl 0

    // When this flag is set, viewTag was explicitly set on this node.
    private const val PFLAG_VIEW_TAG_IS_SET = 1L shl 1

    // When this flag is set, viewTags was explicitly set on this node.
    private const val PFLAG_VIEW_TAGS_IS_SET = 1L shl 2

    // When this flag is set, clickHandler was explicitly set on this node.
    private const val PFLAG_CLICK_HANDLER_IS_SET = 1L shl 3

    // When this flag is set, longClickHandler was explicitly set on this node.
    private const val PFLAG_LONG_CLICK_HANDLER_IS_SET = 1L shl 4

    // When this flag is set, touchHandler was explicitly set on this node.
    private const val PFLAG_TOUCH_HANDLER_IS_SET = 1L shl 5

    // When this flag is set, dispatchPopulateAccessibilityEventHandler
    // was explicitly set on this node.
    private const val PFLAG_DISPATCH_POPULATE_ACCESSIBILITY_EVENT_HANDLER_IS_SET = 1L shl 6

    // When this flag is set, onInitializeAccessibilityEventHandler was explicitly set on this node.
    private const val PFLAG_ON_INITIALIZE_ACCESSIBILITY_EVENT_HANDLER_IS_SET = 1L shl 7

    // When this flag is set, onInitializeAccessibilityNodeInfo was explicitly set on this node.
    private const val PFLAG_ON_INITIALIZE_ACCESSIBILITY_NODE_INFO_HANDLER_IS_SET = 1L shl 8

    // When this flag is set, onPopulateAccessibilityEventHandler was explicitly set on this node
    private const val PFLAG_ON_POPULATE_ACCESSIBILITY_EVENT_HANDLER_IS_SET = 1L shl 9

    // When this flag is set, onRequestSendAccessibilityEventHandler was explicitly set on this
    // node.
    private const val PFLAG_ON_REQUEST_SEND_ACCESSIBILITY_EVENT_HANDLER_IS_SET = 1L shl 10

    // When this flag is set, performAccessibilityActionHandler was explicitly set on this node.
    private const val PFLAG_PERFORM_ACCESSIBILITY_ACTION_HANDLER_IS_SET = 1L shl 11

    // When this flag is set, sendAccessibilityEventHandler was explicitly set on this node.
    private const val PFLAG_SEND_ACCESSIBILITY_EVENT_HANDLER_IS_SET = 1L shl 12

    // When this flag is set, sendAccessibilityEventUncheckedHandler was explicitly set on this
    // node.
    private const val PFLAG_SEND_ACCESSIBILITY_EVENT_UNCHECKED_HANDLER_IS_SET = 1L shl 13

    // When this flag is set, shadowElevation was explicitly set on this node.
    private const val PFLAG_SHADOW_ELEVATION_IS_SET = 1L shl 14

    // When this flag is set, outlineProvider was explicitly set on this node.
    private const val PFLAG_OUTINE_PROVIDER_IS_SET = 1L shl 15

    // When this flag is set, clipToOutline was explicitly set on this node.
    private const val PFLAG_CLIP_TO_OUTLINE_IS_SET = 1L shl 16

    // When this flag is set, focusChangeHandler was explicitly set on this code.
    private const val PFLAG_FOCUS_CHANGE_HANDLER_IS_SET = 1L shl 17

    // When this flag is set, interceptTouchHandler was explicitly set on this node.
    private const val PFLAG_INTERCEPT_TOUCH_HANDLER_IS_SET = 1L shl 18
    private const val PFLAG_SCALE_IS_SET = 1L shl 19
    private const val PFLAG_ALPHA_IS_SET = 1L shl 20
    private const val PFLAG_ROTATION_IS_SET = 1L shl 21
    private const val PFLAG_ACCESSIBILITY_ROLE_IS_SET = 1L shl 22

    // When this flag is set, clipChildren was explicitly set on this node.
    private const val PFLAG_CLIP_CHILDREN_IS_SET = 1L shl 23
    private const val PFLAG_ACCESSIBILITY_ROLE_DESCRIPTION_IS_SET = 1L shl 24
    private const val PFLAG_ROTATION_X_IS_SET = 1L shl 25
    private const val PFLAG_ROTATION_Y_IS_SET = 1L shl 26
    private const val PFLAG_AMBIENT_SHADOW_COLOR_IS_SET = 1L shl 27
    private const val PFLAG_SPOT_SHADOW_COLOR_IS_SET = 1L shl 28

    // When this flag is set, onPopulateAccessibilityNodeHandler was explicitly set on this node
    private const val PFLAG_ON_POPULATE_ACCESSIBILITY_NODE_HANDLER_IS_SET = 1L shl 29

    // When this flag is set, view id was explicitly set on this node
    private const val PFLAG_VIEW_ID_IS_SET = 1L shl 30

    // When this flag is set, onVirtualViewKeyboardFocusChangedHandler was explicitly set on this
    // node
    private const val PFLAG_ON_VIRTUAL_VIEW_KEYBOARD_FOCUS_CHANGED_HANDLER_IS_SET = 1L shl 31

    // When this flag is set, onPerformActionForVirtualViewHandler was explicitly set on this node
    private const val PFLAG_ON_PERFORM_ACTION_FOR_VIRTUAL_VIEW_HANDLER_IS_SET = 1L shl 32

    // When this flag is set, tooltipText was explicitly set on this node.
    private const val PFLAG_TOOLTIP_TEXT_IS_SET = 1L shl 33

    private const val PFLAG_VISIBILITY_IS_SET = 1L shl 34

    private const val PFLAG_FOCUS_ORDER_IS_SET = 1L shl 35

    // When this flag is set, setMinDurationBetweenContentChangesMillis was explicitly set on this
    // node.
    private const val PFLAG_MIN_DURATION_BETWEEN_CHANGES_IS_SET = 1L shl 36

    // When this flag is set, setLabeledBy was explicitly set on this
    // node.
    private const val PFLAG_LABELED_BY_IS_SET = 1L shl 37

    // When this flag is set, paneTitle was explicitly set on this node.
    private const val PFLAG_PANE_TITLE_IS_SET = 1L shl 38

    // When this flag is set, liveRegion was explicitly set on this node.
    private const val PFLAG_LIVE_REGION_IS_SET = 1L shl 39

    // When this flag is set, the node has requested initial accessibility focus.
    private const val PFLAG_REQUEST_INITIAL_ACCESSIBILITY_FOCUS_IS_SET = 1L shl 40

    // When this flag is set, stateDescription was explicitly set on this node.
    private const val PFLAG_STATE_DESCRIPTION_IS_SET = 1L shl 41

    // When this flag is set, AccessibilityNodeInfo.mContainerTitle was explicitly set on this node.
    private const val PFLAG_CONTAINER_TITLE_IS_SET = 1L shl 42

    // When this flag is set, renderEffect was explicitly set on this node.
    private const val PFLAG_RENDER_EFFECT_IS_SET = 1L shl 43

    // When this flag is set, defaultFocusHighlightEnabled was explicitly set on this node.
    private const val PFLAG_DEFAULT_FOCUS_HIGHLIGHT_ENABLED_IS_SET = 1L shl 44
  }
}
