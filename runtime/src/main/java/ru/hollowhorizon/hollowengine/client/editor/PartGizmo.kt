package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RenderModelNode
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.RigEditing
import ru.hollowhorizon.hollowengine.common.colliders.resolveNodeWorldTransform
import ru.hollowhorizon.hollowengine.common.models.RigPose
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.utils.math.*

/**
 * A part of an entity's model under the gizmo: a bone, or something placed on one.
 */
internal sealed class PartGizmo(
    val entity: Entity,
    private val editing: RigEditing?,
    val key: String,
) {
    abstract val label: String

    fun beginGesture() {
        if (editing != null) editing.beginGesture() else WorldHistory.history.begin()
    }

    fun endGesture() {
        if (editing != null) editing.endGesture() else WorldHistory.history.commit()
    }

    /** The part in the world, [partialTick] into the tick. */
    fun worldTransform(partialTick: Float): TrsTransformF = decompose(worldMatrix(partialTick))

    /** The part in the world, [partialTick] into the tick, as a matrix. */
    fun worldMatrix(partialTick: Float): Mat4f = toWorld(partialTick).mul(local(), MutableMat4f())


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

    private fun toWorld(partialTick: Float): Mat4f = resolveNodeWorldTransform(entity, partialTick).matrixF

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
    private val node: RenderModelNode,
    private val rigEditing: RigEditing,
    key: String,
) : PartGizmo(entity, rigEditing, key) {
    val bone: String get() = node.name

    override val label: String get() = bone

    override fun local(): Mat4f = node.globalMatrix

    override fun parent(): Mat4f = node.attachmentParent?.globalMatrix ?: Mat4f.IDENTITY

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
        rigEditing.edit(mergeKey = "pose:$bone", label = UndoLabel("${UndoLabel.LANG}.rig.pose", bone)) { rig -> rig.withBone(bone, rig.holder(bone).copy(pose = pose.takeUnless(RigPose::isIdentity))) }
    }
}

internal class SceneNodeGizmo(
    entity: Entity,
    private val node: SceneObject,
    private val write: (TrsTransformF) -> Unit,
    key: String,
) : PartGizmo(entity, null, key) {
    override val label: String get() = node.name
    override fun local(): Mat4f = node.worldTransform
    override fun parent(): Mat4f = node.parent?.worldTransform ?: Mat4f.IDENTITY
    override fun place(relative: TrsTransformF) = write(relative)
}
