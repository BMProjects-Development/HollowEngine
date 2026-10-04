package ru.hollowhorizon.hollowengine.common.entities.objects

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.entity.EntityInLevelCallback
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.common.attachments.components.bodyComponent
import ru.hollowhorizon.hollowengine.common.colliders.EntityColliders
import ru.hollowhorizon.hollowengine.common.registry.ModEntities
import java.util.Optional
import java.util.UUID

/**
 * A thing placed in the world: a decoration, a lamp, a lever. It has no health, AI, gravity or physics and
 * never despawns; what it looks like and does comes from its components and node scripts.
 */
class WorldObjectEntity(type: EntityType<WorldObjectEntity>, level: Level) : Entity(type, level) {
    constructor(level: Level) : this(ModEntities.OBJECT, level)

    /** The local pose as it is drawn: on the client it eases toward what the server sent. */
    private var shown = ObjectPose()
    private var shownBefore = ObjectPose()
    private var shownSteps = 0

    /** Where a root is moving to on the client, after a position update from the server. */
    private var positionTarget: Vector3d? = null
    private var positionSteps = 0

    /** Set while a change must show at once rather than ease in, like one the editor predicts. */
    private var snapping = false

    /** The tick the parent last changed in: the new local pose that comes with it shows at once, not eased from the old one. */
    private var parentChangedTick = -1

    private var shownName: String? = null

    init {
        noPhysics = true
    }

    val parentId: UUID? get() = entityData.get(PARENT).orElse(null)

    /** The parent, when it is loaded on this side. */
    val parent: WorldObjectEntity?
        get() = parentId?.let { WorldObjects.find(level(), it) }

    /** The pose relative to the parent, or to the world without one. */
    val localPose: ObjectPose
        get() = ObjectPose(
            position = if (parentId == null) Vector3d(x, y, z) else Vector3d(entityData.get(OFFSET)),
            rotation = Quaternionf(entityData.get(ROTATION)),
            scale = Vector3f(entityData.get(SCALE)),
        )

    /** The pose in the world, [partialTick] of the way into the current tick. */
    fun pose(partialTick: Float): ObjectPose = pose(partialTick, 0)

    private fun pose(partialTick: Float, depth: Int): ObjectPose {
        val local = if (partialTick >= 1f) shown else shown.interpolated(shownBefore, partialTick)
        if (parentId == null) return ObjectPose(positionAt(partialTick), local.rotation, local.scale)
        val parent = parent?.takeIf { depth < MAX_DEPTH } ?: return ObjectPose(
            positionAt(partialTick),
            Quaternionf(entityData.get(WORLD_ROTATION)),
            Vector3f(entityData.get(WORLD_SCALE)),
        )
        return parent.pose(partialTick, depth + 1).compose(local)
    }

    /** The entity's own position: a root's, or where a child was left while its parent is not loaded. */
    private fun positionAt(partialTick: Float) = Vector3d(
        Mth.lerp(partialTick.toDouble(), xOld, x),
        Mth.lerp(partialTick.toDouble(), yOld, y),
        Mth.lerp(partialTick.toDouble(), zOld, z),
    )

    /** Places this object so that it ends up at [world], keeping its parent. */
    fun setWorldPose(world: ObjectPose, snap: Boolean = false) {
        if (parentId == null) {
            setLocalPose(world, snap)
            return
        }
        val parent = parent ?: return
        setLocalPose(parent.pose(1f).relativize(world), snap)
    }

    /** Places this object relative to its parent, or in the world without one. */
    fun setLocalPose(local: ObjectPose, snap: Boolean = false) {
        snapping = snap
        if (parentId == null) {
            setPos(local.position.x, local.position.y, local.position.z)
            positionSteps = 0
        } else {
            entityData.set(OFFSET, Vector3f().set(local.position))
        }
        entityData.set(ROTATION, Quaternionf(local.rotation).normalize())
        entityData.set(SCALE, Vector3f(local.scale))
        snapping = false
        if (snap) {
            shownBefore = shown
            setOldPosAndRot()
        }
        updateWorldCache()
    }

