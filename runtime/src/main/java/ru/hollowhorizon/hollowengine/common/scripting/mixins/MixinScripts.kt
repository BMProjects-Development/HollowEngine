package ru.hollowhorizon.hollowengine.common.scripting.mixins

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinDispatch
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec
import ru.hollowhorizon.hollowengine.common.scripting.MIXIN_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptCompilationException
import ru.hollowhorizon.hollowengine.common.scripting.reload.ScriptSides
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import java.io.File
import kotlin.script.experimental.api.constructorArgs

/**
 * Runs every `.mixin.kts` once, right after addons are loaded and before startup scripts, and binds its
 * bodies to the mixins generated for it at launch.
 */
object MixinScripts {
    private val bound = HashMap<ScriptId, Set<String>>()

    private val specs = LinkedHashMap<String, MixinSpecStore.Stored>()

    data class Binding(val bound: Int, val pending: Int)

    @Volatile
    var hasRun: Boolean = false
        private set

    fun run() {
        if (hasRun) return
        hasRun = true

        val scripts = ScriptRegistry.list(".$MIXIN_SCRIPT_EXTENSION").sortedBy(ScriptId::qualified)
            .filter { id -> isPhysicalClient || !ScriptSides.isClientSide(id) }
        if (scripts.isEmpty()) return
        val bindings = scripts.mapNotNull { id -> load(id).getOrNull() }
        store()
        HollowEngine.LOGGER.info(
            "Mixin scripts: {} of {} ran, {} bodies bound, {} waiting for a restart",
            bindings.size,
            scripts.size,
            bindings.sumOf(Binding::bound),
            bindings.sumOf(Binding::pending),
        )
    }

    /** Runs [id] again and rebinds its bodies. Its declarations only change with a restart. */
    fun reload(id: ScriptId): Result<Binding> = load(id).also { store() }

    @Synchronized
    private fun load(id: ScriptId): Result<Binding> {
        var spec: ByteArray? = null
        return ScriptLoader.executeCompiled<MixinScript>(id, { compiled -> spec = specOf(compiled) }) {
            constructorArgs(id.qualified)
        }.map { script ->
            val current = spec
            if (current == null) specs.remove(id.qualified) else specs[id.qualified] =
                MixinSpecStore.Stored(current, sourceHash(id))
            bind(id, script)
        }.onFailure { error ->
            HollowEngine.LOGGER.error("Failed to run mixin script {}", ScriptRegistry.display(id), error)
            if (error is ScriptCompilationException) specs[id.qualified] = MixinSpecStore.Stored(null, sourceHash(id))
        }
    }

    private fun sourceHash(id: ScriptId): String? =
        ScriptRegistry.artifacts(id)?.sourceFile?.takeIf(File::isFile)?.let(MixinSpecStore::sourceHash)

    private fun bind(id: ScriptId, script: MixinScript): Binding {
        val handlers = script.handlers.mapKeys { (key, _) -> ScriptMixinSpec.qualifiedKey(id.qualified, key) }
        bound[id].orEmpty().filterNot(handlers::containsKey).forEach(ScriptMixinDispatch::unbind)

        val missing = handlers.filterNot { (key, handler) -> ScriptMixinDispatch.bind(key, handler) }.keys
        bound[id] = handlers.keys - missing
        if (missing.isNotEmpty()) {
            HollowEngine.LOGGER.warn(
                "{} of {} mixins of '{}' were not applied when the game started: the script is new or its " + "declarations changed. Restart the game to apply them.",
                missing.size,
                handlers.size,
                ScriptRegistry.display(id),
            )
        }
        return Binding(handlers.size - missing.size, missing.size)
    }

    @Synchronized
    private fun store() = MixinSpecStore.write(ScriptFingerprint.currentRuntimeIdentity, specs)

    private fun specOf(compiled: CompiledScript): ByteArray? {
        val type = compiled.type.java
        val path = type.name.replace('.', '/') + ScriptMixinSpec.CLASS_SUFFIX + ".class"
        return type.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
    }
}
