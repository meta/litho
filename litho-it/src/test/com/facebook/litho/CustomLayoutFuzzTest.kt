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
@file:Suppress("KComponentUncachedAllocationDetector")

package com.facebook.litho

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.facebook.litho.accessibility.contentDescription
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.Placeable
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.stateupdates.ComponentWithMeasureCall
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.wrapInView
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope as PrimitiveLayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import java.util.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Differential fuzzing of custom `Layout`s.
 *
 * Random trees mix `Layout`s with several kinds of layout function, flexbox `Row`s and `Column`s,
 * `Row`s aligning their children by baseline, fixed leaves, leaves whose width is state read during
 * render, leaves whose width is state read during layout, size-dependent deferred content
 * (`RenderWithConstraints`), and content measured through `Component.measure` beside a taller
 * sibling. Each tree is driven through random width changes and state updates.
 *
 * Two oracles:
 * - **Incremental equals from scratch.** After every step, the tree laid out incrementally must
 *   mount every view where a fresh render of the same tree, with the same state values, at the same
 *   width, mounts it. A stale cache hit, a lost state read or a mis-carried answer shows up as a
 *   difference, whatever the layout function does.
 * - **No exception** in any step.
 *
 * Shadow validation is on while it runs, so each `Layout` child whose layout holds a subtree copied
 * whole from the last pass is also checked against a fresh layout, on sizes, and the positions of
 * every descendant that is not deferred content; deferred content is compared by its holder's size
 * only. A difference throws.
 *
 * Four modes: cross-pass reuse off and on (a no-op switch until the stack's cross-pass entry
 * exists), right-to-left, and read tracking off. Deferred content under reuse is the same generator
 * in [Mode.CROSS_PASS_ON]: its trees hold `RenderWithConstraints` inside `Layout` children.
 *
 * Seeds known to fail on the code below are listed in [KNOWN_FAILURES], by mode. The test fails on
 * a new failure, and also when a known one passes, so the list only shrinks deliberately.
 */
