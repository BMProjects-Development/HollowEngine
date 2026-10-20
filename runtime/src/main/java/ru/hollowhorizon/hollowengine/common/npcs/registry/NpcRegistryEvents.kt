package ru.hollowhorizon.hollowengine.common.npcs.registry

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityLoadedEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent

private val MinecraftServer.npcsOrNull: ServerNpcs? get() = ServerRuntimeState.contextOrNull(this)?.npcs

@SubscribeEvent
fun onNpcJoinedLevel(event: EntityLoadedEvent) {
    val npc = event.entity as? NpcEntity ?: return
    if (npc.level().isClientSide) return
    npc.server?.npcsOrNull?.onJoined(npc)
}

@SubscribeEvent
fun onNpcEntityRemoved(event: EntityEvent.Removed) {
    event.entity.server?.npcsOrNull?.onEntityRemoved(event.entity, event.reason)
}

@SubscribeEvent
fun onNpcChunkLoaded(event: LevelEvent.ChunkEntitiesLoaded) {
    event.serverLevel.server.npcsOrNull?.onChunkEntitiesLoaded(event.serverLevel, event.chunk.toLong())
}

@SubscribeEvent
fun onNpcLevelSaved(event: LevelEvent.Save) {
    val level = event.level as? ServerLevel ?: return
    level.server.npcsOrNull?.onLevelSaved(level)
}

@SubscribeEvent
fun onNpcServerTick(event: TickEvent.Server) {
    event.server.npcsOrNull?.tick()
}
