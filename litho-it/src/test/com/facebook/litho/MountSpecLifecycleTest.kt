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

import com.facebook.litho.LifecycleStep.StepInfo
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.PreAllocationHandler
import com.facebook.litho.config.UnmountOnDetach
import com.facebook.litho.core.height
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoStatsRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.LithoTestRuleResizeMode
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.litho.widget.MountSpecLifecycleTester
import com.facebook.litho.widget.MountSpecWithMountUnmountAssertion
import com.facebook.litho.widget.PreallocatedMountSpecLifecycleTester
import com.facebook.litho.widget.RecordsShouldUpdate
import com.facebook.litho.widget.SimpleStateUpdateEmulator
import com.facebook.litho.widget.SimpleStateUpdateEmulatorSpec
import com.facebook.litho.widget.Text
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class MountSpecLifecycleTest {

  @JvmField @Rule val lithoViewRule: LithoTestRule = LithoTestRule()

  @JvmField
  @Rule
  val manualResizeLithoViewRule = LithoTestRule(resizeMode = LithoTestRuleResizeMode.MANUAL)

  @JvmField @Rule val lithoStatsRule: LithoStatsRule = LithoStatsRule()

  @JvmField @Rule val expectedException = ExpectedException.none()

  @Test
  fun lifecycle_onSetComponentWithoutLayout_shouldNotCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    manualResizeLithoViewRule.createTestLithoView {
      MountSpecLifecycleTester.create(manualResizeLithoViewRule.context)
          .lifecycleTracker(lifecycleTracker)
          .build()
    }
    manualResizeLithoViewRule.idle()
    assertThat(lifecycleTracker.steps)
        .describedAs("Only render lifecycle methods should be called")
        .containsExactly(
            LifecycleStep.ON_CREATE_INITIAL_STATE,
            LifecycleStep.ON_CREATE_TREE_PROP,
            LifecycleStep.ON_CALCULATE_CACHED_VALUE,
            LifecycleStep.ON_PREPARE,
        )
  }

  @Test
  fun lifecycle_onLayout_shouldCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    lithoViewRule.render {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .lifecycleTracker(lifecycleTracker)
          .intrinsicSize(Size(800, 600))
          .build()
    }
    assertThat(lifecycleTracker.steps)
        .describedAs("Should call the lifecycle methods in expected order")
        .containsExactly(
            LifecycleStep.ON_CREATE_INITIAL_STATE,
            LifecycleStep.ON_CREATE_TREE_PROP,
            LifecycleStep.ON_CALCULATE_CACHED_VALUE,
            LifecycleStep.ON_PREPARE,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_BOUNDS_DEFINED,
            LifecycleStep.ON_ATTACHED,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_BIND,
        )
  }

  @Test
  fun lifecycle_onLayoutWithExactSize_shouldCallLifecycleMethodsExceptMeasure() {
    val lifecycleTracker = LifecycleTracker()
    lithoViewRule.render(widthPx = 600, heightPx = 800) {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .lifecycleTracker(lifecycleTracker)
          .intrinsicSize(Size(800, 600))
          .build()
    }
    assertThat(lifecycleTracker.steps)
        .describedAs("Should call the lifecycle methods in expected order")
        .containsExactly(
            LifecycleStep.ON_CREATE_INITIAL_STATE,
            LifecycleStep.ON_CREATE_TREE_PROP,
            LifecycleStep.ON_CALCULATE_CACHED_VALUE,
            LifecycleStep.ON_PREPARE,
            LifecycleStep.ON_BOUNDS_DEFINED,
            LifecycleStep.ON_ATTACHED,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_BIND,
        )
  }

  @Test
  fun lifecycle_onDetach_shouldCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    // using a component that has an exactly the same size as the container to cover the edge case
    val testLithoView =
        lithoViewRule.render(widthPx = 800, heightPx = 600) {
          MountSpecLifecycleTester.create(lithoViewRule.context)
              .intrinsicSize(Size(800, 600))
              .lifecycleTracker(lifecycleTracker)
              .build()
        }
    lifecycleTracker.reset()
    testLithoView.detachFromWindow()
    val config = testLithoView.lithoView.configuration
    if (config != null && config.unmountOnDetach != UnmountOnDetach.Never) {
      assertThat(lifecycleTracker.steps)
          .describedAs("Should only call")
          .containsExactly(LifecycleStep.ON_UNBIND, LifecycleStep.ON_UNMOUNT)
    } else {
      assertThat(lifecycleTracker.steps)
          .describedAs("Should only call")
          .containsExactly(LifecycleStep.ON_UNBIND)
    }
  }

  @Test
  fun lifecycle_onReAttach_shouldCallLifecycleMethods() {
    val lifecycleTracker1 = LifecycleTracker()
    val lifecycleTracker2 = LifecycleTracker()

    val testLithoView =
        lithoViewRule
            .render {
              Column {
                child(
                    MountSpecLifecycleTester.create(lithoViewRule.context)
                        .lifecycleTracker(lifecycleTracker1)
                        .intrinsicSize(Size(800, 600))
                        // exclude this component from incremental mount
                        .shouldExcludeFromIncrementalMount(true)
                        .build(),
                )
                child(
                    MountSpecLifecycleTester.create(lithoViewRule.context)
                        .lifecycleTracker(lifecycleTracker2)
                        .intrinsicSize(Size(800, 600))
                        .build(),
                )
              }
            }
            .detachFromWindow()

    val config = testLithoView.lithoView.configuration
    if (config != null && config.unmountOnDetach != UnmountOnDetach.Never) {
      assertThat(lifecycleTracker1.steps)
          .describedAs("Should only call unbind because we mark it as excluded from IM")
          .contains(LifecycleStep.ON_UNBIND)
      assertThat(lifecycleTracker2.steps)
          .describedAs("Should call unmount as well")
          .contains(LifecycleStep.ON_UNBIND, LifecycleStep.ON_UNMOUNT)
    } else {
      assertThat(lifecycleTracker1.steps)
          .describedAs("Should call")
          .contains(LifecycleStep.ON_UNBIND)
      assertThat(lifecycleTracker2.steps)
          .describedAs("Should call")
          .contains(LifecycleStep.ON_UNBIND)
    }
    lifecycleTracker1.reset()
    lifecycleTracker2.reset()
    testLithoView.attachToWindow().layout()

    if (config != null && config.unmountOnDetach != UnmountOnDetach.Never) {
      assertThat(lifecycleTracker1.steps)
          .describedAs("Should call bind because it's mounted")
          .containsExactly(LifecycleStep.ON_BIND)
      assertThat(lifecycleTracker2.steps)
          .describedAs("Should call nothing because it's still unmounted")
          .isEmpty()
    } else {
      assertThat(lifecycleTracker1.steps)
          .describedAs("Should call nothing")
          .containsExactly(LifecycleStep.ON_BIND)
      assertThat(lifecycleTracker2.steps)
          .describedAs("Should call")
          .containsExactly(LifecycleStep.ON_BIND)
    }

    if (config != null && config.unmountOnDetach != UnmountOnDetach.Never) {
      testLithoView.lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.UNKNOWN)
      assertThat(lifecycleTracker2.steps)
          .describedAs("Should be mounted as we notify visible bounds changed")
          .containsExactly(LifecycleStep.ON_MOUNT, LifecycleStep.ON_BIND)
    }
  }

  @Test
  fun lifecycle_onRemeasureWithSameSpecs_shouldNotCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    val testLithoView = lithoViewRule.render {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .intrinsicSize(Size(800, 600))
          .lifecycleTracker(lifecycleTracker)
          .build()
    }
    lifecycleTracker.reset()
    testLithoView.measure()
    assertThat(lifecycleTracker.steps)
        .describedAs("No lifecycle methods should be called")
        .isEmpty()
  }

  @Test
  fun lifecycle_onRemeasureWithDifferentSpecs_shouldCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    val testLithoView = lithoViewRule.render {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .intrinsicSize(Size(800, 600))
          .lifecycleTracker(lifecycleTracker)
          .build()
    }
    lifecycleTracker.reset()
    testLithoView.setSizeSpecs(exactly(800), unspecified()).measure()
    assertThat(lifecycleTracker.steps)
        .describedAs("Should call the lifecycle methods in expected order")
        .containsExactly(LifecycleStep.ON_MEASURE, LifecycleStep.ON_BOUNDS_DEFINED)
  }

  @Test
  fun lifecycle_onRemeasureWithExactSize_shouldNotCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    val testLithoView = lithoViewRule.render {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .lifecycleTracker(lifecycleTracker)
          .intrinsicSize(Size(800, 600))
          .build()
    }
    lifecycleTracker.reset()
    testLithoView.setSizePx(800, 600).measure()
    assertThat(lifecycleTracker.steps)
        .describedAs(
            "No lifecycle methods should be called because EXACT measures should skip layout calculation.",
        )
        .isEmpty()
  }

  @Test
  fun lifecycle_onReLayoutAfterMeasureWithExactSize_shouldCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    val testLithoView = lithoViewRule.render {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .lifecycleTracker(lifecycleTracker)
          .intrinsicSize(Size(800, 600))
          .build()
    }
    lifecycleTracker.reset()
    testLithoView.setSizePx(800, 600).measure().layout()

    assertThat(lifecycleTracker.steps)
        .describedAs("Should call the lifecycle methods in expected order")
        .containsExactly(
            LifecycleStep.ON_BOUNDS_DEFINED,
            LifecycleStep.ON_UNBIND,
            LifecycleStep.ON_UNMOUNT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_BIND,
        )
  }

  @Test
  fun lifecycle_onReLayoutAfterMeasureWithExactSizeAsNonRoot_shouldCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    val testLithoView = lithoViewRule.render {
      Column.create(lithoViewRule.context)
          .child(
              MountSpecLifecycleTester.create(lithoViewRule.context)
                  .intrinsicSize(Size(800, 600))
                  .lifecycleTracker(lifecycleTracker)
                  .build(),
          )
          .build()
    }
    lifecycleTracker.reset()
    testLithoView.setSizePx(800, 600).measure().layout()
    assertThat(lifecycleTracker.steps)
        .describedAs("Should call the lifecycle methods in expected order")
        .containsExactly(
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_BOUNDS_DEFINED,
            LifecycleStep.ON_UNBIND,
            LifecycleStep.ON_UNMOUNT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_BIND,
        )
  }

  @Test
  fun lifecycle_onSetShallowCopy_shouldNotCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    val component =
        MountSpecLifecycleTester.create(lithoViewRule.context)
            .intrinsicSize(Size(800, 600))
            .lifecycleTracker(lifecycleTracker)
            .build()
    val testLithoView = lithoViewRule.render { component }
    lifecycleTracker.reset()
    testLithoView.setRoot(component.makeShallowCopy())
    assertThat(lifecycleTracker.steps)
        .describedAs("No lifecycle methods should be called")
        .isEmpty()
  }

  @Test
  fun lifecycle_onRemeasureWithCompatibleSpecs_shouldNotRemount() {
    val lifecycleTracker = LifecycleTracker()
    val testLithoView = lithoViewRule.render {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .intrinsicSize(Size(800, 600))
          .lifecycleTracker(lifecycleTracker)
          .build()
    }
    lifecycleTracker.reset()

    // Force measure call to propagate to ComponentTree
    testLithoView.measure().layout()
    assertThat(lifecycleTracker.steps)
        .describedAs("No lifecycle methods should be called because measure was compatible")
        .isEmpty()
  }

  @Test
  fun lifecycle_onSetSemanticallySimilarComponent_shouldCallLifecycleMethods() {
    val lifecycleTracker = LifecycleTracker()
    val testLithoView = lithoViewRule.render {
      MountSpecLifecycleTester.create(lithoViewRule.context)
          .intrinsicSize(Size(800, 600))
          .lifecycleTracker(lifecycleTracker)
          .build()
    }

    lifecycleTracker.reset()
    val newLifecycleTracker = LifecycleTracker()
    testLithoView.setRoot(
        MountSpecLifecycleTester.create(lithoViewRule.context)
            .intrinsicSize(Size(800, 600))
            .lifecycleTracker(newLifecycleTracker)
            .build(),
    )
    assertThat(lifecycleTracker.steps)
        .describedAs("Should call the lifecycle methods on old instance in expected order")
        .containsExactly(LifecycleStep.ON_UNBIND, LifecycleStep.ON_UNMOUNT)
    assertThat(newLifecycleTracker.steps)
        .describedAs("Should call the lifecycle methods on new instance in expected order")
        .containsExactly(
            LifecycleStep.ON_CREATE_TREE_PROP,
            LifecycleStep.ON_CALCULATE_CACHED_VALUE,
            LifecycleStep.ON_PREPARE,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_BOUNDS_DEFINED,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_BIND,
        )
  }

  @Test
  fun onSetRootWithPreallocatedMountContent_shouldCallLifecycleMethods() {
    val looper = ShadowLooper.getLooperForThread(Thread.currentThread())
    val info: List<StepInfo> = ArrayList()
    lithoViewRule.createTestLithoView(
        componentTree =
            ComponentTree.create(lithoViewRule.context)
                .componentsConfiguration(
                    ComponentsConfiguration.defaultInstance.copy(
                        preAllocationHandler =
                            PreAllocationHandler.Custom(RunnableHandler.DefaultHandler(looper)),
                    ),
                )
                .build(),
    ) {
      PreallocatedMountSpecLifecycleTester.create(lithoViewRule.context).steps(info).build()
    }

    ShadowLooper.runUiThreadTasks()
    assertThat(LifecycleStep.getSteps(info))
        .describedAs("Should call the lifecycle methods on new instance in expected order")
        .containsExactly(
            LifecycleStep.ON_PREPARE,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_BOUNDS_DEFINED,
            LifecycleStep.ON_ATTACHED,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_BIND,
        )
  }

  @Test
  fun onSetRootWithPreallocatedMountContent_shouldCallLifecycleMethodsInRenderCore() {
    val looper = ShadowLooper.getLooperForThread(Thread.currentThread())
    val info: List<StepInfo> = ArrayList()
    lithoViewRule.createTestLithoView(
        componentTree =
            ComponentTree.create(lithoViewRule.context)
                .componentsConfiguration(
                    ComponentsConfiguration.defaultInstance.copy(
                        preAllocationHandler =
                            PreAllocationHandler.Custom(RunnableHandler.DefaultHandler(looper)),
                    ),
                )
                .build(),
    ) {
      PreallocatedMountSpecLifecycleTester.create(lithoViewRule.context).steps(info).build()
    }

    ShadowLooper.runUiThreadTasks()
    assertThat(LifecycleStep.getSteps(info))
        .describedAs("Should call the lifecycle methods on new instance in expected order")
        .containsExactly(
            LifecycleStep.ON_PREPARE,
            LifecycleStep.ON_MEASURE,
            LifecycleStep.ON_BOUNDS_DEFINED,
            LifecycleStep.ON_ATTACHED,
            LifecycleStep.ON_CREATE_MOUNT_CONTENT,
            LifecycleStep.ON_MOUNT,
            LifecycleStep.ON_BIND,
        )
    assertThat(MountContentPools.mountContentPools.size)
        .describedAs("Should contain only 1 content pool")
        .isEqualTo(1)
  }

  @Test
  fun shouldUpdate_shouldUpdateIsCalled_prevAndNextAreInRightOrder() {
    val firstObject = Any()
    val shouldUpdateCalls: MutableList<Diff<Any>> = ArrayList()
    val testLithoView = lithoViewRule.render {
      RecordsShouldUpdate(
          shouldUpdateCalls = shouldUpdateCalls,
          testProp = firstObject,
      )
    }
    val secondObject = Any()
    testLithoView.setRoot(
        RecordsShouldUpdate(
            shouldUpdateCalls = shouldUpdateCalls,
            testProp = secondObject,
        ),
    )
    assertThat(shouldUpdateCalls).hasSize(1)
    assertThat(shouldUpdateCalls[0].previous).isEqualTo(firstObject)
    assertThat(shouldUpdateCalls[0].next).isEqualTo(secondObject)
  }

  /*
   * This case comes from a specific bug where when we shallow-copy components (which we do when we
   * update state) we were setting mHasManualKey to false even if there was a manual key which would
   * cause us to generate different keys between layouts.
   */
  @Test
  fun lifecycle_stateUpdateWithMultipleChildrenOfSameTypeAndManualKeys_doesNotRecreateInitialState() {
    val info_child1 = LifecycleTracker()
    val info_child2 = LifecycleTracker()
    val stateUpdater = SimpleStateUpdateEmulatorSpec.Caller()

    val testLithoView =
        lithoViewRule.render(
            componentTree = ComponentTree.create(lithoViewRule.context).build(),
        ) {
          Column.create(lithoViewRule.context)
              .child(
                  MountSpecLifecycleTester.create(lithoViewRule.context)
                      .intrinsicSize(Size(800, 600))
                      .lifecycleTracker(info_child1)
                      .key("some_key"),
              )
              .child(
                  MountSpecLifecycleTester.create(lithoViewRule.context)
                      .intrinsicSize(Size(800, 600))
                      .lifecycleTracker(info_child2)
                      .key("other_key"),
              )
              .child(SimpleStateUpdateEmulator.create(lithoViewRule.context).caller(stateUpdater))
              .build()
        }
    val mountDelegateTarget = testLithoView.lithoView.mountDelegateTarget
    assertThat(mountDelegateTarget.getMountItemCount()).isGreaterThan(1)
    info_child1.reset()
    info_child2.reset()
    stateUpdater.increment()
    assertThat(info_child1.steps)
        .describedAs("Should not recreate initial state.")
        .doesNotContain(LifecycleStep.ON_CREATE_INITIAL_STATE)
    assertThat(info_child2.steps)
        .describedAs("Should not recreate initial state.")
        .doesNotContain(LifecycleStep.ON_CREATE_INITIAL_STATE)
  }

  /**
   * This test case captures the scenario where unmount can get called on a component for which
   * mount was never invoked. 1. A layout is mounted. 2. The next layout update does not cause a new
   * mount pass. 3. When the next mount pass is triggered, an item is unmounted as it is out of the
   * view port. 4. The unmount must be called on the old (currently) mounted component.
   */
  @Test
  fun whenItemsAreUmounted_thenUnmountMustbeInvokedOnTheCurrentlyMountedComponent() {
    val c = lithoViewRule.context
    val initialComponent =
        Column.create(c)
            .heightPx(200)
            .child(Text.create(c).text("1").heightPx(100))
            .child(
                MountSpecWithMountUnmountAssertion(
                    container = MountSpecWithMountUnmountAssertion.Container(),
                    style = Style.height(100.px),
                ),
            )
            .build()
    val initialComponentTree = ComponentTree.create(c, initialComponent).build()
    val lithoView = LithoView(c.androidContext)

    // Mount a layout with the component.
    lithoView.componentTree = initialComponentTree
    lithoView.measure(exactly(100), exactly(200))
    lithoView.layout(0, 0, 100, 200)

    // Assert that the view is mounted
    assertThat(lithoView.childCount).isEqualTo(1)
    val newComponent =
        Column.create(c)
            .heightPx(200)
            .child(Text.create(c).text("1").heightPx(100))
            .child(
                MountSpecWithMountUnmountAssertion(
                    container = MountSpecWithMountUnmountAssertion.Container(),
                    style = Style.height(100.px),
                ),
            )
            .build()

    // Create a new component tree so that state is recreated
    // but use the id maps, and component tree id from the initial
    // tree so that new ids match the current one
    val newComponentTree =
        ComponentTree.create(c, newComponent)
            .overrideRenderUnitIdMap(
                initialComponentTree.lithoConfiguration.renderUnitIdGenerator,
                initialComponentTree.id,
            )
            .build()
    lithoView.componentTree = newComponentTree

    // Mount a new layout, but with a shorter height, to make the item unmount
    lithoView.measure(exactly(100), exactly(95))
    lithoView.layout(0, 0, 100, 95)

    // Assert that the items is unmounted.
    assertThat(lithoView.childCount).isEqualTo(0)
  }
}
