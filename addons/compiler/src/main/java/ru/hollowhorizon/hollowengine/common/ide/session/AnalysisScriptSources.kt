package ru.hollowhorizon.hollowengine.common.ide.session

import ru.hollowhorizon.hollowengine.common.addons.project.ProjectProperties
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptAnalysisProject
import ru.hollowhorizon.hollowengine.common.scripting.source.*
import java.io.File
import java.io.IOException

internal class AnalysisScriptSources(private val project: ScriptAnalysisProject?) {
    data class Source(val name: String, val path: String, val readText: () -> String)

    fun resolve(name: String, reference: String): Source? {
        val project = project ?: return resolveLocal(name, reference)
        val descriptor = SandboxScriptSource.descriptor(
            project.read(ProjectProperties.PATH)?.toByteArray(Charsets.ISO_8859_1)?.inputStream()
                ?.use(ProjectProperties::read) ?: ProjectProperties()
        )
        val namespace = descriptor.id
        val id = ScriptId.parse(name, namespace)
        val owner = if (id.namespace == namespace) id.copy(path = id.path.removePrefix(SCRIPTS_PREFIX)) else id
        fun read(id: ScriptId): String? = when (id.namespace) {
            namespace -> project.read(SCRIPTS_PREFIX + id.path)
            ScriptRegistry.sandboxNamespace -> null
            else -> ScriptRegistry.artifacts(id)?.sourceFile?.readText()
        }

        var source: Source? = null
        ScriptImports.resolve(owner, reference, { from, to ->
            from == to || if (from == namespace) to in descriptor.dependencies else ScriptRegistry.canImport(from, to)
        }) { candidate ->
            source = read(candidate)?.let { text ->
                val path =
                    if (candidate.namespace == namespace) SCRIPTS_PREFIX + candidate.path else candidate.qualified
                source(candidate, path) { ScriptTranslators.kotlinText(candidate, text, ::read) }
            }
            source != null
        }
        return source
    }

    private fun resolveLocal(name: String, reference: String): Source? {
        val id = ScriptImports.resolve(ScriptRegistry.parse(name), reference) ?: return null
        return registeredSource(id) ?: throw IOException("Cannot load the imported script '$reference'")
    }

    private fun registeredSource(id: ScriptId): Source? {
        val file = ScriptRegistry.artifacts(id)?.sourceFile ?: return null
        val name = editorPathOf(file)
        return source(id, name) { ScriptTranslators.kotlinText(id, file) }
    }

    private fun source(id: ScriptId, path: String, readText: () -> String): Source {
        val translated = ScriptTranslators.translates(id)
        return Source(if (translated) path + TRANSLATED else path, path, readText)
    }

    companion object {
        private const val SCRIPTS_PREFIX = "${SandboxScriptSource.SCRIPTS_DIRECTORY}/"
        private const val TRANSLATED = ".generated.kts"

        /** The path the editor opens files by. */
        fun editorPathOf(file: File): String {
            ScriptRegistry.idOf(file)?.takeIf { it.namespace != ScriptRegistry.sandboxNamespace }
                ?.let { return it.qualified }
            val root = DirectoryManager.HOLLOW_ENGINE.toAbsolutePath().normalize()
            val path = file.toPath().toAbsolutePath().normalize()
            if (!path.startsWith(root)) return file.name
            return root.relativize(path).toString().replace(File.separatorChar, '/')
        }
    }
}
