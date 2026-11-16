package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectService
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.npcs.registry.ServerNpcs
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses
import ru.hollowhorizon.hollowengine.common.scripting.execution.ScriptRunService
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeManager
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeStopRegistry
import java.nio.file.Path
import kotlin.reflect.KClass

class ServerRuntimeContext(
    private val server: MinecraftServer,
    levelRoot: Path,
) {
    val scope: CoroutineScope =
        CoroutineScope(server.dispatcher + SupervisorJob(server.coroutineScope.coroutineContext[Job]))
    internal val nodeStops = NodeStopRegistry()
    val nodes = NodeManager(server)
    internal val remoteProject by lazy { RemoteProjectService() }
    val scriptRuns = ScriptRunService(server, scope, nodes)
    val npcs = ServerNpcs(server, levelRoot.resolve("hollowengine").resolve("npcs.dat"))
    private var sceneState: Scene? = null
    private var disposed = false

    /** Runtime-only server scene; saving and synchronization are not yet implemented. */
    val scene: Scene
        get() {
            check(!disposed) { "Server runtime has been disposed" }
            return sceneState ?: Scene(object : SceneContext {
                override val side = LogicalSide.SERVER
                override fun <T : Any> capability(type: KClass<T>): T? =
                    if (type.java.isInstance(server)) type.java.cast(server) else null
            }).also { sceneState = it }
        }

    fun serialize(tag: CompoundTag) {
        tag.put("nodes", CompoundTag().also(nodes::serialize))
    }

    fun deserialize(tag: CompoundTag) {
        nodes.deserialize(tag.getCompound(if (tag.contains("nodes")) "nodes" else "components"))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        try {
            npcs.close()
        } finally {
            try {
                stopScripts()
            } finally {
                try {
                    sceneState?.close()
                } finally {
                    SharedScriptClasses.clearInstances()
                }
            }
        }
    }

    internal fun stopScripts() {
        scriptRuns.dispose()
        try {
            nodes.dispose()
        } finally {
            scope.cancel()
        }
    }
}

val MinecraftServer.runtimeContext get() = ServerRuntimeState.context(this)
