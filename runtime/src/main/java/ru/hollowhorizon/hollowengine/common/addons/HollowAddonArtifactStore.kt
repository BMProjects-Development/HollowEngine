package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.common.scripting.STARTUP_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.source.AddonScriptSource
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile

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
)

internal class HollowAddonArtifactStore(
    private val cacheRoot: File,
    private val platform: RuntimePlatform = HollowAddonRuntimeEnvironment.platform,
    private val runtimeNamespace: HollowAddonMappingNamespace = HollowAddonRuntimeEnvironment.mappingNamespace(),
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
        val variant = HollowAddonVariants.select(
            stagedFile,
            platform,
            runtimeNamespace,
        )
        val classesFile = extractVariant(stagedFile, fingerprint, variant)
        return HollowAddonCandidate(
            sourceFile = sourceFile.canonicalFile,
            sourceLength = sourceLength,
            sourceModifiedAt = sourceModifiedAt,
            artifactFile = stagedFile,
            classesFile = classesFile,
            fingerprint = fingerprint,
            descriptor = descriptor.copy(mappingNamespace = runtimeNamespace),
            requiresBootstrapLibraries = containsBootstrapLibraries(stagedFile),
            hasStartupScripts = containsStartupScripts(classesFile),
        )
    }

    private fun extractVariant(
        artifact: File,
        fingerprint: String,
        variant: HollowAddonVariant,
    ): File {
        val variantCacheKey = "${platform.id()}-${runtimeNamespace.id}"
        val outputDirectory = cacheRoot.resolve("variants").resolve(fingerprint).resolve(variantCacheKey)
        val outputFile = outputDirectory.resolve("classes.jar")
        JarFile(artifact).use { jar ->
            val entry = requireNotNull(jar.getJarEntry(variant.entryPath)) {
                "Addon variant '${variant.entryPath}' disappeared from ${artifact.name}"
            }
            if (outputFile.isFile && outputFile.length() == entry.size) return outputFile

            outputDirectory.mkdirs()
            val temporaryFile = outputDirectory.resolve("classes.jar.tmp")
            jar.getInputStream(entry).use { input ->
                Files.copy(input, temporaryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(
                    temporaryFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return outputFile
    }

    private fun containsBootstrapLibraries(file: File): Boolean = JarFile(file).use { jar ->
        jar.entries().asSequence().any { entry ->
            !entry.isDirectory &&
                entry.name.startsWith(AddonBootstrapContract.BOOTSTRAP_LIBRARY_PATH) &&
                entry.name.endsWith(".jar")
        }
    }

    private fun containsStartupScripts(file: File): Boolean = JarFile(file).use { jar ->
        val source = ".$STARTUP_SCRIPT_EXTENSION"
        val compiled = source + AddonScriptSource.COMPILED_SUFFIX
        jar.entries().asSequence().any { entry ->
            val name = entry.name
            !entry.isDirectory && (
                name.startsWith(AddonScriptSource.SOURCE_PREFIX) && name.endsWith(source) ||
                    name.startsWith(AddonScriptSource.COMPILED_PREFIX) && name.endsWith(compiled)
                )
        }
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
                }
                .toList()
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
