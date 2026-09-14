package ru.hollowhorizon.hollowengine.common.scripting.reload

import kotlinx.coroutines.*
import net.minecraft.server.packs.resources.ResourceManager
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scripting.RELOAD_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.ScriptEventHandlers
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.implicitReceivers

/**
 * Runs the reload scripts of one side. Each run gets fresh scope carrying the side, and starting run
 * cancels the previous one, so a script never has two generations of its handlers alive at once.
 */
internal class ReloadScriptRunner(private val side: LogicalSide) {
    private var scope: CoroutineScope? = null

    /** Runs every reload script of this side; [receiver] is the implicit receiver its scripts expect. */
    @Synchronized
    fun run(dispatcher: CoroutineDispatcher, resources: ResourceManager, receiver: Any) {
        stop()
        val runScope = CoroutineScope(SupervisorJob() + dispatcher + side + CoroutineName("$side reload scripts"))
        scope = runScope

        val clientSide = side == LogicalSide.CLIENT
        ScriptRegistry.list(".$RELOAD_SCRIPT_EXTENSION").filter { id -> ScriptSides.isClientSide(id) == clientSide }
            .forEach { id -> load(id, runScope, resources, receiver) }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
    }

    private fun load(id: ScriptId, runScope: CoroutineScope, resources: ResourceManager, receiver: Any) {
        val scriptScope = CoroutineScope(runScope.coroutineContext + SupervisorJob(runScope.coroutineContext.job))
        ScriptLoader.executeCompiled<ReloadScript>(
            id = id,
            validate = { compiled ->
                check(compiled.isClientSide == (side == LogicalSide.CLIENT)) {
                    "its compiled form disagrees with its source about @file:ClientSide; rebuild it"
                }
            },
        ) {
            constructorArgs(scriptScope, resources)
            implicitReceivers(receiver)
        }.onSuccess { script ->
            ScriptEventHandlers.subscribe(id, script, scriptScope)
        }.onFailure { error ->
            scriptScope.cancel()
            HollowEngine.LOGGER.error("Failed to execute reload script: {}", ScriptRegistry.display(id), error)
        }
    }
}
