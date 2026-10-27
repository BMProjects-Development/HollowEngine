package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.Serializable
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory

/** Where [entity] is when the checkpoint is passed; every rewind to the checkpoint brings it back there. */
@EditorCategory("hollowengine.gui.ide.story.category.story")
@Action("position")
suspend fun CheckpointScope.position(entity: Entity) = label(this, entity) {
    val placement = capture { Placement.of(entity) }
    undo { placement.applyTo(entity) }
}

/** What [player] carries when the checkpoint is passed; every rewind to the checkpoint gives back exactly that. */
@EditorCategory("hollowengine.gui.ide.story.category.story")
@Action("inventory")
suspend fun CheckpointScope.inventory(player: ServerPlayer) = label(this, player) {
    val inventory = player.inventory
    val items = capture { (0 until inventory.containerSize).map { inventory.getItem(it).copy() } }
    undo {
        items.forEachIndexed { slot, stack -> inventory.setItem(slot, stack.copy()) }
        player.containerMenu.broadcastChanges()
    }
}

/** A place and a facing in a dimension. */
@Serializable
internal class Placement(
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    fun applyTo(entity: Entity) {
        val server = entity.server ?: return
        val key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension))
        val level: ServerLevel = server.getLevel(key) ?: throw StoryException("Dimension '$dimension' does not exist")
        entity.teleportTo(level, x, y, z, emptySet(), yaw, pitch)
        entity.deltaMovement = entity.deltaMovement.scale(0.0)
    }

    companion object {
        fun of(entity: Entity) = Placement(
            entity.level().dimension().location().toString(), entity.x, entity.y, entity.z, entity.yRot, entity.xRot,
        )
    }
}
