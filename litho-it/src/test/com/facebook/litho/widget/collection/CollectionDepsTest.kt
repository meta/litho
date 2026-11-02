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

package com.facebook.litho.widget.collection

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.alpha
import com.facebook.litho.view.rotation
import com.facebook.litho.view.scale
import com.facebook.litho.view.viewTag
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Tests for [Collection]'s deps prop */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionDepsTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun collection_propUpdate_appliedToDependantChildren() {
    class CollectionWithSelectedRows(val alpha: Float, val rotation: Float, val scale: Float) :
        KComponent() {
      override fun ComponentScope.render(): Component = LazyList {
        child(
            Text(
                "deps_null",
                style = Style.viewTag("deps_null").alpha(alpha).rotation(rotation).scale(scale),
            ),
        )
        child(deps = arrayOf(alpha)) {
          Text(
              "deps_alpha",
              style = Style.viewTag("deps_alpha").alpha(alpha).rotation(rotation).scale(scale),
          )
        }
        child(deps = arrayOf(scale)) {
          Text(
              "deps_scale",
              style = Style.viewTag("deps_scale").alpha(alpha).rotation(rotation).scale(scale),
          )
        }
        child(deps = arrayOf()) {
          Text(
              "deps_empty",
              style = Style.viewTag("deps_empty").alpha(alpha).rotation(rotation).scale(scale),
          )
        }
      }
    }

    // Perform an initial layout
    var testLithoView =
        lithoTestRule.render(widthPx = 1000, heightPx = 1000) {
          CollectionWithSelectedRows(.5f, 180f, 0.75f)
        }
    lithoTestRule.idle()

    // Perform a second layout that updates some props. Update alpha and rotation. Do not update
    // scale.
    testLithoView =
        lithoTestRule.render(
            lithoView = testLithoView.lithoView,
            widthPx = 1000,
            heightPx = 1000,
        ) {
          CollectionWithSelectedRows(1f, 0f, 0.75f)
        }
    lithoTestRule.idle()

    // Do the layout again. This is a workaround to force layout to happen in a test.
    testLithoView =
        lithoTestRule.render(
            lithoView = testLithoView.lithoView,
            widthPx = 1000,
            heightPx = 1000,
        ) {
          CollectionWithSelectedRows(1f, 0f, 0.75f)
        }
    lithoTestRule.idle()

    // childWithNullDeps should re-render all changes
    // Verify childWithNullDeps has been updated
    val childWithNullDeps = testLithoView.findViewWithTag("deps_null")
    assertThat(childWithNullDeps.alpha).isEqualTo(1f)
    assertThat(childWithNullDeps.rotation).isEqualTo(0f)
    assertThat(childWithNullDeps.scaleX).isEqualTo(0.75f)

    // childWithAlphaDeps should re-render when alpha changes
    // Verify childWithAlphaDeps has been updated
    val childWithAlphaDeps = testLithoView.findViewWithTag("deps_alpha")
    assertThat(childWithAlphaDeps.alpha).isEqualTo(1f)
    assertThat(childWithAlphaDeps.rotation).isEqualTo(0f)
    assertThat(childWithAlphaDeps.scaleX).isEqualTo(0.75f)

    // childWithScaleDeps should re-render when scale changes
    // Scale has not changed so verify childWithAlphaDeps was not updated
    val childWithScaleDeps = testLithoView.findViewWithTag("deps_scale")
    assertThat(childWithScaleDeps.alpha).isEqualTo(.5f)
    assertThat(childWithScaleDeps.rotation).isEqualTo(180f)
    assertThat(childWithScaleDeps.scaleX).isEqualTo(0.75f)

    // childWithEmptyDeps has an empty dpes array, so should never update
    // Verify childWithEmptyDeps was not updated
    val childWithEmptyDeps = testLithoView.findViewWithTag("deps_empty")
    assertThat(childWithEmptyDeps.alpha).isEqualTo(.5f)
    assertThat(childWithEmptyDeps.rotation).isEqualTo(180f)
    assertThat(childWithEmptyDeps.scaleX).isEqualTo(0.75f)
  }
}
