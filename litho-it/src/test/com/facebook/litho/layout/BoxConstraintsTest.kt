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

package com.facebook.litho.layout

import com.facebook.litho.layout.BoxConstraintsCases.summary
import com.facebook.litho.layout.BoxConstraintsCases.text
import com.facebook.rendercore.SizeConstraints
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

private const val INF = SizeConstraints.Infinity

private fun px(value: Int): BoxLength = BoxLength.Px(value.toFloat())

private fun pct(value: Float): BoxLength = BoxLength.Percent(value)

private fun atMost(width: Int, height: Int = INF) = SizeConstraints(0, width, 0, height)

private fun exact(width: Int, height: Int) = SizeConstraints.exact(width, height)

private fun props(block: BoxLayoutProps.() -> Unit = {}) = BoxLayoutProps().apply(block)

private fun resolve(constraints: SizeConstraints, props: BoxLayoutProps, isRtl: Boolean = false) =
    resolveBoxConstraints(constraints, props, isRtl)

private fun resolveRoot(constraints: SizeConstraints, props: BoxLayoutProps) =
    resolveRootBox(constraints, props, isRtl = false)

/**
 * The rules a container applies to a child's own box props, within the constraints it asks, and the
 * rule a root `Layout` resolves its box by.
 */
class BoxConstraintsTest {

  @Test
  fun `the child's own width wins even over exact constraints`() {
    val b = resolve(exact(200, 50), props { width = px(80) })

    assertThat(b.borderBox.minWidth).isEqualTo(80)
    assertThat(b.borderBox.maxWidth).isEqualTo(80)
  }

  @Test
  fun `an own width wins over a bound`() {
    assertThat(resolve(atMost(300), props { width = px(80) }).borderBox.maxWidth).isEqualTo(80)
    val over = resolve(atMost(60), props { width = px(80) })
    assertThat(over.borderBox.maxWidth).isEqualTo(80)
  }

  @Test
  fun `a percent width is taken of the constraints, and ignored without a bound`() {
    assertThat(resolve(atMost(300), props { width = pct(50f) }).borderBox.maxWidth).isEqualTo(150)
    assertThat(
        resolve(SizeConstraints(), props { width = pct(50f) }).borderBox.hasBoundedWidth,
    )
        .isFalse()
  }

  @Test
  fun `margins come out of the constraints and are reported`() {
    val b = resolve(exact(200, 100), props { margin[BoxEdge.ALL] = BoxLength.Px(10f) })

    assertThat(b.borderBox.maxWidth).isEqualTo(180)
    assertThat(b.borderBox.maxHeight).isEqualTo(80)
    assertThat(b.margins.horizontal).isEqualTo(20)
  }

  @Test
  fun `a percent margin is taken of the constraints' width on both axes`() {
    val b = resolve(atMost(200, 1000), props { margin[BoxEdge.VERTICAL] = BoxLength.Percent(10f) })

    assertThat(b.margins.top).isEqualTo(20)
    assertThat(b.margins.bottom).isEqualTo(20)
  }

  @Test
  fun `a percent margin is 0 when the width is unbounded`() {
    val b = resolve(SizeConstraints(), props { margin[BoxEdge.ALL] = BoxLength.Percent(10f) })

    assertThat(b.margins).isSameAs(IntEdges.None)
  }

  @Test
  fun `a negative margin makes the border box's constraints larger`() {
    val b = resolve(exact(100, 100), props { margin[BoxEdge.LEFT] = BoxLength.Px(-10f) })

    assertThat(b.borderBox.maxWidth).isEqualTo(110)
  }

  @Test
  fun `start and end resolve by direction, as Yoga does`() {
    val declared = props {
      margin[BoxEdge.START] = BoxLength.Px(4f)
      margin[BoxEdge.LEFT] = BoxLength.Px(9f)
    }

    assertThat(resolve(atMost(100), declared).margins.left).isEqualTo(4)
    assertThat(resolve(atMost(100), declared, isRtl = true).margins.left).isEqualTo(9)
    assertThat(resolve(atMost(100), declared, isRtl = true).margins.right).isEqualTo(4)
  }

