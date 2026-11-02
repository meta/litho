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
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.unspecified
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextInputControllerTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @SuppressLint("SetTextI18n") // Simulated typing, not UI copy.
  @Test
  fun `a layout that never commits does not take the controller from the mounted input`() {
    val holder = ControllerHolder()
    val tree = ComponentTree.create(lithoTestRule.context).build()
    val gate = Gate()

    // Layout 1 puts the input under a Row, so its key differs from layout 2's. It's held before the
    // input renders, layout 2 commits and mounts, then layout 1 renders the input and is dropped.
    val supersededLayout = Thread {
      tree.setRootAndSizeSpecSync(Root(1, holder, gate), exactly(500), unspecified())
    }
    supersededLayout.start()
    check(gate.entered.await(20, TimeUnit.SECONDS)) { "layout 1 never started" }
    tree.setRootAndSizeSpecSync(Root(2, holder, null), exactly(500), unspecified())
    val lithoView = mount(tree)
    checkNotNull(findEditText(lithoView)).setText("hello")
    gate.release.countDown()
    supersededLayout.join(20_000)

    lithoView.componentTree = null
    val controller = checkNotNull(holder.controller)
    assertThat(controller.getText()?.toString()).isEqualTo("hello")

    controller.setText("edited")
    assertThat(findEditText(mount(tree))?.text?.toString()).isEqualTo("edited")
  }

  private class ControllerHolder {
    @Volatile var controller: TextInputController? = null
  }

  private class Gate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
  }

  private class Blocker(private val gate: Gate?) : KComponent() {
    override fun ComponentScope.render(): Component {
      if (gate != null) {
        gate.entered.countDown()
        check(gate.release.await(20, TimeUnit.SECONDS)) { "layout 1 was never released" }
      }
      return EmptyComponent()
    }
  }

  private class Root(
      private val version: Int,
      private val holder: ControllerHolder,
      private val gate: Gate?,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val controller = useTextInputController()
      holder.controller = controller
      val input = ExperimentalTextInput(
          initialText = if (version == 1) "A" else "B",
          textInputController = controller,
      )
      return Column {
        child(Blocker(gate))
        if (version == 1) {
          child(Row { child(input) })
        } else {
          child(input)
        }
      }
    }
  }

  private fun mount(tree: ComponentTree): LithoView {
    val lithoView = LithoView(lithoTestRule.context.androidContext)
    lithoView.componentTree = tree
    lithoView.measure(exactly(500), unspecified())
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)
    return lithoView
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
