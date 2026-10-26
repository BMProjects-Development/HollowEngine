package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.serialization.KSerializer
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryInstances.register
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeHost
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * The script instances stages run in, under keys that survive a restart.
 *
 * A key is the source path of the script ([StageScript]); a node adds what it is attached to, since every
 * entity runs its own instance of an attached node. A saved frame names its instance by key, so a story
 * resumes once a script with that key is running again.
 */
object StoryInstances {
    private class Entry(val instance: Any) {
        val users = LinkedHashSet<Job>()
        val lifetime = Job()
    }

    private val byKey = HashMap<String, Entry>()
    private val keys = IdentityHashMap<Any, String>()

    /** Stages the addon declares itself, such as [scene]: always there, under keys that never change. */
    private val builtins = mapOf(SceneStage.KEY to SceneStage)
    private val stages = object : ClassValue<Stages>() {
        override fun computeValue(type: Class<*>): Stages {
            val methods = type.declaredMethods
            return Stages(
                methods.mapNotNull { method -> method.getAnnotation(StageEntry::class.java)?.let { it.name to method } }
                    .toMap(),
                methods.mapNotNull { method -> method.getAnnotation(StageCall::class.java)?.let { it.name to method } }
                    .toMap(),
                methods.mapNotNull { method ->
                    method.getAnnotation(StageResult::class.java)?.let { it.name to method }
                }.toMap(),
            )
        }
    }
    private val listeners = ArrayList<(String) -> Unit>()

    /**
     * Makes [instance] and the scripts it imports available to stories until [scope] ends. The compiler marks
     * every script class with [StageScript]; other instances are ignored.
     */
    fun register(instance: Any, scope: CoroutineScope) {
        checkStoryClasses(instance.javaClass)
        val job = scope.coroutineContext.job
        val added = LinkedHashMap<String, Entry>()
        register(instance, job, added)
        job.invokeOnCompletion { added.forEach { (key, entry) -> remove(key, entry, job) } }
        added.keys.filter(byKey::containsKey).forEach { key -> listeners.forEach { it(key) } }
    }

    /** The key [instance] is addressed by; [register] makes it available after construction has finished. */
    fun keyOf(instance: Any): String = keys.getOrPut(instance) { keyFor(instance) }

    /** The running instance registered under [key], if any. */
    fun find(key: String): Any? = byKey[key]?.instance ?: builtins[key]

    /** The job of the instance under [key], which ends when the instance stops; null when nothing bounds it. */
    internal fun jobOf(key: String): Job? = byKey[key]?.takeIf { it.users.isNotEmpty() }?.lifetime

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

    /** Restores type information generated with the lambda, without serializing serializer implementations. */
    @Suppress("UNCHECKED_CAST")
    internal fun resultSerializer(key: String, stage: String): KSerializer<Any?> {
        val instance = find(key) ?: throw StoryException("No running script is registered as '$key'")
        val factory = stagesOf(instance.javaClass).results[stage]
            ?: throw StoryException("Script '$key' has no result serializer for stage '$stage'; recompile the script")
        return factory.invoke(instance) as KSerializer<Any?>
    }

    /** The script an instance [key] belongs to, or null when the key does not name a script. */
    internal fun scriptOf(key: String): ScriptId? =
        runCatching { ScriptRegistry.parse(key.substringBefore('@')) }.getOrNull()

    /**
     * Whether the script of [key] no longer exists, so nothing will run under [key] again. A script of a namespace
     * that is not installed right now is not gone: turning its addon on brings it back.
     */
    internal fun isGone(key: String): Boolean {
        if (key in builtins) return false
        val id = scriptOf(key) ?: return false
        return ScriptRegistry.source(id.namespace) != null && ScriptRegistry.artifacts(id) == null
    }

    /** The keys of every running instance. */
    val registered: Set<String> get() = byKey.keys

    /** The stages the instance under [key] declares, or none when nothing runs under it. */
    fun stagesOf(key: String): Set<String> = find(key)?.let { stagesOf(it.javaClass).entries.keys }.orEmpty()

    /** Whether the instance under [key] declares [stage]. */
    fun hasStage(key: String, stage: String): Boolean {
        val instance = find(key) ?: return false
        return stage in stagesOf(instance.javaClass).entries
    }

    private suspend fun invoke(
        key: String,
        stage: String,
        pick: (Stages) -> Map<String, Method>,
        arguments: Array<Any?>,
    ): Any? {
        val instance = find(key) ?: throw StoryException("No running script is registered as '$key'")
        val method =
            pick(stagesOf(instance.javaClass))[stage] ?: throw StoryException("Script '$key' has no stage '$stage'")
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
        val entries = byKey.values.toList()
        byKey.clear()
        keys.clear()
        entries.forEach { it.lifetime.cancel() }
    }

