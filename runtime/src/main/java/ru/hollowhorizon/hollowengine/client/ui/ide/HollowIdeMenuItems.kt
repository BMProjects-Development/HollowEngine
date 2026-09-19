package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.TransformGizmoEditor
import ru.hollowhorizon.hollowengine.client.ui.docking.DockingState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownSlider
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.ClientResources
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.network.ReloadServerResourcesPacket
import ru.hollowhorizon.hollowengine.common.utils.DesktopUtil
import ru.hollowhorizon.hollowengine.common.utils.openUrl

private const val ReloadIcon = "hollowengine:textures/gui/icons/reload.svg"
private const val ReformatIcon = "hollowengine:textures/gui/icons/code_editor.svg"
private const val SaveIcon = "hollowengine:textures/gui/icons/save.svg"
private const val DocsIcon = "hollowengine:textures/gui/icons/docs.svg"
private const val GitHubIcon = "hollowengine:textures/gui/icons/github.svg"
private const val LinkIcon = "hollowengine:textures/gui/icons/link.svg"
private const val KeyboardIcon = "hollowengine:textures/gui/icons/keyboard.svg"
private const val ImportIcon = "hollowengine:textures/gui/icons/load.svg"
private const val ExportIcon = "hollowengine:textures/gui/icons/file_zip.svg"

internal const val MenuLang = "hollowengine.gui.ide.menu"
private const val DocsUrl = "https://0mods.team/docs/hollowengine"
private const val RepositoryUrl = "https://github.com/HollowHorizon/HollowEngine"

internal fun hollowIdeFileMenuItems(
    model: HollowIdeModel,
    dock: DockingState,
    packaging: HollowIdeProjectPackaging,
    focusedFile: () -> HollowIdeOpenFile?,
    canReformat: (HollowIdeOpenFile) -> Boolean,
    onReformat: (HollowIdeOpenFile) -> Unit,
    onSearch: () -> Unit,
    operator: Boolean,
): List<UiDropdownItem> {
    val focused = focusedFile()
    return listOfNotNull(
        UiDropdownItem("$MenuLang.search".lang, SearchIcon, shortcut = "Ctrl+N", onClick = onSearch),
        UiDropdownItem("$MenuLang.save".lang, SaveIcon, enabled = focused != null, shortcut = "Ctrl+S", separatorBefore = true) {
            focused?.let { file ->
                model.save(file.path)
                dock.updateItem(file.dockItem())
            }
        },
        UiDropdownItem("$MenuLang.save_all".lang, SaveIcon, enabled = model.files.values.any { it.dirty }) {
            model.saveAll()
            model.files.values.forEach { dock.updateItem(it.dockItem()) }
        },
        UiDropdownItem(
            label = "$MenuLang.reformat".lang,
            icon = ReformatIcon,
            enabled = focused != null && canReformat(focused),
            shortcut = "Ctrl+Alt+L",
        ) {
            focused?.let(onReformat)
        },
        UiDropdownItem("hollowengine.gui.ide.project.settings".lang, OptionsIcon, separatorBefore = true) {
            packaging.openSettings()
        },
        UiDropdownItem("hollowengine.gui.ide.project.import".lang, ImportIcon) { packaging.startImport() },
        UiDropdownItem("hollowengine.gui.ide.project.export".lang, ExportIcon) { packaging.openExport() },
        UiDropdownItem(
            "hollowengine.gui.ide.file.reload_client_resources".lang,
            ReloadIcon,
            shortcut = "F3+T",
            separatorBefore = true,
        ) {
            ClientResources.reload()
        },
        // The server refuses the reload without operator rights anyway.
        UiDropdownItem("hollowengine.gui.ide.file.reload_server_resources".lang, ReloadIcon) {
            ReloadServerResourcesPacket().send()
        }.takeIf { operator },
        UiDropdownItem("hollowengine.gui.ide.file.open_mod_folder".lang, LogoIcon) {
            DesktopUtil.openInExplorer(DirectoryManager.HOLLOW_ENGINE.toFile())
        },
    )
}

/** Every tool window, ticked while it is on screen; picking one opens it or brings it to the front. */
internal fun hollowIdeWindowMenuItems(model: HollowIdeModel, dock: DockingState): List<UiDropdownItem> {
    var separate = false
    return HollowIdeToolWindows.menu.mapNotNull { window ->
        if (window == null) {
            separate = true
            return@mapNotNull null
        }
        UiDropdownItem(
            label = window.title,
            checked = dock.contains(window.id),
            mark = UiDropdownMark.CHECKBOX,
            separatorBefore = separate,
        ) {
            dock.openToolWindow(window, model)
        }.also { separate = false }
    }
}

internal fun hollowIdeToolMenuItems(
    model: HollowIdeModel,
    dock: DockingState,
    operator: Boolean,
): List<UiDropdownItem> {
    fun gizmoMode(labelKey: String, mode: GizmoEditMode) = UiDropdownItem(
        label = labelKey.lang,
        checked = TransformGizmoEditor.isModeShown(mode),
        mark = UiDropdownMark.CHECKBOX,
        closeOnClick = false,
    ) {
        TransformGizmoEditor.toggleMode(mode)
    }

    val gizmo = if (!operator) emptyList() else listOf(
        UiDropdownItem(
            label = "hollowengine.gui.ide.gizmo".lang,
            checked = TransformGizmoEditor.isEnabled,
            mark = UiDropdownMark.CHECKBOX,
            closeOnClick = false,
        ) {
            TransformGizmoEditor.toggleEnabled()
        },
        gizmoMode("hollowengine.gui.ide.gizmo.translate", GizmoEditMode.TRANSLATE),
        gizmoMode("hollowengine.gui.ide.gizmo.rotate", GizmoEditMode.ROTATE),
        gizmoMode("hollowengine.gui.ide.gizmo.scale", GizmoEditMode.SCALE),
    )
    return gizmo + listOf(
        UiDropdownItem(
            label = HollowIdeToolWindows.UiProfiler.title,
            checked = dock.contains(UiProfilerId),
            mark = UiDropdownMark.CHECKBOX,
            closeOnClick = false,
            separatorBefore = true,
        ) {
            if (!dock.close(UiProfilerId)) dock.openToolWindow(HollowIdeToolWindows.UiProfiler, model)
        },
        UiDropdownItem(
            label = "hollowengine.gui.ide.gui_scale".lang,
            slider = UiDropdownSlider(
                value = HollowIdeScale.guiScale,
                min = 0f,
                max = HollowIdeScale.MaxScale.toFloat(),
                step = 1f,
                valueLabel = { HollowIdeScale.label(it) },
                onCommit = { HollowIdeScale.guiScale = it },
            ),
            closeOnClick = false,
            separatorBefore = true,
        ),
    )
}

internal fun hollowIdeHelpMenuItems(onShowShortcuts: () -> Unit): List<UiDropdownItem> {
    return listOf(
        UiDropdownItem("hollowengine.gui.ide.docs".lang, DocsIcon) { openUrl(DocsUrl) },
        UiDropdownItem("$MenuLang.shortcuts".lang, KeyboardIcon, onClick = onShowShortcuts),
        UiDropdownItem("GitHub", GitHubIcon, separatorBefore = true) { openUrl(RepositoryUrl) },
        UiDropdownItem("Telegram", LinkIcon) { openUrl("https://t.me/hollowengine") },
        UiDropdownItem("Discord", LinkIcon) { openUrl("https://discord.gg/qKpPhkwGCY") },
    )
}
