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
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SolidColor
import com.facebook.rendercore.px
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Children 40, 60 and 20 px wide and 10 px high; an exact width of 200 px unless a test says. */
@RunWith(LithoTestRunner::class)
class AlignedColumnLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  /** A child named [name], [width] x [height] px, at [alignment], or with no alignment set. */
  private fun aligned(
      name: String,
      width: Int,
      alignment: ColumnAlignment?,
      height: Style = Style.height(10.px),
  ): Component {
    val style =
        (Style.width(width.px) + height).let { base ->
          if (alignment != null) base.columnAlignment(alignment) else base
        }
    return Named(name, SolidColor(color = Color.BLACK, style = style))
  }

  private fun column() = AlignedColumnLayout(
      listOf(
          aligned("start", 40, ColumnAlignment.START),
          aligned("center", 60, ColumnAlignment.CENTER),
          aligned("end", 20, ColumnAlignment.END),
      ),
  )

  @Test
  fun `under an exact width each child is placed where its alignment says, stacked from the top`() {
    val laidOut = lithoTestRule.layOut(column(), Room.Exactly(200))

    assertThat(laidOut.width to laidOut.height).isEqualTo(200 to 30)
    assertThat(laidOut["start"]).isEqualTo(Rect(0, 0, 40, 10))
    assertThat(laidOut["center"]).isEqualTo(Rect(70, 10, 130, 20))
    assertThat(laidOut["end"]).isEqualTo(Rect(180, 20, 200, 30))
  }

  @Test
  fun `right to left, start is the right edge and end is the left`() {
    val laidOut = lithoTestRule.layOut(column(), Room.Exactly(200), rtl = true)

    assertThat(laidOut["start"]).isEqualTo(Rect(160, 0, 200, 10))
    assertThat(laidOut["center"]).isEqualTo(Rect(70, 10, 130, 20))
    assertThat(laidOut["end"]).isEqualTo(Rect(0, 20, 20, 30))
  }

  @Test
  fun `under an at-most or unbounded width the layout is as wide as the widest child`() {
    for (room in listOf(Room.AtMost(200), Room.Unbounded)) {
      val ltr = lithoTestRule.layOut(column(), room)
      val rtl = lithoTestRule.layOut(column(), room, rtl = true)

      assertThat(ltr.width to ltr.height).describedAs("%s", room).isEqualTo(60 to 30)
      assertThat(ltr["start"]).describedAs("%s", room).isEqualTo(Rect(0, 0, 40, 10))
      assertThat(ltr["center"]).describedAs("%s", room).isEqualTo(Rect(0, 10, 60, 20))
      assertThat(ltr["end"]).describedAs("%s", room).isEqualTo(Rect(40, 20, 60, 30))
      assertThat(rtl["start"]).describedAs("%s, RTL", room).isEqualTo(Rect(20, 0, 60, 10))
      assertThat(rtl["end"]).describedAs("%s, RTL", room).isEqualTo(Rect(0, 20, 20, 30))
    }
  }

  @Test
  fun `a child that sets no alignment is at the start edge`() {
    fun plain() = AlignedColumnLayout(listOf(aligned("plain", 40, null)))

    assertThat(lithoTestRule.layOut(plain(), Room.Exactly(200))["plain"])
        .isEqualTo(Rect(0, 0, 40, 10))
    assertThat(lithoTestRule.layOut(plain(), Room.Exactly(200), rtl = true)["plain"])
        .isEqualTo(Rect(160, 0, 200, 10))
  }

  /** As with `align-self` in a Yoga `Column`: start, centre and end each keep their place. */
  @Test
  fun `a child wider than the layout keeps its place, centred past both edges or else past the opposite one`() {
    fun wide() = AlignedColumnLayout(
        listOf(
            aligned("start", 300, ColumnAlignment.START),
            aligned("center", 300, ColumnAlignment.CENTER),
            aligned("end", 300, ColumnAlignment.END),
        ),
    )

    val ltr = lithoTestRule.layOut(wide(), Room.Exactly(200))
    val rtl = lithoTestRule.layOut(wide(), Room.Exactly(200), rtl = true)

    assertThat(ltr["start"]).isEqualTo(Rect(0, 0, 300, 10))
    assertThat(ltr["center"]).isEqualTo(Rect(-50, 10, 250, 20))
    assertThat(ltr["end"]).isEqualTo(Rect(-100, 20, 200, 30))
    assertThat(rtl["start"]).isEqualTo(Rect(-100, 0, 200, 10))
    assertThat(rtl["center"]).isEqualTo(Rect(-50, 10, 250, 20))
    assertThat(rtl["end"]).isEqualTo(Rect(0, 20, 300, 30))
  }

  @Test
  fun `an odd leftover pixel goes to the end side when centring`() {
    fun centred() = AlignedColumnLayout(listOf(aligned("center", 60, ColumnAlignment.CENTER)))

    assertThat(lithoTestRule.layOut(centred(), Room.Exactly(201))["center"])
        .isEqualTo(Rect(70, 0, 130, 10))
    assertThat(lithoTestRule.layOut(centred(), Room.Exactly(201), rtl = true)["center"])
        .isEqualTo(Rect(71, 0, 131, 10))
  }

  /** A percentage height is of the height the layout is given, as in a `Column` of that height. */
  @Test
  fun `each child is measured within the height the layout is given`() {
    val laidOut =
        lithoTestRule.layOutAsRootExactly(
            AlignedColumnLayout(
                listOf(aligned("half", 40, ColumnAlignment.END, height = Style.heightPercent(50f))),
            ),
            width = 200,
            height = 100,
        )

    assertThat(laidOut["half"]).isEqualTo(Rect(160, 0, 200, 50))
  }

  @Test
  fun `one child is placed at its alignment, and with no children the layout is 0 high`() {
    val one =
        lithoTestRule.layOut(
            AlignedColumnLayout(listOf(aligned("end", 20, ColumnAlignment.END))),
            Room.Exactly(200),
        )
    val noneExact = lithoTestRule.layOut(AlignedColumnLayout(emptyList()), Room.Exactly(200))
    val noneAtMost = lithoTestRule.layOut(AlignedColumnLayout(emptyList()), Room.AtMost(200))

    assertThat(one["end"]).isEqualTo(Rect(180, 0, 200, 10))
    assertThat(noneExact.width to noneExact.height).isEqualTo(200 to 0)
    assertThat(noneAtMost.width to noneAtMost.height).isEqualTo(0 to 0)
  }

  @Test
  fun `each child is measured once`() {
    val runs = List(3) { AtomicInteger() }

    lithoTestRule.layOutOnceAsRoot(
        AlignedColumnLayout(
            List(3) { index -> CountingMeasures(aligned("$index", 20, null), runs[index]) },
        ),
        width = 200,
    )

    assertThat(runs.map { count -> count.get() }).containsExactly(1, 1, 1)
  }

  /** Each child is measured with no minimum, so an exact size doesn't stretch it. */
  @Test
  fun `under an exact size each child keeps its own size`() {
    fun fill() = AlignedColumnLayout(
        listOf(
            Named(
                "fill",
                Column(style = Style.columnAlignment(ColumnAlignment.END)) {
                  child(SolidColor(color = Color.BLACK, style = Style.width(40.px).height(10.px)))
                },
            ),
        ),
    )

    val ltr = lithoTestRule.layOut(fill(), Room.Exactly(200))
    val rtl = lithoTestRule.layOut(fill(), Room.Exactly(200), rtl = true)
    val tall = lithoTestRule.layOutAsRootExactly(column(), width = 200, height = 100)

    assertThat(ltr["fill"]).isEqualTo(Rect(160, 0, 200, 10))
    assertThat(rtl["fill"]).isEqualTo(Rect(0, 0, 40, 10))
    assertThat(tall["center"]).isEqualTo(Rect(70, 10, 130, 20))
    assertThat(tall["end"]).isEqualTo(Rect(180, 20, 200, 30))
  }

  @Test
  fun `under an at-most width, a child wider than it doesn't make the layout wider`() {
    fun wide() = AlignedColumnLayout(
        listOf(
            aligned("end", 300, ColumnAlignment.END),
            aligned("start", 20, ColumnAlignment.START),
        ),
    )

    val ltr = lithoTestRule.layOut(wide(), Room.AtMost(200))
    val rtl = lithoTestRule.layOut(wide(), Room.AtMost(200), rtl = true)

    assertThat(ltr.width).isEqualTo(200)
    assertThat(ltr["end"]).isEqualTo(Rect(-100, 0, 200, 10))
    assertThat(rtl["end"]).isEqualTo(Rect(0, 0, 300, 10))
    assertThat(rtl["start"]).isEqualTo(Rect(180, 10, 200, 20))
  }
}
