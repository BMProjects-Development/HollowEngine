package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.Name
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageMessages

/** Structural restrictions are checked on FIR so the editor can report them before the IR rewrite. */
@OptIn(SymbolInternals::class)
internal object StageFirBodyChecker {
    /** The names of the functions of the script being checked, the names their `@State` gives them included. */
    @OptIn(DirectDeclarationsAccess::class)
    context(context: CheckerContext)
    private fun functionNames(): Set<String> {
        val script = context.containingDeclarations.map { it.fir }.filterIsInstance<FirScript>().firstOrNull() ?: return emptySet()
        return script.declarations.filterIsInstance<FirNamedFunction>().flatMapTo(HashSet()) { function ->
            listOfNotNull(
                function.name.asString(),
                function.getAnnotationByClassId(StageFirIds.STATE, context.session)?.getStringArgument(Name.identifier("name")),
            )
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun check(function: FirFunction, stage: Boolean) {
        val stageLambdas = HashSet<FirAnonymousFunction>()
        function.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirFunctionCall) {
                    (element.argumentList as? FirResolvedArgumentList)?.mapping?.forEach { (argument, parameter) ->
                        if (parameter.returnTypeRef.coneType.isStageLambda(context.session)) {
                            (argument as? FirAnonymousFunctionExpression)?.anonymousFunction?.let(stageLambdas::add)
                        }
                    }
                }
                element.acceptChildren(this)
            }
        })
        val checkpoints = HashSet<String>()
        function.body?.accept(object : FirVisitorVoid() {
            private var nested = 0
            private var handler = 0

            override fun visitElement(element: FirElement) {
                when (element) {
                    is FirAnonymousFunction if element in stageLambdas -> return
                    is FirFunction -> {
                        if (!stage) return // Named local functions receive their own declaration check.
                        nested++
                        element.acceptChildren(this)
                        nested--
                    }

                    is FirCatch -> {
                        handler++
                        element.acceptChildren(this)
                        handler--
                    }

                    is FirTryExpression -> {
                        element.tryBlock.accept(this)
                        element.catches.forEach { it.accept(this) }
                        handler++
                        element.finallyBlock?.accept(this)
                        handler--
                    }

                    is FirFunctionCall -> {
                        checkCall(element)
                        element.acceptChildren(this)
                    }

                    else -> element.acceptChildren(this)
                }
            }

            private fun checkCall(call: FirFunctionCall) {
                val symbol = call.calleeReference.toResolvedCallableSymbol() ?: return
                val checkpoint =
                    symbol.callableId?.packageName == StageFirIds.PACKAGE && symbol.name.asString() == "checkpoint"
                val boundary = checkpoint || symbol.hasAnnotation(
                    StageFirIds.STATE,
                    context.session
                ) || symbol.name.asString() == "invoke" && call.dispatchReceiver?.resolvedType?.isStageLambda(context.session) == true
                if (!boundary) return
                val problem = when {
                    !stage && checkpoint -> StageMessages.CHECKPOINT_OUTSIDE
                    !stage -> null
                    nested > 0 -> StageMessages.NESTED_CALL
                    handler > 0 -> StageMessages.HANDLER_CALL
                    else -> null
                }
                if (problem != null) reporter.reportOn(call.source, StageDiagnostics.STAGE_ERROR, problem)
                if (!stage || !checkpoint) return
                val name =
                    ((call.argumentList as? FirResolvedArgumentList)?.mapping?.entries?.firstOrNull { it.value.name.asString() == "name" }?.key as? FirLiteralExpression)?.value as? String
                when {
                    name.isNullOrBlank() -> reporter.reportOn(
                        call.source,
                        StageDiagnostics.STAGE_ERROR,
                        "A checkpoint needs a constant name"
                    )

                    !checkpoints.add(name) -> reporter.reportOn(
                        call.source, StageDiagnostics.STAGE_ERROR, "Checkpoint '$name' appears twice in this stage"
                    )

                    name in functionNames() -> reporter.reportOn(
                        call.source,
                        StageDiagnostics.STAGE_ERROR,
                        "A checkpoint cannot be named '$name': the script already has a function with this name, and rewind(\"$name\") would read as either"
                    )
                }
            }
        })
    }
}
