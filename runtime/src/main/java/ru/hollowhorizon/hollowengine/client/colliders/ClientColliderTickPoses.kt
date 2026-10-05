package ru.hollowhorizon.hollowengine.client.colliders

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
 * The colliders that act on bodies, posed tick by tick on this client the way the server poses them.
 * What the player sees is the drawn pose; what the player bumps into has to be the pose of the tick it
 * moves in, the same one the server moves mobs by.
 */
object ClientColliderTickPoses {
    /** This tick and the one before, which is what a collider's motion is measured between. */
    private const val HISTORY_TICKS = 2

    private val assets = HashMap<String, ColliderPoseAssets>()
    private val tracks = ColliderPoseTracks(HISTORY_TICKS, ::assetsOf)

    /** The entities whose colliders act on bodies, as of the last tick. */
    var physical: List<PosedHost> = emptyList()
        private set

    /** The entities with colliders, around where the last tick drew them. */
    var hosts: List<ColliderHost> = emptyList()
        private set

    fun recent(entity: Entity): List<List<EntityCollider>> =
        if (isPhysical(entity)) tracks.track(entity)?.history.orEmpty() else emptyList()

    fun tick(level: ClientLevel?) {
        val entities = level?.let { AttachmentRegistry.entitySnapshots(it).map { entry -> entry.first } }.orEmpty()
        physical = entities.filter(::isPhysical)
            .mapNotNull { entity -> tracks.track(entity, advance = true)?.let { PosedHost.of(entity, it.history) } }
        hosts = entities.mapNotNull { entity ->
            val drawn = ClientColliderPoses.of(entity)
            if (drawn.isEmpty()) null else ColliderHost(entity, drawn.map { it.box.bounds }.reduce(AABB::minmax))
        }
    }

    /**
     * Whether [entity] has colliders that act on bodies, what scripts changed counted: only those are
     * posed every tick here, since that runs the animator once more for each of them.
     */
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
