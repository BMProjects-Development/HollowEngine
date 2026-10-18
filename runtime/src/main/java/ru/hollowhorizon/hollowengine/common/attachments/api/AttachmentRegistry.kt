package ru.hollowhorizon.hollowengine.common.attachments.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.extensions.EntityExtension
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry.DATA_NBT
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry.ROOT_NBT
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry.cacheForTransfer
import ru.hollowhorizon.hollowengine.common.attachments.binding.NodeRuntimeState
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentSyncPolicy
import ru.hollowhorizon.hollowengine.common.attachments.components.NoAi
import ru.hollowhorizon.hollowengine.common.attachments.components.NoAiRuntime
import ru.hollowhorizon.hollowengine.common.attachments.components.ai.AIComponentSystems
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySerialization
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.attachments.sync.EntityStateSync
import ru.hollowhorizon.hollowengine.common.attachments.tracking.MCEntity
import ru.hollowhorizon.hollowengine.common.data.NbtDataStore
import ru.hollowhorizon.hollowengine.common.data.Sync
import java.util.*

private data class LevelEntityState(
    val byUuid: MutableMap<UUID, HollowAttachments> = linkedMapOf(),
)

/** The pieces of a [HollowAttachments] that outlive the entity instance they were attached to. */
private class PendingTransfer(
    val components: Map<ResourceLocation, Component>?,
    val data: NbtDataStore?,
)

private data class SideTransferState(
    val pendingByEntityUuid: MutableMap<UUID, PendingTransfer> = linkedMapOf(),
    val transferringEntityUuids: MutableSet<UUID> = linkedSetOf(),
)

/**
 * The per-level registry of [HollowAttachments], and the entity save/load hooks that persist them.
 */
object AttachmentRegistry {
    /** The single root every kind of attached state is written under. */
    private const val ROOT_NBT = "HollowEngine"
    private const val VERSION_NBT = "version"
    private const val COMPONENTS_NBT = "components"
    private const val DATA_NBT = "data"

    /**
     * The sync policy of every synced data key, kept beside [DATA_NBT] rather than inside it so the
     * document itself stays exactly what the script wrote.
     */
    private const val DATA_SYNC_NBT = "dataSync"
    private const val NODES_NBT = "nodes"

    /** Bump when the layout under [ROOT_NBT] changes. */
    private const val CURRENT_VERSION = 1

    private val levelStates = Collections.synchronizedMap(WeakHashMap<Level, LevelEntityState>())
    private val sideTransferState = Collections.synchronizedMap(linkedMapOf<Boolean, SideTransferState>())

    /**
     * Attachments of an entity that is not in a level. For example, an instance being read from NBT
     * before it is added, and the copies worldgen builds only to write a structure's entities into a
     * chunk and then forgets.
     */
    private var MCEntity.detachedState: HollowAttachments?
        get() = (this as EntityExtension).`hollowengine$detachedAttachments`() as HollowAttachments?
        set(value) = (this as EntityExtension).`hollowengine$setDetachedAttachments`(value)

    private val noAiId by lazy {
        ComponentDescriptorRegistry.idFor(NoAi::class)
            ?: error("Component descriptor not found for ${NoAi::class.qualifiedName}")
    }

    fun initLevel(level: Level) {
        NodeRuntimeState.init(level)
        levelState(level)
    }

    fun tick(level: Level) {
        val states = synchronized(levelStates) {
            levelStates[level]?.byUuid?.values?.toList().orEmpty()
        }
        states.forEach { state ->
            val entity = state.entity
            if (entity.level() != level) return@forEach
            if (state.hasPendingNodes && entity.isInLevel) state.activateNodes()
            val components = state.components.readOnly
            NoAiRuntime.apply(entity, components.containsKey(noAiId))
            AIComponentSystems.tickEntity(entity, components)
        }
    }

    fun close(level: Level) {
        NodeRuntimeState.close(level)
        val lastOfItsSide = synchronized(levelStates) {
            levelStates.remove(level)?.byUuid?.values?.forEach {
                it.scope.cancel()
                it.closeRuntime()
            }
            levelStates.keys.none { it.isClientSide == level.isClientSide }
        }

        if (lastOfItsSide) synchronized(sideTransferState) { sideTransferState.remove(level.isClientSide) }
    }

    /** The attachments of [entity], created on first use. */
    fun attachments(entity: MCEntity): HollowAttachments = state(entity)

    /** The attachments of [entity], or null when it has none. Creates nothing. */
    fun attachmentsOrNull(entity: MCEntity): HollowAttachments? {
        entity.detachedState?.let { return if (entity.isInLevel) promote(entity) else it }
        return stateOrNull(entity.level(), entity.uuid)
    }

