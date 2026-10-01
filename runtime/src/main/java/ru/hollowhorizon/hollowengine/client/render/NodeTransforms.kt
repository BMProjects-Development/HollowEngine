package ru.hollowhorizon.hollowengine.client.render

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent
import ru.hollowhorizon.hollowengine.common.colliders.hostPosition
import ru.hollowhorizon.hollowengine.common.colliders.hostRotation
import ru.hollowhorizon.hollowengine.common.attachments.tracking.MCEntity
import ru.hollowhorizon.hollowengine.common.utils.math.*
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ResolvedNodeTransform(
    val transform: TrsTransformF,
    val light: Int,
) {
    val position: Vec3
        get() = Vec3(
            transform.translation.x.toDouble(),
            transform.translation.y.toDouble(),
            transform.translation.z.toDouble(),
        )
}

fun resolveNodeTransform(
    level: Level,
    hostEntityUuid: UUID?,
    transform: TransformComponent,
    partialTick: Float,
): ResolvedNodeTransform? {
    val worldTransform = if (hostEntityUuid == null) {
        TrsTransformF().set(transform.transform)
    } else {
        val host = findNodeHostEntity(level, hostEntityUuid) ?: return null
        resolveNodeWorldTransform(host, transform, partialTick)
    }

    return ResolvedNodeTransform(
        transform = worldTransform,
        light = LevelRenderer.getLightColor(
            level,
            BlockPos.containing(
                worldTransform.translation.x.toDouble(),
                worldTransform.translation.y.toDouble(),
                worldTransform.translation.z.toDouble()
            )
        ),
    )
}

fun resolveNodeWorldTransform(
    host: Entity,
    transform: TransformComponent,
    partialTick: Float,
): TrsTransformF {
    val hostPosition = hostPosition(host, partialTick).let { Vec3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
    val hostRotation = hostRotation(host, partialTick)
    val local = transform.transform
    val worldTranslation = Vec3f(local.translation).rotateBy(hostRotation) + hostPosition
    val worldRotation = MutableQuatF(hostRotation).mul(local.rotation).norm()
    return TrsTransformF().setCompositionOf(
        worldTranslation,
        worldRotation,
        Vec3f(local.scale),
    )
}

/** The world-space box of a node, from the local bounds of whatever is drawn for it. */
fun buildNodeRenderBounds(localBounds: Pair<Vec3f, Vec3f>?, transform: TrsTransformF): AABB {
    val worldTransform = TrsTransformF().set(transform)

    if (localBounds == null) {
        val maxAxis =
            max(max(abs(worldTransform.scale.x), abs(worldTransform.scale.y)), abs(worldTransform.scale.z)).toDouble()
        val position = worldTransform.translation
        return AABB(
            position.x - maxAxis,
            position.y - maxAxis,
            position.z - maxAxis,
            position.x + maxAxis,
            position.y + maxAxis,
            position.z + maxAxis,
        )
    }

    val min = localBounds.first
    val max = localBounds.second
    val matrix = worldTransform.matrixF
    val source = MutableVec3f()
    val transformed = MutableVec3f()
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY

    fun update(x: Float, y: Float, z: Float) {
        source.set(x, y, z)
        matrix.transform(source, 1f, transformed)
        minX = min(minX, transformed.x)
        minY = min(minY, transformed.y)
        minZ = min(minZ, transformed.z)
        maxX = max(maxX, transformed.x)
        maxY = max(maxY, transformed.y)
        maxZ = max(maxZ, transformed.z)
    }

    update(min.x, min.y, min.z)
    update(min.x, min.y, max.z)
    update(min.x, max.y, min.z)
    update(min.x, max.y, max.z)
    update(max.x, min.y, min.z)
    update(max.x, min.y, max.z)
    update(max.x, max.y, min.z)
    update(max.x, max.y, max.z)

    return AABB(
        minX.toDouble(),
        minY.toDouble(),
        minZ.toDouble(),
        maxX.toDouble(),
        maxY.toDouble(),
        maxZ.toDouble(),
    )
}

fun worldTransformToComponent(
    level: net.minecraft.world.level.Level,
    hostEntityUuid: java.util.UUID?,
    worldPosition: Vec3,
    worldRotation: QuatF,
    worldScale: Vec3f,
    partialTick: Float,
): TransformComponent? {
    val normalizedScale = sanitizeScale(worldScale)
    return if (hostEntityUuid == null) {
        TransformComponent(
            translation = Vec3f(worldPosition.x.toFloat(), worldPosition.y.toFloat(), worldPosition.z.toFloat()),
            rotation = QuatF(worldRotation),
            scale = normalizedScale,
        )
    } else {
        val host = findNodeHostEntity(level, hostEntityUuid) ?: return null
        val hostPosition = hostPosition(host, partialTick)
        val hostRotation = hostRotation(host, partialTick)
        val inverseHostRotation = MutableQuatF(hostRotation).invert().norm()
        val localTranslation = Vec3f(
            (worldPosition.x - hostPosition.x).toFloat(),
            (worldPosition.y - hostPosition.y).toFloat(),
            (worldPosition.z - hostPosition.z).toFloat(),
        ).rotateBy(inverseHostRotation)
        val localRotation = MutableQuatF(inverseHostRotation).mul(worldRotation).norm()
        TransformComponent(
            translation = localTranslation,
            rotation = localRotation,
            scale = normalizedScale,
        )
    }
}

fun quatFToGizmoRotation(rotation: QuatF): QuatD =
    MutableQuatD(rotation.x.toDouble(), rotation.y.toDouble(), rotation.z.toDouble(), rotation.w.toDouble()).norm()

fun gizmoRotationToQuatF(rotation: QuatD): QuatF =
    MutableQuatF(rotation.x.toFloat(), rotation.y.toFloat(), rotation.z.toFloat(), rotation.w.toFloat()).norm()

fun findNodeHostEntity(level: net.minecraft.world.level.Level, hostEntityUuid: java.util.UUID): MCEntity? {
    if (level is ServerLevel) return level.getEntity(hostEntityUuid)
    if (level is ClientLevel) {
        level.entitiesForRendering().forEach { entity ->
            if (entity.uuid == hostEntityUuid) return entity
        }
    }
    return null
}

private fun sanitizeScale(scale: Vec3f): Vec3f =
    Vec3f(
        sanitizeScaleComponent(scale.x),
        sanitizeScaleComponent(scale.y),
        sanitizeScaleComponent(scale.z),
    )

private fun sanitizeScaleComponent(value: Float): Float {
    val normalized = if (abs(value) < 0.01f) 0.01f else value
    return if (normalized == -0.0f) 0.01f else normalized
}

