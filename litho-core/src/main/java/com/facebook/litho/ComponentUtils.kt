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
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.annotations.Comparable
import com.facebook.litho.annotations.EventHandlerRebindMode
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.drawable.ComparableDrawable
import com.facebook.rendercore.Equivalence
import com.facebook.rendercore.RenderUnitUpdateException
import com.facebook.rendercore.utils.areObjectsEquivalent
import com.facebook.rendercore.utils.isEquivalentTo
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.Arrays
import java.util.Deque
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

object ComponentUtils {

  @JvmStatic
  fun isSameComponentType(a: Component?, b: Component?): Boolean {
    if (a === b) {
      return true
    } else if (a == null || b == null) {
      return false
    }
    return a.javaClass == b.javaClass
  }

  /**
   * Given two components this method accesses all their internal fields, excluding the fields of
   * StateContainer if the class type is a Component, to check if they are equivalent. There's
   * special equality code to handle special class types e.g. Components, EventHandlers, etc.
   *
   * @param current current Component
   * @param next next Component
   * @return `true` iff the component fields are equivalent.
   */
  @JvmStatic
  fun isEquivalent(current: Component?, next: Component?): Boolean {
    if (current === next) {
      return true
    }
    return if (current == null || next == null) {
      false
    } else {
      current.isEquivalentTo(next)
    }
  }

  @JvmStatic
  fun hasEquivalentState(
      stateContainer1: StateContainer?,
      stateContainer2: StateContainer?,
  ): Boolean {
    if (stateContainer1 == null && stateContainer2 == null) {
      return true
    }
    return if (stateContainer1 == null || stateContainer2 == null) {
      false
    } else {
      hasEquivalentFields(stateContainer1, stateContainer2)
    }
  }

  @JvmStatic
  fun arePropsEquivalent(props1: Array<*>, props2: Array<*>): Boolean {
    if (props1.size != props2.size) return false

    for (i in props1.indices) {
      val prop1 = props1[i]
      val prop2 = props2[i]
      if (prop1 is Component && prop2 is Component) {
        if (!isEquivalent(prop1, prop2)) return false
      } else if (!areObjectsEquivalent(prop1, prop2)) {
        return false
      }
    }
    return true
  }

  /**
   * Given two object instances of the same type, this method accesses all their internal fields, to
   * check if they are equivalent. There's special equality code to handle special class types e.g.
   * Components, EventHandlers, etc. Components are considered equivalent if they have the same
   * props.
   *
   * @param obj1
   * @param obj2
   * @return true if the two instances are equivalent. False otherwise.
   */
  @JvmStatic
  fun hasEquivalentFields(obj1: Any, obj2: Any): Boolean {
    require(obj1.javaClass == obj2.javaClass) { "The input is invalid." }
    if (ComponentsConfiguration.cacheComparableFields) {
      comparedFieldsOf(obj1.javaClass)?.let {
        return hasEquivalentFieldsFrom(it, obj1, obj2)
      }
    }
    return hasEquivalentDeclaredFields(obj1, obj2)
  }

  private fun hasEquivalentFieldsFrom(
      comparedFields: Array<ComparedField>,
      obj1: Any,
      obj2: Any,
  ): Boolean {
    for (compared in comparedFields) {
      if (compared.comparableType == COMPARABLE_TYPE_UNREADABLE) {
        return false
      }
      val field = compared.field
      val val1: Any?
      val val2: Any?
      try {
        val1 = field[obj1]
        val2 = field[obj2]
      } catch (e: IllegalAccessException) {
        throw IllegalStateException("Unable to get fields by reflection.", e)
      }
      if (!isEquivalentUtil(compared.comparableType, compared.classType, val1, val2)) {
        return false
      }
    }
    return true
  }

  private fun hasEquivalentDeclaredFields(obj1: Any, obj2: Any): Boolean {
    for (field in obj1.javaClass.declaredFields) {
      if (!field.isAnnotationPresent(Comparable::class.java)) {
        continue
      }
      val classType = field.type
      val val1: Any?
      val val2: Any?
      try {
        field.isAccessible = true
        val1 = field[obj1]
        val2 = field[obj2]
        field.isAccessible = false
      } catch (e: IllegalAccessException) {
        throw IllegalStateException("Unable to get fields by reflection.", e)
      }
      @Comparable.Type val comparableType = comparableTypeOf(field)
      if (comparableType == COMPARABLE_TYPE_UNREADABLE) {
        return false
      }
      if (!isEquivalentUtil(comparableType, classType, val1, val2)) {
        return false
      }
    }
    return true
  }

