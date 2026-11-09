package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.editor.EditorMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.TransformGizmoEditor
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.docking.DockLang
import ru.hollowhorizon.hollowengine.client.ui.docking.DockingState
import ru.hollowhorizon.hollowengine.client.ui.docking.isOnScreen
import ru.hollowhorizon.hollowengine.client.ui.docking.toggleOnScreen
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownSlider
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.ClientResources
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.network.ReloadServerResourcesPacket
import ru.hollowhorizon.hollowengine.common.utils.DesktopUtil
import ru.hollowhorizon.hollowengine.common.utils.isProduction
import ru.hollowhorizon.hollowengine.common.utils.openUrl

private const val ReloadIcon = "hollowengine:textures/gui/icons/reload.svg"
private const val ReformatIcon = "hollowengine:textures/gui/icons/code_editor.svg"
private const val SaveIcon = "hollowengine:textures/gui/icons/save.svg"
private const val SaveAllIcon = "hollowengine:textures/gui/icons/save_all.svg"
private const val TelegramIcon = "hollowengine:textures/gui/icons/telegram.svg"
private const val DiscordIcon = "hollowengine:textures/gui/icons/discord.svg"
private const val ModFolderIcon = "hollowengine:textures/gui/icons/actions/reveal.svg"
private const val DocsIcon = "hollowengine:textures/gui/icons/info.svg"
private const val GitHubIcon = "hollowengine:textures/gui/icons/github.svg"
private const val KeyboardIcon = "hollowengine:textures/gui/icons/keyboard.svg"
private const val ImportIcon = "hollowengine:textures/gui/icons/load.svg"
private const val ExportIcon = "hollowengine:textures/gui/icons/export.svg"

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
        UiDropdownItem("$MenuLang.search".lang, mark = UiMenuParts.icon(SearchIcon), tail = UiMenuParts.shortcut(HollowKeys.SEARCH.shortcut), onClick = onSearch),
        UiDropdownItem("$MenuLang.save".lang, mark = UiMenuParts.icon(SaveIcon), enabled = focused != null, tail = UiMenuParts.shortcut(HollowKeys.SAVE.shortcut), separatorBefore = true) {
            focused?.let { file ->
                model.save(file.path)
                dock.updateItem(file.dockItem())
            }
        },
        UiDropdownItem("$MenuLang.save_all".lang, mark = UiMenuParts.icon(SaveAllIcon), enabled = model.files.values.any { it.dirty }) {
            model.saveAll()
            model.files.values.forEach { dock.updateItem(it.dockItem()) }
        },
        UiDropdownItem(
            label = "$MenuLang.reformat".lang,
            mark = UiMenuParts.icon(ReformatIcon),
            enabled = focused != null && canReformat(focused),
            tail = UiMenuParts.shortcut(HollowKeys.FORMAT.shortcut),
        ) {
            focused?.let(onReformat)
        },
        UiDropdownItem("hollowengine.gui.ide.project.settings".lang, mark = UiMenuParts.icon(OptionsIcon), separatorBefore = true) {
            packaging.openSettings()
        },
        UiDropdownItem("hollowengine.gui.ide.project.import".lang, mark = UiMenuParts.icon(ImportIcon)) { packaging.startImport() },
        UiDropdownItem("hollowengine.gui.ide.project.export".lang, mark = UiMenuParts.icon(ExportIcon)) { packaging.openExport() },
        UiDropdownItem(
            "hollowengine.gui.ide.file.reload_client_resources".lang,
            mark = UiMenuParts.icon(ReloadIcon),
            tail = UiMenuParts.shortcut("F3+T"),
            separatorBefore = true,
        ) {
            ClientResources.reload()
        },
        // The server refuses the reload without operator rights anyway.
        UiDropdownItem("hollowengine.gui.ide.file.reload_server_resources".lang, mark = UiMenuParts.icon(ReloadIcon)) {
            ReloadServerResourcesPacket().send()
        }.takeIf { operator },
        UiDropdownItem("hollowengine.gui.ide.file.open_mod_folder".lang, mark = UiMenuParts.icon(ModFolderIcon)) {
            DesktopUtil.openInExplorer(DirectoryManager.HOLLOW_ENGINE.toFile())
        },
    )
}

