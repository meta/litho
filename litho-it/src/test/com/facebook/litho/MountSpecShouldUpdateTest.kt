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

// TestDrawableComponent is deprecated but intentionally used here — this test exercises
// MountSpecLithoRenderUnit.shouldUpdateMountItem which only applies to legacy MountSpec components.
// Primitive components use a different code path (PrimitiveLithoRenderUnit).
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import com.facebook.litho.MountSpecLithoRenderUnit.Companion.STATE_DIRTY
import com.facebook.litho.MountSpecLithoRenderUnit.Companion.STATE_UNKNOWN
import com.facebook.litho.MountSpecLithoRenderUnit.Companion.STATE_UPDATED
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestDrawableComponent
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.TextAlignment
import com.facebook.litho.widget.TextComponent
import com.facebook.litho.widget.TextDrawable
import com.facebook.yoga.YogaDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [MountSpecLithoRenderUnit.shouldUpdateMountItem] with render units from different
 * layout passes (mounted vs new). A unit is skipped when its content version equals the mounted
 * unit's; otherwise STATE_DIRTY forces an update, and everything else is compared with the mounted
 * unit. The mount-level tests commit layouts while the view is detached, so they never mount.
 */
@RunWith(LithoTestRunner::class)
class MountSpecShouldUpdateTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  /**
   * STATE_DIRTY forces update even when the mounted and new render units come from different layout
   * passes. This catches state-driven rendering changes that shouldComponentUpdate might miss.
   */
  @Test
  fun stateDirty_forcesUpdate_acrossLayoutPasses() {
    // Layout pass 1: RED component
    val firstComponent =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { firstComponent }
    val firstLayoutState = testLithoView.componentTree.mainThreadLayoutState!!
    val (mountedUnit, mountedData) = findRenderUnitWithState(firstLayoutState, STATE_UNKNOWN)

    // Layout pass 2: BLUE component → STATE_DIRTY for the drawable output
    val secondComponent =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.BLUE)
            .build()
    testLithoView.setRoot(secondComponent)
    val secondLayoutState = testLithoView.componentTree.mainThreadLayoutState!!
    val (newUnit, newData) = findRenderUnitWithState(secondLayoutState, STATE_DIRTY)

    // Mounted render unit is from pass 1, new is from pass 2
    val result =
        MountSpecLithoRenderUnit.shouldUpdateMountItem(mountedUnit, newUnit, mountedData, newData)
    assertThat(result).isTrue()
  }

  @Test
  fun commonPropChange_marksPureRenderMountSpecDirty() {
    val firstComponent =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .alpha(0.5f)
            .build()
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { firstComponent }

    val secondComponent =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .alpha(0.1f)
            .build()
    testLithoView.setRoot(secondComponent)

    val secondLayoutState = testLithoView.componentTree.mainThreadLayoutState!!
    findRenderUnitWithState(secondLayoutState, STATE_DIRTY)
  }

  /** A unit equal to the mounted one keeps its content version, so the update is skipped. */
  @Test
  fun stateUpdated_skipsUpdate_acrossLayoutPasses() {
    // Layout pass 1: RED component (isPureRender so shouldComponentUpdate compares props)
    val component =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { component }
    val firstLayoutState = testLithoView.componentTree.mainThreadLayoutState!!
    val (mountedUnit, mountedData) = findRenderUnitWithState(firstLayoutState, STATE_UNKNOWN)

    // Layout pass 2: same props → STATE_UPDATED
    val sameComponent =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    testLithoView.setRoot(sameComponent)
    val secondLayoutState = testLithoView.componentTree.mainThreadLayoutState!!
    val (newUnit, newData) = findRenderUnitWithState(secondLayoutState, STATE_UPDATED)

    // Mounted from pass 1, new from pass 2 — different render unit objects
    assertThat(mountedUnit).isNotSameAs(newUnit)
    val result =
        MountSpecLithoRenderUnit.shouldUpdateMountItem(mountedUnit, newUnit, mountedData, newData)
    assertThat(result).isFalse()
  }

  /**
   * Pass 1 (mounted) → Pass 2 (same props, never mounted) → Pass 3 (same props). Pass 3 keeps Pass
   * 1's content version through Pass 2, so the update is skipped without comparing props.
   */
  @Test
  fun stateUpdated_skipsUpdate_withIntermediateLayout() {
    val component =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { component }
    val pass1State = testLithoView.componentTree.mainThreadLayoutState!!
    val (pass1Unit, pass1Data) = findRenderUnitWithState(pass1State, STATE_UNKNOWN)

    // Pass 2: same props
    val sameComponent2 =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    testLithoView.setRoot(sameComponent2)

    // Pass 3: same props again
    val sameComponent3 =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    testLithoView.setRoot(sameComponent3)
    val pass3State = testLithoView.componentTree.mainThreadLayoutState!!
    val (pass3Unit, pass3Data) = findRenderUnitWithState(pass3State, STATE_UPDATED)

    // Simulate: pass 1 is mounted, pass 3 is the new layout (pass 2 was intermediate)
    assertThat(pass1Unit).isNotSameAs(pass3Unit)
    assertThat(pass3Unit.contentVersion).isEqualTo(pass1Unit.contentVersion)
    val result =
        MountSpecLithoRenderUnit.shouldUpdateMountItem(pass1Unit, pass3Unit, pass1Data, pass3Data)
    assertThat(result).isFalse()
  }

  /**
   * STATE_DIRTY forces update across three passes when props change in the latest pass, regardless
   * of what intermediate layouts did.
   */
  @Test
  fun stateDirty_forcesUpdate_withIntermediateLayout() {
    val component =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { component }
    val pass1State = testLithoView.componentTree.mainThreadLayoutState!!
    val (pass1Unit, pass1Data) = findRenderUnitWithState(pass1State, STATE_UNKNOWN)

    // Pass 2: same props (intermediate)
    val sameComponent =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.RED)
            .build()
    testLithoView.setRoot(sameComponent)

    // Pass 3: DIFFERENT props
    val differentComponent =
        TestDrawableComponent.create(lithoTestRule.context, true, false, false)
            .color(Color.BLUE)
            .build()
    testLithoView.setRoot(differentComponent)
    val pass3State = testLithoView.componentTree.mainThreadLayoutState!!
    val (pass3Unit, pass3Data) = findRenderUnitWithState(pass3State, STATE_DIRTY)

    // Simulate: pass 1 mounted, pass 3 is new (pass 2 was intermediate)
    val result =
        MountSpecLithoRenderUnit.shouldUpdateMountItem(pass1Unit, pass3Unit, pass1Data, pass3Data)
    assertThat(result).isTrue()
  }

  /**
   * Pass 1 (RED, mounted) → Pass 2 (BLUE, never mounted) → Pass 3 (BLUE). Pass 3 is STATE_UPDATED
   * against Pass 2, but the mounted content is still Pass 1's RED, so it must update.
   */
  @Test
  fun stateUpdated_forcesUpdate_whenDiffedLayoutWasNotMounted() {
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) {
          drawable(Color.RED)
        }
    val pass1State = testLithoView.componentTree.mainThreadLayoutState!!
    val (pass1Unit, pass1Data) = findRenderUnitWithState(pass1State, STATE_UNKNOWN)

    testLithoView.setRoot(drawable(Color.BLUE))
    testLithoView.setRoot(drawable(Color.BLUE))
    val pass3State = testLithoView.componentTree.mainThreadLayoutState!!
    val (pass3Unit, pass3Data) = findRenderUnitWithState(pass3State, STATE_UPDATED)

    val result =
        MountSpecLithoRenderUnit.shouldUpdateMountItem(pass1Unit, pass3Unit, pass1Data, pass3Data)
    assertThat(result).isTrue()
  }

  /** RED is mounted, BLUE is committed twice while detached, then the view is attached again. */
  @Test
  fun mountedColor_followsLatestLayout_whenDiffedLayoutWasNotMounted() {
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) {
          drawable(Color.RED)
        }
    assertThat(mountedColor(testLithoView)).isEqualTo(Color.RED)

    testLithoView.detachFromWindow()
    testLithoView.componentTree.setRootAndSizeSpecSync(
        drawable(Color.BLUE),
        exactly(10),
        exactly(10),
    )
    testLithoView.componentTree.setRootAndSizeSpecSync(
        drawable(Color.BLUE),
        exactly(10),
        exactly(10),
    )
    val committed = checkNotNull(testLithoView.committedLayoutState)
    findRenderUnitWithState(committed, STATE_UPDATED)

    testLithoView.attachToWindow().measure().layout()
    assertThat(mountedColor(testLithoView)).isEqualTo(Color.BLUE)
  }

  /**
   * LTR text is mounted, RTL is committed twice while detached, then the view is attached again.
   * The props never change, so only the layout direction tells the two apart.
   */
  @Test
  fun mountedText_followsLayoutDirection_whenDiffedLayoutWasNotMounted() {
    val ltrAlignment = expectedTextAlignment(rtl = false)
    val rtlAlignment = expectedTextAlignment(rtl = true)
    assertThat(rtlAlignment).isNotEqualTo(ltrAlignment)

    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(300), heightSpec = exactly(300)) {
          text(rtl = false)
        }
    assertThat(mountedTextAlignment(testLithoView)).isEqualTo(ltrAlignment)

    testLithoView.detachFromWindow()
    testLithoView.componentTree.setRootAndSizeSpecSync(text(rtl = true), exactly(300), exactly(300))
    testLithoView.componentTree.setRootAndSizeSpecSync(text(rtl = true), exactly(300), exactly(300))
    testLithoView.attachToWindow().measure().layout()

    assertThat(mountedTextAlignment(testLithoView)).isEqualTo(rtlAlignment)
  }

  /**
   * With [ComponentsConfiguration.useMountSpecContentVersion] off, mount trusts STATE_UPDATED
   * again: Pass 3 is skipped against Pass 1's RED although Pass 2 (BLUE) never mounted.
   */
  @Test
  fun stateUpdated_skipsUpdate_whenDiffedLayoutWasNotMounted_withContentVersionOff() {
    val useMountSpecContentVersion = ComponentsConfiguration.useMountSpecContentVersion
    ComponentsConfiguration.useMountSpecContentVersion = false
    try {
      val testLithoView =
          lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) {
            drawable(Color.RED)
          }
      val pass1State = testLithoView.componentTree.mainThreadLayoutState!!
      val (pass1Unit, pass1Data) = findRenderUnitWithState(pass1State, STATE_UNKNOWN)

      testLithoView.setRoot(drawable(Color.BLUE))
      testLithoView.setRoot(drawable(Color.BLUE))
      val pass3State = testLithoView.componentTree.mainThreadLayoutState!!
      val (pass3Unit, pass3Data) = findRenderUnitWithState(pass3State, STATE_UPDATED)

      val result =
          MountSpecLithoRenderUnit.shouldUpdateMountItem(pass1Unit, pass3Unit, pass1Data, pass3Data)
      assertThat(result).isFalse()
    } finally {
      ComponentsConfiguration.useMountSpecContentVersion = useMountSpecContentVersion
    }
  }

  private fun drawable(color: Int): Component =
      TestDrawableComponent.create(lithoTestRule.context, true, false, false).color(color).build()

  private fun text(rtl: Boolean): Component =
      Column.create(lithoTestRule.context)
          .layoutDirection(if (rtl) YogaDirection.RTL else YogaDirection.LTR)
          .child(
              TextComponent.create(lithoTestRule.context)
                  .text("abc")
                  .alignment(TextAlignment.LAYOUT_START)
                  .widthPx(200)
                  .heightPx(50),
          )
          .build()

  private fun mountedColor(testLithoView: TestLithoView): Int =
      testLithoView.lithoView.drawables.filterIsInstance<ColorDrawable>().single().color

  private fun mountedTextAlignment(testLithoView: TestLithoView): Layout.Alignment? =
      testLithoView.lithoView.drawables.filterIsInstance<TextDrawable>().single().layout?.alignment

  private fun expectedTextAlignment(rtl: Boolean): Layout.Alignment? = mountedTextAlignment(
      lithoTestRule.render(widthSpec = exactly(300), heightSpec = exactly(300)) { text(rtl) },
  )

  private fun findRenderUnitWithState(
      layoutState: LayoutState,
      @MountSpecLithoRenderUnit.UpdateState targetState: Int,
  ): Pair<MountSpecLithoRenderUnit, Any?> {
    for (i in 1 until layoutState.getMountableOutputCount()) {
      val node = layoutState.getMountableOutputAt(i)!!
      if (node.renderUnit is MountSpecLithoRenderUnit) {
        val ru = node.renderUnit as MountSpecLithoRenderUnit
        if (ru.updateState == targetState) {
          return ru to node.layoutData
        }
      }
    }
    throw AssertionError("No render unit with state $targetState found")
  }
}