  /** A field annotated [Comparable] whose annotation could not be read. */
  private const val COMPARABLE_TYPE_UNREADABLE = -1

  private class ComparedField(
      val field: Field,
      val classType: Class<*>,
      @Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
      @Comparable.Type
      val comparableType: Int,
  )

  /**
   * Ceiling on [comparedFieldsByClass], as a backstop against pathological class generation rather
   * than a working limit. Real hosts stay far below it — see the measurement in the diff that
   * introduced this.
   */
  private const val DEFAULT_MAX_CACHED_CLASSES = 16384

  @JvmStatic @VisibleForTesting var maxCachedClasses: Int = DEFAULT_MAX_CACHED_CLASSES

  private val comparedFieldsByClass = ConcurrentHashMap<Class<*>, Array<ComparedField>>()

  @JvmStatic @VisibleForTesting fun cachedClassCountForTesting(): Int = comparedFieldsByClass.size

  @JvmStatic
  @VisibleForTesting
  fun clearComparedFieldCacheForTesting() {
    comparedFieldsByClass.clear()
  }

  /**
   * The [Comparable]-annotated fields of [cls], paired with their comparison type and with
   * accessibility already forced, or null if [cls] is not cached and the cache is full.
   *
   * All three of the reflective steps this replaces — [Class.getDeclaredFields], which clones the
   * array and every [Field]; the [Field.isAnnotationPresent] filter; and the [Field.getAnnotation]
   * read that follows it and allocates an annotation proxy — depend only on the class, but used to
   * run on every comparison.
   *
   * Entries are never evicted; on Android the classes reaching them are loaded by the app's own
   * class loader and live for the process. Growth is bounded by [maxCachedClasses], past which
   * callers resolve the fields themselves. The size check races with concurrent inserts, so the map
   * may overshoot by the number of threads in flight — this is a ceiling, not an exact size.
   */
  private fun comparedFieldsOf(cls: Class<*>): Array<ComparedField>? {
    comparedFieldsByClass[cls]?.let {
      return it
    }
    if (comparedFieldsByClass.size >= maxCachedClasses) {
      return null
    }
    val fields =
        cls.declaredFields
            .filter { it.isAnnotationPresent(Comparable::class.java) }
            .map { field ->
              field.isAccessible = true
              ComparedField(field, field.type, comparableTypeOf(field))
            }
            .toTypedArray()
    return comparedFieldsByClass.putIfAbsent(cls, fields) ?: fields
  }

  @Comparable.Type
  private fun comparableTypeOf(field: Field): Int =
      try {
        field.getAnnotation(Comparable::class.java)?.type ?: COMPARABLE_TYPE_UNREADABLE
      } catch (ignore: IncompatibleClassChangeError) {
        /*
         * Libraries which uses annotations is facing this intermittently in Lollypop 5.0, 5.0.1 &
         * 5.0.2). Google closed this saying it is infeasible to fix this in older OS versions.
         *
         * <p>https://issuetracker.google.com/issues/37045084
         * https://github.com/google/gson/issues/726
         */
        COMPARABLE_TYPE_UNREADABLE
      } catch (ignore: NullPointerException) {
        COMPARABLE_TYPE_UNREADABLE
      }