    /** Every live attachment set, for engine-wide passes such as an addon being reloaded. */
    fun allAttachments(): List<HollowAttachments> = synchronized(levelStates) {
        levelStates.values.flatMap { it.byUuid.values }
    }

    /**
     * Points entity's attachments and start the node scripts that were waiting for it.
     */
    fun onEntityJoinedLevel(entity: MCEntity) {
        val owner = stateOrNull(entity.level(), entity.uuid)?.entity
        if (owner != null && owner !== entity && owner.isInLevel) return

        promote(entity).activateNodes()
    }

    fun componentsById(entity: MCEntity): MutableMap<ResourceLocation, Any> = state(entity).components.asMutableMap()

    /** The components that are allowed over the network, i.e. the ones whose descriptor is `@Syncable`. */
    fun syncableComponents(entity: Entity): Map<ResourceLocation, Component> {
        val state = existingState(entity) ?: return emptyMap()
        return state.components.readOnly.filterKeys { id ->
            ComponentDescriptorRegistry.descriptorOrNull(id)?.syncPolicy == ComponentSyncPolicy.SYNC
        }
    }

    fun syncVersion(entity: Entity): Long = existingState(entity)?.syncVersion ?: 0L

    fun setSyncVersion(entity: Entity, version: Long) {
        state(entity).syncVersion = version
    }

    fun nextSyncVersion(entity: Entity): Long = state(entity).let { ++it.syncVersion }

    fun lastSyncedComponents(entity: Entity): Map<ResourceLocation, Component> =
        existingState(entity)?.lastSyncedComponents ?: emptyMap()

    fun setLastSyncedComponents(entity: Entity, components: Map<ResourceLocation, Component>) {
        state(entity).lastSyncedComponents = components
    }

    fun lastSyncedData(entity: Entity): CompoundTag =
        existingState(entity)?.lastSyncedData ?: CompoundTag()

    fun setLastSyncedData(entity: Entity, data: CompoundTag) {
        state(entity).lastSyncedData = data
    }

    fun lastSyncedOwnerData(entity: Entity): CompoundTag =
        existingState(entity)?.lastSyncedOwnerData ?: CompoundTag()

    fun setLastSyncedOwnerData(entity: Entity, data: CompoundTag) {
        state(entity).lastSyncedOwnerData = data
    }

    fun coroutineScope(entity: Entity): CoroutineScope = state(entity).scope

    /** The entity's data store, or null when it has never been written to. Creates nothing. */
    fun entityDataOrNull(entity: Entity): NbtDataStore? = resolveState(entity)?.dataOrNull

    /** The entity's data store, creating both it and the entity's attachments on first use. */
    fun entityData(entity: Entity): NbtDataStore = state(entity).data

    fun saveEntity(entity: Entity, tag: CompoundTag) {
        val state = existingState(entity) ?: return

        try {
            val root = CompoundTag()
            root.putInt(VERSION_NBT, CURRENT_VERSION)

            state.components.snapshot(entity)?.let { root.put(COMPONENTS_NBT, EntitySerialization.serializeToNbt(it)) }

            state.dataOrNull?.takeUnless { it.isEmpty() }?.let {
                    root.put(DATA_NBT, it.save())
                    writeSyncPolicies(root, it.syncPolicies())
                }

            state.nodesNbt?.takeUnless { it.isEmpty }?.let { root.put(NODES_NBT, it) }

            if (root.allKeys.any { it != VERSION_NBT }) tag.put(ROOT_NBT, root) else tag.remove(ROOT_NBT)
            LegacyEntityNbt.erase(tag)
        } catch (e: Exception) {
            HollowEngine.LOGGER.warn("Failed to save entity {} ({})", entity.id, entity.uuid, e)
        }
    }

    fun loadEntity(entity: Entity, tag: CompoundTag) {
        val root = tag.takeIf { it.contains(ROOT_NBT, Tag.TAG_COMPOUND.toInt()) }?.getCompound(ROOT_NBT)
        if (root != null) {
            read(
                entity = entity,
                components = root.compoundOrNull(COMPONENTS_NBT),
                data = root.compoundOrNull(DATA_NBT),
                dataSync = root.compoundOrNull(DATA_SYNC_NBT),
                nodes = root.compoundOrNull(NODES_NBT),
            )
            return
        }

        if (!LegacyEntityNbt.isPresent(tag)) return
        read(
            entity = entity,
            components = LegacyEntityNbt.componentsOrNull(tag),
            data = LegacyEntityNbt.dataOrNull(tag),
            dataSync = null,
            nodes = LegacyEntityNbt.nodesOrNull(tag),
        )
    }

