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

import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel

/** Default implementation of ErrorEvent handler. */
object DefaultErrorEventHandler : ErrorEventHandler() {

  private const val DEFAULT_ERROR_EVENT_HANDLER = "DefaultErrorEventHandler"

  @Suppress("SENSELESS_COMPARISON")
  override fun onError(cc: ComponentContext, e: Exception): Component? {
    var e = e
    if (cc != null) {
      var categoryKey = "${DEFAULT_ERROR_EVENT_HANDLER}:${cc.logTag}"
      if (e is ReThrownException) {
        e = e.original
      }
      if (e is LithoMetadataExceptionWrapper) {
        val crashingComponentName = e.crashingComponentName
        if (crashingComponentName != null) {
          categoryKey = "$categoryKey:$crashingComponentName"
        }
      }
      val errorMessage = e.message.orEmpty()
      ErrorReporter.report(LogLevel.ERROR, categoryKey, errorMessage)
    }
    ComponentUtils.rethrow(e)
    return null
  }
}
