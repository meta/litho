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

import com.facebook.litho.state.StateId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class StateUpdaterDelegatorTest {

  private val delegator = StateUpdaterDelegator()
  private val globalKey = StringGlobalKey("key")
  private val stateId = StateId.forSpec(treeId = 1, globalKey = globalKey)

  @Test
  fun `throws before a state updater is attached`() {
    assertThatThrownBy { delegator.isFirstMount }.isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun `behaves like a released tree once detached`() {
    val updater = mock<StateUpdater>()
    delegator.attachStateUpdater(updater)

    delegator.detachStateUpdater(updater)
    delegator.updateHookStateAsync(stateId, HookUpdater.NoOp, "attribution", false)
    val container = mock<StateContainer>()

    assertThat(delegator.isFirstMount).isFalse()
    assertThat(delegator.canSkipStateUpdate(stateId, newValue = 1, isLayoutState = false)).isFalse()
    assertThat(delegator.getEventTrigger("trigger")).isNull()
    assertThat(delegator.applyLazyStateUpdatesForContainer(globalKey, container, false))
        .isSameAs(container)
    verifyNoInteractions(updater)
  }

  @Test
  fun `routes to a state updater attached after detaching`() {
    val released = mock<StateUpdater>()
    delegator.attachStateUpdater(released)
    delegator.detachStateUpdater(released)
    val next = mock<StateUpdater>()

    delegator.attachStateUpdater(next)
    delegator.updateHookStateAsync(stateId, HookUpdater.NoOp, "attribution", false)

    verify(next).updateHookStateAsync(any(), any(), any(), any())
  }

  @Test
  fun `detaching an updater that is no longer the delegate keeps the current one`() {
    val released = mock<StateUpdater>()
    val current = mock<StateUpdater>()
    delegator.attachStateUpdater(released)
    delegator.attachStateUpdater(current)

    delegator.detachStateUpdater(released)
    delegator.updateHookStateAsync(stateId, HookUpdater.NoOp, "attribution", false)

    assertThat(delegator.delegate).isSameAs(current)
    verify(current).updateHookStateAsync(any(), any(), any(), any())
  }
}
