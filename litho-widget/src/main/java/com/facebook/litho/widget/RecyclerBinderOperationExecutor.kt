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

import com.facebook.litho.ComponentContext
import com.facebook.litho.widget.collection.OnDataBound

/**
 * An implementation of [RecyclerBinderUpdateCallback.OperationExecutor] that uses [RecyclerBinder].
 */
class RecyclerBinderOperationExecutor(
    private val recyclerBinder: RecyclerBinder,
    private val useBackgroundChangeSets: Boolean = false,
    private val onDataBound: OnDataBound? = null,
    private val onDataRendered:
        ((
            isDataChanged: Boolean,
            isMounted: Boolean,
            monoTimestampMs: Long,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
        ) -> Unit)? =
        null,
) : RecyclerBinderUpdateCallback.OperationExecutor {

  constructor(recyclerBinder: RecyclerBinder) : this(recyclerBinder, false, null, null)

  override fun executeOperations(
      c: ComponentContext,
      operations: List<RecyclerBinderUpdateCallback.Operation>,
  ) {

    for (operation in operations) {
      val components: List<RecyclerBinderUpdateCallback.ComponentContainer>? =
          operation.componentContainers
      var renderInfos: MutableList<RenderInfo>? = null
      if (components != null && components.size > 1) {
        renderInfos = ArrayList()
        for (component in components) {
          renderInfos.add(component.renderInfo ?: ComponentRenderInfo.createEmpty())
        }
      }

      if (useBackgroundChangeSets) {
        applyChangeSetSetAsync(operation, renderInfos)
      } else {
        applyChangeSetSetSync(operation, renderInfos)
      }
    }

    val isDataChanged = operations.isNotEmpty()
    val changeSetCompleteCallback =
        object : ChangeSetCompleteCallback {
          override fun onDataBound() {
            if (!isDataChanged) {
              return
            }
            onDataBound?.invoke()
          }

          override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) {
            // Leverage a combined callback from LazyCollections to return the correct index
            onDataRendered?.invoke(isDataChanged, isMounted, uptimeMillis, -1, -1)
          }
        }

    if (useBackgroundChangeSets) {
      recyclerBinder.notifyChangeSetCompleteAsync(isDataChanged, changeSetCompleteCallback)
    } else {
      recyclerBinder.notifyChangeSetComplete(isDataChanged, changeSetCompleteCallback)
    }
  }

  private fun applyChangeSetSetSync(
      operation: RecyclerBinderUpdateCallback.Operation,
      renderInfos: MutableList<RenderInfo>?,
  ) {
    when (operation.type) {
      RecyclerBinderUpdateCallback.Operation.INSERT ->
          if (renderInfos != null) {
            recyclerBinder.insertRangeAt(operation.index, renderInfos)
          } else {
            recyclerBinder.insertItemAt(
                operation.index,
                operation.componentContainers?.get(0)?.renderInfo,
            )
          }
      RecyclerBinderUpdateCallback.Operation.DELETE ->
          recyclerBinder.removeRangeAt(operation.index, operation.toIndex)
      RecyclerBinderUpdateCallback.Operation.MOVE ->
          recyclerBinder.moveItem(operation.index, operation.toIndex)
      RecyclerBinderUpdateCallback.Operation.UPDATE ->
          if (renderInfos != null) {
            recyclerBinder.updateRangeAt(operation.index, renderInfos)
          } else {
            recyclerBinder.updateItemAt(
                operation.index,
                operation.componentContainers?.get(0)?.renderInfo,
            )
          }
    }
  }

  private fun applyChangeSetSetAsync(
      operation: RecyclerBinderUpdateCallback.Operation,
      renderInfos: MutableList<RenderInfo>?,
  ) {
    when (operation.type) {
      RecyclerBinderUpdateCallback.Operation.INSERT ->
          if (renderInfos != null) {
            recyclerBinder.insertRangeAtAsync(operation.index, renderInfos)
          } else {
            recyclerBinder.insertItemAtAsync(
                operation.index,
                operation.componentContainers?.get(0)?.renderInfo
                    ?: ComponentRenderInfo.createEmpty(),
            )
          }
      RecyclerBinderUpdateCallback.Operation.DELETE ->
          recyclerBinder.removeRangeAtAsync(operation.index, operation.toIndex)
      RecyclerBinderUpdateCallback.Operation.MOVE ->
          recyclerBinder.moveItemAsync(operation.index, operation.toIndex)
      RecyclerBinderUpdateCallback.Operation.UPDATE ->
          if (renderInfos != null) {
            recyclerBinder.updateRangeAtAsync(operation.index, renderInfos)
          } else {
            recyclerBinder.updateItemAtAsync(
                operation.index,
                operation.componentContainers?.get(0)?.renderInfo,
            )
          }
    }
  }
}
