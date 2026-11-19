package ru.hollowhorizon.hollowengine.common.entities.objects

import kotlinx.serialization.Serializable
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.editor.WorldObjectEditing
import ru.hollowhorizon.hollowengine.common.attachments.api.set
import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.attachments.editor.canEditEntities
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.scene.SceneComponentData
import ru.hollowhorizon.hollowengine.common.scene.SceneAddress
import ru.hollowhorizon.hollowengine.common.scene.SceneTreeData
import ru.hollowhorizon.hollowengine.common.sounds.*
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForUuid
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import java.util.*

/** What a new object starts out showing. */
@Serializable
enum class WorldObjectKind {
    EMPTY,
    MODEL,
    VFX,

    /** A sound that fills a room-sized space around where it stands, on and on. */
    SOUND,
    /** A picture, or a video, laid on the surface the object stands on. */
    DECAL,
}

/** What an object made from a dragged sound plays, and where: a box on the ground it stands on, faded out past. */
private fun soundOf(asset: String) = Sound(
    id = "sound",
    source = asset,
    playback = SoundPlayback(repeat = SoundRepeat.Forever, falloff = SoundFalloff(distance = 4f)),
    areas = listOf(SoundArea.Box(offset = Vec3f(0f, 2f, 0f), size = Vec3f(8f, 4f, 8f))),
)

/** How far off the face a dropped decal is cast from, in blocks. */
private const val DECAL_LIFT = 0.25f

internal fun decalPoseOf(face: Direction): TrsTransformF {
    val normal = face.normal.let { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
    val toBottom = if (face.axis == Direction.Axis.Y) Vector3f(0f, 0f, 1f) else Vector3f(0f, -1f, 0f)
    val right = Vector3f(normal).cross(toBottom)
    val turn = Quaternionf().setFromNormalized(Matrix3f(right, normal, toBottom))
    return TrsTransformF().setCompositionOf(
        Vec3f(normal.x, normal.y, normal.z) * DECAL_LIFT,
        QuatF(turn.x, turn.y, turn.z, turn.w),
        Vec3f.ONES,
    )
}

/** Places a new object at a point, showing [asset], and selects it for the player who placed it. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class SpawnWorldObjectPacket(
    val kind: WorldObjectKind,
    val asset: String = "",
    val x: Double,
    val y: Double,
    val z: Double,
    val parentId: Int? = null,

    /** The face of the block the object stands on, by its 3D data value; up unless it was placed on a block. */
    val face: Int = Direction.UP.get3DDataValue(),
) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        if (kind != WorldObjectKind.EMPTY && ResourceLocation.tryParse(asset) == null) return
        val level = player.level()
        val parent = parentId?.let { level.getEntity(it) as? WorldObjectEntity }

        val created = WorldObjectEntity(level)
        created.moveTo(x, y, z, 0f, 0f)
        when (kind) {
            WorldObjectKind.MODEL -> created set Model(asset)
            WorldObjectKind.VFX -> created.sceneObject.attach(VfxComponent(id = "effect", effect = asset))
            WorldObjectKind.SOUND -> created.sceneObject.attach(soundOf(asset))
            WorldObjectKind.DECAL -> created.sceneObject.create("decal").apply {
                localTransform.set(decalPoseOf(Direction.from3DDataValue(face)))
                attach(Decal(texture = asset))
            }
            WorldObjectKind.EMPTY -> Unit
        }
        if (kind != WorldObjectKind.EMPTY) created.customName = asset.substringAfterLast('/').substringBefore('.').literal
        if (!level.addFreshEntity(created)) return
        parent?.let(created::setParent)
        WorldObjectSpawnedPacket(created.id).send(player as? ServerPlayer ?: return)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class WorldObjectSpawnedPacket(val entityId: Int) : HollowPacket {
    override fun handle(player: Player) = WorldObjectEditing.select(entityId)
}

