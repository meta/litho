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
 * 40 px avatars, each overlapping the one before it by 12 px, so each starts 28 px after the one
 * before: four need 124 px and five need 152 px. The "+N" label takes an avatar's place.
 */
@RunWith(LithoTestRunner::class)
class FacepileLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private fun avatars(count: Int): List<Component> = List(count) { index -> box("a$index", 40, 40) }

  private fun facepile(avatars: List<Component> = avatars(5)) =
      FacepileLayout(avatars = avatars, avatarSize = 40.px, overlap = 12.px) { hiddenCount ->
        box("+$hiddenCount", 40, 40)
      }

  @Test
  fun `every avatar is shown, with no label, when they fit exactly`() {
    val laidOut = lithoTestRule.layOut(facepile(avatars(4)), Room.AtMost(124))

    assertThat(laidOut.names).containsExactly("a0", "a1", "a2", "a3")
    assertThat(laidOut["a1"]).isEqualTo(Rect(28, 0, 68, 40))
    assertThat(laidOut["a3"]).isEqualTo(Rect(84, 0, 124, 40))
    assertThat(laidOut.width to laidOut.height).isEqualTo(124 to 40)
  }

  /** Three avatars fit in 123 px, but the label for the fourth needs a place too. */
  @Test
  fun `1 px too narrow for every avatar, two are replaced by a count`() {
    val laidOut = lithoTestRule.layOut(facepile(avatars(4)), Room.AtMost(123))

    assertThat(laidOut.names).containsExactly("a0", "a1", "+2")
    assertThat(laidOut["+2"]).isEqualTo(Rect(56, 0, 96, 40))
    assertThat(laidOut.width).isEqualTo(96)
  }

  @Test
  fun `the label is shown after the avatars when it fits exactly`() {
    val laidOut = lithoTestRule.layOut(facepile(), Room.AtMost(124))

    assertThat(laidOut.names).containsExactly("a0", "a1", "a2", "+2")
    assertThat(laidOut["+2"]).isEqualTo(Rect(84, 0, 124, 40))
  }

  /** Three avatars fit in 123 px, and the label for the rest takes the third one's place. */
  @Test
  fun `1 px short of the label after three avatars, the third gives its place to the label`() {
    val laidOut = lithoTestRule.layOut(facepile(), Room.AtMost(123))

    assertThat(laidOut.names).containsExactly("a0", "a1", "+3")
    assertThat(laidOut["+3"]).isEqualTo(Rect(56, 0, 96, 40))
  }

  @Test
  fun `the label is measured at an avatar's size, whatever its own size`() {
    val laidOut =
        lithoTestRule.layOut(
            FacepileLayout(avatars = avatars(5), avatarSize = 40.px, overlap = 12.px) { hiddenCount
              ->
              wrapping("+$hiddenCount", 20, 20)
            },
            Room.AtMost(124),
        )

    assertThat(laidOut["+2"]).isEqualTo(Rect(84, 0, 124, 40))
  }

  @Test
  fun `each avatar is placed after the one before it, so it's drawn over it`() {
    val laidOut = lithoTestRule.layOut(facepile(), Room.AtMost(124))

    assertThat(laidOut.children.map { (_, bounds) -> bounds.left }).containsExactly(0, 28, 56, 84)
  }

  /**
   * In 123 px the row is 96 px wide; the layout fills the exact width, and right to left its row
   * starts at the right edge.
   */
  @Test
  fun `right to left, the avatars start at the right edge and the label follows to their left`() {
    val laidOut = lithoTestRule.layOut(facepile(), Room.Exactly(123), rtl = true)

    assertThat(laidOut.names).containsExactly("a0", "a1", "+3")
    assertThat(laidOut["a0"]).isEqualTo(Rect(83, 0, 123, 40))
    assertThat(laidOut["a1"]).isEqualTo(Rect(55, 0, 95, 40))
    assertThat(laidOut["+3"]).isEqualTo(Rect(27, 0, 67, 40))
  }

  @Test
  fun `narrower than one avatar, the label is shown alone, from the start edge`() {
    val ltr = lithoTestRule.layOut(facepile(), Room.Exactly(30))
    val rtl = lithoTestRule.layOut(facepile(), Room.Exactly(30), rtl = true)

    assertThat(ltr.names).containsExactly("+5")
    assertThat(ltr["+5"]).isEqualTo(Rect(0, 0, 40, 40))
    assertThat(rtl["+5"]).isEqualTo(Rect(-10, 0, 30, 40))
  }

  @Test
  fun `with an unbounded width, every avatar is shown`() {
    val laidOut = lithoTestRule.layOut(facepile(), Room.Unbounded)

    assertThat(laidOut.names).containsExactly("a0", "a1", "a2", "a3", "a4")
    assertThat(laidOut.width).isEqualTo(152)
  }

  @Test
  fun `with no avatars, the layout is 0 x 0`() {
    val laidOut = lithoTestRule.layOut(facepile(avatars = emptyList()), Room.AtMost(100))

    assertThat(laidOut.names).isEmpty()
    assertThat(laidOut.width to laidOut.height).isEqualTo(0 to 0)
  }

  @Test
  fun `one avatar that fits is shown alone, and one that doesn't is counted`() {
    val fits = lithoTestRule.layOut(facepile(avatars(1)), Room.AtMost(40))
    val doesNotFit = lithoTestRule.layOut(facepile(avatars(1)), Room.AtMost(39))

    assertThat(fits.names).containsExactly("a0")
    assertThat(doesNotFit.names).containsExactly("+1")
  }

  /** In 123 px two avatars and "+3" are shown: no other avatar or label is measured. */
  @Test
  fun `only the avatars and the label shown are measured, each once`() {
    val avatarRuns = List(5) { AtomicInteger() }
    val labelRuns = List(5) { AtomicInteger() }

    lithoTestRule.layOutOnceAsRoot(
        FacepileLayout(
            avatars =
                List(5) { index -> CountingMeasures(box("a$index", 40, 40), avatarRuns[index]) },
            avatarSize = 40.px,
            overlap = 12.px,
        ) { hiddenCount ->
          CountingMeasures(box("+$hiddenCount", 40, 40), labelRuns[hiddenCount - 1])
        },
        width = 123,
    )

    assertThat(avatarRuns.map { runs -> runs.get() }).containsExactly(1, 1, 0, 0, 0)
    assertThat(labelRuns.map { runs -> runs.get() }).containsExactly(0, 0, 1, 0, 0)
  }
}
