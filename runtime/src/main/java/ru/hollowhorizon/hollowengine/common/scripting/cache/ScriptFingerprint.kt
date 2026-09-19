package ru.hollowhorizon.hollowengine.common.scripting.cache

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonRuntimeEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptImports
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptText
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.File
import java.security.MessageDigest

/**
 * The identity of a compiled script artifact.
 *
 * A cached jar holds bytecode produced for one engine build, one Kotlin version, one Minecraft
 * version and one exact set of sources, so all of that goes into the hash.
 */
object ScriptFingerprint {
    /** Bumped whenever the layout of a cached artifact changes in a way older jars cannot satisfy. */
    const val FORMAT_VERSION = 3

    /** What a build compiles addon scripts for: Mojang names, which is what NeoForge runs on. */
    const val NAMED_PRODUCTION_RUNTIME = "neoforge/official/production"

    /**
     * What an artifact was built from, at two levels of detail, and the runtime its bytecode is mapped
     * for.
     */
    data class Fingerprint(val code: String, val layout: String, val runtime: String)

    /**
     * Hash of [id] and everything it is built from, or `null` when the script has no sources to hash
     * (an addon that shipped compiled artifacts only).
     */
    fun compute(id: ScriptId): Fingerprint? {
        val closure = ScriptImports.closure(id)
        val sources = closure.mapNotNull { member ->
            val artifacts = ScriptRegistry.artifacts(member) ?: return@mapNotNull null
            val file = artifacts.sourceFile?.takeIf(File::isFile) ?: return@mapNotNull null
            member to file
        }
        if (sources.none { (member, _) -> member == id }) return null

        val code = MessageDigest.getInstance("SHA-256")
        val layout = MessageDigest.getInstance("SHA-256")

        fun updateBoth(text: String) {
            code.updateText(text)
            layout.updateText(text)
        }

        updateBoth("format=$FORMAT_VERSION")
        updateBoth("engine=${HollowEngineBuild.VERSION}")
        updateBoth("kotlin=${HollowEngineBuild.KOTLIN_VERSION}")
        updateBoth("minecraft=${HollowEngineBuild.MINECRAFT_VERSION}")
        updateBoth("source=${ScriptRegistry.source(id.namespace)?.fingerprint.orEmpty()}")
        sources.forEach { (member, file) ->
            val bytes = file.readBytes()
            updateBoth("script=${member.qualified}")
            updateBoth("definition=${definitionIdentity(member)}")
            code.updateText(ScriptText.normalize(String(bytes, Charsets.UTF_8)))
            layout.update(bytes)
        }
        return Fingerprint(code.digest().toHexString(), layout.digest().toHexString(), currentRuntimeIdentity)
    }

    /**
     * Platform, mapping namespace and dev/production, because the compiler remaps script bytecode into
     * the namespace the game actually runs in.
     *
     * A build compiling scripts ahead of time sets this to the runtime it is producing artifacts for;
     * inside the game it is derived from the running platform.
     */
    @Volatile
    var runtimeIdentity: String? = null

    fun runtimeIdentity(platform: String, mappingNamespace: String, production: Boolean): String =
        "$platform/$mappingNamespace/${if (production) "production" else "development"}"

    val currentRuntimeIdentity: String
        get() = runtimeIdentity ?: runtimeIdentity(
            platform = runCatching { HollowAddonRuntimeEnvironment.platform.id() }.getOrDefault("unknown"),
            mappingNamespace = runCatching { HollowAddonRuntimeEnvironment.mappingNamespace().id }.getOrDefault("unknown"),
            production = RuntimeFlags.production,
        )

    private val providers: List<ScriptClassProvider> by lazy {
        // Fingerprinting must never be the thing that breaks script loading, and the definitions pull in
        // Minecraft classes that are not there in every environment this code runs in.
        runCatching {
            DefaultScriptDefinitions.providers().sortedByDescending { it.extension.length }
        }.onFailure { HollowEngine.LOGGER.error("Cannot read the script definitions for cache keys", it) }
            .getOrDefault(emptyList())
    }

    /**
     * The script definition a file is compiled with its base class, default imports and implicit
     * receivers all end up in the generated constructor, so changing them invalidates old bytecode.
     * Sharing decides whether importers carry the script's classes at all.
     */
    private fun definitionIdentity(id: ScriptId): String {
        val provider = providers.firstOrNull { id.fileName.endsWith(it.extension) } ?: return "unknown"
        return buildString {
            append(provider.extension).append('|')
            append(provider.baseClass).append('|')
            provider.defaultImports.forEach { append(it).append(',') }
            append('|')
            provider.implicitReceivers.forEach { append(it.qualifiedName).append(',') }
            provider.clientSideReceivers?.let { receivers ->
                append('|')
                receivers.forEach { append(it).append(',') }
            }
            if (provider.shared) append("|shared")
        }
    }

    private fun MessageDigest.updateText(text: String) {
        update(text.toByteArray(Charsets.UTF_8))
        update(0)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }
}