    /**
     * Moves this object under [newParent], or out to the world, without moving it. False when that would
     * put it under itself.
     */
    fun setParent(newParent: WorldObjectEntity?): Boolean {
        if (newParent != null && (newParent === this || newParent.isDescendantOf(this))) return false
        val world = pose(1f)
        entityData.set(PARENT, Optional.ofNullable(newParent?.uuid))
        setWorldPose(world, snap = true)
        return true
    }

    /**
     * Hangs this object under [newParent] keeping its local pose as it is: for a copy of a child, whose
     * pose was taken relative to a parent standing exactly where [newParent] stands.
     */
    internal fun attachKeepingLocalPose(newParent: WorldObjectEntity) {
        entityData.set(PARENT, Optional.of(newParent.uuid))
    }

    /** Whether [ancestor] is above this object, however far up. */
    fun isDescendantOf(ancestor: WorldObjectEntity): Boolean {
        var current = parent
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    /** The loaded objects directly under this one. */
    fun children(): List<WorldObjectEntity> = WorldObjects.all(level()).filter { it.parentId == uuid }

    override fun tick() {
        shownBefore = shown
        if (level().isClientSide) ease()
        followParent()
        if (parent != null) updateWorldCache()
        firstTick = false
    }

    /** Moves the drawn pose a step toward the one the server sent, the way living entities ease. */
    private fun ease() {
        if (shownSteps > 0) {
            shown = localData().interpolated(shown, 1f / shownSteps)
            shownSteps--
        }
        val target = positionTarget
        if (positionSteps > 0 && target != null && parentId == null) {
            val weight = 1.0 / positionSteps
            setPos(x + (target.x - x) * weight, y + (target.y - y) * weight, z + (target.z - z) * weight)
            positionSteps--
        }
    }

    private fun followParent() {
        val parent = parent ?: return
        val world = parent.pose(1f).compose(shown)
        if (world.position.distanceSquared(x, y, z) > MOVE_EPSILON) {
            setPos(world.position.x, world.position.y, world.position.z)
        }
    }

    /** Keeps the world rotation and scale, which a child shows while its parent is not loaded. */
    private fun updateWorldCache() {
        if (level().isClientSide || parentId != null && parent == null) return
        val world = pose(1f)
        entityData.set(WORLD_ROTATION, world.rotation)
        entityData.set(WORLD_SCALE, world.scale)
    }

    /** The local pose as the synced data has it, offset included. */
    private fun localData() = ObjectPose(
        position = Vector3d(entityData.get(OFFSET)),
        rotation = Quaternionf(entityData.get(ROTATION)),
        scale = Vector3f(entityData.get(SCALE)),
    )

    override fun lerpTo(x: Double, y: Double, z: Double, yRot: Float, xRot: Float, steps: Int) {
        if (parentId != null) return
        positionTarget = Vector3d(x, y, z)
        positionSteps = steps
    }

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        when (accessor) {
            OFFSET, ROTATION, SCALE -> {
                if (level().isClientSide && !snapping && !firstTick && tickCount != parentChangedTick) {
                    shownSteps = LERP_STEPS
                } else {
                    shown = localData()
                    shownBefore = shown
                }
            }

            PARENT -> {
                parentChangedTick = tickCount
                shown = localData()
                shownBefore = shown
                WorldObjects.changed(level())
            }
        }
        val name = customName?.string
        if (name != shownName) {
            shownName = name
            WorldObjects.changed(level())
        }
    }

    /** The components changed, and with them what the scene window shows for this object. */
    fun onComponentsChanged() {
        WorldObjects.changed(level())
    }

    override fun setLevelCallback(callback: EntityInLevelCallback) {
        super.setLevelCallback(callback)
        if (callback === EntityInLevelCallback.NULL) WorldObjects.remove(this) else WorldObjects.add(this)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(PARENT, Optional.empty())
        builder.define(OFFSET, Vector3f())
        builder.define(ROTATION, Quaternionf())
        builder.define(SCALE, Vector3f(1f))
        builder.define(WORLD_ROTATION, Quaternionf())
        builder.define(WORLD_SCALE, Vector3f(1f))
    }

