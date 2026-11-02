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

package com.facebook.litho.animation

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.facebook.rendercore.thread.utils.ThreadUtils

/** Observes application settings after a UI commit supplies the resolver. */
internal class SystemDurationScale(private val onScaleChanged: (Float) -> Unit) {
  private var resolver: ContentResolver? = null
  private var observer: ContentObserver? = null
  var isOverridden = false
    private set

  fun bind(context: Context) {
    if (!isOverridden && resolver == null) {
      ThreadUtils.assertMainThread()
      resolver = context.applicationContext.contentResolver
    }
  }

  fun initialize() {
    if (isOverridden || observer != null) return
    val resolver = resolver ?: return
    val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
          override fun onChange(selfChange: Boolean) {
            if (this@SystemDurationScale.observer === this) {
              onScaleChanged(read(resolver))
            }
          }
        }
    resolver.registerContentObserver(
        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
        false,
        observer,
    )
    this.observer = observer
    onScaleChanged(read(resolver))
  }

  private fun read(resolver: ContentResolver): Float =
      Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

  internal inline fun <T> withOverride(observeSystemDurationScale: Boolean, block: () -> T): T {
    val previousResolver = resolver
    val previousObserver = observer
    val previousOverride = isOverridden
    resolver = null
    observer = null
    isOverridden = !observeSystemDurationScale
    return try {
      block()
    } finally {
      observer?.let { resolver?.unregisterContentObserver(it) }
      resolver = previousResolver
      observer = previousObserver
      isOverridden = previousOverride
    }
  }
}
