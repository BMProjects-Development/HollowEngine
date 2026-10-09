package ru.hollowhorizon.hollowengine.client.colliders

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.colliders.ColliderHost
import ru.hollowhorizon.hollowengine.common.colliders.ColliderModes
import ru.hollowhorizon.hollowengine.common.colliders.ColliderPoseAssets
import ru.hollowhorizon.hollowengine.common.colliders.ColliderPoseTracks
import ru.hollowhorizon.hollowengine.common.colliders.EntityCollider
import ru.hollowhorizon.hollowengine.common.colliders.EntityColliders
import ru.hollowhorizon.hollowengine.common.colliders.PosedHost
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/**
 * The colliders of entities on this client, posed tick by tick the way the server poses them: from the
 * animator and the rig alone, without what only the drawing adds, as client-only layers or feet put on the
 * ground. What the player aims at, bumps into and sees in the hitbox view is then what the server checks hits
 * and moves mobs by.
 */
object ClientColliderTickPoses {
    /** This tick and the one before: what a collider's motion is measured between, and what a frame lies between. */
    private const val HISTORY_TICKS = 2

    private val assets = HashMap<String, ColliderPoseAssets>()
    private val tracks = ColliderPoseTracks(HISTORY_TICKS, ::assetsOf)

    /** The entities whose colliders act on bodies, as of the last tick. */
    var physical: List<PosedHost> = emptyList()
        private set

    /** The entities with colliders, around where the last tick put them. */
    var hosts: List<ColliderHost> = emptyList()
        private set

    fun recent(entity: Entity): List<List<EntityCollider>> =
        if (isPhysical(entity)) tracks.track(entity)?.history.orEmpty() else emptyList()

    /** The colliders of [entity] where the frame being drawn shows it. */
    fun now(entity: Entity): List<EntityCollider> = at(entity, Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false))

    fun at(entity: Entity, partialTick: Float): List<EntityCollider> {
        val history = tracks.track(entity)?.history ?: return emptyList()
        val now = history.firstOrNull() ?: return emptyList()
        val before = history.getOrNull(1)?.associateBy { it.bone to it.name } ?: return now
        return now.map { collider ->
            val previous = before[collider.bone to collider.name] ?: return@map collider
            EntityCollider(collider.name, collider.bone, collider.spec, previous.volume.lerp(collider.volume, partialTick.toDouble()))
        }
    }

    fun tick(level: ClientLevel?) {
        val entities = level?.let { AttachmentRegistry.entitySnapshots(it).map { entry -> entry.first } }.orEmpty()
        val posed = entities.filter { EntityColliders.hasTargets(it) { true } }
            .mapNotNull { entity -> tracks.track(entity, advance = true)?.let { entity to it } }
        physical = posed.filter { (entity, _) -> isPhysical(entity) }.mapNotNull { (entity, track) -> PosedHost.of(entity, track.history) }
        hosts = posed.mapNotNull { (entity, track) ->
            val now = track.history.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ColliderHost(entity, now.map { it.volume.bounds }.reduce(AABB::minmax))
        }
    }

    private fun isPhysical(entity: Entity): Boolean = EntityColliders.hasTargets(entity, ColliderModes::isPhysical)

    /** Made again whenever the rig, the model or its animator are replaced, as after a reload or a save. */
    private fun assetsOf(model: String): ColliderPoseAssets? {
        if (model.isBlank()) return EmptyModel
        val location = ResourceLocation.tryParse(model) ?: return null
        val rig = RigAssets.of(location)
        val loaded = HollowModelManager.getOrCreate(location).value.takeIf { it !== Model.EMPTY } ?: return null
        val animator = HollowModelManager.animatorOf(location)
        val cached = assets[model]
        if (cached != null && cached.rig === rig && cached.model === loaded && cached.animator === animator) return cached
        return ColliderPoseAssets(rig, animator, loaded).also { assets[model] = it }
    }

    /** A blank model has no skeleton: only what hangs on the model itself has a place. */
    private val EmptyModel = ColliderPoseAssets(ModelRig.EMPTY, null, Model.EMPTY)
}
