package ru.hollowhorizon.hollowengine.client.ui.ide

import java.nio.file.Files
import java.nio.file.Path

/** Coordinates disk access by autosave and the client thread against the last accepted contents. */
internal class HollowIdeTextFileSync(
    private val path: Path,
    initialContent: ByteArray,
) {
    private var content = initialContent
    private var externalRevision = 0L

    @Synchronized
    fun revision(): Long = externalRevision

    /** External changes win even when the editor still has a pending local edit. */
    @Synchronized
    fun reloadIfChanged(reload: (ByteArray) -> Unit): Boolean {
        val current = Files.readAllBytes(path)
        if (content.contentEquals(current)) return false
        reload(current)
        content = current
        externalRevision++
        return true
    }

    /** A delayed save cannot write through a reload or replace an unobserved external edit. */
    @Synchronized
    fun save(bytes: ByteArray, revision: Long, checkActive: () -> Unit = {}): Boolean {
        checkActive()
        if (revision != externalRevision || !content.contentEquals(Files.readAllBytes(path))) return false
        checkActive()
        writeIdeFile(path, bytes)
        content = bytes
        return true
    }
}
