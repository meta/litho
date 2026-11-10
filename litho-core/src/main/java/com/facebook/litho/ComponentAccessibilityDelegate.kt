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

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import com.facebook.litho.LithoRenderUnit.Companion.getComponentContext
import com.facebook.litho.LithoRenderUnit.Companion.getRenderUnit
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.MountItem

private val invalidVirtualViewBounds: Rect = Rect(0, 0, 1, 1)

private fun populateInvalidVirtualViewNode(node: AccessibilityNodeInfoCompat) {
  node.contentDescription = ""
  node.setBoundsInParent(invalidVirtualViewBounds)
}

internal fun populateInvalidVirtualViewNodeIfEnabled(node: AccessibilityNodeInfoCompat) {
  if (ComponentsConfiguration.enableInvalidAccessibilityVirtualNodeFix) {
    populateInvalidVirtualViewNode(node)
  }
}

/**
 * Runs [block] (which is expected to call `super.onInitializeAccessibilityNodeInfo`) inside a
 * try-catch for [IndexOutOfBoundsException] so the rest of the caller's
 * `onInitializeAccessibilityNodeInfo` (Litho-specific accessibility property population) still runs
 * after the platform race.
 *
 * AOSP's `ViewGroup.onInitializeAccessibilityNodeInfoInternal` iterates over
 * `mAttachInfo.mTempArrayList`, a per-window-shared `ArrayList` populated by
 * `addChildrenForAccessibility(...)`. When concurrent or re-entrant accessibility traversal (e.g.
 * `AccessibilityInteractionController.prefetchSiblingsOfRealNode`, a `sendAccessibilityEvent`
 * firing during view recycling, or `UiAutomator` mid-mutation) mutates that shared list while the
 * loop is in flight, the captured size goes stale and `children.get(i)` throws
 * `IndexOutOfBoundsException`. The race is in the platform with no GK/config switch to disable it.
 *
 * Other exception types are intentionally allowed to propagate so unrelated bugs remain loggable.
 *
 * Modeled on the same defensive catch applied for the same crash signature in
 * `NotificationItemView.kt` (D105203673) and the long-standing retry in
 * `AccessibilityUtil.createNodeInfoFromView`.
 *
 * `internal` (not `private`) so the regression test in `ComponentAccessibilityDelegateTest` can
 * drive it directly without having to mock the platform's hidden
 * `View.onInitializeAccessibilityNodeInfoInternal`.
 */
internal inline fun runCatchingPrefetcherRace(block: () -> Unit) {
  try {
    block()
  } catch (e: IndexOutOfBoundsException) {
    // Platform race in ViewGroup.onInitializeAccessibilityNodeInfoInternal; continue so
    // Litho-specific accessibility properties still populate. The framework re-queries on the
    // next event, so the missing child enumeration is transient.
  }
}

/**
 * Class that is used to set up accessibility for [ComponentHost]s. Virtual nodes are only exposed
 * if the component implements support for extra accessibility nodes.
 */
