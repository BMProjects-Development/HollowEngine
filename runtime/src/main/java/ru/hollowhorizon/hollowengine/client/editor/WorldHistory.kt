package ru.hollowhorizon.hollowengine.client.editor

import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.history.UndoStep
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent

/**
 * Everything edited in the world, in the order it was done: an object moved by the gizmo or put under
 * another, a bone posed, something hung on a model.
 */
@ClientOnly
internal object WorldHistory : UndoOwner {
    const val LIMIT = 200

    override val history = UndoHistory(LIMIT)

    /** Remembers [step], done to the entity [entityId]. */
    fun record(entityId: Int, step: UndoStep, mergeKey: String? = null) = history.record(EntityStep(entityId, step), mergeKey)

    /** Drops the steps of [entityId]: something else changed it, and taking them back would undo that too. */
    fun forget(entityId: Int) = history.removeAll { it is EntityStep && it.entityId == entityId }

    @SubscribeEvent
    fun onLevelUnload(event: LevelEvent.Unload) {
        if (event.level.isClientSide) history.clear()
    }

    private class EntityStep(val entityId: Int, private val step: UndoStep) : UndoStep by step {
        override fun merge(next: UndoStep): UndoStep? {
            if (next !is EntityStep || next.entityId != entityId) return null
            return step.merge(next.step)?.let { EntityStep(entityId, it) }
        }
    }
}
