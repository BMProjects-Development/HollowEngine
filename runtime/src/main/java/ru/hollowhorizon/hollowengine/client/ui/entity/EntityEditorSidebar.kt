@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextFieldMode
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptEditorInfo
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptEditorJson
import ru.hollowhorizon.hollowengine.common.attachments.editor.toDescriptor

@Composable
internal fun EntitySidebar(session: EntityEditorSession, width: Float?) {
    Column(
        tags = listOf("ee-sidebar"),
        modifier = if (width != null) Modifier.size(width.px, 100.percent)
        else Modifier.size(100.percent, UiLength.Fit),
    ) {
        SidebarHeader(session)
        SidebarTabs(session)
        if (session.searchOpen) SidebarSearch(session)

        Column(
            tags = listOf("ee-sidebar-body"),
            modifier = if (width != null) Modifier.size(100.percent, 0.px).grow(1f).scrollable(horizontal = false)
            else Modifier.size(100.percent, UiLength.Fit),
        ) {
            when (session.tab) {
                EntityEditorTab.COMPONENTS -> ComponentsTab(session)
                EntityEditorTab.SCRIPTS -> ScriptsTab(session)
            }
        }

        if (session.tab == EntityEditorTab.COMPONENTS) AddComponentFooter(session)
    }
}

@Composable
private fun SidebarHeader(session: EntityEditorSession) {
    Row(tags = listOf("ee-header")) {
        Text(EntityEditorLang.title, tags = listOf("ee-header-title"), modifier = Modifier.grow(1f))
        if (session.isBusy) BusySpinner()
        InspectorIconButton(EntityEditorIcons.SEARCH, EntityEditorLang.search) {
            session.searchOpen = !session.searchOpen
            if (!session.searchOpen) session.query = ""
        }
        InspectorIconButton(EntityEditorIcons.RELOAD, EntityEditorLang.refresh) { session.requestRefresh() }
    }
}

@Composable
private fun SidebarTabs(session: EntityEditorSession) {
    Row(tags = listOf("ee-tabs")) {
        Tab(EntityEditorLang.components, session.tab == EntityEditorTab.COMPONENTS) {
            session.tab = EntityEditorTab.COMPONENTS
        }
        Tab(EntityEditorLang.scripts, session.tab == EntityEditorTab.SCRIPTS) {
            session.tab = EntityEditorTab.SCRIPTS
        }
    }
}

@Composable
private fun Tab(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        tags = if (active) listOf("ee-tab", "active") else listOf("ee-tab"),
        modifier = Modifier.grow(1f).input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .onClick { onClick() },
    ) {
        Text(label, tags = listOf("ee-tab-label"))
    }
}

@Composable
private fun SidebarSearch(session: EntityEditorSession) {
    Row(tags = listOf("ee-search")) {
        Image(EntityEditorIcons.SEARCH, tags = listOf("ee-search-icon"))
        TextField(
            value = session.query,
            id = EntityEditorSearchInput,
            mode = UiTextFieldMode.SINGLE_LINE,
            placeholder = EntityEditorLang.searchHint,
            fontSize = 9f,
            onChange = { session.query = it },
            tags = listOf("insp-input", "flat"),
            modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
        )
        if (session.query.isNotEmpty()) {
            InspectorIconButton(EntityEditorIcons.CLOSE, EntityEditorLang.search) { session.query = "" }
        }
    }
}

@Composable
private fun ComponentsTab(session: EntityEditorSession) {
    val query = session.query
    val entries = session.entries.filter { entry -> matchesComponent(entry, query) }

    if (session.hasSlots) {
        InspectorButton(
            label = EntityEditorLang.inventory,
            icon = EntityEditorIcons.COMPONENT,
            modifier = Modifier.size(100.percent, UiLength.Fit),
        ) { session.openSlots() }
    }

    if (entries.isEmpty()) {
        Text(EntityEditorLang.nothingFound, tags = listOf("insp-hint"))
        return
    }

    entries.forEach { entry ->
        key(entry.id.toString()) { ComponentCard(session, entry, query) }
    }
}

private fun matchesComponent(entry: ComponentEntry, query: String): Boolean {
    if (query.isBlank()) return true
    val descriptor = entry.serializer.descriptor
    val name = ComponentLabels.componentName(entry.id, descriptor)
    if (name.contains(query, ignoreCase = true) || entry.id.toString().contains(query, ignoreCase = true)) return true
    return (0 until descriptor.elementsCount).any { index ->
        ComponentLabels.fieldName(entry.id, descriptor, index)
            .contains(query, ignoreCase = true) || descriptor.getElementName(index).contains(query, ignoreCase = true)
    }
}

