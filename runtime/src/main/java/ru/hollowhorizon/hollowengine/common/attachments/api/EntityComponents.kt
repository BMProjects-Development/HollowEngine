package ru.hollowhorizon.hollowengine.common.attachments.api

import net.minecraft.world.entity.Entity

/**
 * Attaches [component] to this entity, replacing the one it already had of that type.
 */
infix fun Entity.set(component: Any) {
    AttachmentRegistry.attachments(this).components.put(component)
}