    private fun read(
        entity: Entity,
        components: CompoundTag?,
        data: CompoundTag?,
        dataSync: CompoundTag?,
        nodes: CompoundTag?,
    ) {
        if (components == null && data == null && nodes == null) return

        val state = state(entity)
        components?.let { state.components.replaceAll(EntitySerialization.deserializeFromNbt(it).components) }
        data?.let {
            state.data.load(it)
            state.data.loadSyncPolicies(readSyncPolicies(dataSync))
        }
        nodes?.let { state.holdNodes(it) }
    }

    private fun writeSyncPolicies(root: CompoundTag, policies: Map<String, Sync>) {
        if (policies.isEmpty()) return
        root.put(DATA_SYNC_NBT, CompoundTag().apply {
            policies.forEach { (name, sync) -> putString(name, sync.name) }
        })
    }

    private fun readSyncPolicies(saved: CompoundTag?): Map<String, Sync> {
        if (saved == null) return emptyMap()
        return saved.allKeys.mapNotNull { name ->
            val sync = runCatching { Sync.valueOf(saved.getString(name)) }.getOrNull() ?: return@mapNotNull null
            if (sync == Sync.NEVER) null else name to sync
        }.toMap()
    }

    fun onSetLevel(entity: Entity, newLevel: Level) {
        relocateStateToLevel(entity, newLevel)
    }

    fun onRemove(entity: Entity) {
        val level = entity.level()
        val state = levelState(level).byUuid[entity.uuid] ?: return
        if (state.entity !== entity) return

        val transfer = sideState(level).transferringEntityUuids.remove(entity.uuid)
        if (transfer || entity is Player) cacheForTransfer(level, entity.uuid, state)

        levelState(level).byUuid.remove(entity.uuid)
        state.scope.cancel()
        state.closeRuntime()
        EntityStateSync.forget(entity)

        NoAiRuntime.cleanup(entity)
        AIComponentSystems.cleanup(entity)
    }

    fun cloneOwnedState(old: Entity, new: Entity, dropLooseOnDeath: Boolean) {
        val source = existingState(old)
        val cached = if (source == null) takePendingTransfer(old.level(), old.uuid) else null
        if (source == null && cached == null) return

        val components = source?.components?.copyOf() ?: cached?.components.orEmpty()
        val data = source?.dataOrNull ?: cached?.data

        val target = state(new)
        target.components.clear()
        target.components.putAll(if (dropLooseOnDeath) components.withoutLooseOnDeath() else components)
        data?.takeUnless { it.isEmpty() }?.let(target::adoptData)
    }

    fun entitySnapshot(level: Level, entityUuid: UUID): EntitySnapshot? {
        val state = stateOrNull(level, entityUuid) ?: return null
        return state.components.snapshot(state.entity)
    }

    fun entitySnapshots(level: Level): List<Pair<Entity, EntitySnapshot>> = synchronized(levelStates) {
        levelStates[level]?.byUuid?.values?.mapNotNull { state ->
            val entity = state.entity
            val snapshot = state.components.snapshot(entity) ?: return@mapNotNull null
            entity to snapshot
        }.orEmpty()
    }

    fun updateEntitySnapshot(entity: Entity, snapshot: EntitySnapshot) {
        state(entity).components.replaceAll(snapshot.components)
    }

    fun removeEntitySnapshot(level: Level, entityUuid: UUID): Entity? {
        val pending = sideState(level).pendingByEntityUuid
        pending[entityUuid]?.let { cached ->
            if (cached.data == null) pending.remove(entityUuid)
            else pending[entityUuid] = PendingTransfer(null, cached.data)
        }
        val state = stateOrNull(level, entityUuid) ?: return null
        state.components.clear()
        return state.entity
    }

    fun onDimensionChanged(old: Entity, new: Entity, from: Level, to: Level) {
        sideState(from).transferringEntityUuids += old.uuid
        stateOrNull(from, old.uuid)?.let { state ->
            cacheForTransfer(from, old.uuid, state)
            levelState(from).byUuid.remove(old.uuid)
            state.scope.cancel()
            state.closeRuntime()
        }
        restoreFromTransfer(to, new.uuid, state(new))
    }

    fun onPlayerDimensionChanged(player: Player, from: Level, to: Level) {
        stateOrNull(from, player.uuid)?.let { cacheForTransfer(from, player.uuid, it) }
        restoreFromTransfer(to, player.uuid, state(player))
    }

