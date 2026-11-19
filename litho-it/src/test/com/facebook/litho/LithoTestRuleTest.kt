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

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.LithoTestRuleResizeMode
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.alpha
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import com.facebook.litho.view.wrapInView
import com.facebook.litho.widget.TextDrawable
import com.facebook.litho.widget.treeprops.SimpleTreeProp
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class LithoTestRuleTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()
  @JvmField
  @Rule
  val manualResizeLithoTestRule = LithoTestRule(resizeMode = LithoTestRuleResizeMode.MANUAL)

  @JvmField
  @Rule
  val lithoTestRuleWithContextSpecified =
      LithoTestRule(androidContext = Robolectric.buildActivity(Activity::class.java).create().get())

  @JvmField @Rule val realWindowLithoTestRule = LithoTestRule(attachToRealWindow = true)

  @Test
  fun onLithoTestRuleWithTreeProp_shouldPropagateTreeProp() {
    val component = ComponentWithTreeProp()
    val testLithoView =
        lithoTestRule.setTreeProp(SimpleTreeProp::class.java, SimpleTreeProp("test")).render {
          component
        }

    val item = testLithoView.lithoView.getMountItemAt(0).content
    assertThat(item).isInstanceOf(TextDrawable::class.java)
    assertThat((item as TextDrawable).text).isEqualTo("test")
  }

  @Test(expected = RuntimeException::class)
  fun onLithoTestRuleWithoutTreeProp_shouldThrowException() {
    val component = ComponentWithTreeProp()
    lithoTestRule.createTestLithoView().attachToWindow().setRoot(component).measure().layout()
  }

  @Test
  fun onLithoTestRuleExceptionOnBackgroundThread_shouldPropagateExceptionImmediately() {
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component? {
        val randomState = useState { false }
        if (randomState.value) {
          throw Exception("Hi There!")
        }
        return Row(style = Style.width(100.px).height(100.px)) {
          child(Text(text = "some_other_text", style = Style.onClick { randomState.update(true) }))
        }
      }
    }

    val testLithoView = lithoTestRule.render { TestComponent() }
    val thrown: Throwable = Assertions.catchThrowable {
      lithoTestRule.act(testLithoView) { clickOnText("some_other_text") }
    }

    assertThat(thrown).isInstanceOf(RuntimeException::class.java)
    assertThat((thrown.stackTraceToString()).contains("Timed out!")).isFalse
    assertThat(thrown).hasStackTraceContaining("Hi There!")
  }

  @Test
  fun `given dynamic size component when size is updated then view has updated size on resetting root`() {
    val testLithoView = lithoTestRule.render {
      ComponentWithDynamicSize(width = 100.px, height = 100.px, alpha = 1f)
    }

    testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG).run {
      assertThat(width).isEqualTo(100)
      assertThat(height).isEqualTo(100)
      assertThat(alpha).isEqualTo(1f)
    }

    val updatedWidth = 200
    val updatedHeight = 300
    val updatedAlpha = 0.5f
    testLithoView.setRoot(
        ComponentWithDynamicSize(
            width = updatedWidth.px,
            height = updatedHeight.px,
            alpha = updatedAlpha,
        ),
    )

    with(testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG)) {
      assertThat(width).isEqualTo(updatedWidth)
      assertThat(height).isEqualTo(updatedHeight)
      assertThat(alpha).isEqualTo(updatedAlpha)
    }
  }

  @Test
  fun `given rule with manual resizing and dynamic size component when size is updated then view size not updated on resetting root`() {
    val width = 100
    val height = 200
    val alpha = 1f
    val testLithoView = manualResizeLithoTestRule.render {
      ComponentWithDynamicSize(width.px, height.px, alpha)
    }

    with(testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG)) {
      assertThat(this.width).isEqualTo(width)
      assertThat(this.height).isEqualTo(height)
      assertThat(this.alpha).isEqualTo(alpha)
    }

    testLithoView.setRoot(ComponentWithDynamicSize(width = 200.px, height = 300.px, alpha = .5f))

    with(testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG)) {
      assertThat(this.width).isEqualTo(width)
      assertThat(this.height).isEqualTo(height)
      assertThat(this.alpha).isEqualTo(alpha)
    }
  }

  @Test
  fun `given dynamic size component when size is updated then view has updated size on resetting root async`() {
    val testLithoView = lithoTestRule.render {
      ComponentWithDynamicSize(width = 100.px, height = 100.px, alpha = 1f)
    }

    testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG).run {
      assertThat(width).isEqualTo(100)
      assertThat(height).isEqualTo(100)
      assertThat(alpha).isEqualTo(1f)
    }

    val updatedWidth = 200
    val updatedHeight = 300
    val updatedAlpha = 0.5f
    testLithoView.setRootAsync(
        ComponentWithDynamicSize(
            width = updatedWidth.px,
            height = updatedHeight.px,
            alpha = updatedAlpha,
        ),
    )

    with(testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG)) {
      assertThat(width).isEqualTo(updatedWidth)
      assertThat(height).isEqualTo(updatedHeight)
      assertThat(alpha).isEqualTo(updatedAlpha)
    }
  }

  @Test
  fun `given rule with manual resizing and dynamic size component when size is updated then view size not updated on resetting root async`() {
    val width = 100
    val height = 200
    val alpha = 1f
    val testLithoView = manualResizeLithoTestRule.render {
      ComponentWithDynamicSize(width.px, height.px, alpha)
    }

    with(testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG)) {
      assertThat(this.width).isEqualTo(width)
      assertThat(this.height).isEqualTo(height)
      assertThat(this.alpha).isEqualTo(alpha)
    }

    testLithoView.setRootAsync(
        ComponentWithDynamicSize(width = 200.px, height = 300.px, alpha = .5f),
    )

    with(testLithoView.findViewWithTag(ComponentWithDynamicSize.VIEW_TAG)) {
      assertThat(this.width).isEqualTo(width)
      assertThat(this.height).isEqualTo(height)
      assertThat(this.alpha).isEqualTo(alpha)
    }
  }

  @Test
  fun `idleAndLayout uses the latest size specs when a LithoView is reused`() {
    val first = lithoTestRule.render(widthPx = 100, heightPx = 100) { Text("test") }
    val current =
        lithoTestRule.render(lithoView = first.lithoView, widthPx = 200, heightPx = 300) {
          Text("test")
        }

    current.lithoView.requestLayout()
    lithoTestRule.idleAndLayout()

    assertThat(current.lithoView.width).isEqualTo(200)
    assertThat(current.lithoView.height).isEqualTo(300)
  }

  @Test
  fun `idleAndLayout uses the latest size specs when a wrapper switches LithoViews`() {
    val first = lithoTestRule.render(widthPx = 100, heightPx = 100) { Text("test") }
    val current = lithoTestRule.render(widthPx = 200, heightPx = 300) { Text("test") }
    current.useLithoView(first.lithoView).measure().layout()

    current.lithoView.requestLayout()
    lithoTestRule.idleAndLayout()

    assertThat(current.lithoView.width).isEqualTo(200)
    assertThat(current.lithoView.height).isEqualTo(300)
  }

  @Test
  fun `idle leaves layout requests pending and idleAndLayout lays out every rendered view`() {
    val first = lithoTestRule.render(widthPx = 100, heightPx = 100) { Text("first") }
    val second = lithoTestRule.render(widthPx = 200, heightPx = 200) { Text("second") }
    first.setSizePx(110, 120)
    second.setSizePx(210, 220)
    first.lithoView.requestLayout()
    second.lithoView.requestLayout()

    lithoTestRule.idle()
    assertThat(first.lithoView.width).isEqualTo(100)
    assertThat(first.lithoView.height).isEqualTo(100)
    assertThat(second.lithoView.width).isEqualTo(200)
    assertThat(second.lithoView.height).isEqualTo(200)

    lithoTestRule.idleAndLayout()

    assertThat(first.lithoView.width).isEqualTo(110)
    assertThat(first.lithoView.height).isEqualTo(120)
    assertThat(second.lithoView.width).isEqualTo(210)
    assertThat(second.lithoView.height).isEqualTo(220)
  }

  @Test
  fun `provided android context is used as render context`() {
    val testLithoView = lithoTestRuleWithContextSpecified.render { Text("test") }
    assertThat(testLithoView.lithoView.context)
        .isSameAs(lithoTestRuleWithContextSpecified.androidContext)
  }

  @Test
  fun `attachToRealWindow renders into a real window`() {
    val testLithoView = realWindowLithoTestRule.render { Text("test") }

    assertThat(testLithoView.lithoView.isAttachedToWindow).isTrue
    assertThat(testLithoView.lithoView.windowToken).isNotNull
  }

  @Test
  fun `attachToRealWindow keeps every rendered view attached`() {
    val first = realWindowLithoTestRule.render { Text("first") }
    val second = realWindowLithoTestRule.render { Text("second") }

    // Rendering again must not displace what is already in the window.
    assertThat(first.lithoView.isAttachedToWindow).isTrue
    assertThat(second.lithoView.isAttachedToWindow).isTrue
  }

  @Test
  fun `attachToRealWindow detaches on detachFromWindow`() {
    val testLithoView = realWindowLithoTestRule.render { Text("test") }

    testLithoView.detachFromWindow()

    assertThat(testLithoView.lithoView.isAttachedToWindow).isFalse
    assertThat(testLithoView.lithoView.parent).isNull()
  }

  @Test
  fun `attachToRealWindow rejects a non-Activity android context`() {
    Assertions.assertThatThrownBy {
          LithoTestRule(
              androidContext = ApplicationProvider.getApplicationContext(),
              attachToRealWindow = true,
          )
        }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("attachToRealWindow needs an Activity")
  }

  private class ComponentWithDynamicSize(
      private val width: Dimen,
      private val height: Dimen,
      private val alpha: Float,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val style = Style.width(width).height(height).alpha(alpha).viewTag(VIEW_TAG)

      return Row(style = Style.wrapInView()) { child(Row(style = style) { child(Text("test")) }) }
    }

    companion object {
      const val VIEW_TAG = "test_view"
    }
  }

  private class ComponentWithTreeProp : KComponent() {
    override fun ComponentScope.render(): Component {
      val treeProp = getTreeProp<SimpleTreeProp>()
      requireNotNull(treeProp) { "treeProp is a required prop for ComponentWithTreeProp." }
      return Column {
        child(com.facebook.litho.widget.Text.create(context).text(treeProp.name).build())
      }
    }
  }
}
