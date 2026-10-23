package ru.hollowhorizon.hollowengine.common.scripting.deobf

import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remapJars
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.File
import java.util.jar.JarFile
import ru.hollowhorizon.hollowengine.api.ModList as PlatformModList

object NeoForgeEnvironmentSetup : EnvironmentSetup {
    override fun setup(mappings: Mappings, outputDir: File): List<File> {
        return setup(mappings, outputDir, ModList)
    }

    internal fun setup(mappings: Mappings, outputDir: File, mods: PlatformModList): List<File> {
        val classpath = runtimeClasspath()

        val minecraftClasspath = if (RuntimeFlags.production && !HollowEngineBuild.MINECRAFT_VERSION.startsWith("1.21")) {
            remapJars(
                mappings = mappings,
                inputs = listOf(mods.getFile("minecraft")),
                outputDir = outputDir,
                from = "official",
                to = "named",
            )
        } else {
            listOf(mods.getFile("minecraft"))
        }

        return (classpath + minecraftClasspath + mods.getFile("neoforge"))
            .distinctBy { it.absoluteFile.normalize() }
    }

    private fun runtimeClasspath(): List<File> {
        val obfuscatedMinecraftJar = Regex("""[/\\]versions[/\\][^/\\]+[/\\][^/\\]+\.jar$""")
        return launchClasspath()
            .asSequence()
            .filter(String::isNotBlank)
            .map(::File)
            .filter { it.exists() }
            .filterNot { isMinecraftGameJar(it) || obfuscatedMinecraftJar.containsMatchIn(it.absolutePath) }
            .distinctBy { it.absoluteFile.normalize() }
            .toList()
    }

    /**
     * A dedicated server hands the game libraries (brigadier, authlib, DFU...) to the bootstrap launcher as
     * `legacyClassPath` or a `legacyClassPath.file` listing, not as `java.class.path`, so all three are read.
     */
    private fun launchClasspath(): List<String> {
        val listed = System.getProperty("legacyClassPath.file")
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.readLines()
            .orEmpty()
        val legacy = System.getProperty("legacyClassPath").orEmpty().split(File.pathSeparator)
        val java = System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
        return (listed + legacy + java).map(String::trim)
    }

    private fun isMinecraftGameJar(file: File): Boolean {
        if (!file.isFile || !file.name.endsWith(".jar")) return false

        return try {
            JarFile(file).use { jar ->
                val mainClass = jar.manifest?.mainAttributes?.getValue("Main-Class") ?: return false

                when (mainClass) {
                    "net.minecraft.client.Main",
                    "net.minecraft.server.Main",
                    "net.minecraft.bundler.Main"
                        -> true

                    else -> false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isAvailable(): Boolean {
        return loadClass("net.neoforged.fml.loading.FMLLoader") != null ||
                loadClass("net.minecraftforge.fml.loading.FMLLoader") != null
    }
}

private fun loadClass(name: String): Class<*>? {
    return runCatching { Class.forName(name, false, Thread.currentThread().contextClassLoader) }.getOrNull()
        ?: runCatching { Class.forName(name, false, NeoForgeEnvironmentSetup::class.java.classLoader) }.getOrNull()
}
