package ru.hollowhorizon.hollowengine.common.plugin.stages

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers

/** Diagnostic factories shared by Kotlin's front end, the built-in IDE, and IR validation. */
internal object StageDiagnostics : KtDiagnosticsContainer() {
    val STAGE_ERROR = KtDiagnosticFactory1<String>(
        "HOLLOWENGINE_STAGE_ERROR",
        Severity.ERROR,
        SourceElementPositioningStrategies.DEFAULT,
        PsiElement::class,
        Messages,
    )

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Messages

    object Messages : BaseDiagnosticRendererFactory() {
        override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("HollowEngineStages") { map ->
            map.put(STAGE_ERROR, "{0}", CommonRenderers.STRING)
        }
    }
}
