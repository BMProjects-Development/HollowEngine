package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.client.ui.docking.DockPlacement
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeLanguageService
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeFilePreview
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonExtensions
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonRegistration
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * What addons contribute to the in-game IDE. The IDE's own windows, menus and file types are not
 * registered here; contributions are shown next to them and disappear when their addon unloads.
 */
object HollowIdeExtensionPoints {
    val FILE_TYPES = ExtensionPoints.create("hollowengine:ide/file-types".rl, HollowIdeFileType::class)
    val PANELS = ExtensionPoints.create("hollowengine:ide/panels".rl, HollowIdePanel::class)
    val MENU_ITEMS = ExtensionPoints.create("hollowengine:ide/menu-items".rl, HollowIdeMenuItem::class)
    val FILE_ACTIONS = ExtensionPoints.create("hollowengine:ide/file-actions".rl, HollowIdeFileActionProvider::class)
    val PROJECT_ACTIONS = ExtensionPoints.create("hollowengine:ide/project-actions".rl, HollowIdeProjectActionProvider::class)
    val LANGUAGES = ExtensionPoints.create("hollowengine:ide/languages".rl, HollowIdeLanguageService::class)
    val SCRIPT_TEMPLATES = ExtensionPoints.create("hollowengine:ide/script-templates".rl, HollowIdeScriptTemplate::class)
    val PREVIEWS = ExtensionPoints.create("hollowengine:ide/previews".rl, HollowIdeFilePreview::class)
}

/** Operations a contributed panel or menu action may request from the IDE. */
interface HollowIdeContext {
    val focusedFile: HollowIdeOpenFile?

    fun openFile(path: String): Boolean

    /** Opens a window by its id: a built-in one or a contributed panel's qualified id (`addon:panel`). */
    fun openPanel(id: String): Boolean

    fun closePanel(id: String): Boolean

    fun isPanelOpen(id: String): Boolean

    fun saveAll(): Int

    fun refreshProject()

    fun setStatus(message: String)
}

/**
 * A contributed dock panel. It docks like the IDE's own tool windows: [placement] beside the first
 * of [anchors] that is on screen. [title] may be literal text or a translation key.
 */
class HollowIdePanel(
    val id: String,
    val title: String,
    val icon: String = OptionsIcon,
    val placement: DockPlacement = DockPlacement.RIGHT,
    val anchors: List<HollowIdePanelAnchor> = listOf(HollowIdePanelAnchor.EDITORS, HollowIdePanelAnchor.PROJECT),
    val minWidth: Float = 160f,
    val minHeight: Float = 120f,
    val closable: Boolean = true,
    val showInWindowMenu: Boolean = true,
    val content: @Composable (HollowIdeContext) -> Unit,
) {
    init {
        require(id.isNotBlank()) { "IDE panel ID cannot be blank" }
        require(title.isNotBlank()) { "IDE panel title cannot be blank" }
        require(minWidth > 0f && minHeight > 0f) { "IDE panel minimum size must be positive" }
    }
}

/** What a window docks next to when it is opened. */
enum class HollowIdePanelAnchor {
    EDITORS,
    PROJECT,
    TIMELINE,
}

enum class HollowIdeMenu {
    FILE,
    WINDOW,
    TOOLS,
    HELP,
}

/** A contributed entry of a toolbar menu. [label] may be literal text or a translation key. */
class HollowIdeMenuItem(
    val id: String,
    val menu: HollowIdeMenu,
    val label: String,
    val icon: String? = null,
    val checkbox: Boolean = false,
    val closeOnClick: Boolean = true,
    val isVisible: (HollowIdeContext) -> Boolean = { true },
    val isEnabled: (HollowIdeContext) -> Boolean = { true },
    val isChecked: (HollowIdeContext) -> Boolean = { false },
    val run: (HollowIdeContext) -> Unit,
) {
    init {
        require(id.isNotBlank()) { "IDE menu item ID cannot be blank" }
        require(label.isNotBlank()) { "IDE menu item label cannot be blank" }
    }
}

fun interface HollowIdeFileActionProvider {
    fun actions(context: HollowIdeFileActionContext): List<HollowIdeFileAction>
}

/** An entry a contributor adds to the right-click menu of the project tree. */
class HollowIdeProjectAction(
    val id: String,
    val label: String,
    val icon: String? = null,
    val run: (HollowIdeProjectActionContext) -> Unit,
) {
    init {
        require(id.isNotBlank()) { "IDE project action ID cannot be blank" }
        require(label.isNotBlank()) { "IDE project action label cannot be blank" }
    }
}

interface HollowIdeProjectActionContext {
    val ide: HollowIdeContext
    val path: String
    val isDirectory: Boolean
}

fun interface HollowIdeProjectActionProvider {
    fun actions(context: HollowIdeProjectActionContext): List<HollowIdeProjectAction>
}

fun HollowAddonExtensions.registerIdeFileType(type: HollowIdeFileType): HollowAddonRegistration =
    register(HollowIdeExtensionPoints.FILE_TYPES, type.id, type, type.priority)

fun HollowAddonExtensions.registerIdePanel(
    panel: HollowIdePanel,
    priority: Int = 0,
): HollowAddonRegistration = register(HollowIdeExtensionPoints.PANELS, panel.id, panel, priority)

fun HollowAddonExtensions.registerIdeMenuItem(
    item: HollowIdeMenuItem,
    priority: Int = 0,
): HollowAddonRegistration = register(HollowIdeExtensionPoints.MENU_ITEMS, item.id, item, priority)

fun HollowAddonExtensions.registerIdeFileActions(
    id: String,
    provider: HollowIdeFileActionProvider,
    priority: Int = 0,
): HollowAddonRegistration = register(HollowIdeExtensionPoints.FILE_ACTIONS, id, provider, priority)

fun HollowAddonExtensions.registerIdeProjectActions(
    id: String,
    provider: HollowIdeProjectActionProvider,
    priority: Int = 0,
): HollowAddonRegistration = register(HollowIdeExtensionPoints.PROJECT_ACTIONS, id, provider, priority)

/**
 * Registers a language for the editor. The matching contribution with the highest priority wins over
 * the built-in languages until this registration is closed.
 */
fun HollowAddonExtensions.registerIdeLanguage(
    language: HollowIdeLanguageService,
    priority: Int = 0,
): HollowAddonRegistration = register(HollowIdeExtensionPoints.LANGUAGES, language.id, language, priority)

/** Adds a view of the files [preview] matches, with its own button next to the text in the tab bar. */
fun HollowAddonExtensions.registerIdePreview(
    preview: HollowIdeFilePreview,
    priority: Int = 0,
): HollowAddonRegistration = register(HollowIdeExtensionPoints.PREVIEWS, preview.id, preview, priority)

/** Adds a kind of script to the "New script" menu of the project tree. */
fun HollowAddonExtensions.registerIdeScriptTemplate(
    template: HollowIdeScriptTemplate,
    priority: Int = 0,
): HollowAddonRegistration = register(HollowIdeExtensionPoints.SCRIPT_TEMPLATES, template.id, template, priority)
