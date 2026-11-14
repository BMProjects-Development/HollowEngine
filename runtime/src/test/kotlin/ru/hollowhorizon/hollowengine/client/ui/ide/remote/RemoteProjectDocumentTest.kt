package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileType
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeTextDocument
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectFile
import kotlin.test.*

class RemoteProjectDocumentTest {
    private val type = HollowIdeFileType.fallback(
        "text",
        matcher = { _, _ -> true },
        loader = { _, bytes -> HollowIdeTextDocument(bytes.decodeToString()) },
        editor = {})

    @Test
    fun `a server update replaces clean text and invalidates the displayed editor text`() {
        val remote = RemoteProjectDocument(1, "scripts/story.kts", "previous")
        val file = file(remote)
        remote.receive(file, update("other player"))
        assertEquals("other player", file.text)
        assertEquals(1L, file.externalTextRevision)
        assertEquals("next", remote.revision)
        assertFalse(file.dirty)
    }

    @Test
    fun `an update during editing retains both versions until the user resolves the conflict`() {
        val remote = RemoteProjectDocument(1, "scripts/story.kts", "previous")
        val file = file(remote)
        file.update("my edits")
        val latest = update("other player")
        remote.receive(file, latest)
        assertEquals("my edits", file.text)
        assertEquals("previous", remote.revision)
        assertSame(latest, remote.conflict)
        assertTrue(file.dirty)

        remote.useLatestRevision()
        assertNull(remote.conflict)
        assertEquals("my edits", file.text)
        assertEquals("next", remote.revision)
        assertTrue(file.dirty)
    }

    private fun file(remote: RemoteProjectDocument) = HollowIdeOpenFile(
        "remote-project://1/1/scripts/story.kts",
        type,
        HollowIdeTextDocument("original"),
        remote = remote
    )

    private fun update(text: String) = RemoteProjectFile("next", text.encodeToByteArray())
}
