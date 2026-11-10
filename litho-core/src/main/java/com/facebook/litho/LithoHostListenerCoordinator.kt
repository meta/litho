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
import androidx.annotation.VisibleForTesting
import com.facebook.litho.DynamicPropsExtension.DynamicPropsExtensionState
import com.facebook.litho.TransitionsExtension.TransitionsExtensionState
import com.facebook.rendercore.MountDelegateTarget
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.VisibleBoundsCallbacks
import com.facebook.rendercore.incrementalmount.GapWorkerMode
import com.facebook.rendercore.incrementalmount.IncrementalMountExtension
import com.facebook.rendercore.incrementalmount.IncrementalMountExtension.IncrementalMountExtensionState
import com.facebook.rendercore.visibility.VisibilityChangeReason
import com.facebook.rendercore.visibility.VisibilityMountExtension
import com.facebook.rendercore.visibility.VisibilityMountExtension.VisibilityMountExtensionState

/** Helper for dispatching events to multiple MountListenerExtensions in Litho. */
class LithoHostListenerCoordinator(private val mountDelegateTarget: MountDelegateTarget) {

  private var nestedLithoViewsExtensionState: ExtensionState<Void?>? = null
  private var dynamicPropsExtensionState: ExtensionState<DynamicPropsExtensionState>? = null
  internal var visibilityExtensionState: ExtensionState<VisibilityMountExtensionState>? = null
    private set

  private var transitionsExtensionState: ExtensionState<TransitionsExtensionState>? = null
  private var styleTransitionsExtensionState:
      ExtensionState<StyleTransitionsExtension.StyleTransitionsExtensionState>? =
      null
  private var incrementalMountExtensionState: ExtensionState<IncrementalMountExtensionState>? = null
  private var endToEndTestingExtensionState: ExtensionState<Void?>? = null

  fun setCollectNotifyVisibleBoundsChangedCalls(value: Boolean) {
    val mountDelegate = mountDelegateTarget.getMountDelegate()

    mountDelegate?.setCollectVisibleBoundsChangedCalls(value)
  }

  private fun startNotifyVisibleBoundsChangedSection() {
    val mountDelegate = mountDelegateTarget.getMountDelegate()

    mountDelegate?.startNotifyVisibleBoundsChangedSection()
  }

  private fun endNotifyVisibleBoundsChangedSection() {
    val mountDelegate = mountDelegateTarget.getMountDelegate()

    mountDelegate?.endNotifyVisibleBoundsChangedSection()
  }

  fun beforeMount(input: LayoutState, localVisibleRect: Rect?) {
    nestedLithoViewsExtensionState?.beforeMount(localVisibleRect, input)

    transitionsExtensionState?.beforeMount(localVisibleRect, input)

    styleTransitionsExtensionState?.beforeMount(localVisibleRect, input)

    endToEndTestingExtensionState?.beforeMount(localVisibleRect, input)

    dynamicPropsExtensionState?.beforeMount(localVisibleRect, input)

    visibilityExtensionState?.beforeMount(localVisibleRect, input)

    incrementalMountExtensionState?.beforeMount(localVisibleRect, input)
  }

  fun processVisibilityOutputs(
      localVisibleRect: Rect,
      isDirty: Boolean,
      reason: VisibilityChangeReason,
  ) {
    startNotifyVisibleBoundsChangedSection()

    visibilityExtensionState?.let { extensionState ->
      if (isDirty) {
        val processVisibilityOutputs =
            VisibilityMountExtension.shouldProcessVisibilityOutputs(extensionState)

        if (processVisibilityOutputs) {
          VisibilityMountExtension.processVisibilityOutputs(
              extensionState,
              localVisibleRect,
              true,
              reason,
          )
        }
      } else {
        val extension = extensionState.extension
        if (extension is VisibleBoundsCallbacks<*>) {
          // Cast to erased Kotlin generics type reference. The type argument is only checked at
          // compile time. This cast is safe because we know extensionState contains
          // VisibilityMountExtensionState based on the visibilityExtensionState type declaration.
          // See https://kotlinlang.org/docs/generics.html#type-erasure
          @Suppress("UNCHECKED_CAST") @android.annotation.SuppressLint("KotlinGenericsCast")
          (extension as VisibleBoundsCallbacks<VisibilityMountExtensionState>)
              .onVisibleBoundsChanged(extensionState, localVisibleRect, reason)
        }
      }
    }

    endNotifyVisibleBoundsChangedSection()
  }

