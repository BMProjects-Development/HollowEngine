package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.diagnostics.*
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile

/** The errors the stage compiler reports: misplaced stage calls, values a story cannot save. */
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

/** Reports stage errors against the file being compiled and remembers whether there were any. */
internal class StageReporter(private val context: IrPluginContext, private val file: IrFile) {
    var errors = 0
        private set

    fun error(element: IrElement, message: String) {
        errors++
        context.diagnosticReporter.at(element, file).report(StageDiagnostics.STAGE_ERROR, message)
    }
}
