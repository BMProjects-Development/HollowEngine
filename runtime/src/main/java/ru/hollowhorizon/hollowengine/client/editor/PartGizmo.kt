package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.colliders.resolveNodeWorldTransform
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.RigEditing
import ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent
import ru.hollowhorizon.hollowengine.common.models.PlacedAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigPose
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import kotlin.math.abs
import ru.hollowhorizon.hollowengine.client.history.UndoLabel

/**
 * A part of an entity's model under the gizmo: a bone, or something placed on one.
 */
internal sealed class PartGizmo(
    val entity: Entity,
    private val modelTransform: TransformComponent,
    val editing: RigEditing,
    val key: String,
) {
    abstract val label: String

    /** The part in the world, [partialTick] into the tick. */
    fun worldTransform(partialTick: Float): TrsTransformF = decompose(toWorld(partialTick).mul(local(), MutableMat4f()))

    /** Moves the part so it ends up where the gizmo puts it. */
    fun apply(values: GizmoTransformValues, partialTick: Float) {
        val inParent = MutableMat4f()
        toWorld(partialTick).mul(parent(), MutableMat4f()).invert(inParent)
        inParent.mul(TrsTransformF().setCompositionOf(values.translation, values.rotation, values.scale).matrixF)
        place(decompose(inParent))
    }

    /** The part in the outermost model's space. */
    protected abstract fun local(): Mat4f

    /** What the part is placed relative to, in the outermost model's space. */
    protected abstract fun parent(): Mat4f

    /** Writes where the part now is relative to [parent]. */
    protected abstract fun place(relative: TrsTransformF)

    private fun toWorld(partialTick: Float): Mat4f = resolveNodeWorldTransform(entity, modelTransform, partialTick).matrixF

    private fun decompose(matrix: Mat4f): TrsTransformF {
        val translation = MutableVec3f()
        val rotation = MutableQuatF()
        val scale = MutableVec3f()
        matrix.decompose(translation, rotation, scale)
        return TrsTransformF().setCompositionOf(translation, rotation, scale)
    }
}

/** A bone, which keeps what the gizmo did as its pose over the animation. */
internal class BoneGizmo(
    entity: Entity,
    private val model: ModelAttachment,
    private val node: RuntimeNode,
    modelTransform: TransformComponent,
    editing: RigEditing,
    key: String,
) : PartGizmo(entity, modelTransform, editing, key) {
    val bone: String get() = node.name

    override val label: String get() = bone

    override fun local(): Mat4f = node.globalMatrix

    override fun parent(): Mat4f = node.parent?.globalMatrix ?: Mat4f.IDENTITY

    override fun place(relative: TrsTransformF) {
        val current = model.rig.bone(bone)?.pose ?: RigPose.IDENTITY
        val animated = node.transform
        val animatedTranslation = Vec3f(animated.translation) - current.position
        val animatedRotation = MutableQuatF(animated.rotation).mul(QuatF(current.rotation).inverted()).norm()
        val animatedScale = Vec3f(animated.scale) / current.scale

        val pose = RigPose(
            position = relative.translation - animatedTranslation,
            rotation = MutableQuatF(animatedRotation.inverted()).mul(relative.rotation).norm(),
            scale = Vec3f(relative.scale) / animatedScale,
        )
        editing.edit(mergeKey = "pose:$bone", label = UndoLabel("${UndoLabel.LANG}.rig.pose", bone)) { rig -> rig.withBone(bone, rig.holder(bone).copy(pose = pose.takeUnless(RigPose::isIdentity))) }
    }
}

/**
 * A nested model or an effect hung as [attachmentId] on [bone], null being the model itself; [holder] is the
 * node it hangs on as drawn. Its scale is one number, so a drag along one axis scales it evenly.
 */
internal class AttachmentGizmo(
    entity: Entity,
    private val holder: RuntimeNode,
    modelTransform: TransformComponent,
    editing: RigEditing,
    private val bone: String?,
    private val attachmentId: String,
    key: String,
) : PartGizmo(entity, modelTransform, editing, key) {
    override val label: String get() = attachmentId

    private val spec: PlacedAttachmentSpec?
        get() = editing.occupied.holder(bone).attachment(attachmentId) as? PlacedAttachmentSpec

    override fun local(): Mat4f {
        val placed = spec?.localTransform() ?: return holder.globalMatrix
        return holder.globalMatrix.mul(placed.matrixF, MutableMat4f())
    }

    override fun parent(): Mat4f = holder.globalMatrix

    override fun place(relative: TrsTransformF) {
        val current = spec ?: return
        val scale = Vec3f(relative.scale)
        val uniform = listOf(scale.x, scale.y, scale.z).maxBy { abs(it - current.scale) }
        val placed = current.placedAt(Vec3f(relative.translation), QuatF(relative.rotation).eulerDegreesXyz(), uniform)
        editing.edit(mergeKey = "place:${bone.orEmpty()}/$attachmentId", label = UndoLabel("${UndoLabel.LANG}.rig.place", attachmentId)) { rig ->
            rig.withHolder(bone, rig.holder(bone).withAttachment(placed))
        }
    }
}
