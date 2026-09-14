package ru.hollowhorizon.hollowengine.common.compiler.configuration

import ru.hollowhorizon.hollowengine.common.scripting.annotations.ClientSide
import ru.hollowhorizon.hollowengine.common.scripting.annotations.ServerSide
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isClientSideScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.util.PropertiesCollection

/**
 * Receivers a script type switches to under `@file:ClientSide`.
 */
val ScriptCompilationConfigurationKeys.clientSideImplicitReceivers by PropertiesCollection.key<List<KotlinType>>()

/**
 * Handles `@file:ClientSide` and `@file:ServerSide`. Server is the default, so only the client side changes
 * the configuration.
 */
class ScriptSideConfigurator : RefineScriptCompilationConfigurationHandler {
    override fun invoke(
        context: ScriptConfigurationRefinementContext,
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val declared = context.collectedData?.get(ScriptCollectedData.collectedAnnotations).orEmpty()
            .mapTo(HashSet()) { it.annotation.annotationClass.qualifiedName }
        val clientSide = ClientSide::class.qualifiedName in declared
        val serverSide = ServerSide::class.qualifiedName in declared
        if (!clientSide && !serverSide) return context.compilationConfiguration.asSuccess()

        val clientReceivers =
            context.compilationConfiguration[ScriptCompilationConfiguration.clientSideImplicitReceivers]
                ?: return makeFailureResult("@file:ClientSide and @file:ServerSide only apply to .reload.kts; " + "a .startup.kts runs on both sides and checks isClientSide instead")
        if (clientSide && serverSide) {
            return makeFailureResult("A script runs on one side only: keep either @file:ClientSide or @file:ServerSide")
        }
        if (!clientSide) return context.compilationConfiguration.asSuccess()

        return ScriptCompilationConfiguration(context.compilationConfiguration) {
            implicitReceivers.put(clientReceivers)
            isClientSideScript(true)
        }.asSuccess()
    }
}
