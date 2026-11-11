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

package com.facebook.rendercore.thread.utils

/** Config class to enable or disable specific features. */
object RenderCoreThreadUtilsConfig {

  /**
   * Defaults to the presence of an
   * <pre>IS_TESTING</pre>
   *
   * system property at startup but can be overridden at runtime.
   *
   * [ThreadUtils.assertMainThread] no longer reads this flag: an end-to-end or instrumentation run
   * enforces main-thread assertions like production. Tests that drive main-thread-confined code
   * must run it on the UI thread, for example with `@UiThreadTest`.
   */
  @Volatile @JvmField var isEndToEndTestRun: Boolean = System.getProperty("IS_TESTING") != null
}
