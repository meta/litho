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

package com.facebook.samples.litho.documentation.customlayout

import android.graphics.Color
import android.graphics.Rect
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Layout
import com.facebook.litho.LithoLayoutResult
import com.facebook.litho.SizeSpec
import com.facebook.litho.Style
import com.facebook.litho.TestComponentScope
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.widget.SolidColor
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import java.util.concurrent.atomic.AtomicInteger

/** A child a test finds by [name] in the laid-out tree. */
class Named(val name: String, private val content: Component) : KComponent() {
  override fun ComponentScope.render(): Component = content
}

/** A child that renders nothing: a `Layout` keeps its slot, at 0 x 0. */
class RendersNothing : KComponent() {
  override fun ComponentScope.render(): Component? = null
}

/** A child named [name] of exactly [width] by [height] px. */
fun box(name: String, width: Int, height: Int): Component =
    Named(name, SolidColor(color = Color.BLACK, style = Style.width(width.px).height(height.px)))

/**
 * A child named [name] with no width or height of its own, wrapping a [width] x [height] px box: a
 * minimum stretches it, where it wouldn't stretch a [box].
 */
fun wrapping(name: String, width: Int, height: Int): Component = Named(
    name,
    Column {
      child(SolidColor(color = Color.BLACK, style = Style.width(width.px).height(height.px)))
    },
)

/** A child named [name] showing [text] at 32 px. */
fun LithoTestRule.text(name: String, text: String): Component =
    Named(name, with(TestComponentScope(context)) { Text(text = text, textSize = 32.px) })

/**
 * Wraps [content] in a `Layout` whose function counts its runs in [runs] in a pass: about one per
 * set of constraints [content] is measured under. A repeat under the same constraints is reused,
 * and one under an exact size equal to what it declared isn't run again.
 */
class CountingMeasures(
    private val content: Component,
    private val runs: AtomicInteger,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Layout(content) { child, constraints ->
        runs.incrementAndGet()
        val placeable = child.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }
}

/** The width a sample is given by its parent: exactly, at most, or with no limit. */
sealed class Room {
  data class Exactly(val width: Int) : Room()

  data class AtMost(val width: Int) : Room()

  data object Unbounded : Room()
}

/**
 * A laid-out sample: its size, and the bounds of each [Named] child it placed, relative to the
 * sample, in the order it placed them.
 */
data class LaidOut(val width: Int, val height: Int, val children: List<Pair<String, Rect>>) {
  operator fun get(name: String): Rect =
      checkNotNull(children.firstOrNull { (childName, _) -> childName == name }?.second) {
        "$name was not placed; placed: ${children.map { (childName, _) -> childName }}"
      }

  val names: List<String>
    get() = children.map { (name, _) -> name }
}

/**
 * Lays out [sample] as the only child of a `Column` in [room], left to right or, with [rtl], right
 * to left: a `Column` that stretches its child gives it an exact width, and one that aligns it to
 * the start gives it at most that width.
 */
fun LithoTestRule.layOut(sample: Component, room: Room, rtl: Boolean = false): LaidOut =
    collect(renderInRoom(sample, room, rtl))

fun LithoTestRule.renderInRoom(sample: Component, room: Room, rtl: Boolean = false): TestLithoView {
  val widthSpec =
      when (room) {
        is Room.Exactly -> SizeSpec.makeSizeSpec(room.width, SizeSpec.EXACTLY)
        is Room.AtMost -> SizeSpec.makeSizeSpec(room.width, SizeSpec.EXACTLY)
        Room.Unbounded -> SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
      }
  return render(
      widthSpec = widthSpec,
      heightSpec = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
  ) {
    inRoom(sample, room, rtl)
  }
}

/** The `Column` [renderInRoom] puts [sample] in, to set as a new root of the same view. */
fun inRoom(sample: Component, room: Room, rtl: Boolean = false): Component = Column(
    alignItems = if (room is Room.AtMost) YogaAlign.FLEX_START else YogaAlign.STRETCH,
    style = Style.layoutDirection(if (rtl) YogaDirection.RTL else YogaDirection.LTR),
) {
  child(sample)
}

/**
 * Lays out [sample] once, as the root, [width] px wide at most: one layout pass, with no parent to
 * measure it more than once, for counting how often it measures its children.
 */
fun LithoTestRule.layOutOnceAsRoot(sample: Component, width: Int): LaidOut {
  val testView = createTestLithoView()
  testView.setRootAndSizeSpecSync(
      sample,
      SizeSpec.makeSizeSpec(width, SizeSpec.AT_MOST),
      SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
  )
  return collectFrom(checkNotNull(testView.currentRootNode) { "nothing was laid out" })
}

/** Lays out [sample] as the root, exactly [width] by [height] px. */
fun LithoTestRule.layOutAsRootExactly(sample: Component, width: Int, height: Int): LaidOut {
  val testView = render(
      widthSpec = SizeSpec.makeSizeSpec(width, SizeSpec.EXACTLY),
      heightSpec = SizeSpec.makeSizeSpec(height, SizeSpec.EXACTLY),
  ) {
    sample
  }
  return collectFrom(checkNotNull(testView.currentRootNode) { "nothing was laid out" })
}

/** The sample laid out in [testView], as [layOut] reports it. */
fun collect(testView: TestLithoView): LaidOut =
    collectFrom(checkNotNull(testView.currentRootNode) { "nothing was laid out" }.getChildAt(0))

private fun collectFrom(sample: LithoLayoutResult): LaidOut {
  val children = ArrayList<Pair<String, Rect>>()
  for (i in 0 until sample.childCount) {
    val child = sample.getChildAt(i)
    val left = sample.getXForChildAtIndex(i)
    val top = sample.getYForChildAtIndex(i)
    children.add(nameOf(child) to Rect(left, top, left + child.width, top + child.height))
  }
  return LaidOut(sample.width, sample.height, children)
}

/** The name of the [Named] component [result] was laid out for, or `"?"`. */
fun nameOf(result: LithoLayoutResult): String =
    result.node.scopedComponentInfos.firstNotNullOfOrNull { info ->
      (info.component as? Named)?.name
    } ?: "?"
