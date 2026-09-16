package ru.hollowhorizon.hollowengine.common.scripting.cache

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache.SHARED_ATTRIBUTE
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache.parseSharedScripts
import ru.hollowhorizon.hollowengine.common.scripting.source.DEFAULT_SANDBOX_NAMESPACE
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarInputStream

/**
 * Compiled script artifacts on disk: one jar per root script, stamped with the fingerprint of the
 * sources it was built from.
 *
 * A `@file:SharedScript` gets an artifact of its own instead of a copy inside every importer. One
 * dialogue system imported by a thousand scripts is one jar, not a thousand, and at runtime its
 * classes are defined once for everybody.
 */
object ScriptCache {
    /** Fingerprint of the code used to compile the artifact, excluding any formatting of that code. */
    const val HASH_ATTRIBUTE = "Script-Hashcode"

    /** Fingerprint of the exact source bytes that identify only the line numbers where the errors occur. */
    const val LAYOUT_ATTRIBUTE = "Script-Layout"

    /** The runtime the bytecode is mapped for, see [ScriptFingerprint.runtimeIdentity]. */
    const val RUNTIME_ATTRIBUTE = "Script-Runtime"

    /** Which shared scripts were used when building root set. */
    const val SHARED_ATTRIBUTE = "Shared-Scripts"

    /**
     * Single script used to compile an artifact.
     */
    data class SharedScriptRef(val scriptClass: String, val id: ScriptId, val fingerprint: String)

    /** What the artifact says about itself in its manifest. */
    data class Stamp(
        val code: String?,
        val layout: String?,
        val runtime: String?,
        val shared: Map<String, SharedScriptRef>,
    )

    /** Writes [fingerprint] into the manifest of an artifact being built. */
    fun stamp(attributes: Attributes, fingerprint: ScriptFingerprint.Fingerprint) {
        attributes.putValue(HASH_ATTRIBUTE, fingerprint.code)
        attributes.putValue(LAYOUT_ATTRIBUTE, fingerprint.layout)
        attributes.putValue(RUNTIME_ATTRIBUTE, fingerprint.runtime)
    }

    const val ARTIFACT_SUFFIX = ".jar"

    /** Shared script classes are located next to the root artifact of that same script, under this suffix. */
    const val SHARED_ARTIFACT_SUFFIX = ".shared.jar"

    fun artifact(id: ScriptId): File = artifactIn(cacheDirectory(id), id)

    /** Classes of one shared script, referenced by every artifact that imports it. */
    fun sharedArtifact(id: ScriptId): File = sharedArtifactIn(cacheDirectory(id), id)

    fun artifactIn(directory: File, id: ScriptId): File = directory.resolve(id.path + ARTIFACT_SUFFIX)

    /**
     * The location in the [directory] where the shared script classes should be located.
     */
    fun sharedArtifactIn(directory: File, id: ScriptId): File = directory.resolve(id.path + SHARED_ARTIFACT_SUFFIX)

    private fun cacheDirectory(id: ScriptId): File = DirectoryManager.SCRIPT_CACHE.resolve(id.namespace)

    /** What [jar] was compiled from, or `null` if it is missing. */
    fun stampOf(jar: File): Stamp? {
        if (!jar.isFile) return null
        return runCatching {
            jar.inputStream().use { input ->
                JarInputStream(input).use { archive ->
                    val attributes = archive.manifest?.mainAttributes ?: return@use null
                    Stamp(
                        code = attributes.getValue(HASH_ATTRIBUTE),
                        layout = attributes.getValue(LAYOUT_ATTRIBUTE),
                        runtime = attributes.getValue(RUNTIME_ATTRIBUTE),
                        shared = parseSharedScripts(attributes.getValue(SHARED_ATTRIBUTE)),
                    )
                }
            }
        }.onFailure { HollowEngine.LOGGER.warn("Unreadable compiled script '{}'", jar, it) }.getOrNull()
    }

    /** The code fingerprint for which the JAR file was compiled, or `null` if it is missing. */
    fun hashOf(jar: File): String? = stampOf(jar)?.code

