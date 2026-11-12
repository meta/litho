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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.accessibility.ImportantForAccessibility
import com.facebook.litho.accessibility.accessibilityRole
import com.facebook.litho.accessibility.accessibilityRoleDescription
import com.facebook.litho.accessibility.contentDescription
import com.facebook.litho.accessibility.importantForAccessibility
import com.facebook.litho.accessibility.onInitializeAccessibilityNodeInfo
import com.facebook.litho.accessibility.onPopulateAccessibilityNode
import com.facebook.litho.accessibility.stateDescription
import com.facebook.litho.animated.alpha
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.PreAllocationHandler
import com.facebook.litho.core.height
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.border
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Border
import com.facebook.litho.kotlin.widget.BorderEdge
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.focusable
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import com.facebook.litho.visibility.onInvisible
import com.facebook.litho.visibility.onVisible
import com.facebook.litho.widget.LithoScrollView
import com.facebook.litho.widget.collection.LazyList
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.MeasureResult
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.PoolScope
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.primitives.DrawableAllocator
import com.facebook.rendercore.primitives.FixedSizeLayoutBehavior
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.Primitive
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.rendercore.testing.ViewAssertions
import com.facebook.rendercore.utils.fillSpace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class PrimitiveComponentsTest {

  @Rule @JvmField val mLithoTestRule = LithoTestRule()
  @Rule @JvmField val expectedException = ExpectedException.none()

  @Test
  fun `should render primitive component`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()

    val testView = mLithoTestRule.render {
      Column {
        child(
            TestViewPrimitiveComponent(
                TextView(c.androidContext),
                steps,
                style =
                    Style.width(100.px)
                        .height(100.px)
                        .padding(all = 20.px)
                        .backgroundColor(Color.LTGRAY)
                        .border(Border(edgeAll = BorderEdge(width = 5f.dp, color = Color.BLACK))),
            ),
        )
      }
    }
    testView.lithoView.unmountAllItems()

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_UNMOUNT,
        )
  }

  @Test
  fun `fluent setter results are ignored on bind and unbind`() {
    val values = mutableListOf<Int>()
    val content = FluentView(mLithoTestRule.context.androidContext, values)
    val component =
        object : PrimitiveComponent() {
          override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
              Primitive(
                  FixedSizeLayoutBehavior(100.px, 100.px),
                  MountBehavior(ViewAllocator { content }) {
                    42.bindTo(FluentView::setValue, 0)
                  },
              ),
              null,
          )
        }

    val testView = mLithoTestRule.render { component }
    assertThat(values).containsExactly(42)
    testView.lithoView.unmountAllItems()
    assertThat(values).containsExactly(42, 0)
  }

  @Test
  fun `width, height, focusable, viewTag styles respected when set`() {
    val testComponent = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        style = Style.width(667.px).height(668.px).focusable(true).viewTag("test_view_tag"),
    )

    val testView = mLithoTestRule.render { testComponent }

    assertThat(testView.lithoView.childCount).isEqualTo(1)
    val realTestView = testView.lithoView.getChildAt(0)

    ViewAssertions.assertThat(realTestView).matches(match<TextView> { bounds(0, 0, 667, 668) })

    assertThat(realTestView.isFocusable).isTrue()

    testView.findViewWithTag("test_view_tag")
  }

  @Test
  fun `width, height, focusable, viewTag styles respected when updated`() {

    val testView = mLithoTestRule.render {
      TestViewPrimitiveComponent(
          view = TextView(mLithoTestRule.context.androidContext),
          style = Style.width(667.px).height(668.px).focusable(true).viewTag("test_view_tag"),
      )
    }

    assertThat(testView.lithoView.childCount).isEqualTo(1)
    val realTestView = testView.lithoView.getChildAt(0)

    ViewAssertions.assertThat(realTestView).matches(match<TextView> { bounds(0, 0, 667, 668) })

    assertThat(realTestView.isFocusable).isTrue

    testView.findViewWithTag("test_view_tag")

    mLithoTestRule.render(lithoView = testView.lithoView) {
      TestViewPrimitiveComponent(
          view = TextView(mLithoTestRule.context.androidContext),
          style = Style.width(667.px).height(668.px).focusable(false).viewTag("new_test_view_tag"),
      )
    }

    assertThat(testView.lithoView.childCount).isEqualTo(1)
    val newRealTestView = testView.lithoView.getChildAt(0)

    assertThat(newRealTestView.isFocusable).isFalse

    testView.findViewWithTag("new_test_view_tag")
  }

  @Test
  fun `onClick event is dispatched when set`() {
    val wasClicked = AtomicBoolean(false)

    val testComponent = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        style =
            Style.width(667.px).height(668.px).focusable(true).viewTag("click_me").onClick {
              wasClicked.set(true)
            },
    )

    val testView = mLithoTestRule.render { testComponent }

    assertThat(wasClicked.get()).isFalse
    testView.findViewWithTag("click_me").performClick()
    assertThat(wasClicked.get()).isTrue
  }

  @Test
  fun `onVisible event is fired when set`() {
    val eventFired = AtomicBoolean(false)

    val testComponent = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        style =
            Style.width(667.px).height(668.px).focusable(true).viewTag("click_me").onVisible {
              eventFired.set(true)
            },
    )

    mLithoTestRule.render { testComponent }

    assertThat(eventFired.get()).isTrue
  }

  @Test
  fun `widthPercent and heightPercent is respected when set`() {
    val testComponent = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        style = Style.heightPercent(50f).widthPercent(50f),
    )

    val testView = mLithoTestRule.render {
      Row(style = Style.width(100.px).height(100.px)) { child(testComponent) }
    }

    assertThat(testView.lithoView.childCount).isEqualTo(1)
    val realTestView = testView.lithoView.getChildAt(0)

    ViewAssertions.assertThat(realTestView).matches(match<TextView> { bounds(0, 0, 50, 50) })
  }

  @Test
  fun `dynamic alpha is respected when set`() {
    val alpha = 0.5f
    val alphaDV: DynamicValue<Float> = DynamicValue<Float>(alpha)

    val testComponent = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        style = Style.width(100.px).height(100.px).alpha(alphaDV),
    )

    val testView = mLithoTestRule.render { testComponent }

    assertThat(testView.lithoView.alpha).isEqualTo(alpha)

    alphaDV.set(1f)
    assertThat(testView.lithoView.alpha).isEqualTo(1f)

    alphaDV.set(0.7f)
    assertThat(testView.lithoView.alpha).isEqualTo(0.7f)
  }

  @Test
  fun `updating the state in primitive takes effect`() {
    lateinit var stateRef: AtomicReference<String>

    class TestComponent(val view: View) : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val testState: State<String> = useState { "initial" }
        stateRef = AtomicReference(testState.value)
        return LithoPrimitive(
            ViewPrimitive(view = view, updateState = { testState.update { s -> s + "_" + it } }),
            style = null,
        )
      }
    }

    mLithoTestRule.render { TestComponent(TextView(mLithoTestRule.context.androidContext)) }

    mLithoTestRule.idle()

    assertThat(stateRef.get()).describedAs("String state is updated").isEqualTo("initial_mount")
  }

  @Test
  fun `should not remeasure same primitive if size specs match`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)
    val component = TestViewPrimitiveComponent(
        view = view,
        steps = steps,
        style = Style.width(100.px).height(100.px),
    )

    val testView = mLithoTestRule.render { Column { child(component) } }

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
        )

    steps.clear()

    mLithoTestRule.render(lithoView = testView.lithoView) { Column { child(component) } }

    assertThat(LifecycleStep.getSteps(steps)).containsExactly(LifecycleStep.RENDER)
  }

  @Test
  fun `should not remeasure same primitive if size specs match with non exact size`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)
    val component = TestViewPrimitiveComponent(
        view = view,
        steps = steps,
        style = Style.width(100.px).flex(grow = 1f),
    )

    val testView = mLithoTestRule.render { Column { child(component) } }

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
        )

    steps.clear()

    mLithoTestRule.render(lithoView = testView.lithoView) { Column { child(component) } }

    assertThat(LifecycleStep.getSteps(steps)).containsExactly(LifecycleStep.RENDER)
  }

  @Test
  fun `should not remeasure primitive on state update if LayoutBehavior hasn't changed`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)

    class TestComponent(val view: View) : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val testState: State<String> = useState { "initial" }
        return LithoPrimitive(
            ViewPrimitive(view = view, steps = steps, str = testState.value),
            style = Style.viewTag("click_me").onClick { testState.update("updated") },
        )
      }
    }

    val testView = mLithoTestRule.render { TestComponent(view) }

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
        )

    steps.clear()
    testView.findViewWithTag("click_me").performClick()

    mLithoTestRule.render(lithoView = testView.lithoView) { TestComponent(view) }
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(LifecycleStep.ON_UNMOUNT, LifecycleStep.ON_MOUNT)
  }

  @Test
  fun `should remeasure primitive if properties have changed`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)

    val testView = mLithoTestRule.render {
      Column {
        child(
            TestViewPrimitiveComponent(
                identity = 0,
                view = view,
                steps = steps,
                style = Style.width(100.px).flex(grow = 1f),
            ),
        )
      }
    }

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
        )

    steps.clear()

    mLithoTestRule.render(lithoView = testView.lithoView) {
      Column {
        child(
            TestViewPrimitiveComponent(
                identity = 1,
                view = view,
                steps = steps,
                style = Style.width(100.px).flex(grow = 1f),
            ),
        )
      }
    }

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_UNMOUNT,
            LifecycleStep.ON_MOUNT,
        )
  }

  @Test
  fun `should remeasure primitive if size specs change`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)
    val component = TestViewPrimitiveComponent(identity = 0, view = view, steps = steps)

    val testView = mLithoTestRule.render(widthPx = 800, heightPx = 600) { component }

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
        )

    steps.clear()

    mLithoTestRule.render(lithoView = testView.lithoView, widthPx = 1920, heightPx = 1080) {
      component
    }

    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(LifecycleStep.ON_MEASURE, LifecycleStep.ON_UNMOUNT, LifecycleStep.ON_MOUNT)
  }

  @Test
  fun `same instance should be equivalent`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)
    val component = TestViewPrimitiveComponent(identity = 0, view = view, steps = steps)

    assertThat(component.isEquivalentTo(component)).isTrue
  }

  @Test
  fun `components with same prop values should be equivalent`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)
    val a = TestViewPrimitiveComponent(identity = 0, view = view, steps = steps)
    val b = TestViewPrimitiveComponent(identity = 0, view = view, steps = steps)
    assertThat(a.isEquivalentTo(b)).isTrue
  }

  @Test
  fun `components with different prop values should not be equivalent`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)
    val a = TestViewPrimitiveComponent(identity = 0, view = view, steps = steps)
    val b = TestViewPrimitiveComponent(identity = 1, view = view, steps = steps)

    assertThat(a.isEquivalentTo(b)).isFalse
  }

  @Test
  fun `components with different style values should not be equivalent`() {
    val c = mLithoTestRule.context
    val steps = mutableListOf<LifecycleStep.StepInfo>()
    val view = TextView(c.androidContext)
    val a = TestViewPrimitiveComponent(
        identity = 0,
        view = view,
        steps = steps,
        style = Style.width(100.px).height(100.px), /* 100 here */
    )

    val b = TestViewPrimitiveComponent(
        identity = 0,
        view = view,
        steps = steps,
        style = Style.width(200.px).height(200.px), /* 200 here */
    )

    assertThat(a.isEquivalentTo(b)).isFalse
  }

  @Test
  fun `when a11y props are set on style it should set them on the rendered content`() =
      withAccessibilityEnabled {
        val eventHandler: EventHandler<OnInitializeAccessibilityNodeInfoEvent> = mock()
        val onPopulateAccessibilityNodeHandler: EventHandler<OnPopulateAccessibilityNodeEvent> =
            mock()
        val accessibilityDescription =
            mLithoTestRule.context.androidContext.getString(android.R.string.ok)

        val component = TestViewPrimitiveComponent(
            view = EditText(mLithoTestRule.context.androidContext),
            style =
                Style.accessibilityRole(AccessibilityRole.EDIT_TEXT)
                    .accessibilityRoleDescription(accessibilityDescription)
                    .contentDescription(accessibilityDescription)
                    .importantForAccessibility(ImportantForAccessibility.YES)
                    .onInitializeAccessibilityNodeInfo { eventHandler }
                    .onPopulateAccessibilityNode { onPopulateAccessibilityNodeHandler },
        )

        val testView = mLithoTestRule.render { component }
        val nodeInfo = testView.currentRootNode?.node?.nodeInfo
        val renderedContent = testView.lithoView.getChildAt(0)
        assertThat(nodeInfo?.accessibilityRole).isEqualTo(AccessibilityRole.EDIT_TEXT)
        assertThat(nodeInfo?.accessibilityRoleDescription).isEqualTo(accessibilityDescription)
        assertThat(renderedContent.contentDescription).isEqualTo(accessibilityDescription)
        assertThat(renderedContent.importantForAccessibility)
            .isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_YES)
        assertThat(nodeInfo?.onInitializeAccessibilityNodeInfoHandler).isNotNull
        assertThat(nodeInfo?.onPopulateAccessibilityNodeHandler).isNotNull
        assertThat(renderedContent.getTag(ComponentHost.COMPONENT_NODE_INFO_ID)).isSameAs(nodeInfo)
        assertThat(ViewCompat.getAccessibilityDelegate(renderedContent)).isNotNull
      }

  @Test
  fun `when a11y handler style changes it should update the mounted content`() =
      withAccessibilityEnabled {
        val view = EditText(mLithoTestRule.context.androidContext)
        val handledBy = AtomicReference<String?>()
        val testView = mLithoTestRule.render {
          TestViewPrimitiveComponent(
              view = view,
              style = Style.onInitializeAccessibilityNodeInfo { handledBy.set("first") },
          )
        }

        val firstNodeInfo = view.getTag(ComponentHost.COMPONENT_NODE_INFO_ID) as NodeInfo
        checkNotNull(ViewCompat.getAccessibilityDelegate(view))
            .onInitializeAccessibilityNodeInfo(view, AccessibilityNodeInfoCompat.obtain())
        assertThat(handledBy.get()).isEqualTo("first")

        handledBy.set(null)
        mLithoTestRule.render(lithoView = testView.lithoView) {
          TestViewPrimitiveComponent(
              view = view,
              style = Style.onInitializeAccessibilityNodeInfo { handledBy.set("second") },
          )
        }

        val secondNodeInfo = view.getTag(ComponentHost.COMPONENT_NODE_INFO_ID) as NodeInfo
        assertThat(secondNodeInfo).isNotSameAs(firstNodeInfo)
        checkNotNull(ViewCompat.getAccessibilityDelegate(view))
            .onInitializeAccessibilityNodeInfo(view, AccessibilityNodeInfoCompat.obtain())
        assertThat(handledBy.get()).isEqualTo("second")
      }

  @Test
  fun `when a11y handler style is removed it should clear the mounted content`() =
      withAccessibilityEnabled {
        val view = EditText(mLithoTestRule.context.androidContext)
        val wasHandled = AtomicBoolean(false)
        val testView = mLithoTestRule.render {
          TestViewPrimitiveComponent(
              view = view,
              style = Style.onInitializeAccessibilityNodeInfo { wasHandled.set(true) },
          )
        }

        assertThat(view.getTag(ComponentHost.COMPONENT_NODE_INFO_ID)).isNotNull
        val installedDelegate = checkNotNull(ViewCompat.getAccessibilityDelegate(view))
        assertThat(installedDelegate).isInstanceOf(ComponentAccessibilityDelegate::class.java)
        installedDelegate.onInitializeAccessibilityNodeInfo(
            view,
            AccessibilityNodeInfoCompat.obtain(),
        )
        assertThat(wasHandled.get()).isTrue
        wasHandled.set(false)

        mLithoTestRule.render(lithoView = testView.lithoView) {
          TestViewPrimitiveComponent(view = view)
        }

        assertThat(view.getTag(ComponentHost.COMPONENT_NODE_INFO_ID)).isNull()
        val remainingDelegate = ViewCompat.getAccessibilityDelegate(view)
        assertThat(remainingDelegate).isNotInstanceOf(ComponentAccessibilityDelegate::class.java)
        remainingDelegate?.onInitializeAccessibilityNodeInfo(
            view,
            AccessibilityNodeInfoCompat.obtain(),
        )
        assertThat(wasHandled.get()).isFalse
      }

  @Test
  fun `primitive preserves its view accessibility delegate through mount and reuse`() =
      withAccessibilityEnabled {
        fun nodeInfoFor(view: View): AccessibilityNodeInfoCompat =
            AccessibilityNodeInfoCompat.obtain().also { info ->
              checkNotNull(ViewCompat.getAccessibilityDelegate(view))
                  .onInitializeAccessibilityNodeInfo(view, info)
            }

        val stateDescription = mLithoTestRule.context.androidContext.getString(android.R.string.ok)
        val child: ComponentScope.() -> Component = {
          Column(style = Style.width(100.px).height(300.px)) {}
        }
        val testView = mLithoTestRule.render {
          TestVerticalScrollPrimitiveComponent(
              child = child,
              style = Style.stateDescription(stateDescription),
          )
        }
        val scrollView = testView.lithoView.getChildAt(0) as LithoScrollView
        val delegateRegistration =
            scrollView.getTag(ComponentHost.COMPONENT_ACCESSIBILITY_DELEGATE_ID)

        val mountedInfo = nodeInfoFor(scrollView)
        assertThat(mountedInfo.stateDescription).isEqualTo(stateDescription)
        assertThat(mountedInfo.actionList.map { it.id })
            .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)

        testView.lithoView.unmountAllItems()

        assertThat(scrollView.getTag(ComponentHost.COMPONENT_ACCESSIBILITY_DELEGATE_ID))
            .isSameAs(delegateRegistration)
        assertThat(ViewCompat.getAccessibilityDelegate(scrollView))
            .isNotInstanceOf(ComponentAccessibilityDelegate::class.java)
        assertThat(nodeInfoFor(scrollView).actionList.map { it.id })
            .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)

        mLithoTestRule.render(lithoView = testView.lithoView) {
          TestVerticalScrollPrimitiveComponent(child = child)
        }
        val reusedScrollView = testView.lithoView.getChildAt(0) as LithoScrollView
        assertThat(reusedScrollView).isSameAs(scrollView)
        assertThat(reusedScrollView.getTag(ComponentHost.COMPONENT_ACCESSIBILITY_DELEGATE_ID))
            .isSameAs(delegateRegistration)
        val reusedInfo = nodeInfoFor(reusedScrollView)
        assertThat(reusedInfo.stateDescription).isNotEqualTo(stateDescription)
        assertThat(reusedInfo.actionList.map { it.id })
            .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
      }

  private inline fun withAccessibilityEnabled(block: () -> Unit) {
    val accessibilityManager =
        mLithoTestRule.context.androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
    val shadowAccessibilityManager = Shadows.shadowOf(accessibilityManager)
    shadowAccessibilityManager.setEnabled(true)
    shadowAccessibilityManager.setTouchExplorationEnabled(true)
    AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()

    try {
      block()
    } finally {
      shadowAccessibilityManager.setEnabled(false)
      shadowAccessibilityManager.setTouchExplorationEnabled(false)
      AccessibilityUtils.invalidateCachedIsAccessibilityEnabled()
    }
  }

  @Test
  fun `TestDrawablePrimitiveComponent has IMAGE accessibility role by default but overriding it works`() {
    val component1 = TestDrawablePrimitiveComponent(
        drawable = ColorDrawable(Color.RED),
        style = Style.width(100.px).height(100.px),
    )

    val testView1 = mLithoTestRule.render { component1 }
    val node1 = testView1.currentRootNode?.node
    val nodeInfo1 = node1?.nodeInfo
    assertThat(nodeInfo1?.accessibilityRole).isEqualTo(AccessibilityRole.IMAGE)

    val component2 = TestDrawablePrimitiveComponent(
        drawable = ColorDrawable(Color.RED),
        style =
            Style.width(100.px).height(100.px).accessibilityRole(AccessibilityRole.IMAGE_BUTTON),
    )

    val testView2 = mLithoTestRule.render { component2 }
    val node2 = testView2.currentRootNode?.node
    val nodeInfo2 = node2?.nodeInfo
    assertThat(nodeInfo2?.accessibilityRole).isEqualTo(AccessibilityRole.IMAGE_BUTTON)
  }

  @Test
  fun `when dynamic value is set it should update the content`() {
    val tag = DynamicValue<Any?>("0")
    val root = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = tag,
        style = Style.width(100.px).height(100.px),
    )

    val test = mLithoTestRule.render { root }

    test.findViewWithTag("0")

    tag.set("1")

    test.findViewWithTag("1")
  }

  @Test
  fun `when component with dynamic value is unmounted it should unbind the dynamic value`() {
    val tag = DynamicValue<Any?>("0")
    val root = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = tag,
        style = Style.width(100.px).height(100.px),
    )

    val test = mLithoTestRule.render { root }

    val view = test.findViewWithTag("0")

    test.lithoView.setComponentTree(null, true)

    assertThat(tag.numberOfListeners).isEqualTo(0)

    tag.set("1")

    // tag should be set to default value
    assertThat(view.tag).isEqualTo("default_value")
  }

  @Test
  fun `when new dynamic value is set it should unbind the old dynamic value`() {
    val tag1 = DynamicValue<Any?>("0")
    val root1 = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = tag1,
        style = Style.width(100.px).height(100.px),
    )

    val test = mLithoTestRule.render { root1 }

    test.findViewWithTag("0")

    tag1.set("1")

    test.findViewWithTag("1")

    assertThat(tag1.numberOfListeners).isEqualTo(1)

    val tag2 = DynamicValue<Any?>("2")
    val root2 = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = tag2,
        style = Style.width(100.px).height(100.px),
    )

    test.setRoot(root2)

    assertThat(tag1.numberOfListeners).isEqualTo(0)

    // should have view with new tag
    val view = test.findViewWithTag("2")

    // set new tag using the old dynamic value
    tag1.set("3")

    // the above should not work, the tag should not change
    assertThat(view.tag).isEqualTo("2")

    // set the new tag using the new dynamic value
    tag2.set("3")

    // the above should work, the tag should change
    assertThat(view.tag).isEqualTo("3")

    assertThat(tag2.numberOfListeners).isEqualTo(1)
  }

  @Test
  fun `when new dynamic value is null it should unbind the old dynamic value`() {
    val tag1 = DynamicValue<Any?>("0")
    val root1 = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = tag1,
        style = Style.width(100.px).height(100.px),
    )

    val test = mLithoTestRule.render { root1 }

    test.findViewWithTag("0")

    tag1.set("1")

    test.findViewWithTag("1")

    val root2 = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = null,
        style = Style.width(100.px).height(100.px),
    )

    test.setRoot(root2)

    assertThat(tag1.numberOfListeners).isEqualTo(0)
  }

  @Test
  fun `when new dynamic value is set and the old one was null it should bind the new dynamic value`() {
    val root1 = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = null,
        style = Style.width(100.px).height(100.px),
    )

    val test = mLithoTestRule.render { root1 }

    val tag = DynamicValue<Any?>("1")
    val root2 = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = tag,
        style = Style.width(100.px).height(100.px),
    )

    test.setRoot(root2)

    assertThat(tag.numberOfListeners).isEqualTo(1)

    // should have view with new tag
    val view = test.findViewWithTag("1")

    // set new tag using the new dynamic value
    tag.set("2")

    // the above should work, the tag should change
    assertThat(view.tag).isEqualTo("2")

    assertThat(tag.numberOfListeners).isEqualTo(1)
  }

  @Test
  fun `when same dynamic value is used on different components it should update the content for all instances`() {
    val c = mLithoTestRule.context
    val tag = DynamicValue<Any?>("0")

    val test = mLithoTestRule.render {
      Column {
        child(
            TestViewPrimitiveComponent(
                view = EditText(mLithoTestRule.context.androidContext),
                dynamicTag = tag,
                style = Style.width(100.px).height(100.px),
            ),
        )
        child(
            TestViewPrimitiveComponent(
                view = EditText(mLithoTestRule.context.androidContext),
                dynamicTag = tag,
                style = Style.width(100.px).height(100.px),
            ),
        )
      }
    }

    val lithoView = test.lithoView
    val child0 = lithoView.getChildAt(0)
    val child1 = lithoView.getChildAt(1)

    assertThat(child0.tag).isEqualTo("0")
    assertThat(child1.tag).isEqualTo("0")

    tag.set("1")

    assertThat(child0.tag).isEqualTo("1")
    assertThat(child1.tag).isEqualTo("1")
  }

  @Test
  fun `when same component with dynamic value is used multiple times it should update the content for all instances`() {
    val c = mLithoTestRule.context
    val tag = DynamicValue<Any?>("0")
    val component = TestViewPrimitiveComponent(
        view = EditText(mLithoTestRule.context.androidContext),
        dynamicTag = tag,
        style = Style.width(100.px).height(100.px),
    )

    val test = mLithoTestRule.render {
      Column {
        child(component)
        child(component)
      }
    }

    val lithoView = test.lithoView
    val child0 = lithoView.getChildAt(0)
    val child1 = lithoView.getChildAt(1)

    assertThat(child0.tag).isEqualTo("0")
    assertThat(child1.tag).isEqualTo("0")

    tag.set("1")

    assertThat(child0.tag).isEqualTo("1")
    assertThat(child1.tag).isEqualTo("1")
  }

  @Test
  fun `Primitive that renders tree host notifies them about visibility bounds changes`() {
    val steps = mutableListOf<LifecycleStep.StepInfo>()

    val component = TestVerticalScrollPrimitiveComponent(
        child = {
          Column {
            child(
                TestViewPrimitiveComponent(
                    view = TextView(mLithoTestRule.context.androidContext),
                    steps = steps,
                    style =
                        Style.width(100.px).height(100.px).margin(top = 50.px).onVisible {
                          steps.add(LifecycleStep.StepInfo(LifecycleStep.ON_EVENT_VISIBLE))
                        },
                ),
            )
          }
        },
        style = Style.width(100.px).height(100.px),
    )

    // Create a FrameLayout 100x100
    val parent = FrameLayout(mLithoTestRule.context.androidContext)
    parent.measure(exactly(1080), exactly(100))
    parent.layout(0, 0, 1080, 100)

    // Add a new LithoView to that FrameLayout
    val testView = mLithoTestRule.createTestLithoView()
    parent.addView(testView.lithoView)

    // Render the component
    mLithoTestRule.render(testView.lithoView) { component }

    // Since everything is in the view port everything should mount
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_EVENT_VISIBLE,
        )
    steps.clear()

    // should unmount if the component is outside of the visible rect due to the 55px offset
    testView.lithoView.offsetTopAndBottom(55) // 55 offset from top; the item is 50 offset from top
    assertThat(LifecycleStep.getSteps(steps)).containsExactly(LifecycleStep.ON_UNMOUNT)
    steps.clear()

    // should not do anything when no items cross over the view port
    testView.lithoView.offsetTopAndBottom(5) // 55 + 5 = 60 offset from top
    assertThat(LifecycleStep.getSteps(steps)).isEmpty()
    steps.clear()

    // should mount the inner item back when it is back in the view port
    testView.lithoView.offsetTopAndBottom(-20) // 60 - 20 = 40 offset from top
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_EVENT_VISIBLE,
        )
  }

  @Test
  fun `Primitive that is excluded from incremental mount is setting this value properly`() {
    val steps = mutableListOf<LifecycleStep.StepInfo>()

    val component = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        shouldExcludeFromIncrementalMount = true,
        steps = steps,
        style =
            Style.width(100.px)
                .height(100.px)
                .margin(top = 50.px)
                .onVisible { steps.add(LifecycleStep.StepInfo(LifecycleStep.ON_EVENT_VISIBLE)) }
                .onInvisible {
                  steps.add(LifecycleStep.StepInfo(LifecycleStep.ON_EVENT_INVISIBLE))
                },
    )

    // Create a FrameLayout 100x100
    val parent = FrameLayout(mLithoTestRule.context.androidContext)
    parent.measure(exactly(1080), exactly(100))
    parent.layout(0, 0, 1080, 100)

    // Add a new LithoView to that FrameLayout
    val testView = mLithoTestRule.createTestLithoView()
    parent.addView(testView.lithoView)
    testView.lithoView
    // Render the component
    mLithoTestRule.render(testView.lithoView) { Column { child(component) } }

    // Since everything is in the view port everything should mount
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_EVENT_VISIBLE,
        )
    steps.clear()

    // should not unmount if the component is outside of the visible rect due to the 55px offset
    testView.lithoView.offsetTopAndBottom(55) // 55 offset from top; the item is 50 offset from top
    assertThat(LifecycleStep.getSteps(steps)).containsExactly(LifecycleStep.ON_EVENT_INVISIBLE)
    steps.clear()

    // should not do anything when no items cross over the view port
    testView.lithoView.offsetTopAndBottom(5) // 55 + 5 = 60 offset from top
    assertThat(LifecycleStep.getSteps(steps)).isEmpty()
    steps.clear()

    // should trigger visibility event when the component is back in the view port
    testView.lithoView.offsetTopAndBottom(-20) // 60 - 20 = 40 offset from top
    assertThat(LifecycleStep.getSteps(steps)).containsExactly(LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun `Primitive that is not excluded from incremental mount by default is setting this value properly`() {
    val steps = mutableListOf<LifecycleStep.StepInfo>()

    val component = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        steps = steps,
        style =
            Style.width(100.px)
                .height(100.px)
                .margin(top = 50.px)
                .onVisible { steps.add(LifecycleStep.StepInfo(LifecycleStep.ON_EVENT_VISIBLE)) }
                .onInvisible {
                  steps.add(LifecycleStep.StepInfo(LifecycleStep.ON_EVENT_INVISIBLE))
                },
    )

    // Create a FrameLayout 100x100
    val parent = FrameLayout(mLithoTestRule.context.androidContext)
    parent.measure(exactly(1080), exactly(100))
    parent.layout(0, 0, 1080, 100)

    // Add a new LithoView to that FrameLayout
    val testView = mLithoTestRule.createTestLithoView()
    parent.addView(testView.lithoView)
    testView.lithoView
    // Render the component
    mLithoTestRule.render(testView.lithoView) { Column { child(component) } }

    // Since everything is in the view port everything should mount
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_EVENT_VISIBLE,
        )
    steps.clear()

    // should unmount if the component is outside of the visible rect due to the 55px offset
    testView.lithoView.offsetTopAndBottom(55) // 55 offset from top; the item is 50 offset from top
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(LifecycleStep.ON_UNMOUNT, LifecycleStep.ON_EVENT_INVISIBLE)
    steps.clear()

    // should not do anything when no items cross over the view port
    testView.lithoView.offsetTopAndBottom(5) // 55 + 5 = 60 offset from top
    assertThat(LifecycleStep.getSteps(steps)).isEmpty()
    steps.clear()

    // should mount back and trigger visibility event when the component is back in the view port
    testView.lithoView.offsetTopAndBottom(-20) // 60 - 20 = 40 offset from top
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(LifecycleStep.ON_MOUNT, LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun `Primitive that sets that it's not excluded from incremental mount is setting this value properly`() {
    val steps = mutableListOf<LifecycleStep.StepInfo>()

    val component = TestViewPrimitiveComponent(
        view = TextView(mLithoTestRule.context.androidContext),
        shouldExcludeFromIncrementalMount = false,
        steps = steps,
        style =
            Style.width(100.px)
                .height(100.px)
                .margin(top = 50.px)
                .onVisible { steps.add(LifecycleStep.StepInfo(LifecycleStep.ON_EVENT_VISIBLE)) }
                .onInvisible {
                  steps.add(LifecycleStep.StepInfo(LifecycleStep.ON_EVENT_INVISIBLE))
                },
    )

    // Create a FrameLayout 100x100
    val parent = FrameLayout(mLithoTestRule.context.androidContext)
    parent.measure(exactly(1080), exactly(100))
    parent.layout(0, 0, 1080, 100)

    // Add a new LithoView to that FrameLayout
    val testView = mLithoTestRule.createTestLithoView()
    parent.addView(testView.lithoView)
    testView.lithoView
    // Render the component
    mLithoTestRule.render(testView.lithoView) { Column { child(component) } }

    // Since everything is in the view port everything should mount
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(
            LifecycleStep.RENDER,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_EVENT_VISIBLE,
        )
    steps.clear()

    // should unmount if the component is outside of the visible rect due to the 55px offset
    testView.lithoView.offsetTopAndBottom(55) // 55 offset from top; the item is 50 offset from top
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(LifecycleStep.ON_UNMOUNT, LifecycleStep.ON_EVENT_INVISIBLE)
    steps.clear()

    // should not do anything when no items cross over the view port
    testView.lithoView.offsetTopAndBottom(5) // 55 + 5 = 60 offset from top
    assertThat(LifecycleStep.getSteps(steps)).isEmpty()
    steps.clear()

    // should mount back and trigger visibility event when the component is back in the view port
    testView.lithoView.offsetTopAndBottom(-20) // 60 - 20 = 40 offset from top
    assertThat(LifecycleStep.getSteps(steps))
        .containsExactly(LifecycleStep.ON_MOUNT, LifecycleStep.ON_EVENT_VISIBLE)
  }

  @Test
  fun `Primitive component's RenderUnit should have a correct description`() {
    var defaultRenderUnitDescription: String = ""
    val componentNoDescription =
        object : PrimitiveComponent() {
          override fun PrimitiveComponentScope.render(): LithoPrimitive {
            val primitive = Primitive(
                layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
                MountBehavior(ViewAllocator { context -> TextView(context) }) {},
            )

            defaultRenderUnitDescription = primitive.renderUnit.description

            return LithoPrimitive(primitive, null)
          }
        }
    var testView = mLithoTestRule.render { Column { child(componentNoDescription) } }

    assertThat(defaultRenderUnitDescription).contains("com.facebook.litho.PrimitiveComponentsTest")

    testView.lithoView.unmountAllItems()

    var customRenderUnitDescription: String = ""
    val customDescription = "RenderUnit description"
    val componentWithDescription =
        object : PrimitiveComponent() {
          override fun PrimitiveComponentScope.render(): LithoPrimitive {
            val primitive = Primitive(
                layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
                MountBehavior(
                    { customDescription },
                    ViewAllocator { context -> TextView(context) },
                ) {},
            )

            customRenderUnitDescription = primitive.renderUnit.description

            return LithoPrimitive(primitive, null)
          }
        }

    testView = mLithoTestRule.render { Column { child(componentWithDescription) } }

    assertThat(customRenderUnitDescription).isEqualTo(customDescription)

    testView.lithoView.unmountAllItems()
  }

  @Test
  fun `Primitive remounting with the same props should preserve 'unbind' lambdas returned from original 'bind' calls`() {
    val bindInfo = mutableListOf<String>()
    val lv = LithoView(mLithoTestRule.context)
    // mount component
    var testView =
        mLithoTestRule.render(lithoView = lv) {
          TestPrimitiveWithTwoBindersComponent(text = "foo", contentDescription = "bar", bindInfo)
        }
    // remount with the same props
    testView =
        mLithoTestRule.render(lithoView = lv) {
          TestPrimitiveWithTwoBindersComponent(text = "foo", contentDescription = "bar", bindInfo)
        }
    // unmount component
    testView.lithoView.unmountAllItems()
    assertThat(bindInfo)
        .containsExactly(
            "BIND:text",
            "BIND:contentDescription",
            "UNBIND:contentDescription",
            "UNBIND:text",
        )
  }

  @Test
  fun `Primitive binding and unbinding two binders individually should preserve 'unbind' lambdas returned from original 'bind' calls`() {
    val bindInfo = mutableListOf<String>()
    val lv = LithoView(mLithoTestRule.context)
    // mount component
    var testView =
        mLithoTestRule.render(lithoView = lv) {
          TestPrimitiveWithTwoBindersComponent(text = "foo", contentDescription = "bar", bindInfo)
        }
    assertThat(bindInfo).containsExactly("BIND:text", "BIND:contentDescription")

    // remount updating only first binder
    bindInfo.clear()
    testView =
        mLithoTestRule.render(lithoView = lv) {
          TestPrimitiveWithTwoBindersComponent(text = "baz", contentDescription = "bar", bindInfo)
        }
    assertThat(bindInfo).containsExactly("UNBIND:text", "BIND:text")

    // remount updating only second binder
    bindInfo.clear()
    testView =
        mLithoTestRule.render(lithoView = lv) {
          TestPrimitiveWithTwoBindersComponent(text = "baz", contentDescription = "qux", bindInfo)
        }
    assertThat(bindInfo).containsExactly("UNBIND:contentDescription", "BIND:contentDescription")

    // unmount component
    bindInfo.clear()
    testView.lithoView.unmountAllItems()
    assertThat(bindInfo).containsExactly("UNBIND:contentDescription", "UNBIND:text")
  }

  @Test
  fun `bindWithLayoutData with no deps should work the same as with Unit dep`() {
    val unitDepLayoutData = mutableListOf<TestPrimitiveLayoutData>()
    val noDepLayoutData = mutableListOf<TestPrimitiveLayoutData>()

    class TestComponent(private val style: Style) : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        return LithoPrimitive(
            layoutBehavior =
                object : LayoutBehavior {
                  override fun LayoutScope.layout(
                      sizeConstraints: SizeConstraints,
                  ): PrimitiveLayoutResult {
                    val layoutData = TestPrimitiveLayoutData(
                        width = sizeConstraints.maxWidth,
                        height = sizeConstraints.maxHeight,
                    )
                    return PrimitiveLayoutResult(
                        width = layoutData.width,
                        height = layoutData.height,
                        layoutData = layoutData,
                    )
                  }
                },
            mountBehavior =
                MountBehavior(ViewAllocator { context -> View(context) }) {
                  bindWithLayoutData<TestPrimitiveLayoutData>(Unit) { _, layoutData ->
                    unitDepLayoutData.add(layoutData)
                    onUnbind {}
                  }

                  bindWithLayoutData<TestPrimitiveLayoutData> { _, layoutData ->
                    noDepLayoutData.add(layoutData)
                    onUnbind {}
                  }
                },
            style = style,
        )
      }
    }

    // initial render
    val testView = mLithoTestRule.render {
      TestComponent(style = Style.width(100.px).height(100.px))
    }

    assertThat(unitDepLayoutData).containsExactly(TestPrimitiveLayoutData(100, 100))
    assertThat(noDepLayoutData).isEqualTo(unitDepLayoutData)

    // re-render with the same size
    mLithoTestRule.render(lithoView = testView.lithoView) {
      TestComponent(style = Style.width(100.px).height(100.px))
    }

    assertThat(unitDepLayoutData).containsExactly(TestPrimitiveLayoutData(100, 100))
    assertThat(noDepLayoutData).isEqualTo(unitDepLayoutData)

    // re-render with different size
    mLithoTestRule.render(lithoView = testView.lithoView) {
      TestComponent(style = Style.width(200.px).height(200.px))
    }

    assertThat(unitDepLayoutData)
        .containsExactly(TestPrimitiveLayoutData(100, 100), TestPrimitiveLayoutData(200, 200))
    assertThat(noDepLayoutData).isEqualTo(unitDepLayoutData)

    testView.lithoView.unmountAllItems()
  }

  @Test
  fun `should provide non null androidContext in bind block`() {
    var onBindAndroidContext: Context? = null
    var onUnbindAndroidContext: Context? = null

    class TestComponent(val view: View) : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        return LithoPrimitive(
            ViewPrimitive(
                view = view,
                onBind = { context -> onBindAndroidContext = context },
                onUnbind = { context -> onUnbindAndroidContext = context },
            ),
            style = null,
        )
      }
    }

    val testView = mLithoTestRule.render {
      TestComponent(TextView(mLithoTestRule.context.androidContext))
    }

    mLithoTestRule.idle()

    assertThat(onBindAndroidContext).isNotNull
    assertThat(onUnbindAndroidContext).isNull()

    testView.lithoView.unmountAllItems()

    assertThat(onBindAndroidContext).isNotNull
    assertThat(onUnbindAndroidContext).isNotNull
  }

  @Test
  fun `should see custom pool scope in render only if pool scope is provided via component tree`() {
    try {
      ComponentsConfiguration.customPoolScopesEnabled = true
      val poolScope = PoolScope.ManuallyManaged()
      val componentTreeWithPoolScope =
          ComponentTree.create(mLithoTestRule.context).poolScope(poolScope).build()
      val componentTreeWithoutPoolScope = ComponentTree.create(mLithoTestRule.context).build()

      var resolvedPoolScope: PoolScope? = null
      val component =
          object : PrimitiveComponent() {
            override fun PrimitiveComponentScope.render(): LithoPrimitive {
              resolvedPoolScope = PoolScopeTreeProp.value
              val primitive = Primitive(
                  layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
                  MountBehavior(ViewAllocator { context -> TextView(context) }) {},
              )
              return LithoPrimitive(primitive, null)
            }
          }

      // render component with CT that has pool scope
      val testViewWithPoolScope =
          mLithoTestRule.render(componentTree = componentTreeWithPoolScope) { component }
      assertThat(resolvedPoolScope).isSameAs(poolScope)

      resolvedPoolScope = null

      // render component with CT that doesn't have pool scope
      val testViewWithoutPoolScope =
          mLithoTestRule.render(componentTree = componentTreeWithoutPoolScope) { component }
      assertThat(resolvedPoolScope).isSameAs(PoolScope.None)

      // cleanup
      testViewWithPoolScope.lithoView.unmountAllItems()
      testViewWithoutPoolScope.lithoView.unmountAllItems()
    } finally {
      ComponentsConfiguration.customPoolScopesEnabled = false
    }
  }

  @Test
  fun `a tree whose configuration uses pool scopes sees its pool scope without the global flag`() {
    val poolScope = PoolScope.ManuallyManaged()
    val config =
        ComponentsConfiguration.create(mLithoTestRule.context.lithoConfiguration.componentsConfig)
            .usePoolScopes(true)
            .build()
    val componentTree =
        ComponentTree.create(mLithoTestRule.context)
            .componentsConfiguration(config)
            .poolScope(poolScope)
            .build()

    var resolvedPoolScope: PoolScope? = null
    val component =
        object : PrimitiveComponent() {
          override fun PrimitiveComponentScope.render(): LithoPrimitive {
            resolvedPoolScope = PoolScopeTreeProp.value
            val primitive = Primitive(
                layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
                MountBehavior(ViewAllocator { context -> TextView(context) }) {},
            )
            return LithoPrimitive(primitive, null)
          }
        }

    val testView = mLithoTestRule.render(componentTree = componentTree) { component }

    assertThat(ComponentsConfiguration.customPoolScopesEnabled).isFalse()
    assertThat(resolvedPoolScope).isSameAs(poolScope)
    testView.lithoView.unmountAllItems()
  }

  @Test
  fun `a tree whose configuration uses pool scopes pools only the content that asks for it`() {
    val poolScope = PoolScope.ManuallyManaged()
    val plainAllocator = ViewAllocator { context -> TextView(context) }
    val scopedAllocator = ViewAllocator(useCustomPoolScope = true) { context -> TextView(context) }

    val testView =
        mLithoTestRule.render(componentTree = treeWithPoolScope(poolScope)) {
          Column {
            child(FixedSizeViewComponent(plainAllocator))
            child(FixedSizeViewComponent(scopedAllocator))
          }
        }
    testView.lithoView.unmountAllItems()

    val androidContext = mLithoTestRule.context.androidContext
    assertThat(MountContentPools.getContentPoolItemCount(androidContext, plainAllocator, poolScope))
        .isZero()
    assertThat(MountContentPools.getContentPoolItemCount(androidContext, plainAllocator))
        .isEqualTo(1)
    assertThat(
        MountContentPools.getContentPoolItemCount(androidContext, scopedAllocator, poolScope),
    )
        .isEqualTo(1)
  }

  @Test
  fun `a nested tree keeps its parent's pool scope`() {
    val poolScope = PoolScope.ManuallyManaged()
    var parentContext: ComponentContext? = null
    var nestedPoolScope: PoolScope? = null
    val parent =
        object : PrimitiveComponent() {
          override fun PrimitiveComponentScope.render(): LithoPrimitive {
            parentContext = context
            return fixedSizeView(ViewAllocator { c -> TextView(c) })
          }
        }
    mLithoTestRule.render(componentTree = treeWithPoolScope(poolScope)) { parent }

    val nestedTree = ComponentTree.createNestedComponentTree(requireNotNull(parentContext)).build()
    mLithoTestRule.render(componentTree = nestedTree) {
      object : PrimitiveComponent() {
        override fun PrimitiveComponentScope.render(): LithoPrimitive {
          nestedPoolScope = PoolScopeTreeProp.value
          return fixedSizeView(ViewAllocator { c -> TextView(c) })
        }
      }
    }

    assertThat(nestedPoolScope).isSameAs(poolScope)
  }

  @Test
  fun `preallocated content goes to the tree's pool scope`() {
    val poolScope = PoolScope.ManuallyManaged()
    val allocator =
        ViewAllocator(canPreallocate = true, useCustomPoolScope = true) { context ->
          TextView(context)
        }
    val tree = treeWithPoolScope(
        poolScope,
        preAllocationHandler =
            PreAllocationHandler.Custom(
                RunnableHandler.DefaultHandler(android.os.Looper.getMainLooper()),
            ),
    )

    tree.setRootAndSizeSpecSync(FixedSizeViewComponent(allocator), exactly(100), exactly(100))
    mLithoTestRule.idle()

    val androidContext = mLithoTestRule.context.androidContext
    assertThat(MountContentPools.getContentPoolItemCount(androidContext, allocator, poolScope))
        .isEqualTo(1)
    assertThat(MountContentPools.getContentPoolItemCount(androidContext, allocator)).isZero()
  }

  @OptIn(ExperimentalLithoApi::class)
  private fun treeWithPoolScope(
      poolScope: PoolScope,
      preAllocationHandler: PreAllocationHandler? = null,
  ): ComponentTree =
      ComponentTree.create(mLithoTestRule.context)
          .componentsConfiguration(
              mLithoTestRule.context.lithoConfiguration.componentsConfig.copy(
                  usePoolScopes = true,
                  preAllocationHandler = preAllocationHandler,
              ),
          )
          .poolScope(poolScope)
          .build()

  private class FixedSizeViewComponent(private val allocator: ViewAllocator<TextView>) :
      PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive = fixedSizeView(allocator)
  }

  @Test
  fun `should see custom pool scope in render only if the component is inside of a lazy list`() {
    try {
      ComponentsConfiguration.customPoolScopesEnabled = true
      val componentTreeWithoutPoolScope = ComponentTree.create(mLithoTestRule.context).build()

      var firstResolvedPoolScope: PoolScope? = null
      val firstComponent =
          object : PrimitiveComponent() {
            override fun PrimitiveComponentScope.render(): LithoPrimitive {
              firstResolvedPoolScope = PoolScopeTreeProp.value
              val primitive = Primitive(
                  layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
                  MountBehavior(ViewAllocator { context -> TextView(context) }) {},
              )
              return LithoPrimitive(primitive, null)
            }
          }

      var secondResolvedPoolScope: PoolScope? = null
      val secondComponent =
          object : PrimitiveComponent() {
            override fun PrimitiveComponentScope.render(): LithoPrimitive {
              secondResolvedPoolScope = PoolScopeTreeProp.value
              val primitive = Primitive(
                  layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
                  MountBehavior(ViewAllocator { context -> TextView(context) }) {},
              )
              return LithoPrimitive(primitive, null)
            }
          }

      // render component with CT that doesn't have pool scope
      val testViewWithoutPoolScope =
          mLithoTestRule.render(componentTree = componentTreeWithoutPoolScope) {
            Row {
              child(
                  LazyList(style = Style.height(100.dp)) {
                    // first component is inside of a lazy list
                    child(firstComponent)
                  },
              )
              // second component is outside of the lazy collection
              child(secondComponent)
            }
          }

      // first pool scope comes from the lazy list and is not null
      assertThat(firstResolvedPoolScope).isNotNull()
      assertThat(firstResolvedPoolScope).isNotSameAs(PoolScope.None)

      // second pool scope is null
      assertThat(secondResolvedPoolScope).isSameAs(PoolScope.None)

      // cleanup
      testViewWithoutPoolScope.lithoView.unmountAllItems()
    } finally {
      ComponentsConfiguration.customPoolScopesEnabled = false
    }
  }
}

