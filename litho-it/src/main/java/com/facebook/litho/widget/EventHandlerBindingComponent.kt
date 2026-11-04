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

import com.facebook.litho.ClickEvent
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.State
import com.facebook.litho.Wrapper
import com.facebook.litho.eventHandler
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.useCallback
import com.facebook.litho.useEffect
import com.facebook.litho.useState

class EventHandlerBindingComponent(
    private val stateUpdater: StateUpdater,
    private val buttonCreator: ButtonCreator,
    private val onButtonClickListener: OnButtonClickListener,
    private val extraChild: Component? = null,
) : KComponent() {

  class StateUpdater {
    internal var counterState: State<Int>? = null

    fun updateCounterSync(counter: Int) {
      counterState?.updateSync(counter)
    }

    fun updateCounter(counter: Int) {
      counterState?.update(counter)
    }
  }

  fun interface OnButtonClickListener {
    fun onClick(counter: Int)
  }

  fun interface ButtonCreator {
    fun createButton(c: ComponentContext): Component
  }

  override fun ComponentScope.render(): Component {

    val counterState = useState<Int> { 0 }

    useEffect(counterState) {
      stateUpdater.counterState = counterState
      onCleanup { stateUpdater.counterState = null }
    }

    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    val clickListener = useCallback { event: ClickEvent ->
      onRowClick(onButtonClickListener, counterState.value)
    }

    return Column {
      child(Text.create(context).text("Counter: ${counterState.value}").build())
      child(
          SimpleNestedTreeComponent(
              Wrapper.create(context)
                  .delegate(buttonCreator.createButton(context))
                  .clickHandler(eventHandler<ClickEvent>(clickListener))
                  .build(),
          ),
      )
      child(extraChild)
    }
  }

  private fun ResourcesScope.onRowClick(
      onButtonClickListener: OnButtonClickListener,
      counter: Int,
  ) {
    onButtonClickListener.onClick(counter)
  }
}