  @Test
  fun `an own max narrows a bound and an own min raises it`() {
    val b = resolve(
        atMost(300),
        props {
          minWidth = px(40)
          maxWidth = px(120)
        },
    )

    assertThat(b.borderBox.minWidth).isEqualTo(40)
    assertThat(b.borderBox.maxWidth).isEqualTo(120)
  }

  @Test
  fun `an own min wins over the constraints`() {
    val b = resolve(atMost(100), props { minWidth = px(150) })

    assertThat(b.borderBox.minWidth).isEqualTo(150)
    assertThat(b.borderBox.maxWidth).isEqualTo(150)
  }

  @Test
  fun `an own min beats an own max, as in CSS`() {
    val b = resolve(
        atMost(300),
        props {
          minWidth = px(80)
          maxWidth = px(50)
        },
    )

    assertThat(b.borderBox.minWidth).isEqualTo(80)
    assertThat(b.borderBox.maxWidth).isEqualTo(80)
  }

  @Test
  fun `an own min raises exact constraints and an own max lowers them, the min over the max`() {
    val both = resolve(
        exact(200, 50),
        props {
          minWidth = px(250)
          maxWidth = px(100)
        },
    )
    assertThat(both.borderBox.minWidth).isEqualTo(250)
    assertThat(both.borderBox.maxWidth).isEqualTo(250)

    // The own max narrows even an exact question, as in a Column that stretches its children.
    val max = resolve(exact(200, 50), props { maxWidth = px(100) })
    assertThat(max.borderBox.minWidth).isEqualTo(100)
    assertThat(max.borderBox.maxWidth).isEqualTo(100)

    // An own min and max below the exact width: the max lowers it, the min doesn't raise it back.
    assertThat(
        resolve(
            exact(200, 50),
            props {
              minWidth = px(50)
              maxWidth = px(100)
            },
        )
            .borderBox,
    )
        .isEqualTo(exact(100, 50))

    // An own min below the exact width leaves it, as in a Column that stretches its children.
    val min = resolve(exact(200, 50), props { minWidth = px(120) })
    assertThat(min.borderBox.minWidth).isEqualTo(200)
    assertThat(min.borderBox.maxWidth).isEqualTo(200)

    // So declaring a min of 0 is the same as leaving it unset.
    val zero = resolve(exact(200, 50), props { minWidth = px(0) })
    assertThat(zero.borderBox.minWidth).isEqualTo(200)
    assertThat(zero.borderBox.maxWidth).isEqualTo(200)
  }

  @Test
  fun `a range far past any screen stays one constraints can encode`() {
    // A range with a minimum above 0 encodes a minimum up to 8190 and a maximum up to 262142.
    val bigMin = resolve(atMost(20_000, 50), props { minWidth = px(9_000) })
    assertThat(bigMin.borderBox.minWidth).describedAs("fixed at its min").isEqualTo(9_000)
    assertThat(bigMin.borderBox.maxWidth).isEqualTo(9_000)

    val bigMax = resolve(atMost(300_000), props { minWidth = px(100) })
    assertThat(bigMax.borderBox.minWidth).isEqualTo(100)
    assertThat(bigMax.borderBox.maxWidth).describedAs("cut to 262142").isEqualTo(262_142)
  }

  @Test
  fun `a percentage size is of the room and isn't kept in it, as in a Column`() {
    // Within an exact room it is the percentage, as for a child a Column stretches.
    val exactRoom = resolve(exact(200, 50), props { width = pct(50f) })
    assertThat(exactRoom.borderBox.maxWidth).isEqualTo(100)
    assertThat(exactRoom.borderBox.minWidth).isEqualTo(100)
    // Above 100% it is more than the room.
    assertThat(resolve(atMost(300), props { width = pct(150f) }).borderBox.maxWidth).isEqualTo(450)
    // Taken of the room before margins, and not kept inside them.
    val margined = resolve(
        atMost(300),
        props {
          width = pct(100f)
          margin[BoxEdge.HORIZONTAL] = BoxLength.Px(10f)
        },
    )
    assertThat(margined.borderBox.maxWidth).isEqualTo(300)
  }

