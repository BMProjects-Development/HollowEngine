package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoint
import ru.hollowhorizon.hollowengine.client.ui.docking.DockingState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonExtension
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonExtensionChange

/**
 * Shows what addons contribute through [HollowIdeExtensionPoints] next to the IDE's own windows,
 * menus and file types. Addons register from their loading thread, so every change is applied on
 * the client thread, where the dock and the open files live.
 */
internal class HollowIdeContributions(
    private val fileTypes: HollowIdeFileTypeRegistry,
    private val model: HollowIdeModel,
    private val dock: DockingState,
    private val context: HollowIdeContext,
    private val onLanguagesChanged: () -> Unit,
    private val onPreviewsChanged: () -> Unit,
) {
    /** Read by compositions that list contributions, so they are rebuilt when an addon comes or goes. */
    var revision by mutableIntStateOf(0)
        private set

    fun start() {
        HollowIdeExtensionPoints.FILE_TYPES.observeOnClient { change ->
            when (change) {
                is HollowAddonExtensionChange.Added -> installFileType(change.extension)
                is HollowAddonExtensionChange.Removed -> {
                    model.closeFilesUsing(change.extension.value)
                    fileTypes.unregister(change.extension.qualifiedId)
                }
            }
        }
        HollowIdeExtensionPoints.PANELS.observeOnClient { change ->
            if (change is HollowAddonExtensionChange.Removed) dock.close(change.extension.qualifiedId)
        }
        HollowIdeExtensionPoints.MENU_ITEMS.observeOnClient {}
        HollowIdeExtensionPoints.PROJECT_ACTIONS.observeOnClient {}
        HollowIdeExtensionPoints.LANGUAGES.observeOnClient { onLanguagesChanged() }
        HollowIdeExtensionPoints.PREVIEWS.observeOnClient { onPreviewsChanged() }
        HollowIdeExtensionPoints.FILE_TYPES.registrations.forEach(::installFileType)
    }

    /** A contributed panel as a tool window, found by its qualified id. */
    fun toolWindow(id: String): HollowIdeToolWindow? =
        HollowIdeExtensionPoints.PANELS.registrations.firstOrNull { it.qualifiedId == id }?.toToolWindow()

    fun windowMenu(): List<HollowIdeToolWindow> {
        revision
        return HollowIdeExtensionPoints.PANELS.registrations
            .filter { it.value.showInWindowMenu }
            .map { it.toToolWindow() }
    }

    /** Whether [id] is the dock id of a contributed panel. */
    operator fun contains(id: String): Boolean = HollowIdeExtensionPoints.PANELS.registrations.any { it.qualifiedId == id }

    @Composable
    fun PanelContent(id: String) {
        revision
        val panel = HollowIdeExtensionPoints.PANELS.registrations.firstOrNull { it.qualifiedId == id } ?: return
        panel.value.content(context)
    }

    fun menuItems(menu: HollowIdeMenu): List<UiDropdownItem> {
        revision
        return HollowIdeExtensionPoints.MENU_ITEMS.registrations
            .filter { it.value.menu == menu && it.call(false) { item -> item.isVisible(context) } }
            .mapIndexed { index, registration ->
                val item = registration.value
                UiDropdownItem(
                    label = item.label.lang,
                    icon = item.icon,
                    enabled = registration.call(false) { it.isEnabled(context) },
                    checked = registration.call(false) { it.isChecked(context) },
                    mark = UiDropdownMark.CHECKBOX.takeIf { item.checkbox },
                    closeOnClick = item.closeOnClick,
                    separatorBefore = index == 0,
                ) {
                    registration.call(Unit) { it.run(context) }
                }
            }
    }

    fun projectActions(path: String, isDirectory: Boolean): List<HollowIdeProjectActionEntry> {
        revision
        val actionContext = object : HollowIdeProjectActionContext {
            override val ide: HollowIdeContext = context
            override val path: String = path
            override val isDirectory: Boolean = isDirectory
        }
        return HollowIdeExtensionPoints.PROJECT_ACTIONS.registrations.flatMap { registration ->
            registration.call(emptyList()) { it.actions(actionContext) }.map { action ->
                HollowIdeProjectActionEntry(action.label.lang, action.icon) {
                    registration.call(Unit) { action.run(actionContext) }
                }
            }
        }
    }

    private fun installFileType(extension: HollowAddonExtension<HollowIdeFileType>) {
        if (fileTypes.find(extension.qualifiedId) == null) fileTypes.register(extension.qualifiedId, extension.value)
    }

    private fun <T : Any> ExtensionPoint<T>.observeOnClient(onChange: (HollowAddonExtensionChange<T>) -> Unit) {
        observe { change ->
            Minecraft.getInstance().execute {
                onChange(change)
                revision++
            }
        }
    }

    private fun HollowAddonExtension<HollowIdePanel>.toToolWindow(): HollowIdeToolWindow = HollowIdeToolWindow(
        id = qualifiedId,
        titleKey = value.title,
        icon = value.icon,
        placement = value.placement,
        anchors = value.anchors,
        minWidth = value.minWidth,
        minHeight = value.minHeight,
        closable = value.closable,
    )

    /** Runs contributed code under its addon's classloader; a failing contribution is logged, not fatal. */
    private fun <T : Any, R> HollowAddonExtension<T>.call(fallback: R, block: (T) -> R): R =
        runCatching { invoke(block) }
            .onFailure { HollowEngine.LOGGER.error("IDE contribution '{}' failed", qualifiedId, it) }
            .getOrDefault(fallback)
}

internal class HollowIdeProjectActionEntry(
    val label: String,
    val icon: String?,
    val run: () -> Unit,
)
