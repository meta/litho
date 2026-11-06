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

import android.graphics.Rect
import com.facebook.litho.Component
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 30 x 20 px items, 10 px apart on a line and 5 px between lines: three items need 110 px, so 109
 * px holds two per line.
 */
@RunWith(LithoTestRunner::class)
class FlowRowLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private fun items(count: Int): List<Component> = List(count) { index -> box("i$index", 30, 20) }

  private fun flow(items: List<Component> = items(5)) =
      FlowRowLayout(items, itemSpacing = 10.px, lineSpacing = 5.px)

  @Test
  fun `items that fit exactly share a line`() {
    val laidOut = lithoTestRule.layOut(flow(items(3)), Room.AtMost(110))

    assertThat(laidOut["i2"]).isEqualTo(Rect(80, 0, 110, 20))
    assertThat(laidOut.width to laidOut.height).isEqualTo(110 to 20)
  }

  @Test
  fun `1 px too narrow, the last item starts a new line`() {
    val laidOut = lithoTestRule.layOut(flow(items(3)), Room.AtMost(109))

    assertThat(laidOut["i1"]).isEqualTo(Rect(40, 0, 70, 20))
    assertThat(laidOut["i2"]).isEqualTo(Rect(0, 25, 30, 45))
    assertThat(laidOut.width to laidOut.height).isEqualTo(70 to 45)
  }

  @Test
  fun `items fill as many lines as they need`() {
    val laidOut = lithoTestRule.layOut(flow(), Room.Exactly(109))

    assertThat(laidOut["i0"]).isEqualTo(Rect(0, 0, 30, 20))
    assertThat(laidOut["i3"]).isEqualTo(Rect(40, 25, 70, 45))
    assertThat(laidOut["i4"]).isEqualTo(Rect(0, 50, 30, 70))
    assertThat(laidOut.width to laidOut.height).isEqualTo(109 to 70)
  }

  @Test
  fun `right to left, each line starts at the right edge`() {
    val laidOut = lithoTestRule.layOut(flow(), Room.Exactly(109), rtl = true)

    assertThat(laidOut["i0"]).isEqualTo(Rect(79, 0, 109, 20))
    assertThat(laidOut["i1"]).isEqualTo(Rect(39, 0, 69, 20))
    assertThat(laidOut["i2"]).isEqualTo(Rect(79, 25, 109, 45))
    assertThat(laidOut["i4"]).isEqualTo(Rect(79, 50, 109, 70))
  }

  @Test
  fun `a line is as tall as its tallest item`() {
    val laidOut =
        lithoTestRule.layOut(
            flow(listOf(box("short", 30, 10), box("tall", 30, 40), box("next", 30, 20))),
            Room.AtMost(70),
        )

    assertThat(laidOut["short"]).isEqualTo(Rect(0, 0, 30, 10))
    assertThat(laidOut["next"]).isEqualTo(Rect(0, 45, 30, 65))
    assertThat(laidOut.height)
        .describedAs("the second line is only as tall as its item")
        .isEqualTo(65)
  }

  @Test
  fun `an item wider than the width gets a line of its own and extends past the end`() {
    val laidOut =
        lithoTestRule.layOut(
            flow(listOf(box("a", 30, 20), box("wide", 150, 20), box("b", 30, 20))),
            Room.Exactly(100),
        )

    assertThat(laidOut["a"]).isEqualTo(Rect(0, 0, 30, 20))
    assertThat(laidOut["wide"]).isEqualTo(Rect(0, 25, 150, 45))
    assertThat(laidOut["b"]).isEqualTo(Rect(0, 50, 30, 70))
  }

  @Test
  fun `right to left, an item wider than the width extends past the left edge`() {
    val laidOut =
        lithoTestRule.layOut(
            flow(listOf(box("a", 30, 20), box("wide", 150, 20), box("b", 30, 20))),
            Room.Exactly(100),
            rtl = true,
        )

    assertThat(laidOut["a"]).isEqualTo(Rect(70, 0, 100, 20))
    assertThat(laidOut["wide"]).isEqualTo(Rect(-50, 25, 100, 45))
    assertThat(laidOut["b"]).isEqualTo(Rect(70, 50, 100, 70))
  }

  @Test
  fun `with an unbounded width, every item is on one line`() {
    val laidOut = lithoTestRule.layOut(flow(), Room.Unbounded)

    assertThat(laidOut["i4"]).isEqualTo(Rect(160, 0, 190, 20))
    assertThat(laidOut.width to laidOut.height).isEqualTo(190 to 20)
  }

  @Test
  fun `with no items, the layout is 0 x 0`() {
    val laidOut = lithoTestRule.layOut(flow(emptyList()), Room.AtMost(100))

    assertThat(laidOut.width to laidOut.height).isEqualTo(0 to 0)
  }

  @Test
  fun `each item is measured once`() {
    val runs = List(5) { AtomicInteger() }

    lithoTestRule.layOutOnceAsRoot(
        FlowRowLayout(
            List(5) { index -> CountingMeasures(box("i$index", 30, 20), runs[index]) },
            itemSpacing = 10.px,
            lineSpacing = 5.px,
        ),
        width = 109,
    )

    assertThat(runs.map { count -> count.get() }).containsExactly(1, 1, 1, 1, 1)
  }

  /** Items are measured with no minimum height, so an exact height doesn't stretch them. */
  @Test
  fun `under an exact height each item keeps its own height`() {
    val laidOut = lithoTestRule.layOutAsRootExactly(flow(items(3)), width = 109, height = 100)

    assertThat(laidOut["i2"]).isEqualTo(Rect(0, 25, 30, 45))
  }

  @Test
  fun `under an at-most width, an item wider than it doesn't make the layout wider`() {
    fun withWide() = flow(listOf(box("a", 30, 20), box("wide", 150, 20), box("b", 30, 20)))

    val ltr = lithoTestRule.layOut(withWide(), Room.AtMost(100))
    val rtl = lithoTestRule.layOut(withWide(), Room.AtMost(100), rtl = true)

    assertThat(ltr.width).isEqualTo(100)
    assertThat(ltr["wide"]).isEqualTo(Rect(0, 25, 150, 45))
    assertThat(rtl["wide"]).isEqualTo(Rect(-50, 25, 100, 45))
  }
}
