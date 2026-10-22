package ru.hollowhorizon.hollowengine.common.scripting.compiling

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache.SharedScriptRef
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses.clearInstances
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
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
    private class Entry(val reference: SharedScriptRef, val loader: ClassLoader, val dependencies: List<Entry>) {
        val identity: String get() = reference.fingerprint
    }

    private val loaders = HashMap<ScriptId, Entry>()
    private val instances = HashMap<KClass<*>, EvaluationResult>()
    private val kept = HashSet<ClassLoader>()

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
        val result = LinkedHashMap<String, Entry>()
        imports.forEach { collect(it, entries, parent, declared, result) }
        return result.mapValues { it.value.loader }
    }

    private fun collect(
        script: CompiledScript,
        entries: Map<String, ByteArray>,
        parent: ClassLoader?,
        declared: Map<String, SharedScriptRef>,
        result: LinkedHashMap<String, Entry>,
    ) {
        script.otherScripts.forEach { collect(it, entries, parent, declared, result) }

        if (script.compilationConfiguration[ScriptCompilationConfiguration.isSharedScript] != true) return
        val jvmScript = script as? KJvmCompiledScript
            ?: fail("Shared script '${script.sourceLocationId}' has no runtime-compatible JVM class metadata: " +
                "actual type=${script.javaClass.name}, actual loader=${script.javaClass.classLoader}, " +
                "expected loader=${KJvmCompiledScript::class.java.classLoader}")
        val name = jvmScript.scriptClassFQName
        script.otherScripts.forEach { dependency ->
            if (dependency.compilationConfiguration[ScriptCompilationConfiguration.isSharedScript] != true) {
                fail(sharedScriptImportError(script.sourceLocationId.orEmpty(), dependency.sourceLocationId.orEmpty()))
            }
        }
        val recorded = storedReferenceOf(jvmScript)
        val declaration = declared[name]
        if (recorded != null && declaration != null && recorded != declaration) {
            fail("Conflicting shared script metadata for '$name': serialized identity=$recorded, artifact manifest=$declaration. " +
                "Rebuild the artifact and its shared dependencies together.")
        }
        val reference = declaration ?: recorded ?: referenceOf(jvmScript)
            ?: fail("Cannot resolve shared script identity: class='$name', source='${script.sourceLocationId}', " +
                "registered namespaces=${ScriptRegistry.sources().map { it.namespace }}. " +
                "Register the source before compilation or rebuild the compiled artifact; refusing to create a private copy.")
        result[name]?.let { resolved ->
            val previous = resolved.reference
            if (previous != reference) {
                fail("Shared script class '$name' has conflicting identities in one import graph: '$previous' and '$reference'. " +
                    "Use distinct packages for scripts declaring the same class name. Existing loader=${resolved.loader}.")
            }
            return
        }
        val existing = loaders[reference.id]
        if (existing != null && existing.identity == reference.fingerprint) {
            HollowEngine.LOGGER.debug("Reusing shared script '{}', class={}, fingerprint={}, loader={}",
                reference.id.qualified, name, reference.fingerprint, existing.loader)
            result[name] = existing
            return
        }
        if (existing != null) {
            if (existing.loader in kept) {
                fail("Cannot replace startup/shared script '${reference.id.qualified}' while its instance is retained: " +
                    "old fingerprint=${existing.identity}, new fingerprint=${reference.fingerprint}, loader=${existing.loader}. Restart the game.")
            }
        }
        val classes = classesOf(reference, entries)
        val loader = MemoryClassLoader(classes, parent).apply { shareClassesOf(result.mapValues { it.value.loader }) }
        val dependencies = script.otherScripts.filterIsInstance<KJvmCompiledScript>()
            .mapNotNull { result[it.scriptClassFQName] }
        val entry = Entry(reference, loader, dependencies)
        loaders[reference.id] = entry
        if (existing != null) {
            HollowEngine.LOGGER.warn("Shared script '{}' changed identity: class={}, old fingerprint={}, new fingerprint={}, old loader={}, new loader={}",
                reference.id.qualified, name, existing.identity, reference.fingerprint, existing.loader, loader)
        }
        HollowEngine.LOGGER.debug("Created shared script loader: script={}, class={}, fingerprint={}, parent={}, loader={}",
            reference.id.qualified, name, reference.fingerprint, parent, loader)
        result[name] = entry
    }

    private fun storedReferenceOf(script: KJvmCompiledScript): SharedScriptRef? {
        val configuration = script.compilationConfiguration
        val storedId = configuration[ScriptCompilationConfiguration.sharedScriptId]
        val storedFingerprint = configuration[ScriptCompilationConfiguration.sharedScriptFingerprint]
        if (storedId != null && storedFingerprint != null) {
            return SharedScriptRef(script.scriptClassFQName, ScriptRegistry.parse(storedId), storedFingerprint)
        }
        return null
    }

    private fun referenceOf(script: KJvmCompiledScript): SharedScriptRef? {
        val location = script.sourceLocationId ?: return null
        val id = ScriptRegistry.idOfLocation(location) ?: return null
        val fingerprint = runCatching { ScriptFingerprint.compute(id) }
            .onFailure { HollowEngine.LOGGER.error("Cannot compute shared script identity for '${id.qualified}' at '$location'", it) }
            .getOrNull() ?: return null
        return SharedScriptRef(script.scriptClassFQName, id, fingerprint.code)
    }

    private fun classesOf(reference: SharedScriptRef, fallback: Map<String, ByteArray>): Map<String, ByteArray> {
        val artifact = artifactFor(reference)
        if (artifact != null && ScriptCache.hashOf(artifact) == reference.fingerprint) {
            return runCatching {
                entriesOf(artifact)
            }.getOrElse { fail("Unreadable shared script '$artifact': ${it.message}") }
        }

        if (fallback.containsKey(classFileOf(reference.scriptClass))) {
            val prefix = reference.scriptClass.replace('.', '/')
            return fallback.filterKeys { it == "$prefix.class" || it.startsWith("$prefix$") }
        }

        if (artifact != null) {
            fail("Shared script '${reference.id.qualified}' artifact '$artifact' has fingerprint '${ScriptCache.hashOf(artifact)}', " +
                "expected '${reference.fingerprint}'. Rebuild its importers and shared artifact together.")
        }

        fail("Shared script '${reference.id.qualified}' has no compiled classes: neither '${ScriptCache.sharedArtifact(reference.id)}' " +
            "nor a bundled artifact or in-memory copy exists. Reinstall the pack or rebuild it with the compiler addon.")
    }

    private fun artifactFor(reference: SharedScriptRef): File? {
        val candidates = listOfNotNull(
            ScriptCache.sharedArtifact(reference.id).takeIf(File::isFile),
            ScriptRegistry.artifacts(reference.id)?.precompiledShared?.takeIf(File::isFile),
        )
        return candidates.firstOrNull { ScriptCache.hashOf(it) == reference.fingerprint } ?: candidates.firstOrNull()
    }

    private fun fail(message: String): Nothing {
        HollowEngine.LOGGER.error(message)
        throw IllegalStateException(message)
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
        fun keep(entry: Entry) {
            if (!kept.add(entry.loader)) return
            entry.dependencies.forEach(::keep)
        }
        val loader = scriptClass.java.classLoader
        loaders.values.firstOrNull { it.loader === loader }?.let(::keep) ?: kept.add(loader)
    }

    /** Forgets the state of shared scripts, so it does not move into the next world. */
    @Synchronized
    fun clearInstances() {
        instances.keys.removeIf { it.java.classLoader !in kept }
    }
}
