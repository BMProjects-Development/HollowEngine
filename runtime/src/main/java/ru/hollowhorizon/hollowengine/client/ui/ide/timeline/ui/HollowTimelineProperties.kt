package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * What is selected on a timeline: the keys and their curve, or the work area.
 */
@Composable
fun TimelineSelectionFields(
    controller: TimelineController,
    refresh: () -> Unit,
    keyframeExtras: @Composable () -> Unit = {},
) {
    val selectedKey = controller.selectedKeyframes.firstOrNull()
    val selectedCurve = selectedKey?.let { controller.curveOf(it) }

    when {
        selectedKey != null && selectedCurve != null -> {
            KeyframeSection(controller, selectedKey, selectedCurve, refresh, keyframeExtras)
            if (selectedCurve.spec.supportsCurveEditor) {
                CurveSection(controller, selectedKey, selectedCurve, refresh)
            }
            InspectorButton(
                if (controller.selectedKeyframes.size == 1) TimelineLang.DELETE_KEY.lang
                else TimelineLang.DELETE_KEYS.lang,
                tags = listOf("danger"),
            ) {
                controller.deleteSelectedKeyframes()
                refresh()
            }
        }

        controller.isWorkAreaSelected -> WorkAreaSection(controller, refresh)
        else -> EmptySection()
    }
}

/** Whether a timeline has anything selected that [TimelineSelectionFields] would show. */
fun TimelineController.hasInspectableSelection(): Boolean = selectedKeyframes.isNotEmpty() || isWorkAreaSelected

/** A key that changes whenever what [TimelineSelectionFields] shows is a different thing. */
fun TimelineController.selectionKey(): String {
    val keyframe = selectedKeyframes.firstOrNull()
        ?: return if (isWorkAreaSelected) "work-area" else "none"
    return "key-${System.identityHashCode(curveOf(keyframe))}-${System.identityHashCode(keyframe)}"
}

@Composable
private fun KeyframeSection(
    controller: TimelineController,
    keyframe: Keyframe,
    curve: ChannelCurve,
    refresh: () -> Unit,
    extras: @Composable () -> Unit,
) {
    val count = controller.selectedKeyframes.size
    val title = if (count == 1) TimelineLang.KEYFRAME.lang else TimelineLang.KEYFRAMES.lang(count)
    Section(title) {
        Readonly(TimelineLang.CHANNEL.lang, channelPath(controller, curve))
        NumberRow(TimelineLang.TIME.lang, keyframe.time) { time ->
            controller.nudgeSelectedKeyframes(snapTimelineTime(time) - keyframe.time)
            refresh()
        }
        if (curve.spec.valueOptions.isEmpty()) {
            val owner = controller.propertyOf(keyframe)
            val channel = owner?.curves?.indexOfFirst { it === curve } ?: -1
            val bounds = (if (channel >= 0) owner?.bounds(channel) else null) ?: ChannelBounds.Unbounded
            NumberRow(
                TimelineLang.VALUE.lang,
                keyframe.value,
                min = bounds.minimum ?: -Float.MAX_VALUE,
                max = bounds.maximum ?: Float.MAX_VALUE,
            ) { next ->
                controller.setSelectedKeyframeValue(keyframe, next)
                refresh()
            }
        } else {
            DiscreteValueField(curve.spec.valueOptions, keyframe.value) { next ->
                controller.setSelectedKeyframeValue(keyframe, next)
                refresh()
            }
        }
        extras()
    }
}

@Composable
private fun DiscreteValueField(options: List<ChannelValueOption>, value: Float, onChange: (Float) -> Unit) {
    Text(TimelineLang.VALUE.lang, modifier = Modifier.fontSize(9f).foreground(TimelineColors.Muted))
    PillFlow(id = "timeline-discrete-values") {
        options.forEachIndexed { index, option ->
            Pill(option.labelKey.lang, value == option.value, id = "timeline-discrete-value-$index") {
                onChange(option.value)
            }
        }
    }
}

private fun channelPath(controller: TimelineController, curve: ChannelCurve): String {
    val property = controller.propertyOf(curve)
    return listOfNotNull(property?.nameState, curve.name).joinToString(" / ")
}

/**
 * Interpolation is a curve preset: picking one writes the handles that shape the segment, and the
 * handles can then be dragged from here or in the graph editor.
 */
