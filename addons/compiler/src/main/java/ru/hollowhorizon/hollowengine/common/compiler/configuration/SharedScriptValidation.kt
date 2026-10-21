package ru.hollowhorizon.hollowengine.common.compiler.configuration

import ru.hollowhorizon.hollowengine.common.scripting.compiling.isSharedScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.sharedScriptImportError
import org.jetbrains.kotlin.scripting.resolve.resolvedImportScripts
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

internal fun sharedImportDiagnostics(
    sources: List<SourceCode>,
    configuration: (SourceCode) -> ScriptCompilationConfiguration,
): List<ScriptDiagnostic> = buildList {
    sources.forEach { script ->
        val configured = configuration(script)
        if (configured[ScriptCompilationConfiguration.isSharedScript] != true) return@forEach
        configured[ScriptCompilationConfiguration.resolvedImportScripts].orEmpty().filter {
            configuration(it)[ScriptCompilationConfiguration.isSharedScript] != true
        }.forEach { dependency ->
            add(ScriptDiagnostic(
                ScriptDiagnostic.unspecifiedError,
                sharedScriptImportError(script.locationId ?: script.name.orEmpty(), dependency.locationId ?: dependency.name.orEmpty()),
                ScriptDiagnostic.Severity.ERROR,
                script.locationId,
            ))
        }
    }
}
