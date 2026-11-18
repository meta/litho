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

package com.facebook.samples.litho.kotlin.animations.statebased

import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class AnimatedContainerDemosTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  @Test
  fun visibility_initialRender_showsHideButtonAndMountedStatus() {
    val testLithoView = renderVisibility()

    assertThat(testLithoView.findViewWithTextOrNull("AnimatedVisibility")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Hide panel")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Mounted")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Priority inbox")).isNotNull
  }

  @Test
  fun visibility_initialRender_showsAllThreeNotifications() {
    val testLithoView = renderVisibility()

    assertThat(testLithoView.findViewWithTextOrNull("Design review")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Perf run")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("API feedback")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Container motion tokens are ready")).isNotNull
  }

  @Test
  fun visibility_initialRender_showsSiblingContentOutsideAnimation() {
    val testLithoView = renderVisibility()

    assertThat(testLithoView.findViewWithTextOrNull("Moves when the container leaves layout"))
        .isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Next section")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("This row is outside AnimatedVisibility."))
        .isNotNull
  }

  @Test
  fun visibility_clickHideButton_showsShowButtonAndExitingStatus() {
    val testLithoView = renderVisibility()

    lithoTestRule.act(testLithoView) { clickOnText("Hide panel") }

    assertThat(testLithoView.findViewWithTextOrNull("Show panel")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Exiting")).isNotNull
  }

  @Test
  fun visibility_hideThenShow_returnsToHideButtonAndMountedStatus() {
    val testLithoView = renderVisibility()

    lithoTestRule.act(testLithoView) { clickOnText("Hide panel") }
    lithoTestRule.act(testLithoView) { clickOnText("Show panel") }

    assertThat(testLithoView.findViewWithTextOrNull("Hide panel")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Mounted")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Priority inbox")).isNotNull
  }

  @Test
  fun content_initialRender_showsOverviewCardAndKeyWithoutPipelineSummary() {
    val testLithoView = renderContent()

    assertThat(testLithoView.findViewWithTextOrNull("AnimatedContent")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Release health")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("99.2%")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("overview")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull(PIPELINE_SUMMARY)).isNull()
  }

  @Test
  fun content_initialRender_showsAllThreeTabButtons() {
    val testLithoView = renderContent()

    assertThat(testLithoView.findViewWithTextOrNull("Overview")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Pipeline")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Review")).isNotNull
  }

  @Test
  fun content_clickPipeline_showsPipelineCardSummaryAndKey() {
    val testLithoView = renderContent()

    lithoTestRule.act(testLithoView) { clickOnText("Pipeline") }

    assertThat(testLithoView.findViewWithTextOrNull("Build and verification")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("pipeline")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull(PIPELINE_SUMMARY)).isNotNull
  }

  @Test
  fun content_clickReview_showsReviewCardAndKey() {
    val testLithoView = renderContent()

    lithoTestRule.act(testLithoView) { clickOnText("Review") }

    assertThat(testLithoView.findViewWithTextOrNull("Launch checklist")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("review")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("signed")).isNotNull
  }

  @Test
  fun content_navigateAwayAndBack_returnsToOverviewCard() {
    val testLithoView = renderContent()

    lithoTestRule.act(testLithoView) { clickOnText("Pipeline") }
    lithoTestRule.act(testLithoView) { clickOnText("Overview") }

    assertThat(testLithoView.findViewWithTextOrNull("Release health")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("overview")).isNotNull
    assertThat(testLithoView.findViewWithTextOrNull("Overview")).isNotNull
  }

  private companion object {
    const val PIPELINE_SUMMARY =
        "Capacity is tight, but high priority jobs are being promoted through the verification queue."
  }

  private fun renderVisibility(): TestLithoView =
      lithoTestRule.render(widthPx = 1080, heightPx = 1920) { AnimatedVisibilityDemoComponent() }

  private fun renderContent(): TestLithoView =
      lithoTestRule.render(widthPx = 1080, heightPx = 1920) { AnimatedContentDemoComponent() }
}
