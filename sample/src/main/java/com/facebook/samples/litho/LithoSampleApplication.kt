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

package com.facebook.samples.litho

import android.app.Application
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.flipper.android.AndroidFlipperClient
import com.facebook.flipper.android.utils.FlipperUtils
import com.facebook.flipper.plugins.inspector.DescriptorMapping
import com.facebook.flipper.plugins.inspector.InspectorFlipperPlugin
import com.facebook.flipper.plugins.sections.SectionsFlipperPlugin
import com.facebook.flipper.plugins.uidebugger.UIDebuggerFlipperPlugin
import com.facebook.flipper.plugins.uidebugger.core.UIDContext
import com.facebook.flipper.plugins.uidebugger.litho.UIDebuggerLithoSupport
import com.facebook.fresco.vito.init.FrescoVito
import com.facebook.litho.debug.LithoTouchEventDebugger
import com.facebook.litho.editor.flipper.LithoFlipperDescriptors
import com.facebook.rendercore.debug.DebugEventBus
import com.facebook.rendercore.debug.DebugEventLogger
import com.facebook.samples.litho.kotlin.debug.ExampleTouchEventSubscriber
import com.facebook.soloader.SoLoader

class LithoSampleApplication : Application() {
  // ComponentsConfiguration has a non-public ctor; copy() is the only way to derive variants.
  @Suppress("DATA_CLASS_INVISIBLE_COPY_USAGE_WARNING")
  override fun onCreate() {
    super.onCreate()

    DebugEventBus.subscribe(DebugEventLogger())

    LithoTouchEventDebugger.isEnabled = true
    DebugEventBus.subscribe(ExampleTouchEventSubscriber())

    Fresco.initialize(this)
    FrescoVito.initialize()
    SoLoader.init(this, false)

    if (FlipperUtils.shouldEnableFlipper(this)) {
      val client = AndroidFlipperClient.getInstance(this)

      val uiDebuggerContext: UIDContext = UIDContext.create(this)
      UIDebuggerLithoSupport.enable(uiDebuggerContext)
      client.addPlugin(UIDebuggerFlipperPlugin(uiDebuggerContext))

      val descriptorMapping: DescriptorMapping = DescriptorMapping.withDefaults()
      LithoFlipperDescriptors.add(descriptorMapping)
      client.addPlugin(InspectorFlipperPlugin(this, descriptorMapping))
      client.addPlugin(SectionsFlipperPlugin(true))
      client.start()
    }
  }
}
