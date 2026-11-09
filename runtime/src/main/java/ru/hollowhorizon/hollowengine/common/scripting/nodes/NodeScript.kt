package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.CoroutineScope
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import kotlin.coroutines.CoroutineContext

/**
 * Base class of every `*.node.kts` script. The script body runs as the constructor, so by the time it
 * executes [binding] is already set. Persisted delegates restore their values when declared, so the
 * rest of the body sees saved values. The registration handlers ([onStart], [onStop], [onSave],
 * [onLoad], [onUpdate], entity handlers) can attach themselves to this node's [CoroutineScope].
 */
abstract class NodeScript(
    val path: String,
    internal val binding: NodeBinding,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = binding.scope.coroutineContext

    val host: NodeHost get() = binding.host
    val server: MinecraftServer get() = binding.host.server

    internal val onSaveHandlers = mutableListOf<(SerializationContext) -> Unit>()
    internal val onLoadHandlers = mutableListOf<(SerializationContext) -> Unit>()
    internal val onStartHandlers = mutableListOf<suspend CoroutineScope.() -> Unit>()
    internal val sections = linkedMapOf<String, NodeSection>()

    val editor: NodeEditor = NodeEditor(this)

    /**
     * Keeps [section] with this node under [key], beside the script's own `onSave` data. Register it from
     * [ru.hollowhorizon.hollowengine.common.scripting.ScriptStartedEvent]: the saved value is handed back
     * right after, before `onLoad`.
     */
    fun section(key: String, section: NodeSection) {
        require(sections.putIfAbsent(key, section) == null) { "Node '$path' already has a section '$key'" }
    }
}

/** Data an addon stores with a node, see [NodeScript.section]. */
interface NodeSection {
    /** What to write into the node save, or null to write nothing. */
    fun save(): Tag?

    /** Restores what [save] wrote the last time the node was saved. */
    fun load(tag: Tag)
}

data class SerializationContext(
    val server: MinecraftServer,
    val tag: CompoundTag,
)