    private fun register(instance: Any, job: Job, added: MutableMap<String, Entry>) {
        if (!instance.javaClass.isAnnotationPresent(StageScript::class.java)) return
        val key = keyFor(instance)
        val entry = byKey[key]?.takeIf { it.instance === instance } ?: Entry(instance).also { byKey[key] = it }
        if (!entry.users.add(job)) return
        keys[instance] = key
        added[key] = entry
        StoryActions.register(instance.javaClass, namespaceOf(instance), job)
        importsOf(instance).forEach { register(it, job, added) }
    }

    private fun remove(key: String, entry: Entry, job: Job) {
        entry.users.remove(job)
        if (entry.users.isNotEmpty()) return
        if (byKey[key] === entry) byKey.remove(key)
        if (byKey[key]?.instance !== entry.instance) keys.remove(entry.instance)
        entry.lifetime.cancel()
    }

    /**
     * The script id of [type] when its source path belongs to a known script, so a world moved to another directory
     * still finds its scripts; the path itself otherwise, and null for a class that is not a script.
     */
    internal fun scriptPathOf(type: Class<*>): String? {
        val location = type.getAnnotation(StageScript::class.java)?.path ?: return null
        return ScriptRegistry.idOfLocation(location)?.let(ScriptRegistry::display) ?: location
    }

    private fun keyFor(instance: Any): String {
        builtins.entries.firstOrNull { it.value === instance }?.let { return it.key }
        val path = scriptPathOf(instance.javaClass) ?: return instance.javaClass.name
        if (instance !is NodeScript) return path
        return when (val host = instance.host) {
            is NodeHost.Server -> "$path@server"
            is NodeHost.OfEntity -> "$path@${host.entity.uuid}"
        }
    }

    /**
     * Reports a script that was compiled with stages but sees another copy of this addon: its stages and actions
     * carry annotations stories do not recognize, so they would silently not exist.
     */
    private fun checkStoryClasses(type: Class<*>) {
        if (type.isAnnotationPresent(StageScript::class.java)) return
        val seen = runCatching { Class.forName(StageScript::class.java.name, false, type.classLoader) }.getOrNull()
        val problem = when {
            seen != null && seen !== StageScript::class.java -> "runs with a copy of the narrative addon of its own"
            seen == null && type.declaredMethods.any { it.name.startsWith(ENTRY_PREFIX) } -> "declares stages but cannot see the narrative addon"
            else -> return
        }
        HollowEngine.LOGGER.error(
            "Script {} {}, so stories cannot see its stages and actions. " + "List 'hollowengine-narrative' in dependsOn of the project the script belongs to",
            type.name,
            problem,
        )
    }

    /** The namespace the actions of a script are registered in: that of the script, or `script` when unknown. */
    private fun namespaceOf(instance: Any): String {
        val location = instance.javaClass.getAnnotation(StageScript::class.java)?.path ?: return "script"
        return ScriptRegistry.idOfLocation(location)?.namespace ?: "script"
    }

    /**
     * The [saved] variables the instance under [key] and the scripts it imports declare, by the name a story keeps
     * them under. A script property `var trust by saved(0)` is kept in the field `trust$delegate`.
     */
    internal fun savedVariablesOf(key: String): Map<String, SavedVariable<*>> {
        val instance = find(key) ?: return emptyMap()
        val found = LinkedHashMap<String, SavedVariable<*>>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun collect(script: Any) {
            if (!visited.add(script)) return
            val path = scriptPathOf(script.javaClass) ?: script.javaClass.name
            script.javaClass.declaredFields.filter { it.name.endsWith(DELEGATE_SUFFIX) }.forEach { field ->
                val delegate =
                    runCatching { field.trySetAccessible(); field.get(script) }.getOrNull() as? SavedVariable<*>
                        ?: return@forEach
                found["$path/${field.name.removeSuffix(DELEGATE_SUFFIX)}"] = delegate
            }
            importsOf(script).forEach(::collect)
        }
        collect(instance)
        return found
    }

    /** The imported scripts an instance holds: they come in as constructor arguments kept in fields. */
    private fun importsOf(instance: Any): List<Any> =
        instance.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            .filter { it.type.isAnnotationPresent(StageScript::class.java) }.mapNotNull { field ->
                runCatching { field.trySetAccessible(); field.get(instance) }.onFailure {
                        HollowEngine.LOGGER.warn(
                            "Cannot read imported script '{}'",
                            field.name,
                            it
                        )
                    }.getOrNull()
            }

    private fun stagesOf(type: Class<*>): Stages = stages.get(type)

    /** How the compiler names the entries it generates for stages. */
    private const val ENTRY_PREFIX = "stage\$entry\$"

    private const val DELEGATE_SUFFIX = "\$delegate"

    /** The generated methods of a script class: entries that start or resume a stage, calls of stage lambdas. */
    private class Stages(
        val entries: Map<String, Method>,
        val calls: Map<String, Method>,
        val results: Map<String, Method>,
    )
}
