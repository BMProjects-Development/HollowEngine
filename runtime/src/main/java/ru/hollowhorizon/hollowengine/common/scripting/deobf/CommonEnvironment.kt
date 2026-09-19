package ru.hollowhorizon.hollowengine.common.scripting.deobf

import ru.hollowhorizon.hollowengine.common.files.CacheCleanup
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.deobf.CommonEnvironment.earlyClasspath
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.MappingsLoader
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remapJars
import ru.hollowhorizon.hollowengine.runtime.bootstrap.HollowEngineRuntimeBootstrap
import java.io.File
import java.net.URI
import java.util.jar.JarFile

object CommonEnvironment {
    private val outputDir = DirectoryManager.HOLLOW_ENGINE.resolve(".cache/deobf").toFile()
    private val modCopies = DirectoryManager.HOLLOW_ENGINE.resolve(".cache/mods").toFile()

    /** Classes the compiler dumps when debugging is on; they describe one session of compilations. */
    private val compilerDumps = DirectoryManager.HOLLOW_ENGINE.resolve(".cache/compiler").toFile()

    /** Remapped jars of [earlyClasspath], which only the launch that built them uses. */
    private val earlyOutputDir = DirectoryManager.HOLLOW_ENGINE.resolve(".cache/deobf-early").toFile()

    fun setup(compilerJar: File): Pair<Mappings, MutableList<File>> {
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
            outputDir.mkdirs()
        }
        if (earlyOutputDir.exists()) CacheCleanup.delete(earlyOutputDir)
        if (compilerDumps.exists()) CacheCleanup.report(compilerDumps, CacheCleanup.delete(compilerDumps))

        val mappings = loadMappings(compilerJar)
        val built = classpath(mappings, outputDir, ScriptingMods.requested(), ScriptingAddons.classpath())
        CompilerClasspathSnapshot.write(built.classpath)
        retainModCopies(built.classpath + built.modSources)
        return mappings to built.classpath.toMutableList()
    }

    /**
     * The classpath scripts compile against, built while mixins are prepared, before the game or any addon
     * has started.
     */
    fun earlyClasspath(compilerJar: File, mods: List<String>, addons: List<File>): List<File> {
        if (earlyOutputDir.exists()) CacheCleanup.delete(earlyOutputDir)
        return classpath(loadMappings(compilerJar), earlyOutputDir, mods, addons).classpath
    }

    private class Built(val classpath: List<File>, val modSources: List<File>)

    private fun classpath(mappings: Mappings, outputDir: File, mods: List<String>, addons: List<File>): Built {
        val classpath = setupPlatform(mappings, outputDir).toMutableList()
        resolveRuntimeJar()?.takeIf(File::isFile)?.let { runtimeJar ->
            if (!NeoForgeEnvironmentSetup.isAvailable()) {
                classpath += remapJars(mappings, listOf(runtimeJar), outputDir, from = "intermediary", to = "named")
            } else if (classpath.none { it.absoluteFile == runtimeJar.absoluteFile }) {
                classpath += runtimeJar
            }
        }

        val modsEnvironment = ModsEnvironment(mods)
        classpath += modsEnvironment.setup(mappings, outputDir)
        classpath += addons
        return Built(classpath.distinctBy { it.absoluteFile.normalize() }, modsEnvironment.sources)
    }

    private fun retainModCopies(used: List<File>) {
        val directory = modCopies.absoluteFile
        val names = used.filter { it.absoluteFile.parentFile == directory }.mapTo(HashSet(), File::getName)
        CacheCleanup.retain(directory, names)
    }

    /** The mappings shipped in the compiler addon. Reading them touches no game class. */
    fun loadMappings(compilerJar: File): Mappings {
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
