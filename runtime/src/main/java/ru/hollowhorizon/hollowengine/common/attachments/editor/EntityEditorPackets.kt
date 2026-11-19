@file:UseSerializers(ForResourceLocation::class)

package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.scene.acceptsComponentOwner
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.ui.entity.EntityEditorClient
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.scripting.NODE_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.execution.ServerScriptAccessDenied
import ru.hollowhorizon.hollowengine.common.scripting.execution.canControlServerScripts
import ru.hollowhorizon.hollowengine.common.scripting.nodes.EntityNodeRuntime
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeAttachTargets
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.slots.*
import ru.hollowhorizon.hollowengine.common.ui.net.UiSessionManager
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import ru.hollowhorizon.hollowengine.common.utils.mcTranslate
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForResourceLocation

/**
 * Everything the editor needs about one entity, in one packet.
 */
@Serializable
data class EntityEditorSnapshot(
    val entityId: Int,
    val title: String = "",
    val typeId: String = "",
    val isPlayer: Boolean = false,
    val stored: List<@Polymorphic Any> = emptyList(),
    val virtual: List<@Polymorphic Any> = emptyList(),
    val attachedScripts: List<ScriptEditorInfo> = emptyList(),
    val availableScripts: List<String> = emptyList(),
    val scriptFiles: List<String> = emptyList(),
    val hasSlots: Boolean = false,
)

fun Player.canEditEntities(): Boolean = hasPermissions(PlayerPermissions.GAMEMASTER)

object EntityEditorService {
    fun snapshot(entity: Entity): EntityEditorSnapshot {
        val files = ScriptRegistry.list()

        return EntityEditorSnapshot(
            entityId = entity.id,
            title = entity.displayName?.string ?: entity.name.string,
            typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString(),
            isPlayer = entity is Player,
            stored = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.components.orEmpty(),
            virtual = VirtualComponentRegistry.read(entity),
            attachedScripts = EntityNodeRuntime.editors(entity),
            availableScripts = files.filter {
                    it.path.endsWith(".$NODE_SCRIPT_EXTENSION") && NodeAttachTargets.accepts(
                        it,
                        entity.javaClass
                    )
                }.map(ScriptRegistry::display).sorted(),
            scriptFiles = files.map(ScriptRegistry::display).sorted(),
            hasSlots = entity is LivingEntity,
        )
    }

    fun sendState(player: ServerPlayer, entity: Entity) {
        EntityEditorStatePacket(snapshot(entity)).send(player)
    }

    fun apply(entity: Entity, component: Any) {
        if (!component::class.acceptsComponentOwner(entity.javaClass)) return
        if (VirtualComponentRegistry.apply(entity, component)) return
        val id = ComponentDescriptorRegistry.idFor(component::class) ?: return
        AttachmentRegistry.componentsById(entity)[id] = component
    }

    fun remove(entity: Entity, id: ResourceLocation) {
        if (VirtualComponentRegistry.isVirtual(id)) return
        AttachmentRegistry.componentsById(entity).remove(id)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RequestEntityEditorPacket(val entityId: Int) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val target = player.level().getEntity(entityId) ?: return
        EntityEditorService.sendState(player as? ServerPlayer ?: return, target)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class EntityEditorStatePacket(val state: EntityEditorSnapshot) : HollowPacket {
    override fun handle(player: Player) = EntityEditorClient.accept(state)
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class SetEntityComponentsPacket(
    val entityId: Int,
    val components: List<@Polymorphic Any> = emptyList(),
) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val target = player.level().getEntity(entityId) ?: return
        components.forEach { EntityEditorService.apply(target, it) }

        if (components.any(VirtualComponentRegistry::isVirtual)) {
            EntityEditorService.sendState(player as? ServerPlayer ?: return, target)
        }
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RemoveEntityComponentPacket(val entityId: Int, val component: ResourceLocation) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val target = player.level().getEntity(entityId) ?: return
        EntityEditorService.remove(target, component)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class EntityNodeScriptPacket(val entityId: Int, val path: String, val attach: Boolean) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val requester = player as? ServerPlayer ?: return
        val target = player.level().getEntity(entityId) ?: return
        if (!requester.canControlServerScripts()) {
            requester.sendSystemMessage(ServerScriptAccessDenied.mcTranslate())
            EntityEditorService.sendState(requester, target)
            return
        }
        if (attach) EntityNodeRuntime.attach(target, path) else EntityNodeRuntime.detach(target, path)
        EntityEditorService.sendState(requester, target)
    }
}


@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class SetEntityScriptValuesPacket(val entityId: Int, val path: String, val values: String) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val requester = player as? ServerPlayer ?: return
        val target = player.level().getEntity(entityId) ?: return
        if (!requester.canControlServerScripts()) {
            requester.sendSystemMessage(ServerScriptAccessDenied.mcTranslate())
            EntityEditorService.sendState(requester, target)
            return
        }
        val parsed = runCatching { ScriptEditorJson.parseToJsonElement(values) as? JsonObject }.getOrNull() ?: return
        EntityNodeRuntime.applyEditorValues(target, path, parsed)
    }
}


object EntityEditorSlots {
    const val EQUIPMENT = "equipment"
    const val INVENTORY = "inventory"

    val SURFACE: ResourceLocation = ResourceLocation.fromNamespaceAndPath("hollowengine", "entity_editor_slots")
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RequestEntitySlotsPacket(val entityId: Int) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.canEditEntities()) return
        val owner = player as? ServerPlayer ?: return
        val target = owner.level().getEntity(entityId) ?: return
        val living = target as? LivingEntity
        val inventory = (target as? NpcEntity)?.inventory
        if (living == null || inventory == null) return

        val equipmentSlots = EquipmentSource.All

        val session = UiSessionManager.openHeadless(owner, EntityEditorSlots.SURFACE) {
            slots {
                zone(EntityEditorSlots.EQUIPMENT, EquipmentSource(living, equipmentSlots)) {
                    role = SlotZoneRole.NONE
                    copyOnClick = true
                }
                zone(EntityEditorSlots.INVENTORY, NpcInventorySource(inventory)) {
                    role = SlotZoneRole.NONE
                    copyOnClick = true
                }

                playerZones(owner, storage = SlotZoneRole.NONE, armor = null, offhand = null)
            }
        }
        EntitySlotsSessionPacket(entityId, session.id).send(owner)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class EntitySlotsSessionPacket(val entityId: Int, val sessionId: Int) : HollowPacket {
    override fun handle(player: Player) = EntityEditorClient.acceptSlots(entityId, sessionId)
}
