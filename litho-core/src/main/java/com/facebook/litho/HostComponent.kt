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
import android.util.SparseArray
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.PoolingPolicy

internal class HostComponent
protected constructor(
    override val poolingPolicy: PoolingPolicy,
    private val unsafeModificationPolicy: ComponentHost.UnsafeModificationPolicy?,
) : SpecGeneratedComponent("HostComponent") {

  /**
   * We duplicate mComponentDynamicProps here, in order to provide [setCommonDynamicProps] to
   * HostComponent only, which is used in LayoutState to pass Common Dynamic Props from other
   * Components that do not mount a view
   */
  internal var commonDynamicProps: SparseArray<DynamicValue<*>>? = null

  private var implementsVirtualViews = false

  override fun canPreallocate(): Boolean = poolingPolicy.canAcquireContent

  override fun onCreateMountContent(c: Context): Any = ComponentHost(c, unsafeModificationPolicy)

  override fun onCreateMountContentPool(
      poolSizeOverride: Int,
  ): MountContentPools.ContentPool = HostMountContentPool(
      poolSizeOverride,
      poolingPolicy.canAcquireContent || poolingPolicy.canReleaseContent,
  )

  override fun onMount(
      c: ComponentContext?,
      convertContent: Any,
      interStagePropsContainer: InterStagePropsContainer?,
  ) {
    val host = convertContent as ComponentHost

    host.setImplementsVirtualViews(implementsVirtualViews)
  }

  override fun onUnmount(
      c: ComponentContext?,
      mountedContent: Any,
      interStagePropsContainer: InterStagePropsContainer?,
  ) {
    val host = mountedContent as ComponentHost

    // Some hosts might be duplicating parent state which could be 'pressed' and under certain
    // conditions that state might not be cleared from this host and carried to next reuse,
    // therefore applying wrong drawable state. Particular case where this might happen is when
    // host is unmounted as soon as click event is triggered, and host is unmounted before it has
    // chance to reset its internal pressed state.
    if (host.isPressed) {
      host.isPressed = false
    }

    host.clipChildren = false

    host.setImplementsVirtualViews(false)
  }

  override fun onBind(
      c: ComponentContext?,
      mountedContent: Any,
      interStagePropsContainer: InterStagePropsContainer?,
  ) {
    val host = mountedContent as ComponentHost
    host.maybeInvalidateAccessibilityState()
  }

  override fun getMountType(): MountType = MountType.VIEW

  override fun isEquivalentProps(other: Component?): Boolean = this === other

  override fun poolSize(): Int = ComponentsConfiguration.hostComponentPoolSize

  override fun shouldUpdate(
      previous: Component,
      previousStateContainer: StateContainer?,
      next: Component,
      nextStateContainer: StateContainer?,
  ): Boolean = true

  @Suppress("REDUNDANT_VISIBILITY_MODIFIER")
  public override fun getCommonDynamicProps(): SparseArray<DynamicValue<*>>? = commonDynamicProps

  @Suppress("REDUNDANT_VISIBILITY_MODIFIER")
  public override fun hasCommonDynamicProps(): Boolean =
      CollectionsUtils.isNotNullOrEmpty(commonDynamicProps)

  internal fun setImplementsVirtualViews() {
    implementsVirtualViews = true
  }

  companion object {
    @JvmStatic
    fun create(context: ComponentContext): HostComponent {
      val componentsConfiguration: ComponentsConfiguration =
          context.lithoConfiguration.componentsConfig

      var policy: ComponentHost.UnsafeModificationPolicy? =
          componentsConfiguration.componentHostInvalidModificationPolicy

      /* If we are testing host recycling and no policy was set, then we override to be LOG */
      if (
          policy != ComponentHost.UnsafeModificationPolicy.CRASH &&
              componentsConfiguration.componentHostPoolingPolicy.canReleaseContent
      ) {
        policy = ComponentHost.UnsafeModificationPolicy.LOG
      }

      return HostComponent(componentsConfiguration.componentHostPoolingPolicy, policy)
    }
  }
}
