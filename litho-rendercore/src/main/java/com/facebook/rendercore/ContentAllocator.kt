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

package com.facebook.rendercore

import android.content.Context
import com.facebook.rendercore.MountContentPools.ContentPool
import com.facebook.rendercore.MountContentPools.DefaultContentPool

/** Defines whether the content is a View or a Drawable. */
enum class RenderType {
  DRAWABLE,
  VIEW,
}

/**
 * Defines a class that can provide mountable content and have it be pooled.
 *
 * Instances must implement at least createContent and getRenderType methods to allocate the
 * RenderUnit content (View or Drawable) and to make it possible to query the type of the content.
 */
interface ContentAllocator<Content : Any> {

  /**
   * Allocates the mountable content (View or Drawable).
   *
   * **NOTE**: It is important that this method is idempotent and *only* dependent on the provided
   * [Context] parameter. Modifications of the returned content should only happen in
   * [RenderUnit.Binder]s where contextual information is available for mutating the content.
   */
  fun createContent(context: Context): Content

  /** Returns the [RenderType] of the mountable content. */
  val renderType: RenderType

  /** Returns the key that should be used for the pool for the mountable content. */
  fun getPoolKey(): Any {
    return javaClass
  }

  /**
   * Returns the [PoolingPolicy] which defines the behavior allowed regarding releasing and
   * acquiring this allocator's content.
   */
  val poolingPolicy: PoolingPolicy
    get() = PoolingPolicy.Default

  /**
   * This API informs the framework to fill the content pool for this Mountable ahead of time. The
   * default value is `false`, i.e. content is not pre-allocated. Pre-allocation of the content can
   * improve performance in some circumstances where creating the content is expensive.
   *
   * @return `true` to preallocate the content, otherwise `false`
   */
  fun canPreallocate(): Boolean = false

  /** This API informs the framework about the size of the content pool. The default is 3. */
  fun poolSize(): Int = DEFAULT_MAX_PREALLOCATION

  /**
   * Whether this content is pooled under a custom [PoolScope] when the renderer scopes only the
   * content that asks for it.
   */
  val usesCustomPoolScope: Boolean
    get() = false

  /**
   * Creates the content pool the framework should use for this [ContentAllocator].
   *
   * Note: [getPoolKey] is passed to [DefaultContentPool] so the pool's debug name reflects the
   * concrete component type (e.g., "com.facebook.litho.widget.Image") rather than the allocator
   * class name (e.g., "ViewAllocator"), which would be identical for all view-based components.
   */
  fun onCreateMountContentPool(poolSizeOverride: Int = UNSET_POOL_SIZE): ContentPool? {
    val size = if (poolSizeOverride > UNSET_POOL_SIZE) poolSizeOverride else poolSize()
    return DefaultContentPool(getPoolKey(), size)
  }

  /**
   * Called when the framework wants to acquire a pooled content item from the pool. Allows for
   * customization of the acquiring logic.
   */
  fun acquireContent(context: Context, poolScope: PoolScope = PoolScope.None): Any {
    return MountContentPools.acquireMountContent(context, this, poolScope)
  }

  /**
   * Called when the framework wants to release [content] to the pool. Allows for customization of
   * the releasing logic.
   */
  fun recycleContent(context: Context, content: Any, poolScope: PoolScope = PoolScope.None) {
    MountContentPools.recycle(context, this, content, poolScope)
  }

  /**
   * Returns a lambda that will be invoked when this [content] is discarded and should dispose any
   * resources it may be holding onto, because the pool is either full or has been cleared.
   */
  val onContentDiscarded: ((Any) -> Unit)?
    get() = null

  companion object {
    /** Default size of the content pool. */
    const val DEFAULT_MAX_PREALLOCATION: Int = 3
    const val UNSET_POOL_SIZE: Int = -1
  }
}
