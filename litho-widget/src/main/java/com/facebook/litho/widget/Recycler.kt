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

package com.facebook.litho.widget

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.IdRes
import androidx.annotation.RestrictTo
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemAnimator
import androidx.recyclerview.widget.SnapHelper
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Size
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.PrimitiveRecyclerBinderStrategy
import com.facebook.litho.eventHandler
import com.facebook.litho.useState
import com.facebook.litho.widget.LithoRecyclerView.OnAfterLayoutListener
import com.facebook.litho.widget.LithoRecyclerView.OnBeforeLayoutListener
import com.facebook.litho.widget.LithoRecyclerView.TouchInterceptor
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.MountConfigurationScope
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import kotlin.math.max

class Recycler
@JvmOverloads
constructor(
    val binder: Binder<RecyclerView>,
    private val binderStrategy: PrimitiveRecyclerBinderStrategy? = null,
    private val hasFixedSize: Boolean = true,
    private val isClipToPaddingEnabled: Boolean = true,
    private val leftPadding: Int = 0,
    private val topPadding: Int = 0,
    private val rightPadding: Int = 0,
    private val bottomPadding: Int = 0,
    @param:ColorInt private val refreshProgressBarBackgroundColor: Int? = null,
    @param:ColorInt private val refreshProgressBarColor: Int = Color.BLACK,
    private val isClipChildrenEnabled: Boolean = true,
    private val isNestedScrollingEnabled: Boolean = true,
    private val scrollBarStyle: Int = View.SCROLLBARS_INSIDE_OVERLAY,
    private val itemDecorations: List<RecyclerView.ItemDecoration>? = null,
    private val isHorizontalFadingEdgeEnabled: Boolean = false,
    private val isVerticalFadingEdgeEnabled: Boolean = false,
    private val isLeftFadingEnabled: Boolean = true,
    private val isRightFadingEnabled: Boolean = true,
    private val isTopFadingEnabled: Boolean = true,
    private val isBottomFadingEnabled: Boolean = true,
    private val fadingEdgeLength: Int = 0,
    private val edgeFactory: RecyclerView.EdgeEffectFactory? = null,
    @param:IdRes private val recyclerViewId: Int = View.NO_ID,
    private val overScrollMode: Int = View.OVER_SCROLL_ALWAYS,
    private val contentDescription: CharSequence? = null,
    private val itemAnimator: RecyclerView.ItemAnimator? = NoUpdateItemAnimator(),
    private val recyclerEventsController: RecyclerEventsController? = null,
    private val onScrollListeners: List<RecyclerView.OnScrollListener>? = null,
    private val snapHelper: SnapHelper? = null,
    private val isPullToRefreshEnabled: Boolean = true,
    private val touchInterceptor: LithoRecyclerView.TouchInterceptor? = null,
    private val onItemTouchListener: RecyclerView.OnItemTouchListener? = null,
    private val onRefresh: (() -> Unit)? = null,
    private val sectionsViewLogger: SectionsRecyclerView.SectionsRecyclerViewLogger? = null,
    private val excludeFromIncrementalMount: Boolean = false,
    private val paddingAdditionDisabled: Boolean = false,
    private val onBeforeLayoutListener: OnBeforeLayoutListener? = null,
    private val onAfterLayoutListener: OnAfterLayoutListener? = null,
    private val removeAccessibilityListRole: Boolean = false,
    private val style: Style = Style,
) : PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    val measureVersion = useState { 0 }

    val measureChild: State<View.() -> Unit> = useState {
      {
        val position = (layoutParams as RecyclerView.LayoutParams).viewLayoutPosition
        measure(binder.getChildWidthSpec(position), binder.getChildWidthSpec(position))
      }
    }

    val mountBehavior =
        when (
            binderStrategy
                ?: context.lithoConfiguration.componentsConfig.primitiveRecyclerBinderStrategy
        ) {
          PrimitiveRecyclerBinderStrategy.SPLIT_BINDERS ->
              SplitBindersMountBehavior(
                  measureVersion,
                  onRefresh,
                  onScrollListeners,
                  recyclerEventsController,
                  onBeforeLayoutListener,
                  onAfterLayoutListener,
                  measureChild.value,
              )
          PrimitiveRecyclerBinderStrategy.DEFAULT ->
              DefaultMountBehavior(
                  measureVersion,
                  onRefresh,
                  onScrollListeners,
                  recyclerEventsController,
                  onBeforeLayoutListener,
                  onAfterLayoutListener,
                  measureChild.value,
              )
        }

    return LithoPrimitive(
        layoutBehavior =
            RecyclerLayoutBehavior(
                binder = binder,
                startPadding = leftPadding,
                endPadding = rightPadding,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
            ) {
              measureVersion.update { v -> v + 1 }
            },
        mountBehavior = mountBehavior,
        style = style,
    )
  }

  private fun PrimitiveComponentScope.DefaultMountBehavior(
      measureVersion: State<Int>,
      onRefresh: (() -> Unit)?,
      onScrollListeners: List<RecyclerView.OnScrollListener>?,
      recyclerEventsController: RecyclerEventsController?,
      onBeforeLayoutListener: OnBeforeLayoutListener?,
      onAfterLayoutListener: OnAfterLayoutListener?,
      measureChild: View.() -> Unit,
  ): MountBehavior<SectionsRecyclerView> =
      MountBehavior(ViewAllocator { context -> createSectionsRecyclerView(context) }) {
        doesMountRenderTreeHosts = true
        shouldExcludeFromIncrementalMount = excludeFromIncrementalMount

        withDescription("recycler-decorations") {
          bind(itemDecorations, measureChild) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()

            itemDecorations?.forEach { decoration ->
              if (decoration is ItemDecorationWithMeasureFunction) {
                decoration.measure = measureChild
              }
              recyclerView.addItemDecoration(decoration)
            }

            onUnbind {
              itemDecorations?.forEach { decoration ->
                recyclerView.removeItemDecoration(decoration)
                if (decoration is ItemDecorationWithMeasureFunction) {
                  decoration.measure = null
                }
              }
            }
          }
        }

        val mountDeps = arrayOf<Any?>(
            measureVersion.value,
            binder,
            hasFixedSize,
            isClipToPaddingEnabled,
            leftPadding,
            topPadding,
            rightPadding,
            bottomPadding,
            isClipChildrenEnabled,
            scrollBarStyle,
            isHorizontalFadingEdgeEnabled,
            isVerticalFadingEdgeEnabled,
            fadingEdgeLength,
            refreshProgressBarBackgroundColor,
            refreshProgressBarColor,
            removeAccessibilityListRole,
        )

        // Its own binder, so that changing the animator's class never remounts the adapter. It also
        // rebinds with the mount binder, which picks up a new animator instance as before, and
        // comes first so the animator is in place when the adapter is reattached.
        withDescription("recycler-item-animator") {
          bind(itemAnimator?.javaClass, *mountDeps) { sectionsRecyclerView ->
            sectionsRecyclerView.setItemAnimator(itemAnimator)
            onUnbind { sectionsRecyclerView.resetItemAnimator() }
          }
        }

        bindPausesChildrenAnimatingOut(context.lithoConfiguration.componentsConfig, itemAnimator)

        // RecyclerSpec's @OnMount and @OnUnmount
        withDescription("recycler-equivalent-mount") {
          bind(*mountDeps) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()

            bindLegacyMountBinder(
                sectionsRecyclerView = sectionsRecyclerView,
                contentDescription = contentDescription,
                hasFixedSize = hasFixedSize,
                isClipToPaddingEnabled = isClipToPaddingEnabled,
                paddingAdditionDisabled = paddingAdditionDisabled,
                leftPadding = leftPadding,
                topPadding = topPadding,
                rightPadding = rightPadding,
                bottomPadding = bottomPadding,
                isClipChildrenEnabled = isClipChildrenEnabled,
                isNestedScrollingEnabled = isNestedScrollingEnabled,
                scrollBarStyle = scrollBarStyle,
                isHorizontalFadingEdgeEnabled = isHorizontalFadingEdgeEnabled,
                isVerticalFadingEdgeEnabled = isVerticalFadingEdgeEnabled,
                isLeftFadingEnabled = isLeftFadingEnabled,
                isRightFadingEnabled = isRightFadingEnabled,
                isTopFadingEnabled = isTopFadingEnabled,
                isBottomFadingEnabled = isBottomFadingEnabled,
                fadingEdgeLength = fadingEdgeLength,
                recyclerViewId = recyclerViewId,
                overScrollMode = overScrollMode,
                edgeFactory = edgeFactory,
                refreshProgressBarBackgroundColor = refreshProgressBarBackgroundColor,
                refreshProgressBarColor = refreshProgressBarColor,
                removeAccessibilityListRole = removeAccessibilityListRole,
            )

            binder.mount(recyclerView)
            recyclerView.skipFadeInsAfterAdapterMount()

            onUnbind {
              unbindLegacyMountBinder(
                  sectionsRecyclerView = sectionsRecyclerView,
                  refreshProgressBarBackgroundColor = refreshProgressBarBackgroundColor,
                  edgeFactory = edgeFactory,
                  snapHelper = snapHelper,
              )

              binder.unmount(recyclerView)
            }
          }
        }

        // Unmount only: the mount binder above also unbinds on new props or a new measure.
        withDescription("bring-to-front-reset") {
          bind(Unit) { sectionsRecyclerView ->
            onUnbind { sectionsRecyclerView.skipsRedundantBringToFront = false }
          }
        }

        withDescription("recycler-before-layout") {
          bind(onBeforeLayoutListener) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
            onBeforeLayoutListener?.let { recyclerView.setOnBeforeLayoutListener(it) }
            onUnbind { recyclerView.setOnBeforeLayoutListener(null) }
          }
        }

        withDescription("recycler-after-layout") {
          bind(onAfterLayoutListener) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
            onAfterLayoutListener?.let { recyclerView.setOnAfterLayoutListener(it) }
            onUnbind { recyclerView.setOnAfterLayoutListener(null) }
          }
        }

        withDescription("recycler-equivalent-bind") {
          bind(Any()) { sectionsRecyclerView ->
            bindLegacyAttachBinder(
                sectionsRecyclerView = sectionsRecyclerView,
                sectionsViewLogger = sectionsViewLogger,
                isPullToRefreshEnabled = isPullToRefreshEnabled,
                enableKeyboardPullToRefresh =
                    context.lithoConfiguration.componentsConfig.enableKeyboardPullToRefresh,
                onRefresh = onRefresh,
                onScrollListeners = onScrollListeners,
                touchInterceptor = touchInterceptor,
                onItemTouchListener = onItemTouchListener,
                snapHelper = snapHelper,
                recyclerEventsController = recyclerEventsController,
            )

            onUnbind {
              unbindLegacyAttachBinder(
                  sectionsRecyclerView = sectionsRecyclerView,
                  recyclerEventsController = recyclerEventsController,
                  onScrollListeners = onScrollListeners,
                  onItemTouchListener = onItemTouchListener,
              )
            }
          }
        }
      }

  /**
   * This is one [MountBehavior<SectionsRecyclerView>] that uses three different binders to mount
   * the content.
   *
   * The different mount binders are as follows:
   * - Content: This is responsible for mounting/unmounting all recycler view like properties
   *   related to the content and listeners.
   * - ItemDecorations: This will bind/unbind the [itemDecorations]
   * - Binder<RecyclerView>: This will bind/unbind the [binder].
   */
  private fun PrimitiveComponentScope.SplitBindersMountBehavior(
      measureVersion: State<Int>,
      onRefresh: (() -> Unit)?,
      onScrollListeners: List<RecyclerView.OnScrollListener>?,
      recyclerEventsController: RecyclerEventsController?,
      onBeforeLayoutListener: OnBeforeLayoutListener?,
      onAfterLayoutListener: OnAfterLayoutListener?,
      measureChild: View.() -> Unit,
  ): MountBehavior<SectionsRecyclerView> =
      MountBehavior(ViewAllocator { context -> createSectionsRecyclerView(context) }) {
        doesMountRenderTreeHosts = true
        shouldExcludeFromIncrementalMount = excludeFromIncrementalMount

        withDescription("recycler-decorations") {
          bind(itemDecorations, measureChild) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()

            itemDecorations?.forEach { decoration ->
              if (decoration is ItemDecorationWithMeasureFunction) {
                decoration.measure = measureChild
              }
              recyclerView.addItemDecoration(decoration)
            }

            onUnbind {
              itemDecorations?.forEach { decoration ->
                recyclerView.removeItemDecoration(decoration)
                if (decoration is ItemDecorationWithMeasureFunction) {
                  decoration.measure = null
                }
              }
            }
          }
        }

        val mountDeps = arrayOf<Any?>(
            measureVersion.value,
            hasFixedSize,
            isClipToPaddingEnabled,
            leftPadding,
            topPadding,
            rightPadding,
            bottomPadding,
            isClipChildrenEnabled,
            scrollBarStyle,
            isHorizontalFadingEdgeEnabled,
            isVerticalFadingEdgeEnabled,
            fadingEdgeLength,
            refreshProgressBarBackgroundColor,
            refreshProgressBarColor,
            removeAccessibilityListRole,
        )

        // Its own binder, so that changing the animator's class never remounts the adapter. It also
        // rebinds with the mount binder, which picks up a new animator instance as before, and
        // comes first so the animator is in place when the adapter is reattached.
        withDescription("recycler-item-animator") {
          bind(itemAnimator?.javaClass, *mountDeps) { sectionsRecyclerView ->
            sectionsRecyclerView.setItemAnimator(itemAnimator)
            onUnbind { sectionsRecyclerView.resetItemAnimator() }
          }
        }

        bindPausesChildrenAnimatingOut(context.lithoConfiguration.componentsConfig, itemAnimator)

        withDescription("recycler-equivalent-mount") {
          bind(*mountDeps) { sectionsRecyclerView ->
            bindLegacyMountBinder(
                sectionsRecyclerView = sectionsRecyclerView,
                contentDescription = contentDescription,
                hasFixedSize = hasFixedSize,
                isClipToPaddingEnabled = isClipToPaddingEnabled,
                paddingAdditionDisabled = paddingAdditionDisabled,
                leftPadding = leftPadding,
                topPadding = topPadding,
                rightPadding = rightPadding,
                bottomPadding = bottomPadding,
                isClipChildrenEnabled = isClipChildrenEnabled,
                isNestedScrollingEnabled = isNestedScrollingEnabled,
                scrollBarStyle = scrollBarStyle,
                isHorizontalFadingEdgeEnabled = isHorizontalFadingEdgeEnabled,
                isVerticalFadingEdgeEnabled = isVerticalFadingEdgeEnabled,
                isLeftFadingEnabled = isLeftFadingEnabled,
                isRightFadingEnabled = isRightFadingEnabled,
                isTopFadingEnabled = isTopFadingEnabled,
                isBottomFadingEnabled = isBottomFadingEnabled,
                fadingEdgeLength = fadingEdgeLength,
                recyclerViewId = recyclerViewId,
                overScrollMode = overScrollMode,
                edgeFactory = edgeFactory,
                refreshProgressBarBackgroundColor = refreshProgressBarBackgroundColor,
                refreshProgressBarColor = refreshProgressBarColor,
                removeAccessibilityListRole = removeAccessibilityListRole,
            )

            onUnbind {
              unbindLegacyMountBinder(
                  sectionsRecyclerView = sectionsRecyclerView,
                  refreshProgressBarBackgroundColor = refreshProgressBarBackgroundColor,
                  edgeFactory = edgeFactory,
                  snapHelper = snapHelper,
              )
            }
          }
        }

        // Unmount only: the mount binder above also unbinds on new props or a new measure.
        withDescription("bring-to-front-reset") {
          bind(Unit) { sectionsRecyclerView ->
            onUnbind { sectionsRecyclerView.skipsRedundantBringToFront = false }
          }
        }

        withDescription("recycler-before-layout") {
          bind(onBeforeLayoutListener) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
            onBeforeLayoutListener?.let { recyclerView.setOnBeforeLayoutListener(it) }
            onUnbind { recyclerView.setOnBeforeLayoutListener(null) }
          }
        }

        withDescription("recycler-after-layout") {
          bind(onAfterLayoutListener) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
            onAfterLayoutListener?.let { recyclerView.setOnAfterLayoutListener(it) }
            onUnbind { recyclerView.setOnAfterLayoutListener(null) }
          }
        }

        withDescription("recycler-binder") {
          bind(binder) { sectionsRecyclerView ->
            val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
            binder.mount(recyclerView)
            recyclerView.skipFadeInsAfterAdapterMount()
            onUnbind { binder.unmount(recyclerView) }
          }
        }

        withDescription("recycler-equivalent-bind") {
          bind(Any()) { sectionsRecyclerView ->
            bindLegacyAttachBinder(
                sectionsRecyclerView = sectionsRecyclerView,
                sectionsViewLogger = sectionsViewLogger,
                isPullToRefreshEnabled = isPullToRefreshEnabled,
                enableKeyboardPullToRefresh =
                    context.lithoConfiguration.componentsConfig.enableKeyboardPullToRefresh,
                onRefresh = onRefresh,
                onScrollListeners = onScrollListeners,
                touchInterceptor = touchInterceptor,
                onItemTouchListener = onItemTouchListener,
                snapHelper = snapHelper,
                recyclerEventsController = recyclerEventsController,
            )

            onUnbind {
              unbindLegacyAttachBinder(
                  sectionsRecyclerView = sectionsRecyclerView,
                  recyclerEventsController = recyclerEventsController,
                  onScrollListeners = onScrollListeners,
                  onItemTouchListener = onItemTouchListener,
              )
            }
          }
        }
      }

  class NoUpdateItemAnimator : DefaultItemAnimator() {
    init {
      supportsChangeAnimations = false
    }
  }

  companion object {

    // This is the default value for refresh spinner background from RecyclerSpec.
    internal const val DEFAULT_REFRESH_SPINNER_BACKGROUND_COLOR: Int = 0xFFFAFAFA.toInt()

    fun createSectionsRecyclerView(context: Context): SectionsRecyclerView =
        SectionsRecyclerView(context, LithoRecyclerView(context)).apply {
          id = R.id.recycler_view_container_id
        }
  }
}

