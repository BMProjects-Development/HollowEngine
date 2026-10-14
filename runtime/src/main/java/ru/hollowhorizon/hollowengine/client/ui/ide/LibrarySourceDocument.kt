package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile

internal fun String.isVirtualIdePath(): Boolean = startsWith("resource://") ||
    startsWith("library-sources/") || startsWith("decompiled/")

/** Restores archive source tabs written by IDE versions that did not save read-only documents. */
internal fun readLibrarySource(path: String): String? {
    if (!path.startsWith("library-sources/")) return null
    val location = path.removePrefix("library-sources/")
    val separator = location.indexOf("!/")
    if (separator < 0) return null
    val archive = try {
        Path.of(location.substring(0, separator))
    } catch (_: InvalidPathException) {
        return null
    }
    if (!archive.isRegularFile()) return null
    return try {
        ZipFile(archive.toFile()).use { zip ->
            val entry = zip.getEntry(location.substring(separator + 2)) ?: return null
            if (entry.isDirectory) return null
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    } catch (failure: IOException) {
        HollowEngine.LOGGER.warn("Could not restore source tab '{}'", path, failure)
        null
    }
}
