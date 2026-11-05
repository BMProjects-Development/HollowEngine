package ru.hollowhorizon.hollowengine.common.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import ru.hollowhorizon.hollowengine.common.plugin.ir.stages.StageIrGeneration
import ru.hollowhorizon.hollowengine.common.plugin.ir.transformers.ImportedScriptReceiverTransformer

class StateIrGenerationExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        Companion.pluginContext = pluginContext
        moduleFragment.transform(ImportedScriptReceiverTransformer(moduleFragment, pluginContext), null)
        StageIrGeneration(pluginContext).generate(moduleFragment)
    }

    companion object {
        lateinit var pluginContext: IrPluginContext
            private set
    }
}
