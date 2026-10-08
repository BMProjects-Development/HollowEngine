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
import ru.hollowhorizon.hollowengine.client.editor.GizmoTransformValues
import ru.hollowhorizon.hollowengine.client.ui.widgets.ModelViewerState
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.PlacedAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import kotlin.math.abs

/**
 * The transform gizmo over the rig preview, moving the selected part, a collider or anything placed by an
 * offset such as an IK target: the handles and drag math of
 * the world's gizmo, projected through the preview's camera, which has no perspective.
 */
internal class RigPartGizmo {
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

    fun handles(frame: RigGizmoFrame, mode: GizmoEditMode): List<GizmoHandle> {
        val modes = if (frame.isWorldAligned && mode == GizmoEditMode.ROTATE) emptySet() else setOf(mode)
        return geometry.buildHandles(frame.values.translation, frame.values.rotation, modes)
    }

    fun pick(handles: List<GizmoHandle>, x: Float, y: Float): GizmoHandle? = GizmoPicker.pick(handles, x, y)

    fun begin(handle: GizmoHandle, frame: RigGizmoFrame, x: Float, y: Float): GizmoDrag =
        manipulator.begin(handle, frame.values, x, y)

    /** The part [drag] has brought [frame] to, or null while nothing moved. */
    fun drag(drag: GizmoDrag, frame: RigGizmoFrame, x: Float, y: Float, modifiers: Int): RigAttachmentSpec? =
        manipulator.update(drag, x, y, modifiers)?.let(frame::with)

    /** A transform from the keyboard on [frame], starting where the pointer is. */
    fun keyboard(mode: GizmoEditMode, frame: RigGizmoFrame, x: Float, y: Float): GizmoKeyboardTransform =
        GizmoKeyboardTransform(mode, frame.values, x, y, projector, geometry, manipulator)

    /** The segment under the pointer, through the whole model, for picking colliders. */
    fun ray(x: Float, y: Float): Pair<Vec3, Vec3>? {
        val ray = projector.screenRay(x, y) ?: return null
        return ray.origin to ray.origin.add(ray.direction.scale(RAY_LENGTH))
    }

    /** Where a point in model space shows in the preview, in panel pixels. */
    fun screenOf(point: Vec3f): Pair<Float, Float> =
        projector.project(point.x.toDouble(), point.y.toDouble(), point.z.toDouble()).let { it.x to it.y }

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

/** A transform from the keyboard on one part: which one, where it was, and the transform itself. */
internal class RigPartTransform(
    val selection: RigPartSelection,
    val frame: RigGizmoFrame,
    val keyboard: GizmoKeyboardTransform,
)

/**
 * A part as the gizmo sees it: where it stands and how it is turned in model space, and its size. [with]
 * goes back to the fields of the part, in the space of what it hangs on.
 */
internal interface RigGizmoFrame {
    /** The part as it was when the frame was taken, which a cancelled transform puts back. */
    val spec: RigAttachmentSpec

    val values: GizmoTransformValues

    /** Whether the part keeps to the world's axes, so it has nothing to turn. */
    val isWorldAligned: Boolean get() = false

    fun with(values: GizmoTransformValues): RigAttachmentSpec
}

/** Anything placed by an offset, a turn and an even scale on what it hangs on: a target, an effect, a model. */
internal class PlacedFrame(override val spec: RigAttachmentSpec, holder: Mat4f) : RigGizmoFrame {
    private val placed = spec as PlacedAttachmentSpec
    private val holder = MutableMat4f(holder)
    private val holderRotation: QuatF = holder.getRotation(MutableQuatF()).norm()

    override val values: GizmoTransformValues = GizmoTransformValues(
        translation = holder.transform(placed.offset, 1f, MutableVec3f()),
        rotation = holderRotation * eulerRotationXyz(placed.rotation),
        scale = Vec3f(placed.scale, placed.scale, placed.scale),
    )

    override fun with(values: GizmoTransformValues): RigAttachmentSpec {
        val inverse = MutableMat4f(holder)
        if (!inverse.invert()) return spec
        val scale = listOf(values.scale.x, values.scale.y, values.scale.z).maxBy { abs(it - placed.scale) }
        return placed.placedAt(
            offset = inverse.transform(values.translation, 1f, MutableVec3f()),
            rotation = (holderRotation.inverted() * values.rotation).eulerDegreesXyz(),
            scale = abs(scale).coerceAtLeast(MIN_SCALE),
        )
    }

    private companion object {
        const val MIN_SCALE = 0.01f
    }
}

/** The gizmo's view of [spec] hanging on [holder], or null for a part the gizmo does not move. */
internal fun gizmoFrame(spec: RigAttachmentSpec, holder: Mat4f): RigGizmoFrame? = when (spec) {
    is ColliderAttachmentSpec -> ColliderFrame(spec, holder)
    is PlacedAttachmentSpec -> PlacedFrame(spec, holder)
    else -> null
}

/** Whether the gizmo moves [spec], so selecting it in the preview makes sense. */
internal fun hasGizmo(spec: RigAttachmentSpec): Boolean = spec is ColliderAttachmentSpec || spec is PlacedAttachmentSpec
