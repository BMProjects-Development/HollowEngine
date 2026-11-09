package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics

/** Named stages and lifted lambdas persist the same arguments and result. */
internal object StageFirSignatures {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun check(function: FirFunction) {
        (function.contextParameters + function.valueParameters).forEach { parameter ->
            parameter.returnTypeRef.coneType.stageProblem(context.session)?.let {
                reporter.reportOn(parameter.source, StageDiagnostics.STAGE_ERROR,
                    "The arguments of a stage are saved, but '${parameter.name}' cannot be: $it")
            }
        }
        function.receiverParameter?.let { receiver ->
            receiver.typeRef.coneType.stageProblem(context.session)?.let {
                reporter.reportOn(receiver.source, StageDiagnostics.STAGE_ERROR,
                    "The arguments of a stage are saved, but 'this' cannot be: $it")
            }
        }
        val result = function.returnTypeRef.coneType
        if (!result.isUnit) result.stageProblem(context.session)?.let {
            reporter.reportOn(function.source, StageDiagnostics.STAGE_ERROR, "The result of a stage is saved, but $it")
        }
    }
}
