package ru.hollowhorizon.hollowengine.addons.narrative

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.addons.narrative.client.NarrativeClient
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueCommands
import ru.hollowhorizon.hollowengine.addons.narrative.scene.Scenes
import ru.hollowhorizon.hollowengine.addons.narrative.scene.ScriptRegistryStorySource
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCommands
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.addons.extensions
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterCommandsEvent
import ru.hollowhorizon.hollowengine.common.scripting.*
import ru.hollowhorizon.hollowengine.common.scripting.reload.ServerReloadScriptsEvent
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient

/**
 * Dialogues, the `.story` language and cutscenes. NPCs and their action channels stay in the engine;
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
            RELOAD_SCRIPT_EXTENSION -> event.defaultImports += RELOAD_IMPORTS + STORY_PACKAGES
            NODE_SCRIPT_EXTENSION -> event.defaultImports += NODE_IMPORTS + STORY_PACKAGES
            STARTUP_SCRIPT_EXTENSION, SERVER_CONSOLE_SCRIPT_EXTENSION -> event.defaultImports += STORY_PACKAGES
        }
    }

    /** Reloaded scripts registered their actions again, which the stories are checked against. */
    @SubscribeEvent
    fun afterReloadScripts(event: ServerReloadScriptsEvent.Post) = Scenes.reload()

    @SubscribeEvent(-1)
    fun registerCommands(event: RegisterCommandsEvent) {
        val root = requireNotNull(event.dispatcher.root.getChild("hollowengine")) {
            "The HollowEngine root command must be registered before addon commands"
        }
        DialogueCommands.register(root)
        StoryCommands.register(root)
    }

    private companion object {
        const val DIALOGUE_PACKAGE = "ru.hollowhorizon.hollowengine.addons.narrative.scene.*"
        val STORY_PACKAGES = listOf(
            "ru.hollowhorizon.hollowengine.addons.narrative.story.*",
            "ru.hollowhorizon.hollowengine.addons.narrative.npcs.*",
        )

        val RELOAD_IMPORTS = listOf(DIALOGUE_PACKAGE)

        /** Nodes start dialogues and play cutscenes for players. */
        val NODE_IMPORTS = listOf(DIALOGUE_PACKAGE) + listOf(
            "playCutscene",
            "playCutsceneAt",
            "playCutsceneNear",
            "playCutsceneHere",
            "stopCutscene"
        ).map { "ru.hollowhorizon.hollowengine.addons.narrative.cutscene.$it" }
    }
}
