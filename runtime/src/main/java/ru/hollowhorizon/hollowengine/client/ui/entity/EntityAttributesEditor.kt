package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentJson
import ru.hollowhorizon.hollowengine.client.ui.inspector.FloatRow
import ru.hollowhorizon.hollowengine.client.ui.inspector.Hint
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.NumberInput
import ru.hollowhorizon.hollowengine.client.ui.inspector.TextRow
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.attachments.editor.EntityAttributes

@Composable
internal fun EntityAttributesEditor(scope: ComponentEditorScope) {
    val values = scope.component as? EntityAttributes ?: return
    var query by remember(scope.id) { mutableStateOf("") }
    val names = remember(values.attributes.keys) { values.attributes.keys.toList() }
    val completions = remember(names) {
        UiCompletionContributor {
            names.map { id -> UiTextCompletion(label = id, detail = attributeLabel(id), wordChars = ":/._-") }
        }
    }

    fun replace(next: EntityAttributes) {
        ComponentJson.encode(next)?.let(scope::replace)
    }

    FloatRow(EntityEditorLang.statHealth, values.health, id = "ee-attribute-health", min = 0f) {
        replace(values.copy(health = it))
    }
    TextRow(EntityEditorLang.search, query, id = "ee-attribute-search", completions = completions) { query = it }
    Hint("hollowengine.gui.entity_editor.attribute_hint".lang)

    val matches = values.attributes.filterKeys { id ->
        id.contains(query.trim(), ignoreCase = true) || attributeLabel(id).contains(query.trim(), ignoreCase = true)
    }
    if (matches.isEmpty()) Hint(EntityEditorLang.nothingFound)
    matches.forEach { (id, value) ->
        key(id) {
            Column(tags = listOf("insp-field")) {
                Text(attributeLabel(id), tags = listOf("insp-label"))
                Text(id, tags = listOf("insp-hint"))
                Row(id = "ee-attribute-$id", tags = listOf("insp-input-row")) {
                    NumberInput(path = "/ee-attribute/$id", value = value, whole = false) { next ->
                        replace(values.copy(attributes = values.attributes + (id to next)))
                    }
                    values.defaults[id]?.let { default ->
                        InspectorIconButton(
                            id = "ee-attribute-reset-$id",
                            icon = EntityEditorIcons.RELOAD,
                            tooltip = "hollowengine.gui.entity_editor.reset_attribute".lang.format(default),
                        ) {
                            replace(values.copy(attributes = values.attributes + (id to default)))
                        }
                    }
                }
            }
        }
    }
}

private fun attributeLabel(id: String): String {
    val location = ResourceLocation.tryParse(id) ?: return id
    return BuiltInRegistries.ATTRIBUTE.getHolder(location).orElse(null)?.value()?.descriptionId?.lang ?: id
}
