package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.TransformGizmoEditor
import ru.hollowhorizon.hollowengine.client.ui.HollowUiResourceAccess
import ru.hollowhorizon.hollowengine.client.ui.UiProfiler
import ru.hollowhorizon.hollowengine.client.ui.docking.DockItem
import ru.hollowhorizon.hollowengine.client.ui.docking.DockPlacement
import ru.hollowhorizon.hollowengine.client.ui.docking.DockTarget
import ru.hollowhorizon.hollowengine.client.ui.docking.DockingState
import ru.hollowhorizon.hollowengine.client.ui.ide.asset.AssetManagerLang
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
private const val OptionsIcon = "hollowengine:textures/gui/icons/options.svg"

internal fun hollowIdeFileMenuItems(
    model: HollowIdeModel,
    dock: DockingState,
    focusedFile: () -> HollowIdeOpenFile?,
    canReformat: (HollowIdeOpenFile) -> Boolean,
    onReformat: (HollowIdeOpenFile) -> Unit,
): List<UiDropdownItem> {
    val focused = focusedFile()
    val focusedPath = focused?.path
    return listOf(
        UiDropdownItem("hollowengine.gui.ide.file.reload_client_resources".lang, ReloadIcon) {
            ClientResources.reload()
        },
        UiDropdownItem("hollowengine.gui.ide.file.reload_server_resources".lang, ReloadIcon) {
            ReloadServerResourcesPacket().send()
        },
        UiDropdownItem("hollowengine.gui.ide.file.open_mod_folder".lang, LogoIcon) {
            DesktopUtil.openInExplorer(DirectoryManager.HOLLOW_ENGINE.toFile())
        },
        UiDropdownItem("Save", SaveIcon, enabled = focusedPath != null) {
            focusedPath?.let(model::save)
            focusedFile()?.let { dock.updateItem(it.dockItem()) }
        },
        UiDropdownItem("Save All", SaveIcon, enabled = model.files.values.any { it.dirty }) {
            model.saveAll()
            model.files.values.forEach { dock.updateItem(it.dockItem()) }
        },
        UiDropdownItem(
            label = "Reformat Code",
            icon = ReformatIcon,
            enabled = focused != null && canReformat(focused),
        ) {
            focused?.let(onReformat)
        },
    )
}

internal fun hollowIdeWindowMenuItems(model: HollowIdeModel, dock: DockingState): List<UiDropdownItem> {
    return listOf(
        UiDropdownItem("hollowengine.gui.ide.project_tree".lang, ProjectIcon) {
            if (!dock.contains(ProjectTreeId)) {
                dock.open(DockItem(ProjectTreeId, "hollowengine.gui.ide.project_tree".lang, ProjectIcon))
            }
            dock.focus(ProjectTreeId)
        },
        UiDropdownItem(AssetManagerLang.TITLE.lang, AssetManagerIcon) {
            if (!dock.contains(AssetManagerId)) {
                val anchor = ProjectTreeId.takeIf(dock::contains)
                    ?: model.files.values.firstOrNull { dock.contains(it.id) }?.id
                dock.open(
                    DockItem(
                        AssetManagerId,
                        AssetManagerLang.TITLE.lang,
                        AssetManagerIcon,
                        closable = true,
                        minWidth = 520f,
                        minHeight = 260f,
                    ),
                    DockTarget(anchor, DockPlacement.RIGHT),
                )
            }
            dock.focus(AssetManagerId)
        },
        UiDropdownItem("hollowengine.gui.ide.console".lang, ConsoleIcon) {
            if (!dock.contains(ConsoleId)) {
                val anchor = model.files.values.firstOrNull { dock.contains(it.id) }?.id
                    ?: ProjectTreeId.takeIf(dock::contains)
                dock.open(
                    DockItem(
                        ConsoleId,
                        "hollowengine.gui.ide.console".lang,
                        ConsoleIcon,
                        closable = true,
                        minWidth = 360f,
                        minHeight = 180f,
                    ),
                    DockTarget(anchor, DockPlacement.BOTTOM),
                )
            }
            dock.focus(ConsoleId)
        },
        UiDropdownItem("Cutscene Timeline", CutsceneIcon) {
            if (!dock.contains(CutsceneTimelineId)) {
                val anchor = model.files.values.firstOrNull { dock.contains(it.id) }?.id
                    ?: ProjectTreeId.takeIf(dock::contains)
                dock.open(
                    DockItem(
                        CutsceneTimelineId,
                        "Cutscene Timeline",
                        CutsceneIcon,
                        closable = true,
                        minWidth = 520f,
                        minHeight = 260f
                    ),
                    DockTarget(anchor, DockPlacement.BOTTOM),
                )
            }
            dock.focus(CutsceneTimelineId)
        },
        UiDropdownItem("Cutscene Properties", OptionsIcon) {
            if (!dock.contains(CutscenePropertiesId)) {
                val anchor = if (dock.contains(CutsceneTimelineId)) {
                    CutsceneTimelineId
                } else {
                    model.files.values.firstOrNull { dock.contains(it.id) }?.id
                        ?: ProjectTreeId.takeIf(dock::contains)
                }
                dock.open(
                    DockItem(
                        CutscenePropertiesId,
                        "Cutscene Properties",
                        OptionsIcon,
                        closable = true,
                        minWidth = 240f,
                        minHeight = 260f
                    ),
                    DockTarget(anchor, DockPlacement.RIGHT),
                )
            }
            dock.focus(CutscenePropertiesId)
        },
        UiDropdownItem("Cutscene Viewport", CutsceneIcon) {
            if (!dock.contains(CutsceneViewportId)) {
                val anchor = if (dock.contains(CutsceneTimelineId)) {
                    CutsceneTimelineId
                } else {
                    model.files.values.firstOrNull { dock.contains(it.id) }?.id
                        ?: ProjectTreeId.takeIf(dock::contains)
                }
                dock.open(
                    DockItem(CutsceneViewportId, "Cutscene Viewport", CutsceneIcon, minWidth = 320f, minHeight = 180f),
                    DockTarget(anchor, DockPlacement.TOP),
                )
            }
            dock.focus(CutsceneViewportId)
        },
    )
}

