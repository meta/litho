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
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.annotation.RequiresApi
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.Handle
import com.facebook.litho.LithoView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

private const val HIDE = "hideSoftInputFromWindow"
private const val SHOW = "showSoftInput"

/**
 * `TextInput.clearFocus` asks the InputMethodManager to hide the keyboard unless
 * [ComponentsConfiguration.skipRedundantKeyboardHide] is on, the window reports the keyboard as
 * hidden, and the TextInput's focus has not changed since its last hide. Each test runs against
 * both the Spec and the primitive TextInput.
 */
@RequiresApi(Build.VERSION_CODES.R)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class TextInputRedundantKeyboardHideTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveTextInput = false
  private var previousSkipRedundantKeyboardHide = false

  @Before
  fun setUp() {
    previousUsePrimitiveTextInput = ComponentsConfiguration.usePrimitiveTextInput
    previousSkipRedundantKeyboardHide = ComponentsConfiguration.skipRedundantKeyboardHide
    ComponentsConfiguration.skipRedundantKeyboardHide = true
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveTextInput = previousUsePrimitiveTextInput
    ComponentsConfiguration.skipRedundantKeyboardHide = previousSkipRedundantKeyboardHide
  }

  @Test
  fun `clearFocus skips the hide call while the window reports the keyboard hidden`() =
      forEachTextInput {
        clearFocus()

        assertCalls(HIDE, 0)
      }

  @Test
  fun `clearFocus hides right after a focus gain while the window reports the keyboard hidden`() =
      forEachTextInput {
        assertThat(editText.requestFocus()).isTrue()

        clearFocus()

        assertCalls(HIDE, 1)
      }

  @Test
  fun `clearFocus hides right after a direct keyboard show the window does not report yet`() =
      forEachTextInput {
        whenever(imm.isActive(any<View>())).thenReturn(true)
        requestFocus()
        assertCalls(SHOW, 1)

        clearFocus()

        assertCalls(HIDE, 1)
      }

  @Test
  fun `clearFocus hides after a posted keyboard show has run`() = forEachTextInput {
    requestFocus()
    assertCalls(SHOW, 1)

    clearFocus()

    assertCalls(HIDE, 1)
  }

  @Test
  fun `clearFocus cancels a posted keyboard show`() = forEachTextInput {
    TextInput.requestFocus(tree.context, handle)
    clearFocus()

    assertCalls(HIDE, 1)
    assertCalls(SHOW, 0)
  }

  @Test
  fun `clearFocus hides right after a tap on the focused field`() = forEachTextInput {
    requestFocusWithoutKeyboard()
    tap()

    clearFocus()

    assertCalls(HIDE, 2)
  }

  @Test
  fun `clearFocus hides right after a tap that focuses the field`() = forEachTextInput {
    tap()
    assertThat(editText.isFocused).isTrue()

    clearFocus()

    assertCalls(HIDE, 1)
  }

  @Test
  fun `clearFocus hides right after an accessibility click that focuses the field`() =
      forEachTextInput {
        performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertThat(editText.isFocused).isTrue()

        clearFocus()

        assertCalls(HIDE, 1)
      }

  @Test
  fun `clearFocus hides right after an accessibility click on the focused field`() =
      forEachTextInput {
        requestFocusWithoutKeyboard()
        performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK)

        clearFocus()

        assertCalls(HIDE, 2)
      }

  @Test
  fun `clearFocus hides right after a confirm key on the focused field`() {
    for (keyCode in
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )) {
      forEachTextInput {
        requestFocusWithoutKeyboard()
        pressKey(keyCode)

        clearFocus()

        assertCalls(HIDE, 2)
      }
    }
  }

  @Test
  fun `clearFocus hides a keyboard opened for the focused field from outside the TextInput`() =
      forEachTextInput {
        requestFocusWithoutKeyboard()
        imm.showSoftInput(editText, 0) // e.g. app code through activity.currentFocus

        clearFocus()

        assertCalls(HIDE, 2)
      }

  @Test
  fun `clearFocus hides after a direct show on a field that then lost focus`() = forEachTextInput {
    whenever(imm.isActive(any<View>())).thenReturn(true)
    requestFocusWithoutKeyboard()
    requestFocus()
    editText.clearFocus()

    clearFocus()

    assertCalls(HIDE, 2)
  }

  @Test
  fun `clearFocus hides after a posted show ran on a field that then lost focus`() =
      forEachTextInput {
        requestFocusWithoutKeyboard()
        requestFocus()
        editText.clearFocus()

        clearFocus()

        assertCalls(HIDE, 2)
      }

  @Test
  fun `clearFocus cancels a posted show on a field that lost focus`() = forEachTextInput {
    requestFocusWithoutKeyboard()
    TextInput.requestFocus(tree.context, handle)
    editText.clearFocus()

    clearFocus()

    assertCalls(HIDE, 2)
    assertCalls(SHOW, 0)
  }

  @Test
  fun `clearFocus hides while the window reports the keyboard shown`() = forEachTextInput {
    requestFocusWithoutKeyboard()
    reportKeyboardShown()

    clearFocus()

    assertCalls(HIDE, 2)
  }

  @Test
  fun `a hide re-arms the skip`() = forEachTextInput {
    whenever(imm.isActive(any<View>())).thenReturn(true)
    requestFocus()
    clearFocus()
    assertCalls(HIDE, 1)

    clearFocus()

    assertCalls(HIDE, 1)
  }

  @Test
  fun `requestFocusWithoutKeyboard still hides while the window reports the keyboard hidden`() =
      forEachTextInput {
        requestFocusWithoutKeyboard()

        assertCalls(HIDE, 1)
      }

  @Test
  fun `clearFocus hides without the flag`() = forEachTextInput {
    ComponentsConfiguration.skipRedundantKeyboardHide = false

    clearFocus()

    assertCalls(HIDE, 1)
  }

  private fun forEachTextInput(test: Fixture.() -> Unit) {
    for (usePrimitiveTextInput in listOf(false, true)) {
      ComponentsConfiguration.usePrimitiveTextInput = usePrimitiveTextInput
      Fixture(usePrimitiveTextInput).test()
    }
  }

  /** A mounted TextInput whose context hands out a mock InputMethodManager. */
  private class Fixture(private val usePrimitiveTextInput: Boolean) {
    val imm: InputMethodManager = mock()
    val handle = Handle()
    val tree: ComponentTree
    val editText: EditText

    init {
      val activity =
          Robolectric.buildActivity(Activity::class.java).setup().windowFocusChanged(true).get()
      val context =
          object : ContextWrapper(activity) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.INPUT_METHOD_SERVICE) imm else super.getSystemService(name)
          }
      tree = ComponentTree.create(ComponentContext(context)).build()
      tree.setRootAndSizeSpecSync(
          TextInput.create(tree.context).handle(handle).initialText("").build(),
          exactly(500),
          unspecified(),
      )
      val lithoView = LithoView(context)
      lithoView.componentTree = tree
      activity.setContentView(lithoView)
      lithoView.measure(exactly(500), unspecified())
      lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)
      idle()
      editText = checkNotNull(findEditText(lithoView)) { "No EditText mounted" }
      // Touch mode, as on a phone: outside it, clearing focus gives it straight back to the field.
      ReflectionHelpers.callInstanceMethod<Boolean>(
          editText.rootView.parent,
          "ensureTouchModeLocally",
          ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
      )
      assertThat(editText.isInTouchMode).isTrue()
      assertThat(isSoftInputReportedHidden(editText)).isTrue()
    }

    fun requestFocus() {
      TextInput.requestFocus(tree.context, handle)
      idle()
    }

    fun requestFocusWithoutKeyboard() {
      TextInput.requestFocusWithoutKeyboard(tree.context, handle)
      idle()
    }

    fun clearFocus() {
      TextInput.clearFocus(tree.context, handle)
      idle()
    }

    /** Robolectric has no IME, so this sets the window insets the view reads to report one. */
    fun reportKeyboardShown() {
      ReflectionHelpers.setField(
          editText.rootView.parent,
          "mLastWindowInsets",
          WindowInsets.Builder().setVisible(WindowInsets.Type.ime(), true).build(),
      )
      assertThat(isSoftInputReportedHidden(editText)).isFalse()
    }

    fun tap() {
      val time = SystemClock.uptimeMillis()
      val x = editText.width / 2f
      val y = editText.height / 2f
      editText.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
      editText.dispatchTouchEvent(
          MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0),
      )
      idle()
    }

    fun performAccessibilityAction(action: Int) {
      editText.performAccessibilityAction(action, null)
      idle()
    }

    fun pressKey(keyCode: Int) {
      editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
      editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
      idle()
    }

    fun assertCalls(method: String, expected: Int) {
      assertThat(mockingDetails(imm).invocations.count { it.method.name == method })
          .describedAs("$method calls with usePrimitiveTextInput=$usePrimitiveTextInput")
          .isEqualTo(expected)
    }

    private fun idle() {
      shadowOf(Looper.getMainLooper()).idle()
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
  }
}
