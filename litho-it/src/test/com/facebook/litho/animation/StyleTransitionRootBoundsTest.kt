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

@file:OptIn(com.facebook.litho.annotations.ExperimentalLithoApi::class)

package com.facebook.litho.animation

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.facebook.litho.BaseMountingView
import com.facebook.litho.Component
import com.facebook.litho.ComponentTree
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.LithoRenderer
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.LithoTestRuleResizeMode
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.viewTag
import com.facebook.litho.visibility.onVisible
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms
import com.facebook.rendercore.utils.MeasureSpecUtils.atMost
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class StyleTransitionRootBoundsTest {
  @Rule
  @JvmField
  val lithoTestRule = LithoTestRule(
      attachToRealWindow = true,
      resizeMode = LithoTestRuleResizeMode.MANUAL,
  )
  @Rule @JvmField val animationRule = LithoAnimationTestRule { flush() }
  private lateinit var view: TestLithoView
  private lateinit var rootView: BaseMountingView

  @Test fun heightChangesPreserveSizeBeforeMountAndReachTarget() = checkSizeChanges(width = false)

  @Test fun widthChangesPreserveSizeBeforeMountAndReachTarget() = checkSizeChanges(width = true)

  private fun checkSizeChanges(width: Boolean) {
    render(root(100, width))
    var initial = 100
    for (target in listOf(300, 100, 0, 300)) {
      view.setRoot(root(target, width))
      measure()
      assertThat(if (width) rootView.measuredWidth else rootView.measuredHeight).isEqualTo(initial)
      layout()
      val frames = mutableListOf(if (width) rootView.width else rootView.height)
      repeat(12) {
        animationRule.runNextFrame(16.ms)
        frames.add(if (width) rootView.width else rootView.height)
      }
      assertThat(if (target < initial) frames.reversed() else frames).isSorted()
      assertThat(frames).startsWith(initial).endsWith(target)
      // It animates through the frames rather than snapping to the target.
      assertThat(frames.distinct().size).isGreaterThan(3)
      initial = target
    }
  }

  @Test
  fun interruptedAnimationPreservesCurrentSizeAndRemovedTransitionSnaps() {
    render(root(100))
    update(root(300))
    animationRule.advanceBy(80.ms)
    val middle = rootView.height
    assertThat(middle).isBetween(101, 299)
    update(root(300, color = Color.BLUE))
    assertThat(rootView.height).isEqualTo(middle)
    update(root(100))
    assertThat(rootView.height).isEqualTo(middle)
    animationRule.advanceBy(160.ms)
    assertThat(rootView.height).isEqualTo(100)
    update(root(300))
    animationRule.advanceBy(80.ms)
    assertThat(rootView.height).isBetween(101, 299)
    update(root(100, declaresTransition = false))
    assertThat(rootView.height).isEqualTo(100)
    animationRule.advanceBy(160.ms)
    assertThat(rootView.height).isEqualTo(100)
  }

  @Test
  fun newlyDeclaredTransitionSnapsToTarget() {
    render(root(100, declaresTransition = false))
    update(root(300))
    assertThat(rootView.height).isEqualTo(300)
    animationRule.advanceBy(160.ms)
    assertThat(rootView.height).isEqualTo(300)
  }

  @Test
  fun treeSwapDoesNotInheritRunningAnimation() {
    render(root(100))
    update(root(300))
    animationRule.advanceBy(80.ms)
    assertThat(rootView.height).isBetween(101, 299)
    view.useComponentTree(ComponentTree.create(lithoTestRule.context, root(200)).build())
    flush()
    assertThat(rootView.height).isEqualTo(200)
    animationRule.advanceBy(160.ms)
    assertThat(rootView.height).isEqualTo(200)
  }

  @Test
  fun offscreenRootGrowsIntoVisibleArea() {
    render(root(100))
    rootView.translationY = -100f
    flush()
    assertThat(rootView.getLocalVisibleRect(Rect())).isFalse()
    update(root(300))
    assertThat(rootView.height).isEqualTo(100)
    animationRule.advanceBy(160.ms)
    assertThat(rootView.height).isEqualTo(300)
    assertThat(rootView.getLocalVisibleRect(Rect())).isTrue()
  }

  @Test
  fun offscreenAnimationDoesNotMountNewChildrenOrReportVisibility() {
    render(root(100))
    rootView.translationY = -1000f
    flush()
    var visibleCalls = 0
    val child = Column(
        style =
            Style.width(20.dp)
                .height(20.dp)
                .backgroundColor(Color.BLUE)
                .viewTag("child")
                .onVisible { visibleCalls++ },
    )
    update(root(300, child = child))
    animationRule.advanceBy(160.ms)
    assertThat(rootView.height).isEqualTo(300)
    assertThat(rootView.getLocalVisibleRect(Rect())).isFalse()
    assertThat(rootView.findViewWithTag<android.view.View>("child")).isNull()
    assertThat(visibleCalls).isZero()
    rootView.translationY = 0f
    flush()
    assertThat(rootView.findViewWithTag<android.view.View>("child")).isNotNull()
    assertThat(visibleCalls).isEqualTo(1)
  }

  @Test
  fun renderTreeRootPreservesSizeBeforeMountAndReopensFromZero() {
    val context = lithoTestRule.context.androidContext
    rootView = LithoRenderTreeView(context)
    (context as Activity).setContentView(
        rootView,
        FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
    )
    val renderer = LithoRenderer(context)
    renderer.bindTo(rootView)
    val constraints = SizeConstraints(minWidth = 100, maxWidth = 100, maxHeight = 1000)
    try {
      renderer.renderSync(root(100), constraints)
      flush()
      for ((initial, target) in listOf(100 to 300, 300 to 0, 0 to 300)) {
        renderer.renderSync(root(target), constraints)
        lithoTestRule.idle()
        measure()
        assertThat(rootView.measuredHeight).isEqualTo(initial)
        layout()
        animationRule.advanceBy(160.ms)
        assertThat(rootView.height).isEqualTo(target)
      }
    } finally {
      renderer.release()
    }
  }

  private fun render(component: Component) {
    view = lithoTestRule.render(widthSpec = atMost(1000), heightSpec = atMost(1000)) { component }
    rootView = view.lithoView
    rootView.layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    flush()
  }

  private fun update(component: Component) {
    view.setRoot(component)
    flush()
  }

  private fun measure() = rootView.measure(atMost(1000), atMost(1000))

  private fun layout() = rootView.layout(0, 0, rootView.measuredWidth, rootView.measuredHeight)

  private fun flush() {
    lithoTestRule.idle()
    measure()
    layout()
  }

  private fun root(
      size: Int,
      width: Boolean = false,
      declaresTransition: Boolean = true,
      color: Int = Color.RED,
      child: Component? = null,
  ): Component {
    val style =
        Style.width((if (width) size else 100).dp)
            .height((if (width) 100 else size).dp)
            .backgroundColor(color)
    return Column(
        style =
            if (declaresTransition)
                style.transition {
                  if (width) this.width = tween(160.ms, Easing.Linear)
                  else height = tween(160.ms, Easing.Linear)
                }
            else style,
    ) {
      child(child)
    }
  }
}
