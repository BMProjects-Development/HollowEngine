package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene

import androidx.compose.runtime.mutableStateOf
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits

class CutsceneEditorSession {
    val playback = CutscenePlaybackController()
    val timeline = playback.timeline

    val uiRevision = mutableStateOf(0)

    fun invalidateUi() {
        uiRevision.value++
    }

    init {
        timeline.onChanged = {
            syncPlaybackFromTimeline()
            invalidateUi()
        }
        timeline.onTimeChanged = {
            syncPlaybackFromTimeline()
            invalidateUi()
        }
        timeline.onPreviewChanged = {
            updatePreviewState()
            invalidateUi()
        }
        timeline.captureExtraState = { playback.origin }
        timeline.restoreExtraState = { state -> (state as? CutsceneOrigin)?.let { playback.origin = it } }
    }

    val authoringFrame: CutsceneFrame get() = playback.origin.frame

    fun update(deltaSeconds: Float) {
        timeline.onUpdate(deltaSeconds)
        syncPlaybackFromTimeline()
    }

    fun captureFrame(time: Float) {
        val minecraft = Minecraft.getInstance()
        val pose = CutsceneCameraSystem.capturePlayerPose(minecraft) ?: return
        val environment = minecraft.level?.captureCutsceneEnvironment() ?: return
        val frame = authoringFrame
        timeline.edit(TimelineEdits.CAPTURE_KEY) {
            timeline.clearSelection()
            val position = frame.toLocal(pose.position)
            writeChannels(playback.translation, time, listOf(position.x, position.y, position.z))
            writeChannels(playback.rotation, time, frame.toLocalRotation(pose.rotation).decomposedBy(playback.rotation))
            writeChannels(playback.fov, time, listOf(pose.fov))
            writeChannels(playback.timeOfDay, time, listOfNotNull(environment.timeOfDay))
            writeChannels(playback.weather, time, listOfNotNull(environment.weather?.value))
        }
        playback.seek(time)
        updatePreviewState()
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
        timeline.edit(if (keepWorld) TimelineEdits.REANCHOR_CUTSCENE else TimelineEdits.MOVE_CUTSCENE) {
            if (keepWorld) playback.reanchor(position, yaw)
            else playback.origin = playback.origin.moved(position, yaw)
        }
        syncPlaybackFromTimeline()
        invalidateUi()
    }

    /** Puts the origin on the player standing in the world, the usual way to place a scene. */
    fun originToPlayer(keepWorld: Boolean) {
        val player = Minecraft.getInstance().player ?: return
        moveOrigin(Vec3f(player.x.toFloat(), player.y.toFloat(), player.z.toFloat()), player.yRot, keepWorld)
    }

    fun onHollowUiKey(key: Int, modifiers: Int): Boolean {
        val handled = TimelineKeys.handle(timeline, key, modifiers)
        if (handled) invalidateUi()
        return handled
    }

    fun syncPlaybackFromTimeline() {
        playback.updateProperties()
        updatePreviewState()
    }

    fun updatePreviewState() {
        if (timeline.isCameraPreviewEnabled) {
            CutsceneCameraSystem.preview(playback)
        } else if (CutsceneCameraSystem.activeController === playback) {
            CutsceneCameraSystem.stop()
        }
    }

    fun exportCutscene(path: String, name: String) {
        CutsceneStorage.save(path, name, playback.toData(name))
    }

    fun importCutscene(readablePath: String) {
        val data = CutsceneStorage.load(readablePath)
        playback.setupTracks(data)
        timeline.isPlaying = false
        timeline.applyCurrentTime(0f)
        timeline.clearSelection()
        timeline.clearHistory()
        updatePreviewState()
    }

    fun channelValues(property: AnimProperty<*>): List<Float> = property.decomposeAt(timeline.currentTime).toList()
}


object CutsceneEditorSessions {
    val default = CutsceneEditorSession()
}
