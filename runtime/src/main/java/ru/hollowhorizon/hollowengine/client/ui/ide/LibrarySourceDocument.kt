package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.scripting.ide.SourceDocumentPath
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal fun String.isVirtualIdePath(): Boolean = startsWith("resource://") || SourceDocumentPath.parse(this) != null

/**
 * Reads a library source tab back from disk. A decompiled class has no file to read: its text comes from
 * the language that produced it, see [HollowIdeEditorSession.loadDocument].
 */
internal fun readSourceDocument(document: SourceDocumentPath): String? = try {
    when (document) {
        is SourceDocumentPath.SourceFile -> Path.of(document.file).takeIf { it.isRegularFile() }?.readText()
        is SourceDocumentPath.ArchiveEntry -> {
            val archive = Path.of(document.archive)
            if (!archive.isRegularFile()) null else ZipFile(archive.toFile()).use { zip ->
                zip.getEntry(document.entry)?.takeUnless { it.isDirectory }
                    ?.let { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }
            }
        }
        is SourceDocumentPath.Decompiled -> null
    }
} catch (_: InvalidPathException) {
    null
} catch (failure: IOException) {
    HollowEngine.LOGGER.warn("Could not restore source tab '{}'", document.path, failure)
    null
}
