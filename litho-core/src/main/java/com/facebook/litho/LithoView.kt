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
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.annotation.VisibleForTesting
import androidx.core.view.accessibility.AccessibilityManagerCompat
import androidx.core.view.accessibility.AccessibilityManagerCompat.AccessibilityStateChangeListenerCompat
import androidx.lifecycle.LifecycleOwner
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.utils.CommonUtils
import com.facebook.rendercore.utils.MeasureSpecUtils
import com.facebook.rendercore.visibility.VisibilityChangeReason
import java.lang.ref.WeakReference
import java.util.Deque
import java.util.LinkedList
import kotlin.math.max

/** A [ViewGroup] that can host the mounted state of a [Component]. */
open class LithoView
@JvmOverloads
constructor(
    /**
     * @return [ComponentContext] associated with this LithoView. It's a wrapper on the [Context]
     *   originally used to create this LithoView itself.
     */
    @get:JvmName("getComponentContext") val componentContext: ComponentContext,
    attrs: AttributeSet? = null,
) : BaseMountingView(componentContext.androidContext, attrs) {

  private var _componentTree: ComponentTree? = null
  private var isAttachedForTest = false
  private var forceLayout = false
  private var _suppressMeasureComponentTree = false
  private var isMeasuring = false
  private var wasMeasured = false
  private var hasNewComponentTree = false
  private var onPostDrawListener: OnPostDrawListener? = null

  private val accessibilityManager =
      componentContext.androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
          as AccessibilityManager?

  private val accessibilityStateChangeListener = AccessibilityStateChangeListener(this)

  // Keep ComponentTree when detached from this view in case the ComponentTree is shared between
  // sticky header and RecyclerView's binder
  private var temporaryDetachedComponentTree: ComponentTree? = null
  private var doMeasureInLayout = false
  private var mountStartupLoggingInfo: MountStartupLoggingInfo? = null
  private var defaultLithoVisibilityEventsController: AOSPLithoVisibilityEventsController? = null
  private val lithoVisibilityEventsListener =
      LithoVisibilityEventsListener { state: LithoVisibilityEventsController.LithoVisibilityState?
        ->
        if (componentTreeHasVisibilityEventsController()) {
          // If the ComponentTree has a LithoVisibilityEventsController, it take precedence over the
          // the default visibility events controller.
          return@LithoVisibilityEventsListener
        }
        // Pass LIFECYCLE reason for visibility changes triggered by lifecycle events
        when (state) {
          LithoVisibilityEventsController.LithoVisibilityState.HINT_VISIBLE ->
              setVisibilityHintNonRecursive(true, VisibilityChangeReason.LIFECYCLE)

          LithoVisibilityEventsController.LithoVisibilityState.HINT_INVISIBLE ->
              setVisibilityHintNonRecursive(false, VisibilityChangeReason.LIFECYCLE)
          else -> {}
        }
      }

  @JvmOverloads
  constructor(
      context: Context,
      attrs: AttributeSet? = null,
  ) : this(ComponentContext(context), attrs)

  internal fun forceRelayout() {
    forceLayout = true
    requestLayout()
  }

  fun setTemporaryDetachedComponentTree(componentTree: ComponentTree?) {
    temporaryDetachedComponentTree = componentTree
  }

  fun hasTemporaryDetachedComponentTree(): Boolean = temporaryDetachedComponentTree != null

  /**
   * Along with [onDetachedFromWindowForTest] below, makes the LithoView think it's attached/
   * detached in a unit test environment. This also handles setting the same state for all LithoView
   * children.
   *
   * Implementation Note: Ideally, we'd just attach the LithoView to a View hierarchy and let AOSP
   * handle all this for us. The reason we haven't is because attaching to an Activity while also
   * trying to make sure the full LithoView is always considered visible (for the purposes of
   * visibility events and incremental mount) proved difficult - if interested, see summary on the
   * blame diff for this comment for more info.
   */
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  fun onAttachedToWindowForTest() {
    if (isAttachedForTest) {
      return
    }

    onAttachedToWindow()
    isAttachedForTest = true

    dispatchAttachedForTestToChildren()
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  fun onDetachedFromWindowForTest() {
    if (!isAttachedForTest) {
      return
    }

    isAttachedForTest = false
    onDetachedFromWindow()

    dispatchAttachedForTestToChildren()
  }

  /**
   * If set to true, the onMeasure(..) call won't measure the ComponentTree with the given measure
   * specs, but it will just use them as measured dimensions.
   */
  fun suppressMeasureComponentTree(suppress: Boolean) {
    _suppressMeasureComponentTree = suppress
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    trace(
        {
          "LithoView.onMeasure[${currentLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
        },
    ) {
      onMeasureInternal(widthMeasureSpec, heightMeasureSpec)
      wasMeasured = true
    }
  }

  private fun onMeasureInternal(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    // mAnimatedWidth/mAnimatedHeight >= 0 if something is driving a width/height animation.
    var widthMeasureSpec = widthMeasureSpec
    var heightMeasureSpec = heightMeasureSpec
    val animating = animatedWidth != SIZE_UNSET || animatedHeight != SIZE_UNSET
    // up to date view sizes, taking into account running animations
    val upToDateWidth = if (animatedWidth != SIZE_UNSET) animatedWidth else width
    val upToDateHeight = if (animatedHeight != SIZE_UNSET) animatedHeight else height
    animatedWidth = SIZE_UNSET
    animatedHeight = SIZE_UNSET

    if (animating) {
      // If the mount state is dirty, we want to ignore the current animation and calculate the
      // new LayoutState as normal below. That LayoutState has the opportunity to define its own
      // transition to a new width/height from the current height of the LithoView, or if not we
      // will jump straight to that width/height.
      if (!isMountStateDirty) {
        setMeasuredDimension(upToDateWidth, upToDateHeight)
        return
      }
    }

    val layoutParams = layoutParams
    if (layoutParams is LayoutManagerOverrideParams) {
      val layoutManagerOverrideParams = layoutParams as LayoutManagerOverrideParams
      val overrideWidthSpec = layoutManagerOverrideParams.widthMeasureSpec
      if (overrideWidthSpec != MeasureSpecUtils.unspecified()) {
        widthMeasureSpec = overrideWidthSpec
      }
      val overrideHeightSpec = layoutManagerOverrideParams.heightMeasureSpec
      if (overrideHeightSpec != MeasureSpecUtils.unspecified()) {
        heightMeasureSpec = overrideHeightSpec
      }
    }

    var width = MeasureSpec.getSize(widthMeasureSpec)
    var height = MeasureSpec.getSize(heightMeasureSpec)

    if (temporaryDetachedComponentTree != null && _componentTree == null) {
      componentTree = temporaryDetachedComponentTree
      temporaryDetachedComponentTree = null
    }

    if (
        !forceLayout &&
            SizeSpec.getMode(widthMeasureSpec) == SizeSpec.EXACTLY &&
            SizeSpec.getMode(heightMeasureSpec) == SizeSpec.EXACTLY
    ) {
      // If the measurements are exact, postpone LayoutState calculation from measure to layout.
      // This is part of the fix for android's double measure bug. Doing this means that if we get
      // remeasured with different exact measurements, we don't compute two layouts.
      doMeasureInLayout = true
      setMeasuredDimension(width, height)
      return
    }

    isMeasuring = true

    _componentTree?.let { tree ->
      if (!_suppressMeasureComponentTree) {
        val forceRelayout = forceLayout
        forceLayout = false
        tree.measure(
            adjustMeasureSpecForPadding(widthMeasureSpec, paddingRight + paddingLeft),
            adjustMeasureSpecForPadding(heightMeasureSpec, paddingTop + paddingBottom),
            layoutSize,
            forceRelayout,
        )

        width = layoutSize[0]
        height = layoutSize[1]
        doMeasureInLayout = false
      }
    }

    val canAnimateRootBounds =
        !_suppressMeasureComponentTree &&
            _componentTree != null &&
            (!hasNewComponentTree || !hasMountedAtLeastOnce())

    if (canAnimateRootBounds) {
      // We might need to collect transitions before mount to know whether this LithoView has
      // width or height animation.
      maybeCollectAllTransitions()

      val initialAnimatedWidth =
          getInitialAnimatedMountingViewWidth(upToDateWidth, hasNewComponentTree)
      if (initialAnimatedWidth != SIZE_UNSET) {
        width = initialAnimatedWidth
      }

      val initialAnimatedHeight =
          getInitialAnimatedMountingViewHeight(upToDateHeight, hasNewComponentTree)
      if (initialAnimatedHeight != SIZE_UNSET) {
        height = initialAnimatedHeight
      }
    }
    setMeasuredDimension(width, height)

    hasNewComponentTree = false
    isMeasuring = false
  }

  fun hasMeasuredAtLeastOnce(): Boolean = wasMeasured || measuredHeight != 0 || measuredWidth != 0

  override fun onBeforeLayout(left: Int, top: Int, right: Int, bottom: Int) {
    super.onBeforeLayout(left, top, right, bottom)
    val tree = _componentTree
    check(tree != null && !tree.isReleased) {
      "Trying to layout a LithoView holding onto a released ComponentTree"
    }

    if (doMeasureInLayout || tree.mainThreadLayoutState == null) {
      val widthWithoutPadding = max(0, right - left - paddingRight - paddingLeft)
      val heightWithoutPadding = max(0, bottom - top - paddingTop - paddingBottom)

      // Call measure so that we get a layout state that we can use for layout.
      tree.measure(
          MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.EXACTLY),
          layoutSize,
          false,
      )
      hasNewComponentTree = false
      doMeasureInLayout = false
    }
  }

  @SuppressLint("LambdaEquality")
  override fun onLifecycleOwnerChanged(
      previousLifecycleOwner: LifecycleOwner?,
      currentLifecycleOwner: LifecycleOwner?,
  ) {
    if (ComponentsConfiguration.enableDefaultVisibilityEventsController) {
      // If a new LifecycleOwner is set, we need to update the default visibility events controller

      if (previousLifecycleOwner !== currentLifecycleOwner && currentLifecycleOwner != null) {
        cleanupDefaultVisibilityController()
        initializeDefaultVisibilityController(currentLifecycleOwner)
      }

      // If the current LifecycleOwner is null, we need to cleanup the default visibility events
      if (currentLifecycleOwner == null) {
        cleanupDefaultVisibilityController()
      }
    }
  }

  private fun initializeDefaultVisibilityController(lifecycleOwner: LifecycleOwner) {
    val controller = AOSPLithoVisibilityEventsController(lifecycleOwner)
    defaultLithoVisibilityEventsController = controller
    controller.addListener(lithoVisibilityEventsListener)
    if (!componentTreeHasVisibilityEventsController()) {
      initVisibilityHint(controller.visibilityState)
    }
  }

  private fun cleanupDefaultVisibilityController() {
    defaultLithoVisibilityEventsController?.let { controller ->
      controller.removeListener(lithoVisibilityEventsListener)
      defaultLithoVisibilityEventsController = null
      if (!componentTreeHasVisibilityEventsController()) {
        resetVisibilityHint()
      }
    }
  }

  fun assertNotInMeasure() {
    if (isMeasuring) {
      // If the ComponentTree is updated during measure, the following .layout() call will not run
      // on the ComponentTree that was prepared in measure.
      throw RuntimeException("Cannot update ComponentTree while in the middle of measure")
    }
  }

  var componentTree: ComponentTree?
    @JvmName("getComponentTree") get() = _componentTree
    @JvmName("setComponentTree")
    set(value) {
      setComponentTree(value, true)
    }

  val mountedLayoutState: LayoutState?
    get() {
      val tree = _componentTree
      return tree?.mainThreadLayoutState
    }

  @get:VisibleForTesting
  val rootComponent: Component?
    get() {
      val componentTree = _componentTree
      return componentTree?.root
    }

  fun setOnPostDrawListener(onPostDrawListener: OnPostDrawListener?) {
    this.onPostDrawListener = onPostDrawListener
  }

  open fun setComponentTree(
      componentTree: ComponentTree?,
      unmountAllWhenComponentTreeSetToNull: Boolean,
  ) {
    ThreadUtils.assertMainThread()
    assertNotInMeasure()

    // Cannot set a released ComponentTree
    if (componentTree?.isReleased == true) {
      return
    }

    temporaryDetachedComponentTree = null
    if (this._componentTree === componentTree) {
      if (isAttached) {
        rebind()
      }
      return
    }

    hasNewComponentTree =
        this._componentTree == null ||
            componentTree == null ||
            this._componentTree?.id != componentTree.id
    setMountStateDirty()

    this._componentTree?.let { oldTree ->
      if (componentTree == null && unmountAllWhenComponentTreeSetToNull) {
        unmountAllItems()
      } else if (componentTree != null) {
        onBeforeSettingNewTree()
      }

      if (oldTree.isAttached()) {
        oldTree.detach()
      }

      oldTree.clearLithoView()
    }

    this._componentTree = componentTree

    setupMountExtensions()

    this._componentTree?.let { newTree ->
      check(!newTree.isReleased) {
        ("Setting a released ComponentTree to a LithoView, released component was: ${newTree.getReleasedComponent()}")
      }
      newTree.setLithoView(this)

      if (isAttached) {
        newTree.attach()
      } else {
        requestLayout()
      }
    }
  }

  /** Change the root component synchronously. */
  open fun setComponent(component: Component?) {
    val tree = _componentTree
    if (tree == null) {
      componentTree = ComponentTree.create(componentContext, component).build()
    } else {
      tree.root = component
    }
  }

  /**
   * Change the root component measuring it on a background thread before updating the UI. If this
   * [LithoView] doesn't have a ComponentTree initialized, the root will be computed synchronously.
   */
  open fun setComponentAsync(component: Component?) {
    val tree = _componentTree
    if (tree == null) {
      componentTree = ComponentTree.create(componentContext, component).build()
    } else {
      tree.setRootAsync(component)
    }
  }

  /**
   * @return true if this LithoView has a ComponentTree attached and a
   *   LithoVisibilityEventsController is set on it, false otherwise.
   */
  @Synchronized
  fun componentTreeHasVisibilityEventsController(): Boolean =
      _componentTree?.isSubscribedToVisibilityEventsController() == true

  /**
   * If this LithoView has a ComponentTree attached to it, set a LithoVisibilityEventsController if
   * it doesn't already have one.
   *
   * @return true if the LithoView's ComponentTree was subscribed as listener to the given
   *   LithoVisibilityEventsController, false otherwise.
   */
  @Synchronized
  fun subscribeComponentTreeToVisibilityEventsController(
      controller: LithoVisibilityEventsController,
  ): Boolean {
    val tree = _componentTree ?: return false

    if (tree.isSubscribedToVisibilityEventsController()) {
      return false
    }

    tree.subscribeToVisibilityEventsController(controller)
    return true
  }

  fun setLifecycleOwnerTreePropToComponentTree(lifecycleOwner: LifecycleOwner) {
    _componentTree?.setLifecycleOwnerTreeProp(lifecycleOwner)
  }

  override fun setVisibilityHint(isVisible: Boolean, skipMountingIfNotVisible: Boolean) {
    if (componentTreeHasVisibilityEventsController()) {
      ErrorReporter.report(
          LogLevel.WARNING,
          LITHO_VISIBILITY_EVENTS_CONTROLLER_FOUND,
          "Setting visibility hint but a LithoVisibilityEventsController was found, ignoring.",
      )

      return
    }
    super.setVisibilityHint(isVisible, skipMountingIfNotVisible)
  }

  override fun setVisibilityHint(isVisible: Boolean) {
    if (componentTreeHasVisibilityEventsController()) {
      ErrorReporter.report(
          LogLevel.WARNING,
          LITHO_VISIBILITY_EVENTS_CONTROLLER_FOUND,
          "Setting visibility hint but a LithoVisibilityEventsController was found, ignoring.",
      )

      return
    }
    super.setVisibilityHint(isVisible)
  }

  override fun onAttached() {
    // Not calling super intentionally as in the LithoView case we want ComponentTree to control the
    // rebind logic.
    _componentTree?.attach()

    refreshAccessibilityDelegatesIfNeeded(true)

    if (accessibilityManager != null) {
      AccessibilityManagerCompat.addAccessibilityStateChangeListener(
          accessibilityManager,
          accessibilityStateChangeListener,
      )
    }
  }

  override fun onDetached() {
    super.onDetached()
    _componentTree?.detach()

    if (accessibilityManager != null) {
      AccessibilityManagerCompat.removeAccessibilityStateChangeListener(
          accessibilityManager,
          accessibilityStateChangeListener,
      )
    }

    _suppressMeasureComponentTree = false
  }

  override fun onBeforeMount(): Any? {
    super.onBeforeMount()
    val loggedFirstMount = MountStartupLoggingInfo.maybeLogFirstMountStart(mountStartupLoggingInfo)
    val loggedLastMount =
        MountStartupLoggingInfo.maybeLogLastMountStart(mountStartupLoggingInfo, this)
    return (if (loggedFirstMount) 1 else 0) or (if (loggedLastMount) 2 else 0)
  }

  override fun onAfterMount(fromOnBeforeMount: Any?) {
    super.onAfterMount(fromOnBeforeMount)
    checkNotNull(fromOnBeforeMount) {
      "Should have received wether firs and last mount should be logged"
    }
    val fromBefore = fromOnBeforeMount as Int
    if (isAttachedForTest) {
      dispatchAttachedForTestToChildren()
    }

    if ((fromBefore and 1) != 0) {
      MountStartupLoggingInfo.logFirstMountEnd(mountStartupLoggingInfo)
    }
    if ((fromBefore and 2) != 0) {
      MountStartupLoggingInfo.logLastMountEnd(mountStartupLoggingInfo)
    }
  }

  override fun draw(canvas: Canvas) {
    trace({ "LithoView.draw" }) { drawInternal(canvas) }
  }

  private fun drawInternal(canvas: Canvas) {
    try {
      canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
      super.draw(canvas)
    } catch (t: Throwable) {
      throw LithoMetadataExceptionWrapper(_componentTree, t)
    }

    onPostDrawListener?.onPostDraw()
  }

  override val currentLayoutState: LayoutState?
    get() = _componentTree?.mainThreadLayoutState

  override val treeState: TreeState?
    get() = _componentTree?.uiTreeState

  override val hasTree: Boolean
    get() = _componentTree != null

  override val treeName: String?
    get() = _componentTree?.getSimpleName()

  override val configuration: ComponentsConfiguration?
    get() = _componentTree?.lithoConfiguration?.componentsConfig

  override val isIncrementalMountEnabled: Boolean
    get() = (_componentTree?.isIncrementalMountEnabled == true)

  override val isVisibilityProcessingEnabled: Boolean
    get() = (_componentTree?.isVisibilityProcessingEnabled() == true)

  /**
   * Deprecated: Consider subscribing the LithoView to a [LithoVisibilityEventsController] instead.
   */
  @Deprecated("Subscribe the LithoView to a LithoVisibilityEventsController instead.")
  fun release() {
    ThreadUtils.assertMainThread()
    if (componentTreeHasVisibilityEventsController()) {
      ErrorReporter.report(
          LogLevel.WARNING,
          LITHO_VISIBILITY_EVENTS_CONTROLLER_FOUND,
          "Trying to release a LithoView but a LithoVisibilityEventsController was found, ignoring.",
      )

      return
    }

    val childrenLithoViews = childMountingViewsFromCurrentlyMountedItems
    if (childrenLithoViews != null) {
      for (child in childrenLithoViews) {
        if (child is LithoView) {
          child.release()
        }
      }
    }

    _componentTree?.let { tree ->
      tree.release()
      clearDebugOverlay(this)
      _componentTree = null
    }
    wasMeasured = false

    cleanupDefaultVisibilityController()
  }

  @get:VisibleForTesting
  val dynamicPropsManager: DynamicPropsManager?
    get() {
      val lithoHostListenerCoordinator = lithoHostListenerCoordinator
      return lithoHostListenerCoordinator?.dynamicPropsManager
    }

  fun setMountStartupLoggingInfo(
      startupLogger: LithoStartupLogger,
      startupLoggerAttribution: String,
      firstMountCalled: BooleanArray,
      lastMountCalled: BooleanArray,
      isLastAdapterItem: Boolean,
      isOrientationVertical: Boolean,
  ) {
    mountStartupLoggingInfo =
        MountStartupLoggingInfo(
            startupLogger,
            startupLoggerAttribution,
            firstMountCalled,
            lastMountCalled,
            isLastAdapterItem,
            isOrientationVertical,
        )
  }

  fun resetMountStartupLoggingInfo() {
    mountStartupLoggingInfo = null
  }

  @DoNotStrip
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  fun findTestItems(testKey: String?): Deque<TestItem> {
    val lithoHostListenerCoordinator = lithoHostListenerCoordinator ?: return LinkedList()

    val extension = lithoHostListenerCoordinator.endToEndTestingExtension
    checkNotNull(extension) {
      ("Trying to access TestItems while ComponentsConfiguration.isEndToEndTestRun is false.")
    }

    return extension.findTestItems(testKey)
  }

  private fun dispatchAttachedForTestToChildren() {
    recursivelyDispatchedAttachedForTest(this, isAttachedForTest)
  }

  private class AccessibilityStateChangeListener(lithoView: LithoView) :
      AccessibilityStateChangeListenerCompat() {
    private val lithoView = WeakReference(lithoView)

    override fun onAccessibilityStateChanged(enabled: Boolean) {
      AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()
      val lithoView = lithoView.get() ?: return

      lithoView.rerenderForAccessibility(enabled)
    }
  }

  fun rerenderForAccessibility(enabled: Boolean) {
    refreshAccessibilityDelegatesIfNeeded(enabled)
    // must force (not just request)
    forceRelayout()
  }

  public override fun setVisibilityHintNonRecursive(
      isVisible: Boolean,
      reason: VisibilityChangeReason,
  ) {
    super.setVisibilityHintNonRecursive(isVisible, reason)
  }

  fun initVisibilityHint(
      lithoVisibilityState: LithoVisibilityEventsController.LithoVisibilityState?,
  ) {
    if (lithoVisibilityState != null) {
      if (
          lithoVisibilityState == LithoVisibilityEventsController.LithoVisibilityState.HINT_VISIBLE
      ) {
        setVisibilityHintNonRecursive(true, VisibilityChangeReason.UNKNOWN)
      } else if (
          lithoVisibilityState ==
              LithoVisibilityEventsController.LithoVisibilityState.HINT_INVISIBLE
      ) {
        setVisibilityHintNonRecursive(false, VisibilityChangeReason.UNKNOWN)
      }
    }
  }

  public override fun resetVisibilityHint() {
    super.resetVisibilityHint()
  }

  public override fun performLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.performLayout(changed, l, t, r, b)
  }

  override fun toString(): String =
      // Append only the root component's name; the full component tree is available on demand via
      // LithoViewTestHelper.viewToString.
      super.toString() + (DebugComponent.getRootInstance(this)?.component?.simpleName ?: "")

  override val hostHierarchyMountStateIdentifier: String?
    /**
     * For Litho we will use the root as hint for which is the hierarchy backed by the
     * [com.facebook.rendercore.MountState].
     */
    get() {
      val root = _componentTree?.root
      return if (root != null) CommonUtils.getSectionNameForTracing(root.javaClass) else null
    }

  internal class MountStartupLoggingInfo(
      private val startupLogger: LithoStartupLogger,
      private val startupLoggerAttribution: String,
      private val firstMountLogged: BooleanArray,
      private val lastMountLogged: BooleanArray,
      private val isLastAdapterItem: Boolean,
      private val isOrientationVertical: Boolean,
  ) {
    companion object {
      @JvmStatic
      fun maybeLogFirstMountStart(loggingInfo: MountStartupLoggingInfo?): Boolean {
        if (
            loggingInfo != null &&
                LithoStartupLogger.isEnabled(loggingInfo.startupLogger) &&
                loggingInfo.firstMountLogged != null &&
                !loggingInfo.firstMountLogged[0]
        ) {
          loggingInfo.startupLogger.markPoint(
              LithoStartupLogger.FIRST_MOUNT,
              LithoStartupLogger.START,
              loggingInfo.startupLoggerAttribution,
          )
          return true
        }
        return false
      }

      @JvmStatic
      fun maybeLogLastMountStart(
          loggingInfo: MountStartupLoggingInfo?,
          lithoView: LithoView,
      ): Boolean {
        if (
            loggingInfo != null &&
                LithoStartupLogger.isEnabled(loggingInfo.startupLogger) &&
                loggingInfo.firstMountLogged != null &&
                loggingInfo.firstMountLogged[0] &&
                loggingInfo.lastMountLogged != null &&
                !loggingInfo.lastMountLogged[0]
        ) {
          val parent = lithoView.parent as ViewGroup? ?: return false

          if (
              loggingInfo.isLastAdapterItem ||
                  (if (loggingInfo.isOrientationVertical)
                      (lithoView.bottom >= parent.height - parent.paddingBottom)
                  else (lithoView.right >= parent.width - parent.paddingRight))
          ) {
            loggingInfo.startupLogger.markPoint(
                LithoStartupLogger.LAST_MOUNT,
                LithoStartupLogger.START,
                loggingInfo.startupLoggerAttribution,
            )
            return true
          }
        }
        return false
      }

      @JvmStatic
      fun logFirstMountEnd(loggingInfo: MountStartupLoggingInfo?) {
        if (loggingInfo == null) {
          return
        }
        loggingInfo.startupLogger.markPoint(
            LithoStartupLogger.FIRST_MOUNT,
            LithoStartupLogger.END,
            loggingInfo.startupLoggerAttribution,
        )
        loggingInfo.firstMountLogged[0] = true
      }

      @JvmStatic
      fun logLastMountEnd(loggingInfo: MountStartupLoggingInfo?) {
        if (loggingInfo == null) {
          return
        }
        loggingInfo.startupLogger.markPoint(
            LithoStartupLogger.LAST_MOUNT,
            LithoStartupLogger.END,
            loggingInfo.startupLoggerAttribution,
        )
        loggingInfo.lastMountLogged[0] = true
      }
    }
  }

  fun interface OnPostDrawListener {
    fun onPostDraw()
  }

  companion object {
    private const val LITHO_VISIBILITY_EVENTS_CONTROLLER_FOUND =
        "lithoView:LithoVisibilityEventsControllerFound"

    const val VISIBLE = View.VISIBLE

    private val layoutSize = IntArray(2)

    /**
     * Create a new [LithoView] instance and initialize it with the given [Component] root.
     *
     * @param context Android [Context].
     * @param component The root component to draw.
     * @return [LithoView] able to render a [Component] hierarchy.
     */
    @JvmStatic
    fun create(context: Context, component: Component?): LithoView {
      val lithoView = LithoView(context)
      lithoView.componentTree = ComponentTree.create(ComponentContext(context), component).build()
      return lithoView
    }

    @JvmStatic
    fun create(
        context: Context,
        component: Component?,
        controller: LithoVisibilityEventsController,
        lifecycleOwner: LifecycleOwner?,
    ): LithoView = create(ComponentContext(context), component, controller, lifecycleOwner)

    /**
     * Create a new [LithoView] instance and initialize it with the given [Component] root.
     *
     * @param context [ComponentContext].
     * @param component The root component to draw.
     * @return [LithoView] able to render a [Component] hierarchy.
     */
    @JvmStatic
    fun create(context: ComponentContext, component: Component?): LithoView {
      val lithoView = LithoView(context)
      lithoView.componentTree = ComponentTree.create(context, component).build()
      return lithoView
    }

    /**
     * Creates a new LithoView and sets a new ComponentTree on it. The ComponentTree is subscribed
     * to the given LithoVisibilityEventsController instance.
     */
    @JvmStatic
    fun create(
        context: ComponentContext,
        component: Component?,
        controller: LithoVisibilityEventsController,
        lifecycleOwner: LifecycleOwner?,
    ): LithoView {
      val lithoView = LithoView(context)
      lithoView.componentTree =
          ComponentTree.create(context, component)
              .withLithoVisibilityEventsController(controller)
              .withLifecycleOwnerTreeProp(lifecycleOwner)
              .build()
      return lithoView
    }

    /** Create a new [LithoView] instance and initialize it with a custom [ComponentTree]. */
    @JvmStatic
    fun create(context: Context, componentTree: ComponentTree): LithoView =
        create(ComponentContext(context), componentTree)

    /** Create a new [LithoView] instance and initialize it with a custom [ComponentTree]. */
    @JvmStatic
    fun create(context: ComponentContext, componentTree: ComponentTree): LithoView {
      val lithoView = LithoView(context)
      lithoView.componentTree = componentTree
      return lithoView
    }

    private fun adjustMeasureSpecForPadding(measureSpec: Int, padding: Int): Int {
      val mode = MeasureSpec.getMode(measureSpec)
      if (mode == MeasureSpec.UNSPECIFIED) {
        return measureSpec
      }
      val size = max(0, MeasureSpec.getSize(measureSpec) - padding)
      return MeasureSpec.makeMeasureSpec(size, mode)
    }

    private fun recursivelyDispatchedAttachedForTest(
        viewGroup: ViewGroup,
        isAttachedForTest: Boolean,
    ) {
      for (i in 0..<viewGroup.childCount) {
        val child = viewGroup.getChildAt(i)
        if (child is LithoView) {
          if (isAttachedForTest) {
            child.onAttachedToWindowForTest()
          } else {
            child.onDetachedFromWindowForTest()
          }
        } else if (child is ViewGroup) {
          recursivelyDispatchedAttachedForTest(child, isAttachedForTest)
        }
      }
    }
  }
}
