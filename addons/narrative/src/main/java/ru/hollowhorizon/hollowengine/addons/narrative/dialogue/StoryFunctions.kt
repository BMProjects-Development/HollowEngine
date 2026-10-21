package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryFunctionCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryType

/**
 * What a story function can see while it runs.
 *
 * [state] is the function's own checkpoint. It is persisted with the session, so a function that
 * wants to survive a restart mid-execution writes its progress there and reads it back on the next
 * run; functions that do not bother are simply re-executed from the start (their statement is
 * at-least-once). The tag is dropped once the call returns.
 */
interface StoryCallContext {
    val session: DialogueSession
    val server: MinecraftServer
    val players: List<ServerPlayer>
    val state: CompoundTag

    /** Persists the session (including [state]) right now, without waiting for the next statement. */
    fun checkpoint()
}

/** A registered function body. */
fun interface StoryFunctionHandler {
    suspend fun invoke(context: StoryCallContext, args: StoryArguments)
}

/**
 * The functions a story may call. Overloads share a name and are told apart by arity and argument
 * types; the compiler rejects calls no overload can accept, and the final pick happens at runtime
 * against the actual values.
 */
class StoryFunctionRegistry(private val parent: StoryFunctionRegistry? = null) : StoryFunctionCatalog {
    private val functions = LinkedHashMap<String, MutableList<Registration>>()

    private class Registration(val signature: StoryFunctionSignature, val handler: StoryFunctionHandler)

    override fun overloads(name: String): List<StoryFunctionSignature>? {
        val own = functions[name]?.map { it.signature }
        val inherited = parent?.overloads(name)
        return when {
            own == null -> inherited
            inherited == null -> own
            else -> inherited + own
        }
    }

    val names: Set<String> get() = functions.keys + (parent?.names ?: emptySet())

    /** Registers one overload. Later registrations win when several fit the same call. */
    fun register(name: String, params: List<StoryParam>, handler: StoryFunctionHandler) {
        val signature = StoryFunctionSignature(name, params)
        val list = functions.getOrPut(name) { mutableListOf() }
        require(list.none { it.signature.params.map(StoryParam::name) == params.map(StoryParam::name) }) {
            "Function '$name' already has an overload with these parameters"
        }
        list += Registration(signature, handler)
    }

    /**
     * The everyday form: parameters spelled with [string]/[number]/..., the body an extension on
     * [StoryCallContext] so `players`, `state` and `actor` are simply in scope.
     *
     * ```kotlin
     * functions.add("wait", number("time")) { args -> delay(args.millis("time")) }
     * ```
     */
    fun add(
        name: String,
        vararg params: StoryParam,
        handler: suspend StoryCallContext.(StoryArguments) -> Unit,
    ) = register(name, params.toList()) { context, args -> context.handler(args) }

    /** Removes every overload of [name]; used when an addon reloads its functions. */
    fun unregister(name: String) {
        functions.remove(name)
    }

    /**
     * Picks the overload for a call. [positional] and [named] are already evaluated, so this is the
     * runtime half of resolution: the compiler guaranteed at least one candidate existed for the
     * literals it could see.
     */
    fun resolve(
        name: String,
        positional: List<StoryValue>,
        named: Map<String, StoryValue>,
    ): ResolvedFunction? {
        val local = functions[name].orEmpty().lastOrNull { StoryArguments.fits(it.signature, positional, named) }
        if (local != null) return ResolvedFunction(local.signature, local.handler)
        return parent?.resolve(name, positional, named)
    }

    fun has(name: String): Boolean = functions.containsKey(name) || parent?.has(name) == true

    /**
     * Types come from the lambda, names from [params]:
     *
     * ```kotlin
     * functions.add<String, Float>("play-video", ["name", "volume"]) { name, volume -> … }
     * ```
     *
     * The last [optional] parameters may be omitted by the story.
     */
    inline fun <reified A> add(
        name: String,
        params: List<String>,
        optional: Int = 0,
        crossinline block: suspend StoryCallContext.(A) -> Unit,
    ) {
        require(params.size == 1) { "Function '$name' declares 1 parameter but got ${params.size} names" }
        register(name, paramList<A, Unit, Unit>(params, optional, 1)) { context, args ->
            context.block(args.convert(params[0]))
        }
    }

    inline fun <reified A, reified B> add(
        name: String,
        params: List<String>,
        optional: Int = 0,
        crossinline block: suspend StoryCallContext.(A, B) -> Unit,
    ) {
        require(params.size == 2) { "Function '$name' declares 2 parameters but got ${params.size} names" }
        register(name, paramList<A, B, Unit>(params, optional, 2)) { context, args ->
            context.block(args.convert(params[0]), args.convert(params[1]))
        }
    }

    inline fun <reified A, reified B, reified C> add(
        name: String,
        params: List<String>,
        optional: Int = 0,
        crossinline block: suspend StoryCallContext.(A, B, C) -> Unit,
    ) {
        require(params.size == 3) { "Function '$name' declares 3 parameters but got ${params.size} names" }
        register(name, paramList<A, B, C>(params, optional, 3)) { context, args ->
            context.block(args.convert(params[0]), args.convert(params[1]), args.convert(params[2]))
        }
    }

    /** Builds the parameter list of a typed registration; the last [optional] ones may be omitted. */
    inline fun <reified A, reified B, reified C> paramList(
        names: List<String>,
        optional: Int,
        arity: Int,
    ): List<StoryParam> {
        val types = listOf(storyTypeOf<A>(), storyTypeOf<B>(), storyTypeOf<C>()).take(arity)
        return names.mapIndexed { index, name ->
            StoryParam(name, types[index], optional = index >= names.size - optional)
        }
    }

    companion object {
        inline fun <reified T> storyTypeOf(): StoryType = when (T::class) {
            String::class -> StoryType.STRING
            Float::class, Int::class, Long::class, Double::class -> StoryType.NUMBER
            Boolean::class -> StoryType.BOOL
            List::class -> StoryType.LIST
            else -> StoryType.ANY
        }
    }
}

class ResolvedFunction(val signature: StoryFunctionSignature, val handler: StoryFunctionHandler)

/** Converts an argument to the type a typed registration asked for. */
inline fun <reified T> StoryArguments.convert(name: String): T {
    val value: Any? = when (T::class) {
        String::class -> string(name)
        Float::class -> number(name)
        Int::class -> int(name)
        Long::class -> millis(name)
        Double::class -> number(name).toDouble()
        Boolean::class -> bool(name)
        List::class -> list(name)
        else -> this[name]
    }
    return value as T
}
