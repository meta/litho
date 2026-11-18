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

import android.graphics.PixelFormat
import android.graphics.drawable.InsetDrawable
import android.os.Looper
import android.widget.EditText
import com.facebook.litho.ComponentTree
import com.facebook.litho.config.PreAllocationHandler
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.unspecified
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.RunnableHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDrawable

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
class ExperimentalTextInputPreallocationTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @After
  fun clearPools() {
    MountContentPools.clear()
  }

  @Test
  fun `a tree with a preallocation handler builds the EditText before it mounts`() {
    val tree =
        ComponentTree.create(lithoTestRule.context)
            .componentsConfiguration(
                lithoTestRule.context.lithoConfiguration.componentsConfig.copy(
                    preAllocationHandler =
                        PreAllocationHandler.Custom(
                            RunnableHandler.DefaultHandler(Looper.getMainLooper()),
                        ),
                ),
            )
            .build()

    tree.setRootAndSizeSpecSync(
        ExperimentalTextInput(initialText = "hello"),
        exactly(500),
        unspecified(),
    )
    lithoTestRule.idle()

    assertThat(pooledEditTexts()).isEqualTo(1)
  }

  @Test
  fun `a tree without a preallocation handler builds no EditText before it mounts`() {
    val tree = ComponentTree.create(lithoTestRule.context).build()

    tree.setRootAndSizeSpecSync(
        ExperimentalTextInput(initialText = "hello"),
        exactly(500),
        unspecified(),
    )
    lithoTestRule.idle()

    assertThat(pooledEditTexts()).isZero()
  }

  @Test
  @Config(shadows = [RacingDefaultBackground::class])
  fun `an EditText built on the main thread survives the shared default background race`() {
    RacingDefaultBackground.racesLeft = 1

    val editText = EditTextWithEventHandlers(lithoTestRule.context.androidContext)

    assertThat(editText.background).isNull()
  }

  @Test
  fun `the EditText is built without the theme's default background and mount sets it`() {
    val androidContext = lithoTestRule.context.androidContext
    assertThat(EditText(androidContext).background).isNotNull()

    assertThat(EditTextAllocator.createContent(androidContext).background).isNull()

    val testView = lithoTestRule.render { ExperimentalTextInput(initialText = "hello") }
    val mounted = testView.lithoView.getChildAt(0) as EditTextWithEventHandlers
    assertThat(mounted.background).isNotNull()
  }

  private fun pooledEditTexts(): Int =
      MountContentPools.getContentPoolItemCount(
          lithoTestRule.context.androidContext,
          EditTextAllocator,
      )
}

/**
 * The theme's default EditText background is an [InsetDrawable]. This makes it throw, [racesLeft]
 * times, the NPE that its shared ConstantState throws when another thread builds an EditText at the
 * same time.
 */
@Implements(InsetDrawable::class)
internal class RacingDefaultBackground : ShadowDrawable() {
  @Implementation
  fun getOpacity(): Int {
    if (racesLeft > 0) {
      racesLeft--
      throw NullPointerException("DrawableContainerState.createAllFutures")
    }
    return PixelFormat.TRANSLUCENT
  }

  companion object {
    var racesLeft: Int = 0
  }
}
