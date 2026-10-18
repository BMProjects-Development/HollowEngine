package ru.hollowhorizon.hollowengine.client.ui.screen

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeEditorSession
import ru.hollowhorizon.hollowengine.client.ui.widgets.EditableTextField
import ru.hollowhorizon.hollowengine.client.ui.widgets.TextFieldState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiInlayHintsProvider
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextFieldMode

@Composable
internal fun HollowUiEditorDemo(
    keyLog: String,
    onKeyLog: (String) -> Unit,
) {
    // Each editor edits its own document, so each one needs its own analysis session.
    var textFieldRevision by remember { mutableStateOf(0L) }
    val textFieldSession = remember {
        HollowIdeEditorSession(EditorDemoPath) { textFieldRevision++ }
    }
    var editableRevision by remember { mutableStateOf(0L) }
    val editableSession = remember {
        HollowIdeEditorSession(EditorDemoPath) { editableRevision++ }
    }
    DisposableEffect(textFieldSession, editableSession) {
        onDispose {
            textFieldSession.close()
            editableSession.close()
        }
    }
    var codeText by remember { mutableStateOf(EditorDemoNodeText) }
    val textFieldInlays = remember(textFieldSession) {
        UiInlayHintsProvider { text -> textFieldSession.inlayHints(text) }
    }
    val editableInlays = remember(editableSession) {
        UiInlayHintsProvider { text -> editableSession.inlayHints(text) }
    }
    val diagnostics = textFieldSession.diagnostics(codeText)

    Box(tags = listOf("editor-demo-stage"), modifier = Modifier.scrollable()) {
        Column(tags = listOf("editor-demo-card"), modifier = Modifier.position(0.px, 0.px)) {
            Text("TextField editor mode", tags = listOf("card-title"))
            TextField(
                value = codeText,
                mode = UiTextFieldMode.MULTI_LINE,
                multiCaret = true,
                onChange = { text ->
                    codeText = text
                    textFieldSession.requestAnalysis(text, text.length)
                },
                syntaxHighlighter = textFieldSession.highlighter,
                completionContributor = textFieldSession.completions,
                diagnostics = diagnostics,
                inlayHintsProvider = textFieldInlays,
                inlayRevision = textFieldRevision,
                placeholder = "Type code here",
                tags = listOf("editor-text-field"),
                modifier = Modifier.size(520.px, 210.px)
                    .textWrap(false)
                    .onKeyInput { input ->
                        if (input.key != GLFW.GLFW_KEY_F2) return@onKeyInput
                        onKeyLog("F2 captured before the default text-field keymap")
                        input.consume()
                    }
            )
            Text(keyLog, tags = listOf("editor-key-log"))
        }

        Column(tags = listOf("editor-demo-card", "lazy-column-card"), modifier = Modifier.position(552.px, 0.px)) {
            Text("LazyColumn", tags = listOf("card-title"))
            LazyColumn(tags = listOf("lazy-column-demo"), gap = 5f) {
                items(10_000) { index ->
                    Row(tags = listOf("lazy-list-row")) {
                        Text((index + 1).toString().padStart(5, '0'), tags = listOf("lazy-row-index"))
                        Text("Virtualized row ${index + 1}", tags = listOf("body"))
                    }
                }
            }
        }

        Column(tags = listOf("editor-demo-card", "lazy-row-card"), modifier = Modifier.position(0.px, 282.px)) {
            Text("LazyRow", tags = listOf("card-title"))
            LazyRow(tags = listOf("lazy-row-demo"), gap = 8f) {
                items(10_000) { index ->
                    Column(tags = listOf("lazy-row-tile")) {
                        Text("#${index + 1}", tags = listOf("lazy-row-tile-title"))
                        Text("tile", tags = listOf("body"))
                    }
                }
            }
        }

        Column(tags = listOf("editor-demo-card"), modifier = Modifier.position(552.px, 282.px)) {
            Text("EditableTextField demo.node.kts", tags = listOf("card-title"))
            val editableState = remember {
                TextFieldState(
                    initialText = EditorDemoNodeText,
                    multiline = true,
                    indentSize = 4,
                    autoPairs = true,
                    multiCaret = true,
                )
            }
            Text(
                if (editableState.wrap) "wrap: on (click to toggle)" else "wrap: off (click to toggle)",
                tags = listOf("body"),
                modifier = Modifier.onClick { editableState.wrap = !editableState.wrap },
            )
            EditableTextField(
                state = editableState,
                tags = listOf("editable-text-field"),
                syntaxHighlighter = editableSession.highlighter,
                inlayHintsProvider = editableInlays,
                inlayRevision = editableRevision,
                modifier = Modifier.size(500.px, 180.px)
                    .background(UiColor(0.1f, 0.11f, 0.13f, 1f))
                    .padding(6.px),
            )
        }
    }
}

private const val EditorDemoPath = "demo.node.kts"

private val EditorDemoNodeText = """
// virtual file: demo.node.kts
node("hollow:demo") {
    val title = "Training Dummy"
    val a = 1
    transform {
        position(0.0, 1.5, 0.0)
        rotation(0.0, 180.0, 0.0)
    }
    behavior {
        repeat(3) { index ->
            say(title + " #" + index + a)
        }
    }
}
""".trimIndent()
