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

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Pair
import android.util.SparseArray
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import com.facebook.litho.CollectionsUtils.isNotNullOrEmpty
import com.facebook.litho.ComponentUtils.handle
import com.facebook.litho.ComponentUtils.rethrow
import com.facebook.litho.visibility.Visibility

/**
 * Takes care of dynamic Props
 *
 * Usage from [DynamicPropsExtension]
 *
 * Unique per [DynamicPropsExtension] instance, which calls [onBindComponentToContent] and
 * [onUnbindComponent] when it binds and unbinds Components respectively.
 *
 * When Component is being bound, a DynamicPropsManager subscribes to [DynamicValue]s that its
 * represent dynamic props, and keeps the mounted content the Components is rendered into in sync
 * with [DynamicValue.get] until the Component gets unbound, at which point, the DynamicPropsManager
 * unsubscribes from the DynamicValues.
 */
class DynamicPropsManager : DynamicValue.OnValueChangeListener<Any?> {
  private val dependentComponents:
      MutableMap<
          DynamicValue<Any?>,
          MutableSet<Pair<Component, SparseArray<out DynamicValue<Any?>>>>,
      > =
      HashMap()
  private val affectingDynamicValues: MutableMap<Component, Set<DynamicValue<Any?>?>> = HashMap()
  private val contents: MutableMap<Component, Any> = HashMap()
  private val backgroundsBeforeDynamic: MutableMap<View, Drawable?> = HashMap()

  @Suppress("USELESS_CAST")
  fun onBindComponentToContent(
      component: Component,
      scopedContext: ComponentContext?,
      commonDynamicProps: SparseArray<out DynamicValue<Any?>>?,
      content: Any,
  ) {
    val hasCommonDynamicPropsToBind = hasCommonDynamicPropsToBind(commonDynamicProps, content)
    if (!hasCommonDynamicPropsToBind && !component.hasCustomDynamicProps()) {
      return
    }
    val dynamicValues: MutableSet<DynamicValue<Any?>?> = HashSet()
    if (commonDynamicProps != null && hasCommonDynamicPropsToBind) {
      val view = content as View
      if (hasDynamicBackground(commonDynamicProps) && view !in backgroundsBeforeDynamic) {
        backgroundsBeforeDynamic[view] = view.background
      }
      // Go through all common dynamic props
      for (i in 0 until commonDynamicProps.size()) {
        val key = commonDynamicProps.keyAt(i)
        val value = commonDynamicProps.valueAt(i)
        bindCommonDynamicProp(key, value, content as View)
        addDependentComponentAndSubscribeIfNeeded(value, Pair(component, commonDynamicProps))
        dynamicValues.add(value)
      }
    }
    val customDynamicProps = getCustomDynamicProps(component)
    // Go through all the custom dynamic props
    for (i in customDynamicProps.indices) {
      val value = customDynamicProps[i]
      try {
        (component as SpecGeneratedComponent).bindDynamicProp(i, value?.get(), content)
        addDependentComponentAndSubscribeIfNeeded(value, Pair(component, commonDynamicProps))
        dynamicValues.add(value)
      } catch (e: Exception) {
        if (scopedContext != null) {
          handle(scopedContext, e)
        } else {
          rethrow(e)
        }
      }
    }
    affectingDynamicValues[component] = dynamicValues
    contents[component] = content
  }

  fun onUnbindComponent(
      component: Component,
      commonDynamicProps: SparseArray<out DynamicValue<Any?>>?,
      content: Any,
  ) {
    if (
        !hasCommonDynamicPropsToBind(commonDynamicProps, content) &&
            !component.hasCustomDynamicProps()
    ) {
      return
    }
    contents.remove(component)
    restoreBackground(content)
    val dynamicValues = affectingDynamicValues[component] ?: return
    for (value in dynamicValues) {
      removeDependentComponentAndUnsubscribeIfNeeded(value, Pair(component, commonDynamicProps))
    }

    // Go through all common dynamic props to reset them if they were set
    if (commonDynamicProps != null) {
      for (i in 0 until commonDynamicProps.size()) {
        val key = commonDynamicProps.keyAt(i)
        resetDynamicValues(key, content)
      }
    }
    affectingDynamicValues.remove(component)
  }

