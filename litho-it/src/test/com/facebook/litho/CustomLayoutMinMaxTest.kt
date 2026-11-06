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

@file:OptIn(ExperimentalLithoApi::class)

package com.facebook.litho

import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.marginPercent
import com.facebook.litho.core.maxHeight
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.maxWidthPercent
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.minWidthPercent
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val VIEWPORT = 1000
private const val CHILD = 100
private const val M = 7

/** Measures the only child under [constraints] and places it at the origin. */
private fun measuringUnder(constraints: (SizeConstraints) -> SizeConstraints): LayoutFunctionN =
    LayoutFunctionN { ms, c ->
      val p = ms[0].measure(constraints(c))
      layout(c.maxWidth, p.height) { p.place(0, 0) }
    }

private val bounded = measuringUnder { c -> SizeConstraints(0, c.maxWidth, 0, c.maxHeight) }

private fun LithoTestRule.renderAndFindContainer(component: Component): LithoLayoutResult =
    findIn(render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Column { child(component) } })

private fun findIn(testView: TestLithoView): LithoLayoutResult {
  val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
  return checkNotNull(findContainer(root as LithoLayoutResult)) { "container not found in tree" }
}

private fun findContainer(result: LithoLayoutResult): LithoLayoutResult? {
  if (result.node.tailComponent is Layout) return result
  for (i in 0 until result.childCount) {
    findContainer(result.getChildAt(i))?.let {
      return it
    }
  }
  return null
}

/**
 * A child's declared min and max replace the bound of the constraints a custom layout measures it
 * under on their side, as Compose's `requiredWidthIn` does, rather than being overwritten by them.
 * Both win even over an exact constraint, and between the two the min wins, as in CSS.
 */
@RunWith(LithoTestRunner::class)
class CustomLayoutMinMaxTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `a declared maxWidth holds under a looser bound`() {
    val capped =
        SimpleMountSpecTester(style = Style.width(CHILD.px).maxWidth(50.px).height(CHILD.px))

    val result = lithoTestRule.renderAndFindContainer(Layout(listOf(capped), layout = bounded))

