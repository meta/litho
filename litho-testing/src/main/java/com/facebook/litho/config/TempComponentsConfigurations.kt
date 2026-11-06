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

package com.facebook.litho.config

object TempComponentsConfigurations {

  private val original: ComponentsConfiguration = ComponentsConfiguration.defaultInstance

  @JvmStatic
  @Suppress("DATA_CLASS_INVISIBLE_COPY_USAGE_WARNING")
  fun setShouldAddHostViewForRootComponent(value: Boolean) {
    ComponentsConfiguration.defaultInstance =
        ComponentsConfiguration.defaultInstance.copy(shouldAddHostViewForRootComponent = value)
  }

  @JvmStatic
  fun restoreShouldAddHostViewForRootComponent() {
    ComponentsConfiguration.defaultInstance = original
  }
}
