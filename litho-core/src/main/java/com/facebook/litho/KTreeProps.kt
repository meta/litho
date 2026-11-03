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

/**
 * Defines a single tree prop override. Unlike `Pair<Class<T>, T>` there can be multiple
 * [TreePropValuePair] with the same corresponding class.
 *
 * [TreeProp]'s should be declared separately and then overridden in [TreePropProvider]. If there
 * are no overrides available, then the default value will be used (if any).
 *
 * You can create a Pair with the `to` infix function, e.g. `StringTreeProp to "Hello World!"`.
 */
typealias TreePropValuePair<T> = Pair<TreeProp<T>, T>

/**
 * A component which provides one or more tree props to the given child hierarchy. A tree prop is a
 * mapping from a type to an instance of that type which is accessible throughout the entire subtree
 * where it's defined.
 *
 * Tree props are useful for providing theme info, logging tags, or other things that generally need
 * to be available throughout a hierarchy, without having to manual thread them through as
 * individual props. Tree props can be accessed in children via [getTreeProp].
 */
@Suppress("FunctionName")
@Deprecated(
    "Please, use the new [TreePropProvider] with [TreePropValuePair] args. You can use " +
        "[legacyTreePropOf] for interoperability with old API",
)
@JvmName("LegacyTreePropProvider") // avoid JVM declaration clash with the other TreePropProvider
inline fun TreePropProvider(
    vararg props: Pair<Class<*>, *>,
    crossinline component: () -> Component,
): KComponent {
  val resolvedComponent = component()
  return TreePropProviderImpl(classProps = props, child = resolvedComponent)
}

/**
 * A component which provides one or more TreeProp values to the given child hierarchy. A tree prop
 * is a mapping from a [TreeProp] key object to an value of a corresponding type which is accessible
 * throughout the entire subtree where it's defined.
 *
 * Tree props are useful for providing theme info, logging tags, or other things that generally need
 * to be available throughout a hierarchy, without having to manual thread them through as
 * individual props. Tree prop value of a specific [TreeProp] can be accessed in children via
 * [TreeProp.value] property in [ComponentScope].
 */
@Suppress("FunctionName")
inline fun TreePropProvider(
    vararg props: TreePropValuePair<*>,
    crossinline component: () -> Component,
): KComponent {
  val resolvedComponent = component()
  return TreePropProviderImpl(treeProps = props, child = resolvedComponent)
}

/**
 * Same as [TreePropProvider], but accepts a lambda that may return a nullable component, in which
 * case it'll return null itself.
 */
@Suppress("FunctionName")
@Deprecated(
    "Please, use the new [NullableTreePropProvider] with [TreePropValuePair] args. You can use " +
        "[legacyTreePropOf] for interoperability with old API",
)
@JvmName("NullableLegacyTreePropProvider") // avoid JVM declaration clash with overload
inline fun NullableTreePropProvider(
    vararg props: Pair<Class<*>, *>,
    crossinline component: () -> Component?,
): KComponent? {
  val resolvedComponent = component() ?: return null
  return TreePropProviderImpl(classProps = props, child = resolvedComponent)
}

/**
 * Same as [TreePropProvider], but accepts a lambda that may return a nullable component, in which
 * case it'll return null itself.
 */
@Suppress("FunctionName")
inline fun NullableTreePropProvider(
    vararg props: TreePropValuePair<*>,
    crossinline component: () -> Component?,
): KComponent? {
  val resolvedComponent = component() ?: return null
  return TreePropProviderImpl(treeProps = props, child = resolvedComponent)
}

/** See [TreePropProvider]. */
@PublishedApi
internal class TreePropProviderImpl(
    private val classProps: Array<out Pair<Class<*>, *>>? = null,
    private val treeProps: Array<out TreePropValuePair<*>>? = null,
    private val child: Component,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    classProps?.forEach { createTreeProp(it.first, it.second) }
    treeProps?.forEach { createTreeProp(it.first, it.second) }
    return child
  }
}

private fun <T> ComponentScope.createTreeProp(clazz: Class<out T>, value: T) {
  if (!context.isParentTreePropContainerCloned) {
    context.treePropContainer = TreePropContainer.acquire(context.treePropContainer)
    context.isParentTreePropContainerCloned = true
  }
  context.treePropContainer?.put(clazz, value)
}

private fun <T> ComponentScope.createTreeProp(prop: TreeProp<out T>, value: T) {
  if (!context.isParentTreePropContainerCloned) {
    context.treePropContainer = TreePropContainer.acquire(context.treePropContainer)
    context.isParentTreePropContainerCloned = true
  }
  context.treePropContainer?.put(prop, value)
}

/**
 * Returns the instance registered for the type [T] in this hierarchy, and `null` if no value was
 * registered. Tree props are registered for a sub-hierarchy via [TreePropProvider] or
 * [com.facebook.litho.annotations.OnCreateTreeProp] in the Spec API.
 */
inline fun <reified T : Any> ResourcesScope.getTreeProp(): T? = context.getTreeProp(T::class.java)

/**
 * Returns the instance registered for the type [T] in this hierarchy, throws if no value was
 * registered. Tree props are registered for a sub-hierarchy via [TreePropProvider] or
 * [com.facebook.litho.annotations.OnCreateTreeProp] in the Spec API.
 */
inline fun <reified T : Any> ResourcesScope.requireTreeProp(): T = checkNotNull(getTreeProp())
