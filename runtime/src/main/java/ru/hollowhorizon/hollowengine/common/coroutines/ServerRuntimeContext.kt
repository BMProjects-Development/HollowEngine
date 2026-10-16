package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses
import ru.hollowhorizon.hollowengine.common.scripting.execution.ScriptRunService
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeManager

class ServerRuntimeContext(
    server: MinecraftServer,
) {
    val scope: CoroutineScope =
        CoroutineScope(server.dispatcher + SupervisorJob(server.coroutineScope.coroutineContext[Job]))
    val nodes = NodeManager(server)
    val scriptRuns = ScriptRunService(server, scope, nodes)

    fun serialize(tag: CompoundTag) {
        tag.put("nodes", CompoundTag().also(nodes::serialize))
    }

    fun deserialize(tag: CompoundTag) {
        nodes.deserialize(tag.getCompound(if (tag.contains("nodes")) "nodes" else "components"))
    }

    fun dispose() {
        scriptRuns.dispose()
        nodes.dispose()
        scope.cancel()
        SharedScriptClasses.clearInstances()
    }
}

val MinecraftServer.runtimeContext get() = ServerRuntimeState.context(this)