  internal fun enableIncrementalMount(mode: GapWorkerMode) {
    // A reused LithoView keeps its first mode until unmountAllItems drops this coordinator.
    if (incrementalMountExtensionState != null) {
      return
    }

    incrementalMountExtensionState =
        mountDelegateTarget.registerMountExtension(IncrementalMountExtension.getInstance(mode))
  }

  internal fun disableIncrementalMount() {
    if (incrementalMountExtensionState == null) {
      return
    }

    val mountDelegate = mountDelegateTarget.getMountDelegate()
    mountDelegate?.unregisterMountExtension(
        requireNotNull(incrementalMountExtensionState).extension,
    )

    incrementalMountExtensionState = null
  }

  internal fun enableVisibilityProcessing(lithoView: BaseMountingView) {
    if (visibilityExtensionState != null) {
      return
    }

    visibilityExtensionState =
        mountDelegateTarget.registerMountExtension(VisibilityMountExtension.getInstance())
    visibilityExtensionState?.let { extensionState ->
      VisibilityMountExtension.setRootHost(extensionState, lithoView)
    }
  }

  internal fun disableVisibilityProcessing() {
    if (visibilityExtensionState == null) {
      return
    }

    val mountDelegate = mountDelegateTarget.getMountDelegate()
    mountDelegate?.unregisterMountExtension(requireNotNull(visibilityExtensionState).extension)

    visibilityExtensionState = null
  }

  internal fun enableEndToEndTestProcessing() {
    if (endToEndTestingExtensionState != null) {
      throw IllegalStateException(
          "End to end test processing has already been enabled on this coordinator",
      )
    }

    endToEndTestingExtensionState =
        mountDelegateTarget.registerMountExtension(EndToEndTestingExtension(mountDelegateTarget))
  }

  internal fun enableNestedLithoViewsExtension() {
    if (nestedLithoViewsExtensionState != null) {
      throw IllegalStateException(
          "Nested LithoView extension has already been enabled on this coordinator",
      )
    }

    nestedLithoViewsExtensionState =
        mountDelegateTarget.registerMountExtension(NestedLithoViewsExtension())
  }

  @VisibleForTesting
  fun getVisibilityExtensionState(): ExtensionState<*>? {
    return visibilityExtensionState
  }

  @VisibleForTesting
  fun getIncrementalMountExtensionState(): ExtensionState<*>? {
    return incrementalMountExtensionState
  }

  internal fun clearLastMountedTreeId() {
    transitionsExtensionState?.let { extensionState ->
      TransitionsExtension.clearLastMountedTreeId(extensionState)
    }
  }

  internal val endToEndTestingExtension: EndToEndTestingExtension?
    get() = endToEndTestingExtensionState?.extension as? EndToEndTestingExtension

  internal fun enableTransitions() {
    if (transitionsExtensionState != null) {
      throw IllegalStateException("Transitions have already been enabled on this coordinator.")
    }

    transitionsExtensionState =
        mountDelegateTarget.registerMountExtension(
            TransitionsExtension.getInstance(
                if (AnimationsDebug.ENABLED) AnimationsDebug.TAG else null,
            ),
        )
  }

  @Suppress("USELESS_CAST")
  internal fun enableStyleTransitions() {
    if (styleTransitionsExtensionState != null) {
      throw IllegalStateException(
          "Style transitions have already been enabled on this coordinator.",
      )
    }

    styleTransitionsExtensionState =
        mountDelegateTarget.registerMountExtension(StyleTransitionsExtension)
            as ExtensionState<StyleTransitionsExtension.StyleTransitionsExtensionState>
  }

  internal fun collectAllTransitions(layoutState: LayoutState) {
    if (transitionsExtensionState == null) {
      return
    }

    transitionsExtensionState?.let { extensionState ->
      TransitionsExtension.collectAllTransitions(extensionState, layoutState)
    }
  }

  @VisibleForTesting
  internal fun useVisibilityExtension(extension: VisibilityMountExtension<*>) {
    visibilityExtensionState = mountDelegateTarget.registerMountExtension(extension)
  }

  fun enableDynamicProps() {
    if (dynamicPropsExtensionState != null) {
      return
    }

    dynamicPropsExtensionState = mountDelegateTarget.registerMountExtension(DynamicPropsExtension)
  }

  @get:VisibleForTesting
  val dynamicPropsManager: DynamicPropsManager?
    get() = dynamicPropsExtensionState?.state?.dynamicPropsManager

  fun clearVisibilityItems(reason: VisibilityChangeReason) {
    visibilityExtensionState?.let { extensionState ->
      VisibilityMountExtension.clearVisibilityItems(extensionState, reason)
    }
  }
}
