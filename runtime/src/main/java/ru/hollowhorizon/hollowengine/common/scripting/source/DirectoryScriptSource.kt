package ru.hollowhorizon.hollowengine.common.scripting.source

import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException

/**
 * Scripts kept in a plain directory. Used for the sandbox at runtime and for an addon's own
 * `src/main/resources/scripts` while it is being built.
 */
open class DirectoryScriptSource(
    override val namespace: String,
    val directory: File,
    override val classLoader: ClassLoader,
    override val classpath: List<File> = emptyList(),
    override val dependencies: List<String> = emptyList(),
    override val fingerprint: String = "",
) : ScriptSource {
    override fun list(): List<ScriptId> {
        if (!directory.isDirectory) return emptyList()
        val base = directory.toPath()
        return directory.walkTopDown()
            .filter { it.isFile && isScriptSourceFile(it.name) }
            .map { file -> ScriptId(namespace, base.relativize(file.toPath()).toString().replace('\\', '/')) }
            .sortedBy(ScriptId::path)
            .toList()
    }

    override fun read(id: ScriptId): ScriptArtifacts? {
        if (id.namespace != namespace) return null
        val file = resolve(id) ?: return null
        return ScriptArtifacts(id, sourceFile = file, precompiled = null)
    }

    private fun resolve(id: ScriptId): File? {
        val file = try {
            directory.resolve(id.path).canonicalFile
        } catch (_: IOException) {
            return null
        } catch (_: InvalidPathException) {
            return null
        }
        if (!file.isFile) return null
        // A path may not climb out of the directory through `..` segments.
        if (!file.toPath().startsWith(directory.canonicalFile.toPath())) return null
        return file
    }
}