  @Test
  fun `an aspect ratio derives the open axis from the fixed one`() {
    val b = resolve(
        SizeConstraints(0, 200, 0, 1000),
        props {
          width = px(120)
          aspectRatio = 2f
        },
    )

    assertThat(b.borderBox.maxHeight).isEqualTo(60)
    assertThat(b.borderBox.minHeight).isEqualTo(60)
  }

  @Test
  fun `a derived axis is limited only by its own min and max`() {
    val b = resolve(
        SizeConstraints(0, 200, 0, 40),
        props {
          width = px(120)
          aspectRatio = 2f
        },
    )
    assertThat(b.borderBox.maxHeight).describedAs("past the 40 bound").isEqualTo(60)

    val capped = resolve(
        SizeConstraints(0, 200, 0, 40),
        props {
          width = px(120)
          maxHeight = px(50)
          aspectRatio = 2f
        },
    )
    assertThat(capped.borderBox.maxHeight).isEqualTo(50)
  }

  @Test
  fun `a percent padding is taken of the constraints' width`() {
    val b = resolve(atMost(200), props { padding[BoxEdge.ALL] = BoxLength.Percent(5f) })

    assertThat(b.padding.left).isEqualTo(10)
    assertThat(b.padding.top).isEqualTo(10)
    assertThat(b.borderBox.minWidth).isEqualTo(20)
  }

  @Test
  fun `padding that does not fit answers the smallest box`() {
    val b = resolve(exact(10, 10), props { padding[BoxEdge.ALL] = BoxLength.Px(8f) })

    assertThat(b.borderBox.maxWidth).isEqualTo(16)
  }

  @Test
  fun `margins that do not fit leave the border box no room`() {
    val b = resolve(exact(10, 10), props { margin[BoxEdge.LEFT] = BoxLength.Px(20f) })

    assertThat(b.borderBox.maxWidth).isEqualTo(0)
  }

  @Test
  fun `a child with no size of its own stays inside the constraints when its edges fit`() {
    val constraintsToTry = buildList {
      for (w in listOf(0, 40, 200)) {
        for (h in listOf(0, 40, 200)) {
          add(exact(w, h))
          add(atMost(w, h))
          add(SizeConstraints(w / 2, w, 0, h))
        }
      }
      add(SizeConstraints())
      add(atMost(100))
    }
    val edges = listOf(0f to false, 12f to false, 10f to true)
    var checked = 0
    for (q in constraintsToTry) {
      for ((edge, isPercent) in edges) {
        val length = if (isPercent) BoxLength.Percent(edge) else BoxLength.Px(edge)
        val b = resolve(
            q,
            props {
              margin[BoxEdge.ALL] = length
              padding[BoxEdge.ALL] = length
            },
        )
        val edgesWidth = b.margins.horizontal + b.padding.horizontal + b.border.horizontal
        val edgesHeight = b.margins.vertical + b.padding.vertical + b.border.vertical
        if (
            (q.hasBoundedWidth && edgesWidth > q.maxWidth) ||
                (q.hasBoundedHeight && edgesHeight > q.maxHeight)
        ) {
          continue
        }
        checked++
        assertThat(b.borderBox.minWidth + b.margins.horizontal)
            .describedAs("min width in $q")
            .isGreaterThanOrEqualTo(q.minWidth)
        assertThat(b.borderBox.minHeight + b.margins.vertical).isGreaterThanOrEqualTo(q.minHeight)
        if (q.hasBoundedWidth) {
          assertThat(b.borderBox.maxWidth + b.margins.horizontal)
              .describedAs("max width in $q")
              .isLessThanOrEqualTo(q.maxWidth)
        }
        if (q.hasBoundedHeight) {
          assertThat(b.borderBox.maxHeight + b.margins.vertical)
              .describedAs("max height in $q")
              .isLessThanOrEqualTo(q.maxHeight)
        }
        assertThat(b.borderBox.minWidth).isLessThanOrEqualTo(b.borderBox.maxWidth)
        assertThat(b.borderBox.minHeight).isLessThanOrEqualTo(b.borderBox.maxHeight)
      }
    }
    assertThat(checked).isGreaterThan(40)
  }