/** The editor changes one component on the object. The normal entity tracker publishes the result. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class EditWorldObjectComponentPacket(
    val entityId: Int,
    val entityUuid: @Serializable(ForUuid::class) UUID,
    val componentId: @Serializable(ForUuid::class) UUID,
    val data: @Serializable(ForCompoundNBT::class) CompoundTag? = null,
    val nodeId: @Serializable(ForUuid::class) UUID? = null,
) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val entity = player.level().getEntity(entityId) as? WorldObjectEntity ?: return
        if (entity.uuid != entityUuid) return
        val root = entity.sceneObject
        val owner = if (nodeId == null) root else root.scene.resolve(SceneAddress(nodeId)) ?: return
        val current = owner.component(componentId)
        if (current != null && !SceneComponentData.synced(current)) return
        if (data == null) owner.detach(componentId)
        else SceneComponentData.set(owner, componentId, data, syncOnly = true)
    }
}

/** Creates or edits an authored child of a world object's scene root. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class EditWorldObjectNodePacket(
    val entityId: Int,
    val entityUuid: @Serializable(ForUuid::class) UUID,
    val nodeId: @Serializable(ForUuid::class) UUID,
    val parentId: @Serializable(ForUuid::class) UUID? = null,
    val name: String? = null,
    val pose: @Serializable(ForCompoundNBT::class) CompoundTag? = null,
    val contents: @Serializable(ForCompoundNBT::class) CompoundTag? = null,
) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val entity = player.level().getEntity(entityId) as? WorldObjectEntity ?: return
        if (entity.uuid != entityUuid) return
        val root = entity.sceneObject
        val node = root.scene.resolve(SceneAddress(nodeId))
        if (node === root) return
        when {
            name != null -> {
                if (node != null || name.isBlank()) return
                val parent = if (parentId == null) root else root.scene.resolve(SceneAddress(parentId)) ?: return
                if (parent.children.any { it.name == name }) return
                val child = parent.create(name, nodeId)
                if (pose != null) SceneTreeData.applyPose(child, pose)
                if (contents != null) {
                    child.setEnabled(contents.getBoolean("enabled"))
                    SceneTreeData.apply(child, contents, syncOnly = true)
                }
            }
            pose != null -> node?.let { SceneTreeData.applyPose(it, pose) }
            else -> node?.remove()
        }
    }
}

/** Puts an object where the editor dragged it, in world space; the object keeps its parent. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class WorldObjectPosePacket(
    val entityId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val rotation: List<Float>,
    val scale: List<Float>,
) : HollowPacket {
    constructor(entityId: Int, pose: ObjectPose) : this(
        entityId,
        pose.position.x, pose.position.y, pose.position.z,
        pose.rotation.let { listOf(it.x, it.y, it.z, it.w) },
        pose.scale.let { listOf(it.x, it.y, it.z) },
    )

    override fun handle(player: Player) {
        if (!player.canEditEntities() || rotation.size != 4 || scale.size != 3) return
        val target = player.level().worldObject(entityId) ?: return
        target.setWorldPose(
            ObjectPose(
                position = Vector3d(x, y, z),
                rotation = Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]).normalize(),
                scale = Vector3f(scale[0], scale[1], scale[2]),
            )
        )
    }
}

/** Moves an object under another one, or out to the world when [parentId] is null, leaving it where it is. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class WorldObjectParentPacket(val entityId: Int, val parentId: Int? = null) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val level = player.level()
        val target = level.worldObject(entityId) ?: return
        val parent = parentId?.let { level.worldObject(it) ?: return }
        target.setParent(parent)
    }
}

/** Removes objects together with everything under them. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RemoveWorldObjectsPacket(val entityIds: List<Int>) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        entityIds.forEach { id ->
            player.level().worldObject(id)?.subtree()?.asReversed()?.forEach { it.remove(Entity.RemovalReason.DISCARDED) }
        }
    }
}

private fun Level.worldObject(id: Int): WorldObjectEntity? = getEntity(id) as? WorldObjectEntity

/** Copies an object with everything under it, in place, and selects the copy for the player. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class DuplicateWorldObjectPacket(val entityId: Int) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val source = player.level().worldObject(entityId) ?: return
        val copies = HashMap<WorldObjectEntity, WorldObjectEntity>()
        source.subtree().forEach { node ->
            val parent = if (node === source) null else node.parent?.let(copies::get) ?: return@forEach
            duplicate(node, parent)?.let { copies[node] = it }
        }
        val copy = copies[source] ?: return
        WorldObjectSpawnedPacket(copy.id).send(player as? ServerPlayer ?: return)
    }

    /** A copy of [source] with its components and scripts, put under [parent], the copy of its own parent. */
    private fun duplicate(source: WorldObjectEntity, parent: WorldObjectEntity?): WorldObjectEntity? {
        val tag = CompoundTag()
        if (!source.save(tag)) return null
        tag.remove("UUID")
        val copy = EntityType.create(tag, source.level()).orElse(null) as? WorldObjectEntity ?: return null
        parent?.let(copy::attachKeepingLocalPose)
        return copy.takeIf { source.level().addFreshEntity(it) }
    }
}

/** Takes the player to an object, loaded or only bookmarked, a few steps off and facing it. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class GoToWorldObjectPacket(val uuid: @Serializable(ForUuid::class) UUID) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val traveler = player as? ServerPlayer ?: return
        val server = traveler.server
        val loaded = server.allLevels.firstNotNullOfOrNull { it.getEntity(uuid) as? WorldObjectEntity }
        val (level, target) = if (loaded != null) {
            loaded.level() as ServerLevel to loaded.position()
        } else {
            val favorite = WorldObjectFavorites.of(server)[uuid] ?: return
            val dimension = ResourceLocation.tryParse(favorite.dimension) ?: return
            val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension)) ?: return
            level to Vec3(favorite.x, favorite.y, favorite.z)
        }

        val heading = traveler.lookAngle.multiply(1.0, 0.0, 1.0).takeIf { it.lengthSqr() > 1.0e-4 }?.normalize() ?: Vec3(0.0, 0.0, 1.0)
        val standAt = target.subtract(heading.scale(STAND_OFF)).add(0.0, 1.0, 0.0)
        val look = target.subtract(standAt)
        val yaw = (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG).toFloat() - 90f
        val pitch = (-Mth.atan2(look.y, look.horizontalDistance()) * Mth.RAD_TO_DEG).toFloat()
        traveler.teleportTo(level, standAt.x, standAt.y, standAt.z, yaw, pitch)
    }

    companion object {
        /** How far from the object the player stands, in blocks. */
        const val STAND_OFF = 4.0
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class SetWorldObjectFavoritePacket(val uuid: @Serializable(ForUuid::class) UUID, val favorite: Boolean) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val server = player.server ?: return
        if (!favorite) {
            WorldObjectFavorites.remove(server, uuid)
            return
        }
        val target = server.allLevels.firstNotNullOfOrNull { it.getEntity(uuid) as? WorldObjectEntity } ?: return
        WorldObjectFavorites.add(target)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RequestWorldObjectFavoritesPacket : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val server = player.server ?: return
        WorldObjectFavoritesPacket(WorldObjectFavorites.of(server).all).send(player as? ServerPlayer ?: return)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class WorldObjectFavoritesPacket(val favorites: List<WorldObjectFavorite>) : HollowPacket {
    override fun handle(player: Player) = WorldObjectEditing.acceptFavorites(favorites)
}
