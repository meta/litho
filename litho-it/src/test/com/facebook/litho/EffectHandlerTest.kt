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

import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.effects.ContextualEffectHandler
import com.facebook.litho.effects.EffectContext
import com.facebook.litho.effects.EffectHandler
import com.facebook.litho.effects.OptimizedContextualEffectHandler
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.dp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class EffectHandlerTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun fixedDepInEffectDoesNotTriggerCleanupAndRun() {
    val counter = mutableMapOf<EffectPhase, Int>()
    val testView = lithoTestRule.render { EffectTestComponent(counter) }
    assertThat(counter).isEqualTo(phaseCount(run = 1))

    lithoTestRule.act(testView) { clickOnTag(TAG) }
    assertThat(counter).isEqualTo(phaseCount(run = 1))
  }

  @Test
  fun changingDepInEffectTriggersCleanupAndRun() {
    val counter = mutableMapOf<EffectPhase, Int>()
    val testView = lithoTestRule.render { EffectTestComponent(counter, readStateInDep = true) }
    assertThat(counter).isEqualTo(phaseCount(run = 1))

    lithoTestRule.act(testView) { clickOnTag(TAG) }
    assertThat(counter).isEqualTo(phaseCount(run = 2, cleanup = 1))
  }

  @Test
  fun stateReadInEffectRunTriggersCleanupAndRun() {
    val counter = mutableMapOf<EffectPhase, Int>()
    val testView = lithoTestRule.render {
      EffectTestComponent(counter, readStateInEffectRun = true)
    }
    assertThat(counter).isEqualTo(phaseCount(run = 1))

    lithoTestRule.act(testView) { clickOnTag(TAG) }
    assertThat(counter).isEqualTo(phaseCount(run = 2, cleanup = 1))
  }

  @Test
  fun stateReadInDetachDoesNotTriggerCleanupAndRun() {
    val counter = mutableMapOf<EffectPhase, Int>()
    val testView = lithoTestRule.render {
      EffectTestComponent(counter, readStateInEffectCleanup = true)
    }
    assertThat(counter).isEqualTo(phaseCount(run = 1))

    lithoTestRule.act(testView) { clickOnTag(TAG) }
    assertThat(counter).isEqualTo(phaseCount(run = 1))
  }

  @Test
  fun whenDepChanges_cleanupUsesPreviousValueWhileRunUsesNewValue() {
    val stateValues = mutableListOf<Int>()
    val testView = lithoTestRule.render {
      EffectTestComponent(
          mutableMapOf(),
          stateValues = stateValues,
          readStateInDep = true,
          readStateInEffectRun = true,
          readStateInEffectCleanup = true,
      )
    }
    assertThat(stateValues).isEqualTo(listOf(0))
    stateValues.clear()

    lithoTestRule.act(testView) { clickOnTag(TAG) }
    assertThat(stateValues).isEqualTo(listOf(0, 1))
  }

  @Test
  fun whenStateReadInEffectRunChanges_cleanupUsesPreviousValueWhileRunUsesNewValue() {
    val stateValues = mutableListOf<Int>()
    val testView = lithoTestRule.render {
      EffectTestComponent(
          mutableMapOf(),
          stateValues = stateValues,
          readStateInEffectRun = true,
          readStateInEffectCleanup = true,
      )
    }
    assertThat(stateValues).isEqualTo(listOf(0))
    stateValues.clear()

    lithoTestRule.act(testView) { clickOnTag(TAG) }
    assertThat(stateValues).isEqualTo(listOf(0, 1))
  }

  @Test
  fun effectHandlerFactory_returnsContextualHandlerWhenOptimizedDisabled() {
    assertThat(EffectHandler(useOptimizedContextualEffectHandler = false))
        .isInstanceOf(ContextualEffectHandler::class.java)
  }

  @Test
  fun effectHandlerFactory_returnsOptimizedContextualHandlerWhenOptimizationAllowed() {
    assertThat(EffectHandler()).isInstanceOf(OptimizedContextualEffectHandler::class.java)
  }

  @Test
  fun optimizedContextualEffectHandler_removesOnlyCleanedEffectStateReadRecords() {
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE)
    try {
      val handler = OptimizedContextualEffectHandler()
      val state1 = testStateId(1)
      val state2 = testStateId(2)
      val cleanContext = EffectContext(DIRECT_EFFECT_TREE_ID, emptySet())
      val dirtyContext = EffectContext(DIRECT_EFFECT_TREE_ID, setOf(state1))
      val retainedStats = DirectEffectStats()
      val removedStats = DirectEffectStats()
      val rerunStats = DirectEffectStats()
      val retained = DirectEffectAttachable("retained", retainedStats, stateToRead = state1)
      val removed = DirectEffectAttachable("removed", removedStats, stateToRead = state2)

      with(handler) { cleanContext.updateEffects(listOf(retained, removed)).run() }
      assertThat(handler.getTrackedStateReadCountForTest()).isEqualTo(2)
      assertThat(handler.getTrackedEffectReadCountForTest()).isEqualTo(2)

      with(handler) {
        cleanContext
            .updateEffects(
                listOf(
                    DirectEffectAttachable("retained", DirectEffectStats(), stateToRead = state1),
                ),
            )
            .run()
      }
      assertThat(handler.getTrackedStateReadCountForTest()).isEqualTo(1)
      assertThat(handler.getTrackedEffectReadCountForTest()).isEqualTo(1)
      assertThat(removedStats.detachCount).isEqualTo(1)

      with(handler) {
        dirtyContext
            .updateEffects(
                listOf(DirectEffectAttachable("retained", rerunStats, stateToRead = state1)),
            )
            .run()
      }
      assertThat(retainedStats.detachCount).isEqualTo(1)
      assertThat(rerunStats.attachCount).isEqualTo(1)
      assertThat(handler.getTrackedStateReadCountForTest()).isEqualTo(1)
      assertThat(handler.getTrackedEffectReadCountForTest()).isEqualTo(1)
    } finally {
      ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED)
    }
  }

  @Test
  fun optimizedContextualEffectHandler_doesNotRetainLegacyStateReadRecords() {
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE)
    try {
      val handler = OptimizedContextualEffectHandler()
      val state = testStateId(1)
      val context = EffectContext(DIRECT_EFFECT_TREE_ID, emptySet())
      val stats = DirectEffectStats()

      with(handler) {
        context
            .updateEffects(
                listOf(
                    DirectEffectAttachable(
                        "legacy",
                        stats,
                        stateToRead = state,
                        useLegacyUpdateBehavior = true,
                    ),
                ),
            )
            .run()
      }

      assertThat(stats.attachCount).isEqualTo(1)
      assertThat(handler.getTrackedStateReadCountForTest()).isZero()
      assertThat(handler.getTrackedEffectReadCountForTest()).isZero()
    } finally {
      ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED)
    }
  }

  @Test
  fun optimizedContextualEffectHandler_clearsPendingEffectsWhenAttachThrows() {
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE)
    try {
      val handler = OptimizedContextualEffectHandler()
      val context = EffectContext(DIRECT_EFFECT_TREE_ID, emptySet())
      val runnable =
          with(handler) {
            context.updateEffects(
                listOf(
                    DirectEffectAttachable("throwing", DirectEffectStats(), throwOnAttach = true),
                ),
            )
          }

      assertThatThrownBy { runnable.run() }.isInstanceOf(IllegalStateException::class.java)
      assertThat(handler.hasPendingEffectsForTest()).isFalse()

      with(handler) { context.updateEffects(emptyList()).run() }
    } finally {
      ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED)
    }
  }

  @Test
  fun optimizedContextualEffectHandler_deduplicatesIncomingEffectsBeforeSchedulingRuns() {
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE)
    try {
      val handler = OptimizedContextualEffectHandler()
      val context = EffectContext(DIRECT_EFFECT_TREE_ID, emptySet())
      val currentStats = DirectEffectStats()
      val firstDuplicateStats = DirectEffectStats()
      val secondDuplicateStats = DirectEffectStats()
      val current = DirectEffectAttachable("same", currentStats, shouldUpdate = true)
      val firstDuplicate = DirectEffectAttachable("same", firstDuplicateStats)
      val secondDuplicate = DirectEffectAttachable("same", secondDuplicateStats)

      with(handler) { context.updateEffects(listOf(current)).run() }
      with(handler) { context.updateEffects(listOf(firstDuplicate, secondDuplicate)).run() }

      assertThat(currentStats.detachCount).isEqualTo(1)
      assertThat(firstDuplicateStats.attachCount).isZero()
      assertThat(secondDuplicateStats.attachCount).isEqualTo(1)

      with(handler) { context.updateEffects(emptyList()).run() }
      assertThat(firstDuplicateStats.detachCount).isZero()
      assertThat(secondDuplicateStats.detachCount).isEqualTo(1)
    } finally {
      ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED)
    }
  }

  @Test
  fun contextualEffectHandler_onCleanupKeepsEffectsAttachedFromACleanupCallback() {
    assertOnCleanupKeepsEffectsAttachedFromACleanupCallback(ContextualEffectHandler())
  }

  @Test
  fun optimizedContextualEffectHandler_onCleanupKeepsEffectsAttachedFromACleanupCallback() {
    assertOnCleanupKeepsEffectsAttachedFromACleanupCallback(OptimizedContextualEffectHandler())
  }

  /**
   * A cleanup callback that attaches effects to the same handler, e.g. by committing a newer
   * layout, leaves them attached, tracked and cleaned up by the next cleanup.
   */
  private fun assertOnCleanupKeepsEffectsAttachedFromACleanupCallback(handler: EffectHandler) {
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE)
    try {
      val state = testStateId(1)
      val context = EffectContext(DIRECT_EFFECT_TREE_ID, emptySet())
      val outgoingStats = DirectEffectStats()
      val incomingStats = DirectEffectStats()
      val incoming = DirectEffectAttachable("incoming", incomingStats, stateToRead = state)
      val outgoing = DirectEffectAttachable(
          "outgoing",
          outgoingStats,
          onFirstDetach = { with(handler) { context.updateEffects(listOf(incoming)).run() } },
      )
      with(handler) { context.updateEffects(listOf(outgoing)).run() }

      handler.onCleanup()

      assertThat(outgoingStats.detachCount).isEqualTo(1)
      assertThat(incomingStats.attachCount).isEqualTo(1)
      assertThat(handler.effects.orEmpty()).containsExactly(incoming)
      assertThat(handler.hasReadsFor(setOf(state))).isTrue()

      handler.onCleanup()

      assertThat(incomingStats.detachCount).isEqualTo(1)
      assertThat(handler.effects.orEmpty()).isEmpty()
      assertThat(handler.hasReadsFor(setOf(state))).isFalse()
    } finally {
      ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED)
    }
  }
}

