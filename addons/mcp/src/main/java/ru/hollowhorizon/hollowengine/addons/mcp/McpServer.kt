package ru.hollowhorizon.hollowengine.addons.mcp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.IOException
import java.net.Inet6Address
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The HTTP endpoint agents connect to. Every agent connection opens its own MCP session, and all of
 * them share [toolset]; [instructions] is asked again for each session, so it reports the game as it is
 * when the agent connects.
 */
internal class McpServer(
    private val version: String,
    private val toolset: List<McpTool>,
    private val instructions: () -> String,
) {
    @Volatile
    private var listener: Listener? = null

    val urls: List<String> get() = listener?.urls.orEmpty()

    fun connection(client: AgentClient): String? {
        val current = listener ?: return null
        val access = current.access.get() ?: return null
        return client.connection(current.copyUrl, access.token)
    }

    /** The addon serializes calls to this method with its load and unload operations. */
    suspend fun reconfigure(settings: McpSettings?) = withContext(NonCancellable) {
        val current = listener
        when {
            settings == null -> stop()
            current?.binding == settings.binding -> current.access.set(settings.access)
            else -> {
                val previous = current?.access?.get()?.let { McpSettings(current.binding, it) }
                stop()
                try {
                    listener = bind(settings)
                } catch (error: Exception) {
                    if (previous != null) {
                        try {
                            listener = bind(previous)
                        } catch (rollbackError: Exception) {
                            error.addSuppressed(rollbackError)
                        }
                    }
                    throw error
                }
                HollowEngine.LOGGER.info("MCP server listening on {}", urls.joinToString())
            }
        }
        if (settings != null && settings.binding.hosts.any { !it.isLoopbackAddress } && settings.access.allowlist.isEmpty) {
            HollowEngine.LOGGER.warn(
                "MCP is listening beyond loopback with an empty allowlist. Any reachable IP can use it with the bearer token",
            )
        }
    }

    private suspend fun bind(settings: McpSettings): Listener {
        val binding = settings.binding
        val addresses = binding.hosts.toList()
        val access = AtomicReference<McpAccess?>()
        val sessions = ConcurrentHashMap.newKeySet<Server>()
        val server = embeddedServer(CIO, configure = {
            addresses.forEach { address ->
                connector {
                    host = address.hostAddress
                    port = binding.port
                }
            }
        }) {
            enforceAccess(access)
            mcpStreamableHttp(
                path = PATH,
                allowedHosts = binding.httpHosts.toList(),
                allowedOrigins = binding.httpHosts.map { "http://$it" },
            ) {
                createSession().also { protocol ->
                    sessions.add(protocol)
                    protocol.onConnect {
                        protocol.sessions.values.single().onClose { sessions.remove(protocol) }
                    }
                }
            }
        }
        try {
            server.startSuspend(wait = false)
            val connectors = server.engine.resolvedConnectors()
            val urls = connectors.map { endpointUrl(it.host, it.port) }
            val localIndex = addresses.indexOfFirst { it.isLoopbackAddress || it.isAnyLocalAddress }.coerceAtLeast(0)
            val local = addresses[localIndex]
            val copyHost = when {
                !local.isAnyLocalAddress -> local.hostAddress
                local is Inet6Address -> "::1"
                else -> "127.0.0.1"
            }
            val copyUrl = endpointUrl(copyHost, connectors[localIndex].port)
            access.set(settings.access)
            return Listener(server, binding, access, urls, copyUrl, sessions)
        } catch (error: Exception) {
            try {
                server.stopSuspend(gracePeriodMillis = 200, timeoutMillis = 2_000)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            // CIO can surface a connector failure as cancellation of its startup job.
            val hosts = addresses.joinToString { it.hostAddress }
            throw IOException("Could not bind MCP on port ${binding.port} at $hosts", error)
        }
    }

    private suspend fun stop() {
        val current = listener ?: return
        current.access.set(null)
        listener = null
        try {
            current.engine.stopSuspend(gracePeriodMillis = 200, timeoutMillis = 2_000)
        } finally {
            // The SDK owns a notification scope per server, outside Ktor's request scope.
            current.sessions.forEach { protocol ->
                try {
                    protocol.close()
                } catch (error: Exception) {
                    HollowEngine.LOGGER.warn("Could not close an MCP session", error)
                }
            }
        }
    }

    private fun endpointUrl(host: String, port: Int): String =
        URI("http", null, host, port, PATH, null, null).toASCIIString()

    private fun createSession(): Server = Server(
        serverInfo = Implementation(name = "hollowengine", version = version, title = "HollowEngine"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructionsProvider = instructions,
    ) {
        toolset.forEach { tool ->
            addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.schema,
                toolAnnotations = ToolAnnotations(readOnlyHint = tool.readOnly, destructiveHint = false),
            ) { request -> runTool(tool, ToolArguments(request.arguments)) }
        }
    }

    private suspend fun runTool(tool: McpTool, arguments: ToolArguments) = try {
        tool.handler(arguments)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ToolInputException) {
        errorResult(error.message.orEmpty())
    } catch (error: Exception) {
        HollowEngine.LOGGER.error("MCP tool '{}' failed", tool.name, error)
        errorResult("${tool.name} failed: ${error.message ?: error::class.simpleName}")
    }

    private fun Application.enforceAccess(active: AtomicReference<McpAccess?>) {
        intercept(ApplicationCallPipeline.Plugins) {
            val access = active.get()
            val address = call.request.local.remoteAddress
            when {
                access == null -> call.respondText("MCP is unavailable", status = HttpStatusCode.ServiceUnavailable)
                !access.allowlist.allows(address) -> {
                    HollowEngine.LOGGER.warn("MCP denied request from {}: IP is outside the allowlist", address)
                    call.respondText("Client IP is not allowed", status = HttpStatusCode.Forbidden)
                }

                !access.acceptsToken(call.request.headers[HttpHeaders.Authorization]) -> {
                    HollowEngine.LOGGER.warn("MCP denied request from {}: missing or invalid bearer token", address)
                    call.respondText("Missing or wrong bearer token", status = HttpStatusCode.Unauthorized)
                }

                else -> return@intercept
            }
            finish()
        }
    }

    private class Listener(
        val engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
        val binding: McpBinding,
        val access: AtomicReference<McpAccess?>,
        val urls: List<String>,
        val copyUrl: String,
        val sessions: Set<Server>,
    )

    private companion object {
        const val PATH = "/mcp"
    }
}
