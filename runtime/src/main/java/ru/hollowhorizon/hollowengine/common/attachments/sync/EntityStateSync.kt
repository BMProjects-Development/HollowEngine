package ru.hollowhorizon.hollowengine.common.attachments.sync

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.data.NbtDataStore
import ru.hollowhorizon.hollowengine.common.data.Sync
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityTrackingEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.network.sendTrackingEntity
import java.lang.ref.WeakReference
import java.util.*

/**
 * Keeps every `@Syncable` component and every synced data key in step with the clients tracking the entity.
 *
 * Writing a component marks its entity dirty; one batch per entity goes out at the end of the server
 * tick. Batching is what makes a script safe: spawning an NPC, giving it a model and starting an
 * animation in the same tick used to send three packets, the first of them before the entity existed
 * for anyone.
 */
object EntityStateSync {
    /** How long a batch waits for an entity that has not arrived yet: ~5 seconds. */
    internal const val PENDING_TIMEOUT_TICKS = 100

    private val dirty = Collections.synchronizedSet(LinkedHashSet<Entity>())
    private val deferred = LinkedHashMap<Int, DeferredBatches>()

    private val receivedListeners = ArrayList<(Entity) -> Unit>()

    /** Calls [listener] on the client with every entity a batch from the server was just applied to. */
    fun onReceived(listener: (Entity) -> Unit) {
        receivedListeners += listener
    }

    /** Marks [entity]'s synced state as needing a batch. Called for every component and data write. */
    fun markDirty(entity: Entity) {
        if (entity.level().isClientSide) return
        dirty += entity
    }

    fun forget(entity: Entity) {
        dirty -= entity
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.Server) {
        flush(event.server)
    }

    /**
     * The baseline for a player that just started tracking entity. Vanilla has already sent the spawn
     * packet by this point, so this is the earliest moment the client can make sense of the state.
     */
    @SubscribeEvent
    fun onStartTracking(event: EntityTrackingEvent.Start) {
        val player = event.player as? ServerPlayer ?: return
        sendBaseline(player, event.entity)
    }

    /** A player does not track itself, so its own state has to be pushed explicitly. */
    fun sendSelfBaseline(player: ServerPlayer) {
        sendBaseline(player, player)
    }

    private fun sendBaseline(player: ServerPlayer, entity: Entity) {
        val components = syncableOf(entity)
        val data = dataOf(entity, owner = player === entity)
        if (components.isEmpty() && data.isEmpty) return

        EntityStateSyncPacket(
            entityId = entity.id,
            version = AttachmentRegistry.syncVersion(entity),
            full = true,
            changed = EntitySnapshot(components.values.toList()),
            dataChanged = data,
        ).send(player)
    }

    private fun flush(server: MinecraftServer) {
        val pending = synchronized(dirty) {
            if (dirty.isEmpty()) return
            dirty.toList().also { dirty.clear() }
        }

        pending.forEach { entity ->
            if (entity.level().isClientSide || entity.isRemoved) return@forEach
            if (entity.level().server !== server) return@forEach
            runCatching { flushEntity(entity) }
        }
    }

    private fun flushEntity(entity: Entity) {
        val components = syncableOf(entity)
        val batch = batchOf(components, AttachmentRegistry.lastSyncedComponents(entity))

        val tracked = dataOf(entity, owner = false)
        val trackedBatch = dataBatchOf(tracked, AttachmentRegistry.lastSyncedData(entity))

        val owner = entity as? ServerPlayer
        val owned = if (owner == null) CompoundTag() else ownerDataOf(entity)
        val ownedBatch = dataBatchOf(owned, AttachmentRegistry.lastSyncedOwnerData(entity))

        if (batch.isEmpty && trackedBatch.isEmpty && ownedBatch.isEmpty) return

        val version = AttachmentRegistry.nextSyncVersion(entity)
        AttachmentRegistry.setLastSyncedComponents(entity, components)
        AttachmentRegistry.setLastSyncedData(entity, tracked)
        AttachmentRegistry.setLastSyncedOwnerData(entity, owned)

        val snapshot = EntitySnapshot(batch.changed.values.toList())
        if (!batch.isEmpty || !trackedBatch.isEmpty) {
            EntityStateSyncPacket(
                entityId = entity.id,
                version = version,
                changed = snapshot,
                removed = batch.removed,
                dataChanged = trackedBatch.changed,
                dataRemoved = trackedBatch.removed,
            ).sendTrackingEntity(entity)
        }

        if (owner == null) return
        EntityStateSyncPacket(
            entityId = entity.id,
            version = version,
            changed = snapshot,
            removed = batch.removed,
            dataChanged = merged(trackedBatch.changed, ownedBatch.changed),
            dataRemoved = trackedBatch.removed + ownedBatch.removed,
        ).send(owner)
    }

    /**
     * Applies a batch on the client, holding on to it when the entity is not there yet.
     */
    internal fun receive(level: Level, packet: EntityStateSyncPacket) {
        val entity = level.getEntity(packet.entityId)
        if (entity == null) {
            defer(level, packet)
            return
        }
        applyTo(entity, packet)
    }

    private fun applyTo(entity: Entity, packet: EntityStateSyncPacket) {
        if (!shouldApply(packet.version, AttachmentRegistry.syncVersion(entity), packet.full)) return
        AttachmentRegistry.setSyncVersion(entity, packet.version)

        val components = AttachmentRegistry.componentsById(entity)
        if (packet.full) {
            components.clear()
            packet.changed.components.forEach { component -> components[idOf(component)] = component }
        } else {
            packet.removed.forEach { id -> components.remove(id) }
            packet.changed.components.forEach { component -> components[idOf(component)] = component }
        }

        applyData(entity, packet)
        receivedListeners.forEach { it(entity) }
    }

