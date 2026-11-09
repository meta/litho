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

import android.content.Context
import android.util.Log
import com.facebook.litho.LithoRenderUnit.Companion.getRenderUnit
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.rendercore.ContentAllocator
import com.facebook.rendercore.PoolScope
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.debug.DebugEventDispatcher.beginTrace
import com.facebook.rendercore.debug.DebugEventDispatcher.endTrace
import com.facebook.rendercore.debug.DebugEventDispatcher.generateTraceIdentifier

/**
 * Pre-allocate the mount content of all MountSpec in the given [ComponentTree].
 *
 * Note that this pre-allocation must be executed only after layout has been created.
 */
class ContentPreAllocator
constructor(
    private val treeId: Int,
    private val componentContext: ComponentContext,
    private val mountContentHandler: RunnableHandler,
    private val nodeSupplier: () -> List<RenderTreeNode>,
    private val preAllocator: (Context, ContentAllocator<*>, PoolScope) -> Boolean,
) {

  private val runnable = Runnable(::executeInternal)

  val isHandlerTracing: Boolean
    get() = mountContentHandler.isTracing()

  fun execute(tag: String) {
    mountContentHandler.remove(runnable)
    mountContentHandler.post(runnable, tag)
  }

  fun cancel() {
    mountContentHandler.remove(runnable)
  }

  fun executeSync() {
    runnable.run()
  }

  private fun executeInternal() {
    val traceIdentifier =
        generateTraceIdentifier(LithoDebugEvent.ComponentTreeMountContentPreallocated)
    if (traceIdentifier != null) {
      beginTrace(
          traceIdentifier,
          LithoDebugEvent.ComponentTreeMountContentPreallocated,
          treeId.toString(),
          hashMapOf(),
      )
    }

    ComponentsSystrace.trace({ "mount.preAllocateContent" }) {
      val mountOutputs = nodeSupplier()
      preAllocateMountContent(mountOutputs)
    }

    if (traceIdentifier != null) {
      endTrace(traceIdentifier)
    }
  }

  private fun preAllocateMountContent(mountableOutputs: List<RenderTreeNode>) {
    if (mountableOutputs.isEmpty()) return

    for (treeNode in mountableOutputs) {
      val component = getRenderUnit(treeNode).component
      if (
          !component.isSpecGeneratedComponentThatCanPreallocate() &&
              !treeNode.isPrimitiveThatCanPreallocate()
      ) {
        continue
      }
      val componentSimpleName = component.simpleName

      ComponentsSystrace.trace({ "mount.preAllocateContent:$componentSimpleName" }) {
        val preallocated = preAllocator(
            componentContext.androidContext,
            treeNode.renderUnit.contentAllocator,
            treeNode.poolScope,
        )
        Log.d(
            "ContentPreAllocator",
            "Preallocation of $componentSimpleName" + if (preallocated) " succeeded" else " failed",
        )
      }
    }
  }

  private fun RenderTreeNode.isPrimitiveThatCanPreallocate(): Boolean {
    val renderUnit = renderUnit
    return renderUnit is PrimitiveLithoRenderUnit &&
        renderUnit.primitiveRenderUnit.contentAllocator.canPreallocate()
  }

  private fun Component.isSpecGeneratedComponentThatCanPreallocate(): Boolean {
    return this is SpecGeneratedComponent && canPreallocate()
  }
}
