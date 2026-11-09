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

package com.facebook.litho.ir

import com.facebook.kotlin.compilercompat.compatExtensionReceiver
import com.facebook.kotlin.compilercompat.compatPutValueArgument
import com.facebook.kotlin.compilercompat.compatValueParameters
import com.facebook.litho.common.LithoNames
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.FqName

/**
 * IR transformer that wraps every call to a @Hook-annotated function with
 * `ComponentScope.enterHook(callSiteId)` before the call and `ComponentScope.exitHook()` after.
 *
 * The `callSiteId` is a stable hash of the enclosing callable identity and its local hook-call
 * ordinal. At runtime, `ComponentScope.enterHook` uses per-id counters and a parent pointer to
 * build a structured [HookKey]. Direct calls in render or @Hook functions use the statically known
 * occurrence index `0`; calls inside loops and lambdas use runtime counting. This supports
 * conditional hooks even when hook-bearing lambdas come from separately compiled modules.
 *
 * Generated code pattern:
 * ```
 * scope.enterHook(callSiteId, 0) // Direct call site: occurrence is known at compile time
 * // or scope.enterHook(callSiteId) for a dynamically repeated call site
 * try {
 *   scope.hookCall(...)
 * } finally {
 *   scope.exitHook()
 * }
 * ```
 */
