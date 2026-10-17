package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback

import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.SegmentLane
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineSegment
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackLanes.Companion.MIN_SECTION
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.playback.PlaybackSection
import ru.hollowhorizon.hollowengine.common.playback.PlaybackSections
import ru.hollowhorizon.hollowengine.common.playback.TimelineEvent
import ru.hollowhorizon.hollowengine.common.utils.Color

/** What of a timeline steers its playback: the events, and the sections with their rules. */
internal data class PlaybackParts(val events: List<TimelineEvent>, val sections: List<PlaybackSection>)

/** An event or a section the author picked on its lane. */
internal sealed interface PlaybackPick {
    data class Event(val index: Int) : PlaybackPick
    data class Section(val id: String) : PlaybackPick
}

/**
 * The lanes of events and sections of a timeline that has them, as montages and effects do, with what their menus
 * offer and how their blocks are edited. The editor that owns the file says how to read and change them.
 */
internal class PlaybackLanes(
    private val read: () -> PlaybackParts,
    private val duration: () -> Float,
    private val edit: (label: String, mergeKey: String?, change: (PlaybackParts) -> PlaybackParts) -> Unit,
) {
    /** The events and sections as the file has them now. */
    val parts: PlaybackParts get() = read()

    /** Told when the author picks an event or a section on a lane, or lets go of it. */
    var onPick: ((PlaybackPick?) -> Unit)? = null

    val eventLane = SegmentLane("events", playbackText("lane_events"), markers = true)
    val sectionLane = SegmentLane("sections", playbackText("lane_sections"), exclusive = true)
    val lanes: List<SegmentLane> get() = listOf(sectionLane, eventLane)

    /** Answers the conditions while the editor plays the timeline, from the preview data. */
    var condition: (String) -> Boolean = { true }

    init {
        eventLane.isListed = read().events.isNotEmpty()
        sectionLane.isListed = read().sections.isNotEmpty()
        eventLane.onEdit = { segment -> segment.index()?.let { index -> editEvent(index) { it.copy(time = segment.start) } } }
        eventLane.onSelect = { segment -> onPick?.invoke(segment?.index()?.let(PlaybackPick::Event)) }
        eventLane.menu = ::eventMenu
        sectionLane.onEdit = { segment -> editSection(segment.id) { it.copy(start = segment.start, end = segment.end) } }
        sectionLane.onSelect = { segment -> onPick?.invoke(segment?.let { PlaybackPick.Section(it.id) }) }
        sectionLane.menu = ::sectionMenu
    }

    /** Puts the markers and blocks where the file has them. */
    fun refresh() {
        val parts = read()
        eventLane.segments.clear()
        parts.events.forEachIndexed { index, event ->
            eventLane.segments += TimelineSegment("$INDEXED$index", event.time, 0f, event.name, EventColor)
        }
        sectionLane.segments.clear()
        parts.sections.forEach { section ->
            sectionLane.segments += TimelineSegment(
                id = section.id,
                start = section.start,
                length = (section.end - section.start).coerceAtLeast(0f),
                label = section.label(),
                color = if (section.hasRules) RuledColor else SectionColor,
            )
        }
    }

    /**
     * Where the playhead gets to from [from], played on for [by] seconds through the sections, as the editor plays
     * the timeline; [condition] answers the rules.
     */
    fun advance(from: Float, by: Float): Float {
        val parts = read()
        val entering = from <= PlaybackSections.EPSILON
        return PlaybackSections.advance(parts.sections, parts.events, from, by, entering, condition).time
    }

    fun addEvent(time: Float) {
        eventLane.isListed = true
        edit(playbackKey("add_event"), null) { parts ->
            parts.copy(events = parts.events + TimelineEvent(time, freeName(parts.events.map { it.name }, "event")))
        }
    }

    fun editEvent(index: Int, change: (TimelineEvent) -> TimelineEvent) {
        edit(playbackKey("edit_event"), "event-$index") { parts ->
            parts.copy(events = parts.events.mapIndexed { at, event -> if (at == index) change(event) else event })
        }
    }

    fun removeEvent(index: Int) {
        edit(playbackKey("remove_event"), null) { parts ->
            parts.copy(events = parts.events.filterIndexed { at, _ -> at != index })
        }
    }

    /** Adds a section from [time], as long as the free time after it allows. */
    fun addSection(time: Float) {
        sectionLane.isListed = true
        edit(playbackKey("add_section"), null) { parts ->
            val start = time.coerceIn(0f, (duration() - MIN_SECTION).coerceAtLeast(0f))
            val next = parts.sections.filter { it.start >= start }.minOfOrNull { it.start } ?: duration()
            if (parts.sections.any { start >= it.start && start < it.end } || next - start < MIN_SECTION) return@edit parts
            val ids = parts.sections.map { it.id }
            val section = PlaybackSection(
                id = freeName(ids, "section"),
                start = start,
                end = minOf(start + NEW_SECTION, next),
            )
            parts.copy(sections = (parts.sections + section).sortedBy { it.start })
        }
    }

    fun editSection(id: String, change: (PlaybackSection) -> PlaybackSection) {
        edit(playbackKey("edit_section"), "section-$id") { parts ->
            parts.copy(sections = parts.sections.map { if (it.id == id) change(it).fitted() else it }.sortedBy { it.start })
        }
    }

    fun removeSection(id: String) {
        edit(playbackKey("remove_section"), null) { parts -> parts.copy(sections = parts.sections.filterNot { it.id == id }) }
    }

    private fun eventMenu(time: Float, segment: TimelineSegment?): List<UiDropdownItem> {
        val index = segment?.index() ?: return listOf(UiDropdownItem(playbackText("add_event")) { addEvent(time) })
        return listOf(UiDropdownItem(playbackText("remove_event")) { removeEvent(index) })
    }

    private fun sectionMenu(time: Float, segment: TimelineSegment?): List<UiDropdownItem> {
        if (segment == null) return listOf(UiDropdownItem(playbackText("add_section")) { addSection(time) })
        return listOf(UiDropdownItem(playbackText("remove_section")) { removeSection(segment.id) })
    }

    /** The section kept at least [MIN_SECTION] long, its end no earlier than its start. */
    private fun PlaybackSection.fitted(): PlaybackSection {
        val from = start.coerceAtLeast(0f)
        return copy(start = from, end = end.coerceAtLeast(from + MIN_SECTION))
    }

    private fun TimelineSegment.index(): Int? = id.removePrefix(INDEXED).toIntOrNull()

    private companion object {
        const val INDEXED = "at-"

        /** How long a section added by hand is, in seconds, before the author drags it out. */
        const val NEW_SECTION = 1f

        /** The shortest a section may be: rules of a shorter one would send the playhead nowhere. */
        const val MIN_SECTION = 0.05f

        val EventColor = Color("E06C75")
        val SectionColor = Color("5C6370")
        val RuledColor = Color("D19A66")
    }
}

/** How a section reads on its block: its name, or the id it got when added. */
internal fun PlaybackSection.label(): String = name.ifBlank { id }

/** [base] with the first number after it that none of [taken] has. */
private fun freeName(taken: List<String>, base: String): String {
    var index = taken.size + 1
    while ("$base$index" in taken) index++
    return "$base$index"
}

internal fun playbackKey(name: String): String = "hollowengine.gui.playback.$name"

internal fun playbackText(name: String): String = playbackKey(name).lang
