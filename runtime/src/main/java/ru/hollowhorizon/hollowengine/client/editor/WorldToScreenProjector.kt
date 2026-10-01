package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeScale
import kotlin.math.tan

private const val NEAR_PLANE_EPSILON = 0.05f

/**
 * A world point projected into the HollowUi overlay's logical coordinate space.
 */
data class ScreenPoint(
    val x: Float,
    val y: Float,
    val depth: Float,
    val onScreen: Boolean,
)

/** A world-space ray reconstructed by un-projecting a screen point. */
data class WorldRay(
    val origin: Vec3,
    val direction: Vec3,
)

/**
 * Projects points of one camera into the logical pixels of the surface it is shown on, and back into
 * rays. The world has one ([WorldToScreenProjector]); a preview panel that draws the same gizmo keeps
 * its own, captured from its own camera and sized to the panel.
 */
open class GizmoProjector {
    private val combined = Matrix4f()
    private val inverse = Matrix4f()
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var fovYRadians = 1.22f
    private var logicalWidth = 1f
    private var logicalHeight = 1f


    private val scratch = Vector4f()

    /**
     * Takes the camera of the next frame. [view] is the rotation of the camera alone: points are made
     * relative to [cameraPosition] before it applies, which keeps world coordinates precise.
     */
    fun capture(
        view: Matrix4f,
        projection: Matrix4f,
        cameraPosition: Vec3,
        fovDegrees: Float,
        width: Float,
        height: Float,
    ) {
        combined.set(projection).mul(view)
        combined.invert(inverse)
        camX = cameraPosition.x
        camY = cameraPosition.y
        camZ = cameraPosition.z
        fovYRadians = Math.toRadians(fovDegrees.toDouble()).toFloat()
        logicalWidth = width.coerceAtLeast(1f)
        logicalHeight = height.coerceAtLeast(1f)
    }

    val width: Float get() = logicalWidth
    val height: Float get() = logicalHeight
    val cameraPosition: Vec3 get() = Vec3(camX, camY, camZ)

    fun screenCenter(): Pair<Float, Float> = logicalWidth * 0.5f to logicalHeight * 0.5f

    fun project(world: Vec3): ScreenPoint? = project(world.x, world.y, world.z)

    fun project(worldX: Double, worldY: Double, worldZ: Double): ScreenPoint {
        scratch.set((worldX - camX).toFloat(), (worldY - camY).toFloat(), (worldZ - camZ).toFloat(), 1f)
        combined.transform(scratch)
        val w = scratch.w
        if (!w.isFinite() || w <= NEAR_PLANE_EPSILON) return ScreenPoint(0f, 0f, w, onScreen = false)
        val ndcX = scratch.x / w
        val ndcY = scratch.y / w
        val screenX = (ndcX * 0.5f + 0.5f) * logicalWidth
        val screenY = (0.5f - ndcY * 0.5f) * logicalHeight
        if (!screenX.isFinite() || !screenY.isFinite()) return ScreenPoint(0f, 0f, w, onScreen = false)
        return ScreenPoint(screenX, screenY, depth = w, onScreen = true)
    }

    /** Un-projects a logical screen point into a world-space ray (origin on the near plane). */
    fun screenRay(screenX: Float, screenY: Float): WorldRay? {
        val ndcX = screenX / logicalWidth * 2f - 1f
        val ndcY = 1f - screenY / logicalHeight * 2f
        val near = unproject(ndcX, ndcY, -1f) ?: return null
        val far = unproject(ndcX, ndcY, 1f) ?: return null
        val dir = far.subtract(near)
        val length = dir.length()
        if (length < 1.0e-9) return null
        return WorldRay(near, dir.scale(1.0 / length))
    }

    private fun unproject(ndcX: Float, ndcY: Float, ndcZ: Float): Vec3? {
        scratch.set(ndcX, ndcY, ndcZ, 1f)
        inverse.transform(scratch)
        val w = scratch.w
        if (w == 0f) return null
        return Vec3(
            scratch.x / w + camX,
            scratch.y / w + camY,
            scratch.z / w + camZ,
        )
    }

    open fun worldPerPixel(world: Vec3): Float {
        if (logicalHeight <= 0f) return 0.05f
        val distance = world.distanceTo(cameraPosition).toFloat()
        val worldHeightAtDepth = 2f * tan(fovYRadians * 0.5f) * distance
        return (worldHeightAtDepth / logicalHeight).coerceAtLeast(1.0e-5f)
    }
}

/** The projector of the game camera, in the logical pixels of the IDE overlay. */
object WorldToScreenProjector : GizmoProjector() {
    fun capture(view: Matrix4f, projection: Matrix4f, cameraPosition: Vec3, fovDegrees: Float) {
        val target = Minecraft.getInstance().mainRenderTarget
        val scale = HollowIdeScale.factor()
        capture(view, projection, cameraPosition, fovDegrees, target.width / scale, target.height / scale)
    }
}