private const val DIRECT_EFFECT_TREE_ID = 7
private const val TAG = "key"

private enum class EffectPhase {
  RUN,
  CLEANUP,
}

private fun testStateId(index: Int): StateId = StateId(
    DIRECT_EFFECT_TREE_ID,
    HookGlobalKey(StringGlobalKey("state:$index"), index, RenderScope.Resolve),
)

private class DirectEffectStats {
  var attachCount: Int = 0
  var detachCount: Int = 0
}

private class DirectEffectAttachable(
    override val uniqueId: Any,
    private val stats: DirectEffectStats,
    private val stateToRead: StateId? = null,
    private val useLegacyUpdateBehavior: Boolean = false,
    private val throwOnAttach: Boolean = false,
    private val shouldUpdate: Boolean = false,
    private var onFirstDetach: (() -> Unit)? = null,
) : Attachable {

  override fun attach() {
    stats.attachCount++
    if (throwOnAttach) {
      throw IllegalStateException("attach failed")
    }
    stateToRead?.let { StateReadRecorder.read(it) }
  }

  override fun detach() {
    stats.detachCount++
    val callback = onFirstDetach
    onFirstDetach = null
    callback?.invoke()
  }

  override fun shouldUpdate(nextAttachable: Attachable): Boolean = shouldUpdate

  override fun useLegacyUpdateBehavior(): Boolean = useLegacyUpdateBehavior
}

