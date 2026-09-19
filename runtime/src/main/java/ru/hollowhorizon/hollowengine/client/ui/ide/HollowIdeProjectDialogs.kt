package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Checkbox
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.HollowUiContent
import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.LocalUiViewport
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Popup
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.TextField
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.UiBoxMode
import ru.hollowhorizon.hollowengine.client.ui.UiCursorShape
import ru.hollowhorizon.hollowengine.client.ui.UiLength
import ru.hollowhorizon.hollowengine.client.ui.UiPopupAlignment
import ru.hollowhorizon.hollowengine.client.ui.align
import ru.hollowhorizon.hollowengine.client.ui.alignItems
import ru.hollowhorizon.hollowengine.client.ui.cursor
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.maxSize
import ru.hollowhorizon.hollowengine.client.ui.onClick
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.scrollable
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.textWrap
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.source.DEFAULT_SANDBOX_NAMESPACE

private const val LANG = "hollowengine.gui.ide.project"
private const val SettingsIcon = "hollowengine:textures/gui/icons/options.svg"
private const val ImportIcon = "hollowengine:textures/gui/icons/load.svg"
private const val ExportIcon = "hollowengine:textures/gui/icons/file_zip.svg"

/** What can be done with the project as a whole, at the right end of the project tree's tab bar. */
@Composable
internal fun HollowIdeProjectActions(packaging: HollowIdeProjectPackaging) {
    Row(
        tags = listOf("project-actions"),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER),
    ) {
        ToolbarButton("project-settings-button", SettingsIcon, "$LANG.settings".lang, packaging::openSettings)
        ToolbarButton("project-import-button", ImportIcon, "$LANG.import".lang, packaging::startImport)
        ToolbarButton("project-export-button", ExportIcon, "$LANG.export".lang, packaging::openExport)
    }
}

