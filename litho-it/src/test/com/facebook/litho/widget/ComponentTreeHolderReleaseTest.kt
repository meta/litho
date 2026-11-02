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

import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.LithoVisibilityEventsController
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState
import com.facebook.litho.LithoVisibilityEventsControllerDelegate
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec
import com.facebook.litho.StateUpdaterDelegator
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.lang.ref.WeakReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class ComponentTreeHolderReleaseTest {

  private val context = ComponentContext(ApplicationProvider.getApplicationContext())

  private val detachingConfiguration =
      ComponentsConfiguration.create().detachStateUpdaterOnTreeRelease(true).build()

  @Test
  fun `released tree is collected while its holder is alive`() {
    val holder = createHolder(detachingConfiguration)

    val tree = layoutAndRelease(holder)

    awaitCollected(tree)
    assertNewTreeAttaches(holder)
  }

  @Test
  fun `tree released through the holder's visibility controller is collected`() {
    val parentController = LithoVisibilityEventsControllerDelegate()
    val holder = createHolder(detachingConfiguration, parentController)

    // With a visibility controller, releaseTree moves the holder's own controller to DESTROYED.
    val tree = layoutAndRelease(holder)

    awaitCollected(tree)
    assertNewTreeAttaches(holder)
  }

  @Test
  fun `tree is collected when the parent visibility controller is destroyed`() {
    val parentController = LithoVisibilityEventsControllerDelegate()
    val holder = createHolder(detachingConfiguration, parentController)
    val tree = layout(holder)

    parentController.moveToVisibilityState(LithoVisibilityState.DESTROYED)

    awaitCollected(tree)
    assertThat(holder.componentTree).isNull()
  }

  @Test
  fun `released tree stays reachable through its holder when the flag is off`() {
    val holder = createHolder(ComponentsConfiguration.defaultInstance)

    val tree = layoutAndRelease(holder)

    assertThat(isCollected(tree)).isFalse()
    assertThat(holder.componentTree).isNull()
  }

  @Test
  fun `releasing a holder keeps a shared delegator routed to the other holder's tree`() {
    val sharedDelegator = StateUpdaterDelegator()
    val holderA = createHolder(detachingConfiguration, stateUpdaterDelegator = sharedDelegator)
    val holderB = createHolder(detachingConfiguration, stateUpdaterDelegator = sharedDelegator)
    layout(holderA)
    layout(holderB)
    val treeB = checkNotNull(holderB.componentTree)

    holderA.releaseTree()

    assertRoutesTo(sharedDelegator, treeB)
  }

  @Test
  fun `releasing through a visibility controller keeps a shared delegator on the other tree`() {
    val sharedDelegator = StateUpdaterDelegator()
    val holderA = createHolder(
        detachingConfiguration,
        LithoVisibilityEventsControllerDelegate(),
        sharedDelegator,
    )
    val holderB = createHolder(
        detachingConfiguration,
        LithoVisibilityEventsControllerDelegate(),
        sharedDelegator,
    )
    layout(holderA)
    layout(holderB)
    val treeB = checkNotNull(holderB.componentTree)

    holderA.releaseTree()

    assertRoutesTo(sharedDelegator, treeB)
  }

  @Test
  fun `destroying a released tree's controller again keeps the replacement tree routed`() {
    val delegator = StateUpdaterDelegator()
    val holder = createHolder(
        detachingConfiguration,
        LithoVisibilityEventsControllerDelegate(),
        delegator,
    )
    layout(holder)
    val firstController =
        checkNotNull(checkNotNull(holder.componentTree).lithoVisibilityEventsController)
    holder.releaseTree()
    layout(holder)
    val replacement = checkNotNull(holder.componentTree)

    firstController.moveToVisibilityState(LithoVisibilityState.DESTROYED)

    assertRoutesTo(delegator, replacement)
  }

  private fun createHolder(
      configuration: ComponentsConfiguration,
      parentController: LithoVisibilityEventsController? = null,
      stateUpdaterDelegator: StateUpdaterDelegator? = null,
  ): ComponentTreeHolder =
      ComponentTreeHolder.create(configuration)
          .renderInfo(ComponentRenderInfo.create().component(SimpleMountSpecTester()).build())
          .lithoVisibilityEventsController(parentController)
          .stateUpdaterDelegator(stateUpdaterDelegator)
          .build()

  private fun assertRoutesTo(delegator: StateUpdaterDelegator, tree: ComponentTree) {
    assertThat(delegator.delegate).isSameAs(tree)
    val flipped = !tree.isFirstMount
    delegator.isFirstMount = flipped
    assertThat(tree.isFirstMount).isEqualTo(flipped)
  }

  // Both helpers keep the tree out of the calling test's stack frame so it can be collected.
  private fun layout(holder: ComponentTreeHolder): WeakReference<ComponentTree> {
    val spec = SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY)
    holder.computeLayoutSync(context, spec, spec, Size())
    return WeakReference(checkNotNull(holder.componentTree))
  }

  private fun layoutAndRelease(holder: ComponentTreeHolder): WeakReference<ComponentTree> {
    val tree = layout(holder)
    holder.releaseTree()
    return tree
  }

  private fun assertNewTreeAttaches(holder: ComponentTreeHolder) {
    assertThat(holder.componentTree).isNull()
    layout(holder)
    val newTree = checkNotNull(holder.componentTree)
    assertThat(newTree.isReleased).isFalse()
    assertThat(holder.hasCompletedLatestLayout()).isTrue()
  }

  private fun isCollected(ref: WeakReference<*>): Boolean {
    repeat(20) {
      if (ref.get() == null) {
        return true
      }
      Runtime.getRuntime().gc()
      Thread.sleep(10)
    }
    return false
  }

  private fun awaitCollected(ref: WeakReference<*>) {
    if (!isCollected(ref)) {
      fail<Unit>("Released ComponentTree was not garbage collected")
    }
  }
}
