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

package com.facebook.rendercore.visibility

import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.collection.LongSet
import androidx.collection.MutableScatterMap
import com.facebook.rendercore.Host
import com.facebook.rendercore.RenderCoreSystrace
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute.Bounds
import com.facebook.rendercore.debug.DebugEventAttribute.CallbackType
import com.facebook.rendercore.debug.DebugEventAttribute.ComponentClassName
import com.facebook.rendercore.debug.DebugEventAttribute.Key
import com.facebook.rendercore.debug.DebugEventAttribute.Name
import com.facebook.rendercore.debug.DebugEventAttribute.RenderUnitId
import com.facebook.rendercore.debug.DebugEventDispatcher
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.MountExtension
import com.facebook.rendercore.extensions.VisibleBoundsCallbacks
import com.facebook.rendercore.visibility.VisibilityExtensionConfigs.DEBUG_TAG

class VisibilityMountExtension<Input : VisibilityExtensionInput> private constructor() :
    MountExtension<Input, VisibilityMountExtension.VisibilityMountExtensionState>(),
    VisibleBoundsCallbacks<VisibilityMountExtension.VisibilityMountExtensionState> {

  override fun createState(): VisibilityMountExtensionState {
    return VisibilityMountExtensionState()
  }

  override fun beforeMount(
      extensionState: ExtensionState<VisibilityMountExtensionState>,
      input: Input?,
      localVisibleRect: Rect?,
  ) {
    val nonNullInput = requireNotNull(input)

    val isTracing = RenderCoreSystrace.isTracing()
    if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
      Log.d(DEBUG_TAG, "beforeMount")
    }
    if (isTracing) {
      RenderCoreSystrace.beginSection("VisibilityExtension.beforeMount")
    }
    val state = extensionState.state

    state.visibilityOutputs = nonNullInput.visibilityOutputs
    state.renderUnitIdsWhichHostRenderTrees = nonNullInput.renderUnitIdsWhichHostRenderTrees
    state.previousLocalVisibleRect.setEmpty()
    state.previousTransformedVisibleRect.setEmpty()
    state.currentLocalVisibleRect = localVisibleRect
    state.visibilityBoundsTransformer = nonNullInput.visibilityBoundsTransformer
    state.input = nonNullInput

    if (isTracing) {
      RenderCoreSystrace.endSection()
    }
  }

  override fun afterMount(extensionState: ExtensionState<VisibilityMountExtensionState>) {
    afterMount(extensionState, VisibilityChangeReason.MOUNT)
  }

  /**
   * Called after mount completes to process visibility outputs.
   *
   * @param extensionState the extension state
   * @param reason the reason for visibility change (defaults to MOUNT)
   */
  override fun afterMount(
      extensionState: ExtensionState<VisibilityMountExtensionState>,
      reason: VisibilityChangeReason,
  ) {
    val isTracing = RenderCoreSystrace.isTracing()
    if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
      Log.d(DEBUG_TAG, "afterMount")
    }
    if (isTracing) {
      RenderCoreSystrace.beginSection("VisibilityExtension.afterMount")
    }

    val processVisibilityOutputs = shouldProcessVisibilityOutputs(extensionState)

    if (processVisibilityOutputs) {
      val state = extensionState.state
      processVisibilityOutputs(extensionState, state.currentLocalVisibleRect, true, reason)
    }

    if (isTracing) {
      RenderCoreSystrace.endSection()
    }
  }

  override fun onVisibleBoundsChanged(
      extensionState: ExtensionState<VisibilityMountExtensionState>,
      localVisibleRect: Rect?,
      reason: VisibilityChangeReason,
  ) {
    if (reason != VisibilityChangeReason.UNKNOWN) {
      extensionState.state.lastReason = reason
    }
    val processVisibilityOutputs = shouldProcessVisibilityOutputs(extensionState)
    val isTracing = RenderCoreSystrace.isTracing()

    if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
      Log.d(
          DEBUG_TAG,
          "onVisibleBoundsChanged [processVisibilityOutputs=$processVisibilityOutputs]",
      )
    }
    if (isTracing) {
      RenderCoreSystrace.beginSection("VisibilityExtension.onVisibleBoundsChanged")
    }

    if (processVisibilityOutputs) {
      processVisibilityOutputs(extensionState, localVisibleRect, false, reason)
    }

    if (isTracing) {
      RenderCoreSystrace.endSection()
    }
  }

  override fun onUnbind(extensionState: ExtensionState<VisibilityMountExtensionState>) {
    val reason = extensionState.state.lastReason
    clearVisibilityItems(extensionState, reason)
    extensionState.state.lastReason = VisibilityChangeReason.UNKNOWN
  }

  override fun onUnmount(extensionState: ExtensionState<VisibilityMountExtensionState>) {
    val state = extensionState.state
    state.previousLocalVisibleRect.setEmpty()
    state.previousTransformedVisibleRect.setEmpty()
    state.input = null
  }

  class VisibilityMountExtensionState internal constructor() {
    // Holds a list with information about the components linked to the VisibilityOutputs that are
    // stored in LayoutState. An item is inserted in this map if its corresponding component is
    // visible. When the component exits the viewport, the item associated with it is removed from
    // the map.
    internal val visibilityIdToItemMap: MutableScatterMap<Any, VisibilityItem>? =
        MutableScatterMap()

    internal val previousLocalVisibleRect: Rect = Rect()
    internal val previousTransformedVisibleRect: Rect = Rect()

    // Reusable Rect instances for transformer calls to avoid allocations during scroll
    internal val transformerOutputRect: Rect = Rect()
    internal val viewportOutputRect: Rect = Rect()
    // Not transformerOutputRect: a running pass holds that one, and its callbacks can move views.
    internal val skipCheckRect: Rect = Rect()

    internal var visibilityOutputs: List<VisibilityOutput> = emptyList()
    internal var renderUnitIdsWhichHostRenderTrees: LongSet? = null
    internal var currentLocalVisibleRect: Rect? = null
    internal var visibilityBoundsTransformer: VisibilityBoundsTransformer? = null
    internal var input: VisibilityExtensionInput? = null

    /** Tracks the most recent non-UNKNOWN visibility change reason from onVisibleBoundsChanged. */
    internal var lastReason: VisibilityChangeReason = VisibilityChangeReason.UNKNOWN

    /** @deprecated Only used for Litho's integration. Marked for removal. */
    @Deprecated("") internal var rootHost: Host? = null
  }

  companion object {
    private val instance: VisibilityMountExtension<*> =
        VisibilityMountExtension<VisibilityExtensionInput>()

    /** Empty rect used when the host is detached from window to trigger invisibility events. */
    private val EMPTY_RECT = Rect()

    /**
     * Returns the singleton instance of [VisibilityMountExtension].
     *
     * The unchecked cast is safe because [VisibilityMountExtension] does not perform any
     * type-specific operations on the generic parameter [T] at runtime. The generic parameter
     * exists only for compile-time type safety to ensure callers provide compatible
     * [VisibilityExtensionInput] types. Due to JVM type erasure, all [VisibilityMountExtension]
     * instances are identical at runtime regardless of their type parameter, making this singleton
     * pattern safe.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    @android.annotation.SuppressLint("KotlinGenericsCast")
    fun <T : VisibilityExtensionInput> getInstance(): VisibilityMountExtension<T> {
      return instance as VisibilityMountExtension<T>
    }

    @VisibleForTesting
    @JvmStatic
    fun getVisibilityIdToItemMap(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
    ): Map<Any, VisibilityItem> {
      return getVisibilityIdToItemMap(extensionState.state)
    }

    @JvmStatic
    fun getVisibilityIdToItemMap(state: VisibilityMountExtensionState): Map<Any, VisibilityItem> {
      return checkNotNull(state.visibilityIdToItemMap).asMap()
    }

    @UiThread
    @JvmStatic
    fun clearVisibilityItems(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
        reason: VisibilityChangeReason,
    ) {
      val state = extensionState.state

      if (reason != VisibilityChangeReason.UNKNOWN) {
        state.lastReason = reason
      }
      clearVisibilityItemsNonincremental(extensionState.renderStateId, state, reason)
      state.previousLocalVisibleRect.setEmpty()
      state.previousTransformedVisibleRect.setEmpty()
    }

    /** @deprecated Only used for Litho's integration. Marked for removal. */
    @Deprecated("")
    @JvmStatic
    fun setRootHost(extensionState: ExtensionState<VisibilityMountExtensionState>, root: Host) {
      val state = extensionState.state
      state.rootHost = root
    }

    @JvmStatic
    fun notifyOnUnbind(extensionState: ExtensionState<VisibilityMountExtensionState>) {
      clearVisibilityItems(extensionState, VisibilityChangeReason.UNKNOWN)
    }

    /**
     * Process visibility outputs with the given reason.
     *
     * @param extensionState the extension state
     * @param localVisibleRect the current visible rect
     * @param isDirty whether the mount state is dirty
     * @param reason the reason for visibility change
     */
    @UiThread
    @JvmStatic
    fun processVisibilityOutputs(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
        localVisibleRect: Rect?,
        isDirty: Boolean,
        reason: VisibilityChangeReason,
    ) {
      val state = extensionState.state
      val isTracing = RenderCoreSystrace.isTracing()

      // When enabled, use an empty rect if the root host is detached from the window.
      // This causes all visibility outputs to be treated as invisible, properly firing
      // InvisibleEvent, UnfocusedVisibleEvent, etc. This prevents incorrect visibility events
      // caused by View.getLocalVisibleRect() returning stale/full rects for detached views.
      // Note: View.isAttachedToWindow remains true during temporary detaches (e.g. RecyclerView
      // item recycling, disappearing transitions), so those legitimate cases are not affected.
      val host = getRootHost(extensionState)
      val isUsingEmptyRectForDetach =
          state.input?.useEmptyVisibleRectOnDetach == true && host?.isAttachedToWindow == false
      val effectiveRect = if (isUsingEmptyRectForDetach) EMPTY_RECT else localVisibleRect

      try {
        if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
          Log.d(DEBUG_TAG, "processVisibilityOutputs")
        }
        if (isTracing) {
          RenderCoreSystrace.beginSection("VisibilityExtension.processVisibilityOutputs")
        }

        processVisibilityOutputsNonInc(
            extensionState,
            effectiveRect,
            isDirty,
            isUsingEmptyRectForDetach,
            reason,
        )
      } finally {
        if (isTracing) {
          RenderCoreSystrace.endSection()
        }
      }

      // Save the effective rect (empty when detached) to previousLocalVisibleRect,
      // so that the skip optimization works correctly with the detached state.
      if (effectiveRect != null) {
        state.previousLocalVisibleRect.set(effectiveRect)
      }
    }

    @UiThread
    private fun processVisibilityOutputsNonInc(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
        localVisibleRect: Rect?,
        isDirty: Boolean,
        isUsingEmptyRectForDetach: Boolean = false,
        reason: VisibilityChangeReason,
    ) {
      if (localVisibleRect == null) {
        if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
          Log.d(DEBUG_TAG, "SKIP: local visible rect is null.")
        }
        return
      }

      val previousVisibleRect = extensionState.state.previousLocalVisibleRect
      // When using empty rect for detached state, don't use the transformer
      // since it would return stale data from getLocalVisibleRect()
      val transformer: VisibilityBoundsTransformer? =
          if (isUsingEmptyRectForDetach) null else extensionState.state.visibilityBoundsTransformer

      var transformedLocalVisibleRect: Rect? = null
      if (!isDirty && previousVisibleRect == localVisibleRect) {
        // We can skip processing because the visible rect doesn't change, but we have to check if
        // there's a transformed rect to process.

        var hasTransformedRectToProcess = false
        if (transformer != null) {
          transformedLocalVisibleRect =
              transformRootHostVisibleRect(
                  extensionState,
                  transformer,
                  extensionState.state.transformerOutputRect,
              )
          if (extensionState.state.previousTransformedVisibleRect != transformedLocalVisibleRect) {
            //  We can skip processing because the transformed visible rect doesn't change as
            // well.
            hasTransformedRectToProcess = true
            extensionState.state.previousTransformedVisibleRect.set(transformedLocalVisibleRect)
          }
        }

        if (!hasTransformedRectToProcess) {
          if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
            Log.d(
                DEBUG_TAG,
                "Skip Processing: " +
                    "[isDirty=" +
                    isDirty +
                    ", previousVisibleRect=" +
                    previousVisibleRect +
                    ", previousTransformedVisibleRect=" +
                    extensionState.state.previousTransformedVisibleRect +
                    "]",
            )
          }

          return
        }
      }

      val state = extensionState.state
      val size = state.visibilityOutputs.size

      if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
        Log.d(DEBUG_TAG, "Visibility Outputs to process: $size")
      }

      if (extensionState.state.visibilityOutputs.isNotEmpty()) {
        if (transformer != null && transformedLocalVisibleRect == null) {
          transformedLocalVisibleRect =
              transformRootHostVisibleRect(
                  extensionState,
                  transformer,
                  extensionState.state.transformerOutputRect,
              )
        }

        val isTracing = RenderCoreSystrace.isTracing()

        val isOptimized =
            VisibilityExtensionConfigs.isOptimizedVisibilityOutputProcessingEnabled &&
                !localVisibleRect.isEmpty
        val effectiveViewportTop: Int
        val effectiveViewportBottom: Int
        if (isOptimized) {
          val top = localVisibleRect.top
          val bottom = localVisibleRect.bottom
          if (transformedLocalVisibleRect != null) {
            effectiveViewportTop = minOf(top, transformedLocalVisibleRect.top)
            effectiveViewportBottom = maxOf(bottom, transformedLocalVisibleRect.bottom)
          } else {
            effectiveViewportTop = top
            effectiveViewportBottom = bottom
          }
        } else {
          effectiveViewportTop = 0
          effectiveViewportBottom = 0
        }

        val intersection = Rect()
        for (j in 0 until size) {
          val visibilityOutput = state.visibilityOutputs[j]
          val visibilityOutputBounds = visibilityOutput.bounds

          if (
              isOptimized &&
                  (visibilityOutputBounds.bottom < effectiveViewportTop ||
                      visibilityOutputBounds.top > effectiveViewportBottom)
          ) {
            val visibilityOutputId = visibilityOutput.id
            val trackedItem = state.visibilityIdToItemMap?.get(visibilityOutputId)
            if (trackedItem != null) {
              dispatchOffScreenInvisible(
                  extensionState.renderStateId,
                  state,
                  visibilityOutput,
                  trackedItem,
                  visibilityOutputId,
                  reason,
              )
            }
            continue
          }

          val componentName = visibilityOutput.key

          if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
            Log.d(DEBUG_TAG, "Processing Visibility for: $componentName")
          }
          if (isTracing) {
            RenderCoreSystrace.beginSection("visibilityHandlers:$componentName")
          }

          val shouldUseTransformedVisibleRect =
              transformer != null && transformer.shouldUseTransformedVisibleRect(visibilityOutput)
          val boundsIntersect =
              intersection.setIntersect(
                  visibilityOutputBounds,
                  if (shouldUseTransformedVisibleRect && transformedLocalVisibleRect != null)
                      transformedLocalVisibleRect
                  else localVisibleRect,
              )
          val isFullyVisible = boundsIntersect && intersection == visibilityOutputBounds
          val visibilityOutputId = visibilityOutput.id
          var visibilityItem = state.visibilityIdToItemMap?.get(visibilityOutputId)

          val wasFullyVisible: Boolean
          if (visibilityItem != null) {
            wasFullyVisible = visibilityItem.wasFullyVisible()
            visibilityItem.setWasFullyVisible(isFullyVisible)
          } else {
            wasFullyVisible = false
          }

          if (isFullyVisible && wasFullyVisible) {
            // VisibilityOutput is still fully visible, no new events to dispatch, skip to next
            if (isTracing) {
              RenderCoreSystrace.endSection()
            }
            if (visibilityItem != null) {
              visibilityItem.setDoNotClearInThisPass(isDirty)
            }
            continue
          }

          val onVisible: VisibilityEventCallbackData? = visibilityOutput.onVisible
          val onFocusedVisible: VisibilityEventCallbackData? = visibilityOutput.onFocusedVisible
          val onUnfocusedVisible: VisibilityEventCallbackData? = visibilityOutput.onUnfocusedVisible
          val onFullImpression: VisibilityEventCallbackData? = visibilityOutput.onFullImpression
          val onInvisible: VisibilityEventCallbackData? = visibilityOutput.onInvisible
          val onVisibilityChange: VisibilityEventCallbackData? = visibilityOutput.onVisibilityChange

          val isCurrentlyVisible =
              boundsIntersect &&
                  isInVisibleRange(visibilityOutput, visibilityOutputBounds, intersection)

          if (visibilityItem != null) {
            // If we did a relayout due to e.g. a state update then the handlers will have changed,
            // so we should keep them up to date.
            visibilityItem.unfocusedHandler = onUnfocusedVisible
            visibilityItem.invisibleHandler = onInvisible

            if (!isCurrentlyVisible) {
              // Either the component is invisible now, but used to be visible, or the key on the
              // component has changed so we should generate new visibility events for the new
              // component.
              maybeDispatchOnInvisible(extensionState.renderStateId, visibilityItem, reason)

              if (onVisibilityChange != null) {
                val visChangedTraceId: Int? =
                    DebugEventDispatcher.generateTraceIdentifier(
                        DebugEvent.RenderUnitOnVisibilityChanged,
                    )
                if (visChangedTraceId != null) {
                  DebugEventDispatcher.beginTrace(
                      traceIdentifier = visChangedTraceId,
                      type = DebugEvent.RenderUnitOnVisibilityChanged,
                      renderStateId = extensionState.renderStateId.toString(),
                      attributes =
                          createVisibilityDebugAttributes(visibilityItem, "onVisibilityChanged"),
                  )
                }
                VisibilityUtils.dispatchOnVisibilityChanged(
                    visibilityChangedHandler = onVisibilityChange.callback,
                    visibleTop = 0,
                    visibleLeft = 0,
                    visibleWidth = 0,
                    visibleHeight = 0,
                    rootHostViewWidth = 0,
                    rootHostViewHeight = 0,
                    percentVisibleWidth = 0f,
                    percentVisibleHeight = 0f,
                    reason = reason,
                )
                if (visChangedTraceId != null) {
                  DebugEventDispatcher.endTrace(visChangedTraceId)
                }
              }

              if (visibilityItem.isInFocusedRange) {
                visibilityItem.setFocusedRange(false)
                if (visibilityItem.unfocusedHandler != null) {
                  val unfocusedTraceId: Int? =
                      DebugEventDispatcher.generateTraceIdentifier(DebugEvent.RenderUnitOnUnfocused)
                  if (unfocusedTraceId != null) {
                    DebugEventDispatcher.beginTrace(
                        traceIdentifier = unfocusedTraceId,
                        type = DebugEvent.RenderUnitOnUnfocused,
                        renderStateId = extensionState.renderStateId.toString(),
                        attributes = createVisibilityDebugAttributes(visibilityItem, "onUnfocused"),
                    )
                  }
                  VisibilityUtils.dispatchOnUnfocused(
                      requireNotNull(visibilityItem.unfocusedHandler).callback,
                      reason,
                  )
                  if (unfocusedTraceId != null) {
                    DebugEventDispatcher.endTrace(unfocusedTraceId)
                  }
                }
              }

              state.visibilityIdToItemMap?.remove(visibilityOutputId)
              visibilityItem = null
            } else {
              // Processed, do not clear.
              visibilityItem.setDoNotClearInThisPass(isDirty)
            }
          }

          if (isCurrentlyVisible) {
            // The component is visible now, but used to be outside the viewport.
            if (visibilityItem == null) {
              val globalKey = visibilityOutput.id
              visibilityItem =
                  VisibilityItem(
                      key = globalKey,
                      invisibleHandler = onInvisible,
                      unfocusedHandler = onUnfocusedVisible,
                      visibilityChangedHandler = onVisibilityChange,
                      componentName = visibilityOutput.key,
                      componentClassName = visibilityOutput.componentClassName,
                      renderUnitId = visibilityOutput.renderUnitId,
                      bounds = visibilityOutput.bounds,
                  )

              visibilityItem.setDoNotClearInThisPass(isDirty)
              visibilityItem.setWasFullyVisible(isFullyVisible)
              state.visibilityIdToItemMap?.set(visibilityOutputId, visibilityItem)

              if (onVisible != null) {
                val content: Any? =
                    if (visibilityOutput.hasMountableContent)
                        getContentById(extensionState, visibilityOutput.renderUnitId)
                    else null

                val traceIdentifier: Int? =
                    DebugEventDispatcher.generateTraceIdentifier(DebugEvent.RenderUnitOnVisible)
                if (traceIdentifier != null) {
                  DebugEventDispatcher.beginTrace(
                      traceIdentifier = traceIdentifier,
                      type = DebugEvent.RenderUnitOnVisible,
                      renderStateId = extensionState.renderStateId.toString(),
                      attributes = createVisibilityDebugAttributes(visibilityItem, "onVisible"),
                  )
                }

                VisibilityUtils.dispatchOnVisible(onVisible.callback, content, reason)

                if (traceIdentifier != null) {
                  DebugEventDispatcher.endTrace(traceIdentifier)
                }
              }
            }

            var transformedViewportRect: Rect? = null
            if (
                onFocusedVisible != null || onUnfocusedVisible != null || onVisibilityChange != null
            ) {
              if (shouldUseTransformedVisibleRect) {
                val host = getRootHost(extensionState)
                if (host != null && (host.parent is View)) {
                  val parent = host.parent as View
                  val outRect = extensionState.state.viewportOutputRect.apply { setEmpty() }
                  checkNotNull(transformer).getViewportRect(parent, outRect)
                  transformedViewportRect = outRect
                }
              }
            }

            // Check if the component has entered or exited the focused range.
            if (onFocusedVisible != null || onUnfocusedVisible != null) {
              if (
                  isInFocusedRange(
                      extensionState = extensionState,
                      componentBounds = visibilityOutputBounds,
                      componentVisibleBounds = intersection,
                      shouldUseTransformedVisibleRect = shouldUseTransformedVisibleRect,
                      transformedViewportRect = transformedViewportRect,
                  )
              ) {
                if (!visibilityItem.isInFocusedRange) {
                  visibilityItem.setFocusedRange(true)
                  if (onFocusedVisible != null) {
                    val focusedTraceId: Int? =
                        DebugEventDispatcher.generateTraceIdentifier(DebugEvent.RenderUnitOnFocused)
                    if (focusedTraceId != null) {
                      DebugEventDispatcher.beginTrace(
                          traceIdentifier = focusedTraceId,
                          type = DebugEvent.RenderUnitOnFocused,
                          renderStateId = extensionState.renderStateId.toString(),
                          attributes = createVisibilityDebugAttributes(visibilityItem, "onFocused"),
                      )
                    }
                    VisibilityUtils.dispatchOnFocused(
                        onFocusedVisible.callback,
                        reason,
                    )
                    if (focusedTraceId != null) {
                      DebugEventDispatcher.endTrace(focusedTraceId)
                    }
                  }
                }
              } else {
                if (visibilityItem.isInFocusedRange) {
                  visibilityItem.setFocusedRange(false)
                  if (onUnfocusedVisible != null) {
                    val unfocusedTraceId: Int? =
                        DebugEventDispatcher.generateTraceIdentifier(
                            DebugEvent.RenderUnitOnUnfocused,
                        )
                    if (unfocusedTraceId != null) {
                      DebugEventDispatcher.beginTrace(
                          traceIdentifier = unfocusedTraceId,
                          type = DebugEvent.RenderUnitOnUnfocused,
                          renderStateId = extensionState.renderStateId.toString(),
                          attributes =
                              createVisibilityDebugAttributes(visibilityItem, "onUnfocused"),
                      )
                    }
                    VisibilityUtils.dispatchOnUnfocused(
                        onUnfocusedVisible.callback,
                        reason,
                    )
                    if (unfocusedTraceId != null) {
                      DebugEventDispatcher.endTrace(unfocusedTraceId)
                    }
                  }
                }
              }
            }

            // If the component has not entered the full impression range yet, make sure to update
            // the information about the visible edges.
            if (onFullImpression != null && !visibilityItem.isInFullImpressionRange) {
              visibilityItem.setVisibleEdges(visibilityOutputBounds, intersection)

              if (visibilityItem.isInFullImpressionRange) {
                val content: Any? =
                    if (visibilityOutput.hasMountableContent)
                        getContentById(extensionState, visibilityOutput.renderUnitId)
                    else null
                val fullImpressionTraceId: Int? =
                    DebugEventDispatcher.generateTraceIdentifier(
                        DebugEvent.RenderUnitOnFullImpression,
                    )
                if (fullImpressionTraceId != null) {
                  DebugEventDispatcher.beginTrace(
                      traceIdentifier = fullImpressionTraceId,
                      type = DebugEvent.RenderUnitOnFullImpression,
                      renderStateId = extensionState.renderStateId.toString(),
                      attributes =
                          createVisibilityDebugAttributes(visibilityItem, "onFullImpression"),
                  )
                }
                VisibilityUtils.dispatchOnFullImpression(onFullImpression.callback, content, reason)
                if (fullImpressionTraceId != null) {
                  DebugEventDispatcher.endTrace(fullImpressionTraceId)
                }
              }
            }

            if (onVisibilityChange != null) {
              val visibleWidth = getVisibleWidth(intersection)
              val visibleHeight = getVisibleHeight(intersection)
              var rootHostViewWidth = getRootHostViewWidth(extensionState)
              var rootHostViewHeight = getRootHostViewHeight(extensionState)
              if (shouldUseTransformedVisibleRect && transformedViewportRect != null) {
                rootHostViewWidth = transformedViewportRect.width()
                rootHostViewHeight = transformedViewportRect.height()
              }

              val visChangedTraceId: Int? =
                  DebugEventDispatcher.generateTraceIdentifier(
                      DebugEvent.RenderUnitOnVisibilityChanged,
                  )
              if (visChangedTraceId != null) {
                DebugEventDispatcher.beginTrace(
                    traceIdentifier = visChangedTraceId,
                    type = DebugEvent.RenderUnitOnVisibilityChanged,
                    renderStateId = extensionState.renderStateId.toString(),
                    attributes =
                        createVisibilityDebugAttributes(visibilityItem, "onVisibilityChanged"),
                )
              }
              VisibilityUtils.dispatchOnVisibilityChanged(
                  visibilityChangedHandler = onVisibilityChange.callback,
                  visibleTop = getVisibleTop(visibilityOutputBounds, intersection),
                  visibleLeft = getVisibleLeft(visibilityOutputBounds, intersection),
                  visibleWidth = visibleWidth,
                  visibleHeight = visibleHeight,
                  rootHostViewWidth = rootHostViewWidth,
                  rootHostViewHeight = rootHostViewHeight,
                  percentVisibleWidth = 100f * visibleWidth / visibilityOutputBounds.width(),
                  percentVisibleHeight = 100f * visibleHeight / visibilityOutputBounds.height(),
                  reason = reason,
              )
              if (visChangedTraceId != null) {
                DebugEventDispatcher.endTrace(visChangedTraceId)
              }
            }
          }

          if (isTracing) {
            RenderCoreSystrace.endSection()
          }
        }
      }

      val mountDelegate = extensionState.mountDelegate
      state.renderUnitIdsWhichHostRenderTrees?.forEach { id ->
        if (VisibilityExtensionConfigs.isDebugLoggingEnabled) {
          Log.d(DEBUG_TAG, "RecursivelyNotify:RenderUnit[id=$id]")
        }
        mountDelegate.notifyVisibleBoundsChangedForItem(mountDelegate.getContentById(id), reason)
      }

      if (isDirty) {
        clearVisibilityItems(extensionState, reason)
      }
      // clearVisibilityItems empties the recorded rect, so record it after: the next pass compares
      // against it to decide whether it can skip. Only the tree's outputs and nested trees depend
      // on that. A tree without outputs computes no transformed rect on a dirty pass, or on one
      // whose local visible rect changed, so it computes one here only if it hosts nested trees.
      if (
          transformer?.shouldRecordTransformedVisibleRectAtEveryPass() == true &&
              (state.visibilityOutputs.isNotEmpty() ||
                  state.renderUnitIdsWhichHostRenderTrees?.isNotEmpty() == true)
      ) {
        state.previousTransformedVisibleRect.set(
            transformedLocalVisibleRect
                ?: transformRootHostVisibleRect(
                    extensionState,
                    transformer,
                    state.transformerOutputRect,
                ),
        )
      }
    }

    /** Runs [transformer] on the root host's visible rect into [outRect], and returns [outRect]. */
    private fun transformRootHostVisibleRect(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
        transformer: VisibilityBoundsTransformer,
        outRect: Rect,
    ): Rect {
      outRect.setEmpty()
      transformer.getTransformedLocalVisibleRect(extensionState.rootHost, outRect)
      return outRect
    }

    private fun createVisibilityDebugAttributes(
        visibilityItem: VisibilityItem,
        callbackType: String,
    ): Map<String, Any?> {
      val attributes: MutableMap<String, Any?> = HashMap()
      attributes[RenderUnitId] = visibilityItem.renderUnitId
      attributes[Name] = visibilityItem.componentName
      attributes[Bounds] = visibilityItem.bounds
      attributes[Key] = visibilityItem.key
      attributes[CallbackType] = callbackType
      if (visibilityItem.componentClassName != null) {
        attributes[ComponentClassName] = visibilityItem.componentClassName
      }
      return attributes
    }

    /**
     * This method will dispatch an {@code onInvisibleEvent} to the given {@link VisibilityItem}
     * only if it returns a non-null invisible handler from {@link
     * VisibilityItem#getInvisibleHandler()}.
     */
    private fun maybeDispatchOnInvisible(
        renderStateId: Int,
        visibilityItem: VisibilityItem,
        reason: VisibilityChangeReason,
    ) {
      if (visibilityItem.invisibleHandler == null) {
        return
      }

      val traceIdentifier: Int? =
          DebugEventDispatcher.generateTraceIdentifier(DebugEvent.RenderUnitOnInvisible)
      if (traceIdentifier != null) {
        DebugEventDispatcher.beginTrace(
            traceIdentifier = traceIdentifier,
            type = DebugEvent.RenderUnitOnInvisible,
            renderStateId = renderStateId.toString(),
            attributes = createVisibilityDebugAttributes(visibilityItem, "onInvisible"),
        )
      }

      VisibilityUtils.dispatchOnInvisible(
          requireNotNull(visibilityItem.invisibleHandler).callback,
          reason,
      )

      if (traceIdentifier != null) {
        DebugEventDispatcher.endTrace(traceIdentifier)
      }
    }

    private fun dispatchOffScreenInvisible(
        renderStateId: Int,
        state: VisibilityMountExtensionState,
        visibilityOutput: VisibilityOutput,
        visibilityItem: VisibilityItem,
        visibilityOutputId: Any,
        reason: VisibilityChangeReason,
    ) {
      visibilityItem.unfocusedHandler = visibilityOutput.onUnfocusedVisible
      visibilityItem.invisibleHandler = visibilityOutput.onInvisible

      maybeDispatchOnInvisible(renderStateId, visibilityItem, reason)

      val onVisibilityChange = visibilityOutput.onVisibilityChange
      if (onVisibilityChange != null) {
        VisibilityUtils.dispatchOnVisibilityChanged(
            visibilityChangedHandler = onVisibilityChange.callback,
            visibleTop = 0,
            visibleLeft = 0,
            visibleWidth = 0,
            visibleHeight = 0,
            rootHostViewWidth = 0,
            rootHostViewHeight = 0,
            percentVisibleWidth = 0f,
            percentVisibleHeight = 0f,
            reason = reason,
        )
      }

      if (visibilityItem.isInFocusedRange) {
        visibilityItem.setFocusedRange(false)
        if (visibilityItem.unfocusedHandler != null) {
          VisibilityUtils.dispatchOnUnfocused(
              requireNotNull(visibilityItem.unfocusedHandler).callback,
              reason,
          )
        }
      }

      visibilityItem.setWasFullyVisible(false)
      state.visibilityIdToItemMap?.remove(visibilityOutputId)
    }

    private fun isInVisibleRange(
        visibilityOutput: VisibilityOutput,
        bounds: Rect,
        visibleBounds: Rect,
    ): Boolean {
      val heightRatio = visibilityOutput.visibleHeightRatio
      val widthRatio = visibilityOutput.visibleWidthRatio

      if (heightRatio == 0f && widthRatio == 0f) {
        return true
      }

      return isInRatioRange(heightRatio, bounds.height(), visibleBounds.height()) &&
          isInRatioRange(widthRatio, bounds.width(), visibleBounds.width())
    }

    /** Returns true if the component is in the focused visible range. */
    private fun isInFocusedRange(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
        componentBounds: Rect,
        componentVisibleBounds: Rect,
        shouldUseTransformedVisibleRect: Boolean,
        transformedViewportRect: Rect?,
    ): Boolean {
      val host = getRootHost(extensionState) ?: return false
      if (host.parent !is View) {
        return false
      }
      val parent = host.parent as View

      var halfViewportArea = parent.width * parent.height / 2
      if (shouldUseTransformedVisibleRect && transformedViewportRect != null) {
        halfViewportArea = transformedViewportRect.width() * transformedViewportRect.height() / 2
      }

      val totalComponentArea = computeRectArea(componentBounds)
      val visibleComponentArea = computeRectArea(componentVisibleBounds)

      // The component has entered the focused range either if it is larger than half of the
      // viewport and it occupies at least half of the viewport or if it is smaller than half of
      // the viewport and it is fully visible.
      return if (totalComponentArea >= halfViewportArea) {
        visibleComponentArea >= halfViewportArea
      } else {
        componentBounds == componentVisibleBounds
      }
    }

    @UiThread
    private fun clearVisibilityItemsNonincremental(
        renderStateId: Int,
        state: VisibilityMountExtensionState,
        reason: VisibilityChangeReason,
    ) {
      val isTracing = RenderCoreSystrace.isTracing()
      if (isTracing) {
        RenderCoreSystrace.beginSection("VisibilityExtension.clearIncrementalItems")
      }

      val toClear: MutableList<Any> = ArrayList()

      state.visibilityIdToItemMap?.forEach { key, visibilityItem ->
        if (visibilityItem.doNotClearInThisPass()) {
          // This visibility item has already been accounted for in this pass, so ignore it.
          visibilityItem.setDoNotClearInThisPass(false)
        } else {
          toClear.add(key)
        }
      }

      for (i in toClear.indices) {
        val key = toClear[i]
        val visibilityItem = state.visibilityIdToItemMap?.get(key)

        if (visibilityItem != null) {
          val onUnfocused: VisibilityEventCallbackData? = visibilityItem.unfocusedHandler
          val onVisibilityChanged: VisibilityEventCallbackData? =
              visibilityItem.visibilityChangedHandler

          maybeDispatchOnInvisible(renderStateId, visibilityItem, reason)

          if (visibilityItem.isInFocusedRange) {
            visibilityItem.setFocusedRange(false)
            if (onUnfocused != null) {
              val unfocusedTraceId: Int? =
                  DebugEventDispatcher.generateTraceIdentifier(DebugEvent.RenderUnitOnUnfocused)
              if (unfocusedTraceId != null) {
                DebugEventDispatcher.beginTrace(
                    traceIdentifier = unfocusedTraceId,
                    type = DebugEvent.RenderUnitOnUnfocused,
                    renderStateId = renderStateId.toString(),
                    attributes = createVisibilityDebugAttributes(visibilityItem, "onUnfocused"),
                )
              }
              VisibilityUtils.dispatchOnUnfocused(onUnfocused.callback, reason)
              if (unfocusedTraceId != null) {
                DebugEventDispatcher.endTrace(unfocusedTraceId)
              }
            }
          }

          if (onVisibilityChanged != null) {
            val visChangedTraceId: Int? =
                DebugEventDispatcher.generateTraceIdentifier(
                    DebugEvent.RenderUnitOnVisibilityChanged,
                )
            if (visChangedTraceId != null) {
              DebugEventDispatcher.beginTrace(
                  traceIdentifier = visChangedTraceId,
                  type = DebugEvent.RenderUnitOnVisibilityChanged,
                  renderStateId = renderStateId.toString(),
                  attributes =
                      createVisibilityDebugAttributes(visibilityItem, "onVisibilityChanged"),
              )
            }
            VisibilityUtils.dispatchOnVisibilityChanged(
                visibilityChangedHandler = onVisibilityChanged.callback,
                visibleTop = 0,
                visibleLeft = 0,
                visibleWidth = 0,
                visibleHeight = 0,
                rootHostViewWidth = 0,
                rootHostViewHeight = 0,
                percentVisibleWidth = 0f,
                percentVisibleHeight = 0f,
                reason = reason,
            )
            if (visChangedTraceId != null) {
              DebugEventDispatcher.endTrace(visChangedTraceId)
            }
          }

          visibilityItem.setWasFullyVisible(false)
        }

        state.visibilityIdToItemMap?.remove(key)
      }

      if (isTracing) {
        RenderCoreSystrace.endSection()
      }
    }

    @JvmStatic
    fun shouldProcessVisibilityOutputs(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
    ): Boolean {
      val state = extensionState.state

      if (
          state.input != null && !requireNotNull(state.input).isProcessingVisibilityOutputsEnabled
      ) {
        return false
      }

      val host = getRootHost(extensionState)
      return host == null || !host.hasTransientState()
    }

    /**
     * Whether a visibility pass for this tree, given [visibleRect] as its local visible rect, would
     * end in the extension's own skip, with the transform leaving the whole rect or none of it: the
     * last pass saw [visibleRect] as its local rect, the recorded transformed rect is [visibleRect]
     * or empty, and the transformer, run on the root host now, returns that same rect. This doesn't
     * measure the root host's local visible rect, so the caller must already know it is
     * [visibleRect]. Only a transformer that records the transformed rect at every pass keeps that
     * record current, so a tree under any other transformer answers false. A tree with no outputs
     * and no nested trees may hold an older record, but nothing it dispatches depends on it.
     */
    @UiThread
    @JvmStatic
    fun isVisibleRectStillWholeOrCovered(
        extensionState: ExtensionState<VisibilityMountExtensionState>,
        visibleRect: Rect,
    ): Boolean {
      val state = extensionState.state
      val transformer = state.visibilityBoundsTransformer ?: return false
      val recordedRect = state.previousTransformedVisibleRect
      val wasLastPassWholeOrCovered =
          transformer.shouldRecordTransformedVisibleRectAtEveryPass() &&
              !visibleRect.isEmpty &&
              state.previousLocalVisibleRect == visibleRect &&
              (recordedRect == visibleRect || recordedRect.isEmpty)
      if (!wasLastPassWholeOrCovered) {
        return false
      }
      return transformRootHostVisibleRect(extensionState, transformer, state.skipCheckRect) ==
          recordedRect
    }

    private fun getRootHost(extensionState: ExtensionState<VisibilityMountExtensionState>): Host? {
      val state = extensionState.state

      return state.rootHost ?: extensionState.rootHost
    }

    private fun isInRatioRange(ratio: Float, length: Int, visibleLength: Int): Boolean {
      return visibleLength >= ratio * length
    }

    private fun computeRectArea(rect: Rect): Int {
      return if (rect.isEmpty) 0 else rect.width() * rect.height()
    }

    @JvmStatic
    fun isVisible(state: VisibilityMountExtensionState, key: Any): Boolean {
      return state.visibilityIdToItemMap?.containsKey(key) ?: false
    }

    @JvmStatic
    fun getVisibleTop(itemBounds: Rect, itemIntersection: Rect): Int {
      return itemIntersection.top - itemBounds.top
    }

    @JvmStatic
    fun getVisibleLeft(itemBounds: Rect, itemIntersection: Rect): Int {
      return itemIntersection.left - itemBounds.left
    }

    @JvmStatic
    fun getVisibleWidth(itemIntersection: Rect): Int {
      return itemIntersection.width()
    }

    @JvmStatic
    fun getVisibleHeight(itemIntersection: Rect): Int {
      return itemIntersection.height()
    }

    @JvmStatic
    fun getRootHostViewWidth(extensionState: ExtensionState<VisibilityMountExtensionState>): Int {
      val host = getRootHost(extensionState) ?: return 0
      if (host.parent !is View) {
        return 0
      }
      val parent = host.parent as View
      return parent.width
    }

    @JvmStatic
    fun getRootHostViewHeight(extensionState: ExtensionState<VisibilityMountExtensionState>): Int {
      val host = getRootHost(extensionState) ?: return 0
      if (host.parent !is View) {
        return 0
      }
      val parent = host.parent as View
      return parent.height
    }
  }
}
