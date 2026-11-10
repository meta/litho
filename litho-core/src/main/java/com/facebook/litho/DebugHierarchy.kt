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

import com.facebook.litho.LithoLayoutData.Companion.verifyAndGetLithoLayoutData
import kotlin.jvm.JvmField

/**
 * [DebugHierarchy] provides a light(er) weight way to track and access information about the
 * component parentage of a given [com.facebook.rendercore.MountItem]. For a given
 * [com.facebook.rendercore.MountItem], it provides access to a linked list of [Class] objects
 * representing the class of the [Component] and each of it's hierarchy parents.
 */
object DebugHierarchy {

  @JvmStatic
  fun newNode(parent: Node?, component: Component, components: List<Component>): Node =
      Node(parent, component, components, OutputUnitType.HOST)

  @JvmStatic fun getMountItemCount(host: ComponentHost): Int = host.mountItemCount

  @JvmStatic
  fun getMountItemContent(host: ComponentHost, mountItemIndex: Int): Any =
      host.getMountItemAt(mountItemIndex).content

  @JvmStatic
  fun getMountItemHierarchy(host: ComponentHost, mountItemIndex: Int): Node? =
      verifyAndGetLithoLayoutData(host.getMountItemAt(mountItemIndex).renderTreeNode.layoutData)
          .debugHierarchy

  @JvmStatic
  fun getOutputUnitTypeName(@OutputUnitType type: Int): String? =
      when (type) {
        OutputUnitType.CONTENT -> "CONTENT"
        OutputUnitType.BACKGROUND -> "BACKGROUND"
        OutputUnitType.FOREGROUND -> "FOREGROUND"
        OutputUnitType.HOST -> "HOST"
        OutputUnitType.BORDER -> "BORDER"
        else -> null
      }

  class Node(
      @JvmField val parent: Node?,
      @JvmField val component: Component,
      @JvmField val components: List<Component>,
      @Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
      @OutputUnitType
      @JvmField
      val type: Int,
  ) {

    fun mutateType(type: Int): Node =
        if (this.type == type) {
          this
        } else {
          Node(parent, component, components, type)
        }

    private fun toHierarchyString(sb: StringBuilder) {
      parent?.toHierarchyString(sb)
      if (components.isEmpty()) {
        sb.append("(no components)")
        sb.append(',')
        return
      }
      for (i in components.indices.reversed()) {
        sb.append(components[i].simpleName)
        sb.append(',')
      }
    }

    fun toHierarchyString(): String {
      val sb = StringBuilder()
      sb.append('{')
      toHierarchyString(sb)
      if (sb.length > 1) {
        sb.deleteCharAt(sb.length - 1)
      }
      sb.append('}')
      return sb.toString()
    }
  }
}