  @Test
  fun `an own height fixes the height, and a percentage height fills its share`() {
    val fixed = resolve(SizeConstraints(0, 300, 0, 300), props { height = px(80) })
    assertThat(fixed.borderBox.minHeight).isEqualTo(80)
    assertThat(fixed.borderBox.maxHeight).isEqualTo(80)

    val share = resolve(SizeConstraints(0, 300, 0, 200), props { height = pct(50f) })
    assertThat(share.borderBox.minHeight).isEqualTo(100)
    assertThat(share.borderBox.maxHeight).isEqualTo(100)
  }

  @Test
  fun `an own min and an own max replace the bounds of the height too`() {
    val both = resolve(
        SizeConstraints(0, 300, 0, 300),
        props {
          minHeight = px(40)
          maxHeight = px(120)
        },
    )
    assertThat(both.borderBox.minHeight).isEqualTo(40)
    assertThat(both.borderBox.maxHeight).isEqualTo(120)

    val max = resolve(exact(50, 200), props { maxHeight = px(100) })
    assertThat(max.borderBox.minHeight).isEqualTo(100)
    assertThat(max.borderBox.maxHeight).isEqualTo(100)
  }

  @Test
  fun `an own max only narrows the room, as maxWidth does in a Column`() {
    val above = resolve(atMost(250), props { maxWidth = px(280) })
    assertThat(above.borderBox.maxWidth).isEqualTo(250)

    val aboveExact = resolve(exact(200, 50), props { maxWidth = px(280) })
    assertThat(aboveExact.borderBox.minWidth).isEqualTo(200)
    assertThat(aboveExact.borderBox.maxWidth).isEqualTo(200)

    val margined = resolve(
        atMost(300),
        props {
          maxWidth = pct(100f)
          margin[BoxEdge.HORIZONTAL] = BoxLength.Px(20f)
        },
    )
    assertThat(margined.borderBox.maxWidth).describedAs("inside the margins").isEqualTo(260)
  }

  @Test
  fun `an own size stays within its own min and max, the min over the max`() {
    fun box(block: BoxLayoutProps.() -> Unit) = resolve(atMost(300), props(block)).borderBox

    assertThat(
        box {
          width = px(200)
          maxWidth = px(120)
        }
            .maxWidth,
    )
        .isEqualTo(120)
    assertThat(
        box {
          width = px(50)
          minWidth = px(80)
        }
            .minWidth,
    )
        .isEqualTo(80)
    val conflicting = box {
      width = px(100)
      minWidth = px(80)
      maxWidth = px(50)
    }
    assertThat(conflicting.minWidth).isEqualTo(80)
    assertThat(conflicting.maxWidth).isEqualTo(80)
  }

  @Test
  fun `an aspect ratio derives the width from a fixed height`() {
    val b = resolve(
        SizeConstraints(0, 1000, 0, 200),
        props {
          height = px(60)
          aspectRatio = 2f
        },
    )

    assertThat(b.borderBox.minWidth).isEqualTo(120)
    assertThat(b.borderBox.maxWidth).isEqualTo(120)
  }

  @Test
  fun `a derived axis is raised to its own min`() {
    val b = resolve(
        SizeConstraints(0, 1000, 0, 1000),
        props {
          width = px(120)
          aspectRatio = 2f
          minHeight = px(80)
        },
    )

    assertThat(b.borderBox.minHeight).isEqualTo(80)
    assertThat(b.borderBox.maxHeight).isEqualTo(80)
  }

