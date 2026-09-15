package ru.hollowhorizon.hollowengine.common.scripting.compiling

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache.SharedScriptRef
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.util.jar.JarFile
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript

/**
 * The classes of `@file:SharedScript` scripts, defined once for the whole game.
 *
 * An imported script is compiled into the module of every script that imports it, so each importer's
 * jar carries its own copy. The cache keeps one artifact per shared script instead, and this registry
 * defines its classes once: without it two importers would end up with two unrelated classes for one
 * file, and `@file:SharedScript` could only share an instance inside a single import graph.
 */
object SharedScriptClasses {
    private class Entry(val identity: String, val loader: ClassLoader)

    /** Bytecode of one shared script, and what identifies the revision it came from. */
    private class Classes(val identity: String, val entries: Map<String, ByteArray>)

    private val loaders = HashMap<String, Entry>()
    private val instances = HashMap<KClass<*>, EvaluationResult>()
    private val kept = HashSet<KClass<*>>()
    private val reportedMismatches = HashSet<String>()

    /**
     * Where the classes of shared scripts among [imports] have to be looked up, by class name.
     *
     * Imports come before their importers, so a shared script that imports another one is built after
     * the loader it needs already exists.
     */
    @Synchronized
    fun loadersFor(
        imports: List<CompiledScript>,
        entries: Map<String, ByteArray>,
        parent: ClassLoader?,
        declared: Map<String, SharedScriptRef> = emptyMap(),
    ): Map<String, ClassLoader> {
        val result = LinkedHashMap<String, ClassLoader>()
        imports.forEach { collect(it, entries, parent, declared, result) }
        return result
    }

    private fun collect(
        script: CompiledScript,
        entries: Map<String, ByteArray>,
        parent: ClassLoader?,
        declared: Map<String, SharedScriptRef>,
        result: LinkedHashMap<String, ClassLoader>,
    ) {
        script.otherScripts.forEach { collect(it, entries, parent, declared, result) }

        if (script.compilationConfiguration[ScriptCompilationConfiguration.isSharedScript] != true) return
        val name = (script as? KJvmCompiledScript)?.scriptClassFQName ?: return
        if (name in result) return

        // No stable identity means no safe sharing: better a private copy than a wrong one.
        val reference = declared[name] ?: referenceOf(script) ?: return
        val existing = loaders[name]
        if (existing != null && existing.identity == reference.fingerprint) {
            result[name] = existing.loader
            return
        }
        val classes = classesOf(reference, entries) ?: return

        val entry = if (existing != null && existing.identity == classes.identity) {
            existing
        } else {
            val delegates = result.toMap()
            Entry(
                classes.identity, MemoryClassLoader(classes.entries, parent).apply { shareClassesOf(delegates) }).also {
                loaders[name] = it
            }
        }
        result[name] = entry.loader
    }

    /**
     * Makes the classes of a shared script that runs on its own the ones its importers link against, so
     * they get the instance it created. Without this the root and an importer would each define their own
     * class for one file, and an instance remembered for one is invisible to the other.
     */
    @Synchronized
    fun adopt(script: CompiledScript, scriptClass: KClass<*>) {
        val identity = when (script) {
            is KJvmCompiledScriptFromJar -> script.fingerprint
            is KJvmCompiledScript -> referenceOf(script)?.fingerprint
            else -> null
        } ?: return
        val name = scriptClass.java.name
        if (loaders[name]?.identity == identity) return
        loaders[name] = Entry(identity, scriptClass.java.classLoader)
    }

    private fun referenceOf(script: KJvmCompiledScript): SharedScriptRef? {
        val location = script.sourceLocationId ?: return null
        val id = ScriptRegistry.idOf(File(location)) ?: runCatching { ScriptRegistry.parse(location) }.getOrNull()
            ?.takeIf { ScriptRegistry.artifacts(it) != null } ?: return null
        val fingerprint = runCatching { ScriptFingerprint.compute(id) }.getOrNull() ?: return null
        return SharedScriptRef(script.scriptClassFQName, id, fingerprint.code)
    }

    private fun classesOf(reference: SharedScriptRef, fallback: Map<String, ByteArray>): Classes? {
        val artifact = artifactFor(reference)
        if (artifact != null) {
            val provided = ScriptCache.hashOf(artifact)
            if (provided != reference.fingerprint) reportMismatch(reference, artifact, provided)
            return runCatching {
                Classes(
                    provided ?: artifact.identity(), entriesOf(artifact)
                )
            }.onFailure { HollowEngine.LOGGER.error("Unreadable shared script '{}'", artifact, it) }.getOrNull()
        }

        if (fallback.containsKey(classFileOf(reference.scriptClass))) {
            return Classes(reference.fingerprint, fallback)
        }

        HollowEngine.LOGGER.error(
            "Shared script '{}' has no compiled classes: neither '{}' nor an artifact shipped with it exists, and the artifacts importing it carry no copy. Reinstall the pack, or install HollowEngineCompiler Addon to rebuild it.",
            ScriptRegistry.display(reference.id),
            ScriptCache.sharedArtifact(reference.id),
        )
        return null
    }

    private fun artifactFor(reference: SharedScriptRef): File? {
        val candidates = listOfNotNull(
            ScriptCache.sharedArtifact(reference.id).takeIf(File::isFile),
            ScriptRegistry.artifacts(reference.id)?.precompiledShared?.takeIf(File::isFile),
        )
        return candidates.firstOrNull { ScriptCache.hashOf(it) == reference.fingerprint } ?: candidates.firstOrNull()
    }

    private fun File.identity(): String = "$canonicalPath@${lastModified()}:${length()}"

    private fun reportMismatch(reference: SharedScriptRef, artifact: File, provided: String?) {
        if (!reportedMismatches.add("${reference.id.qualified}=$provided")) return
        HollowEngine.LOGGER.warn(
            "Compiled shared script '{}' is not the one the scripts importing it were built against ({} holds {} instead of {}); linking against it anyway",
            ScriptRegistry.display(reference.id),
            artifact,
            provided ?: "no fingerprint",
            reference.fingerprint,
        )
    }

    private fun entriesOf(artifact: File): Map<String, ByteArray> = JarFile(artifact).use { jar ->
        jar.entries().asSequence().associate { it.name to jar.getInputStream(it).readBytes() }
    }

    private fun classFileOf(scriptClass: String): String = scriptClass.replace('.', '/') + ".class"

    /** The instance of a shared script, or `null` when it has not been evaluated yet. */
    @Synchronized
    fun instanceOf(scriptClass: KClass<*>): EvaluationResult? = instances[scriptClass]

    @Synchronized
    fun rememberInstance(scriptClass: KClass<*>, result: EvaluationResult) {
        instances[scriptClass] = result
    }

    /** Keeps the instance of [scriptClass] through [clearInstances], for scripts that cannot run twice. */
    @Synchronized
    fun keepInstance(scriptClass: KClass<*>) {
        kept += scriptClass
    }

    /** Forgets the state of shared scripts, so it does not move into the next world. */
    @Synchronized
    fun clearInstances() {
        instances.keys.retainAll(kept)
    }
}
