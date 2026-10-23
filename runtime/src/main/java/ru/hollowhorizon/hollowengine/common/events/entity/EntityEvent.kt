package ru.hollowhorizon.hollowengine.common.events.entity

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.common.events.Cancellable
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

open class EntityEvent(val entity: Entity) : Event {

    class Hurt(entity: Entity, val source: DamageSource, var amount: Float) : EntityEvent(entity), Cancellable {
        companion object : EventHandler<Hurt>()

        override var isCanceled: Boolean = false
    }

    class ChangeDimension(entity: Entity, val new: Entity, val from: Level, val to: Level) : EntityEvent(entity) {
        companion object : EventHandler<ChangeDimension>()
    }

    /** The entity is being written to NBT; what goes into its data now is part of this save. */
    class Saving(entity: Entity) : EntityEvent(entity) {
        companion object : EventHandler<Saving>()
    }

    /** The entity is leaving its level for [reason]; it is still whole when this is posted. */
    class Removed(entity: Entity, val reason: Entity.RemovalReason) : EntityEvent(entity) {
        companion object : EventHandler<Removed>()
    }
}
