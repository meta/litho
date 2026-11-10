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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.SparseArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.FloatRange
import androidx.annotation.IdRes
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.collection.SparseArrayCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import com.facebook.litho.CollectionsUtils.mergeSparseArrays
import com.facebook.litho.ComponentHostUtils.asTextContent
import com.facebook.litho.ComponentHostUtils.extractContent
import com.facebook.litho.ComponentHostUtils.extractImageContent
import com.facebook.litho.ComponentHostUtils.maybeSetDrawableState
import com.facebook.litho.ComponentHostUtils.moveItem
import com.facebook.litho.ComponentHostUtils.removeItem
import com.facebook.litho.ComponentHostUtils.scrapItemAt
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.EventDispatcherUtils.dispatchOnInterceptTouch
import com.facebook.litho.LithoLayoutData.Companion.getExpandedTouchBounds
import com.facebook.litho.LithoLayoutData.Companion.getZIndex
import com.facebook.litho.LithoRenderUnit.Companion.getRenderUnit
import com.facebook.litho.LithoRenderUnit.Companion.isDuplicateChildrenStates
import com.facebook.litho.LithoRenderUnit.Companion.isDuplicateParentState
import com.facebook.litho.LithoRenderUnit.Companion.isTouchableDisabled
import com.facebook.litho.ThreadUtils.assertMainThread
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.Host
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.MountItem
import com.facebook.rendercore.MountItemVisitor
import com.facebook.rendercore.MountState
import com.facebook.rendercore.ResourceCache
import com.facebook.rendercore.ResourceResolver
import com.facebook.rendercore.debug.DebugEventAttribute.Name
import com.facebook.rendercore.debug.DebugEventDispatcher.dispatch
import com.facebook.rendercore.graphics.drawscope.DrawContext
import com.facebook.rendercore.transitions.DisappearingHost

/**
 * A [ViewGroup] that can host the mounted state of a [Component]. This is used by [MountState] to
 * wrap mounted drawables to handle click events and update drawable states accordingly.
 */