class TestViewPrimitiveComponent(
    val view: View,
    val steps: MutableList<LifecycleStep.StepInfo>? = null,
    val identity: Int = 0,
    val dynamicTag: DynamicValue<Any?>? = null,
    val shouldExcludeFromIncrementalMount: Boolean? = null,
    val style: Style? = null,
) : PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {

    steps?.add(LifecycleStep.StepInfo(LifecycleStep.RENDER))

    return LithoPrimitive(
        ViewPrimitive(
            id = identity,
            view = view,
            steps = steps,
            dynamicTag = dynamicTag,
            excludeFromIncrementalMount =
                this@TestViewPrimitiveComponent.shouldExcludeFromIncrementalMount,
        ),
        style,
    )
  }
}

@Suppress("TestFunctionName")
private fun PrimitiveComponentScope.ViewPrimitive(
    id: Int = 0,
    view: View,
    steps: MutableList<LifecycleStep.StepInfo>? = null,
    dynamicTag: DynamicValue<Any?>? = null,
    updateState: ((String) -> Unit)? = null,
    str: String? = null,
    excludeFromIncrementalMount: Boolean? = null,
    onBind: ((Context) -> Unit)? = null,
    onUnbind: ((Context) -> Unit)? = null,
): Primitive {

  class ViewPrimitiveLayoutBehavior(private val id: Int = 0) : LayoutBehavior {
    override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {
      steps?.add(LifecycleStep.StepInfo(LifecycleStep.ON_MEASURE))
      val size = IntSize.fillSpace(sizeConstraints, 100, 100)
      return PrimitiveLayoutResult(
          size = size,
          layoutData = TestPrimitiveLayoutData(size.width, size.height),
      )
    }
  }

  return Primitive(
      layoutBehavior = ViewPrimitiveLayoutBehavior(id),
      mountBehavior =
          MountBehavior(
              ViewAllocator {
                steps?.add(LifecycleStep.StepInfo(LifecycleStep.ON_CREATE_MOUNT_CONTENT))
                view
              },
          ) {
            shouldExcludeFromIncrementalMount = excludeFromIncrementalMount == true

            // using tag for convenience of tests
            bindDynamic(dynamicTag, View::setTag, "default_value")

            bindWithLayoutData<TestPrimitiveLayoutData>(id, steps, str) { _, _ ->
              onBind?.invoke(androidContext)
              updateState?.invoke("mount")
              steps?.add(LifecycleStep.StepInfo(LifecycleStep.ON_MOUNT))

              onUnbind {
                onUnbind?.invoke(androidContext)
                steps?.add(LifecycleStep.StepInfo(LifecycleStep.ON_UNMOUNT))
              }
            }
          },
  )
}

