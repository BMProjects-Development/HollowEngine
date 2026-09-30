package ru.hollowhorizon.hollowengine.common.models

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.utils.math.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk

/** A null bone denotes a hitbox in model space. */
data class RigHitbox(val bone: String?, val geometry: HitboxGeometry)
data class HitboxIntersection(val geometry: HitboxGeometry, val location: Vec3)

fun Iterable<HitboxGeometry>.trace(start: Vec3, end: Vec3): HitboxIntersection? =
    mapNotNull { box ->
        if (!box.bounds.contains(start) && box.bounds.clip(start, end).isEmpty) null
        else box.clip(start, end)?.let { HitboxIntersection(box, it) }
    }.minByOrNull { it.location.distanceToSqr(start) }

fun ModelRig.hitboxes(roots: List<RuntimeNode>, modelTransform: Mat4f = Mat4f.IDENTITY): List<RigHitbox> = buildList {
    fun append(attachments: List<RigAttachmentSpec>, bone: String?, transform: Mat4f) {
        attachments.filterIsInstance<HitboxAttachmentSpec>().forEach {
            add(RigHitbox(bone, HitboxGeometry(it, it.matrix(transform))))
        }
    }
    append(attachments, null, modelTransform)
    roots.flatMap { it.walk() }.forEach { bone ->
        append(bone(bone.name)?.attachments.orEmpty(), bone.name, modelTransform.mul(bone.globalMatrix, MutableMat4f()))
    }
}

/** A transformed unit box. Ray tests and previews use the same bone/offset/rotation/size matrix. */
class HitboxGeometry(val spec: HitboxAttachmentSpec, val matrix: Mat4f) {
    val center = matrix.transform(Vec3f.ZERO, 1f, MutableVec3f())
    val x = matrix.transform(Vec3f(0.5f, 0f, 0f), 0f, MutableVec3f())
    val y = matrix.transform(Vec3f(0f, 0.5f, 0f), 0f, MutableVec3f())
    val z = matrix.transform(Vec3f(0f, 0f, 0.5f), 0f, MutableVec3f())

    val bounds: AABB by lazy {
        val radius = Vec3f(
            kotlin.math.abs(x.x) + kotlin.math.abs(y.x) + kotlin.math.abs(z.x),
            kotlin.math.abs(x.y) + kotlin.math.abs(y.y) + kotlin.math.abs(z.y),
            kotlin.math.abs(x.z) + kotlin.math.abs(y.z) + kotlin.math.abs(z.z),
        )
        AABB((center - radius).minecraft(), (center + radius).minecraft())
    }

    /** Includes rays starting inside a hitbox, and rejects singular (zero-scale) transforms. */
    fun clip(start: Vec3, end: Vec3): Vec3? {
        val inverse = MutableMat4f(matrix)
        if (!inverse.invert()) return null
        val localStart = inverse.transform(start.vector(), 1f, MutableVec3f()).minecraft()
        val localEnd = inverse.transform(end.vector(), 1f, MutableVec3f()).minecraft()
        if (UNIT.contains(localStart)) return start
        val hit = UNIT.clip(localStart, localEnd).orElse(null) ?: return null
        return matrix.transform(hit.vector(), 1f, MutableVec3f()).minecraft()
    }

    private companion object {
        val UNIT = AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
    }
}

private fun Vec3.vector() = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())
private fun Vec3f.minecraft() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
