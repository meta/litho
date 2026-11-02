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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.Component
import com.facebook.litho.StateValue
import com.facebook.litho.sections.Section
import com.facebook.litho.sections.common.SingleComponentSection
import com.facebook.litho.testing.sections.SectionsTestHelper
import com.facebook.litho.testing.sections.SubSection
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class EventHandlerBindingSectionSpecTest {

  private lateinit var sectionsTestHelper: SectionsTestHelper

  @Before
  fun setUp() {
    sectionsTestHelper = SectionsTestHelper(ApplicationProvider.getApplicationContext<Context>())
  }

  @Test
  fun onCreateInitialState_setsCounterToZero() {
    val counter = StateValue<Int>()

    EventHandlerBindingSectionSpec.onCreateInitialState(sectionsTestHelper.context, counter)

    assertThat(counter.get()).isZero()
  }

  @Test
  fun updateCounter_setsRequestedBoundaryValue() {
    val counter = StateValue<Int>()

    EventHandlerBindingSectionSpec.updateCounter(counter, Int.MIN_VALUE)

    assertThat(counter.get()).isEqualTo(Int.MIN_VALUE)
  }

  @Test
  fun onCreateChildren_bindsUpdaterAndBuildsCounterAndButtonRows() {
    val stateUpdater = EventHandlerBindingSectionSpec.StateUpdater()
    val buttonContexts = mutableListOf<Any>()
    val button = Text.create(sectionsTestHelper.context).text("Button").build()
    val buttonCreator = EventHandlerBindingSectionSpec.ButtonCreator { context ->
      buttonContexts.add(context)
      button
    }
    val section = createSection(stateUpdater, buttonCreator)

    stateUpdater.updateCounterSync(7)
    sectionsTestHelper.prepare(section)
    val scopedContext = checkNotNull(sectionsTestHelper.getScopedContext(section))
    val initialChildren = checkNotNull(
        SectionsTestHelper.getSubSections(
            EventHandlerBindingSectionSpec.onCreateChildren(
                scopedContext,
                stateUpdater,
                buttonCreator,
                counter = 0,
                buttonBgColor = 0,
            ),
        ),
    )
    stateUpdater.updateCounterSync(7)
    val updatedChildren = checkNotNull(sectionsTestHelper.getChildren(section))

    assertThat(initialChildren).hasSize(2)
    assertThat(initialChildren[0]).isEqualTo(counterRow(0))
    assertThat(initialChildren[1].sectionType).isEqualTo(SingleComponentSection::class.java)
    assertThat(updatedChildren).hasSize(2)
    assertThat(updatedChildren[0]).isEqualTo(counterRow(7))
    assertThat(updatedChildren[1].sectionType).isEqualTo(SingleComponentSection::class.java)
    assertThat(buttonContexts).hasSize(2)
    assertThat(buttonContexts.first()).isSameAs(scopedContext)
  }

  @Test
  fun onRowClick_forwardsCurrentCounter() {
    var clickedCounter: Int? = null

    EventHandlerBindingSectionSpec.onRowClick(
        sectionsTestHelper.context,
        onButtonClickListener = { clickedCounter = it },
        counter = 11,
    )

    assertThat(clickedCounter).isEqualTo(11)
  }

  @Test
  fun onRowClick_whenListenerFails_propagatesFailure() {
    val failure = IllegalStateException("listener failed")

    val thrown =
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
          EventHandlerBindingSectionSpec.onRowClick(
              sectionsTestHelper.context,
              onButtonClickListener = { throw failure },
              counter = 3,
          )
        }

    assertThat(thrown).isSameAs(failure)
  }

  private fun createSection(
      stateUpdater: EventHandlerBindingSectionSpec.StateUpdater,
      buttonCreator: EventHandlerBindingSectionSpec.ButtonCreator =
          EventHandlerBindingSectionSpec.ButtonCreator { context ->
            Text.create(context).text("Button").build()
          },
  ): EventHandlerBindingSection =
      EventHandlerBindingSection.create(sectionsTestHelper.context)
          .stateUpdater(stateUpdater)
          .buttonCreator(buttonCreator)
          .onButtonClickListener {}
          .build()

  private fun counterRow(counter: Int): SubSection =
      singleComponentRow(Text.create(sectionsTestHelper.context).text("Counter: $counter").build())

  private fun singleComponentRow(component: Component): SubSection {
    val section: Section =
        SingleComponentSection.create(sectionsTestHelper.context).component(component).build()
    return SubSection.of(section)
  }
}
