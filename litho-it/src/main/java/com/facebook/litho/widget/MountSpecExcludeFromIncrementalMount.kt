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

import android.widget.TextView
import com.facebook.litho.LifecycleStep
import com.facebook.litho.LifecycleTracker
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Size
import com.facebook.litho.Style
import com.facebook.litho.onCleanup
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator

class MountSpecExcludeFromIncrementalMount(
    private val lifecycleTracker: LifecycleTracker,
    private val style: Style? = null,
) : PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    // Test fixture for the opt-out itself.
    @Suppress("PrimitiveExcludeFromIncrementalMountDetector")
    shouldExcludeFromIncrementalMount = true

    useState { lifecycleTracker.addStep(LifecycleStep.ON_CREATE_INITIAL_STATE) }

    val measuredSize = useState { Size() }

    useEffect(lifecycleTracker) {
      lifecycleTracker.addStep(LifecycleStep.ON_ATTACHED)
      onCleanup { lifecycleTracker.addStep(LifecycleStep.ON_DETACHED) }
    }

    return LithoPrimitive(
        layoutBehavior =
            MountSpecExcludeFromIncrementalMountLayoutBehavior(
                lifecycleTracker,
                measuredSize.value,
            ),
        mountBehavior =
            MountBehavior(ViewAllocator { context -> TextView(context) }) {
              bind(lifecycleTracker) { textView ->
                lifecycleTracker.addStep(LifecycleStep.ON_MOUNT)

                onUnbind { lifecycleTracker.addStep(LifecycleStep.ON_UNMOUNT) }
              }
              bind(Any()) {
                lifecycleTracker.addStep(LifecycleStep.ON_BIND)

                onUnbind { lifecycleTracker.addStep(LifecycleStep.ON_UNBIND) }
              }
            },
        style = style,
    )
  }
}

private class MountSpecExcludeFromIncrementalMountLayoutBehavior(
    private val lifecycleTracker: LifecycleTracker,
    private val measuredSize: Size,
) : LayoutBehavior {
  override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {
    val width = sizeConstraints.maxWidth
    val height = sizeConstraints.maxHeight

    measuredSize.width = width
    measuredSize.height = height

    lifecycleTracker.addStep(LifecycleStep.ON_MEASURE, measuredSize)

    return PrimitiveLayoutResult(width = width, height = height, layoutData = measuredSize)
  }
}