  @Test
  fun `an aspect ratio counts only with one axis fixed, and only when finite`() {
    val bothFixed = resolve(
        SizeConstraints(0, 1000, 0, 1000),
        props {
          width = px(120)
          height = px(30)
          aspectRatio = 2f
        },
    )
    assertThat(bothFixed.borderBox.maxHeight).isEqualTo(30)

    val infinite =
        resolve(SizeConstraints.fixedWidth(200), props { aspectRatio = Float.POSITIVE_INFINITY })
    assertThat(infinite.borderBox.minHeight).isEqualTo(0)
    assertThat(infinite.borderBox.hasBoundedHeight).isFalse()
    val fromHeight = resolve(
        atMost(300),
        props {
          height = px(50)
          aspectRatio = Float.POSITIVE_INFINITY
        },
    )
    assertThat(fromHeight.borderBox.minWidth).isEqualTo(0)
    assertThat(fromHeight.borderBox.maxWidth).isEqualTo(300)
  }

  @Test
  fun `a percentage min, max or size has no effect when its axis is unbounded`() {
    val max = resolve(SizeConstraints(), props { maxWidth = pct(50f) })
    assertThat(max.borderBox.hasBoundedWidth).isFalse()

    val height = resolve(atMost(300), props { height = pct(50f) })
    assertThat(height.borderBox.minHeight).isEqualTo(0)
    assertThat(height.borderBox.hasBoundedHeight).isFalse()
  }

  @Test
  fun `a percentage size past the room is kept, whatever negative margins do to the range`() {
    val b = resolve(
        atMost(300),
        props {
          width = pct(150f)
          margin[BoxEdge.HORIZONTAL] = BoxLength.Px(-20f)
        },
    )

    assertThat(b.borderBox.minWidth).isEqualTo(450)
    assertThat(b.borderBox.maxWidth).isEqualTo(450)
  }

  @Test
  fun `padding and border set the smallest the border box can be, after the aspect ratio`() {
    val bordered = resolve(exact(4, 4), props { border[BoxEdge.HORIZONTAL] = BoxLength.Px(5f) })
    assertThat(bordered.borderBox.minWidth).isEqualTo(10)

    val padded = resolve(SizeConstraints(), props { padding[BoxEdge.VERTICAL] = BoxLength.Px(8f) })
    assertThat(padded.borderBox.minHeight).isEqualTo(16)
    assertThat(padded.borderBox.hasBoundedHeight).isFalse()

    val derived = resolve(
        SizeConstraints(0, 1000, 0, 1000),
        props {
          width = px(120)
          aspectRatio = 2f
          padding[BoxEdge.VERTICAL] = BoxLength.Px(40f)
        },
    )
    assertThat(derived.borderBox.minHeight)
        .describedAs("60 from the ratio, raised to the padding")
        .isEqualTo(80)
  }

  @Test
  fun `a negative margin raises both bounds of the room`() {
    val b = resolve(atMost(200), props { margin[BoxEdge.HORIZONTAL] = BoxLength.Px(-20f) })

    assertThat(b.borderBox.minWidth).isEqualTo(40)
    assertThat(b.borderBox.maxWidth).isEqualTo(240)
  }

  @Test
  fun `the height stays encodable too, and no size is past what an axis stores`() {
    val bigMin = resolve(atMost(50, 20_000), props { minHeight = px(9_000) })
    assertThat(bigMin.borderBox.minHeight).isEqualTo(9_000)
    assertThat(bigMin.borderBox.maxHeight).isEqualTo(9_000)

    val wide = resolve(atMost(1080), props { width = px(1_200_000_000) })
    assertThat(wide.borderBox.maxWidth).isEqualTo(1_073_741_822)
    val loose = resolve(SizeConstraints(), props { maxWidth = BoxLength.Px(1.5e9f) })
    assertThat(loose.borderBox.maxWidth).isEqualTo(1_073_741_822)
  }