internal class ComponentAccessibilityDelegate
@JvmOverloads
constructor(
    private val view: View,
    private var nodeInfo: NodeInfo? = null,
    originalFocus: Int,
    originalImportantForAccessibility: Int,
    private var wrappedDelegate: AccessibilityDelegateCompat? = null,
) : NullTolerantExploreByTouchHelper(view) {

  private val superDelegate: AccessibilityDelegateCompat = SuperDelegate()

  init {
    // We need to reset these two properties, as ExploreByTouchHelper sets focusable to "true" and
    // importantForAccessibility to "Yes" (if it is Auto). If we don't reset these it would force
    // every element that has this delegate attached to be focusable, and not allow for
    // announcement coalescing.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      view.focusable = originalFocus
    } else {
      // In APIs prior to 26, the View.FOCUSABLE int was private, so check against its value.
      view.isFocusable = originalFocus == VIEW_FOCUSABLE
    }
    ViewCompat.setImportantForAccessibility(view, originalImportantForAccessibility)
  }

  /**
   * [ComponentHost] contains the logic for setting the [NodeInfo] containing the [EventHandler]s
   * for its delegate instance whenever it is set/unset
   *
   * @see ComponentHost.setTag
   */
  fun setNodeInfo(nodeInfo: NodeInfo) {
    this.nodeInfo = nodeInfo
  }

  /**
   * Clears the [NodeInfo] reference from this delegate. This should be called when the view is
   * being recycled to prevent memory leaks. The [NodeInfo] contains references to event handlers
   * that may hold references to fragments or other UI components that should be garbage collected.
   */
  fun clearNodeInfo() {
    this.nodeInfo = null
  }

  fun clearWrappedDelegate() {
    wrappedDelegate = null
  }

  fun setWrappedDelegate(delegate: AccessibilityDelegateCompat?) {
    wrappedDelegate = delegate
  }

  private fun defaultDispatchPopulateAccessibilityEvent(
      host: View,
      event: AccessibilityEvent,
  ): Boolean =
      wrappedDelegate?.dispatchPopulateAccessibilityEvent(host, event)
          ?: super.dispatchPopulateAccessibilityEvent(host, event)

  private fun defaultOnInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
    wrappedDelegate?.onInitializeAccessibilityEvent(host, event)
        ?: super.onInitializeAccessibilityEvent(host, event)
  }

  private fun defaultOnInitializeAccessibilityNodeInfo(
      host: View,
      node: AccessibilityNodeInfoCompat,
  ) {
    wrappedDelegate?.onInitializeAccessibilityNodeInfo(host, node)
        ?: super.onInitializeAccessibilityNodeInfo(host, node)
  }

  private fun defaultOnPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
    wrappedDelegate?.onPopulateAccessibilityEvent(host, event)
        ?: super.onPopulateAccessibilityEvent(host, event)
  }

  private fun defaultOnRequestSendAccessibilityEvent(
      host: ViewGroup,
      child: View,
      event: AccessibilityEvent,
  ): Boolean =
      wrappedDelegate?.onRequestSendAccessibilityEvent(host, child, event)
          ?: superOnRequestSendAccessibilityEvent(host, child, event)

  private fun defaultPerformAccessibilityAction(
      host: View,
      action: Int,
      args: Bundle?,
  ): Boolean =
      wrappedDelegate?.performAccessibilityAction(host, action, args)
          ?: super.performAccessibilityAction(host, action, args)

  private fun defaultSendAccessibilityEvent(host: View, eventType: Int) {
    wrappedDelegate?.sendAccessibilityEvent(host, eventType)
        ?: super.sendAccessibilityEvent(host, eventType)
  }

  private fun defaultSendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
    wrappedDelegate?.sendAccessibilityEventUnchecked(host, event)
        ?: super.sendAccessibilityEventUnchecked(host, event)
  }

  @Suppress("SENSELESS_COMPARISON")
  override fun onInitializeAccessibilityNodeInfo(host: View, node: AccessibilityNodeInfoCompat) {
    val mountItem = getAccessibleMountItem(view)

    val initHandler = nodeInfo?.onInitializeAccessibilityNodeInfoHandler
    if (initHandler != null) {
      EventDispatcherUtils.dispatchOnInitializeAccessibilityNodeInfoEvent(
          initHandler,
          host,
          node,
          superDelegate,
      )
      dispatchOnPopulateAccessibilityNodeEvent(host, node)
    } else if (mountItem != null) {
      runCatchingPrefetcherRace { defaultOnInitializeAccessibilityNodeInfo(host, node) }
      // Coalesce the accessible mount item's information with the
      // the root host view's as they are meant to behave as a single
      // node in the accessibility framework.
      val component = getRenderUnit(mountItem).component
      val scopedContext = getComponentContext(mountItem.renderTreeNode)
      try {
        dispatchOnPopulateAccessibilityNodeEvent(host, node)
        if (component is SpecGeneratedComponent) {
          component.onPopulateAccessibilityNode(
              scopedContext,
              host,
              node,
              getInterStageProps(mountItem),
          )
        }
      } catch (e: Exception) {
        if (scopedContext != null) {
          ComponentUtils.handle(scopedContext, e)
        }
      }
    } else {
      runCatchingPrefetcherRace { defaultOnInitializeAccessibilityNodeInfo(host, node) }
      if (ComponentsConfiguration.enablePopulateAccessibilityNodeOnAllHosts) {
        dispatchOnPopulateAccessibilityNodeEvent(host, node)
      }
    }

    val nodeInfoForFlags = nodeInfo
    if (
        nodeInfoForFlags != null &&
            nodeInfoForFlags.screenReaderFocusState != NodeInfo.SCREEN_READER_FOCUS_UNSET
    ) {
      node.isScreenReaderFocusable =
          nodeInfoForFlags.screenReaderFocusState == NodeInfo.SCREEN_READER_FOCUS_SET_TRUE
    }

    if (
        nodeInfoForFlags != null &&
            nodeInfoForFlags.initialFocusState != NodeInfo.REQUEST_INITIAL_ACCESSIBILITY_FOCUS_UNSET
    ) {
      node.setRequestInitialAccessibilityFocus(
          nodeInfoForFlags.initialFocusState ==
              NodeInfo.REQUEST_INITIAL_ACCESSIBILITY_FOCUS_SET_TRUE,
      )
    }

    // If an accessibilityRole has been set, set the className here.  It's important that this
    // happens *after* any calls to super, since the super call will set a className of its own and
    // override this one.
    if (nodeInfoForFlags != null && nodeInfoForFlags.accessibilityRole != null) {
      node.className = nodeInfoForFlags.accessibilityRole
    }

    if (nodeInfoForFlags != null && nodeInfoForFlags.accessibilityRoleDescription != null) {
      node.roleDescription = nodeInfoForFlags.accessibilityRoleDescription

      // if no role was explicitly specified, set a role of "NONE".  This allows the role
      // description to still be announced without changing any other behavior.
      if (nodeInfoForFlags.accessibilityRole == null) {
        node.className = AccessibilityRole.NONE
      }
    }

    if (nodeInfoForFlags != null && nodeInfoForFlags.stateDescription != null) {
      node.stateDescription = nodeInfoForFlags.stateDescription
    }

    if (
        nodeInfoForFlags != null &&
            nodeInfoForFlags.accessibilityHeadingState != NodeInfo.ACCESSIBILITY_HEADING_UNSET
    ) {
      node.isHeading =
          nodeInfoForFlags.accessibilityHeadingState == NodeInfo.ACCESSIBILITY_HEADING_SET_TRUE
    }

    val minDuration = nodeInfoForFlags?.minDurationBetweenContentChangesMillis
    if (minDuration != null) {
      node.setMinDurationBetweenContentChangesMillis(minDuration)
    }

    // Both relations name another View of this tree, so they are looked up under the tree's root:
    // the mounting view this host belongs to. Found by walking up from the host, it needs no mount
    // item, so a card whose content is hosted by Views of its own, or a clickable host with no
    // content at all, gets its relations like any other host.
    val labeledBy = nodeInfoForFlags?.labeledBy
    val focusOrder = nodeInfoForFlags?.focusOrder
    val treeRoot = if (labeledBy != null || focusOrder?.next != null) mountingViewOf(view) else null
    if (labeledBy != null && treeRoot != null) {
      val labeledByView = treeRoot.findViewWithTag<View>(labeledBy)
      if (labeledByView != null) {
        node.setLabeledBy(labeledByView)
      }
    }

    if (focusOrder != null && focusOrder.next != null && treeRoot != null) {
      val nextView = getNextView(focusOrder, treeRoot)
      if (nextView != null) {
        node.setTraversalBefore(nextView)
      }
    }

    if (nodeInfoForFlags != null) {
      node.setContainerTitle(nodeInfoForFlags.containerTitle)
    }
  }

  private fun dispatchOnPopulateAccessibilityNodeEvent(
      host: View,
      node: AccessibilityNodeInfoCompat,
  ) {
    val handler = nodeInfo?.onPopulateAccessibilityNodeHandler
    if (handler != null) {
      EventDispatcherUtils.dispatchOnPopulateAccessibilityNode(handler, host, node)
    }
  }

  private fun dispatchOnVirtualViewKeyboardFocusChangedEvent(
      host: View,
      node: AccessibilityNodeInfoCompat?,
      virtualViewId: Int,
      hasFocus: Boolean,
  ) {
    val handler = nodeInfo?.onVirtualViewKeyboardFocusChangedHandler
    if (handler != null) {
      EventDispatcherUtils.dispatchVirtualViewKeyboardFocusChanged(
          handler,
          host,
          node,
          virtualViewId,
          hasFocus,
          superDelegate,
      )
    }
  }

  private fun dispatchOnPerformActionForVirtualViewEvent(
      host: View,
      node: AccessibilityNodeInfoCompat,
      virtualViewId: Int,
      action: Int,
      arguments: Bundle?,
  ): Boolean {
    val handler = nodeInfo?.onPerformActionForVirtualViewHandler
    if (handler != null) {
      return EventDispatcherUtils.dispatchPerformActionForVirtualView(
          handler,
          host,
          node,
          virtualViewId,
          action,
          arguments,
      )
    }
    return false
  }

  /**
   * Whether the accessible mount item currently exposes any virtual views.
   *
   * [ComponentHost.implementsVirtualViews] only records that the mounted component's *type* can
   * declare extra accessibility nodes, not that it currently has any: `Text` declares them for its
   * clickable spans and reports zero whenever the caller opts out of per-span nodes. Key dispatch
   * has to tell the two apart, because [ExploreByTouchHelper.dispatchKeyEvent] claims ENTER and
   * DPAD_CENTER unconditionally — delegating to it with no virtual views to move between swallows
   * those keys before the host's own `View.onKeyUp` can turn them into a click.
   */
  @Suppress("SENSELESS_COMPARISON")
  fun hasExtraAccessibilityNodes(): Boolean {
    val mountItem = getAccessibleMountItem(view) ?: return false
    val component = getRenderUnit(mountItem).component as? SpecGeneratedComponent ?: return false
    val scopedContext = getComponentContext(mountItem)
    return try {
      component.getExtraAccessibilityNodesCount(scopedContext, getInterStageProps(mountItem)) > 0
    } catch (e: Exception) {
      if (scopedContext != null) {
        ComponentUtils.handle(scopedContext, e)
      }
      false
    }
  }

  @Suppress("SENSELESS_COMPARISON")
  override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
    val mountItem = getAccessibleMountItem(view) ?: return

    val renderUnit = getRenderUnit(mountItem)
    if (renderUnit.component !is SpecGeneratedComponent) {
      return
    }
    @Suppress("USELESS_CAST") val component = renderUnit.component as SpecGeneratedComponent
    val scopedContext = getComponentContext(mountItem)

    try {
      val extraAccessibilityNodesCount =
          component.getExtraAccessibilityNodesCount(scopedContext, getInterStageProps(mountItem))

      // Expose extra accessibility nodes declared by the component to the
      // accessibility framework. The actual nodes will be populated in
      // {@link #onPopulateNodeForVirtualView}.
      for (i in 0 until extraAccessibilityNodesCount) {
        virtualViewIds.add(i)
      }
    } catch (e: Exception) {
      if (scopedContext != null) {
        ComponentUtils.handle(scopedContext, e)
      }
    }
  }

  @Suppress("SENSELESS_COMPARISON")
  override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
    val mountItem = getAccessibleMountItem(view)
    if (mountItem == null) {
      // ExploreByTouchHelper insists that we set something.
      populateInvalidVirtualViewNode(node)
      return
    }

    val drawable = mountItem.content as Drawable
    val bounds = drawable.bounds

    val renderUnit = getRenderUnit(mountItem)
    if (renderUnit.component !is SpecGeneratedComponent) {
      populateInvalidVirtualViewNodeIfEnabled(node)
      return
    }
    @Suppress("USELESS_CAST") val component = renderUnit.component as SpecGeneratedComponent
    val scopedContext = getComponentContext(mountItem)

    node.className = component.javaClass.name

    try {
      if (
          virtualViewId >=
              component.getExtraAccessibilityNodesCount(
                  scopedContext,
                  getInterStageProps(mountItem),
              )
      ) {
        // ExploreByTouchHelper insists that we set something.
        populateInvalidVirtualViewNode(node)
        return
      }

      component.onPopulateExtraAccessibilityNode(
          scopedContext,
          node,
          virtualViewId,
          bounds.left,
          bounds.top,
          getInterStageProps(mountItem),
      )
    } catch (e: Exception) {
      if (scopedContext != null) {
        ComponentUtils.handle(scopedContext, e)
      }
    }
  }

  /**
   * Finds extra accessibility nodes under the given event coordinates. Returns [INVALID_ID]
   * otherwise.
   */
  @Suppress("SENSELESS_COMPARISON")
  override fun getVirtualViewAt(x: Float, y: Float): Int {
    val mountItem = getAccessibleMountItem(view) ?: return INVALID_ID

    val renderUnit = getRenderUnit(mountItem)
    if (renderUnit.component !is SpecGeneratedComponent) {
      return INVALID_ID
    }
    @Suppress("USELESS_CAST") val component = renderUnit.component as SpecGeneratedComponent
    val scopedContext = getComponentContext(mountItem)

    try {
      if (
          component.getExtraAccessibilityNodesCount(scopedContext, getInterStageProps(mountItem)) ==
              0
      ) {
        return INVALID_ID
      }

      val drawable = mountItem.content as Drawable
      val bounds = drawable.bounds

      // Try to find an extra accessibility node that intersects with
      // the given coordinates.
      val virtualViewId =
          component.getExtraAccessibilityNodeAt(
              scopedContext,
              x.toInt() - bounds.left,
              y.toInt() - bounds.top,
              getInterStageProps(mountItem),
          )

      return if (virtualViewId >= 0) virtualViewId else INVALID_ID
    } catch (e: Exception) {
      if (scopedContext != null) {
        ComponentUtils.handle(scopedContext, e)
      }
      return INVALID_ID
    }
  }

  override fun onVirtualViewKeyboardFocusChanged(virtualViewId: Int, hasFocus: Boolean) {
    val nodeProvider = this.getAccessibilityNodeProvider(view) ?: return

    val node = nodeProvider.findFocus(AccessibilityNodeInfoCompat.FOCUS_INPUT)

    val mountItem = getAccessibleMountItem(view) ?: return

    val renderUnit = getRenderUnit(mountItem)
    if (renderUnit.component !is SpecGeneratedComponent) {
      dispatchOnVirtualViewKeyboardFocusChangedEvent(view, node, virtualViewId, hasFocus)
      return
    }
    @Suppress("USELESS_CAST") val component = renderUnit.component as SpecGeneratedComponent
    @Suppress("USELESS_ELVIS") val scopedContext = getComponentContext(mountItem) ?: return

    try {
      if (
          virtualViewId >=
              component.getExtraAccessibilityNodesCount(
                  scopedContext,
                  getInterStageProps(mountItem),
              )
      ) {
        return
      }

      if (component.implementsKeyboardFocusChangeForVirtualViews()) {
        component.onVirtualViewKeyboardFocusChanged(
            scopedContext,
            view,
            node,
            virtualViewId,
            hasFocus,
            getInterStageProps(mountItem),
        )
      }
    } catch (e: Exception) {
      ComponentUtils.handle(scopedContext, e)
    }
  }

  override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
    // ExploreByTouchHelper enforces subclasses to set a content description
    // or text on new events but components don't provide APIs to do so yet.
    event.contentDescription = ""
  }

  override fun onPerformActionForVirtualView(
      virtualViewId: Int,
      action: Int,
      arguments: Bundle?,
  ): Boolean {
    val nodeProvider = this.getAccessibilityNodeProvider(view) ?: return false

    val node = nodeProvider.findFocus(AccessibilityNodeInfoCompat.FOCUS_INPUT) ?: return false

    val mountItem = getAccessibleMountItem(view) ?: return false

    val renderUnit = getRenderUnit(mountItem)
    if (renderUnit.component !is SpecGeneratedComponent) {
      return dispatchOnPerformActionForVirtualViewEvent(
          view,
          node,
          virtualViewId,
          action,
          arguments,
      )
    }
    @Suppress("USELESS_CAST") val component = renderUnit.component as SpecGeneratedComponent
    @Suppress("USELESS_ELVIS") val scopedContext = getComponentContext(mountItem) ?: return false

    try {
      if (
          virtualViewId >=
              component.getExtraAccessibilityNodesCount(
                  scopedContext,
                  getInterStageProps(mountItem),
              )
      ) {
        return false
      }

      if (component.implementsOnPerformActionForVirtualView()) {
        return component.onPerformActionForVirtualView(
            scopedContext,
            view,
            node,
            virtualViewId,
            action,
            arguments,
            getInterStageProps(mountItem),
        )
      }
    } catch (e: Exception) {
      ComponentUtils.handle(scopedContext, e)
    }

    return false
  }

  /**
   * Returns a [AccessibilityNodeProviderCompat] if the host contains a component that implements
   * custom accessibility logic. Returns `NULL` otherwise. Components with accessibility content are
   * automatically wrapped in hosts by [LayoutState].
   */
  @Suppress("SENSELESS_COMPARISON")
  override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? {
    val mountItem = getAccessibleMountItem(view)
    if (mountItem != null && getRenderUnit(mountItem) != null) {
      val component = getRenderUnit(mountItem).component
      if (component is SpecGeneratedComponent && component.implementsExtraAccessibilityNodes()) {
        return super.getAccessibilityNodeProvider(host)
      }
    }

    return wrappedDelegate?.getAccessibilityNodeProvider(host)
  }

  override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
    val handler = nodeInfo?.onInitializeAccessibilityEventHandler
    if (handler != null) {
      EventDispatcherUtils.dispatchOnInitializeAccessibilityEvent(
          handler,
          host,
          event,
          superDelegate,
      )
    } else {
      defaultOnInitializeAccessibilityEvent(host, event)
    }
  }

  override fun sendAccessibilityEvent(host: View, eventType: Int) {
    val handler = nodeInfo?.sendAccessibilityEventHandler
    if (handler != null) {
      EventDispatcherUtils.dispatchSendAccessibilityEvent(
          handler,
          host,
          eventType,
          superDelegate,
      )
    } else {
      defaultSendAccessibilityEvent(host, eventType)
    }
  }

  override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
    val handler = nodeInfo?.sendAccessibilityEventUncheckedHandler
    if (handler != null) {
      EventDispatcherUtils.dispatchSendAccessibilityEventUnchecked(
          handler,
          host,
          event,
          superDelegate,
      )
    } else {
      defaultSendAccessibilityEventUnchecked(host, event)
    }
  }

  override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean {
    val handler = nodeInfo?.dispatchPopulateAccessibilityEventHandler
    if (handler != null) {
      return EventDispatcherUtils.dispatchDispatchPopulateAccessibilityEvent(
          handler,
          host,
          event,
          superDelegate,
      )
    }

    return defaultDispatchPopulateAccessibilityEvent(host, event)
  }

  override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
    val handler = nodeInfo?.onPopulateAccessibilityEventHandler
    if (handler != null) {
      EventDispatcherUtils.dispatchOnPopulateAccessibilityEvent(
          handler,
          host,
          event,
          superDelegate,
      )
    } else {
      defaultOnPopulateAccessibilityEvent(host, event)
    }
  }

  override fun onRequestSendAccessibilityEventSafe(
      host: ViewGroup,
      child: View,
      event: AccessibilityEvent,
  ): Boolean {
    val handler = nodeInfo?.onRequestSendAccessibilityEventHandler
    if (handler != null) {
      return EventDispatcherUtils.dispatchOnRequestSendAccessibilityEvent(
          handler,
          host,
          child,
          event,
          superDelegate,
      )
    }

    return defaultOnRequestSendAccessibilityEvent(host, child, event)
  }

  override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
    val handler = nodeInfo?.performAccessibilityActionHandler
    if (handler != null) {
      return EventDispatcherUtils.dispatchPerformAccessibilityActionEvent(
          handler,
          host,
          action,
          args,
          superDelegate,
      )
    }

    return defaultPerformAccessibilityAction(host, action, args)
  }

  private inner class SuperDelegate : AccessibilityDelegateCompat() {

    override fun dispatchPopulateAccessibilityEvent(
        host: View,
        event: AccessibilityEvent,
    ): Boolean {
      return defaultDispatchPopulateAccessibilityEvent(host, event)
    }

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
      defaultOnInitializeAccessibilityEvent(host, event)
    }

    override fun onInitializeAccessibilityNodeInfo(host: View, node: AccessibilityNodeInfoCompat) {
      defaultOnInitializeAccessibilityNodeInfo(host, node)
    }

    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
      defaultOnPopulateAccessibilityEvent(host, event)
    }

    override fun onRequestSendAccessibilityEvent(
        host: ViewGroup,
        child: View,
        event: AccessibilityEvent,
    ): Boolean {
      return defaultOnRequestSendAccessibilityEvent(host, child, event)
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
      return defaultPerformAccessibilityAction(host, action, args)
    }

    override fun sendAccessibilityEvent(host: View, eventType: Int) {
      defaultSendAccessibilityEvent(host, eventType)
    }

    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
      defaultSendAccessibilityEventUnchecked(host, event)
    }
  }

  companion object {
    // Maps to the private View.FOCUSABLE
    private const val VIEW_FOCUSABLE = 1

    @JvmStatic
    fun getNextView(focusOrderModel: FocusOrderModel, treeRoot: View): View? {
      var current: FocusOrderModel? = focusOrderModel
      while (current != null) {
        var nextView: View? = null
        val next = current.next
        if (next != null) {
          nextView = findViewWithTagValueUnder(treeRoot, R.id.component_focus_order, next.key)
        }
        if (nextView != null) {
          return nextView
        }
        current = current.next
      }
      return null
    }

    @JvmStatic
    fun getInterStageProps(item: MountItem): InterStagePropsContainer? {
      return LithoLayoutData.getInterStageProps(item.renderTreeNode.layoutData)
    }

    /**
     * The mounting view whose tree [view] belongs to: [view] itself, or its nearest such ancestor.
     */
    private fun mountingViewOf(view: View): View? {
      var current: Any? = view
      while (current != null) {
        if (current is BaseMountingView) {
          return current
        }
        current = (current as? View)?.parent
      }
      return null
    }

    @JvmStatic
    internal fun getAccessibleMountItem(view: View): MountItem? {
      if (view is ComponentHost) {
        return view.accessibleMountItem
      }
      // Looked up by content, not by child index: a host's mount items include its drawables and
      // are ordered by mount index, while its child Views are ordered by when they were added.
      val parentView = view.parent as? ComponentHost ?: return null
      for (i in 0 until parentView.mountItemCount) {
        val item = parentView.getMountItemAt(i)
        if (item.content === view) {
          return item
        }
      }
      return null
    }
  }
}
