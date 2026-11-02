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

import android.app.Activity
import android.transition.Fade
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.LithoView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.rendercore.text.RCTextView
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * An Android `Fade` re-parents Views that disappeared during the transition into the scene root's
 * overlay, using the references it captured before Litho unmounted them. When that View is a pooled
 * `RCTextView`, it has already been released to the mount pool, so the overlay gives a pooled View
 * a parent and the next mount that acquires it must not add it to a host.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextPrimitiveTransitionOverlayPoolTest(private val usePrimitiveText: Boolean) {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveText = false

  @Before
  fun setUp() {
    RuntimeEnvironment.setQualifiers(TextPrimitiveParityTest.DENSITY_QUALIFIER)
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    ComponentsConfiguration.usePrimitiveText = usePrimitiveText
  }

  @After
  fun tearDown() {
    ShadowLooper.unPauseMainLooper()
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
  }

  @Test
  fun `text unmounted under a fade is not handed to the next mount while in the overlay`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val c = ComponentContext(activity)
    val titleBar = LithoView(activity)
    val header = LithoView(activity)
    val headerContainer =
        FrameLayout(activity).apply {
          addView(header, ViewGroup.LayoutParams.MATCH_PARENT, HEADER_HEIGHT_PX)
        }
    val root =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          addView(titleBar, ViewGroup.LayoutParams.MATCH_PARENT, HEADER_HEIGHT_PX)
          addView(headerContainer, ViewGroup.LayoutParams.MATCH_PARENT, HEADER_HEIGHT_PX)
        }
    activity.setContentView(root)
    header.setComponentTree(
        ComponentTree.create(c, Text.create(c).text("Header").textSizePx(TEXT_SIZE_PX).build())
            .build(),
        false,
    )
    layout(root)
    val headerText = findTextViews(header).singleOrNull()

    // Keep the fade in flight, as on a device, instead of letting the legacy looper finish it.
    ShadowLooper.pauseMainLooper()
    TransitionManager.beginDelayedTransition(headerContainer, Fade(Fade.OUT))
    header.unmountAllItems()
    assertThat(headerText?.parent).describedAs("unmounting detaches the text").isNull()
    headerContainer.viewTreeObserver.dispatchOnPreDraw()

    if (usePrimitiveText) {
      assertThat(checkNotNull(headerText).parent)
          .describedAs("the fade re-parents the released text into the overlay")
          .isNotNull
    }

    titleBar.setComponentTree(
        ComponentTree.create(c, Text.create(c).text("Title").textSizePx(TEXT_SIZE_PX).build())
            .build(),
        false,
    )
    if (usePrimitiveText) {
      assertThat(checkNotNull(headerText).parent)
          .describedAs("the released text is still in the overlay when the next mount runs")
          .isNotNull
    }
    layout(root)

    if (usePrimitiveText) {
      assertThat(findTextViews(titleBar)).hasSize(1).doesNotContain(headerText)
    }
  }

  private fun layout(root: View) {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY),
    )
    root.layout(0, 0, WIDTH_PX, HEIGHT_PX)
  }

  private fun findTextViews(view: View): List<RCTextView> {
    if (view is RCTextView) {
      return listOf(view)
    }
    if (view !is ViewGroup) {
      return emptyList()
    }
    return (0 until view.childCount).flatMap { findTextViews(view.getChildAt(it)) }
  }

  companion object {
    private const val WIDTH_PX = 1080
    private const val HEIGHT_PX = 1920
    private const val HEADER_HEIGHT_PX = 200
    private const val TEXT_SIZE_PX = 40

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveText={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
