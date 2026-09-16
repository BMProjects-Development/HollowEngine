package ru.hollowhorizon.hollowengine.common.scripting.deobf

import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.MappingsLoader
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remapJars
import ru.hollowhorizon.hollowengine.common.utils.isProduction
import ru.hollowhorizon.hollowengine.runtime.bootstrap.HollowEngineRuntimeBootstrap
import java.io.File
import java.net.URI
import java.util.jar.JarFile

object CommonEnvironment {
    private val outputDir = DirectoryManager.HOLLOW_ENGINE.resolve(".cache/deobf").toFile()

    fun setup(compilerJar: File): Pair<Mappings, MutableList<File>> {
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
            outputDir.mkdirs()
        }

        val mappings = setupMappings(compilerJar)

        val classpath = setupPlatform(mappings, outputDir).toMutableList()
        resolveRuntimeJar()?.takeIf(File::isFile)?.let { runtimeJar ->
            if(!NeoForgeEnvironmentSetup.isAvailable()) {
                classpath += remapJars(mappings, listOf(runtimeJar), outputDir, from = "intermediary", to = "named")
            } else {
                if (classpath.none { it.absoluteFile == runtimeJar.absoluteFile }) {
                    classpath += runtimeJar
                }
            }
        }

        if (isProduction) classpath += ModsEnvironment(*HollowEngineConfig.scriptingMods.toTypedArray()).setup(mappings, outputDir)

        return mappings to classpath
    }

    private fun setupMappings(compilerJar: File): Mappings {
        JarFile(compilerJar).use { jar ->
            val file = jar.getJarEntry("mappings-1.21.1.tiny")
            return MappingsLoader.loadMappings(jar.getInputStream(file))
        }
    }

    //@formatter:off
    fun setupPlatform(mappings: Mappings, outputDir: File): List<File> = when {
        NeoForgeEnvironmentSetup.isAvailable() -> NeoForgeEnvironmentSetup.setup(mappings, outputDir)
        else -> FabricEnvironmentSetup.setup(mappings, outputDir)
    }
    //@formatter:on

    /**
     * The jar the isolated runtime was actually loaded from.
     */
    internal fun resolveRuntimeJar(): File? {
        val anchor = HollowEngineRuntimeBootstrap::class.java
        val resource = anchor.classLoader?.getResource(anchor.name.replace('.', '/') + ".class") ?: return null
        if (resource.protocol != "jar") return null

        val jar = resource.path.substringBefore("!/")
        return runCatching { File(URI(jar)) }.getOrNull()?.takeIf(File::isFile)
    }
}
