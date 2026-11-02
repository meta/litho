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
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import com.facebook.litho.AccessibilityUtils
import com.facebook.litho.Component
import com.facebook.litho.ComponentHost
import com.facebook.litho.ComponentScope
import com.facebook.litho.DebugComponent
import com.facebook.litho.DynamicValue
import com.facebook.litho.KComponent
import com.facebook.litho.LithoViewTestHelper
import com.facebook.litho.Style
import com.facebook.litho.animated.alpha
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.padding
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.assertj.LithoAssertions
import com.facebook.litho.transition.transitionKey
import com.facebook.litho.view.clickable
import com.facebook.litho.view.enabled
import com.facebook.litho.view.focusable
import com.facebook.litho.view.onClick
import com.facebook.litho.view.onLongClick
import com.facebook.litho.view.onTouch
import com.facebook.litho.view.selected
import com.facebook.litho.view.viewTag
import com.facebook.litho.view.wrapInView
import com.facebook.rendercore.dp
import com.facebook.rendercore.text.RCTextView
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

/** Text lookups and clicks in tests behave the same for legacy and primitive Text. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextLookupParityTest(private val usePrimitiveText: Boolean) {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveText = false

  @Before
  fun setUp() {
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    ComponentsConfiguration.usePrimitiveText = usePrimitiveText
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
  }

  @Test
  fun `host text content includes the mounted text`() {
    val testLithoView = render { Column(style = Style.onClick {}) { child(Text(text = LABEL)) } }

    assertThat(allHostText(testLithoView.lithoView)).contains(LABEL)
    LithoAssertions.assertThat(testLithoView).hasVisibleText(LABEL)
  }

  @Test
  fun `the found view of plain text is its clickable, selectable container`() {
    var clicks = 0
    val testLithoView = render {
      Column(style = Style.padding(all = 8.dp).onClick { clicks++ }.selected(true)) {
        child(Text(text = LABEL))
      }
    }

    val found = testLithoView.findViewWithText(LABEL)

    assertThat(found.isSelected).isTrue()
    assertThat(found.performClick()).isTrue()
    assertThat(clicks).isEqualTo(1)
  }

  @Test
  fun `debug components report the mounted text`() {
    val testLithoView = render { Column { child(Text(text = LABEL)) } }

    val root = checkNotNull(DebugComponent.getRootInstance(testLithoView.lithoView))

    assertThat(root.descendantsAndSelf().mapNotNull { it.textContent }.toList()).contains(LABEL)
    assertThat(root.allTextContent).contains(LABEL)
  }

  @Test
  fun `the found view of direct RichText is the text view`() {
    val testLithoView = render {
      Column(style = Style.padding(all = 8.dp).onClick {}) { child(RichText(text = LABEL)) }
    }

    assertThat(testLithoView.findViewWithText(LABEL)).isInstanceOf(RCTextView::class.java)
    assertThat(allHostText(testLithoView.lithoView)).doesNotContain(LABEL)
  }

  @Test
  fun `the end-to-end dump lists each text once`() {
    val text = render { Column { child(Text(text = LABEL)) } }
    val richText = render { Column { child(RichText(text = OTHER_LABEL)) } }

    assertThat(
        LithoViewTestHelper.viewToString(text.lithoView, false).occurrences("text=\"$LABEL\""),
    )
        .isEqualTo(1)
    assertThat(
        LithoViewTestHelper.viewToString(richText.lithoView, false)
            .occurrences("text=\"$OTHER_LABEL\""),
    )
        .isEqualTo(1)
  }

  private fun String.occurrences(needle: String): Int =
      windowed(needle.length).count { it == needle }

  @Test
  fun `the found view of text with its own click handler is the text`() {
    var textClicks = 0
    var containerClicks = 0
    val testLithoView = render {
      Column(style = Style.padding(all = 8.dp).onClick { containerClicks++ }) {
        child(Text(text = LABEL, style = Style.onClick { textClicks++ }))
      }
    }

    assertThat(testLithoView.findViewWithText(LABEL).performClick()).isTrue()

    assertThat(textClicks).isEqualTo(1)
    assertThat(containerClicks).isZero()
  }

  @Test
  fun `clickOnText reaches the click handler of the text's container`() {
    var clicks = 0
    val testLithoView = render {
      Column(style = Style.padding(all = 8.dp).onClick { clicks++ }) { child(Text(text = LABEL)) }
    }

    lithoTestRule.act(testLithoView) { clickOnText(LABEL) }

    assertThat(clicks).isEqualTo(1)
  }

  @Test
  fun `clickOnText reaches a click handler set on the text itself`() {
    var clicks = 0
    val testLithoView = render {
      Column(style = Style.padding(all = 8.dp)) {
        child(Text(text = LABEL, style = Style.onClick { clicks++ }))
      }
    }

    lithoTestRule.act(testLithoView) { clickOnText(LABEL) }

    assertThat(clicks).isEqualTo(1)
  }

  @Test
  fun `the found view matches legacy for each text style`() {
    val actual = LEGACY_LOOKUPS.keys.associateWith { name -> lookUp(STYLES.getValue(name)) }

    assertThat(actual).isEqualTo(LEGACY_LOOKUPS)
  }

  @Test
  fun `the found view matches legacy with accessibility enabled`() {
    val accessibilityManager =
        Shadows.shadowOf(
            lithoTestRule.context.androidContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE,
            ) as AccessibilityManager,
        )
    accessibilityManager.setEnabled(true)
    accessibilityManager.setTouchExplorationEnabled(true)
    AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()
    try {
      assertThat(lookUp { Style.selected(true) }).isEqualTo(Lookup(tag = null, clicked = false))
    } finally {
      accessibilityManager.setEnabled(false)
      accessibilityManager.setTouchExplorationEnabled(false)
      AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()
    }
  }

  /** Finds [LABEL], styled by [textStyle] inside a tagged clickable container, and clicks it. */
  private fun lookUp(textStyle: ComponentScope.() -> Style): Lookup {
    var containerClicks = 0
    val found = render {
      Column(
          style =
              Style.padding(all = 8.dp).viewTag(CONTAINER_TAG).onClick {
                containerClicks++
              },
      ) {
        child(Text(text = LABEL, style = textStyle()))
      }
    }
        .findViewWithText(LABEL)
    val clicked = found.performClick()
    return Lookup(found.tag, clicked, containerClicks)
  }

  private data class Lookup(val tag: Any?, val clicked: Boolean, val containerClicks: Int = 0)

  private fun render(content: ComponentScope.() -> Component): TestLithoView =
      lithoTestRule.render(widthPx = 500, heightPx = 200) {
        object : KComponent() {
          override fun ComponentScope.render(): Component = content()
        }
      }

  private fun DebugComponent.descendantsAndSelf(): Sequence<DebugComponent> = sequence {
    yield(this@descendantsAndSelf)
    for (child in childComponents) {
      yieldAll(child.descendantsAndSelf())
    }
  }

  private fun allHostText(view: View): List<String> {
    val texts = mutableListOf<String>()
    if (view is ComponentHost) {
      view.textContentText.forEach { texts += it.toString() }
    }
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        texts += allHostText(view.getChildAt(i))
      }
    }
    return texts
  }

  companion object {
    private const val LABEL = "Tap me"
    private const val OTHER_LABEL = "Bravo"
    private const val CONTAINER_TAG = "container"

    private val STYLES: Map<String, ComponentScope.() -> Style> = mapOf(
        "plain" to { Style },
        "selected" to { Style.selected(true) },
        "disabled" to { Style.enabled(false) },
        "disabled click handler" to { Style.onClick(enabled = false) {} },
        "click handler, disabled" to { Style.onClick {}.enabled(false) },
        "click handler" to { Style.onClick {} },
        "click handler, not clickable" to { Style.onClick {}.clickable(false) },
        "long click handler" to { Style.onLongClick { true } },
        "touch handler" to { Style.onTouch { false } },
        "view tag" to { Style.viewTag("text") },
        "focusable" to { Style.focusable(true) },
        "wrapInView" to { Style.wrapInView() },
        "dynamic alpha" to { Style.alpha(DynamicValue(1f)) },
        "transition key" to { Style.transitionKey(context, "text") },
    )

    /** What legacy Text resolves to: its container, or a wrapper host of its own. */
    private val LEGACY_LOOKUPS: Map<String, Lookup> = mapOf(
        "plain" to Lookup(CONTAINER_TAG, clicked = true, containerClicks = 1),
        "selected" to Lookup(CONTAINER_TAG, clicked = true, containerClicks = 1),
        "disabled" to Lookup(CONTAINER_TAG, clicked = true, containerClicks = 1),
        "disabled click handler" to Lookup(CONTAINER_TAG, clicked = true, containerClicks = 1),
        "click handler, disabled" to Lookup(CONTAINER_TAG, clicked = true, containerClicks = 1),
        "click handler" to Lookup(tag = null, clicked = true),
        "click handler, not clickable" to Lookup(tag = null, clicked = true),
        "long click handler" to Lookup(tag = null, clicked = false),
        "touch handler" to Lookup(tag = null, clicked = false),
        "view tag" to Lookup(tag = "text", clicked = false),
        "focusable" to Lookup(tag = null, clicked = false),
        "wrapInView" to Lookup(tag = null, clicked = false),
        "dynamic alpha" to Lookup(tag = null, clicked = false),
        "transition key" to Lookup(tag = null, clicked = false),
    )

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveText={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