  @Test
  fun `a size or an edge that is not a finite number counts as unset, as in Yoga`() {
    val nan = resolve(atMost(300), props { width = BoxLength.Px(Float.NaN) })
    assertThat(nan.borderBox.minWidth).isEqualTo(0)
    assertThat(nan.borderBox.maxWidth).isEqualTo(300)

    val infiniteMin =
        resolve(atMost(1080), props { minWidth = BoxLength.Px(Float.POSITIVE_INFINITY) })
    assertThat(infiniteMin.borderBox.minWidth).isEqualTo(0)
    assertThat(infiniteMin.borderBox.maxWidth).isEqualTo(1080)

    val infiniteShare =
        resolve(atMost(300), props { width = BoxLength.Percent(Float.POSITIVE_INFINITY) })
    assertThat(infiniteShare.borderBox.minWidth).isEqualTo(0)

    val nanMax = resolve(atMost(300), props { maxWidth = BoxLength.Percent(Float.NaN) })
    assertThat(nanMax.borderBox.maxWidth).isEqualTo(300)

    val edge = resolve(
        atMost(300),
        props {
          margin[BoxEdge.ALL] = BoxLength.Px(10f)
          margin[BoxEdge.LEFT] = BoxLength.Px(Float.NaN)
        },
    )
    assertThat(edge.margins.left).describedAs("the edges behind it apply").isEqualTo(10)
  }

  // ───────────────────────── properties every child meets, over generated children and constraints

  @Test
  fun `RTL resolves as LTR mirrored, with left and right declarations swapped`() {
    forEachCase { constraints, declared ->
      val rtl = resolve(constraints, declared.build(), isRtl = true)
      val ltr = resolve(constraints, declared.leftRightSwapped().build())

      assertThat(summary(rtl))
          .describedAs("%s in %s", declared, constraints)
          .isEqualTo(summary(ltr, mirrored = true))
    }
  }

  @Test
  fun `declaring a prop at its default changes nothing`() {
    forEachCase { constraints, declared ->
      val unset = summary(resolve(constraints, declared.build()))
      for (variant in declared.withDefaults()) {
        assertThat(summary(resolve(constraints, variant.build())))
            .describedAs("%s in %s", variant, constraints)
            .isEqualTo(unset)
      }
    }
  }

  @Test
  fun `exact constraints are met by a child with no size of its own whose edges fit`() {
    forEachCase { constraints, generated ->
      val declared =
          generated.copy(
              width = BoxLength.Unset,
              height = BoxLength.Unset,
              minWidth = BoxLength.Unset,
              maxWidth = BoxLength.Unset,
              minHeight = BoxLength.Unset,
              maxHeight = BoxLength.Unset,
              aspectRatio = Float.NaN,
          )
      val b = resolve(constraints, declared.build())
      val edgesWidth = b.margins.horizontal + b.padding.horizontal + b.border.horizontal
      val edgesHeight = b.margins.vertical + b.padding.vertical + b.border.vertical
      if (constraints.hasExactWidth && edgesWidth <= constraints.maxWidth) {
        assertThat(b.borderBox.minWidth)
            .describedAs("%s in %s", declared, constraints)
            .isEqualTo(b.borderBox.maxWidth)
            .isEqualTo(constraints.maxWidth - b.margins.horizontal)
      }
      if (constraints.hasExactHeight && edgesHeight <= constraints.maxHeight) {
        assertThat(b.borderBox.minHeight)
            .describedAs("%s in %s", declared, constraints)
            .isEqualTo(b.borderBox.maxHeight)
            .isEqualTo(constraints.maxHeight - b.margins.vertical)
      }
    }
  }

  @Test
  fun `the same constraints always give the same answer`() {
    forEachCase { constraints, declared ->
      val props = declared.build()
      val first = summary(resolve(constraints, props))
      resolve(SizeConstraints(0, 97, 0, 41), props)
      resolve(exact(333, 5), props)

      assertThat(summary(resolve(constraints, props))).isEqualTo(first)
    }
  }

  // ───────────────────────── relationships between two resolutions