@Composable
private fun ComponentCard(session: EntityEditorSession, entry: ComponentEntry, query: String) {
    val descriptor = entry.serializer.descriptor
    if (ComponentLabels.isHidden(descriptor)) return

    var expanded by remember(entry.id) { mutableStateOf(false) }
    var draft by remember(entry.id) { mutableStateOf<JsonObject?>(null) }

    val document = draft ?: entry.json ?: return
    val name = ComponentLabels.componentName(entry.id, descriptor)
    val icon = ComponentLabels.componentIcon(descriptor) ?: EntityEditorIcons.COMPONENT
    val nameMatches = query.isBlank() || name.contains(query, ignoreCase = true) || entry.id.toString()
        .contains(query, ignoreCase = true)

    val open = expanded || query.isNotBlank()

    val apply: (JsonObject) -> Unit = { edited ->
        val decoded = ComponentJson.decode(entry.serializer, edited)
        if (decoded == null) {
            draft = edited
        } else {
            draft = null
            session.update(entry, decoded)
        }
    }

    Column(tags = if (entry.virtual) listOf("ee-card", "virtual") else listOf("ee-card")) {
        Row(
            tags = listOf("ee-card-head"),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                .onClick { expanded = !expanded },
        ) {
            Image(icon, tags = listOf("ee-card-icon"))
            Text(name, tags = listOf("ee-card-title"), modifier = Modifier.grow(1f))
            if (entry.virtual) {
                Text(EntityEditorLang.virtual, id = "ee-badge-${entry.id}", tags = listOf("ee-badge"),
                    modifier = Modifier.input(hoverable = true).tooltipOnHover(EntityEditorLang.virtualHint))
            } else {
                InspectorIconButton(
                    EntityEditorIcons.REMOVE,
                    EntityEditorLang.remove,
                    tags = listOf("ee-card-remove")
                ) {
                    session.remove(entry)
                }
            }
            DisclosureArrow(open)
        }

        if (!open) return@Column

        ComponentLabels.componentDescription(descriptor)?.let { Text(it, tags = listOf("insp-hint")) }

        val extras = ComponentEditors.of(entry.id)
        val scope = remember(entry.id, document) { ComponentEditorScope(entry, document, apply) }

        Column(tags = listOf("ee-card-body")) {
            if (extras != null && extras.before) extras.content(scope)
            if (extras == null || !extras.replacesFields) {
                AutoFields(
                    owner = entry.id,
                    descriptor = descriptor,
                    value = document,
                    path = "/${entry.id}",
                    query = if (nameMatches) "" else query,
                    onChange = apply,
                )
            }
            if (extras != null && !extras.before) extras.content(scope)
        }
    }
}

@Composable
private fun AddComponentFooter(session: EntityEditorSession) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }

    Column(tags = listOf("ee-footer")) {
        if (open) {
            val candidates = session.addable.filter { descriptor ->
                filter.isBlank() || descriptor.id.toString()
                    .contains(filter, ignoreCase = true) || ComponentLabels.componentName(
                    descriptor.id, descriptor.serializer.descriptor
                ).contains(filter, ignoreCase = true)
            }

            Column(tags = listOf("ee-add-panel")) {
                TextField(
                    value = filter,
                    id = "ee-add-filter",
                    placeholder = EntityEditorLang.searchHint,
                    fontSize = 9f,
                    onChange = { filter = it },
                    tags = listOf("insp-input"),
                    modifier = Modifier.size(100.percent, 22.px),
                )
                Column(
                    tags = listOf("ee-add-list"),
                    modifier = Modifier.size(100.percent, 160.px).scrollable(horizontal = false),
                ) {
                    candidates.forEach { descriptor ->
                        key(descriptor.id.toString()) {
                            val label = ComponentLabels.componentName(descriptor.id, descriptor.serializer.descriptor)
                            Row(
                                tags = listOf("ee-add-row"),
                                modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                                    .onClick {
                                        session.add(descriptor)
                                        open = false
                                        filter = ""
                                    },
                            ) {
                                Image(
                                    ComponentLabels.componentIcon(descriptor.serializer.descriptor)
                                        ?: EntityEditorIcons.COMPONENT,
                                    tags = listOf("ee-add-icon"),
                                )
                                Column(modifier = Modifier.grow(1f)) {
                                    Text(label, tags = listOf("ee-add-label"))
                                    Text(descriptor.id.toString(), tags = listOf("ee-add-id"))
                                }
                            }
                        }
                    }
                    if (candidates.isEmpty()) Text(EntityEditorLang.allAdded, tags = listOf("insp-hint"))
                }
            }
        }

        InspectorButton(
            label = EntityEditorLang.addComponent,
            icon = EntityEditorIcons.ADD,
            tags = listOf("primary"),
            modifier = Modifier.size(100.percent, 30.px),
        ) { open = !open }
    }
}

