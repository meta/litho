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

package com.facebook.litho.visibility

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.KComponent
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.LithoView
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.primitives.FixedSizeLayoutBehavior
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.rendercore.visibility.VisibilityBoundsTransformer
import com.facebook.rendercore.visibility.VisibilityOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class VisibilityBoundsTransformerScrollTest {

  private val occlusion = BottomBandOcclusion()

  @Rule
  @JvmField
  val lithoTestRule = LithoTestRule(
      componentsConfiguration =
          ComponentsConfiguration.defaultInstance.copy(visibilityBoundsTransformer = occlusion),
  )

  @Test
  fun `a row that one scroll frame carries from clear to fully under the band stops counting as visible`() {
    occlusion.bandTopPx = 80
    var isVisible = false
    val row =
        renderRowInList(Style.onVisible { isVisible = true }.onInvisible { isVisible = false })
    assertThat(isVisible).isTrue()

    row.lithoView.offsetTopAndBottom(80)

    assertThat(isVisible).isFalse()
  }

  @Test
  fun `a row that one scroll frame carries from fully under the band to clear is fully seen`() {
    occlusion.bandTopPx = 80
    var fullImpressions = 0
    val row = renderRowInList(Style.onFullImpression { fullImpressions++ })
    row.lithoView.offsetTopAndBottom(80)
    val fullImpressionsUnderBand = fullImpressions

    row.lithoView.offsetTopAndBottom(-80)

    assertThat(fullImpressions).isEqualTo(fullImpressionsUnderBand + 1)
  }

  @Test
  fun `rows and their nested trees report the same events whether a scroll step skips them or not`() {
    occlusion.bandTopPx = 60
    val skippingList = ScrollingList(runsPassAtEveryStep = false)
    val passingList = ScrollingList(runsPassAtEveryStep = true)

    for (dy in List(30) { 3 } + List(30) { -3 }) {
      skippingList.scrollBy(dy)
      passingList.scrollBy(dy)
    }

    assertThat(skippingList.events).isEqualTo(passingList.events)
    assertThat(skippingList.events)
        .contains("row 1 nested invisible SCROLL", "row 1 nested visible SCROLL")
    assertThat(skippingList.skippedSteps.asList()).allMatch { it > 0 }
  }

  @Test
  fun `with incremental mount off, a tree nested in a row without outputs stops counting as visible once the band covers the row again`() {
    occlusion.bandTopPx = 0
    var isNestedVisible = false
    val config =
        ComponentsConfiguration.defaultInstance.copy(
            visibilityBoundsTransformer = occlusion,
            incrementalMountEnabled = false,
        )
    val list = FrameLayout(lithoTestRule.context.androidContext)
    list.measure(exactly(100), exactly(100))
    list.layout(0, 0, 100, 100)
    val row =
        lithoTestRule.createTestLithoView(
            componentTree =
                ComponentTree.create(lithoTestRule.context).componentsConfiguration(config).build(),
        )
    list.addView(row.lithoView)
    lithoTestRule.render(lithoView = row.lithoView, widthPx = 100, heightPx = 20) {
      Column(style = Style.width(100.px).height(20.px)) {
        child(Row(style = Style.width(100.px).height(10.px)))
        child(
            NestedTree(
                Row(
                    style =
                        Style.width(100.px)
                            .height(10.px)
                            .onVisible { isNestedVisible = true }
                            .onInvisible { isNestedVisible = false },
                ),
            ),
        )
      }
    }
    occlusion.bandTopPx = 60
    row.lithoView.offsetTopAndBottom(-5)
    assertThat(isNestedVisible).isTrue()

    occlusion.bandTopPx = 0
    row.lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(isNestedVisible).isFalse()
  }

  @Test
  fun `a tree that a relayout moves above a covered row reports visible once it rises past the band, in a list that doesn't clip rows`() {
    occlusion.bandTopPx = 60
    var isNestedVisible = false
    val nestedRow = Row(
        style =
            Style.width(100.px)
                .height(10.px)
                .onVisible { isNestedVisible = true }
                .onInvisible { isNestedVisible = false },
    )
    val row = renderRowInList(nestedTree = NestedTree(nestedRow))
    val abovePx = -5
    row.setRoot(
        Column(style = Style.width(100.px).height(20.px)) {
          child(NestedTree(nestedRow, style = Style.margin(top = abovePx.px)))
        },
    )
    (row.lithoView.parent as ViewGroup).clipChildren = false
    row.lithoView.offsetTopAndBottom(72)
    assertThat(isNestedVisible).isFalse()

    row.lithoView.offsetTopAndBottom(-9)

    assertThat(isNestedVisible).isTrue()
  }

  /** Renders a 100 x 20 px row, with [nestedTree] at its top, at the top of a 100 x 100 px list. */
  private fun renderRowInList(style: Style = Style, nestedTree: Component? = null): TestLithoView {
    val list = FrameLayout(lithoTestRule.context.androidContext)
    list.measure(exactly(100), exactly(100))
    list.layout(0, 0, 100, 100)
    val row = lithoTestRule.createTestLithoView()
    list.addView(row.lithoView)
    return lithoTestRule.render(lithoView = row.lithoView, widthPx = 100, heightPx = 20) {
      Column(style = Style.width(100.px).height(20.px) + style) { child(nestedTree) }
    }
  }

  /**
   * A 100 x 100 px list of four rows, 25 px apart. Rows alternate with and without visibility
   * outputs of their own, and each holds a nested tree. With [runsPassAtEveryStep], every row runs
   * its pass after each step, as a row that can't skip one does.
   */
  private inner class ScrollingList(private val runsPassAtEveryStep: Boolean) {
    val events = mutableListOf<String>()
    private val list =
        FrameLayout(lithoTestRule.context.androidContext).apply {
          measure(exactly(100), exactly(100))
          layout(0, 0, 100, 100)
        }
    private val rows =
        List(4) { index ->
          val row = CountingLithoView(lithoTestRule.context)
          list.addView(row)
          lithoTestRule.render(lithoView = row, widthPx = 100, heightPx = 20) {
            ListRow("row $index", hasOutputs = index % 2 == 0, events)
          }
          row.offsetTopAndBottom(index * 25)
          row
        }

    /** How many of the steps that left each row visible started no pass for it. */
    val skippedSteps = IntArray(rows.size)

    fun scrollBy(dy: Int) {
      rows.forEachIndexed { index, row ->
        val passes = row.passes
        row.offsetTopAndBottom(dy)
        val visibleRect = Rect()
        if (row.getLocalVisibleRect(visibleRect)) {
          if (row.passes == passes) {
            skippedSteps[index]++
          }
          if (runsPassAtEveryStep) {
            row.notifyVisibleBoundsChanged(visibleRect, true, VisibilityChangeReason.SCROLL)
          }
        }
      }
    }
  }

  /** A [LithoView] that counts the visible-bounds passes it starts. */
  private class CountingLithoView(context: ComponentContext) : LithoView(context) {
    var passes = 0
      private set

    override fun notifyVisibleBoundsChanged(
        visibleRect: Rect,
        processVisibilityOutputs: Boolean,
        reason: VisibilityChangeReason,
    ) {
      passes++
      super.notifyVisibleBoundsChanged(visibleRect, processVisibilityOutputs, reason)
    }
  }

  /** A 100 x 20 px row whose bottom half is a nested tree with visibility outputs. */
  private class ListRow(
      private val id: String,
      private val hasOutputs: Boolean,
      private val events: MutableList<String>,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val size = Style.width(100.px).height(20.px)
      return Column(style = if (hasOutputs) size + loggingVisibility(id, events) else size) {
        child(Row(style = Style.width(100.px).height(10.px)))
        child(
            NestedTree(
                Row(
                    style =
                        Style.width(100.px).height(10.px) + loggingVisibility("$id nested", events),
                ),
            ),
        )
      }
    }
  }

  /** Mounts [content] in a [LithoView] of its own. */
  private class NestedTree(private val content: Component, private val style: Style? = null) :
      PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive {
      val tree = useState { ComponentTree.createNestedComponentTree(context, content).build() }
      return LithoPrimitive(
          layoutBehavior = FixedSizeLayoutBehavior(100.px, 10.px),
          mountBehavior =
              MountBehavior(ViewAllocator { context -> LithoView(context) }) {
                doesMountRenderTreeHosts = true
                bind(tree.value) { lithoView ->
                  lithoView.componentTree = tree.value
                  onUnbind { lithoView.componentTree = null }
                }
              },
          style = style,
      )
    }
  }

  /** Occludes everything in the list below [bandTopPx]. */
  private class BottomBandOcclusion : VisibilityBoundsTransformer {
    var bandTopPx = 0
    private val listOffset = Point()

    override fun getTransformedLocalVisibleRect(host: ViewGroup, outRect: Rect) {
      if (!host.getGlobalVisibleRect(outRect, listOffset)) {
        outRect.setEmpty()
        return
      }
      outRect.bottom = minOf(outRect.bottom, bandTopPx)
      if (outRect.isEmpty) {
        outRect.setEmpty()
        return
      }
      outRect.offset(-listOffset.x, -listOffset.y)
    }

    override fun getViewportRect(view: View, outRect: Rect) {
      view.getLocalVisibleRect(outRect)
    }

    override fun shouldUseTransformedVisibleRect(visibilityOutput: VisibilityOutput): Boolean = true

    override fun shouldRecordTransformedVisibleRectAtEveryPass(): Boolean = true
  }

  private companion object {
    /** Logs every visibility event of the component, with what it reports, to [events]. */
    private fun loggingVisibility(id: String, events: MutableList<String>): Style =
        Style.onVisible { events += "$id visible ${it.reason}" }
            .onInvisible { events += "$id invisible ${it.reason}" }
            .onFocusedVisible { events += "$id focused ${it.reason}" }
            .onUnfocusedVisible { events += "$id unfocused ${it.reason}" }
            .onFullImpression { events += "$id full impression ${it.reason}" }
            .onVisibilityChanged {
              events +=
                  "$id changed top ${it.visibleTop} left ${it.visibleLeft} " +
                      "size ${it.visibleWidth}x${it.visibleHeight} " +
                      "percent ${it.percentVisibleWidth}x${it.percentVisibleHeight} " +
                      "root ${it.rootHostViewWidth}x${it.rootHostViewHeight} ${it.reason}"
            }
  }
}
