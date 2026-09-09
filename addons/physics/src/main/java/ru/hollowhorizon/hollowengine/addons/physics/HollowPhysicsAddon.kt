package ru.hollowhorizon.hollowengine.addons.physics

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollState
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachment
import ru.hollowhorizon.hollowengine.addons.physics.rig.PhysicsRigOverlay
import ru.hollowhorizon.hollowengine.addons.physics.rig.RagdollPreview
import ru.hollowhorizon.hollowengine.addons.physics.rig.RagdollRigGenerator
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachment
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorlds
import ru.hollowhorizon.hollowengine.api.extensions.closeWith
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorStateFactories
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigGenerators
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigOverlays
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigPreviews
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentFactories
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
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

        RigPreviews.register("hollowengine:physics/ragdoll") { RagdollPreview() }.closeWith(scope)
        RigOverlays.register(PhysicsRigOverlay.ID, PhysicsRigOverlay).closeWith(scope)
        RigGenerators.register(
            id = RagdollRigGenerator.ID,
            titleKey = "hollowengine.gui.rig_editor.generate_ragdoll",
            generator = RagdollRigGenerator,
        ).closeWith(scope)

        if (JoltNatives.ensureLoaded().isSuccess) {
            HollowEngine.LOGGER.info("Physics is ready")
        }
    }

    override suspend fun unload(context: HollowAddonContext) {
        PhysicsWorlds.closeAll()
    }

    /**
     * The client keeps one simulation per level.
     */
    @SubscribeEvent
    fun onClientTick(event: TickEvent.Client) {
        val level = event.minecraft.level
        PhysicsWorlds.retainOnly(level)
        level?.let { PhysicsWorlds.find(it)?.tick(SECONDS_PER_TICK) }
    }

    private companion object {
        const val SECONDS_PER_TICK = 1f / 20f
    }
}
