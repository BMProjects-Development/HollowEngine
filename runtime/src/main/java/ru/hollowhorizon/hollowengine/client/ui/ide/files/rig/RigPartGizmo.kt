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
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigBoneOrigin
import ru.hollowhorizon.hollowengine.common.models.RigPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
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

    /** Where [drag] has brought the part, or null while nothing moved. */
    fun drag(drag: GizmoDrag, x: Float, y: Float, modifiers: Int): GizmoTransformValues? =
        manipulator.update(drag, x, y, modifiers)

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
 * A part as the gizmo sees it: where it stands and how it is turned in model space, and its size. It writes
 * itself back into the rig, in the space of what it hangs on.
 */
internal interface RigGizmoFrame {
    val values: GizmoTransformValues

    /** Whether the part keeps to the world's axes, so it has nothing to turn. */
    val isWorldAligned: Boolean get() = false

    /** [rig] with the part moved to [values]. */
    fun placed(rig: ModelRig, values: GizmoTransformValues): ModelRig

    /** [rig] with the part back where it was when the frame was taken, as a cancelled transform leaves it. */
    fun restored(rig: ModelRig): ModelRig
}

/** Something hung on a bone, the attachment [selection] names, as it was: [spec]. */
internal abstract class AttachmentFrame(private val selection: RigPartSelection) : RigGizmoFrame {
    abstract val spec: RigAttachmentSpec

    /** The attachment moved to [values]. */
    abstract fun with(values: GizmoTransformValues): RigAttachmentSpec

    override fun placed(rig: ModelRig, values: GizmoTransformValues): ModelRig = write(rig, with(values))

    override fun restored(rig: ModelRig): ModelRig = write(rig, spec)

    private fun write(rig: ModelRig, attachment: RigAttachmentSpec): ModelRig =
        rig.withHolder(selection.bone, rig.holder(selection.bone).withAttachment(selection.id, attachment))
}

/** Anything placed by an offset, a turn and an even scale on what it hangs on: a target, an effect, a model. */
internal class PlacedFrame(selection: RigPartSelection, override val spec: RigAttachmentSpec, holder: Mat4f) : AttachmentFrame(selection) {
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
        // A drag along one axis scales evenly: the axis that moved furthest is the one the drag was on.
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

/** Where a bone the rig adds stands under its parent, at [parent] in model space. It has no size to scale. */
internal class BoneOriginFrame(private val bone: String, private val origin: RigBoneOrigin, parent: Mat4f) : RigGizmoFrame {
    private val parent = MutableMat4f(parent)
    private val parentRotation: QuatF = parent.getRotation(MutableQuatF()).norm()

    override val values: GizmoTransformValues = GizmoTransformValues(
        translation = parent.transform(origin.offset, 1f, MutableVec3f()),
        rotation = parentRotation * eulerRotationXyz(origin.rotation),
        scale = Vec3f.ONES,
    )

    override fun placed(rig: ModelRig, values: GizmoTransformValues): ModelRig {
        val inverse = MutableMat4f(parent)
        if (!inverse.invert()) return rig
        return write(rig, origin.copy(
            offset = inverse.transform(values.translation, 1f, MutableVec3f()),
            rotation = (parentRotation.inverted() * values.rotation).eulerDegreesXyz(),
        ))
    }

    override fun restored(rig: ModelRig): ModelRig = write(rig, origin)

    private fun write(rig: ModelRig, moved: RigBoneOrigin): ModelRig = rig.withBone(bone, rig.holder(bone).copy(origin = moved))
}

/**
 * A bone of the model, moved by its pose over whatever animates it. What the animation does to the bone is
 * taken once, when the frame is: drag events come faster than frames, and the drawn bone lags a step behind
 * the pose being written.
 */
internal class BonePoseFrame(private val bone: String, private val node: RuntimeNode, private val pose: RigPose?) : RigGizmoFrame {
    private val parent: Mat4f = MutableMat4f((node.parent as? RuntimeNode)?.globalMatrix ?: Mat4f.IDENTITY)
    private val current = pose ?: RigPose.IDENTITY
    private val animatedTranslation = Vec3f(node.transform.translation) - current.position
    private val animatedRotation = MutableQuatF(node.transform.rotation).mul(current.rotation.inverted()).norm()
    private val animatedScale = Vec3f(node.transform.scale) / current.scale

    override val values: GizmoTransformValues = decompose(node.globalMatrix)

    override fun placed(rig: ModelRig, values: GizmoTransformValues): ModelRig {
        val inverse = MutableMat4f(parent)
        if (!inverse.invert()) return rig
        val composed = TrsTransformF().setCompositionOf(values.translation, values.rotation, values.scale).matrixF
        val relative = decompose(inverse.mul(composed, MutableMat4f()))
        return write(rig, RigPose(
            position = relative.translation - animatedTranslation,
            rotation = MutableQuatF(animatedRotation.inverted()).mul(relative.rotation).norm(),
            scale = relative.scale / animatedScale,
        ))
    }

    override fun restored(rig: ModelRig): ModelRig = write(rig, current)

    private fun write(rig: ModelRig, next: RigPose): ModelRig =
        rig.withBone(bone, rig.holder(bone).copy(pose = next.takeUnless(RigPose::isIdentity)))

    private fun decompose(matrix: Mat4f): GizmoTransformValues {
        val translation = MutableVec3f()
        val rotation = MutableQuatF()
        val scale = MutableVec3f()
        matrix.decompose(translation, rotation, scale)
        return GizmoTransformValues(translation, rotation.norm(), scale)
    }
}

/** The part id that stands for the pose of a bone of the model itself, rather than anything hung on it. */
internal const val BONE_POSE_PART = "#pose"

/** The part id that stands for where an added bone itself is, rather than anything hung on it. */
internal const val BONE_ORIGIN_PART = "#origin"

/** The gizmo's view of [spec], which [selection] names, hanging on [holder]; null for a part the gizmo does not move. */
internal fun gizmoFrame(selection: RigPartSelection, spec: RigAttachmentSpec, holder: Mat4f): RigGizmoFrame? = when (spec) {
    is ColliderAttachmentSpec -> ColliderFrame(selection, spec, holder)
    is PlacedAttachmentSpec -> PlacedFrame(selection, spec, holder)
    else -> null
}

/** Whether the gizmo moves [spec], so selecting it in the preview makes sense. */
internal fun hasGizmo(spec: RigAttachmentSpec): Boolean = spec is ColliderAttachmentSpec || spec is PlacedAttachmentSpec