class TestDrawablePrimitiveComponent(val drawable: Drawable, val style: Style? = null) :
    PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    return LithoPrimitive(
        layoutBehavior =
            object : LayoutBehavior {
              override fun LayoutScope.layout(
                  sizeConstraints: SizeConstraints,
              ): PrimitiveLayoutResult {
                val width =
                    if (sizeConstraints.hasExactWidth) {
                      sizeConstraints.maxWidth
                    } else {
                      100
                    }

                val height =
                    if (sizeConstraints.hasExactHeight) {
                      sizeConstraints.maxHeight
                    } else {
                      100
                    }

                val result = MeasureResult(width, height, TestPrimitiveLayoutData(width, height))
                return PrimitiveLayoutResult(
                    result.width,
                    result.height,
                    layoutData = result.layoutData,
                )
              }
            },
        mountBehavior =
            MountBehavior(DrawableAllocator { drawable }) {
              bindWithLayoutData<TestPrimitiveLayoutData>(Unit) { drawable, testPrimitiveLayoutData
                ->
                onUnbind {}
              }
            },
        Style.accessibilityRole(AccessibilityRole.IMAGE) + style,
    )
  }
}

class TestVerticalScrollPrimitiveComponent(
    val child: ComponentScope.() -> Component,
    val style: Style? = null,
) : PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {

    val componentTree = useState {
      ComponentTree.createNestedComponentTree(context, this@render.child()).build()
    }

    return LithoPrimitive(
        layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
        mountBehavior =
            MountBehavior(ViewAllocator { context -> LithoScrollView(context) }) {
              doesMountRenderTreeHosts = true

              bind(componentTree.value) { scrollView ->
                scrollView.isVerticalScrollBarEnabled = true
                scrollView.mount(
                    componentTree.value,
                    LithoScrollView.ScrollPosition(0),
                    null,
                )
                onUnbind {}
              }
            },
        style,
    )
  }
}

