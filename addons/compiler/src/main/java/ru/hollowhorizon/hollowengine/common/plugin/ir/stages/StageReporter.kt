package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics

/** Reports stage errors against the file being compiled and remembers whether there were any. */
internal class StageReporter(private val context: IrPluginContext, private val file: IrFile) {
    var errors = 0
        private set

    fun error(element: IrElement, message: String) {
        errors++
        context.diagnosticReporter.at(element, file).report(StageDiagnostics.STAGE_ERROR, message)
    }
}
