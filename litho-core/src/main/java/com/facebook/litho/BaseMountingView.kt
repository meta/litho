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
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.collection.LongObjectMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.mutableLongObjectMapOf
import androidx.collection.mutableScatterSetOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.facebook.infer.annotation.ThreadConfined
import com.facebook.litho.ComponentUtils.wrapWithMetadata
import com.facebook.litho.ComponentsSystrace.systrace
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.LithoMountData.Companion.getViewAttributeFlags
import com.facebook.litho.LithoViewTestHelper.toDebugString
import com.facebook.litho.ThreadUtils.assertMainThread
import com.facebook.litho.Transition.RootBoundsTransition
import com.facebook.litho.TreeState.TreeMountInfo
import com.facebook.litho.animation.AnimatedProperties
import com.facebook.litho.animation.AnimatedProperty
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.UnmountOnDetach
import com.facebook.litho.debug.DebugOverlay
import com.facebook.litho.debug.DebugOverlay.Companion.getDebugOverlay
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.debug.formatMountSummaryByComponent
import com.facebook.litho.state.BinderUpdateTracer
import com.facebook.litho.state.StateId
import com.facebook.litho.state.UiStateReadRecords
import com.facebook.litho.stats.LithoStats.incrementComponentMountCount
import com.facebook.litho.utils.IncrementalMountUtils
import com.facebook.rendercore.BinderId
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.MountDelegateTarget
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderCoreExtensionHost
import com.facebook.rendercore.RenderTreeUpdateListener
import com.facebook.rendercore.debug.DebugEventDispatcher
import com.facebook.rendercore.extensions.RenderCoreExtension.Companion.onRegisterForPremount
import com.facebook.rendercore.extensions.RenderCoreExtension.Companion.onUnregisterForPremount
import com.facebook.rendercore.incrementalmount.GapWorkerMode
import com.facebook.rendercore.transitions.AnimatedRootHost
import com.facebook.rendercore.visibility.CachedVisibleRectProvider
import com.facebook.rendercore.visibility.FocusedVisibleEvent
import com.facebook.rendercore.visibility.FullImpressionVisibleEvent
import com.facebook.rendercore.visibility.InvisibleEvent
import com.facebook.rendercore.visibility.UnfocusedVisibleEvent
import com.facebook.rendercore.visibility.VisibilityChangeReason
import com.facebook.rendercore.visibility.VisibilityExtensionConfigs
import com.facebook.rendercore.visibility.VisibilityMountExtension.Companion.isVisibleRectStillWholeOrCovered
import com.facebook.rendercore.visibility.VisibilityMountExtension.VisibilityMountExtensionState
import com.facebook.rendercore.visibility.VisibilityOutput
import com.facebook.rendercore.visibility.VisibilityUtils.dispatchOnFocused
import com.facebook.rendercore.visibility.VisibilityUtils.dispatchOnFullImpression
import com.facebook.rendercore.visibility.VisibilityUtils.dispatchOnInvisible
import com.facebook.rendercore.visibility.VisibilityUtils.dispatchOnUnfocused
import com.facebook.rendercore.visibility.VisibilityUtils.dispatchOnVisible
import com.facebook.rendercore.visibility.VisibleEvent
import java.util.ArrayDeque
import java.util.Deque

