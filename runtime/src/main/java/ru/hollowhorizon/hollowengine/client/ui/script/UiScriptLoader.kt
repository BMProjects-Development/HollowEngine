package ru.hollowhorizon.hollowengine.client.ui.script

import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.ReloadListener
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.UI_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.ui.UiScript
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry
import ru.hollowhorizon.hollowengine.common.utils.Side

/**
 * Runs every `.ui.kts` script the engine can see, from the sandbox directory and from addons. Running a
 * UI script only registers declarations, nothing is composed until a screen is opened or an overlay is
 * shown, so a reload is cheap and free of side effects.
 */
@ReloadListener(Side.CLIENT)
object UiScriptLoader : ResourceManagerReloadListener {
    override fun onResourceManagerReload(resourceManager: ResourceManager) = reload()

    fun reload() {
        UiDefinitionRegistry.clear()
        ScriptRegistry.list(".$UI_SCRIPT_EXTENSION").forEach { id ->
            ScriptLoader.execute<UiScript>(id).onFailure { error ->
                HollowEngine.LOGGER.error("Failed to execute UI script: {}", ScriptRegistry.display(id), error)
            }
        }

        // The old composables belong to classes that no longer exist, so nothing shown may survive.
        UiScriptHudHost.reload()
        UiScreenOverrides.reload()
    }
}
