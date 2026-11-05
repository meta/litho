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

package com.facebook.rendercore

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import com.facebook.rendercore.extensions.RenderCoreExtension
import com.facebook.rendercore.geometry.Point
import com.facebook.rendercore.renderunits.HostRenderUnit
import com.facebook.rendercore.testing.DrawableWrapperUnit
import com.facebook.rendercore.testing.LayoutResultWrappingNode
import com.facebook.rendercore.testing.RenderCoreTestRule
import com.facebook.rendercore.testing.SimpleLayoutResult
import com.facebook.rendercore.testing.TestLayoutResultVisitor
import com.facebook.rendercore.testing.TestMountExtension
import com.facebook.rendercore.testing.TestRenderCoreExtension
import com.facebook.rendercore.testing.ViewAssertions
import com.facebook.rendercore.testing.ViewWrapperUnit
import com.facebook.rendercore.testing.match.ViewMatchNode
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewHierarchyTest {

  @Rule @JvmField val renderCoreTestRule = RenderCoreTestRule()

  @Test
  fun onRenderSimpleLayoutResult_shouldRenderTheView() {
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(renderCoreTestRule.context), 1))
            .width(100)
            .height(100)
            .build()
    renderCoreTestRule.useRootNode(LayoutResultWrappingNode(root)).render()
    ViewAssertions.assertThat(renderCoreTestRule.rootHost as View)
        .matches(
            ViewMatchNode.forType(Host::class.java)
                .child(ViewMatchNode.forType(TextView::class.java).bounds(0, 0, 100, 100)),
        )
  }

  @Test
  fun onRenderSimpleLayoutResult_shouldRenderTheDrawable() {
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(DrawableWrapperUnit(ColorDrawable(Color.BLACK), 1))
            .width(100)
            .height(100)
            .build()
    renderCoreTestRule.useRootNode(LayoutResultWrappingNode(root)).render()
    ViewAssertions.assertThat(renderCoreTestRule.rootHost as View)
        .matches(ViewMatchNode.forType(Host::class.java).bounds(0, 0, 100, 100))
    val host = renderCoreTestRule.rootHost as HostView
    Assertions.assertThat(host.mountItemCount).describedAs("Number mounted items").isEqualTo(1)
    val item = host.getMountItemAt(0)
    Assertions.assertThat(item.content)
        .describedAs("Mounted item")
        .isInstanceOf(ColorDrawable::class.java)
  }

  @Test
  fun onRenderNestedLayoutResultsWithoutHostRenderUnits_shouldRenderTheView() {
    val c = renderCoreTestRule.context
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(200)
            .height(200)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 2))
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                    .x(100)
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .x(50)
                    .y(100)
                    .width(100)
                    .height(100)
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(ViewWrapperUnit(TextView(c), 4))
                            .width(100)
                            .height(100),
                    ),
            )
            .build()
    renderCoreTestRule.useRootNode(LayoutResultWrappingNode(root)).render()
    ViewAssertions.assertThat(renderCoreTestRule.rootHost as View)
        .matches(
            ViewMatchNode.forType(Host::class.java)
                .child(
                    ViewMatchNode.forType(TextView::class.java)
                        .bounds(0, 0, 100, 100)
                        .absoluteBoundsForRootType(0, 0, 100, 100, RootHost::class.java),
                )
                .child(
                    ViewMatchNode.forType(TextView::class.java)
                        .bounds(100, 0, 100, 100)
                        .absoluteBoundsForRootType(100, 0, 100, 100, RootHost::class.java),
                )
                .child(
                    ViewMatchNode.forType(TextView::class.java)
                        .bounds(50, 100, 100, 100)
                        .absoluteBoundsForRootType(50, 100, 100, 100, RootHost::class.java),
                ),
        )
  }

  @Test
  fun onRenderNestedLayoutResultsWithoutHostRenderUnits_shouldRenderTheViewsAndDrawables() {
    val c = renderCoreTestRule.context
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(240)
            .height(240)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(DrawableWrapperUnit(ColorDrawable(Color.BLACK), 2))
                    .padding(5, 5, 5, 5)
                    .width(120)
                    .height(120),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(DrawableWrapperUnit(ColorDrawable(Color.BLUE), 3))
                    .x(5)
                    .y(5)
                    .width(110)
                    .height(110),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 4))
                    .x(10)
                    .y(10)
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(DrawableWrapperUnit(ColorDrawable(Color.BLUE), 5))
                    .x(15)
                    .y(15)
                    .width(95)
                    .height(95),
            )
            .build()
    renderCoreTestRule.useRootNode(LayoutResultWrappingNode(root)).render()
    ViewAssertions.assertThat(renderCoreTestRule.rootHost as View)
        .matches(
            ViewMatchNode.forType(Host::class.java)
                .child(
                    ViewMatchNode.forType(TextView::class.java)
                        .bounds(10, 10, 100, 100)
                        .absoluteBoundsForRootType(10, 10, 100, 100, RootHost::class.java),
                ),
        )
    val host = renderCoreTestRule.rootHost as HostView
    Assertions.assertThat(host.mountItemCount).describedAs("Number mounted items").isEqualTo(4)
    Assertions.assertThat(host.getMountItemAt(0).content)
        .describedAs("Mounted item")
        .isInstanceOf(ColorDrawable::class.java)
    Assertions.assertThat((host.getMountItemAt(0).content as Drawable).bounds)
        .describedAs("Drawable bounds are")
        .isEqualTo(Rect(5, 5, 115, 115))
    Assertions.assertThat(host.getMountItemAt(1).content)
        .describedAs("Mounted item")
        .isInstanceOf(ColorDrawable::class.java)
    Assertions.assertThat(host.getMountItemAt(3).content)
        .describedAs("Mounted item")
        .isInstanceOf(ColorDrawable::class.java)
  }

  @Test
  fun onRenderNestedLayoutResultsWithHostRenderUnits_shouldRenderTheView() {
    val c = renderCoreTestRule.context
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(200)
            .height(200)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 2))
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                    .x(100)
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(HostRenderUnit(4))
                    .x(50)
                    .y(100)
                    .width(100)
                    .height(100)
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(ViewWrapperUnit(TextView(c), 5))
                            .width(100)
                            .height(100),
                    ),
            )
            .build()
    renderCoreTestRule.useRootNode(LayoutResultWrappingNode(root)).render()
    ViewAssertions.assertThat(renderCoreTestRule.rootHost as View)
        .matches(
            ViewMatchNode.forType(Host::class.java)
                .child(
                    ViewMatchNode.forType(TextView::class.java)
                        .bounds(0, 0, 100, 100)
                        .absoluteBoundsForRootType(0, 0, 100, 100, RootHost::class.java),
                )
                .child(
                    ViewMatchNode.forType(TextView::class.java)
                        .bounds(100, 0, 100, 100)
                        .absoluteBoundsForRootType(100, 0, 100, 100, RootHost::class.java),
                )
                .child(
                    ViewMatchNode.forType(Host::class.java)
                        .bounds(50, 100, 100, 100)
                        .child(
                            ViewMatchNode.forType(TextView::class.java)
                                .bounds(0, 0, 100, 100)
                                .absoluteBoundsForRootType(50, 100, 100, 100, RootHost::class.java),
                        ),
                ),
        )
  }

  @Test
  fun onRenderDeeplyNestedMultiHostLayoutResults_shouldRenderTheView() {
    val c = renderCoreTestRule.context
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(1000)
            .height(1000)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 1))
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .y(100)
                    .width(400)
                    .height(400)
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(ViewWrapperUnit(TextView(c), 2))
                            .x(100)
                            .y(100)
                            .width(100)
                            .height(100),
                    )
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(HostRenderUnit(3))
                            .x(200)
                            .y(200)
                            .width(200)
                            .height(200)
                            .child(
                                SimpleLayoutResult.create()
                                    .renderUnit(ViewWrapperUnit(TextView(c), 4))
                                    .x(100)
                                    .y(100)
                                    .width(100)
                                    .height(100),
                            ),
                    ),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(HostRenderUnit(5))
                    .y(400)
                    .width(400)
                    .height(400)
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(ViewWrapperUnit(TextView(c), 6))
                            .x(100)
                            .y(100)
                            .width(100)
                            .height(100),
                    )
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(HostRenderUnit(7))
                            .x(200)
                            .y(200)
                            .width(200)
                            .height(200)
                            .child(
                                SimpleLayoutResult.create()
                                    .renderUnit(ViewWrapperUnit(TextView(c), 8))
                                    .x(100)
                                    .y(100)
                                    .width(100)
                                    .height(100),
                            ),
                    ),
            )
            .build()
    val extension = TestRenderCoreExtension()
    renderCoreTestRule
        .useExtensions(arrayOf<RenderCoreExtension<*, *>>(extension))
        .useRootNode(LayoutResultWrappingNode(root))
        .render()
    ViewAssertions.assertThat(renderCoreTestRule.rootHost as View)
        .matches(
            ViewMatchNode.forType(Host::class.java)
                .child(ViewMatchNode.forType(TextView::class.java).bounds(0, 0, 100, 100))
                .child(
                    ViewMatchNode.forType(TextView::class.java)
                        .bounds(100, 200, 100, 100)
                        .absoluteBoundsForRootType(100, 200, 100, 100, RootHost::class.java),
                )
                .child(
                    ViewMatchNode.forType(Host::class.java)
                        .bounds(200, 300, 200, 200)
                        .absoluteBoundsForRootType(200, 300, 200, 200, RootHost::class.java)
                        .child(
                            ViewMatchNode.forType(TextView::class.java)
                                .bounds(100, 100, 100, 100)
                                .absoluteBoundsForRootType(
                                    300,
                                    400,
                                    100,
                                    100,
                                    RootHost::class.java,
                                ),
                        ),
                )
                .child(
                    ViewMatchNode.forType(Host::class.java)
                        .bounds(0, 400, 400, 400)
                        .absoluteBoundsForRootType(0, 400, 400, 400, RootHost::class.java)
                        .child(
                            ViewMatchNode.forType(TextView::class.java)
                                .bounds(100, 100, 100, 100)
                                .absoluteBoundsForRootType(
                                    100,
                                    500,
                                    100,
                                    100,
                                    RootHost::class.java,
                                ),
                        )
                        .child(
                            ViewMatchNode.forType(Host::class.java)
                                .bounds(200, 200, 200, 200)
                                .absoluteBoundsForRootType(200, 600, 200, 200, RootHost::class.java)
                                .child(
                                    ViewMatchNode.forType(TextView::class.java)
                                        .bounds(100, 100, 100, 100)
                                        .absoluteBoundsForRootType(
                                            300,
                                            700,
                                            100,
                                            100,
                                            RootHost::class.java,
                                        ),
                                ),
                        ),
                ),
        )
    val e = extension.getMountExtension() as TestMountExtension?
    Assertions.assertThat(e).isNotNull
    Assertions.assertThat(e?.input).isNotNull
    Assertions.assertThat(e?.input as List<*>).hasSize(11)
    val results: List<TestLayoutResultVisitor.Result> =
        e.input as List<TestLayoutResultVisitor.Result>
    Assertions.assertThat(results[0].x).isEqualTo(0)
    Assertions.assertThat(results[0].y).isEqualTo(0)
    Assertions.assertThat(results[1].x).isEqualTo(0)
    Assertions.assertThat(results[1].y).isEqualTo(0)
    Assertions.assertThat(results[2].x).isEqualTo(0)
    Assertions.assertThat(results[2].y).isEqualTo(0)
    Assertions.assertThat(results[3].x).isEqualTo(0)
    Assertions.assertThat(results[3].y).isEqualTo(100)
    Assertions.assertThat(results[4].x).isEqualTo(100)
    Assertions.assertThat(results[4].y).isEqualTo(200)
    Assertions.assertThat(results[5].x).isEqualTo(200)
    Assertions.assertThat(results[5].y).isEqualTo(300)
    Assertions.assertThat(results[6].x).isEqualTo(300)
    Assertions.assertThat(results[6].y).isEqualTo(400)
    Assertions.assertThat(results[7].x).isEqualTo(0)
    Assertions.assertThat(results[7].y).isEqualTo(400)
    Assertions.assertThat(results[8].x).isEqualTo(100)
    Assertions.assertThat(results[8].y).isEqualTo(500)
    Assertions.assertThat(results[9].x).isEqualTo(200)
    Assertions.assertThat(results[9].y).isEqualTo(600)
    Assertions.assertThat(results[10].x).isEqualTo(300)
    Assertions.assertThat(results[10].y).isEqualTo(700)
  }

  @Test
  fun `when getting the relative position of a LayoutResult then it should return the correct Position`() {
    val c = renderCoreTestRule.context
    val target: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 3))
            .x(100)
            .y(100)
            .width(100)
            .height(100)
            .build()
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(500)
            .height(500)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 2))
                    .width(100)
                    .height(100),
            )
            .child(SimpleLayoutResult.create().x(100).y(100).width(300).height(300).child(target))
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(HostRenderUnit(4))
                    .y(400)
                    .width(100)
                    .height(100)
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(ViewWrapperUnit(TextView(c), 5))
                            .width(100)
                            .height(100),
                    ),
            )
            .build()

    val position = root.getRelativePositionOf(target = target)

    assertThat(position).isEqualTo(Point(200f, 200f))
  }

  @Test
  fun `when getting the relative position of the root then it should return Zero`() {
    val c = renderCoreTestRule.context
    val target: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 3))
            .x(100)
            .y(100)
            .width(100)
            .height(100)
            .build()

    val position = target.getRelativePositionOf(target = target)

    assertThat(position).isEqualTo(Point.Zero)
  }

  @Test
  fun `when getting the relative position of non existent LayoutResult then it should throw an error`() {
    val c = renderCoreTestRule.context
    val target: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 3))
            .x(100)
            .y(100)
            .width(100)
            .height(100)
            .build()

    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 3))
            .x(100)
            .y(100)
            .width(100)
            .height(100)
            .build()

    var e: Exception? = null
    try {
      root.getRelativePositionOf(target = target)
    } catch (ex: Exception) {
      e = ex
    }

    assertThat(e).isNotNull
    assertThat(e?.message).isEqualTo(LayoutResultPositionCalculator.TARGET_NOT_FOUND_MESSAGE)
  }
}
