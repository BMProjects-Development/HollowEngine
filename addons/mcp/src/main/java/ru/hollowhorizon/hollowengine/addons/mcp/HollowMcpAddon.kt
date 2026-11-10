package ru.hollowhorizon.hollowengine.addons.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.mcp.client.screenshotTool
import ru.hollowhorizon.hollowengine.addons.mcp.docs.DocsLibrary
import ru.hollowhorizon.hollowengine.addons.mcp.tools.codeTools
import ru.hollowhorizon.hollowengine.addons.mcp.tools.docsTools
import ru.hollowhorizon.hollowengine.addons.mcp.tools.runCommandTool
import ru.hollowhorizon.hollowengine.addons.mcp.tools.runSnippetTool
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterCommandsEvent
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient

class HollowMcpAddon : HollowAddonEntrypoint {
    private lateinit var server: McpServer
    private lateinit var scope: CoroutineScope
    private val configLock = Mutex()

    /** Why the server is not running, when it is not. */
    @Volatile
    private var status: Component = Component.translatableWithFallback(
        "hollowengine_mcp.command.status.starting", "The MCP server has not started yet",
    )

    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        this.scope = scope
        val docs = DocsLibrary.load(context.classLoader, DOCS_ROOT)
        val tools = buildList {
            add(runCommandTool())
            add(runSnippetTool())
            addAll(codeTools())
            addAll(docsTools(docs))
            if (isPhysicalClient) add(screenshotTool())
        }
        val instructions = McpInstructions(context.classLoader)
        server = McpServer(context.descriptor.version, tools, instructions::render)
        try {
            withContext(Dispatchers.IO) { applyConfig(createIfMissing = true) }
        } catch (error: CancellationException) {
            try {
                stopServer()
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    private suspend fun applyConfig(createIfMissing: Boolean): Component? = configLock.withLock {
        try {
            val settings = loadMcpSettings(defaultEnabled = isPhysicalClient, createIfMissing = createIfMissing)
            server.reconfigure(settings)
            if (settings == null) {
                status = Component.translatableWithFallback(
                    "hollowengine_mcp.command.status.disabled",
                    "MCP is off. Set enabled = true in config/hollowengine-mcp.toml and run /he mcp reload",
                )
                HollowEngine.LOGGER.info("The MCP server is off in config/hollowengine-mcp.toml")
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = Component.translatableWithFallback(
                "hollowengine_mcp.command.status.config_failed",
                "MCP could not apply its configuration. See the game log for details.",
            )
            status = failure
            HollowEngine.LOGGER.error("Could not apply the MCP configuration", error)
            failure
        }
    }

    private fun reload(source: CommandSourceStack): Int {
        scope.launch(Dispatchers.IO) {
            val failure = applyConfig(createIfMissing = false)
            source.server.execute {
                if (failure == null) {
                    source.sendSuccess({
                        Component.translatableWithFallback(
                            "hollowengine_mcp.command.reloaded", "MCP configuration reloaded",
                        )
                    }, false)
                } else {
                    source.sendFailure(failure)
                }
            }
        }
        return 1
    }

    override suspend fun unload(context: HollowAddonContext) = stopServer()

    private suspend fun stopServer() =
        withContext(Dispatchers.IO + NonCancellable) { configLock.withLock { server.reconfigure(null) } }

    @SubscribeEvent(-1)
    fun registerCommands(event: RegisterCommandsEvent) {
        val root = requireNotNull(event.dispatcher.root.getChild("hollowengine")) {
            "The HollowEngine root command must be registered before addon commands"
        }
        McpCommand(server, status = { status }, reload = ::reload).register(root)
    }

    private companion object {
        const val DOCS_ROOT = "hollowengine-mcp/docs"
    }
}
