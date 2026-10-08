package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.common.colliders.ServerColliderAssets
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.colliders.ColliderDebugRenderer
import ru.hollowhorizon.hollowengine.client.editor.GizmoTransformValues
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAlignment
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.colliders.EntityCollider
import ru.hollowhorizon.hollowengine.common.colliders.placeColliders
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

/** Which part is selected: the bone it hangs on, null for the model itself, and its id. */
internal data class RigPartSelection(val bone: String?, val id: String)

/**
 * Puts a saved rig to use in the world right away: on this client, and on the server running in this
 * game if there is one. A dedicated server reads it on its next reload.
 */
internal fun publishRig(modelId: String, rig: ModelRig) {
    val location = ResourceLocation.tryParse(modelId) ?: return
    RigAssets.register(location, rig)
    Minecraft.getInstance().singleplayerServer?.execute { ServerColliderAssets.invalidate(modelId) }
}

/** The colliders of [rig] on the preview's current pose, in model space. */
internal fun previewColliders(rig: ModelRig, roots: List<RuntimeNode>): List<EntityCollider> =
    rig.placeColliders(roots, IDENTITY, Vec3.ZERO)

/** The matrix of what a collider hangs on, in model space; the model itself is the identity. */
internal fun holderMatrix(roots: List<RuntimeNode>, bone: String?): Mat4f? {
    if (bone == null) return IDENTITY
    return roots.firstNotNullOfOrNull { root -> root.walk().firstOrNull { it.name == bone } }?.globalMatrix
}

internal fun DebugLines.Batch.colliders(colliders: List<EntityCollider>, selected: RigPartSelection?) {
    colliders.forEach { collider ->
        val isSelected = selected != null && selected.bone == collider.bone && selected.id == collider.name
        val color = if (isSelected) SELECTED_COLOR else ColliderDebugRenderer.colorOf(collider.spec.modes)
        collider.volume.outline { start, end -> line(start.toVec3f(), end.toVec3f(), color) }
        // The box the gizmo sizes, around a selected shape that does not fill it.
        if (isSelected && collider.volume !== collider.volume.frame) {
            collider.volume.frame.outline { start, end -> line(start.toVec3f(), end.toVec3f(), FRAME_COLOR) }
        }
    }
}

/**
 * A collider as the gizmo sees it: its center and turn in model space, and its size along its own axes.
 * [with] goes back to the fields of the collider, in the space of what it hangs on.
 */
internal class ColliderFrame(override val spec: ColliderAttachmentSpec, holder: Mat4f) : RigGizmoFrame {
    private val holder = MutableMat4f(holder)
    private val holderRotation: QuatF = holder.getRotation(MutableQuatF()).norm()

    override val isWorldAligned: Boolean get() = spec.alignment == ColliderAlignment.WORLD

    override val values: GizmoTransformValues = GizmoTransformValues(
        translation = holder.transform(spec.offset, 1f, MutableVec3f()),
        rotation = if (isWorldAligned) QuatF.IDENTITY else holderRotation * spec.orientation,
        scale = spec.size,
    )

    override fun with(values: GizmoTransformValues): ColliderAttachmentSpec {
        val inverse = MutableMat4f(holder)
        if (!inverse.invert()) return spec
        val local = holderRotation.inverted() * values.rotation
        return spec.copy(
            offset = inverse.transform(values.translation, 1f, MutableVec3f()),
            rotation = if (isWorldAligned) spec.rotation else eulerDegrees(local),
            size = Vec3f(values.scale.x.sized(), values.scale.y.sized(), values.scale.z.sized()),
        )
    }

    private fun Float.sized(): Float = abs(this).coerceAtLeast(MIN_SIZE)

    /** Euler degrees in the order the collider applies them: X, then Y, then Z. */
    private fun eulerDegrees(rotation: QuatF): Vec3f {
        val angles = Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w).getEulerAnglesXYZ(Vector3f())
        return Vec3f(angles.x.degrees(), angles.y.degrees(), angles.z.degrees())
    }

    private fun Float.degrees(): Float = Math.toDegrees(toDouble()).toFloat()
}

private fun Vec3.toVec3f() = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())

private val IDENTITY: Mat4f = MutableMat4f().setIdentity()
private const val MIN_SIZE = 0.01f

private val SELECTED_COLOR = 0xFFFFA333.toInt()
private val FRAME_COLOR = 0x66FFA333
