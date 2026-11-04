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

package com.facebook.samples.litho.integration.reactivebridge

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.core.padding
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.useCached
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp

/**
 * Demonstrates bridging a callback-based data source into Litho state using `useState` +
 * `useEffect`
 * + `onCleanup`.
 *
 * Use this pattern when your data source is imperative (e.g., BroadcastReceiver, sensor listener,
 * SDK callback) and does not expose a Flow. Always pair listener registration with `onCleanup` to
 * avoid leaks.
 *
 * Inspired by IG's InlineFollowButtonComponent which bridges IgEventBus events into Litho state.
 */
// start_example
class CallbackBridgeComponent : KComponent() {

  override fun ComponentScope.render(): Component {
    val eventSource = useCached { FakeEventSource() }
    val lastEvent = useState { "No events yet" }

    useEffect(eventSource) {
      val listener =
          object : FakeEventSource.Listener {
            override fun onEvent(event: String) {
              lastEvent.update(event)
            }
          }
      eventSource.addListener(listener)
      onCleanup { eventSource.removeListener(listener) }
    }

    return Column(style = Style.padding(all = 16.dp)) {
      child(
          Text(
              text = "Callback Bridge (useState + useEffect)",
              textSize = 18.sp,
              style = Style.padding(bottom = 8.dp),
          ),
      )
      child(
          Text(
              text = "Bridges imperative callback APIs into Litho state.",
              textSize = 14.sp,
              style = Style.padding(bottom = 12.dp),
          ),
      )
      child(
          Text(
              text = "Last event: ${lastEvent.value}",
              textSize = 16.sp,
              style = Style.padding(bottom = 8.dp),
          ),
      )
      child(
          Text(
              text = "Fire Event",
              textSize = 16.sp,
              style = Style.padding(all = 8.dp).onClick { eventSource.fireEvent() },
          ),
      )
    }
  }
}

/**
 * Simulates an imperative callback-based data source (e.g., BroadcastReceiver, sensor manager, SDK
 * callback).
 */
class FakeEventSource {
  fun interface Listener {
    fun onEvent(event: String)
  }

  private val listeners = mutableListOf<Listener>()
  private var eventCount = 0

  fun addListener(listener: Listener) {
    listeners.add(listener)
  }

  fun removeListener(listener: Listener) {
    listeners.remove(listener)
  }

  fun fireEvent() {
    eventCount++
    val event = "Event #$eventCount"
    listeners.forEach { it.onEvent(event) }
  }
}
// end_example