class TestPrimitiveWithTwoBindersComponent(
    private val text: String,
    private val contentDescription: String,
    private val bindInfo: MutableList<String>,
) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    return LithoPrimitive(
        layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
        mountBehavior =
            MountBehavior(ViewAllocator { context -> TextView(context) }) {
              bind(text) { content ->
                bindInfo.add("BIND:text")
                content.text = text
                onUnbind {
                  bindInfo.add("UNBIND:text")
                  content.text = null
                }
              }
              bindWithLayoutData<Any?>(contentDescription) { content, _ ->
                bindInfo.add("BIND:contentDescription")
                content.contentDescription = contentDescription
                onUnbind {
                  bindInfo.add("UNBIND:contentDescription")
                  content.contentDescription = null
                }
              }
            },
        style = null,
    )
  }
}

@DataClassGenerate data class TestPrimitiveLayoutData(val width: Int, val height: Int)

private class FluentView(context: Context, private val values: MutableList<Int>) : View(context) {
  fun setValue(value: Int): FluentView {
    values.add(value)
    return this
  }
}

private fun PrimitiveComponentScope.fixedSizeView(
    allocator: ViewAllocator<TextView>,
): LithoPrimitive = LithoPrimitive(
    Primitive(FixedSizeLayoutBehavior(100.px, 100.px), MountBehavior(allocator) {}),
    null,
)
