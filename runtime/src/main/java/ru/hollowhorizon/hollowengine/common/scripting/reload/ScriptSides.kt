package ru.hollowhorizon.hollowengine.common.scripting.reload

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.scripting.compiling.loadKotlinCompiledScriptFromJar
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptText

object ScriptSides {
    private val CLIENT_SIDE = Regex("""(?<![\w.])@file\s*:\s*(?:\[(?:[^\]]*?\s)?)?(?:[\w.]*\.)?ClientSide\b""")

    fun isClientSide(id: ScriptId): Boolean {
        val artifacts = ScriptRegistry.artifacts(id) ?: return false
        artifacts.sourceFile?.let { source -> return declaresClientSide(source.readText()) }
        val jar = artifacts.precompiled ?: return false
        return runCatching {
            jar.loadKotlinCompiledScriptFromJar(ScriptRegistry.source(id.namespace)?.classLoader).isClientSide
        }.onFailure { error ->
            HollowEngine.LOGGER.error("Cannot tell the side of '{}' from its compiled artifact", ScriptRegistry.display(id), error)
        }.getOrDefault(false)
    }

    fun declaresClientSide(text: String): Boolean = CLIENT_SIDE.containsMatchIn(ScriptText.normalize(text))
}
