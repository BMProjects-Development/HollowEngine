package ru.hollowhorizon.hollowengine.client.ui.inspector

import androidx.compose.runtime.Composable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorWidget
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorWidgets
import ru.hollowhorizon.hollowengine.common.utils.rl

/** A field handed to a registered editor: what it is called, its type, its value as JSON, and where edits go. */
class InspectorWidgetField(
    val label: String?,
    val description: String?,
    val descriptor: SerialDescriptor,
    val value: JsonElement,
    val path: String,
    val onChange: (JsonElement) -> Unit,
)

/** An editor for a field that asks for it by [EditorWidget], drawn in place of the one its type would get. */
interface InspectorWidget {
    @Composable
    fun Content(field: InspectorWidgetField)
}

/** The field editors fields can ask for by id; addons add their own. */
object InspectorWidgets {
    val point = ExtensionPoints.create<InspectorWidget>("hollowengine:inspector/widgets".rl)

    init {
        register(EditorWidgets.COLLIDERS, object : InspectorWidget {
            @Composable
            override fun Content(field: InspectorWidgetField) =
                CollidersField(field.label, field.description, field.value, field.onChange)
        })
    }

    fun register(id: String, widget: InspectorWidget): ExtensionHandle = point.register(id.rl, widget)

    /** The editor registered under [id], or null, and the field gets the editor of its type. */
    fun find(id: String): InspectorWidget? = point.find(id.rl)
}
