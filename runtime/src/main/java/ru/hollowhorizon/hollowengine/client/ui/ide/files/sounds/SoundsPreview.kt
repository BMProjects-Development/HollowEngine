package ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.sounds.SoundDecoders
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewError
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeTextModel
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.inspector.NumberRow
import ru.hollowhorizon.hollowengine.client.ui.inspector.ToggleRow
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import java.io.File

internal const val SoundsFileName = "sounds.json"

private const val ListWidth = 220f
private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val SoundIcon = "hollowengine:textures/gui/icons/file_sound.png"

private fun key(name: String) = "hollowengine.gui.sounds_editor.$name"

internal fun String.isSoundsFile(): Boolean = substringAfterLast('/').equals(SoundsFileName, ignoreCase = true)

/** What the editor keeps while its tab is switched away. */
private class SoundsPreviewState {
    val model = HollowIdeTextModel(SoundEventsModel::parse)
    val list = LazyListState()
    val detailScroll = UiScrollHandle()
    var query by mutableStateOf("")
    var selected by mutableStateOf<SoundEvent?>(null)
}

/**
 * Master/detail editor for `sounds.json`, shown as the file's preview. Edits go back into the text,
 * which saves them; styling lives in `sounds-editor.hss`.
 */
@Composable
internal fun SoundsPreview(context: HollowIdePreviewContext) {
    val state = context.state { SoundsPreviewState() }
    val parsed = state.model.read(context.text)
    val model = parsed.getOrNull() ?: return HollowIdePreviewError(parsed.exceptionOrNull())
    val file = context.file

    val namespaceDir = file.path.substringBeforeLast('/', "")
    val namespace = namespaceDir.substringAfterLast('/').ifEmpty { "minecraft" }
    val availableSounds = remember(file.path) { scanAvailableSounds(namespaceDir, namespace) }

    val soundCompletions: UiCompletionContributor? = remember(availableSounds) {
        if (availableSounds.isEmpty()) null
        else UiCompletionContributor { completion ->
            val prefix = completion.text.take(completion.caret.coerceIn(0, completion.text.length))
            availableSounds.filter { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }
                .map { UiTextCompletion(label = it, insertText = it, icon = SoundIcon) }
        }
    }

    fun markChanged() {
        if (context.readOnly) {
            // Nothing is written, so the next read has to bring the fields back to what the file says.
            state.model.invalidate()
            return
        }
        val text = model.serialize()
        state.model.wrote(text)
        context.edit(text)
    }

    val current = state.selected?.takeIf { it in model.events } ?: model.events.firstOrNull()

    Column(
        tags = listOf("ide-file-panel", "sounds-editor-root"),
        modifier = Modifier.style(InspectorStylesheet).style("hollowengine:ui/styles/sounds-editor.hss").focusScope(),
    ) {
        Row(tags = listOf("sounds-editor-toolbar"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            Text(key("title").lang, tags = listOf("sounds-editor-title"), modifier = Modifier.grow(1f))
            Row(modifier = Modifier.gap(5.px).alignItems(vertical = UiAlign.CENTER)) {
                Box(tags = listOf("sounds-editor-status-dot") + if (file.dirty) listOf("dirty") else listOf("saved"))
                Text(
                    (if (file.dirty) key("unsaved") else key("saved")).lang,
                    tags = listOf("sounds-editor-status") + if (file.dirty) listOf("dirty") else emptyList(),
                )
            }
        }

        Row(modifier = Modifier.size(100.percent, 0.px).grow(1f).gap(6.px)) {
            Column(
                tags = listOf("sounds-editor-list"),
                modifier = Modifier.size(ListWidth.px, 100.percent),
            ) {
                TextField(
                    value = state.query,
                    onChange = { state.query = it },
                    placeholder = key("search").lang,
                    tags = listOf("sounds-editor-search"),
                    modifier = Modifier.size(100.percent, 22.px),
                )
                SoundsButton(key("add_event"), AddIcon, modifier = Modifier.size(100.percent, 24.px)) {
                    state.selected = model.addEvent()
                    markChanged()
                }
                val filtered =
                    model.events.filter { state.query.isBlank() || it.name.contains(state.query, ignoreCase = true) }
                if (filtered.isEmpty()) {
                    Text(key("no_events").lang, tags = listOf("sounds-editor-empty"))
                }
                LazyColumn(
                    tags = listOf("sounds-editor-list-scroll"),
                    modifier = Modifier.size(100.percent, 0.px).grow(1f),
                    state = state.list,
                    gap = 2f,
                ) {
                    items(filtered.size) { index ->
                        val event = filtered[index]
                        EventRow(
                            event = event,
                            selected = event === current,
                            onSelect = { state.selected = event },
                            onDelete = {
                                if (event === state.selected) state.selected = null
                                model.events.remove(event)
                                markChanged()
                            },
                        )
                    }
                }
            }

            Column(
                tags = listOf("sounds-editor-detail"),
                modifier = Modifier.size(0.px, 100.percent).grow(1f)
                    .scrollable(horizontal = false, state = state.detailScroll),
            ) {
                val event = current
                if (event == null) {
                    Text(key("placeholder").lang, tags = listOf("sounds-editor-placeholder"))
                } else {
                    EventDetail(
                        event = event,
                        soundCompletions = soundCompletions,
                        onChanged = ::markChanged,
                    )
                }
            }
        }
    }
}


@Composable
private fun EventRow(
    event: SoundEvent,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val tags = buildList {
        add("sounds-editor-event")
        if (selected) add("selected")
    }
    Row(
        tags = tags,
        modifier = Modifier.size(100.percent, 24.px).input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND).alignItems(vertical = UiAlign.CENTER).onClick { event2 ->
                if (event2.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onSelect()
                event2.consume()
            },
    ) {
        Text(
            event.name.ifBlank { key("unnamed").lang },
            tags = listOf("sounds-editor-event-label"),
            modifier = Modifier.grow(1f),
        )
        Text(key("event_sounds").lang.format(event.sounds.size), tags = listOf("sounds-editor-event-count"))
        SoundsIconButton(RemoveIcon, tags = listOf("sounds-editor-event-delete"), onClick = onDelete)
    }
}

@Composable
private fun EventDetail(
    event: SoundEvent,
    soundCompletions: UiCompletionContributor?,
    onChanged: () -> Unit,
) {
    LabeledField(key("name").lang) {
        TextField(
            value = event.name,
            onChange = { event.name = sanitizeEventName(it); onChanged() },
            placeholder = "block.custom.break",
            tags = listOf("sounds-editor-field"),
            modifier = Modifier.size(100.percent, 22.px),
        )
    }
    LabeledField(key("subtitle").lang) {
        TextField(
            value = event.subtitle,
            onChange = { event.subtitle = it; onChanged() },
            placeholder = "subtitles.block.custom.break",
            tags = listOf("sounds-editor-field"),
            modifier = Modifier.size(100.percent, 22.px),
        )
    }
    Row(
        tags = listOf("sounds-editor-toggle"),
        modifier = Modifier.size(100.percent, 20.px).alignItems(vertical = UiAlign.CENTER)
    ) {
        Text(key("replace").lang, tags = listOf("sounds-editor-label"), modifier = Modifier.grow(1f))
        Checkbox(
            checked = event.replace,
            variant = UiCheckboxVariant.SWITCH,
            onCheckedChange = { event.replace = it; onChanged() },
        )
    }

    Row(
        tags = listOf("sounds-editor-section"),
        modifier = Modifier.size(100.percent, 24.px).alignItems(vertical = UiAlign.CENTER)
    ) {
        Text(
            key("sounds").lang.format(event.sounds.size),
            tags = listOf("sounds-editor-section-title"),
            modifier = Modifier.grow(1f)
        )
        SoundsButton(key("add_sound"), AddIcon, modifier = Modifier.size(112.px, 22.px)) {
            event.sounds += SoundEntry()
            onChanged()
        }
    }

    event.sounds.forEachIndexed { index, sound ->
        SoundCard(
            sound = sound,
            index = index,
            soundCompletions = soundCompletions,
            onDelete = { event.sounds.remove(sound); onChanged() },
            onChanged = onChanged,
        )
    }
}

@Composable
private fun SoundCard(
    sound: SoundEntry,
    index: Int,
    soundCompletions: UiCompletionContributor?,
    onDelete: () -> Unit,
    onChanged: () -> Unit,
) {
    Column(tags = listOf("sounds-editor-sound-card")) {
        Row(modifier = Modifier.size(100.percent, 22.px).gap(4.px).alignItems(vertical = UiAlign.CENTER)) {
            TextField(
                value = sound.name,
                onChange = { sound.name = sanitizeSoundName(it); onChanged() },
                placeholder = "namespace:path",
                insight = UiCodeInsight(completions = soundCompletions),
                tags = listOf("sounds-editor-field"),
                modifier = Modifier.size(0.px, 22.px).grow(1f),
            )
            SoundsIconButton(RemoveIcon, tags = listOf("sounds-editor-sound-delete"), onClick = onDelete)
        }

        NumberRow(key("volume").lang, sound.volume, min = 0f, max = 1f, slider = true) {
            sound.volume = it; onChanged()
        }
        NumberRow(key("pitch").lang, sound.pitch, min = 0.5f, max = 2f, slider = true) { sound.pitch = it; onChanged() }

        Row(modifier = Modifier.size(100.percent, 22.px).gap(6.px).alignItems(vertical = UiAlign.CENTER)) {
            NumberRow(key("weight").lang, sound.weight, min = 1) { sound.weight = it; onChanged() }
            NumberRow(key("attenuation").lang, sound.attenuationDistance, min = 0) {
                sound.attenuationDistance = it; onChanged()
            }
        }

        Row(modifier = Modifier.size(100.percent, 20.px).gap(10.px).alignItems(vertical = UiAlign.CENTER)) {
            ToggleRow("stream", sound.stream) { sound.stream = it; onChanged() }
            ToggleRow("preload", sound.preload) { sound.preload = it; onChanged() }
            Text("${key("type").lang}:", tags = listOf("sounds-editor-label"))
            val typeId = "sounds-editor-type-$index"
            var typeOpen by remember { mutableStateOf(false) }
            UiDropdown(
                id = typeId,
                label = sound.type.jsonName,
                expanded = typeOpen,
                onExpandedChange = { typeOpen = it },
                items = SoundEntryType.entries.map { type ->
                    UiDropdownItem(
                        label = type.jsonName,
                        checked = sound.type == type,
                        onClick = { sound.type = type; onChanged() },
                    )
                },
                tags = listOf("sounds-editor-type"),
            )
        }
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(tags = listOf("sounds-editor-labeled")) {
        Text(label, tags = listOf("sounds-editor-label"))
        content()
    }
}

@Composable
private fun SoundsButton(labelKey: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Button(labelKey.lang, icon = icon, modifier = modifier, onClick = onClick)

@Composable
private fun SoundsIconButton(icon: String, tags: List<String>, onClick: () -> Unit) = IconButton(
    icon,
    tooltip = null,
    tags = listOf("inline", "danger") + tags,
    modifier = Modifier.size(16.px, 16.px),
    onClick = onClick
)

/** Enumerates audio files under `<namespace>/sounds/` as `namespace:path` ids for name suggestions. */
private fun scanAvailableSounds(namespaceDir: String, namespace: String): List<String> {
    if (namespaceDir.isEmpty()) return emptyList()
    val soundsRoot = runCatching { "$namespaceDir/sounds".fromReadablePath() }.getOrNull() ?: return emptyList()
    if (!soundsRoot.isDirectory) return emptyList()
    return soundsRoot.walkTopDown().filter { it.isFile && it.extension.lowercase() in SoundDecoders.extensions }
        .map { relativeSoundId(soundsRoot, it, namespace) }.distinct().sorted().toList()
}

private fun relativeSoundId(root: File, file: File, namespace: String): String {
    val relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    val withoutExtension = if (file.extension.lowercase() == "ogg") relative.substringBeforeLast('.') else relative
    return "$namespace:$withoutExtension"
}

/** Keeps event keys to the characters a Minecraft resource path accepts. */
private fun sanitizeEventName(input: String): String =
    input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "_./-" }

/** Same as [sanitizeEventName] but also allows the `:` namespace separator. */
private fun sanitizeSoundName(input: String): String =
    input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "_./-:" }
