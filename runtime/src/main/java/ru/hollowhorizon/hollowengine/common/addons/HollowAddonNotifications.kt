package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.notification.HollowNotifications
import ru.hollowhorizon.hollowengine.client.utils.langOrDefault

/** Tells whoever sits at the game what happened to the addons; the log and `/he addons list` have it all. */
internal interface HollowAddonNotifier {
    fun loaded(addons: List<HollowAddonDescriptor>)

    /** [addons] were running and have been started again, from the same file or a new one. */
    fun reloaded(addons: List<HollowAddonDescriptor>)

    fun unloaded(addons: List<HollowAddonDescriptor>)

    /** The addon of [status] is meant to run and does not. */
    fun notRunning(status: HollowAddonStatus)

    fun restartRequired(descriptor: HollowAddonDescriptor)
}

/** Notifications in the corner of a client; a dedicated server has only its log. */
internal object HollowAddonNotifications : HollowAddonNotifier {
    private const val LANG = "hollowengine.gui.notification"

    override fun loaded(addons: List<HollowAddonDescriptor>) = onClient { Client.lifecycle("addon_loaded", "addons_loaded", addons) }

    override fun reloaded(addons: List<HollowAddonDescriptor>) =
        onClient { Client.lifecycle("addon_reloaded", "addons_reloaded", addons) }

    override fun unloaded(addons: List<HollowAddonDescriptor>) = onClient { Client.unloaded(addons) }

    override fun notRunning(status: HollowAddonStatus) = onClient { Client.notRunning(status) }

    override fun restartRequired(descriptor: HollowAddonDescriptor) {
        HollowEngine.LOGGER.warn("Addon '{}' will be enabled after restarting the game.", descriptor.name)
        onClient { Client.restartRequired(descriptor.name) }
    }

    private inline fun onClient(show: () -> Unit) {
        if (!HollowAddonRuntimeEnvironment.isClient) return
        runCatching(show).onFailure { HollowEngine.LOGGER.warn("Could not show an addon notification", it) }
    }

    /** Kept apart, so that a dedicated server never loads the classes of the client's interface. */
    private object Client {
        fun lifecycle(single: String, several: String, addons: List<HollowAddonDescriptor>) {
            HollowNotifications.success(
                if (addons.size == 1) text(single, DEFAULTS.getValue(single), addons.single().name, addons.single().version)
                else text(several, DEFAULTS.getValue(several), addons.joinToString { it.name })
            )
        }

        fun unloaded(addons: List<HollowAddonDescriptor>) {
            HollowNotifications.info(
                if (addons.size == 1) text("addon_unloaded", DEFAULTS.getValue("addon_unloaded"), addons.single().name)
                else text("addons_unloaded", DEFAULTS.getValue("addons_unloaded"), addons.joinToString { it.name })
            )
        }

        fun notRunning(status: HollowAddonStatus) {
            val key = "addon_" + status.state.name.lowercase()
            val title = text(key, DEFAULTS[key] ?: DEFAULTS.getValue("addon_failed"), status.descriptor.name, status.descriptor.version)
            HollowNotifications.error(title + status.details?.let { ": $it" }.orEmpty())
        }

        fun restartRequired(name: String) {
            HollowNotifications.warning(text("addon_restart", DEFAULTS.getValue("addon_restart"), name))
        }

        /** The translations may not be loaded yet while the game starts, which is when addons fail most. */
        private fun text(key: String, default: String, vararg args: Any): String = "$LANG.$key".langOrDefault(default, *args)

        private val DEFAULTS = mapOf(
            "addon_loaded" to "Addon '%s' %s loaded",
            "addons_loaded" to "Addons loaded: %s",
            "addon_reloaded" to "Addon '%s' %s reloaded",
            "addons_reloaded" to "Addons reloaded: %s",
            "addon_unloaded" to "Addon '%s' unloaded",
            "addons_unloaded" to "Addons unloaded: %s",
            "addon_failed" to "Addon '%s' %s failed to load",
            "addon_missing_mods" to "Addon '%s' %s is missing mods",
            "addon_waiting_for_dependencies" to "Addon '%s' %s is waiting for other addons",
            "addon_rejected" to "Addon '%s' %s was rejected",
            "addon_restart" to "Addon '%s' will be enabled after a restart",
        )
    }
}
