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

package com.facebook.samples.litho.java.events;

import com.facebook.litho.ClickEvent;
import com.facebook.litho.Column;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.EventHandler;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnCreateLayout;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.annotations.Param;
import com.facebook.litho.widget.Text;

// start_example
@LayoutSpec(events = {ClickTextEvent.class})
class ClickEventComponentSpec {

  @OnCreateLayout
  static Component onCreateLayout(ComponentContext c) {
    return Column.create(c)
        .child(Text.create(c).text("OK").clickHandler(ClickEventComponent.onButtonClick(c, "OK")))
        .child(
            Text.create(c)
                .text("Cancel")
                .clickHandler(ClickEventComponent.onButtonClick(c, "Cancel")))
        .build();
  }

  @OnEvent(ClickEvent.class)
  @SuppressWarnings("rawtypes")
  protected static void onButtonClick(ComponentContext c, @Param String text) {
    EventHandler handler = ClickEventComponent.getClickTextEventHandler(c);
    if (handler != null) {
      ClickEventComponent.dispatchClickTextEvent(handler, text);
    }
  }
}
// end_example
