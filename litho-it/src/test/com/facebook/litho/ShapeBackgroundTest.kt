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

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import com.facebook.litho.animated.alpha
import com.facebook.litho.animated.background
import com.facebook.litho.animated.backgroundColor
import com.facebook.litho.binders.viewBinder
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.drawable.ComparableCircleDrawable
import com.facebook.litho.drawable.ComparableColorDrawable
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.elevation
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.MountItem
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.dp
import com.facebook.rendercore.primitives.FixedSizeLayoutBehavior
import com.facebook.rendercore.primitives.Primitive
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode.NATIVE

/**
 * Framework-level checks for `Style.background(color, shape)`. Native graphics, because the legacy
 * Paint shadow does not take alpha from setColor, so it reports 0 for any color not given an
 * explicit setAlpha, including GradientDrawable's.
 */
@RequiresApi(Build.VERSION_CODES.N)
@GraphicsMode(NATIVE)
@RunWith(LithoTestRunner::class)
class ShapeBackgroundTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  // Code that fades a View's background sets alpha on the drawable itself. The circle is the View's
  // own background.

  @Test
  fun circle_drawsTheColorsColorAndAlpha() {
    val view = mountedPlainView(Style.background(HALF_RED, Shape.Circle))
    assertThat(pixelDrawnByView(view)).isEqualTo(HALF_RED)
  }

  @Test
  fun circle_atFullAlphaAfterFading_restoresTheColorsOwnAlpha() {
    val view = mountedPlainView(Style.background(HALF_RED, Shape.Circle))
    view.background.alpha = 128
    assertThat(pixelDrawnByView(view)).isEqualTo(0x40FF0000)

    view.background.alpha = 255

    assertThat(pixelDrawnByView(view)).isEqualTo(HALF_RED)
  }

  @Test
  fun circle_atHalfAlpha_halvesTheColorsOwnAlpha() {
    assertThat(alphaDrawnAsViewBackground(Shape.Circle, alpha = 128)).isEqualTo(0x40)
  }

  @Suppress("DEPRECATION")
  @SuppressLint("DeprecatedMethod")
  @Test
  fun circle_fadedOut_isTransparent() {
    val circle = ComparableCircleDrawable.create(Color.RED)
    assertThat(circle.opacity).isEqualTo(PixelFormat.TRANSLUCENT)
    circle.alpha = 0
    assertThat(circle.opacity).isEqualTo(PixelFormat.TRANSPARENT)
  }

  @Test
  fun circle_reportsTheColorFilterItDrawsWith() {
    val circle = ComparableCircleDrawable.create(HALF_RED)
    val filter = PorterDuffColorFilter(Color.BLUE, PorterDuff.Mode.SRC_IN)
    circle.colorFilter = filter
    assertThat(circle.colorFilter).isSameAs(filter)
  }

  @Test
  fun circle_reportsTheAlphaItIsGiven() {
    val circle = ComparableCircleDrawable.create(HALF_RED)
    assertThat(circle.alpha).isEqualTo(255)
    circle.alpha = 128
    assertThat(circle.alpha).isEqualTo(128)
  }

  @Test
  fun circle_alphaChanges_invalidateTheDrawable() {
    val circle = ComparableCircleDrawable.create(HALF_RED)
    assertInvalidates(circle) { circle.alpha = 128 }
    assertInvalidates(circle) { circle.alpha = 255 }
  }

  @Test
  fun circle_tintColorsThePixels_andClearingTintRestoresTheColor() {
    val circle = ComparableCircleDrawable.create(Color.RED)
    circle.setTint(Color.BLUE)
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.BLUE)
    assertThat(circle.colorFilter).isNull()

    circle.setTintList(null)

    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.RED)
    assertThat(circle.colorFilter).isNull()
  }

  @Test
  fun circle_tintModeChanges_updateThePixels() {
    val circle = ComparableCircleDrawable.create(Color.RED)
    circle.setTint(Color.BLUE)
    circle.setTintMode(PorterDuff.Mode.MULTIPLY)
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.BLACK)

    circle.setTintMode(PorterDuff.Mode.SRC_IN)

    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.BLUE)
  }

  @Test
  fun circle_statefulTint_tracksTheDrawableState() {
    val circle = ComparableCircleDrawable.create(Color.RED)
    circle.setTintList(pressedTint())
    assertThat(circle.isStateful).isTrue
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.GREEN)

    circle.state = intArrayOf(android.R.attr.state_pressed)
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.BLUE)

    circle.state = intArrayOf()
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.GREEN)
    circle.setTintList(null)
    assertThat(circle.isStateful).isFalse
  }

  @Test
  fun circle_colorFilterOverridesTint_andClearingItRestoresCurrentTint() {
    val circle = ComparableCircleDrawable.create(Color.RED)
    circle.setTintList(pressedTint())
    val filter = PorterDuffColorFilter(Color.YELLOW, PorterDuff.Mode.SRC_IN)
    circle.colorFilter = filter
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.YELLOW)
    assertThat(circle.colorFilter).isSameAs(filter)

    circle.state = intArrayOf(android.R.attr.state_pressed)
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.YELLOW)
    circle.colorFilter = null
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.BLUE)
    assertThat(circle.colorFilter).isNull()

    circle.setTintList(null)
    assertThat(pixelDrawnByDrawable(circle)).isEqualTo(Color.RED)
  }

  @Test
  fun circle_tintChanges_invalidateTheDrawable() {
    val circle = ComparableCircleDrawable.create(Color.RED)
    assertInvalidates(circle) { circle.setTint(Color.BLUE) }
    assertInvalidates(circle) { circle.setTintList(pressedTint()) }
    assertInvalidates(circle) { circle.state = intArrayOf(android.R.attr.state_pressed) }
    assertInvalidates(circle) { circle.setTintMode(PorterDuff.Mode.MULTIPLY) }
    assertInvalidates(circle) { circle.setTintList(null) }
  }

  @Test
  fun circle_viewBackgroundTint_colorsThePixels() {
    val view = mountedPlainView(Style.background(Color.RED, Shape.Circle))
    view.backgroundTintList = ColorStateList.valueOf(Color.BLUE)

    assertThat(pixelDrawnByView(view)).isEqualTo(Color.BLUE)
  }

  @Test
  fun roundedRectangle_atFullAlpha_keepsTheColorsOwnAlpha() {
    assertThat(alphaDrawnAsViewBackground(Shape.RoundedRectangle(16.px), alpha = 255))
        .isEqualTo(0x80)
  }

  @Test
  fun roundedRectangle_atHalfAlpha_halvesTheColorsOwnAlpha() {
    assertThat(alphaDrawnAsViewBackground(Shape.RoundedRectangle(16.px), alpha = 128))
        .isEqualTo(0x40)
  }

  // A View's background gives it its outline through ViewOutlineProvider.BACKGROUND, and elevation
  // casts a shadow from that outline only when its alpha is above 0.

  @Test
  fun circle_onAViewWithElevation_castsACircularShadow() {
    val view = mountedPlainView(Style.background(Color.RED, Shape.Circle).elevation(8.dp))
    assertThat(view.background).isInstanceOf(ComparableCircleDrawable::class.java)
    assertCircularOutline(view)
  }

  @Test
  fun circle_translucentOutline_usesTheDrawnAlpha() {
    val view = mountedPlainView(Style.background(HALF_RED, Shape.Circle))
    assertThat(backgroundOutline(view).alpha).isEqualTo(128 / 255f)

    view.background.alpha = 128

    assertThat(backgroundOutline(view).alpha).isEqualTo(64 / 255f)
  }

  @Test
  fun roundedRectangle_onAViewWithElevation_castsARoundedShadow() {
    val view = mountedPlainView(
        Style.background(Color.RED, Shape.RoundedRectangle(16.px)).elevation(8.dp),
    )
    val outline = backgroundOutline(view)
    val rect = Rect()
    assertThat(outline.getRect(rect)).describedAs("outline is a round rect").isTrue
    assertThat(rect).describedAs("outline bounds").isEqualTo(Rect(0, 0, 200, 80))
    assertThat(outline.radius).describedAs("outline radius").isEqualTo(16f)
    assertThat(outline.alpha).describedAs("outline alpha (0 casts no shadow)").isEqualTo(1f)
  }

  @Test
  fun circle_paintsTheRegionClipKeeps_onAnOddLeftoverWidth() {
    val drawable = ComparableCircleDrawable.create(Color.RED).apply { setBounds(0, 0, 201, 80) }
    val view = View(lithoTestRule.context.androidContext).apply { layout(0, 0, 201, 80) }
    val clip = Outline()
    Shape.Circle.getOutlineProvider(lithoTestRule.context).getOutline(view, clip)
    val clipRect = Rect()
    assertThat(clip.getRect(clipRect)).isTrue
    assertThat(clipRect).isEqualTo(Rect(60, 0, 140, 80))
    assertThat(drawnCircle(drawable)).isEqualTo(RectF(60f, 0f, 140f, 80f))
  }

  @Test
  fun circle_equivalence_comparesEffectiveAlpha() {
    val opaque = ComparableCircleDrawable.create(Color.RED)
    val faded = ComparableCircleDrawable.create(Color.RED).apply { alpha = 128 }
    val sameFade = ComparableCircleDrawable.create(Color.RED).apply { alpha = 128 }
    val transparent = ComparableCircleDrawable.create(Color.TRANSPARENT)
    val sameTransparency = ComparableCircleDrawable.create(Color.TRANSPARENT).apply { alpha = 0 }

    assertThat(opaque.isEquivalentTo(faded)).isFalse
    assertThat(faded.isEquivalentTo(sameFade)).isTrue
    assertThat(faded.hashCode()).isEqualTo(sameFade.hashCode())
    assertThat(transparent.isEquivalentTo(sameTransparency)).isTrue
    assertThat(transparent.hashCode()).isEqualTo(sameTransparency.hashCode())
  }

  @Test
  fun removingDynamicColor_restoresTheStaticCircle() {
    assertStaticBackgroundRestored(
        Style.background(Color.RED, Shape.Circle),
        Style.backgroundColor(DynamicValue(Color.BLUE)),
        transparentCorners = true,
    )
  }

  @Test
  fun removingDynamicDrawable_restoresTheStaticCircle() {
    assertStaticBackgroundRestored(
        Style.background(Color.RED, Shape.Circle),
        Style.background(DynamicValue(Color.BLUE.toDrawable())),
        transparentCorners = true,
    )
  }

  @Test
  fun removingDynamicColor_restoresTheStaticRoundedRectangle() {
    assertStaticBackgroundRestored(
        Style.background(Color.RED, Shape.RoundedRectangle(16.px)),
        Style.backgroundColor(DynamicValue(Color.BLUE)),
        transparentCorners = true,
    )
  }

  @Test
  fun removingDynamicDrawable_restoresTheStaticRoundedRectangle() {
    assertStaticBackgroundRestored(
        Style.background(Color.RED, Shape.RoundedRectangle(16.px)),
        Style.background(DynamicValue(Color.BLUE.toDrawable())),
        transparentCorners = true,
    )
  }

  @Test
  fun removingDynamicColor_restoresTheStaticColor() {
    assertStaticBackgroundRestored(
        Style.backgroundColor(Color.RED),
        Style.backgroundColor(DynamicValue(Color.BLUE)),
        transparentCorners = false,
    )
  }

  @Test
  fun removingDynamicDrawable_restoresTheStaticColor() {
    assertStaticBackgroundRestored(
        Style.backgroundColor(Color.RED),
        Style.background(DynamicValue(Color.BLUE.toDrawable())),
        transparentCorners = false,
    )
  }

  @Test
  fun reusedBackgroundColor_keepsDynamicColorChangesOnOneView() {
    val color = DynamicValue(Color.RED)
    val style = Style.backgroundColor(Color.GRAY)
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row {
            child(PlainView(style.viewTag(FIRST_VIEW_TAG).backgroundColor(color)))
            child(PlainView(style.viewTag(SECOND_VIEW_TAG)))
          }
        }

    color.set(Color.BLUE)

    assertThat(pixelDrawnByView(testLithoView.findViewWithTag(FIRST_VIEW_TAG)))
        .isEqualTo(Color.BLUE)
    assertThat(pixelDrawnByView(testLithoView.findViewWithTag(SECOND_VIEW_TAG)))
        .isEqualTo(Color.GRAY)
  }

  @Test
  fun removingTheShape_keepsTheBackgroundColorStillDeclared() {
    val testLithoView = lithoTestRule.render { ColorThenCircle(withCircle = true) }

    testLithoView.setRoot(ColorThenCircle(withCircle = false))

    assertThat(testLithoView.lithoView.background)
        .isEqualTo(ComparableColorDrawable.create(Color.GRAY))
  }

  @Test
  fun recoloringTheShape_keepsTheLaterBackgroundColorOnTop() {
    val testLithoView = lithoTestRule.render { CircleThenColor(circleColor = Color.RED) }

    testLithoView.setRoot(CircleThenColor(circleColor = Color.BLUE))

    assertThat(testLithoView.lithoView.background)
        .isEqualTo(ComparableColorDrawable.create(Color.GRAY))
  }

  @Test
  fun recoloringTheShape_updatesItsVisibleColor() {
    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(style = Style.width(100.px).height(100.px).background(Color.RED, Shape.Circle))
        }
    assertThat(pixelDrawnByView(testLithoView.lithoView)).isEqualTo(Color.RED)

    testLithoView.setRoot(
        Row(style = Style.width(100.px).height(100.px).background(Color.BLUE, Shape.Circle)),
    )

    assertThat(pixelDrawnByView(testLithoView.lithoView)).isEqualTo(Color.BLUE)
  }

  private fun pressedTint(): ColorStateList = ColorStateList(
      arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
      intArrayOf(Color.BLUE, Color.GREEN),
  )

  private fun assertInvalidates(drawable: Drawable, action: () -> Unit) {
    val callback = mock<Drawable.Callback>()
    drawable.callback = callback
    action()
    verify(callback).invalidateDrawable(drawable)
  }

  private fun pixelDrawnByDrawable(drawable: Drawable): Int {
    drawable.setBounds(0, 0, 80, 80)
    val bitmap = createBitmap(80, 80)
    drawable.draw(Canvas(bitmap))
    return bitmap[40, 40]
  }

  private fun pixelDrawnByView(view: View, x: Int = view.width / 2, y: Int = view.height / 2): Int {
    val bitmap = createBitmap(view.width, view.height)
    view.draw(Canvas(bitmap))
    return bitmap[x, y]
  }

  private fun assertStaticBackgroundRestored(
      staticStyle: Style,
      dynamicStyle: Style,
      transparentCorners: Boolean,
  ) {
    for (primitive in listOf(false, true)) {
      val lifecycle = MountLifecycle()
      val style = observedBackgroundStyle(staticStyle, lifecycle)
      val testLithoView =
          lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
            backgroundComponent(style + dynamicStyle, primitive)
          }
      val view = testLithoView.findViewWithTag(BACKGROUND_VIEW_TAG)
      val oldId = mountItemForView(testLithoView, view).renderUnit.id
      assertThat(pixelDrawnByView(view)).isEqualTo(Color.BLUE)

      testLithoView.setRoot(backgroundComponent(style, primitive))

      assertThat(testLithoView.findViewWithTag(BACKGROUND_VIEW_TAG)).isSameAs(view)
      val newUnit = mountItemForView(testLithoView, view).renderUnit
      assertThat(newUnit.id).isEqualTo(oldId)
      assertThat(lifecycle.binds).isEqualTo(1)
      assertThat(lifecycle.unbinds).isZero
      assertThat(view.background).isNotNull()
      assertThat(pixelDrawnByView(view)).describedAs("primitive=$primitive").isEqualTo(Color.RED)
      assertThat(pixelDrawnByView(view, x = 0, y = 0))
          .isEqualTo(if (transparentCorners) Color.TRANSPARENT else Color.RED)

      testLithoView.lithoView.unmountAllItems()

      assertThat(view.background).isNull()
      assertThat(lifecycle.unbinds).isEqualTo(1)
    }
  }

  private fun observedBackgroundStyle(style: Style, lifecycle: MountLifecycle): Style =
      style
          .width(100.px)
          .height(100.px)
          .viewTag(BACKGROUND_VIEW_TAG)
          .viewBinder(MountLifecycleBinder, lifecycle)

  private fun backgroundComponent(style: Style, primitive: Boolean): Component =
      if (primitive) PlainView(style) else Row { child(Row(style = style)) }

  private fun mountItemForView(testLithoView: TestLithoView, view: View): MountItem {
    val target = testLithoView.lithoView.mountDelegateTarget
    for (i in 0 until target.getMountItemCount()) {
      val item = target.getMountItemAt(i)
      if (item != null && item.content === view) {
        return item
      }
    }
    error("View has no mount item")
  }

  private class MountLifecycle {
    var binds = 0
    var unbinds = 0
  }

  private object MountLifecycleBinder : RenderUnit.Binder<MountLifecycle, View, Any> {
    override fun shouldUpdate(
        currentModel: MountLifecycle,
        newModel: MountLifecycle,
        currentLayoutData: Any?,
        nextLayoutData: Any?,
    ): Boolean = currentModel !== newModel

    override fun bind(
        context: Context,
        content: View,
        model: MountLifecycle,
        layoutData: Any?,
    ): Any {
      model.binds++
      return Unit
    }

    override fun unbind(
        context: Context,
        content: View,
        model: MountLifecycle,
        layoutData: Any?,
        bindData: Any?,
    ) {
      model.unbinds++
    }
  }

  /**
   * Mounts `background(HALF_RED, shape)` on a View-mounting component, sets the View background's
   * alpha when [alpha] is given, and reads the center pixel of that background drawn at the View's
   * size into a real bitmap.
   */
  private fun alphaDrawnAsViewBackground(shape: Shape, alpha: Int?): Int {
    val view = mountedPlainView(Style.background(HALF_RED, shape))
    val background = checkNotNull(view.background)
    if (alpha != null) {
      background.alpha = alpha
    }
    background.setBounds(0, 0, view.width, view.height)
    val bitmap = createBitmap(view.width, view.height)
    background.draw(Canvas(bitmap))
    return Color.alpha(bitmap[view.width / 2, view.height / 2])
  }

  private fun mountedPlainView(style: Style): View =
      lithoTestRule
          .render(widthSpec = unspecified(), heightSpec = unspecified()) {
            Row { child(PlainView(style.viewTag(PLAIN_VIEW_TAG))) }
          }
          .findViewWithTag(PLAIN_VIEW_TAG)

  /** The outline elevation uses, built the way View.setBackgroundBounds/rebuildOutline do. */
  private fun backgroundOutline(view: View): Outline {
    checkNotNull(view.background).setBounds(0, 0, view.width, view.height)
    return Outline().apply {
      setEmpty()
      alpha = 1f
      view.outlineProvider.getOutline(view, this)
    }
  }

  private fun assertCircularOutline(view: View) {
    val outline = backgroundOutline(view)
    val rect = Rect()
    assertThat(outline.getRect(rect)).describedAs("outline is a round rect").isTrue
    assertThat(rect).describedAs("outline bounds").isEqualTo(Rect(60, 0, 140, 80))
    assertThat(outline.radius).describedAs("outline radius").isEqualTo(40f)
    assertThat(outline.alpha).describedAs("outline alpha (0 casts no shadow)").isEqualTo(1f)
  }

  private fun drawnCircle(drawable: Drawable): RectF {
    var circle = RectF()
    val canvas = mock<Canvas>()
    doAnswer { invocation ->
          val cx = invocation.getArgument<Float>(0)
          val cy = invocation.getArgument<Float>(1)
          val radius = invocation.getArgument<Float>(2)
          circle = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
          null
        }
        .whenever(canvas)
        .drawCircle(anyFloat(), anyFloat(), anyFloat(), any())
    doAnswer { invocation ->
          circle = RectF(invocation.getArgument<RectF>(0))
          null
        }
        .whenever(canvas)
        .drawOval(any<RectF>(), any())
    drawable.draw(canvas)
    return circle
  }

  private class ColorThenCircle(private val withCircle: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(100.px).height(100.px).backgroundColor(Color.GRAY) +
                (if (withCircle) Style.background(Color.RED, Shape.Circle) else null),
    )
  }

  private class CircleThenColor(private val circleColor: Int) : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(100.px)
                .height(100.px)
                .background(circleColor, Shape.Circle)
                .backgroundColor(Color.GRAY),
    )
  }

  private class DrawableThenCircle(
      private val drawable: Drawable?,
      private val withCircle: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(100.px).height(100.px) +
                (if (drawable != null) Style.background(drawable) else null) +
                (if (withCircle) Style.background(Color.RED, Shape.Circle) else null),
    )
  }

  private class PlainView(private val style: Style) : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
        Primitive(
            layoutBehavior = FixedSizeLayoutBehavior(200.px, 80.px),
            mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
        ),
        style = style,
    )
  }

  private companion object {
    const val BACKGROUND_VIEW_TAG = "background_view"
    const val HALF_RED = 0x80FF0000.toInt()
    const val PLAIN_VIEW_TAG = "plain_view"
    const val FIRST_VIEW_TAG = "first_view"
    const val SECOND_VIEW_TAG = "second_view"
  }
}
