package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.hostYawDegrees
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.*
import kotlin.math.sqrt

/** One collider of an entity, where it is in the world for one tick or one frame. */
class EntityCollider(
    val name: String,
    val bone: String?,
    val spec: ColliderAttachmentSpec,
    val volume: ColliderVolume,
)

internal val ModelRig.colliders: List<Pair<String?, ColliderAttachmentSpec>>
    get() = allAttachments().mapNotNull { (bone, spec) -> (spec as? ColliderAttachmentSpec)?.let { bone to it } }

fun ModelRig.hasColliders(modes: (ColliderModes) -> Boolean = { true }): Boolean =
    attachments.any { it is ColliderAttachmentSpec && modes(it.modes) } || bones.values.any { bone ->
        bone.attachments.any {
            it is ColliderAttachmentSpec && modes(it.modes)
        }
    }

/**
 * Places every collider of the rig on the current pose of [roots]. [toEntity] carries model space to the
 * world around [origin], the entity's position, which is added last to stay precise far from spawn.
 */
internal fun ModelRig.placeColliders(
    roots: List<RuntimeNode>,
    toEntity: Mat4f,
    origin: Vec3,
    modelMatrix: Mat4f? = null,
): List<EntityCollider> {
    val placed = colliders
    if (placed.isEmpty()) return emptyList()

    val nodes = roots.flatMap { it.walk() }.associateBy { it.name }
    val onModel = modelMatrix?.let { toEntity.mul(it, MutableMat4f()) } ?: toEntity
    return placed.mapNotNull { (bone, spec) ->
        val holder = if (bone == null) onModel else {
            val node = nodes[bone] ?: return@mapNotNull null
            toEntity.mul(node.globalMatrix, MutableMat4f())
        }
        EntityCollider(spec.id, bone, spec, spec.place(holder, toEntity, origin))
    }
}

/** Where [holder] carries this collider to: its shape inside its box, which keeps only where its center goes when world-aligned. */
internal fun ColliderAttachmentSpec.place(holder: Mat4f, toEntity: Mat4f, origin: Vec3): ColliderVolume =
    ColliderShapeFactories.place(shape, frame(holder, toEntity, origin))

private fun ColliderAttachmentSpec.frame(holder: Mat4f, toEntity: Mat4f, origin: Vec3): ColliderBox {
    if (alignment == ColliderAlignment.ORIENTED) return ColliderBox.of(
        holder.mul(localMatrix(), MutableMat4f()),
        origin
    )

    val center = holder.transform(offset, 1f, MutableVec3f())
    val scale = toEntity.uniformScale()
    return ColliderBox.aligned(
        origin.add(center.x.toDouble(), center.y.toDouble(), center.z.toDouble()),
        Vec3(size.x * scale / 2.0, size.y * scale / 2.0, size.z * scale / 2.0),
    )
}

/** The skeleton of [model] standing in its rest pose, with its matrices worked out. */
internal fun restPose(model: Model): List<RuntimeNode> =
    model.scenes.getOrNull(model.scene)?.nodes.orEmpty().map { RuntimeNode(it, null) }.onEach { root ->
            root.walk().forEach(RuntimeNode::resetPose)
            root.updateHierarchyMatrices()
        }

/** [fitEntityBox] around the colliders [rig] hangs on [model] standing at rest, placed by [modelMatrix]. */
internal fun fitEntityBox(model: Model, rig: ModelRig, modelMatrix: Mat4f): Pair<Float, Float>? =
    fitEntityBox(rig.placeColliders(restPose(model), modelMatrix, Vec3.ZERO))

/**
 * A square entity box, width to height, around [colliders] placed relative to the entity's feet. The
 * width reaches the corner farthest from the vertical line through the feet, so the box holds whichever
 * way the entity turns; the height reaches the highest corner. Null when nothing is above the feet.
 */
fun fitEntityBox(colliders: List<EntityCollider>): Pair<Float, Float>? {
    var radius = 0.0
    var top = 0.0
    colliders.forEach { collider ->
        collider.volume.frame.corners().forEach { corner ->
            radius = maxOf(radius, sqrt(corner.x * corner.x + corner.z * corner.z))
            top = maxOf(top, corner.y)
        }
    }
    if (top <= 0.0 || radius <= 0.0) return null
    return (radius * 2.0).toFloat() to top.toFloat()
}

/**
 * Model space to the world around the entity: the node's own transform, turned with the entity's body.
 * The entity's position is left out; colliders add it in double precision.
 */
internal fun entityModelMatrix(host: Entity, transform: TransformComponent, partialTick: Float): Mat4f =
    MutableMat4f().rotate(hostRotation(host, partialTick)).scale(hostScale(host, partialTick))
        .mul(transform.transform.matrixF)

/** Where a model node carried by [host] stands in the world: its own transform, then the host's. */
fun resolveNodeWorldTransform(host: Entity, transform: TransformComponent, partialTick: Float): TrsTransformF {
    val hostPosition = hostPosition(host, partialTick).let { Vec3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
    val hostRotation = hostRotation(host, partialTick)
    val hostScale = hostScale(host, partialTick)
    val local = transform.transform
    val worldTranslation = (Vec3f(local.translation) * hostScale).rotateBy(hostRotation) + hostPosition
    val worldRotation = MutableQuatF(hostRotation).mul(local.rotation).norm()
    return TrsTransformF().setCompositionOf(worldTranslation, worldRotation, Vec3f(local.scale) * hostScale)
}

/** How the host turns what it carries: a world object turns freely, any other entity with its body's yaw. */
internal fun hostRotation(host: Entity, partialTick: Float): QuatF {
    if (host is WorldObjectEntity) return host.pose(partialTick).rotation.let { QuatF(it.x, it.y, it.z, it.w) }
    val yaw = when (host) {
        is LivingEntity -> Mth.rotLerp(partialTick, host.yBodyRotO, host.yBodyRot)
        else -> Mth.rotLerp(partialTick, host.yRotO, host.yRot)
    }
    return QuatF(hostYawDegrees(yaw).deg, Vec3f.Y_AXIS)
}

/** How the host scales what it carries; only world objects have a scale of their own. */
internal fun hostScale(host: Entity, partialTick: Float): Vec3f {
    if (host !is WorldObjectEntity) return Vec3f.ONES
    return host.pose(partialTick).scale.let { Vec3f(it.x, it.y, it.z) }
}

/** Where the host carries things from. A child world object is placed by its parent, not by its own last two ticks. */
internal fun hostPosition(host: Entity, partialTick: Float): Vec3 {
    if (host is WorldObjectEntity) return host.pose(partialTick).position.let { Vec3(it.x, it.y, it.z) }
    return Vec3(
        Mth.lerp(partialTick.toDouble(), host.xOld, host.x),
        Mth.lerp(partialTick.toDouble(), host.yOld, host.y),
        Mth.lerp(partialTick.toDouble(), host.zOld, host.z),
    )
}

private fun Mat4f.uniformScale(): Float {
    val x = transform(Vec3f.X_AXIS, 0f, MutableVec3f()).length()
    val y = transform(Vec3f.Y_AXIS, 0f, MutableVec3f()).length()
    val z = transform(Vec3f.Z_AXIS, 0f, MutableVec3f()).length()
    return (x + y + z) / 3f
}