  private fun restoreBackground(content: Any) {
    if (content !is View || content !in backgroundsBeforeDynamic) {
      return
    }
    val background = backgroundsBeforeDynamic.remove(content)
    if (content.background !== background) {
      content.background = background
    }
  }

  private fun addDependentComponentAndSubscribeIfNeeded(
      value: DynamicValue<Any?>?,
      componentWithProps: Pair<Component, SparseArray<out DynamicValue<Any?>>>,
  ) {
    if (value == null) {
      return
    }
    var dependentComponents = dependentComponents[value]
    if (dependentComponents == null) {
      dependentComponents = HashSet()
      this.dependentComponents[value] = dependentComponents
      value.attachListener(this)
    }
    dependentComponents.add(componentWithProps)
  }

  private fun removeDependentComponentAndUnsubscribeIfNeeded(
      value: DynamicValue<Any?>?,
      componentWithProps: Pair<Component, SparseArray<out DynamicValue<Any?>>>,
  ) {
    if (value == null) {
      return
    }
    val dependentComponents = dependentComponents[value] ?: return
    dependentComponents.remove(componentWithProps)
    if (dependentComponents.isEmpty()) {
      this.dependentComponents.remove(value)
      value.detach(this)
    }
  }

  @Suppress("SENSELESS_COMPARISON")
  override fun onValueChange(value: DynamicValue<Any?>) {
    val dependentComponents = dependentComponents[value] ?: return

    // It's possible that applying a dynamic prop could bind or unbind a component - snapshot the
    // components here to prevent a ConcurrentModificationException during iteration
    val dependentComponentSnapshot: Array<Pair<Component, SparseArray<out DynamicValue<Any?>>>> =
        dependentComponents.toTypedArray()
    for (componentWithProps in dependentComponentSnapshot) {
      if (componentWithProps == null) {
        continue
      }
      val component = componentWithProps.first
      val commonDynamicProps = componentWithProps.second
      val content = contents[component] ?: continue
      if (hasCommonDynamicPropsToBind(commonDynamicProps, content)) {
        for (i in 0 until commonDynamicProps.size()) {
          if (commonDynamicProps.valueAt(i) === value) {
            bindCommonDynamicProp(commonDynamicProps.keyAt(i), value, content as View)
          }
        }
      }
      val dynamicProps = getCustomDynamicProps(component)
      for (i in dynamicProps.indices) {
        if (value === dynamicProps[i]) {
          (component as SpecGeneratedComponent).bindDynamicProp(i, value.get(), content)
        }
      }
    }
  }

  @VisibleForTesting
  fun hasCachedContent(component: Component): Boolean {
    return contents.containsKey(component)
  }

  companion object {
    const val KEY_ALPHA: Int = 1
    const val KEY_TRANSLATION_X: Int = 2
    const val KEY_TRANSLATION_Y: Int = 3
    const val KEY_TRANSLATION_Z: Int = 4
    const val KEY_SCALE_X: Int = 5
    const val KEY_SCALE_Y: Int = 6
    const val KEY_ELEVATION: Int = 7
    const val KEY_BACKGROUND_COLOR: Int = 8
    const val KEY_ROTATION: Int = 9
    const val KEY_ROTATION_X: Int = 10
    const val KEY_ROTATION_Y: Int = 11
    const val KEY_BACKGROUND_DRAWABLE: Int = 12
    const val KEY_FOREGROUND_COLOR: Int = 13
    const val KEY_VISIBILITY: Int = 14

    private fun resetDynamicValues(key: Int, content: Any) {
      if (content !is View) {
        return
      }
      when (key) {
        KEY_ALPHA ->
            if (content.alpha != 1f) {
              content.alpha = 1f
            }
        KEY_TRANSLATION_X ->
            if (content.translationX != 0f) {
              content.translationX = 0f
            }
        KEY_TRANSLATION_Y ->
            if (content.translationY != 0f) {
              content.translationY = 0f
            }
        KEY_TRANSLATION_Z ->
            if (content.translationZ != 0f) {
              content.translationZ = 0f
            }
        KEY_SCALE_X ->
            if (content.scaleX != 1f) {
              content.scaleX = 1f
            }
        KEY_SCALE_Y ->
            if (content.scaleY != 1f) {
              content.scaleY = 1f
            }
        KEY_ELEVATION ->
            if (content.elevation != 0f) {
              content.elevation = 0f
            }
        KEY_ROTATION ->
            if (content.rotation != 0f) {
              content.rotation = 0f
            }
        KEY_ROTATION_X ->
            if (content.rotationX != 0f) {
              content.rotationX = 0f
            }
        KEY_ROTATION_Y ->
            if (content.rotationY != 0f) {
              content.rotationY = 0f
            }
        KEY_FOREGROUND_COLOR ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && content.foreground != null) {
              setViewForeground(content, null)
            }
        KEY_VISIBILITY ->
            if (content.visibility != View.VISIBLE) {
              content.visibility = View.VISIBLE
            }
      }
    }

