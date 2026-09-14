package ru.hollowhorizon.hollowengine.common.scripting.source

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.scripting.ClientReloadScripts
import ru.hollowhorizon.hollowengine.client.ui.script.UiScriptLoader
import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.scripting.nodes.EntityNodeRuntime
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient

/**
 * Keeps everything that runs scripts in step with the namespaces that provide them. Enabling an addon
 * starts its nodes back up and has the client run its UI and reload scripts again; disabling one stops its
 * nodes without discarding their saved state. Server reload scripts follow the datapack reload that the
 * addon runtime starts on every change of the loaded addons.
 */
object ScriptSourceLifecycle : ScriptSourceListener {
    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            ScriptRegistry.addListener(this)
            installed = true
        }
    }

    override fun onScriptSourceChanged(namespace: String, available: Boolean) {
        ServerRuntimeState.servers().forEach { server ->
            runCatching {
                val nodes = server.runtimeContext.nodes
                if (available) nodes.resumeNamespace(namespace) else nodes.suspendNamespace(namespace)
            }.onFailure { HollowEngine.LOGGER.error("Failed to update server nodes of '$namespace'", it) }
        }
        runCatching {
            if (available) EntityNodeRuntime.resumeNamespace(namespace)
            else EntityNodeRuntime.suspendNamespace(namespace)
        }.onFailure { HollowEngine.LOGGER.error("Failed to update entity nodes of '$namespace'", it) }

        if (isPhysicalClient) {
            runCatching {
                UiScriptLoader.reload()
                ClientReloadScripts.rerun()
            }.onFailure { HollowEngine.LOGGER.error("Failed to reload client scripts after '$namespace' changed", it) }
        }
    }
}
