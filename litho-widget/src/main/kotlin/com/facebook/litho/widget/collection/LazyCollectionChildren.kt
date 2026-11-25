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

package com.facebook.litho.widget.collection

import com.facebook.litho.Component

/**
 * A container for the children of a Lazy Collection. Manages the creation of ids, and callback
 * registration.
 */
class LazyCollectionChildren {

  val collectionChildren
    get() = collectionChildrenBuilder.toList()

  internal val effectiveIndexToId: MutableMap<Int, MutableSet<Any>> by lazy { mutableMapOf() }
  internal val idToChild: MutableMap<Any, CollectionChild> by lazy { mutableMapOf() }

  /** The child groups declared here, in order. Null until the first one. */
  internal var childGroups: MutableList<ChildGroup>? = null
    private set

  private val collectionChildrenBuilder = mutableListOf<CollectionChild>()
  /**
   * Whether any added child relies on a generated id. Generated ids are positional, so item
   * animations would play on the wrong rows when such children are inserted, removed or moved.
   */
  internal var hasGeneratedIds: Boolean = false
    private set

  private var nextStaticId = 0
  private val typeToFreq: MutableMap<Int, Int> by lazy { mutableMapOf() }

  /** Prepare the final id that will be assigned to the child. */
  private fun getResolvedId(id: Any?, component: Component? = null): Any {
    // Generate an id that is unique to the [CollectionContainerScope] in which it was defined
    // If an id has been explicitly defined on the child, use that
    // If the child has a component generate an id including the type and frequency
    // Otherwise the child has a null component or a lambda generator, so generate an id.
    return id ?: generateIdForComponent(component) ?: generateStaticId()
  }

  private fun generateStaticId(): Any {
    return "staticId:${nextStaticId++}"
  }

  /** Generate an id for a non-null Component. */
  private fun generateIdForComponent(component: Component?): Any? {
    val typeId = component?.typeId ?: return null
    typeToFreq[typeId] = (typeToFreq[typeId] ?: 0) + 1
    return "${typeId}:${typeToFreq[typeId]}"
  }

  private fun maybeRegisterForOnEnterOrNearCallbacks(child: CollectionChild) {
    child.onNearViewport ?: return

    idToChild[child.id] = child
    val index = collectionChildrenBuilder.lastIndex
    val effectiveIndex = index - child.onNearViewport.offset
    effectiveIndexToId.getOrPut(effectiveIndex) { mutableSetOf() }.add(child.id)
  }

  fun add(
      component: Component?,
      id: Any? = null,
      isSticky: Boolean = false,
      isFullSpan: Boolean = false,
      spanSize: Int? = null,
      onNearViewport: OnNearCallback? = null,
      customAttributes: Map<String, Any?>? = null,
      parentWidthPercent: Float = -1f,
      parentHeightPercent: Float = -1f,
  ) {
    val resolvedId = getResolvedId(id, component)
    component ?: return
    if (id == null) {
      hasGeneratedIds = true
    }
    val child = CollectionChild(
        resolvedId,
        component,
        null,
        isSticky,
        isFullSpan,
        spanSize,
        parentWidthPercent,
        parentHeightPercent,
        null,
        customAttributes,
        onNearViewport,
    )
    collectionChildrenBuilder.add(child)
    maybeRegisterForOnEnterOrNearCallbacks(child)
  }

  fun add(
      id: Any? = null,
      isSticky: Boolean = false,
      isFullSpan: Boolean = false,
      spanSize: Int? = null,
      onNearViewport: OnNearCallback? = null,
      deps: Array<Any?>,
      parentWidthPercent: Float = -1f,
      parentHeightPercent: Float = -1f,
      componentFunction: () -> Component?,
  ) {
    if (id == null) {
      hasGeneratedIds = true
    }
    val child = CollectionChild(
        getResolvedId(id),
        null,
        { componentFunction() },
        isSticky,
        isFullSpan,
        spanSize,
        parentWidthPercent,
        parentHeightPercent,
        deps,
        null,
        onNearViewport,
    )
    collectionChildrenBuilder.add(child)
    maybeRegisterForOnEnterOrNearCallbacks(child)
  }

  internal fun addChildGroup(id: Any, content: LazyListChildGroupScope.() -> Unit) {
    val groups = childGroups ?: ArrayList<ChildGroup>().also { childGroups = it }
    groups.add(ChildGroup(id, content, position = collectionChildrenBuilder.size))
  }

  internal fun inheritGeneratedIds(children: LazyCollectionChildren) {
    hasGeneratedIds = hasGeneratedIds || children.hasGeneratedIds
  }

  /** Adds [child], whose id is already final. */
  internal fun addResolved(child: CollectionChild) {
    collectionChildrenBuilder.add(child)
    maybeRegisterForOnEnterOrNearCallbacks(child)
  }
}
