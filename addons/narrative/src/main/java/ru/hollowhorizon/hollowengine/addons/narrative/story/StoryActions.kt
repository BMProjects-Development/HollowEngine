package ru.hollowhorizon.hollowengine.addons.narrative.story

import io.github.classgraph.ClassGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonLoadedEvent
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import java.io.File
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction

/**
 * The actions stories can call and journal, by id. A script registers its actions when it starts, an addon when it
 * loads. A journal record names its action by id, so after a restart it is undone by whatever declares that id then.
 */
object StoryActions {
    private val byId = HashMap<String, StoryAction>()
    private val byMethod = HashMap<Method, StoryAction>()
    private val byBlock = HashMap<Class<*>, StoryAction>()
    private val users = HashMap<Class<*>, Int>()

    /** What [all] reads: replaced on every change, so the editor may read it off the game thread. */
    @Volatile
    private var snapshot: List<StoryAction> = emptyList()

    /**
     * Registers the [Action] and [SimpleAction] functions [type] declares under [namespace] until [job] ends; every
     * instance of a script registers its class, which stays registered while one of them runs.
     */
    fun register(type: Class<*>, namespace: String, job: Job? = null) {
        val count = users.merge(type, 1, Int::plus)!!
        job?.invokeOnCompletion { release(type) }
        if (count > 1) return
        val declared =
            type.declaredMethods.mapNotNull { declare(it, namespace) }.groupBy { it.id }.mapNotNull { (id, same) ->
                    if (same.size > 1) HollowEngine.LOGGER.error(
                        "Actions {} of {} share the id '{}'; give each of them an id of its own",
                        same.map { it.method.name },
                        type.name,
                        id
                    )
                    same.first()
                }
        declared.forEach { action ->
            val previous = byId.put(action.id, action)
            if (previous != null && previous.method.declaringClass.name != type.name) {
                HollowEngine.LOGGER.warn(
                    "Action '{}' of {} replaces the one of {}",
                    action.id,
                    type.name,
                    previous.method.declaringClass.name
                )
            }
            byMethod[action.method] = action
        }
        snapshot = byId.values.toList()
    }

    private fun release(type: Class<*>) {
        val count = users[type] ?: return
        if (count > 1) {
            users[type] = count - 1
            return
        }
        users.remove(type)
        byMethod.values.filter { it.method.declaringClass == type }.forEach(::remove)
    }

    /** The action registered as [id], if any. */
    fun find(id: String): StoryAction? = byId[id]

    /** Every registered action, for editors and commands. */
    val all: List<StoryAction> get() = snapshot

    /** Registers the actions of an addon that loaded, found the same way as its event listeners. */
    @SubscribeEvent
    fun onAddonLoaded(event: HollowAddonLoadedEvent) =
        registerFound(event.classes, event.classLoader, event.packagePrefix, event.id, event.scope.coroutineContext.job)

    /**
     * Registers under [namespace] the actions of every class in [classes] (a jar or a directory) within
     * [packagePrefix], as an addon's actions are registered when it loads.
     */
    fun registerFound(classes: File, classLoader: ClassLoader, packagePrefix: String?, namespace: String, job: Job? = null) {
        val names = ClassGraph()
            .enableClassInfo().enableAnnotationInfo().enableMethodInfo()
            .disableNestedJarScanning().ignoreParentClassLoaders()
            .overrideClassLoaders(classLoader).overrideClasspath(classes)
            .scan().use { scan ->
                (scan.getClassesWithMethodAnnotation(Action::class.java.name).names + scan.getClassesWithMethodAnnotation(
                    SimpleAction::class.java.name
                ).names).distinct()
            }
        names.filter { packagePrefix == null || it.startsWith(packagePrefix) }.forEach { name ->
            runCatching { register(Class.forName(name, false, classLoader), namespace, job) }
                .onFailure { HollowEngine.LOGGER.error("Cannot register the actions of {}", name, it) }
        }
    }

    /** The action whose body [block] is: the function the compiler put the lambda in. */
    internal fun enclosing(block: Any, what: String): StoryAction {
        byBlock[block.javaClass]?.let { return it }
        val method = block.javaClass.enclosingMethod
        val action = method?.let(byMethod::get) ?: throw StoryException(
            when {
                method == null -> "$what must be the body of an @Action function"
                method.isAnnotationPresent(Action::class.java) -> "Action '${method.declaringClass.name}.${method.name}' is not registered; actions register when their script starts or their addon loads"

                else -> "$what must be the body of an @Action function, not of '${method.name}'"
            }
        )
        byBlock[block.javaClass] = action
        return action
    }

    /** Undoes what [entry] did; logs why when it cannot. */
    internal fun undo(entry: JournalEntry) {
        val replay = try {
            StoryValues.withOffWorldNpcs { replay(entry) }
        } catch (missing: StoryEntityMissing) {
            StoryDeferredReplays.undo(entry, missing.uuid)
            return
        }
        val undo = replay?.scope?.undo ?: return
        try {
            UndoScope().undo()
        } catch (conflict: ActionConflict) {
            HollowEngine.LOGGER.warn("Story '{}' leaves '{}' as it is: {}", entry.run, entry.action, conflict.message)
        } catch (failure: Exception) {
            HollowEngine.LOGGER.error("Story '{}' failed to undo '{}'", entry.run, entry.action, failure)
        }
    }

