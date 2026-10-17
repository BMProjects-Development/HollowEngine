package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.ui.docking.DockItem
import ru.hollowhorizon.hollowengine.client.ui.docking.DockPlacement
import ru.hollowhorizon.hollowengine.client.ui.docking.DockTarget
import ru.hollowhorizon.hollowengine.client.ui.docking.DockingState
import ru.hollowhorizon.hollowengine.client.ui.ide.asset.AssetManagerLang
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * A window the IDE can open from the Windows menu. Its placement is declared once here instead of
 * being repeated at every place that opens it.
 */
internal class HollowIdeToolWindow(
    val id: String,
    val titleKey: String,
    val icon: String,
    val placement: DockPlacement,
    val anchors: List<HollowIdePanelAnchor>,
    val minWidth: Float = 48f,
    val minHeight: Float = 32f,
    val closable: Boolean = true,
    val titleInToolbar: Boolean = false,
) {
    val title: String get() = titleKey.lang

    fun dockItem(): DockItem = DockItem(
        id = id,
        title = title,
        icon = icon,
        closable = closable,
        minWidth = minWidth,
        minHeight = minHeight,
        pinnable = true,
        titleInToolbar = titleInToolbar,
    )
}

internal object HollowIdeToolWindows {
    val Project = HollowIdeToolWindow(
        id = ProjectTreeId,
        titleKey = "hollowengine.gui.ide.project_tree",
        icon = ProjectIcon,
        placement = DockPlacement.LEFT,
        anchors = listOf(HollowIdePanelAnchor.EDITORS),
    )
    val AssetManager = HollowIdeToolWindow(
        id = AssetManagerId,
        titleKey = AssetManagerLang.TITLE,
        icon = AssetManagerIcon,
        placement = DockPlacement.RIGHT,
        anchors = listOf(HollowIdePanelAnchor.PROJECT, HollowIdePanelAnchor.EDITORS),
        minWidth = 260f,
        minHeight = 130f,
    )
    val Console = HollowIdeToolWindow(
        id = ConsoleId,
        titleKey = "hollowengine.gui.ide.console",
        icon = ConsoleIcon,
        placement = DockPlacement.BOTTOM,
        anchors = listOf(HollowIdePanelAnchor.EDITORS, HollowIdePanelAnchor.PROJECT),
        minWidth = 180f,
        minHeight = 90f,
        titleInToolbar = true,
    )
    val Timeline = HollowIdeToolWindow(
        id = TimelineId,
        titleKey = "hollowengine.gui.ide.windows.timeline",
        icon = CutsceneIcon,
        placement = DockPlacement.BOTTOM,
        anchors = listOf(HollowIdePanelAnchor.EDITORS, HollowIdePanelAnchor.PROJECT),
        minWidth = 260f,
        minHeight = 130f,
        titleInToolbar = true,
    )
    val Scene = HollowIdeToolWindow(
        id = SceneId,
        titleKey = "hollowengine.gui.ide.windows.scene",
        icon = "hollowengine:textures/gui/icons/layers.svg",
        placement = DockPlacement.LEFT,
        anchors = listOf(HollowIdePanelAnchor.PROJECT, HollowIdePanelAnchor.EDITORS),
        minWidth = 160f,
        minHeight = 120f,
    )
    val Inspector = HollowIdeToolWindow(
        id = InspectorId,
        titleKey = "hollowengine.gui.ide.windows.inspector",
        icon = OptionsIcon,
        placement = DockPlacement.RIGHT,
        anchors = listOf(HollowIdePanelAnchor.EDITORS, HollowIdePanelAnchor.TIMELINE, HollowIdePanelAnchor.PROJECT),
        minWidth = 120f,
        minHeight = 130f,
    )
    val GameViewport = HollowIdeToolWindow(
        id = GameViewportId,
        titleKey = "hollowengine.gui.ide.windows.game_viewport",
        icon = CutsceneIcon,
        placement = DockPlacement.TOP,
        anchors = listOf(HollowIdePanelAnchor.TIMELINE, HollowIdePanelAnchor.EDITORS, HollowIdePanelAnchor.PROJECT),
        minWidth = 160f,
        minHeight = 90f,
    )
    val History = HollowIdeToolWindow(
        id = HistoryId,
        titleKey = "hollowengine.gui.history.title",
        icon = "hollowengine:textures/gui/icons/history.svg",
        placement = DockPlacement.RIGHT,
        anchors = listOf(HollowIdePanelAnchor.EDITORS, HollowIdePanelAnchor.PROJECT),
        minWidth = 160f,
        minHeight = 120f,
        titleInToolbar = true,
    )
    val UiProfiler = HollowIdeToolWindow(
        id = UiProfilerId,
        titleKey = "hollowengine.gui.ide.tools.ui_profiler",
        icon = OptionsIcon,
        placement = DockPlacement.BOTTOM,
        anchors = listOf(HollowIdePanelAnchor.TIMELINE, HollowIdePanelAnchor.PROJECT),
        minWidth = 180f,
        minHeight = 130f,
    )

    val menu: List<HollowIdeToolWindow?> = listOf(
        Project, Scene, Inspector, AssetManager, Console, History,
        null,
        Timeline, GameViewport,
    )

    /** Every window the editor knows how to open, for turning a remembered id back into one. */
    val all: List<HollowIdeToolWindow> = listOf(
        Project, Scene, AssetManager, Console, Timeline, Inspector, GameViewport, History, UiProfiler,
    )

    fun byId(id: String): HollowIdeToolWindow? = all.firstOrNull { it.id == id }
}

/** Opens [window] beside its preferred neighbour, or just focuses it if it is already open. */
internal fun DockingState.openToolWindow(window: HollowIdeToolWindow, model: HollowIdeModel) {
    if (contains(window.id)) {
        focus(window.id)
        return
    }
    val anchor = window.anchors.firstNotNullOfOrNull { anchor ->
        when (anchor) {
            HollowIdePanelAnchor.EDITORS -> model.files.values.firstOrNull { contains(it.id) }?.id
            HollowIdePanelAnchor.PROJECT -> ProjectTreeId.takeIf(::contains)
            HollowIdePanelAnchor.TIMELINE -> TimelineId.takeIf(::contains)
        }
    }
    open(window.dockItem(), DockTarget(anchor, window.placement))
}