  @Test
  fun `a child with margins gets the box it would get without them in constraints shrunk by them`() {
    var checked = 0
    forEachCase { constraints, declared ->
      // Percent sizes and padding resolve against the constraints themselves, so they would
      // resolve differently against the shrunk ones.
      if (declared.hasPercentSizeOrPadding) return@forEachCase
      val b = resolve(constraints, declared.build())
      val h = b.margins.horizontal
      val v = b.margins.vertical
      // Margins alone wider than the constraints overflow them, which the shrunk constraints, at
      // 0, cannot show. A child's own size and min overflowing them is covered: that compares
      // equal.
      if (
          (constraints.hasBoundedWidth && h > constraints.maxWidth) ||
              (constraints.hasBoundedHeight && v > constraints.maxHeight)
      ) {
        return@forEachCase
      }
      val shrunk = SizeConstraints(
          minWidth = (constraints.minWidth - h).coerceAtLeast(0),
          maxWidth =
              if (constraints.hasBoundedWidth) (constraints.maxWidth - h).coerceAtLeast(0) else INF,
          minHeight = (constraints.minHeight - v).coerceAtLeast(0),
          maxHeight =
              if (constraints.hasBoundedHeight) (constraints.maxHeight - v).coerceAtLeast(0)
              else INF,
      )
      val without = resolve(shrunk, declared.copy(margins = emptyList()).build())
      checked++

      assertThat(summary(without))
          .describedAs("%s in %s", declared, constraints)
          .isEqualTo(summary(b).replace(text(b.margins), text(IntEdges.None)))
    }
    assertThat(checked).isGreaterThan(1_000)
  }

