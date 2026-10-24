package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
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
        checkCheckpointName(expression)
        val mapping = (expression.argumentList as? FirResolvedArgumentList)?.mapping ?: return
        val startsStory = startsStory(expression)
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
                checkCaptures(lambda, startsStory)
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

    /**
     * Warns about `rewind("x")` and `temporary(until = "x")` naming no checkpoint of the script. It is no error:
     * another script whose stages the story calls may pass one under that name.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkCheckpointName(call: FirFunctionCall) {
        val symbol = call.calleeReference.toResolvedCallableSymbol() ?: return
        if (symbol.callableId?.packageName != StageFirIds.PACKAGE) return
        val parameter = when (symbol.name.asString()) {
            "rewind" -> "checkpoint"
            "temporary" -> "until"
            else -> return
        }
        val argument = (call.argumentList as? FirResolvedArgumentList)?.mapping?.entries
            ?.firstOrNull { it.value.name.asString() == parameter }?.key as? FirLiteralExpression ?: return
        val name = argument.value as? String ?: return
        val file = context.containingDeclarations.map { it.fir }.filterIsInstance<FirFile>().firstOrNull() ?: return
        if (name in checkpointNames(file)) return
        reporter.reportOn(
            argument.source,
            StageDiagnostics.STAGE_WARNING,
            "No checkpoint of this script is named '$name': it only works if another script the story runs passes one"
        )
    }

    /** The constant names the checkpoints of [file] are given. */
    private fun checkpointNames(file: FirFile): Set<String> {
        val names = HashSet<String>()
        file.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirFunctionCall) {
                    val symbol = element.calleeReference.toResolvedCallableSymbol()
                    if (symbol?.callableId?.packageName == StageFirIds.PACKAGE && symbol.name.asString() == "checkpoint") {
                        ((element.argumentList.arguments.firstOrNull() as? FirLiteralExpression)?.value as? String)?.let(names::add)
                    }
                }
                element.acceptChildren(this)
            }
        })
        return names
    }

    /** `launchStory` and `StoryRuns.launch` start a story of their own, which keeps copies of what it captures. */
    private fun startsStory(call: FirFunctionCall): Boolean {
        val symbol = call.calleeReference.toResolvedCallableSymbol() ?: return false
        val name = symbol.name.asString()
        return (name == "launchStory" || name == "launch") && symbol.callableId?.packageName == StageFirIds.PACKAGE
    }

    /**
     * Whether the var [symbol] can be shared with a `@State` lambda: a `@State` function or lambda declares it
     * directly, so the compiler can turn it into a box the stage and the lambda share.
     */
    context(context: CheckerContext)
    private fun isShareable(symbol: FirLocalPropertySymbol): Boolean {
        val owner = context.containingDeclarations.asReversed().map { it.fir }.filterIsInstance<FirFunction>()
            .firstOrNull { it.declaresDirectly(symbol) } ?: return false
        return when (owner) {
            is FirNamedFunction -> owner.hasAnnotation(StageFirIds.STATE, context.session)
            is FirAnonymousFunction -> context.callsOrAssignments.filterIsInstance<FirFunctionCall>().any { call ->
                (call.argumentList as? FirResolvedArgumentList)?.mapping?.any { (argument, parameter) ->
                    (argument as? FirAnonymousFunctionExpression)?.anonymousFunction === owner &&
                        parameter.returnTypeRef.coneType.isStageLambda(context.session)
                } == true
            }
            else -> false
        }
    }

    private fun FirFunction.declaresDirectly(symbol: FirLocalPropertySymbol): Boolean {
        var found = false
        body?.acceptChildren(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found || element is FirFunction) return
                if (element is FirProperty && element.symbol == symbol) found = true else element.acceptChildren(this)
            }
        })
        return found
    }

    /**
     * Why the local function [function] cannot be called from a `@State` lambda, or null when it can: the compiler
     * copies it out of its scope, so what it uses from there must be saved with the lambda, like a capture.
     */
    context(context: CheckerContext)
    private fun localFunctionProblem(function: FirFunction, startsStory: Boolean, visited: MutableSet<FirFunction>): String? {
        if (!visited.add(function)) return null
        if (function.typeParameters.isNotEmpty()) return "it has type parameters"
        val owned = HashSet<FirBasedSymbol<*>>()
        function.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirDeclaration) owned += element.symbol
                element.acceptChildren(this)
            }
        })
        var problem: String? = null
        function.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (problem != null) return
                problem = when (element) {
                    is FirQualifiedAccessExpression -> access(element)
                    is FirVariableAssignment -> assignment(element)
                    else -> null
                }
                element.acceptChildren(this)
            }

            private fun access(expression: FirQualifiedAccessExpression): String? {
                val symbol = expression.calleeReference.toResolvedCallableSymbol() ?: return null
                if (symbol in owned) return null
                val name = symbol.name.asString()
                return when {
                    symbol is FirConstructorSymbol && symbol.resolvedReturnType.toRegularClassSymbol(context.session)?.fir?.isLocal == true ->
                        "it makes local class '${symbol.callableId.classId?.shortClassName}'"

                    symbol is FirFunctionSymbol<*> && symbol.fir.isLocal ->
                        if (expression is FirCallableReferenceAccess) "it refers to local function '$name'"
                        else localFunctionProblem(symbol.fir, startsStory, visited)

                    symbol is FirLocalPropertySymbol && symbol.fir.isVar -> when {
                        !isShareable(symbol) -> "it uses 'var $name', which no @State function or lambda declares directly"
                        startsStory -> "it uses 'var $name', and a story started here keeps copies of what it captures"
                        else -> symbol.resolvedReturnType.stageProblem(context.session)?.let { "it uses '$name', which cannot be saved: $it" }
                    }

                    symbol is FirLocalPropertySymbol || symbol is FirValueParameterSymbol ->
                        symbol.resolvedReturnType.stageProblem(context.session)?.let { "it uses '$name', which cannot be saved: $it" }

                    else -> null
                }
            }

            private fun assignment(assignment: FirVariableAssignment): String? {
                val symbol = (assignment.lValue as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
                if (symbol !is FirLocalPropertySymbol || symbol in owned || (symbol.fir.isVar && isShareable(symbol))) return null
                return "it assigns '${symbol.name}', which it can only read"
            }
        })
        return problem
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkCaptures(lambda: FirAnonymousFunction, startsStory: Boolean) {
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

                    symbol is FirFunctionSymbol<*> && symbol.fir.isLocal ->
                        if (qualifiedAccessExpression is FirCallableReferenceAccess) StageMessages.localClosure("local function '$name'")
                        else localFunctionProblem(symbol.fir, startsStory, HashSet())?.let { StageMessages.localFunction(name, it) }

                    symbol is FirLocalPropertySymbol && symbol.fir.isVar -> when {
                        !isShareable(symbol) -> StageMessages.mutableCapture(name)
                        startsStory -> StageMessages.sharedVar(name, "a story started here keeps copies of what it captures; pass the value instead")
                        symbol.fir.initializer == null -> StageMessages.sharedVar(name, "give it a value where it is declared")
                        else -> symbol.resolvedReturnType.stageProblem(context.session)?.let { StageMessages.sharedVar(name, "its value is saved, but $it") }
                    }

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
                if (symbol != null && symbol !in owned && symbol is FirLocalPropertySymbol && !(symbol.fir.isVar && isShareable(symbol))) {
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
