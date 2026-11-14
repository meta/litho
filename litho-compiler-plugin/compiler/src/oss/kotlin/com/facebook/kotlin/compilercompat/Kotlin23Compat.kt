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

// The internal build gets these from a compat library that spans several Kotlin versions. The open
// source build pins Kotlin 2.3, so it implements them directly. Buck never compiles this file.
@file:OptIn(org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class)

package com.facebook.kotlin.compilercompat

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrSymbol

/** Marks code that reads the message collector, which needs an opt-in on some Kotlin versions. */
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
annotation class MessageCollectorAccessCompat

abstract class FirFunctionCallCheckerCompat : FirFunctionCallChecker(MppCheckerKind.Common) {
  abstract fun check(
      expression: FirFunctionCall,
      context: CheckerContext,
      reporter: DiagnosticReporter,
  )

  context(context: CheckerContext, reporter: DiagnosticReporter)
  final override fun check(expression: FirFunctionCall) {
    check(expression, context, reporter)
  }
}

abstract class FirSimpleFunctionCheckerCompat(
    private val checker: (FirNamedFunction, CheckerContext, DiagnosticReporter) -> Unit
) : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  final override fun check(declaration: FirNamedFunction) {
    checker(declaration, context, reporter)
  }
}

abstract class FirRegularClassCheckerCompat(
    private val checker: (FirRegularClass, CheckerContext, DiagnosticReporter) -> Unit
) : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  final override fun check(declaration: FirRegularClass) {
    checker(declaration, context, reporter)
  }
}

/** Kotlin 2.3 still calls the named function checkers `simpleFunctionCheckers`. */
abstract class DeclarationCheckersCompat : DeclarationCheckers() {
  open val namedFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>> = emptySet()

  final override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>>
    get() = namedFunctionCheckers
}

val IrCall.compatDispatchReceiver: IrExpression?
  get() = dispatchReceiver

val IrCall.compatExtensionReceiver: IrExpression?
  get() = extensionReceiver

val IrFunction.compatValueParameters: List<IrValueParameter>
  get() = valueParameters

val IrMemberAccessExpression<*>.valueArgumentsCountCompat: Int
  get() = valueArgumentsCount

fun IrMemberAccessExpression<*>.getValueArgumentCompat(index: Int): IrExpression? =
    getValueArgument(index)

fun IrMemberAccessExpression<*>.putValueArgumentCompat(index: Int, valueArgument: IrExpression?) {
  putValueArgument(index, valueArgument)
}

fun <S : IrSymbol> IrMemberAccessExpression<S>.compatPutValueArgument(
    index: Int,
    valueArgument: IrExpression?,
) {
  putValueArgument(index, valueArgument)
}
