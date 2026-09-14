package ru.hollowhorizon.hollowengine.client.scripting

import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.api.ReloadListener
import ru.hollowhorizon.hollowengine.common.coroutines.dispatcher
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scripting.reload.ReloadScriptRunner
import ru.hollowhorizon.hollowengine.common.utils.Side

/** Implicit receiver of `@file:ClientSide` reload scripts. */
class ClientReloadContext(val minecraft: Minecraft)

/**
 * Runs `@file:ClientSide` reload scripts on every client resource reload.
 */
@ReloadListener(Side.CLIENT)
object ClientReloadScripts : ResourceManagerReloadListener {
    private val runner = ReloadScriptRunner(LogicalSide.CLIENT)

    @Volatile
    private var resources: ResourceManager? = null

    override fun onResourceManagerReload(resourceManager: ResourceManager) = run(resourceManager)

    /**
     * Runs the scripts again against the resources of the last reload, for when the set of scripts changes
     * without one. Does nothing before the first reload, which is still coming.
     */
    fun rerun() {
        val minecraft = Minecraft.getInstance() ?: return
        minecraft.execute { run(resources ?: return@execute) }
    }

    private fun run(resourceManager: ResourceManager) {
        resources = resourceManager
        val minecraft = Minecraft.getInstance()
        runner.run(minecraft.dispatcher, resourceManager, ClientReloadContext(minecraft))
    }
}
