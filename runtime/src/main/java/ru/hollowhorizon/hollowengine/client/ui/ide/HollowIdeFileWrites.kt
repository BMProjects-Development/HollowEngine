package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.common.files.writeProjectFile
import java.nio.file.Files
import java.nio.file.Path

/** Readers, including live HSS reload, must never see a truncated autosave. */
internal fun writeIdeFile(path: Path, bytes: ByteArray) =
    writeProjectFile(if (Files.isSymbolicLink(path)) path.toRealPath() else path.toAbsolutePath(), bytes)
