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
@file:SuppressLint("MissingTestAnnotation")

package com.facebook.litho

import android.annotation.SuppressLint
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.editor.instances.StyleEditorInstance
import com.facebook.litho.editor.model.EditorShape
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.LayoutProps
import com.facebook.litho.layout.Measurable
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.get
import com.facebook.litho.layout.layoutProps
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.litho.widget.collection.LazyList
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.px
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val VIEWPORT = 1000
private const val CHILD = 100

@JvmInline private value class ChildOffset(val px: Int) : LayoutProps

private val NO_OFFSET = ChildOffset(0)

private fun Style.testOffset(px: Int): Style = layoutProps(ChildOffset(px))

private fun Measurable.offset(): Int = layoutProps(NO_OFFSET).px

private data class Position(val x: Int, val y: Int) : LayoutProps

private fun Style.testPosition(x: Int, y: Int): Style = layoutProps(Position(x, y))

private sealed interface Side : LayoutProps {
  object Start : Side

  object End : Side
}

private fun Style.testSide(side: Side): Style = layoutProps(side)

/** Places each child at x = the offset it carries. */
private val byOffset = LayoutFunctionN { ms, c ->
  val ps = ms.map { it.measure(SizeConstraints.exact(CHILD, CHILD)) }
  layout(c.maxWidth, CHILD) { ps.forEachIndexed { i, p -> p.place(x = ms[i].offset(), y = 0) } }
}

private fun LithoTestRule.renderAndFindContainer(component: Component): LithoLayoutResult =
    findIn(render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Column { child(component) } })

private fun findIn(testView: TestLithoView): LithoLayoutResult {
  val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
  return checkNotNull(findContainer(root as LithoLayoutResult)) { "container not found in tree" }
}

/** Depth-first, pre-order: the outermost Layout is found first. */
private fun findContainer(result: LithoLayoutResult): LithoLayoutResult? {
  if (result.node.tailComponent is Layout) return result
  for (i in 0 until result.childCount) {
    findContainer(result.getChildAt(i))?.let {
      return it
    }
  }
  return null
}

private fun box(style: Style = Style): Component =
    SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px) + style)

private class StyleHolder(@JvmField val style: Style)

private data class XState(val state: State<Int>) : LayoutProps

private fun Style.testXState(state: State<Int>): Style = layoutProps(XState(state))

private val byXState = LayoutFunctionN { ms, c ->
  val p = ms[0].measure(SizeConstraints.exact(CHILD, CHILD))
  val x = ms[0][XState::class]?.state?.value ?: 0
  layout(c.maxWidth, CHILD) { p.place(x = x, y = 0) }
}

/** Holds state whose update a test leaves pending, so the next root reconciles. */
private class Pending(private val holder: AtomicReference<State<Int>>) : KComponent() {
  override fun ComponentScope.render(): Component {
    holder.set(useState { 0 })
    return box()
  }
}

/** Carries its own state in a child's style; only the layout function reads the value. */
private class Slider(
    private val renders: AtomicInteger,
    private val holder: AtomicReference<State<Int>>,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    renders.incrementAndGet()
    val x = useState { 0 }
    holder.set(x)
    val children =
        useCached(x) {
          listOf(
              SimpleMountSpecTester(
                  style = Style.width(CHILD.px).height(CHILD.px).testXState(x),
              ),
          )
        }
    return Layout(children, layout = byXState)
  }
}

