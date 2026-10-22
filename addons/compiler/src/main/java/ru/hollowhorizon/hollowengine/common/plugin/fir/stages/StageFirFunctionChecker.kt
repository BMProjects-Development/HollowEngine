package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.name.Name
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageMessages

@OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
internal object StageFirFunctionChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (context.containingDeclarations.none { it.fir is FirScript }) return
        StageFirActions.check(declaration)
        val stage = declaration.hasAnnotation(StageFirIds.STATE, context.session)
        StageFirBodyChecker.check(declaration, stage)
        if (!stage) return
        val owner = context.containingDeclarations.lastOrNull()?.fir as? FirScript
        fun stageName(function: FirNamedFunction): String =
            function.getAnnotationByClassId(StageFirIds.STATE, context.session)
                ?.getStringArgument(Name.identifier("name"))?.takeIf(String::isNotBlank) ?: function.name.asString()

        val name = stageName(declaration)
        val first = owner?.declarations?.filterIsInstance<FirNamedFunction>()?.firstOrNull {
            it.hasAnnotation(StageFirIds.STATE, context.session) && stageName(it) == name
        }
        if (first != null && first !== declaration) reporter.reportOn(
            declaration.source,
            StageDiagnostics.STAGE_ERROR,
            "Two stages of this script are named '$name'; give one of them different @State(name = ...)"
        )
        val problem = when {
            context.containingDeclarations.lastOrNull()?.fir !is FirScript -> StageMessages.TOP_LEVEL
            !declaration.isSuspend -> StageMessages.SUSPEND
            declaration.typeParameters.isNotEmpty() -> StageMessages.TYPE_PARAMETERS
            else -> null
        }
        if (problem != null) reporter.reportOn(declaration.source, StageDiagnostics.STAGE_ERROR, problem)
        StageFirSignatures.check(declaration)
    }
}
