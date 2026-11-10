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

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.View
import android.widget.TextView
import com.facebook.litho.LithoRenderUnit.Companion.getRenderUnit
import com.facebook.rendercore.ClassBinderKey
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.incrementalmount.ExcludeFromIncrementalMountBinder
import com.facebook.rendercore.visibility.VisibilityMountExtension
import com.facebook.rendercore.visibility.VisibilityOutput
import java.util.IdentityHashMap

/**
 * A DebugComponent represents a node in Litho's component hierarchy. DebugComponent removes the
 * need to worry about implementation details of whether a node is represented by a [ ] or a
 * [ComponentLayout]. The purpose of this class is for tools such as Flipper's UI inspector to be
 * able to easily visualize a component hierarchy without worrying about implementation details of
 * Litho.
 */
class DebugComponent
private constructor(
    val globalKey: String,
    private val result: LithoLayoutResult,
    private val node: LithoNode,
    private val componentIndex: Int,
    private val x: Int,
    private val y: Int,
    private val xOffset: Int,
    private val yOffset: Int,
) {

  interface Overrider {
    fun applyComponentOverrides(key: String, component: Component)

    fun applyStateOverrides(key: String, state: StateContainer)

    fun applyLayoutOverrides(key: String, node: DebugLayoutNodeEditor)
  }

  var isRoot = false
    get() = componentIndex == 0 && field
    private set

  /** @return the [ComponentContext] for this component. */
  val context: ComponentContext
    get() = result.context

  /** @return The litho view hosting this component. */
  val lithoView: BaseMountingView?
    get() = result.context.mountedView as BaseMountingView?

  /** @return If this debug component represents a layout node, return it. */
  val layoutNode: DebugLayoutNode?
    get() = if (isLayoutNode) DebugLayoutNode(result) else null

  /** @return True if this not has layout information attached to it (backed by a Yoga node) */
  val isLayoutNode: Boolean
    get() = componentIndex == 0

  /** @return This component's testKey or null if none is set. */
  val testKey: String?
    get() = if (isLayoutNode) node.testKey else null

  /**
   * Returns this component's testKey or null if none is set.
   *
   * Unlike [testKey], this function can return a test key set on any Component, including container
   * Components which resolve into LayoutNodes.
   *
   * Unlike [testKey], this function can also return test keys set on individual Components even
   * when they are all resolved into a single InternalNode.
   */
  val componentTestKey: String?
    get() = node.getCommonPropsAt(componentIndex)?.testKey

  /**
   * @return This component's componentTag or null if none is set. Unlike [testKey], this will
   *   return tags for any Component, including Components which are not LayoutNodes.
   */
  val componentTag: Any?
    get() = node.getCommonPropsAt(componentIndex)?.componentTag

  /** @return This component's key or null if none is set. */
  val key: String?
    get() = component.run { if (hasManualKey()) key else null }

  /** @return The Component instance this debug component wraps. */
  val component: Component
    get() = node.getComponentAt(componentIndex)

  private val componentGlobalKey: String
    get() = node.getComponentContextAt(componentIndex).globalKey.toString()

  val stateContainer: StateContainer?
    get() = node.getComponentInfoAt(componentIndex).stateContainer

  private val xFromRoot: Int
    get() = x + xOffset

  private val yFromRoot: Int
    get() = y + yOffset

  private val isNotTailComponent: Boolean
    get() = componentIndex != 0

  val widthSpec: String
    get() = SizeSpec.toSimpleString(result.widthSpec)

  val heightSpec: String
    get() = SizeSpec.toSimpleString(result.heightSpec)

  val viewTags: SparseArray<Any>?
    get() = node.nodeInfo?.viewTags

  fun setOverrider(overrider: Overrider) {
    overriders[globalKey] = overrider
  }

  /**
   * Get the list of components composed by this component. This will not include any [View]s that
   * are mounted by this component as those are not components. Use [this.getMountedView] for that.
   *
   * @return A list of child components.
   */
  val childComponents: List<DebugComponent>
    get() {
      if (result is NullLithoLayoutResult) {
        return emptyList()
      } else {
        return when {
          isNotTailComponent -> getImmediateDescendantAsChild()
          result is DeferredLithoLayoutResult -> {
            val nestedResult = result.result ?: return emptyList()
            if (nestedResult.node.componentCount == 1) {
              return when (nestedResult.childCount) {
                0 -> emptyList()
                else -> getChildren(nestedResult, xFromRoot, yFromRoot)
              }
            }
            val index = (nestedResult.node.componentCount - 2).coerceAtLeast(0)
            val component = getInstance(
                nestedResult,
                index,
                result.getXForChildAtIndex(0),
                result.getYForChildAtIndex(0),
                xFromRoot,
                yFromRoot,
            )
            listOfNotNull(component)
          }
          else -> getChildren(result, xFromRoot, yFromRoot)
        }
      }
    }

  private fun getImmediateDescendantAsChild(): List<DebugComponent> {
    val index = componentIndex - 1
    if (index < 0) {
      return emptyList()
    }
    val component = getInstance(result, index, x, y, xOffset, yOffset)
    return listOfNotNull(component)
  }

  /** @return A mounted view or null if this component does not mount a view. */
  val mountedView: View?
    get() = mountedContent as? View

  /** @return A mounted drawable or null if this component does not mount a drawable. */
  val mountedDrawable: Drawable?
    get() = mountedContent as? Drawable

  /** @return The bounds of this component relative to its hosting [LithoView]. */
  val boundsInLithoView: Rect
    get() {
      if (this.isRoot) {
        return Rect(0, 0, result.width, result.height)
      }
      val x = xFromRoot
      val y = yFromRoot
      return Rect(x, y, x + result.width, y + result.height)
    }

  /** @return The bounds of this component relative to its parent. */
  val bounds: Rect
    get() {
      return Rect(x, y, x + result.width, y + result.height)
    }

  /**
   * Many components can result in a single Lithonode/layout resut (from delegatation / custom
   * components) We want only the first component to 'take' the offset of the underlying layout node
   * other wise each delegating custom component will appear to multiply the offset.
   *
   * DeferredNode nodes have to be handled differently. The head component on the DeferredNodeResult
   * will create a separate DebugComponent node but any margin that needs to be added to its bounds
   * will be applied on the nested result node, which is hosted on another DebugComponent instance.
   */
  /**
   * The bounds of this component relative to its parent componen
   *
   * @return
   */
  val boundsInParentDebugComponent: Rect
    get() {
      /**
       * Many components can result in a single Lithonode/layout resut (from delegatation / custom
       * components) We want only the first component to 'take' the offset of the underlying layout
       * node other wise each delegating custom component will appear to multiply the offset.
       *
       * DeferredNode nodes have to be handled differently. The head component on the
       * DeferredNodeResult will create a separate DebugComponent node but any margin that needs to
       * be added to its bounds will be applied on the nested result node, which is hosted on
       * another DebugComponent instance.
       */
      val isHeadComponent = componentIndex == node.componentCount - 1
      val xFromNestedResult: Int
      val yFromNestedResult: Int
      if (result is DeferredLithoLayoutResult) {
        xFromNestedResult = result.getXForChildAtIndex(0)
        yFromNestedResult = result.getYForChildAtIndex(0)
      } else {
        xFromNestedResult = 0
        yFromNestedResult = 0
      }
      val x = if (isHeadComponent) x + xFromNestedResult else 0
      val y = if (isHeadComponent) y + yFromNestedResult else 0
      return Rect(x, y, x + result.width, y + result.height)
    }

  /** @return The alpha value of this component */
  val alpha: Float
    get() = result.node.nodeInfo?.alpha ?: 1.0f

  /** @return True if this and given debug components share the same internal node */
  fun isSameNode(other: DebugComponent): Boolean = node === other.node

  /**
   * @return A concatenated string of all text content within the underlying LithoView. Null if the
   *   node doesn't have an associated LithoView.
   */
  val allTextContent: String?
    get() = buildString {
      val mountDelegateTarget = lithoView?.mountDelegateTarget ?: return null
      for (i in 0 until mountDelegateTarget.getRenderUnitCount()) {
        val mountItem = mountDelegateTarget.getMountItemAt(i) ?: continue
        val content = mountItem.content
        val textContent = ComponentHostUtils.asTextContent(content)
        if (textContent != null) {
          for (charSequence in textContent.textList) append(charSequence)
        } else if (content is TextView) {
          append(content.text)
        }
      }
    }

  /**
   * @return The text content of the component wrapped by the debug component, or null if no
   *   TextContent/TextView are found.
   */
  val textContent: String?
    get() = rawTextContent?.toString()

  /**
   * @return The raw text content as a [CharSequence], preserving any [android.text.Spanned] span
   *   information (bold, color, clickable spans, mentions, etc.). Returns null if no
   *   TextContent/TextView are found. Use this instead of [textContent] when span information
   *   matters (e.g. rich text dumps).
   */
  val rawTextContent: CharSequence?
    get() {
      val mountDelegateTarget = lithoView?.mountDelegateTarget ?: return null
      for (i in 0 until mountDelegateTarget.getRenderUnitCount()) {
        val mountItem = mountDelegateTarget.getMountItemAt(i) ?: continue
        val mountItemComponent = getRenderUnit(mountItem).component
        if (mountItemComponent.instanceId == component.instanceId) {
          val content = mountItem.content
          val textContent = ComponentHostUtils.asTextContent(content)
          if (textContent != null) {
            val texts = textContent.textList
            if (texts.isEmpty()) continue
            if (texts.size == 1) {
              val single = texts[0]
              if (single.isNotEmpty()) return single
            } else {
              val sb = android.text.SpannableStringBuilder()
              for (cs in texts) sb.append(cs)
              if (sb.isNotEmpty()) return sb
            }
          } else if (content is TextView) {
            val text = content.text
            if (!text.isNullOrEmpty()) return text
          }
        }
      }
      return null
    }

  /** @return The [ComponentHost] that wraps this component or null if one cannot be found. */
  val componentHost: ComponentHost?
    get() {
      val mountDelegateTarget = lithoView?.mountDelegateTarget ?: return null
      for (i in 0 until mountDelegateTarget.getRenderUnitCount()) {
        val mountItem = mountDelegateTarget.getMountItemAt(i) ?: continue
        val mountItemComponent = getRenderUnit(mountItem).component
        if (mountItemComponent.isEquivalentTo(component)) {
          return mountItem.host as ComponentHost?
        }
      }
      return null
    }

  fun rerender() {
    (lithoView as? LithoView)?.forceRelayout()
  }

  fun canResolve(): Boolean {
    return component is SpecGeneratedComponent &&
        TestLayoutState.canResolve(component as SpecGeneratedComponent)
  }

  val mountedContent: Any?
    get() {
      if (!isLayoutNode) {
        return null
      }
      val mountDelegateTarget = lithoView?.mountDelegateTarget
      if (mountDelegateTarget != null) {
        for (i in 0 until mountDelegateTarget.getRenderUnitCount()) {
          val mountItem = mountDelegateTarget.getMountItemAt(i) ?: continue
          val component = getRenderUnit(mountItem).component
          if (component === node.tailComponent) {
            return mountItem.content
          }
        }
      }
      return null
    }

  /** Read-only lookup caches for one uninterrupted inspection of the mounted hierarchy. */
  class Inspection(private val checkActive: () -> Unit = {}) {
    private val mounts = IdentityHashMap<BaseMountingView, MountIndex>()
    private val visibleKeys = IdentityHashMap<BaseMountingView, Set<String>>()
    private val children = IdentityHashMap<DebugComponent, List<DebugComponent>>()
    private val keys = IdentityHashMap<DebugComponent, String>()

    fun childComponents(component: DebugComponent): List<DebugComponent> {
      checkActive()
      return children.getOrPut(component) { component.childComponents }
    }

    fun isVisible(component: DebugComponent, host: BaseMountingView): Boolean {
      checkActive()
      val visible =
          visibleKeys.getOrPut(host) {
            val state = host.visibilityExtensionState ?: return@getOrPut emptySet()
            val result = mutableSetOf<String>()
            for (key in VisibilityMountExtension.getVisibilityIdToItemMap(state).keys) {
              checkActive()
              result.add(key.toString())
            }
            result
          }
      return keys.getOrPut(component) { component.componentGlobalKey } in visible
    }

    fun mountedView(component: DebugComponent): View? {
      checkActive()
      if (!component.isLayoutNode) return null
      val index = mountIndex(component) ?: return null
      val tail = component.node.tailComponent
      if (index.firstContent.containsKey(tail)) return index.firstContent[tail] as? View
      index.failure?.let { throw it }
      return null
    }

    fun textContent(component: DebugComponent): String? {
      checkActive()
      val index = mountIndex(component) ?: return null
      val id = component.component.instanceId
      if (index.text.containsKey(id)) return index.text[id]
      for (content in index.contentsById[id].orEmpty()) {
        checkActive()
        val textContent = ComponentHostUtils.asTextContent(content)
        val text =
            if (textContent != null) {
              textContent.textList.joinToString("") { text ->
                checkActive()
                text
              }
            } else {
              (content as? TextView)?.text?.toString()
            }
        if (!text.isNullOrEmpty()) {
          index.text[id] = text
          return text
        }
      }
      index.failure?.let { throw it }
      index.text[id] = null
      return null
    }

    private fun mountIndex(component: DebugComponent): MountIndex? {
      val host = component.lithoView ?: return null
      return mounts.getOrPut(host) {
        val index = MountIndex()
        @Suppress("USELESS_ELVIS") val target = host.mountDelegateTarget ?: return@getOrPut index
        val count = target.getRenderUnitCount()
        for (i in 0 until count) {
          checkActive()
          try {
            val item = target.getMountItemAt(i) ?: continue
            val mountedComponent = getRenderUnit(item).component
            if (!index.firstContent.containsKey(mountedComponent)) {
              index.firstContent[mountedComponent] = item.content
            }
            index.contentsById
                .getOrPut(mountedComponent.instanceId) { mutableListOf() }
                .add(item.content)
          } catch (failure: Exception) {
            index.failure = failure
            break
          }
        }
        checkActive()
        index
      }
    }

    private class MountIndex {
      val firstContent = IdentityHashMap<Component, Any?>()
      val contentsById = mutableMapOf<Int, MutableList<Any?>>()
      val text = mutableMapOf<Int, String?>()
      var failure: Exception? = null
    }
  }

  companion object {
    private val overriders: MutableMap<String, Overrider> = HashMap()

    @JvmStatic
    @Synchronized
    fun getInstance(
        result: LithoLayoutResult,
        componentIndex: Int,
        x: Int,
        y: Int,
        xOffset: Int,
        yOffset: Int,
    ): DebugComponent? {
      val node = result.node
      val context = result.context
      if (result is NullLithoLayoutResult || componentIndex >= node.componentCount) {
        return null
      }
      val componentKey = node.getGlobalKeyAt(componentIndex).toString()
      return DebugComponent(
          globalKey = generateGlobalKey(context, componentKey),
          result = result,
          node = result.node,
          componentIndex = componentIndex,
          x = x,
          y = y,
          xOffset = xOffset,
          yOffset = yOffset,
      )
    }

    @JvmStatic
    fun getRootInstance(view: BaseMountingView): DebugComponent? =
        getRootInstance(view.currentLayoutState)

    fun getRootInstance(componentTree: ComponentTree?): DebugComponent? =
        if (componentTree == null) null else getRootInstance(componentTree.mainThreadLayoutState)

    @JvmStatic
    fun getRootInstance(
        layoutState: LayoutState?,
    ): DebugComponent? {
      val root = layoutState?.rootLayoutResult
      if (root == null || root is NullLithoLayoutResult) {
        return null
      }
      check(root is LithoLayoutResult) { "Expected root to be a LithoLayoutResult" }
      val node = root.node
      val outerWrapperComponentIndex = (node.componentCount - 1).coerceAtLeast(0)
      return getInstance(root, outerWrapperComponentIndex, 0, 0, 0, 0)?.apply { isRoot = true }
    }

    @JvmStatic
    fun getInstance(result: LithoLayoutResult, x: Int, y: Int): DebugComponent? {
      val rootNode = result.node
      val outerWrapperComponentIndex = (rootNode.componentCount - 1).coerceAtLeast(0)
      return getInstance(result, outerWrapperComponentIndex, x, y, 0, 0)
    }

    /**
     * Returns whether this Component should be considered to be mounted, even outside of
     * incremental mount.
     *
     * @see SpecGeneratedComponent.excludeFromIncrementalMount
     */
    @JvmStatic
    fun isExcludedFromIncrementalMount(
        debugComponent: DebugComponent,
        layoutState: LayoutState,
    ): Boolean {
      val renderUnit = getRenderUnit(debugComponent, layoutState)
      val component = debugComponent.component

      val shouldExcludePrimitiveFromIncrementalMount: Boolean =
          renderUnit?.findAttachBinderByKey<ExcludeFromIncrementalMountBinder>(
              ClassBinderKey.of(ExcludeFromIncrementalMountBinder::class.java),
          ) != null
      val shouldExcludeSpecGeneratedComponentFromIncrementalMount: Boolean =
          component is SpecGeneratedComponent && component.excludeFromIncrementalMount()

      return shouldExcludePrimitiveFromIncrementalMount ||
          shouldExcludeSpecGeneratedComponentFromIncrementalMount
    }

    @JvmStatic
    fun getRenderUnit(debugComponent: DebugComponent, layoutState: LayoutState): RenderUnit<*>? {
      val component = debugComponent.component
      for (i in 0 until layoutState.getMountableOutputCount()) {
        val renderTreeNode = layoutState.getMountableOutputAt(i)
        val lithoRenderUnit = renderTreeNode.renderUnit as LithoRenderUnit
        if (lithoRenderUnit.componentContext.componentScope === component) {
          return lithoRenderUnit
        }
      }
      return null
    }

    @JvmStatic
    fun getVisibilityOutput(
        debugComponent: DebugComponent,
        layoutState: LayoutState,
    ): VisibilityOutput? {
      val componentGlobalKey = debugComponent.componentGlobalKey
      for (i in 0 until layoutState.visibilityOutputCount) {
        val visibilityOutput = layoutState.getVisibilityOutputAt(i)
        if (visibilityOutput.id.toString() == componentGlobalKey) {
          return visibilityOutput
        }
      }
      return null
    }

    @JvmStatic
    fun isVisible(debugComponent: DebugComponent, lithoView: BaseMountingView): Boolean {
      val componentGlobalKey = debugComponent.componentGlobalKey
      return lithoView.visibilityExtensionState?.let { state ->
        val map = VisibilityMountExtension.getVisibilityIdToItemMap(state)
        map.keys.any { it.toString() == componentGlobalKey }
      } ?: false
    }

    private fun generateGlobalKey(context: ComponentContext, componentKey: String?): String =
        generateGlobalKey(context.lithoTree?.id, componentKey)

    @JvmStatic
    fun generateGlobalKey(treeId: Int?, globalKey: String?): String =
        "${treeId?: "notree"}:${globalKey}"

    @JvmStatic
    fun applyOverrides(context: ComponentContext, component: Component, componentKey: GlobalKey) {
      if (overriders.isEmpty()) {
        return
      }
      val key = generateGlobalKey(context, componentKey.toString())
      val overrider = overriders[key]
      if (overrider != null) {
        overrider.applyComponentOverrides(key, component)
        val stateContainer = context.scopedComponentInfo.stateContainer
        if (stateContainer != null) {
          overrider.applyStateOverrides(key, stateContainer)
        }
      }
    }

    @JvmStatic
    fun applyOverrides(context: ComponentContext, node: LithoNode) {
      if (overriders.isEmpty() || node.componentCount == 0) {
        return
      }
      val componentKey = node.getGlobalKeyAt(0).toString()
      val key = generateGlobalKey(context, componentKey)
      val overrider = overriders[key]
      overrider?.applyLayoutOverrides(key, DebugLayoutNodeEditor(node))
    }

    private fun getChildren(result: LithoLayoutResult, xOffset: Int, yOffset: Int) = buildList {
      for (i in 0 until result.childCount) {
        val childNode = result.getChildAt(i)
        val index = (childNode.node.componentCount - 1).coerceAtLeast(0)
        getInstance(
            childNode,
            index,
            result.getXForChildAtIndex(i),
            result.getYForChildAtIndex(i),
            xOffset,
            yOffset,
        )
            ?.let { add(it) }
      }
    }
  }
}
