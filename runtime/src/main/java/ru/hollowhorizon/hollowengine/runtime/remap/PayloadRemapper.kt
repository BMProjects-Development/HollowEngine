package ru.hollowhorizon.hollowengine.runtime.remap

import org.objectweb.asm.commons.Remapper
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A remapper that reports whether it changed anything while a class was being visited.
 */
abstract class TrackingRemapper : Remapper() {
    var changed: Boolean = false
        private set

    protected fun <T> track(original: T, mapped: T): T {
        if (original != mapped) changed = true
        return mapped
    }

    fun resetChanged() {
        changed = false
    }
}

class TableRemapper(private val table: PayloadRemapTable) : TrackingRemapper() {
    override fun map(internalName: String): String =
        track(internalName, table.classes[internalName] ?: internalName)

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        if (name == "<init>" || name == "<clinit>") return name
        if (!descriptor.startsWith("(")) return mapFieldName(owner, name, descriptor)
        return track(name, table.methods["$owner.$name$descriptor"] ?: name)
    }

    override fun mapFieldName(owner: String, name: String, descriptor: String?): String =
        track(name, table.fields["$owner.$name"] ?: name)

    override fun mapRecordComponentName(owner: String, name: String, descriptor: String): String =
        mapFieldName(owner, name, descriptor)

    override fun mapSignature(signature: String?, typeSignature: Boolean): String? =
        if (signature?.isEmpty() == true) null else super.mapSignature(signature, typeSignature)
}

/**
 * Compiled scripts that travel inside a jar, as jars of their own. Their classes are part of what the
 * jar refers to, so a rewrite covers them too.
 */
const val PRECOMPILED_SCRIPTS = "META-INF/hollowengine/scripts/"

/**
 * How one pass over a jar rewrites it.
 *
 * [forArtifact] hands out the remapper for one compiled script, given that script's own classes, for
 * rewrites that need to look into them. [artifactRuntime], when set, restamps every compiled script
 * with the runtime its bytecode is mapped for once the pass is done.
 */
class PayloadRewrite(
    val classes: TrackingRemapper,
    val forArtifact: (classes: Map<String, ByteArray>) -> TrackingRemapper = { classes },
    val artifactRuntime: String? = null,
)

fun remapPayload(input: File, output: File, rewrite: PayloadRewrite) {
    output.parentFile?.mkdirs()
    JarFile(input).use { jar ->
        JarOutputStream(output.outputStream().buffered()).use { out ->
            val written = HashSet<String>()
            jar.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                if (entry.name.endsWith(".RSA") || entry.name.endsWith(".SF")) return@forEach
                if (!written.add(entry.name)) return@forEach

                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                val content = when {
                    entry.name.startsWith(PRECOMPILED_SCRIPTS) -> {
                        if (entry.name.endsWith(".jar")) remapArtifact(bytes, rewrite) else bytes
                    }
                    entry.name.endsWith(".class") -> rewrite.classes.remapIfChanged(bytes)
                    else -> bytes
                }

                out.putNextEntry(JarEntry(entry.name))
                out.write(content)
                out.closeEntry()
            }
        }
    }
}

fun PayloadRemapTable.applyTo(input: File, output: File, artifactRuntime: String? = null) =
    remapPayload(input, output, PayloadRewrite(TableRemapper(this), artifactRuntime = artifactRuntime))

private fun remapArtifact(bytes: ByteArray, rewrite: PayloadRewrite): ByteArray {
    val entries = LinkedHashMap<String, ArtifactEntry>()
    val manifest = JarInputStream(ByteArrayInputStream(bytes)).use { input ->
        generateSequence { input.nextJarEntry }
            .filterNot { it.isDirectory || it.name.equals(JarFile.MANIFEST_NAME, ignoreCase = true) }
            .forEach { entry -> entries[entry.name] = ArtifactEntry(entry.time, input.readBytes()) }
        input.manifest ?: Manifest()
    }
    val remapper = rewrite.forArtifact(
        entries.filterKeys { it.endsWith(".class") }.entries.associate { (name, entry) ->
            name.removeSuffix(".class") to entry.bytes
        }
    )
    rewrite.artifactRuntime?.let { manifest.mainAttributes.putValue(ScriptCache.RUNTIME_ATTRIBUTE, it) }

    val output = ByteArrayOutputStream(bytes.size)
    JarOutputStream(output).use { jar ->
        jar.putNextEntry(JarEntry(JarFile.MANIFEST_NAME).apply { time = ARTIFACT_ENTRY_TIME })
        manifest.write(jar)
        jar.closeEntry()
        entries.forEach { (name, entry) ->
            jar.putNextEntry(JarEntry(name).apply { time = entry.time.takeIf { it >= 0 } ?: ARTIFACT_ENTRY_TIME })
            jar.write(if (name.endsWith(".class")) remapper.remapIfChanged(entry.bytes) else entry.bytes)
            jar.closeEntry()
        }
    }
    return output.toByteArray()
}

private class ArtifactEntry(val time: Long, val bytes: ByteArray)

private const val ARTIFACT_ENTRY_TIME = 318211200000L

private fun TrackingRemapper.remapIfChanged(bytes: ByteArray): ByteArray {
    resetChanged()
    val remapped = bytes.remap(this)
    return if (changed) remapped else bytes
}
