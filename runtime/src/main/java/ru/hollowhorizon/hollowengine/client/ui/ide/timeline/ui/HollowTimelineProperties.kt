package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ChannelBounds
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ChannelCurve
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ChannelValueOption
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.CurvePresets
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.HandleMode
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyInterpolation
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyTangent
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.Keyframe
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TangentSide
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits

/** The cutscene's own fields, composed inside whichever panel the shared inspector is drawn in. */
@Composable
internal fun HollowTimelineProperties(session: CutsceneEditorSession, refresh: () -> Unit) {
    PreviewSection(session.timeline, refresh)
    OriginSection(session, refresh)
    TimelineSelectionFields(session.timeline, refresh) { WorldReadout(session) }
}

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
                if (controller.selectedKeyframes.size == 1) CutsceneLang.DELETE_KEY.lang
                else CutsceneLang.DELETE_KEYS.lang,
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
private fun PreviewSection(controller: TimelineController, refresh: () -> Unit) {
    Section(CutsceneLang.PREVIEW.lang) {
        Row(
            modifier = Modifier.size(100.percent, 24.px)
                .alignItems(vertical = UiAlign.CENTER)
                .gap(8.px)
        ) {
            Pill(
                if (controller.isCameraPreviewEnabled) CutsceneLang.CAMERA_ON.lang
                else CutsceneLang.CAMERA_OFF.lang,
                controller.isCameraPreviewEnabled
            ) {
                controller.applyCameraPreviewEnabled(!controller.isCameraPreviewEnabled)
                refresh()
            }
            Text(
                if (controller.isPlaying) CutsceneLang.PLAYING.lang else CutsceneLang.PAUSED.lang,
                modifier = Modifier.fontSize(10f).foreground(TimelineColors.Muted),
            )
        }
        Readonly(CutsceneLang.CURRENT_TIME.lang, "%.3f s".format(controller.currentTime).replace(',', '.'))
        Readonly(CutsceneLang.DURATION.lang, "%.3f s".format(controller.workAreaEnd).replace(',', '.'))
    }
}

@Composable
private fun OriginSection(session: CutsceneEditorSession, refresh: () -> Unit) {
    val origin = session.playback.origin
    Section(CutsceneLang.ORIGIN.lang, id = "timeline-origin-section") {
        FloatRow(CutsceneLang.ORIGIN_X.lang, origin.x, min = -Float.MAX_VALUE, max = Float.MAX_VALUE) { next ->
            session.moveOrigin(Vec3f(next, origin.y, origin.z), origin.yaw, keepWorld = false)
            refresh()
        }
        FloatRow(CutsceneLang.ORIGIN_Y.lang, origin.y, min = -Float.MAX_VALUE, max = Float.MAX_VALUE) { next ->
            session.moveOrigin(Vec3f(origin.x, next, origin.z), origin.yaw, keepWorld = false)
            refresh()
        }
        FloatRow(CutsceneLang.ORIGIN_Z.lang, origin.z, min = -Float.MAX_VALUE, max = Float.MAX_VALUE) { next ->
            session.moveOrigin(Vec3f(origin.x, origin.y, next), origin.yaw, keepWorld = false)
            refresh()
        }
        FloatRow(CutsceneLang.ORIGIN_YAW.lang, origin.yaw, min = -360f, max = 360f) { next ->
            session.moveOrigin(origin.position, next, keepWorld = false)
            refresh()
        }
        Row(modifier = Modifier.size(100.percent, UiLength.Auto).gap(6.px)) {
            ToolbarButton(CutsceneLang.ORIGIN_MOVE_HERE.lang, "timeline-origin-move") {
                session.originToPlayer(keepWorld = false)
                refresh()
            }
            ToolbarButton(CutsceneLang.ORIGIN_REBASE.lang, "timeline-origin-rebase") {
                session.originToPlayer(keepWorld = true)
                refresh()
            }
        }
    }
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
    val title = if (count == 1) CutsceneLang.KEYFRAME.lang else CutsceneLang.KEYFRAMES.lang(count)
    Section(title) {
        Readonly(CutsceneLang.CHANNEL.lang, channelPath(controller, curve))
        FloatRow(CutsceneLang.TIME.lang, keyframe.time, min = 0f, max = controller.workAreaEnd) { time ->
            controller.nudgeSelectedKeyframes(snapTimelineTime(time, currentUiKeyModifiers()) - keyframe.time)
            refresh()
        }
        if (curve.spec.valueOptions.isEmpty()) {
            val owner = controller.propertyOf(keyframe)
            val channel = owner?.curves?.indexOfFirst { it === curve } ?: -1
            val bounds = (if (channel >= 0) owner?.bounds(channel) else null) ?: ChannelBounds.Unbounded
            FloatRow(
                CutsceneLang.VALUE.lang,
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
    Text(CutsceneLang.VALUE.lang, modifier = Modifier.fontSize(9f).foreground(TimelineColors.Muted))
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

@Composable
private fun WorldReadout(session: CutsceneEditorSession) {
    val pose = session.playback.currentPose
    Readonly(CutsceneLang.WORLD.lang, formatVec3(pose.position))
    Readonly(CutsceneLang.WORLD_ROTATION.lang, formatVec3(pose.rotation))
}

private fun formatVec3(vector: Vec3f): String =
    "%.2f  %.2f  %.2f".format(vector.x, vector.y, vector.z).replace(',', '.')

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

    Section(CutsceneLang.INTERPOLATION.lang, id = "timeline-interpolation-section") {
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

        Text(CutsceneLang.HANDLES.lang, modifier = Modifier.fontSize(9f).foreground(TimelineColors.Muted))
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
                CutsceneLang.HANDLES_AUTO_HINT.lang,
                modifier = Modifier.size(100.percent, UiLength.Fit)
                    .fontSize(9f)
                    .foreground(TimelineColors.Muted),
            )
            return@Section
        }
        TangentRow(controller, keyframe, TangentSide.INCOMING, CutsceneLang.HANDLE_IN.lang, refresh)
        TangentRow(controller, keyframe, TangentSide.OUTGOING, CutsceneLang.HANDLE_OUT.lang, refresh)
    }
}

private fun handleLabel(mode: HandleMode): String = when (mode) {
    HandleMode.AUTO -> CutsceneLang.HANDLES_AUTO.lang
    HandleMode.MIRRORED -> CutsceneLang.HANDLES_MIRRORED.lang
    HandleMode.ALIGNED -> CutsceneLang.HANDLES_ALIGNED.lang
    HandleMode.FREE -> CutsceneLang.HANDLES_FREE.lang
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
        FloatRow(label, tangent.time) { next ->
            applyTangent(controller, keyframe, side, KeyTangent(next, keyframe.tangent(side).value))
            refresh()
        }
        FloatRow("", tangent.value) { next ->
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
    Section(CutsceneLang.WORK_AREA.lang) {
        FloatRow(CutsceneLang.END.lang, controller.workAreaEnd, min = 0.1f, max = Float.POSITIVE_INFINITY) { time ->
            controller.workAreaEnd = snapTimelineTime(time, currentUiKeyModifiers()).coerceAtLeast(0.1f)
            if (controller.currentTime > controller.workAreaEnd) controller.applyCurrentTime(0f)
            refresh()
        }
    }
}

@Composable
private fun EmptySection() {
    Section(CutsceneLang.SELECTION.lang) {
        Text(
            CutsceneLang.NO_SELECTION.lang,
            modifier = Modifier.size(100.percent, 22.px)
                .fontSize(11f)
                .foreground(TimelineColors.Muted)
        )
    }
}
