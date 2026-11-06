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
import com.facebook.litho.SizeSpec
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Labelled buttons 120 x 20 px, and icon-only buttons 50 x 10 px. */
@RunWith(LithoTestRunner::class)
class ActionBarLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private fun actionBar() =
      ActionBarLayout(labelled = box("labelled", 120, 20), iconOnly = box("iconOnly", 50, 10))

  @Test
  fun `the labelled buttons are shown when they fit exactly`() {
    val ltr = lithoTestRule.layOut(actionBar(), Room.Exactly(120))
    val rtl = lithoTestRule.layOut(actionBar(), Room.Exactly(120), rtl = true)

    assertThat(ltr.names).containsExactly("labelled")
    assertThat(ltr["labelled"]).isEqualTo(Rect(0, 0, 120, 20))
    assertThat(rtl["labelled"]).isEqualTo(Rect(0, 0, 120, 20))
  }

  @Test
  fun `the icons are shown when the labelled buttons are 1 px too wide`() {
    val ltr = lithoTestRule.layOut(actionBar(), Room.Exactly(119))
    val rtl = lithoTestRule.layOut(actionBar(), Room.Exactly(119), rtl = true)

    assertThat(ltr.names).containsExactly("iconOnly")
    assertThat(ltr["iconOnly"]).isEqualTo(Rect(0, 0, 50, 10))
    assertThat(rtl.names).containsExactly("iconOnly")
    assertThat(rtl["iconOnly"]).isEqualTo(Rect(69, 0, 119, 10))
  }

  @Test
  fun `under an at-most width the layout fills it, as tall as the buttons it shows`() {
    val fits = lithoTestRule.layOut(actionBar(), Room.AtMost(120))
    val doesNotFit = lithoTestRule.layOut(actionBar(), Room.AtMost(119))

    assertThat(fits.width to fits.height).isEqualTo(120 to 20)
    assertThat(doesNotFit.width to doesNotFit.height).isEqualTo(119 to 10)
  }

  @Test
  fun `in a wide width the labelled buttons start at the start edge`() {
    for (room in listOf(Room.Exactly(1000), Room.AtMost(1000))) {
      val ltr = lithoTestRule.layOut(actionBar(), room)
      val rtl = lithoTestRule.layOut(actionBar(), room, rtl = true)

      assertThat(ltr.width).describedAs("%s", room).isEqualTo(1000)
      assertThat(ltr["labelled"]).describedAs("%s", room).isEqualTo(Rect(0, 0, 120, 20))
      assertThat(rtl["labelled"]).describedAs("%s", room).isEqualTo(Rect(880, 0, 1000, 20))
    }
  }

  @Test
  fun `with an unbounded width, the labelled buttons are shown at their own width`() {
    val ltr = lithoTestRule.layOut(actionBar(), Room.Unbounded)
    val rtl = lithoTestRule.layOut(actionBar(), Room.Unbounded, rtl = true)

    assertThat(ltr.names).containsExactly("labelled")
    assertThat(ltr.width).isEqualTo(120)
    assertThat(rtl["labelled"]).isEqualTo(Rect(0, 0, 120, 20))
  }

  @Test
  fun `the icons are measured only when the labelled buttons don't fit`() {
    val labelledRuns = AtomicInteger()
    val iconOnlyRuns = AtomicInteger()
    fun counted() = ActionBarLayout(
        labelled = CountingMeasures(box("labelled", 120, 20), labelledRuns),
        iconOnly = CountingMeasures(box("iconOnly", 50, 10), iconOnlyRuns),
    )

    lithoTestRule.layOutOnceAsRoot(counted(), width = 1000)
    assertThat(labelledRuns.get() to iconOnlyRuns.get()).isEqualTo(1 to 0)

    labelledRuns.set(0)
    lithoTestRule.layOutOnceAsRoot(counted(), width = 100)
    assertThat(labelledRuns.get() to iconOnlyRuns.get()).isEqualTo(1 to 1)
  }

  /**
   * Narrowing the width chooses again, and widening it chooses the labelled buttons again: the
   * choice is made on every layout, never kept from an earlier width.
   */
  @Test
  fun `the buttons are chosen again when the width changes`() {
    val testView = lithoTestRule.renderInRoom(actionBar(), Room.Exactly(1000))
    assertThat(collect(testView).names).containsExactly("labelled")

    resize(testView, width = 100)
    assertThat(collect(testView).names).containsExactly("iconOnly")

    resize(testView, width = 1000)
    assertThat(collect(testView).names).containsExactly("labelled")
  }

  /** Measured with no width limit, the labels stay on one line, so their width is their own. */
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `labels too wide for the width are replaced, never wrapped`() {
    fun labels() = ActionBarLayout(
        labelled = lithoTestRule.text("labelled", "Like  Comment  Share"),
        iconOnly = lithoTestRule.text("iconOnly", "+"),
    )

    val wide = lithoTestRule.layOut(labels(), Room.Exactly(2000))
    val narrow = lithoTestRule.layOut(labels(), Room.Exactly(200))

    assertThat(wide.names).containsExactly("labelled")
    assertThat(wide["labelled"].width()).isGreaterThan(200)
    assertThat(narrow.names).describedAs("not wrapped to fit 200 px").containsExactly("iconOnly")
  }

  private fun resize(testView: TestLithoView, width: Int) {
    testView.setSizeSpecs(
        SizeSpec.makeSizeSpec(width, SizeSpec.EXACTLY),
        SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
    )
    testView.measure().layout()
  }

  /** Each variant is measured with no minimum, so an exact width doesn't stretch it. */
  @Test
  fun `under an exact width the buttons keep their own width`() {
    fun wrapped() = ActionBarLayout(
        labelled = wrapping("labelled", 120, 20),
        iconOnly = wrapping("iconOnly", 50, 10),
    )

    val fits = lithoTestRule.layOut(wrapped(), Room.Exactly(1000))
    val fitsRtl = lithoTestRule.layOut(wrapped(), Room.Exactly(1000), rtl = true)
    val doesNotFit = lithoTestRule.layOut(wrapped(), Room.Exactly(119))

    assertThat(fits["labelled"]).isEqualTo(Rect(0, 0, 120, 20))
    assertThat(fitsRtl["labelled"]).isEqualTo(Rect(880, 0, 1000, 20))
    assertThat(doesNotFit["iconOnly"]).isEqualTo(Rect(0, 0, 50, 10))
  }
}
