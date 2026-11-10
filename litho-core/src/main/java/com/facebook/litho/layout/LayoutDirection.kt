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

package com.facebook.litho.layout

import android.content.Context
import android.view.View
import com.facebook.litho.ContextUtils.isLayoutDirectionRTL
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaNode

/**
 * A class for defining layout directions. A layout direction can be left-to-right (LTR) or
 * right-to-left (RTL). It can also be inherited (from a parent) or deduced from the default
 * language script of a locale.
 */
@JvmInline
value class LayoutDirection private constructor(val value: Int) {

  val isInherit: Boolean
    get() = this == INHERIT

  val isRTL: Boolean
    get() = this == RTL

  val isLTR: Boolean
    get() = this == LTR

  fun getLayoutDirectionForView(): Int {
    return when (this) {
      LTR -> View.LAYOUT_DIRECTION_LTR
      RTL -> View.LAYOUT_DIRECTION_RTL
      INHERIT -> View.LAYOUT_DIRECTION_INHERIT
      LOCALE -> View.LAYOUT_DIRECTION_LOCALE
      else -> throw IllegalArgumentException("Unknown layout direction $value")
    }
  }

  companion object {

    /** Horizontal layout direction is from Left to Right. */
    val LTR: LayoutDirection = LayoutDirection(0)

    /** Horizontal layout direction is from Right to Left. */
    val RTL: LayoutDirection = LayoutDirection(1)

    /** Horizontal layout direction is inherited. */
    val INHERIT: LayoutDirection = LayoutDirection(2)

    /** Horizontal layout direction is deduced from the default language script for the locale. */
    val LOCALE: LayoutDirection = LayoutDirection(3)

    @JvmStatic
    fun fromInt(value: Int): LayoutDirection {
      return when (value) {
        View.LAYOUT_DIRECTION_LTR -> LTR
        View.LAYOUT_DIRECTION_RTL -> RTL
        View.LAYOUT_DIRECTION_INHERIT -> INHERIT
        View.LAYOUT_DIRECTION_LOCALE -> LOCALE
        else -> throw IllegalArgumentException("Unknown layout direction $value")
      }
    }

    fun fromContext(context: Context): LayoutDirection {
      return if (context.isLayoutDirectionRTL()) RTL else LTR
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun fromYoga(node: YogaNode): LayoutDirection {
      return when (node.layoutDirection) {
        YogaDirection.INHERIT -> INHERIT
        YogaDirection.LTR -> LTR
        YogaDirection.RTL -> RTL
        else -> throw IllegalArgumentException("Unknown layout direction ${node.layoutDirection}")
      }
    }

    fun LayoutDirection?.isNullOrInherit(): Boolean {
      return this == null || this.isInherit
    }
  }
}
