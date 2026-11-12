package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CameraKeyChannels
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneCameraSystem
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneWeather
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.LocalHollowIdeWindow
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyInsertMenu
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyParts
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineLang
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.math.roundToLong

private const val STYLE = "hollowengine-narrative:ui/styles/cutscene.hss"
private const val ICONS = "hollowengine:textures/gui/icons/timeline/"

@Composable
internal fun CutsceneViewport(
    file: HollowIdeOpenFile,
    session: CutsceneEditorSession,
    showGuides: Boolean,
    inputActive: Boolean,
) {
    val status = worldCameraPreviewStatus()
    val ready = status == WorldCameraPreviewStatus.READY
    val input = remember(session) { CutsceneViewportInput(session) }
    val window = LocalHollowIdeWindow.current
    val pointer = remember(session) { KeyMenuPointer() }
    var keyMenu by remember(session) { mutableStateOf<UiRect?>(null) }
    var modesOpen by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        var previousFrame = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                input.advanceDolly(now)
                if (session.timeline.isPlaying) {
                    val seconds = ((now - previousFrame) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                    session.update(seconds)
                }
                previousFrame = now
            }
        }
    }
    DisposableEffect(session) {
        onDispose { session.timeline.isPlaying = false }
    }
    Column(modifier = Modifier.size(100.percent, 100.percent).style(STYLE)) {
        Row(tags = listOf("cutscene-viewport-toolbar")) {
            val playing = session.timeline.isPlaying
            IconButton(
                icon = ICONS + if (playing) "pause.svg" else "play.svg",
                tooltip = TimelineLang.PLAY.lang,
                id = "${file.id}-play",
                active = playing,
            ) { session.timeline.togglePlayback() }
            UiDropdown(
                id = "${file.id}-camera-mode",
                label = input.mode.label,
                expanded = modesOpen,
                onExpandedChange = { modesOpen = it },
                tags = listOf("panel-dropdown"),
                items = CutsceneCameraMode.entries.map { mode ->
                    UiDropdownItem(mode.label, mark = UiMenuParts.radio(mode == input.mode)) {
                        input.changeMode(mode)
                        modesOpen = false
                    }
                },
            )
            IconButton(
                ICONS + "capture.svg",
                CutsceneLang.PLAYER_VIEW.lang,
                id = "${file.id}-player-view",
                enabled = ready
            ) {
                CutsceneCameraSystem.capturePlayerPose()?.let {
                    session.timeline.isPlaying = false
                    session.cameraDraft.value = it
                }
            }
            IconButton(
                ICONS + "eye.svg",
                CutsceneLang.RESET_VIEW.lang,
                id = "${file.id}-reset-view",
                enabled = session.cameraDraft.value != null
            ) {
                input.reset()
            }
        }
        Box(
            id = "${file.id}-camera-viewport",
            tags = listOf("cutscene-camera-viewport"),
            mode = UiBoxMode.STACK,
            modifier = Modifier.size(100.percent, 0.px).grow(1f).worldCameraPreview {
                    input.pollFlight(window, ready && inputActive && keyMenu == null && !modesOpen)
                    val frame = session.previewFrame
                    val environment = frame.environment
                    WorldCameraPreviewFrame(
                        pose = frame.pose,
                        cameraPath = if (showGuides) session.cameraPath.value else null,
                        environment = WorldPreviewEnvironment(
                            timeOfDay = environment.timeOfDay?.roundToLong(),
                            weather = when (environment.weather) {
                                CutsceneWeather.CLEAR -> WorldPreviewWeather.CLEAR
                                CutsceneWeather.RAIN -> WorldPreviewWeather.RAIN
                                CutsceneWeather.THUNDER -> WorldPreviewWeather.THUNDER
                                null -> null
                            },
                        ),
                    )
                }.input(hoverable = true, draggable = ready).focus().onFocus { input.focused = true }
                .onUnfocus { input.focused = false }.onPlaced { pointer.bounds = it }
                .onHover { pointer.position = UiRect(it.x, it.y, 0f, 0f) }.onKeyInput { key ->
                    if (ready && inputActive && !key.repeat && HollowKeys.INSERT_KEY.matches(key)) {
                        session.timeline.isPlaying = false
                        keyMenu = pointer.anchor()
                        key.consume()
                    }
                }.onPress { event ->
                    pointer.position = UiRect(event.x, event.y, 0f, 0f)
                    if (ready && input.begin(event)) event.consume()
                }.onDrag { event ->
                    if (input.drag(event)) event.consume()
                }.onRelease { event -> input.release(event.button) }.onScroll { event ->
                    if (ready && input.scroll(event)) event.consume()
                },
        ) {
            if (!ready) CutscenePreviewMessage(CutsceneLang.NO_WORLD.lang)
        }
        KeyInsertMenu(
            keyMenu,
            CameraKeyChannels.entries,
            CameraKeyChannels::label,
            onPick = { session.captureCamera(session.timeline.currentTime, session.previewPose, channels = it) },
            onClose = { keyMenu = null },
        )
    }
}

@Composable
internal fun CutscenePreviewMessage(message: String) {
    Box(
        mode = UiBoxMode.STACK,
        modifier = Modifier.size(100.percent, 100.percent).style(STYLE).inputTransparent(),
    ) {
        Text(
            message,
            tags = listOf("cutscene-preview-message"),
            modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
        )
    }
}

private val CameraKeyChannels.label: String
    get() = when (this) {
        CameraKeyChannels.POSITION -> KeyParts.LOCATION.label
        CameraKeyChannels.ROTATION -> KeyParts.ROTATION.label
        CameraKeyChannels.FOV -> CutsceneLang.FOV.lang
        CameraKeyChannels.ALL -> KeyParts.ALL.label
    }

private val CutsceneCameraMode.label: String get() = "${CutsceneLang.MODE}.$key".lang

private class KeyMenuPointer {
    var bounds = UiRect.Zero
    var position: UiRect? = null

    fun anchor() = position ?: UiRect(bounds.x + bounds.width * 0.5f, bounds.y + bounds.height * 0.5f, 0f, 0f)
}