    /** Brings the world to where [entry] ended: with its fast-forward, or by performing it again. */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun fastForward(entry: JournalEntry) {
        val replay = try {
            replay(entry)
        } catch (missing: StoryEntityMissing) {
            StoryDeferredReplays.fastForward(entry, missing.uuid)
            return
        }
        val scope = replay?.scope as? ActionScope<Any?> ?: return
        val block = scope.fastForward ?: scope.perform ?: return
        try {
            withContext(Replaying()) { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            HollowEngine.LOGGER.error("Story '{}' failed to fast-forward '{}'", entry.run, entry.action, failure)
        }
    }

    /**
     * Finishes the action of [entry], which a restart interrupted: performs it again under the same record, with the
     * snapshots taken before it first started, and returns its result.
     */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun resume(entry: JournalEntry): Any? {
        val replay = replay(entry) ?: throw StoryException("Story '${entry.run}' cannot finish '${entry.action}'")
        val perform = (replay.scope as? ActionScope<Any?>)?.perform
            ?: throw StoryException("Story '${entry.run}' cannot finish '${entry.action}': it performs nothing")
        val value = try {
            withContext(ActiveAction(entry.seq)) { perform() }
        } catch (cancelled: CancellationException) {
            throw unloaded(replay.call)?.takeIf { currentCoroutineContext().isActive } ?: cancelled
        }
        entry.done = true
        return value
    }

    /**
     * Calls the action of [entry] again with its saved receivers and parameters, declaring its blocks only. An entity
     * it needs that is not loaded is thrown as [StoryEntityMissing], so the caller can wait for it.
     */
    private fun replay(entry: JournalEntry): ActionReplay? {
        val action = byId[entry.action] ?: run {
            HollowEngine.LOGGER.warn(
                "Story '{}' cannot replay '{}': no action has this id now",
                entry.run,
                entry.action
            )
            return null
        }
        return try {
            ActionReplay(entry, action).also(action::replay)
        } catch (missing: StoryEntityMissing) {
            throw missing
        } catch (failure: Exception) {
            HollowEngine.LOGGER.warn("Story '{}' cannot replay '{}': {}", entry.run, entry.action, failure.message)
            null
        }
    }

    private fun declare(method: Method, namespace: String): StoryAction? {
        val reversible = method.getAnnotation(Action::class.java)
        val name = reversible?.id ?: method.getAnnotation(SimpleAction::class.java)?.id ?: return null
        // The Kotlin name, not the JVM one: a parameter of a value class such as Duration mangles the latter.
        val id = name.ifBlank { kebabCase(method.kotlinFunction?.name ?: method.name) }
            .let { if (':' in it) it else "$namespace:$it" }
        return runCatching {
            StoryAction(
                id,
                method,
                reversible != null
            )
        }.onFailure {
                HollowEngine.LOGGER.error(
                    "Action '{}' of {} cannot be used: {}",
                    id,
                    method.declaringClass.name,
                    it.message
                )
            }.getOrNull()
    }

    private fun remove(action: StoryAction) {
        if (byId[action.id] === action) byId.remove(action.id)
        byMethod.remove(action.method)
        byBlock.values.removeIf { it === action }
        snapshot = byId.values.toList()
    }

    /** `placeBlock` as `place-block`, the way scenes name commands. */
    private fun kebabCase(name: String): String =
        name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "-").lowercase()
}

/**
 * A function registered as an action: how its receivers and parameters are written to a journal record and read
 * back to call it again.
 */
class StoryAction internal constructor(val id: String, internal val method: Method, val reversible: Boolean) {
    private enum class Kind { SCRIPT, OBJECT, CHECKPOINT, UNSAVED, VALUE }

    private class Parameter(
        val reflected: KParameter,
        val name: String,
        val kind: Kind,
        val serializer: KSerializer<Any?>? = null,
    )

    internal val function: KFunction<*> =
        method.kotlinFunction ?: throw StoryException("'${method.name}' is not a Kotlin function")
    private val parameters: List<Parameter> by lazy { function.parameters.map(::parameter) }

    init {
        if (reversible && !function.isSuspend) throw StoryException("Action '$id' must be a suspend function")
        function.isAccessible = true
        if (reversible) parameters
    }

    /** The parameter names of the function, receivers included, as a record keeps them. */
    val parameterNames: List<String> get() = parameters.map { it.name }

    /** What the function is a member of: its object or a running instance of its script; null at the top level. */
    internal fun owner(): Any? {
        val owner = method.declaringClass
        owner.kotlin.objectInstance?.let { return it }
        if (!owner.isAnnotationPresent(StageScript::class.java)) return null
        return StoryInstances.instanceOf(owner) ?: throw StoryException("Action '$id' belongs to a script that is not running")
    }

