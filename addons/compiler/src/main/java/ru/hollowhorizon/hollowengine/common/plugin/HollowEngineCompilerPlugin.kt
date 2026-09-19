package ru.hollowhorizon.hollowengine.common.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import ru.hollowhorizon.hollowengine.common.plugin.ir.StateIrGenerationExtension
import ru.hollowhorizon.hollowengine.common.plugin.mixins.MixinDeclarationCollector
import ru.hollowhorizon.hollowengine.common.plugin.mixins.MixinIrGenerationExtension

@OptIn(ExperimentalCompilerApi::class)
class HollowEngineCompilerPlugin : CompilerPluginRegistrar() {
    override val pluginId: String = "HollowEngineCompilerPlugin"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(StateIrGenerationExtension())
        val mixins = configuration.get(MixinDeclarationCollector.KEY) ?: MixinDeclarationCollector()
        IrGenerationExtension.registerExtension(MixinIrGenerationExtension(mixins))
    }
}