  @Test
  fun `a percentage gives what the equivalent points give`() {
    forEachCase { constraints, declared ->
      if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) return@forEachCase
      // A percentage size fills the room as `fillMaxWidth` does, while a size in pixels is
      // required, so only the other percentages come to what their pixels give.
      if (declared.width is BoxLength.Percent || declared.height is BoxLength.Percent) {
        return@forEachCase
      }
      val asPoints = declared.percentsAsPoints(constraints.maxWidth, constraints.maxHeight)

      assertThat(summary(resolve(constraints, asPoints.build())))
          .describedAs("%s in %s", declared, constraints)
          .isEqualTo(summary(resolve(constraints, declared.build())))
    }
  }

  @Test
  fun `a later unit replaces an earlier one on the same edge`() {
    val pointsLast = props {
      margin[BoxEdge.LEFT] = BoxLength.Percent(10f)
      margin[BoxEdge.LEFT] = BoxLength.Px(5f)
    }
    val percentLast = props {
      margin[BoxEdge.LEFT] = BoxLength.Px(5f)
      margin[BoxEdge.LEFT] = BoxLength.Percent(10f)
    }

    assertThat(resolve(atMost(200), pointsLast).margins.left).isEqualTo(5)
    assertThat(resolve(atMost(200), percentLast).margins.left).isEqualTo(20)
    assertThat(pointsLast.margin[BoxEdge.LEFT]).isEqualTo(px(5))
    assertThat(percentLast.margin[BoxEdge.LEFT]).isEqualTo(pct(10f))
    assertThat(percentLast.margin[BoxEdge.TOP]).isEqualTo(BoxLength.Unset)
  }

  @Test
  fun `a root's ratio keeps the width its holder decides and derives the height past the room`() {
    // The exact width decides the axis; the derived height is kept within its own max only.
    val box = resolveRoot(
        SizeConstraints(200, 200, 0, 50),
        props {
          minWidth = px(186)
          maxWidth = px(276)
          maxHeight = px(385)
          aspectRatio = 3.7f
        },
    )

    assertThat(box.borderBox).isEqualTo(exact(200, 54))
  }

  @Test
  fun `a root's ratio keeps its declared height when the derived width leaves its range`() {
    val declared = props {
      height = px(50)
      maxWidth = px(100)
      aspectRatio = 3f
    }

    assertThat(resolveRoot(atMost(200, 200), declared).borderBox).isEqualTo(exact(100, 50))
    assertThat(resolve(atMost(200, 200), declared).borderBox).isEqualTo(exact(100, 50))
  }

  @Test
  fun `a root derives its height from its width as a child does`() {
    val declared = props {
      width = px(18)
      aspectRatio = 2.4f
    }

    assertThat(resolveRoot(atMost(300, 300), declared).borderBox)
        .isEqualTo(resolve(atMost(300, 300), declared).borderBox)
  }

  @Test
  fun `a root's ratio derives from its declared width before its padding floors it, as a child's`() {
    val declared = props {
      width = px(50)
      padding[BoxEdge.LEFT] = px(50)
      padding[BoxEdge.RIGHT] = px(50)
      aspectRatio = 1f
    }

    assertThat(resolveRoot(atMost(300, 300), declared).borderBox).isEqualTo(exact(100, 50))
    assertThat(resolve(atMost(300, 300), declared).borderBox).isEqualTo(exact(100, 50))
  }

  @Test
  fun `a root's ratio keeps the width its holder fills, the height within its own max`() {
    val box = resolveRoot(
        SizeConstraints(200, 200, 0, 300),
        props {
          maxHeight = px(50)
          aspectRatio = 1f
        },
    )

    assertThat(box.borderBox).isEqualTo(exact(200, 50))
  }

  @Test
  fun `a root's ratio leaves the width its holder fills when no width in range keeps it`() {
    val box = resolveRoot(
        SizeConstraints(200, 200, 0, 300),
        props {
          minWidth = px(150)
          maxHeight = px(50)
          aspectRatio = 1f
        },
    )

    assertThat(box.borderBox).isEqualTo(exact(200, 50))
  }

  @Test
  fun `a root's ratio yields when no size in both ranges keeps it`() {
    val box = resolveRoot(
        SizeConstraints(100, 100, 0, 300),
        props {
          minHeight = px(200)
          aspectRatio = 1f
        },
    )

    assertThat(box.borderBox).isEqualTo(exact(100, 200))
  }

  @Test
  fun `a root's declared width stays when its padding floors the derived height`() {
    val constraints = SizeConstraints(200, 200, 0, 300)
    val declared = props {
      width = px(100)
      padding[BoxEdge.TOP] = px(150)
      aspectRatio = 1f
    }

    assertThat(resolveRoot(constraints, declared).borderBox).isEqualTo(exact(100, 150))
    assertThat(resolve(constraints, declared).borderBox).isEqualTo(exact(100, 150))
  }

  @Test
  fun `a root fills an exact axis and wraps its content on an at-most one`() {
    val box = resolveRoot(SizeConstraints(200, 200, 0, 300), props())

    assertThat(box.borderBox).isEqualTo(SizeConstraints(200, 200, 0, 300))
  }

  @Test
  fun `a root's own min wins over an own max below it`() {
    val box = resolveRoot(
        atMost(300, 300),
        props {
          minWidth = px(200)
          maxWidth = px(100)
        },
    )

    assertThat(box.borderBox).isEqualTo(SizeConstraints(200, 200, 0, 300))
  }

  @Test
  fun `a root's ratio whose inverse is not finite counts as none`() {
    val box = resolveRoot(
        SizeConstraints(100, 100, 0, 300),
        props { aspectRatio = Float.MIN_VALUE },
    )

    assertThat(box.borderBox).isEqualTo(SizeConstraints(100, 100, 0, 300))
  }

  @Test
  fun `a root rounds a percentage as a child does`() {
    val declared = props { width = pct(26.5f) }

    assertThat(resolveRoot(atMost(100), declared).borderBox.maxWidth).isEqualTo(26)
    assertThat(resolve(atMost(100), declared).borderBox.maxWidth).isEqualTo(26)
  }

  private fun forEachCase(block: (SizeConstraints, BoxConstraintsCases.Declared) -> Unit) {
    for (declared in CASES) {
      for (constraints in BoxConstraintsCases.constraints) {
        block(constraints, declared)
      }
    }
  }

  private companion object {
    val CASES: List<BoxConstraintsCases.Declared> = BoxConstraintsCases.generated(count = 600)
  }
}
