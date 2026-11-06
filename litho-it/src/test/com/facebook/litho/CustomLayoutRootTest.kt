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

package com.facebook.litho

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.margin
import com.facebook.litho.core.marginPercent
import com.facebook.litho.core.maxHeight
import com.facebook.litho.core.maxHeightPercent
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.maxWidthPercent
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.padding
import com.facebook.litho.core.paddingPercent
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.aspectRatio
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.flexbox.position
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope as PrimitiveLayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.yoga.YogaDirection
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A `Layout` laid out on its own, as a tree's root, is built by its builder with no Yoga node: its
 * box resolves from its own box props within the root's constraints, as a `Layout`'s child's do
 * within its room.
 */
@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
class CustomLayoutRootTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `its margins come out of the room, and its padding insets its children`() {
    val view =
        lithoTestRule.render(widthPx = 200, heightPx = 100) {
          Layout(
              listOf(FixedView(30, 20)),
              style = Style.margin(left = 7.px, top = 3.px).padding(all = 5.px),
              layout = FILLS_ROOM,
          )
        }
    val root = rootResult(view)

    assertThat(root.measureOutput).isInstanceOf(DefaultMeasureOutput::class.java)
    assertThat(root.width).describedAs("the exact width, less the margins").isEqualTo(193)
    assertThat(root.height).isEqualTo(97)
    assertThat(root.resolvedMargins?.left).isEqualTo(7)
    // As for a Yoga root, the root offset positions only its animation: it mounts at the origin.
    assertThat(mountedFrame(view)).isEqualTo(Rect(5, 5, 35, 25))
  }

  /**
   * The root offset is where the root's animation starts: at its left and top margins, as a Yoga
   * root's is, and in RTL at the physical edges its start margin resolves to.
   */
  @Test
  fun `its animation starts at its margins, as a Yoga root's does, in both directions`() {
    fun origin(root: Component): Point {
      val view = lithoTestRule.render(widthPx = 200, heightPx = 100) { root }
      val bounds =
          checkNotNull(view.componentTree.mainThreadLayoutState?.animatableRootItem)
              .getAbsoluteBounds()
      return Point(bounds.left, bounds.top)
    }

    for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
      val style = Style.layoutDirection(direction).margin(start = 7.px, top = 3.px)
      val layout = origin(Layout(listOf(FixedView(30, 20)), style = style, layout = FILLS_ROOM))
      val yoga = origin(Column(style = style) { child(FixedView(30, 20)) })

      assertThat(layout).describedAs("%s", direction).isEqualTo(yoga)
      val expectedLeft = if (direction == YogaDirection.RTL) 0 else 7
      assertThat(layout).describedAs("%s", direction).isEqualTo(Point(expectedLeft, 3))
    }
  }

  @Test
  fun `under an at-most room it takes the size its function declares, with its padding`() {
    val view =
        lithoTestRule.render(widthPx = 200, heightPx = 100) {
          Layout(
              listOf(FixedView(30, 20)),
              style = Style.padding(all = 4.px),
              layout = WRAPS_CHILD,
          )
        }
    relayoutAtMost(view, width = 200, height = 100)
    val root = rootResult(view)

    assertThat(root.width).isEqualTo(38)
    assertThat(root.height).isEqualTo(28)
    assertThat(SizeSpec.getMode(root.widthSpec))
        .describedAs("the root's spec, which deferred content is reused by")
        .isEqualTo(SizeSpec.AT_MOST)
    assertThat(SizeSpec.getSize(root.widthSpec)).isEqualTo(200)
  }

  @Test
  fun `right to left, its start margin is on its right`() {
    val view =
        lithoTestRule.render(widthPx = 200, heightPx = 100) {
          Layout(
              listOf(FixedView(30, 20)),
              style = Style.layoutDirection(YogaDirection.RTL).margin(start = 7.px),
              layout = FILLS_ROOM,
          )
        }
    val root = rootResult(view)

    assertThat(root.width).isEqualTo(193)
    assertThat(root.resolvedMargins?.right).isEqualTo(7)
    assertThat(mountedFrame(view).left).describedAs("not offset by a right margin").isEqualTo(0)
  }

  @Test
  fun `a function that throws is reported once, and lays out as 0 by 0`() {
    val reported = ArrayList<Exception>()

    class Guarded : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        useErrorBoundary { failure: Exception -> reported.add(failure) }
        return Layout(listOf(FixedView(30, 20)), layout = THROWS)
      }
    }

    lithoTestRule.render(widthPx = 200, heightPx = 100) { Guarded() }

    assertThat(reported).hasSize(1)
    assertThat(reported[0]).hasStackTraceContaining("thrown by the function")
  }

  /**
   * A `Layout` that throws, placed inside a Yoga child of a `Layout`, is reported once: its result
   * reaches the final tree once, however often the Yoga tree around it is laid out.
   */
  @Test
  fun `a placed Layout's failure inside a Yoga child is reported exactly once`() {
    val reported = ArrayList<Exception>()

    class Guarded : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        useErrorBoundary { failure: Exception -> reported.add(failure) }
        return Layout(
            listOf(Column { child(Layout(listOf(FixedView(30, 20)), layout = THROWS)) }),
            layout = MEASURES_TWICE_PLACES,
        )
      }
    }

    lithoTestRule.render(widthPx = 200, heightPx = 100) { Guarded() }

    assertThat(reported).hasSize(1)
    assertThat(reported[0]).hasStackTraceContaining("thrown by the function")
  }

  /** The same `Layout`, only measured, never reaches the final tree, so it's never reported. */
  @Test
  fun `a Layout's failure inside a Yoga child that's only probed is never reported`() {
    val reported = ArrayList<Exception>()

    class Guarded : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        useErrorBoundary { failure: Exception -> reported.add(failure) }
        return Layout(
            listOf(Column { child(Layout(listOf(FixedView(30, 20)), layout = THROWS)) }),
            layout = PROBES_ONLY,
        )
      }
    }

    lithoTestRule.render(widthPx = 200, heightPx = 100) { Guarded() }

    assertThat(reported).isEmpty()
  }

  /**
   * A leaf whose measure throws, under a `Layout`, reaches the error boundary as it does under a
   * Column: once.
   */
  @Test
  fun `a leaf whose measure throws is reported once, as under a Column`() {
    fun reportedUnder(parent: (Component) -> Component): List<Exception> {
      val reported = ArrayList<Exception>()

      class Guarded : KComponent() {
        @Suppress("KComponentUncachedAllocationDetector")
        override fun ComponentScope.render(): Component {
          useErrorBoundary { failure: Exception -> reported.add(failure) }
          return parent(ThrowingLeaf())
        }
      }

      lithoTestRule.render(widthPx = 200, heightPx = 100) { Guarded() }
      return reported
    }

    val underLayout = reportedUnder { leaf -> Layout(listOf(leaf), layout = WRAPS_CHILD) }
    val underColumn = reportedUnder { leaf -> Column { child(leaf) } }

    assertThat(underColumn).describedAs("under a Column").hasSize(1)
    assertThat(underLayout).describedAs("under a Layout").hasSize(1)
    assertThat(underLayout[0]).hasStackTraceContaining("thrown by the leaf's measure")
  }

  @Test
  fun `a state update that renders its child again lays it out again`() {
    lateinit var width: State<Int>

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { 30 }
        width = state
        return Layout(listOf(FixedView(state.value, 20)), layout = WRAPS_CHILD)
      }
    }

    val view = lithoTestRule.render(widthPx = 200, heightPx = 100) { Host() }
    relayoutAtMost(view, width = 200, height = 100)
    lithoTestRule.act(view) { width.update(50) }
    lithoTestRule.idle()

    assertThat(rootResult(view).width).isEqualTo(50)
  }

  /**
   * Its holder is its parent, and gives it a room from 0 to the root constraints, which its own
   * style decides within, and which only a size it declares or derives from its aspect ratio can
   * pass, as a child's can. Exact width 200, at most height 300.
   */
  @Test
  fun `its own style decides its box within the room its holder gives`() {
    val cases = listOf(
        Triple("no width, start margin", Style.margin(start = 7.px), RootBox(193, 20, 7, 0)),
        Triple("width 100", Style.width(100.px), RootBox(100, 20, 0, 0)),
        Triple("width 50%", Style.widthPercent(50f), RootBox(100, 20, 0, 0)),
        Triple("width 300", Style.width(300.px), RootBox(300, 20, 0, 0)),
        Triple("aspect ratio 0.5", Style.aspectRatio(0.5f), RootBox(200, 400, 0, 0)),
        Triple(
            "aspect ratio 0.5, own max height 250",
            Style.aspectRatio(0.5f).maxHeight(250.px),
            RootBox(200, 250, 0, 0),
        ),
        Triple("position inset", Style.position(left = 10.px), RootBox(200, 20, 0, 0)),
    )
    for ((name, style, expected) in cases) {
      for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
        val box =
            rootBoxOf(EXACTLY_200, AT_MOST_300) {
              Layout(
                  listOf(FixedView(30, 20)),
                  style = Style.layoutDirection(direction) + style,
                  layout = WRAPS_CHILD,
              )
            }
        // A start margin is on the right in RTL, where it doesn't move the root.
        val expectedX = if (direction == YogaDirection.RTL) 0 else expected.x
        assertThat(box)
            .describedAs("%s, %s", name, direction)
            .isEqualTo(expected.copy(x = expectedX))
      }
    }
  }

  /**
   * The edge rules `resolveRootBox` documents, each against content of 30 x 20, at an exact width
   * of 200 and a height at most 300 or, for the percentage, unbounded.
   */
  @Test
  fun `its own limits, padding, ratio and percentages follow the rules it documents`() {
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val cases = listOf(
        Triple("own min above own max: the min wins", Style.minWidth(150.px).maxWidth(100.px), 150),
        Triple(
            "a declared width with its own min above its own max: the min wins",
            Style.width(120.px).minWidth(150.px).maxWidth(100.px),
            150,
        ),
        Triple(
            "own max with a margin: it bounds the border box, within the room",
            Style.maxWidth(190.px).margin(start = 20.px),
            180,
        ),
        Triple(
            "padding past the room: the border box floors at it",
            Style.padding(horizontal = 120.px),
            240,
        ),
    )
    for ((name, style, width) in cases) {
      val box =
          rootBoxOf(EXACTLY_200, AT_MOST_300) {
            Layout(listOf(FixedView(30, 20)), style = style, layout = WRAPS_CHILD)
          }
      assertThat(box.width).describedAs(name).isEqualTo(width)
    }

    val yielding =
        rootBoxOf(EXACTLY_200, AT_MOST_300) {
          Layout(
              listOf(FixedView(30, 20)),
              style = Style.aspectRatio(0.5f).minWidth(150.px).maxHeight(50.px),
              layout = WRAPS_CHILD,
          )
        }
    assertThat(yielding)
        .describedAs("no size in both ranges keeps the ratio: it yields, the width kept")
        .isEqualTo(RootBox(200, 50, 0, 0))
    val notANumber =
        rootBoxOf(EXACTLY_200, AT_MOST_300) {
          Layout(
              listOf(FixedView(30, 20)),
              style = Style.aspectRatio(Float.NaN),
              layout = WRAPS_CHILD,
          )
        }
    assertThat(notANumber)
        .describedAs("a ratio that isn't finite counts as none")
        .isEqualTo(RootBox(200, 20, 0, 0))
    val percentOfUnbounded =
        rootBoxOf(EXACTLY_200, unbounded) {
          Layout(
              listOf(FixedView(30, 20)),
              style = Style.heightPercent(50f),
              layout = WRAPS_CHILD,
          )
        }
    assertThat(percentOfUnbounded)
        .describedAs("a percentage of an unbounded axis has no effect")
        .isEqualTo(RootBox(200, 20, 0, 0))
  }

  /**
   * With neither axis decided, its aspect ratio has no effect, as a `Layout`'s child's has none.
   */
  @Test
  fun `with neither axis decided, its aspect ratio has no effect`() {
    val atMost200 = SizeSpec.makeSizeSpec(200, SizeSpec.AT_MOST)
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val cases = listOf(
        Triple(
            "at most 200 x 300, ratio 2",
            Triple(atMost200, AT_MOST_300, 2f),
            RootBox(30, 20, 0, 0),
        ),
        Triple(
            "at most 200 x 300, ratio 0.5",
            Triple(atMost200, AT_MOST_300, 0.5f),
            RootBox(30, 20, 0, 0),
        ),
        Triple(
            "unbounded x at most 300, ratio 2",
            Triple(unbounded, AT_MOST_300, 2f),
            RootBox(30, 20, 0, 0),
        ),
        Triple("unbounded, ratio 2", Triple(unbounded, unbounded, 2f), RootBox(30, 20, 0, 0)),
    )
    for ((name, specs, expected) in cases) {
      val (widthSpec, heightSpec, ratio) = specs
      val box =
          rootBoxOf(widthSpec, heightSpec) {
            Layout(
                listOf(FixedView(30, 20)),
                style = Style.aspectRatio(ratio),
                layout = WRAPS_CHILD,
            )
          }
      assertThat(box).describedAs(name).isEqualTo(expected)
    }
  }

  @Test
  fun `a child is larger than its parent only when the parent measures it in a larger room`() {
    val view =
        lithoTestRule.render(widthPx = 200, heightPx = 100) {
          Layout(listOf(FixedView(300, 20)), layout = UNBOUNDED_ACROSS_PLACED_LEFT_OF_ORIGIN)
        }

    assertThat(rootResult(view).width).isEqualTo(200)
    assertThat(mountedFrame(view)).isEqualTo(Rect(-50, 0, 250, 20))
  }

  /**
   * Where a `Layout` root and a `Column` root differ, a Yoga root clips content, breaks the ratio
   * or comes out a pixel off: margins across an exact axis, an aspect ratio, position insets,
   * margins on an axis with an own max (a Yoga root keeps its margin box within the max), an own
   * min above its own max, and logical and physical horizontal margins together in RTL. Everywhere
   * else, a `Layout` root gets the box and the offset a `Column` root gets.
   */
  @Test
  fun `outside the documented differences, its box and offset are a Column root's`() {
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val constraints = listOf(
        "exact x at most" to (EXACTLY_200 to AT_MOST_300),
        "at most" to (SizeSpec.makeSizeSpec(200, SizeSpec.AT_MOST) to AT_MOST_300),
        "unbounded" to (unbounded to unbounded),
    )
    val styles: List<Pair<String, Style>> = listOf(
        "none" to Style,
        "width 100" to Style.width(100.px),
        "height 40" to Style.height(40.px),
        "width 50%" to Style.widthPercent(50f),
        "padding" to Style.padding(start = 4.px, top = 2.px, end = 6.px),
        "percent padding" to Style.paddingPercent(all = 5f),
        "min width 120" to Style.minWidth(120.px),
        "max width 150" to Style.maxWidth(150.px),
        "min height 50" to Style.minHeight(50.px),
    )
    val boundedOnly: List<Pair<String, Style>> = listOf(
        "start and top margins" to Style.margin(start = 7.px, top = 3.px),
        "percent margins" to Style.marginPercent(start = 5f),
    )
    val mismatches = ArrayList<String>()
    for ((constraintName, specs) in constraints) {
      val (widthSpec, heightSpec) = specs
      val cases =
          if (SizeSpec.getMode(widthSpec) == SizeSpec.EXACTLY) styles else styles + boundedOnly
      for ((styleName, style) in cases) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val styled = Style.layoutDirection(direction) + style
          val layout =
              rootBoxOf(widthSpec, heightSpec) {
                Layout(listOf(FixedView(30, 20)), style = styled, layout = WRAPS_CHILD)
              }
          val column =
              rootBoxOf(widthSpec, heightSpec) {
                Column(style = styled) { child(FixedView(30, 20)) }
              }
          if (layout != column) {
            mismatches += "$constraintName, $styleName, $direction: Layout $layout, Column $column"
          }
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * Its own min is kept past the room, as a declared size is and as for a child: exactly and at
   * most, across and down, in both directions. A Yoga `Column` root keeps it past the room only
   * down.
   */
  @Test
  fun `its own min is kept past the room, as a declared size is`() {
    val exactly200 = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
    val atMost200 = SizeSpec.makeSizeSpec(200, SizeSpec.AT_MOST)
    val exactly300 = SizeSpec.makeSizeSpec(300, SizeSpec.EXACTLY)
    val cases = listOf(
        Triple(
            "min width 300, exactly 200 wide",
            Style.minWidth(300.px),
            exactly200 to AT_MOST_300,
        ),
        Triple("min width 300, at most 200 wide", Style.minWidth(300.px), atMost200 to AT_MOST_300),
        Triple(
            "min height 400, at most 300 high",
            Style.minHeight(400.px),
            atMost200 to AT_MOST_300,
        ),
        Triple(
            "min height 400, exactly 300 high",
            Style.minHeight(400.px),
            atMost200 to exactly300,
        ),
    )
    val expected = listOf(300 to 20, 300 to 20, 30 to 400, 30 to 400)
    for ((index, case) in cases.withIndex()) {
      val (name, style, specs) = case
      for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
        val box =
            rootBoxOf(specs.first, specs.second) {
              Layout(
                  listOf(FixedView(30, 20)),
                  style = Style.layoutDirection(direction) + style,
                  layout = WRAPS_CHILD,
              )
            }
        assertThat(box.width to box.height)
            .describedAs("%s, %s", name, direction)
            .isEqualTo(expected[index])
      }
    }
  }

  /**
   * A negative size or own max has no effect, as for a child and in CSS: it gets the box it gets
   * without it, exactly, at most and with no bound, in both directions. A Yoga root fills its room
   * instead when its size is negative.
   */
  @Test
  fun `a negative size or own max has no effect, as for a child`() {
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val constraints = listOf(
        "exact x at most" to (EXACTLY_200 to AT_MOST_300),
        "at most" to (SizeSpec.makeSizeSpec(200, SizeSpec.AT_MOST) to AT_MOST_300),
        "unbounded" to (unbounded to unbounded),
    )
    val styles: List<Pair<String, Style>> = listOf(
        "width -10" to Style.width((-10f).px),
        "width -50%" to Style.widthPercent(-50f),
        "width -0.5%" to Style.widthPercent(-0.5f),
        "height -10" to Style.height((-10f).px),
        "height -50%" to Style.heightPercent(-50f),
        "height -0.5%" to Style.heightPercent(-0.5f),
        "max width -10" to Style.maxWidth((-10f).px),
        "max width -50%" to Style.maxWidthPercent(-50f),
        "max height -10" to Style.maxHeight((-10f).px),
        "max height -0.5%" to Style.maxHeightPercent(-0.5f),
    )
    val mismatches = ArrayList<String>()
    for ((constraintName, specs) in constraints) {
      val (widthSpec, heightSpec) = specs
      for ((name, style) in styles) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val with =
              rootBoxOf(widthSpec, heightSpec) {
                Layout(
                    listOf(FixedView(30, 20)),
                    style = Style.layoutDirection(direction) + style,
                    layout = WRAPS_CHILD,
                )
              }
          val without =
              rootBoxOf(widthSpec, heightSpec) {
                Layout(
                    listOf(FixedView(30, 20)),
                    style = Style.layoutDirection(direction),
                    layout = WRAPS_CHILD,
                )
              }
          if (with != without)
              mismatches += "$constraintName, $name, $direction: $with, without $without"
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * A size it declares stays when it's larger than the room, within its own min and max, as for a
   * `Column` root: exactly, at most and with no bound, in both directions.
   */
  @Test
  fun `a declared size past its room stays, as for a Column root`() {
    val constraints = listOf(
        "exactly 300 x 300" to SizeSpec.makeSizeSpec(300, SizeSpec.EXACTLY),
        "at most 300 x 300" to SizeSpec.makeSizeSpec(300, SizeSpec.AT_MOST),
        "unbounded" to SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
    )
    val styles: List<Pair<String, Style>> = listOf(
        "width 400" to Style.width(400.px),
        "height 400" to Style.height(400.px),
        "width 400, max width 350" to Style.width(400.px).maxWidth(350.px),
        "width 100, min width 350" to Style.width(100.px).minWidth(350.px),
        "width 150%" to Style.widthPercent(150f),
    )
    val mismatches = ArrayList<String>()
    for ((constraintName, spec) in constraints) {
      for ((styleName, style) in styles) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val styled = Style.layoutDirection(direction) + style
          val layout =
              rootBoxOf(spec, spec) {
                Layout(listOf(FixedView(30, 20)), style = styled, layout = WRAPS_CHILD)
              }
          val column = rootBoxOf(spec, spec) { Column(style = styled) { child(FixedView(30, 20)) } }
          if (layout != column) {
            mismatches += "$constraintName, $styleName, $direction: Layout $layout, Column $column"
          }
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * Across an exact room its margins come out of the room, as a child's do in a `Column` that
   * stretches it, in both directions, where a Yoga root keeps the room's size and its margins only
   * move it.
   */
  @Test
  fun `across an exact room its margins come out of the room, as a stretched child's do`() {
    val exactly300 = SizeSpec.makeSizeSpec(300, SizeSpec.EXACTLY)
    val cases = listOf(
        "start margin 6" to Style.margin(start = 6.px),
        "horizontal margins -20" to Style.margin(horizontal = (-20f).px),
        "start margin 5%" to Style.marginPercent(start = 5f),
    )
    for ((name, style) in cases) {
      for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
        val styled = Style.layoutDirection(direction) + style
        val root =
            rootBoxOf(exactly300, exactly300) {
              Layout(listOf(FixedView(30, 20)), style = styled, layout = WRAPS_CHILD)
            }
        val stretched =
            lithoTestRule.render(widthSpec = exactly300, heightSpec = exactly300) {
              Column(style = Style.layoutDirection(direction)) {
                child(Column(style = style) { child(FixedView(30, 20)) })
              }
            }
        val childWidth =
            (checkNotNull(stretched.componentTree.mainThreadLayoutState?.rootLayoutResult)
                    as LithoLayoutResult)
                .getChildAt(0)
                .width
        assertThat(root.width).describedAs("%s, %s", name, direction).isEqualTo(childWidth)
        assertThat(root.height).describedAs("%s, %s", name, direction).isEqualTo(300)
      }
    }
    val vertical =
        rootBoxOf(exactly300, exactly300) {
          Layout(
              listOf(FixedView(30, 20)),
              style = Style.margin(vertical = 5.px),
              layout = WRAPS_CHILD,
          )
        }
    assertThat(vertical.height).describedAs("exactly 300 high, less 5 and 5").isEqualTo(290)
  }

  /**
   * With an axis decided, its aspect ratio derives the other, as a child's does in a `Column`,
   * where a Yoga root ignores its ratio and wraps its content.
   */
  @Test
  fun `with an axis decided, its aspect ratio derives the other, as a child's does`() {
    val atMost300 = SizeSpec.makeSizeSpec(300, SizeSpec.AT_MOST)
    val cases = listOf(
        "width 120, ratio 3" to Style.width(120.px).aspectRatio(3f),
        "height 40, ratio 2" to Style.height(40.px).aspectRatio(2f),
    )
    for ((name, style) in cases) {
      val root =
          rootBoxOf(atMost300, atMost300) {
            Layout(listOf(FixedView(30, 20)), style = style, layout = WRAPS_CHILD)
          }
      val inColumn =
          lithoTestRule.render(widthSpec = atMost300, heightSpec = atMost300) {
            Column { child(Column(style = style) { child(FixedView(30, 20)) }) }
          }
      val child =
          (checkNotNull(inColumn.componentTree.mainThreadLayoutState?.rootLayoutResult)
                  as LithoLayoutResult)
              .getChildAt(0)
      assertThat(root.width to root.height).describedAs(name).isEqualTo(child.width to child.height)
    }
  }

  /**
   * With neither axis decided, its aspect ratio gives it the box it gives the same `Layout` as a
   * `Layout`'s child measured under the same constraints: none, in both directions.
   */
  @Test
  fun `with neither axis decided, its aspect ratio does what a Layout child's does`() {
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val atMost300 = SizeSpec.makeSizeSpec(300, SizeSpec.AT_MOST)
    val specs = listOf(
        "at most 300 x 300" to (atMost300 to atMost300),
        "unbounded" to (unbounded to unbounded),
        "unbounded x at most 300" to (unbounded to atMost300),
        "at most 300 x unbounded" to (atMost300 to unbounded),
    )
    val mismatches = ArrayList<String>()
    for ((specName, spec) in specs) {
      val (widthSpec, heightSpec) = spec
      val constraints = SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec)
      for (ratio in listOf(3f, 2f, 0.5f)) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val style = Style.layoutDirection(direction).aspectRatio(ratio)
          val root =
              rootBoxOf(widthSpec, heightSpec) {
                Layout(listOf(FixedView(30, 20)), style = style, layout = WRAPS_CHILD)
              }
          val view =
              lithoTestRule.render(widthPx = 1000, heightPx = 1000) {
                Column {
                  child(
                      Layout(
                          listOf(
                              Layout(
                                  listOf(FixedView(30, 20)),
                                  style = style,
                                  layout = WRAPS_CHILD,
                              ),
                          ),
                          layout =
                              LayoutFunctionN { measurables, _ ->
                                val placeable = measurables[0].measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                              },
                      ),
                  )
                }
              }
          val child = rootResult(view).getChildAt(0).getChildAt(0)
          if (root.width != child.width || root.height != child.height) {
            mismatches +=
                "$specName, ratio $ratio, $direction: root ${root.width}x${root.height}, " +
                    "child ${child.width}x${child.height}"
          }
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * With an axis decided, by an exact constraint or a declared size, its aspect ratio gives it the
   * box it gives the same `Layout` as a `Layout`'s child under the same constraints: the decided
   * axis keeps its size and the other is derived, within its own min and max only, in both
   * directions.
   */
  @Test
  fun `with an axis decided, its aspect ratio does what a Layout child's does`() {
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val atMost300 = SizeSpec.makeSizeSpec(300, SizeSpec.AT_MOST)
    val cases = listOf(
        "exactly 200 x at most 300" to Triple(EXACTLY_200, atMost300, Style),
        "exactly 200 x unbounded" to Triple(EXACTLY_200, unbounded, Style),
        "at most 300 x exactly 200" to Triple(atMost300, EXACTLY_200, Style),
        "unbounded x exactly 200" to Triple(unbounded, EXACTLY_200, Style),
        "width 120, at most 300" to Triple(atMost300, atMost300, Style.width(120.px)),
        "height 120, at most 300" to Triple(atMost300, atMost300, Style.height(120.px)),
        "exactly 200 x at most 300, max height 150" to
            Triple(EXACTLY_200, atMost300, Style.maxHeight(150.px)),
        "exactly 200 x at most 300, min height 250" to
            Triple(EXACTLY_200, atMost300, Style.minHeight(250.px)),
        "at most 300 x exactly 200, max width 150" to
            Triple(atMost300, EXACTLY_200, Style.maxWidth(150.px)),
    )
    val mismatches = ArrayList<String>()
    for ((name, case) in cases) {
      val (widthSpec, heightSpec, declared) = case
      val constraints = SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec)
      for (ratio in listOf(3f, 2f, 0.5f)) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val style = Style.layoutDirection(direction).aspectRatio(ratio) + declared
          val root =
              rootBoxOf(widthSpec, heightSpec) {
                Layout(listOf(FixedView(30, 20)), style = style, layout = WRAPS_CHILD)
              }
          val view =
              lithoTestRule.render(widthPx = 1000, heightPx = 1000) {
                Column {
                  child(
                      Layout(
                          listOf(
                              Layout(
                                  listOf(FixedView(30, 20)),
                                  style = style,
                                  layout = WRAPS_CHILD,
                              ),
                          ),
                          layout =
                              LayoutFunctionN { measurables, _ ->
                                val placeable = measurables[0].measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                              },
                      ),
                  )
                }
              }
          val child = rootResult(view).getChildAt(0).getChildAt(0)
          if (root.width != child.width || root.height != child.height) {
            mismatches +=
                "$name, ratio $ratio, $direction: root ${root.width}x${root.height}, " +
                    "child ${child.width}x${child.height}"
          }
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * Generated: sizes in px and %, mins and maxes, padding and margins on every kind of edge, under
   * exact, at-most and unbounded root constraints, in both directions, each outside the documented
   * differences.
   */
  @Test
  fun `generated cases outside the documented differences get a Column root's box and offset`() {
    val unbounded = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
    val constraints = listOf(
        Triple("exact x at most", EXACTLY_200, AT_MOST_300),
        Triple("at most", SizeSpec.makeSizeSpec(200, SizeSpec.AT_MOST), AT_MOST_300),
        Triple("unbounded", unbounded, unbounded),
    )
    val mismatches = ArrayList<String>()
    var compared = 0
    for ((constraintName, widthSpec, heightSpec) in constraints) {
      val exactWidth = SizeSpec.getMode(widthSpec) == SizeSpec.EXACTLY
      for ((styleName, style) in generatedRootStyles(count = 120, seed = 23, exactWidth)) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val styled = Style.layoutDirection(direction) + style
          val layout =
              rootBoxOf(widthSpec, heightSpec) {
                Layout(listOf(FixedView(30, 20)), style = styled, layout = WRAPS_CHILD)
              }
          val column =
              rootBoxOf(widthSpec, heightSpec) {
                Column(style = styled) { child(FixedView(30, 20)) }
              }
          compared++
          if (layout != column) {
            mismatches += "$constraintName, $styleName, $direction: Layout $layout, Column $column"
          }
        }
      }
    }
    assertThat(compared).isEqualTo(3 * 120 * 2)
    assertThat(mismatches).describedAs("of %s compared", compared).isEmpty()
  }

  /**
   * [count] root styles from [seed], each with a name saying what it declares. Sized for room 200
   * by 300: a declared width and the horizontal margins come to at most 190, a height and the
   * vertical margins to at most 230. It leaves out what isn't a `Column` root's box:
   * - an own min above its own max: which of the two a Yoga root keeps depends on its declared size
   *   and the constraints, where the room rule keeps the max;
   * - a margin on an axis with an own max, or across an exact width;
   * - logical and physical horizontal margins together.
   */
  private fun generatedRootStyles(
      count: Int,
      seed: Int,
      exactWidth: Boolean,
  ): List<Pair<String, Style>> {
    val random = Random(seed)
    val horizontal = listOf("start", "end", "left", "right", "horizontal", "all")
    val vertical = listOf("top", "bottom", "vertical", "all")
    return List(count) {
      val parts = ArrayList<String>()
      var style: Style = Style
      fun maybe(chance: Int = 3): Boolean = random.nextInt(chance) == 0
      if (maybe()) {
        val size = random.nextInt(20, 140)
        style += Style.width(size.px)
        parts += "width $size"
      } else if (maybe(5)) {
        val percent = random.nextInt(10, 70)
        style += Style.widthPercent(percent.toFloat())
        parts += "width $percent%"
      }
      if (maybe()) {
        val size = random.nextInt(10, 180)
        style += Style.height(size.px)
        parts += "height $size"
      } else if (maybe(5)) {
        val percent = random.nextInt(10, 60)
        style += Style.heightPercent(percent.toFloat())
        parts += "height $percent%"
      }
      val minWidth = if (maybe(4)) random.nextInt(0, 140) else null
      val maxWidth = if (maybe(4)) random.nextInt(minWidth ?: 40, 190) else null
      val minHeight = if (maybe(4)) random.nextInt(0, 180) else null
      val maxHeight = if (maybe(4)) random.nextInt(minHeight ?: 30, 230) else null
      minWidth?.let { size ->
        style += Style.minWidth(size.px)
        parts += "min width $size"
      }
      maxWidth?.let { size ->
        style += Style.maxWidth(size.px)
        parts += "max width $size"
      }
      minHeight?.let { size ->
        style += Style.minHeight(size.px)
        parts += "min height $size"
      }
      maxHeight?.let { size ->
        style += Style.maxHeight(size.px)
        parts += "max height $size"
      }
      repeat(random.nextInt(0, 3)) {
        val edge = (horizontal + vertical).random(random)
        if (maybe(3)) {
          val percent = random.nextInt(0, 5)
          style += paddingPercentOn(edge, percent.toFloat())
          parts += "padding $edge $percent%"
        } else {
          val size = random.nextInt(0, 12)
          style += paddingOn(edge, size)
          parts += "padding $edge $size"
        }
      }
      val horizontalMargins =
          if (random.nextBoolean()) listOf("start", "end", "horizontal")
          else listOf("left", "right")
      val marginEdges =
          (if (exactWidth || maxWidth != null) emptyList() else horizontalMargins) +
              (if (maxHeight != null) emptyList() else vertical - "all")
      repeat(if (marginEdges.isEmpty()) 0 else random.nextInt(0, 3)) {
        val edge = marginEdges.random(random)
        val size = random.nextInt(0, 12)
        style += marginOn(edge, size)
        parts += "margin $edge $size"
      }
      (if (parts.isEmpty()) "none" else parts.joinToString()) to style
    }
  }

  private fun paddingOn(edge: String, size: Int): Style =
      when (edge) {
        "start" -> Style.padding(start = size.px)
        "end" -> Style.padding(end = size.px)
        "left" -> Style.padding(left = size.px)
        "right" -> Style.padding(right = size.px)
        "top" -> Style.padding(top = size.px)
        "bottom" -> Style.padding(bottom = size.px)
        "horizontal" -> Style.padding(horizontal = size.px)
        "vertical" -> Style.padding(vertical = size.px)
        else -> Style.padding(all = size.px)
      }

  private fun paddingPercentOn(edge: String, percent: Float): Style =
      when (edge) {
        "start" -> Style.paddingPercent(start = percent)
        "end" -> Style.paddingPercent(end = percent)
        "left" -> Style.paddingPercent(left = percent)
        "right" -> Style.paddingPercent(right = percent)
        "top" -> Style.paddingPercent(top = percent)
        "bottom" -> Style.paddingPercent(bottom = percent)
        "horizontal" -> Style.paddingPercent(horizontal = percent)
        "vertical" -> Style.paddingPercent(vertical = percent)
        else -> Style.paddingPercent(all = percent)
      }

  private fun marginOn(edge: String, size: Int): Style =
      when (edge) {
        "start" -> Style.margin(start = size.px)
        "end" -> Style.margin(end = size.px)
        "left" -> Style.margin(left = size.px)
        "right" -> Style.margin(right = size.px)
        "top" -> Style.margin(top = size.px)
        "bottom" -> Style.margin(bottom = size.px)
        "horizontal" -> Style.margin(horizontal = size.px)
        "vertical" -> Style.margin(vertical = size.px)
        else -> Style.margin(all = size.px)
      }

  /** The root's border box, and where its holder places it. */
  private data class RootBox(val width: Int, val height: Int, val x: Int, val y: Int)

  private fun rootBoxOf(widthSpec: Int, heightSpec: Int, root: () -> Component): RootBox {
    val view = lithoTestRule.render(widthPx = 200, heightPx = 300) { root() }
    view.setSizeSpecs(widthSpec, heightSpec).measure().layout()
    lithoTestRule.idle()
    val result = rootResult(view)
    val origin =
        checkNotNull(view.componentTree.mainThreadLayoutState?.animatableRootItem)
            .getAbsoluteBounds()
    return RootBox(result.width, result.height, origin.left, origin.top)
  }

  private fun relayoutAtMost(view: TestLithoView, width: Int, height: Int) {
    view
        .setSizeSpecs(
            SizeSpec.makeSizeSpec(width, SizeSpec.AT_MOST),
            SizeSpec.makeSizeSpec(height, SizeSpec.AT_MOST),
        )
        .measure()
        .layout()
    lithoTestRule.idle()
  }

  private fun rootResult(view: TestLithoView): LithoLayoutResult =
      checkNotNull(view.componentTree.mainThreadLayoutState?.rootLayoutResult) as LithoLayoutResult

  /** Its only child's mounted view's frame, in the LithoView. */
  private fun mountedFrame(view: TestLithoView): Rect {
    val child = view.lithoView.getChildAt(0)
    return Rect(child.left, child.top, child.right, child.bottom)
  }
}

/** Measures its child within its own room and places it at the origin; it fills the room. */
private val FILLS_ROOM = LayoutFunctionN { measurables, constraints ->
  val placeable =
      measurables[0].measure(
          SizeConstraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight),
      )
  layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
}

/** Measures its child within its own room and takes its size. */
private val WRAPS_CHILD = LayoutFunctionN { measurables, constraints ->
  val placeable =
      measurables[0].measure(
          SizeConstraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight),
      )
  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private val THROWS = LayoutFunctionN { _, _ -> error("thrown by the function") }

private val EXACTLY_200 = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY)
private val AT_MOST_300 = SizeSpec.makeSizeSpec(300, SizeSpec.AT_MOST)

/** Measures its child with no bound across, and places it 50 left of its own origin. */
private val UNBOUNDED_ACROSS_PLACED_LEFT_OF_ORIGIN = LayoutFunctionN { measurables, constraints ->
  val placeable =
      measurables[0].measure(
          SizeConstraints(maxWidth = SizeConstraints.Infinity, maxHeight = constraints.maxHeight),
      )
  layout(constraints.maxWidth, placeable.height) { placeable.place(-50, 0) }
}

/** Measures its child at two widths, then places the second measurement. */
private val MEASURES_TWICE_PLACES = LayoutFunctionN { measurables, constraints ->
  measurables[0].measure(SizeConstraints(maxWidth = constraints.maxWidth / 2, maxHeight = 50))
  val placeable =
      measurables[0].measure(SizeConstraints(maxWidth = constraints.maxWidth, maxHeight = 50))
  layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
}

/** Measures its child and places nothing. */
private val PROBES_ONLY = LayoutFunctionN { measurables, constraints ->
  measurables[0].measure(SizeConstraints(maxWidth = constraints.maxWidth, maxHeight = 50))
  layout(constraints.maxWidth, 50) {}
}

/** A Primitive whose measure throws. */
private class ThrowingLeaf : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
      layoutBehavior = ThrowingBehavior,
      mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
      style = null,
  )
}

private object ThrowingBehavior : LayoutBehavior {
  override fun PrimitiveLayoutScope.layout(
      sizeConstraints: SizeConstraints,
  ): PrimitiveLayoutResult = error("thrown by the leaf's measure")
}

/** A Primitive of [width] by [height] within its constraints, mounting a plain View. */
private class FixedView(
    private val width: Int,
    private val height: Int,
) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
      layoutBehavior = FixedBehavior(width, height),
      mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
      style = null,
  )
}

private class FixedBehavior(private val width: Int, private val height: Int) : LayoutBehavior {
  override fun PrimitiveLayoutScope.layout(
      sizeConstraints: SizeConstraints,
  ): PrimitiveLayoutResult = PrimitiveLayoutResult(
      width = minOf(width, sizeConstraints.maxWidth),
      height = minOf(height, sizeConstraints.maxHeight),
  )
}
