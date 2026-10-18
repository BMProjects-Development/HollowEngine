package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.ui.SpringZoom
import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.OrbitCamera
import ru.hollowhorizon.hollowengine.client.ui.widgets.anglesAround
import ru.hollowhorizon.hollowengine.client.ui.widgets.orbitTo
import ru.hollowhorizon.hollowengine.client.vfx.VfxBudget
import ru.hollowhorizon.hollowengine.client.vfx.VfxInstance
import ru.hollowhorizon.hollowengine.client.vfx.VfxPlaneEnvironment
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import kotlin.math.cos
import kotlin.math.sin

/** The vertical field of view of the preview camera, in degrees. */
internal const val VfxPreviewFieldOfView = 50f

/**
 * The preview of the effect being edited: an orbit camera with perspective, a floor, and one
 * playing instance.
 */
@Stable
class VfxPreviewState(initialDistance: Float = 4.5f) : OrbitCamera {
    override var yaw by mutableStateOf(35f)
    override var pitch by mutableStateOf(22f)
    override val pitchLimit: Float get() = 89f
    private val zoomSpring = SpringZoom(initialDistance, MIN_DISTANCE, MAX_DISTANCE, perNotch = ZOOM_PER_NOTCH)
    val distance: Float get() = zoomSpring.value
    var targetX by mutableStateOf(0f)
    var targetY by mutableStateOf(0.7f)
    var targetZ by mutableStateOf(0f)

    var showFloor by mutableStateOf(true)
    var showShape by mutableStateOf(true)

    /** The viewport size in panel pixels, which gizmo projects into. */
    var viewportWidth by mutableStateOf(0f)
    var viewportHeight by mutableStateOf(0f)

    /** How far the effect has been simulated. */
    var time by mutableStateOf(0f)
        private set

    /** Where the timeline of the effect is, which the rules of its sections may have taken off [time]. */
    var timelineTime by mutableStateOf(0f)
        private set

    /** What the effect reads as `d.` in the preview, where there is no handle; see [PlaybackPreview]. */
    var data: Map<String, Float> = emptyMap()
        set(value) {
            field = value
            instance?.useData(value)
        }

    /** Bumped whenever [instance] is replaced, so what reads its nodes knows to look again. */
    var revision by mutableStateOf(0)
        private set

    /** Particles alive, refreshed by the editor clock rather than read from the instance by the UI. */
    var liveParticles by mutableStateOf(0)
        private set

    var instance: VfxInstance? = null
        private set

    /** What the editor holds over the timeline; see [VfxInstance.held]. */
    var held: (String, VfxProperty) -> FloatArray? = { _, _ -> null }

    /** Places the effect again, after what is held changed. */
    fun repose() {
        instance?.repose()
    }

    private var builtFor: VfxEffect? = null
    private var lastFrameNanos = 0L

    /**
     * Brings the playing instance in line with [effect]. Returns whether it had to be rebuilt.
     */
    fun sync(effect: VfxEffect, asset: String): Boolean {
        if (builtFor === effect) return false
        val playing = instance
        val previous = builtFor
        builtFor = effect
        if (playing != null && previous != null && playing.adoptPlacement(effect)) {
            revision++
            return false
        }

        val at = timelineTime
        instance = VfxInstance(effect, asset, VfxPlaneEnvironment(0.0), seed = PREVIEW_SEED).also {
            it.held = { node, property -> held(node, property) }
            it.useData(data)
            it.update(0f)
        }
        revision++
        time = 0f
        lastFrameNanos = 0L
        if (at in 0f..CATCH_UP_LIMIT) seek(at) else refreshCount()
        return true
    }

    fun restart() {
        instance?.restart()
        time = 0f
        timelineTime = 0f
        lastFrameNanos = 0L
        refreshCount()
    }

    /**
     * Seconds since the previous frame of the editor.
     */
    fun deltaSinceLastFrame(frameNanos: Long): Float {
        val previous = lastFrameNanos
        lastFrameNanos = frameNanos
        if (previous == 0L) return 0f
        return ((frameNanos - previous) / NANOS_PER_SECOND).toFloat().coerceIn(0f, MAX_STEP)
    }

