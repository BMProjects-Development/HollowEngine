package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.client.ui.SpringFloat
import ru.hollowhorizon.hollowengine.client.ui.UiEvent
import ru.hollowhorizon.hollowengine.client.ui.isShiftDown
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.max

internal enum class CutsceneCameraMode(val key: String) { ORBIT("orbit"), FLY("fly") }

internal class CutsceneViewportInput(private val session: CutsceneEditorSession) {
    var mode by mutableStateOf(CutsceneCameraMode.ORBIT)
        private set
    var focused = false
    private var lastFlightFrame = 0L
    private var navigation: CutsceneCameraNavigation? = null
    private var gesture: Gesture? = null
    private var zoom: Dolly? = null
    private val dollySpring = SpringFloat(0f, rest = 0.001f)

    fun changeMode(next: CutsceneCameraMode) {
        mode = next
        cancelGestures()
        lastFlightFrame = 0L
    }

    fun begin(event: UiEvent): Boolean {
        val fly = mode == CutsceneCameraMode.FLY
        val button = if (fly) GLFW.GLFW_MOUSE_BUTTON_RIGHT else GLFW.GLFW_MOUSE_BUTTON_MIDDLE
        if (event.button != button) return false
        session.timeline.isPlaying = false
        zoom = null
        gesture = Gesture(
            button,
            if (fly) currentNavigation() else pick(event),
            event.isShiftDown(),
            event.height,
            session.timeline.currentTime,
        )
        return true
    }

    fun drag(event: UiEvent): Boolean {
        val start =
            gesture?.takeIf { it.button == event.button && it.time == session.timeline.currentTime } ?: return false
        val x = event.dragTotalX
        val y = event.dragTotalY
        val view = when {
            mode == CutsceneCameraMode.FLY -> {
                val pose = start.view.pose.look(x, y).copy(position = session.previewPose.position)
                start.view.copy(pose = pose)
            }

            start.pan -> start.view.pan(x, y, start.height)
            else -> start.view.orbit(x, y)
        }
        apply(view)
        return true
    }

    fun release(button: Int) {
        if (gesture?.button == button) gesture = null
    }

    fun scroll(event: UiEvent): Boolean {
        if (mode != CutsceneCameraMode.ORBIT || gesture != null || event.rawScrollY == 0f) return false
        session.timeline.isPlaying = false
        val running = zoom?.takeIf { navigation?.pose == session.cameraDraft.value }
        val next = running ?: Dolly(pick(event), pointerRay(event)).also {
            zoom = it
            dollySpring.snapTo(0f)
            navigation = it.start
            session.cameraDraft.value = it.start.pose
        }
        val step = max(next.start.distance * DOLLY_FRACTION, MIN_DOLLY_STEP)
        dollySpring.target += event.rawScrollY * step
        return true
    }

    fun advanceDolly(now: Long) {
        dollySpring.advance(now)
        val moving = zoom ?: return
        if (navigation?.pose != session.cameraDraft.value || session.timeline.isPlaying) {
            zoom = null
            return
        }
        apply(moving.start.dolly(moving.direction, dollySpring.value))
        if (dollySpring.value == dollySpring.target) zoom = null
    }

    /** The render thread owns GLFW input polling, including for a detached viewport. */
    fun pollFlight(window: Long, enabled: Boolean) {
        if (!enabled || mode != CutsceneCameraMode.FLY || !focused || window == 0L || GLFW.glfwGetWindowAttrib(
                window,
                GLFW.GLFW_FOCUSED
            ) != GLFW.GLFW_TRUE
        ) {
            lastFlightFrame = 0L
            return
        }
        val now = System.nanoTime()
        val previous = lastFlightFrame
        lastFlightFrame = now
        if (previous == 0L) return
        fun down(key: Int) = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS
        fun axis(positive: Int, negative: Int) = (if (down(positive)) 1f else 0f) - (if (down(negative)) 1f else 0f)
        if (down(GLFW.GLFW_KEY_LEFT_CONTROL) || down(GLFW.GLFW_KEY_RIGHT_CONTROL) || down(GLFW.GLFW_KEY_LEFT_ALT) || down(
                GLFW.GLFW_KEY_RIGHT_ALT
            )
        ) return
        val horizontal = axis(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A)
        val forward = axis(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S)
        val ascending = down(GLFW.GLFW_KEY_SPACE) || down(GLFW.GLFW_KEY_E)
        val descending = down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT) || down(GLFW.GLFW_KEY_Q)
        val vertical = (if (ascending) 1f else 0f) - (if (descending) 1f else 0f)
        if (horizontal == 0f && forward == 0f && vertical == 0f) return
        val pose = session.previewPose
        val direction = (pose.direction(horizontal, 0f, -forward) + Vec3f(0f, vertical, 0f)).normed()
        val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.1f)
        session.timeline.isPlaying = false
        session.cameraDraft.value = pose.copy(position = pose.position + direction * (FLY_SPEED * seconds))
    }

    fun reset() {
        cancelGestures()
        navigation = null
        session.cameraDraft.value = null
    }

    private fun cancelGestures() {
        gesture = null
        zoom = null
    }

    private fun pick(event: UiEvent): CutsceneCameraNavigation {
        val pose = session.previewPose
        val previous = navigation?.takeIf { it.pose == session.cameraDraft.value }
        return CutsceneCameraNavigation.aroundPointer(pose, pointerRay(event), previous?.pivot, Minecraft.getInstance())
    }

    private fun pointerRay(event: UiEvent): Vec3f = session.previewPose.viewportRay(
        (event.localX / event.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
        (event.localY / event.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
        event.width.coerceAtLeast(1f) / event.height.coerceAtLeast(1f),
    )

    private fun currentNavigation(): CutsceneCameraNavigation {
        val pose = session.previewPose
        return CutsceneCameraNavigation(pose, pose.position)
    }

    private fun apply(view: CutsceneCameraNavigation) {
        navigation = view
        session.cameraDraft.value = view.pose
    }

    private data class Gesture(
        val button: Int,
        val view: CutsceneCameraNavigation,
        val pan: Boolean,
        val height: Float,
        val time: Float,
    )

    private data class Dolly(val start: CutsceneCameraNavigation, val direction: Vec3f)

    private companion object {
        const val DOLLY_FRACTION = 0.15f
        const val MIN_DOLLY_STEP = 0.15f
        const val FLY_SPEED = 8f
    }
}
