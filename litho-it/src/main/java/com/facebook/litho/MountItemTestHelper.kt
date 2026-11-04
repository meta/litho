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
import com.facebook.litho.LithoLayoutData.Companion.NO_Z_INDEX
import com.facebook.litho.MountSpecLithoRenderUnit.Companion.STATE_UNKNOWN
import com.facebook.rendercore.MountItem

object MountItemTestHelper {
  @JvmStatic
  fun create(
      component: Component,
      content: Any,
      context: ComponentContext,
      info: NodeInfo?,
      bounds: Rect?,
      flags: Int,
      importantForAccessibility: Int,
  ): MountItem {
    val unit: LithoRenderUnit =
        MountSpecLithoRenderUnit.create(
            0,
            component,
            null,
            context,
            info,
            flags,
            importantForAccessibility,
            STATE_UNKNOWN,
            0,
            null,
        )
    val width = bounds?.width() ?: 0
    val height = bounds?.height() ?: 0
    val node = create(
        unit,
        bounds ?: Rect(),
        null,
        LithoLayoutData(
            width,
            height,
            null,
            NO_Z_INDEX,
            null,
            if (component is SpecGeneratedComponent) {
              component.isMountSizeDependent
            } else {
              false
            },
            null,
        ),
        null,
    )
    return MountItem(node, content)
  }
}
