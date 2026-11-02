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

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView.ScaleType
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.litho.view.onClick
import com.facebook.litho.view.onTouch
import com.facebook.litho.view.testKey
import com.facebook.litho.widget.PrimitiveImage
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.litho.widget.Text
import com.facebook.rendercore.px
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LithoViewTestHelperTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  @Before
  fun skipIfNonDebug() {
    Assume.assumeTrue(
        "These tests cover debug functionality and can only be run for debug builds.",
        LithoDebugConfigurations.isDebugModeEnabled,
    )
  }

  @Test
  fun testBasicViewToString() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            return SimpleMountSpecTester(style = Style.width(100.px).height(100.px))
          }
        }
    val lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(component)
    lithoView.measure(unspecified(), unspecified())
    val string = LithoViewTestHelper.viewToString(lithoView)
    assertThat(string)
        .containsPattern(
            """
            litho.InlineLayout\{\w+ V.E...... .. 0,0-100,100\}
              litho.SimpleMountSpecTester\{\w+ V.E...... .. 0,0-100,100\}
            """
                .trimIndent(),
        )
  }

  @Test
  fun testBasicRootInstanceToStringWithDepth() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            return SimpleMountSpecTester(style = Style.width(100.px).height(100.px))
          }
        }
    val lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(component)
    lithoView.measure(unspecified(), unspecified())
    val root = DebugComponent.getRootInstance(lithoView)
    val string =
        LithoViewTestHelper.rootInstanceToString(root, false /* embedded */, 1 /* string depth */)
    assertThat(string)
        .containsPattern(
            """
            \n  litho.InlineLayout\{\w+ V.E...... .. 0,0-100,100}
                litho.SimpleMountSpecTester\{\w+ V.E...... .. 0,0-100,100}
            """
                .trimIndent(),
        )
  }

  @Test
  fun viewToStringWithText() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            return Column.create(c)
                .child(
                    SimpleMountSpecTester(
                        style = Style.testKey("test-drawable").width(100.px).height(100.px),
                    ),
                )
                .child(Text.create(c).widthPx(100).heightPx(100).text("Hello, World"))
                .build()
          }
        }
    val lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(component)
    lithoView.measure(unspecified(), unspecified())
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)
    val string = LithoViewTestHelper.viewToString(lithoView)
    assertThat(string)
        .containsPattern(
            """
            litho.InlineLayout\{\w+ V.E...... .. 0,0-100,200\}
              litho.Column\{\w+ V.E...... .. 0,0-100,200\}
                litho.SimpleMountSpecTester\{\w+ V.E...... .. 0,0-100,100 litho:id/test-drawable}
                litho.Text\{\w+ V.E...... .. 0,100-100,200}
                  litho.TextComponent\{\w+ V.E...... .. 0,0-100,100 text="Hello, World"}
            """
                .trimIndent(),
        )
  }

  @Test
  fun viewToString_whenDrawablePaintsInsideItsLayoutBox_printsPaintedBounds() {
    val drawable = GradientDrawable().apply { setSize(20, 10) }
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            return PrimitiveImage(
                drawable = drawable,
                scaleType = ScaleType.CENTER_INSIDE,
                style = Style.width(100.px).height(100.px),
            )
          }
        }
    val lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(component)
    lithoView.measure(unspecified(), unspecified())
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)

    val string = LithoViewTestHelper.viewToString(lithoView)

    assertThat(string).containsPattern("""0,0-100,100 painted=40,45-60,55\}""")
  }

  @Test
  fun viewToString_whenDrawableFillsItsLayoutBox_omitsPaintedBounds() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            return SimpleMountSpecTester(style = Style.width(100.px).height(100.px))
          }
        }
    val lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(component)
    lithoView.measure(unspecified(), unspecified())
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)

    val string = LithoViewTestHelper.viewToString(lithoView)

    assertThat(string).doesNotContain("painted=")
  }

  @Test
  fun viewToStringForE2E_withExtraDescription_componentKeyIsPrinted() {
    val c = lithoTestRule.context
    val component: Component =
        Column.create(c)
            .key("column")
            .child(
                key("simple") { SimpleMountSpecTester(style = Style.width(100.px).height(100.px)) },
            )
            .child(Text.create(c).key("text").widthPx(100).heightPx(100).text("Hello, World"))
            .build()

    val string =
        LithoViewTestHelper.viewToStringForE2E(
            lithoTestRule.render { component }.lithoView,
            0,
            false,
        ) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }
    assertThat(string)
        .containsPattern(
            """
            litho.Column\{\w+ V.E...... .. 0,0-1080,200, key=column}
              litho.SimpleMountSpecTester\{\w+ V.E...... .. 0,0-100,100, key=simple}
              litho.Text\{\w+ V.E...... .. 0,100-100,200, key=text}
                litho.TextComponent\{\w+ V.E...... .. 0,0-100,100 text="Hello, World", key=null}

            """
                .trimIndent(),
        )
  }

  @Test
  fun `when root Component uses sizes then TestHelper toString should contain nested tree with children`() {
    val view =
        lithoTestRule
            .render {
              ComponentContainerWithSize(
                  component =
                      Column {
                        child(TextHolderComponent())
                        child(TextHolderComponent())
                      },
              )
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.ComponentContainerWithSize\{\w+ V.E...... .. 0,0-1080,82, key=null}
              litho.RenderWithConstraints\{\w+ V.E...... .. 0,0-1080,82, key=null}
                litho.Column\{\w+ V.E...... .. 0,0-1080,82, key=null}
                  litho.TextHolderComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                    litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
                  litho.TextHolderComponent\{\w+ V.E...... .. 0,41-1080,82, key=null}
                    litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  @Test
  fun `when root Component uses sizes then TestHelper toString should contain delegated nested tree with children`() {
    val view =
        lithoTestRule
            .render {
              ComponentContainerWithSize(
                  component =
                      DelegatingComponent(
                          component =
                              Column {
                                child(TextHolderComponent())
                                child(TextHolderComponent())
                              },
                      ),
              )
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.ComponentContainerWithSize\{\w+ V.E...... .. 0,0-1080,82, key=null}
              litho.RenderWithConstraints\{\w+ V.E...... .. 0,0-1080,82, key=null}
                litho.DelegatingComponent\{\w+ V.E...... .. 0,0-1080,82, key=null}
                  litho.Column\{\w+ V.E...... .. 0,0-1080,82, key=null}
                    litho.TextHolderComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                        litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
                    litho.TextHolderComponent\{\w+ V.E...... .. 0,41-1080,82, key=null}
                      litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                        litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}

            """
                .trimIndent(),
        )
  }

  @Test
  fun `when root Component uses sizes then TestHelper toString should contain nested tree`() {
    val view =
        lithoTestRule.render { ComponentContainerWithSize(component = ParentComponent()) }.lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.ComponentContainerWithSize\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.RenderWithConstraints\{\w+ V.E...... .. 0,0-1080,41, key=null}
                litho.ParentComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                  litho.TextHolderComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                    litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  @Test
  fun `when child Component uses sizes then TestHelper toString should contain nested tree`() {
    val view =
        lithoTestRule
            .render { Column { child(ComponentContainerWithSize(component = ParentComponent())) } }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.Column\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.ComponentContainerWithSize\{\w+ V.E...... .. 0,0-1080,41, key=null}
                litho.RenderWithConstraints\{\w+ V.E...... .. 0,0-1080,41, key=null}
                  litho.ParentComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                    litho.TextHolderComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                        litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  @Test
  fun `when delegated root Component uses sizes then TestHelper toString should contain nested tree`() {
    val view =
        lithoTestRule
            .render {
              DelegatingComponent(
                  component = ComponentContainerWithSize(component = ParentComponent()),
              )
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.DelegatingComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.ComponentContainerWithSize\{\w+ V.E...... .. 0,0-1080,41, key=null}
                litho.RenderWithConstraints\{\w+ V.E...... .. 0,0-1080,41, key=null}
                  litho.ParentComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                    litho.TextHolderComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                        litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  @Test
  fun `when delegated child Component uses sizes then TestHelper toString should contain nested tree`() {
    val view =
        lithoTestRule
            .render {
              Column {
                child(
                    DelegatingComponent(
                        component = ComponentContainerWithSize(component = ParentComponent()),
                    ),
                )
              }
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.Column\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.DelegatingComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                litho.ComponentContainerWithSize\{\w+ V.E...... .. 0,0-1080,41, key=null}
                  litho.RenderWithConstraints\{\w+ V.E...... .. 0,0-1080,41, key=null}
                    litho.ParentComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.TextHolderComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                        litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                          litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  @Test
  fun `when leaf component is measured then TestHelper toString should contain the leaf node`() {
    val view =
        lithoTestRule
            .render {
              MeasuringComponent(
                  component = Text(text = "hello", style = Style.testKey("test-key")),
              )
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.MeasuringComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.Text\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key, key=null}
                litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}

            """
                .trimIndent(),
        )
  }

  @Test
  fun `when delegated leaf component is measured then TestHelper toString should contain the leaf node`() {
    val view =
        lithoTestRule
            .render {
              MeasuringComponent(
                  component =
                      DelegatingComponent(
                          component = Text(text = "hello", style = Style.testKey("test-key")),
                      ),
              )
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.MeasuringComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.DelegatingComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                  litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  @Test
  fun `when leaf component is delegated measured then TestHelper toString should contain the leaf node`() {
    val view =
        lithoTestRule
            .render {
              DelegatingComponent(component = MeasuringComponent(component = TextHolderComponent()))
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.DelegatingComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.MeasuringComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                litho.TextHolderComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                  litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                    litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  @Test
  fun `when delegated component is measured as child then TestHelper toString should contain all the components`() {
    val view =
        lithoTestRule
            .render {
              Column {
                child(
                    DelegatingComponent(
                        component =
                            MeasuringComponent(
                                component =
                                    DelegatingComponent(
                                        component =
                                            Text(text = "hello", style = Style.testKey("test-key")),
                                    ),
                            ),
                    ),
                )
              }
            }
            .lithoView

    val string =
        LithoViewTestHelper.viewToStringForE2E(view, 0, false) { debugComponent, sb ->
          sb.append(", key=").append(debugComponent.key)
        }

    assertThat(string)
        .containsPattern(
            """
            litho.Column\{\w+ V.E...... .. 0,0-1080,41, key=null}
              litho.DelegatingComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                litho.MeasuringComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                  litho.DelegatingComponent\{\w+ V.E...... .. 0,0-1080,41, key=null}
                    litho.Text\{\w+ V.E...... .. 0,0-1080,41, key=null}
                      litho.TextComponent\{\w+ V.E...... .. 0,0-1080,41 litho:id/test-key text="hello", key=null}
            """
                .trimIndent(),
        )
  }

  class DelegatingComponent(val component: Component) : KComponent() {
    override fun ComponentScope.render(): Component {
      return component
    }
  }

  class ParentComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      return TextHolderComponent()
    }
  }

  class TextHolderComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      return Text(text = "hello", style = Style.testKey("test-key"))
    }
  }

  class MeasuringComponent(val component: Component) : KComponent() {
    override fun ComponentScope.render(): Component {
      component.measure(context, unspecified(), unspecified(), Size())
      return component
    }
  }

  @Test
  fun viewToStringForE2E_withClickHandler_showsClickableFlag() {
    val view = lithoTestRule.render { ClickableComponent() }.lithoView

    val string = LithoViewTestHelper.viewToStringForE2E(view, 0, false) { _, _ -> }

    assertThat(string).containsPattern("""litho.TextComponent\{\w+ V.E...C.. ..""")
  }

  @Test
  fun viewToStringForE2E_withTouchHandler_showsTouchableFlag() {
    val view = lithoTestRule.render { TouchableComponent() }.lithoView

    val string = LithoViewTestHelper.viewToStringForE2E(view, 0, false) { _, _ -> }

    assertThat(string).containsPattern("""litho.TextComponent\{\w+ V.E.....T ..""")
  }

  @Test
  fun viewToStringForE2E_withBothHandlers_showsBothFlags() {
    val view = lithoTestRule.render { ClickableAndTouchableComponent() }.lithoView

    val string = LithoViewTestHelper.viewToStringForE2E(view, 0, false) { _, _ -> }

    assertThat(string).containsPattern("""litho.TextComponent\{\w+ V.E...C.T ..""")
  }

  class ClickableComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      return Text(text = "click me", style = Style.testKey("click-key").onClick {})
    }
  }

  class TouchableComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      return Text(text = "touch me", style = Style.testKey("touch-key").onTouch { true })
    }
  }

  class ClickableAndTouchableComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      return Text(
          text = "click and touch me",
          style = Style.testKey("both-key").onClick {}.onTouch { true },
      )
    }
  }

  private class ComponentContainerWithSize(private val component: Component) : KComponent() {
    override fun ComponentScope.render(): Component {
      return RenderWithConstraints { constraints ->
        val outputSize = Size()
        component.measure(
            context,
            constraints.toWidthSpec(),
            constraints.toHeightSpec(),
            outputSize,
        )
        component
      }
    }
  }
}
