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
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A 64 px square content and a 21 x 11 px badge, odd so the halves round: the badge sticks out 11
 * px past the content's end and 6 px above it.
 */
@RunWith(LithoTestRunner::class)
class BadgeOverlayLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private fun badged() =
      BadgeOverlayLayout(content = box("content", 64, 64), badge = box("badge", 21, 11))

  @Test
  fun `the badge is centred on the content's top-end corner, and the layout grows to hold it`() {
    val laidOut = lithoTestRule.layOut(badged(), Room.AtMost(200))

    assertThat(laidOut.width to laidOut.height).isEqualTo(75 to 70)
    assertThat(laidOut["content"]).isEqualTo(Rect(0, 6, 64, 70))
    assertThat(laidOut["badge"]).isEqualTo(Rect(54, 0, 75, 11))
  }

  @Test
  fun `right to left, the badge is on the content's top-left corner`() {
    val laidOut = lithoTestRule.layOut(badged(), Room.AtMost(200), rtl = true)

    assertThat(laidOut["content"]).isEqualTo(Rect(11, 6, 75, 70))
    assertThat(laidOut["badge"]).isEqualTo(Rect(0, 0, 21, 11))
  }

  @Test
  fun `the badge is placed after the content, so it's drawn over it`() {
    val laidOut = lithoTestRule.layOut(badged(), Room.AtMost(200))

    assertThat(laidOut.names).containsExactly("content", "badge")
  }

  @Test
  fun `with an unbounded width, the layout matches a wide width`() {
    val laidOut = lithoTestRule.layOut(badged(), Room.Unbounded)

    assertThat(laidOut.width).isEqualTo(75)
    assertThat(laidOut["badge"]).isEqualTo(Rect(54, 0, 75, 11))
  }

  /**
   * In 50 px the content is given 39 and keeps its own 64, from its start edge. The badge follows
   * the content's `measuredWidth`, so it stays on the corner the content is drawn with.
   */
  @Test
  fun `the badge stays on the corner of content wider than the width it's given`() {
    val ltr = lithoTestRule.layOut(badged(), Room.Exactly(50))
    val rtl = lithoTestRule.layOut(badged(), Room.Exactly(50), rtl = true)

    assertThat(ltr.width).isEqualTo(50)
    assertThat(ltr["content"]).isEqualTo(Rect(0, 6, 64, 70))
    assertThat(ltr["badge"]).isEqualTo(Rect(54, 0, 75, 11))
    assertThat(rtl["content"]).isEqualTo(Rect(-14, 6, 50, 70))
    assertThat(rtl["badge"]).isEqualTo(Rect(-25, 0, -4, 11))
  }

  @Test
  fun `each child is measured once`() {
    val contentRuns = AtomicInteger()
    val badgeRuns = AtomicInteger()

    lithoTestRule.layOutOnceAsRoot(
        BadgeOverlayLayout(
            content = CountingMeasures(box("content", 64, 64), contentRuns),
            badge = CountingMeasures(box("badge", 21, 11), badgeRuns),
        ),
        width = 200,
    )

    assertThat(contentRuns.get() to badgeRuns.get()).isEqualTo(1 to 1)
  }

  @Test
  fun `under an at-most width, content wider than it doesn't make the layout wider`() {
    val ltr = lithoTestRule.layOut(badged(), Room.AtMost(50))
    val rtl = lithoTestRule.layOut(badged(), Room.AtMost(50), rtl = true)

    assertThat(ltr.width).isEqualTo(50)
    assertThat(ltr["content"]).isEqualTo(Rect(0, 6, 64, 70))
    assertThat(rtl["content"]).isEqualTo(Rect(-14, 6, 50, 70))
    assertThat(rtl["badge"]).isEqualTo(Rect(-25, 0, -4, 11))
  }
}
