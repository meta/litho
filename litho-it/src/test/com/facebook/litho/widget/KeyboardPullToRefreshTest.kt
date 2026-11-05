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

import android.annotation.SuppressLint
import android.app.Activity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.PrimitiveRecyclerBinderStrategy
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.eventHandlerWithReturn
import com.facebook.litho.kotlin.widget.TextInput
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.sections.SectionContext
import com.facebook.litho.sections.common.SingleComponentSection
import com.facebook.litho.sections.widget.ListRecyclerConfiguration
import com.facebook.litho.sections.widget.RecyclerBinderConfiguration
import com.facebook.litho.sections.widget.RecyclerCollectionComponent
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useCallback
import com.facebook.litho.useState
import com.facebook.litho.view.focusable
import com.facebook.litho.view.onKeyEvent
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.collection.LazyList
import com.facebook.litho.widget.collection.useLazyCollectionController
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class KeyboardPullToRefreshTest {
  private val activityController =
      Robolectric.buildActivity(KeyRecordingActivity::class.java).setup()
  @field:SuppressLint("ActivityStoredInField") private val activity = activityController.get()
  @get:Rule val rule = LithoTestRule(androidContext = activity, attachToRealWindow = true)

  @After
  fun tearDown() {
    activityController.pause().stop().destroy()
  }

  private var nextPressTime = 100L
  private var currentPressTime = 0L

  @Test
  fun `every backend routes refresh shortcuts through the focused window`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      val list = view.list()
      focus(list)
      for ((code, modifiers) in shortcuts) {
        activity.keyDowns.clear()
        activity.keyUps.clear()
        val before = refreshes
        press(list, code, modifiers)
        assertThat(refreshes).describedAs("$backend/$code").isEqualTo(before + 1)
        assertThat(list.isRefreshing).describedAs("$backend/$code").isTrue()
        assertThat(activity.keyDowns).isEmpty()
        assertThat(activity.keyUps).isEmpty()
        assertThat(activity.window.decorView.findFocus()).isSameAs(list.recyclerView)
        list.isRefreshing = false
      }
      list.isRefreshing = false
      view.release()
    }
  }

  @Test
  fun `null key events do not refresh any backend`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      val list = view.list()
      focus(list)

      assertThat(list.dispatchKeyEvent(null)).describedAs("$backend").isFalse()
      assertThat(refreshes).describedAs("$backend").isZero()
      assertThat(list.isRefreshing).describedAs("$backend").isFalse()

      press(list)
      assertThat(refreshes).describedAs("$backend").isEqualTo(1)
      list.isRefreshing = false
      view.release()
    }
  }

  @Test
  fun `an unowned refresh key release without a focused child is unhandled`() {
    val list = SectionsRecyclerView(activity, RecyclerView(activity))
    var refreshes = 0
    list.onKeyboardRefresh = { refreshes++ }

    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code = KeyEvent.KEYCODE_REFRESH)))
        .isFalse()
    assertThat(refreshes).isZero()
    assertThat(list.isRefreshing).isFalse()
  }

  @Test
  fun `every backend retains six held device shortcuts until release`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      val list = view.list()
      val received = mutableListOf<KeyEvent>()
      val child = View(activity)
      list.addView(child, ViewGroup.LayoutParams(100, 100))
      child.isFocusableInTouchMode = true
      child.layout(0, 0, 100, 100)
      child.setOnKeyListener { _, _, event ->
        received.add(event)
        false
      }
      assertThat(child.requestFocus()).isTrue()
      activity.keyDowns.clear()
      activity.keyUps.clear()
      val downs =
          listOf(-1, 11).flatMap { device ->
            shortcuts.map { (code, modifiers) ->
              key(
                  KeyEvent.ACTION_DOWN,
                  downTime = 100,
                  code = code,
                  modifiers = modifiers,
                  deviceId = device,
              )
            }
          }
      downs.forEach { activity.dispatchKeyEvent(it) }
      for (down in downs.reversed()) {
        activity.dispatchKeyEvent(
            key(
                KeyEvent.ACTION_DOWN,
                repeat = 1,
                downTime = 101,
                code = down.keyCode,
                modifiers = 0,
                deviceId = down.deviceId,
            ),
        )
        activity.dispatchKeyEvent(
            key(
                KeyEvent.ACTION_UP,
                downTime = 101,
                code = down.keyCode,
                modifiers = 0,
                deviceId = down.deviceId,
            ),
        )
      }
      assertThat(received).describedAs("$backend").containsExactlyElementsOf(downs)
      assertThat(activity.keyDowns).describedAs("$backend").isEmpty()
      assertThat(activity.keyUps).describedAs("$backend").isEmpty()
      assertThat(refreshes).describedAs("$backend").isEqualTo(1)
      list.isRefreshing = false
      list.removeView(child)
      view.release()
    }
  }

  @Test
  fun `every backend keeps another device editor input while owning Ctrl R`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      val list = view.list()
      val editor = EditText(activity)
      list.addView(editor, ViewGroup.LayoutParams(100, 100))
      editor.layout(0, 0, 100, 100)
      val received = mutableListOf<KeyEvent>()
      editor.setOnKeyListener { _, _, event ->
        received.add(event)
        false
      }
      val callback = list.onKeyboardRefresh
      list.onKeyboardRefresh = null
      assertThat(editor.requestFocus()).isTrue()
      val down = key(KeyEvent.ACTION_DOWN, downTime = 100, modifiers = 0, deviceId = 11)
      val up = key(KeyEvent.ACTION_UP, downTime = 100, modifiers = 0, deviceId = 11)
      val baselineDown = activity.dispatchKeyEvent(down)
      val baselineUp = activity.dispatchKeyEvent(up)
      assertThat(editor.text.toString()).isEqualTo("r")
      list.onKeyboardRefresh = callback
      focus(list)
      activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, downTime = 100, deviceId = 10))
      assertThat(editor.requestFocus()).isTrue()
      editor.setText("")
      received.clear()
      activity.keyDowns.clear()
      activity.keyUps.clear()
      assertThat(activity.dispatchKeyEvent(down)).describedAs("$backend").isEqualTo(baselineDown)
      assertThat(activity.dispatchKeyEvent(up)).describedAs("$backend").isEqualTo(baselineUp)
      assertThat(editor.text.toString()).describedAs("$backend").isEqualTo("r")
      assertThat(received).describedAs("$backend").containsExactly(down, up)
      activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, repeat = 1, downTime = 101, modifiers = 0, deviceId = 10),
      )
      activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_UP, downTime = 101, modifiers = 0, deviceId = 10),
      )
      assertThat(received).describedAs("$backend").containsExactly(down, up)
      assertThat(refreshes).describedAs("$backend").isEqualTo(1)
      assertThat(editor.isFocused).isTrue()
      list.isRefreshing = false
      list.removeView(editor)
      view.release()
    }
  }

  @Test
  fun `fresh editor downs discard stale ownership after the old up left the collection`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      val list = view.list()
      val editor = EditText(activity)
      list.addView(editor, ViewGroup.LayoutParams(100, 100))
      editor.layout(0, 0, 100, 100)
      val outside = View(activity)
      activity.findViewById<ViewGroup>(android.R.id.content).addView(outside)
      outside.layout(0, 0, 100, 100)
      outside.isFocusableInTouchMode = true
      val received = mutableListOf<KeyEvent>()
      editor.setOnKeyListener { _, _, event ->
        received.add(event)
        false
      }
      for (modifiers in
          listOf(0, KeyEvent.META_ALT_ON, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)) {
        val callback = list.onKeyboardRefresh
        list.onKeyboardRefresh = null
        assertThat(editor.requestFocus()).isTrue()
        editor.setText("")
        val down = key(KeyEvent.ACTION_DOWN, modifiers = modifiers)
        val up = key(KeyEvent.ACTION_UP, downTime = down.downTime, modifiers = modifiers)
        activity.keyDowns.clear()
        activity.keyUps.clear()
        val baselineDown = activity.dispatchKeyEvent(down)
        val baselineUp = activity.dispatchKeyEvent(up)
        val text = editor.text.toString()
        val fallbackDowns = activity.keyDowns.toList()
        val fallbackUps = activity.keyUps.toList()
        list.onKeyboardRefresh = callback
        list.isRefreshing = false
        focus(list)
        val owned = key(KeyEvent.ACTION_DOWN, downTime = down.downTime - 1)
        activity.dispatchKeyEvent(owned)
        assertThat(outside.requestFocus()).isTrue()
        activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = owned.downTime))
        assertThat(editor.requestFocus()).isTrue()
        editor.setText("")
        received.clear()
        activity.keyDowns.clear()
        activity.keyUps.clear()
        assertThat(activity.dispatchKeyEvent(down))
            .describedAs("$backend/$modifiers")
            .isEqualTo(baselineDown)
        assertThat(activity.dispatchKeyEvent(up)).isEqualTo(baselineUp)
        assertThat(received).containsExactly(down, up)
        assertThat(editor.text.toString()).isEqualTo(text)
        assertThat(activity.keyDowns).containsExactlyElementsOf(fallbackDowns)
        assertThat(activity.keyUps).containsExactlyElementsOf(fallbackUps)
        assertThat(editor.isFocused).isTrue()
      }
      assertThat(refreshes).describedAs("$backend").isEqualTo(3)
      list.isRefreshing = false
      (outside.parent as ViewGroup).removeView(outside)
      list.removeView(editor)
      view.release()
    }
  }

  @Test
  fun `default off keeps refresh shortcuts unhandled on every backend`() {
    assertThat(ComponentsConfiguration.defaultInstance.enableKeyboardPullToRefresh).isFalse()
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend, gate = false) { refreshes++ }
      val list = view.list()
      focus(list)

      for ((code, modifiers) in shortcuts) {
        activity.keyDowns.clear()
        activity.keyUps.clear()
        val down = key(KeyEvent.ACTION_DOWN, code = code, modifiers = modifiers)
        val up = key(KeyEvent.ACTION_UP, code = code, modifiers = modifiers)
        assertThat(activity.dispatchKeyEvent(down)).isTrue()
        assertThat(activity.dispatchKeyEvent(up)).isTrue()
        assertThat(activity.keyDowns).describedAs("$backend/$code").containsExactly(down)
        assertThat(activity.keyUps).describedAs("$backend/$code").containsExactly(up)
      }
      assertThat(refreshes).describedAs("$backend").isZero()
      assertThat(list.isRefreshing).describedAs("$backend").isFalse()
      view.release()
    }
  }

  @Test
  fun `disabled pull to refresh stays disabled on every backend`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend, refreshEnabled = false) { refreshes++ }
      val list = view.list()
      focus(list)

      press(list)

      assertThat(refreshes).describedAs("$backend").isZero()
      assertThat(list.isRefreshing).describedAs("$backend").isFalse()
      view.release()
    }
  }

  @Test
  fun `a new press is ignored while the refresh indicator is set on every backend`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      val list = view.list()
      focus(list)

      press(list)
      press(list)
      assertThat(refreshes).describedAs("$backend").isEqualTo(1)
      list.isRefreshing = false
      press(list)
      assertThat(refreshes).describedAs("$backend").isEqualTo(2)
      list.isRefreshing = false
      view.release()
    }
  }

  @Test
  fun `a recycled view does not retain the old refresh callback when the gate is off`() {
    for (backend in Backend.entries) {
      MountContentPools.clear()
      var oldRefreshes = 0
      var newRefreshes = 0
      val first = render(backend) { oldRefreshes++ }
      val pooled = first.list()
      first.release()

      val second = render(backend, gate = false) { newRefreshes++ }
      val list = second.list()
      assertThat(list).describedAs("$backend").isSameAs(pooled)
      focus(list)
      press(list)

      assertThat(oldRefreshes).describedAs("$backend").isZero()
      assertThat(newRefreshes).describedAs("$backend").isZero()
      assertThat(list.isRefreshing).describedAs("$backend").isFalse()
      second.release()
    }
  }

  @Test
  fun `a pooled view with the gate on uses only its new callback`() {
    for (backend in Backend.entries) {
      MountContentPools.clear()
      val oldStates = mutableListOf<String>()
      val newStates = mutableListOf<String>()
      val first = render(backend) { oldStates.add("old") }
      val pooled = first.list()
      first.release()
      val second = render(backend) { newStates.add("new") }
      val list = second.list()
      assertThat(list).describedAs("$backend").isSameAs(pooled)
      focus(list)

      press(list)

      assertThat(oldStates).describedAs("$backend").isEmpty()
      assertThat(newStates).describedAs("$backend").containsExactly("new")
      assertThat(list.isRefreshing).isTrue()
      list.isRefreshing = false
      second.release()
    }
  }

  @Test
  fun `an unmounted list cannot call its old refresh callback`() {
    for (backend in Backend.entries) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      val list = view.list()
      view.lithoView.unmountAllItems()
      assertThat(list.onKeyboardRefresh as Any?).isNull()

      assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isFalse()
      assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isFalse()

      assertThat(refreshes).describedAs("$backend").isZero()
      assertThat(list.isRefreshing).describedAs("$backend").isFalse()
      view.release()
    }
  }

  @Test
  fun `refreshing a scrolled lazy collection preserves its scroll position`() {
    for (backend in listOf(Backend.LAZY_DEFAULT, Backend.LAZY_SPLIT, Backend.LAZY_NEW)) {
      var refreshes = 0
      val view = render(backend) { refreshes++ }
      rule.idle()
      view.measure().layout()
      val list = view.list()
      list.recyclerView.scrollBy(0, 60)
      focus(list)
      val offset = list.recyclerView.computeVerticalScrollOffset()
      assertThat(offset).describedAs("$backend").isGreaterThan(0)

      press(list)

      assertThat(refreshes).describedAs("$backend").isEqualTo(1)
      assertThat(list.recyclerView.computeVerticalScrollOffset()).isEqualTo(offset)
      assertThat(list.recyclerView.isFocused).isTrue()
      list.isRefreshing = false
      view.release()
    }
  }

  @Test
  fun `horizontal lazy collections do not acquire refresh shortcuts`() {
    for (backend in listOf(Backend.LAZY_DEFAULT, Backend.LAZY_SPLIT, Backend.LAZY_NEW)) {
      var refreshes = 0
      val view = render(backend, orientation = RecyclerView.HORIZONTAL) { refreshes++ }
      val list = view.list()
      focus(list)

      press(list)

      assertThat(refreshes).describedAs("$backend").isZero()
      assertThat(list.isRefreshing).describedAs("$backend").isFalse()
      view.release()
    }
  }

  @Test
  fun `mounted state updates preserve the consumed press through callback rebind and remount`() {
    for (backend in lazyBackends) {
      val oldStates = mutableListOf<Int>()
      val newStates = mutableListOf<Int>()
      val view =
          rule.render(componentTree = tree(backend), widthPx = 200, heightPx = 200) {
            RebindingList({ oldStates.add(it) }, { newStates.add(it) })
          }
      val list = view.list()
      focus(list)
      val activity = rule.context.androidContext as Activity
      val down = key(KeyEvent.ACTION_DOWN)

      assertThat(activity.dispatchKeyEvent(down)).describedAs("$backend").isTrue()
      rule.idle()
      view.measure().layout()

      assertThat(view.list()).describedAs("$backend").isSameAs(list)
      assertThat(list.recyclerView.paddingTop).describedAs("$backend").isEqualTo(1)
      assertThat(list.isRefreshing).describedAs("$backend").isFalse()
      assertThat(
          activity.dispatchKeyEvent(
              key(KeyEvent.ACTION_DOWN, repeat = 1, downTime = down.downTime),
          ),
      )
          .describedAs("$backend")
          .isTrue()
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime)))
          .describedAs("$backend")
          .isTrue()
      assertThat(oldStates).describedAs("$backend").containsExactly(0)
      assertThat(newStates).describedAs("$backend").isEmpty()

      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
      assertThat(oldStates).describedAs("$backend").containsExactly(0)
      assertThat(newStates).describedAs("$backend").containsExactly(1)
      rule.idle()
      activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
      view.release()
    }
  }

  @Test
  fun `a mounted horizontal carousel refreshes its eligible vertical ancestor`() {
    for (backend in lazyBackends) {
      var outerRefreshes = 0
      var innerRefreshes = 0
      val view =
          renderNested(backend, RecyclerView.HORIZONTAL, { innerRefreshes++ }) { outerRefreshes++ }
      val outer = view.list()
      val inner = view.innerList()
      focus(inner)

      val activity = rule.context.androidContext as Activity
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()

      assertThat(outerRefreshes).describedAs("$backend").isEqualTo(1)
      assertThat(innerRefreshes).describedAs("$backend").isZero()
      assertThat(outer.isRefreshing).isTrue()
      assertThat(inner.isRefreshing).isFalse()
      assertThat(activity.window.decorView.findFocus()).isSameAs(inner.recyclerView)
      outer.isRefreshing = false
      view.release()
    }
  }

  @Test
  fun `the nearest of two mounted refreshable lists owns the shortcut`() {
    for (backend in lazyBackends) {
      var outerRefreshes = 0
      var innerRefreshes = 0
      val view =
          renderNested(backend, RecyclerView.VERTICAL, { innerRefreshes++ }) { outerRefreshes++ }
      val outer = view.list()
      val inner = view.innerList()
      focus(inner)

      val activity = rule.context.androidContext as Activity
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()

      assertThat(outerRefreshes).describedAs("$backend").isZero()
      assertThat(innerRefreshes).describedAs("$backend").isEqualTo(1)
      assertThat(outer.isRefreshing).isFalse()
      assertThat(inner.isRefreshing).isTrue()
      inner.isRefreshing = false
      view.release()
    }
  }

  @Test
  fun `a mounted nonrefreshable nested list allows the ancestor refresh`() {
    for (backend in lazyBackends) {
      var refreshes = 0
      val view = renderNested(backend, RecyclerView.VERTICAL, null) { refreshes++ }
      val outer = view.list()
      focus(view.innerList())

      val activity = rule.context.androidContext as Activity
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()

      assertThat(refreshes).describedAs("$backend").isEqualTo(1)
      outer.isRefreshing = false
      view.release()
    }
  }

  @Test
  fun `mounted Litho rows keep directional navigation and refresh focus`() {
    for (backend in lazyBackends) {
      var refreshes = 0
      val childKeys = mutableListOf<KeyEvent>()
      val view = renderFocusList(
          backend,
          { event ->
            childKeys.add(event)
            false
          },
      ) {
        refreshes++
      }
      val list = view.list()
      val first = view.lithoView.findViewWithTag<View>("row-1")
      val second = view.lithoView.findViewWithTag<View>("row-2")
      assertThat(first.isFocusable).isTrue()
      assertThat(first.isAttachedToWindow).isTrue()
      assertThat(first.height).isGreaterThan(0)
      assertThat(first.requestFocus()).isTrue()

      activity.consumeFallback = false
      for (code in listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_DOWN)) {
        assertThat(first.requestFocus()).isTrue()
        childKeys.clear()
        dispatchThroughViewRoot(key(KeyEvent.ACTION_DOWN, code = code, modifiers = 0))
        dispatchThroughViewRoot(key(KeyEvent.ACTION_UP, code = code, modifiers = 0))
        assertThat(childKeys.map { it.action })
            .containsExactly(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)
        assertThat(activity.window.decorView.findFocus())
            .describedAs("$backend/$code")
            .isSameAs(second)
        assertThat(refreshes).isZero()
      }
      activity.consumeFallback = true

      for ((code, modifiers) in shortcuts) {
        childKeys.clear()
        val before = refreshes
        press(list, code, modifiers)
        assertThat(refreshes).describedAs("$backend/$code").isEqualTo(before + 1)
        assertThat(childKeys).hasSize(1)
        assertThat(childKeys.single().action).isEqualTo(KeyEvent.ACTION_DOWN)
        assertThat(list.isRefreshing).isTrue()
        assertThat(activity.window.decorView.findFocus()).isSameAs(second)
        list.isRefreshing = false
      }
      view.release()
    }
  }

  @Test
  fun `a mounted Litho row handler owns both events before refresh`() {
    val received = mutableListOf<KeyEvent>()
    var refreshes = 0
    val view = renderFocusList(
        Backend.LAZY_DEFAULT,
        { event ->
          received.add(event)
          true
        },
    ) {
      refreshes++
    }
    val row = view.lithoView.findViewWithTag<View>("row-1")
    assertThat(row.requestFocus()).isTrue()
    press(view.list())
    assertThat(received.map { it.action }).containsExactly(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)
    assertThat(refreshes).isZero()
    assertThat(view.list().isRefreshing).isFalse()
    assertThat(activity.window.decorView.findFocus()).isSameAs(row)
    view.release()
  }

  @Test
  fun `mounted Litho TextInput keeps editor input and shortcut key up handling`() {
    for (backend in lazyBackends) {
      var refreshes = 0
      val received = mutableListOf<KeyEvent>()
      val view = renderFocusList(
          backend,
          { event ->
            received.add(event)
            false
          },
      ) {
        refreshes++
      }
      val list = view.list()
      val editor = checkNotNull(view.lithoView.findViewWithTag<View>("editor").findEditor())
      assertThat(editor.onCheckIsTextEditor()).isTrue()
      assertThat(editor.isAttachedToWindow).isTrue()
      assertThat(editor.requestFocus()).isTrue()
      val callback = list.onKeyboardRefresh
      for ((code, modifiers) in
          shortcuts + listOf(KeyEvent.KEYCODE_R to 0, KeyEvent.KEYCODE_ENTER to 0)) {
        val down = key(KeyEvent.ACTION_DOWN, code = code, modifiers = modifiers)
        val up = key(KeyEvent.ACTION_UP, code = code, modifiers = modifiers)
        list.onKeyboardRefresh = null
        editor.setText("")
        received.clear()
        activity.keyDowns.clear()
        activity.keyUps.clear()
        val baselineDown = activity.dispatchKeyEvent(down)
        val baselineUp = activity.dispatchKeyEvent(up)
        val baselineText = editor.text.toString()
        if (code == KeyEvent.KEYCODE_R && modifiers == 0) {
          assertThat(baselineText).isEqualTo("r")
        }
        val childBaseline = received.toList()
        val fallbackDowns = activity.keyDowns.toList()
        val fallbackUps = activity.keyUps.toList()
        list.onKeyboardRefresh = callback
        editor.setText("")
        received.clear()
        activity.keyDowns.clear()
        activity.keyUps.clear()
        assertThat(activity.dispatchKeyEvent(down)).isEqualTo(baselineDown)
        assertThat(activity.dispatchKeyEvent(up)).isEqualTo(baselineUp)
        assertThat(received).containsExactlyElementsOf(childBaseline)
        assertThat(received).contains(up)
        assertThat(editor.text.toString()).isEqualTo(baselineText)
        assertThat(activity.keyDowns).containsExactlyElementsOf(fallbackDowns)
        assertThat(activity.keyUps).containsExactlyElementsOf(fallbackUps)
      }
      assertThat(refreshes).describedAs("$backend").isZero()
      assertThat(list.isRefreshing).isFalse()
      assertThat(activity.window.decorView.findFocus()).isSameAs(editor)
      view.release()
    }
  }

  @Test
  fun `a pinned mounted Litho header refreshes without moving focus`() {
    for (backend in lazyBackends) {
      var refreshes = 0
      val view = renderFocusList(backend, { false }) { refreshes++ }
      val list = view.list()
      list.recyclerView.scrollBy(0, 100)
      rule.idle()
      view.measure().layout()
      val header = checkNotNull(list.stickyHeaderView)
      val focused = checkNotNull(header.findViewWithTag<View>("header"))
      assertThat(list.isStickyHeaderHidden).isFalse()
      assertThat(focused.isAttachedToWindow).isTrue()
      assertThat(focused.height).isGreaterThan(0)
      assertThat(focused.requestFocus()).isTrue()
      val offset = list.recyclerView.computeVerticalScrollOffset()
      assertThat(offset).isGreaterThan(0)

      press(list, KeyEvent.KEYCODE_F5, 0)

      assertThat(refreshes).describedAs("$backend").isEqualTo(1)
      assertThat(list.isRefreshing).isTrue()
      assertThat(activity.window.decorView.findFocus()).isSameAs(focused)
      assertThat(list.recyclerView.computeVerticalScrollOffset()).isEqualTo(offset)
      list.isRefreshing = false
      view.release()
    }
  }

  private fun dispatchThroughViewRoot(event: KeyEvent) {
    // Android's ViewRoot input stage performs directional focus navigation after window dispatch.
    val root = checkNotNull(activity.window.decorView.parent)
    Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true)
    rule.idle()
    ReflectionHelpers.callInstanceMethod<Unit>(
        root,
        "dispatchInputEvent",
        ClassParameter.from(InputEvent::class.java, event),
    )
    rule.idle()
  }

  private fun renderFocusList(
      backend: Backend,
      onChildKey: (KeyEvent) -> Boolean,
      onRefresh: () -> Unit,
  ): TestLithoView {
    val view =
        rule.render(componentTree = tree(backend), widthPx = 200, heightPx = 200) {
          FocusList(onChildKey, onRefresh)
        }
    repeat(3) {
      rule.idle()
      view.measure().layout()
    }
    return view
  }

  private fun renderNested(
      backend: Backend,
      innerOrientation: Int,
      innerRefresh: (() -> Unit)?,
      outerRefresh: () -> Unit,
  ): TestLithoView {
    val view =
        rule.render(componentTree = tree(backend), widthPx = 200, heightPx = 200) {
          NestedLists(innerOrientation, innerRefresh, outerRefresh)
        }
    repeat(3) {
      rule.idle()
      view.measure().layout()
    }
    return view
  }

  private fun tree(backend: Backend, gate: Boolean = true): ComponentTree =
      ComponentTree.create(rule.context)
          .componentsConfiguration(
              rule.context.lithoConfiguration.componentsConfig.copy(
                  enableKeyboardPullToRefresh = gate,
                  enableNewCollection = backend == Backend.LAZY_NEW,
                  primitiveRecyclerBinderStrategy = backend.strategy,
              ),
          )
          .build()

  private fun render(
      backend: Backend,
      gate: Boolean = true,
      refreshEnabled: Boolean = true,
      orientation: Int = RecyclerView.VERTICAL,
      onRefresh: () -> Unit,
  ): TestLithoView {
    return rule.render(componentTree = tree(backend, gate), widthPx = 200, heightPx = 200) {
      when (backend) {
        Backend.RECYCLER_DEFAULT,
        Backend.RECYCLER_SPLIT ->
            Recycler(
                binder = RecyclerBinder.Builder().build(context),
                binderStrategy = backend.strategy,
                isPullToRefreshEnabled = refreshEnabled,
                onRefresh = onRefresh,
                style = Style.viewTag(TAG).width(200.px).height(200.px),
            )
        Backend.SECTIONS_DEFAULT,
        Backend.SECTIONS_SPLIT -> LegacyList(backend.strategy, refreshEnabled, onRefresh)
        else -> LazyTestList(refreshEnabled, orientation, onRefresh)
      }
    }
  }

  private fun TestLithoView.list(): SectionsRecyclerView =
      checkNotNull(lithoView.findViewWithTag<View>(TAG).findList())

  private fun TestLithoView.innerList(): SectionsRecyclerView =
      checkNotNull(lithoView.findViewWithTag<View>(INNER_TAG).findList())

  private fun View.findEditor(): EditText? {
    if (this is EditText) return this
    if (this is ViewGroup) {
      for (i in 0 until childCount) {
        getChildAt(i).findEditor()?.let {
          return it
        }
      }
    }
    return null
  }

  private fun View.findList(): SectionsRecyclerView? {
    if (this is SectionsRecyclerView) {
      return this
    }
    if (this is ViewGroup) {
      for (i in 0 until childCount) {
        getChildAt(i).findList()?.let {
          return it
        }
      }
    }
    return null
  }

  private fun focus(list: SectionsRecyclerView) {
    assertThat(list.isAttachedToWindow).isTrue()
    assertThat(list.recyclerView.width).isGreaterThan(0)
    assertThat(list.recyclerView.height).isGreaterThan(0)
    list.recyclerView.isFocusableInTouchMode = true
    assertThat(list.recyclerView.requestFocus()).isTrue()
    assertThat(activity.window.decorView.findFocus()).isSameAs(list.recyclerView)
  }

  private fun press(
      list: SectionsRecyclerView,
      code: Int = KeyEvent.KEYCODE_R,
      modifiers: Int = KeyEvent.META_CTRL_ON,
  ) {
    assertThat(list.isAttachedToWindow).isTrue()
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code = code, modifiers = modifiers))
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code = code, modifiers = modifiers))
  }

  private fun key(
      action: Int,
      repeat: Int = 0,
      downTime: Long? = null,
      code: Int = KeyEvent.KEYCODE_R,
      modifiers: Int = KeyEvent.META_CTRL_ON,
      deviceId: Int = -1,
  ): KeyEvent {
    val time =
        downTime
            ?: if (action == KeyEvent.ACTION_DOWN && repeat == 0) {
              (++nextPressTime).also { currentPressTime = it }
            } else currentPressTime
    return KeyEvent(
        time,
        time + repeat + 1,
        action,
        code,
        repeat,
        modifiers,
        deviceId,
        0,
        0,
        InputDevice.SOURCE_KEYBOARD,
    )
  }

  private class FocusList(
      private val onChildKey: (KeyEvent) -> Boolean,
      private val onRefresh: () -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val onRowKey = useCallback<View, Int, KeyEvent, Boolean> { _, _, event -> onChildKey(event) }
      val onEditorKeyUp = useCallback<KeyUpEvent, Boolean> { event -> onChildKey(event.keyEvent) }
      return LazyList(
          onPullToRefresh = onRefresh,
          style = Style.viewTag(TAG).width(200.px).height(200.px),
      ) {
        for (id in focusRowIds) {
          child(
              id = id,
              isSticky = id == "header",
              component =
                  Column(
                      style =
                          Style.viewTag(id)
                              .focusable(true)
                              .width(200.px)
                              .height(40.px)
                              .onKeyEvent(listener = onRowKey),
                  ),
          )
        }
        child(
            id = "editor",
            component =
                TextInput(
                    initialText = "",
                    style = Style.viewTag("editor").height(40.px),
                    onKeyUp = onEditorKeyUp,
                ),
        )
        for (i in 0 until 10) {
          child(id = i, component = Column(style = Style.height(40.px)))
        }
      }
    }
  }

  private class RebindingList(
      private val onOldRefresh: (Int) -> Unit,
      private val onNewRefresh: (Int) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val version = useState { 0 }
      val controller = useLazyCollectionController()
      val renderedVersion = version.value
      val callback = if (renderedVersion == 0) onOldRefresh else onNewRefresh
      return LazyList(
          topPadding = version.value.px,
          lazyCollectionController = controller,
          onPullToRefresh = {
            callback(renderedVersion)
            controller.setRefreshing(false)
            version.update { it + 1 }
          },
          style = Style.viewTag(TAG).width(200.px).height(200.px),
      ) {
        child(id = 0, component = Column(style = Style.height(40.px)))
      }
    }
  }

  private class NestedLists(
      private val innerOrientation: Int,
      private val innerRefresh: (() -> Unit)?,
      private val outerRefresh: () -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = LazyList(
        onPullToRefresh = outerRefresh,
        style = Style.viewTag(TAG).width(200.px).height(200.px),
    ) {
      child(
          id = "inner",
          component =
              LazyList(
                  orientation = innerOrientation,
                  onPullToRefresh = innerRefresh,
                  style = Style.viewTag(INNER_TAG).width(200.px).height(100.px),
              ) {
                for (i in 0 until 10) {
                  child(id = i, component = Column(style = Style.width(40.px).height(40.px)))
                }
              },
      )
    }
  }

  private class LegacyList(
      private val strategy: PrimitiveRecyclerBinderStrategy,
      private val refreshEnabled: Boolean,
      private val onRefresh: () -> Unit,
  ) : KComponent() {
    @SuppressLint("DeprecatedClass")
    override fun ComponentScope.render(): Component =
        RecyclerCollectionComponent.create(context)
            .section(
                SingleComponentSection.create(SectionContext(context))
                    .component(Column(style = Style.height(40.px)))
                    .build(),
            )
            .recyclerConfiguration(
                ListRecyclerConfiguration.create()
                    .recyclerBinderConfiguration(
                        RecyclerBinderConfiguration.create()
                            .primitiveRecyclerBinderStrategy(strategy)
                            .build(),
                    )
                    .build(),
            )
            .disablePTR(!refreshEnabled)
            .ignoreLoadingUpdates(true)
            .pTRRefreshEventHandler(
                eventHandlerWithReturn { _: PTRRefreshEvent ->
                  onRefresh()
                  true
                },
            )
            .viewTag(TAG)
            .widthPx(200)
            .heightPx(200)
            .build()
  }

  private class LazyTestList(
      private val refreshEnabled: Boolean,
      private val orientation: Int,
      private val onRefresh: () -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = LazyList(
        orientation = orientation,
        onPullToRefresh = if (refreshEnabled) onRefresh else null,
        style = Style.viewTag(TAG).width(200.px).height(200.px),
    ) {
      for (i in 0 until 10) {
        child(id = i, component = Column(style = Style.width(40.px).height(40.px)))
      }
    }
  }

  private enum class Backend(val strategy: PrimitiveRecyclerBinderStrategy) {
    RECYCLER_DEFAULT(PrimitiveRecyclerBinderStrategy.DEFAULT),
    RECYCLER_SPLIT(PrimitiveRecyclerBinderStrategy.SPLIT_BINDERS),
    SECTIONS_DEFAULT(PrimitiveRecyclerBinderStrategy.DEFAULT),
    SECTIONS_SPLIT(PrimitiveRecyclerBinderStrategy.SPLIT_BINDERS),
    LAZY_DEFAULT(PrimitiveRecyclerBinderStrategy.DEFAULT),
    LAZY_SPLIT(PrimitiveRecyclerBinderStrategy.SPLIT_BINDERS),
    LAZY_NEW(PrimitiveRecyclerBinderStrategy.DEFAULT),
  }

  private companion object {
    const val TAG = "keyboard_refresh_list"
    const val INNER_TAG = "inner_keyboard_refresh_list"
    val shortcuts = listOf(
        KeyEvent.KEYCODE_R to KeyEvent.META_CTRL_ON,
        KeyEvent.KEYCODE_F5 to 0,
        KeyEvent.KEYCODE_REFRESH to 0,
    )
    val focusRowIds = listOf("header", "row-1", "row-2")
    val lazyBackends = listOf(Backend.LAZY_DEFAULT, Backend.LAZY_SPLIT, Backend.LAZY_NEW)
  }
}
