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

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import com.facebook.litho.Component
import com.facebook.litho.ResourcesScope
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Tests [TextInput]'s `shouldUseMatchParentLayoutParams` on both TextInput implementations. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextInputLayoutParamsTest(private val usePrimitiveTextInput: Boolean) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveTextInput={0}")
    fun implementations(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @get:Rule val lithoTestRule = LithoTestRule()

  private var savedUsePrimitiveTextInput = false
  private var savedTextInputUsesMatchParentLayoutParams = false

  @Before
  fun setUp() {
    savedUsePrimitiveTextInput = ComponentsConfiguration.usePrimitiveTextInput
    savedTextInputUsesMatchParentLayoutParams =
        ComponentsConfiguration.textInputUsesMatchParentLayoutParams
    ComponentsConfiguration.usePrimitiveTextInput = usePrimitiveTextInput
    ComponentsConfiguration.textInputUsesMatchParentLayoutParams = false
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveTextInput = savedUsePrimitiveTextInput
    ComponentsConfiguration.textInputUsesMatchParentLayoutParams =
        savedTextInputUsesMatchParentLayoutParams
  }

  private fun ResourcesScope.input(
      shouldUseMatchParentLayoutParams: Boolean,
      maxLines: Int = Int.MAX_VALUE,
  ): Component = Column {
    child(
        TextInput.create(context)
            .multiline(true)
            .maxLines(maxLines)
            .shouldUseMatchParentLayoutParams(shouldUseMatchParentLayoutParams)
            .build(),
    )
  }

  private fun render(
      shouldUseMatchParentLayoutParams: Boolean,
      maxLines: Int = Int.MAX_VALUE,
  ): TestLithoView =
      lithoTestRule.render(widthPx = 720, heightPx = 1000) {
        input(shouldUseMatchParentLayoutParams, maxLines)
      }

  private fun editTextOf(view: TestLithoView): EditText = view.lithoView.getChildAt(0) as EditText

  @Test
  fun `by default an edit requests a layout from the host`() {
    val view = render(shouldUseMatchParentLayoutParams = false)
    assertThat(view.lithoView.isLayoutRequested).isFalse

    editTextOf(view).append("a")

    assertThat(view.lithoView.isLayoutRequested).isTrue
  }

  @Test
  fun `with MATCH_PARENT layout params an edit does not request a layout from the host`() {
    val view = render(shouldUseMatchParentLayoutParams = true)
    assertThat(view.lithoView.isLayoutRequested).isFalse

    editTextOf(view).append("a")

    assertThat(view.lithoView.isLayoutRequested).isFalse
  }

  @Test
  fun `with MATCH_PARENT layout params a new line still grows the input`() {
    val view = render(shouldUseMatchParentLayoutParams = true)
    val editText = editTextOf(view)
    val oneLineHeight = editText.height

    editText.append("a\nb")
    lithoTestRule.idle()

    assertThat(editText.height).isGreaterThan(oneLineHeight)
  }

  /**
   * Guard: after a new line that keeps the size, the next edit must not remeasure. The remeasure's
   * mount re-applies the line limits and measures the view again, so this passes without the
   * line-count save.
   */
  @Test
  fun `with MATCH_PARENT layout params edits after a new line that keeps the size do not remeasure`() {
    // One line tall, so the new line changes the line count but not the bounds.
    val view = render(shouldUseMatchParentLayoutParams = true, maxLines = 1)
    val editText = editTextOf(view)
    var stateUpdateLayouts = 0
    view.lithoView.componentTree!!.addMeasureListener { _, _, _, isStateUpdate ->
      if (isStateUpdate) stateUpdateLayouts++
    }

    editText.append("a\nb")
    lithoTestRule.idle()
    editText.append("c")
    lithoTestRule.idle()

    assertThat(stateUpdateLayouts).isEqualTo(1)
  }

  /**
   * The Primitive lays out a line count change on the layout thread, and with MATCH_PARENT no frame
   * remeasures the input before that layout mounts. An edit that undoes the change in between must
   * still remeasure.
   */
  @Test
  fun `with MATCH_PARENT layout params a new line deleted before it mounts leaves one line`() {
    val view = render(shouldUseMatchParentLayoutParams = true)
    val editText = editTextOf(view)
    val oneLineHeight = editText.height

    editText.append("a\nb")
    // Runs only the layout thread, so the two-line layout waits to mount.
    lithoTestRule.finishAsyncUpdates()
    editText.text.delete(1, 3)
    lithoTestRule.idle()

    assertThat(editText.height).isEqualTo(oneLineHeight)
  }

  @Test
  fun `unmounting gives the pooled view back the host default layout params`() {
    val view = render(shouldUseMatchParentLayoutParams = true)
    val editText = editTextOf(view)

    view.lithoView.unmountAllItems()

    assertThat(editText.layoutParams.width).isEqualTo(WRAP_CONTENT)
    assertThat(editText.layoutParams.height).isEqualTo(WRAP_CONTENT)
  }

  @Test
  fun `a pooled input reused without MATCH_PARENT requests a layout on edits again`() {
    val first = render(shouldUseMatchParentLayoutParams = true)
    val pooled = editTextOf(first)
    first.lithoView.unmountAllItems()

    val second = render(shouldUseMatchParentLayoutParams = false)
    assertThat(editTextOf(second)).isSameAs(pooled)
    assertThat(second.lithoView.isLayoutRequested).isFalse

    pooled.append("a")

    assertThat(second.lithoView.isLayoutRequested).isTrue
  }

  @Test
  fun `changing the prop on a mounted input swaps its layout params in place`() {
    val view = render(shouldUseMatchParentLayoutParams = true)
    val editText = editTextOf(view)

    view.setRoot { input(shouldUseMatchParentLayoutParams = false) }
    lithoTestRule.idle()
    assertThat(editTextOf(view)).isSameAs(editText)
    assertThat(editText.layoutParams.width).isEqualTo(WRAP_CONTENT)

    view.setRoot { input(shouldUseMatchParentLayoutParams = true) }
    lithoTestRule.idle()
    assertThat(editTextOf(view)).isSameAs(editText)
    assertThat(editText.layoutParams.width).isEqualTo(MATCH_PARENT)
  }

  // `textInputUsesMatchParentLayoutParams` gives every input what the prop gives one input. None of
  // these inputs set the prop.

  @Test
  fun `with the config flag an edit does not request a layout from the host`() {
    ComponentsConfiguration.textInputUsesMatchParentLayoutParams = true
    val view = render(shouldUseMatchParentLayoutParams = false)
    assertThat(view.lithoView.isLayoutRequested).isFalse

    editTextOf(view).append("a")

    assertThat(view.lithoView.isLayoutRequested).isFalse
  }

  @Test
  fun `with the config flag a pooled input that had the prop still does not request a layout on edits`() {
    ComponentsConfiguration.textInputUsesMatchParentLayoutParams = true
    val first = render(shouldUseMatchParentLayoutParams = true)
    val pooled = editTextOf(first)
    first.lithoView.unmountAllItems()

    val second = render(shouldUseMatchParentLayoutParams = false)
    assertThat(editTextOf(second)).isSameAs(pooled)
    assertThat(second.lithoView.isLayoutRequested).isFalse

    pooled.append("a")

    assertThat(second.lithoView.isLayoutRequested).isFalse
  }

  /** Guard: passes before and after the flag; growing comes from Litho's line-count remeasure. */
  @Test
  fun `with the config flag a new line still grows the input`() {
    ComponentsConfiguration.textInputUsesMatchParentLayoutParams = true
    val view = render(shouldUseMatchParentLayoutParams = false)
    val editText = editTextOf(view)
    val oneLineHeight = editText.height

    editText.append("a\nb")
    lithoTestRule.idle()

    assertThat(editText.height).isGreaterThan(oneLineHeight)
  }

  /** Guard: with no edit-time layout, a new line undone before its resize mounts still shrinks. */
  @Test
  fun `with the config flag a new line deleted before it mounts leaves one line`() {
    ComponentsConfiguration.textInputUsesMatchParentLayoutParams = true
    val view = render(shouldUseMatchParentLayoutParams = false)
    val editText = editTextOf(view)
    val oneLineHeight = editText.height

    editText.append("a\nb")
    lithoTestRule.finishAsyncUpdates()
    editText.text.delete(1, 3)
    lithoTestRule.idle()

    assertThat(editText.height).isEqualTo(oneLineHeight)
  }
}
