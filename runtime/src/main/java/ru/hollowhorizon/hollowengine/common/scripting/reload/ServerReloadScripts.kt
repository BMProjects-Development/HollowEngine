package ru.hollowhorizon.hollowengine.common.scripting.reload

import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.ReloadListener
import ru.hollowhorizon.hollowengine.common.compat.util.currentRecipeManagerOrNull
import ru.hollowhorizon.hollowengine.common.coroutines.ServerThreadDispatcher
import ru.hollowhorizon.hollowengine.common.dialogue.StoryEngine
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterCommandsEvent

/**
 * Runs server reload scripts on every datapack load: the opening of a world and `/reload`.
 */
@ReloadListener
object ServerReloadScripts : ResourceManagerReloadListener {
    private val runner = ReloadScriptRunner(LogicalSide.SERVER)

    @Volatile
    private var pendingCommands: RegisterCommandsEvent? = null

    /** Called by the platform when the command tree of the datapack load in progress has been created. */
    fun onCommandsCreated(event: RegisterCommandsEvent) {
        pendingCommands = event
    }

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        StoryEngine.prepareReload()
        val recipeManager = currentRecipeManagerOrNull()
        if (recipeManager == null) {
            HollowEngine.LOGGER.warn("Skipping reload scripts: RecipeManager is not initialized yet")
        } else {
            runner.run(ServerThreadDispatcher, resourceManager, ServerReloadContext(recipeManager))
        }
        StoryEngine.completeReload()

        pendingCommands?.let { event ->
            pendingCommands = null
            RegisterCommandsEvent.post(event)
        }
    }

    /** The scripts belong to the server that loaded them and end with it. */
    fun stop() = runner.stop()
}
