package ru.hollowhorizon.hollowengine.common.scripting.startup

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.scripting.StartupScriptNotice
import ru.hollowhorizon.hollowengine.common.scripting.STARTUP_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.ScriptEventHandlers
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptImports
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import kotlin.script.experimental.api.constructorArgs

/**
 * Runs every `.startup.kts` once, right after addons are initialized and before mod loader fires its
 * registration events.
 *
 * Startup scripts are shared: the instance created here is the one every script importing it gets, for as
 * long as the game runs. That is why a script runs after the startup scripts it imports.
 */
object StartupScripts {
    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("Startup scripts"))

    /** Once set, a newly appearing startup script can only run after a restart. */
    @Volatile
    var hasRun: Boolean = false
        private set

    fun run() {
        if (hasRun) return
        hasRun = true

        val failed = orderedScripts().filterNot(::load)
        if (failed.isNotEmpty() && isPhysicalClient) {
            StartupScriptNotice.show(failed.map(ScriptRegistry::display))
        }
    }

    private fun load(id: ScriptId): Boolean = ScriptLoader.execute<StartupScript>(id) {
        constructorArgs(isPhysicalClient, id.namespace)
    }.onSuccess { script ->
        SharedScriptClasses.keepInstance(script::class)
        ScriptEventHandlers.subscribe(id, script, scope)
    }.onFailure { error ->
        HollowEngine.LOGGER.error("Failed to execute startup script: {}", ScriptRegistry.display(id), error)
    }.isSuccess

    private fun orderedScripts(): List<ScriptId> {
        val sources = ScriptRegistry.sources().associateBy { it.namespace }
        val ordered = LinkedHashSet<String>()
        fun visit(namespace: String, path: Set<String>) {
            if (namespace in ordered || namespace in path) return
            val source = sources[namespace] ?: return
            source.dependencies.forEach { dependency -> visit(dependency, path + namespace) }
            ordered += namespace
        }
        sources.keys.forEach { namespace -> visit(namespace, emptySet()) }

        val scripts = ordered.flatMap { namespace ->
            ScriptRegistry.listIn(namespace, ".$STARTUP_SCRIPT_EXTENSION").sortedBy(ScriptId::path)
        }
        return importsFirst(scripts)
    }

    /** [scripts] reordered so that each one comes after the scripts among them it imports. */
    private fun importsFirst(scripts: List<ScriptId>): List<ScriptId> {
        val known = scripts.toSet()
        val ordered = LinkedHashSet<ScriptId>()
        val visiting = HashSet<ScriptId>()
        fun visit(id: ScriptId) {
            if (id in ordered || !visiting.add(id)) return
            ScriptImports.closure(id).drop(1).filter(known::contains).forEach(::visit)
            ordered += id
        }
        scripts.forEach(::visit)
        return ordered.toList()
    }
}