/**
 * The mount binder reattaches the adapter on padding or size changes while the list stays on
 * screen. RecyclerView then treats every visible row as newly added, so without this they would all
 * fade in. On a first mount it has no effect: RecyclerView never animates its first layout.
 */
private fun RecyclerView.skipFadeInsAfterAdapterMount() {
  (itemAnimator as? LazyCollectionItemAnimator)?.skipFadeInsInNextLayout(this)
}

/**
 * Lists given the default Lazy Collection animator, while its flag is on, pause the children they
 * keep on screen only to animate them out. Every other list keeps today's visibility timing.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun MountConfigurationScope<SectionsRecyclerView>.bindPausesChildrenAnimatingOut(
    componentsConfig: ComponentsConfiguration,
    itemAnimator: RecyclerView.ItemAnimator?,
) {
  val pausesChildrenAnimatingOut =
      componentsConfig.useDefaultItemAnimatorInLazyCollections &&
          itemAnimator === LazyCollectionItemAnimator.Default
  withDescription("recycler-children-animating-out") {
    bind(pausesChildrenAnimatingOut) { sectionsRecyclerView ->
      val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
      recyclerView.pausesChildrenAnimatingOut = pausesChildrenAnimatingOut
      onUnbind { recyclerView.pausesChildrenAnimatingOut = false }
    }
  }
}

fun SectionsRecyclerView.requireLithoRecyclerView(): LithoRecyclerView =
    recyclerView as? LithoRecyclerView
        ?: throw java.lang.IllegalStateException(
            "RecyclerView not found, it should not be removed from SwipeRefreshLayout",
        )

private class RecyclerLayoutBehavior(
    private val binder: Binder<RecyclerView>,
    private val startPadding: Int,
    private val endPadding: Int,
    private val topPadding: Int,
    private val bottomPadding: Int,
    private val onRemeasure: () -> Unit,
) : LayoutBehavior {
  override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {
    val size = Size()

    binder.measure(
        size,
        maybeGetSpecWithPadding(sizeConstraints.toWidthSpec(), startPadding + endPadding),
        maybeGetSpecWithPadding(sizeConstraints.toHeightSpec(), topPadding + bottomPadding),
        if (binder.isCrossAxisWrapContent || binder.isMainAxisWrapContent)
            eventHandler { onRemeasure() }
        else null,
    )
    return PrimitiveLayoutResult(
        max(sizeConstraints.minWidth, size.width + startPadding + endPadding),
        max(sizeConstraints.minHeight, size.height + topPadding + bottomPadding),
    )
  }
}

/**
 * Binds the [sectionsRecyclerView] to the data according to the behavior that was present in the
 * initial version of the RecyclerSpec regarding mount binders (previously `RecyclerSpec.onMount`).
 */
