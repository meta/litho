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
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import com.facebook.litho.ComponentTree
import com.facebook.litho.ContextUtils
import com.facebook.litho.ThreadUtils
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.widget.TextInputComponentSpec.ForMeasureEditText
import com.facebook.rendercore.ContentAllocator
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.PoolingPolicy
import com.facebook.rendercore.RenderType

/**
 * Pools the measurement-only `EditText` of a legacy [TextInput] in [MountContentPools], so that a
 * measure on the main thread can take one built on the background layout thread instead of
 * constructing it. Does nothing unless [ComponentsConfiguration.prefetchTextInputMeasureEditText]
 * is enabled.
 *
 * Pooled views are never released back ([PoolingPolicy.AcquireOnly]): each one measures once, so no
 * state is carried from one measure to the next.
 */
object TextInputMeasureEditTextAllocator : ContentAllocator<EditText> {

  private val handler: Handler by lazy { Handler(ComponentTree.getDefaultLayoutThreadLooper()) }

  override val renderType: RenderType = RenderType.VIEW

  override val poolingPolicy: PoolingPolicy = PoolingPolicy.AcquireOnly

  override fun poolSize(): Int = 1

  override fun createContent(context: Context): EditText {
    val configuration = Configuration(context.resources.configuration)
    return ForMeasureEditText(context).apply { builtConfiguration = configuration }
  }

  /**
   * Builds a view into the pool of [context], unless one is pooled, once the layout thread has no
   * other work queued. Nothing is built for a finishing or destroyed Activity.
   */
  @JvmStatic
  fun prefill(context: Context) {
    if (!ComponentsConfiguration.prefetchTextInputMeasureEditText) {
      return
    }
    handler.post {
      Looper.myQueue().addIdleHandler {
        if (ContextUtils.getValidActivityForContext(context) != null) {
          MountContentPools.maybePreallocateContent(context, TextInputMeasureEditTextAllocator)
        }
        false
      }
    }
  }

  /**
   * On the main thread, takes the view pooled for [context] and refills the pool. Returns null off
   * the main thread, when no view is pooled for [context], or if the view was built under another
   * configuration. It never creates a pool: only [prefill] does.
   */
  internal fun take(context: Context): ForMeasureEditText? {
    if (
        !ComponentsConfiguration.prefetchTextInputMeasureEditText ||
            !ThreadUtils.isMainThread ||
            MountContentPools.getContentPoolItemCount(context, this) == 0
    ) {
      return null
    }
    val editText = MountContentPools.acquireMountContent(context, this) as ForMeasureEditText
    prefill(context)
    return editText.takeIf { it.builtConfiguration?.diff(context.resources.configuration) == 0 }
  }
}
