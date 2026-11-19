package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.editor.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RenderModelNode
import ru.hollowhorizon.hollowengine.client.ui.widgets.ModelViewerState
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.math.*

/**
 * The gizmo over the rig preview, moving the selected part, a collider or anything placed by an offset such as an IK
 * target. It is the world's gizmo, projected through the preview's camera, which has no perspective.
 */
internal class RigPartGizmo {
    private val projector = OrthographicProjector().apply { axesFrame = ModelPreviewAxes }
    val session = GizmoSession(projector, GizmoSnapping.MODEL)

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

    /** The segment under the pointer, through the whole model, for picking colliders. */
    fun ray(x: Float, y: Float): Pair<Vec3, Vec3>? {
        val ray = projector.screenRay(x, y) ?: return null
        return ray.origin to ray.origin.add(ray.direction.scale(RAY_LENGTH))
    }

    /** Where a point in model space shows in the preview, in panel pixels. */
    fun screenOf(point: Vec3f): Pair<Float, Float> =
        projector.project(point.x.toDouble(), point.y.toDouble(), point.z.toDouble()).let { it.x to it.y }

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

/** The part [frame] stands for, as the gizmo moves it: written into [document]'s rig, one step per gesture. */
internal class RigPartTarget(private val document: RigEditing, private val frame: RigGizmoFrame) : GizmoTarget {
    override val values: GizmoTransformValues get() = frame.values

    override fun canEdit(mode: GizmoEditMode): Boolean = mode != GizmoEditMode.ROTATE || !frame.isWorldAligned

    override fun place(values: GizmoTransformValues, mode: GizmoEditMode) = document.edit { frame.placed(it, values) }

    override fun restore() = document.edit { frame.restored(it) }

    override val canReset: Boolean get() = true

    override fun reset(mode: GizmoEditMode): Boolean {
        val reset = frame.reset(document.rig, mode) ?: return false
        document.edit { reset }
        return true
    }

    override fun beginEdit() = document.beginGesture()

    override fun endEdit() = document.endGesture()
}

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

    /** [rig] with [mode]'s part of the placement back at rest, or null when the part has none to reset. */
    fun reset(rig: ModelRig, mode: GizmoEditMode): ModelRig? = null
}

/** Something hung on a bone, the attachment [selection] names, as it was: [spec]. */
internal abstract class AttachmentFrame(private val selection: RigPartSelection) : RigGizmoFrame {
    abstract val spec: Any

    /** The attachment moved to [values]. */
    abstract fun with(values: GizmoTransformValues): Any

    override fun placed(rig: ModelRig, values: GizmoTransformValues): ModelRig = write(rig, with(values))

    override fun restored(rig: ModelRig): ModelRig = write(rig, spec)

    /** The attachment with [mode]'s part of its placement at rest, or null when it has nothing there to reset. */
    protected open fun resting(mode: GizmoEditMode): Any? = null

    override fun reset(rig: ModelRig, mode: GizmoEditMode): ModelRig? = resting(mode)?.let { write(rig, it) }

    private fun write(rig: ModelRig, attachment: Any): ModelRig =
        rig.withHolder(selection.bone, rig.holder(selection.bone).withAttachment(selection.id, attachment))
}

/** The local transform of a bone the rig adds under its parent. */
internal class BoneOriginFrame(private val bone: String, private val origin: RigBoneOrigin, parent: Mat4f) :
    RigGizmoFrame {
    private val parent = MutableMat4f(parent)
    override val values: GizmoTransformValues = decompose(parent.mul(origin.localTransform().matrixF, MutableMat4f()))

    override fun placed(rig: ModelRig, values: GizmoTransformValues): ModelRig {
        val inverse = MutableMat4f(parent)
        if (!inverse.invert()) return rig
        val world = TrsTransformF().setCompositionOf(values.translation, values.rotation, values.scale).matrixF
        val local = decompose(inverse.mul(world, MutableMat4f()))
        return write(rig, origin.copy(offset = local.translation, rotation = local.rotation.eulerDegreesXyz(), scale = local.scale))
    }

    override fun restored(rig: ModelRig): ModelRig = write(rig, origin)

    override fun reset(rig: ModelRig, mode: GizmoEditMode): ModelRig? = when (mode) {
        GizmoEditMode.TRANSLATE -> write(rig, origin.copy(offset = Vec3f.ZERO))
        GizmoEditMode.ROTATE -> write(rig, origin.copy(rotation = Vec3f.ZERO))
        GizmoEditMode.SCALE -> write(rig, origin.copy(scale = Vec3f.ONES))
    }

    private fun write(rig: ModelRig, moved: RigBoneOrigin): ModelRig =
        rig.withBone(bone, rig.holder(bone).copy(origin = moved))
}