@DoNotStrip
open class ComponentHost(
    context: Context,
    attrs: AttributeSet?,
    private val unsafeModificationPolicy: UnsafeModificationPolicy?,
) : Host(context, attrs), DisappearingHost, SupportsPivotTransform {
  private val mountItems: SparseArrayCompat<MountItem> = SparseArrayCompat()
  private var scrapMountItemsArray: SparseArrayCompat<MountItem>? = null
  private val viewMountItems = SparseArrayCompat<MountItem>()
  private var scrapViewMountItemsArray: SparseArrayCompat<MountItem>? = null
  private val drawableMountItems = SparseArrayCompat<MountItem>()
  private var scrapDrawableMountItems: SparseArrayCompat<MountItem>? = null
  private var disappearingItems: ArrayList<MountItem?>? = null
  private var contentDescription: CharSequence? = null
  private var viewTags: SparseArray<Any>? = null
  private val dispatchDraw: InterleavedDispatchDraw = InterleavedDispatchDraw()
  private var viewDrawingOrder = emptyArray<DrawingOrder>()
  private var childDrawingOrder = IntArray(0)
  private var isChildDrawingOrderDirty = false
  private var inLayout = false
  /**
   * When a ViewGroup gets a child with duplicateParentState=true added to it, it forever sets a
   * flag (FLAG_NOTIFY_CHILDREN_ON_DRAWABLE_STATE_CHANGE) which makes the View crash if it ever has
   * addStatesFromChildren set to true. We track this so we know not to recycle ComponentHosts that
   * have had this flag set.
   */
  private var hadChildWithDuplicateParentState = false
  private var componentAccessibilityDelegate: ComponentAccessibilityDelegate? = null
  private var isComponentAccessibilityDelegateSet = false
  private var onLongClickListener: ComponentLongClickListener? = null
  private var onKeyListener: ((View, Int, KeyEvent) -> Boolean)? = null
  private var pivotXPercent = UNSET
  private var pivotYPercent = UNSET
  /**
   * Is `true` if and only if any accessible mounted child content has extra A11Y nodes. This is
   * `false` by default, and is set for every mount, unmount, and update call.
   */
  private var implementsVirtualViews = false
  /**
   * This flag is used to understand if a view property (e.g, click listener) was modified under the
   * context of a Litho operation or not. It is used to detect unsafe modifications and log them.
   *
   * @see {@link LithoViewAttributesExtension}
   */
  private var isSafeViewModificationsEnabled = false

  /**
   * Sets a focus change listener on this host.
   *
   * @return The previously set focus change listener
   */
  var componentFocusChangeListener: ComponentFocusChangeListener? = null
    set(value) {
      field = value
      withSafeModification { this.onFocusChangeListener = value }
    }

  /**
   * Sets a touch listener on this host.
   *
   * @return The previous set touch listener.
   */
  var componentTouchListener: ComponentTouchListener? = null
    set(value) {
      field = value
      withSafeModification { setOnTouchListener(value) }
    }

  /**
   * An [EventHandler] that will be invoked when [ComponentHost.onInterceptTouchEvent] is called.
   */
  var onInterceptTouchEventHandler: EventHandler<InterceptTouchEvent>? = null

  var touchExpansionDelegate: TouchExpansionDelegate? = null
    private set

  var drawBehind: ((Canvas) -> Unit)? = null

  val drawContext: DrawContext by
      lazy(LazyThreadSafetyMode.NONE) {
        DrawContext(
            ResourceResolver(context, ResourceCache.getLatest(context.resources.configuration)),
        )
      }
  /**
   * Hosts are guaranteed to have only one accessible component in them due to the way the view
   * hierarchy is constructed in [LayoutState]. There might be other non-accessible components in
   * the same hosts such as a background/foreground component though. This is why this method
   * iterates over all mount items in order to find the accessible one.
   */
  val accessibleMountItem: MountItem?
    get() {
      for (i in 0 until mountItemCount) {
        val item = getMountItemAt(i)
        // For inexplicable reason, item is null sometimes.
        if (getRenderUnit(item).isAccessible) {
          return item
        }
      }
      return null
    }

  val hasOnlyOneAccessibleItem: Boolean
    get() {
      var count = 0
      for (i in 0 until mountItemCount) {
        val item = getMountItemAt(i)
        // For inexplicable reason, item is null sometimes.
        if (getRenderUnit(item).isAccessible) {
          count = count + 1
        }
        if (count > 1) {
          return false
        }
      }
      return true
    }

  /** @return list of drawables that are mounted on this host. */
  val drawables: List<Drawable>
    get() {
      val size = drawableMountItems.size()
      if (size == 0) {
        return mutableListOf()
      }
      val drawables: MutableList<Drawable> = ArrayList(size)
      for (i in 0 until size) {
        val drawable = drawableMountItems.valueAt(i).content as Drawable
        drawables.add(drawable)
      }
      return drawables
    }

  /** @return list of names of content mounted on this host. */
  val contentNames: List<String>
    get() {
      val contentSize = mountItems.size()
      if (contentSize == 0) {
        return mutableListOf()
      }
      val contentNames: MutableList<String> = ArrayList(contentSize)
      for (i in 0 until contentSize) {
        contentNames.add(getMountItemName(getMountItemAt(i)))
      }
      return contentNames
    }

  /** @return the text content that is mounted on this host. */
  @get:DoNotStrip
  val textContent: List<TextContent>
    get() {
      val result = ArrayList<TextContent>(mountItems.size())
      for (i in 0 until mountItems.size()) {
        val item = mountItems.valueAt(i)
        asTextContent(item.content, item.renderTreeNode.renderUnit as? LithoRenderUnit)
            ?.let(result::add)
      }
      return result
    }

  /**
   * This is a helper method to get all the text (as [CharSequence]) that is contained inside this
   * [ComponentHost].
   *
   * We should be able to remove this method once the Kotlin migration is finished and doing this
   * kind of filtering option is easier.
   *
   * The correct behavior of this method relies on the correct implementation of [TextContent] in
   * Mountables.
   */
  val textContentText: List<CharSequence>
    get() = textContent.flatMap { it.textList }

  /** @return the image content that is mounted on this host. */
  val imageContent: ImageContent
    get() = extractImageContent(extractContent(mountItems))

  /**
   * Returns the Drawables associated with this ComponentHost for animations, for example the
   * background Drawable and/or the drawable that otherwise has a transitionKey on it that has
   * caused it to be hosted in this ComponentHost.
   *
   * The core purpose of exposing these drawables is so that when animating the bounds of this
   * ComponentHost, we also properly animate the bounds of its contained Drawables at the same time.
   */
  val linkedDrawablesForAnimation: List<Drawable>?
    get() {
      var drawables: MutableList<Drawable>? = null
      val size = drawableMountItems.size()
      for (i in 0 until size) {
        val mountItem = drawableMountItems.valueAt(i)
        if (getRenderUnit(mountItem).flags and LithoRenderUnit.LAYOUT_FLAG_MATCH_HOST_BOUNDS != 0) {
          if (drawables == null) {
            drawables = ArrayList()
          }
          drawables.add(mountItem.content as Drawable)
        }
      }
      return drawables
    }

  constructor(
      context: Context,
      unsafeModificationPolicy: UnsafeModificationPolicy?,
  ) : this(context, null, unsafeModificationPolicy)

  constructor(context: ComponentContext) : this(context.androidContext, null, null)

  init {
    setWillNotDraw(false)
    isChildrenDrawingOrderEnabled = true
    refreshAccessibilityDelegatesIfNeeded(true)
    clipChildren = false
  }

  override fun mount(index: Int, mountItem: MountItem) {
    mount(index, mountItem, mountItem.renderTreeNode.bounds)
  }

  /**
   * Mounts the given [MountItem] with unique index.
   *
   * @param index index of the [MountItem]. Guaranteed to be the same index as is passed for the
   *   corresponding `unmount(index, mountItem)` call.
   * @param mountItem item to be mounted into the host.
   * @param bounds the bounds of the item that is to be mounted into the host
   */
  fun mount(index: Int, mountItem: MountItem, bounds: Rect) {
    checkNotVisitingMountItems()
    val content = mountItem.content
    val renderUnit = getRenderUnit(mountItem)
    if (content is Drawable) {
      mountDrawable(index, mountItem, bounds)
    } else if (content is View) {
      viewMountItems.put(index, mountItem)
      mountView(content, renderUnit.flags, renderUnit.componentContext)
      maybeRegisterTouchExpansion(index, mountItem)
      maybeRegisterViewForAccessibility(renderUnit, content)
    }
    mountItems.put(index, mountItem)
    mountItem.host = this
    updateAccessibilityState(renderUnit)
  }

  private fun ensureDisappearingItems() {
    if (disappearingItems == null) {
      disappearingItems = ArrayList()
    }
  }

  override fun unmount(mountItem: MountItem) {
    // Before ensureScrapMountItemsArray(), which would otherwise leave a scrap array behind when
    // the guarded overload below rejects the call.
    checkNotVisitingMountItems()
    val indexOfValue = mountItems.indexOfValue(mountItem)
    val index =
        if (indexOfValue == -1) {
          ensureScrapMountItemsArray()
          val indexOfValueInScrap = requireScrapMountItemsArray().indexOfValue(mountItem)
          requireScrapMountItemsArray().keyAt(indexOfValueInScrap)
        } else {
          mountItems.keyAt(indexOfValue)
        }
    unmount(index, mountItem)
  }

  /**
   * Unmounts the given [MountItem] with unique index.
   *
   * @param index index of the [MountItem]. Guaranteed to be the same index as was passed for the
   *   corresponding `mount(index, mountItem)` call.
   * @param mountItem item to be unmounted from the host.
   */
  override fun unmount(index: Int, mountItem: MountItem) {
    checkNotVisitingMountItems()
    val content = mountItem.content
    if (content is Drawable) {
      unmountDrawable(content)
      removeItem(index, drawableMountItems, scrapDrawableMountItems)
    } else if (content is View) {
      unmountView(content)
      removeItem(index, viewMountItems, scrapViewMountItemsArray)
      isChildDrawingOrderDirty = true
      maybeUnregisterTouchExpansion(index, mountItem)
    }
    removeItem(index, mountItems, scrapMountItemsArray)
    releaseScrapDataStructuresIfNeeded()
    updateAccessibilityState(getRenderUnit(mountItem))
    mountItem.host = null
  }

  /**
   * This method is needed because if the disappearing item ended up being remounted to the root,
   * then the index can be different than the one it was created with.
   *
   * @param mountItem
   */
  override fun startDisappearingMountItem(mountItem: MountItem) {
    val index = mountItems.keyAt(mountItems.indexOfValue(mountItem))
    startUnmountDisappearingItem(index, mountItem)
  }

  fun startUnmountDisappearingItem(index: Int, mountItem: MountItem) {
    checkNotVisitingMountItems()
    val content = mountItem.content
    if (content is Drawable) {
      removeItem(index, drawableMountItems, scrapDrawableMountItems)
    } else if (content is View) {
      removeItem(index, viewMountItems, scrapViewMountItemsArray)
      isChildDrawingOrderDirty = true
      maybeUnregisterTouchExpansion(index, mountItem)
    }
    removeItem(index, mountItems, scrapMountItemsArray)
    releaseScrapDataStructuresIfNeeded()
    ensureDisappearingItems()
    requireDisappearingItems().add(mountItem)
    mountItem.host = null
  }

  override fun finaliseDisappearingItem(mountItem: MountItem): Boolean {
    ensureDisappearingItems()
    if (!requireDisappearingItems().remove(mountItem)) {
      return false
    }
    val content = mountItem.content
    if (content is Drawable) {
      unmountDrawable(content)
    } else if (content is View) {
      unmountView(content)
      isChildDrawingOrderDirty = true
    }
    updateAccessibilityState(getRenderUnit(mountItem))
    return true
  }

  fun hasDisappearingItems(): Boolean {
    return !disappearingItems.isNullOrEmpty()
  }

  private fun maybeMoveTouchExpansionIndexes(item: MountItem, oldIndex: Int, newIndex: Int) {
    val expandedTouchBounds = getExpandedTouchBounds(item.renderTreeNode.layoutData)
    val expansionDelegate = touchExpansionDelegate
    if (expandedTouchBounds == null || expansionDelegate == null) {
      return
    }
    expansionDelegate.moveTouchExpansionIndexes(oldIndex, newIndex)
  }

  private fun maybeRegisterTouchExpansion(index: Int, item: MountItem) {
    getExpandedTouchBounds(item.renderTreeNode.layoutData) ?: return

    val content = item.content
    if (this == content) {
      // Don't delegate to ourselves or we'll cause a StackOverflowError
      return
    }

    if (touchExpansionDelegate == null) {
      touchExpansionDelegate = TouchExpansionDelegate(this)
      touchDelegate = touchExpansionDelegate
    }
    touchExpansionDelegate?.registerTouchExpansion(index, (content as View), item)
  }

  private fun maybeUnregisterTouchExpansion(index: Int, item: MountItem) {
    if (touchExpansionDelegate == null) {
      return
    }

    val content = item.content
    if (this == content) {
      // Recursive delegation is never unregistered
      return
    }

    touchExpansionDelegate?.unregisterTouchExpansion(index)
  }

  /**
   * Returns the Litho-managed View whose expanded touch bounds contain the host-local point.
   *
   * If targets overlap, this uses the same reverse mount order as touch dispatch. This method does
   * not dispatch a touch event and returns null when Litho's expansion delegate is not the active
   * Android [touchDelegate].
   */
  @UiThread
  fun findExpandedTouchTarget(x: Int, y: Int): View? {
    val expansionDelegate = touchExpansionDelegate ?: return null
    if (touchDelegate !== expansionDelegate) {
      return null
    }
    return expansionDelegate.findDelegateViewAt(x, y)
  }

  override val mountItemCount: Int
    get() = mountItems.size()

  /** @return the [MountItem] that was mounted with the given index. */
  override fun getMountItemAt(index: Int): MountItem {
    return mountItems.valueAt(index)
  }

  override fun visitMountItemsUnguarded(visitor: MountItemVisitor): Boolean {
    for (i in 0 until mountItems.size()) {
      if (!visitor.visit(mountItems.valueAt(i))) return false
    }
    scrapMountItemsArray?.let { scrap ->
      for (i in 0 until scrap.size()) {
        if (!visitor.visit(scrap.valueAt(i))) return false
      }
    }
    return true
  }

  /** @return the content descriptons that are set on content mounted on this host */
  @SuppressLint("GetContentDescriptionOverride")
  override fun getContentDescription(): CharSequence? {
    return contentDescription
  }

  override fun setContentDescription(contentDescription: CharSequence?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.setContentDescription(contentDescription)
    if (this.contentDescription == contentDescription) {
      return
    }
    // This is a fix for an issue where TalkBack doesn't re-announce the content description after
    // a state update in some cases. It's behind a flag so that it can be turned off in case it
    // breaks something unexpectedly. See T193726518 for more details.
    if (!contentDescription.isNullOrEmpty() && this.isAccessibilityFocused) {
      sendAccessibilityEvent(AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION)
    }
    this.contentDescription = contentDescription
  }

  override fun setTag(key: Int, tag: Any?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.setTag(key, tag)
    if (key == COMPONENT_NODE_INFO_ID) {
      if (tag != null) {
        refreshAccessibilityDelegatesIfNeeded(true)
        if (componentAccessibilityDelegate != null) {
          (tag as? NodeInfo)?.let { componentAccessibilityDelegate?.setNodeInfo(it) }
        }
      } else {
        // Clear the accessibility delegate to prevent memory leaks when the view is pooled.
        // The delegate holds NodeInfo which references event handlers that can retain references
        // to fragments/activities after they are destroyed.
        refreshAccessibilityDelegatesIfNeeded(false)
        componentAccessibilityDelegate = null
      }
    }
  }

  override fun setTransformPivot(
      @FloatRange(from = 0.0, to = 100.0) pivotXPercent: Float,
      @FloatRange(from = 0.0, to = 100.0) pivotYPercent: Float,
  ) {
    this.pivotXPercent = pivotXPercent
    this.pivotYPercent = pivotYPercent
    updatePivots()
  }

  private fun updatePivots() {
    // Assigning pivotX/pivotY marks the pivot as explicitly set, which stops the platform from
    // re-centering it on resize. The percentages must stay tracked even when they are the default
    // 50/50, so that onSizeChanged() can do that re-centering itself.
    pivotX = width * pivotXPercent / 100f
    pivotY = height * pivotYPercent / 100f
  }

  override fun resetPivot() {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    this.pivotXPercent = UNSET
    this.pivotYPercent = UNSET
    super.resetPivot()
  }

  /**
   * Moves the MountItem associated to oldIndex in the newIndex position. This happens when a
   * LithoView needs to re-arrange the internal order of its items. If an item is already present in
   * newIndex the item is guaranteed to be either unmounted or moved to a different index by
   * subsequent calls to either [ComponentHost.unmount] or [ComponentHost.moveItem].
   *
   * @param item The item that has been moved.
   * @param oldIndex The current index of the MountItem.
   * @param newIndex The new index of the MountItem.
   */
  override fun moveItem(item: MountItem?, oldIndex: Int, newIndex: Int) {
    checkNotVisitingMountItems()
    var movedItem = item
    if (movedItem == null && scrapMountItemsArray != null) {
      movedItem = requireScrapMountItemsArray()[oldIndex]
    }

    if (movedItem == null) {
      return
    }

    // Check if we're trying to move a mount item from a place where it doesn't exist.
    // If so, fail early and throw exception with description.
    if (isIllegalMountItemMove(movedItem, oldIndex)) {
      val givenMountItemDescription = movedItem.renderTreeNode.generateDebugString(null)
      val existingMountItem = mountItems[oldIndex]
      val existingMountItemDescription =
          existingMountItem?.renderTreeNode?.generateDebugString(null) ?: "null"
      throw IllegalStateException(
          "Attempting to move MountItem from index: $oldIndex to index: $newIndex, but given MountItem does not exist at provided old index.\nGiven MountItem: $givenMountItemDescription\nExisting MountItem at old index: $existingMountItemDescription",
      )
    }
    maybeMoveTouchExpansionIndexes(movedItem, oldIndex, newIndex)
    val content = movedItem.content
    if (content is Drawable) {
      moveDrawableItem(oldIndex, newIndex)
    } else if (content is View) {
      isChildDrawingOrderDirty = true
      if (viewMountItems[newIndex] != null) {
        ensureScrapViewMountItemsArray()
        scrapItemAt(newIndex, viewMountItems, scrapViewMountItemsArray)
      }
      moveItem(oldIndex, newIndex, viewMountItems, scrapViewMountItemsArray)
      if (ComponentsConfiguration.enableComponentHostViewMoveInvalidation) {
        invalidate()
      }
    }
    if (mountItems[newIndex] != null) {
      ensureScrapMountItemsArray()
      scrapItemAt(newIndex, mountItems, scrapMountItemsArray)
    }
    moveItem(oldIndex, newIndex, mountItems, scrapMountItemsArray)
    releaseScrapDataStructuresIfNeeded()
  }

  override fun removeViewListeners() {
    withSafeModification {
      setOnClickListener(null)
      componentLongClickListener?.eventHandler = null
      componentFocusChangeListener?.eventHandler = null
      componentTouchListener?.eventHandler = null
      onInterceptTouchEventHandler = null
      // Clear the nodeInfo reference from the accessibility delegate to prevent memory leaks.
      // The nodeInfo contains event handlers that may hold references to fragments or other
      // UI components that should be garbage collected when the view is recycled.
      componentAccessibilityDelegate?.clearNodeInfo()
    }
  }

  private fun isIllegalMountItemMove(mountItem: MountItem, moveFromIndex: Int): Boolean {
    // If the mount item exists at the given index in the mount items array, this is a legal move.
    return if (mountItem == mountItems[moveFromIndex]) {
      false
    } else {
      // If the mount item exists at the given index in the scrap array, this is a legal move.
      // Otherwise, it is illegal.
      scrapMountItemsArray == null || mountItem != requireScrapMountItemsArray()[moveFromIndex]
    }
  }

  /**
   * Add view tags to this host.
   *
   * @param viewTags the map containing the tags by id.
   */
  fun addViewTags(viewTags: SparseArray<Any>?) {
    if (viewTags == null) {
      this.viewTags = viewTags
    } else {
      this.viewTags = mergeSparseArrays(this.viewTags, viewTags)
    }
  }

  fun unsetViewTags() {
    this.viewTags = null
  }

  /**
   * Sets a long click listener on this host.
   *
   * @return The previously set long click listener
   */
  var componentLongClickListener: ComponentLongClickListener?
    get() = onLongClickListener
    set(listener) {
      onLongClickListener = listener
      withSafeModification { setOnLongClickListener(listener) }
    }

  /**
   * Sets a key listener on this host.
   *
   * @return The previously set key listener
   */
  var componentKeyListener: ((View, Int, KeyEvent) -> Boolean)?
    get() = onKeyListener
    set(listener) {
      onKeyListener = listener
      withSafeModification { setOnKeyListener(listener) }
    }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    val interceptTouchEventHandler = onInterceptTouchEventHandler
    return if (interceptTouchEventHandler != null) {
      dispatchOnInterceptTouch(interceptTouchEventHandler, this, ev)
    } else super.onInterceptTouchEvent(ev)
  }

  private fun updateAccessibilityState(renderUnit: LithoRenderUnit) {
    // If the item has extra A11Y nodes then virtual views are implemented.
    val component = renderUnit.component
    if (
        renderUnit.isAccessible &&
            component is SpecGeneratedComponent &&
            component.implementsExtraAccessibilityNodes()
    ) {
      setImplementsVirtualViews(true)
    }
    maybeInvalidateAccessibilityState()

    // If there are no more mounted items then virtual views are implemented.
    if (mountItemCount == 0) {
      setImplementsVirtualViews(false)
    }
  }

  private fun maybeRegisterViewForAccessibility(renderUnit: LithoRenderUnit, view: View) {
    if (view is ComponentHost) {
      // We already registered the accessibility delegate when building the host.
      return
    }
    val nodeInfo = view.getTag(COMPONENT_NODE_INFO_ID) as? NodeInfo
    if (isComponentAccessibilityDelegateSet) {
      // Check if AccessibilityDelegate is set on the host, it means accessibility is enabled.
      nodeInfo?.let { registerAccessibilityDelegateOnView(view, it) }
    }
  }

  /**
   * Invalidates the accessibility tree of this host if an AccessibilityDelegate is set and any
   * children implement virtual views.
   */
  fun maybeInvalidateAccessibilityState() {
    if (hasAccessibilityDelegateAndVirtualViews() && componentAccessibilityDelegate != null) {
      componentAccessibilityDelegate?.invalidateRoot()
    }
  }

  fun implementsVirtualViews(): Boolean {
    return implementsVirtualViews
  }

  fun setImplementsVirtualViews(implementsVirtualViews: Boolean) {
    this.implementsVirtualViews = implementsVirtualViews
  }

  fun hadChildWithDuplicateParentState(): Boolean {
    return hadChildWithDuplicateParentState
  }

  private fun hasAccessibilityDelegateAndVirtualViews(): Boolean {
    return isComponentAccessibilityDelegateSet && implementsVirtualViews
  }

  public override fun dispatchHoverEvent(event: MotionEvent): Boolean {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    return ((componentAccessibilityDelegate != null &&
        implementsVirtualViews &&
        componentAccessibilityDelegate?.dispatchHoverEvent(event) == true) ||
        super.dispatchHoverEvent(event))
  }

  public override fun onFocusChanged(
      gainFocus: Boolean,
      direction: Int,
      previouslyFocusedRect: Rect?,
  ) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    if (componentAccessibilityDelegate != null && implementsVirtualViews) {
      componentAccessibilityDelegate?.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    return ((componentAccessibilityDelegate != null &&
        implementsVirtualViews &&
        hasOnlyOneAccessibleItem &&
        // `implementsVirtualViews` is a property of the component *type*, so a host whose content
        // currently exposes no virtual views still reaches here. Delegating in that state would
        // make ENTER and DPAD_CENTER dead on Litho buttons: ExploreByTouchHelper claims both
        // unconditionally, and with nothing to move between it swallows them instead of letting
        // View.onKeyUp turn them into a click.
        componentAccessibilityDelegate?.hasExtraAccessibilityNodes() == true &&
        componentAccessibilityDelegate?.dispatchKeyEvent(event) == true) ||
        super.dispatchKeyEvent(event))
  }

  val contentDescriptions: List<CharSequence?>
    get() {
      val contentDescriptions: MutableList<CharSequence?> = ArrayList()
      val size = drawableMountItems.size()
      for (i in 0 until size) {
        val contentDescription =
            getRenderUnit(requireNotNull(drawableMountItems.valueAt(i))).contentDescription
        if (contentDescription != null) {
          contentDescriptions.add(contentDescription)
        }
      }
      val hostContentDescription = contentDescription
      if (hostContentDescription != null) {
        contentDescriptions.add(hostContentDescription)
      }
      return contentDescriptions
    }

  private fun mountView(view: View, flags: Int, componentContext: ComponentContext) {
    val childShouldDuplicateParentState = isDuplicateParentState(flags)
    if (childShouldDuplicateParentState) {
      view.isDuplicateParentStateEnabled = true
      hadChildWithDuplicateParentState = true
    }
    if (view is ComponentHost && isDuplicateChildrenStates(flags)) {
      try {
        view.setAddStatesFromChildren(true)
      } catch (e: IllegalStateException) {
        ComponentUtils.handle(componentContext, e)
      }
    }
    isChildDrawingOrderDirty = true
    var lp = view.layoutParams
    if (lp == null) {
      lp = generateDefaultLayoutParams()
      view.layoutParams = lp
    }
    if (inLayout) {
      super.addViewInLayout(view, -1, view.layoutParams, true)
      invalidate()
    } else {
      super.addView(view, -1, view.layoutParams)
    }
  }

  private fun unmountView(view: View) {
    isChildDrawingOrderDirty = true
    if (inLayout) {
      super.removeViewInLayout(view)
    } else {
      super.removeView(view)
    }
    view.isDuplicateParentStateEnabled = false
    if (view is ComponentHost && view.addStatesFromChildren()) {
      view.setAddStatesFromChildren(false)
    }
  }

  public override fun dispatchDraw(canvas: Canvas) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    ComponentsSystrace.trace({ "mount.dispatchDraw" }) {
      drawBehind?.let { it ->
        ComponentsSystrace.trace({ "mount.drawBehind" }) { it.invoke(canvas) }
      }
      dispatchDraw.start(canvas)
      try {
        try {
          super.dispatchDraw(canvas)
        } catch (e: LithoMetadataExceptionWrapper) {
          val mountItemCount = mountItemCount
          val componentNames = StringBuilder("[")
          for (i in 0 until mountItemCount) {
            val item = mountItems[i]
            componentNames.append(
                if (item != null) getRenderUnit(item).component.simpleName else "null",
            )
            if (i < mountItemCount - 1) {
              componentNames.append(", ")
            } else {
              componentNames.append("]")
            }
          }
          e.addCustomMetadata("component_names_from_mount_items", componentNames.toString())
          throw e
        }

        // Cover the case where the host has no child views, in which case
        // getChildDrawingOrder() will not be called and the draw index will not
        // be incremented. This will also cover the case where drawables must be
        // painted after the last child view in the host.
        if (dispatchDraw.isRunning) {
          dispatchDraw.drawNext()
        }
      } finally {
        // Always clear the interleaved-draw state, even when drawing throws, so a
        // stale canvas cannot make drawNext() run outside of dispatchDraw().
        dispatchDraw.end()
      }

      // Everything from mMountItems was drawn at this point. Then ViewGroup took care of drawing
      // disappearing views, as they still added as children. Thus the only thing left to draw is
      // disappearing drawables
      val size = if (disappearingItems == null) 0 else requireDisappearingItems().size
      for (index in 0 until size) {
        val content = requireDisappearingItems()[index]?.content
        if (content is Drawable) {
          content.draw(canvas)
        }
      }
    }
    DebugDraw.draw(this, canvas)
  }

  public override fun getChildDrawingOrder(childCount: Int, i: Int): Int {
    updateChildDrawingOrderIfNeeded()

    // This method is called in very different contexts within a ViewGroup
    // e.g. when handling input events, drawing, etc. We only want to call
    // the draw methods if the InterleavedDispatchDraw is active.
    if (dispatchDraw.isRunning) {
      dispatchDraw.drawNext()
    }
    return childDrawingOrder[i]
  }

  override fun shouldDelayChildPressedState(): Boolean {
    return false
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    assertMainThread()
    var handled = false

    if (isEnabled) {
      // Iterate drawable from last to first to respect drawing order.
      for (i in drawableMountItems.size() - 1 downTo 0) {
        val item = drawableMountItems.valueAt(i)
        val content = item.content
        if (content is Touchable && !isTouchableDisabled(getRenderUnit(item).flags)) {
          @Suppress("USELESS_CAST") val t = content as Touchable
          if (t.shouldHandleTouchEvent(event) && t.onTouchEvent(event, this)) {
            handled = true
            break
          }
        }
      }
    }

    if (!handled) {
      handled = super.onTouchEvent(event)
    }

    return handled
  }

  protected open fun performLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = Unit

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    inLayout = true
    performLayout(changed, l, t, r, b)
    inLayout = false
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    if (pivotXPercent != UNSET && pivotYPercent != UNSET) {
      updatePivots()
    }
  }

  override fun requestLayout() {
    if (ComponentsConfiguration.enableRequestLayoutMainThreadCheck) {
      assertMainThread()
    }
    // Don't request a layout if it will be blocked by any parent. Requesting a layout that is
    // then ignored by an ancestor means that this host will remain in a state where it thinks that
    // it has requested layout, and will therefore ignore future layout requests. This will lead to
    // problems if a child (e.g. a ViewPager) requests a layout later on, since the request will be
    // wrongly ignored by this host.
    var parent: ViewParent? = this
    while (parent is ComponentHost) {
      if (!parent.shouldRequestLayout()) {
        return
      }
      parent = parent.parent
    }
    super.requestLayout()
  }

  protected open fun shouldRequestLayout(): Boolean {
    // Don't bubble during layout.
    return !inLayout
  }

  @SuppressLint("MissingSuperCall")
  override fun verifyDrawable(who: Drawable): Boolean {
    return true
  }

  override fun drawableStateChanged() {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.drawableStateChanged()
    val size = drawableMountItems.size()
    for (i in 0 until size) {
      val mountItem = drawableMountItems.valueAt(i)
      val renderUnit = getRenderUnit(mountItem)
      maybeSetDrawableState(this, (mountItem.content as Drawable), renderUnit.flags)
    }
  }

  override fun jumpDrawablesToCurrentState() {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.jumpDrawablesToCurrentState()
    val size = drawableMountItems.size()
    for (i in 0 until size) {
      val drawable = drawableMountItems.valueAt(i).content as Drawable
      DrawableCompat.jumpToCurrentState(drawable)
    }
  }

  override fun setVisibility(visibility: Int) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.setVisibility(visibility)
    val size = drawableMountItems.size()
    if (size > 0) {
      // We only do a main thread assert if there are drawable mount items because visibility may
      // be set on a LithoView during background layout inflation (AsyncLayoutInflater) before
      // we have any mounted content - we don't want to crash in that case.
      assertMainThread()
      for (i in 0 until size) {
        val drawable = drawableMountItems.valueAt(i).content as Drawable
        drawable.setVisible(visibility == VISIBLE, false)
      }
    }
  }

  override fun getTag(key: Int): Any? {
    val tags = viewTags
    if (tags != null) {
      val value = tags[key]
      if (value != null) {
        return value
      }
    }
    return super.getTag(key)
  }

  protected fun refreshAccessibilityDelegatesIfNeeded(isAccessibilityEnabled: Boolean) {
    if (isAccessibilityEnabled == isComponentAccessibilityDelegateSet) {
      return
    }
    if (isAccessibilityEnabled && componentAccessibilityDelegate == null) {
      val nodeInfo = getTag(COMPONENT_NODE_INFO_ID) as? NodeInfo

      val originalFocusable =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.focusable
          } else {
            if (this.isFocusable) {
              VIEW_FOCUSABLE
            } else {
              VIEW_NOT_FOCUSABLE
            }
          }
      componentAccessibilityDelegate =
          ComponentAccessibilityDelegate(
              this,
              nodeInfo,
              originalFocusable,
              ViewCompat.getImportantForAccessibility(this),
          )
    }
    ViewCompat.setAccessibilityDelegate(
        this,
        if (isAccessibilityEnabled) componentAccessibilityDelegate else null,
    )
    isComponentAccessibilityDelegateSet = isAccessibilityEnabled
    if (!isAccessibilityEnabled) {
      return
    }
    val size = childCount
    for (i in 0 until size) {
      val child = getChildAt(i)
      if (child is ComponentHost) {
        child.refreshAccessibilityDelegatesIfNeeded(true)
      } else {
        val nodeInfo = child.getTag(COMPONENT_NODE_INFO_ID) as? NodeInfo
        nodeInfo?.let { registerAccessibilityDelegateOnView(child, it) }
      }
    }
  }

  override fun setAccessibilityDelegate(accessibilityDelegate: AccessibilityDelegate?) {
    super.setAccessibilityDelegate(accessibilityDelegate)

    // We cannot compare against mComponentAccessibilityDelegate directly, since it is not the
    // delegate that we receive here. Instead, we'll set this to true at the point that we set that
    // delegate explicitly.
    isComponentAccessibilityDelegateSet = false
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun addView(child: View) {
    throw UnsupportedOperationException("Adding Views manually within LithoViews is not supported")
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun addView(child: View, index: Int) {
    throw UnsupportedOperationException("Adding Views manually within LithoViews is not supported")
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun addView(child: View, index: Int, params: LayoutParams) {
    throw UnsupportedOperationException("Adding Views manually within LithoViews is not supported")
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun addViewInLayout(
      child: View,
      index: Int,
      params: LayoutParams,
      preventRequestLayout: Boolean,
  ): Boolean {
    throw UnsupportedOperationException("Adding Views manually within LithoViews is not supported")
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun attachViewToParent(child: View, index: Int, params: LayoutParams) {
    throw UnsupportedOperationException("Adding Views manually within LithoViews is not supported")
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun removeView(view: View) {
    throw UnsupportedOperationException(
        "Removing Views manually within LithoViews is not supported",
    )
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun removeViewInLayout(view: View) {
    throw UnsupportedOperationException(
        "Removing Views manually within LithoViews is not supported",
    )
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun removeViewsInLayout(start: Int, count: Int) {
    throw UnsupportedOperationException(
        "Removing Views manually within LithoViews is not supported",
    )
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun removeViewAt(index: Int) {
    throw UnsupportedOperationException(
        "Removing Views manually within LithoViews is not supported",
    )
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun removeViews(start: Int, count: Int) {
    throw UnsupportedOperationException(
        "Removing Views manually within LithoViews is not supported",
    )
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun removeAllViewsInLayout() {
    throw UnsupportedOperationException(
        "Removing Views manually within LithoViews is not supported",
    )
  }

  /**
   * Litho handles adding/removing views automatically using mount/unmount calls. Manually adding/
   * removing views will mess up Litho's bookkeeping of added views and cause weird crashes down the
   * line.
   */
  @Deprecated("")
  override fun removeDetachedView(child: View, animate: Boolean) {
    throw UnsupportedOperationException(
        "Removing Views manually within LithoViews is not supported",
    )
  }

  /**
   * Manually adds a View as a child of this ComponentHost for the purposes of testing. **This
   * should only be used for tests as this is not safe and will likely cause weird crashes if used
   * in a production environment**.
   */
  @VisibleForTesting
  fun addViewForTest(view: View) {
    val params = if (view.layoutParams == null) generateDefaultLayoutParams() else view.layoutParams
    super.addView(view, -1, params)
  }

  private fun updateChildDrawingOrderIfNeeded() {
    if (!isChildDrawingOrderDirty) {
      return
    }
    val childCount = childCount
    if (childDrawingOrder.size < childCount) {
      childDrawingOrder = IntArray(childCount + 5)
    }

    // Collect view mount items with their drawing order
    if (viewDrawingOrder.size != viewMountItems.size()) {
      viewDrawingOrder = Array(viewMountItems.size()) { DrawingOrder(-1, -1) }
    }

    val viewMountItemCount = viewMountItems.size()
    var hasCustomZIndex = false
    for (i in 0 until viewMountItemCount) {
      val mountIndex = viewMountItems.keyAt(i)
      val mountItem = viewMountItems.valueAt(i)
      val child = mountItem.content as View
      val zIndex = getZIndex(mountItem.renderTreeNode.layoutData)
      val order = viewDrawingOrder[i]
      order.childIndex = indexOfChild(child)
      order.zIndex = zIndex ?: mountIndex
      hasCustomZIndex = hasCustomZIndex || (zIndex != null)
    }

    // Sort by drawingOrder (stable sort preserves mount order for equal values)
    if (hasCustomZIndex) {
      viewDrawingOrder.sortWith(drawingOrderComparator)
    }

    var index = 0
    for (viewWithOrder in viewDrawingOrder) {
      childDrawingOrder[index++] = viewWithOrder.childIndex
    }

    // Draw disappearing items on top of mounted views.
    val size = if (disappearingItems == null) 0 else requireDisappearingItems().size
    for (i in 0 until size) {
      val child = requireDisappearingItems()[i]?.content
      if (child is View) {
        childDrawingOrder[index++] = indexOfChild(child)
      }
    }
    isChildDrawingOrderDirty = false
  }

  override fun safelyUnmountAll() {
    checkNotVisitingMountItems()
    // Drain scrap first: removeItem() in unmount() prefers the scrap array when an
    // index is present in both, so emptying scrap first ensures the subsequent
    // pass over mountItems actually clears mountItems (and mountItemCount drops).
    drainSafely(scrapMountItemsArray)
    drainSafely(mountItems)
  }

  private fun drainSafely(items: SparseArrayCompat<MountItem>?) {
    if (items == null) return
    while (items.size() > 0) {
      val last = items.size() - 1
      val key = items.keyAt(last)
      val item = items.valueAt(last)
      try {
        unmount(key, item)
      } catch (t: Throwable) {
        // Last-resort: drop the entry so we can't loop forever on a single bad item.
        // unmount() may have already removed the entry before throwing (via
        // removeItem()), so guard against the index being out of bounds.
        if (last < items.size()) {
          items.removeAt(last)
        }
      }
    }
  }

  private fun ensureScrapViewMountItemsArray() {
    if (scrapViewMountItemsArray == null) {
      scrapViewMountItemsArray = SparseArrayCompat(SCRAP_ARRAY_INITIAL_SIZE)
    }
  }

  private fun ensureScrapMountItemsArray() {
    if (scrapMountItemsArray == null) {
      scrapMountItemsArray = SparseArrayCompat(SCRAP_ARRAY_INITIAL_SIZE)
    }
  }

  private fun releaseScrapDataStructuresIfNeeded() {
    if (scrapMountItemsArray?.isEmpty == true) {
      scrapMountItemsArray = null
    }
    if (scrapViewMountItemsArray?.isEmpty == true) {
      scrapViewMountItemsArray = null
    }
  }

  private fun mountDrawable(index: Int, mountItem: MountItem, bounds: Rect) {
    assertMainThread()
    drawableMountItems.put(index, mountItem)
    val drawable = mountItem.content as Drawable
    drawable.setVisible(visibility == VISIBLE, false)
    drawable.callback = this
    invalidate(bounds)
  }

  private fun unmountDrawable(drawable: Drawable) {
    assertMainThread()
    drawable.callback = null
    invalidate(drawable.bounds)
    releaseScrapDataStructuresIfNeeded()
  }

  private fun moveDrawableItem(oldIndex: Int, newIndex: Int) {
    assertMainThread()

    // When something is already present in newIndex position we need to keep track of it.
    if (drawableMountItems[newIndex] != null) {
      ensureScrapDrawableMountItemsArray()
      scrapItemAt(newIndex, drawableMountItems, scrapDrawableMountItems)
    }

    // Move the MountItem in the new position.
    moveItem(oldIndex, newIndex, drawableMountItems, scrapDrawableMountItems)

    // Drawing order changed, invalidate the whole view.
    this.invalidate()
    releaseScrapDataStructuresIfNeeded()
  }

  private fun ensureScrapDrawableMountItemsArray() {
    if (scrapDrawableMountItems == null) {
      scrapDrawableMountItems = SparseArrayCompat(SCRAP_ARRAY_INITIAL_SIZE)
    }
  }

  private inline fun withSafeModification(block: () -> Unit) {
    val prevSafeModification = isSafeViewModificationsEnabled
    try {
      setSafeViewModificationsEnabled(true)
      block()
    } finally {
      setSafeViewModificationsEnabled(prevSafeModification)
    }
  }

  /**
   * Encapsulates the logic for drawing a set of views and drawables respecting their drawing order
   * withing the component host i.e. allow interleaved views and drawables to be drawn with the
   * correct z-index.
   */
  private inner class InterleavedDispatchDraw {
    private var canvas: Canvas? = null
    private var drawIndex = 0
    private var itemsToDraw = 0

    fun start(canvas: Canvas) {
      this.canvas = canvas
      drawIndex = 0
      itemsToDraw = mountItems.size()
    }

    val isRunning: Boolean
      get() = canvas != null && drawIndex < itemsToDraw

    fun drawNext() {
      // Snapshot the canvas: a nested dispatchDraw() on this host (re-entered from a
      // Drawable.draw() callback) resets and clears this shared state via start()/end().
      // Drawing with the entry canvas keeps the outer pass going instead of crashing.
      val currentCanvas = canvas ?: return

      val size = mountItems.size()
      for (i in drawIndex until size) {
        val mountItem = mountItems.valueAt(i)
        val content = mountItem.content

        // During a ViewGroup's dispatchDraw() call with children drawing order enabled,
        // getChildDrawingOrder() will be called before each child view is drawn. This
        // method will only draw the drawables "between" the child views and the let
        // the host draw its children as usual. This is why views are skipped here.
        if (content is View) {
          drawIndex = i + 1
          return
        }
        if (!mountItem.isBound) {
          continue
        }
        trace({ "draw: ${getMountItemName(mountItem)}" }) {
          (content as Drawable).draw(currentCanvas)
        }
      }
      drawIndex = itemsToDraw
    }

    fun end() {
      canvas = null
    }
  }

  override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    // The view framework requires that a contentDescription be set for the
    // getIterableTextForAccessibility method to work.  If one isn't set, all text granularity
    // actions will be ignored.
    if (
        action == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY ||
            action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
    ) {
      var contentDesc: CharSequence? = null
      if (!contentDescription.isNullOrEmpty()) {
        contentDesc = contentDescription
      } else if (contentDescriptions.isNotEmpty()) {
        contentDesc = contentDescriptions.joinToString(separator = ", ")
      } else {
        val textContentText = textContentText
        if (textContentText.isNotEmpty()) {
          contentDesc = textContentText.joinToString(separator = ", ")
        }
      }
      if (contentDesc == null) {
        return false
      }
      contentDescription = contentDesc
      super.setContentDescription(contentDescription)
    }
    return super.performAccessibilityAction(action, arguments)
  }

  override fun hasOverlappingRendering(): Boolean {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    return if (width <= 0 || height <= 0) {
      // Views with size zero can't possibly have overlapping rendering.
      // Returning false here prevents the rendering system from creating
      // zero-sized layers, which causes crashes.
      false
    } else if (
        width > ComponentsConfiguration.overlappingRenderingViewSizeLimit ||
            height > ComponentsConfiguration.overlappingRenderingViewSizeLimit
    ) {
      false
    } else {
      super.hasOverlappingRendering()
    }
  }

  override fun setAlpha(alpha: Float) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    if (alpha != 0f && alpha != 1f) {
      if (
          width >= ComponentsConfiguration.partialAlphaWarningSizeThresold ||
              height >= ComponentsConfiguration.partialAlphaWarningSizeThresold
      ) {
        if (HAS_WARNED_ABOUT_PARTIAL_ALPHA) {
          // Only warn about partial alpha once per process lifetime to avoid spamming (this might
          // be called frequently from inside an animation)
          return
        }
        HAS_WARNED_ABOUT_PARTIAL_ALPHA = true
        ErrorReporter.report(
            LogLevel.ERROR,
            PARTIAL_ALPHA_TEXTURE_TOO_BIG,
            "Partial alpha ($alpha) with large view ($width, $height)",
        )
      }
    }
    super.setAlpha(alpha)
  }

  override fun invalidate() {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.invalidate()
  }

  override fun invalidate(l: Int, t: Int, r: Int, b: Int) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.invalidate(l, t, r, b)
  }

  override fun invalidate(dirty: Rect) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    super.invalidate(dirty)
  }

  override fun setInLayout() {
    inLayout = true
  }

  override fun unsetInLayout() {
    inLayout = false
  }

  fun setSafeViewModificationsEnabled(enabled: Boolean) {
    isSafeViewModificationsEnabled = enabled
  }

  private fun checkUnsafeViewModification() {
    if (!isSafeViewModificationsEnabled && unsafeModificationPolicy != null) {
      when (unsafeModificationPolicy) {
        UnsafeModificationPolicy.LOG ->
            dispatch(LithoDebugEvent.DebugInfo, { "-1" }, LogLevel.DEBUG) {
                attribute: MutableMap<String, Any?> ->
              attribute[Name] = "unsafe-component-host-modification"
            }
        UnsafeModificationPolicy.CRASH ->
            throw ComponentHostInvalidModification(
                "Should not modify component host outside of the Litho View Attributes Extensions." +
                    " Let us know if your use case is valid",
            )
      }
    }
  }

  private fun requireScrapMountItemsArray(): SparseArrayCompat<MountItem> {
    return checkNotNull(scrapMountItemsArray)
  }

  private fun requireDisappearingItems(): ArrayList<MountItem?> {
    return checkNotNull(disappearingItems)
  }

  override fun setOnClickListener(l: OnClickListener?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    checkUnsafeViewModification()
    super.setOnClickListener(l)
  }

  override fun setOnLongClickListener(l: OnLongClickListener?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    checkUnsafeViewModification()
    super.setOnLongClickListener(l)
  }

  override fun setOnKeyListener(l: OnKeyListener?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    checkUnsafeViewModification()
    super.setOnKeyListener(l)
  }

  override fun setOnTouchListener(l: OnTouchListener?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    checkUnsafeViewModification()
    super.setOnTouchListener(l)
  }

  override fun setTag(tag: Any?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    checkUnsafeViewModification()
    super.setTag(tag)
  }

  override fun setEnabled(enabled: Boolean) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    checkUnsafeViewModification()
    super.setEnabled(enabled)
  }

  override fun setOnFocusChangeListener(l: OnFocusChangeListener?) {
    if (ComponentsConfiguration.enableExpandedComponentHostMainThreadChecks) {
      assertMainThread()
    }
    checkUnsafeViewModification()
    super.setOnFocusChangeListener(l)
  }

  /** Clean up all fields to avoid being reused with an incorrect state. */
  fun cleanup() {
    checkNotVisitingMountItems()
    mountItems.clear()
    viewMountItems.clear()
    drawableMountItems.clear()
    scrapViewMountItemsArray = null
    scrapMountItemsArray = null
    scrapDrawableMountItems = null
    contentDescription = null
    viewTags = null
    inLayout = false
    componentAccessibilityDelegate = null
    isComponentAccessibilityDelegateSet = false
    onLongClickListener = null
    onKeyListener = null
    onInterceptTouchEventHandler = null
    pivotXPercent = UNSET
    pivotYPercent = UNSET
    implementsVirtualViews = false
    isSafeViewModificationsEnabled = false
    componentTouchListener = null
    touchExpansionDelegate = null
  }

  /**
   * This determines what is the actions to take if we detected an invalid modification of a
   * [ComponentHost].
   *
   * This can happen for example if a client sets a click listener outside of a Litho specific
   * codepath.
   *
   * This method demonstrates how to use a Column with a specific style.
   *
   * Example Kotlin code:
   * ```
   * Column(style = Style.onVisible { event ->
   *   val view = event.content as? ComponentHost
   *   if(view != null) view.setOnClickListener { ... } // This an invalid usage
   * }
   * ```
   */
  enum class UnsafeModificationPolicy(val key: String) {
    LOG("log"),
    CRASH("crash"),
  }

  /**
   * This exception is to allow us to identify potential wrong modifications of a [ComponentHost].
   * This can happen if clients get access to them (e.g. onVisibility callbacks) and then perform
   * modifications such as setting click listeners/modifying alpha. It is important to identify
   * these situations since it can break other behaviors such as host recycling.
   *
   * There might be valid cases where this happens, but we will defer that evaluation to once they
   * are identified.
   */
  class ComponentHostInvalidModification(message: String) : RuntimeException(message)

  companion object {
    @IdRes
    @JvmField
    val COMPONENT_ACCESSIBILITY_DELEGATE_ID: Int = R.id.component_accessibility_delegate
    @IdRes @JvmField val COMPONENT_NODE_INFO_ID: Int = R.id.component_node_info
    const val PARTIAL_ALPHA_TEXTURE_TOO_BIG: String = "PartialAlphaTextureTooBig"
    private const val SCRAP_ARRAY_INITIAL_SIZE = 4
    private var HAS_WARNED_ABOUT_PARTIAL_ALPHA: Boolean = false
    private const val UNSET: Float = Float.MIN_VALUE
    // Maps to the private View.FOCUSABLE
    private const val VIEW_FOCUSABLE: Int = 1
    // Maps to the private View.NOT_FOCUSABLE
    private const val VIEW_NOT_FOCUSABLE: Int = 0

    internal fun registerAccessibilityDelegateOnView(view: View, nodeInfo: NodeInfo) {
      val existingRegistration =
          view.getTag(COMPONENT_ACCESSIBILITY_DELEGATE_ID) as? AccessibilityDelegateRegistration
      val currentDelegate = ViewCompat.getAccessibilityDelegate(view)
      if (
          existingRegistration?.isInstalled == true &&
              (currentDelegate === existingRegistration.lithoDelegate ||
                  (existingRegistration.viewRewrappedLithoDelegate &&
                      currentDelegate === existingRegistration.originalDelegate))
      ) {
        existingRegistration.lithoDelegate.setNodeInfo(nodeInfo)
        return
      }
      existingRegistration?.lithoDelegate?.clearNodeInfo()
      existingRegistration?.lithoDelegate?.clearWrappedDelegate()

      if (existingRegistration == null && currentDelegate is ComponentAccessibilityDelegate) {
        currentDelegate.setNodeInfo(nodeInfo)
        view.setTag(
            COMPONENT_ACCESSIBILITY_DELEGATE_ID,
            AccessibilityDelegateRegistration(
                lithoDelegate = currentDelegate,
                originalDelegate = null,
                viewRewrappedLithoDelegate = false,
                isInstalled = true,
            ),
        )
        return
      }

      val originalFocusable =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.focusable
          } else {
            if (view.isFocusable) {
              VIEW_FOCUSABLE
            } else {
              VIEW_NOT_FOCUSABLE
            }
          }

      val registration =
          existingRegistration
              ?: AccessibilityDelegateRegistration(
                  lithoDelegate =
                      ComponentAccessibilityDelegate(
                          view = view,
                          nodeInfo = nodeInfo,
                          originalFocus = originalFocusable,
                          originalImportantForAccessibility = view.importantForAccessibility,
                          wrappedDelegate = currentDelegate,
                      ),
              )
      val lithoDelegate = registration.lithoDelegate
      if (existingRegistration != null) {
        lithoDelegate.setNodeInfo(nodeInfo)
        lithoDelegate.setWrappedDelegate(currentDelegate)
      }
      ViewCompat.setAccessibilityDelegate(view, lithoDelegate)

      val delegateAfterRegistration = ViewCompat.getAccessibilityDelegate(view)
      val viewRewrappedLithoDelegate = delegateAfterRegistration !== lithoDelegate
      if (viewRewrappedLithoDelegate) {
        // RCTextView and similar Views immediately put their own delegate back around ours. Keeping
        // their delegate wrapped inside ours as well would create a cycle and recurse forever.
        lithoDelegate.clearWrappedDelegate()
      }
      registration.originalDelegate = currentDelegate
      registration.viewRewrappedLithoDelegate = viewRewrappedLithoDelegate
      registration.isInstalled = true
      view.setTag(COMPONENT_ACCESSIBILITY_DELEGATE_ID, registration)
    }

    internal fun unregisterAccessibilityDelegateOnView(view: View) {
      val registration =
          view.getTag(COMPONENT_ACCESSIBILITY_DELEGATE_ID) as? AccessibilityDelegateRegistration
              ?: return
      if (!registration.isInstalled) {
        return
      }

      val currentDelegate = ViewCompat.getAccessibilityDelegate(view)
      when {
        currentDelegate === registration.lithoDelegate ->
            ViewCompat.setAccessibilityDelegate(view, registration.originalDelegate)
        registration.viewRewrappedLithoDelegate &&
            currentDelegate === registration.originalDelegate ->
            // The View put its own delegate back around Litho. Setting null lets the View remove
            // Litho from that chain and reinstall its own delegate, as RCTextView does.
            ViewCompat.setAccessibilityDelegate(view, null)
      }

      registration.lithoDelegate.clearNodeInfo()
      registration.lithoDelegate.clearWrappedDelegate()
      registration.originalDelegate = null
      registration.viewRewrappedLithoDelegate = false
      registration.isInstalled = false
    }

    private fun getMountItemName(mountItem: MountItem): String {
      return getRenderUnit(mountItem).component.simpleName
    }
  }
}

private class AccessibilityDelegateRegistration(
    val lithoDelegate: ComponentAccessibilityDelegate,
    var originalDelegate: AccessibilityDelegateCompat? = null,
    var viewRewrappedLithoDelegate: Boolean = false,
    var isInstalled: Boolean = false,
)

private data class DrawingOrder(var childIndex: Int, var zIndex: Int)

private val drawingOrderComparator: Comparator<DrawingOrder> =
    compareBy<DrawingOrder> { it.zIndex }.thenBy { it.childIndex }
