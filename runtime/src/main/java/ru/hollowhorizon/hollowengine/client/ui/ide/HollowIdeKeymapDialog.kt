package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowInputKind
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyAction
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyChord
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeymap
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.notification.HollowNotifications
import ru.hollowhorizon.hollowengine.client.ui.widgets.Button
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiButtonKind
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.config.Config
import java.io.File

private const val LANG = "hollowengine.gui.keymap"
private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val ResetIcon = "hollowengine:textures/gui/icons/undo.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/cross.svg"
private const val CaptureId = "ide-keymap-capture"
private const val MaxHeightShare = 0.8f
private const val DialogChromeHeight = 120f
private const val MinListHeight = 120f

/** Which binding of which action the next key press becomes; [replacing] null adds one. */
private data class KeymapCapture(val action: HollowKeyAction, val replacing: HollowKeyChord?)

/**
 * Every action of the editor with its chords: a click on a chord waits for a new one, the cross removes it, and the
 * plus adds another. Chords another action of the same part of the editor already uses are marked.
 */
@Composable
internal fun HollowIdeKeymapDialog(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    var query by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf<KeymapCapture?>(null) }
    val focus = LocalUiFocusRequester.current
    val startCapture: (KeymapCapture) -> Unit = { next ->
        capture = next
        focus.request(CaptureId)
    }

    val viewport = LocalUiViewport.current
    Popup(
        anchorBounds = viewport,
        alignment = CenteredOnViewport,
        id = "ide-keymap-dialog-popup",
        tags = listOf("dropdown-popup", "ide-keymap-dialog"),
        layer = 100,
        modal = true,
        onDismiss = onDismiss,
    ) {
        Column(
            tags = listOf("ide-keymap-content"),
            modifier = Modifier.size(520.px, UiLength.Fit),
        ) {
            Row(
                tags = listOf("ide-keymap-header"),
                modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(vertical = UiAlign.CENTER)
            ) {
                Text("$LANG.title".lang, tags = listOf("ide-keymap-title"), modifier = Modifier.grow(1f))
                Button("$LANG.import".lang, id = "ide-keymap-import", onClick = ::importKeymap)
                Button("$LANG.export".lang, id = "ide-keymap-export", onClick = ::exportKeymap)
                Button("$LANG.reset_all".lang, id = "ide-keymap-reset-all", kind = UiButtonKind.DANGER) {
                    HollowKeymap.resetAll()
                }
            }
            SearchField(query) { query = it }
            LaunchedEffect(Unit) { focus.request("ide-keymap-search") }
            Column(
                tags = listOf("ide-keymap-list"),
                modifier = Modifier.size(100.percent, UiLength.Fit).maxSize(
                        height = (viewport.height * MaxHeightShare - DialogChromeHeight).coerceAtLeast(MinListHeight).px
                    ).scrollable(horizontal = false, hasHorizontalScrollbar = false),
            ) {
                HollowKeymap.actions.filter { it.matchesQuery(query) }.groupBy { it.context }
                    .forEach { (context, actions) ->
                        Text(context.label, tags = listOf("ide-keymap-group"))
                        actions.forEach { action ->
                            key(action.id) { ActionRow(action, capture, startCapture) { capture = null } }
                        }
                    }
            }
            Text(
                "$LANG.hint".lang,
                tags = listOf("ide-keymap-hint"),
                modifier = Modifier.size(100.percent, UiLength.Fit)
            )
        }
    }
}

