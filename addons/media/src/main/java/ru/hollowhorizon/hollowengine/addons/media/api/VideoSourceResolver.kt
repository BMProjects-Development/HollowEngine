package ru.hollowhorizon.hollowengine.addons.media.api

import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.nio.file.Path

internal object VideoSourceResolver {
    private val networkSourcePattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.+")

    fun resolve(source: String): String =
        if (source.startsWith("resource:") || source.matches(networkSourcePattern)) source else resolve(Path.of(source.removePrefix("file:")))

    fun resolve(path: Path): String {
        val resolved = if (path.isAbsolute) path else DirectoryManager.HOLLOW_ENGINE.resolve(path)
        return resolved.toAbsolutePath().normalize().toString()
    }
}
