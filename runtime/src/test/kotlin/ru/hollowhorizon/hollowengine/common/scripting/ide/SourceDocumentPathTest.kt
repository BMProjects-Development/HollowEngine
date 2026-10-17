package ru.hollowhorizon.hollowengine.common.scripting.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceDocumentPathTest {
    @Test
    fun `saved tab paths parse back to the document they were written for`() {
        val documents = listOf(
            SourceDocumentPath.ArchiveEntry("C:\\Users\\dev\\.gradle\\mod-1.0-sources.jar", "net/example/Mod.java"),
            SourceDocumentPath.ArchiveEntry("/usr/lib/jvm/lib/src.zip", "java.base/java/util/List.java"),
            SourceDocumentPath.SourceFile("C:\\Projects\\runtime\\src\\main\\java\\Node.kt"),
            SourceDocumentPath.Decompiled("net/minecraft/world/phys/Vec3"),
        )
        for (document in documents) assertEquals(document, SourceDocumentPath.parse(document.path))
    }

    @Test
    fun `virtual file system paths split at the archive separator only`() {
        assertEquals(
            SourceDocumentPath.ArchiveEntry("C:/libs/a-sources.jar", "pkg/A.kt"),
            SourceDocumentPath.ofLibraryFile("C:/libs/a-sources.jar!/pkg/A.kt"),
        )
        assertEquals(SourceDocumentPath.SourceFile("C:/src/A.kt"), SourceDocumentPath.ofLibraryFile("C:/src/A.kt"))
    }

    @Test
    fun `project files are not source documents`() {
        assertNull(SourceDocumentPath.parse("scripts/example.node.kts"))
        assertNull(SourceDocumentPath.parse("decompiled/notes.txt"))
    }
}
