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

package com.facebook.litho.rangemanager

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.Component
import com.facebook.litho.LithoView
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.BackgroundLayoutLooperRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.SizeConstraints
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * With [LithoCollectionItem]'s `updatesShownTreeAsync`, new content for a view already showing the
 * item's tree is laid out in the background; without it, bind lays it out right away.
 */
@RunWith(LithoTestRunner::class)
class LithoCollectionItemShownTreeAsyncTest {

  @JvmField @Rule val backgroundLayoutLooperRule = BackgroundLayoutLooperRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val built = mutableListOf<Component>()
  private var title = "first"

  @Test
  fun `a rebind of the same view keeps the committed layout until the background layout lands`() {
    val view = LithoView(context)
    val item = item(updatesShownTreeAsync = true)
    item.bind(view)

    title = "second"
    item.bind(view)

    assertThat(committedRootIsLatest(view)).isFalse
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    shadowOf(Looper.getMainLooper()).idle()
    assertThat(committedRootIsLatest(view)).isTrue
  }

  @Test
  fun `a rebind of the same view lays the new content out right away without the option`() {
    val view = LithoView(context)
    val item = item(updatesShownTreeAsync = false)
    item.bind(view)

    title = "second"
    item.bind(view)

    assertThat(committedRootIsLatest(view)).isTrue
  }

  @Test
  fun `a view new to the tree binds the new content right away with the option`() {
    val item = item(updatesShownTreeAsync = true)
    item.bind(LithoView(context))
    item.unbind()

    title = "second"
    val view = LithoView(context)
    item.bind(view)

    assertThat(committedRootIsLatest(view)).isTrue
  }

  @Test
  fun `a view new to the tree shows the latest content while an async update is in flight`() {
    val item = item(updatesShownTreeAsync = true)
    val first = LithoView(context)
    item.bind(first)
    title = "second"
    item.bind(first)
    item.unbind()

    val second = LithoView(context)
    item.bind(second)

    // The background layout never ran, so only a synchronous layout commits the latest root.
    assertThat(
        checkNotNull(checkNotNull(second.componentTree).getCommittedLayoutState())
            .isForComponentId(built.last().instanceId),
    )
        .isTrue
  }

  private fun item(updatesShownTreeAsync: Boolean) = LithoCollectionItem(
      context = context,
      dataProvider = { title },
      component = { Row().also { built += it } },
      sizeConstraints = SizeConstraints.exact(100, 100),
      traceTag = "test",
      updatesShownTreeAsync = updatesShownTreeAsync,
  )

  private fun committedRootIsLatest(view: LithoView): Boolean =
      checkNotNull(checkNotNull(view.componentTree).mainThreadLayoutState)
          .isForComponentId(built.last().instanceId)
}