    /**
     * Moves the effect to [seconds].
     */
    fun seek(seconds: Float) {
        val target = seconds.coerceAtLeast(0f)
        instance?.seek(time, target, MAX_CATCH_UP_STEPS, advance = ::advanceBy)
        time = target
        timelineTime = instance?.timelineTime ?: target
        refreshCount()
    }

    private var pendingSeek = Float.NaN

    /**
     * Asks for [seek] on the next frame.
     */
    fun requestSeek(seconds: Float) {
        pendingSeek = seconds
    }

    /** Runs the seek asked for since the last frame, if any; returns whether there was one. */
    fun applyPendingSeek(): Boolean {
        val target = pendingSeek
        if (target.isNaN()) return false
        pendingSeek = Float.NaN
        seek(target)
        return true
    }

    /** Advances the effect by [dt] seconds. */
    fun advanceBy(dt: Float) {
        val playing = instance ?: return

        val eye = cameraBasis().eye
        playing.camera.set(eye.x, eye.y, eye.z)
        playing.partialTick = TickHandler.partialTick
        playing.gameTime = TickHandler.clientFrame + TickHandler.partialTick
        playing.budget = VfxBudget.share(1)
        playing.update(dt)
        time = playing.time
        timelineTime = playing.timelineTime
        refreshCount()
    }

    private fun refreshCount() {
        val count = instance?.particleCount ?: 0
        if (count != liveParticles) liveParticles = count
    }

    private var dragYaw = 0f
    private var dragPitch = 0f
    private val dragTarget = Vector3f()

    /** Remembers the camera as it was when a drag started; drag is measured from here. */
    fun beginCameraDrag() {
        dragYaw = yaw
        dragPitch = pitch
        dragTarget.set(targetX, targetY, targetZ)
    }

    fun orbit(totalX: Float, totalY: Float) =
        orbitTo((dragYaw - totalX * ORBIT_SPEED) % 360f, (dragPitch + totalY * ORBIT_SPEED).coerceIn(-89f, 89f))

    /** Where the selected node is, in effect space; see [OrbitCamera.selectionCenter]. */
    var selection: () -> Vec3f? = { null }

    override val selectionCenter: Vec3f? get() = selection()

    override fun turnTo(yaw: Float, pitch: Float, around: Vec3f?) {
        val before = around?.let { cameraBasis() }
        this.yaw = yaw
        this.pitch = pitch
        if (around == null || before == null) return
        val after = cameraBasis()
        val offset = Vector3f(targetX - around.x, targetY - around.y, targetZ - around.z)
        val turned = Vector3f(after.right).mul(offset.dot(before.right))
            .add(Vector3f(after.up).mul(offset.dot(before.up)))
            .add(Vector3f(after.forward).mul(offset.dot(before.forward)))
        targetX = around.x + turned.x
        targetY = around.y + turned.y
        targetZ = around.z + turned.z
    }

    override fun focus(point: Vec3f) {
        targetX = point.x
        targetY = point.y
        targetZ = point.z
    }

    /** Slides point, that camera circles around, along the screen, by a share of the distance. */
    fun pan(totalX: Float, totalY: Float) {
        val basis = cameraBasis()
        val scale = distance * PAN_SPEED
        targetX = dragTarget.x + (-basis.right.x * totalX + basis.up.x * totalY) * scale
        targetY = dragTarget.y + (-basis.right.y * totalX + basis.up.y * totalY) * scale
        targetZ = dragTarget.z + (-basis.right.z * totalX + basis.up.z * totalY) * scale
    }

    /** One wheel notch, scrolling up moves the camera back. */
    fun zoom(scrollY: Float) = zoomSpring.scroll(scrollY)

    fun advanceCamera(frameNanos: Long) = zoomSpring.advance(frameNanos)

    class Basis(val eye: Vector3f, val right: Vector3f, val up: Vector3f, val forward: Vector3f)

    override fun toView(world: Vec3f): Vec3f {
        val basis = cameraBasis()
        val direction = Vector3f(world.x, world.y, world.z)
        return Vec3f(direction.dot(basis.right), direction.dot(basis.up), -direction.dot(basis.forward))
    }

