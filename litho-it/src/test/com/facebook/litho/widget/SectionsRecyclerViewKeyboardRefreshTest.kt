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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SectionsRecyclerViewKeyboardRefreshTest {
  private val activityController =
      Robolectric.buildActivity(KeyRecordingActivity::class.java).setup()
  @field:SuppressLint("ActivityStoredInField") private val activity = activityController.get()
  private val recyclerView = RecyclerView(activity)
  private val list = SectionsRecyclerView(activity, recyclerView)
  private var refreshes = 0
  private var nextPressTime = 100L
  private var currentPressTime = 0L

  init {
    recyclerView.layoutManager = LinearLayoutManager(activity)
    activity.setContentView(list)
    list.onKeyboardRefresh = { refreshes++ }
    focus(recyclerView)
  }

  @After
  fun tearDown() {
    activityController.pause().stop().destroy()
  }

  @Test
  fun `refresh shortcuts show loading and invoke refresh once per press`() {
    for ((code, modifiers) in shortcuts) {
      list.isRefreshing = false
      val before = refreshes

      assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code, modifiers))).isTrue()
      assertThat(refreshes).isEqualTo(before + 1)
      assertThat(list.isRefreshing).isTrue()
      assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code, modifiers))).isTrue()
      assertThat(refreshes).isEqualTo(before + 1)
      assertThat(recyclerView.isFocused).isTrue()
    }
  }

  @Test
  fun `held keys do not refresh again even if the callback finishes synchronously`() {
    list.onKeyboardRefresh = {
      refreshes++
      list.isRefreshing = false
    }

    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1))).isTrue()
    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 2))).isTrue()
    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()
    assertThat(refreshes).isEqualTo(1)
    assertThat(list.isRefreshing).isFalse()

    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
    assertThat(refreshes).isEqualTo(2)
    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()
  }

  @Test
  fun `overlapping shortcuts retain both presses through either release order`() {
    val softly = SoftAssertions()
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    val pairs = shortcuts.flatMap { first -> shortcuts.filter { it != first }.map { first to it } }
    for ((first, second) in pairs) {
      for (reverseRelease in listOf(false, true)) {
        for (synchronous in listOf(false, true)) {
          activity.setContentView(View(activity))
          activity.setContentView(list)
          focus(child)
          received.clear()
          activity.keyDowns.clear()
          activity.keyUps.clear()
          refreshes = 0
          list.isRefreshing = false
          list.onKeyboardRefresh = {
            refreshes++
            if (synchronous) list.isRefreshing = false
          }
          val downs =
              listOf(first, second).mapIndexed { index, (code, modifiers) ->
                key(KeyEvent.ACTION_DOWN, code, modifiers, downTime = 100L + index)
              }
          val label = "$first/$second reverse=$reverseRelease sync=$synchronous"
          softly.assertThat(activity.dispatchKeyEvent(downs[0])).describedAs(label).isTrue()
          val controlUp =
              if (first.first == KeyEvent.KEYCODE_R) {
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, downTime = 100).also {
                  activity.dispatchKeyEvent(it)
                }
              } else null
          softly.assertThat(activity.dispatchKeyEvent(downs[1])).describedAs(label).isTrue()
          downs.forEach {
            activity.dispatchKeyEvent(
                key(KeyEvent.ACTION_DOWN, it.keyCode, 0, repeat = 1, downTime = 102),
            )
          }
          val releases = if (reverseRelease) downs.reversed() else downs
          activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, releases[0].keyCode, 0, downTime = 102))
          activity.dispatchKeyEvent(
              key(KeyEvent.ACTION_DOWN, releases[1].keyCode, 0, repeat = 2, downTime = 103),
          )
          activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, releases[1].keyCode, 0, downTime = 103))

          softly
              .assertThat(received)
              .describedAs(label)
              .containsExactlyElementsOf(listOfNotNull(downs[0], controlUp, downs[1]))
          softly.assertThat(activity.keyDowns).describedAs(label).isEmpty()
          softly
              .assertThat(activity.keyUps)
              .describedAs(label)
              .containsExactlyElementsOf(listOfNotNull(controlUp))
          softly.assertThat(refreshes).describedAs(label).isEqualTo(if (synchronous) 2 else 1)
          softly.assertThat(child.isFocused).describedAs(label).isTrue()
        }
      }
    }
    softly.assertAll()
  }

  @Test
  fun `another device keeps ordinary R while the original device owns Ctrl R`() {
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    focus(child)
    val down = key(KeyEvent.ACTION_DOWN, downTime = 100, deviceId = 10)
    activity.dispatchKeyEvent(down)
    received.clear()
    for (device in listOf(-1, 11)) {
      val ordinary = listOf(
          key(KeyEvent.ACTION_DOWN, modifiers = 0, downTime = 100, deviceId = device),
          key(KeyEvent.ACTION_DOWN, modifiers = 0, repeat = 1, downTime = 100, deviceId = device),
          key(KeyEvent.ACTION_UP, modifiers = 0, downTime = 100, deviceId = device),
      )
      activity.keyDowns.clear()
      activity.keyUps.clear()
      received.clear()
      ordinary.forEach { assertThat(activity.dispatchKeyEvent(it)).isTrue() }
      assertThat(received).describedAs("device $device").containsExactlyElementsOf(ordinary)
      assertThat(activity.keyDowns).containsExactly(ordinary[0], ordinary[1])
      assertThat(activity.keyUps).containsExactly(ordinary[2])
    }
    received.clear()
    activity.keyDowns.clear()
    activity.keyUps.clear()
    activity.dispatchKeyEvent(
        key(KeyEvent.ACTION_DOWN, modifiers = 0, repeat = 1, downTime = 102, deviceId = 10),
    )
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, modifiers = 0, downTime = 102, deviceId = 10))
    assertThat(received).isEmpty()
    assertThat(activity.keyDowns).isEmpty()
    assertThat(activity.keyUps).isEmpty()
    assertThat(refreshes).isEqualTo(1)
  }

  @Test
  fun `chord timestamps do not expose an owned repeat or release to the child`() {
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    focus(child)
    val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F5, 0, downTime = 100)
    val letterDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, downTime = 101)
    val letterUp = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, downTime = 101)
    activity.dispatchKeyEvent(down)
    activity.dispatchKeyEvent(letterDown)
    activity.dispatchKeyEvent(letterUp)
    activity.keyDowns.clear()
    activity.keyUps.clear()
    activity.dispatchKeyEvent(
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F5, 0, repeat = 1, downTime = 101),
    )
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F5, 0, downTime = 101))
    assertThat(received).containsExactly(down, letterDown, letterUp)
    assertThat(activity.keyDowns).isEmpty()
    assertThat(activity.keyUps).isEmpty()
    list.isRefreshing = false
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F5, 0, downTime = 102))
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F5, 0, downTime = 102))
    assertThat(refreshes).isEqualTo(2)
  }

  @Test
  fun `child redispatch of the same initial down refreshes once even with a copied event`() {
    val softly = SoftAssertions()
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    focus(child)
    list.onKeyboardRefresh = {
      refreshes++
      list.isRefreshing = false
    }
    for (copy in listOf(false, true)) {
      received.clear()
      val down = key(KeyEvent.ACTION_DOWN)
      val inner = if (copy) KeyEvent(down) else down
      var redispatched = false
      child.setOnKeyListener { _, _, event ->
        received.add(event)
        if (!redispatched && event.action == KeyEvent.ACTION_DOWN) {
          redispatched = true
          assertThat(list.dispatchKeyEvent(inner)).isTrue()
        }
        false
      }
      val before = refreshes
      assertThat(activity.dispatchKeyEvent(down)).isTrue()
      softly.assertThat(refreshes).describedAs("copy=$copy").isEqualTo(before + 1)
      softly.assertThat(received).describedAs("copy=$copy").containsExactly(down, inner)
      activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1, downTime = down.downTime + 1))
      activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime + 1))
      softly.assertThat(received).describedAs("copy=$copy").containsExactly(down, inner)
      softly.assertThat(activity.keyDowns).describedAs("copy=$copy").isEmpty()
      softly.assertThat(activity.keyUps).describedAs("copy=$copy").isEmpty()
    }
    softly.assertAll()
  }

  @Test
  fun `child redispatch of a different shortcut keeps both presses`() {
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    focus(child)
    list.onKeyboardRefresh = {
      refreshes++
      list.isRefreshing = false
    }
    val down = key(KeyEvent.ACTION_DOWN, downTime = 100)
    val inner = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F5, 0, downTime = 101)
    var redispatched = false
    child.setOnKeyListener { _, _, event ->
      received.add(event)
      if (!redispatched && event.action == KeyEvent.ACTION_DOWN) {
        redispatched = true
        assertThat(list.dispatchKeyEvent(inner)).isTrue()
      }
      false
    }
    activity.dispatchKeyEvent(down)
    for (event in listOf(down, inner)) {
      activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, event.keyCode, 0, repeat = 1, downTime = 102),
      )
      activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, event.keyCode, 0, downTime = 102))
    }
    assertThat(received).containsExactly(down, inner)
    assertThat(refreshes).isEqualTo(2)
    assertThat(activity.keyDowns).isEmpty()
    assertThat(activity.keyUps).isEmpty()
  }

  @Test
  fun `six device shortcut owners survive callback clearing and rebinding`() {
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    focus(child)
    val downs =
        listOf(10, 11).flatMap { device ->
          shortcuts.map { (code, modifiers) ->
            key(KeyEvent.ACTION_DOWN, code, modifiers, downTime = 100, deviceId = device)
          }
        }
    downs.forEach { activity.dispatchKeyEvent(it) }
    list.onKeyboardRefresh = null
    list.isRefreshing = false
    var newRefreshes = 0
    for ((index, down) in downs.withIndex()) {
      if (index == 3) list.onKeyboardRefresh = { newRefreshes++ }
      activity.dispatchKeyEvent(
          key(
              KeyEvent.ACTION_DOWN,
              down.keyCode,
              0,
              repeat = 1,
              downTime = 101,
              deviceId = down.deviceId,
          ),
      )
      val up = key(KeyEvent.ACTION_UP, down.keyCode, 0, downTime = 101, deviceId = down.deviceId)
      activity.dispatchKeyEvent(KeyEvent.changeFlags(up, KeyEvent.FLAG_CANCELED))
    }
    assertThat(received).containsExactlyElementsOf(downs)
    assertThat(activity.keyDowns).isEmpty()
    assertThat(activity.keyUps).isEmpty()
    assertThat(refreshes).isEqualTo(1)
    assertThat(newRefreshes).isZero()
    val orphan = key(KeyEvent.ACTION_UP, downTime = 101, deviceId = 10)
    activity.dispatchKeyEvent(orphan)
    assertThat(received).contains(orphan)
    assertThat(activity.keyUps).containsExactly(orphan)
  }

  @Test
  fun `a canceled initial down discards an old owner without claiming a new one`() {
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    for (time in listOf(100L, 101L)) {
      activity.setContentView(View(activity))
      activity.setContentView(list)
      focus(child)
      list.isRefreshing = false
      activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, downTime = 100))
      received.clear()
      activity.keyDowns.clear()
      activity.keyUps.clear()
      val canceled =
          KeyEvent.changeFlags(key(KeyEvent.ACTION_DOWN, downTime = time), KeyEvent.FLAG_CANCELED)
      val repeat = key(KeyEvent.ACTION_DOWN, repeat = 1, downTime = 100)
      val up = key(KeyEvent.ACTION_UP, downTime = 100)
      activity.dispatchKeyEvent(canceled)
      activity.dispatchKeyEvent(repeat)
      activity.dispatchKeyEvent(up)
      assertThat(received).describedAs("canceled time=$time").containsExactly(canceled, repeat, up)
      assertThat(activity.keyDowns).containsExactly(canceled, repeat)
      assertThat(activity.keyUps).containsExactly(up)
    }
    assertThat(refreshes).isEqualTo(2)
  }

  @Test
  fun `true detach clears every device shortcut owner even during refresh callback`() {
    val received = mutableListOf<KeyEvent>()
    val child = recordingChild(received)
    focus(child)
    val downs =
        listOf(-1, 11).flatMap { device ->
          shortcuts.map { (code, modifiers) ->
            key(KeyEvent.ACTION_DOWN, code, modifiers, deviceId = device)
          }
        }
    downs.forEach { activity.dispatchKeyEvent(it) }
    activity.setContentView(View(activity))
    assertThat(list.isAttachedToWindow).isFalse()
    activity.setContentView(list)
    focus(child)
    received.clear()
    val ups = downs.map {
      key(KeyEvent.ACTION_UP, it.keyCode, 0, downTime = it.downTime, deviceId = it.deviceId)
    }
    ups.forEach { activity.dispatchKeyEvent(it) }
    assertThat(received).containsExactlyElementsOf(ups)
    assertThat(activity.keyUps).containsExactlyElementsOf(ups)

    received.clear()
    activity.keyUps.clear()
    list.isRefreshing = false
    list.onKeyboardRefresh = { activity.setContentView(View(activity)) }
    val down = key(KeyEvent.ACTION_DOWN)
    activity.dispatchKeyEvent(down)
    assertThat(list.isAttachedToWindow).isFalse()
    activity.setContentView(list)
    focus(child)
    received.clear()
    val up = key(KeyEvent.ACTION_UP, downTime = down.downTime)
    activity.dispatchKeyEvent(up)
    assertThat(received).containsExactly(up)
    assertThat(activity.keyUps).containsExactly(up)
  }

  @Test
  fun `an existing refresh suppresses another request`() {
    list.isRefreshing = true

    press()

    assertThat(refreshes).isZero()
    assertThat(list.isRefreshing).isTrue()
  }

  @Test
  fun `unmodified letters and extra modifiers keep their normal behavior`() {
    val child =
        object : View(activity) {
          val received = mutableListOf<KeyEvent>()

          override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            received.add(event)
            return false
          }

          override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            received.add(event)
            return false
          }
        }
    recyclerView.addView(child)
    focus(child)
    for ((code, modifiers) in ordinaryKeys) {
      val down = key(KeyEvent.ACTION_DOWN, code, modifiers)
      val up = key(KeyEvent.ACTION_UP, code, modifiers)
      child.received.clear()
      assertThat(list.dispatchKeyEvent(down)).describedAs("down $code/$modifiers").isFalse()
      assertThat(list.dispatchKeyEvent(up)).describedAs("up $code/$modifiers").isFalse()
      assertThat(child.received).containsExactly(down, up)

      list.onKeyboardRefresh = null
      activity.keyDowns.clear()
      activity.keyUps.clear()
      val baselineDown = activity.dispatchKeyEvent(down)
      val baselineUp = activity.dispatchKeyEvent(up)
      val fallbackDowns = activity.keyDowns.toList()
      val fallbackUps = activity.keyUps.toList()
      list.onKeyboardRefresh = { refreshes++ }
      activity.keyDowns.clear()
      activity.keyUps.clear()
      child.received.clear()
      assertThat(activity.dispatchKeyEvent(down)).isEqualTo(baselineDown)
      assertThat(activity.dispatchKeyEvent(up)).isEqualTo(baselineUp)
      assertThat(child.received).containsExactly(down, up)
      assertThat(activity.keyDowns).containsExactlyElementsOf(fallbackDowns)
      assertThat(activity.keyUps).containsExactlyElementsOf(fallbackUps)
      assertThat(child.isFocused).isTrue()
    }

    assertThat(refreshes).isZero()
    assertThat(list.isRefreshing).isFalse()
  }

  @Test
  fun `releasing control before R still consumes the matching key up`() {
    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, modifiers = 0))).isTrue()
    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, modifiers = 0))).isFalse()
    assertThat(refreshes).isEqualTo(1)
  }

  @Test
  fun `an orphan key up or canceled key down does not refresh`() {
    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isFalse()
    list.dispatchKeyEvent(KeyEvent.changeFlags(key(KeyEvent.ACTION_DOWN), KeyEvent.FLAG_CANCELED))

    assertThat(refreshes).isZero()
  }

  @Test
  fun `a focused child can refresh without moving focus`() {
    val child = View(activity)
    recyclerView.addView(child)
    focus(child)

    press()

    assertThat(refreshes).isEqualTo(1)
    assertThat(child.isFocused).isTrue()
  }

  @Test
  fun `a child's key listener takes precedence`() {
    val child = View(activity)
    recyclerView.addView(child)
    focus(child)
    var childKeys = 0
    child.setOnKeyListener { _, _, _ ->
      childKeys++
      true
    }

    press()

    assertThat(childKeys).isEqualTo(2)
    assertThat(refreshes).isZero()
  }

  @Test
  fun `text editors keep refresh shortcuts`() {
    val editor = EditText(activity)
    recyclerView.addView(editor)
    focus(editor)

    val received = mutableListOf<KeyEvent>()
    editor.setOnKeyListener { _, _, event ->
      received.add(event)
      false
    }
    for ((code, modifiers) in shortcuts + ordinaryKeys) {
      val down = key(KeyEvent.ACTION_DOWN, code, modifiers)
      val up = key(KeyEvent.ACTION_UP, code, modifiers)
      list.onKeyboardRefresh = null
      editor.setText("")
      activity.keyDowns.clear()
      activity.keyUps.clear()
      val baselineDown = activity.dispatchKeyEvent(down)
      val baselineUp = activity.dispatchKeyEvent(up)
      val baselineText = editor.text.toString()
      val fallbackDowns = activity.keyDowns.toList()
      val fallbackUps = activity.keyUps.toList()

      list.onKeyboardRefresh = { refreshes++ }
      editor.setText("")
      received.clear()
      activity.keyDowns.clear()
      activity.keyUps.clear()
      assertThat(activity.dispatchKeyEvent(down)).isEqualTo(baselineDown)
      assertThat(activity.dispatchKeyEvent(up)).isEqualTo(baselineUp)
      assertThat(received).containsExactly(down, up)
      assertThat(editor.text.toString()).isEqualTo(baselineText)
      assertThat(activity.keyDowns).containsExactlyElementsOf(fallbackDowns)
      assertThat(activity.keyUps).containsExactlyElementsOf(fallbackUps)
    }

    assertThat(refreshes).isZero()
    assertThat(list.isRefreshing).isFalse()
    assertThat(editor.isFocused).isTrue()
  }

  @Test
  fun `a focused sticky header can refresh its collection`() {
    val header = View(activity)
    header.layoutParams = ViewGroup.LayoutParams(100, 100)
    list.stickyHeaderView = header
    focus(header)

    press()

    assertThat(refreshes).isEqualTo(1)
    assertThat(header.isFocused).isTrue()
  }

  @Test
  fun `a nonrefreshable nested list falls back to the eligible parent`() {
    val innerRecycler = RecyclerView(activity)
    val innerList = SectionsRecyclerView(activity, innerRecycler)
    recyclerView.addView(innerList)
    innerList.layout(0, 0, 100, 100)
    focus(innerRecycler)

    press()

    assertThat(refreshes).isEqualTo(1)
    assertThat(innerRecycler.isFocused).isTrue()
  }

  @Test
  fun `clearing the keyboard callback preserves its consumed key up`() {
    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
    list.onKeyboardRefresh = null
    list.isRefreshing = false

    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()
    press()

    assertThat(refreshes).isEqualTo(1)
    assertThat(list.isRefreshing).isFalse()
  }

  @Test
  fun `a disabled refresh container ignores shortcuts`() {
    list.isEnabled = false

    press()

    assertThat(refreshes).isZero()
    assertThat(list.isRefreshing).isFalse()
  }

  @Test
  fun `a disabled recycler ignores shortcuts`() {
    recyclerView.isEnabled = false

    press()

    assertThat(refreshes).isZero()
  }

  @Test
  fun `an orphan unknown key up reaches the focused child with the gate off`() {
    list.onKeyboardRefresh = null
    val child = View(activity)
    recyclerView.addView(child)
    focus(child)
    val received = mutableListOf<KeyEvent>()
    child.setOnKeyListener { _, _, event ->
      received.add(event)
      true
    }
    val event = key(KeyEvent.ACTION_UP, code = KeyEvent.KEYCODE_UNKNOWN, downTime = 700)

    assertThat(activity.dispatchKeyEvent(event)).isTrue()

    assertThat(received).containsExactly(event)
    assertThat(refreshes).isZero()
  }

  @Test
  fun `window dispatch refreshes the focused list before Activity fallback`() {
    assertThat(list.isAttachedToWindow).isTrue()
    assertThat(recyclerView.width).isGreaterThan(0)

    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))).isTrue()
    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()

    assertThat(refreshes).isEqualTo(1)
    assertThat(activity.keyDowns).isEmpty()
    assertThat(activity.keyUps).isEmpty()
    assertThat(activity.window.decorView.findFocus()).isSameAs(recyclerView)
  }

  @Test
  fun `window dispatch reaches Activity onKeyDown when the gate is off`() {
    list.onKeyboardRefresh = null
    val down = key(KeyEvent.ACTION_DOWN)

    assertThat(activity.dispatchKeyEvent(down)).isTrue()
    assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))).isTrue()

    assertThat(activity.keyDowns).containsExactly(down)
    assertThat(activity.keyUps).hasSize(1)
    assertThat(refreshes).isZero()
  }

  @Test
  fun `a key up from another device or key does not release the consumed press`() {
    val down = key(KeyEvent.ACTION_DOWN)
    assertThat(list.dispatchKeyEvent(down)).isTrue()
    val unrelatedUp = key(KeyEvent.ACTION_UP, downTime = down.downTime, deviceId = 11)

    assertThat(activity.dispatchKeyEvent(unrelatedUp)).isTrue()
    assertThat(activity.keyUps).containsExactly(unrelatedUp)
    val otherKeyUp = key(KeyEvent.ACTION_UP, code = KeyEvent.KEYCODE_A, downTime = down.downTime)
    activity.dispatchKeyEvent(otherKeyUp)
    assertThat(activity.keyUps).containsExactly(unrelatedUp, otherKeyUp)
    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime))).isTrue()
  }

  @Test
  fun `callback rebinding keeps repeats and key up in the consumed press`() {
    list.onKeyboardRefresh = {
      refreshes++
      list.isRefreshing = false
    }
    val down = key(KeyEvent.ACTION_DOWN)
    assertThat(list.dispatchKeyEvent(down)).isTrue()
    var newRefreshes = 0
    list.onKeyboardRefresh = { newRefreshes++ }

    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, downTime = down.downTime))).isTrue()
    assertThat(
        list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1, downTime = down.downTime)),
    )
        .isTrue()
    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime))).isTrue()
    assertThat(newRefreshes).isZero()

    press()
    assertThat(refreshes).isEqualTo(1)
    assertThat(newRefreshes).isEqualTo(1)
  }

  @Test
  fun `a consumed press stays owned when focus moves to a child with a key listener`() {
    val down = key(KeyEvent.ACTION_DOWN)
    assertThat(activity.dispatchKeyEvent(down)).isTrue()
    val child = View(activity)
    recyclerView.addView(child)
    focus(child)
    var childKeys = 0
    child.setOnKeyListener { _, _, _ ->
      childKeys++
      true
    }

    activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1, downTime = down.downTime))
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime))

    assertThat(childKeys).isZero()
    assertThat(refreshes).isEqualTo(1)
  }

  @Test
  fun `a child's consumed down does not become a refresh on a later repeat`() {
    val child = View(activity)
    recyclerView.addView(child)
    focus(child)
    child.setOnKeyListener { _, _, event ->
      event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
    }
    val down = key(KeyEvent.ACTION_DOWN)

    activity.dispatchKeyEvent(down)
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1, downTime = down.downTime))
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime))

    assertThat(refreshes).isZero()
  }

  @Test
  fun `a new press works after focus leaves the collection without its old key up`() {
    list.onKeyboardRefresh = {
      refreshes++
      list.isRefreshing = false
    }
    val down = key(KeyEvent.ACTION_DOWN)
    activity.dispatchKeyEvent(down)
    val outside = View(activity)
    (list.parent as ViewGroup).addView(outside)
    focus(outside)
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime))
    focus(recyclerView)

    val next = key(KeyEvent.ACTION_DOWN)
    assertThat(next.downTime).isNotEqualTo(down.downTime)
    activity.dispatchKeyEvent(next)
    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP))

    assertThat(refreshes).isEqualTo(2)
  }

  @Test
  fun `true window detach clears a consumed press`() {
    val down = key(KeyEvent.ACTION_DOWN)
    list.dispatchKeyEvent(down)
    activity.setContentView(View(activity))
    assertThat(list.isAttachedToWindow).isFalse()
    activity.setContentView(list)
    focus(recyclerView)

    assertThat(list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, downTime = down.downTime))).isFalse()
  }

  @Test
  fun `function and lock modifiers preserve refresh key meaning`() {
    for ((code, modifiers) in
        listOf(
            KeyEvent.KEYCODE_F5 to KeyEvent.META_FUNCTION_ON,
            KeyEvent.KEYCODE_F5 to (KeyEvent.META_FUNCTION_ON or KeyEvent.META_CAPS_LOCK_ON),
            KeyEvent.KEYCODE_R to (KeyEvent.META_CTRL_ON or KeyEvent.META_NUM_LOCK_ON),
            KeyEvent.KEYCODE_REFRESH to KeyEvent.META_FUNCTION_ON,
            KeyEvent.KEYCODE_REFRESH to KeyEvent.META_CTRL_ON,
            KeyEvent.KEYCODE_REFRESH to KeyEvent.META_ALT_ON,
        )) {
      list.isRefreshing = false
      val before = refreshes

      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code, modifiers))).isTrue()
      assertThat(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code, modifiers))).isTrue()

      assertThat(refreshes).isEqualTo(before + 1)
    }
  }

  private fun press() {
    list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))
    list.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
  }

  private fun recordingChild(received: MutableList<KeyEvent>): View =
      View(activity).also { child ->
        recyclerView.addView(child)
        child.setOnKeyListener { _, _, event ->
          received.add(event)
          false
        }
      }

  private fun focus(view: View) {
    if (view.width == 0 || view.height == 0) {
      view.layout(0, 0, 100, 100)
    }
    view.isFocusableInTouchMode = true
    assertThat(view.requestFocus()).isTrue()
    assertThat(view.isFocused).isTrue()
  }

  private fun key(
      action: Int,
      code: Int = KeyEvent.KEYCODE_R,
      modifiers: Int = KeyEvent.META_CTRL_ON,
      repeat: Int = 0,
      downTime: Long? = null,
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

  private val ordinaryKeys = listOf(
      KeyEvent.KEYCODE_TAB to 0,
      KeyEvent.KEYCODE_DPAD_DOWN to 0,
      KeyEvent.KEYCODE_ENTER to 0,
      KeyEvent.KEYCODE_R to 0,
      KeyEvent.KEYCODE_R to KeyEvent.META_ALT_ON,
      KeyEvent.KEYCODE_R to (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
      KeyEvent.KEYCODE_F5 to KeyEvent.META_CTRL_ON,
      KeyEvent.KEYCODE_F5 to KeyEvent.META_ALT_ON,
      KeyEvent.KEYCODE_F5 to KeyEvent.META_SHIFT_ON,
  )

  private val shortcuts = listOf(
      KeyEvent.KEYCODE_R to KeyEvent.META_CTRL_ON,
      KeyEvent.KEYCODE_F5 to 0,
      KeyEvent.KEYCODE_REFRESH to 0,
  )
}

class KeyRecordingActivity : Activity() {
  var consumeFallback = true
  val keyDowns = mutableListOf<KeyEvent>()
  val keyUps = mutableListOf<KeyEvent>()

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    keyDowns.add(event)
    return consumeFallback
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    keyUps.add(event)
    return consumeFallback
  }
}