fun ResourcesScope.bindLegacyMountBinder(
    sectionsRecyclerView: SectionsRecyclerView,
    contentDescription: CharSequence?,
    hasFixedSize: Boolean,
    isClipToPaddingEnabled: Boolean,
    paddingAdditionDisabled: Boolean,
    leftPadding: Int,
    topPadding: Int,
    rightPadding: Int,
    bottomPadding: Int,
    isClipChildrenEnabled: Boolean,
    isNestedScrollingEnabled: Boolean,
    scrollBarStyle: Int,
    isHorizontalFadingEdgeEnabled: Boolean,
    isVerticalFadingEdgeEnabled: Boolean,
    isLeftFadingEnabled: Boolean,
    isRightFadingEnabled: Boolean,
    isTopFadingEnabled: Boolean,
    isBottomFadingEnabled: Boolean,
    fadingEdgeLength: Int,
    @IdRes recyclerViewId: Int,
    overScrollMode: Int,
    edgeFactory: RecyclerView.EdgeEffectFactory?,
    @ColorInt refreshProgressBarBackgroundColor: Int?,
    @ColorInt refreshProgressBarColor: Int,
    removeAccessibilityListRole: Boolean = false,
): LithoRecyclerView {
  val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()

  recyclerView.contentDescription = contentDescription
  recyclerView.setHasFixedSize(hasFixedSize)
  recyclerView.clipToPadding = isClipToPaddingEnabled
  sectionsRecyclerView.clipToPadding = isClipToPaddingEnabled
  if (!paddingAdditionDisabled) {
    ViewCompat.setPaddingRelative(
        recyclerView,
        leftPadding,
        topPadding,
        rightPadding,
        bottomPadding,
    )
  }
  recyclerView.clipChildren = isClipChildrenEnabled
  sectionsRecyclerView.clipChildren = isClipChildrenEnabled
  recyclerView.isNestedScrollingEnabled = isNestedScrollingEnabled
  sectionsRecyclerView.isNestedScrollingEnabled = isNestedScrollingEnabled
  recyclerView.scrollBarStyle = scrollBarStyle
  recyclerView.isHorizontalFadingEdgeEnabled = isHorizontalFadingEdgeEnabled
  recyclerView.isVerticalFadingEdgeEnabled = isVerticalFadingEdgeEnabled
  recyclerView.setLeftFadingEnabled(isLeftFadingEnabled)
  recyclerView.setRightFadingEnabled(isRightFadingEnabled)
  recyclerView.setTopFadingEnabled(isTopFadingEnabled)
  recyclerView.setBottomFadingEnabled(isBottomFadingEnabled)
  recyclerView.setFadingEdgeLength(fadingEdgeLength.dp.toPixels())
  recyclerView.id = recyclerViewId
  recyclerView.overScrollMode = overScrollMode
  edgeFactory?.let { recyclerView.edgeEffectFactory = it }

  if (refreshProgressBarBackgroundColor != null) {
    sectionsRecyclerView.setProgressBackgroundColorSchemeColor(refreshProgressBarBackgroundColor)
  }

  sectionsRecyclerView.setColorSchemeColors(refreshProgressBarColor)

  // Remove the accessibility list role to prevent TalkBack from announcing
  // the entire RecyclerView as a list when it should only expose actual lists.
  if (removeAccessibilityListRole) {
    ViewCompat.setImportantForAccessibility(recyclerView, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO)
  }

  return recyclerView
}

