package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeHost
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * The script instances stages run in, under keys that survive a restart.
 *
 * A key is the source path of the script ([StageScript]); a node adds what it is attached to, since every
 * entity runs its own instance of an attached node. A saved frame names its instance by key, so a story
 * resumes once a script with that key is running again.
 */
object StoryInstances {
    private class Entry(val instance: Any, val job: Job?)

    private val byKey = HashMap<String, Entry>()
    private val keys = IdentityHashMap<Any, String>()
    private val stages = HashMap<Class<*>, Stages>()
    private val listeners = ArrayList<(String) -> Unit>()

    /**
     * Makes [instance] and the scripts it imports available to stories until [scope] ends. The compiler marks
     * every script class with [StageScript]; other instances are ignored.
     */
    fun register(instance: Any, scope: CoroutineScope) {
        val job = scope.coroutineContext.job
        val added = ArrayList<String>()
        register(instance, job, added)
        job.invokeOnCompletion { added.forEach { key -> remove(key, instance) } }
        added.forEach { key -> listeners.forEach { it(key) } }
    }

    /** The key [instance] is addressed by, registering it if it was not. */
    fun keyOf(instance: Any): String = keys[instance] ?: keyFor(instance).also { key ->
        keys[instance] = key
        byKey.putIfAbsent(key, Entry(instance, null))
    }

    /** The running instance registered under [key], if any. */
    fun find(key: String): Any? = byKey[key]?.instance

    /** The job of the instance under [key], which ends when the instance stops; null when nothing bounds it. */
    internal fun jobOf(key: String): Job? = byKey[key]?.job

    /** Calls [listener] with every key that becomes available. */
    internal fun onAvailable(listener: (String) -> Unit) {
        listeners += listener
    }

    /**
     * Starts or resumes [stage] of the instance under [key] with the encoded [args] and returns what it
     * returns.
     */
    suspend fun invoke(key: String, stage: String, args: CompoundTag): Any? =
        invoke(key, stage, { it.entries }, arrayOf(args))

    /** Calls the stage lambda [stage] of the instance under [key] with its [captured] values and [parameters]. */
    suspend fun call(key: String, stage: String, captured: CompoundTag, parameters: Array<out Any?>): Any? =
        invoke(key, stage, { it.calls }, arrayOf(captured, *parameters))

    /** The keys of every running instance. */
    val registered: Set<String> get() = byKey.keys

    /** The stages the instance under [key] declares, or none when nothing runs under it. */
    fun stagesOf(key: String): Set<String> = find(key)?.let { stagesOf(it.javaClass).entries.keys }.orEmpty()

    /** Whether the instance under [key] declares [stage]. */
    fun hasStage(key: String, stage: String): Boolean {
        val instance = find(key) ?: return false
        return stage in stagesOf(instance.javaClass).entries
    }

    private suspend fun invoke(key: String, stage: String, pick: (Stages) -> Map<String, Method>, arguments: Array<Any?>): Any? {
        val instance = find(key) ?: throw StoryException("No running script is registered as '$key'")
        val method = pick(stagesOf(instance.javaClass))[stage] ?: throw StoryException("Script '$key' has no stage '$stage'")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(instance, *arguments, continuation)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }

    /** Forgets everything, for tests and server shutdown. */
    internal fun clear() {
        byKey.clear()
        keys.clear()
    }

    private fun register(instance: Any, job: Job, added: MutableList<String>) {
        if (!instance.javaClass.isAnnotationPresent(StageScript::class.java)) return
        val key = keyFor(instance)
        if (byKey[key]?.let { it.instance === instance && it.job != null } == true) return
        keys[instance] = key
        byKey[key] = Entry(instance, job)
        added += key
        StoryActions.register(instance.javaClass, namespaceOf(instance), job)
        importsOf(instance).forEach { register(it, job, added) }
    }

    private fun remove(key: String, instance: Any) {
        if (byKey[key]?.instance === instance) byKey.remove(key)
        keys.remove(instance)
    }

    /**
     * The script id when the source path belongs to a known script, so a world moved to another directory still
     * finds its scripts; the path itself otherwise.
     */
    private fun keyFor(instance: Any): String {
        val location = instance.javaClass.getAnnotation(StageScript::class.java)?.path ?: return instance.javaClass.name
        val path = ScriptRegistry.idOfLocation(location)?.let(ScriptRegistry::display) ?: location
        if (instance !is NodeScript) return path
        return when (val host = instance.host) {
            is NodeHost.Server -> "$path@server"
            is NodeHost.OfEntity -> "$path@${host.entity.uuid}"
        }
    }

    /** The namespace the actions of a script are registered in: that of the script, or `script` when unknown. */
    private fun namespaceOf(instance: Any): String {
        val location = instance.javaClass.getAnnotation(StageScript::class.java)?.path ?: return "script"
        return ScriptRegistry.idOfLocation(location)?.namespace ?: "script"
    }

    /** The imported scripts an instance holds: they come in as constructor arguments kept in fields. */
    private fun importsOf(instance: Any): List<Any> = instance.javaClass.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
        .filter { it.type.isAnnotationPresent(StageScript::class.java) }
        .mapNotNull { field ->
            runCatching { field.trySetAccessible(); field.get(instance) }
                .onFailure { HollowEngine.LOGGER.warn("Cannot read imported script '{}'", field.name, it) }
                .getOrNull()
        }

    private fun stagesOf(type: Class<*>): Stages = stages.getOrPut(type) {
        val methods = type.declaredMethods.onEach { it.trySetAccessible() }
        Stages(
            methods.mapNotNull { method -> method.getAnnotation(StageEntry::class.java)?.let { it.name to method } }.toMap(),
            methods.mapNotNull { method -> method.getAnnotation(StageCall::class.java)?.let { it.name to method } }.toMap(),
        )
    }

    /** The generated methods of a script class: entries that start or resume a stage, calls of stage lambdas. */
    private class Stages(val entries: Map<String, Method>, val calls: Map<String, Method>)
}
