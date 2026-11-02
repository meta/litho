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

package com.facebook.litho.widget

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.AccessibilityRole
import com.facebook.litho.AccessibilityUtils
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.AccessibilityTarget
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.accessibilityProbe
import com.facebook.rendercore.px
import com.facebook.rendercore.text.RCTextView
import com.facebook.widget.accessibility.delegates.AccessibleClickableSpan
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAccessibilityManager

/** Clickable spans reach screen readers the same way for legacy and primitive Text. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextSpanAccessibilityParityTest(private val usePrimitiveText: Boolean) {

  @JvmField @Rule val lithoTestRule = LithoTestRule(attachToRealWindow = true)

  private var previousUsePrimitiveText = false
  private lateinit var shadowManager: ShadowAccessibilityManager

  @Before
  fun setUp() {
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    ComponentsConfiguration.usePrimitiveText = usePrimitiveText
    val manager =
        ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    shadowManager = Shadows.shadowOf(manager)
    shadowManager.setEnabled(true)
    shadowManager.setTouchExplorationEnabled(true)
    AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
    shadowManager.setEnabled(false)
    shadowManager.setTouchExplorationEnabled(false)
    AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()
  }

  @Test
  fun `spans are not separate nodes without accessibleClickableSpans`() {
    assertThat(spanNodes(plainSpan(), accessibleClickableSpans = false)).isEmpty()
  }

  @Test
  fun `a plain clickable span is a button node with its text`() {
    val node = spanNodes(plainSpan(), accessibleClickableSpans = true).single().info()

    assertThat(node.text.toString()).isEqualTo("tap")
    assertThat(node.className).isEqualTo(AccessibilityRole.BUTTON)
    assertThat(node.isClickable).isTrue()
  }

  @Test
  fun `an accessible span uses its description, role and role description`() {
    val span =
        object :
            AccessibleClickableSpan("Open profile", AccessibilityRole.CHECK_BOX, "profile toggle") {
          override fun onClick(widget: View) = Unit
        }

    val node = spanNodes(span, accessibleClickableSpans = true).single().info()

    assertThat(node.contentDescription.toString()).isEqualTo("Open profile")
    assertThat(node.className).isEqualTo(AccessibilityRole.CHECK_BOX)
    assertThat(AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString())
        .isEqualTo("profile toggle")
  }

  @Test
  fun `an accessible span without a role is a button`() {
    val span =
        object : AccessibleClickableSpan("Open profile") {
          override fun onClick(widget: View) = Unit
        }

    val node = spanNodes(span, accessibleClickableSpans = true).single().info()

    assertThat(node.contentDescription.toString()).isEqualTo("Open profile")
    assertThat(node.className).isEqualTo(AccessibilityRole.BUTTON)
  }

  @Test
  fun `a zero-length span is not announced`() {
    val nodes =
        spanNodes(accessibleSpan(), accessibleClickableSpans = true, start = 3, end = 3).map {
          it.info()
        }

    assertThat(nodes).noneMatch { it.isAnnounced() }
  }

  @Test
  fun `a span cut off by maxLines is not announced`() {
    val text = "word ".repeat(60) + "tap"
    val nodes = spanNodes(
        accessibleSpan(),
        accessibleClickableSpans = true,
        text = text,
        start = text.length - 3,
        end = text.length,
        maxLines = 1,
    )
        .map { it.info() }

    assertThat(nodes).noneMatch { it.isAnnounced() }
  }

  @Test
  fun `a span node can't be clicked once its text stops exposing spans`() {
    var clicks = 0
    val span =
        object : AccessibleClickableSpan("Open profile") {
          override fun onClick(widget: View) {
            clicks++
          }
        }
    val spanned = SpannableString("tap here").apply { setSpan(span, 0, 3, 0) }
    val testLithoView = lithoTestRule.render {
      textWithSpans(spanned, accessibleClickableSpans = true)
    }
    val exposed = testLithoView.accessibilityProbe().nodes().single { it.isVirtual }

    testLithoView.setRoot(textWithSpans(spanned, accessibleClickableSpans = false))
    lithoTestRule.idle()

    assertThat(testLithoView.accessibilityProbe().nodes().filter { it.isVirtual }).isEmpty()
    assertThat(exposed.performAction(AccessibilityNodeInfo.ACTION_CLICK)).isFalse()
    assertThat(clicks).isEqualTo(0)
  }

  // Primitive only: legacy Text's stand-in node for a hidden span can still take focus.
  @Test
  fun `a span cut off by maxLines can't take accessibility focus`() {
    if (!usePrimitiveText) return
    val text = "word ".repeat(60) + "tap"
    val spanned =
        SpannableString(text).apply {
          setSpan(accessibleSpan(), text.length - 3, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    val testLithoView = lithoTestRule.render {
      textWithSpans(spanned, accessibleClickableSpans = true, maxLines = 1)
    }
    val provider = checkNotNull(findRCTextView(testLithoView.lithoView)).accessibilityNodeProvider

    assertThat(provider.performAction(0, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        .isFalse()
  }

  @Test
  fun `a focused span loses accessibility focus once its text stops exposing spans`() {
    if (!usePrimitiveText) return
    val spanned = SpannableString("tap here").apply { setSpan(accessibleSpan(), 0, 3, 0) }
    val testLithoView = lithoTestRule.render {
      textWithSpans(spanned, accessibleClickableSpans = true)
    }
    val view = checkNotNull(findRCTextView(testLithoView.lithoView))
    assertThat(
        view.accessibilityNodeProvider.performAction(
            0,
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null,
        ),
    )
        .isTrue()

    testLithoView.setRoot(textWithSpans(spanned, accessibleClickableSpans = false))
    lithoTestRule.idle()

    val provider = checkNotNull(findRCTextView(testLithoView.lithoView)).accessibilityNodeProvider
    assertThat(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)).isNull()
    assertThat(provider.performAction(0, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        .isFalse()
  }

  private fun findRCTextView(view: View): RCTextView? {
    if (view is RCTextView) {
      return view
    }
    if (view !is ViewGroup) {
      return null
    }
    return (0 until view.childCount).firstNotNullOfOrNull { findRCTextView(view.getChildAt(it)) }
  }

  private fun AccessibilityNodeInfo.isAnnounced(): Boolean =
      isVisibleToUser || isClickable || !contentDescription.isNullOrEmpty()

  private fun spanNodes(
      span: ClickableSpan,
      accessibleClickableSpans: Boolean,
      text: String = "tap here",
      start: Int = 0,
      end: Int = 3,
      maxLines: Int = Int.MAX_VALUE,
  ): List<AccessibilityTarget> {
    val spanned =
        SpannableString(text).apply {
          setSpan(
              span,
              start,
              end,
              if (start == end) Spanned.SPAN_INCLUSIVE_INCLUSIVE
              else Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
          )
        }
    val testLithoView = lithoTestRule.render {
      textWithSpans(spanned, accessibleClickableSpans, maxLines)
    }
    return testLithoView.accessibilityProbe().nodes().filter { it.isVirtual }
  }

  private fun textWithSpans(
      spanned: Spanned,
      accessibleClickableSpans: Boolean,
      maxLines: Int = Int.MAX_VALUE,
  ): Component =
      object : KComponent() {
        override fun ComponentScope.render(): Component = Column {
          child(
              Text(
                  text = spanned,
                  accessibleClickableSpans = accessibleClickableSpans,
                  maxLines = maxLines,
                  ellipsize = if (maxLines == 1) TextUtils.TruncateAt.END else null,
                  style = Style.width(SIZE.px).height(SIZE.px),
              ),
          )
        }
      }

  private fun accessibleSpan(): ClickableSpan =
      object : AccessibleClickableSpan("Open profile") {
        override fun onClick(widget: View) = Unit
      }

  private fun plainSpan(): ClickableSpan =
      object : ClickableSpan() {
        override fun onClick(widget: View) = Unit
      }

  companion object {
    private const val SIZE = 200

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveText={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