private class EffectTestComponent(
    private val counter: MutableMap<EffectPhase, Int>,
    private val stateValues: MutableList<Int> = mutableListOf(),
    private val readStateInDep: Boolean = false,
    private val readStateInEffectRun: Boolean = false,
    private val readStateInEffectCleanup: Boolean = false,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    val state = useState { 0 }
    val getState = { readState: Boolean, phase: EffectPhase? ->
      if (phase != null) counter[phase] = counter.getOrDefault(phase, 0) + 1
      if (readState) state.value else -1
    }
    useEffect(getState(readStateInDep, null)) {
      val stateValueInEffect = getState(readStateInEffectRun, EffectPhase.RUN)
      stateValues.add(stateValueInEffect)
      onCleanup {
        val stateValueInCleanup = getState(readStateInEffectCleanup, EffectPhase.CLEANUP)
        stateValues.add(stateValueInCleanup)
      }
    }
    return Column(
        style =
            Style.width(100.dp).height(100.dp).viewTag("key").onClick { state.update { it + 1 } },
    )
  }
}

private fun phaseCount(run: Int = 0, cleanup: Int = 0): Map<EffectPhase, Int> =
    mapOf(EffectPhase.RUN to run, EffectPhase.CLEANUP to cleanup).filterValues { it > 0 }
