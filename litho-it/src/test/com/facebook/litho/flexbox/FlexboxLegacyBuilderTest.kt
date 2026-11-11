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

package com.facebook.litho.flexbox

import android.annotation.SuppressLint
import android.graphics.Color
import com.facebook.litho.Border
import com.facebook.litho.Column
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.DynamicValue
import com.facebook.litho.EventDispatchInfo
import com.facebook.litho.EventHandler
import com.facebook.litho.Row
import com.facebook.litho.Style
import com.facebook.litho.annotations.EventHandlerRebindMode
import com.facebook.litho.binders.onBindView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.heightAuto
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.core.widthAuto
import com.facebook.litho.it.R
import com.facebook.litho.kotlinStyle
import com.facebook.litho.layout.flexbox.KColumn
import com.facebook.litho.layout.flexbox.KRow
import com.facebook.litho.layout.flexbox.Row as KRowDsl
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.litho.view.backgroundColor
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaJustify
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the W2 wrapper: the legacy `Row.create(c)...build()` / `Column.create(c)...build()`
 * builders either produce a [KRow] / [KColumn] carrying the builder's props (flag ON), or retain
 * their original direct legacy-builder behaviour (flag OFF), as selected by
 * [ComponentsConfiguration.useKComponentForLegacyRowColumn].
 */