  @Suppress("UNCHECKED_CAST")
  private fun isEquivalentUtil(
      @Comparable.Type comparableType: Int,
      classType: Class<*>,
      val1: Any?,
      val2: Any?,
  ): Boolean {
    when (comparableType) {
      Comparable.FLOAT ->
          if ((val1 as Float).compareTo(val2 as Float) != 0) {
            return false
          }
      Comparable.DOUBLE ->
          if ((val1 as Double).compareTo(val2 as Double) != 0) {
            return false
          }
      Comparable.ARRAY ->
          if (!areArraysEquals(classType, val1, val2)) {
            return false
          }
      Comparable.PRIMITIVE ->
          if (val1 != val2) {
            return false
          }
      Comparable.COMPARABLE_DRAWABLE ->
          if (!(val1 as ComparableDrawable).isEquivalentTo(val2 as ComparableDrawable)) {
            return false
          }
      Comparable.COLLECTION_COMPLEVEL_0 -> {
        val c1 = val1 as Collection<*>?
        val c2 = val2 as Collection<*>?
        if (if (c1 != null) c1 != c2 else c2 != null) {
          return false
        }
      }
      Comparable.COLLECTION_COMPLEVEL_1,
      Comparable.COLLECTION_COMPLEVEL_2,
      Comparable.COLLECTION_COMPLEVEL_3,
      Comparable.COLLECTION_COMPLEVEL_4 -> {
        // N.B. This relies on the IntDef to be in increasing order.
        val level = comparableType - Comparable.COLLECTION_COMPLEVEL_0
        if (!areComponentCollectionsEquals(level, val1 as Collection<*>?, val2 as Collection<*>?)) {
          return false
        }
      }
      Comparable.COMPONENT ->
          if (
              if (val1 != null) !(val1 as Component).isEquivalentTo(val2 as Component?)
              else val2 != null
          ) {
            return false
          }
      Comparable.SECTION ->
          if (!isEquivalentTo(val1 as Equivalence<Any>?, val2 as Equivalence<Any>?)) {
            return false
          }
      Comparable.EVENT_HANDLER,
      Comparable.EVENT_HANDLER_IN_PARAMETERIZED_TYPE ->
          if (
              if (val1 != null) !(val1 as EventHandler<*>).isEquivalentTo(val2 as EventHandler<*>?)
              else val2 != null
          ) {
            return false
          }
      Comparable.OTHER ->
          if (if (val1 != null) val1 != val2 else val2 != null) {
            return false
          }
      Comparable.CHARSEQUENCE ->
          if (!areObjectsEquivalent(val1, val2)) {
            return false
          }
      else -> {}
    }
    return true
  }

  /**
   * Calculate the level of the target Component/Section. The level here means how many bracket
   * pairs are needed to break until reaching the component type. For example, the level of
   * List&lt;Component&gt; is 1, and the level of List&lt;List&lt;Component&gt;&gt; is 2.
   *
   * @return the level of the target component, or 0 if the target isn't a component.
   */
  @JvmStatic
  fun levelOfComponentsInCollection(type: Type): Int {
    var type = type
    var level = 0
    while (true) {
      if (isParameterizedCollection(type)) {
        type = (type as ParameterizedType).actualTypeArguments[0]
        level++
      } else if (type is WildcardType) {
        type = type.upperBounds[0]
      } else {
        break
      }
    }
    return if (type is Class<*> && Component::class.java.isAssignableFrom(type)) level else 0
  }

  private fun isParameterizedCollection(type: Type): Boolean =
      type is ParameterizedType &&
          MutableCollection::class.java.isAssignableFrom(type.rawType as Class<*>)

