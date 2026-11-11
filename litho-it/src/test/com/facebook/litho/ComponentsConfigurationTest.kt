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
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.sections.SectionContext
import com.facebook.litho.sections.common.SingleComponentSection
import com.facebook.litho.sections.widget.ListRecyclerConfiguration
import com.facebook.litho.sections.widget.RecyclerBinderConfiguration
import com.facebook.litho.sections.widget.RecyclerCollectionComponent
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.RecyclerBinderConfig
import com.facebook.rendercore.incrementalmount.GapWorkerMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class ComponentsConfigurationTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private val componentContext =
      ComponentContext(ApplicationProvider.getApplicationContext<Context>())

  private val defaultConfiguration = ComponentsConfiguration.defaultInstance

  @Test
  fun testSetFlagThroughComponentConfigToComponentTree() {
    ComponentsConfiguration.defaultInstance =
        defaultConfiguration.copy(incrementalMountEnabled = true)
    val componentTree =
        ComponentTree.create(componentContext)
            .componentsConfiguration(ComponentsConfiguration.defaultInstance)
            .build()
    val componentsConfiguration = componentTree.context.lithoConfiguration.componentsConfig

    assertThat(componentsConfiguration.incrementalMountEnabled).isTrue
    ComponentsConfiguration.defaultInstance = defaultConfiguration
  }

  @Test
  fun testSetFlagThroughComponentConfigToComponentTreeWithRecyclerCollectionComponent() {
    ComponentsConfiguration.defaultInstance =
        defaultConfiguration.copy(incrementalMountEnabled = false)

    val recyclerBinderConfiguration =
        RecyclerBinderConfiguration.create()
            .recyclerBinderConfig(
                RecyclerBinderConfig(
                    componentsConfiguration =
                        ComponentsConfiguration.defaultInstance.copy(
                            incrementalMountEnabled = true,
                        ),
                ),
            )
            .build()

    val testLithoView =
        lithoTestRule.render(widthPx = 10, heightPx = 10) {
          RecyclerCollectionComponent.create(componentContext)
              .recyclerConfiguration(
                  ListRecyclerConfiguration.create()
                      .recyclerBinderConfiguration(recyclerBinderConfiguration)
                      .build(),
              )
              .section(
                  SingleComponentSection.create(SectionContext(componentContext))
                      .component(
                          Row.create(componentContext)
                              .viewTag("rv_row")
                              .heightDip(100f)
                              .widthDip(100f),
                      )
                      .build(),
              )
              .build()
        }
    val childView = testLithoView.lithoView.findViewWithTag("rv_row") as LithoView?
    assertThat(childView).isNotNull
    val componentsConfiguration =
        childView?.componentTree?.context?.lithoConfiguration?.componentsConfig
    assertThat(componentsConfiguration?.incrementalMountEnabled).isTrue
    ComponentsConfiguration.defaultInstance = defaultConfiguration
  }

  @Test
  fun testOverrideDefaultBuilder() {
    ComponentsConfiguration.defaultInstance =
        defaultConfiguration.copy(incrementalMountEnabled = true)
    assertThat(ComponentsConfiguration.defaultInstance.incrementalMountEnabled).isTrue

    ComponentsConfiguration.defaultInstance =
        defaultConfiguration.copy(incrementalMountEnabled = false)
    assertThat(ComponentsConfiguration.defaultInstance.incrementalMountEnabled).isFalse

    ComponentsConfiguration.defaultInstance = defaultConfiguration
  }

  @Test
  fun gapWorkerModeDefaultsOffAndIsSetPerTree() {
    assertThat(ComponentsConfiguration.defaultInstance.gapWorkerMode).isEqualTo(GapWorkerMode.OFF)

    GapWorkerMode.entries.forEach { mode ->
      val configuration =
          ComponentsConfiguration.create(ComponentsConfiguration.defaultInstance)
              .gapWorkerMode(mode)
              .build()
      assertThat(configuration.gapWorkerMode).isEqualTo(mode)
    }
  }

  @Test
  fun testEnableInputConnectionFixDefaultValue() {
    assertThat(ComponentsConfiguration.enableInputConnectionFix).isFalse
  }

  @Test
  fun testEnableInputConnectionFixCanBeEnabled() {
    val originalValue = ComponentsConfiguration.enableInputConnectionFix
    try {
      ComponentsConfiguration.enableInputConnectionFix = true
      assertThat(ComponentsConfiguration.enableInputConnectionFix).isTrue
    } finally {
      ComponentsConfiguration.enableInputConnectionFix = originalValue
    }
  }

  @Test
  fun testEnableInputConnectionFixCanBeDisabled() {
    val originalValue = ComponentsConfiguration.enableInputConnectionFix
    try {
      ComponentsConfiguration.enableInputConnectionFix = false
      assertThat(ComponentsConfiguration.enableInputConnectionFix).isFalse
    } finally {
      ComponentsConfiguration.enableInputConnectionFix = originalValue
    }
  }

  @Test
  fun `translation reset is dropped when host cleanup is off`() {
    val config =
        ComponentsConfiguration.create()
            .enableHostViewAttributesCleanUp(false, resetTranslation = true)
            .build()

    assertThat(config.resetHostTranslationOnCleanUp).isFalse
  }

  @Test
  fun `translation reset is kept when host cleanup is on`() {
    val config =
        ComponentsConfiguration.create()
            .enableHostViewAttributesCleanUp(true, resetTranslation = true)
            .build()

    assertThat(config.resetHostTranslationOnCleanUp).isTrue
  }
}
