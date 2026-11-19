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

import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config

@RequiresApi(Build.VERSION_CODES.R)
@Config(sdk = [33])
@RunWith(LithoTestRunner::class)
class SoftInputVisibilityTest {

  @Test
  fun `a window that reports the keyboard hidden is hidden`() {
    assertThat(isSoftInputReportedHidden(viewWithInsets(imeVisible = false))).isTrue()
  }

  @Test
  fun `a window that reports the keyboard shown is not hidden`() {
    assertThat(isSoftInputReportedHidden(viewWithInsets(imeVisible = true))).isFalse()
  }

  @Test
  fun `a view without window insets is not known to be hidden`() {
    val view = mock<View>()
    whenever(view.rootWindowInsets).thenReturn(null)

    assertThat(isSoftInputReportedHidden(view)).isFalse()
  }

  private fun viewWithInsets(imeVisible: Boolean): View {
    val insets = WindowInsets.Builder().setVisible(WindowInsets.Type.ime(), imeVisible).build()
    val view = mock<View>()
    whenever(view.rootWindowInsets).thenReturn(insets)
    return view
  }
}
