package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.inspector.Hint
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.inspector.NameRow
import ru.hollowhorizon.hollowengine.client.ui.inspector.Pills
import ru.hollowhorizon.hollowengine.client.ui.inspector.Section
import ru.hollowhorizon.hollowengine.client.ui.inspector.TextRow
import ru.hollowhorizon.hollowengine.client.ui.inspector.assetCompletions
import ru.hollowhorizon.hollowengine.client.ui.inspector.NumberRow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ExpressionEditing
import ru.hollowhorizon.hollowengine.common.playback.PlaybackAction
import ru.hollowhorizon.hollowengine.common.playback.PlaybackRule
import ru.hollowhorizon.hollowengine.common.playback.PlaybackSection

private const val SectionIcon = "hollowengine:textures/gui/icons/loops.svg"
private const val EventIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"

/** What a rule checked as a section starts may do, and one checked as it ends. */
private val BeforeActions = listOf(PlaybackAction.SKIP, PlaybackAction.JUMP)
private val AfterActions = listOf(PlaybackAction.REPEAT, PlaybackAction.JUMP)

/**
 * The inspector of an event or a section picked on its lane. Conditions are written in the language of the file,
 * which [conditions] knows; [eventHint] says how scripts reach events of this kind of file.
 */
internal fun playbackInspectorTarget(
    lanes: PlaybackLanes,
    pick: PlaybackPick,
    conditions: ExpressionEditing<*>,
    eventHint: String,
    onRemoved: () -> Unit,
): InspectorTarget = when (pick) {
    is PlaybackPick.Event -> InspectorTarget(
        id = "playback-event-${pick.index}",
        title = lanes.parts.events.getOrNull(pick.index)?.name.orEmpty(),
        icon = EventIcon,
        subtitle = playbackText("event"),
    ) { EventFields(lanes, pick.index, eventHint, onRemoved) }

    is PlaybackPick.Section -> InspectorTarget(
        id = "playback-section-${pick.id}",
        title = lanes.parts.sections.firstOrNull { it.id == pick.id }?.label() ?: pick.id,
        icon = SectionIcon,
        subtitle = playbackText("section"),
    ) { SectionFields(lanes, pick.id, conditions, onRemoved) }
}

@Composable
private fun EventFields(lanes: PlaybackLanes, index: Int, hint: String, onRemoved: () -> Unit) {
    val event = lanes.parts.events.getOrNull(index) ?: return Hint(playbackText("event_removed"))
    Column(tags = listOf("insp-body")) {
        Section(playbackText("event"), help = hint) {
            NameRow(playbackText("name"), event.name) { value -> lanes.editEvent(index) { it.copy(name = value) } }
            NumberRow(playbackText("time"), event.time, min = 0f) { value -> lanes.editEvent(index) { it.copy(time = value) } }
        }
        InspectorButton(playbackText("remove_event"), tags = listOf("danger")) {
            lanes.removeEvent(index)
            onRemoved()
        }
    }
}

@Composable
private fun SectionFields(
    lanes: PlaybackLanes,
    id: String,
    conditions: ExpressionEditing<*>,
    onRemoved: () -> Unit,
) {
    val parts = lanes.parts
    val section = parts.sections.firstOrNull { it.id == id } ?: return Hint(playbackText("section_removed"))
    val events = parts.events.map { it.name }.distinct()
    fun edit(change: (PlaybackSection) -> PlaybackSection) = lanes.editSection(id, change)

    Column(tags = listOf("insp-body")) {
        Section(playbackText("section"), help = playbackText("section_hint")) {
            NameRow(playbackText("name"), section.name.ifBlank { section.id }) { value -> edit { it.copy(name = value) } }
            NumberRow(playbackText("start"), section.start, min = 0f) { value -> edit { it.copy(start = value) } }
            NumberRow(playbackText("end"), section.end, min = 0f) { value -> edit { it.copy(end = value) } }
        }
        Rules(
            title = playbackText("before"),
            help = playbackText("before_hint"),
            key = "before",
            rules = section.before,
            actions = BeforeActions,
            events = events,
            conditions = conditions,
        ) { rules -> edit { it.copy(before = rules) } }
        Rules(
            title = playbackText("after"),
            help = playbackText("after_hint"),
            key = "after",
            rules = section.after,
            actions = AfterActions,
            events = events,
            conditions = conditions,
        ) { rules -> edit { it.copy(after = rules) } }
        InspectorButton(playbackText("remove_section"), tags = listOf("danger")) {
            lanes.removeSection(id)
            onRemoved()
        }
    }
}

/** One list of rules, the first whose condition holds deciding; [actions] are what a rule of it may do. */
@Composable
private fun Rules(
    title: String,
    help: String,
    key: String,
    rules: List<PlaybackRule>,
    actions: List<PlaybackAction>,
    events: List<String>,
    conditions: ExpressionEditing<*>,
    onChange: (List<PlaybackRule>) -> Unit,
) {
    Section(title, help = help) {
        rules.forEachIndexed { index, rule ->
            key(index) {
                Section("", boxed = true) {
                    Row(tags = listOf("insp-input-row")) {
                        Box(modifier = Modifier.grow(1f)) {
                            Pills(actions, rule.action, { playbackText("action_${it.name.lowercase()}") }) { action ->
                                onChange(rules.replacing(index, rule.copy(action = action)))
                            }
                        }
                        InspectorIconButton(RemoveIcon, playbackText("remove_rule")) {
                            onChange(rules.filterIndexed { at, _ -> at != index })
                        }
                    }
                    TextRow(
                        label = playbackText("condition"),
                        value = rule.condition,
                        id = "rule-$key-$index-condition",
                        completions = conditions.completions,
                        highlighter = conditions.highlighter,
                        diagnostics = conditions.diagnostics(rule.condition),
                        help = playbackText("condition_hint"),
                    ) { value -> onChange(rules.replacing(index, rule.copy(condition = value))) }
                    if (rule.action == PlaybackAction.JUMP) TextRow(
                        label = playbackText("jump_event"),
                        value = rule.event,
                        id = "rule-$key-$index-event",
                        completions = assetCompletions(events),
                    ) { value -> onChange(rules.replacing(index, rule.copy(event = value.trim()))) }
                }
            }
        }
        InspectorButton(playbackText("add_rule")) {
            onChange(rules + PlaybackRule(action = actions.first()))
        }
    }
}

private fun List<PlaybackRule>.replacing(index: Int, rule: PlaybackRule): List<PlaybackRule> =
    mapIndexed { at, existing -> if (at == index) rule else existing }