@Composable
private fun ScriptsTab(session: EntityEditorSession) {
    val query = session.query
    val attached = session.attachedScripts.filter { matchesScript(it, query) }

    Text(EntityEditorLang.attached, tags = listOf("ee-section-title"))
    when {
        session.attachedScripts.isEmpty() -> Text(EntityEditorLang.noScripts, tags = listOf("insp-hint"))
        attached.isEmpty() -> Text(EntityEditorLang.nothingFound, tags = listOf("insp-hint"))
        else -> attached.forEach { script -> key(script.path) { ScriptCard(session, script, query) } }
    }

    InspectorButton(
        label = EntityEditorLang.attach,
        icon = EntityEditorIcons.ADD,
        tags = listOf("primary"),
        modifier = Modifier.size(100.percent, UiLength.Fit),
    ) {
        session.pendingPicker = InspectorAssetRequest(
            title = EntityEditorLang.attach,
            candidates = session.availableScripts,
            current = "",
            onPick = session::attachScript,
        )
    }

    if (session.availableScripts.isEmpty()) {
        Text(EntityEditorLang.noSuitableScripts, tags = listOf("insp-hint"))
    }
}

@Composable
private fun ScriptCard(session: EntityEditorSession, script: ScriptEditorInfo, query: String) {
    var expanded by remember(script.path) { mutableStateOf(false) }

    val fields = script.fields.filterNot { it.hidden }
    val name = scriptName(script)
    val nameMatches =
        query.isBlank() || name.contains(query, ignoreCase = true) || script.path.contains(query, ignoreCase = true)
    val open = fields.isNotEmpty() && (expanded || !nameMatches)

    Column(tags = listOf("ee-card")) {
        Row(
            tags = listOf("ee-card-head"),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                .onClick { expanded = !expanded },
        ) {
            Image(script.icon.ifBlank { EntityEditorIcons.SCRIPT }, tags = listOf("ee-script-icon"))
            Column(modifier = Modifier.grow(1f)) {
                Text(name, tags = listOf("ee-script-name"))
                Text(script.path, tags = listOf("ee-script-path"))
            }
            InspectorIconButton(EntityEditorIcons.REMOVE, EntityEditorLang.detach, tags = listOf("ee-card-remove")) {
                session.detachScript(script.path)
            }
            if (fields.isNotEmpty()) DisclosureArrow(open)
        }

        if (!open) return@Column

        if (script.description.isNotBlank()) {
            Text(ComponentLabels.translate(script.description), tags = listOf("insp-hint"))
        }

        val descriptor = remember(fields) { fields.toDescriptor("hollowengine.node.${script.path}") }
        val values = remember(script.values) { scriptValues(script.values) }

        Column(tags = listOf("ee-card-body")) {
            AutoFields(
                owner = null,
                descriptor = descriptor,
                value = values,
                path = "/script/${script.path}",
                query = if (nameMatches) "" else query,
                onChange = { edited -> session.updateScript(script, edited) },
            )
        }
    }
}

private fun scriptName(script: ScriptEditorInfo): String =
    if (script.name.isNotBlank()) ComponentLabels.translate(script.name)
    else ComponentLabels.prettify(script.path.substringAfterLast('/').substringBefore(".node.kts"))

private fun scriptValues(text: String): JsonObject =
    runCatching { ScriptEditorJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

private fun matchesScript(script: ScriptEditorInfo, query: String): Boolean {
    if (query.isBlank()) return true
    if (script.path.contains(query, ignoreCase = true)) return true
    if (scriptName(script).contains(query, ignoreCase = true)) return true
    return script.fields.any { field ->
        field.name.contains(query, ignoreCase = true) || ComponentLabels.translate(field.label)
            .contains(query, ignoreCase = true)
    }
}
