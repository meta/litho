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

package com.facebook.samples.litho.documentation.customlayout

import android.graphics.Color
import android.graphics.Rect
import com.facebook.litho.Component
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SolidColor
import com.facebook.rendercore.px
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class EqualSplitLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  /** A child with no width of its own, which fills the share it's given. */
  private fun filler(name: String, height: Int): Component =
      Named(name, SolidColor(color = Color.BLACK, style = Style.height(height.px)))

  private fun threeFillers() =
      EqualSplitLayout(listOf(filler("a", 10), filler("b", 20), filler("c", 15)))

  @Test
  fun `the left-over pixel goes to the first share, and the layout is as tall as the tallest`() {
    val laidOut = lithoTestRule.layOut(threeFillers(), Room.AtMost(100))

    assertThat(laidOut.width to laidOut.height).isEqualTo(100 to 20)
    assertThat(laidOut["a"]).isEqualTo(Rect(0, 0, 34, 10))
    assertThat(laidOut["b"]).isEqualTo(Rect(34, 0, 67, 20))
    assertThat(laidOut["c"]).isEqualTo(Rect(67, 0, 100, 15))
  }

  @Test
  fun `right to left, the first share is at the right edge`() {
    val laidOut = lithoTestRule.layOut(threeFillers(), Room.Exactly(100), rtl = true)

    assertThat(laidOut["a"]).isEqualTo(Rect(66, 0, 100, 10))
    assertThat(laidOut["b"]).isEqualTo(Rect(33, 0, 66, 20))
    assertThat(laidOut["c"]).isEqualTo(Rect(0, 0, 33, 15))
  }

  @Test
  fun `a width that divides evenly gives equal shares`() {
    val laidOut = lithoTestRule.layOut(threeFillers(), Room.Exactly(99))

    assertThat(listOf("a", "b", "c").map { name -> laidOut[name].width() })
        .containsExactly(33, 33, 33)
  }

  @Test
  fun `with fewer pixels than children, the last share is 0 px wide`() {
    val laidOut = lithoTestRule.layOut(threeFillers(), Room.Exactly(2))

    assertThat(listOf("a", "b", "c").map { name -> laidOut[name].left to laidOut[name].width() })
        .containsExactly(0 to 1, 1 to 1, 2 to 0)
  }

  @Test
  fun `with an unbounded width, each share is as wide as the widest child`() {
    fun boxes() = EqualSplitLayout(listOf(box("a", 30, 10), box("b", 50, 10), box("c", 40, 10)))

    val ltr = lithoTestRule.layOut(boxes(), Room.Unbounded)
    val rtl = lithoTestRule.layOut(boxes(), Room.Unbounded, rtl = true)

    assertThat(ltr.width).isEqualTo(150)
    assertThat(ltr["a"]).isEqualTo(Rect(0, 0, 30, 10))
    assertThat(ltr["b"]).isEqualTo(Rect(50, 0, 100, 10))
    assertThat(ltr["c"]).isEqualTo(Rect(100, 0, 140, 10))
    assertThat(rtl.width).isEqualTo(150)
    assertThat(rtl["a"]).isEqualTo(Rect(120, 0, 150, 10))
    assertThat(rtl["b"]).isEqualTo(Rect(50, 0, 100, 10))
    assertThat(rtl["c"]).isEqualTo(Rect(10, 0, 50, 10))
  }

  @Test
  fun `a child with a width of its own keeps it, from the start of its share`() {
    val layout = EqualSplitLayout(listOf(box("a", 60, 10), filler("b", 10), filler("c", 10)))

    val ltr = lithoTestRule.layOut(layout, Room.Exactly(99))
    val rtl = lithoTestRule.layOut(layout, Room.Exactly(99), rtl = true)

    assertThat(ltr["a"]).isEqualTo(Rect(0, 0, 60, 10))
    assertThat(ltr["b"]).isEqualTo(Rect(33, 0, 66, 10))
    assertThat(rtl["a"]).isEqualTo(Rect(39, 0, 99, 10))
  }

  @Test
  fun `one child takes the whole width`() {
    val laidOut = lithoTestRule.layOut(EqualSplitLayout(listOf(filler("a", 10))), Room.AtMost(100))

    assertThat(laidOut["a"]).isEqualTo(Rect(0, 0, 100, 10))
  }

  @Test
  fun `with no children, the layout is 0 x 0`() {
    val laidOut = lithoTestRule.layOut(EqualSplitLayout(emptyList()), Room.AtMost(100))

    assertThat(laidOut.width to laidOut.height).isEqualTo(0 to 0)
  }

  @Test
  fun `each child is measured once`() {
    val runs = List(3) { AtomicInteger() }

    lithoTestRule.layOutOnceAsRoot(
        EqualSplitLayout(List(3) { index -> CountingMeasures(filler("$index", 10), runs[index]) }),
        width = 100,
    )

    assertThat(runs.map { count -> count.get() }).containsExactly(1, 1, 1)
  }

  /** Each share is measured with no minimum height, so an exact height doesn't stretch it. */
  @Test
  fun `under an exact height each child keeps its own height`() {
    val laidOut =
        lithoTestRule.layOutAsRootExactly(
            EqualSplitLayout(listOf(wrapping("a", 10, 10))),
            width = 99,
            height = 50,
        )

    assertThat(laidOut["a"]).isEqualTo(Rect(0, 0, 99, 10))
  }
}
