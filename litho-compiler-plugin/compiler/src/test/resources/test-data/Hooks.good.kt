// @nolint
// PROCESSOR: SimpleClassDumpProcessor
// class OuterConditionalComponent
//   val condition: Boolean
//   fun render(): Component
// class OwnScope
// class OwnScopeSubclass
// class HookInRender
//   val a: String
//   val b: Int
//   fun render(): Component
// class NonLitho
//   val age: Int
//   val name: String
//   fun useBinding(): Boolean
//   fun useEffect(): Boolean
//   fun useHooks(): Unit
//   fun useState(): Boolean
// EXPECTED:
// END

// FILE: HookInRender.kt

import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.useState

class HookInRender(private val a: String, val b: Int) : KComponent() {
  override fun ComponentScope.render(): Component {
    val reversed = useState { false }
    return Column(isReversed = reversed.value)
  }
}

// FILE: NonLitho.kt

class NonLitho {
  val name = "Some Name"
  val age get() = 42

  fun useHooks() {
    useEffect()
    useBinding()
    useState()
  }

  fun useEffect(): Boolean = true
  fun useBinding(): Boolean = true
  fun useState(): Boolean = false
}

// FILE: HookInNestedComponent.kt

import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.useCached
import com.facebook.litho.useState
import com.facebook.litho.annotations.Hook

fun foo() = object : KComponent() {
  override fun ComponentScope.render(): Component {
    val reversed = useState { false }
    return Column(isReversed = reversed.value)
  }
}

// FILE: HookInNestedFunction.kt

import com.facebook.litho.ComponentScope
import com.facebook.litho.useCached
import com.facebook.litho.annotations.Hook

fun bar() {
  @Hook fun ComponentScope.useZero(): Int = useCached { 0 }
}

// FILE: HookInNestedComponentInsideConditional.kt

import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.useState

class OuterConditionalComponent(private val condition: Boolean) : KComponent() {
  override fun ComponentScope.render(): Component {
    if (condition) {
      val child = object : KComponent() {
        override fun ComponentScope.render(): Component {
          val state = useState { false }
          return Column()
        }
      }
    }
    return Column()
  }
}

// FILE: HookInPrimitiveRender.kt

import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.useState

// Covers the PrimitiveComponentScope receiver branch of isRenderMethod. A real PrimitiveComponent
// would have to supply mount and layout behaviours, which this rule does not look at.
fun PrimitiveComponentScope.render(): Int {
  useState { 0 }
  return 0
}

// FILE: HookInDeferredLambda.kt

import com.facebook.litho.ComponentScope
import com.facebook.litho.animated.useBinding
import com.facebook.litho.annotations.Hook
import com.facebook.litho.useCached

// Deliberately not reported: the rule looks through lambdas to the enclosing named function, and
// cannot tell a lambda that runs during render from one that does not. Hooks in a deferred lambda
// are the separate KComponentHookInDeferredLambda androidlint rule.
@Hook
fun ComponentScope.useDeferred(): Boolean =
    useCached(1) {
      useBinding { true }
      false
    }

// FILE: HookInOwnHookScopeReceiverLambda.kt

import com.facebook.litho.ComponentScope
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.RunsInOwnHookScope
import com.facebook.litho.useState

// A lambda whose receiver runs in its own hook scope hosts its hooks in any function. This is the
// shape of a LazyList childGroup body in a helper.
@OptIn(ExperimentalLithoApi::class)
@RunsInOwnHookScope
abstract class OwnScope(host: ComponentScope) : ComponentScope(host)

abstract class OwnScopeSubclass(host: ComponentScope) : OwnScope(host)

fun hosted(content: OwnScope.() -> Unit) = Unit

fun hostedSubclass(content: OwnScopeSubclass.() -> Unit) = Unit

fun helper() {
  hosted { useState { 0 } }
  hostedSubclass { useState { 0 } }
}
