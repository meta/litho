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

import android.graphics.Color
import android.graphics.Rect
import androidx.collection.MutableScatterSet
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.maxHeight
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.border
import com.facebook.litho.layout.BoxLayoutProps
import com.facebook.litho.layout.IntEdges
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.state.StateId
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.foregroundColor
import com.facebook.litho.view.onClick
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.px
import com.facebook.yoga.YogaMeasureOutput
import com.facebook.yoga.YogaNode
import com.facebook.yoga.YogaUnit
import com.facebook.yoga.YogaValue
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins what the cache copy of a [LithoLayoutResult] (`LithoYogaLayoutFunction.copyLayoutResult`,
 * for full and partial reuse) takes from its source, field by field.
 *
 * Each per-pass field is classified once, in [CACHE_COPY], and a reflection guard fails when a
 * field is added to [RECORD_CLASSES] without a classification. The Yoga node and its style inputs
 * are on [YogaLayoutOutput], the result's measure output; Litho's record of the pass is on the
 * result itself, which `copyCachedFrom` copies. Fields are looked up by name, with any leading `_`
 * dropped, on the result and on its measure output.
 */
@RunWith(LithoTestRunner::class)
class LayoutResultCopyIsolationTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `every field of the record is classified`() {
    assertThat(recordFieldNames(RECORD_CLASSES).distinct())
        .describedAs("cache copy classification")
        .containsExactlyInAnyOrderElementsOf(CACHE_COPY.keys)
    assertThat(recordFieldNames(RESULT_SUBCLASSES_WITHOUT_FIELDS))
        .describedAs("fields the copy doesn't carry")
        .isEmpty()
  }

  @Test
  fun `a cache copy carries each field as classified`() {
    val tree = renderTree()
    val source = tree.leaf
    populate(source, CACHE_COPY, tree)
    prepareResets(source, CACHE_COPY)
    val clone = yogaNodeOf(source).cloneWithoutChildren()

    val copy = cacheCopy(source, clone)

    assertCopy(source, copy, CACHE_COPY)
    assertThat(yogaNodeOf(copy)).describedAs("the clone passed in").isSameAs(clone)
    assertThat(copy.node).describedAs("the node passed in").isSameAs(source.node)
    assertOutputsOfItsOwn(source, copy)
  }

  @Test
  fun `a cache copy's bounds and state reads are its own`() {
    val tree = renderTree()
    val source = tree.leaf
    populate(source, CACHE_COPY, tree)

    val copy = cacheCopy(source, yogaNodeOf(source).cloneWithoutChildren())

    assertMutationIsolated(source, copy)
  }

  @Test
  fun `release clears only a result's diff node and Yoga node data, and a cache copy reads it`() {
    val tree = renderTree()
    val source = tree.leaf
    populate(source, CACHE_COPY, tree)
    prepareResets(source, CACHE_COPY)
    yogaNodeOf(source).data = Any()
    val before = CACHE_COPY.keys.associateWith { valueOf(source, it) }

    source.releaseLayoutPhaseData()

    SoftAssertions.assertSoftly { softly ->
      for ((name, value) in before) {
        val after = valueOf(source, name)
        if (name == "diffNode") {
          softly.assertThat(after).describedAs("released $name").isNull()
        } else if (isValueLike(value)) {
          softly.assertThat(after).describedAs("$name after release").isEqualTo(value)
        } else {
          softly.assertThat(after).describedAs("$name after release").isSameAs(value)
        }
      }
      softly.assertThat(yogaNodeOf(source).data).describedAs("released Yoga node data").isNull()
    }

    val cached = cacheCopy(source, yogaNodeOf(source).cloneWithoutChildren())
    assertCopy(source, cached, CACHE_COPY - "diffNode", requireObservable = false)
    assertThat(cached.diffNode).isNull()

    // What the mount and the next pass read from a copy of a released result.
    assertThat(cached.contentRenderUnit).isSameAs(source.contentRenderUnit).isNotNull()
    assertThat(cached.layoutData).isSameAs(source.layoutData).isNotNull()
    assertThat(cached.lastMeasuredSize).isEqualTo(source.lastMeasuredSize)
    assertThat(cached.widthSpec).isEqualTo(source.widthSpec)
    assertThat(cached.heightSpec).isEqualTo(source.heightSpec)
    assertThat(adjustedBoundsOf(cached)).isEqualTo(adjustedBoundsOf(source))
  }

  /** How a copy takes one field from its source. */
  private sealed interface CopyRule

  /** Equal to the source's. */
  private object ByValue : CopyRule

  /** The source's very instance, on purpose. */
  private object Shared : CopyRule

  /** A new instance, equal in content to the source's, so mutating one leaves the other. */
  private data class DeepCopied(val by: String) : CopyRule

  /** [value] whatever the source had, set by [by]. */
  private data class Reset(val value: Any?, val by: String) : CopyRule

  /** Asserted by the test itself, not generically. */
  private data class Checked(val why: String) : CopyRule

  /** Not asserted. */
  private data class Ignored(val why: String) : CopyRule

  private class Tree(
      val root: LithoLayoutResult,
      val leaf: LithoLayoutResult,
      val all: List<LithoLayoutResult>,
  )

  private fun renderTree(): Tree {
    val view =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column(style = Style.width(VIEWPORT.px)) {
            child(SourceLeaf())
            child(
                Layout(
                    listOf(SimpleMountSpecTester(style = Style.width(LEAF.px).height(LEAF.px))),
                    style = Style.backgroundColor(Color.GREEN),
                    layout = PLACE_FIRST,
                ),
            )
            repeat(DONOR_LEAVES) {
              child(SimpleMountSpecTester(style = Style.width(LEAF.px).height(LEAF.px)))
            }
          }
        }
    val root =
        checkNotNull(view.componentTree.mainThreadLayoutState?.rootLayoutResult)
            as LithoLayoutResult
    val all = ArrayList<LithoLayoutResult>()
    fun walk(result: LithoLayoutResult) {
      all.add(result)
      for (i in 0 until result.childCount) walk(result.getChildAt(i))
    }
    walk(root)
    return Tree(
        root = root,
        leaf = all.single { it.node.headComponent is SourceLeaf },
        all = all,
    )
  }

  /**
   * Gives [source] a value other than a fresh result's for every field [rules] assert, so that a
   * copy that drops a field fails. Values a real layout set are kept.
   */
  private fun populate(source: LithoLayoutResult, rules: Map<String, CopyRule>, tree: Tree) {
    val fresh = freshLike(source)
    val donorResults = tree.all.filter { it !== source && it !== tree.root }.iterator()
    val sourceUnits = RENDER_UNITS.mapNotNull { valueOf(source, it) }
    val donorUnits =
        tree.all
            .flatMap { result -> RENDER_UNITS.mapNotNull { valueOf(result, it) } }
            .distinctBy { System.identityHashCode(it) }
            .filter { unit -> sourceUnits.none { it === unit } }
            .iterator()
    val node = source.node
    for ((name, rule) in rules) {
      if (rule is Ignored || name in NOT_POPULATED) continue
      val current = valueOf(source, name)
      if (current != null && current != valueOf(fresh, name)) continue
      when (name) {
        "adjustedBounds" -> (current as Rect).set(1, 2, 3, 4)
        "stateReads" ->
            source.addStateRead(
                StateId(TREE_ID, HookGlobalKey(node.tailComponentKey, 0, RenderScope.Resolve)),
            )
        "children" -> source.addChild(donorResults.next())
        in RENDER_UNITS -> setValue(source, name, donorUnits.next())
        in STYLE_VALUES -> setValue(source, name, YogaValue(52f, YogaUnit.POINT))
        in BOOLEANS -> setValue(source, name, true)
        "widthSpec" -> setValue(source, name, SizeSpec.makeSizeSpec(123, SizeSpec.EXACTLY))
        "heightSpec" -> setValue(source, name, SizeSpec.makeSizeSpec(45, SizeSpec.AT_MOST))
        "lastMeasuredSize" -> setValue(source, name, YogaMeasureOutput.make(12, 34))
        "layoutData" -> setValue(source, name, Any())
        "diffNode" ->
            setValue(
                source,
                name,
                DefaultDiffNode(
                    node.tailComponent,
                    node.tailComponentKey,
                    node.tailScopedComponentInfo,
                ),
            )
        "delegate",
        "actualDeferredNodeResult" -> setValue(source, name, donorResults.next())
        "effects" -> setValue(source, name, ArrayList<Attachable>())
        "resolvedMargins" -> setValue(source, name, IntEdges(2, 3, 4, 5))
        "childBoxProps" -> setValue(source, name, BoxLayoutProps())
        "failure" -> setValue(source, name, RuntimeException("sentinel failure"))
        else -> error("no sentinel for $name: give it one")
      }
    }
  }

  /** Makes each boolean reset observable: the source holds the other value. */
  private fun prepareResets(source: LithoLayoutResult, rules: Map<String, CopyRule>) {
    for ((name, rule) in rules) {
      if (rule is Reset && rule.value is Boolean) {
        setValue(source, name, !rule.value)
      }
    }
  }

  private fun assertCopy(
      source: LithoLayoutResult,
      copy: LithoLayoutResult,
      rules: Map<String, CopyRule>,
      requireObservable: Boolean = true,
  ) {
    val fresh = freshLike(source)
    SoftAssertions.assertSoftly { softly ->
      for ((name, rule) in rules) {
        val expected = valueOf(source, name)
        val actual = valueOf(copy, name)
        when (rule) {
          ByValue -> {
            if (requireObservable) {
              softly
                  .assertThat(expected)
                  .describedAs("source $name is set")
                  .isNotEqualTo(valueOf(fresh, name))
            }
            softly.assertThat(actual).describedAs("$name, copied by value").isEqualTo(expected)
          }
          Shared -> {
            softly.assertThat(expected).describedAs("source $name is set").isNotNull()
            softly.assertThat(actual).describedAs("$name, shared").isSameAs(expected)
          }
          is DeepCopied -> {
            if (requireObservable) {
              softly
                  .assertThat(expected)
                  .describedAs("source $name is set")
                  .isNotEqualTo(valueOf(fresh, name))
            }
            softly
                .assertThat(actual)
                .describedAs("$name, deep-copied by ${rule.by}")
                .isEqualTo(expected)
            softly
                .assertThat(actual)
                .describedAs("$name, deep-copied by ${rule.by}")
                .isNotSameAs(expected)
          }
          is Reset -> {
            if (requireObservable) {
              softly
                  .assertThat(expected)
                  .describedAs("source $name differs from its reset")
                  .isNotEqualTo(rule.value)
            }
            softly
                .assertThat(actual)
                .describedAs("$name, reset by ${rule.by}")
                .isEqualTo(rule.value)
          }
          is Checked,
          is Ignored -> Unit
        }
      }
    }
  }

  /** [copy] has a measure output of its own. */
  private fun assertOutputsOfItsOwn(source: LithoLayoutResult, copy: LithoLayoutResult) {
    assertThat(copy.measureOutput).isNotSameAs(source.measureOutput)
  }

  private fun assertMutationIsolated(source: LithoLayoutResult, copy: LithoLayoutResult) {
    val sourceBounds = Rect(adjustedBoundsOf(source))
    val sourceReads = stateReadsOf(source).asSet().toSet()
    val copyBounds = Rect(adjustedBoundsOf(copy))
    val copyReads = stateReadsOf(copy).asSet().toSet()
    val newRead =
        StateId(TREE_ID, HookGlobalKey(source.node.tailComponentKey, 99, RenderScope.Resolve))

    adjustedBoundsOf(copy).set(100, 200, 300, 400)
    stateReadsOf(copy).add(newRead)

    assertThat(adjustedBoundsOf(source)).describedAs("source bounds").isEqualTo(sourceBounds)
    assertThat(stateReadsOf(source).asSet().toSet())
        .describedAs("source reads")
        .isEqualTo(sourceReads)

    adjustedBoundsOf(copy).set(copyBounds)
    stateReadsOf(copy).remove(newRead)
    adjustedBoundsOf(source).set(5, 6, 7, 8)
    stateReadsOf(source).add(newRead)

    assertThat(adjustedBoundsOf(copy)).describedAs("copy bounds").isEqualTo(copyBounds)
    assertThat(stateReadsOf(copy).asSet().toSet()).describedAs("copy reads").isEqualTo(copyReads)
  }

  private fun cacheCopy(source: LithoLayoutResult, yogaNode: YogaNode): LithoLayoutResult {
    val method =
        LithoYogaLayoutFunction::class
            .java
            .getDeclaredMethod(
                "copyLayoutResult",
                LithoLayoutResult::class.java,
                LithoNode::class.java,
                YogaNode::class.java,
            )
    method.isAccessible = true
    return method.invoke(LithoYogaLayoutFunction, source, source.node, yogaNode)
        as LithoLayoutResult
  }

  /** A result of [source]'s node with every field at its default. */
  private fun freshLike(source: LithoLayoutResult): LithoLayoutResult =
      source.node.createLayoutResult(
          YogaLayoutOutput(
              yogaNode = NodeConfig.createYogaNode(),
              widthFromStyle = YogaValue.UNDEFINED,
              heightFromStyle = YogaValue.UNDEFINED,
              minWidthFromStyle = YogaValue.UNDEFINED,
              maxWidthFromStyle = YogaValue.UNDEFINED,
              minHeightFromStyle = YogaValue.UNDEFINED,
              maxHeightFromStyle = YogaValue.UNDEFINED,
          ),
      )

  private fun yogaNodeOf(result: LithoLayoutResult): YogaNode =
      valueOf(result, "yogaNode") as YogaNode

  private fun adjustedBoundsOf(result: LithoLayoutResult): Rect =
      valueOf(result, "adjustedBounds") as Rect

  @Suppress("UNCHECKED_CAST")
  private fun stateReadsOf(result: LithoLayoutResult): MutableScatterSet<StateId> =
      checkNotNull(valueOf(result, "stateReads")) { "no state reads" } as MutableScatterSet<StateId>

  private fun isValueLike(value: Any?): Boolean =
      value == null || value is Number || value is Boolean || value is YogaValue

  private companion object {
    const val VIEWPORT = 500
    const val LEAF = 20
    const val DONOR_LEAVES = 6
    const val TREE_ID = 4242

    /** Where a result's per-pass fields live; [recordFieldNames] reads them. */
    val RECORD_CLASSES: List<Class<*>> = listOf(
        YogaLayoutOutput::class.java,
        LithoLayoutResult::class.java,
    )

    /** Results the copy creates whose own fields it would not carry. */
    val RESULT_SUBCLASSES_WITHOUT_FIELDS: List<Class<*>> =
        listOf(DeferredLithoLayoutResult::class.java, NullLithoLayoutResult::class.java)

    val RENDER_UNITS = setOf(
        "contentRenderUnit",
        "hostRenderUnit",
        "borderRenderUnit",
    )
    val STYLE_VALUES = setOf(
        "widthFromStyle",
        "heightFromStyle",
        "minWidthFromStyle",
        "maxWidthFromStyle",
        "minHeightFromStyle",
        "maxHeightFromStyle",
    )
    val BOOLEANS = setOf(
        "isCachedLayout",
        "isDiffedLayout",
        "wasMeasured",
        "cachedMeasuresValid",
        "measureHadExceptions",
        "boundsDefined",
        "subtreeHasCustomLayout",
    )

    /** The result's measure output, read from the result itself. */
    val STRUCTURE = setOf("measureOutput")

    /** Fields the source keeps as the real layout made them. */
    val NOT_POPULATED = STRUCTURE + setOf("yogaNode", "context", "node")

    const val BY_RESETS = "copyCachedFrom's resets"
    const val NOT_CARRIED = "copyCachedFrom, which leaves it at its default"

    /** `LithoYogaLayoutFunction.copyLayoutResult`. */
    val CACHE_COPY: Map<String, CopyRule> = mapOf(
        // YogaLayoutOutput.
        "yogaNode" to Checked("the clone passed in"),
        // Yoga style inputs, copied by data class copy().
        "widthFromStyle" to ByValue,
        "heightFromStyle" to ByValue,
        "minWidthFromStyle" to ByValue,
        "maxWidthFromStyle" to ByValue,
        "minHeightFromStyle" to ByValue,
        "maxHeightFromStyle" to ByValue,
        // The record of the pass, on the result, copied by copyCachedFrom.
        "widthSpec" to ByValue,
        "heightSpec" to ByValue,
        "lastMeasuredSize" to ByValue,
        "isCachedLayout" to Reset(true, BY_RESETS),
        "isDiffedLayout" to Reset(false, BY_RESETS),
        "cachedMeasuresValid" to Reset(true, BY_RESETS),
        "wasMeasured" to Reset(false, BY_RESETS),
        "measureHadExceptions" to Reset(false, BY_RESETS),
        "boundsDefined" to ByValue,
        // cloneCachedSubtree then replaces it when the diff tree has one.
        "diffNode" to Shared,
        "layoutData" to Shared,
        "contentRenderUnit" to Shared,
        "hostRenderUnit" to Shared,
        "borderRenderUnit" to Shared,
        "delegate" to Shared,
        "failure" to Reset(null, NOT_CARRIED),
        "effects" to Shared,
        "actualDeferredNodeResult" to Reset(null, "$NOT_CARRIED: no deferred node caching"),
        "adjustedBounds" to DeepCopied("adjustedBounds.set in copyCachedFrom"),
        "stateReads" to DeepCopied("addStateReads in copyCachedFrom"),
        "resolvedMargins" to Reset(null, NOT_CARRIED),
        "defaultLayoutBuilder" to
            Ignored("held by a `Layout` in a Yoga tree, which is never cached"),
        "childBoxProps" to Shared,
        // LithoLayoutResult's structure.
        "context" to Shared,
        "node" to Checked("the node passed in"),
        "measureOutput" to Checked("an output of its own"),
        "children" to Reset(emptyList<Any>(), "createLayoutResult: cloneCachedSubtree adds them"),
        "subtreeHasCustomLayout" to Reset(false, "createLayoutResult"),
    )

    /** Places its child's first measurement at the origin. */
    val PLACE_FIRST = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(LEAF, LEAF))
      layout(constraints.maxWidth, LEAF) { placeable.place(0, 0) }
    }

    /** Instance field names declared by [classes], without a leading `_`. */
    private fun recordFieldNames(classes: List<Class<*>>): List<String> = classes.flatMap { cls ->
      cls.declaredFields
          .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
          .map { it.name.removePrefix("_") }
    }

    private fun valueOf(result: LithoLayoutResult, name: String): Any? {
      val (owner, field) = fieldOf(result, name)
      return field.get(owner)
    }

    private fun setValue(result: LithoLayoutResult, name: String, value: Any?) {
      val (owner, field) = fieldOf(result, name)
      field.set(owner, value)
    }

    /**
     * The field [name] or `_name`, on [result] or its measure output, exactly one of them. The
     * output itself is read from the result.
     */
    private fun fieldOf(result: LithoLayoutResult, name: String): Pair<Any, Field> {
      val owners: List<Any> =
          if (name in STRUCTURE) listOf(result) else listOf(result, result.measureOutput)
      val found = owners.mapNotNull { owner ->
        generateSequence<Class<*>>(owner.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull {
              !Modifier.isStatic(it.modifiers) && (it.name == name || it.name == "_$name")
            }
            ?.let { owner to it.also { field -> field.isAccessible = true } }
      }
      return found.singleOrNull()
          ?: error("$name is on ${found.size} of the result and its measure output")
    }
  }
}

/** A leaf with every render unit it can have, and a state read. */
private class SourceLeaf : KComponent() {
  override fun ComponentScope.render(): Component {
    val size = useState { 40 }
    return SimpleMountSpecTester(
        style =
            Style.width(size.value.px)
                .minWidth(10.px)
                .maxWidth(100.px)
                .minHeight(10.px)
                .maxHeight(100.px)
                .backgroundColor(Color.RED)
                .foregroundColor(Color.BLUE)
                .border(width = 2.px, color = Color.BLACK)
                .onClick {},
    )
  }
}
