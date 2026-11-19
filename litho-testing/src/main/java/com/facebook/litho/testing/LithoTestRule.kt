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

package com.facebook.litho.testing

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.ErrorEventHandler
import com.facebook.litho.LithoView
import com.facebook.litho.LithoVisibilityEventsController
import com.facebook.litho.TestComponentScope
import com.facebook.litho.TreePropContainer
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import java.lang.Exception
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * This test utility allows clients to create a [TestLithoView] instance that allows to test
 * assertion on the view hierarchy rendered by a Litho components.
 *
 * ```
 *  @RunWith(AndroidJUnit4::class)
 *  class LithoSampleTest {
 *
 *  @Rule @JvmField val LithoTestRule = LithoTestRule()
 *  @Test
 *  fun test() {
 *   val testLithoView =  LithoTestRule.render { TestComponent() }
 *
 *    // or you can use setRoot/measure/layout for more fine-grained control
 *    val testLithoView = LithoTestRule.createTestLithoView().attachToWindow().setRoot(TestComponent()).measure().layout()
 *    // Test your assertions on the TestLithoView instance.
 *    }
 * }
 * ```
 */
class LithoTestRule
@JvmOverloads
constructor(
    val componentsConfiguration: ComponentsConfiguration? = null,
    val themeResId: Int? = null,
    private val lithoVisibilityEventsController: (() -> LithoVisibilityEventsController)? = null,
    private val lifecycleOwner: (() -> LifecycleOwner)? = null,
    private val resizeMode: LithoTestRuleResizeMode = LithoTestRuleResizeMode.AUTO,
    val androidContext: Context? = null,
    /**
     * Renders into a real Activity window instead of the synthetic one, so that framework behavior
     * only reachable from a real window is exercised. See [TestLithoView.attachToWindow].
     *
     * Opt-in rather than the default because a real window also changes measure/layout timing,
     * focus and touch mode, and when `ViewTreeObserver` callbacks fire, and `LithoTestRule` has
     * well over a thousand callers.
     */
    val attachToRealWindow: Boolean = false,
) : TestRule {
  lateinit var context: ComponentContext
  private val threadLooperController: BaseThreadLooperController = ThreadLooperController()
  private val testLithoViews = mutableListOf<TestLithoView>()

  init {
    LithoDebugConfigurations.isDebugModeEnabled = true
    // A given androidContext is used as-is, so the rule cannot supply the Activity the real window
    // needs. Rejecting it here rather than at attach time keeps the failure next to the mistake.
    require(!attachToRealWindow || androidContext == null || androidContext is Activity) {
      "attachToRealWindow needs an Activity, but was given a ${androidContext?.javaClass?.name} as " +
          "androidContext. Pass an Activity, or drop androidContext and let the rule create one."
    }
  }

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      override fun evaluate() {
        try {
          initializeContext()
          threadLooperController.init()
          base.evaluate()
        } finally {
          testLithoViews.clear()
          threadLooperController.clean()
          // A Factory installed by the test outlives clear(), which only empties the pool maps.
          MountContentPools.setMountContentPoolFactory(null)
          MountContentPools.clear()
          context.clearCalculationStateContext()
        }
      }
    }
  }

  private fun initializeContext(): ComponentContext {
    if (androidContext != null) {
      context = ComponentContext(androidContext)
    } else if (attachToRealWindow) {
      // setup() drives the Activity through to resumed, which create() alone does not do and a live
      // window requires.
      val newActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
      themeResId?.let { newActivity.setTheme(it) }
      context = ComponentContext(newActivity)
    } else if (themeResId != null) {
      val newActivity = Robolectric.buildActivity(Activity::class.java).create().get()
      newActivity.setTheme(themeResId)
      context = ComponentContext(newActivity)
    } else {
      context = ComponentContext(getApplicationContext<Context>())
    }

    return context
  }

  fun useContext(c: ComponentContext): LithoTestRule {
    context = c
    return this
  }

  /** Sets a new [TreeProp] for the next layout pass. */
  fun setTreeProp(klass: Class<*>, instance: Any?): LithoTestRule {
    val props = context.treePropContainer ?: TreePropContainer()
    props.put(klass, instance)
    context.treePropContainer = props
    return this
  }

  /**
   * Creates new TestLithoView holder responsible for keeping an instance of LithoView, allowing to
   * find Views/Components or perform assertions on it. For simple Component rendering without
   * fine-grained control, use [render]
   */
  @JvmOverloads
  fun createTestLithoView(
      lithoView: LithoView? = null,
      componentTree: ComponentTree? = null,
      widthPx: Int? = null,
      heightPx: Int? = null,
      widthSpec: Int = DEFAULT_WIDTH_SPEC,
      heightSpec: Int = DEFAULT_HEIGHT_SPEC,
      componentFunction: (ComponentScope.() -> Component?)? = null,
  ): TestLithoView {
    val testLithoView = TestLithoView(
        context = context,
        componentsConfiguration = componentsConfiguration,
        lithoVisibilityEventsController = lithoVisibilityEventsController?.invoke(),
        lifecycleOwner = lifecycleOwner?.invoke(),
        resizeMode = resizeMode,
        idleTask = { idle() },
        attachToRealWindow = attachToRealWindow,
        onLithoViewChanged = { owner, view ->
          testLithoViews.removeAll { it.usesLithoView(view) }
          testLithoViews += owner
        },
    )
    componentTree?.let { testLithoView.useComponentTree(componentTree) }
    lithoView?.let { testLithoView.useLithoView(lithoView) }

    val resolvedWidthSpec = if (widthPx != null) exactly(widthPx) else widthSpec
    val resolvedHeightSpec = if (heightPx != null) exactly(heightPx) else heightSpec
    testLithoView.setSizeSpecs(resolvedWidthSpec, resolvedHeightSpec)

    componentFunction?.let {
      with(TestComponentScope(context)) { componentFunction() }
          ?.let { component -> testLithoView.setRoot(component) }
    }
    return testLithoView
  }

  /** Sets the new root to render. */
  @JvmOverloads
  fun render(
      lithoView: LithoView? = null,
      componentTree: ComponentTree? = null,
      widthPx: Int? = null,
      heightPx: Int? = null,
      widthSpec: Int = DEFAULT_WIDTH_SPEC,
      heightSpec: Int = DEFAULT_HEIGHT_SPEC,
      componentFunction: ComponentScope.() -> Component?,
  ): TestLithoView {
    val testLithoView = createTestLithoView(
        lithoView = lithoView,
        componentTree = componentTree,
        widthPx = widthPx,
        heightPx = heightPx,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        componentFunction = componentFunction,
    )
    return testLithoView.attachToWindow().measure().layout()
  }

  /**
   * Perform any interactions defined in the [InteractionScope] or on the [LithoTestRule].
   *
   * During tests we need to make sure that everything is in sync in the Main Thread and in the
   * Background Thread, just like in real life use case. This functions takes off the responsibility
   * from you to use the Loopers and manage the thread synchronisation. You only need to pass here
   * one of the defined interactions from [LithoTestRule] or [InteractionScope], and we will take
   * care of all of the rest
   */
  fun act(
      testLithoView: TestLithoView,
      action: TestLithoView.InteractionsScope.() -> Unit,
  ): LithoTestRule {
    testLithoView.InteractionsScope().action()
    idle()
    return this
  }

  /**
   * Drains all pending work on both the Litho layout thread and the main looper, repeating until
   * both are simultaneously idle (quiescence).
   *
   * A single drain pass is not sufficient because work ping-pongs between threads: layout completes
   * on the background thread and posts a result to the main thread, which in turn schedules a
   * follow-up layout (e.g. HScroll resolving its height after the initial pass). Each call to
   * [idle] processes one full round-trip, so the loop continues until neither looper has pending
   * work.
   *
   * Throws [IllegalStateException] if quiescence is not reached within [MAX_IDLE_PASSES] passes,
   * which indicates a component is continuously enqueuing layout work (an application bug).
   *
   * Use this after any action that triggers async layout or visibility events. It doesn't run the
   * layout pass a view requests; use [idleAndLayout] for that.
   */
  fun idle(): Unit = drainTasks(runTraversals = false)

  /**
   * Like [idle], and also runs the measure/layout pass a real window would run for views that
   * requested layout, including nested ones such as collection items. Repeats until no work or
   * layout request is left, with the same pass limit as [idle].
   *
   * Applies to laid-out views in [LithoTestRuleResizeMode.AUTO]; [LithoTestRuleResizeMode.MANUAL]
   * views still need explicit [TestLithoView.measure] and [TestLithoView.layout] calls. Views in a
   * real window (see [attachToRealWindow]) are laid out by the window's own traversal, which the
   * main-looper drain runs, and are sized by the window rather than the test size specs.
   */
  fun idleAndLayout(): Unit = drainTasks(runTraversals = true)

  private fun drainTasks(runTraversals: Boolean) {
    var passes = 0
    while (
        !threadLooperController.isIdle() ||
            !shadowOf(Looper.getMainLooper()).isIdle ||
            (runTraversals && runPendingTraversals())
    ) {
      check(++passes <= MAX_IDLE_PASSES) {
        "Did not reach quiescence after $MAX_IDLE_PASSES passes — possible infinite layout loop"
      }
      threadLooperController.runToEndOfTasksSync()
      shadowOf(Looper.getMainLooper()).idle()
    }
  }

  private fun runPendingTraversals(): Boolean {
    var ran = false
    for (testLithoView in testLithoViews) {
      ran = testLithoView.runPendingTraversal() || ran
    }
    return ran
  }

  fun finishAsyncUpdates() {
    threadLooperController.runToEndOfTasksSync()
  }

  /**
   * Creates a [LithoView] configured for screenshot capture.
   *
   * The [LithoView] is created without triggering a measure/layout pass so that the screenshot test
   * rule can control when measurement happens. Callers are responsible for any desired component
   * wrapping (e.g. a [com.facebook.litho.Column] to prevent components from stretching to
   * full-screen width or to ensure correct RTL behaviour).
   */
  fun createLithoViewForScreenshot(component: Component): LithoView {
    val lithoView = LithoView(context.androidContext)
    lithoView.componentTree = ComponentTree.create(context, component).build()
    return lithoView
  }

  /**
   * Creates a ComponentTree that will propagate exceptions.
   *
   * ```
   * val componentTree = LithoTestRule.withThrowOnErrorHandler()
   * val lithoView = LithoTestRule.render(componentTree = componentTree)
   * ```
   */
  fun withThrowOnErrorHandler(): ComponentTree {
    return ComponentTree.create(context)
        .componentsConfiguration(
            ComponentsConfiguration.create(context.lithoConfiguration.componentsConfig)
                .errorEventHandler(
                    object : ErrorEventHandler() {
                      override fun onError(cc: ComponentContext, e: Exception): Component? {
                        throw e
                      }
                    },
                )
                .build(),
        )
        .build()
  }

  companion object {
    private const val MAX_IDLE_PASSES = 10
  }
}