    internal fun encode(call: Array<out Any?>): CompoundTag {
        if (call.size != parameters.size) throw StoryException(
            "action { } of '$id' takes the ${parameters.size} receivers and parameters of its function in order, not ${call.size}"
        )
        val tag = CompoundTag()
        parameters.forEachIndexed { index, parameter ->
            val value = call[index]
            when (parameter.kind) {
                Kind.SCRIPT -> tag.putString(
                    parameter.name,
                    StoryInstances.keyOf(value ?: throw StoryException("'$id' got no script"))
                )

                Kind.VALUE -> StoryValues.put(tag, parameter.name, parameter.serializer!!, value)
                Kind.OBJECT, Kind.CHECKPOINT, Kind.UNSAVED -> Unit
            }
        }
        return tag
    }

    /**
     * Calls the function again with what [replay] recorded, unsaved parameters left to their defaults; its body
     * declares the blocks into [replay] and returns without suspending.
     */
    internal fun replay(replay: ActionReplay) {
        val arguments = decode(replay.entry.args)
        var outcome: Result<Any?>? = null
        ActionReplay.begin(replay)
        try {
            suspend { function.callSuspendBy(arguments) }.startCoroutine(Continuation(EmptyCoroutineContext) {
                outcome = it
            })
        } finally {
            ActionReplay.end()
        }
        (outcome ?: throw StoryException("'$id' suspended before its action { } declared its blocks")).getOrThrow()
        if (replay.scope == null) throw StoryException("'$id' did not reach its action { }")
    }

    private fun decode(tag: CompoundTag): Map<KParameter, Any?> =
        parameters.filter { it.kind != Kind.UNSAVED }.associate { parameter ->
            parameter.reflected to when (parameter.kind) {
                Kind.SCRIPT -> tag.getString(parameter.name)
                    .let { StoryInstances.find(it) ?: throw StoryException("Script '$it' is not running") }

                Kind.OBJECT -> method.declaringClass.kotlin.objectInstance
                Kind.CHECKPOINT -> CheckpointScope.REPLAY
                Kind.VALUE -> StoryValues.get(tag, parameter.name, parameter.serializer!!)
                Kind.UNSAVED -> null
            }
        }

    private fun parameter(parameter: KParameter): Parameter {
        val name = if (parameter.kind == KParameter.Kind.EXTENSION_RECEIVER) "this" else parameter.name
            ?: "argument${parameter.index}"
        return when {
            parameter.kind == KParameter.Kind.INSTANCE -> {
                val owner = method.declaringClass
                when {
                    owner.isAnnotationPresent(StageScript::class.java) -> Parameter(
                        parameter,
                        "this\$script",
                        Kind.SCRIPT
                    )

                    owner.kotlin.objectInstance != null -> Parameter(parameter, "this\$object", Kind.OBJECT)
                    else -> throw StoryException("Action '$id' must be declared in a script, an object or at the top level")
                }
            }

            parameter.kind != KParameter.Kind.EXTENSION_RECEIVER && parameter.kind != KParameter.Kind.VALUE -> throw StoryException(
                "Action '$id' cannot take context parameters"
            )

            parameter.type.classifier == CheckpointScope::class -> Parameter(parameter, name, Kind.CHECKPOINT)
            parameter.annotations.any { it is Unsaved } -> {
                if (!parameter.isOptional) throw StoryException("The unsaved '$name' of '$id' needs a default value for the journal to call it with")
                Parameter(parameter, name, Kind.UNSAVED)
            }

            else -> {
                val serializer = try {
                    StoryValues.serializer(parameter.type)
                } catch (failure: StoryException) {
                    throw StoryException("The journal keeps the parameters of '$id', but '$name' cannot be kept: ${failure.message}")
                }
                Parameter(parameter, name, Kind.VALUE, serializer)
            }
        }
    }
}

/** A call of an action made again by the journal: the record it replays and the blocks its body declared. */
internal class ActionReplay(val entry: JournalEntry, val action: StoryAction) {
    var scope: CaptureScope? = null

    /** The receivers and parameters the call was made with again. */
    var call: Array<out Any?> = emptyArray()

    fun snapshot(index: Int, serializer: KSerializer<Any?>): Any? {
        val tag = entry.snapshots.get(index.toString())
            ?: throw StoryException("The record of '${action.id}' has no snapshot #$index: the action captures more than it did then")
        return StoryValues.decode(serializer, tag)
    }

    companion object {
        private val CURRENT = ThreadLocal<ActionReplay?>()

        fun begin(replay: ActionReplay) = CURRENT.set(replay)

        fun end() = CURRENT.remove()

        /** The replay [action] runs in, if its body is being called again by the journal. */
        fun take(action: StoryAction): ActionReplay? {
            val replay = CURRENT.get() ?: return null
            CURRENT.remove()
            if (replay.action !== action) throw StoryException(
                "'${replay.action.id}' reached the action { } of '${action.id}'; the body of an action must be its own action { }"
            )
            return replay
        }
    }
}
