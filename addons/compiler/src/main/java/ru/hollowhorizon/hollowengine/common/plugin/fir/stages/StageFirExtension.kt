package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics

/** The same checkers run during compilation and standalone IDE analysis, without generating bytecode. */
internal class StageFirRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::StageFirExtension
        registerDiagnosticContainers(StageDiagnostics)
    }
}

private class StageFirExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers = setOf(StageFirCallChecker)
    }

    override val declarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers = setOf(StageFirFunctionChecker)
    }
}