@Composable
private fun ToolbarButton(id: String, icon: String, tooltip: String, onClick: () -> Unit) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOf("project-action"),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .tooltipOnHover(tooltip)
            .onClick { event ->
                onClick()
                event.consume()
            },
    ) {
        Image(icon, tags = listOf("project-action-icon"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
    }
}

@Composable
internal fun HollowIdeProjectDialogs(packaging: HollowIdeProjectPackaging) {
    packaging.settings?.let { SettingsDialog(packaging, it) }
    packaging.export?.let { ExportDialog(packaging, it) }
    packaging.import?.let { ImportDialog(packaging, it) }
}

@Composable
private fun SettingsDialog(packaging: HollowIdeProjectPackaging, form: ProjectSettingsForm) {
    ProjectDialog("project-settings", "$LANG.settings.title".lang, packaging::cancelSettings) {
        val viewport = LocalUiViewport.current
        Column(
            id = "project-settings-fields",
            tags = listOf("project-dialog-scroll"),
            modifier = Modifier.size(100.percent, UiLength.Fit)
                .maxSize(height = (viewport.height - DialogChromeHeight).coerceAtLeast(MinScrollHeight).px)
                .scrollable(horizontal = false, hasHorizontalScrollbar = false),
        ) {
            Field("$LANG.field.id".lang, form.id, "project-settings-id", "$LANG.field.id.hint".lang) { form.id = it }
            Field("$LANG.field.name".lang, form.name, "project-settings-name", form.id) { form.name = it }
            Field("$LANG.field.version".lang, form.version, "project-settings-version") { form.version = it }
            EnvironmentField(form)
            Field("$LANG.field.description".lang, form.description, "project-settings-description") {
                form.description = it
            }
            Field("$LANG.field.authors".lang, form.authors, "project-settings-authors", "$LANG.field.authors.hint".lang) {
                form.authors = it
            }
            Field("$LANG.field.license".lang, form.license, "project-settings-license", "All Rights Reserved") {
                form.license = it
            }
            Field("$LANG.field.icon".lang, form.icon, "project-settings-icon", "$LANG.field.icon.hint".lang) { form.icon = it }
            if (form.dependencyChoices.isNotEmpty()) {
                Text("$LANG.field.dependencies".lang, tags = listOf("project-dialog-label"))
                CheckboxList("project-settings-dependencies") {
                    form.dependencyChoices.forEach { addon ->
                        CheckboxRow("project-dependency-$addon", addon, addon in form.dependencies) {
                            form.toggleDependency(addon, it)
                        }
                    }
                }
            }
            ModDependenciesField(form)
        }
        Errors(form.errors)
        Actions {
            DialogButton("project-settings-cancel", "$LANG.cancel".lang, onClick = packaging::cancelSettings)
            DialogButton("project-settings-save", "$LANG.save".lang, primary = true, onClick = packaging::saveSettings)
        }
    }
}

@Composable
private fun EnvironmentField(form: ProjectSettingsForm) {
    var expanded by remember { mutableStateOf(false) }
    Column(tags = listOf("project-dialog-field"), modifier = Modifier.size(100.percent, UiLength.Fit)) {
        Text("$LANG.field.environment".lang, tags = listOf("project-dialog-label"))
        UiDropdown(
            id = "project-settings-environment",
            label = form.environment.label(),
            expanded = expanded,
            onExpandedChange = { expanded = it },
            items = ProjectSettingsForm.environments.map { environment ->
                UiDropdownItem(environment.label(), checked = environment == form.environment) {
                    form.environment = environment
                }
            },
            tags = listOf("project-dialog-dropdown"),
        )
    }
}

private fun HollowAddonEnvironment.label(): String = "$LANG.environment.${name.lowercase()}".lang

@Composable
private fun ModDependenciesField(form: ProjectSettingsForm) {
    Column(tags = listOf("project-dialog-field"), modifier = Modifier.size(100.percent, UiLength.Fit)) {
        Text("$LANG.field.mods".lang, tags = listOf("project-dialog-label"))
        Paragraph("$LANG.field.mods.hint".lang, "project-dialog-hint")
        TextField(
            value = form.modFilter,
            onChange = { form.modFilter = it },
            placeholder = "$LANG.field.mods.filter".lang,
            id = "project-settings-mod-filter",
            tags = listOf("project-dialog-input"),
            modifier = Modifier.size(100.percent, 22.px),
        )
        CheckboxList("project-settings-mods") {
            val mods = form.visibleModChoices
            if (mods.isEmpty()) Paragraph("$LANG.field.mods.none".lang, "project-dialog-hint")
            mods.forEach { mod ->
                val label = when {
                    !form.isInstalled(mod) -> "$LANG.field.mods.missing".lang(mod.id)
                    mod.name == mod.id -> mod.id
                    else -> "${mod.name} (${mod.id})"
                }
                CheckboxRow("project-mod-${mod.id}", label, mod.id in form.modDependencies) {
                    form.toggleModDependency(mod.id, it)
                }
            }
        }
    }
}

@Composable
private fun CheckboxList(id: String, content: HollowUiContent) {
    Column(
        id = id,
        tags = listOf("project-dialog-dependencies"),
        modifier = Modifier.size(100.percent, UiLength.Fit)
            .maxSize(height = CheckboxListHeight.px)
            .scrollable(horizontal = false, hasHorizontalScrollbar = false),
        content = content,
    )
}

@Composable
private fun CheckboxRow(id: String, label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        tags = listOf("project-dialog-checkbox-row"),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, id = id)
        Text(label)
    }
}

private const val DialogChromeHeight = 140f
private const val MinScrollHeight = 120f
private const val CheckboxListHeight = 96f

@Composable
private fun ExportDialog(packaging: HollowIdeProjectPackaging, form: ProjectExportForm) {
    ProjectDialog("project-export", "$LANG.export.title".lang, packaging::cancelExport) {
        val result = form.result
        if (result != null) {
            Paragraph("$LANG.export.done_message".lang(result.absolutePath))
            Actions {
                DialogButton("project-export-show", "$LANG.show_in_explorer".lang, onClick = packaging::showExported)
                DialogButton("project-export-close", "$LANG.close".lang, primary = true, onClick = packaging::cancelExport)
            }
        } else {
            val properties = packaging.properties
            val blocked = properties.id == DEFAULT_SANDBOX_NAMESPACE
            Paragraph("${properties.displayName} (${properties.id})")
            Field("$LANG.field.version".lang, form.version, "project-export-version") { form.version = it }
            Row(
                tags = listOf("project-dialog-checkbox-row"),
                modifier = Modifier.alignItems(vertical = UiAlign.CENTER),
            ) {
                Checkbox(
                    checked = form.includeSources,
                    onCheckedChange = { form.includeSources = it },
                    id = "project-export-sources",
                )
                Text("$LANG.export.sources".lang)
            }
            Paragraph("$LANG.export.sources.hint".lang, "project-dialog-hint")
            Text("$LANG.export.output".lang, tags = listOf("project-dialog-label"))
            Row(
                tags = listOf("project-dialog-path-row"),
                modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(vertical = UiAlign.CENTER),
            ) {
                Text(
                    form.output.absolutePath,
                    tags = listOf("project-dialog-path"),
                    modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).textWrap(),
                )
                DialogButton(
                    "project-export-choose",
                    "$LANG.choose".lang,
                    enabled = !form.running,
                    onClick = packaging::chooseExportFile,
                )
            }
            Errors(form.errors)
            if (form.running) Paragraph(form.progress, "project-dialog-progress")
            Actions {
                DialogButton(
                    "project-export-cancel",
                    "$LANG.cancel".lang,
                    enabled = !form.running,
                    onClick = packaging::cancelExport,
                )
                DialogButton(
                    "project-export-run",
                    "$LANG.export.button".lang,
                    primary = true,
                    enabled = !form.running && !blocked,
                    onClick = packaging::runExport,
                )
            }
        }
    }
}