    /**
     * Does [jar] file contain bytecode that corresponds to the same code as [fingerprint],
     * regardless of how that code was organized.
     */
    fun isValid(jar: File, fingerprint: ScriptFingerprint.Fingerprint?): Boolean {
        if (fingerprint == null) return false
        val stamp = stampOf(jar) ?: return false
        return stamp.code == fingerprint.code && stamp.runtime == fingerprint.runtime
    }

    /**
     * Shows if [jar] was compiled from the source code currently on the disk,
     * including formatting, so that recompiling would serve no purpose.
     */
    fun isCurrent(jar: File, fingerprint: ScriptFingerprint.Fingerprint?): Boolean {
        if (fingerprint == null) return false
        val stamp = stampOf(jar) ?: return false
        return stamp.code == fingerprint.code && stamp.layout == fingerprint.layout &&
            stamp.runtime == fingerprint.runtime
    }

    /**
     * Specifies whether [jar] is up to date and whether the classes of all shared scripts it references are located in
     * [sharedDirectory].
     */
    fun isComplete(jar: File, fingerprint: ScriptFingerprint.Fingerprint?, sharedDirectory: File): Boolean {
        if (!isCurrent(jar, fingerprint)) return false
        return sharedScriptsOf(jar).values.all { reference ->
            hashOf(sharedArtifactIn(sharedDirectory, reference.id)) == reference.fingerprint
        }
    }

    fun invalidate(id: ScriptId) {
        val file = artifact(id)
        if (file.isFile && !file.delete()) {
            HollowEngine.LOGGER.warn("Could not remove the compiled script '{}'", file)
        }
    }

    /** Links to shared scripts [jar] associated with the class name that each one defines. */
    fun sharedScriptsOf(jar: File): Map<String, SharedScriptRef> = stampOf(jar)?.shared.orEmpty()

    /** The value of [SHARED_ATTRIBUTE] for [references]; [parseSharedScripts] reads it back. */
    fun sharedScriptsAttribute(references: Collection<SharedScriptRef>): String =
        references.joinToString(";") { "${it.scriptClass}=${it.id.qualified}=${it.fingerprint}" }

    private fun parseSharedScripts(raw: String?): Map<String, SharedScriptRef> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val nameEnd = entry.indexOf('=')
            if (nameEnd <= 0) return@mapNotNull null
            val name = entry.substring(0, nameEnd)
            val rest = entry.substring(nameEnd + 1)
            val separator = rest.lastIndexOf('=')
            if (separator <= 0) return@mapNotNull null
            val id = runCatching {
                ScriptId.parse(rest.substring(0, separator), DEFAULT_SANDBOX_NAMESPACE)
            }.getOrNull() ?: return@mapNotNull null
            name to SharedScriptRef(name, id, rest.substring(separator + 1))
        }.toMap()
    }

    /**
     * Removes artifacts that no longer belong to any known script, so renaming or deleting a script
     * does not leave its bytecode behind forever.
     *
     * Only namespaces represented in [known] are touched: a disabled addon has no scripts to report and
     * must not lose the artifacts it will need when it is enabled again.
     */
    fun prune(known: Collection<ScriptId>) {
        val root = DirectoryManager.SCRIPT_CACHE
        if (!root.isDirectory) return
        val expected = known.flatMapTo(HashSet()) {
            listOf(artifact(it).canonicalPath, sharedArtifact(it).canonicalPath)
        }
        known.mapTo(HashSet(), ScriptId::namespace).forEach { namespace ->
            val namespaceRoot = root.resolve(namespace)
            if (!namespaceRoot.isDirectory) return@forEach
            namespaceRoot.walkBottomUp().forEach { file ->
                when {
                    file.isFile && file.extension == "jar" && file.canonicalPath !in expected -> {
                        if (file.delete()) HollowEngine.LOGGER.debug("Removed stale compiled script '{}'", file)
                    }

                    file.isDirectory && file != namespaceRoot && file.list()?.isEmpty() == true -> file.delete()
                }
            }
        }
    }
}