abstract class BaseMountingView
@JvmOverloads
constructor(context: ComponentContext, attrs: AttributeSet? = null) :
    ComponentHost(context.androidContext, attrs, unsafeModificationPolicy = null),
    RenderCoreExtensionHost,
    AnimatedRootHost,
    UiStateReadRecords.Config {

  abstract val configuration: ComponentsConfiguration?

  abstract val isIncrementalMountEnabled: Boolean

  abstract val currentLayoutState: LayoutState?

  protected abstract val isVisibilityProcessingEnabled: Boolean

  protected abstract val treeState: TreeState?

  protected abstract val hasTree: Boolean

  val viewAttributeFlags: Int

  val previousMountBounds: Rect = Rect()

  /**
   * Whether the pass that set [previousMountBounds] also processed visibility, and the extensions
   * still hold what it found. [isFullyShownAndMounted] and [isTransformStillWholeOrCovered] read
   * it: full bounds alone don't show that `onVisible` was dispatched for them.
   */
  private var isVisibilityProcessedForMountBounds = false

  /**
   * The bounds of the mounted tree's nested trees, united, in this view's coordinates. Only a view
   * whose transformer records at every pass keeps them, for [isTransformStillWholeOrCovered].
   */
  private val nestedTreeBounds = Rect()

  @get:VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  val mountDelegateTarget: MountDelegateTarget
    get() = mountState

  @get:VisibleForTesting
  val childMountingViewsFromCurrentlyMountedItems: List<BaseMountingView>
    get() = getChildMountingViewsFromCurrentlyMountedItems(mountState)

  var isAttached: Boolean = false
    private set

  var isMountStateDirty: Boolean = false
    private set

  var isMounting: Boolean = false
    private set

  protected var lifecycleOwner: LifecycleOwner? = null
    private set

  @JvmField protected var animatedWidth: Int = SIZE_UNSET

  @JvmField protected var animatedHeight: Int = SIZE_UNSET

  protected var lithoHostListenerCoordinator: LithoHostListenerCoordinator? = null
    private set

  // This is a flag to indicate that the BaseMountingView is currently being detached temporarily,
  // like when disappearing transition happens we will detach the view and re-attach to its skip
  // root.
  protected var isTemporaryDetached: Boolean = false

  protected val mountInfo: TreeMountInfo?
    get() {
      val treeState = this.treeState
      return treeState?.mountInfo
    }

  protected open val treeName: String?
    get() {
      val layoutState = currentLayoutState

      return layoutState?.rootName ?: ""
    }

  override val isReadTrackingEnabled: Boolean
    get() = configuration?.enableStateReadTracking == true

  private var onDirtyMountListener: OnDirtyMountListener? = null

  @get:JvmName("getUiStateReadRecords")
  internal val uiStateReadRecords: UiStateReadRecords =
      UiStateReadRecords(this, BinderUpdateTracer())

  private val mountState = MountState(this, systrace, uiStateReadRecords)

  private var reentrantMounts: Deque<ReentrantMount>? = null

  private var transientStateCount = 0

  private var hasTransientState = false

  private var hasVisibilityHint = false

  private var pauseMountingWhileVisibilityHintFalse = false

  private var visibilityHintIsVisible = false

  private var skipMountingIfNotVisible = false

  /**
   * Tracks the most recent non-UNKNOWN visibility change reason. Used to propagate the correct
   * reason when the view is detached (e.g., RecyclerView recycling a child after scroll).
   */
  private var lastVisibilityReason: VisibilityChangeReason = VisibilityChangeReason.UNKNOWN

  private val rect = Rect()

  @JvmOverloads
  constructor(
      context: Context,
      attrs: AttributeSet? = null,
  ) : this(ComponentContext(context), attrs)

  init {
    viewAttributeFlags = getViewAttributeFlags(this)
  }

  override fun currentTreeId(): Int = currentLayoutState?.treeId ?: ComponentTree.INVALID_ID

  /**
   * Sets the width that the BaseMountingView should take on the next measure pass and then requests
   * a layout. This should be called from animation-driving code on each frame to animate the size
   * of the BaseMountingView.
   */
  override fun setAnimatedWidth(width: Int) {
    animatedWidth = width
    requestLayout()
  }

  override fun performLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    trace(
        {
          "BaseMountingView.performLayout[${currentLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
        },
    ) {
      if (hasTree) {
        onBeforeLayout(l, t, r, b)

        val wasMountTriggered = mountComponentIfNeeded()

        // If this happens the LithoView might have moved on Screen without a scroll event
        // triggering incremental mount. We trigger one here to be sure all the content is visible.
        if (!wasMountTriggered && !IncrementalMountUtils.skipMountOnLayoutDuringScroll) {
          notifyVisibleBoundsChanged(VisibilityChangeReason.MOUNT)
        }

        if (!wasMountTriggered || shouldAlwaysLayoutChildren()) {
          // If the layout() call on the component didn't trigger a mount step,
          // we might need to perform an inner layout traversal on children that
          // requested it as certain complex child views (e.g. ViewPager,
          // RecyclerView, etc) rely on that.
          performLayoutOnChildrenIfNecessary(this)
        }
      }
    }
  }

  /**
   * Indicates if the children of this view should be laid regardless to a mount step being
   * triggered on layout. This step can be important when some of the children in the hierarchy are
   * changed (e.g. resized) but the parent wasn't.
   *
   * Since the framework doesn't expect its children to resize after being mounted, this should be
   * used only for extreme cases where the underline views are complex and need this behavior.
   *
   * @return boolean Returns true if the children of this view should be laid out even when a mount
   *   step was not needed.
   */
  protected open fun shouldAlwaysLayoutChildren(): Boolean {
    return false
  }

  protected open fun onBeforeLayout(left: Int, top: Int, right: Int, bottom: Int) = Unit

  protected abstract fun onLifecycleOwnerChanged(
      previousLifecycleOwner: LifecycleOwner?,
      currentLifecycleOwner: LifecycleOwner?,
  )

  @SuppressLint("LambdaEquality")
  private fun attachLifecycleOwner() {
    val lifecycleOwner = getDefaultLifecycleOwner(this)
    if (lifecycleOwner != null && this.lifecycleOwner != lifecycleOwner) {
      val previousLifecycleOwner = this.lifecycleOwner
      this.lifecycleOwner = lifecycleOwner
      onLifecycleOwnerChanged(previousLifecycleOwner, this.lifecycleOwner)
    }
  }

  private fun detachLifecycleOwner() {
    if (lifecycleOwner != null) {
      val previousLifecycleOwner = lifecycleOwner
      lifecycleOwner = null
      onLifecycleOwnerChanged(previousLifecycleOwner, null)
    }
  }

  /**
   * Invoke this before the result of currentLayoutState is about to change to a new non-null tree.
   */
  fun onBeforeSettingNewTree() {
    clearVisibilityItems(VisibilityChangeReason.MOUNT)
    clearLastMountedTree()
  }

  public override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    onAttach()
  }

  public override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    onDetach()
  }

  override fun onStartTemporaryDetach() {
    isTemporaryDetached = true
    super.onStartTemporaryDetach()
    onDetach()
  }

  override fun onFinishTemporaryDetach() {
    isTemporaryDetached = false
    super.onFinishTemporaryDetach()
    onAttach()
  }

  private fun onAttach() {
    if (!isAttached) {
      isAttached = true
      attachLifecycleOwner()
      onAttached()
    }
  }

  protected open fun onAttached() {
    mountState.attach()
  }

  private fun onDetach() {
    if (isAttached) {
      isAttached = false
      onDetached()
      detachLifecycleOwner()
    }
  }

  fun rebind() {
    mountState.attach()
  }

  /**
   * To be called this when the LithoView is about to become inactive. This means that either the
   * view is about to be recycled or moved off-screen.
   */
  fun unbind() {
    mountState.detach()
    isVisibilityProcessedForMountBounds = false
  }

  protected open fun onDetached() {
    maybeUnmountComponents()
    mountState.detach()
    isVisibilityProcessedForMountBounds = false
  }

  private fun maybeUnmountComponents() {
    if (
        unmountsOnDetach &&
            !isTemporaryDetached &&
            !hasTransientState() &&
            isIncrementalMountEnabled
    ) {
      notifyVisibleBoundsChanged(EMPTY_RECT, true, lastVisibilityReason)
    }
  }

  /**
   * Sets the height that the BaseMountingView should take on the next measure pass and then
   * requests a layout. This should be called from animation-driving code on each frame to animate
   * the size of the BaseMountingView.
   */
  override fun setAnimatedHeight(height: Int) {
    animatedHeight = height
    requestLayout()
  }

  override fun hasTransientState(): Boolean {
    return if (ComponentsConfiguration.shouldOverrideHasTransientState) {
      hasTransientState
    } else {
      super.hasTransientState()
    }
  }

  override fun setHasTransientState(hasTransientState: Boolean) {
    super.setHasTransientState(hasTransientState)

    if (hasTransientState) {
      if (transientStateCount == 0 && hasTree) {
        notifyVisibleBoundsChanged(Rect(0, 0, width, height), false, VisibilityChangeReason.UNKNOWN)
      }
      if (transientStateCount == 0) {
        this.hasTransientState = true
      }
      transientStateCount++
    } else {
      transientStateCount--
      if (transientStateCount == 0) {
        this.hasTransientState = false
      }
      if (transientStateCount == 0 && hasTree) {
        // We mounted everything when the transient state was set on this view. We need to do this
        // partly to unmount content that is not visible but mostly to get the correct visibility
        // events to be fired.
        notifyVisibleBoundsChanged(VisibilityChangeReason.ANIMATION)
      }
      if (transientStateCount < 0) {
        transientStateCount = 0
      }
    }
  }

  override fun notifyVisibleBoundsChanged(
      visibleRect: Rect,
      processVisibilityOutputs: Boolean,
      reason: VisibilityChangeReason,
  ) {
    if (currentLayoutState == null) {
      return
    }

    if (reason != VisibilityChangeReason.UNKNOWN) {
      lastVisibilityReason = reason
    }

    trace(
        {
          "mount.notifyVisibleBoundsChangedWithRect[${currentLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
        },
    ) {
      if (isIncrementalMountEnabled) {
        mountComponent(visibleRect, processVisibilityOutputs, reason)
      } else if (processVisibilityOutputs) {
        processVisibilityOutputs(visibleRect, reason)
      }
    }
  }

  @UiThread
  fun mountComponent(currentVisibleArea: Rect?, processVisibilityOutputs: Boolean) {
    mountComponent(currentVisibleArea, processVisibilityOutputs, VisibilityChangeReason.MOUNT)
  }

  @UiThread
  internal fun mountComponent(
      currentVisibleArea: Rect?,
      processVisibilityOutputs: Boolean,
      reason: VisibilityChangeReason,
  ) {
    assertMainThread()

    if (isMounting) {
      collectReentrantMount(ReentrantMount(currentVisibleArea, processVisibilityOutputs, reason))
      return
    }

    maybeMountOrNotifyVisibleBoundsChange(currentVisibleArea, processVisibilityOutputs, reason)

    consumeReentrantMounts()
  }

  fun setMountStateDirty() {
    isMountStateDirty = true
    previousMountBounds.setEmpty()
  }

  /**
   * Deprecated: Consider subscribing the ComponentTree to a [LithoVisibilityEventsController]
   * instead.
   */
  @Deprecated("Subscribe the ComponentTree to a LithoVisibilityEventsController instead.")
  fun unmountAllItems() {
    mountState.unmountAllItems()
    lithoHostListenerCoordinator = null
    previousMountBounds.setEmpty()
  }

  fun mountStateNeedsRemount(): Boolean {
    return mountState.needsRemount()
  }

  private fun collectReentrantMount(reentrantMount: ReentrantMount) {
    val mounts = reentrantMounts
    if (mounts == null) {
      reentrantMounts = ArrayDeque()
    } else if (mounts.size > REENTRANT_MOUNTS_MAX_ATTEMPTS) {
      logReentrantMountsExceedMaxAttempts()
      mounts.clear()
      return
    }
    requireNotNull(reentrantMounts).add(reentrantMount)
  }

  private fun consumeReentrantMounts() {
    if (reentrantMounts != null) {
      val requests: Deque<ReentrantMount> = ArrayDeque(reentrantMounts)
      requireNotNull(reentrantMounts).clear()

      while (!requests.isEmpty()) {
        val request = requireNotNull(requests.pollFirst())
        setMountStateDirty()
        mount(request.currentVisibleArea, request.processVisibilityOutputs, request.reason)
      }
    }
  }

  private fun maybeMountOrNotifyVisibleBoundsChange(
      actualVisibleRect: Rect?,
      requestVisibilityEvents: Boolean,
      reason: VisibilityChangeReason,
  ) {
    val layoutState = currentLayoutState ?: return

    if (shouldPauseMountingWithVisibilityHintFalse()) {
      return
    }

    val visibleRectToUse: Rect?
    val processVisibilityOutputs: Boolean
    if (transientStateCount > 0 && hasTree && isIncrementalMountEnabled) {
      // If transient state is set but the MountState is dirty we want to re-mount everything.
      // Otherwise, we don't need to do anything as the entire BaseMountingView was mounted when
      // the transient state was set.
      if (!isMountStateDirty) {
        return
      } else {
        visibleRectToUse = Rect(0, 0, width, height)
        processVisibilityOutputs = false
      }
    } else {
      visibleRectToUse = actualVisibleRect
      processVisibilityOutputs = requestVisibilityEvents
    }

    if (visibleRectToUse == null) {
      previousMountBounds.setEmpty()
    } else {
      previousMountBounds.set(visibleRectToUse)
    }
    isVisibilityProcessedForMountBounds = visibleRectToUse != null && processVisibilityOutputs

    val needsMount = isMountStateDirty || mountStateNeedsRemount()
    if (visibleRectToUse != null && !needsMount) {
      try {
        isMounting = true
        layoutState.setShouldProcessVisibilityOutputs(processVisibilityOutputs)
        requireNotNull(mountState.getMountDelegate())
            .notifyVisibleBoundsChanged(visibleRectToUse, reason)
      } finally {
        isMounting = false
      }
    } else {
      mount(visibleRectToUse, processVisibilityOutputs, reason)
    }
  }

  private fun mount(
      visibleRect: Rect?,
      shouldProcessVisibilityEvents: Boolean,
      reason: VisibilityChangeReason,
      isProbe: Boolean = false,
  ) {
    val layoutState = currentLayoutState ?: return
    if (shouldPauseMountingWithVisibilityHintFalse()) {
      return
    }

    val isMountStateDirty = isMountStateDirty
    try {
      // If this is the first mount, we need to set the hasMounted flag on the TreeState.

      // A probe mounts content the user cannot see, so it must not consume the first mount: that
      // would suppress the root appear transition the real first mount is supposed to run.
      val mountInfo = mountInfo
      if (mountInfo != null && !mountInfo.hasMounted && !isProbe) {
        mountInfo.isFirstMount = true
        mountInfo.hasMounted = true
      }

      isMounting = true

      val updatedStates = treeState?.consumeMountUpdates() ?: emptySet()
      val renderTree = layoutState.toRenderTree()
      val dirtyBinders = uiStateReadRecords.getBindersToRebind(updatedStates)
      setupMountExtensions()
      val coordinator = requireNotNull(lithoHostListenerCoordinator)

      val treeChanged = mountState.willRemountWith(renderTree)
      if (treeChanged) {
        val onBeforeMountResult = onBeforeMount()

        layoutState.setShouldProcessVisibilityOutputs(shouldProcessVisibilityEvents)

        coordinator.beforeMount(layoutState, visibleRect)
        updateNestedTreeBounds(layoutState)

        mountState.mount(renderTree, dirtyBinders, reason)
        incrementComponentMountCount()
        drawDebugOverlay(this, layoutState.componentTreeId)

        onAfterMount(onBeforeMountResult)

        // Dispatch post-mount attributes for phase logging correlation
        DebugEventDispatcher.dispatch(
            type = LithoDebugEvent.MountEnd,
            renderStateId = { layoutState.componentTreeId.toString() },
        ) { attrs ->
          attrs[LithoDebugEventAttributes.LayoutVersion] = layoutState.version
          mountState.mountStats?.let { stats ->
            attrs[LithoDebugEventAttributes.SummaryByComponent] =
                formatMountSummaryByComponent(stats.summaryByComponent)
          }
        }

        val treeState = this.treeState
        if (isMountStateDirty && treeState != null) {
          layoutState.recordRenderData()
          treeState.recordRenderData(layoutState)
        }
      } else if (dirtyBinders.isNotEmpty()) {
        // Fast path: tree is unchanged, only specific binders need rebinding.
        // Use targeted O(K) rebindBinders instead of O(N) mount traversal.
        val groupedBinders = mutableLongObjectMapOf<MutableScatterSet<BinderId>>()
        dirtyBinders.forEach { binderId ->
          groupedBinders.getOrPut(binderId.renderUnitId) { mutableScatterSetOf() }.add(binderId)
        }
        @Suppress("UNCHECKED_CAST")
        mountState.rebindBinders(groupedBinders as LongObjectMap<ScatterSet<BinderId>>)
      }
      val dirtyHosts = uiStateReadRecords.getContentIdsToInvalidate(updatedStates)
      if (dirtyHosts.isNotEmpty()) {
        mountState.invalidateHosts(dirtyHosts)
      }
    } catch (e: Exception) {
      throw wrapWithMetadata(this, e)
    } finally {
      mountInfo?.isFirstMount = false
      this.isMountStateDirty = false
      isMounting = false
      if (isMountStateDirty) {
        onDirtyMountComplete()
      }
    }
  }

  /**
   * Rebinds binders and invalidates hosts that read [dirtyStates]; returns true if anything was
   * applied.
   */
  fun applyChanges(dirtyStates: Set<StateId>): Boolean {
    val binders = uiStateReadRecords.getBindersToRebind(dirtyStates)
    val dirtyHosts = uiStateReadRecords.getContentIdsToInvalidate(dirtyStates)
    if (binders.isEmpty() && dirtyHosts.isEmpty()) {
      return false
    }

    if (binders.isNotEmpty()) {
      val dirtyBinders = mutableLongObjectMapOf<MutableScatterSet<BinderId>>()
      binders.forEach { binderId ->
        dirtyBinders.getOrPut(binderId.renderUnitId) { mutableScatterSetOf() }.add(binderId)
      }
      mountState.rebindBinders(dirtyBinders as LongObjectMap<ScatterSet<BinderId>>)
    }
    if (dirtyHosts.isNotEmpty()) {
      mountState.invalidateHosts(dirtyHosts)
    }
    return true
  }

  /**
   * Called before the mounting process actually happens on this view. It can return an Object that
   * will be passed in as param to onAfterMount
   *
   * @return an object that will be passed to onAfterMount
   */
  open fun onBeforeMount(): Any? {
    return null
  }

  /**
   * Called right after the mountProcess is finished before resetting the dirtyMount flag.
   *
   * @param fromOnBeforeMount this is whatever was returned by the onBeforMount call. The default is
   *   null.
   */
  open fun onAfterMount(fromOnBeforeMount: Any?) = Unit

  // We pause mounting while the visibility hint is set to false, because the visible rect of
  // the BaseMountingView is not consistent with what's currently on screen.
  private fun shouldPauseMountingWithVisibilityHintFalse(): Boolean {
    return (pauseMountingWhileVisibilityHintFalse && hasVisibilityHint && !visibilityHintIsVisible)
  }

  /**
   * Dispatch a visibility events to all the components hosted in this BaseMountingView.
   *
   * Marked as @Deprecated to indicate this method is experimental and should not be widely used.
   *
   * NOTE: Can only be used when Incremental Mount is disabled! Call this method when the
   * BaseMountingView is considered eligible for the visibility event (i.e. only dispatch
   * VisibleEvent when the BaseMountingView is visible in its container).
   *
   * @param visibilityEventType The class type of the visibility event to dispatch. Supported:
   *   VisibleEvent.class, InvisibleEvent.class, FocusedVisibleEvent.class,
   *   UnfocusedVisibleEvent.class, FullImpressionVisibleEvent.class.
   */
  @Deprecated("Experimental: only valid when incremental mount is disabled. Avoid new usages.")
  fun dispatchVisibilityEvent(visibilityEventType: Class<*>?) {
    check(!isIncrementalMountEnabled) {
      ("dispatchVisibilityEvent - " +
          "Can't manually trigger visibility events when incremental mount is enabled")
    }

    val layoutState = currentLayoutState

    if (layoutState != null && visibilityEventType != null) {
      for (i in 0 until layoutState.visibilityOutputCount) {
        dispatchVisibilityEvent(layoutState.getVisibilityOutputAt(i), visibilityEventType)
      }

      val childViews = childMountingViewsFromCurrentlyMountedItems
      for (baseMountingView in childViews) {
        baseMountingView.dispatchVisibilityEvent(visibilityEventType)
      }
    }
  }

  private fun dispatchVisibilityEvent(
      visibilityOutput: VisibilityOutput,
      visibilityEventType: Class<*>,
  ) {
    val content =
        if (visibilityOutput.hasMountableContent)
            mountState.getContentById(visibilityOutput.renderUnitId)
        else null
    if (visibilityEventType == VisibleEvent::class.java) {
      if (visibilityOutput.onVisible != null) {
        dispatchOnVisible(
            requireNotNull(visibilityOutput.onVisible).callback,
            content,
            VisibilityChangeReason.UNKNOWN,
        )
      }
    } else if (visibilityEventType == InvisibleEvent::class.java) {
      if (visibilityOutput.onInvisible != null) {
        dispatchOnInvisible(
            requireNotNull(visibilityOutput.onInvisible).callback,
            VisibilityChangeReason.UNKNOWN,
        )
      }
    } else if (visibilityEventType == FocusedVisibleEvent::class.java) {
      if (visibilityOutput.onFocusedVisible != null) {
        dispatchOnFocused(
            requireNotNull(visibilityOutput.onFocusedVisible).callback,
            VisibilityChangeReason.UNKNOWN,
        )
      }
    } else if (visibilityEventType == UnfocusedVisibleEvent::class.java) {
      if (visibilityOutput.onUnfocusedVisible != null) {
        dispatchOnUnfocused(
            requireNotNull(visibilityOutput.onUnfocusedVisible).callback,
            VisibilityChangeReason.UNKNOWN,
        )
      }
    } else if (visibilityEventType == FullImpressionVisibleEvent::class.java) {
      if (visibilityOutput.onFullImpression != null) {
        dispatchOnFullImpression(
            requireNotNull(visibilityOutput.onFullImpression).callback,
            content,
            VisibilityChangeReason.UNKNOWN,
        )
      }
    }
  }

  @Synchronized
  fun setOnDirtyMountListener(onDirtyMountListener: OnDirtyMountListener?) {
    this.onDirtyMountListener = onDirtyMountListener
  }

  @Synchronized
  protected fun onDirtyMountComplete() {
    onDirtyMountListener?.onDirtyMount(this)
  }

  /** @return Whether the current Litho tree has been mounted at least once. */
  protected fun hasMountedAtLeastOnce(): Boolean {
    val mountInfo = mountInfo
    return mountInfo != null && mountInfo.hasMounted
  }

  private fun logReentrantMountsExceedMaxAttempts() {
    val message =
        ("Reentrant mounts exceed max attempts" +
            ", view=" +
            toDebugString(this) +
            ", component=" +
            (if (hasTree) treeName else null))
    // Changed from FATAL to ERROR to prevent crashes during DebugInfoReporter migration
    ErrorReporter.report(LogLevel.ERROR, REENTRANT_MOUNTS_EXCEED_MAX_ATTEMPTS, message)
  }

  override fun notifyVisibleBoundsChanged(reason: VisibilityChangeReason) {
    if (currentLayoutState == null) {
      return
    }

    trace("mount.notifyVisibleBoundsChanged") { notifyVisibleBoundsChanged(null, reason) }
  }

  override fun onRegisterForPremount(frameTimeMs: Long?) {
    val config = configuration
    if (config != null && config.gapWorkerMode != GapWorkerMode.OFF) {
      trace(
          {
            "mount.onRegisterForPremount[${currentLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
          },
      ) {
        mount(Rect(), false, VisibilityChangeReason.MOUNT)
        onRegisterForPremount(mountState, frameTimeMs)
      }
    }
  }

  override fun onUnregisterForPremount() {
    val config = configuration
    if (config != null && config.gapWorkerMode != GapWorkerMode.OFF) {
      trace(
          {
            "mount.onUnregisterForPremount[${currentLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
          },
      ) {
        onUnregisterForPremount(mountState)
      }
    }
  }

  override fun setRenderTreeUpdateListener(listener: RenderTreeUpdateListener?) {
    mountState.setRenderTreeUpdateListener(listener)
  }

  /**
   * If true, calling [.setVisibilityHint] will delegate to [ ][.setVisibilityHint] and skip
   * mounting if the visibility hint was set to false. You should not need this unless you don't
   * have control over calling setVisibilityHint on the BaseMountingView you own.
   */
  fun setSkipMountingIfNotVisible(skipMountingIfNotVisible: Boolean) {
    assertMainThread()
    this.skipMountingIfNotVisible = skipMountingIfNotVisible
  }

  @UiThread
  fun notifyVisibleBoundsChanged(
      rect: Rect?,
      reason: VisibilityChangeReason,
  ) {
    assertMainThread()

    val config = configuration
    if (!hasTree || (unmountsOnDetach && rect == previousMountBounds)) {
      return
    }

    val visibleRectToUse: Rect
    val areBoundsVisible: Boolean
    if (rect == null) {
      // As [View.getLocalVisibleRect] on detach can return a value which cannot be for used for
      // processing visibility. For example, a visible Rect can be returned even when it's totally
      // invisible. This can cause components to not dispatch the correct visibility events
      // correctly or not get unmounted by incremental mount. We manually invokes the visible bounds
      // change call with the appropriate visible rect when the view is getting attached or
      // detached.
      val actualVisibleRect = Rect()
      areBoundsVisible = getLocalVisibleRect(actualVisibleRect)
      visibleRectToUse = actualVisibleRect
      if (
          areBoundsVisible &&
              config?.skipMountWhenFullyShownAndMounted == true &&
              isFullyShownAndMounted(actualVisibleRect)
      ) {
        return
      }
    } else {
      // Since we don't have a reliable way to determine if the current view is visible or not, we
      // have to use an empty rect to unmount all components.
      areBoundsVisible = false
      visibleRectToUse = Rect(rect)
    }

    if (
        areBoundsVisible ||
            (unmountsOnDetach && (rect != null && rect.isEmpty)) ||
            hasComponentsExcludedFromIncrementalMount(
                currentLayoutState,
            ) // It might not be yet visible but animating from 0
            // height/width in
            // which case we still
            // need to mount them to trigger animation.
            || animatingRootBoundsFromZero(visibleRectToUse)
    ) {
      mountComponent(visibleRectToUse, true, reason)
    }
  }

  /**
   * Whether [visibleRect] covers all of this view and the last mount already processed exactly that
   * rect, so mounting again can't mount, unmount or dispatch anything. A visibility transformer can
   * redefine the bounds without the view moving, so a view with one is never skipped.
   */
  private fun isFullyShownAndMounted(visibleRect: Rect): Boolean =
      !visibleRect.isEmpty &&
          visibleRect.left == 0 &&
          visibleRect.top == 0 &&
          visibleRect.right == width &&
          visibleRect.bottom == height &&
          visibleRect == previousMountBounds &&
          isVisibilityProcessedForMountBounds &&
          configuration?.visibilityBoundsTransformer == null &&
          !mountStateNeedsRemount()

  // This is used to detect if the rect is visible or not, more details could be found at here:
  // https://developer.android.com/reference/android/view/View#getLocalVisibleRect(android.graphics.Rect)
  private fun isRectVisible(rect: Rect): Boolean {
    val width = measuredWidth
    val height = measuredHeight

    if (width == 0 || height == 0) {
      // It means the view is not measured yet.
      return false
    }

    if (rect.isEmpty) {
      return false
    }

    if ((rect.left < 0 && rect.right <= 0) || (rect.top < 0 && rect.bottom <= 0)) {
      // It means the view is outside of left or top of the screen.
      return false
    }

    if (
        (rect.left >= width && rect.right > width) || (rect.top >= height && rect.bottom > height)
    ) {
      // It means the view is outside of right or bottom of the screen.
      return false
    }

    return true
  }

  private fun animatingRootBoundsFromZero(currentVisibleArea: Rect): Boolean {
    val layoutState = currentLayoutState

    return (hasTree && !hasMountedAtLeastOnce()) &&
        layoutState != null &&
        ((layoutState.rootHeightAnimation != null && currentVisibleArea.height() == 0) ||
            (layoutState.rootWidthAnimation != null && currentVisibleArea.width() == 0))
  }

  @VisibleForTesting
  fun processVisibilityOutputs(
      currentVisibleArea: Rect?,
      reason: VisibilityChangeReason,
  ) {
    if (currentLayoutState == null || !isVisibilityProcessingEnabled) {
      return
    }

    trace(
        {
          "BaseMountingView.processVisibilityOutputs[${currentLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
        },
    ) {
      val layoutState = currentLayoutState

      if (layoutState == null) {
        Log.w(TAG, "Main Thread Layout state is not found")
        return
      }

      layoutState.setShouldProcessVisibilityOutputs(true)

      lithoHostListenerCoordinator?.processVisibilityOutputs(
          requireNotNull(currentVisibleArea),
          isMountStateDirty,
          reason,
      )

      previousMountBounds.set(requireNotNull(currentVisibleArea))
      isVisibilityProcessedForMountBounds = true
    }
  }

  fun mountComponentIfNeeded(): Boolean {
    if (isMountStateDirty || mountStateNeedsRemount()) {
      trace(
          {
            "mount.mountComponentIfNeeded[${currentLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
          },
      ) {
        if (isIncrementalMountEnabled) {
          notifyVisibleBoundsChanged(null, VisibilityChangeReason.MOUNT)
        } else {
          val visibleRect = Rect()
          getLocalVisibleRect(visibleRect)
          mountComponent(visibleRect, true)
        }
      }

      return true
    }

    return false
  }

  protected fun setupMountExtensions() {
    if (lithoHostListenerCoordinator == null) {
      val coordinator = LithoHostListenerCoordinator(mountState)

      coordinator.enableNestedLithoViewsExtension()
      coordinator.enableTransitions()
      coordinator.enableStyleTransitions()

      if (ComponentsConfiguration.isEndToEndTestRun) {
        coordinator.enableEndToEndTestProcessing()
      }

      coordinator.enableDynamicProps()
      lithoHostListenerCoordinator = coordinator
    }

    val coordinator = requireNotNull(lithoHostListenerCoordinator)

    if (hasTree) {
      if (isIncrementalMountEnabled) {
        coordinator.enableIncrementalMount(configuration?.gapWorkerMode ?: GapWorkerMode.OFF)
      } else {
        coordinator.disableIncrementalMount()
      }

      if (isVisibilityProcessingEnabled) {
        coordinator.enableVisibilityProcessing(this)
      } else {
        coordinator.disableVisibilityProcessing()
      }
    }

    coordinator.setCollectNotifyVisibleBoundsChangedCalls(true)
  }

  /**
   * If we have transition key on root component we might run bounds animation on BaseMountingView
   * which requires to know animating value in [BaseMountingView.onMeasure]. In such case we need to
   * collect all transitions before mount happens but after layout computation is finalized.
   */
  protected fun maybeCollectAllTransitions() {
    if (isMountStateDirty) {
      if (!hasTree) {
        return
      }

      val layoutState = currentLayoutState
      if (layoutState?.rootTransitionId == null) {
        return
      }
      // TODO: can this be a generic callback?
      lithoHostListenerCoordinator?.collectAllTransitions(layoutState)
    }
  }

  open protected fun resetVisibilityHint() {
    hasVisibilityHint = false
    pauseMountingWhileVisibilityHintFalse = false
    if (configuration?.resetVisibilityHintOnTreeDetach == true) {
      visibilityHintIsVisible = false
    }
  }

  open protected fun setVisibilityHintNonRecursive(
      isVisible: Boolean,
      reason: VisibilityChangeReason,
  ) {
    assertMainThread()

    if (!hasTree) {
      return
    }

    if (!hasVisibilityHint && isVisible) {
      return
    }

    // If the BaseMountingView previously had the visibility hint set to false, then when it's set
    // back
    // to true we should trigger a mount, in case the visible bounds changed while mounting was
    // paused.
    hasVisibilityHint = true
    pauseMountingWhileVisibilityHintFalse = true

    val forceMount = shouldPauseMountingWithVisibilityHintFalse()
    visibilityHintIsVisible = isVisible

    if (isVisible) {
      if (shouldDispatchVisibilityEvent()) {
        if (forceMount) {
          notifyVisibleBoundsChanged(reason)
        } else if (getLocalVisibleRect(rect)) {
          processVisibilityOutputs(rect, reason)
        }
      }
    } else {
      // if false: no-op, doesn't have visible area, is not ready or not attached
      clearVisibilityItems(reason)
    }
  }

  /**
   * Deprecated: Consider subscribing the ComponentTree to a [LithoVisibilityEventsController]
   * instead.
   *
   * Call this to tell the LithoView whether it is visible or not. In general, you shouldn't require
   * this as the system will do this for you. However, when a new activity/fragment is added on top
   * of the one hosting this view, the LithoView remains in the backstack but receives no callback
   * to indicate that it is no longer visible.
   *
   * While the LithoView has the visibility hint set to false, it will be treated by the framework
   * as not in the viewport, so no new mounting events will be processed until the visibility hint
   * is set back to true.
   *
   * @param isVisible if true, this will find the current visible rect and process visibility
   *   outputs using it. If false, any invisible and unfocused events will be called.
   */
  @Deprecated("Subscribe the ComponentTree to a LithoVisibilityEventsController instead.")
  open fun setVisibilityHint(isVisible: Boolean) {
    setVisibilityHintInternal(isVisible, true, VisibilityChangeReason.VISIBILITY_HINT)
  }

  /**
   * Deprecated: Consider subscribing the ComponentTree to a [LithoVisibilityEventsController]
   * instead.
   *
   * This method only maintains the contract with usages of setVisibilityHint from before
   * `skipMountingIfNotVisible` defaulted to true.
   */
  @Deprecated("Subscribe the ComponentTree to a LithoVisibilityEventsController instead.")
  open fun setVisibilityHint(isVisible: Boolean, skipMountingIfNotVisible: Boolean) {
    if (this.skipMountingIfNotVisible) {
      setVisibilityHint(isVisible)
      return
    }

    setVisibilityHintInternal(
        isVisible,
        skipMountingIfNotVisible,
        VisibilityChangeReason.VISIBILITY_HINT,
    )
  }

  internal fun setVisibilityHintInternal(
      isVisible: Boolean,
      skipMountingIfNotVisible: Boolean,
      reason: VisibilityChangeReason,
  ) {
    assertMainThread()
    if (!hasTree) {
      return
    }

    // If the BaseMountingView previously had the visibility hint set to false, then when it's set
    // back
    // to true we should trigger a mount, in case the visible bounds changed while mounting was
    // paused.
    hasVisibilityHint = true
    pauseMountingWhileVisibilityHintFalse = skipMountingIfNotVisible

    val forceMount = shouldPauseMountingWithVisibilityHintFalse()
    visibilityHintIsVisible = isVisible

    if (isVisible) {
      if (shouldDispatchVisibilityEvent()) {
        if (forceMount) {
          notifyVisibleBoundsChanged(reason)
        } else if (getLocalVisibleRect(rect)) {
          processVisibilityOutputs(rect, reason)
        }
        recursivelySetVisibleHint(true, skipMountingIfNotVisible)
      }
    } else {
      // if false: no-op, doesn't have visible area, is not ready or not attached
      recursivelySetVisibleHint(false, skipMountingIfNotVisible)
      clearVisibilityItems(reason)
    }
  }

  private fun clearVisibilityItems(reason: VisibilityChangeReason) {
    lithoHostListenerCoordinator?.clearVisibilityItems(reason)
    isVisibilityProcessedForMountBounds = false
  }

  /** This should be called when setting a null component tree to the litho view. */
  private fun clearLastMountedTree() {
    lithoHostListenerCoordinator?.clearLastMountedTreeId()
  }

  /**
   * Since detached LithoView is still receiving visibility event and ends up that IM being kicked
   * off, we need to make sure that current view is in the state of being attached. Another fact is
   * that we could get an incorrect visible bounds when view gets detached.
   *
   * @return True if LithoView is attached and we need to invoke IM or dispatch visibility event.
   */
  private fun shouldDispatchVisibilityEvent(): Boolean = !(unmountsOnDetach && !isAttached)

  private val unmountsOnDetach: Boolean
    get() = (configuration?.unmountOnDetach ?: UnmountOnDetach.Never) != UnmountOnDetach.Never

  /**
   * @return the width value that that the MountingView should be animating from. If this returns
   *   non-negative value, we will override the measured width with this value so that initial
   *   animated value is correctly applied.
   */
  @ThreadConfined(ThreadConfined.UI)
  fun getInitialAnimatedMountingViewWidth(
      currentAnimatedWidth: Int,
      hasNewComponentTree: Boolean,
  ): Int {
    val transition = currentLayoutState?.rootWidthAnimation
    return getInitialAnimatedMountingViewDimension(
        currentAnimatedWidth,
        hasNewComponentTree,
        transition,
        AnimatedProperties.WIDTH,
    )
  }

  /**
   * @return the height value that the MountingView should be animating from. If this returns
   *   non-negative value, we will override the measured height with this value so that initial
   *   animated value is correctly applied.
   */
  @ThreadConfined(ThreadConfined.UI)
  fun getInitialAnimatedMountingViewHeight(
      currentAnimatedHeight: Int,
      hasNewComponentTree: Boolean,
  ): Int {
    val transition = currentLayoutState?.rootHeightAnimation
    return getInitialAnimatedMountingViewDimension(
        currentAnimatedHeight,
        hasNewComponentTree,
        transition,
        AnimatedProperties.HEIGHT,
    )
  }

  private fun getInitialAnimatedMountingViewDimension(
      currentAnimatedDimension: Int,
      hasNewComponentTree: Boolean,
      rootBoundsTransition: RootBoundsTransition?,
      property: AnimatedProperty,
  ): Int {
    if (rootBoundsTransition == null) {
      return SIZE_UNSET
    }
    val hasMounted = hasMountedAtLeastOnce()
    val appearTransition = rootBoundsTransition.appearTransition
    if (!hasMounted && appearTransition != null) {
      return Transition.getRootAppearFromValue(
              appearTransition,
              requireNotNull(currentLayoutState),
              property,
          )
          .toInt()
    }

    if (hasMounted && !hasNewComponentTree) {
      return currentAnimatedDimension
    }

    return SIZE_UNSET
  }

  private fun recursivelySetVisibleHint(isVisible: Boolean, skipMountingIfNotVisible: Boolean) {
    val childMountingViews = childMountingViewsFromCurrentlyMountedItems
    for (i in childMountingViews.indices.reversed()) {
      val mountingView = childMountingViews[i]
      mountingView.setVisibilityHint(isVisible, skipMountingIfNotVisible)
    }
  }

  override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
    mountForFocusSearchIfNeeded()
    super.addFocusables(views, direction, focusableMode)
  }

  /**
   * A parent may lay this view out beyond its own bounds and then ask what focusables it holds --
   * RecyclerView does exactly that to answer a focus search that runs off the edge of a list.
   * Incremental mount keys off [getLocalVisibleRect], so in that state nothing is mounted and we
   * would answer "none", dropping the content out of the focus order entirely. Mount for the query
   * instead. Visibility events are deliberately not processed: the content is laid out, not seen.
   *
   * This is a probe, not a display, so it goes to [mount] rather than [mountComponent]: it must
   * record nothing about having been seen. In particular [previousMountBounds] has to keep reading
   * empty. Both [onOffsetOrTranslationChange] and `IncrementalMountUtils` treat bounds that match
   * the view's full size as "fully visible and already completely mounted" and would then skip the
   * pass that dispatches the visible event, losing the impression for a card the user does go on to
   * see.
   */
  private fun mountForFocusSearchIfNeeded() {
    // Whether content is mounted is asked of the mount state rather than of previousMountBounds,
    // which a probe leaves empty. Only the root host survives an incremental unmount, so anything
    // above it means the focus search already has real content to walk.
    if (!hasTree || width <= 0 || height <= 0 || mountState.getMountItemCount() > 1) {
      return
    }
    // mountComponent's guards are skipped along with its bookkeeping, so re-check the two that
    // matter: a reentrant mount would be dropped rather than queued, and under transient state the
    // whole view is mounted already.
    if (isMounting || (transientStateCount > 0 && isIncrementalMountEnabled)) {
      return
    }
    mount(Rect(0, 0, width, height), false, VisibilityChangeReason.MOUNT, isProbe = true)
    // Draining is mountComponent's job, and nothing else on this path does it: a mount collected
    // while the probe held isMounting would otherwise sit queued until the next mountComponent.
    consumeReentrantMounts()
  }

  override fun offsetTopAndBottom(offset: Int) {
    super.offsetTopAndBottom(offset)

    onOffsetOrTranslationChange()
  }

  override fun offsetLeftAndRight(offset: Int) {
    super.offsetLeftAndRight(offset)

    onOffsetOrTranslationChange()
  }

  override fun setTranslationX(translationX: Float) {
    if (translationX == getTranslationX()) {
      return
    }
    super.setTranslationX(translationX)

    onOffsetOrTranslationChange()
  }

  override fun setTranslationY(translationY: Float) {
    if (translationY == getTranslationY()) {
      return
    }
    super.setTranslationY(translationY)

    onOffsetOrTranslationChange()
  }

  private fun onOffsetOrTranslationChange() {
    if (!hasTree || parent !is View || IncrementalMountUtils.skipMountOnLayoutDuringScroll) {
      return
    }

    val parentWidth = (parent as View).width
    val parentHeight = (parent as View).height

    val translationX = translationX.toInt()
    val translationY = translationY.toInt()
    val top = top + translationY
    val bottom = bottom + translationY
    val left = left + translationX
    val right = right + translationX
    val previousRect = previousMountBounds

    if (
        left >= 0 &&
            top >= 0 &&
            right <= parentWidth &&
            bottom <= parentHeight &&
            previousRect.left >= 0 &&
            previousRect.top >= 0 &&
            previousRect.right <= parentWidth &&
            previousRect.bottom <= parentHeight &&
            previousRect.width() == width &&
            previousRect.height() == height &&
            isTransformStillWholeOrCovered()
    ) {
      // View is fully visible, and has already been completely mounted.
      return
    }

    val rect = Rect()
    val parentCachedRect =
        if (VisibilityExtensionConfigs.isCachedParentVisibleRectEnabled) {
          (parent as? CachedVisibleRectProvider)?.cachedLocalVisibleRect
        } else {
          null
        }
    if (parentCachedRect != null) {
      rect.set(parentCachedRect)
      if (!rect.intersect(left, top, right, bottom)) {
        return
      }
      rect.offset(-left, -top)
    } else if (!getLocalVisibleRect(rect)) {
      return
    }

    notifyVisibleBoundsChanged(rect, true, VisibilityChangeReason.SCROLL)
  }

  /**
   * Whether the visibility bounds transformer, if any, still leaves all of this view visible or
   * none of it, as the extension recorded, after a last pass that processed the whole view. The
   * view can then take the same early return as a view without a transformer. Its nested trees are
   * left whole or covered with it only while they lie inside it: a parent that doesn't clip its
   * children shows what lies outside. So a view whose layout places a nested tree outside itself
   * never skips. For a transformer that doesn't record at every pass, the extension answers false
   * without running the transform.
   */
  private fun isTransformStillWholeOrCovered(): Boolean {
    if (configuration?.visibilityBoundsTransformer == null) {
      return true
    }
    val extensionState = lithoHostListenerCoordinator?.visibilityExtensionState ?: return false
    return isVisibilityProcessedForMountBounds &&
        previousMountBounds.contains(nestedTreeBounds) &&
        isVisibleRectStillWholeOrCovered(extensionState, previousMountBounds)
  }

  /** Unites the bounds of [layoutState]'s nested trees into [nestedTreeBounds]. */
  private fun updateNestedTreeBounds(layoutState: LayoutState) {
    if (
        configuration
            ?.visibilityBoundsTransformer
            ?.shouldRecordTransformedVisibleRectAtEveryPass() != true
    ) {
      return
    }
    nestedTreeBounds.setEmpty()
    layoutState.renderUnitIdsWhichHostRenderTrees?.forEach { id ->
      val node = layoutState.getMountableOutputAt(layoutState.getPositionForId(id))
      nestedTreeBounds.union(
          node.absoluteX,
          node.absoluteY,
          node.absoluteX + node.bounds.width(),
          node.absoluteY + node.bounds.height(),
      )
    }
  }

  val visibilityExtensionState: VisibilityMountExtensionState?
    get() {
      val lithoHostListenerCoordinator = lithoHostListenerCoordinator
      if (lithoHostListenerCoordinator != null) {
        val visibilityExtensionState = lithoHostListenerCoordinator.visibilityExtensionState
        if (visibilityExtensionState != null) {
          return visibilityExtensionState.state as VisibilityMountExtensionState?
        }
      }

      return null
    }

  override fun shouldRequestLayout(): Boolean {
    // Don't bubble up layout requests while mounting.
    if (hasTree && isMounting) {
      return false
    }

    return super.shouldRequestLayout()
  }

  override fun onVisibilityAggregated(isVisible: Boolean) {
    super.onVisibilityAggregated(isVisible)
    if (ComponentsConfiguration.defaultInstance.enableCheckVisibilityAggregated) {
      if (isVisible) {
        setVisibilityHintInternal(
            isVisible = true,
            skipMountingIfNotVisible = true,
            reason = VisibilityChangeReason.VISIBILITY_HINT,
        )
      } else {
        setVisibilityHintInternal(
            isVisible = false,
            skipMountingIfNotVisible = true,
            reason = VisibilityChangeReason.VISIBILITY_HINT,
        )
      }
    }
  }

  /**
   * An encapsulation of currentVisibleArea and processVisibilityOutputs for each re-entrant mount.
   */
  private class ReentrantMount(
      val currentVisibleArea: Rect?,
      val processVisibilityOutputs: Boolean,
      val reason: VisibilityChangeReason,
  )

  fun interface OnDirtyMountListener {
    /**
     * Called when finishing a mount where the mount state was dirty. This indicates that there were
     * new props/state in the tree, or the BaseMountingView was mounting a new ComponentTree
     */
    fun onDirtyMount(view: BaseMountingView)
  }

  companion object {
    private const val REENTRANT_MOUNTS_EXCEED_MAX_ATTEMPTS =
        "ComponentTree:ReentrantMountsExceedMaxAttempts"
    private const val REENTRANT_MOUNTS_MAX_ATTEMPTS = 25
    const val SIZE_UNSET: Int = -1
    private val TAG: String = BaseMountingView::class.java.simpleName
    private val EMPTY_RECT = Rect()

    private fun performLayoutOnChildrenIfNecessary(host: ComponentHost) {
      val childCount = host.childCount
      if (childCount == 0) {
        return
      }

      // Snapshot the children before traversal as measure/layout could trigger events which cause
      // children to be mounted/unmounted.
      val children = arrayOfNulls<View>(childCount)
      for (i in 0 until childCount) {
        children[i] = host.getChildAt(i)
      }

      for (i in 0 until childCount) {
        val child = requireNotNull(children[i])
        if (child.parent != host) {
          // child has been removed
          continue
        }

        if (child.isLayoutRequested) {
          // The hosting view doesn't allow children to change sizes dynamically as
          // this would conflict with the component's own layout calculations.
          child.measure(
              MeasureSpec.makeMeasureSpec(child.width, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(child.height, MeasureSpec.EXACTLY),
          )
          child.layout(child.left, child.top, child.right, child.bottom)
        }

        if (child is ComponentHost) {
          performLayoutOnChildrenIfNecessary(child)
        }
      }
    }

    private fun getDefaultLifecycleOwner(view: View): LifecycleOwner? {
      return if (
          ComponentsConfiguration.defaultInstance.enableDefaultLifecycleOwnerAsFragmentOrActivity
      ) {
        try {
          FragmentManager.findFragment(view)
        } catch (e: IllegalStateException) {
          getLifecycleOwnerFromContext(view.context)
        }
      } else {
        view.findViewTreeLifecycleOwner()
      }
    }

    private fun getLifecycleOwnerFromContext(context: Context): LifecycleOwner? {
      return if (context is LifecycleOwner) {
        context
      } else if (context is ContextWrapper) {
        getLifecycleOwnerFromContext(context.baseContext)
      } else {
        null
      }
    }

    private fun getChildMountingViewsFromCurrentlyMountedItems(
        mountDelegateTarget: MountDelegateTarget,
    ): List<BaseMountingView> {
      val childMountingViews = ArrayList<BaseMountingView>()

      var i = 0
      val size = mountDelegateTarget.getMountItemCount()
      while (i < size) {
        val content = mountDelegateTarget.getContentAt(i)
        if (content is HasLithoViewChildren) {
          content.obtainLithoViewChildren(childMountingViews)
        }
        i++
      }

      return childMountingViews
    }

    // Check if we should ignore the result of visible rect checking and continue doing
    // IncrementalMount.
    private fun hasComponentsExcludedFromIncrementalMount(layoutState: LayoutState?): Boolean {
      return layoutState != null && layoutState.hasComponentsExcludedFromIncrementalMount
    }

    fun drawDebugOverlay(view: BaseMountingView?, id: Int) {
      if (DebugOverlay.isEnabled && view != null) {
        val drawable: Drawable = getDebugOverlay(id)
        clearDebugOverlay(view)
        drawable.setBounds(0, 0, view.width, view.height)
        view.overlay.add(drawable)
      }
    }

    @JvmStatic
    fun clearDebugOverlay(view: BaseMountingView?) {
      if (DebugOverlay.isEnabled && view != null) {
        view.overlay.clear()
      }
    }
  }
}
