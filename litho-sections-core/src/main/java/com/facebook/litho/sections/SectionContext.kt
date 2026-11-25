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

package com.facebook.litho.sections

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.facebook.litho.ComponentContext
import com.facebook.litho.EventDispatchInfo
import com.facebook.litho.EventHandler
import com.facebook.litho.EventTrigger
import com.facebook.litho.GlobalKey
import com.facebook.litho.Handle
import com.facebook.litho.LithoConfiguration
import com.facebook.litho.StateContainer
import com.facebook.litho.StringGlobalKey
import com.facebook.litho.TreePropContainer
import com.facebook.litho.allNotNull
import com.facebook.litho.annotations.EventHandlerRebindMode
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.widget.SectionsDebug
import java.lang.ref.WeakReference

class SectionContext
@JvmOverloads
constructor(
    context: Context,
    config: LithoConfiguration? = LithoConfiguration(context),
    treeProps: TreePropContainer? = null,
) : ComponentContext(context, config, treeProps) {

  constructor(
      context: ComponentContext,
  ) : this(context.androidContext, context.lithoConfiguration, context.treePropContainerCopy)

  val keyHandler: KeyHandler = KeyHandler()

  private var strongSectionTree: SectionTree? = null
  private var weakSectionTree: WeakReference<SectionTree>? = null

  val sectionTree: SectionTree?
    get() = strongSectionTree ?: weakSectionTree?.get()

  fun hasCollectedSectionTree(): Boolean = weakSectionTree.let { it != null && it.get() == null }

  private fun setSectionTree(sectionTree: SectionTree?) {
    if (sectionTree != null && ComponentsConfiguration.weakSectionTreeHandles) {
      weakSectionTree = WeakReference(sectionTree)
    } else {
      strongSectionTree = sectionTree
    }
  }

  private fun copySectionTree(from: SectionContext) {
    strongSectionTree = from.strongSectionTree
    weakSectionTree = from.weakSectionTree
  }

  var treeLoadingEventHandler: EventHandler<LoadingEvent>? = null
    private set

  var changeSetCalculationState: ChangeSetCalculationState? = null
    private set

  private var scope: WeakReference<Section>? = null

  /**
   * Notify the [SectionTree] that it needs to synchronously perform a state update.
   *
   * @param stateUpdate state update to perform
   */
  override fun updateStateSync(stateUpdate: StateContainer.StateUpdate, attribution: String) {
    allNotNull(scope?.get(), sectionTree) { section, sectionTree ->
      if (SectionsDebug.ENABLED) {
        Log.d(
            SectionsDebug.TAG,
            "(${sectionTree.hashCode()}) updateState from ${stateUpdate.javaClass.name}",
        )
      }
      sectionTree.updateState(section.globalKey, stateUpdate, attribution)
    }
  }

  override fun updateStateLazy(stateUpdate: StateContainer.StateUpdate) {
    val sectionTree = sectionTree
    val section = scope?.get() ?: return

    sectionTree?.updateStateLazy(section.globalKey, stateUpdate)
  }

  /**
   * Notify the [SectionTree] that it needs to asynchronously perform a state update.
   *
   * @param stateUpdate state update to perform
   */
  override fun updateStateAsync(stateUpdate: StateContainer.StateUpdate, attribution: String) {
    allNotNull(sectionScope, sectionTree) { section, sectionTree ->
      if (SectionsDebug.ENABLED) {
        Log.d(
            SectionsDebug.TAG,
            "(${sectionTree.hashCode()}) updateStateAsync from ${stateUpdate.javaClass.name}",
        )
      }
      sectionTree.updateStateAsync(section.globalKey, stateUpdate, attribution)
    }
  }

  /** TODO: Add rebind mode to this API. */
  fun <E> newEventHandler(id: Int, params: Array<Any>?): EventHandler<E> {
    val section =
        scope?.get() ?: throw IllegalStateException("Called newEventHandler on a released Section")
    return EventHandler(id, EventHandlerRebindMode.REBIND, EventDispatchInfo(section, this), params)
  }

  /** @return New instance of [EventTrigger] that is created by the current mScope. */
  override fun <E> newEventTrigger(id: Int, childKey: String, handle: Handle?): EventTrigger<E> {
    val parentKey = scope?.get()?.globalKey ?: ""
    return EventTrigger(parentKey, id, childKey, handle)
  }

  // Section's globalKey is set once during construction, so the wrapper can be cached for the
  // lifetime of this SectionContext.
  @Volatile private var _cachedGlobalKey: StringGlobalKey? = null

  override val globalKey: GlobalKey
    get() {
      _cachedGlobalKey?.let {
        return it
      }
      val section =
          scope?.get()
              ?: throw IllegalStateException(
                  "globalKey cannot be accessed from a SectionContext without a scope",
              )
      val newKey = StringGlobalKey(section.globalKey)
      _cachedGlobalKey = newKey
      return newKey
    }

  val sectionScope: Section?
    get() = scope?.get()

  companion object {
    const val NO_SCOPE_EVENT_HANDLER: String = "SectionContext:NoScopeEventHandler"

    @JvmStatic
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun withSectionTree(context: SectionContext, sectionTree: SectionTree?): SectionContext {
      val sectionContext = SectionContext(context)
      sectionContext.setSectionTree(sectionTree)
      sectionContext.treeLoadingEventHandler = SectionTreeLoadingEventHandler(sectionTree)
      return sectionContext
    }

    @JvmStatic
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun withScope(context: SectionContext, scope: Section): SectionContext {
      val sectionContext = SectionContext(context)
      sectionContext.copySectionTree(context)
      sectionContext.treeLoadingEventHandler = context.treeLoadingEventHandler
      sectionContext.changeSetCalculationState = context.changeSetCalculationState
      sectionContext.scope = WeakReference(scope)
      return sectionContext
    }

    @JvmStatic
    fun forNewChangeSetCalculation(contextFromSectionTree: SectionContext): SectionContext {
      val sectionContext = SectionContext(contextFromSectionTree)
      sectionContext.copySectionTree(contextFromSectionTree)
      sectionContext.treeLoadingEventHandler = contextFromSectionTree.treeLoadingEventHandler
      sectionContext.changeSetCalculationState = ChangeSetCalculationState()
      return sectionContext
    }
  }
}
