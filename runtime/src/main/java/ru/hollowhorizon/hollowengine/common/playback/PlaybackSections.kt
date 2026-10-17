package ru.hollowhorizon.hollowengine.common.playback

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A named moment of a timeline, for scripts to act on and for rules to jump to. */
@Serializable
data class TimelineEvent(
    val time: Float,
    val name: String,
)

/** Where a rule sends the playhead. */
@Serializable
enum class PlaybackAction {
    /** Past the section, to its end: a rule checked when the section starts. */
    SKIP,

    /** Back to the start of the section: a rule checked when it ends. */
    REPEAT,

    /** To the event named by the rule. */
    JUMP,
}

/** What happens when [condition] holds; a blank condition always does. */
@Serializable
data class PlaybackRule(
    val condition: String = "",
    val action: PlaybackAction,
    val event: String = "",
)

/**
 * A stretch of a timeline with rules on where playback goes from it. [before] is checked as playback enters the
 * section at its start, [after] as it reaches the end; the first rule whose condition holds decides, and with none
 * playback goes on. Sections do not overlap.
 */
@Serializable
data class PlaybackSection(
    val id: String,
    val name: String = "",
    val start: Float = 0f,
    val end: Float = 1f,
    val before: List<PlaybackRule> = emptyList(),
    val after: List<PlaybackRule> = emptyList(),
) {
    val hasRules: Boolean get() = before.isNotEmpty() || after.isNotEmpty()

    /** Every condition of the section, for compiling them ahead of playback. */
    fun conditions(): List<String> = (before + after).map { it.condition }.filter { it.isNotBlank() }
}

/** Where [PlaybackSections.advance] left the playhead, and whether any rule was asked on the way. */
class PlaybackStep(val time: Float, val decided: Boolean)

/**
 * Plays a timeline through its sections. Playback is a function of where it started and how far it has gone, with
 * the rules asked at each boundary on the way.
 */
object PlaybackSections {
    /**
     * Plays from [from] for [by] seconds and says where it got to. [entering] says the playhead has just arrived at
     * [from]. [condition] answers a rule's condition; [onSpan] is told each stretch played, in order, with whether it starts
     * where a rule landed the playhead rather than where it played to.
     */
    fun advance(
        sections: List<PlaybackSection>,
        events: List<TimelineEvent>,
        from: Float,
        by: Float,
        entering: Boolean,
        condition: (String) -> Boolean,
        onSpan: (from: Float, to: Float, landed: Boolean) -> Unit = { _, _, _ -> },
    ): PlaybackStep {
        val ruled = sections.filter { it.hasRules && it.end - it.start > EPSILON }
        var left = by.coerceAtLeast(0f)
        if (ruled.isEmpty()) {
            onSpan(from, from + left, entering)
            return PlaybackStep(from + left, false)
        }

        fun holds(rule: PlaybackRule) = rule.condition.isBlank() || condition(rule.condition)
        fun eventTime(name: String) = events.firstOrNull { it.name == name }?.time

        var at = from
        var entry = entering
        var landed = entering
        var decided = false
        var jumps = 0
        while (true) {
            if (entry && jumps < MAX_JUMPS) {
                val entered = ruled.firstOrNull { it.before.isNotEmpty() && abs(it.start - at) <= EPSILON }
                if (entered != null) {
                    decided = true
                    val rule = entered.before.firstOrNull { it.action != PlaybackAction.REPEAT && holds(it) }
                    val target = when (rule?.action) {
                        PlaybackAction.SKIP -> entered.end
                        PlaybackAction.JUMP -> eventTime(rule.event)
                        else -> null
                    }
                    if (target != null) {
                        at = target
                        entry = true
                        landed = true
                        jumps++
                        continue
                    }
                }
            }

            val next = if (jumps < MAX_JUMPS) nextBoundary(ruled, at, left) else null
            if (next == null) {
                onSpan(at, at + left, landed)
                return PlaybackStep(at + left, decided)
            }
            onSpan(at, next, landed)
            landed = false
            left = (left - (next - at)).coerceAtLeast(0f)
            at = next

            val ending = ruled.firstOrNull { it.after.isNotEmpty() && abs(it.end - at) <= EPSILON }
            if (ending != null) {
                decided = true
                val rule = ending.after.firstOrNull { it.action != PlaybackAction.SKIP && holds(it) }
                val target = when (rule?.action) {
                    PlaybackAction.REPEAT -> ending.start
                    PlaybackAction.JUMP -> eventTime(rule.event)
                    else -> null
                }
                if (target != null) {
                    at = target
                    entry = rule?.action == PlaybackAction.JUMP
                    landed = true
                    jumps++
                    continue
                }
            }
            entry = ruled.any { it.before.isNotEmpty() && abs(it.start - at) <= EPSILON }
        }
    }

    /** The first boundary with rules after [at] and no further than [left] seconds on, or null. */
    private fun nextBoundary(sections: List<PlaybackSection>, at: Float, left: Float): Float? {
        var next: Float? = null
        sections.forEach { section ->
            if (section.before.isNotEmpty()) next = closer(next, section.start, at, left)
            if (section.after.isNotEmpty()) next = closer(next, section.end, at, left)
        }
        return next
    }

    private fun closer(best: Float?, boundary: Float, at: Float, left: Float): Float? {
        if (boundary <= at + EPSILON || boundary > at + left + EPSILON) return best
        return if (best == null || boundary < best) boundary else best
    }

    /** How close two times are to count as one; also the shortest section rules are asked for. */
    const val EPSILON = 1e-4f

    /** How many times rules may move the playhead in one call: more, and a loop of empty sections plays on. */
    private const val MAX_JUMPS = 32
}