/**
 * A bone of the model, moved by its pose over whatever animates it. What the animation does to the bone is
 * taken once, when the frame is: drag events come faster than frames, and the drawn bone lags a step behind
 * the pose being written.
 */
internal class BonePoseFrame(private val bone: String, private val node: RenderModelNode, private val pose: RigPose?) :
    RigGizmoFrame {
    private val parent: Mat4f = MutableMat4f((node.attachmentParent as? RenderModelNode)?.globalMatrix ?: Mat4f.IDENTITY)
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
        return write(
            rig, RigPose(
                position = relative.translation - animatedTranslation,
                rotation = MutableQuatF(animatedRotation.inverted()).mul(relative.rotation).norm(),
                scale = relative.scale / animatedScale,
            )
        )
    }

    override fun restored(rig: ModelRig): ModelRig = write(rig, current)

    /**
     * Puts [mode]'s part of the bone where the model has it at rest, as Blender clears a pose: over an animation the
     * pose is what takes this moment of it back off, so the animation has the bone again elsewhere on the timeline.
     */
    override fun reset(rig: ModelRig, mode: GizmoEditMode): ModelRig {
        val rest = node.definition.baseTransform
        val pose = when (mode) {
            GizmoEditMode.TRANSLATE -> current.copy(position = settled(Vec3f(rest.translation) - animatedTranslation, Vec3f.ZERO))
            GizmoEditMode.ROTATE -> current.copy(rotation = settled(animatedRotation.inverted() * QuatF(rest.rotation)))
            GizmoEditMode.SCALE -> current.copy(scale = settled(Vec3f(rest.scale) / animatedScale, Vec3f.ONES))
        }
        return write(rig, pose)
    }

    /** [value], or [plain] when it only differs from it by rounding, so a bone with nothing animating it gets no pose. */
    private fun settled(value: Vec3f, plain: Vec3f): Vec3f = if (value.isFuzzyEqual(plain, REST_EPSILON)) plain else value

    private fun settled(turn: QuatF): QuatF =
        turn.normed().let { if (it.isFuzzyEqualRotation(QuatF.IDENTITY, REST_EPSILON)) QuatF.IDENTITY else it }

    private companion object {
        const val REST_EPSILON = 1.0e-4f
    }

    private fun write(rig: ModelRig, next: RigPose): ModelRig =
        rig.withBone(bone, rig.holder(bone).copy(pose = next.takeUnless(RigPose::isIdentity)))

}

private fun decompose(matrix: Mat4f): GizmoTransformValues {
    val translation = MutableVec3f()
    val rotation = MutableQuatF()
    val scale = MutableVec3f()
    matrix.decompose(translation, rotation, scale)
    return GizmoTransformValues(translation, rotation.norm(), scale)
}

/** The part id that stands for the pose of a bone of the model itself, rather than anything hung on it. */
internal const val BONE_POSE_PART = "#pose"

/** The part id that stands for where an added bone itself is, rather than anything hung on it. */
internal const val BONE_ORIGIN_PART = "#origin"

/** The gizmo's view of [spec], which [selection] names, hanging on [holder]; null for a part the gizmo does not move. */
internal fun gizmoFrame(selection: RigPartSelection, spec: Any, holder: Mat4f): RigGizmoFrame? =
    when (spec) {
        is ColliderAttachmentSpec -> ColliderFrame(selection, spec, holder)
        else -> null
    }

/** Whether the gizmo moves [spec], so selecting it in the preview makes sense. */
internal fun hasGizmo(spec: Any): Boolean = spec is ColliderAttachmentSpec
