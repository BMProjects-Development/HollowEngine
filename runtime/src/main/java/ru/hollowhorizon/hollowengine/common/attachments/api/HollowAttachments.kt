package ru.hollowhorizon.hollowengine.common.attachments.api

import kotlinx.coroutines.CoroutineScope
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.coroutines.EntityScope
import androidx.compose.runtime.mutableStateMapOf
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.common.data.NbtDataStore
import ru.hollowhorizon.hollowengine.common.data.Sync
import ru.hollowhorizon.hollowengine.common.entities.EntityBodies
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.attachments.sync.EntityStateSync
import ru.hollowhorizon.hollowengine.common.attachments.tracking.MCEntity
import ru.hollowhorizon.hollowengine.common.scripting.nodes.EntityNodeManager

/**
 * Everything attached to one entity, under one owner.
 *
 * The split between them is deliberate and stays:
 * - [components] are *data* other systems read - the renderer, the AI runtime, the client. They are the
 *   only part that goes over the network, and only when the descriptor says so.
 * - [data] is the script-owned storage, addressed by entity rather than by script on purpose, so one script
 *   can read what another left on an NPC.
 * - [nodes] are script-behavior, server-side only.
 * - [runtime] is whatever should live exactly as long as the entity and never be saved or sent.
 */
class HollowAttachments internal constructor(entity: MCEntity) {
    var entity: MCEntity = entity
        internal set

    val components = ComponentStore()
    val scope: CoroutineScope = EntityScope(entity)

    private var dataStore: NbtDataStore? = null
    private var nodeManager: EntityNodeManager? = null
    private var runtimeState: RuntimeAttachments? = null

    /**
     * Nodes that this entity was saved with, held it until entity actually joins a level.
     */
    private var pendingNodes: CompoundTag? = null

    /** The script storage, or null when nothing has ever been written to it. Creates nothing. */
    val dataOrNull: NbtDataStore? get() = dataStore

    val data: NbtDataStore get() = dataStore ?: createDataStore().also { dataStore = it }

    /**
     * State that lives exactly as long as this entity: drawn models, their animators, anything else
     * worth keeping while it exists. Never saved, never synced, gone when the entity is removed.
     */
    val runtime: RuntimeAttachments get() = runtimeState ?: RuntimeAttachments().also { runtimeState = it }

    /** The node scripts attached to this entity, or null when none were ever attached. */
    val nodesOrNull: EntityNodeManager? get() = nodeManager

    val nodes: EntityNodeManager
        get() = nodeManager ?: EntityNodeManager(entity).also { nodeManager = it }

    /** What [nodes] would serialize to, without starting them. */
    internal val nodesNbt: CompoundTag? get() = nodeManager?.serialize() ?: pendingNodes

    /** Whether entity is still waiting to have its saved nodes attached. */
    internal val hasPendingNodes: Boolean get() = pendingNodes != null

    /** Remembers what [activateNodes] should attach once this entity joins a level. */
    internal fun holdNodes(tag: CompoundTag) {
        pendingNodes = tag
    }

    /** Attaches the held nodes. Called when the entity joins a level. */
    internal fun activateNodes() {
        val held = pendingNodes ?: return
        pendingNodes = null
        nodes.deserialize(held)
    }

    /**
     * Rises with every batch [EntityStateSync] sends for this entity on the server, and records the last
     * batch applied on the client.
     */
    var syncVersion: Long = 0L

    /** What the clients tracking this entity were last told, so a batch can carry only the difference. */
    var lastSyncedComponents: Map<ResourceLocation, Component> = emptyMap()

    /** The [Sync.TRACKING] data every tracking client was last told, for the same reason. */
    var lastSyncedData: CompoundTag = CompoundTag()

    /** The [Sync.OWNER] data the entity itself was last told, sent to nobody else. */
    var lastSyncedOwnerData: CompoundTag = CompoundTag()

    init {
        components.onChange = {
            EntityStateSync.markDirty(entity)
            EntityBodies.onComponentsChanged(entity)
            (entity as? WorldObjectEntity)?.onComponentsChanged()
        }
    }

    /**
     * The client copy holds each key in Compose state, so a screen that reads `entity.data[Key]`
     * recomposes when the value the server sent for it changes.
     */
    private fun createDataStore(): NbtDataStore =
        NbtDataStore(if (entity.level().isClientSide) mutableStateMapOf() else LinkedHashMap())
            .also { store -> store.onChange = { EntityStateSync.markDirty(entity) } }

    internal fun adoptData(store: NbtDataStore?) {
        dataStore = store?.also { it.onChange = { EntityStateSync.markDirty(entity) } }
    }

    /**
     * Rebinds this attachment set to a new instance of the same entity. The scope is not carried over:
     * the old one is canceled by the caller, which hands [nodes] over as the state the new instance
     * re-attaches them from once it joins a level.
     */
    internal fun rebindTo(newEntity: MCEntity, nodes: CompoundTag?): HollowAttachments =
        HollowAttachments(newEntity).also { target ->
            target.pendingNodes = nodes
            target.components.putAll(components.copyOf())
            target.adoptData(dataStore)
            target.syncVersion = syncVersion
            target.lastSyncedComponents = lastSyncedComponents
            target.lastSyncedData = lastSyncedData
            target.lastSyncedOwnerData = lastSyncedOwnerData
        }
}
