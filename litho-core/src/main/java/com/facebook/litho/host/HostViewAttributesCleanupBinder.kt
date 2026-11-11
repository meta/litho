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

package com.facebook.litho.host

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.view.ViewCompat
import com.facebook.litho.ComponentHost
import com.facebook.litho.ViewAttributes.Companion.setBackgroundCompat
import com.facebook.litho.ViewAttributes.Companion.unsetAccessibilityDelegate
import com.facebook.litho.ViewAttributes.Companion.unsetAmbientShadowColor
import com.facebook.litho.ViewAttributes.Companion.unsetClickHandler
import com.facebook.litho.ViewAttributes.Companion.unsetContentDescription
import com.facebook.litho.ViewAttributes.Companion.unsetFocusChangeHandler
import com.facebook.litho.ViewAttributes.Companion.unsetForeground
import com.facebook.litho.ViewAttributes.Companion.unsetInterceptTouchEventHandler
import com.facebook.litho.ViewAttributes.Companion.unsetLongClickHandler
import com.facebook.litho.ViewAttributes.Companion.unsetSpotShadowColor
import com.facebook.litho.ViewAttributes.Companion.unsetTooltipText
import com.facebook.litho.ViewAttributes.Companion.unsetTouchHandler
import com.facebook.litho.ViewAttributes.Companion.unsetViewId
import com.facebook.litho.ViewAttributes.Companion.unsetViewLayoutDirection
import com.facebook.litho.ViewAttributes.Companion.unsetViewStateListAnimator
import com.facebook.litho.ViewAttributes.Companion.unsetViewTag
import com.facebook.litho.ViewAttributes.Companion.unsetViewTags
import com.facebook.rendercore.Host
import com.facebook.rendercore.RenderUnit

class HostViewAttributesCleanupBinder(private val resetTranslation: Boolean = false) :
    RenderUnit.Binder<Any?, Host, Any> {

  override fun shouldUpdate(
      currentModel: Any?,
      newModel: Any?,
      currentLayoutData: Any?,
      nextLayoutData: Any?,
  ): Boolean = false

  override fun bind(context: Context, content: Host, model: Any?, layoutData: Any?): Any? {
    return null
  }

  override fun unbind(
      context: Context,
      content: Host,
      model: Any?,
      layoutData: Any?,
      bindData: Any?,
  ) {
    unsetAllViewAttributes(content, resetTranslation)
    if (content is ComponentHost) {
      content.cleanup()
    }
  }
}

private fun unsetAllViewAttributes(content: Host, resetTranslation: Boolean) {

  if (content is ComponentHost) {
    content.setSafeViewModificationsEnabled(true)
  }

  content.visibility = View.VISIBLE
  unsetClickHandler(content)
  unsetLongClickHandler(content)
  unsetFocusChangeHandler(content)
  unsetTouchHandler(content)
  unsetInterceptTouchEventHandler(content)
  unsetViewId(content)
  unsetViewTag(content)
  unsetViewTags(content, null)
  unsetViewStateListAnimator(content)
  ViewCompat.setElevation(content, 0f)
  unsetAmbientShadowColor(content, -1)
  unsetSpotShadowColor(content, -1)
  content.outlineProvider = ViewOutlineProvider.BACKGROUND
  content.clipToOutline = false
  content.clipChildren = true
  unsetContentDescription(content)
  unsetTooltipText(content)
  content.scaleX = 1f
  content.scaleY = 1f
  content.alpha = 1f
  content.rotation = 0f
  content.rotationX = 0f
  content.rotationY = 0f
  if (resetTranslation) {
    content.translationX = 0f
    content.translationY = 0f
  }
  content.isClickable = true
  content.isLongClickable = true
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    content.focusable = View.FOCUSABLE_AUTO
  } else {
    content.isFocusable = false
  }
  content.isEnabled = true
  content.isSelected = false
  ViewCompat.setKeyboardNavigationCluster(content, false)
  ViewCompat.setImportantForAccessibility(content, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO)
  unsetAccessibilityDelegate(content)
  setBackgroundCompat(content, null)
  unsetForeground(content)
  unsetViewLayoutDirection(content)
  content.setLayerType(View.LAYER_TYPE_NONE, null)
  ViewCompat.setSystemGestureExclusionRects(content, emptyList())

  if (content is ComponentHost) {
    content.setSafeViewModificationsEnabled(false)
  }
}
