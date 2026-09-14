package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import java.util.*

private data class DispatcherState(
    val dispatcher: SingleThreadDispatcher,
    val scope: CoroutineScope,
)

object RuntimeDispatcherState {
    private val serverStates = Collections.synchronizedMap(WeakHashMap<MinecraftServer, DispatcherState>())
    private val clientStates = Collections.synchronizedMap(WeakHashMap<Minecraft, DispatcherState>())

    fun createServer(server: MinecraftServer, serverThread: Thread) {
        serverStates.computeIfAbsent(server) {
            val dispatcher = SingleThreadDispatcher("MinecraftServer.dispatcher", serverThread)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            DispatcherState(dispatcher, scope)
        }
    }

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

    fun serverDispatcherOrNull(server: MinecraftServer): SingleThreadDispatcher? = serverStates[server]?.dispatcher

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
}
