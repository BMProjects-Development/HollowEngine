package ru.hollowhorizon.hollowengine.common.compiler.configuration

import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Import
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptImports
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptTranslators
import java.io.File
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileBasedScriptSource
import kotlin.script.experimental.host.FileScriptSource

/**
 * Handles `@file:Import("...")` through the script registry. Physical scripts use their registered identity,
 * IDE scripts use the logical editor path as that identity, and unregistered physical scripts fall back to
 * paths relative to their source file.
 */
class HollowScriptConfigurator : RefineScriptCompilationConfigurationHandler {
    override operator fun invoke(context: ScriptConfigurationRefinementContext) = processAnnotations(context)

    private fun processAnnotations(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val references = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)
            .orEmpty()
            .asSequence()
            .map { it.annotation }
            .filter { annotation ->
                annotation.annotationClass.qualifiedName == "ru.hollowhorizon.hollowengine.common.scripting.annotations.Import"
            }
            .flatMap { annotation ->
                val fileMethod = annotation.javaClass.getMethod("file")
                val filesArray = fileMethod.invoke(annotation) as? Array<*> ?: emptyArray<Any>()
                filesArray.map { it.toString() }
            }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (references.isEmpty()) return context.compilationConfiguration.asSuccess()

        val script = context.script
        val scriptFile = (script as? FileBasedScriptSource)?.file
        val owner = when (script) {
            is KtFileScriptSource -> ScriptRegistry.parse(script.ktFile.name)
            else -> scriptFile?.let(ScriptRegistry::idOf)
        }
        val isIdeSource = script is KtFileScriptSource
        val importedSources = ArrayList<SourceCode>(references.size)
        references.forEach { reference ->
            val resolution = runCatching { resolve(owner, scriptFile, reference) }
            val resolved = resolution.getOrNull()
            if (resolved == null) {
                if (isIdeSource) return@forEach
                val message = resolution.exceptionOrNull()?.message
                    ?: "Cannot resolve the imported script '$reference'"
                return makeFailureResult(message)
            }
            importedSources += FileScriptSource(resolved)
        }

        if (importedSources.isEmpty()) return context.compilationConfiguration.asSuccess()

        return ScriptCompilationConfiguration(context.compilationConfiguration) {
            importScripts.append(importedSources)
        }.asSuccess()
    }

    private fun resolve(owner: ScriptId?, scriptFile: File?, reference: String): File? {
        if (owner != null) {
            val id = ScriptImports.resolve(owner, reference) ?: return null
            val source = ScriptRegistry.artifacts(id)?.sourceFile ?: return null
            return ScriptTranslators.kotlinSource(id, source)
        }
        val base = scriptFile?.parentFile ?: return File(reference).takeIf(File::isFile)
        return base.resolve(reference).takeIf(File::isFile) ?: File(reference).takeIf(File::isFile)
    }
}
