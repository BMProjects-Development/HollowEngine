package ru.hollowhorizon.hollowengine.addons.physics

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollState
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorlds
import ru.hollowhorizon.hollowengine.api.extensions.closeWith
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorStateFactories
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.models.AnimatorStateTypes

/**
 * Physics for HollowEngine, on Jolt.
 */
class HollowPhysicsAddon : HollowAddonEntrypoint {
    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        AnimatorStateTypes.register(RagdollStateSpec.TYPE).closeWith(scope)
        AnimatorStateFactories.register(RagdollStateSpec.TYPE_ID) { spec ->
            RagdollState(spec as RagdollStateSpec)
        }.closeWith(scope)

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
