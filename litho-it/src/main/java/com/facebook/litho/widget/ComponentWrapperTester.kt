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

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LifecycleStep
import com.facebook.litho.LifecycleTracker
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Wrapper
import com.facebook.litho.eventHandler
import com.facebook.litho.useState
import com.facebook.rendercore.visibility.FocusedVisibleEvent
import com.facebook.rendercore.visibility.FullImpressionVisibleEvent
import com.facebook.rendercore.visibility.InvisibleEvent
import com.facebook.rendercore.visibility.UnfocusedVisibleEvent
import com.facebook.rendercore.visibility.VisibleEvent

class ComponentWrapperTester(
    private val content: Component,
    private val lifecycleTracker: LifecycleTracker,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    @Suppress("UNUSED_VARIABLE")
    val state = useState {
      lifecycleTracker.addStep(LifecycleStep.ON_CREATE_INITIAL_STATE)
      0
    }

    return Wrapper.create(context)
        .delegate(content)
        .visibleHandler(eventHandler<VisibleEvent> { onVisible(lifecycleTracker) })
        .focusedHandler(eventHandler<FocusedVisibleEvent> { onFocusedVisible(lifecycleTracker) })
        .invisibleHandler(eventHandler<InvisibleEvent> { onInvisible(lifecycleTracker) })
        .unfocusedHandler(
            eventHandler<UnfocusedVisibleEvent> { onUnfocusedVisibleEvent(lifecycleTracker) },
        )
        .fullImpressionHandler(
            eventHandler<FullImpressionVisibleEvent> {
              onFullImpressionVisibleEvent(lifecycleTracker)
            },
        )
        .widthPx(10)
        .heightPx(10)
        .build()
  }

  private fun ResourcesScope.onVisible(lifecycleTracker: LifecycleTracker) {
    lifecycleTracker.addStep(LifecycleStep.ON_EVENT_VISIBLE)
  }

  private fun ResourcesScope.onFocusedVisible(lifecycleTracker: LifecycleTracker) {
    lifecycleTracker.addStep(LifecycleStep.ON_FOCUSED_EVENT_VISIBLE)
  }

  private fun ResourcesScope.onInvisible(lifecycleTracker: LifecycleTracker) {
    lifecycleTracker.addStep(LifecycleStep.ON_EVENT_INVISIBLE)
  }

  private fun ResourcesScope.onUnfocusedVisibleEvent(lifecycleTracker: LifecycleTracker) {
    lifecycleTracker.addStep(LifecycleStep.ON_UNFOCUSED_EVENT_VISIBLE)
  }

  private fun ResourcesScope.onFullImpressionVisibleEvent(lifecycleTracker: LifecycleTracker) {
    lifecycleTracker.addStep(LifecycleStep.ON_FULL_IMPRESSION_VISIBLE_EVENT)
  }
}
