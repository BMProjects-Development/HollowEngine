package ru.hollowhorizon.hollowengine.common.files

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Write beside the destination so readers see a complete version of the file. */
internal fun writeProjectFile(target: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(target.parent, ".hollowengine-", ".tmp")
    try {
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
