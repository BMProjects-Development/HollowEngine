package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.editor.GizmoDrag
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoGeometry
import ru.hollowhorizon.hollowengine.client.editor.GizmoHandle
import ru.hollowhorizon.hollowengine.client.editor.GizmoHandleId
import ru.hollowhorizon.hollowengine.client.editor.GizmoKeyboardTransform
import ru.hollowhorizon.hollowengine.client.editor.GizmoManipulator
import ru.hollowhorizon.hollowengine.client.editor.GizmoPicker
import ru.hollowhorizon.hollowengine.client.editor.GizmoProjector
import ru.hollowhorizon.hollowengine.client.editor.GizmoRenderer
import ru.hollowhorizon.hollowengine.client.editor.GizmoSnapping
import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope
import ru.hollowhorizon.hollowengine.client.ui.widgets.ModelViewerState
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec

/**
 * The transform gizmo over the rig preview, moving the selected collider: the handles and drag math of
 * the world's gizmo, projected through the preview's camera, which has no perspective.
 */
internal class RigColliderGizmo {
    private val projector = OrthographicProjector()
    private val geometry = GizmoGeometry(projector)
    private val manipulator = GizmoManipulator(projector, GizmoSnapping.MODEL)

    /** Takes the preview's camera; false while the panel has not been laid out yet. */
    fun capture(viewer: ModelViewerState): Boolean {
        val width = viewer.panelWidth
        val height = viewer.panelHeight
        if (width <= 1f || height <= 1f || viewer.pixelsPerUnit <= 0f) return false

        val panel = viewer.panelMatrix()
        val camera = panel.invert(Matrix4f()).transformPosition(Vector3f(width / 2f, height / 2f, DEPTH / 2f))
        val toNdc = Matrix4f().set(
            2f / width, 0f, 0f, 0f,
            0f, -2f / height, 0f, 0f,
            0f, 0f, -1f / DEPTH, 0f,
            -1f, 1f, 0f, 1f,
        )
        projector.capture(
            view = Matrix4f(panel).translate(camera),
            projection = toNdc,
            cameraPosition = Vec3(camera.x.toDouble(), camera.y.toDouble(), camera.z.toDouble()),
            fovDegrees = 60f,
            width = width,
            height = height,
        )
        projector.unitsPerPixel = 1f / viewer.pixelsPerUnit
        return true
    }

    fun handles(frame: ColliderFrame, mode: GizmoEditMode): List<GizmoHandle> {
        val modes = if (frame.isWorldAligned && mode == GizmoEditMode.ROTATE) emptySet() else setOf(mode)
        return geometry.buildHandles(frame.values.translation, frame.values.rotation, modes)
    }

    fun pick(handles: List<GizmoHandle>, x: Float, y: Float): GizmoHandle? = GizmoPicker.pick(handles, x, y)

    fun begin(handle: GizmoHandle, frame: ColliderFrame, x: Float, y: Float): GizmoDrag =
        manipulator.begin(handle, frame.values, x, y)

    /** The collider [drag] has brought [frame] to, or null while nothing moved. */
    fun drag(drag: GizmoDrag, frame: ColliderFrame, x: Float, y: Float, modifiers: Int): ColliderAttachmentSpec? =
        manipulator.update(drag, x, y, modifiers)?.let(frame::with)

    /** A transform from the keyboard on [frame], starting where the pointer is. */
    fun keyboard(mode: GizmoEditMode, frame: ColliderFrame, x: Float, y: Float): GizmoKeyboardTransform =
        GizmoKeyboardTransform(mode, frame.values, x, y, projector, geometry, manipulator)

    /** The segment under the pointer, through the whole model, for picking colliders. */
    fun ray(x: Float, y: Float): Pair<Vec3, Vec3>? {
        val ray = projector.screenRay(x, y) ?: return null
        return ray.origin to ray.origin.add(ray.direction.scale(RAY_LENGTH))
    }

    fun draw(scope: UiCanvasDrawScope, handles: List<GizmoHandle>, hovered: GizmoHandleId?, drag: GizmoDrag?) {
        drag?.let { GizmoRenderer.drawRotationSector(scope, geometry, projector, it) }
        GizmoRenderer.drawHandles(scope, handles, hovered, drag?.handleId)
    }

    private class OrthographicProjector : GizmoProjector() {
        var unitsPerPixel = 0.01f

        override fun worldPerPixel(world: Vec3): Float = unitsPerPixel
    }

    private companion object {
        /** How deep the preview's clip space reaches, in panel pixels, toward and away from the viewer. */
        const val DEPTH = 100_000f
        const val RAY_LENGTH = 10_000.0
    }
}

/** A transform from the keyboard on one collider: which one, where it was, and the transform itself. */
internal class ColliderTransform(
    val selection: ColliderSelection,
    val frame: ColliderFrame,
    val keyboard: GizmoKeyboardTransform,
)
