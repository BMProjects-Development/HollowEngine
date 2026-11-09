package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneEditorSessions
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneStorage
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.DefaultTimeline
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.HollowTimelineEditor
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.LocalTimelineRevision
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineColors
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineLang
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.ToolbarButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val CutsceneIcon = "hollowengine:textures/gui/icons/film.svg"

@Composable
internal fun CutsceneTimelineDock(session: CutsceneEditorSession, keyboardActive: Boolean = true) {
    session.uiRevision.value
    var dialog by remember { mutableStateOf(CutsceneDialog.NONE) }

    LaunchedEffect(session) {
        var lastNanos = -1L
        while (isActive) {
            withFrameNanos { now ->
                val delta =
                    if (lastNanos < 0L) 0f else ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                lastNanos = now
                if (session.timeline.isPlaying) session.update(delta)
                if (session.timeline.curveAxis.advance(now)) session.invalidateUi()
            }
        }
    }

    PublishInspector(source = "cutscene", key = cutsceneInspectorKey(session)) {
        cutsceneInspectorTarget(session)
    }

    CompositionLocalProvider(LocalTimelineRevision provides session.uiRevision.value) {
        Box(id = "cutscene-timeline-dock", mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
            HollowTimelineEditor(
                controller = session.timeline,
                refresh = session::invalidateUi,
                onKeyInput = session::onHollowUiKey,
                keyboardActive = keyboardActive,
                onCapture = { session.captureFrame(session.timeline.currentTime) },
                onSave = { dialog = CutsceneDialog.SAVE },
                onLoad = { dialog = CutsceneDialog.LOAD },
            )

            when (dialog) {
                CutsceneDialog.SAVE -> SaveCutsceneDialog(session) { dialog = CutsceneDialog.NONE }
                CutsceneDialog.LOAD -> LoadCutsceneDialog(session) { dialog = CutsceneDialog.NONE }
                CutsceneDialog.NONE -> {}
            }
        }
    }
}

private fun cutsceneInspectorTarget(session: CutsceneEditorSession) = InspectorTarget(
    id = "cutscene-${cutsceneInspectorKey(session)}",
    title = TimelineLang.PROPERTIES.lang,
    icon = CutsceneIcon,
) {
    CompositionLocalProvider(LocalTimelineRevision provides session.uiRevision.value) {
        CutsceneProperties(session, refresh = session::invalidateUi)
    }
}

private fun cutsceneInspectorKey(session: CutsceneEditorSession): String {
    val controller = session.timeline
    val keyframe = controller.selectedKeyframes.firstOrNull()
        ?: return if (controller.isWorkAreaSelected) "work-area" else "none"
    return "key-${controller.curveOf(keyframe)?.spec?.name}-${keyframe.time}"
}

private enum class CutsceneDialog { NONE, SAVE, LOAD }

// ---------------------------------------------------------------------------
// Save / Load dialogs (lightweight overlays over the timeline dock)
// ---------------------------------------------------------------------------

@Composable
private fun SaveCutsceneDialog(session: CutsceneEditorSession, onClose: () -> Unit) {
    var name by remember { mutableStateOf(session.playback.toData().name) }
    DialogFrame(CutsceneLang.SAVE_TITLE.lang, onClose) {
        Text(CutsceneLang.NAME.lang, modifier = Modifier.fontSize(10f).foreground(TimelineColors.Muted))
        TextField(
            value = name,
            onChange = { name = it },
            modifier = Modifier.size(100.percent, 24.px)
                .background(TimelineColors.Background)
                .border(1.px, TimelineColors.Border, 3f)
                .padding(6.px, 2.px)
                .foreground(TimelineColors.Text)
                .fontSize(11f),
        )
        Row(modifier = Modifier.size(100.percent, UiLength.Auto).gap(8.px).align(horizontal = UiAlign.END)) {
            ToolbarButton(TimelineLang.CANCEL.lang, "cutscene-save-cancel") { onClose() }
            ToolbarButton(TimelineLang.SAVE.lang, "cutscene-save-confirm", primary = true) {
                if (name.isNotBlank()) {
                    session.exportCutscene("", name.trim())
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun LoadCutsceneDialog(session: CutsceneEditorSession, onClose: () -> Unit) {
    val files = remember { CutsceneStorage.listFiles() }
    DialogFrame(CutsceneLang.LOAD_TITLE.lang, onClose) {
        if (files.isEmpty()) {
            Text(CutsceneLang.NO_CUTSCENES.lang, modifier = Modifier.fontSize(11f).foreground(TimelineColors.Muted))
        } else {
            Column(
                modifier = Modifier.size(100.percent, UiLength.Auto)
                    .gap(2.px)
                    .scrollable(horizontal = false),
            ) {
                files.forEach { file ->
                    Box(
                        modifier = Modifier.size(100.percent, 24.px)
                            .background(TimelineColors.Background)
                            .border(1.px, TimelineColors.Border, 3f)
                            .cursor(UiCursorShape.HAND)
                            .onClick {
                                session.importCutscene(file)
                                onClose()
                                it.consume()
                            },
                    ) {
                        Text(
                            file.removeSuffix(".${CutsceneStorage.EXTENSION}"),
                            modifier = Modifier.align(vertical = UiAlign.CENTER)
                                .margin(8.px, 0.px, 0.px, 0.px)
                                .fontSize(11f)
                                .foreground(TimelineColors.Text),
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.size(100.percent, UiLength.Auto).align(horizontal = UiAlign.END)) {
            ToolbarButton(TimelineLang.CLOSE.lang, "cutscene-load-close") { onClose() }
        }
    }
}

@Composable
private fun DialogFrame(title: String, onClose: () -> Unit, content: HollowUiContent) {
    val viewport = LocalUiViewport.current
    Popup(
        anchorBounds = viewport,
        alignment = CenteredOnAnchor,
        id = "cutscene-dialog-popup",
        layer = 100,
        modal = true,
        onDismiss = onClose,
    ) {
        Column(
            id = "cutscene-dialog",
            modifier = Modifier.size(340.px, UiLength.Auto)
                .maxSize(height = (viewport.height - 80f).coerceAtLeast(120f).px)
                .background(TimelineColors.Panel)
                .border(1.px, TimelineColors.Border, 6f)
                .padding(14.px)
                .gap(8.px)
                .scrollable(horizontal = false),
        ) {
            Text(title, modifier = Modifier.fontSize(13f).foreground(TimelineColors.Text))
            content()
        }
    }
}

private val CenteredOnAnchor = UiPopupAlignment(
    anchorHorizontal = UiAlign.CENTER,
    anchorVertical = UiAlign.CENTER,
    popupHorizontal = UiAlign.CENTER,
    popupVertical = UiAlign.CENTER,
)

/** The cutscene being authored, shown in the timeline window while no editor has published its own timeline. */
internal object CutsceneTimeline : DefaultTimeline {
    override val controller: TimelineController
        get() = CutsceneEditorSessions.default.timeline

    @Composable
    override fun Content(keyboardActive: Boolean) = CutsceneTimelineDock(CutsceneEditorSessions.default, keyboardActive)
}
