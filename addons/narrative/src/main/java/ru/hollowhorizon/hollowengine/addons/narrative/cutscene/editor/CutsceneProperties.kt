package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineColors
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineSelectionFields
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.ToolbarButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** The cutscene's own fields, composed inside whichever panel the shared inspector is drawn in. */
@Composable
internal fun CutsceneProperties(session: CutsceneEditorSession, refresh: () -> Unit) {
    PreviewSection(session.timeline, refresh)
    OriginSection(session, refresh)
    TimelineSelectionFields(session.timeline, refresh) { WorldReadout(session) }
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
        NumberRow(CutsceneLang.ORIGIN_X.lang, origin.x) { next ->
            session.moveOrigin(Vec3f(next, origin.y, origin.z), origin.yaw, keepWorld = false)
            refresh()
        }
        NumberRow(CutsceneLang.ORIGIN_Y.lang, origin.y) { next ->
            session.moveOrigin(Vec3f(origin.x, next, origin.z), origin.yaw, keepWorld = false)
            refresh()
        }
        NumberRow(CutsceneLang.ORIGIN_Z.lang, origin.z) { next ->
            session.moveOrigin(Vec3f(origin.x, origin.y, next), origin.yaw, keepWorld = false)
            refresh()
        }
        NumberRow(CutsceneLang.ORIGIN_YAW.lang, origin.yaw, min = -360f, max = 360f, step = 1f) { next ->
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
private fun WorldReadout(session: CutsceneEditorSession) {
    val pose = session.playback.currentPose
    Readonly(CutsceneLang.WORLD.lang, formatVec3(pose.position))
    Readonly(CutsceneLang.WORLD_ROTATION.lang, formatVec3(pose.rotation))
}

private fun formatVec3(vector: Vec3f): String =
    "%.2f  %.2f  %.2f".format(vector.x, vector.y, vector.z).replace(',', '.')