    private fun cacheForTransfer(level: Level, uuid: UUID, state: HollowAttachments) {
        val components = state.components.copyOf().takeUnless { it.isEmpty() }
        val data = state.dataOrNull?.takeUnless { it.isEmpty() }
        if (components == null && data == null) sideState(level).pendingByEntityUuid.remove(uuid)
        else sideState(level).pendingByEntityUuid[uuid] = PendingTransfer(components, data)
    }

    /** Takes what [cacheForTransfer] left for [uuid], so only one instance can claim it. */
    private fun takePendingTransfer(level: Level, uuid: UUID): PendingTransfer? =
        synchronized(sideTransferState) { sideState(level).pendingByEntityUuid.remove(uuid) }

    private fun restoreFromTransfer(level: Level, uuid: UUID, target: HollowAttachments) {
        takePendingTransfer(level, uuid)?.let { cached ->
            cached.components?.let {
                target.components.clear()
                target.components.putAll(it)
            }
            cached.data?.let(target::adoptData)
        }
        sideState(level).transferringEntityUuids.remove(uuid)
    }

    private fun relocateStateToLevel(entity: Entity, newLevel: Level) {
        val target = levelState(newLevel).byUuid
        synchronized(levelStates) {
            levelStates.entries.forEach { (level, state) ->
                if (level === newLevel || level.isClientSide != newLevel.isClientSide) return@forEach
                val moved = state.byUuid.remove(entity.uuid) ?: return@forEach
                target[entity.uuid] = if (moved.entity !== entity) rebind(moved, entity) else moved
            }
        }
    }

    private fun stateOrNull(level: Level, uuid: UUID): HollowAttachments? =
        synchronized(levelStates) { levelStates[level]?.byUuid?.get(uuid) }

    /**
     * What [entity] is reading and writing right now, creating nothing.
     *
     * Addressing attachments by uuid answers for whichever instance the level holds, which is not this
     * one while it is still being built. Everything that takes an entity has to resolve them the same
     * way its writes do, or a caller ends up reading an empty set and putting it back.
     */
    private fun existingState(entity: MCEntity): HollowAttachments? =
        entity.detachedState ?: stateOrNull(entity.level(), entity.uuid)

    /**
     * The entity's attachments, including pendingByEntityUuid by cloned/died player.
     */
    private fun resolveState(entity: MCEntity): HollowAttachments? {
        existingState(entity)?.let { return it }
        val pending = synchronized(sideTransferState) {
            sideState(entity.level()).pendingByEntityUuid.containsKey(entity.uuid)
        }
        return if (pending) state(entity) else null
    }

    private fun state(entity: MCEntity): HollowAttachments {
        stateOrNull(entity.level(), entity.uuid)?.let { if (it.entity === entity) return it }
        if (entity.isInLevel) return promote(entity)
        return entity.detachedState ?: createState(entity).also { entity.detachedState = it }
    }

    /**
     * Applies attachments to [entity], which the level holds.
     */
    private fun promote(entity: MCEntity): HollowAttachments = synchronized(levelStates) {
        val map = levelState(entity.level()).byUuid
        val previous = map[entity.uuid]
        if (previous?.entity === entity) return previous

        val loaded = entity.detachedState?.also { entity.detachedState = null }
        val state = when {
            loaded != null -> loaded.also {
                previous?.scope?.cancel()
                previous?.closeRuntime()
            }
            previous != null -> rebind(previous, entity)
            else -> createState(entity)
        }
        map[entity.uuid] = state
        state
    }

    private val MCEntity.isInLevel: Boolean get() = level().getEntity(id) === this

    private fun createState(entity: MCEntity): HollowAttachments =
        HollowAttachments(entity).also { restoreFromTransfer(entity.level(), entity.uuid, it) }

    private fun rebind(source: HollowAttachments, entity: MCEntity): HollowAttachments {
        val nodes = source.nodesNbt
        source.scope.cancel()
        source.closeRuntime()
        return source.rebindTo(entity, nodes)
    }

    private fun levelState(level: Level): LevelEntityState = synchronized(levelStates) {
        levelStates.computeIfAbsent(level) { LevelEntityState() }
    }

    private fun sideState(level: Level): SideTransferState = synchronized(sideTransferState) {
        sideTransferState.computeIfAbsent(level.isClientSide) { SideTransferState() }
    }

    private fun CompoundTag.compoundOrNull(key: String): CompoundTag? =
        takeIf { it.contains(key, Tag.TAG_COMPOUND.toInt()) }?.getCompound(key)
}

fun Level.findEntityByUuid(uuid: UUID): Entity? = when (this) {
    is ServerLevel -> getEntity(uuid)
    is ClientLevel -> entitiesForRendering().firstOrNull { it.uuid == uuid }
    else -> null
}
