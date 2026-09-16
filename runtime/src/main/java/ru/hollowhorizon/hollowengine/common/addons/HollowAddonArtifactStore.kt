package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.common.scripting.STARTUP_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.source.AddonScriptSource
import ru.hollowhorizon.hollowengine.runtime.remap.PayloadRemapTable
import ru.hollowhorizon.hollowengine.runtime.remap.applyTo
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.Deflater

internal data class HollowAddonCandidate(
    val sourceFile: File,
    val sourceLength: Long,
    val sourceModifiedAt: Long,
    val artifactFile: File,
    val classesFile: File,
    val fingerprint: String,
    val descriptor: HollowAddonDescriptor,
    val requiresBootstrapLibraries: Boolean,
    val hasStartupScripts: Boolean,
    val hasAssets: Boolean,
    val hasData: Boolean,
)

internal class HollowAddonArtifactStore(
    private val cacheRoot: File,
    private val platform: RuntimePlatform = HollowAddonRuntimeEnvironment.platform,
    private val runtimeNamespace: HollowAddonMappingNamespace = HollowAddonRuntimeEnvironment.mappingNamespace(),
    private val runtimeIdentity: () -> String = { ScriptFingerprint.currentRuntimeIdentity },
) {
    private val hostLibraries = listOf(
        "kotlin-stdlib",
        "kotlin-reflect",
        "kotlinx-coroutines",
        "koin-core",
        "slf4j-",
        "log4j-",
        "annotations-",
    ) + AddonBootstrapContract.HOST_NATIVE_LIBRARY_PREFIXES

    fun stage(sourceFile: File): HollowAddonCandidate {
        require(sourceFile.isFile && sourceFile.extension.equals("jar", ignoreCase = true)) {
            "Addon artifact is not a jar: ${sourceFile.absolutePath}"
        }
        val sourceLength = sourceFile.length()
        val sourceModifiedAt = sourceFile.lastModified()
        val fingerprint = sourceFile.sha256()
        val stagingDirectory = cacheRoot.resolve("artifacts").resolve(fingerprint)
        val stagedFile = stagingDirectory.resolve("addon.jar")
        if (!stagedFile.isFile || stagedFile.length() != sourceFile.length()) {
            stagingDirectory.mkdirs()
            Files.copy(sourceFile.toPath(), stagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val sourceChanged = sourceFile.length() != sourceLength || sourceFile.lastModified() != sourceModifiedAt
        val stagedFingerprint = stagedFile.sha256()
        if (sourceChanged || stagedFingerprint != fingerprint) {
            Files.deleteIfExists(stagedFile.toPath())
            throw IllegalStateException("Addon jar changed while it was being staged: ${sourceFile.name}")
        }
        val descriptor = HollowAddonDescriptorReader.read(stagedFile)
        val contents = JarFile(stagedFile).use { jar ->
            HollowAddonLayout.requireCurrentFormat(jar)
            val names = jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            Contents(
                requiresBootstrapLibraries = names.any { name ->
                    name.startsWith(AddonBootstrapContract.BOOTSTRAP_LIBRARY_PATH) && name.endsWith(".jar")
                },
                hasStartupScripts = names.any(::isStartupScript),
                hasAssets = names.any { it.startsWith(HollowAddonLayout.ASSETS_PREFIX) },
                hasData = names.any { it.startsWith(HollowAddonLayout.DATA_PREFIX) },
            )
        }
        val classesFile = unpackClasses(stagedFile, fingerprint)
        return HollowAddonCandidate(
            sourceFile = sourceFile.canonicalFile,
            sourceLength = sourceLength,
            sourceModifiedAt = sourceModifiedAt,
            artifactFile = stagedFile,
            classesFile = classesFile,
            fingerprint = fingerprint,
            descriptor = descriptor.copy(mappingNamespace = runtimeNamespace),
            requiresBootstrapLibraries = contents.requiresBootstrapLibraries,
            hasStartupScripts = contents.hasStartupScripts,
            hasAssets = contents.hasAssets,
            hasData = contents.hasData,
        )
    }

    private class Contents(
        val requiresBootstrapLibraries: Boolean,
        val hasStartupScripts: Boolean,
        val hasAssets: Boolean,
        val hasData: Boolean,
    )

    private fun unpackClasses(artifact: File, fingerprint: String): File {
        val variantCacheKey = "${platform.id()}-${runtimeNamespace.id}"
        val outputDirectory = cacheRoot.resolve("variants").resolve(fingerprint).resolve(variantCacheKey)
        val outputFile = outputDirectory.resolve("classes.jar")
        if (outputFile.isFile) return outputFile

        outputDirectory.mkdirs()
        val unpacked = outputDirectory.resolve("named.jar.tmp")
        val table = JarFile(artifact).use { jar ->
            val classes = jar.getJarEntry(HollowAddonLayout.CLASSES_JAR)
            val scripts = jar.entries().asSequence().filterNot { it.isDirectory }.filter { entry ->
                entry.name.startsWith(HollowAddonLayout.SOURCE_PREFIX) || entry.name.startsWith(HollowAddonLayout.COMPILED_PREFIX)
            }.toList()
            if (classes != null && scripts.isEmpty()) {
                jar.getInputStream(classes)
                    .use { Files.copy(it, unpacked.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } else {
                writeUnpacked(jar, classes, scripts, unpacked)
            }
            jar.getJarEntry(HollowAddonLayout.REMAP_TABLE)
                ?.takeIf { runtimeNamespace == HollowAddonMappingNamespace.INTERMEDIARY }
                ?.let { entry -> jar.getInputStream(entry).use(PayloadRemapTable::read) }
        }
        val finished = if (table == null) {
            unpacked
        } else {
            outputDirectory.resolve("classes.jar.tmp").also { remapped ->
                table.applyTo(unpacked, remapped, artifactRuntime = runtimeIdentity())
                Files.deleteIfExists(unpacked.toPath())
            }
        }
        try {
            Files.move(
                finished.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(finished.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return outputFile
    }

    private fun writeUnpacked(jar: JarFile, classes: JarEntry?, scripts: List<JarEntry>, target: File) {
        JarOutputStream(target.outputStream().buffered()).use { output ->
            output.setLevel(Deflater.BEST_SPEED)
            val written = HashSet<String>()
            fun put(name: String, input: InputStream) {
                if (!written.add(name)) return
                output.putNextEntry(JarEntry(name))
                input.copyTo(output)
                output.closeEntry()
            }

            classes?.let { entry ->
                JarInputStream(jar.getInputStream(entry)).use { nested ->
                    generateSequence { nested.nextJarEntry }.filterNot { it.isDirectory }
                        .forEach { put(it.name, nested) }
                }
            }
            scripts.forEach { entry -> jar.getInputStream(entry).use { put(entry.name, it) } }
        }
    }

    private fun isStartupScript(name: String): Boolean {
        val source = ".$STARTUP_SCRIPT_EXTENSION"
        val compiled = source + AddonScriptSource.COMPILED_SUFFIX
        return name.startsWith(AddonScriptSource.SOURCE_PREFIX) && name.endsWith(source) || name.startsWith(
            AddonScriptSource.COMPILED_PREFIX
        ) && name.endsWith(compiled)
    }

    fun extractLibraries(candidate: HollowAddonCandidate): List<File> {
        val libraryDirectory = cacheRoot.resolve("libraries").resolve(candidate.fingerprint)
        libraryDirectory.mkdirs()
        return JarFile(candidate.artifactFile).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(LIBRARY_PATH) && it.name.endsWith(".jar") }
                .mapNotNull { entry ->
                    val fileName = entry.name.substringAfterLast('/')
                    if (hostLibraries.any(fileName::startsWith)) return@mapNotNull null
                    val outputFile = libraryDirectory.resolve(fileName).canonicalFile
                    require(outputFile.parentFile == libraryDirectory.canonicalFile) {
                        "Illegal bundled library path '${entry.name}'"
                    }
                    if (!outputFile.isFile || outputFile.length() != entry.size) {
                        jar.getInputStream(entry).use { input ->
                            Files.copy(input, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    outputFile
                }.toList()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val LIBRARY_PATH = AddonBootstrapContract.REGULAR_LIBRARY_PATH
    }
}
