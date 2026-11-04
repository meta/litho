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

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.View
import androidx.annotation.IntDef
import com.facebook.litho.ComponentHostUtils.maybeSetDrawableState
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.LithoLayoutData.Companion.getInterStageProps
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.ContentAllocator
import com.facebook.rendercore.MountContentPools.ContentPool
import com.facebook.rendercore.PoolingPolicy
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RenderType
import com.facebook.rendercore.RenderUnit

/** This [RenderUnit] encapsulates a Litho output to be mounted using Render Core. */
class MountSpecLithoRenderUnit
private constructor(
    id: Long,
    component: Component,
    commonDynamicProps: SparseArray<DynamicValue<Any?>>?,
    nodeInfo: NodeInfo?,
    flags: Int,
    importantForAccessibility: Int,
    @UpdateState val updateState: Int,
    /**
     * The id of the [LayoutState] in which this unit's props, state or layout direction last
     * changed: its diff base's when layout found the two equal, else that of the [LayoutState] that
     * created it. These ids are unique across trees. Mount compares it with the mounted unit's.
     */
    internal val contentVersion: Int,
    context: ComponentContext,
    debugKey: String?,
) :
    LithoRenderUnit(
        id,
        component,
        commonDynamicProps,
        nodeInfo,
        flags,
        importantForAccessibility,
        context,
        debugKey,
        excludesFromIncrementalMount =
            component is SpecGeneratedComponent && component.excludeFromIncrementalMount(),
    ),
    ContentAllocator<Any> {

  override val renderType: RenderType = getRenderType(component)

  private var isShouldUpdateCachingEnabled = false
  private var isShouldUpdateResultCached = false
  private var isCachedShouldUpdateResult = false

  init {
    addOptionalMountBinders(DelegateBinder.createDelegateBinder(this, mountBinder))
    addAttachBinder(DelegateBinder.createDelegateBinder(this, binderBinder))
  }

  override fun onStartUpdateRenderUnit() {
    isShouldUpdateCachingEnabled = true
  }

  override fun onEndUpdateRenderUnit() {
    isShouldUpdateCachingEnabled = false
    isShouldUpdateResultCached = false
  }

  override fun onCreateMountContentPool(poolSizeOverride: Int): ContentPool? {
    return try {
      if (component is SpecGeneratedComponent) {
        component.onCreateMountContentPool(poolSizeOverride)
      } else {
        null
      }
    } catch (e: Exception) {
      if (componentContext != null) {
        ComponentUtils.handle(componentContext, e)
      }
      null
    }
  }

  override fun createContent(c: Context): Any {
    return component.createMountContent(c)
  }

  override fun getPoolKey(): Any {
    return component.javaClass
  }

  override val poolingPolicy: PoolingPolicy =
      if (component is SpecGeneratedComponent) {
        component.poolingPolicy
      } else {
        PoolingPolicy.Disabled
      }

  override fun poolSize(): Int {
    return if (component is SpecGeneratedComponent) {
      component.poolSize()
    } else {
      super.poolSize()
    }
  }

  override val description: String = component.simpleName

  override val contentAllocator: ContentAllocator<Any> = this

  companion object {

    const val STATE_UNKNOWN = 0
    const val STATE_UPDATED = 1
    const val STATE_DIRTY = 2

    val mountBinder: Binder<MountSpecLithoRenderUnit, Any, Any> =
        object : Binder<MountSpecLithoRenderUnit, Any, Any> {
          override fun shouldUpdate(
              current: MountSpecLithoRenderUnit,
              next: MountSpecLithoRenderUnit,
              currentData: Any?,
              nextData: Any?,
          ): Boolean {
            return if (next.component is HostComponent) {
              false
            } else {
              shouldUpdateMountItem(current, next, currentData, nextData)
            }
          }

          override fun bind(
              context: Context,
              content: Any,
              unit: MountSpecLithoRenderUnit,
              layoutData: Any?,
          ): Any? {
            val component = unit.component
            (component as SpecGeneratedComponent).mount(
                getComponentContext(unit),
                content,
                getInterStageProps(layoutData),
            )
            return null
          }

          override fun unbind(
              context: Context,
              content: Any,
              unit: MountSpecLithoRenderUnit,
              layoutData: Any?,
              bindData: Any?,
          ) {
            (unit.component as SpecGeneratedComponent).unmount(
                getComponentContext(unit),
                content,
                getInterStageProps(layoutData),
            )
          }
        }

    val binderBinder: Binder<MountSpecLithoRenderUnit, Any, Any> =
        object : Binder<MountSpecLithoRenderUnit, Any, Any> {
          override fun shouldUpdate(
              current: MountSpecLithoRenderUnit,
              next: MountSpecLithoRenderUnit,
              c: Any?,
              n: Any?,
          ): Boolean {
            return true
          }

          override fun bind(
              context: Context,
              content: Any,
              unit: MountSpecLithoRenderUnit,
              layoutData: Any?,
          ): Any? {
            if (content is Drawable) {
              if (content.callback is View) {
                val view = content.callback as View?
                maybeSetDrawableState(view!!, content, unit.flags)
              }
            }
            (unit.component as SpecGeneratedComponent).bind(
                getComponentContext(unit),
                content,
                getInterStageProps(layoutData),
            )
            return null
          }

          override fun unbind(
              context: Context,
              content: Any,
              unit: MountSpecLithoRenderUnit,
              layoutData: Any?,
              bindData: Any?,
          ) {
            (unit.component as SpecGeneratedComponent).unbind(
                getComponentContext(unit),
                content,
                getInterStageProps(layoutData),
            )
          }
        }

    @JvmStatic
    fun create(
        id: Long,
        component: Component,
        commonDynamicProps: SparseArray<DynamicValue<Any?>>?,
        context: ComponentContext,
        nodeInfo: NodeInfo?,
        flags: Int,
        importantForAccessibility: Int,
        @UpdateState updateState: Int,
        contentVersion: Int,
        debugKey: String?,
    ): MountSpecLithoRenderUnit {
      return MountSpecLithoRenderUnit(
          id,
          component,
          commonDynamicProps,
          nodeInfo,
          flags,
          importantForAccessibility,
          updateState,
          contentVersion,
          context,
          debugKey,
      )
    }

    @JvmStatic
    fun shouldUpdateMountItem(
        current: MountSpecLithoRenderUnit,
        next: MountSpecLithoRenderUnit,
        currentData: Any?,
        nextData: Any?,
    ): Boolean {
      if (current.isShouldUpdateCachingEnabled && current.isShouldUpdateResultCached) {
        return current.isCachedShouldUpdateResult
      }
      val nextContext = getComponentContext(next)
      val currentContext = getComponentContext(current)
      val result = shouldUpdateMountItem(
          next,
          nextData as LithoLayoutData?,
          nextContext,
          current,
          currentData as LithoLayoutData?,
          currentContext,
      )
      if (current.isShouldUpdateCachingEnabled && !current.isShouldUpdateResultCached) {
        current.isCachedShouldUpdateResult = result
        current.isShouldUpdateResultCached = true
      }
      return result
    }

    private fun getRenderType(component: Component?): RenderType {
      requireNotNull(component) { "Null output used for LithoRenderUnit." }
      return if (component.mountType == Component.MountType.DRAWABLE) {
        RenderType.DRAWABLE
      } else {
        RenderType.VIEW
      }
    }

    private fun shouldUpdateMountItem(
        nextRenderUnit: MountSpecLithoRenderUnit,
        nextLayoutData: LithoLayoutData?,
        nextContext: ComponentContext?,
        currentRenderUnit: MountSpecLithoRenderUnit,
        currentLayoutData: LithoLayoutData?,
        currentContext: ComponentContext?,
    ): Boolean {
      @UpdateState val updateState = nextRenderUnit.updateState
      val currentComponent = currentRenderUnit.component
      val nextComponent = nextRenderUnit.component
      val isMountSizeDependent =
          nextComponent is SpecGeneratedComponent && nextComponent.isMountSizeDependent

      // If the two components have different sizes and the mounted content depends on the size we
      // just return true immediately.
      if (
          isMountSizeDependent &&
              !sameSize(
                  checkNotNull(nextLayoutData),
                  checkNotNull(currentLayoutData),
              )
      ) {
        return true
      }
      if (ComponentsConfiguration.useMountSpecContentVersion) {
        // Equal versions mean equal props, state and direction, however many layouts were
        // committed without mounting in between. updateState only compares with the layout this
        // one was diffed against, which may never have mounted.
        if (nextRenderUnit.contentVersion == currentRenderUnit.contentVersion) {
          return false
        }
        // A size-dependent spec mounts from inter-stage props, which can't be proven equal: layout
        // rebuilds them, and they hold objects, such as a text Layout, that compare by identity.
        if (updateState == STATE_DIRTY || isMountSizeDependent) {
          return true
        }
      } else {
        if (updateState == STATE_DIRTY) {
          return true
        }
        if (updateState == STATE_UPDATED) {
          return false
        }
      }
      return shouldUpdate(currentComponent, currentContext, nextComponent, nextContext)
    }

    private fun shouldUpdate(
        currentComponent: Component,
        currentScopedContext: ComponentContext?,
        nextComponent: Component,
        nextScopedContext: ComponentContext?,
    ): Boolean {
      return trace({ "MountState.shouldUpdate" }) {
        try {
          currentComponent.shouldComponentUpdate(
              currentScopedContext,
              currentComponent,
              nextScopedContext,
              nextComponent,
          )
        } catch (e: Exception) {
          if (nextScopedContext != null) {
            ComponentUtils.handle(nextScopedContext, e)
          }
          true
        }
      }
    }

    private fun sameSize(next: LithoLayoutData, current: LithoLayoutData): Boolean {
      return next.width == current.width && next.height == current.height
    }

    @JvmStatic
    @UpdateState
    fun getUpdateState(node: RenderTreeNode): Int {
      return (node.renderUnit as MountSpecLithoRenderUnit).updateState
    }
  }

  @IntDef(STATE_UPDATED, STATE_UNKNOWN, STATE_DIRTY)
  @Retention(AnnotationRetention.SOURCE)
  annotation class UpdateState
}
