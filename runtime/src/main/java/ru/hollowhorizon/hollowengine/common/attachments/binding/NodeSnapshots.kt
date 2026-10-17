package ru.hollowhorizon.hollowengine.common.attachments.binding

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.Snapshot
import java.util.*

val ROOT_COMPONENT_ID: UUID = UUID(0L, 1L)

data class NodeMaterializedRecord(
    val snapshotId: UUID,
    val snapshot: Snapshot,
    val hostEntity: Entity?,
) {
    val hostEntityUuid: UUID? get() = hostEntity?.uuid
    val isEntityBound: Boolean get() = hostEntity != null
}

data class SnapshotComponentView(
    val id: UUID = ROOT_COMPONENT_ID,
    val components: List<Component>,
)

data class ModelNodeEntry(
    val nodeId: UUID,
    val model: Model,
    val transform: TransformComponent,
    val animations: AnimationsComponent?,
    val materials: MaterialsComponent?,
)

fun Snapshot.snapshotIdOrNull(): UUID? = when (this) {
    is EntitySnapshot -> entity?.uuid
}

fun Snapshot.hostEntityUuidOrNull(): UUID? = (this as? EntitySnapshot)?.entity?.uuid

fun Snapshot.nodeByIdOrNull(nodeId: UUID): SnapshotComponentView? =
    if (nodeId == ROOT_COMPONENT_ID) SnapshotComponentView(components = components) else null

fun Snapshot.transformOrNull(): TransformComponent? =
    components.filterIsInstance<TransformComponent>().firstOrNull()

fun Snapshot.modelOrNull(): Model? =
    components.filterIsInstance<Model>().firstOrNull()

fun Snapshot.animationsOrNull(): AnimationsComponent? =
    components.filterIsInstance<AnimationsComponent>().firstOrNull()

fun Snapshot.materialsOrNull(): MaterialsComponent? =
    components.filterIsInstance<MaterialsComponent>().firstOrNull()

fun Snapshot.modelNodes(): List<ModelNodeEntry> {
    val model = components.filterIsInstance<Model>().firstOrNull() ?: return emptyList()
    val transform = components.filterIsInstance<TransformComponent>().firstOrNull() ?: TransformComponent()
    val animations = components.filterIsInstance<AnimationsComponent>().firstOrNull()
    val materials = components.filterIsInstance<MaterialsComponent>().firstOrNull()
    return listOf(ModelNodeEntry(ROOT_COMPONENT_ID, model, transform, animations, materials))
}

fun <T : Snapshot> T.withOrReplace(component: Component, nodeId: UUID? = null): T {
    if (nodeId != null && nodeId != ROOT_COMPONENT_ID) return this
    val id = ComponentDescriptorRegistry.idFor(component::class)
        ?: error("Component descriptor not found for ${component::class.qualifiedName}")
    val merged = LinkedHashMap<ResourceLocation, Component>()
    components.forEach { existing ->
        val existingId = ComponentDescriptorRegistry.idFor(existing::class)
            ?: error("Component descriptor not found for ${existing::class.qualifiedName}")
        merged[existingId] = existing
    }
    merged[id] = component
    return withComponents(merged.values.toList())
}

fun <T : Snapshot> T.removeComponents(predicate: (Component) -> Boolean, nodeId: UUID? = null): T {
    if (nodeId != null && nodeId != ROOT_COMPONENT_ID) return this
    return withComponents(components.filterNot(predicate))
}

@Suppress("UNCHECKED_CAST")
fun <T : Snapshot> T.withComponents(updatedComponents: List<Component>): T =
    when (this) {
        is EntitySnapshot -> copy(components = updatedComponents).also { it.entity = entity }
    } as T