/** Every tool window, ticked while it is on screen; picking one opens it or brings it to the front. */
internal fun hollowIdeWindowMenuItems(
    model: HollowIdeModel,
    dock: DockingState,
    contributed: List<HollowIdeToolWindow>,
    onResetLayout: () -> Unit,
): List<UiDropdownItem> {
    var separate = false
    val entries = if (contributed.isEmpty()) HollowIdeToolWindows.menu else HollowIdeToolWindows.menu + listOf(null) + contributed
    val windows = entries.mapNotNull { window ->
        if (window == null) {
            separate = true
            return@mapNotNull null
        }
        UiDropdownItem(
            label = window.title,
            mark = UiMenuParts.check(dock.isOnScreen(window.id)),
            separatorBefore = separate,
        ) {
            if (!dock.toggleOnScreen(window.id)) dock.openToolWindow(window, model)
        }.also { separate = false }
    }
    return windows + UiDropdownItem(
        label = DockLang.ShowStripes,
        mark = UiMenuParts.check(dock.stripesVisible),
        separatorBefore = true,
        closeOnClick = false,
    ) {
        dock.stripesVisible = !dock.stripesVisible
    } + UiDropdownItem(
        label = "$MenuLang.reset_layout".lang,
        onClick = onResetLayout,
    )
}

internal fun hollowIdeToolMenuItems(
    model: HollowIdeModel,
    dock: DockingState,
    operator: Boolean,
): List<UiDropdownItem> {
    fun gizmoMode(labelKey: String, mode: GizmoEditMode) = UiDropdownItem(
        label = labelKey.lang,
        mark = UiMenuParts.check(TransformGizmoEditor.isModeShown(mode)),
        closeOnClick = false,
    ) {
        TransformGizmoEditor.toggleMode(mode)
    }

    val editor = if (!operator) emptyList() else listOf(
        UiDropdownItem(
            label = "hollowengine.gui.ide.editor_mode".lang,
            mark = UiMenuParts.check(EditorMode.isEnabled),
            closeOnClick = false,
        ) {
            EditorMode.toggle()
        },
    )

    val gizmo = if (!operator || isProduction) emptyList() else listOf(
        gizmoMode("hollowengine.gui.ide.gizmo.translate", GizmoEditMode.TRANSLATE),
        gizmoMode("hollowengine.gui.ide.gizmo.rotate", GizmoEditMode.ROTATE),
        gizmoMode("hollowengine.gui.ide.gizmo.scale", GizmoEditMode.SCALE),
    )
    return editor + gizmo + listOf(
        UiDropdownItem(
            label = HollowIdeToolWindows.UiProfiler.title,
            mark = UiMenuParts.check(dock.contains(UiProfilerId)),
            closeOnClick = false,
            separatorBefore = true,
        ) {
            if (!dock.close(UiProfilerId)) dock.openToolWindow(HollowIdeToolWindows.UiProfiler, model)
        },
        UiDropdownItem.slider(
            "hollowengine.gui.ide.gui_scale".lang,
            UiDropdownSlider(
                value = HollowIdeScale.guiScale,
                min = 0f,
                max = HollowIdeScale.MaxScale.toFloat(),
                step = 1f,
                valueLabel = { HollowIdeScale.label(it) },
                onCommit = { HollowIdeScale.guiScale = it },
            ),
        ).copy(separatorBefore = true),
    )
}

internal fun hollowIdeHelpMenuItems(onShowKeymap: () -> Unit): List<UiDropdownItem> {
    return listOf(
        UiDropdownItem("hollowengine.gui.ide.docs".lang, mark = UiMenuParts.icon(DocsIcon)) { openUrl(DocsUrl) },
        UiDropdownItem("$MenuLang.shortcuts".lang, mark = UiMenuParts.icon(KeyboardIcon), tail = UiMenuParts.shortcut(HollowKeys.KEYMAP.shortcut), onClick = onShowKeymap),
        UiDropdownItem("GitHub", mark = UiMenuParts.icon(GitHubIcon), separatorBefore = true) { openUrl(RepositoryUrl) },
        UiDropdownItem("Telegram", mark = UiMenuParts.icon(TelegramIcon)) { openUrl("https://t.me/hollowengine") },
        UiDropdownItem("Discord", mark = UiMenuParts.icon(DiscordIcon)) { openUrl("https://discord.gg/qKpPhkwGCY") },
    )
}