@RunWith(LithoTestRunner::class)
class CustomLayoutFuzzTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var validationWas = false

  private val runTag: String = Integer.toHexString(System.identityHashCode(this))

  /** Builds every `Layout` as a `Column`: a failure that persists is not a custom-layout one. */
  private var withoutLayouts = false

  private var mode = Mode.CROSS_PASS_OFF

  enum class Mode {
    CROSS_PASS_OFF,
    /**
     * Reuses layouts across passes; a no-op until the cross-pass entry exists (`crossPassReuse`).
     */
    CROSS_PASS_ON,
    RTL,
  }

  @Before
  fun saveFlags() {
    validationWas = ComponentsConfiguration.customLayoutShadowValidation
    ComponentsConfiguration.customLayoutShadowValidation = true
  }

  @After
  fun restoreFlags() {
    ComponentsConfiguration.customLayoutShadowValidation = validationWas
    crossPassReuse = false
  }

  @Test
  fun `incremental layout matches a fresh layout, reuse off`() {
    fuzz(Mode.CROSS_PASS_OFF)
  }

  @Test
  fun `incremental layout matches a fresh layout, reuse on`() {
    fuzz(Mode.CROSS_PASS_ON)
  }

  @Test
  fun `incremental layout matches a fresh layout, right to left`() {
    fuzz(Mode.RTL)
  }

  private fun fuzz(mode: Mode) {
    this.mode = mode
    crossPassReuse = mode == Mode.CROSS_PASS_ON
    val reuse = mode.name
    val failures = ArrayList<String>()
    val failedSeeds = HashSet<Int>()
    val seeds = Integer.getInteger("litho.fuzz.seeds", SEEDS)
    val first = Integer.getInteger("litho.fuzz.first", 0)
    val maxFailures = Integer.getInteger("litho.fuzz.maxFailures", MAX_FAILURES)
    for (seed in first until first + seeds) {
      var failure = runSeed(seed)
      if (failure != null) {
        // The same seed with every Layout a Column: if that fails too, it isn't a Layout problem.
        withoutLayouts = true
        nextId = 0
        val control = runSeed(seed)
        withoutLayouts = false
        if (control != null) {
          println("FUZZCONTROL|reuse=$reuse|$control")
          failure = null
        }
      }
      if (failure != null) {
        failedSeeds.add(seed)
        if (seed !in KNOWN_FAILURES.getValue(mode)) {
          failures.add(failure)
        }
        println("FUZZFAIL|reuse=$reuse|$failure")
        if (failures.size >= maxFailures) break
      }
    }
    val nowPassing =
        KNOWN_FAILURES.getValue(mode).filter {
          it in first until first + seeds && it !in failedSeeds
        }
    println(
        "FUZZDONE|reuse=$reuse|seeds=$seeds|failures=${failedSeeds.size}|known=${failedSeeds.filter { it in KNOWN_FAILURES.getValue(mode) }}|new=${failures.size}",
    )
    assertThat(failures).describedAs("new fuzz failures (first $maxFailures)").isEmpty()
    assertThat(nowPassing)
        .describedAs("known failures that pass now: remove them from KNOWN_FAILURES[$mode]")
        .isEmpty()
  }

  /** Null if the seed passes; otherwise a one-line description of the first difference. */
  private fun runSeed(seed: Int): String? {
    val random = Random(seed.toLong())
    nextId = 0
    val model = generate(random, depth = 0)
    val world = World()
    model.collectState(world, random)
    var width = WIDTHS[random.nextInt(WIDTHS.size)]
    val steps = ArrayList<String>()
    val view: TestLithoView
    try {
      view = lithoTestRule.render(widthPx = width, heightPx = VIEWPORT) { top(model, world) }
    } catch (e: Throwable) {
      return "seed=$seed|step=initial|exception=${describe(e)}|tree=${model.describe()}"
    }
    for (step in 0 until STEPS) {
      // Only state a mounted component holds: updating one whose component is no longer rendered
      // (a deferred branch not shown now) changes nothing, but a fresh render would start from it.
      val mounted = frames(view).keys
      val stateIds = world.states.keys.filter { "n$it" in mounted }.sorted()
      val op =
          if (stateIds.isEmpty() || random.nextInt(3) == 0) {
            width = WIDTHS[random.nextInt(WIDTHS.size)]
            "width=$width"
          } else {
            val id = stateIds[random.nextInt(stateIds.size)]
            val value = STATE_WIDTHS[random.nextInt(STATE_WIDTHS.size)]
            world.values[id] = value
            "state[$id]=$value"
          }
      steps.add(op)
      if (TRACE) println("FUZZTRACE|" + runTag + "|--- step $op")
      try {
        if (op.startsWith("width=")) {
          view.setSizePx(width, VIEWPORT).measure().layout()
        } else {
          val id = op.substringAfter('[').substringBefore(']').toInt()
          val value = world.values.getValue(id)
          lithoTestRule.act(view) { world.states.getValue(id).update(value) }
          lithoTestRule.idle()
          view.measure().layout()
        }
      } catch (e: Throwable) {
        return "seed=$seed|steps=$steps|exception=${describe(e)}|tree=${model.describe()}"
      }
      val incremental = frames(view)
      if (TRACE) println("FUZZTRACE|" + runTag + "|incremental " + incremental)
      if (TRACE) println("FUZZTRACE|" + runTag + "|--- fresh render")
      val fresh =
          try {
            val freshWorld = World().also { it.values.putAll(world.values) }
            frames(
                lithoTestRule.render(widthPx = width, heightPx = VIEWPORT) {
                  top(model, freshWorld)
                },
            )
          } catch (e: Throwable) {
            return "seed=$seed|steps=$steps|fresh-exception=${describe(e)}|tree=${model.describe()}"
          }
      if (TRACE) println("FUZZTRACE|" + runTag + "|fresh " + fresh)
      if (incremental != fresh) {
        val keys = (incremental.keys + fresh.keys).sorted()
        val diff =
            keys
                .filter { incremental[it] != fresh[it] }
                .take(4)
                .joinToString(",") {
                  "$it:${incremental[it]?.toShortString()}!=${fresh[it]?.toShortString()}"
                }
        return "seed=$seed|steps=$steps|diff=$diff|tree=${model.describe()}"
      }
    }
    return null
  }

  private fun describe(e: Throwable): String {
    var t: Throwable = e
    while (t.cause != null && t.cause !== t && t.message?.contains("already placed") != true) {
      t = t.cause!!
    }
    return "${t.javaClass.simpleName}:${t.message?.take(Integer.getInteger("litho.fuzz.messageLength", 140))}"
  }

  // ------------------------------------------------------------------------------------- model

  private sealed class FNode {
    abstract val id: Int

    open fun collectState(world: World, random: Random) {}

    abstract fun describe(): String
  }

  private class FLeaf(override val id: Int, val w: Int, val h: Int) : FNode() {
    override fun describe() = "Leaf$id(${w}x$h)"
  }

  /** A leaf whose width is state its component reads while rendering. */
  private class FRenderState(override val id: Int, val h: Int) : FNode() {
    override fun collectState(world: World, random: Random) {
      world.values[id] = STATE_WIDTHS[random.nextInt(STATE_WIDTHS.size)]
    }

    override fun describe() = "RenderState$id"
  }

  /** A leaf whose width is state read only while it is laid out, inside a Column. */
  private class FLayoutState(override val id: Int, val h: Int, val bare: Boolean) : FNode() {
    override fun collectState(world: World, random: Random) {
      world.values[id] = STATE_WIDTHS[random.nextInt(STATE_WIDTHS.size)]
    }

    override fun describe() = "LayoutState$id${if (bare) "" else "InColumn"}"
  }

  private class FFlex(override val id: Int, val row: Boolean, val children: List<FNode>) : FNode() {
    override fun collectState(world: World, random: Random) {
      children.forEach { it.collectState(world, random) }
    }

    override fun describe() =
        (if (row) "Row" else "Column") + "[" + children.joinToString(",") { it.describe() } + "]"
  }

  /** A `Row` aligning its children by their first baselines. */
  private class FBaselineRow(override val id: Int, val children: List<FNode>) : FNode() {
    override fun collectState(world: World, random: Random) {
      children.forEach { it.collectState(world, random) }
    }

    override fun describe() = "BaselineRow[" + children.joinToString(",") { it.describe() } + "]"
  }

  private class FLayout(override val id: Int, val kind: Int, val children: List<FNode>) : FNode() {
    override fun collectState(world: World, random: Random) {
      children.forEach { it.collectState(world, random) }
    }

    override fun describe() =
        "Layout${KIND_NAMES[kind]}[" + children.joinToString(",") { it.describe() } + "]"
  }

  /** Size-dependent content: one child at or under the threshold, another above it. */
  private class FDeferred(
      override val id: Int,
      val threshold: Int,
      val narrow: FNode,
      val wide: FNode,
  ) : FNode() {
    override fun collectState(world: World, random: Random) {
      narrow.collectState(world, random)
      wide.collectState(world, random)
    }

    override fun describe() = "Deferred<=${threshold}(${narrow.describe()}|${wide.describe()})"
  }

  /** Content measured through `Component.measure` at an exact size, beside a taller sibling. */
  private class FMeasureCall(
      override val id: Int,
      val inner: FNode,
      val size: Int,
      val sibling: Int,
  ) : FNode() {
    override fun collectState(world: World, random: Random) {
      inner.collectState(world, random)
    }

    override fun describe() = "MeasureCall$size+$sibling(${inner.describe()})"
  }

  private class World {
    val values = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    // Renders may run off the test thread.
    val states = java.util.concurrent.ConcurrentHashMap<Int, State<Int>>()
  }

  private var nextId = 0

  /**
   * [inMeasureCall]: under content cached by `Component.measure`. No state is generated there:
   * Litho's measured-result cache keeps the content's old size after a state update, with or
   * without a `Layout` (a bug outside custom layout).
   */
  private fun generate(random: Random, depth: Int, inMeasureCall: Boolean = false): FNode {
    val id = nextId++
    val leafy = depth >= MAX_DEPTH || random.nextInt(10) < 3 + depth * 2
    if (leafy) {
      if (inMeasureCall) {
        // No state under content cached by `Component.measure`: see [generate].
        return FLeaf(id, 20 + random.nextInt(8) * 20, 20 + random.nextInt(3) * 10)
      }
      return when (random.nextInt(4)) {
        0 -> FRenderState(id, 20 + random.nextInt(3) * 10)
        1 -> FLayoutState(id, 20 + random.nextInt(3) * 10, bare = random.nextBoolean())
        else -> FLeaf(id, 20 + random.nextInt(8) * 20, 20 + random.nextInt(3) * 10)
      }
    }
    fun kids(min: Int, max: Int) =
        List(min + random.nextInt(max - min + 1)) { generate(random, depth + 1, inMeasureCall) }
    return when (random.nextInt(11)) {
      0,
      1 -> FFlex(id, row = random.nextBoolean(), children = kids(1, 3))
      4 -> FBaselineRow(id, kids(2, 3))
      2 ->
          FDeferred(
              id,
              300 + random.nextInt(4) * 100,
              generate(random, depth + 1, inMeasureCall),
              generate(random, depth + 1, inMeasureCall),
          )
      3 ->
          FMeasureCall(
              id,
              generate(random, depth + 1, inMeasureCall = true),
              100 + random.nextInt(3) * 50,
              150 + random.nextInt(3) * 50,
          )
      else -> FLayout(id, random.nextInt(KINDS.size), kids(1, 3))
    }
  }

  // ------------------------------------------------------------------------------------- build

  /** The generated tree, right to left in [Mode.RTL]. */
  private fun top(model: FNode, world: World): Component =
      if (mode == Mode.RTL) {
        Column(style = Style.layoutDirection(YogaDirection.RTL)) { child(build(model, world)) }
      } else {
        build(model, world)
      }

  private fun build(node: FNode, world: World): Component =
      when (node) {
        is FLeaf -> tagged(node.id, Style.width(node.w.px).height(node.h.px))
        is FRenderState -> RenderStateLeaf(node.id, node.h, world)
        is FLayoutState -> LayoutStateLeaf(node.id, node.h, node.bare, world)
        is FFlex ->
            if (node.row) Row { node.children.forEach { child(build(it, world)) } }
            else Column { node.children.forEach { child(build(it, world)) } }
        is FBaselineRow ->
            Row(alignItems = YogaAlign.BASELINE) {
              node.children.forEach { child(build(it, world)) }
            }
        is FLayout ->
            if (withoutLayouts) Column { node.children.forEach { child(build(it, world)) } }
            else Layout(node.children.map { build(it, world) }, layout = KINDS[node.kind])
        is FDeferred ->
            RenderWithConstraints { c ->
              val narrow = c.hasBoundedWidth && c.maxWidth <= node.threshold
              if (TRACE) {
                println(
                    "FUZZTRACE|" +
                        runTag +
                        "|deferred" +
                        node.id +
                        " under " +
                        c +
                        " -> " +
                        (if (narrow) "narrow" else "wide"),
                )
              }
              // Each branch under a container of its own type: the same component in both, at the
              // same position, would otherwise share a key, and so its state.
              if (narrow) Row { child(build(node.narrow, world)) }
              else Column { child(build(node.wide, world)) }
            }
        is FMeasureCall ->
            Row {
              child(
                  ComponentWithMeasureCall.create(lithoTestRule.context)
                      .component(build(node.inner, world))
                      .widthSpec(SizeSpec.makeSizeSpec(node.size, SizeSpec.EXACTLY))
                      .heightSpec(SizeSpec.makeSizeSpec(node.size, SizeSpec.EXACTLY))
                      .shouldCacheResult(true)
                      .build(),
              )
              child(Column(style = Style.width(10.px).height(node.sibling.px)))
            }
      }

  private class RenderStateLeaf(private val id: Int, private val h: Int, private val world: World) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useState { world.values.getValue(id) }
      world.states[id] = state
      return tagged(id, Style.width(state.value.px).height(h.px))
    }
  }

  private class LayoutStateLeaf(
      private val id: Int,
      private val h: Int,
      private val bare: Boolean,
      private val world: World,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useState { world.values.getValue(id) }
      world.states[id] = state
      val leaf = WidthFromLayoutState(state, h, if (bare) "n$id" else null)
      return if (bare) leaf
      else Column(style = Style.wrapInView().contentDescription("n$id")) { child(leaf) }
    }
  }

  // ------------------------------------------------------------------------------------- frames

  /**
   * Every tagged view's frame, in the LithoView's coordinates, if the viewport shows it:
   * incremental mount keeps a view that moved out of view mounted, where a fresh render never
   * mounts it.
   */
  private fun frames(view: TestLithoView): Map<String, Rect> {
    val out = HashMap<String, Rect>()
    val viewport = Rect(0, 0, view.lithoView.width, view.lithoView.height)
    fun walk(v: View) {
      val tag = v.contentDescription?.toString()
      if (tag != null && tag.startsWith("n")) {
        val rect = Rect(0, 0, v.width, v.height)
        view.lithoView.offsetDescendantRectToMyCoords(v, rect)
        if (Rect.intersects(rect, viewport)) {
          out[tag] = rect
        }
      }
      if (v is ViewGroup) {
        for (i in 0 until v.childCount) walk(v.getChildAt(i))
      }
    }
    walk(view.lithoView)
    return out
  }

  private companion object {
    /**
     * Whether layouts are reused across passes in [Mode.CROSS_PASS_ON]: a switch for the stack's
     * cross-pass entry to point at its flag. Until then it changes nothing.
     */
    var crossPassReuse: Boolean = false

    /**
     * Seeds known to fail on the code below, by mode, each with its reason. A listed seed that
     * passes fails the test.
     */
    val KNOWN_FAILURES: Map<Mode, Set<Int>> = mapOf(
        Mode.CROSS_PASS_OFF to emptySet(),
        Mode.CROSS_PASS_ON to emptySet(),
        Mode.RTL to emptySet(),
    )

    const val SEEDS = 400
    val TRACE: Boolean = java.lang.Boolean.getBoolean("litho.fuzz.trace")
    const val STEPS = 5
    const val MAX_DEPTH = 3
    const val MAX_FAILURES = 5
    const val VIEWPORT = 4000
    val WIDTHS = intArrayOf(240, 360, 480, 720, 1080)
    val STATE_WIDTHS = intArrayOf(20, 60, 140, 260)

    /** The container's width if bounded, else its widest child's: never an unbounded size. */
    fun declaredWidth(c: SizeConstraints, ps: List<Placeable>): Int =
        if (c.hasBoundedWidth) c.maxWidth else maxOf(c.minWidth, ps.maxOfOrNull { it.width } ?: 0)

    fun tagged(id: Int, style: Style): Component =
        Column(style = style.wrapInView().contentDescription("n$id"))

    /** Children side by side, each measured unbounded across. */
    val SIDE_BY_SIDE = LayoutFunctionN { ms, c ->
      val ps = ms.map {
        it.measure(SizeConstraints(maxWidth = SizeConstraints.Infinity, maxHeight = 400))
      }
      val w = ps.sumOf { it.width }
      val h = ps.maxOfOrNull { it.height } ?: 0
      layout(if (c.hasBoundedWidth) c.maxWidth.coerceAtLeast(c.minWidth) else w, h) {
        var x = 0
        for (p in ps) {
          p.place(x, 0)
          x += p.width
        }
      }
    }

    /** Children stacked, each measured at most the container's width. */
    val STACKED = LayoutFunctionN { ms, c ->
      val ps = ms.map { it.measure(SizeConstraints(maxWidth = c.maxWidth, maxHeight = 400)) }
      layout(declaredWidth(c, ps), ps.sumOf { it.height }) {
        var y = 0
        for (p in ps) {
          p.place(0, y)
          y += p.height
        }
      }
    }

    /** Children in a line, each placed only if it still fits: the rest are left out. */
    val PLACES_IF_FITS = LayoutFunctionN { ms, c ->
      val ps = ms.map {
        it.measure(SizeConstraints(maxWidth = SizeConstraints.Infinity, maxHeight = 400))
      }
      val limit = if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE
      layout(declaredWidth(c, ps), ps.maxOfOrNull { it.height } ?: 0) {
        var x = 0
        for (p in ps) {
          if (x + p.width <= limit) {
            p.place(x, 0)
            x += p.width
          }
        }
      }
    }

    /** Each child measured unbounded, then exactly at that size, the second placed. */
    val MEASURE_TWICE = LayoutFunctionN { ms, c ->
      val ps = ms.map { m ->
        val natural =
            m.measure(SizeConstraints(maxWidth = SizeConstraints.Infinity, maxHeight = 400))
        m.measure(SizeConstraints.exact(natural.width, natural.height))
      }
      layout(declaredWidth(c, ps), ps.sumOf { it.height }) {
        var y = 0
        for (p in ps) {
          p.place(0, y)
          y += p.height
        }
      }
    }

    /** Each child tried narrow and wide; the narrow one placed on narrow containers. */
    val TRY_NARROW_OR_WIDE = LayoutFunctionN { ms, c ->
      val narrowRoom = c.hasBoundedWidth && c.maxWidth < 600
      val ps = ms.map { m ->
        val narrow = m.measure(SizeConstraints(maxWidth = 120, maxHeight = 400))
        val wide = m.measure(SizeConstraints(maxWidth = 500, maxHeight = 400))
        if (narrowRoom) narrow else wide
      }
      layout(declaredWidth(c, ps), ps.sumOf { it.height }) {
        var y = 0
        for (p in ps) {
          p.place(0, y)
          y += p.height
        }
      }
    }

    /** Each child given the container's width exactly. */
    val FILLS = LayoutFunctionN { ms, c ->
      val w = if (c.hasBoundedWidth) c.maxWidth else 300
      val ps = ms.map { it.measure(SizeConstraints(minWidth = w, maxWidth = w, maxHeight = 400)) }
      layout(w, ps.sumOf { it.height }) {
        var y = 0
        for (p in ps) {
          p.place(0, y)
          y += p.height
        }
      }
    }

    val KINDS =
        listOf(SIDE_BY_SIDE, STACKED, PLACES_IF_FITS, MEASURE_TWICE, TRY_NARROW_OR_WIDE, FILLS)
    val KIND_NAMES =
        listOf("SideBySide", "Stacked", "PlacesIfFits", "MeasureTwice", "TryNarrowOrWide", "Fills")
  }
}

/** A leaf whose width is state read only while it is laid out. */
private class WidthFromLayoutState(
    private val width: State<Int>,
    private val h: Int,
    private val tag: String?,
) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
      layoutBehavior = WidthFromLayoutStateBehavior(width, h),
      mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
      style = tag?.let { Style.wrapInView().contentDescription(it) },
  )
}

private class WidthFromLayoutStateBehavior(private val width: State<Int>, private val h: Int) :
    LayoutBehavior {
  override fun PrimitiveLayoutScope.layout(
      sizeConstraints: SizeConstraints,
  ): PrimitiveLayoutResult = PrimitiveLayoutResult(width = width.value, height = h)
}
