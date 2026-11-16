package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import kotlin.coroutines.CoroutineContext

/**
 * The scope every coroutine bound to one entity hangs off: node scripts attach their own child scope to
 * it, so cancelling this one tears the entity's nodes down with it.
 */
class EntityScope(override val coroutineContext: CoroutineContext) : CoroutineScope {
    constructor(entity: Entity) : this(
        SupervisorJob(entity.server?.coroutineScope?.coroutineContext?.get(Job)) +
            (entity.server?.dispatcher ?: Minecraft.getInstance().dispatcher)
    )
}
