package ru.hollowhorizon.hollowengine.client.ui.ide

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Readers, including live HSS reload, must never see a truncated autosave. */
internal fun writeIdeFile(path: Path, bytes: ByteArray, replaceExisting: Boolean = true) {
    val target = if (replaceExisting && Files.isSymbolicLink(path)) path.toRealPath() else path.toAbsolutePath()
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.write(temporary, bytes)
        if (!replaceExisting) {
            Files.move(temporary, target)
            return
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