/**
 * Unbinds the [sectionsRecyclerView] to the data according to the behavior that was present in the
 * initial version of the RecyclerSpec regarding mount binders (previously
 * `RecyclerSpec.onUnmount`).
 */
fun unbindLegacyMountBinder(
    sectionsRecyclerView: SectionsRecyclerView,
    @ColorInt refreshProgressBarBackgroundColor: Int?,
    edgeFactory: RecyclerView.EdgeEffectFactory?,
    snapHelper: SnapHelper?,
) {
  val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()

  recyclerView.id = View.NO_ID

  if (refreshProgressBarBackgroundColor != null) {
    sectionsRecyclerView.setProgressBackgroundColorSchemeColor(
        Recycler.DEFAULT_REFRESH_SPINNER_BACKGROUND_COLOR,
    )
  }

  if (edgeFactory != null) {
    recyclerView.edgeEffectFactory = sectionsRecyclerView.defaultEdgeEffectFactory
  }

  snapHelper?.attachToRecyclerView(null)
}

/**
 * Binds the [sectionsRecyclerView] to the data according to the behavior that was present in the
 * initial version of the RecyclerSpec regarding the attach binders (previously
 * RecyclerSpec.onBind).
 */
fun bindLegacyAttachBinder(
    sectionsRecyclerView: SectionsRecyclerView,
    sectionsViewLogger: SectionsRecyclerView.SectionsRecyclerViewLogger?,
    isPullToRefreshEnabled: Boolean,
    onRefresh: (() -> Unit)?,
    onScrollListeners: List<RecyclerView.OnScrollListener>?,
    touchInterceptor: TouchInterceptor?,
    onItemTouchListener: RecyclerView.OnItemTouchListener?,
    snapHelper: SnapHelper?,
    recyclerEventsController: RecyclerEventsController?,
    enableKeyboardPullToRefresh: Boolean = false,
): LithoRecyclerView {
  val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()

  sectionsRecyclerView.setSectionsRecyclerViewLogger(sectionsViewLogger)

  // contentDescription should be set on the recyclerView itself, and not the
  // sectionsRecycler.
  sectionsRecyclerView.contentDescription = null

  sectionsRecyclerView.isEnabled = isPullToRefreshEnabled && onRefresh != null
  sectionsRecyclerView.onKeyboardRefresh = if (enableKeyboardPullToRefresh) onRefresh else null

  if (onRefresh != null) {
    sectionsRecyclerView.setOnRefreshListener { onRefresh() }
  }

  if (onScrollListeners != null) {
    for (i in onScrollListeners.indices) {
      recyclerView.addOnScrollListener(onScrollListeners[i])
    }
  }

  if (touchInterceptor != null) {
    recyclerView.setTouchInterceptor(touchInterceptor)
  }

  if (onItemTouchListener != null) {
    recyclerView.addOnItemTouchListener(onItemTouchListener)
  }

  // We cannot detach the snap helper in unbind, so it may be possible for it to
  // get attached twice which causes SnapHelper to raise an exception.
  if (recyclerView.onFlingListener == null) {
    snapHelper?.attachToRecyclerView(recyclerView)
  }

  if (recyclerEventsController != null) {
    recyclerEventsController.setSectionsRecyclerView(sectionsRecyclerView)
    recyclerEventsController.snapHelper = snapHelper
  }

  if (sectionsRecyclerView.hasBeenDetachedFromWindow()) {
    recyclerView.requestLayout()
    sectionsRecyclerView.setHasBeenDetachedFromWindow(false)
  }

  return recyclerView
}

