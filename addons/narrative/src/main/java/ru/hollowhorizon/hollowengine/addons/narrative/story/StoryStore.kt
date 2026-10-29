package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.Util
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Writes a complete snapshot before replacing the last save, keeping a recoverable previous version. */
internal class StoryStore(private val file: Path) {
    private val backup = file.resolveSibling("${file.fileName}_old")

    fun read(): CompoundTag? {
        if (!Files.exists(file) && !Files.exists(backup)) return null
        return read(file) ?: read(backup)
    }

    private fun read(path: Path): CompoundTag? {
        if (!Files.exists(path)) return null
        return try {
            DataInputStream(Files.newInputStream(path)).use(NbtIo::read)
        } catch (failure: Exception) {
            HollowEngine.LOGGER.error("Cannot read stories from {}", path, failure)
            null
        }
    }

    fun write(tag: CompoundTag): Boolean {
        var temporary: Path? = null
        return try {
            Files.createDirectories(file.parent)
            temporary = Files.createTempFile(file.parent, "${file.fileName}-", ".tmp")
            Files.newOutputStream(temporary).use { tag.save(it) }
            Util.safeReplaceOrMoveFile(file, temporary, backup, false).also {
                if (!it) HollowEngine.LOGGER.error("Cannot replace story save {}", file)
            }
        } catch (failure: Exception) {
            HollowEngine.LOGGER.error("Cannot save stories to {}", file, failure)
            false
        } finally {
            temporary?.let { path ->
                runCatching { Files.deleteIfExists(path) }.onFailure {
                    HollowEngine.LOGGER.warn("Cannot remove temporary story save {}", path, it)
                }
            }
        }
    }
}