class HookIdentityTransformer(private val pluginContext: IrPluginContext) :
    IrElementTransformerVoid() {

  private val hookAnnotationFqName = FqName("com.facebook.litho.annotations.Hook")

  private val componentScopeClassSymbol by lazy {
    pluginContext.referenceClass(LithoNames.ComponentScope)
  }

  @OptIn(DeprecatedForRemovalCompilerApi::class) // compatValueParameters
  private val enterHookFun: IrSimpleFunctionSymbol? by lazy {
    componentScopeClassSymbol?.let { classSymbol ->
      classSymbol.owner.declarations
          .filterIsInstance<IrSimpleFunction>()
          .firstOrNull { it.name == LithoNames.enterHook && it.compatValueParameters.size == 1 }
          ?.symbol
    }
  }

  @OptIn(DeprecatedForRemovalCompilerApi::class) // compatValueParameters
  private val enterHookWithKnownIndexFun: IrSimpleFunctionSymbol? by lazy {
    componentScopeClassSymbol?.let { classSymbol ->
      classSymbol.owner.declarations
          .filterIsInstance<IrSimpleFunction>()
          .firstOrNull { it.name == LithoNames.enterHook && it.compatValueParameters.size == 2 }
          ?.symbol
    }
  }

  @OptIn(DeprecatedForRemovalCompilerApi::class) // compatValueParameters
  private val exitHookFun: IrSimpleFunctionSymbol? by lazy {
    componentScopeClassSymbol?.let { classSymbol ->
      classSymbol.owner.declarations
          .filterIsInstance<IrSimpleFunction>()
          .firstOrNull { it.name == LithoNames.exitHook && it.compatValueParameters.isEmpty() }
          ?.symbol
    }
  }

  /** The current enclosing function, used for building IR. */
  private var currentFunction: IrFunction? = null

  /** Stable identity of the nearest enclosing named function. Lambdas share their owner's scope. */
  private var currentFunctionIdentity: String? = null

  /** Next local hook-call ordinal for each enclosing function. */
  private val hookCallCounters: MutableMap<String, Int> = mutableMapOf()

  private var currentFunctionHasSingleInvocationCallSites: Boolean = false
  private var loopDepth: Int = 0

  override fun visitFunction(declaration: IrFunction): IrStatement {
    val previousFunction = currentFunction
    val previousFunctionIdentity = currentFunctionIdentity
    val previousFunctionHasSingleInvocationCallSites = currentFunctionHasSingleInvocationCallSites
    val previousLoopDepth = loopDepth
    currentFunction = declaration
    if (!declaration.isLambda()) {
      currentFunctionIdentity = declaration.stableIdentity(previousFunctionIdentity)
    }
    currentFunctionHasSingleInvocationCallSites = declaration.hasSingleInvocationHookCallSites()
    loopDepth = 0
    return try {
      super.visitFunction(declaration)
    } finally {
      currentFunction = previousFunction
      currentFunctionIdentity = previousFunctionIdentity
      currentFunctionHasSingleInvocationCallSites = previousFunctionHasSingleInvocationCallSites
      loopDepth = previousLoopDepth
    }
  }

  override fun visitLoop(loop: IrLoop): IrExpression {
    return try {
      loopDepth++
      super.visitLoop(loop)
    } finally {
      loopDepth--
    }
  }

  @OptIn(DeprecatedForRemovalCompilerApi::class) // compatPutValueArgument
  override fun visitCall(expression: IrCall): IrExpression {
    // First, transform children (nested hook calls in arguments, etc.)
    val transformed = super.visitCall(expression) as IrCall

    // Check if the called function has @Hook annotation
    if (!transformed.symbol.owner.hasAnnotation(hookAnnotationFqName)) {
      return transformed
    }

    val enterHookWithKnownIndex =
        if (currentFunctionHasSingleInvocationCallSites && loopDepth == 0) {
          enterHookWithKnownIndexFun
        } else {
          null
        }
    val enterHook = enterHookWithKnownIndex ?: enterHookFun ?: return transformed
    val exitHook = exitHookFun ?: return transformed

    // Get the ComponentScope receiver (extension receiver for hook extension functions)
    val receiver = findComponentScopeReceiver(transformed) ?: return transformed

    val function = currentFunction ?: return transformed
    val functionIdentity = currentFunctionIdentity ?: return transformed
    val callSiteId = nextCallSiteId(functionIdentity)

    val builder = DeclarationIrBuilder(
        pluginContext,
        function.symbol,
        expression.startOffset,
        expression.endOffset,
    )

    return builder.irBlock(resultType = transformed.type) {
      // enterHook(callSiteId, 0), or enterHook(callSiteId) when the occurrence is dynamic.
      +irCall(enterHook).apply {
        dispatchReceiver = receiver.safeCopy()
        compatPutValueArgument(0, irInt(callSiteId))
        if (enterHookWithKnownIndex != null) {
          compatPutValueArgument(1, irInt(0))
        }
      }

      // try { hookCall(...) } finally { exitHook() }
      val exitHookCall = irCall(exitHook).apply { dispatchReceiver = receiver.safeCopy() }
      +IrTryImpl(
          expression.startOffset,
          expression.endOffset,
          transformed.type,
          transformed,
          listOf(),
          exitHookCall,
      )
    }
  }

  /**
   * Finds the ComponentScope receiver expression from a hook call. Hook functions are extension
   * functions on ComponentScope (or subclasses), so we look at the extension receiver first, then
   * dispatch receiver.
   */
  @OptIn(DeprecatedForRemovalCompilerApi::class) // compatExtensionReceiver
  private fun findComponentScopeReceiver(call: IrCall): IrExpression? {
    val scopeClass = componentScopeClassSymbol ?: return null

    call.compatExtensionReceiver?.let { receiver ->
      if (receiver.type.isSubtypeOfClass(scopeClass)) {
        return receiver
      }
    }

    call.dispatchReceiver?.let { receiver ->
      if (receiver.type.isSubtypeOfClass(scopeClass)) {
        return receiver
      }
    }

    return null
  }

  private fun IrFunction.isLambda(): Boolean =
      this is IrSimpleFunction &&
          (name.isSpecial || origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA)

  @OptIn(DeprecatedForRemovalCompilerApi::class)
  private fun IrFunction.stableIdentity(enclosingFunctionIdentity: String?): String {
    val callableName =
        fqNameWhenAvailable?.asString()
            ?: parentClassOrNull?.fqNameForIrSerialization?.asString()?.let {
              "$it#${name.asString()}"
            }
            ?: name.asString()
    val scopedName =
        if (enclosingFunctionIdentity == null || fqNameWhenAvailable != null) {
          callableName
        } else {
          "$enclosingFunctionIdentity#$callableName"
        }
    val receiver = extensionReceiverParameterCompat?.type?.stableName()?.let { "$it." }.orEmpty()
    val parameters = compatValueParameters.joinToString(",") { it.type.stableName() }
    return "$receiver$scopedName($parameters)"
  }

  // Type parameters and local classes have no class FqName. Render them: `IrType` does not
  // override `toString()`, and `IrType.hashCode()` mixes in identity hashes, so `toString()`
  // changes on every compilation.
  private fun IrType.stableName(): String = classFqName?.asString() ?: render()

  private fun nextCallSiteId(functionIdentity: String): Int {
    val ordinal = hookCallCounters[functionIdentity] ?: 0
    hookCallCounters[functionIdentity] = ordinal + 1
    val callSiteId = "$functionIdentity:$ordinal".hashCode()
    return if (callSiteId == RESERVED_CALL_SITE_ID) callSiteId + 1 else callSiteId
  }

  @OptIn(DeprecatedForRemovalCompilerApi::class)
  private fun IrFunction.hasSingleInvocationHookCallSites(): Boolean {
    if (
        this !is IrSimpleFunction ||
            name.isSpecial ||
            origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
    ) {
      return false
    }
    if (hasAnnotation(hookAnnotationFqName)) {
      return true
    }

    val scopeClass = componentScopeClassSymbol ?: return false
    return overriddenSymbols.isNotEmpty() &&
        name == LithoNames.render &&
        extensionReceiverParameterCompat?.type?.isSubtypeOfClass(scopeClass) == true
  }

  /** Creates a copy of an IrExpression that is safe to use in multiple positions. */
  private fun IrExpression.safeCopy(): IrExpression {
    return when (this) {
      is IrGetValue ->
          IrGetValueImpl(
              startOffset,
              endOffset,
              type,
              symbol,
              origin,
          )
      else -> this
    }
  }

  private companion object {
    /** Reserved by `HookKey` for ART-profile warm-up. */
    const val RESERVED_CALL_SITE_ID: Int = Int.MIN_VALUE
  }
}

// `IrFunction.extensionReceiverParameter` was removed in Kotlin 2.4; `parameters` carries the
// extension receiver on every supported compiler.
private val IrFunction.extensionReceiverParameterCompat: IrValueParameter?
  get() = parameters.singleOrNull { it.kind == IrParameterKind.ExtensionReceiver }
