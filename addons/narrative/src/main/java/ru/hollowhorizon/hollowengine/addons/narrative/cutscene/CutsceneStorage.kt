package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File

object CutsceneStorage {
    const val ROOT_READABLE_PATH = "cutscenes"
    const val EXTENSION = "dat"

    private const val VERSION = CutsceneMigrations.CURRENT_VERSION

    private val root: File
        get() = DirectoryManager.HOLLOW_ENGINE.resolve(ROOT_READABLE_PATH).toFile().apply {
            if (!exists()) mkdirs()
        }

    fun save(path: String, name: String, data: CutsceneData): File {
        val file = resolve(path, name)
        file.parentFile?.mkdirs()

        val cutscene = data.copy(name = normalizeName(name).ifBlank { data.name })
        file.writeBytes(encode(cutscene))
        return file
    }

    fun encode(cutscene: CutsceneData): ByteArray {
        val tag = CompoundTag().apply {
            putInt("version", VERSION)
            putString("name", cutscene.name)
            put("data", NBTFormat.serialize(cutscene))
        }

        return ByteArrayOutputStream().also { tag.save(it) }.toByteArray()
    }

    fun load(readablePath: String): CutsceneData {
        val file = resolveReadablePath(withExtension(readablePath))
        return file.inputStream().buffered().let(::DataInputStream).use { decode(NbtIo.read(it), readablePath) }
    }

    fun decode(bytes: ByteArray, readablePath: String): CutsceneData =
        DataInputStream(ByteArrayInputStream(bytes)).use { decode(NbtIo.read(it), readablePath) }

    private fun decode(tag: CompoundTag, readablePath: String): CutsceneData {
        val payload = tag.get("data") ?: error("Cutscene file has no `data` payload: $readablePath")
        return CutsceneMigrations.read(payload, tag.getInt("version"))
    }

    fun normalizeFileName(name: String): String {
        val trimmed = normalizeName(name)
        require(trimmed.isNotBlank()) { "Cutscene name must not be empty" }
        require(trimmed.none { it == '/' || it == '\\' }) { "Cutscene name must not contain path separators" }
        require(trimmed != "." && trimmed != "..") { "Cutscene name must not be a relative path marker" }
        return "$trimmed.$EXTENSION"
    }

    private fun normalizeName(name: String): String {
        val base = name.trim().replace('\\', '/').substringAfterLast('/')
        return if (base.endsWith(".$EXTENSION", ignoreCase = true)) base.dropLast(EXTENSION.length + 1) else base
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
        val normalized = path.trim().replace('\\', '/').removePrefix("$ROOT_READABLE_PATH/").trim('/')
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
        return if (normalized.endsWith(".$EXTENSION", ignoreCase = true)) normalized else "$normalized.$EXTENSION"
    }
}
