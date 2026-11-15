package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileType
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileTypeRegistry
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeModel
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

    @Test
    fun `a server file uses the registered path and content matcher and stores binary edits`() {
        val path = "assets/test/graph.data"
        val registered = HollowIdeFileType(
            "addon-graph",
            matcher = { name, bytes -> name == path && bytes.firstOrNull() == 0.toByte() },
            loader = { name, bytes ->
                assertEquals(path, name)
                BinaryDocument(bytes)
            },
            editor = {},
        )
        val registry = HollowIdeFileTypeRegistry().apply { register(registered) }
        val model = HollowIdeModel(registry)
        val initial = byteArrayOf(0, 42)
        val remote = RemoteProjectDocument(1, path, "original")
        val project = RemoteProjectAnalysis(1) {}
        val file = assertNotNull(model.openRemote("remote-project://1/1/$path", initial, remote, project) { false })
        assertSame(registry.open(path) { initial }?.type, file.type)
        assertEquals(path, file.projectPath)
        assertNull(file.textOrNull)
        val document = file.document as BinaryDocument
        document.edit(byteArrayOf(0, 43))
        assertTrue(file.hasUnsavedChanges)
        assertFalse(file.dirty)

        val server = RemoteProjectFile("new", byteArrayOf(0, 44))
        remote.receive(file, server)
        assertSame(server, remote.conflict)
        assertContentEquals(byteArrayOf(0, 43), file.encode())
        remote.useServerVersion(file)
        assertNull(remote.conflict)
        assertContentEquals(server.bytes, file.encode())
        assertEquals(1L, file.externalTextRevision)
        assertFalse(file.hasUnsavedChanges)
    }

    @Test
    fun `save acknowledgement does not mark a newer edit saved`() {
        val file = file(RemoteProjectDocument(1, "scripts/story.kts", "old"))
        file.update("sent")
        val sent = file.encode()
        file.update("newer edit")
        file.markSavedIfContent(sent)
        assertTrue(file.hasUnsavedChanges)
        file.markSavedIfContent(file.encode())
        assertFalse(file.hasUnsavedChanges)
    }

    private class BinaryDocument(private var bytes: ByteArray) : HollowIdeFileDocument {
        override val readOnly = false
        override var isModified = false
            private set

        fun edit(next: ByteArray) {
            bytes = next
            isModified = true
        }

        override fun encode() = bytes
        override fun reload(bytes: ByteArray) {
            this.bytes = bytes
        }

        override fun markSaved() {
            isModified = false
        }
    }

    private fun file(remote: RemoteProjectDocument) = HollowIdeOpenFile(
        "remote-project://1/1/scripts/story.kts",
        type,
        HollowIdeTextDocument("original"),
        remote = remote
    )

    private fun update(text: String) = RemoteProjectFile("next", text.encodeToByteArray())
}