    private fun bindCommonDynamicProp(key: Int, value: DynamicValue<*>, target: View) {
      when (key) {
        KEY_ALPHA -> target.alpha = resolve(value)
        KEY_TRANSLATION_X -> target.translationX = resolve(value)
        KEY_TRANSLATION_Y -> target.translationY = resolve(value)
        KEY_TRANSLATION_Z -> target.translationZ = resolve(value)
        KEY_SCALE_X -> target.scaleX = resolve(value)
        KEY_SCALE_Y -> target.scaleY = resolve(value)
        KEY_ELEVATION -> target.elevation = resolve(value)
        KEY_BACKGROUND_COLOR -> setDynamicBackgroundColor(target, resolve(value))
        KEY_ROTATION -> target.rotation = resolve(value)
        KEY_ROTATION_X -> target.rotationX = resolve(value)
        KEY_ROTATION_Y -> target.rotationY = resolve(value)
        KEY_BACKGROUND_DRAWABLE -> target.background = resolve(value)
        KEY_FOREGROUND_COLOR -> setViewForeground(target, resolve<Int>(value))
        KEY_VISIBILITY -> target.visibility = resolve<Visibility>(value).toViewVisibility()
      }
    }

    private fun setDynamicBackgroundColor(target: View, @ColorInt color: Int) {
      if (target.background is DynamicBackgroundColorDrawable) {
        target.setBackgroundColor(color)
      } else {
        target.background = DynamicBackgroundColorDrawable(color)
      }
    }

    private fun hasDynamicBackground(
        commonDynamicProps: SparseArray<out DynamicValue<*>>,
    ): Boolean =
        commonDynamicProps.indexOfKey(KEY_BACKGROUND_COLOR) >= 0 ||
            commonDynamicProps.indexOfKey(KEY_BACKGROUND_DRAWABLE) >= 0

    @Suppress("UNCHECKED_CAST")
    private fun <T> resolve(dynamicValue: DynamicValue<*>): T {
      return dynamicValue.get() as T
    }

    /**
     * Common dynamic props could only be bound to Views. To make it work for the LayoutSpec and
     * MountDrawableSpec components we create a wrapping HostComponent and copy the dynamic props
     * there. Thus DynamicPropsManager should ignore non-MountViewSpecs
     *
     * @param commonDynamicProps to consider
     * @return true if Component has common dynamic props, that DynamicPropsManager should take an
     *   action on
     */
    private fun hasCommonDynamicPropsToBind(
        commonDynamicProps: SparseArray<out DynamicValue<*>>?,
        content: Any,
    ): Boolean {
      return isNotNullOrEmpty(commonDynamicProps) && content is View
    }

    fun Component.hasCustomDynamicProps(): Boolean {
      return (this is SpecGeneratedComponent && dynamicProps.isNotEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun getCustomDynamicProps(component: Component): Array<DynamicValue<Any?>?> {
      return if (component is SpecGeneratedComponent)
          component.dynamicProps as Array<DynamicValue<Any?>?>
      else emptyArray()
    }
  }

  /**
   * The background a dynamic background color sets, so it never recolors a static [ColorDrawable]
   * that [View.setBackgroundColor] would update in place.
   */
  private class DynamicBackgroundColorDrawable(@ColorInt color: Int) : ColorDrawable(color)
}
