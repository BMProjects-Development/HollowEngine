package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.ui.inspector.withField

internal val LocalEntityEditorSession: ProvidableCompositionLocal<EntityEditorSession?> = compositionLocalOf { null }

class ComponentEditorScope internal constructor(
    private val entry: ComponentEntry,
    private val document: JsonObject,
    private val apply: (JsonObject) -> Unit,
) {
    val component: Any get() = entry.value
    val id: ResourceLocation get() = entry.id
    val json: JsonObject get() = document

    fun set(field: String, value: JsonElement) = apply(document.withField(field, value))

    fun replace(document: JsonObject) = apply(document)
}

typealias ComponentEditorContent = @Composable (ComponentEditorScope) -> Unit

internal class ComponentEditorRegistration(
    val content: ComponentEditorContent,
    val replacesFields: Boolean,
    val before: Boolean,
)

/**
 * Extra UI for a specific component.
 */
object ComponentEditors {
    private val registry = LinkedHashMap<ResourceLocation, ComponentEditorRegistration>()

    /**
     * @param before draws the extra UI above the generated fields instead of below them.
     * @param replacesFields hides the generated fields entirely.
     */
    fun register(
        id: ResourceLocation,
        before: Boolean = false,
        replacesFields: Boolean = false,
        content: ComponentEditorContent,
    ) {
        registry[id] = ComponentEditorRegistration(content, replacesFields, before)
    }

    fun unregister(id: ResourceLocation) {
        registry.remove(id)
    }

    internal fun of(id: ResourceLocation): ComponentEditorRegistration? = registry[id]
}