    /** Met by its colliders, or by its box when the body gives it a size; otherwise the crosshair passes through. */
    override fun isPickable(): Boolean = EntityColliders.hasTargets(this) || bodyComponent?.hasSize == true

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        val reach = RENDER_DISTANCE * getViewScale()
        return distance < reach * reach
    }

    override fun ignoreExplosion(explosion: Explosion): Boolean = true

    override fun isIgnoringBlockTriggers(): Boolean = true

    override fun canChangeDimensions(from: Level, to: Level): Boolean = false

    override fun readAdditionalSaveData(tag: CompoundTag) {
        val data = tag.getCompound(SAVE_KEY)
        entityData.set(PARENT, Optional.ofNullable(if (data.hasUUID("Parent")) data.getUUID("Parent") else null))
        data.readVector("Offset")?.let { entityData.set(OFFSET, it) }
        data.readRotation("Rotation")?.let { entityData.set(ROTATION, it) }
        data.readVector("Scale")?.let { entityData.set(SCALE, it) }
        data.readRotation("WorldRotation")?.let { entityData.set(WORLD_ROTATION, it) }
        data.readVector("WorldScale")?.let { entityData.set(WORLD_SCALE, it) }
        shown = localData()
        shownBefore = shown
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        val data = CompoundTag()
        parentId?.let { data.putUUID("Parent", it) }
        data.put("Offset", entityData.get(OFFSET).let { newFloatList(it.x, it.y, it.z) })
        data.put("Rotation", entityData.get(ROTATION).let { newFloatList(it.x, it.y, it.z, it.w) })
        data.put("Scale", entityData.get(SCALE).let { newFloatList(it.x, it.y, it.z) })
        data.put("WorldRotation", entityData.get(WORLD_ROTATION).let { newFloatList(it.x, it.y, it.z, it.w) })
        data.put("WorldScale", entityData.get(WORLD_SCALE).let { newFloatList(it.x, it.y, it.z) })
        tag.put(SAVE_KEY, data)
        if (!level().isClientSide) WorldObjectFavorites.refresh(this)
    }

    override fun remove(reason: RemovalReason) {
        super.remove(reason)
        if (!level().isClientSide && reason.shouldDestroy()) WorldObjectFavorites.forget(this)
    }

    private fun CompoundTag.readVector(key: String): Vector3f? {
        val list = floats(key, 3) ?: return null
        return Vector3f(list.getFloat(0), list.getFloat(1), list.getFloat(2))
    }

    private fun CompoundTag.readRotation(key: String): Quaternionf? {
        val list = floats(key, 4) ?: return null
        return Quaternionf(list.getFloat(0), list.getFloat(1), list.getFloat(2), list.getFloat(3)).normalize()
    }

    private fun CompoundTag.floats(key: String, size: Int): ListTag? =
        getList(key, Tag.TAG_FLOAT.toInt()).takeIf { it.size == size }

    companion object {
        private val PARENT: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(WorldObjectEntity::class.java, EntityDataSerializers.OPTIONAL_UUID)
        private val OFFSET: EntityDataAccessor<Vector3f> =
            SynchedEntityData.defineId(WorldObjectEntity::class.java, EntityDataSerializers.VECTOR3)
        private val ROTATION: EntityDataAccessor<Quaternionf> =
            SynchedEntityData.defineId(WorldObjectEntity::class.java, EntityDataSerializers.QUATERNION)
        private val SCALE: EntityDataAccessor<Vector3f> =
            SynchedEntityData.defineId(WorldObjectEntity::class.java, EntityDataSerializers.VECTOR3)
        private val WORLD_ROTATION: EntityDataAccessor<Quaternionf> =
            SynchedEntityData.defineId(WorldObjectEntity::class.java, EntityDataSerializers.QUATERNION)
        private val WORLD_SCALE: EntityDataAccessor<Vector3f> =
            SynchedEntityData.defineId(WorldObjectEntity::class.java, EntityDataSerializers.VECTOR3)

        private const val SAVE_KEY = "HollowObject"

        /** How deep a hierarchy is followed; deeper parents are treated as missing. */
        private const val MAX_DEPTH = 64

        /** Ticks a change from the server takes to ease in on the client. */
        private const val LERP_STEPS = 3

        /** In blocks; far enough for a decoration to stay in view across its tracking range. */
        private const val RENDER_DISTANCE = 160.0

        private const val MOVE_EPSILON = 1.0e-8
    }
}
