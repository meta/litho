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

import androidx.collection.MutableScatterMap
import com.facebook.infer.annotation.ThreadConfined
import com.facebook.infer.annotation.ThreadSafe
import com.facebook.litho.config.ComponentsConfiguration
import java.util.Collections
import java.util.Objects

/**
 * A data structure to store tree props.
 *
 * @see TreeProp
 */
@ThreadConfined(ThreadConfined.ANY)
class TreePropContainer
private constructor(
    /** Holds the entries unless [scatterMap] is set, in which case it is [UNUSED_MAP]. */
    private val map: MutableMap<TreeProp<*>, Any?>,
    /**
     * Holds the entries when [ComponentsConfiguration.useScatterMapInTreePropContainer] was true at
     * creation. Guarded by its own monitor, as `Collections.synchronizedMap` guards [map].
     */
    private val scatterMap: MutableScatterMap<TreeProp<*>, Any?>?,
) {

  constructor() : this(ComponentsConfiguration.useScatterMapInTreePropContainer)

  private constructor(
      useScatterMap: Boolean,
  ) : this(
      if (useScatterMap) UNUSED_MAP else Collections.synchronizedMap(HashMap<TreeProp<*>, Any?>()),
      if (useScatterMap) MutableScatterMap(0) else null,
  )

  fun put(key: Class<*>, value: Any?) {
    val treeProp = legacyTreePropOf(key)
    putEntry(treeProp, value)
  }

  fun <T> put(treeProp: TreeProp<out T>, value: T) {
    putEntry(treeProp, value)
  }

  private fun putEntry(treeProp: TreeProp<*>, value: Any?) {
    val scatterMap = scatterMap
    if (scatterMap != null) {
      synchronized(scatterMap) { scatterMap[treeProp] = value }
    } else {
      map[treeProp] = value
    }
  }

  /**
   * The entries, read under this container's own monitor.
   *
   * Anything spanning two containers has to go through this rather than holding one monitor while
   * reaching for the other: `equals` on a pair is reachable from both the main thread and a resolve
   * thread with the operands the other way round, and `Collections.synchronizedMap` takes the
   * receiver's monitor before it touches the argument, so operand order is lock order.
   */
  private fun snapshot(): Map<TreeProp<*>, Any?> = synchronized(map) { HashMap(map) }

  /**
   * The keys and values, interleaved, read under this container's own monitor. Used wherever a
   * [scatterMap] is involved, for the same reason as [snapshot].
   */
  private fun entries(): Array<Any?> {
    val scatterMap = scatterMap
    if (scatterMap != null) {
      return synchronized(scatterMap) {
        val entries = arrayOfNulls<Any?>(scatterMap.size * 2)
        var i = 0
        scatterMap.forEach { key, value ->
          entries[i++] = key
          entries[i++] = value
        }
        entries
      }
    }
    return synchronized(map) {
      val entries = arrayOfNulls<Any?>(map.size * 2)
      var i = 0
      for ((key, value) in map) {
        entries[i++] = key
        entries[i++] = value
      }
      entries
    }
  }

  /** Writes interleaved [entries] under this container's own monitor. */
  private fun putEntries(entries: Array<Any?>) {
    val scatterMap = scatterMap
    if (scatterMap != null) {
      synchronized(scatterMap) {
        var i = 0
        while (i < entries.size) {
          scatterMap[entries[i] as TreeProp<*>] = entries[i + 1]
          i += 2
        }
      }
    } else {
      synchronized(map) {
        var i = 0
        while (i < entries.size) {
          map[entries[i] as TreeProp<*>] = entries[i + 1]
          i += 2
        }
      }
    }
  }

  /** The value set for [key], or [MISSING]. Holds this container's monitor only for the lookup. */
  private fun valueOrMissing(key: TreeProp<*>): Any? {
    val scatterMap = scatterMap
    if (scatterMap != null) {
      return synchronized(scatterMap) { scatterMap.getOrDefault(key, MISSING) }
    }
    return synchronized(map) { if (map.containsKey(key)) map[key] else MISSING }
  }

  private fun size(): Int {
    val scatterMap = scatterMap
    return if (scatterMap != null) synchronized(scatterMap) { scatterMap.size } else map.size
  }

  @JvmName("putAll")
  internal fun putAll(treeProps: TreePropContainer?) {
    if (treeProps == null) {
      return
    }
    if (scatterMap == null && treeProps.scatterMap == null) {
      map.putAll(treeProps.snapshot())
    } else {
      putEntries(treeProps.entries())
    }
  }

  operator fun <T : Any> get(key: Class<T>): T? {
    val treeProp = legacyTreePropOf(key)
    val scatterMap = scatterMap
    if (scatterMap != null) {
      return synchronized(scatterMap) { scatterMap[treeProp] } as T?
    }
    return map[treeProp] as T?
  }

  operator fun <T> get(prop: TreeProp<T>): T {
    val scatterMap = scatterMap
    if (scatterMap != null) {
      val value = synchronized(scatterMap) { scatterMap.getOrDefault(prop, MISSING) }
      return if (value === MISSING) prop.defaultValue else value as T
    }
    if (map.containsKey(prop)) {
      return map[prop] as T
    }
    return prop.defaultValue
  }

  operator fun set(key: Class<*>, value: Any?) = put(key, value)

  fun reset() {
    val scatterMap = scatterMap
    if (scatterMap != null) {
      synchronized(scatterMap) { scatterMap.clear() }
    } else {
      map.clear()
    }
  }

  /** Whether no tree prop is set. */
  fun isEmpty(): Boolean = size() == 0

  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }

    if (other !is TreePropContainer) return false

    if (scatterMap == null && other.scatterMap == null) {
      // Only one side needs copying: comparing a private copy against the other's live map takes
      // that map's monitor per lookup and releases it, never while holding ours. Snapshotting both
      // would allocate twice on a path that is sampled during scroll.
      return snapshot() == other.map
    }

    // The same one-sided copy, compared against the other container one lookup at a time.
    val entries = entries()
    if (entries.size / 2 != other.size()) {
      return false
    }
    var i = 0
    while (i < entries.size) {
      val otherValue = other.valueOrMissing(entries[i] as TreeProp<*>)
      if (otherValue === MISSING || entries[i + 1] != otherValue) {
        return false
      }
      i += 2
    }
    return true
  }

  override fun hashCode(): Int {
    if (scatterMap == null) {
      return Objects.hash(snapshot())
    }
    val entries = entries()
    var hash = 0
    var i = 0
    while (i < entries.size) {
      hash += entries[i].hashCode() xor entries[i + 1].hashCode()
      i += 2
    }
    // What `Objects.hash` returns for a `Map` holding the same entries, so equal containers hash
    // alike whichever map backs them.
    return 31 + hash
  }

  companion object {
    private val MISSING = Any()

    /** Stands in for [map] in a container backed by its [scatterMap]; never read or written. */
    private val UNUSED_MAP: MutableMap<TreeProp<*>, Any?> = Collections.emptyMap()

    /** @return a copy of the provided TreeProps instance; returns null if source is null */
    @JvmStatic
    @ThreadSafe(enableChecks = false)
    fun copy(source: TreePropContainer?): TreePropContainer? =
        if (source == null) {
          null
        } else {
          acquire(source)
        }

    /**
     * Whenever a Spec sets tree props, the TreeProps map from the parent is copied. If parent
     * TreeProps are null, a new TreeProps instance is created to copy the current tree props.
     *
     * Infer knows that newProps is owned but doesn't know that newProps.mMap is owned.
     */
    @JvmStatic
    @ThreadSafe(enableChecks = false)
    fun acquire(source: TreePropContainer?): TreePropContainer {
      val useScatterMap = ComponentsConfiguration.useScatterMapInTreePropContainer
      if (source == null) {
        return TreePropContainer(useScatterMap)
      }
      // Unlike putAll, the destination here cannot have escaped yet, so nothing can be holding
      // its monitor and taking both in one go is safe — and saves a copy on a resolve-hot path.
      val sourceScatterMap = source.scatterMap
      if (!useScatterMap && sourceScatterMap == null) {
        val newProps = TreePropContainer(false)
        synchronized(source.map) { newProps.map.putAll(source.map) }
        return newProps
      }
      if (useScatterMap && sourceScatterMap != null) {
        return synchronized(sourceScatterMap) {
          val scatterMap = MutableScatterMap<TreeProp<*>, Any?>(sourceScatterMap.size)
          scatterMap.putAll(sourceScatterMap)
          TreePropContainer(UNUSED_MAP, scatterMap)
        }
      }
      // The source was created before the flag changed.
      val newProps = TreePropContainer(useScatterMap)
      newProps.putEntries(source.entries())
      return newProps
    }
  }
}