/**
 * Unbinds the [sectionsRecyclerView] to the data according to the behavior that was present in the
 * initial version of the RecyclerSpec regarding the attach binders (previously
 * `RecyclerSpec.onUnbind`).
 */
fun unbindLegacyAttachBinder(
    sectionsRecyclerView: SectionsRecyclerView,
    recyclerEventsController: RecyclerEventsController?,
    onScrollListeners: List<RecyclerView.OnScrollListener>?,
    onItemTouchListener: RecyclerView.OnItemTouchListener?,
) {
  val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
  sectionsRecyclerView.stickyHeaderTopOffset = 0

  sectionsRecyclerView.setSectionsRecyclerViewLogger(null)

  if (recyclerEventsController != null) {
    recyclerEventsController.setSectionsRecyclerView(null)
    recyclerEventsController.snapHelper = null
  }

  if (onScrollListeners != null) {
    for (i in onScrollListeners.indices) {
      recyclerView.removeOnScrollListener(onScrollListeners[i])
    }
  }

  if (onItemTouchListener != null) {
    recyclerView.removeOnItemTouchListener(onItemTouchListener)
  }

  recyclerView.setTouchInterceptor(null)

  sectionsRecyclerView.setOnRefreshListener(null)
  sectionsRecyclerView.onKeyboardRefresh = null
}
