package ru.hollowhorizon.hollowengine.common.scripting

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.ScriptCompilationContext
import ru.hollowhorizon.hollowengine.common.scripting.compiling.loadKotlinCompiledScriptFromJar
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptArtifacts
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.script.experimental.api.ScriptEvaluationConfiguration

/**
 * The single way to turn a [ScriptId] into something runnable.
 *
 * Artifacts are preferred over sources whenever they match: one shipped inside an addon, then one in
 * the local cache, then a fresh compilation. When nothing matches and no compiler is installed a stale
 * artifact is still used, because a modpack without the compiler addon has nothing better to fall back
 * on and silence would be worse than a warning.
 */
object ScriptLoader {
    private val rejectedArtifacts = ConcurrentHashMap.newKeySet<String>()

    fun compile(id: ScriptId): Result<CompiledScript> {
        val artifacts = ScriptRegistry.artifacts(id)
            ?: return Result.failure(NoSuchElementException("Unknown script '${ScriptRegistry.display(id)}'"))
        val fingerprint = ScriptFingerprint.compute(id)
        val cached = ScriptCache.artifact(id)

        currentArtifact(artifacts, fingerprint, cached)?.let { jar ->
            return runCatching { load(id, jar) }
        }

        val scripting = ScriptingEnvironment.currentOrNull()
        if (scripting != null && artifacts.sourceFile != null) {
            val context = ScriptCompilationContext(
                extraClasspath = ScriptRegistry.classpath(id.namespace),
                baseClassLoader = ScriptRegistry.classLoader(id.namespace),
                cacheOutput = fingerprint?.let { cached },
                cacheFingerprint = fingerprint,
            )
            return scripting.compiler.compile(artifacts.sourceFile, context).onSuccess {
                // The artifact at this path has just been rewritten, so an earlier rejection of it
                // says nothing about the new one.
                rejectedArtifacts -= cached.canonicalPath
            }.map { it as CompiledScript }
        }

        val stale = listOfNotNull(
            cached.takeIf(File::isFile), artifacts.precompiled
        ).firstOrNull { it.isFile && it.canonicalPath !in rejectedArtifacts }
        if (stale != null) {
            report(id, stale, fingerprint)
            return runCatching { load(id, stale) }
        }

        return Result.failure(
            IllegalStateException(
                "Cannot load '${ScriptRegistry.display(id)}': no up-to-date compiled artifact and no compiler addon",
            ),
        )
    }

    /**
     * Compiles [id], hands its class to [validate] for host-specific checks, then runs it. A linkage
     * failure means the artifact was built against a different engine or mod set than the one running,
     * so it is dropped and rebuilt once before the error is reported.
     */
    fun <T> execute(
        id: ScriptId,
        validate: (KClass<*>) -> Unit = {},
        body: ScriptEvaluationConfiguration.Builder.() -> Unit = {},
    ): Result<T> = executeCompiled(id, { compiled -> validate(compiled.type) }, body)

    internal fun <T> executeCompiled(
        id: ScriptId,
        validate: (CompiledScript) -> Unit = {},
        body: ScriptEvaluationConfiguration.Builder.() -> Unit = {},
    ): Result<T> {
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            val result = runCatching {
                val compiled = compile(id).getOrThrow()
                validate(compiled)
                compiled.execute<T>(body).getOrThrow()
            }
            val failure = result.exceptionOrNull() ?: return result
            if (!isStaleArtifact(failure) || attempt > 0 || !ScriptingEnvironment.isAvailable()) {
                return Result.failure(failure)
            }
            lastFailure = failure
            HollowEngine.LOGGER.info(
                "Rebuilding '{}': its compiled copy was built against a different version of the engine",
                ScriptRegistry.display(id),
            )
            HollowEngine.LOGGER.debug("What the outdated '{}' failed on", ScriptRegistry.display(id), failure)
            reject(id)
        }
        return Result.failure(lastFailure ?: IllegalStateException("Failed to execute '${ScriptRegistry.display(id)}'"))
    }

    internal fun isStaleArtifact(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)
            .any { it is LinkageError }

    /**
     * Compiles every known script so a pack can be shipped to players who have no compiler addon.
     * Returns the scripts that failed, with the reason.
     */
    fun compileAll(ids: Collection<ScriptId> = ScriptRegistry.list(SCRIPT_EXTENSION)): Map<ScriptId, Throwable> {
        val failures = LinkedHashMap<ScriptId, Throwable>()
        ids.forEach { id ->
            compile(id).onFailure { failures[id] = it }
        }
        ScriptCache.prune(ScriptRegistry.list(SCRIPT_EXTENSION))
        return failures
    }

    /** Drops the cached artifact of [id] and stops trusting any artifact it currently resolves to. */
    fun reject(id: ScriptId) {
        ScriptCache.artifact(id).takeIf(File::isFile)?.let { rejectedArtifacts += it.canonicalPath }
        ScriptRegistry.artifacts(id)?.precompiled?.let { rejectedArtifacts += it.canonicalPath }
        ScriptCache.invalidate(id)
    }

    private fun currentArtifact(
        artifacts: ScriptArtifacts,
        fingerprint: ScriptFingerprint.Fingerprint?,
        cached: File,
    ): File? {
        val bundled = artifacts.precompiled?.takeIf { it.canonicalPath !in rejectedArtifacts }
        if (bundled != null) {
            if (artifacts.sourceFile == null) return bundled
            if (ScriptCache.isCurrent(bundled, fingerprint)) return bundled
        }
        if (cached.canonicalPath in rejectedArtifacts) return null
        return cached.takeIf { ScriptCache.isCurrent(it, fingerprint) }
    }

    private fun report(id: ScriptId, jar: File, fingerprint: ScriptFingerprint.Fingerprint?) {
        if (ScriptCache.isValid(jar, fingerprint)) {
            HollowEngine.LOGGER.info(
                "'{}' was compiled from the same code formatted differently; the line numbers it reports may not be the ones in the file",
                ScriptRegistry.display(id),
            )
        } else {
            HollowEngine.LOGGER.warn(
                "Using an outdated compiled '{}': its sources changed but the Kotlin compiler addon is not installed",
                ScriptRegistry.display(id),
            )
        }
    }

    private fun load(id: ScriptId, jar: File): CompiledScript {
        val classLoader = ScriptRegistry.source(id.namespace)?.classLoader
        return jar.loadKotlinCompiledScriptFromJar(classLoader)
    }

    private const val SCRIPT_EXTENSION = ".kts"
    private const val MAX_CAUSE_DEPTH = 8
}