/** What a child carries in its own `Style` for the `Layout` it sits in. */
@RunWith(LithoTestRunner::class)
class LayoutPropsTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `a value set in Style reaches the layout function`() {
    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(box(Style.testOffset(10)), box(Style.testOffset(20))),
                layout = byOffset,
            ),
        )

    assertThat((0 until result.childCount).map { result.getXForChildAtIndex(it) })
        .describedAs("all zero would mean the value never arrived and every child got the default")
        .containsExactly(10, 20)
  }

  @Test
  fun `coupled values are replaced together`() {
    var seen = Position(0, 0)
    val readsPosition = LayoutFunctionN { ms, c ->
      val position = ms[0].layoutProps(Position(0, 0))
      seen = position
      val p = ms[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(c.maxWidth, c.maxHeight) { p.place(position.x, position.y) }
    }

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(
                    box(Style.testPosition(x = 10, y = 20).testPosition(x = 30, y = 40)),
                ),
                layout = readsPosition,
            ),
        )

    assertThat(seen).isEqualTo(Position(30, 40))
    assertThat(result.getXForChildAtIndex(0)).isEqualTo(30)
    assertThat(result.getYForChildAtIndex(0)).isEqualTo(40)
  }

  @Test
  fun `a child that sets nothing gets the reader's default`() {
    val result = lithoTestRule.renderAndFindContainer(Layout(listOf(box()), layout = byOffset))

    assertThat(result.getXForChildAtIndex(0)).isEqualTo(0)
  }

  @Test
  fun `the later of two values in one style wins`() {
    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(listOf(box(Style.testOffset(10).testOffset(30))), layout = byOffset),
        )

    assertThat(result.getXForChildAtIndex(0)).isEqualTo(30)
  }

  @Test
  fun `an outer component's value overrides the one it wraps`() {
    val wrapped =
        Wrapper.create(lithoTestRule.context)
            .delegate(box(Style.testOffset(10)))
            .kotlinStyle(Style.testOffset(30))
            .build()

    val result = lithoTestRule.renderAndFindContainer(Layout(listOf(wrapped), layout = byOffset))

    assertThat(result.getXForChildAtIndex(0))
        .describedAs("head wins, as it does for every other layout prop")
        .isEqualTo(30)
  }

  @Test
  fun `a missing outer value falls through to the inner component`() {
    val wrapped =
        Wrapper.create(lithoTestRule.context)
            .delegate(box(Style.testOffset(25)))
            .kotlinStyle(Style.testPosition(x = 1, y = 2))
            .build()

    val result = lithoTestRule.renderAndFindContainer(Layout(listOf(wrapped), layout = byOffset))

    assertThat(result.getXForChildAtIndex(0)).isEqualTo(25)
  }

  @Test
  fun `a value the container does not read is ignored`() {
    data class GridColumn(val index: Int) : LayoutProps

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(box(Style.layoutProps(GridColumn(7)).testOffset(15))),
                layout = byOffset,
            ),
        )

    assertThat(result.getXForChildAtIndex(0)).isEqualTo(15)
  }

  @Test
  fun `two classes with the same shape are different keys`() {
    data class OtherOffset(val px: Int) : LayoutProps
    var other: OtherOffset? = null
    val readsBoth = LayoutFunctionN { ms, c ->
      other = ms[0][OtherOffset::class]
      val p = ms[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(c.maxWidth, CHILD) { p.place(x = ms[0].offset(), y = 0) }
    }

    val result =
        lithoTestRule.renderAndFindContainer(
            Layout(
                listOf(box(Style.testOffset(15).layoutProps(OtherOffset(70)))),
                layout = readsBoth,
            ),
        )

    assertThat(result.getXForChildAtIndex(0))
        .describedAs("70 or a cast failure would mean the two classes shared one slot")
        .isEqualTo(15)
    assertThat(other).isEqualTo(OtherOffset(70))
  }

  @Test
  fun `each class has its own style item field`() {
    val fields = mutableListOf<StyleItemField>()
    Style.testOffset(1).testPosition(2, 3).testOffset(4).forEach { fields += it.field }

    assertThat(fields[0]).isNotEqualTo(fields[1])
    assertThat(fields[0].toString()).isNotEqualTo(fields[1].toString())
    assertThat(fields[0]).isEqualTo(fields[2])
    assertThat(fields[0].hashCode()).isEqualTo(fields[2].hashCode())
    assertThat(fields[0].toString()).isEqualTo(fields[2].toString())
  }

  @Test
  fun `the style editor shows each class as its own entry`() {
    val holder = StyleHolder(Style.testOffset(15).testPosition(2, 3))

    val shape =
        StyleEditorInstance.instance.read(StyleHolder::class.java.getField("style"), holder)
            as EditorShape

    assertThat(shape.value).hasSize(2)
  }

  @Test
  fun `a sealed value is stored and read under its sealed type`() {
    var seen: Side? = null
    val readsSide = LayoutFunctionN { ms, c ->
      seen = ms[0].layoutProps<Side>(Side.Start)
      val p = ms[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(c.maxWidth, CHILD) { p.place(0, 0) }
    }

    lithoTestRule.renderAndFindContainer(
        Layout(listOf(box(Style.testSide(Side.End))), layout = readsSide),
    )

    assertThat(seen)
        .describedAs("Start would mean the value was stored under its case, not under Side")
        .isEqualTo(Side.End)
  }

  /**
   * A layout prop on a Spec component lives only in its `CommonProps`, so whether a change reaches
   * the container depends on `CommonProps` equivalence. Reconciliation reuses a subtree whose
   * components compare equal, which it does when a state update is pending as the root is set.
   */
  @Test
  fun `a layout prop change on a Spec child survives reconciliation`() {
    val holder = AtomicReference<State<Int>>()
    fun root(offset: Int): Component = Column {
      child(Layout(listOf(wrapped(offset), Pending(holder)), layout = byOffset))
    }
    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { root(10) }

    lithoTestRule.act(testView) {
      // Left pending, so setting the root reconciles against the tree that is already there.
      holder.get().update(1)
      testView.setRoot(root(20))
    }
    lithoTestRule.idle()

    assertThat(findIn(testView).getXForChildAtIndex(0))
        .describedAs("10 means the change compared equal and the old child was reused")
        .isEqualTo(20)
  }

  /** A LazyList re-renders a row only if its component does not compare equal to the last one. */
  @Test
  fun `a layout prop change on a Spec child reaches a LazyList row`() {
    val seen = AtomicInteger(-1)
    // One instance for both roots, so the row differs only in the value its child carries.
    val readsOffset = LayoutFunctionN { ms, c ->
      seen.set(ms[0].offset())
      val p = ms[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(c.maxWidth, CHILD) { p.place(0, 0) }
    }
    fun ResourcesScope.root(offset: Int): Component =
        LazyList(style = Style.height(VIEWPORT.px)) {
          child(id = 1, component = Layout(listOf(wrapped(offset)), layout = readsOffset))
        }
    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { root(10) }
    lithoTestRule.idle()
    assertThat(seen.get()).describedAs("the row was laid out at all").isEqualTo(10)

    testView.setRoot { root(20) }
    lithoTestRule.idle()

    assertThat(seen.get())
        .describedAs("10 means the row compared equal and was never laid out again")
        .isEqualTo(20)
  }

  /** A Spec component carrying a [ChildOffset] in its `CommonProps`, around a fixed-size box. */
  private fun wrapped(offset: Int): Component =
      Wrapper.create(lithoTestRule.context)
          .delegate(box())
          .kotlinStyle(Style.testOffset(offset))
          .build()

  /**
   * The `State` object travels in the child's style, and only the layout function reads its value.
   * The read is recorded on the container, so the update lays it out again without a render.
   */
  @Test
  fun `a State held in a layout prop moves the child without re-rendering`() {
    val renders = AtomicInteger()
    val holder = AtomicReference<State<Int>>()
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column { child(Slider(renders, holder)) }
        }
    val rendersBefore = renders.get()

    lithoTestRule.act(testView) { holder.get().update(50) }
    lithoTestRule.idle()

    assertThat(findIn(testView).getXForChildAtIndex(0))
        .describedAs("0 means the read was not recorded and layout did not run again")
        .isEqualTo(50)
    assertThat(renders.get()).describedAs("only layout read the state").isEqualTo(rendersBefore)
  }

  @Test
  fun `a Layout's own layout props are read by its parent, not by itself`() {
    var innerSaw = -1
    val inner = Layout(
        listOf(box()),
        style = Style.width(CHILD.px).height(CHILD.px).testOffset(40),
        deps = emptyArray(),
        layout =
            LayoutFunctionN { ms, _ ->
              innerSaw = ms[0].offset()
              val p = ms[0].measure(SizeConstraints.exact(CHILD, CHILD))
              layout(CHILD, CHILD) { p.place(0, 0) }
            },
    )

    val result = lithoTestRule.renderAndFindContainer(Layout(listOf(inner), layout = byOffset))

    assertThat(result.getXForChildAtIndex(0)).describedAs("the parent read it").isEqualTo(40)
    assertThat(innerSaw).describedAs("its own child did not carry it").isEqualTo(0)
  }
}
