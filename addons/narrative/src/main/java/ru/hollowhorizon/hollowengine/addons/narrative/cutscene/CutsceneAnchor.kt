package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Serializable
data class CutsceneOrigin(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val yaw: Float = 0f,
) {
    val position: Vec3f get() = Vec3f(x, y, z)

    val frame: CutsceneFrame get() = CutsceneFrame(position, yaw)

    fun moved(position: Vec3f, yaw: Float = this.yaw) = CutsceneOrigin(position.x, position.y, position.z, yaw)
}

class CutsceneFrame(val origin: Vec3f, val yaw: Float) {

    fun toWorld(local: Vec3f): Vec3f = origin + rotate(local, yaw)

    fun toLocal(world: Vec3f): Vec3f = rotate(world - origin, -yaw)

    fun toWorldRotation(local: Vec3f) = Vec3f(local.x, local.y + yaw, local.z)

    fun toLocalRotation(world: Vec3f) = Vec3f(world.x, world.y - yaw, world.z)

    companion object {
        val IDENTITY = CutsceneFrame(Vec3f.ZERO, 0f)

        private fun rotate(vector: Vec3f, degrees: Float): Vec3f {
            if (degrees == 0f) return vector
            val radians = degrees * PI.toFloat() / 180f
            val cos = cos(radians)
            val sin = sin(radians)
            return Vec3f(
                vector.x * cos - vector.z * sin,
                vector.y,
                vector.x * sin + vector.z * cos,
            )
        }
    }
}

@Serializable
enum class CutsceneAnchorKind {
    WORLD, PLAYER, ENTITY, BLOCK,
}

@Serializable
data class CutsceneAnchor(
    val kind: CutsceneAnchorKind = CutsceneAnchorKind.WORLD,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val yaw: Float = 0f,
    val entityId: Int = -1,
    val rotate: Boolean = true,
    val follow: Boolean = false,
) {
    companion object {
        val WHERE_RECORDED = CutsceneAnchor()
    }
}

object CutsceneAnchors {
    fun resolve(origin: CutsceneOrigin, anchor: CutsceneAnchor): CutsceneFrame = when (anchor.kind) {
        CutsceneAnchorKind.WORLD -> origin.frame
        CutsceneAnchorKind.BLOCK -> frame(origin, Vec3f(anchor.x, anchor.y, anchor.z), anchor.yaw, anchor.rotate)

        CutsceneAnchorKind.PLAYER -> {
            val player = Minecraft.getInstance().player
            if (player == null) origin.frame else frame(origin, player, anchor.rotate)
        }

        CutsceneAnchorKind.ENTITY -> {
            val entity = anchor.entityId.takeIf { it >= 0 }?.let { Minecraft.getInstance().level?.getEntity(it) }
            if (entity == null) origin.frame else frame(origin, entity, anchor.rotate)
        }
    }

    fun follows(anchor: CutsceneAnchor): Boolean = when (anchor.kind) {
        CutsceneAnchorKind.WORLD, CutsceneAnchorKind.BLOCK -> false
        CutsceneAnchorKind.PLAYER, CutsceneAnchorKind.ENTITY -> anchor.follow
    }

    private fun frame(origin: CutsceneOrigin, entity: Entity, rotate: Boolean) = frame(
        origin,
        Vec3f(entity.x.toFloat(), entity.y.toFloat(), entity.z.toFloat()),
        entity.yRot,
        rotate,
    )

    private fun frame(origin: CutsceneOrigin, position: Vec3f, yaw: Float, rotate: Boolean) =
        CutsceneFrame(position, if (rotate) yaw else origin.yaw)
}
