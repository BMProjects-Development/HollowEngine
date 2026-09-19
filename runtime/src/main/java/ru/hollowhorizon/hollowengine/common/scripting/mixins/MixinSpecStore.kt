package ru.hollowhorizon.hollowengine.common.scripting.mixins

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptText
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.*

/**
 * Mixin specs of the scripts that ran in the previous launch, which is what the next launch applies.
 */
internal object MixinSpecStore {
    private const val SUFFIX = ".class"
    private const val FAILED_SUFFIX = ".failed"
    private val SOURCE_ATTRIBUTE = Attributes.Name("Source-Hash")

    /**
     * A spec and the hash of the source it was compiled from, `null` for a script shipped without sources.
     * A `null` spec records a source that does not compile, so it is not compiled again until it changes.
     */
    class Stored(val spec: ByteArray?, val sourceHash: String?)

    /** One per runtime, so a directory both loaders run from keeps what each of them applies. */
    private fun file(runtime: String): File =
        DirectoryManager.HOLLOW_ENGINE.resolve("cache/mixins/specs-${runtime.replace('/', '-')}.jar").toFile()

    fun sourceHash(source: File): String {
        val text = ScriptText.normalize(source.readText())
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Stored specs by script id, or nothing when they were written for another [runtime]. */
    fun read(runtime: String): Map<String, Stored> {
        val jar = file(runtime).takeIf(File::isFile) ?: return emptyMap()
        return runCatching {
            JarFile(jar).use { archive ->
                val manifest = archive.manifest
                if (manifest?.mainAttributes?.getValue(ScriptCache.RUNTIME_ATTRIBUTE) != runtime) {
                    return@use emptyMap()
                }
                archive.entries().asSequence().filter { it.name.endsWith(SUFFIX) || it.name.endsWith(FAILED_SUFFIX) }
                    .associate { entry ->
                        val failed = entry.name.endsWith(FAILED_SUFFIX)
                        val spec = if (failed) null else archive.getInputStream(entry).use { it.readBytes() }
                        val hash = manifest.getAttributes(entry.name)?.getValue(SOURCE_ATTRIBUTE)
                        val scriptId = entry.name.removeSuffix(if (failed) FAILED_SUFFIX else SUFFIX)
                        URLDecoder.decode(scriptId, Charsets.UTF_8) to Stored(spec, hash)
                    }
            }
        }.onFailure { HollowEngine.LOGGER.warn("Unreadable mixin specs '{}'", jar, it) }.getOrDefault(emptyMap())
    }

    fun write(runtime: String, specs: Map<String, Stored>) {
        val target = file(runtime)
        runCatching {
            target.parentFile.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            val entries = specs.mapKeys { (scriptId, stored) ->
                URLEncoder.encode(scriptId, Charsets.UTF_8) + if (stored.spec == null) FAILED_SUFFIX else SUFFIX
            }
            val manifest = Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
                mainAttributes.putValue(ScriptCache.RUNTIME_ATTRIBUTE, runtime)
                mainAttributes.putValue("Spec-Version", ScriptMixinSpec.VERSION.toString())
                entries.forEach { (name, stored) ->
                    stored.sourceHash?.let { hash ->
                        this.entries[name] = Attributes().apply { put(SOURCE_ATTRIBUTE, hash) }
                    }
                }
            }
            JarOutputStream(temporary.outputStream(), manifest).use { archive ->
                entries.forEach { (name, stored) ->
                    archive.putNextEntry(JarEntry(name))
                    stored.spec?.let(archive::write)
                    archive.closeEntry()
                }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { HollowEngine.LOGGER.error("Failed to store the mixin specs of scripts", it) }
    }
}
