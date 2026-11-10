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

import androidx.annotation.VisibleForTesting
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel

object ComponentKeyUtils {

  private const val DUPLICATE_MANUAL_KEY = "ComponentKeyUtils:DuplicateManualKey"
  private val TYPE_ID_PATTERN = "(\\d+)".toRegex()
  private const val PREFIX_FOR_MANUAL_KEY = "$"
  private const val SEPARATOR = ","
  private const val SEPARATOR_FOR_MANUAL_KEY = ",$"

  /**
   * @param keyParts a list of objects that will be concatenated to form another component's key
   * @return a key formed by concatenating the key parts delimited by a separator.
   */
  @JvmStatic
  fun getKeyWithSeparator(vararg keyParts: Any): String = buildString {
    append(PREFIX_FOR_MANUAL_KEY).append(keyParts[0])
    for (i in 1..keyParts.lastIndex) append(SEPARATOR_FOR_MANUAL_KEY).append(keyParts[i])
  }

  @JvmStatic
  fun getKeyWithSeparator(parentGlobalKey: String, key: String): String {
    return "$parentGlobalKey$SEPARATOR$key"
  }

  @JvmStatic
  fun getKeyForChildPosition(currentKey: String, index: Int): String {
    if (index == 0) {
      return currentKey
    }
    return "$currentKey!$index"
  }

  /**
   * Generate a global key for the given component that is unique among all of this component's
   * children of the same type. If a manual key has been set on the child component using the .key()
   * method, return the manual key.
   *
   * @param parentComponent parent component within the layout context
   * @param childComponent child component with the parent context
   * @return a unique global key for this component relative to its siblings.
   */
  @JvmStatic
  fun generateGlobalKey(
      parentContext: ComponentContext,
      parentComponent: Component?,
      childComponent: Component,
  ): GlobalKey {
    val hasManualKey = childComponent.hasManualKey()

    // Fall back to string-based keys when the flag is off
    if (!ComponentsConfiguration.useMemoryEfficientGlobalKeys) {
      return StringGlobalKey(
          generateGlobalKeyString(parentContext, parentComponent, childComponent),
      )
    }

    // Root component (no parent)
    if (parentComponent == null) {
      return if (hasManualKey) {
        ManualGlobalKey(key = "$${childComponent.key}", position = 0, parent = null)
      } else {
        DefaultGlobalKey(key = childComponent.typeId, position = 0, parent = null)
      }
    }

    val parentGlobalKey: GlobalKey? = parentContext.nullableGlobalKey

    // If parent global key is null, fall back to string path (shouldn't happen normally)
    if (parentGlobalKey == null) {
      return StringGlobalKey(
          generateGlobalKeyString(parentContext, parentComponent, childComponent),
      )
    }

    // Memory-efficient path: ManualGlobalKey or DefaultGlobalKey with parent pointer.
    // No contagion -- children of manually-keyed components get DefaultGlobalKey with a
    // ManualGlobalKey parent, preserving the structural chain.
    return if (hasManualKey) {
      val key = "$${childComponent.key}"
      val index = parentContext.scopedComponentInfo.getManualKeyUsagesCountAndIncrement(key)
      if (index != 0) {
        logDuplicateManualKeyWarning(childComponent, key.substring(1))
      }
      ManualGlobalKey(key = key, position = index, parent = parentGlobalKey)
    } else {
      val index = parentContext.scopedComponentInfo.getChildCountAndIncrement(childComponent)
      DefaultGlobalKey(key = childComponent.typeId, position = index, parent = parentGlobalKey)
    }
  }

  private fun generateGlobalKeyString(
      parentContext: ComponentContext,
      parentComponent: Component?,
      childComponent: Component,
  ): String {
    val hasManualKey = childComponent.hasManualKey()
    val key = if (hasManualKey) "$${childComponent.key}" else childComponent.key
    val globalKey: String
    if (parentComponent == null) {
      globalKey = key
    } else {
      val parentGlobalKey = parentContext.globalKey.toString()
      val childKey = getKeyWithSeparator(parentGlobalKey, key)
      val index: Int
      if (hasManualKey) {
        index = parentContext.scopedComponentInfo.getManualKeyUsagesCountAndIncrement(key)
        if (index != 0) {
          logDuplicateManualKeyWarning(childComponent, key.substring(1))
        }
      } else {
        index = parentContext.scopedComponentInfo.getChildCountAndIncrement(childComponent)
      }
      globalKey = getKeyForChildPosition(childKey, index)
    }
    return globalKey
  }

