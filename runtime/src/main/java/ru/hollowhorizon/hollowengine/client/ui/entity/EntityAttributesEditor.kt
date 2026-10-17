package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentJson
import ru.hollowhorizon.hollowengine.client.ui.inspector.Hint
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.NumberField
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.attachments.editor.EntityAttributes

@Composable
internal fun EntityAttributesEditor(scope: ComponentEditorScope) {
    val values = scope.component as? EntityAttributes ?: return
    val catalog = remember { entityAttributeCatalog() }
    var query by remember(scope.id) { mutableStateOf("") }
    var adding by remember(scope.id) { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    val available = catalog.values.filter { it.id !in values.attributes }
    val completions = remember(values.attributes.keys) {
        UiCompletionContributor {
            values.attributes.keys.map { id ->
                val name = catalog[id]?.translatedName
                UiTextCompletion(
                    label = name ?: id,
                    insertText = id,
                    detail = if (name == null) "" else id,
                    filterText = "$id ${name.orEmpty()}",
                    wordChars = ":/._-",
                )
            }
        }
    }

    fun replace(next: EntityAttributes) {
        ComponentJson.encode(next)?.let(scope::replace)
    }

    Column(tags = listOf("ee-attributes")) {
        AttributeValueRow("health", EntityEditorLang.statHealth, null, values.health.toDouble(), {
            replace(values.copy(health = it.toFloat().coerceAtLeast(0f)))
        })
        Row(id = "ee-attribute-tools", tags = listOf("ee-attribute-tools")) {
            TextField(
                value = query,
                placeholder = EntityEditorLang.search,
                id = "ee-attribute-search",
                completionContributor = completions,
                fontSize = 9f,
                onChange = { query = it },
                tags = listOf("insp-input", "ee-attribute-search"),
            )
            Image(
                "hollowengine:textures/gui/icons/info.svg",
                id = "ee-attribute-help",
                tags = listOf("insp-help"),
                modifier = Modifier.input(hoverable = true)
                    .tooltipOnHover("hollowengine.gui.entity_editor.attribute_hint".lang),
            )
            InspectorIconButton(
                id = "ee-attribute-add",
                icon = EntityEditorIcons.ADD,
                tooltip = if (available.isEmpty()) "hollowengine.gui.entity_editor.attribute_all_added".lang
                    else "hollowengine.gui.entity_editor.attribute_add".lang,
                modifier = Modifier.onPlaced { anchor = it },
                tags = if (available.isEmpty()) listOf("disabled") else emptyList(),
            ) { if (available.isNotEmpty()) adding = true }
        }

        val matches = values.attributes.filterKeys { id ->
            catalog[id]?.matches(query) ?: id.contains(query.trim(), ignoreCase = true)
        }
        if (matches.isEmpty()) Hint(EntityEditorLang.nothingFound)
        matches.forEach { (id, value) ->
            key(id) {
                val name = catalog[id]?.translatedName
                AttributeValueRow(id, name ?: id, id.takeIf { name != null }, value, { next ->
                    replace(values.copy(attributes = values.attributes + (id to next)))
                }) {
                    values.defaults[id]?.let { default ->
                        InspectorIconButton(
                            id = "ee-attribute-reset-$id",
                            icon = EntityEditorIcons.RELOAD,
                            tooltip = "hollowengine.gui.entity_editor.reset_attribute".lang.format(default),
                        ) { replace(values.copy(attributes = values.attributes + (id to default))) }
                    }
                    if (id in values.removable) {
                        InspectorIconButton(
                            id = "ee-attribute-remove-$id",
                            icon = EntityEditorIcons.REMOVE,
                            tooltip = EntityEditorLang.remove,
                            tags = listOf("danger"),
                        ) {
                            replace(values.copy(
                                attributes = values.attributes - id,
                                defaults = values.defaults - id,
                                removable = values.removable - id,
                            ))
                        }
                    }
                }
            }
        }
    }
    if (adding) AttributePicker(anchor, available, onClose = { adding = false }) { option ->
        replace(values.copy(
            attributes = values.attributes + (option.id to option.defaultValue),
            defaults = values.defaults + (option.id to option.defaultValue),
            removable = values.removable + option.id,
        ))
        query = ""
        adding = false
    }
}

@Composable
private fun AttributeValueRow(
    id: String,
    label: String,
    codeId: String?,
    value: Double,
    onChange: (Double) -> Unit,
    actions: HollowUiContent? = null,
) {
    Row(id = "ee-attribute-$id", tags = listOf("ee-attribute-row")) {
        Text(
            label,
            id = "ee-attribute-label-$id",
            tags = listOf("ee-attribute-label"),
            modifier = codeId?.let { Modifier.input(hoverable = true).tooltipOnHover(it) },
        )
        Box(tags = listOf("ee-attribute-value")) {
            NumberField(value = value, id = "insp-number/ee-attribute/$id", modifier = Modifier.grow(1f), onChange = onChange)
        }
        Row(tags = listOf("ee-attribute-actions")) { actions?.invoke() }
    }
}
