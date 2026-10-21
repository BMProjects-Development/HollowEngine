package ru.hollowhorizon.hollowengine.addons.narrative

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.addons.narrative.client.NarrativeClient
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueCommands
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.ScriptRegistryStorySource
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryEngine
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.addons.extensions
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterCommandsEvent
import ru.hollowhorizon.hollowengine.common.scripting.NODE_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.RELOAD_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.ScriptDefaultImportsEvent
import ru.hollowhorizon.hollowengine.common.scripting.reload.ServerReloadScriptsEvent
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient

/**
 * Dialogues and the `.story` language. NPCs, their action channels and cutscenes stay in the engine;
 * this addon builds stories on top of them.
 */
class HollowNarrativeAddon : HollowAddonEntrypoint {
    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        ScriptRegistry.addListener(ScriptRegistryStorySource)
        context.extensions.onUnload { ScriptRegistry.removeListener(ScriptRegistryStorySource) }
        if (isPhysicalClient) NarrativeClient.register(context.extensions)
    }

    @SubscribeEvent
    fun addDefaultImports(event: ScriptDefaultImportsEvent) {
        when (event.suffix) {
            RELOAD_SCRIPT_EXTENSION -> event.defaultImports += RELOAD_IMPORTS
            NODE_SCRIPT_EXTENSION -> event.defaultImports += DIALOGUE_PACKAGE
        }
    }

    /** Engine-owned story functions are reinstalled before reload scripts register theirs. */
    @SubscribeEvent
    fun beforeReloadScripts(event: ServerReloadScriptsEvent.Pre) = StoryEngine.prepareReload()

    @SubscribeEvent
    fun afterReloadScripts(event: ServerReloadScriptsEvent.Post) = StoryEngine.completeReload()

    @SubscribeEvent(-1)
    fun registerCommands(event: RegisterCommandsEvent) {
        val root = requireNotNull(event.dispatcher.root.getChild("hollowengine")) {
            "The HollowEngine root command must be registered before addon commands"
        }
        DialogueCommands.register(root)
    }

    private companion object {
        const val DIALOGUE_PACKAGE = "ru.hollowhorizon.hollowengine.addons.narrative.dialogue.*"

        /** Reload scripts register story functions, so they also get the signature helpers. */
        val RELOAD_IMPORTS = listOf(DIALOGUE_PACKAGE) +
            listOf("actor", "any", "boolean", "list", "number", "signature", "string")
                .map { "ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.$it" }
    }
}
