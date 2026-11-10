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
import android.graphics.drawable.Drawable
import androidx.annotation.VisibleForTesting
import com.facebook.litho.drawable.DrawableUtils

@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
class DrawableComponent
private constructor(
    private val drawable: Drawable,
    private val drawableWidth: Int,
    private val drawableHeight: Int,
) : SpecGeneratedComponent("DrawableComponent") {

  override fun onBoundsDefined(
      c: ComponentContext,
      layout: ComponentLayout,
      interStagePropsContainer: InterStagePropsContainer?,
  ): Unit = Unit

  override fun onCreateMountContent(c: Context): Any = MatrixDrawable<Drawable>()

  override fun onMount(
      context: ComponentContext?,
      content: Any,
      interStagePropsContainer: InterStagePropsContainer?,
  ) {
    @Suppress("UNCHECKED_CAST") val drawable = content as MatrixDrawable<Drawable>
    drawable.mount(this.drawable)
  }

  override fun onBind(
      c: ComponentContext?,
      mountedContent: Any,
      interStagePropsContainer: InterStagePropsContainer?,
  ) {
    val mountedDrawable = mountedContent as MatrixDrawable<*>
    mountedDrawable.bind(drawableWidth, drawableHeight)
  }

  override fun onUnmount(
      context: ComponentContext?,
      mountedContent: Any,
      interStagePropsContainer: InterStagePropsContainer?,
  ) {
    val matrixDrawable = mountedContent as MatrixDrawable<*>
    matrixDrawable.unmount()
  }

  override fun isPureRender(): Boolean = true

  override fun getMountType(): MountType = MountType.DRAWABLE

  override fun isEquivalentProps(o: Component?): Boolean {
    if (this === o) {
      return true
    }
    if (o == null || o !is DrawableComponent) {
      return false
    }
    return DrawableUtils.isEquivalentTo(drawable, o.drawable)
  }

  override fun canPreallocate(): Boolean = true

  companion object {
    @JvmStatic
    fun create(drawable: Drawable, width: Int, height: Int): DrawableComponent =
        DrawableComponent(drawable, width, height)
  }
}