@SuppressLint("DeprecatedClass", "DeprecatedMethod")
@RunWith(LithoTestRunner::class)
class FlexboxLegacyBuilderTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private val c
    get() = lithoTestRule.context

  private lateinit var originalConfig: ComponentsConfiguration

  @Before
  fun setUp() {
    // Default the flag ON for these tests so build() returns KRow/KColumn.
    originalConfig = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        ComponentsConfiguration.create(originalConfig).useKComponentForLegacyRowColumn(true).build()
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.defaultInstance = originalConfig
  }

  private fun setFlag(enabled: Boolean) {
    ComponentsConfiguration.defaultInstance =
        ComponentsConfiguration.create(originalConfig)
            .useKComponentForLegacyRowColumn(enabled)
            .build()
  }

  // ===== Flag gating =====

  @Test
  fun rowCreate_flagOn_returnsKRow() {
    assertThat(Row.create(c).build()).isInstanceOf(KRow::class.java)
  }

  @Test
  fun columnCreate_flagOn_returnsKColumn() {
    assertThat(Column.create(c).build()).isInstanceOf(KColumn::class.java)
  }

  @Test
  fun rowCreate_flagOff_returnsLegacyRow() {
    setFlag(false)
    assertThat(Row.create(c).build()).isInstanceOf(Row::class.java)
  }

  @Test
  fun columnCreate_flagOff_returnsLegacyColumn() {
    setFlag(false)
    assertThat(Column.create(c).build()).isInstanceOf(Column::class.java)
  }

  // ===== simpleName =====

  @Test
  fun rowCreate_defaultSimpleName_isRow() {
    assertThat(Row.create(c).build().simpleName).isEqualTo("Row")
  }

  @Test
  fun columnCreate_defaultSimpleName_isColumn() {
    assertThat(Column.create(c).build().simpleName).isEqualTo("Column")
  }

  @Test
  fun create_customSimpleName_isCarriedThrough() {
    assertThat(Row.create(c, "MyRow").build().simpleName).isEqualTo("MyRow")
    assertThat(Column.create(c, "MyColumn").build().simpleName).isEqualTo("MyColumn")
  }

  @Test
  fun builder_flagOn_nullDynamicBackgroundIsNotSet() {
    val builder = Row.create(c).backgroundColor(null as DynamicValue<Int>?)

    assertThat(builder.hasBackgroundSet()).isFalse()
  }

  @Test
  fun builder_flagOn_fractionalElevationIsPreserved() {
    val view = lithoTestRule.render { Row.create(c).shadowElevationPx(1.5f).build() }

    assertThat(view.currentRootNode?.node?.nodeInfo?.shadowElevation).isEqualTo(1.5f)
  }

  // ===== Children =====

  @Test
  fun builder_children_areAdded() {
    val view = lithoTestRule.render {
      Row.create(c).child(Row.create(c).build()).child(Column.create(c).build()).build()
    }
    assertThat(requireNotNull(view.currentRootNode?.node).childCount).isEqualTo(2)
  }

  @Test
  fun builder_flagOff_childAddedAfterBuild_isAdded() {
    setFlag(false)
    val builder = Column.create(c)
    val component = builder.build()

    builder.child(Row.create(c).build())

    val view = lithoTestRule.render { component }
    assertThat(requireNotNull(view.currentRootNode?.node).childCount).isEqualTo(1)
  }

  @Test
  fun builder_flagOn_defStyleIsApplied() {
    val view =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Column.create(c, 0, R.style.ApplyStylesTest_WidthHeight).build()
        }

    assertThat(view.lithoView.width).isEqualTo(37)
    assertThat(view.lithoView.height).isEqualTo(100)
  }

  @Test
  fun builder_flagOff_nullVisibilityHandlerStillAppliesRatios() {
    setFlag(false)
    val view = lithoTestRule.render {
      Column.create(c).visibleHandler(0.25f, 0.5f, null).build()
    }

    assertThat(requireNotNull(view.currentRootNode?.node).visibleWidthRatio).isEqualTo(0.25f)
    assertThat(requireNotNull(view.currentRootNode?.node).visibleHeightRatio).isEqualTo(0.5f)
  }

  @Test
  fun builder_flagOn_nullVisibilityHandlerStillAppliesRatios() {
    val view = lithoTestRule.render {
      Column.create(c).visibleHandler(0.25f, 0.5f, null).build()
    }

    assertThat(requireNotNull(view.currentRootNode?.node).visibleWidthRatio).isEqualTo(0.25f)
    assertThat(requireNotNull(view.currentRootNode?.node).visibleHeightRatio).isEqualTo(0.5f)
  }

  // ===== Equivalence with the Kotlin DSL (flag ON) =====

  @Test
  fun containerProps_equivalentToDsl() {
    val fromBuilder =
        Row.create(c)
            .alignItems(YogaAlign.CENTER)
            .justifyContent(YogaJustify.SPACE_BETWEEN)
            .reverse(true)
            .build() as KRow
    val fromDsl = KRowDsl(
        alignItems = YogaAlign.CENTER,
        justifyContent = YogaJustify.SPACE_BETWEEN,
        isReversed = true,
    )
    assertThat(fromBuilder.isEquivalentTo(fromDsl)).isTrue()
  }

  @Test
  fun eachStyleProp_wrapperReconcilesIdenticallyToDsl() {
    // The wrapper's setters call the SAME Style extensions as the Kotlin DSL, so a builder-built
    // KRow is structurally identical to the DSL-built one. This asserts they reconcile identically
    // per prop — i.e. the wrapper is never *worse* than the launched DSL path.
    val cases: List<Triple<String, (Row.Builder) -> Row.Builder, () -> KRow>> = listOf(
        Triple("widthPx", { b -> b.widthPx(100) }, { KRowDsl(style = Style.width(100.px)) }),
        Triple("heightPx", { b -> b.heightPx(50) }, { KRowDsl(style = Style.height(50.px)) }),
        Triple(
            "paddingPx",
            { b -> b.paddingPx(YogaEdge.ALL, 8) },
            { KRowDsl(style = Style.padding(all = 8.px)) },
        ),
        Triple("flexGrow", { b -> b.flexGrow(1f) }, { KRowDsl(style = Style.flex(grow = 1f)) }),
        Triple(
            "alignSelf",
            { b -> b.alignSelf(YogaAlign.CENTER) },
            { KRowDsl(style = Style.alignSelf(YogaAlign.CENTER)) },
        ),
        Triple(
            "backgroundColor",
            { b -> b.backgroundColor(0xFF112233.toInt()) },
            { KRowDsl(style = Style.backgroundColor(0xFF112233.toInt())) },
        ),
    )
    val mismatches = mutableListOf<String>()
    for ((name, viaBuilder, viaDsl) in cases) {
      val builderReconciles =
          (viaBuilder(Row.create(c)).build() as KRow).isEquivalentTo(
              viaBuilder(Row.create(c)).build() as KRow,
          )
      val dslReconciles = viaDsl().isEquivalentTo(viaDsl())
      if (builderReconciles != dslReconciles) {
        mismatches.add("$name (builder=$builderReconciles dsl=$dslReconciles)")
      }
    }
    assertThat(mismatches).isEmpty()
  }

  @Test
  fun singleStyleProp_reachesNode_inBothFlagStates() {
    for (enabled in listOf(true, false)) {
      setFlag(enabled)
      val view = lithoTestRule.render { Column.create(c).widthPx(100).heightPx(10).build() }
      assertThat(view.currentRootNode?.width).describedAs("flag=$enabled").isEqualTo(100)
    }
  }

  @Test
  fun emptyBuilder_equivalentToDsl() {
    assertThat((Row.create(c).build() as KRow).isEquivalentTo(KRowDsl())).isTrue()
  }

  // ===== Re-render equivalence matches the legacy path =====

  // Spec code re-creates its handlers on every render. REBIND handlers share the component's
  // EventDispatchInfo, so handlers from two renders are isEquivalentTo but not identical.
  private val dispatchInfo = EventDispatchInfo(null, null)

  private fun <E> rebindHandler(id: Int): EventHandler<E> =
      EventHandler(id, EventHandlerRebindMode.REBIND, dispatchInfo, arrayOf<Any?>("model"))

  private fun redBorder(color: Int = Color.RED): Border =
      Border.create(c).widthPx(YogaEdge.ALL, 2).color(YogaEdge.ALL, color).build()

  @Test
  fun identicalReRender_isEquivalent_inBothFlagStates() {
    val cases: List<Pair<String, () -> Component>> = listOf(
        "clickHandler" to { Row.create(c).clickHandler(rebindHandler(1)).build() },
        "touchHandler" to { Row.create(c).touchHandler(rebindHandler(2)).build() },
        "visibleHandler" to { Row.create(c).visibleHandler(0.5f, 0.5f, rebindHandler(3)).build() },
        "fullImpressionHandler" to
            {
              Row.create(c).fullImpressionHandler(rebindHandler(4)).build()
            },
        "onInitializeAccessibilityNodeInfoHandler" to
            {
              Row.create(c).onInitializeAccessibilityNodeInfoHandler(rebindHandler(5)).build()
            },
        "dispatchPopulateAccessibilityEventHandler" to
            {
              Row.create(c).dispatchPopulateAccessibilityEventHandler(rebindHandler(6)).build()
            },
        "border" to { Row.create(c).border(redBorder()).build() },
        "child with clickHandler" to
            {
              Column.create(c).child(Row.create(c).clickHandler(rebindHandler(7))).build()
            },
    )
    val mismatches = mutableListOf<String>()
    for (enabled in listOf(true, false)) {
      setFlag(enabled)
      for ((name, build) in cases) {
        if (!build().isEquivalentTo(build())) {
          mismatches.add("$name (flag=$enabled)")
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /** Each production render builds from a new [ComponentContext], so nothing may hold on to it. */
  @Test
  fun reRenderFromFreshContext_isEquivalent_inBothFlagStates() {
    val cases: List<Pair<String, (ComponentContext) -> Component>> = listOf(
        "transitionKey" to { ctx -> Row.create(ctx).transitionKey("key").build() },
        "clickHandler" to { ctx -> Row.create(ctx).clickHandler(rebindHandler(1)).build() },
        "padding and background" to
            { ctx ->
              Column.create(ctx).paddingPx(YogaEdge.ALL, 8).backgroundColor(Color.RED).build()
            },
    )
    val mismatches = mutableListOf<String>()
    for (enabled in listOf(true, false)) {
      setFlag(enabled)
      for ((name, build) in cases) {
        if (!build(ComponentContext(c)).isEquivalentTo(build(ComponentContext(c)))) {
          mismatches.add("$name (flag=$enabled)")
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  @Test
  fun differentHandler_isNotEquivalent() {
    val first = Row.create(c).clickHandler(rebindHandler(1)).build()
    val second = Row.create(c).clickHandler(rebindHandler(2)).build()

    assertThat(first.isEquivalentTo(second)).isFalse()
  }

  @Test
  fun differentBorder_isNotEquivalent() {
    val first = Row.create(c).border(redBorder()).build()
    val second = Row.create(c).border(redBorder(Color.BLUE)).build()

    assertThat(first.isEquivalentTo(second)).isFalse()
  }

  /** `CommonProps` equivalence ignores binders, so the builder's `kotlinStyle` is compared too. */
  @Test
  fun changedBinderDep_isNotEquivalent() {
    fun boundRow(dep: Int) =
        Row.create(c)
            .kotlinStyle(
                Style.onBindView(dep) { view ->
                  view.tag = dep
                  onUnbind { view.tag = null }
                },
            )
            .build()

    assertThat(boundRow(1).isEquivalentTo(boundRow(2))).isFalse()
  }

  /**
   * Spec builders return the same mutable component from every `build()`, so a setter called after
   * `build()` changes the earlier output too. The flag-ON outputs share the builder's props and
   * behave the same way.
   */
  @Test
  fun setterAfterBuild_changesEarlierOutput_inBothFlagStates() {
    for (enabled in listOf(true, false)) {
      setFlag(enabled)
      val builder = sizedRow()
      val first = builder.build()
      val second = builder.widthPx(20).build()

      assertThat(first.isEquivalentTo(second)).describedAs("flag=$enabled").isTrue()
      val view = lithoTestRule.render { first }
      assertThat(view.currentRootNode?.width).describedAs("flag=$enabled").isEqualTo(20)
    }
  }

  /**
   * Renders [child] inside a `FLEX_START` column. Only a nested child shows the auto sizes: the
   * root's dimensions are restored from the size specs.
   */
  private fun nestedSize(child: Component): Pair<Int, Int> {
    val root =
        lithoTestRule
            .render { Column.create(c).alignItems(YogaAlign.FLEX_START).child(child).build() }
            .currentRootNode!!
    val node = root.getChildAt(0)
    return node.width to node.height
  }

  @Test
  fun autoSize_isNotEquivalentToExplicitSize_inBothFlagStates() {
    for (enabled in listOf(true, false)) {
      setFlag(enabled)
      val sized = sizedRow().build()
      val widthAuto = sizedRow().kotlinStyle(Style.widthAuto()).build()
      val heightAuto = sizedRow().kotlinStyle(Style.heightAuto()).build()

      assertThat(sized.isEquivalentTo(widthAuto)).describedAs("widthAuto flag=$enabled").isFalse()
      assertThat(sized.isEquivalentTo(heightAuto)).describedAs("heightAuto flag=$enabled").isFalse()
      assertThat(nestedSize(sized)).describedAs("sized flag=$enabled").isEqualTo(10 to 10)
      assertThat(nestedSize(widthAuto)).describedAs("widthAuto flag=$enabled").isEqualTo(0 to 10)
      assertThat(nestedSize(heightAuto)).describedAs("heightAuto flag=$enabled").isEqualTo(10 to 0)
    }
  }

  // ===== flex shorthand =====

  /**
   * Two content-sized children in a 300px row, with 150px of free space to distribute. A `0px` flex
   * basis would ignore the content widths and split the row evenly; an `auto` basis grows each
   * child from its own content width.
   */
  private fun twoChildWidths(flex: Float): String {
    val root =
        lithoTestRule
            .render {
              Row.create(c)
                  .widthPx(300)
                  .child(Row.create(c).flex(flex).child(Row.create(c).widthPx(50).heightPx(10)))
                  .child(Row.create(c).flex(flex).child(Row.create(c).widthPx(100).heightPx(10)))
                  .build()
            }
            .currentRootNode!!
    return "${root.getChildAt(0).width}/${root.getChildAt(1).width}"
  }

  /** A single content-sized child in a 300px row, so the grow factor alone decides its width. */
  private fun soleChildWidth(flex: Float): Int =
      lithoTestRule
          .render {
            Row.create(c)
                .widthPx(300)
                .child(Row.create(c).flex(flex).child(Row.create(c).widthPx(50).heightPx(10)))
                .build()
          }
          .currentRootNode!!
          .getChildAt(0)
          .width

  @Test
  fun flexPositive_identicalInBothFlagStates() {
    // Mapping the shorthand to `grow` leaves basis `auto`, and so does the legacy path: Litho's
    // YogaConfig sets useWebDefaults, under which `Node.processFlexBasis` returns `auto` rather
    // than `0px` for a positive `flex` (LithoYogaFactory.createYogaConfig). A `0px` basis would
    // give 150/150 here.
    val flagOn = twoChildWidths(1f)
    setFlag(false)
    assertThat(flagOn).isEqualTo(twoChildWidths(1f)).isEqualTo("125/175")
  }

  @Test
  fun flexNegative_identicalInBothFlagStates() {
    // Guards the `flex < 0` mapping: routing a negative to `grow` puts a negative grow factor on
    // the node, which the legacy path never produces and which stretches the child instead of
    // leaving it at its content width.
    val flagOn = soleChildWidth(-1f)
    setFlag(false)
    assertThat(flagOn).isEqualTo(soleChildWidth(-1f)).isEqualTo(50)
  }

  // ===== Nullable DynamicValue setters =====

  /**
   * The base tolerates a null [DynamicValue] and real call sites rely on it — a Mockito double of a
   * non-null Kotlin property yields null, which is how `StickerCtaAnimator.scaleX` reaches
   * `StickerCtaComponent`. A non-null Kotlin override would add an
   * `Intrinsics.checkNotNullParameter` that runs before the flag is read, so this must hold in both
   * flag states.
   */
  private fun setEveryDynamicValueToNull(b: Row.Builder): Row.Builder =
      b.backgroundColor(null as DynamicValue<Int>?)
          .backgroundDynamicDrawable(null)
          .foregroundColor(null as DynamicValue<Int>?)
          .alpha(null as DynamicValue<Float>?)
          .rotation(null as DynamicValue<Float>?)
          .rotationX(null as DynamicValue<Float>?)
          .rotationY(null as DynamicValue<Float>?)
          .scaleX(null as DynamicValue<Float>?)
          .scaleY(null as DynamicValue<Float>?)
          .translationX(null as DynamicValue<Float>?)
          .translationY(null as DynamicValue<Float>?)
          .translationZ(null as DynamicValue<Float>?)
          .shadowElevation(null as DynamicValue<Float>?)

  private fun sizedRow() = Row.create(c).widthPx(10).heightPx(10)

  /** The nulls are forwarded to `CommonProps` rather than dropped; the container still renders. */
  @Test
  fun nullDynamicValues_stillRender_inBothFlagStates() {
    for (enabled in listOf(true, false)) {
      setFlag(enabled)
      val view = lithoTestRule.render { setEveryDynamicValueToNull(sizedRow()).build() }
      assertThat(view.currentRootNode?.width).describedAs("flag=$enabled").isEqualTo(10)
    }
  }

  // ===== Props without a Style API reach the node =====

  @Test
  fun visibilityOutputTag_reachesLithoNode() {
    val view = lithoTestRule.render {
      Column.create(c).visibilityOutputTag("occlusion-tag").widthPx(10).heightPx(10).build()
    }
    assertThat(view.currentRootNode?.node?.visibilityOutputTag).isEqualTo("occlusion-tag")
  }

  @Test
  fun kotlinStyle_reachesNode_inBothFlagStates() {
    for (enabled in listOf(true, false)) {
      setFlag(enabled)
      val view = lithoTestRule.render {
        Column.create(c).kotlinStyle(Style.width(100.px)).heightPx(10).build()
      }
      assertThat(view.currentRootNode?.width).describedAs("flag=$enabled").isEqualTo(100)
    }
  }
}
