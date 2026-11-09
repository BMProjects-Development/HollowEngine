package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.TextFieldState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCaret
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import ru.hollowhorizon.hollowengine.common.scripting.ide.FileRename
import java.io.IOException

/** The name being typed for the declaration at [caret] of [path], whose text was [text] when F2 was pressed. */
internal class CodeRenameDialog(
    val path: String,
    val text: String,
    val caret: Int,
    val original: String,
    val x: Float,
    val y: Float,
) {
    var name by mutableStateOf(original)
}

/** F2 on a name in the code: renames the declaration and what refers to it through every script of the project. */
internal class HollowIdeCodeRename(
    private val model: HollowIdeModel,
    private val sessionOf: (String) -> HollowIdeEditorSession,
    private val editorOf: (String) -> TextFieldState?,
    private val changed: (HollowIdeOpenFile) -> Unit,
    private val setStatus: (String) -> Unit,
) {
    var dialog by mutableStateOf<CodeRenameDialog?>(null)
        private set

    /** Asks what the caret of [file] names, and opens the dialog at [x], [y] once it is known to be renamable. */
    fun start(file: HollowIdeOpenFile, caret: Int, x: Float, y: Float): Boolean {
        if (file.readOnly) return false
        val text = file.text
        sessionOf(file.path).resolveRenameTarget(text, caret) { target ->
            if (target == null) setStatus(LANG_UNAVAILABLE.lang)
            else dialog = CodeRenameDialog(file.path, text, caret, target.name, x, y)
        }
        return true
    }

    fun cancel() {
        dialog = null
    }

    fun confirm() {
        val open = dialog ?: return
        val name = open.name.trim()
        if (!NAME.matches(name)) {
            setStatus(LANG_INVALID.lang(name))
            return
        }
        dialog = null
        if (name == open.original) return
        val documents =
            model.files.values.filterNot { it.readOnly }.mapNotNull { file -> file.textOrNull?.let { file.path to it } }
                .toMap()
        sessionOf(open.path).rename(open.text, open.caret, name, documents) { renames -> apply(open, name, renames) }
    }

    private fun apply(open: CodeRenameDialog, name: String, renames: List<FileRename>) {
        if (renames.isEmpty()) {
            setStatus(LANG_NOTHING.lang(open.original))
            return
        }
        val label = UndoLabel(LANG_UNDO, open.original, name)
        val done = renames.filter { rename -> write(rename, label) }
        val skipped = renames.size - done.size
        val count = done.sumOf { it.edits.size }
        setStatus(
            if (skipped == 0) LANG_DONE.lang(count, done.size)
            else LANG_PARTLY.lang(count, done.size, skipped)
        )
    }

    /** Writes [rename] into the open editor of its file, or onto the disk; false when the text changed meanwhile. */
    private fun write(rename: FileRename, label: UndoLabel): Boolean {
        val next = rename.applied()
        val open = model.files[rename.path]
        if (open?.textOrNull != null) {
            if (open.readOnly || open.text.unixLines() != rename.text) return false
            editorOf(rename.path)?.let { editor ->
                editor.applyEdit(next, listOf(UiTextCaret(shifted(editor.caret, rename))), label)
            }
            model.updateText(rename.path, next)
            changed(open)
            return true
        }
        return try {
            val file = rename.path.fromReadablePath()
            val disk = file.readText()
            if (disk.unixLines() != rename.text) return false
            writeIdeFile(file.toPath(), (if ("\r\n" in disk) next.replace("\n", "\r\n") else next).toByteArray())
            true
        } catch (exception: IOException) {
            HollowEngine.LOGGER.error("Could not rename in {}", rename.path, exception)
            false
        }
    }

    /** Where [caret] lands once the edits of [rename] before it are made. */
    private fun shifted(caret: Int, rename: FileRename): Int =
        caret + rename.edits.filter { it.end <= caret }.sumOf { it.replacement.length - (it.end - it.start) }

    private fun String.unixLines() = replace("\r\n", "\n").replace('\r', '\n')

    private companion object {
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val LANG = "hollowengine.gui.ide.editor.rename"
        const val LANG_TITLE = "$LANG.title"
        const val LANG_UNAVAILABLE = "$LANG.unavailable"
        const val LANG_INVALID = "$LANG.invalid"
        const val LANG_NOTHING = "$LANG.nothing"
        const val LANG_DONE = "$LANG.done"
        const val LANG_PARTLY = "$LANG.partly"
        const val LANG_UNDO = "${UndoLabel.LANG}.code.rename"
    }

    /** The field the new name is typed in, at where F2 was pressed. */
    @Composable
    fun Dialog() {
        val open = dialog ?: return
        val field = remember(open) {
            TextFieldState(initialText = open.original).apply {
                setSelection(
                    0,
                    open.original.length
                )
            }
        }
        Popup(
            anchorBounds = UiRect(open.x, open.y, 0f, 0f),
            alignment = UiPopupAlignment.Cursor,
            id = "code-rename-dialog",
            tags = listOf("dropdown-popup", "project-name-dialog"),
            modal = true,
            onDismiss = ::cancel,
        ) {
            val focus = LocalUiFocusRequester.current
            LaunchedEffect(open) { focus.request(INPUT) }
            Text(LANG_TITLE.lang(open.original), tags = listOf("project-name-dialog-title"))
            TextField(
                value = open.name,
                onChange = { open.name = it },
                state = field,
                id = INPUT,
                tags = listOf("project-name-dialog-input"),
                modifier = Modifier.size(180.px, 24.px).onKeyInput { input ->
                    when (input.key) {
                        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> confirm()
                        GLFW.GLFW_KEY_ESCAPE -> cancel()
                        else -> return@onKeyInput
                    }
                    input.consume()
                },
            )
        }
    }
}

private const val INPUT = "code-rename-input"
