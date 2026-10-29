package ru.hollowhorizon.hollowengine.addons.narrative.scene

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptSourceListener

/**
 * Reads `.story` files out of the same namespaces that provide scripts, so a story is addressed
 * exactly like a script: `project:stories/example.story`, or an unqualified path for the sandbox.
 */
object ScriptRegistryStorySource : StorySourceProvider, ScriptSourceListener {
    override fun read(address: String): String? {
        val artifacts = runCatching { ScriptRegistry.artifacts(address) }
            .onFailure { HollowEngine.LOGGER.error("Failed to read story '$address'", it) }
            .getOrNull() ?: return null
        val file = artifacts.sourceFile?.takeIf { it.isFile } ?: return null
        return runCatching { file.readText() }
            .onFailure { HollowEngine.LOGGER.error("Failed to read story file $file", it) }
            .getOrNull()
    }

    /** A namespace appearing or disappearing can change what a story address resolves to. */
    override fun onScriptSourceChanged(namespace: String, available: Boolean) {
        Scenes.library.invalidate()
    }
}