@Composable
private fun ImportDialog(packaging: HollowIdeProjectPackaging, form: ProjectImportForm) {
    ProjectDialog("project-import", "$LANG.import.title".lang, packaging::cancelImport) {
        val plan = form.plan
        if (plan == null) {
            Errors(form.errors)
            Actions {
                DialogButton("project-import-close", "$LANG.close".lang, primary = true, onClick = packaging::cancelImport)
            }
        } else {
            Paragraph("$LANG.import.question".lang(plan.properties.displayName, plan.properties.version, form.file.name))
            Paragraph("$LANG.import.warning".lang, "project-dialog-warning")
            plan.conflictingAddon?.let { addon -> Paragraph("$LANG.import.conflict".lang(addon), "project-dialog-warning") }
            Errors(form.errors)
            Actions {
                DialogButton(
                    "project-import-cancel",
                    "$LANG.cancel".lang,
                    enabled = !form.running,
                    onClick = packaging::cancelImport,
                )
                DialogButton(
                    "project-import-run",
                    "$LANG.import.button".lang,
                    primary = true,
                    enabled = !form.running,
                    onClick = packaging::confirmImport,
                )
            }
        }
    }
}

@Composable
private fun ProjectDialog(id: String, title: String, onDismiss: () -> Unit, content: HollowUiContent) {
    Popup(
        anchorBounds = LocalUiViewport.current,
        alignment = CenteredOnViewport,
        id = "$id-popup",
        tags = listOf("dropdown-popup", "project-dialog"),
        layer = 100,
        modal = true,
        onDismiss = onDismiss,
    ) {
        Column(
            id = id,
            tags = listOf("project-dialog-content"),
            modifier = Modifier.size(340.px, UiLength.Fit),
        ) {
            Text(title, tags = listOf("project-dialog-title"))
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    id: String,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    Column(tags = listOf("project-dialog-field"), modifier = Modifier.size(100.percent, UiLength.Fit)) {
        Text(label, tags = listOf("project-dialog-label"))
        TextField(
            value = value,
            onChange = onChange,
            placeholder = placeholder,
            id = id,
            tags = listOf("project-dialog-input"),
            modifier = Modifier.size(100.percent, 22.px),
        )
    }
}

@Composable
private fun Paragraph(text: String, tag: String = "project-dialog-text") {
    Text(text, tags = listOf(tag), modifier = Modifier.size(100.percent, UiLength.Fit).textWrap())
}

@Composable
private fun Errors(errors: List<String>) {
    errors.forEach { error -> Paragraph(error, "project-dialog-error") }
}

@Composable
private fun Actions(content: HollowUiContent) {
    Row(
        tags = listOf("project-dialog-actions"),
        modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(horizontal = UiAlign.END),
        content = content,
    )
}

@Composable
private fun DialogButton(
    id: String,
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOfNotNull("project-dialog-button", if (primary) "primary" else "secondary", "disabled".takeUnless { enabled }),
        modifier = Modifier.size(UiLength.Fit, 24.px)
            .input(hoverable = true, clickable = true)
            .cursor(if (enabled) UiCursorShape.HAND else UiCursorShape.DEFAULT)
            .onClick { event ->
                if (enabled) onClick()
                event.consume()
            },
    ) {
        Text(label, modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
    }
}