internal fun hollowIdeToolMenuItems(dock: DockingState, profiler: UiProfiler): List<UiDropdownItem> {
    return listOf(
        UiDropdownItem(
            label = "UI Profiler",
            checked = dock.contains(UiProfilerId),
            mark = UiDropdownMark.CHECKBOX,
            closeOnClick = false,
        ) {
            if (dock.contains(UiProfilerId)) {
                dock.close(UiProfilerId)
                profiler.enabled = false
            } else {
                val anchor = if (dock.contains(CutsceneTimelineId)) {
                    CutsceneTimelineId
                } else {
                    ProjectTreeId.takeIf(dock::contains)
                }
                dock.open(
                    DockItem(
                        UiProfilerId,
                        "UI Profiler",
                        OptionsIcon,
                        closable = true,
                        minWidth = 360f,
                        minHeight = 260f
                    ),
                    DockTarget(anchor, DockPlacement.BOTTOM),
                )
                dock.focus(UiProfilerId)
                profiler.enabled = true
            }
        },
        UiDropdownItem(
            label = "hollowengine.gui.ide.gizmo".lang,
            checked = TransformGizmoEditor.isEnabled,
            mark = UiDropdownMark.CHECKBOX,
            closeOnClick = false,
        ) {
            TransformGizmoEditor.toggleEnabled()
        },
        UiDropdownItem(
            label = "Translate",
            checked = TransformGizmoEditor.mode == GizmoEditMode.TRANSLATE,
            mark = UiDropdownMark.RADIO,
            closeOnClick = false,
        ) {
            TransformGizmoEditor.setMode(GizmoEditMode.TRANSLATE)
        },
        UiDropdownItem(
            label = "Rotate",
            checked = TransformGizmoEditor.mode == GizmoEditMode.ROTATE,
            mark = UiDropdownMark.RADIO,
            closeOnClick = false,
        ) {
            TransformGizmoEditor.setMode(GizmoEditMode.ROTATE)
        },
        UiDropdownItem(
            label = "Scale",
            checked = TransformGizmoEditor.mode == GizmoEditMode.SCALE,
            mark = UiDropdownMark.RADIO,
            closeOnClick = false,
        ) {
            TransformGizmoEditor.setMode(GizmoEditMode.SCALE)
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
        ),
    )
}

internal fun hollowIdeHelpMenuItems(): List<UiDropdownItem> {
    return listOf(
        UiDropdownItem("Telegram", DocsIcon) { openUrl("https://t.me/hollowengine") },
        UiDropdownItem("Discord", DocsIcon) { openUrl("https://discord.gg/qKpPhkwGCY") },
    )
}
