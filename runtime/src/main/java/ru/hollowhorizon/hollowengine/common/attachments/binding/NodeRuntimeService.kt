package ru.hollowhorizon.hollowengine.common.attachments.binding

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.api.findEntityByUuid
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.Snapshot
import ru.hollowhorizon.hollowengine.common.network.sendTrackingEntityAndSelf
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import java.util.*

/**
 * The snapshots materialized in one level. Every snapshot belongs to an entity: nodes that lived in
 * the world on their own were removed together with the lighting they were mostly used for.
 */
class NodeRuntimeService(
    private val level: Level,
) {
    val records: Collection<NodeMaterializedRecord>
        get() = AttachmentRegistry.entitySnapshots(level).map { (entity, snapshot) ->
            NodeMaterializedRecord(entity.uuid, snapshot, entity)
        }

    fun forEachModelNodeRecord(action: (NodeMaterializedRecord, ModelNodeEntry) -> Unit) {
        records.forEach { record ->
            record.snapshot.modelNodes().forEach { node -> action(record, node) }
        }
    }

    /**
     * The model nodes of a single entity. The renderer draws one entity at a time, and walking every
     * record for each of them made that pass quadratic in the number of models in the level.
     */
    fun forEachModelNodeOf(entity: Entity, action: (NodeMaterializedRecord, ModelNodeEntry) -> Unit) {
        val snapshot = AttachmentRegistry.entitySnapshot(level, entity.uuid) ?: return
        val record = NodeMaterializedRecord(entity.uuid, snapshot, entity)
        snapshot.modelNodes().forEach { node -> action(record, node) }
    }

    fun snapshot(id: UUID): Snapshot? {
        val snapshot = AttachmentRegistry.entitySnapshot(level, id) ?: return null
        val entity = level.findEntityByUuid(id)
        return if (entity == null) snapshot else snapshot.withEntity(entity)
    }

    fun materialize(snapshot: Snapshot) {
        when (snapshot) {
            is EntitySnapshot -> snapshot.entity?.let { AttachmentRegistry.updateEntitySnapshot(it, snapshot) }
        }
    }

    fun remove(id: UUID, syncToClients: Boolean = false): Boolean {
        val removed = AttachmentRegistry.removeEntitySnapshot(level, id) ?: return false
        if (level is ServerLevel && syncToClients) {
            EntitySnapshotRemovePacket(removed.id).sendTrackingEntityAndSelf(removed)
        }
        return true
    }

    fun updateTransform(id: UUID, pose: TrsTransformF, nodeId: UUID? = null): Boolean {
        if (nodeId != null && nodeId != ROOT_COMPONENT_ID) return false
        val entity = level.findEntityByUuid(id) ?: return false
        val attachments = AttachmentRegistry.attachmentsOrNull(entity) ?: return false
        if (attachments.snapshot()?.modelOrNull() == null) return false
        attachments.setModelPose(pose)
        return true
    }

    /**
     * Writing the components is all a caller has to do. [EntityStateSync] batches the change out to the
     * clients tracking the entity at the end of the tick.
     */
    fun updateSnapshot(id: UUID, snapshot: Snapshot): Boolean {
        when (snapshot) {
            is EntitySnapshot -> {
                val entity = snapshot.entity ?: level.findEntityByUuid(id)
                if (entity == null) {
                    HollowEngine.LOGGER.warn(
                        "Cannot update entity snapshot {}: entity not found in level {}",
                        id,
                        level.dimension().location(),
                    )
                    return false
                }
                AttachmentRegistry.updateEntitySnapshot(entity, snapshot.withEntity(entity))
            }
        }
        return true
    }

    fun removeEntityNodesFromPlayer(player: ServerPlayer, hostEntityUuid: UUID) {
        val host = level.findEntityByUuid(hostEntityUuid) ?: return
        EntitySnapshotRemovePacket(host.id).send(player)
    }
}

object NodeRuntimeState {
    private val services = Collections.synchronizedMap(WeakHashMap<Level, NodeRuntimeService>())

    fun init(level: Level) {
        services.computeIfAbsent(level, ::NodeRuntimeService)
    }

    fun service(level: Level): NodeRuntimeService =
        services[level] ?: error("Node runtime state is not initialized for $level")

    fun close(level: Level) {
        services.remove(level)
    }
}

typealias NodeSceneService = NodeRuntimeService