/** The search, drawn the way a tree's filter is: a field with a magnifier, and a cross once something is typed. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(tags = listOf("tree-filter-row")) {
        Image(SearchIcon, tags = listOf("tree-filter-icon"))
        TextField(
            value = query,
            onChange = onChange,
            placeholder = "$LANG.search".lang,
            id = "ide-keymap-search",
            tags = listOf("tree-filter-input"),
            modifier = Modifier.grow(1f),
        )
        if (query.isNotEmpty()) {
            IconButton(RemoveIcon, "$LANG.clear".lang, tags = listOf("inline", "tree-filter-close")) { onChange("") }
        }
    }
}

@Composable
private fun ActionRow(
    action: HollowKeyAction,
    capture: KeymapCapture?,
    onCapture: (KeymapCapture) -> Unit,
    onCaptureEnd: () -> Unit,
) {
    val changed = HollowKeymap.isChanged(action)
    Row(
        tags = listOfNotNull("ide-keymap-row", "changed".takeIf { changed }),
        modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(vertical = UiAlign.CENTER),
    ) {
        Text(action.label, tags = listOf("ide-keymap-label"), modifier = Modifier.size(0.px, UiLength.Fit).grow(1f))
        action.bindings.forEach { chord ->
            if (capture?.action == action && capture.replacing == chord) {
                CaptureChip(capture, onCaptureEnd)
            } else {
                ChordChip(action, chord) { onCapture(KeymapCapture(action, chord)) }
            }
        }
        if (capture?.action == action && capture.replacing == null) CaptureChip(capture, onCaptureEnd)
        IconButton(AddIcon, "$LANG.add".lang, id = "ide-keymap-add-${action.id}", tags = listOf("inline")) {
            onCapture(KeymapCapture(action, null))
        }
        if (changed) {
            IconButton(ResetIcon, "$LANG.reset".lang, id = "ide-keymap-reset-${action.id}", tags = listOf("inline")) {
                HollowKeymap.reset(action)
            }
        }
    }
}

@Composable
private fun ChordChip(action: HollowKeyAction, chord: HollowKeyChord, onReplace: () -> Unit) {
    val conflicts = HollowKeymap.conflicts(action, chord)
    Row(
        id = "ide-keymap-chord-${action.id}-$chord",
        tags = listOfNotNull("ide-keymap-chord", "conflict".takeIf { conflicts.isNotEmpty() }),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .alignItems(vertical = UiAlign.CENTER).tooltipOnHover(
                if (conflicts.isEmpty()) "$LANG.replace".lang
                else "$LANG.conflict".lang(conflicts.joinToString(", ") { it.label }),
            ).onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onReplace()
                event.consume()
            },
    ) {
        Text(chord.displayText, tags = listOf("ide-keymap-chord-text"))
        IconButton(RemoveIcon, "$LANG.remove".lang, tags = listOf("inline", "ide-keymap-chord-remove")) {
            HollowKeymap.rebind(action, action.bindings - chord)
        }
    }
}

/**
 * Takes the next chord: a key with what is held, or, for actions that take them, a mouse button pressed over it or
 * the wheel turned over it. Escape or the focus leaving gives up; the modifier keys alone only wait.
 */
@Composable
private fun CaptureChip(capture: KeymapCapture, onEnd: () -> Unit) {
    val action = capture.action
    fun take(chord: HollowKeyChord) {
        val current = action.bindings
        val next = capture.replacing?.let { old -> current.map { if (it == old) chord else it } } ?: (current + chord)
        HollowKeymap.rebind(action, next)
        onEnd()
    }
    Box(
        id = CaptureId,
        tags = listOf("ide-keymap-chord", "capturing"),
        modifier = Modifier.focus().input(hoverable = true, clickable = true).onUnfocus { onEnd() }
            .onKeyInput(priority = 100) { input ->
                input.consume()
                when {
                    input.repeat -> Unit
                    HollowKeyChord.isModifierKey(input.key) ->
                        if (HollowInputKind.HOLD in action.kinds) take(HollowKeyChord.key(input.key))
                    input.key == GLFW.GLFW_KEY_ESCAPE && input.modifiers == 0 -> onEnd()
                    HollowInputKind.KEY in action.kinds || HollowInputKind.HOLD in action.kinds -> take(HollowKeyChord.key(input.key, input.modifiers))
                }
            }.onPress { event ->
                event.consume()
                if (HollowInputKind.MOUSE in action.kinds && event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    take(HollowKeyChord.mouse(event.button, event.modifiers))
                }
            }.onScroll { event ->
                event.consume()
                if (HollowInputKind.WHEEL in action.kinds) take(HollowKeyChord.wheel(currentUiKeyModifiers()))
            },
    ) {
        Text("$LANG.press".lang, tags = listOf("ide-keymap-chord-text"))
    }
}

private fun HollowKeyAction.matchesQuery(query: String): Boolean {
    val text = query.trim()
    if (text.isEmpty()) return true
    return label.contains(text, ignoreCase = true) || bindings.any { it.displayText.contains(text, ignoreCase = true) }
}

private fun exportKeymap() = Thread({
    val suggested = File(Config.CONFIG_DIR.toFile(), "hollowengine/keymap-export.json")
    val target = NativeFileDialogs.saveJson("$LANG.export".lang, suggested) ?: return@Thread
    Minecraft.getInstance().execute { HollowKeymap.export(target.toPath()) }
}, "HollowEngine keymap export").apply { isDaemon = true }.start()

private fun importKeymap() = Thread({
    val source = NativeFileDialogs.openJson("$LANG.import".lang, Config.CONFIG_DIR.toFile()) ?: return@Thread
    Minecraft.getInstance().execute {
        if (!HollowKeymap.import(source.toPath())) HollowNotifications.error("$LANG.import_failed".lang(source.name))
    }
}, "HollowEngine keymap import").apply { isDaemon = true }.start()
