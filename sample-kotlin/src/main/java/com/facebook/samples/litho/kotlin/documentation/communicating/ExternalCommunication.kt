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

package com.facebook.samples.litho.kotlin.documentation.communicating

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.onCleanup
import com.facebook.litho.useEffect
import com.facebook.litho.useFlow
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import kotlinx.coroutines.flow.StateFlow

// start_external_observer_interface
interface CartUpdateListener {
  fun addListener(listener: () -> Unit)

  fun removeListener(listener: () -> Unit)
}

// end_external_observer_interface

// start_component_with_listener
class CartBadge(private val cartListener: CartUpdateListener) : KComponent() {
  override fun ComponentScope.render(): Component {
    val itemCount = useState { 0 }

    useEffect(cartListener) {
      val listener: () -> Unit = { itemCount.update { it + 1 } }
      cartListener.addListener(listener)
      onCleanup { cartListener.removeListener(listener) }
    }

    return Text(text = "Cart (${itemCount.value})")
  }
}

// end_component_with_listener

// start_component_event_observer
fun interface AnalyticsService {
  fun trackPurchaseClicked(productId: String, price: Double)
}

// end_component_event_observer

// start_component_with_callback
class PurchaseButton(
    private val productId: String,
    private val price: Double,
    private val analyticsService: AnalyticsService,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    return Text(
        text = "Buy Now - $$price",
        style = Style.onClick { analyticsService.trackPurchaseClicked(productId, price) },
    )
  }
}

// end_component_with_callback

// start_component_with_flow
class CartBadgeWithFlow(private val cartItemCountFlow: StateFlow<Int>) : KComponent() {
  override fun ComponentScope.render(): Component {
    val itemCount = useFlow(cartItemCountFlow)
    return Text(text = "Cart ($itemCount)")
  }
}
// end_component_with_flow
