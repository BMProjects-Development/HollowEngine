package ru.hollowhorizon.hollowengine.common.scripting.ide

/**
 * Where a read-only document opened by navigation comes from. [path] is the editor path of its tab
 * and is written into the saved layout.
 */
sealed interface SourceDocumentPath {
    val path: String

    /** A library source file on disk, for example one under a module's `src/main/java`. */
    data class SourceFile(val file: String) : SourceDocumentPath {
        override val path: String get() = LibraryPrefix + file
    }

    /** An entry of a source archive: a `-sources.jar`, an attached zip or the JDK's `src.zip`. */
    data class ArchiveEntry(val archive: String, val entry: String) : SourceDocumentPath {
        override val path: String get() = LibraryPrefix + archive + ArchiveSeparator + entry
    }

    /** A class decompiled from bytecode. [internalName] is the JVM name of the outermost class. */
    data class Decompiled(val internalName: String) : SourceDocumentPath {
        override val path: String get() = "$DecompiledPrefix$internalName.java"
    }

    companion object {
        private const val LibraryPrefix = "library-sources/"
        private const val DecompiledPrefix = "decompiled/"
        private const val ArchiveSeparator = "!/"

        fun parse(path: String): SourceDocumentPath? = when {
            path.startsWith(LibraryPrefix) -> ofLibraryFile(path.removePrefix(LibraryPrefix))
            path.startsWith(DecompiledPrefix) && path.endsWith(".java") -> Decompiled(
                path.removePrefix(DecompiledPrefix).removeSuffix(".java")
            )

            else -> null
        }

        /** A path as a VFS spells it: `archive.jar!/entry` for an archive entry, a plain path otherwise. */
        fun ofLibraryFile(vfsPath: String): SourceDocumentPath {
            val separator = vfsPath.indexOf(ArchiveSeparator)
            return if (separator < 0) SourceFile(vfsPath)
            else ArchiveEntry(vfsPath.substring(0, separator), vfsPath.substring(separator + ArchiveSeparator.length))
        }
    }
}
