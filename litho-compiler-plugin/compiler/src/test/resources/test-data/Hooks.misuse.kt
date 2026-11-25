// @nolint
// PROCESSOR: ErrorOnlyProcessor
// EXPECTED:
// e: file:///Hooks.kt:10:40 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// e: file:///Hooks.kt:94:20 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// e: file:///Hooks.kt:102:23 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// e: file:///Hooks.kt:111:55 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// e: file:///Hooks.kt:115:30 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// e: file:///Hooks.kt:117:73 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// e: file:///Hooks.kt:119:48 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// e: file:///Hooks.kt:121:54 Hooks can only be called from render() or from another @Hook function. Either annotate this function @Hook, which makes its own call sites subject to the same rule, or hoist the hook into the caller and pass the value in. See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks
// END

// FILE: Hooks.kt

import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.animated.useBinding
import com.facebook.litho.annotations.Hook
import com.facebook.litho.useState

class HookInNonHookCallSite {
  fun ComponentScope.useOurBinding() = useBinding { true }
}

class HookInIfCondition {
  @Hook
  fun ComponentScope.useConditionalState(): Boolean {
    if (true) {
      useState { true }
      return true
    } else {
      return false
    }
  }
}

class HookInWhileLoop {
  @Hook
  fun ComponentScope.useConditionalState() {
    while (true) {
      val k = useState { 0 }
    }
  }
}

class HookInWhenCondition(val color: Color) : com.facebook.litho.KComponent() {
  override fun ComponentScope.render(): Component {
    when (color) {
      Color.RED -> useState {}
      Color.GREEN -> println("green")
      Color.BLUE -> println("blue")
    }
    return Column()
  }
}

enum class Color {
  RED,
  GREEN,
  BLUE
}

class HookInWhileLoopInRender : KComponent() {
  override fun ComponentScope.render(): Component {
    while (true) { val k = useState {} }
    return Column()
  }
}

class HookInForLoop {
  @Hook
  fun ComponentScope.useConditionalState() {
    for (i in 1..3) useState {}
  }
}

class HookInForEachLoop {
  @Hook
  fun ComponentScope.useConditionalState() {
    (1..3).forEach { useState {} }
  }
}

class HookInForEachLoopInRender : KComponent() {
  override fun ComponentScope.render(): Component {
    (1..3).forEach { useState {} }
    return Column()
  }
}

class HookInTryCatch {
  @Hook
  fun ComponentScope.useTryCatchState() {
    try {
      useState { 0 }
    } catch (e: Exception) {
      useState { 1 }
    }
  }
}

// A local function is a named function, so it is the container the rule looks at -- being lexically
// inside render() does not lend it render()'s hook scope.
class HookInLocalFunctionInRender : KComponent() {
  override fun ComponentScope.render(): Component {
    fun helper() = useState { 0 }
    return Column()
  }
}

class HookInAnonymousObjectInRender : KComponent() {
  override fun ComponentScope.render(): Component {
    val holder = object {
      fun compute() = useState { 0 }
    }
    return Column()
  }
}

// Only a lambda whose receiver runs in its own hook scope hosts a hook. Any other lambda, including
// one whose receiver is a plain ComponentScope, is looked through to the plain helper around it.
class HookInNonOwnScopeReceiverLambda {
  fun ComponentScope.helper() = with("not a scope") { useState { 0 } }

  fun hosted(content: ComponentScope.() -> Unit) = Unit

  fun forwarded() = hosted { useState { 0 } }

  fun plainCounter(scope: ComponentScope, initial: Int) = with(scope) { useState { initial } }

  fun ran(scope: ComponentScope) = scope.run { useState { 0 } }

  fun applied(scope: ComponentScope) = scope.apply { useState { 0 } }
}
