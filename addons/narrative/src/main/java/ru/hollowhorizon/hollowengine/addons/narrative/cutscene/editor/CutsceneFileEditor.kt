package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.*
import ru.hollowhorizon.hollowengine.client.ui.ide.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.PublishTimeline
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineTarget
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.utils.lang

internal const val CutsceneIcon = "hollowengine-narrative:textures/gui/icons/cutscene.svg"
private const val CutsceneExtension = ".${CutsceneStorage.EXTENSION}"

private fun isCutscenePath(path: String): Boolean =
    path.startsWith("${CutsceneStorage.ROOT_READABLE_PATH}/", ignoreCase = true) && path.endsWith(
        CutsceneExtension,
        ignoreCase = true
    )

internal val CutsceneFileType = HollowIdeFileType(
    id = "cutscene",
    priority = 280,
    pathMatcher = ::isCutscenePath,
    matcher = { path, _ -> isCutscenePath(path) },
    loader = { path, bytes -> CutsceneDocument(path, bytes, ::starterCutscene) },
    editor = ::CutsceneFileEditor,
    icon = CutsceneIcon,
    openBesideGame = true,
    onOpened = { if (!it.isPanelOpen(TimelineId)) it.openPanel(TimelineId) },
    tracksDocumentChanges = true,
)

internal val CreateCutscene = WorldSceneAction { ide ->
    UiDropdownItem(
        CutsceneLang.CREATE.lang,
        mark = UiMenuParts.icon(CutsceneIcon),
        enabled = Minecraft.getInstance().player != null,
    ) { createCutscene(ide) }
}

private fun createCutscene(ide: HollowIdeContext) {
    ide.createFile(
        directory = CutsceneStorage.ROOT_READABLE_PATH,
        title = CutsceneLang.CREATE.lang,
        suggestedName = "cutscene",
        extension = CutsceneExtension,
    ) { fileName ->
        val name = fileName.dropLast(CutsceneExtension.length)
        CutsceneStorage.encode(starterCutscene(name))
    }
}

private fun starterCutscene(name: String): CutsceneData {
    val minecraft = Minecraft.getInstance()
    val player = minecraft.player ?: return CutsceneData(name = name)
    val pose = CutsceneCameraSystem.capturePlayerPose(minecraft) ?: return CutsceneData(name = name)
    val origin = CutsceneOrigin(player.x.toFloat(), player.y.toFloat(), player.z.toFloat(), pose.yaw)
    val session = CutsceneEditorSession()
    session.load(CutsceneData(name = name, origin = origin))
    session.captureCamera(0f, pose)
    return session.playback.toData(name)
}

@Composable
private fun CutsceneFileEditor(file: HollowIdeOpenFile) {
    val document = file.document as CutsceneDocument
    val ide = LocalHollowIdeContext.current
    val session = document.session
    val error = document.error
    if (error != null) {
        CutscenePreviewMessage(CutsceneLang.LOAD_FAILED.lang(error))
        return
    }

    val active = ide?.activeFile.let { it == null || it === file }
    if (active) {
        PublishTimeline(file.id, document) {
            TimelineTarget(
                id = file.id,
                controller = session.timeline,
                refresh = session::invalidateUi,
                onCapture = session::captureFrame,
                inspector = { refresh -> CutsceneProperties(session, refresh) },
            )
        }
        CutsceneScene(file, ide, session)
    }

    CutsceneViewport(file, session, showGuides = active, inputActive = ide == null || ide.focusedFile === file)
}

@Composable
private fun CutsceneScene(file: HollowIdeOpenFile, ide: HollowIdeContext?, session: CutsceneEditorSession) {
    var selected by remember(file) { mutableStateOf<String?>(null) }
    PublishScene(file.id, selected) {
        SceneTarget(
            id = file.id,
            items = listOf(
                UiTreeItem(
                    CameraRig.CAMERA_ID,
                    CutsceneLang.CAMERA.lang,
                    0,
                    null,
                    CutsceneIcon,
                    selected = selected == CameraRig.CAMERA_ID
                ),
            ),
            onSelect = { selected = it },
            onToggle = {},
            menu = { id ->
                if (id == null && ide != null) listOf(CreateCutscene.item(ide))
                else listOf(
                    UiDropdownItem(
                        CutsceneLang.INSERT_CAMERA_KEY.lang,
                        enabled = Minecraft.getInstance().level != null
                    ) {
                        session.captureCamera(session.timeline.currentTime, session.previewPose)
                    },
                )
            },
            history = session.timeline.history,
        )
    }
}
