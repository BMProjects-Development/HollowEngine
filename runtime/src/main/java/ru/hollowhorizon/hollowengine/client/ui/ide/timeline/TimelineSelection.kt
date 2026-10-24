package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import kotlin.math.abs

/** Keys available to selection commands, including channels inside folded groups. */
private fun TimelineController.selectableCurves(respectFocus: Boolean = true): List<ChannelCurve> = buildList {
    fun visit(group: TrackGroup) {
        if (!group.hasListedContent || group.isLocked || !group.isVisible) return
        group.properties.filter { it.isListed && it.isVisible && !it.isLocked }.forEach { property ->
            property.curves.filterTo(this) { curve ->
                curve.isListed && curve.isVisible && (viewMode != TimelineViewMode.CURVES ||
                        curve.spec.supportsCurveEditor && (!respectFocus || focusedCurves.isEmpty() || isFocused(curve)))
            }
        }
        group.children.forEach(::visit)
    }
    groups.forEach(::visit)
}

fun TimelineController.selectAllKeys() = select(selectableCurves().flatMap { it.keyframes }, additive = false)

/** Extends a pose selection across channels; with no selected keys, uses the playhead. */
fun TimelineController.selectKeyColumns() {
    val times = selectedKeyframes.map { it.time }.ifEmpty { listOf(currentTime) }.sorted()
    val keys = selectableCurves().flatMap { curve ->
        curve.keyframes.filter { key ->
            val index = times.binarySearch(key.time)
            if (index >= 0) true else {
                val insertion = -index - 1
                listOfNotNull(times.getOrNull(insertion), times.getOrNull(insertion - 1))
                    .any { abs(it - key.time) <= ChannelCurve.KEY_TIME_EPSILON }
            }
        }
    }
    select(keys, additive = true)
}

fun TimelineController.selectTrackKeys(curves: List<ChannelCurve>, additive: Boolean = false) {
    val available = selectableCurves(respectFocus = false).toSet()
    select(curves.filter { it in available }.flatMap { it.keyframes }, additive)
}
