package ru.hollowhorizon.hollowengine.common.entities

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.BodyComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.bodyComponent

/**
 * What [BodyComponent] changes about vanilla: each call takes what vanilla would answer and returns what
 * the entity's body makes of it. An entity without the component keeps vanilla's answer.
 */
object EntityBodies {
    fun isPushable(entity: Entity, vanilla: Boolean): Boolean = vanilla && entity.bodyComponent?.pushable != false

    fun pushesOthers(entity: Entity): Boolean = entity.bodyComponent?.pushesOthers != false

    fun isSolid(entity: Entity, vanilla: Boolean): Boolean = vanilla || entity.bodyComponent?.solid == true && entity.isAlive

    fun dimensions(entity: Entity, vanilla: EntityDimensions): EntityDimensions =
        entity.bodyComponent?.resize(vanilla) ?: vanilla

    /** Resizes [entity] when its components now give it another size than the one it was given last. */
    fun onComponentsChanged(entity: Entity) {
        val runtime = AttachmentRegistry.attachmentsOrNull(entity)?.runtime ?: return
        val size = entity.bodyComponent?.takeIf(BodyComponent::hasSize)?.let { it.width to it.height }
        if (size == runtime.getOrNull<AppliedSize>(AppliedSizeKey)?.size) return

        runtime.remove(AppliedSizeKey)
        runtime.getOrPut(AppliedSizeKey) { AppliedSize(size) }
        entity.refreshDimensions()
    }

    private class AppliedSize(val size: Pair<Float, Float>?)

    private data object AppliedSizeKey
}
