package ru.hollowhorizon.hollowengine.common.scripting.deobf

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.utils.json.JsonFormat
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.io.path.exists

private val cacheMods = File("hollowengine/.cache/mods/").absoluteFile.apply {
    if (!exists()) this.mkdirs()
}.toPath()

internal fun collectModJars(file: File): List<File> {
    val mod = cacheMods.resolve(file.name)
    if (!mod.exists()) Files.copy(file.toPath(), mod)

    val jar = JarFile(file)
    val stream = JsonFormat.decodeFromStream<JarInJars>(
        jar.getInputStream(
            jar.getEntry("META-INF/jarjar/metadata.json") ?: return listOf(file)
        )
    )

    return stream.jars.flatMap {
        val embedMod = cacheMods.resolve(it.path.substringAfterLast("/"))
        val entry = jar.getInputStream(jar.getEntry(it.path))
        if (!embedMod.exists()) Files.copy(entry, embedMod)
        collectModJars(embedMod.toFile())
    } + mod.toFile()
}

@Serializable
class JarInJars(
    val jars: List<Jar>,
) {
    @Serializable
    class Jar(
        val identifier: Identifier,
        val version: Version,
        val path: String,
        val isObfuscated: Boolean = false,
    ) {
        @Serializable
        data class Identifier(val group: String, val artifact: String)

        @Serializable
        data class Version(val range: String, val artifactVersion: String)
    }
}