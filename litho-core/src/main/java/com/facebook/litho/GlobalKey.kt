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

import com.facebook.litho.config.LithoDebugConfigurations
import java.util.Objects

/**
 * A sealed class hierarchy representing a component's global key in the Litho component tree.
 *
 * Instead of storing the full concatenated string path (e.g. "42,17,5!1"), subclasses can store key
 * segments structurally (typeId + position + parent pointer), reducing memory allocations.
 *
 * All downstream consumers continue to receive [String] via [toString] bridges.
 */
sealed class GlobalKey(open val key: Any, val parent: GlobalKey? = null) {

  protected var cachedHashCode: Int = 0

  abstract override fun equals(other: Any?): Boolean

  override fun hashCode(): Int {
    if (cachedHashCode != 0) return cachedHashCode
    val h = calculateHashCode()
    cachedHashCode = h
    return h
  }

  override fun toString(): String {
    return buildKeyString()
  }

  protected abstract fun buildKeyString(): String

  protected abstract fun calculateHashCode(): Int

  fun isDescendantOf(ancestor: GlobalKey): Boolean {
    var current: GlobalKey? = this.parent
    while (current != null) {
      if (current == ancestor) return true
      current = current.parent
    }
    return false
  }

  /**
   * Returns true if this key equals [ancestor] or is a descendant of it.
   *
   * For structural keys ([DefaultGlobalKey], [ManualGlobalKey]) the parent chain is walked with no
   * string allocation. When either side is a [StringGlobalKey] (legacy, flag-off path) falls back
   * to [toString] prefix comparison.
   *
   * Note: the [StringGlobalKey] fallback uses [String.startsWith], which can produce false
   * positives across sibling keys that share a textual prefix -- e.g. with `ancestor = "1"`, the
   * key `"10,2"` is reported as a descendant even though it is not. This behaviour is inherited
   * from the pre-structural code path and is preserved for compatibility.
   */
  fun isOrIsDescendantOf(ancestor: GlobalKey): Boolean {
    if (this == ancestor) return true
    if (isDescendantOf(ancestor)) return true
    // StringGlobalKey has no parent pointer -- need string fallback for legacy paths.
    // DefaultGlobalKey and ManualGlobalKey always have complete parent chains.
    if (this !is StringGlobalKey && ancestor !is StringGlobalKey) {
      return false
    }
    return toString().startsWith(ancestor.toString())
  }
}

/**
 * Wraps a full concatenated string key (current behavior). Used for all keys when the config flag
 * is off (legacy/control path). Has no parent pointer -- structural traversal is not possible.
 */
class StringGlobalKey(override val key: String) : GlobalKey(key = key, parent = null) {

  override fun buildKeyString(): String = key

  override fun calculateHashCode(): Int {
    return key.hashCode()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StringGlobalKey) return false
    return key == other.key
  }
}

/**
 * Memory-efficient linked structure for auto-generated keys. Used when the config flag is on and no
 * manual key is present in the ancestry.
 *
 * [key] is the component's typeId, [position] is the sibling index (0 = first of this type).
 *
 * [buildKeyString] produces the same format as the current string concatenation:
 * `"parentKey,typeId"` for position 0, `"parentKey,typeId!N"` for position N > 0.
 */
class DefaultGlobalKey(
    override val key: Int,
    val position: Int,
    parent: GlobalKey?,
) : GlobalKey(key, parent) {

  override fun buildKeyString(): String {
    // The full structural key string is an O(depth^2) ancestor-path rebuild and is only needed for
    // debugging/inspection. In non-debug builds toString() is no longer used as a functional key
    // (consumers hold GlobalKey directly), so return a cheap identity string instead.
    if (!LithoDebugConfigurations.isDebugModeEnabled) {
      return "GlobalKey@${hashCode()}"
    }
    return ComponentsSystrace.trace({ "DefaultGlobalKey.buildKeyString" }) {
      val parentStr = parent?.toString()
      val base = if (parentStr != null) "$parentStr,$key" else "$key"
      if (position == 0) base else "$base!$position"
    }
  }

  override fun calculateHashCode(): Int {
    return Objects.hash(parent, key, position)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DefaultGlobalKey) return false
    return key == other.key && position == other.position && parent == other.parent
  }
}

/**
 * Structural key for components with a manual key (set via `.key("...")`). Unlike
 * [StringGlobalKey], this preserves the parent pointer so that structural descendant checks work
 * across manual key boundaries without falling back to string prefix comparison.
 *
 * [key] is the manual key string (prefixed with "$"), [position] is the manual key usage count (0 =
 * first usage, >0 = duplicate).
 */
class ManualGlobalKey(
    override val key: String,
    val position: Int,
    parent: GlobalKey?,
) : GlobalKey(key, parent) {

  override fun buildKeyString(): String {
    // See DefaultGlobalKey.buildKeyString: skip the O(depth^2) structural rebuild in non-debug
    // builds, where toString() is no longer used as a functional key.
    if (!LithoDebugConfigurations.isDebugModeEnabled) {
      return "GlobalKey@${hashCode()}"
    }
    return ComponentsSystrace.trace({ "ManualGlobalKey.buildKeyString" }) {
      val parentStr = parent?.toString()
      @Suppress("REDUNDANT_SINGLE_EXPRESSION_STRING_TEMPLATE")
      val base = if (parentStr != null) "$parentStr,$key" else "$key"
      if (position == 0) base else "$base!$position"
    }
  }

  override fun calculateHashCode(): Int {
    return Objects.hash(parent, key, position)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ManualGlobalKey) return false
    return key == other.key && position == other.position && parent == other.parent
  }
}