    override fun anglesFor(back: Vec3f): Pair<Float, Float> = anglesAround(back, yawSign = 1f, yawOffset = 0f)

    /** Where the camera is and where it looks, in effect space. */
    fun cameraBasis(): Basis {
        val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
        val back = Vector3f(cos(pitchRad) * sin(yawRad), sin(pitchRad), cos(pitchRad) * cos(yawRad))
        val eye = Vector3f(targetX, targetY, targetZ).add(Vector3f(back).mul(distance))
        val forward = Vector3f(back).negate()
        val right = Vector3f(forward).cross(Vector3f(0f, 1f, 0f)).normalize()
        val up = Vector3f(right).cross(forward).normalize()
        return Basis(eye, right, up, forward)
    }

    /** The camera, turned by [shake] degrees of pitch, yaw and roll when there is a shake to show. */
    fun viewMatrix(shake: FloatArray? = null): Matrix4f {
        val basis = cameraBasis()
        val look = Matrix4f().setLookAt(
            basis.eye.x, basis.eye.y, basis.eye.z,
            targetX, targetY, targetZ,
            0f, 1f, 0f,
        )
        if (shake == null) return look
        return Matrix4f()
            .rotateZ(Math.toRadians(shake[2].toDouble()).toFloat())
            .rotateX(Math.toRadians(shake[0].toDouble()).toFloat())
            .rotateY(Math.toRadians(shake[1].toDouble()).toFloat())
            .mul(look)
    }

    fun perspective(width: Float, height: Float): Matrix4f =
        Matrix4f().setPerspective(FIELD_OF_VIEW, (width / height.coerceAtLeast(1f)).coerceAtLeast(0.01f), NEAR, FAR)

    /**
     * Normalized device coordinates to panel pixels.
     */
    private fun pixels(rect: UiRect): Matrix4f {
        val halfWidth = rect.width / 2f
        val halfHeight = rect.height / 2f
        return Matrix4f(
            halfWidth, 0f, 0f, 0f,
            0f, -halfHeight, 0f, 0f,
            0f, 0f, -PANEL_DEPTH, 0f,
            rect.x + halfWidth, rect.y + halfHeight, 0f, 1f,
        )
    }

    /** Effect space to panel pixels, before the perspective division. */
    fun panelMatrix(width: Float, height: Float): Matrix4f =
        pixels(UiRect(0f, 0f, width, height)).mul(perspective(width, height)).mul(viewMatrix())

    /** Where [point] lands in the panel, or null when it is behind the camera. */
    fun project(matrix: Matrix4f, point: Vector3f): Vector3f? {
        val clip = matrix.transform(Vector4f(point, 1f))
        if (clip.w <= NEAR) return null
        return Vector3f(clip.x / clip.w, clip.y / clip.w, clip.w)
    }

    private val renderer = VfxPreviewRenderer()

    /**
     * Draws the floor and the effect into the preview's own target, then that target onto the node,
     * rounded as the node is, so the preview sits in a rounded panel like the rest of the IDE.
     */
    fun draw(scope: UiCanvasDrawScope) {
        scope.drawGl { render(rect, poseStack) }
        scope.drawTexture(scope.bounds, { renderer.texture }, flipY = true, opaque = true, radius = scope.cornerRadius)
    }

    private fun render(rect: UiRect, stack: PoseStack) {
        val playing = instance ?: return
        if (rect.width <= 1f || rect.height <= 1f) return
        renderer.render(this, playing, rect, stack)
    }

    /** Frees the target the preview draws into. */
    fun close() = renderer.close()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MAX_STEP = 0.25f

        const val PREVIEW_SEED = 0x5EED

        val FIELD_OF_VIEW = Math.toRadians(VfxPreviewFieldOfView.toDouble()).toFloat()
        const val NEAR = 0.05f
        const val FAR = 1024f

        const val PANEL_DEPTH = 500f

        const val ORBIT_SPEED = 0.35f
        const val PAN_SPEED = 0.0025f
        const val MIN_DISTANCE = 0.3f
        const val MAX_DISTANCE = 60f
        const val ZOOM_PER_NOTCH = 1.1f

        const val MAX_CATCH_UP_STEPS = 90

        const val CATCH_UP_LIMIT = 4f
    }
}