@Composable
private fun CurveSection(
    controller: TimelineController,
    keyframe: Keyframe,
    curve: ChannelCurve,
    refresh: () -> Unit,
) {
    val index = curve.keyframes.indexOfFirst { it === keyframe }
    val next = curve.keyframes.getOrNull(index + 1)
    val active = CurvePresets.match(keyframe, next)
    var category by remember { mutableStateOf(active?.category ?: CurvePresets.categories.first()) }

    Section(TimelineLang.INTERPOLATION.lang, id = "timeline-interpolation-section") {
        active?.let { CurvePreview(it) }
        PillFlow(id = "timeline-preset-categories") {
            CurvePresets.categories.forEach { name ->
                Pill(name, category == name, id = "preset-category-$name") { category = name }
            }
        }
        PillFlow(id = "timeline-presets") {
            CurvePresets.of(category).forEach { preset ->
                Pill(preset.name, active?.id == preset.id, id = "preset-${preset.id}") {
                    controller.applyPreset(preset)
                    refresh()
                }
            }
        }
        if (keyframe.interpolation != KeyInterpolation.BEZIER) return@Section

        Text(TimelineLang.HANDLES.lang, modifier = Modifier.fontSize(9f).foreground(TimelineColors.Muted))
        PillFlow(id = "timeline-handle-modes") {
            HandleMode.entries.forEach { mode ->
                Pill(handleLabel(mode), keyframe.handleMode == mode, id = "handle-mode-${mode.name}") {
                    controller.setSelectedHandleMode(mode)
                    refresh()
                }
            }
        }
        if (keyframe.handleMode == HandleMode.AUTO) {
            Text(
                TimelineLang.HANDLES_AUTO_HINT.lang,
                modifier = Modifier.size(100.percent, UiLength.Fit)
                    .fontSize(9f)
                    .foreground(TimelineColors.Muted),
            )
            return@Section
        }
        TangentRow(controller, keyframe, TangentSide.INCOMING, TimelineLang.HANDLE_IN.lang, refresh)
        TangentRow(controller, keyframe, TangentSide.OUTGOING, TimelineLang.HANDLE_OUT.lang, refresh)
    }
}

private fun handleLabel(mode: HandleMode): String = when (mode) {
    HandleMode.AUTO -> TimelineLang.HANDLES_AUTO.lang
    HandleMode.MIRRORED -> TimelineLang.HANDLES_MIRRORED.lang
    HandleMode.ALIGNED -> TimelineLang.HANDLES_ALIGNED.lang
    HandleMode.FREE -> TimelineLang.HANDLES_FREE.lang
}

@Composable
private fun TangentRow(
    controller: TimelineController,
    keyframe: Keyframe,
    side: TangentSide,
    label: String,
    refresh: () -> Unit,
) {
    val tangent = keyframe.tangent(side)
    Row(modifier = Modifier.size(100.percent, UiLength.Fit).gap(4.px)) {
        NumberRow(label, tangent.time) { next ->
            applyTangent(controller, keyframe, side, KeyTangent(next, keyframe.tangent(side).value))
            refresh()
        }
        NumberRow("", tangent.value) { next ->
            applyTangent(controller, keyframe, side, KeyTangent(keyframe.tangent(side).time, next))
            refresh()
        }
    }
}

private fun applyTangent(
    controller: TimelineController,
    keyframe: Keyframe,
    side: TangentSide,
    tangent: KeyTangent,
) {
    controller.edit(TimelineEdits.EDIT_HANDLES) {
        controller.setTangent(keyframe, side, tangent, keyframe.handleMode, timeScale = 1f, valueScale = 1f)
    }
}

@Composable
private fun WorkAreaSection(controller: TimelineController, refresh: () -> Unit) {
    Section(TimelineLang.WORK_AREA.lang) {
        NumberRow(TimelineLang.END.lang, controller.workAreaEnd, min = 0.1f, max = Float.POSITIVE_INFINITY) { time ->
            controller.workAreaEnd = snapTimelineTime(time).coerceAtLeast(0.1f)
            if (controller.currentTime > controller.workAreaEnd) controller.applyCurrentTime(0f)
            refresh()
        }
    }
}

@Composable
private fun EmptySection() {
    Section(TimelineLang.SELECTION.lang) {
        Text(
            TimelineLang.NO_SELECTION.lang,
            modifier = Modifier.size(100.percent, 22.px)
                .fontSize(11f)
                .foreground(TimelineColors.Muted)
        )
    }
}
