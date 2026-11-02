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

package com.facebook.samples.litho.samplekotlin.communicating

import android.graphics.Color
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.flexbox.border
import com.facebook.litho.kotlin.widget.Border
import com.facebook.litho.kotlin.widget.BorderEdge
import com.facebook.litho.kotlin.widget.BorderRadius
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp
import com.facebook.samples.litho.common.communicating.ComponentEventObserver

// start_demo
class ChildComponentSendsEventToParentComponent(
    private val observer: ComponentEventObserver,
    private val onChildClickEvent: () -> Unit,
) : KComponent() {
  override fun ComponentScope.render(): Component? {
    return Column(style = Style.margin(all = 30.dp)) {
      child(Text(text = "ChildComponent", textSize = 20f.sp))
      child(
          Text(
              text = "Click to send event to parent!",
              textSize = 15f.sp,
              style =
                  Style.padding(all = 5.dp)
                      .border(
                          Border(
                              edgeAll = BorderEdge(color = Color.BLACK, width = 1.dp),
                              radius = BorderRadius(all = 2.dp),
                          ),
                      )
                      .onClick { onChildClickEvent() },
          ),
      )
      child(
          Text(
              text = "Click to send event to Activity!",
              textSize = 15f.sp,
              style =
                  Style.padding(all = 5.dp)
                      .border(
                          Border(
                              edgeAll = BorderEdge(color = Color.BLACK, width = 1.dp),
                              radius = BorderRadius(all = 2.dp),
                          ),
                      )
                      .onClick { observer.onComponentClicked() },
          ),
      )
    }
  }
}
// end_demo
