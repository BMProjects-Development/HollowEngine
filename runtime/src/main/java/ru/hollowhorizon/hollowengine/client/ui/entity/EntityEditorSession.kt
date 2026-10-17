package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.client.ui.UiEntityView
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentJson
import ru.hollowhorizon.hollowengine.client.ui.inspector.EditorAssetSources
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorAssetRequest
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorHost
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
import ru.hollowhorizon.hollowengine.common.ui.net.CloseUiPacket

internal enum class EntityEditorTab { COMPONENTS, SCRIPTS }

/** One editable component: its identity, its value, and the serializer the fields are built from. */
internal data class ComponentEntry(
    val id: ResourceLocation,
    val value: Component,
    val serializer: KSerializer<Component>,
    val virtual: Boolean,
) {
    val json: JsonObject? get() = ComponentJson.encode(value)
}

/**
 * The client's copy of one entity's editable state.
 */
@Stable
internal class EntityEditorSession(initial: EntityEditorSnapshot) : InspectorHost {
    var snapshot by mutableStateOf(initial)
        private set

    var tab by mutableStateOf(EntityEditorTab.COMPONENTS)
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    var view by mutableStateOf(UiEntityView.Portrait)
    var autoRotate by mutableStateOf(false)
    var pendingPicker by mutableStateOf<InspectorAssetRequest?>(null)
    var slotSessionId by mutableStateOf<Int?>(null)
        private set

    /**
     * How many answers the editor is still waiting for.
     */
    var pending by mutableStateOf(0)
        private set

    val isBusy: Boolean get() = pending > 0

    val entityId: Int get() = snapshot.entityId
    val entity: Entity? get() = Minecraft.getInstance().level?.getEntity(entityId)

    /** Virtual components first: they are the entity itself. */
    val entries: List<ComponentEntry>
        get() = snapshot.virtual.mapNotNull { entryOf(it, virtual = true) } + snapshot.stored.mapNotNull {
            entryOf(
                it, virtual = false
            )
        }

    /** Component types that are not on the entity yet. */
    val addable: List<ComponentDescriptor<*>>
        get() {
            val present = snapshot.stored.mapNotNull(ComponentJson::idOf).toSet()
            return ComponentDescriptorRegistry.map { it.value }.filter { it.id !in present }
                .sortedBy { it.id.toString() }
        }

    val attachedScripts: List<ScriptEditorInfo> get() = snapshot.attachedScripts

    val availableScripts: List<String>
        get() {
            val attached = snapshot.attachedScripts.mapTo(mutableSetOf(), ScriptEditorInfo::path)
            return snapshot.availableScripts.filter { it !in attached }
        }

    fun accept(next: EntityEditorSnapshot) {
        if (next.entityId != entityId) return
        snapshot = next
        pending = 0
    }

    fun acceptSlots(session: Int) {
        slotSessionId = session
        pending = (pending - 1).coerceAtLeast(0)
    }

    fun closeSlots() {
        slotSessionId?.let { CloseUiPacket(it).send() }
        slotSessionId = null
    }

    fun openSlots() {
        if (slotSessionId != null) return
        pending++
        RequestEntitySlotsPacket(entityId).send()
    }

    fun requestRefresh() {
        pending++
        RequestEntityEditorPacket(entityId).send()
    }

    fun update(entry: ComponentEntry, value: Component) {
        snapshot = if (entry.virtual) {
            snapshot.copy(virtual = snapshot.virtual.replacing(entry.id, value))
        } else {
            snapshot.copy(stored = snapshot.stored.replacing(entry.id, value))
        }
        SetEntityComponentsPacket(entityId, listOf(value)).send()
    }

    fun add(descriptor: ComponentDescriptor<*>) {
        @Suppress("UNCHECKED_CAST") val serializer = descriptor.serializer as KSerializer<Component>
        val value = ComponentJson.defaultOf(serializer) ?: return
        snapshot = snapshot.copy(stored = snapshot.stored + value)
        SetEntityComponentsPacket(entityId, listOf(value)).send()
    }

    fun remove(entry: ComponentEntry) {
        if (entry.virtual) return
        snapshot = snapshot.copy(stored = snapshot.stored.filterNot { ComponentJson.idOf(it) == entry.id })
        RemoveEntityComponentPacket(entityId, entry.id).send()
    }

    fun attachScript(path: String) {
        pending++
        EntityNodeScriptPacket(entityId, path, attach = true).send()
    }

    fun detachScript(path: String) {
        pending++
        EntityNodeScriptPacket(entityId, path, attach = false).send()
    }

    fun updateScript(script: ScriptEditorInfo, values: JsonObject) {
        val text = values.toString()
        snapshot = snapshot.copy(
            attachedScripts = snapshot.attachedScripts.map {
                if (it.path == script.path) it.copy(values = text) else it
            },
        )
        SetEntityScriptValuesPacket(entityId, script.path, text).send()
    }

    override val canPickAssets: Boolean get() = true

    override fun pickAsset(request: InspectorAssetRequest) {
        pendingPicker = request
    }

    override fun assets(extensions: List<String>): List<String> = extensions.flatMap { extension ->
        snapshot.scriptFiles.filter { it.endsWith(extension) } + EditorAssetSources.list(extension)
    }.distinct().sorted()

    val hasSlots: Boolean get() = snapshot.hasSlots

    private fun entryOf(component: Component, virtual: Boolean): ComponentEntry? {
        val id = ComponentJson.idOf(component) ?: return null
        val serializer = ComponentJson.serializerOf(component) ?: return null
        return ComponentEntry(id, component, serializer, virtual)
    }

    private fun List<Component>.replacing(id: ResourceLocation, value: Component): List<Component> =
        map { if (ComponentJson.idOf(it) == id) value else it }
}
