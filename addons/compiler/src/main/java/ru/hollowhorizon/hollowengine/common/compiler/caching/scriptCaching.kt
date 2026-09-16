package ru.hollowhorizon.hollowengine.common.compiler.caching

import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache.SharedScriptRef
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint.Fingerprint
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isSharedScript
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.impl.scriptMetadataPath
import kotlin.script.experimental.jvm.impl.toBytes
import kotlin.script.experimental.util.PropertiesCollection
import kotlin.use

/**
 * One shared script as it is written out: which classes belong to it and what to stamp its artifact with.
 */
private class SharedScript(
    val reference: SharedScriptRef,
    val fingerprint: Fingerprint,
    val classes: Map<String, ByteArray>,
) {
    private val classPrefix = reference.scriptClass.replace('.', '/')

    fun owns(path: String): Boolean = path == "$classPrefix.class" || path.startsWith("$classPrefix$")
}

/**
 * Writes the compiled root script module to [outputJar], and the classes of all shared scripts
 * that it imports next to it: in [sharedDirectory] when building artifacts, and if this parameter is `null`, in
 * the game's own cache.
 */
fun KJvmCompiledScript.saveScriptToJar(outputJar: File, fingerprint: Fingerprint, sharedDirectory: File? = null) {
    val module = (getCompiledModule() as? KJvmCompiledModuleInMemory)
        ?: throw IllegalArgumentException("Unsupported module type ${getCompiledModule()}")

    val shared = collectSharedScripts(module.compilerOutputFiles, fingerprint.runtime)
    shared.forEach { script -> writeSharedArtifact(script, sharedDirectory) }
    val ownClasses = module.compilerOutputFiles.filterKeys { path -> shared.none { it.owns(path) } }

    FileOutputStream(outputJar).use {
        val manifest = Manifest().apply {
            mainAttributes.apply {
                putValue("Manifest-Version", "1.0")
                putValue("Created-By", "HollowEngine ScriptingEngine")
                ScriptCache.stamp(this, fingerprint)
                putValue("Main-Class", scriptClassFQName)
                if (shared.isNotEmpty()) {
                    putValue(
                        ScriptCache.SHARED_ATTRIBUTE,
                        ScriptCache.sharedScriptsAttribute(shared.map { it.reference }),
                    )
                }
            }
        }

        JarOutputStream(it, manifest).use { jar ->
            // Write sanitized compiled script metadata
            jar.putNextEntry(JarEntry(scriptMetadataPath(scriptClassFQName)))
            jar.write(withPortableSourceLocations().apply(::shrinkSerializableScriptData).toBytes())
            jar.closeEntry()

            ownClasses.forEach { (path, bytes) ->
                jar.putNextEntry(JarEntry(path))
                jar.write(bytes)
                jar.closeEntry()
            }

            jar.finish()
            jar.flush()
        }
        it.flush()
    }
}

/**
 * The shared scripts this compilation produced classes for.
 */
private fun KJvmCompiledScript.collectSharedScripts(output: Map<String, ByteArray>, runtime: String): List<SharedScript> {
    val found = LinkedHashMap<String, SharedScript>()

    fun visit(script: CompiledScript) {
        script.otherScripts.forEach(::visit)
        if (script.compilationConfiguration[ScriptCompilationConfiguration.isSharedScript] != true) return
        val jvmScript = script as? KJvmCompiledScript ?: return
        val name = jvmScript.scriptClassFQName
        if (name in found) return

        val source = jvmScript.sourceLocationId?.let(::File)?.takeIf(File::isFile) ?: return
        val id = ScriptRegistry.idOf(source) ?: return
        val fingerprint = runCatching { ScriptFingerprint.compute(id) }.getOrNull()?.copy(runtime = runtime) ?: return

        val prefix = name.replace('.', '/')
        val classes = output.filterKeys { it == "$prefix.class" || it.startsWith("$prefix$") }
        if (classes.isEmpty()) return

        found[name] = SharedScript(SharedScriptRef(name, id, fingerprint.code), fingerprint, classes)
    }

    otherScripts.forEach(::visit)
    return found.values.toList()
}

private fun writeSharedArtifact(script: SharedScript, directory: File?) {
    val output = directory?.let { ScriptCache.sharedArtifactIn(it, script.reference.id) } ?: ScriptCache.sharedArtifact(
        script.reference.id
    )
    if (ScriptCache.isCurrent(output, script.fingerprint)) return

    output.parentFile?.mkdirs()
    val temporary = File(output.parentFile, output.name + ".tmp")
    FileOutputStream(temporary).use { stream ->
        val manifest = Manifest().apply {
            mainAttributes.apply {
                putValue("Manifest-Version", "1.0")
                putValue("Created-By", "HollowEngine ScriptingEngine")
                ScriptCache.stamp(this, script.fingerprint)
            }
        }

        JarOutputStream(stream, manifest).use { jar ->
            script.classes.forEach { (path, bytes) ->
                jar.putNextEntry(JarEntry(path))
                jar.write(bytes)
                jar.closeEntry()
            }
            jar.finish()
            jar.flush()
        }
        stream.flush()
    }
    Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private fun KJvmCompiledScript.withPortableSourceLocations(): KJvmCompiledScript = KJvmCompiledScript(
    portableLocation(sourceLocationId),
    compilationConfiguration,
    scriptClassFQName,
    resultField,
    otherScripts.map { other -> (other as? KJvmCompiledScript)?.withPortableSourceLocations() ?: other },
    null,
)

private fun portableLocation(location: String?): String? {
    val file = location?.let(::File) ?: return null
    return ScriptRegistry.idOf(file)?.qualified ?: file.name
}

private fun shrinkSerializableScriptData(compiledScript: KJvmCompiledScript) {
    (compiledScript.compilationConfiguration.entries() as? MutableSet<Map.Entry<PropertiesCollection.Key<*>, Any?>>)?.removeIf { it.key == ScriptCompilationConfiguration.dependencies || it.key == ScriptCompilationConfiguration.defaultImports }
}
