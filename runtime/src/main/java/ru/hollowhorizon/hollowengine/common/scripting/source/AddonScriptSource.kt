package ru.hollowhorizon.hollowengine.common.scripting.source

import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * Scripts shipped inside an addon variant jar. Sources live under `scripts/`, artifacts compiled at
 * build time live under `META-INF/hollowengine/scripts/` with the same relative path plus a `.jar`
 * suffix. An addon may ship either or both; a build can deliberately omit the sources.
 *
 * Both kinds are materialized into the cache directory on first use, because the compiler and the
 * compiled-script loader both work with real files.
 */
class AddonScriptSource(
    override val namespace: String,
    private val archive: File,
    override val classLoader: ClassLoader,
    override val classpath: List<File>,
    override val dependencies: List<String>,
    /**
     * The addon version. It is part of the cache key, and the build produces artifacts for the same
     * key, which it could not do if this were derived from the jar itself.
     */
    override val fingerprint: String,
    /** Content hash of the jar, so extracted files of two builds of one version never mix. */
    private val storageKey: String,
) : ScriptSource {
    private val entries: Map<String, ArchiveEntry> = readEntries()

    override fun list(): List<ScriptId> = entries.keys.sorted().map { path -> ScriptId(namespace, path) }

    override fun read(id: ScriptId): ScriptArtifacts? {
        if (id.namespace != namespace) return null
        val entry = entries[id.path] ?: return null
        val sourceFile = entry.source?.let { extract(it, sourceCacheFile(id)) }
        val compiledFile = entry.compiled?.let { extract(it, bundledCacheFile(id)) }
        val sharedFile = entry.compiledShared?.let { extract(it, bundledSharedCacheFile(id)) }
        if (sourceFile == null && compiledFile == null) return null
        return ScriptArtifacts(id, sourceFile, precompiled = compiledFile, precompiledShared = sharedFile)
    }

    private fun readEntries(): Map<String, ArchiveEntry> = JarFile(archive).use { jar ->
        val collected = LinkedHashMap<String, ArchiveEntry>()
        jar.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
            val name = entry.name
            if (name.startsWith(SOURCE_PREFIX) && isScriptSourceFile(name)) {
                val path = name.removePrefix(SOURCE_PREFIX)
                collected[path] = collected[path].orEmpty().copy(source = name)
            } else if (name.startsWith(COMPILED_PREFIX) && name.endsWith(SHARED_EXTENSION)) {
                val path = name.removePrefix(COMPILED_PREFIX).removeSuffix(SHARED_SUFFIX)
                collected[path] = collected[path].orEmpty().copy(compiledShared = name)
            } else if (name.startsWith(COMPILED_PREFIX) && name.endsWith(COMPILED_EXTENSION)) {
                val path = name.removePrefix(COMPILED_PREFIX).removeSuffix(COMPILED_SUFFIX)
                collected[path] = collected[path].orEmpty().copy(compiled = name)
            }
        }
        collected
    }

    private fun sourceCacheFile(id: ScriptId): File =
        DirectoryManager.SCRIPT_SOURCE_CACHE.resolve(namespace).resolve(storageKey).resolve(id.path)

    private fun bundledCacheFile(id: ScriptId): File =
        bundleDirectory().resolve(id.path + COMPILED_SUFFIX)

    private fun bundledSharedCacheFile(id: ScriptId): File =
        bundleDirectory().resolve(id.path + SHARED_SUFFIX)

    private fun bundleDirectory(): File =
        DirectoryManager.SCRIPT_BUNDLE_CACHE.resolve(namespace).resolve(storageKey)
            .resolve(ScriptFingerprint.currentRuntimeIdentity)

    private fun extract(entryName: String, target: File): File? = JarFile(archive).use { jar ->
        val entry = jar.getJarEntry(entryName) ?: return null
        if (target.isFile && target.length() == entry.size) return target
        target.parentFile.mkdirs()
        jar.getInputStream(entry).use { input ->
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target
    }

    private fun ArchiveEntry?.orEmpty(): ArchiveEntry = this ?: ArchiveEntry()

    private data class ArchiveEntry(
        val source: String? = null,
        val compiled: String? = null,
        val compiledShared: String? = null,
    )

    companion object {
        const val SOURCE_PREFIX = "scripts/"
        const val COMPILED_PREFIX = "META-INF/hollowengine/scripts/"
        const val COMPILED_SUFFIX = ScriptCache.ARTIFACT_SUFFIX
        const val SHARED_SUFFIX = ScriptCache.SHARED_ARTIFACT_SUFFIX
        const val SCRIPT_EXTENSION = ".kts"
        private const val COMPILED_EXTENSION = SCRIPT_EXTENSION + COMPILED_SUFFIX
        private const val SHARED_EXTENSION = SCRIPT_EXTENSION + SHARED_SUFFIX
    }
}
