package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageMessages

@OptIn(SymbolInternals::class)
internal object StageFirCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (context.containingDeclarations.none { it.fir is FirScript }) return
        StageFirActions.checkCall(expression)
        val mapping = (expression.argumentList as? FirResolvedArgumentList)?.mapping ?: return
        mapping.forEach { (argument, parameter) ->
            if (!parameter.returnTypeRef.coneType.isStageLambda(context.session)) return@forEach
            val lambda = (argument as? FirAnonymousFunctionExpression)?.anonymousFunction
            if (lambda != null) {
                val arity =
                    lambda.valueParameters.size + lambda.contextParameters.size + if (lambda.receiverParameter != null) 1 else 0
                if (arity > 8) reporter.reportOn(
                    argument.source,
                    StageDiagnostics.STAGE_ERROR,
                    "A @State lambda takes at most 8 parameters, its receiver included"
                )
                StageFirSignatures.check(lambda)
                checkCaptures(lambda)
                StageFirBodyChecker.check(lambda, stage = true)
            } else {
                val forwarded =
                    (argument as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol() as? FirValueParameterSymbol
                if (forwarded == null || !forwarded.resolvedReturnType.isStageLambda(context.session)) {
                    reporter.reportOn(argument.source, StageDiagnostics.STAGE_ERROR, StageMessages.FUNCTION_VALUE)
                }
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkCaptures(lambda: FirAnonymousFunction) {
        val owned = HashSet<FirBasedSymbol<*>>()
        lambda.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirDeclaration) owned += element.symbol
                element.acceptChildren(this)
            }
        })
        lambda.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                when (element) {
                    is FirQualifiedAccessExpression -> checkAccess(element)
                    is FirVariableAssignment -> checkAssignment(element)
                }
                element.acceptChildren(this)
            }

            private fun checkAccess(qualifiedAccessExpression: FirQualifiedAccessExpression) {
                val symbol = qualifiedAccessExpression.calleeReference.toResolvedCallableSymbol() ?: return
                if (symbol in owned) return
                val name = symbol.name.asString()
                val message = when {
                    symbol is FirConstructorSymbol && symbol.resolvedReturnType.toRegularClassSymbol(context.session)?.fir?.isLocal == true -> StageMessages.localClosure(
                        "local class '${symbol.callableId.classId?.shortClassName}'"
                    )

                    symbol is FirFunctionSymbol<*> && symbol.fir.isLocal -> StageMessages.localClosure("local function '$name'")
                    symbol is FirLocalPropertySymbol && symbol.fir.isVar -> StageMessages.mutableCapture(name)
                    symbol is FirLocalPropertySymbol || symbol is FirValueParameterSymbol -> symbol.resolvedReturnType.stageProblem(
                        context.session
                    )?.let { StageMessages.unsavedCapture(name, it) }

                    else -> null
                }
                if (message != null) reporter.reportOn(
                    qualifiedAccessExpression.source, StageDiagnostics.STAGE_ERROR, message
                )
            }

            private fun checkAssignment(variableAssignment: FirVariableAssignment) {
                val symbol =
                    (variableAssignment.lValue as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
                if (symbol != null && symbol !in owned && symbol is FirLocalPropertySymbol) {
                    reporter.reportOn(
                        variableAssignment.source,
                        StageDiagnostics.STAGE_ERROR,
                        StageMessages.assignCapture(symbol.name.asString())
                    )
                }
            }
        })
    }
}
