package ru.hollowhorizon.hollowengine.common.scripting.deobf

import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remapJars
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.jar.JarFile

object NeoForgeEnvironmentSetup : EnvironmentSetup {
    override fun setup(mappings: Mappings, outputDir: File): List<File> {
        val classpath = runtimeClasspath()

        return if (RuntimeFlags.production && !HollowEngineBuild.MINECRAFT_VERSION.startsWith("1.21")) {
            val remapped = remapJars(
                mappings = mappings,
                inputs = listOf(ModList.getFile("minecraft")),
                outputDir = outputDir,
                from = "official",
                to = "named",
            )
            classpath + remapped
        } else {
            classpath + ModList.getFile("minecraft")
        }.distinctBy { it.absoluteFile.normalize() }
    }

    private fun runtimeClasspath(): List<File> {
        val obfuscatedMinecraftJar = Regex("""[/\\]versions[/\\][^/\\]+[/\\][^/\\]+\.jar$""")
        return System.getProperty("java.class.path")
            .orEmpty()
            .split(File.pathSeparator)
            .asSequence()
            .filter(String::isNotBlank)
            .map(::File)
            .filter { it.exists() }
            .filterNot { isMinecraftGameJar(it) || obfuscatedMinecraftJar.containsMatchIn(it.absolutePath) }
            .distinctBy { it.absoluteFile.normalize() }
            .toList()
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

private fun Class<*>.callStatic(name: String): Any? {
    val method = methods.firstOrNull { it.name == name && it.parameterCount == 0 && Modifier.isStatic(it.modifiers) }
        ?: declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 && Modifier.isStatic(it.modifiers) }
        ?: return null
    return runCatching {
        method.isAccessible = true
        method.invoke(null)
    }.getOrNull()
}

private fun Any.call(name: String): Any? {
    val method = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        ?: javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
        ?: return null
    return runCatching {
        method.isAccessible = true
        method.invoke(this)
    }.getOrNull()
}

private fun Any.member(name: String): Any? {
    call(name)?.let { return it }
    val getter = "get${name.replaceFirstChar { it.uppercase() }}"
    call(getter)?.let { return it }
    val field = javaClass.fields.firstOrNull { it.name == name }
        ?: javaClass.declaredFields.firstOrNull { it.name == name }
        ?: return null
    return runCatching {
        field.isAccessible = true
        field.get(this)
    }.getOrNull()
}

private fun Any?.flattenToFiles(): List<File> {
    return when (this) {
        null -> emptyList()
        is File -> listOf(this)
        is Path -> listOf(toFile())
        is String -> listOf(File(this))
        is Array<*> -> flatMap { it.flattenToFiles() }
        is Iterable<*> -> flatMap { it.flattenToFiles() }
        is Map<*, *> -> values.flatMap { it.flattenToFiles() }
        else -> emptyList()
    }
}
