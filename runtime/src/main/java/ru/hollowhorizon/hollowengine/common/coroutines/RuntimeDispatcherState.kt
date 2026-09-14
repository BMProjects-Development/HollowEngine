package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import ru.hollowhorizon.hollowengine.common.utils.currentServerOrNull
import java.util.*

private data class DispatcherState(
    val dispatcher: SingleThreadDispatcher,
    val scope: CoroutineScope,
)

object RuntimeDispatcherState {
    private val serverStates = Collections.synchronizedMap(WeakHashMap<MinecraftServer, DispatcherState>())
    private val clientStates = Collections.synchronizedMap(WeakHashMap<Minecraft, DispatcherState>())

    /** Handed out while the datapacks of a world load, before its server exists; that server adopts it. */
    private var upcomingServer: SingleThreadDispatcher? = null

    fun createServer(server: MinecraftServer, serverThread: Thread) {
        val dispatcher = takeUpcomingServer()?.also { it.bind(serverThread) }
            ?: SingleThreadDispatcher(SERVER_DISPATCHER, serverThread)
        serverStates.computeIfAbsent(server) {
            DispatcherState(dispatcher, CoroutineScope(SupervisorJob() + dispatcher))
        }
    }

    /**
     * The dispatcher of the server whose datapacks are loading: the running one on `/reload`, or the one
     * about to be created while a world opens. Work dispatched to the latter runs once that server ticks.
     */
    @Synchronized
    fun loadingServerDispatcher(): SingleThreadDispatcher {
        currentServerOrNull()?.let { server -> serverStates[server]?.let { return it.dispatcher } }
        return upcomingServer ?: SingleThreadDispatcher(SERVER_DISPATCHER, thread = null).also { upcomingServer = it }
    }

    @Synchronized
    private fun takeUpcomingServer(): SingleThreadDispatcher? = upcomingServer.also { upcomingServer = null }

    fun runServerTasks(server: MinecraftServer) {
        server(server).dispatcher.runTasks()
    }

    fun stopServer(server: MinecraftServer) {
        val state = serverStates.remove(server) ?: return
        state.scope.cancel()
        state.dispatcher.runTasks()
        state.dispatcher.shutdown()
    }

    fun serverDispatcher(server: MinecraftServer) = server(server).dispatcher

    fun serverScope(server: MinecraftServer) = server(server).scope

    fun createClient(client: Minecraft) {
        clientStates.computeIfAbsent(client) {
            val dispatcher = SingleThreadDispatcher("Minecraft.dispatcher", Thread.currentThread())
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            DispatcherState(dispatcher, scope)
        }
    }

    fun runClientTasks(client: Minecraft) {
        client(client).dispatcher.runTasks()
    }

    fun stopClient(client: Minecraft) {
        val state = clientStates.remove(client) ?: return
        state.scope.cancel()
        state.dispatcher.runTasks()
        state.dispatcher.shutdown()
    }

    fun clientDispatcher(client: Minecraft) = client(client).dispatcher

    fun clientScope(client: Minecraft) = client(client).scope

    fun clientScopeOrNull(client: Minecraft): CoroutineScope? = clientStates[client]?.scope

    private fun server(server: MinecraftServer) =
        serverStates[server] ?: error("Server dispatcher state is not initialized for $server")

    private fun client(client: Minecraft) =
        clientStates[client] ?: error("Client dispatcher state is not initialized for $client")

    private const val SERVER_DISPATCHER = "MinecraftServer.dispatcher"
}
