package ru.hollowhorizon.hollowengine.addons.physics

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.collider.HullFitter
import ru.hollowhorizon.hollowengine.addons.physics.collider.JoltColliderFactory
import ru.hollowhorizon.hollowengine.addons.physics.collider.JoltColliderShapes
import ru.hollowhorizon.hollowengine.addons.physics.collider.PhysicsColliderShapes
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollReplicas
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollState
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.*
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorlds
import ru.hollowhorizon.hollowengine.api.extensions.closeWith
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorStateFactories
import ru.hollowhorizon.hollowengine.client.models.internal.rig.ColliderFitters
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigGenerators
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigOverlays
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigPreviews
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentFactories
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeFactories
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeTypes
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.models.AnimatorStateTypes
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes

/**
 * Physics for HollowEngine, on Jolt.
 */
class HollowPhysicsAddon : HollowAddonEntrypoint {
    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        AnimatorStateTypes.register(RagdollStateSpec.TYPE).closeWith(scope)
        AnimatorStateFactories.register(RagdollStateSpec.TYPE_ID) { spec ->
            RagdollState(spec as RagdollStateSpec)
        }.closeWith(scope)

        RigAttachmentTypes.register(RigidBodyAttachmentSpec.TYPE).closeWith(scope)
        RigAttachmentFactories.register(RigidBodyAttachmentSpec.TYPE_ID) { spec, context ->
            RigidBodyAttachment(spec as RigidBodyAttachmentSpec, context.node)
        }.closeWith(scope)
        RigAttachmentTypes.register(JointAttachmentSpec.TYPE).closeWith(scope)
        RigAttachmentFactories.register(JointAttachmentSpec.TYPE_ID) { spec, context ->
            JointAttachment(spec as JointAttachmentSpec, context.node)
        }.closeWith(scope)

        PhysicsColliderShapes.TYPES.forEach { type ->
            ColliderShapeTypes.register(type).closeWith(scope)
            ColliderShapeFactories.register(type.id, JoltColliderFactory).closeWith(scope)
        }

        RigPreviews.register("hollowengine:physics/ragdoll") { RagdollPreview() }.closeWith(scope)
        RigOverlays.register(PhysicsRigOverlay.ID, PhysicsRigOverlay).closeWith(scope)
        RigGenerators.register(
            id = RagdollRigGenerator.ID,
            titleKey = "hollowengine.gui.rig_editor.generate_ragdoll",
            generator = RagdollRigGenerator,
            icon = "hollowengine-physics:textures/gui/icons/ragdoll.svg",
        ).closeWith(scope)
        ColliderFitters.register(HullFitter.ID, HullFitter).closeWith(scope)

        if (JoltNatives.ensureLoaded().isSuccess) {
            HollowEngine.LOGGER.info("Physics is ready")
        }
    }

    override suspend fun unload(context: HollowAddonContext) {
        PhysicsWorlds.closeAll()
        JoltColliderShapes.clear()
    }

    /**
     * The client keeps the simulation of the level it is in, and the ragdolls the server sent of entities it still has.
     */
    @ClientOnly
    @SubscribeEvent
    fun onClientTick(event: TickEvent.Client) {
        val level = event.minecraft.level
        PhysicsWorlds.retainClient(level)
        level?.let { PhysicsWorlds.find(it)?.tick(SECONDS_PER_TICK) }
        if (level == null) RagdollReplicas.clear() else RagdollReplicas.retain { level.getEntity(it) != null }
    }

    /** The server ticks the simulation of each of its levels; the ragdolls in it are stepped as they are posed. */
    @SubscribeEvent
    fun onServerTick(event: TickEvent.Server) {
        PhysicsWorlds.serverWorlds.forEach { it.tick(SECONDS_PER_TICK) }
    }

    @SubscribeEvent
    fun onLevelUnload(event: LevelEvent.Unload) {
        if (!event.level.isClientSide) PhysicsWorlds.close(event.level)
    }

    private companion object {
        const val SECONDS_PER_TICK = 1f / 20f
    }
}