    /** Creates the client store only when there is actually something to put in it. */
    private fun applyData(entity: Entity, packet: EntityStateSyncPacket) {
        if (packet.dataChanged.isEmpty && packet.dataRemoved.isEmpty() && !packet.full) return
        val store =
            if (packet.dataChanged.isEmpty) storeOf(entity) ?: return
            else AttachmentRegistry.entityData(entity)
        store.applySync(packet.dataChanged, packet.dataRemoved, packet.full)
    }

    private fun defer(level: Level, packet: EntityStateSyncPacket) {
        synchronized(deferred) {
            val existing = deferred[packet.entityId]?.takeIf { it.level.get() === level }
            val batches = existing ?: DeferredBatches(WeakReference(level)).also { deferred[packet.entityId] = it }
            batches.enqueue(packet)
            batches.expiresAtTick = TickHandler.clientFrame + PENDING_TIMEOUT_TICKS
        }
    }

    /** Retries the parked batches; called once per client tick rather than once per packet. */
    internal fun drainDeferred(level: Level?) {
        synchronized(deferred) {
            if (deferred.isEmpty()) return
            val now = TickHandler.clientFrame
            val iterator = deferred.entries.iterator()
            while (iterator.hasNext()) {
                val (entityId, batches) = iterator.next()
                if (level == null || batches.level.get() !== level) {
                    iterator.remove()
                    continue
                }

                val entity = level.getEntity(entityId)
                if (entity != null) {
                    batches.drain().forEach { packet -> applyTo(entity, packet) }
                    iterator.remove()
                    continue
                }

                if (now >= batches.expiresAtTick) {
                    HollowEngine.LOGGER.warn(
                        "Dropping {} state batch(es) for entity {}: it never appeared within {} ticks",
                        batches.size,
                        entityId,
                        PENDING_TIMEOUT_TICKS,
                    )
                    iterator.remove()
                }
            }
        }
    }

    /** Batches waiting for their entity, kept in arrival order. */
    internal class DeferredBatches(val level: WeakReference<Level>) {
        private val packets = ArrayDeque<EntityStateSyncPacket>()
        var expiresAtTick: Int = 0

        val size: Int get() = packets.size

        fun enqueue(packet: EntityStateSyncPacket) {
            if (packet.full) packets.clear()
            packets.addLast(packet)
        }

        fun drain(): List<EntityStateSyncPacket> = packets.toList().also { packets.clear() }
    }

    private fun syncableOf(entity: Entity): Map<ResourceLocation, Component> =
        AttachmentRegistry.syncableComponents(entity)

    private fun storeOf(entity: Entity): NbtDataStore? = AttachmentRegistry.entityDataOrNull(entity)

    private fun dataOf(entity: Entity, owner: Boolean): CompoundTag {
        val store = storeOf(entity) ?: return CompoundTag()
        return if (owner) store.syncedSnapshot(Sync.TRACKING, Sync.OWNER) else store.syncedSnapshot(Sync.TRACKING)
    }

    private fun ownerDataOf(entity: Entity): CompoundTag =
        storeOf(entity)?.syncedSnapshot(Sync.OWNER) ?: CompoundTag()

    /** What a delta has to carry to turn [previous] into [current] on the client. */
    internal fun batchOf(
        current: Map<ResourceLocation, Component>,
        previous: Map<ResourceLocation, Component>,
    ): ComponentBatch = ComponentBatch(
        changed = current.filter { (id, component) -> previous[id] != component },
        removed = previous.keys.filterNot { it in current },
    )

    /** The same, over the raw NBT of the synced data keys, where tag equality already is value equality. */
    internal fun dataBatchOf(current: CompoundTag, previous: CompoundTag): DataBatch = DataBatch(
        changed = CompoundTag().apply {
            current.allKeys.forEach { name ->
                val tag = current.get(name) ?: return@forEach
                if (previous.get(name) != tag) put(name, tag)
            }
        },
        removed = previous.allKeys.filterNot { current.contains(it) },
    )

    private fun merged(first: CompoundTag, second: CompoundTag): CompoundTag = CompoundTag().apply {
        first.allKeys.forEach { name -> first.get(name)?.let { put(name, it) } }
        second.allKeys.forEach { name -> second.get(name)?.let { put(name, it) } }
    }

    internal fun shouldApply(version: Long, applied: Long, full: Boolean): Boolean =
        if (full) version >= applied else version > applied

    internal data class ComponentBatch(
        val changed: Map<ResourceLocation, Component>,
        val removed: List<ResourceLocation>,
    ) {
        val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()
    }

    internal data class DataBatch(
        val changed: CompoundTag,
        val removed: List<String>,
    ) {
        val isEmpty: Boolean get() = changed.isEmpty && removed.isEmpty()
    }

    private fun idOf(component: Component): ResourceLocation =
        ComponentDescriptorRegistry.idFor(component::class)
            ?: error("Component descriptor not found for ${component::class.qualifiedName}")

    @SubscribeEvent
    @ClientOnly
    fun onClientTickDrainEntityStateSync(event: TickEvent.Client) {
        drainDeferred(event.minecraft.level)
    }
}
