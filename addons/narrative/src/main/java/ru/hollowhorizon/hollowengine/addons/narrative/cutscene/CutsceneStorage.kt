package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object CutsceneStorage {
    const val ROOT_READABLE_PATH = "cutscenes"
    const val EXTENSION = "dat"

    private const val VERSION = CutsceneMigrations.CURRENT_VERSION
    private const val VERSION_KEY = "version"
    private const val NAME_KEY = "name"
    private const val DATA_KEY = "data"

    private val root: File
        get() = DirectoryManager.HOLLOW_ENGINE.resolve(ROOT_READABLE_PATH).toFile().apply {
            if (!exists()) mkdirs()
        }

    fun save(path: String, name: String, data: CutsceneData): File {
        val file = resolve(path, name)
        file.parentFile?.mkdirs()

        val cutscene = data.copy(name = normalizeName(name).ifBlank { data.name })
        val tag = CompoundTag().apply {
            putInt(VERSION_KEY, VERSION)
            putString(NAME_KEY, cutscene.name)
            put(DATA_KEY, NBTFormat.serialize(cutscene))
        }

        FileOutputStream(file).use { stream ->
            tag.save(DataOutputStream(stream))
        }
        return file
    }

    fun load(readablePath: String): CutsceneData {
        val file = resolveReadablePath(withExtension(readablePath))
        val tag = FileInputStream(file).use { it.loadAsNBT() } as? CompoundTag
            ?: error("Cutscene file is not a compound NBT tag: $readablePath")
        val payload = tag.get(DATA_KEY)
            ?: error("Cutscene file has no `$DATA_KEY` payload: $readablePath")
        return CutsceneMigrations.read(payload, tag.getInt(VERSION_KEY))
    }

    fun listFiles(): List<String> {
        val rootFile = root
        return rootFile.walk()
            .filter(File::isFile)
            .filter { it.extension.equals(EXTENSION, ignoreCase = true) }
            .map { it.relativeTo(rootFile).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    fun normalizeFileName(name: String): String {
        val trimmed = normalizeName(name)
        require(trimmed.isNotBlank()) { "Cutscene name must not be empty" }
        require(trimmed.none { it == '/' || it == '\\' }) { "Cutscene name must not contain path separators" }
        require(trimmed != "." && trimmed != "..") { "Cutscene name must not be a relative path marker" }
        return "$trimmed.$EXTENSION"
    }

    private fun normalizeName(name: String): String {
        return name.trim().replace('\\', '/').substringAfterLast('/').removeSuffix(".$EXTENSION")
    }

    private fun resolve(path: String, name: String): File {
        val folder = normalizeFolder(path)
        val fileName = normalizeFileName(name)
        val relative = if (folder.isBlank()) fileName else "$folder/$fileName"
        return resolveReadablePath(relative)
    }

    private fun resolveReadablePath(readablePath: String): File {
        val normalized = readablePath.trim().replace('\\', '/').removePrefix("$ROOT_READABLE_PATH/")
        require(normalized.isNotBlank()) { "Cutscene path must not be empty" }
        require(!File(normalized).isAbsolute) { "Cutscene path must be relative to hollowengine/$ROOT_READABLE_PATH" }

        val base = root.toPath().toAbsolutePath().normalize()
        val resolved = base.resolve(normalized).normalize()
        require(resolved.startsWith(base)) { "Cutscene path escapes hollowengine/$ROOT_READABLE_PATH: $readablePath" }
        return resolved.toFile()
    }

    private fun normalizeFolder(path: String): String {
        val normalized = path.trim()
            .replace('\\', '/')
            .removePrefix("$ROOT_READABLE_PATH/")
            .trim('/')
        if (normalized.isBlank()) return ""
        require(!File(normalized).isAbsolute) { "Cutscene folder must be relative" }
        normalized.split('/').forEach { segment ->
            require(segment.isNotBlank() && segment != "." && segment != "..") {
                "Cutscene folder contains an invalid segment: $path"
            }
        }
        return normalized
    }

    private fun withExtension(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        return if (normalized.endsWith(".$EXTENSION")) normalized else "$normalized.$EXTENSION"
    }
}
