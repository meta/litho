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

import android.text.InputFilter
import android.text.Spanned
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.Handle
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.TextInput
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.unspecified
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** TextInput behaves the same with the legacy and the primitive implementation. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextInputBehaviorParityTest(private val usePrimitiveTextInput: Boolean) {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveTextInput = false

  @Before
  fun setUp() {
    previousUsePrimitiveTextInput = ComponentsConfiguration.usePrimitiveTextInput
    ComponentsConfiguration.usePrimitiveTextInput = usePrimitiveTextInput
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveTextInput = previousUsePrimitiveTextInput
  }

  @Test
  fun `getText before the input is mounted returns the initial text`() {
    val handle = Handle()
    val tree = layOutWithoutMounting(input(handle, initialText = "draft"))

    assertThat(TextInput.getText(tree.context, handle)?.toString()).isEqualTo("draft")
  }

  @Test
  fun `setText before the input is mounted is shown once it mounts`() {
    val handle = Handle()
    val tree = layOutWithoutMounting(input(handle, initialText = "draft"))

    TextInput.setText(tree.context, handle, "edited")
    val editText = mount(tree)

    assertThat(editText.text.toString()).isEqualTo("edited")
  }

  @Test
  fun `setText before the input is mounted lays it out for the new text`() {
    // Without the setText, the input lays out for its initial text: one line.
    assertThat(mount(layOutWithoutMounting(multilineInput(Handle(), "a"))).lineCount).isEqualTo(1)
    val handle = Handle()
    val tree = layOutWithoutMounting(multilineInput(handle, initialText = "a"))

    TextInput.setText(tree.context, handle, "a\nb\nc\nd")
    val editText = mount(tree)

    assertThat(editText.lineCount).isEqualTo(4)
    assertThat(editText.height).isEqualTo(heightOf(multilineInput(Handle(), "a\nb\nc\nd")))
  }

  @Test
  fun `replaceText before the input is mounted is shown once it mounts`() {
    val handle = Handle()
    val tree = layOutWithoutMounting(input(handle, initialText = "draft"))

    TextInput.replaceText(tree.context, handle, "X", 0, 1, false)
    val editText = mount(tree)

    assertThat(editText.text.toString()).isEqualTo("Xraft")
  }

  @Test
  fun `a KeyUp handler decides whether the key is consumed`() {
    val consumes = render(keyUpInput(consume = true))
    val ignores = render(keyUpInput(consume = false))

    assertThat(consumes.onKeyUp(KeyEvent.KEYCODE_A, keyUp(KeyEvent.KEYCODE_A))).isTrue()
    assertThat(ignores.onKeyUp(KeyEvent.KEYCODE_A, keyUp(KeyEvent.KEYCODE_A))).isFalse()
  }

  @Test
  fun `re-rendering with an input connection handler keeps the mounted input as is`() {
    val testLithoView =
        lithoTestRule.render(widthPx = 500, heightPx = 200) { inputWithConnection {} }
    val filters = editTextOf(testLithoView).filters

    testLithoView.setRoot(inputWithConnection {})
    lithoTestRule.idle()

    assertThat(editTextOf(testLithoView).filters).isSameAs(filters)
  }

  @Test
  fun `the latest input connection handler is used after a re-render`() {
    val calls = mutableListOf<String>()
    val testLithoView =
        lithoTestRule.render(widthPx = 500, heightPx = 200) {
          inputWithConnection { calls += "first" }
        }

    testLithoView.setRoot(inputWithConnection { calls += "second" })
    lithoTestRule.idle()
    editTextOf(testLithoView).onCreateInputConnection(EditorInfo())

    assertThat(calls).containsExactly("second")
  }

  @Test
  fun `an input given a fixed size is laid out without measuring an EditText`() {
    val measures = MeasureCounter()

    val tree = layOutWithoutMounting { fixedSizeInput(measures, widthPx = 300, heightPx = 90) }

    assertThat(measures.count).isEqualTo(0)
    val editText = mount(tree)
    assertThat(editText.width to editText.height).isEqualTo(300 to 90)
  }

  private fun inputWithConnection(onCreate: () -> Unit): Component =
      object : KComponent() {
        override fun ComponentScope.render(): Component = TextInput(
            initialText = "",
            inputFilter = InputFilter.LengthFilter(100),
            onInputConnection = { event ->
              onCreate()
              checkNotNull(event.inputConnection)
            },
        )
      }

  private fun editTextOf(testLithoView: TestLithoView): EditText =
      checkNotNull(findEditText(testLithoView.lithoView)) { "No EditText mounted" }

  private fun keyUpInput(consume: Boolean): Component =
      object : KComponent() {
        override fun ComponentScope.render(): Component =
            TextInput(initialText = "", onKeyUp = { consume })
      }

  private fun keyUp(keyCode: Int): KeyEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)

  private fun render(component: Component): EditText = checkNotNull(
      findEditText(lithoTestRule.render(widthPx = 500, heightPx = 200) { component }.lithoView),
  ) {
    "No EditText mounted"
  }

  private fun input(handle: Handle, initialText: String): (ComponentContext) -> Component = { c ->
    TextInput.create(c).handle(handle).initialText(initialText).build()
  }

  private fun multilineInput(handle: Handle, initialText: String): (ComponentContext) -> Component =
      { c ->
        TextInput.create(c).handle(handle).multiline(true).initialText(initialText).build()
      }

  private fun fixedSizeInput(measures: MeasureCounter, widthPx: Int, heightPx: Int): Component =
      object : KComponent() {
        override fun ComponentScope.render(): Component = Column {
          child(
              TextInput(
                  initialText = "draft",
                  inputFilter = measures,
                  style = Style.width(widthPx.px).height(heightPx.px),
              ),
          )
        }
      }

  private fun heightOf(component: (ComponentContext) -> Component): Int =
      mount(layOutWithoutMounting(component)).height

  private fun layOutWithoutMounting(component: (ComponentContext) -> Component): ComponentTree {
    val tree = ComponentTree.create(lithoTestRule.context).build()
    tree.setRootAndSizeSpecSync(component(tree.context), exactly(500), unspecified())
    return tree
  }

  private fun mount(tree: ComponentTree): EditText {
    val lithoView = LithoView(lithoTestRule.context.androidContext)
    lithoView.componentTree = tree
    lithoView.measure(exactly(500), unspecified())
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)
    return checkNotNull(findEditText(lithoView)) { "No EditText mounted" }
  }

  private fun findEditText(view: View): EditText? {
    if (view is EditText) {
      return view
    }
    if (view !is ViewGroup) {
      return null
    }
    return (0 until view.childCount).firstNotNullOfOrNull { findEditText(view.getChildAt(it)) }
  }

  /** Measuring an input sets its text on a new EditText, which runs this filter once. */
  private class MeasureCounter : InputFilter {
    var count = 0

    override fun filter(
        source: CharSequence?,
        start: Int,
        end: Int,
        dest: Spanned?,
        dstart: Int,
        dend: Int,
    ): CharSequence? {
      count++
      return null
    }
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveTextInput={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
