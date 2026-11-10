package ru.hollowhorizon.hollowengine.client.gui.scripting

import ru.hollowhorizon.hollowengine.client.ui.ide.*
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.nio.file.Files
import kotlin.test.*

class HollowIdeModelTests {
    @Test
    fun `saving window tabs writes only their modified files including edits not yet reflected by the UI`() {
        val root = Files.createTempDirectory(DirectoryManager.HOLLOW_ENGINE, "ide-save-window-").toFile()
        val script = root.resolve("script.kts")
        val asset = root.resolve("asset.fixture")
        val other = root.resolve("other.kts")
        try {
            listOf(script, asset, other).forEach { it.writeText("initial") }
            val types = testFileTypes().apply {
                register(
                    HollowIdeFileType.extensions(
                        id = "tracked-fixture",
                        extensions = listOf("fixture"),
                        loader = { _, bytes -> TrackedDocument(bytes.decodeToString()) },
                        editor = {},
                    )
                )
            }
            val model = HollowIdeModel(types)
            fun open(name: String) = assertIs<HollowIdeOpenResult.File>(model.openFile("${root.name}/$name")).file
            val scriptTab = open(script.name)
            val assetTab = open(asset.name)
            val otherTab = open(other.name)
            scriptTab.update("script edit")
            assertIs<TrackedDocument>(assetTab.document).edit("asset edit")
            otherTab.update("other edit")
            assertFalse(assetTab.dirty)

            assertTrue(model.saveModifiedFiles(listOf(scriptTab.path, assetTab.path)))

            assertEquals("script edit", script.readText())
            assertEquals("asset edit", asset.readText())
            assertEquals("initial", other.readText())
            assertFalse(assetTab.document.isModified)
            assertTrue(otherTab.dirty)
        } finally {
            listOf(script, asset, other).forEach { it.delete() }
            root.delete()
        }
    }

    private class TrackedDocument(private var text: String) : HollowIdeFileDocument {
        override val readOnly = false
        override var isModified = false
            private set

        fun edit(text: String) {
            this.text = text
            isModified = true
        }

        override fun encode(): ByteArray = text.encodeToByteArray()

        override fun markSaved() {
            isModified = false
        }
    }

    @Test
    fun `focus selection keeps existing multi selection when target is selected`() {
        val model = HollowIdeModel(testFileTypes())
        val first = HollowIdeFileNode("first.kts", "first.kts", 0, false)
        val second = HollowIdeFileNode("second.kts", "second.kts", 0, false)

        model.select(first)
        model.select(second, additive = true)
        model.focusSelection(first)

        assertEquals("first.kts", model.selectedTreePath)
        assertEquals(listOf("first.kts", "second.kts"), model.selectedTreePaths.toList())
    }

    @Test
    fun `delete removes selected file without path provider crash`() {
        val root = DirectoryManager.HOLLOW_ENGINE.toFile().resolve("codex-delete-test-${System.nanoTime()}")
        val file = root.resolve("script.kts")
        try {
            root.mkdirs()
            file.writeText("val value = 1")

            val model = HollowIdeModel(testFileTypes())
            val path = "${root.name}/${file.name}"

            assertEquals(HollowIdeFileOperationResult.Success, model.delete(listOf(path)))
            assertFalse(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `refresh preserves expansion at every tree depth`() {
        val root = DirectoryManager.HOLLOW_ENGINE.toFile().resolve("codex-tree-test-${System.nanoTime()}")
        try {
            root.resolve("first/second/third").mkdirs()
            val treeRoot = HollowIdeFileNode(root.name, root.name, 0, true).apply {
                expanded = true
                refresh()
            }
            val first = treeRoot.children.single().apply {
                expanded = true
                refresh()
            }
            first.children.single().apply {
                expanded = true
                refresh()
            }

            treeRoot.refresh()

            val refreshedFirst = treeRoot.children.single()
            val refreshedSecond = refreshedFirst.children.single()
            assertTrue(refreshedFirst.expanded)
            assertTrue(refreshedSecond.expanded)
            assertEquals("third", refreshedSecond.children.single().name)
        } finally {
            root.deleteRecursively()
        }
    }
}
