package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics

/** Action arguments are persisted too; report their restrictions through the same front end as stages. */
@OptIn(SymbolInternals::class)
internal object StageFirActions {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun check(function: FirNamedFunction) {
        if (!function.hasAnnotation(StageFirIds.ACTION, context.session)) return
        val owner = context.containingDeclarations.lastOrNull()?.fir
        val problem = when {
            !function.isSuspend -> "An action must be a suspend function"
            owner !is FirScript && (owner as? FirRegularClass)?.classKind != ClassKind.OBJECT -> "An action must be declared at the top level of a script or in an object"
            function.typeParameters.isNotEmpty() -> "An action cannot have type parameters: the journal saves its arguments by their declared types"
            function.contextParameters.isNotEmpty() -> "An action cannot take context parameters"
            else -> null
        }
        if (problem != null) reporter.reportOn(function.source, StageDiagnostics.STAGE_ERROR, problem)
        function.valueParameters.forEach { parameter ->
            if (parameter.hasAnnotation(StageFirIds.UNSAVED, context.session)) {
                if (parameter.defaultValue == null) reporter.reportOn(
                    parameter.source,
                    StageDiagnostics.STAGE_ERROR,
                    "The journal calls the action with the default of the unsaved '${parameter.name}', so it needs one"
                )
            } else if (parameter.returnTypeRef.coneType.classId != StageFirIds.CHECKPOINT_SCOPE) {
                parameter.returnTypeRef.coneType.stageProblem(context.session)?.let {
                    reporter.reportOn(
                        parameter.source,
                        StageDiagnostics.STAGE_ERROR,
                        "The journal saves the arguments of an action, but '${parameter.name}' cannot be saved: $it; mark it @Unsaved if undoing does not need it"
                    )
                }
            }
        }
        function.receiverParameter?.let { receiver ->
            if (receiver.typeRef.coneType.classId != StageFirIds.CHECKPOINT_SCOPE) {
                receiver.typeRef.coneType.stageProblem(context.session)?.let {
                    reporter.reportOn(
                        receiver.source,
                        StageDiagnostics.STAGE_ERROR,
                        "The journal saves the arguments of an action, but 'this' cannot be saved: $it"
                    )
                }
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun checkCall(call: FirFunctionCall) {
        val symbol = call.calleeReference.toResolvedCallableSymbol() ?: return
        if (symbol.callableId?.packageName != StageFirIds.PACKAGE || symbol.name.asString() !in setOf(
                "action",
                "label"
            )
        ) return
        val owner = context.containingDeclarations.lastOrNull { it.fir is FirFunction }?.fir as? FirFunction
        if (owner?.hasAnnotation(StageFirIds.ACTION, context.session) != true) reporter.reportOn(
            call.source,
            StageDiagnostics.STAGE_ERROR,
            "action { } and label { } must be the body of an @Action function, written directly in it"
        )
    }
}