  @JvmStatic
  fun areArraysEquals(classType: Class<*>, val1: Any?, val2: Any?): Boolean {
    val innerClassType =
        requireNotNull(classType.componentType) {
          "Expected 'classType' to be a class of an array type. Found $classType"
        }
    return when {
      java.lang.Byte.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as ByteArray?, val2 as ByteArray?)
      java.lang.Short.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as ShortArray?, val2 as ShortArray?)
      Character.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as CharArray?, val2 as CharArray?)
      Integer.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as IntArray?, val2 as IntArray?)
      java.lang.Long.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as LongArray?, val2 as LongArray?)
      java.lang.Float.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as FloatArray?, val2 as FloatArray?)
      java.lang.Double.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as DoubleArray?, val2 as DoubleArray?)
      java.lang.Boolean.TYPE.isAssignableFrom(innerClassType) ->
          Arrays.equals(val1 as BooleanArray?, val2 as BooleanArray?)
      else -> Arrays.equals(val1 as Array<*>?, val2 as Array<*>?)
    }
  }

  @JvmStatic
  fun areCollectionsEquals(type: Type, c1: Collection<*>?, c2: Collection<*>?): Boolean {
    val level = levelOfComponentsInCollection(type)
    if (level > 0) {
      return areComponentCollectionsEquals(level, c1, c2)
    }
    return if (c1 != null) c1 == c2 else c2 == null
  }

  private fun areComponentCollectionsEquals(
      level: Int,
      c1: Collection<*>?,
      c2: Collection<*>?,
  ): Boolean {
    require(level >= 1) { "Level cannot be < 1" }
    if (c1 === c2) {
      return true
    }
    if (c1 == null || c2 == null || c1.size != c2.size) {
      return false
    }
    val i1 = c1.iterator()
    val i2 = c2.iterator()
    while (i1.hasNext() && i2.hasNext()) {
      if (level == 1) {
        if (!(i1.next() as Component).isEquivalentTo(i2.next() as Component?)) {
          return false
        }
      } else {
        if (
            !areComponentCollectionsEquals(
                level - 1,
                i1.next() as Collection<*>?,
                i2.next() as Collection<*>?,
            )
        ) {
          return false
        }
      }
    }
    return true
  }

  /**
   * @return String representation of the tree with the root at the passed node For example:
   *   PlaygroundComponent |-Text[trans.key="text_transition_key";] |-Row | +-Text
   *   +-Text[manual.key="text2";]
   */
  @JvmStatic
  fun treeToString(root: LithoNode?): String {
    if (root == null) {
      return "null"
    }
    return buildString {
      val stack: Deque<LithoNode?> = LinkedList()
      stack.addLast(null)
      stack.addLast(root)
      var level = 0
      while (!stack.isEmpty()) {
        val node = stack.removeLast()
        if (node == null) {
          level--
          continue
        }
        val component = node.tailComponent
        if (node !== root) {
          append('\n')
          var isLast: Boolean
          val iterator = stack.iterator()
          iterator.next()
          iterator.next()
          for (index in 0 until level - 1) {
            isLast = iterator.next() == null
            if (!isLast) {
              while (iterator.next() != null) {}
            }
            append(if (isLast) ' ' else "\u2502").append(' ')
          }
          append(if (stack.last == null) "\u2514\u2574" else "\u251C\u2574")
        }
        append(component.simpleName)
        if (component.hasManualKey() || node.hasTransitionKey() || node.testKey != null) {
          append('[')
          if (component.hasManualKey()) {
            append("manual.key=\"").append(component.key).append("\";")
          }
          if (node.hasTransitionKey()) {
            append("trans.key=\"").append(node.transitionKey).append("\";")
          }
          if (node.testKey != null) {
            append("test.key=\"").append(node.testKey).append("\";")
          }
          append(']')
        }
        if (node.childCount == 0) {
          continue
        }
        stack.addLast(null)
        for (index in node.childCount - 1 downTo 0) {
          stack.addLast(node.getChildAt(index))
        }
        level++
      }
    }
  }

  /**
   * Reraise an error event up the hierarchy so it can be caught by another component, or reach the
   * root and cause the application to crash.
   *
   * @param c The component context the error event was caught in.
   * @param e The original exception.
   */
  @JvmStatic
  fun raise(c: ComponentContext?, e: Exception?) {
    throw ReThrownException(requireNotNull(e), requireNotNull(c).errorEventHandler)
  }

  /** Utility to dispatch an unhandled exception to a component. To be used by the framework. */
  @JvmStatic
  fun dispatchErrorEvent(c: ComponentContext, e: Exception) {
    val event = ErrorEvent()
    event.exception = e
    event.componentContext = c
    dispatchErrorEvent(c, event)
  }

  /** Utility to dispatch an error event to a component. To be used by the generated component. */
  @JvmStatic
  fun dispatchErrorEvent(c: ComponentContext, e: ErrorEvent) {
    val handler = c.errorEventHandler
    handler.dispatchEvent(e)
  }

  /**
   * Utility to get a component to handle an exception gracefully during the layout phase when
   * dealing with component hierarchy.
   */
  @JvmStatic
  fun handleWithHierarchy(parent: ComponentContext, component: Component?, exception: Exception) {
    // A shadow-validation layout handles no errors: the check turns them into a Mismatch.
    if (CustomLayoutShadowValidation.isRunning) {
      throw exception
    }
    val nextHandler = parent.errorEventHandler
    var exceptionToThrow = exception
    val lastHandler =
        when (exception) {
          is ReThrownException -> {
            exceptionToThrow = exception.original
            exception.lastHandler
          }
          is LithoMetadataExceptionWrapper -> exception.lastHandler
          else -> null
        }
    val metadataWrapper = wrapWithMetadata(parent, exceptionToThrow)
    metadataWrapper.addComponentNameForLayoutStack(component?.simpleName.toString())

    // This means it was already handled by this handler so throw it up to the next frame until we
    // get a new handler or get to the root
    if (lastHandler === nextHandler) {
      metadataWrapper.lastHandler = lastHandler
      throw metadataWrapper
    } else if (nextHandler is ErrorEventHandler) { // at the root
      nextHandler.onError(parent, metadataWrapper)
    } else { // Handle again with new handler
      try {
        dispatchErrorEvent(parent, exceptionToThrow)
      } catch (ex: ReThrownException) { // error handler re-raised the exception
        metadataWrapper.lastHandler = nextHandler
        throw metadataWrapper
      }
    }
  }

  /**
   * Utility to get a component to handle an exception gracefully outside the layout phase. If the
   * component re-raises the exception using [raise(ComponentContext, Exception)] then the utility
   * will rethrow the exception out of Litho.
   */
  @JvmStatic
  fun handle(c: ComponentContext, exception: Exception) {
    if (CustomLayoutShadowValidation.isRunning) {
      throw exception
    }
    trace({ "handleError" }) {
      try {
        if (c.componentScope != null) {
          // acquire component hierarchy metadata leveraging the global key
          val metadataExceptionWrapper = wrapWithMetadata(c, exception)
          val hierarchy = Component.generateHierarchy(c.globalKey)
          for (componentName in hierarchy) {
            metadataExceptionWrapper.addComponentNameForLayoutStack(componentName)
          }
          dispatchErrorEvent(c, metadataExceptionWrapper)
        } else {
          // we're not able to get global key hierarchy metadata from a ComponentContext without a
          // scope
          dispatchErrorEvent(c, exception)
        }
      } catch (re: ReThrownException) {
        throw wrapWithMetadata(c, exception)
      } catch (e: Exception) {
        throw wrapWithMetadata(c, e)
      }
    }
  }

  /** Utility to re-throw exceptions. */
  @JvmStatic
  fun rethrow(e: Exception) {
    when (e) {
      is ReThrownException -> rethrow(e.original)
      is RuntimeException -> throw e
      else -> throw RuntimeException(e)
    }
  }

  /**
   * Uses the given ComponentContext to add metadata to a wrapper exception (if the wrapper doesn't
   * already exist) and return it.
   */
  @JvmStatic
  fun wrapWithMetadata(c: ComponentContext?, e: Exception): LithoMetadataExceptionWrapper =
      when (e) {
        is LithoMetadataExceptionWrapper -> e
        else -> LithoMetadataExceptionWrapper(c, cause = e)
      }

  /**
   * Uses the given ComponentTree to add metadata to a wrapper exception (if the wrapper doesn't
   * already exist) and return it.
   */
  @JvmStatic
  fun wrapWithMetadata(view: BaseMountingView, e: Exception): LithoMetadataExceptionWrapper =
      when (view) {
        is LithoView -> wrapWithMetadata(view.componentTree, e)
        else -> LithoMetadataExceptionWrapper(cause = e)
      }

  /**
   * Uses the given ComponentTree to add metadata to a wrapper exception (if the wrapper doesn't
   * already exist) and return it.
   */
  @JvmStatic
  fun wrapWithMetadata(c: ComponentTree?, e: Exception): LithoMetadataExceptionWrapper =
      when (e) {
        is LithoMetadataExceptionWrapper -> e
        is RenderUnitUpdateException ->
            LithoMetadataExceptionWrapper(c, e).also { w ->
              e.renderUnitDebugKey?.let {
                Component.generateHierarchy(it).forEach(w::addComponentNameForLayoutStack)
              }
            }
        else -> LithoMetadataExceptionWrapper(c, e)
      }

  @JvmStatic
  fun createOrGetErrorEventHandler(
      component: Component?,
      parentContext: ComponentContext,
      scopedContext: ComponentContext?,
  ): EventHandler<ErrorEvent> =
      if (component is SpecGeneratedComponent && component.hasOwnErrorHandler()) {
        EventHandler(
            Component.ERROR_EVENT_HANDLER_ID,
            EventHandlerRebindMode.NONE,
            EventDispatchInfo(component as SpecGeneratedComponent?, scopedContext),
            null,
        )
      } else {
        parentContext.errorEventHandler
      }
}