  private fun logDuplicateManualKeyWarning(component: Component, key: String) {
    ErrorReporter.report(
        LogLevel.WARNING,
        DUPLICATE_MANUAL_KEY,
        "The manual key $key you are setting on this ${component.simpleName} " +
            "is a duplicate and will be changed into a unique one. " +
            "This will result in unexpected behavior if you don't change it.",
    )
  }

  @JvmStatic
  fun getKeyWithSeparatorForTest(vararg keyParts: Any): String = buildString {
    append(keyParts[0])
    for (i in 1..keyParts.lastIndex) append(SEPARATOR).append(keyParts[i])
  }

  /** @return key of a given component. */
  @VisibleForTesting fun getKeyForTesting(component: Component): String = component.getKey()

  private fun resolveTypeName(type: Any): String =
      if (type is Class<*>) type.name else type.toString()

  /**
   * Maps a key segment (e.g. "3") to a human-readable simple name using a reverse lookup map (id ->
   * type) for O(1) lookup.
   */
  @JvmStatic
  fun formatKeySegment(key: String, idToTypeMap: Map<Int, Any>): String {
    if (key.startsWith(PREFIX_FOR_MANUAL_KEY)) {
      return key
    }
    val idPart = TYPE_ID_PATTERN.find(key)?.value ?: return "id($key)"
    val id = idPart.toIntOrNull() ?: return "id($key)"
    val type = idToTypeMap[id] ?: return "id($key)"
    return if (type is Class<*>) "<cls>${type.name}</cls>" else type.toString()
  }

  /**
   * Extracts the parent component's simple name directly from a [GlobalKey].
   *
   * For [DefaultGlobalKey]: O(1) pointer dereference + map get (zero allocations).
   *
   * For [StringGlobalKey]: two [String.lastIndexOf] calls + one substring + one map get — no full
   * key split, no list allocation, no iteration over all segments.
   */
  @JvmStatic
  @Suppress("REDUNDANT_ELSE_IN_WHEN", "USELESS_CAST")
  fun parentNameFromKey(globalKey: GlobalKey?, idToTypeMap: Map<Int, Any>): String? {
    return when (globalKey) {
      null -> null
      is DefaultGlobalKey -> {
        val typeKey = (globalKey.parent ?: return null).key as? Int ?: return null
        idToTypeMap[typeKey]?.let { resolveTypeName(it) }
      }
      is ManualGlobalKey -> {
        val parent = globalKey.parent ?: return null
        when (parent) {
          is DefaultGlobalKey -> idToTypeMap[parent.key]?.let { resolveTypeName(it) }
          is ManualGlobalKey -> parent.key as? String
          is StringGlobalKey -> {
            val key = parent.key
            val lastComma = key.lastIndexOf(',')
            if (lastComma < 0) key else key.substring(lastComma + 1)
          }
        }
      }
      is StringGlobalKey -> {
        val key = globalKey.key
        val lastComma = key.lastIndexOf(',')
        if (lastComma < 0) return null
        val secondLastComma = key.lastIndexOf(',', lastComma - 1)
        val parentSegment =
            if (secondLastComma < 0) key.substring(0, lastComma)
            else key.substring(secondLastComma + 1, lastComma)
        if (parentSegment.startsWith(PREFIX_FOR_MANUAL_KEY)) return parentSegment
        val idPart = TYPE_ID_PATTERN.find(parentSegment)?.value ?: return null
        val id = idPart.toIntOrNull() ?: return null
        idToTypeMap[id]?.let { resolveTypeName(it) }
      }
      else -> null
    }
  }
}
