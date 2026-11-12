package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.AnimProperty
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

class CutsceneEditorSession {
    val playback = CutscenePlaybackController()
    val timeline = playback.timeline

    val uiRevision = mutableStateOf(0)
    val cameraDraft = mutableStateOf<CameraPose?>(null)
    private val animatedFrame = mutableStateOf(sampleFrame())
    internal val previewFrame: CutscenePreviewFrame
        get() {
            val frame = animatedFrame.value
            return cameraDraft.value?.let { frame.copy(pose = it) } ?: frame
        }
    val previewPose: CameraPose get() = previewFrame.pose
    internal val cameraPath = derivedStateOf {
        timeline.keysRevision
        val translation = playback.translation
        val times = translation.curves.filter { it.isVisible }
            .flatMap { curve -> curve.keyframes.map { it.time } }
            .distinct().sorted()

        val frame = authoringFrame
        if (times.isEmpty()) emptyList() else buildList {
            add(frame.toWorld(translation.valueAt(times.first())))
            for (index in 1 until times.size) {
                val start = times[index - 1]
                val end = times[index]
                for (sample in 1..PATH_SAMPLES_PER_SEGMENT) {
                    val time = start + (end - start) * sample / PATH_SAMPLES_PER_SEGMENT
                    add(frame.toWorld(translation.valueAt(time)))
                }
            }
        }
    }

    fun invalidateUi() {
        uiRevision.value++
    }

    init {
        timeline.onChanged = {
            refreshCamera()
            invalidateUi()
        }
        timeline.onTimeChanged = {
            refreshCamera()
            invalidateUi()
        }
        timeline.captureExtraState = { playback.origin }
        timeline.restoreExtraState = { state -> (state as? CutsceneOrigin)?.let { playback.origin = it } }
    }

    val authoringFrame: CutsceneFrame get() = playback.origin.frame

    fun update(deltaSeconds: Float) {
        timeline.onUpdate(deltaSeconds)
    }

    fun captureFrame() {
        val world = Minecraft.getInstance().level?.captureCutsceneEnvironment() ?: return
        val frame = previewFrame
        val animated = frame.environment
        captureCamera(
            timeline.currentTime,
            frame.pose,
            CutsceneEnvironment(animated.timeOfDay ?: world.timeOfDay, animated.weather ?: world.weather),
        )
    }

    fun captureCamera(
        time: Float,
        pose: CameraPose,
        environment: CutsceneEnvironment = CutsceneEnvironment(),
        channels: CameraKeyChannels = CameraKeyChannels.ALL,
    ) {
        val frame = authoringFrame
        val keepDraft = channels != CameraKeyChannels.ALL && cameraDraft.value != null && time == timeline.currentTime
        timeline.edit(TimelineEdits.CAPTURE_KEY) {
            timeline.clearSelection()
            if (channels == CameraKeyChannels.ALL || channels == CameraKeyChannels.POSITION) {
                val position = frame.toLocal(pose.position)
                writeChannels(playback.translation, time, listOf(position.x, position.y, position.z))
            }
            if (channels == CameraKeyChannels.ALL || channels == CameraKeyChannels.ROTATION) {
                writeChannels(
                    playback.rotation,
                    time,
                    frame.toLocalRotation(pose.rotation).decomposedBy(playback.rotation)
                )
            }
            if (channels == CameraKeyChannels.ALL || channels == CameraKeyChannels.FOV) {
                writeChannels(playback.fov, time, listOf(pose.fov))
            }
            writeChannels(playback.timeOfDay, time, listOfNotNull(environment.timeOfDay))
            writeChannels(playback.weather, time, listOfNotNull(environment.weather?.value))
        }
        timeline.applyCurrentTime(time)
        if (keepDraft) cameraDraft.value = pose
    }

    private fun <T> T.decomposedBy(property: AnimProperty<T>): List<Float> {
        val values = FloatArray(property.channels.size)
        property.type.decompose(this, values)
        return values.toList()
    }

    private fun writeChannels(property: AnimProperty<*>, time: Float, values: List<Float>) {
        if (timeline.isLocked(property)) return
        val created = property.curves.mapIndexedNotNull { channel, curve ->
            val value = values.getOrNull(channel) ?: return@mapIndexedNotNull null
            val unwrapped = curve.spec.unwrap(value, curve.valueAt(time, value))
            timeline.setKey(curve, time, unwrapped, selectKey = false)
        }
        timeline.select(created, additive = true)
    }

    fun moveOrigin(position: Vec3f, yaw: Float, keepWorld: Boolean) {
        timeline.edit(if (keepWorld) REANCHOR else MOVE) {
            if (keepWorld) playback.reanchor(position, yaw)
            else playback.origin = playback.origin.moved(position, yaw)
        }
    }

    /** Puts the origin on the player standing in the world, the usual way to place a scene. */
    fun originToPlayer(keepWorld: Boolean) {
        val player = Minecraft.getInstance().player ?: return
        moveOrigin(Vec3f(player.x.toFloat(), player.y.toFloat(), player.z.toFloat()), player.yRot, keepWorld)
    }

    private fun refreshCamera() {
        playback.updateProperties()
        animatedFrame.value = sampleFrame()
        cameraDraft.value = null
    }

    private fun sampleFrame() = CutscenePreviewFrame(playback.currentPose, playback.currentEnvironment)

    fun load(data: CutsceneData) {
        cameraDraft.value = null
        playback.setupTracks(data)
        timeline.notifyKeysChanged()
        timeline.isPlaying = false
        timeline.applyCurrentTime(0f)
        timeline.clearSelection()
        timeline.clearHistory()
        invalidateUi()
    }
}

internal data class CutscenePreviewFrame(val pose: CameraPose, val environment: CutsceneEnvironment)

enum class CameraKeyChannels { POSITION, ROTATION, FOV, ALL }

private const val PATH_SAMPLES_PER_SEGMENT = 16

private val MOVE = UndoLabel("${UndoLabel.LANG}.timeline.move_cutscene")
private val REANCHOR = UndoLabel("${UndoLabel.LANG}.timeline.reanchor_cutscene")
