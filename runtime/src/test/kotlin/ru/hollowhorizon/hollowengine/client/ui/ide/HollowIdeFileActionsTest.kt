package ru.hollowhorizon.hollowengine.client.ui.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HollowIdeFileActionsTest {
    @Test
    fun `a read only file without a formatter drops save and reformat`() {
        val context = TestContext(openFile(readOnly = true), canFormat = false)

        val ids = fileContextMenuActions(context).map { it.id }

        assertFalse(HollowIdeStandardFileActions.SaveId in ids)
        assertFalse(HollowIdeStandardFileActions.ReformatId in ids)
        assertTrue(HollowIdeStandardFileActions.CopyPathId in ids)
    }

    @Test
    fun `save is only enabled while the file has unsaved changes`() {
        val file = openFile()
        val context = TestContext(file)
        val save = fileContextMenuActions(context).single { it.id == HollowIdeStandardFileActions.SaveId }

        assertFalse(save.isEnabled(context))
        file.markDirty()
        assertTrue(save.isEnabled(context))

        save.run(context)
        assertTrue(context.saved)
    }

    @Test
    fun `file type actions follow the standard ones behind a separator`() {
        var invoked = false
        val custom = HollowIdeFileAction(id = "reload-model", label = "Reload Model") { invoked = true }
        val context = TestContext(openFile(actions = listOf(custom)))

        val actions = fileContextMenuActions(context)
        val added = actions.last()

        assertEquals("reload-model", added.id)
        assertTrue(added.separatorBefore, "type actions open a group of their own")
        added.run(context)
        assertTrue(invoked)
    }

    @Test
    fun `a type replaces a standard action by declaring its own id`() {
        var reloaded = false
        val override = HollowIdeFileAction(id = HollowIdeStandardFileActions.SaveId, label = "Export") {
            reloaded = true
        }
        val context = TestContext(openFile(actions = listOf(override)))

        val actions = fileContextMenuActions(context)
        val save = actions.single { it.id == HollowIdeStandardFileActions.SaveId }

        assertEquals("Export", save.label)
        assertEquals(0, actions.indexOf(save), "it keeps the place of the action it replaces")
        save.run(context)
        assertTrue(reloaded)
        assertFalse(context.saved)
    }

    @Test
    fun `hidden type actions never reach the menu`() {
        val hidden = HollowIdeFileAction(id = "hidden", label = "Hidden", isVisible = { false }) {}
        val context = TestContext(openFile(actions = listOf(hidden)))

        assertFalse(fileContextMenuActions(context).any { it.id == "hidden" })
    }

    private fun openFile(
        readOnly: Boolean = false,
        actions: List<HollowIdeFileAction> = emptyList(),
    ): HollowIdeOpenFile {
        val type = HollowIdeFileType.extensions(
            id = "test-text",
            extensions = listOf(".kts"),
            actions = actions,
            loader = { _, bytes -> HollowIdeTextDocument(bytes.toString(Charsets.UTF_8)) },
            editor = {},
        )
        return HollowIdeOpenFile("scripts/demo.kts", type, HollowIdeTextDocument("val x = 1", readOnly))
    }

    private class TestContext(
        override val file: HollowIdeOpenFile,
        override val canFormat: Boolean = true,
    ) : HollowIdeFileActionContext {
        var saved = false
        var closed = false
        var lastStatus = ""

        override fun save(): Boolean {
            saved = true
            return true
        }

        override fun close() {
            closed = true
        }

        override fun closeOthers() = Unit
        override fun closeAll() = Unit
        override fun reformat() = Unit
        override fun revealInProjectView() = Unit
        override fun showInExplorer() = Unit
        override fun copyPath() = Unit

        override fun setStatus(message: String) {
            lastStatus = message
        }
    }
}