    assertThat(result.getChildAt(0).width)
        .describedAs("100 means the bound replaced the declared max")
        .isEqualTo(50)
  }

  @Test
  fun `a declared maxHeight holds under a looser bound`() {
    val capped =
        SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px).maxHeight(50.px))

    // An explicit height bound on every run. Under `bounded`, the intrinsic run's height is
    // unbounded, so today's code writes the declared max itself and the test would pass unfixed.
    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(capped),
                layout = measuringUnder { c -> SizeConstraints(0, c.maxWidth, 0, 300) },
            ),
        )

    assertThat(result.getChildAt(0).height)
        .describedAs("100 means the 300 bound replaced the declared max")
        .isEqualTo(50)
  }

  @Test
  fun `a percent maxWidth resolves against the bound`() {
    val fill = SimpleMountSpecTester(style = Style.maxWidthPercent(50f).height(CHILD.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(fill),
                layout = measuringUnder { c -> SizeConstraints(0, 300, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width).isEqualTo(150)
  }

  @Test
  fun `a declared maxWidth wins over an exact constraint`() {
    // No own width, so the exact width is what the own max has to narrow.
    val capped = SimpleMountSpecTester(style = Style.maxWidth(50.px).height(CHILD.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(listOf(capped), layout = measuringUnder { SizeConstraints.exact(300, CHILD) }),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("the own max wins over the exact 300, as in a Column that stretches it")
        .isEqualTo(50)
  }

  @Test
  fun `a declared maxWidth is the box, with margins outside it`() {
    val fill =
        SimpleMountSpecTester(style = Style.maxWidth(150.px).height(CHILD.px).margin(all = M.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(fill),
                layout = measuringUnder { c -> SizeConstraints(0, 300, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("150 - 2M means the max went in without the margins Yoga takes out of it")
        .isEqualTo(150)
  }

  /**
   * The same with a percentage margin. A percentage is never kept between measurements, since it
   * resolves against the space offered, so the layout that corrects the declared max for the
   * margins used to run without them and changed nothing.
   */
  @Test
  fun `a declared maxWidth is the box with a percentage margin outside it`() {
    val fill = SimpleMountSpecTester(
        style = Style.maxWidth(150.px).height(CHILD.px).marginPercent(horizontal = 5f),
    )

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(fill),
                layout = measuringUnder { c -> SizeConstraints(0, 300, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("short by its margins means the max went in without them")
        .isEqualTo(150)
  }

  /**
   * A declared max that comes to a fraction of a pixel is rounded, as Yoga rounds it for the same
   * child in a Column, not truncated a pixel short.
   */
  @Test
  fun `a fractional declared max rounds as it does in a Column`() {
    val bound = 301
    fun fill() = SimpleMountSpecTester(style = Style.maxWidthPercent(50f).height(CHILD.px))

    val inColumn =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column { child(Column(style = Style.width(bound.px)) { child(fill()) }) }
        }
    val columnWidth =
        checkNotNull(inColumn.componentTree.mainThreadLayoutState?.rootLayoutResult)
            .let { it as LithoLayoutResult }
            .getChildAt(0)
            .getChildAt(0)
            .width

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(fill()),
                layout = measuringUnder { c -> SizeConstraints(0, bound, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("50%% of %s, as a Column sizes it", bound)
        .isEqualTo(columnWidth)
  }

  @Test
  fun `a declared minWidth holds under a looser constraint minimum`() {
    val floored = SimpleMountSpecTester(style = Style.width(20.px).minWidth(80.px).height(CHILD.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(floored),
                layout = measuringUnder { c -> SizeConstraints(10, c.maxWidth, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("20 means the constraint's minimum of 10 replaced the declared 80")
        .isEqualTo(80)
  }

  @Test
  fun `a percent minWidth resolves against the bound`() {
    val floored =
        SimpleMountSpecTester(style = Style.width(20.px).minWidthPercent(50f).height(CHILD.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(floored),
                layout = measuringUnder { c -> SizeConstraints(0, 300, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width).isEqualTo(150)
  }

  @Test
  fun `a declared minWidth beats an exact constraint`() {
    val floored =
        SimpleMountSpecTester(style = Style.width(20.px).minWidth(400.px).height(CHILD.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(listOf(floored), layout = measuringUnder { SizeConstraints.exact(300, CHILD) }),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("the own min wins over the exact 300, as in Yoga and CSS")
        .isEqualTo(400)
  }

  /**
   * A declared min larger than the bound wins over it, as in Yoga and CSS, so the child's answer
   * can be wider than the constraints it was asked under. Here the child also declares a width,
   * which the min then raises.
   */
  @Test
  fun `a declared minWidth above the bound wins over it`() {
    val floored =
        SimpleMountSpecTester(style = Style.width(20.px).minWidth(400.px).height(CHILD.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(floored),
                layout = measuringUnder { c -> SizeConstraints(0, 300, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("the own min wins over the 300 bound, as in Yoga and CSS")
        .isEqualTo(400)
  }

  /** The same for a child with no width of its own, whose room the min raises past the bound. */
  @Test
  fun `a declared minWidth above the bound wins over it without a declared width`() {
    val floored = SimpleMountSpecTester(style = Style.minWidth(400.px).height(CHILD.px))

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(floored),
                layout = measuringUnder { c -> SizeConstraints(0, 300, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("the own min raises the room past the 300 bound")
        .isEqualTo(400)
  }

  @Test
  fun `a declared minWidth beats a declared maxWidth, as in CSS`() {
    val capped = SimpleMountSpecTester(
        style = Style.width(20.px).minWidth(80.px).maxWidth(50.px).height(CHILD.px),
    )

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(capped),
                layout = measuringUnder { c -> SizeConstraints(0, 300, 0, c.maxHeight) },
            ),
        )

    assertThat(result.getChildAt(0).width)
        .describedAs("50 would mean the declared max beat the declared min")
        .isEqualTo(80)
  }

  /**
   * An own min above an own max wins on every path, as in CSS: a leaf a `Layout` measures, the same
   * leaf inside a Yoga tree a `Layout` measures, the leaf in a `Column`, and a root `Layout`,
   * across and down, in both directions and under any room.
   */
  @Test
  fun `an own min above an own max wins on every path, as in CSS`() {
    val rooms = listOf(
        "at most 300" to SizeConstraints(0, 300, 0, 300),
        "exactly 300" to SizeConstraints.exact(300, 300),
        "unbounded" to SizeConstraints(),
    )
    val mismatches = ArrayList<String>()
    for (down in listOf(false, true)) {
      for (size in listOf(20, 100)) {
        val style =
            if (down) Style.width(CHILD.px).height(size.px).minHeight(80.px).maxHeight(50.px)
            else Style.width(size.px).minWidth(80.px).maxWidth(50.px).height(CHILD.px)
        val axis = if (down) "down" else "across"
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          for ((roomName, room) in rooms) {
            val direct =
                lithoTestRule
                    .renderAndFindContainer(
                        Column(style = Style.layoutDirection(direction)) {
                          child(
                              Layout(
                                  listOf(SimpleMountSpecTester(style = style)),
                                  layout = measuringUnder { room },
                              ),
                          )
                        },
                    )
                    .getChildAt(0)
            val inYogaTree =
                lithoTestRule
                    .renderAndFindContainer(
                        Column(style = Style.layoutDirection(direction)) {
                          child(
                              Layout(
                                  listOf(
                                      Column(alignItems = YogaAlign.FLEX_START) {
                                        child(SimpleMountSpecTester(style = style))
                                      },
                                  ),
                                  layout = measuringUnder { room },
                              ),
                          )
                        },
                    )
                    .getChildAt(0)
                    .getChildAt(0)
            val inColumn = checkNotNull(
                lithoTestRule
                    .render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
                      Column(
                          alignItems = YogaAlign.FLEX_START,
                          style = Style.layoutDirection(direction),
                      ) {
                        child(Column { child(SimpleMountSpecTester(style = style)) })
                      }
                    }
                    .componentTree
                    .mainThreadLayoutState
                    ?.rootLayoutResult,
            )
                .let { it as LithoLayoutResult }
                .getChildAt(0)
                .getChildAt(0)
            val sizes =
                listOf(direct, inYogaTree, inColumn).map { if (down) it.height else it.width }
            if (sizes.any { it != 80 }) {
              mismatches +=
                  "$axis, size $size, $direction, $roomName: direct, in a Yoga tree, in a Column = $sizes"
            }
          }
        }
      }
    }
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val atMost300 = SizeSpec.makeSizeSpec(300, SizeSpec.AT_MOST)
    val exactly300 = SizeSpec.makeSizeSpec(300, SizeSpec.EXACTLY)
    for (down in listOf(false, true)) {
      val style =
          if (down) Style.minHeight(80.px).maxHeight(50.px)
          else Style.minWidth(80.px).maxWidth(50.px)
      for (spec in listOf(atMost300, exactly300, unbounded)) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val root = checkNotNull(
              lithoTestRule
                  .render(widthSpec = spec, heightSpec = spec) {
                    Layout(
                        listOf(SimpleMountSpecTester(style = Style.width(30.px).height(20.px))),
                        style = Style.layoutDirection(direction) + style,
                        layout =
                            LayoutFunctionN { measurables, constraints ->
                              val placeable =
                                  measurables[0].measure(
                                      SizeConstraints(
                                          0,
                                          constraints.maxWidth,
                                          0,
                                          constraints.maxHeight,
                                      ),
                                  )
                              layout(
                                  placeable.width.coerceIn(
                                      constraints.minWidth,
                                      constraints.maxWidth,
                                  ),
                                  placeable.height.coerceIn(
                                      constraints.minHeight,
                                      constraints.maxHeight,
                                  ),
                              ) {
                                placeable.place(0, 0)
                              }
                            },
                    )
                  }
                  .componentTree
                  .mainThreadLayoutState
                  ?.rootLayoutResult,
          )
              .let { it as LithoLayoutResult }
          val size = if (down) root.height else root.width
          if (size != 80) {
            mismatches += "root, ${if (down) "down" else "across"}, spec $spec, $direction: $size"
          }
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }
}
