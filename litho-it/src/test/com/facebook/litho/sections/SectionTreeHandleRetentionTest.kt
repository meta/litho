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

package com.facebook.litho.sections

import android.app.Activity
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.EmptyComponent
import com.facebook.litho.EventDispatchInfo
import com.facebook.litho.EventHandler
import com.facebook.litho.Handle
import com.facebook.litho.KComponent
import com.facebook.litho.NoOpEventHandler
import com.facebook.litho.Style
import com.facebook.litho.annotations.EventHandlerRebindMode
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.eventHandlerWithReturn
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.sections.common.DataDiffSection
import com.facebook.litho.sections.common.RenderEvent
import com.facebook.litho.sections.common.SingleComponentSection
import com.facebook.litho.sections.widget.RecyclerCollectionEventsController
import com.facebook.litho.testing.sections.TestSectionCreator
import com.facebook.litho.testing.sections.TestSectionCreator.createChangeSetComponent
import com.facebook.litho.testing.sections.TestTarget
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.EventHandlerBindingSection
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.litho.widget.SmoothScrollAlignmentType
import com.facebook.litho.widget.collection.RecyclerCollectionComponent
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.px
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import java.lang.ref.WeakReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@Suppress("DEPRECATION")
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class SectionTreeHandleRetentionTest {

  private var previousWeakHandles = false

  @Before
  fun setUp() {
    previousWeakHandles = ComponentsConfiguration.weakSectionTreeHandles
    ComponentsConfiguration.weakSectionTreeHandles = true
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.weakSectionTreeHandles = previousWeakHandles
  }

  @Test
  fun `handles kept by app code do not keep the list of a released tree alive`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup()
    val controller = CapturingEventsController()
    val section =
        SingleComponentSection.create(SectionContext(activity.get()))
            .component(EmptyComponent())
            .build()
    val tree = ComponentTree.create(ComponentContext(activity.get()), EmptyComponent()).build()
    val handler = renderList(tree, controller, section)
    val sectionTree = checkNotNull(controller.latest)

    tree.release()

    awaitCollected(sectionTree)
    val context = handler.dispatchInfo.componentContext as SectionContext
    assertThat(context.sectionTree).isNull()
    assertThat(SectionLifecycle.getEventTrigger(context, 1, "key")).isNull()
    assertThat(SectionLifecycle.getEventTrigger(context, 1, Handle())).isNull()
    controller.requestRefresh(true)
    controller.requestScroll("id", 0)
    controller.requestSmoothScroll("id", 0, SmoothScrollAlignmentType.DEFAULT)
    controller.requestFocusOnRoot(1, 0)
    activity.pause().stop().destroy()
  }

  @Test
  fun `handles keep their list while weak handles are off`() {
    ComponentsConfiguration.weakSectionTreeHandles = false
    val activity = Robolectric.buildActivity(Activity::class.java).setup()
    val controller = CapturingEventsController()
    val section =
        SingleComponentSection.create(SectionContext(activity.get()))
            .component(EmptyComponent())
            .build()
    val tree = ComponentTree.create(ComponentContext(activity.get()), EmptyComponent()).build()
    val handler = renderList(tree, controller, section)

    tree.release()
    Runtime.getRuntime().gc()

    val context = handler.dispatchInfo.componentContext as SectionContext
    assertThat(context.sectionTree).isNotNull()
    activity.pause().stop().destroy()
  }

  @Test
  fun `weak handles off preserves the missing tree trigger error`() {
    ComponentsConfiguration.weakSectionTreeHandles = false
    val base = SectionContext(org.robolectric.RuntimeEnvironment.getApplication())
    val section = SingleComponentSection.create(base).component(EmptyComponent()).build()
    val context = SectionContext.withScope(base, section)
    assertThatThrownBy { SectionLifecycle.getEventTrigger(context, 1, Handle()) }
        .isInstanceOf(NullPointerException::class.java)
    assertThat(context.sectionScope).isSameAs(section)
  }

  @Test
  fun `creates no-op event handlers without a fatal report once its tree is collected`() {
    val fatalReports = mutableListOf<String>()
    val previousReporter = ErrorReporter.instance
    ErrorReporter.provide { level, categoryKey, _, _, _, _ ->
      if (level == LogLevel.FATAL) {
        fatalReports += categoryKey
      }
    }
    try {
      val (context, tree) = collectableTreeContext()
      repeat(30) {
        if (tree.get() != null) {
          System.gc()
          System.runFinalization()
        }
      }
      assertThat(tree.get()).isNull()
      assertThat(EventHandlerBindingSection.onRowClick(context))
          .isInstanceOf(NoOpEventHandler::class.java)
      assertThat(fatalReports).isEmpty()
    } finally {
      ErrorReporter.provide(previousReporter)
    }
  }

  @Test
  fun `refresh goes to the tree bound while showing the spinner`() {
    for (weak in listOf(false, true)) {
      ComponentsConfiguration.weakSectionTreeHandles = weak
      val activity = Robolectric.buildActivity(Activity::class.java).setup()
      val context = SectionContext(activity.get())
      val first = SectionTree.create(context, TestTarget()).build()
      val second = SectionTree.create(context, TestTarget()).build()
      first.setRoot(createChangeSetComponent("first"))
      second.setRoot(createChangeSetComponent("second"))
      ShadowLooper.idleMainLooper()
      val firstRoot = currentSection(first)
      val secondRoot = currentSection(second)
      val controller = RecyclerCollectionEventsController()
      val view =
          object : SectionsRecyclerView(activity.get(), RecyclerView(activity.get())) {
            override fun setRefreshing(refreshing: Boolean) {
              if (refreshing) {
                controller.setSectionTree(second)
              }
              super.setRefreshing(refreshing)
            }
          }
      controller.setSectionsRecyclerView(view)
      controller.setSectionTree(first)

      controller.requestRefresh(true)

      assertThat(firstRoot.refreshCalled).describedAs("weak=%s", weak).isFalse()
      assertThat(secondRoot.refreshCalled).describedAs("weak=%s", weak).isTrue()
      first.release()
      second.release()
    }
  }

  @Test
  fun `a live owner keeps its list through forced GCs`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup()
    val controller = CapturingEventsController()
    val section =
        SingleComponentSection.create(SectionContext(activity.get()))
            .component(EmptyComponent())
            .build()
    val owner = ComponentTree.create(ComponentContext(activity.get()), EmptyComponent()).build()
    renderList(owner, controller, section)
    repeat(20) {
      System.gc()
      System.runFinalization()
      Thread.sleep(10)
    }
    assertThat(owner.isReleased).isFalse()
    assertThat(controller.latest?.get()).isNotNull()
    assertThat(controller.latest?.get()?.isReleased).isFalse()
    controller.requestRefresh(false)
    owner.release()
    activity.pause().stop().destroy()
  }

  private fun renderList(
      tree: ComponentTree,
      controller: CapturingEventsController,
      section: Section,
  ): EventHandler<Any> {
    tree.setRootAndSizeSpecSync(
        RowsList(listOf("a", "b", "c"), controller),
        exactly(SIZE_PX),
        exactly(SIZE_PX),
    )
    val sectionTree = checkNotNull(controller.latest?.get())
    val context = SectionContext.withScope(sectionTree.context, section)
    return EventHandler(1, EventHandlerRebindMode.REBIND, EventDispatchInfo(section, context), null)
  }

  private fun awaitCollected(ref: WeakReference<*>) {
    repeat(20) {
      if (ref.get() == null) {
        return
      }
      Runtime.getRuntime().gc()
      Thread.sleep(10)
    }
    fail<Unit>("The SectionTree of a released tree was not garbage collected")
  }

  private class CapturingEventsController : RecyclerCollectionEventsController() {
    var latest: WeakReference<SectionTree>? = null

    override fun setSectionTree(sectionTree: SectionTree?) {
      super.setSectionTree(sectionTree)
      latest = sectionTree?.let { WeakReference(it) }
    }
  }

  private class RowsList(
      private val rows: List<String>,
      private val controller: RecyclerCollectionEventsController,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = RecyclerCollectionComponent(
        section =
            DataDiffSection.create<String>(SectionContext(context))
                .data(rows)
                .renderEventHandler(
                    eventHandlerWithReturn { event: RenderEvent<out String> ->
                      ComponentRenderInfo.create()
                          .component(Text(text = event.model, style = Style.height(ROW_PX.px)))
                          .build()
                    },
                )
                .build(),
        disablePTR = true,
        eventsController = controller,
        style = Style.height(SIZE_PX.px),
    )
  }

  private companion object {
    const val SIZE_PX = 100
    const val ROW_PX = 20
  }

  private fun collectableTreeContext(): Pair<SectionContext, WeakReference<SectionTree>> {
    val activity = Robolectric.buildActivity(Activity::class.java).setup()
    val tree = SectionTree.create(SectionContext(activity.get()), TestTarget()).build()
    tree.release()
    return tree.context to WeakReference(tree)
  }

  private fun currentSection(tree: SectionTree): TestSectionCreator.TestSection =
      SectionTree::class
          .java
          .getDeclaredField("mCurrentSection")
          .apply { isAccessible = true }
          .get(tree) as TestSectionCreator.TestSection
}
