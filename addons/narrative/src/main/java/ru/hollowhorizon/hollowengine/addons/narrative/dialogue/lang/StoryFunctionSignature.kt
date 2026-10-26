package ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.*

enum class StoryType {
    STRING, NUMBER, BOOL, LIST, ACTOR, ANY;

    fun accepts(value: StoryValue): Boolean = when (this) {
        ANY -> true
        STRING -> value is StoryString || value is StoryNumber || value is StoryBool
        NUMBER -> value is StoryNumber || (value is StoryString && value.value.toFloatOrNull() != null)
        BOOL -> value is StoryBool
        LIST -> value is StoryList
        ACTOR -> value is StoryActor || value is StoryString
    }
}

/**
 * One parameter. A parameter is required unless it has a [default].
 */
data class StoryParam(
    val name: String,
    val type: StoryType = StoryType.ANY,
    val optional: Boolean = false,
    val default: StoryValue? = null,
    /**
     * The values this parameter accepts, when they are a known set, HUD layer names, animation
     * play modes, etc. The editor offers them; nothing checks against them, since a story may
     * legitimately name something the engine only learns about later.
     */
    val suggestions: List<String> = emptyList(),
) {
    /** How the editor writes it: `volume=1.0`, or `volume?` when the default is not known. */
    fun display(): String = when {
        default != null -> "$name=${default.display()}"
        optional -> "$name?"
        else -> name
    }
}

/** One overload of a story function. Overloads of the same name are told apart by arity and types. */
data class StoryFunctionSignature(
    val name: String,
    val params: List<StoryParam>,
) {

    init {
        require(params.map { it.name }.toSet().size == params.size) {
            "Duplicate parameter name in signature of '$name'"
        }
    }
}

fun string(name: String, optional: Boolean = false, default: String? = null) =
    StoryParam(name, StoryType.STRING, optional || default != null, default?.let(::StoryString))

fun number(name: String, optional: Boolean = false, default: Float? = null) =
    StoryParam(name, StoryType.NUMBER, optional || default != null, default?.let(::StoryNumber))

fun boolean(name: String, optional: Boolean = false, default: Boolean? = null) =
    StoryParam(name, StoryType.BOOL, optional || default != null, default?.let(::StoryBool))

fun list(name: String, optional: Boolean = false) = StoryParam(name, StoryType.LIST, optional)

fun actor(name: String, optional: Boolean = false) = StoryParam(name, StoryType.ACTOR, optional)

fun any(name: String, optional: Boolean = false, suggestions: List<String> = emptyList()) =
    StoryParam(name, StoryType.ANY, optional, suggestions = suggestions)

fun signature(name: String, vararg params: StoryParam) = StoryFunctionSignature(name, params.toList())

/**
 * What the compiler knows about the available functions. The runtime registry implements it; the IDE
 * can pass a permissive implementation while an addon's functions are not loaded yet.
 */
interface StoryFunctionCatalog {
    /** Overloads of [name], or null when the name is unknown (which the compiler reports as an error). */
    fun overloads(name: String): List<StoryFunctionSignature>?

    /** The names the editor offers; empty when the catalog cannot list them. */
    val names: Collection<String> get() = emptyList()

    companion object {
        /** Accepts every call, used when function knowledge is unavailable. */
        val PERMISSIVE = object : StoryFunctionCatalog {
            override fun overloads(name: String) = emptyList<StoryFunctionSignature>()
        }
    }
}

/**
 * Declares a story functions catalog:
 *
 * ```kotlin
 * storyCatalog {
 *     "play-video"(string("name"))
 *     "play-video"(string("name"), number("volume"))
 *     "wait"(number("time"))
 * }
 * ```
 */
fun storyCatalog(block: StoryCatalogBuilder.() -> Unit): StoryFunctionCatalog =
    StoryCatalogBuilder().apply(block).build()

class StoryCatalogBuilder {
    private val functions = LinkedHashMap<String, MutableList<StoryFunctionSignature>>()

    /** Adds one overload of the receiver function. */
    operator fun String.invoke(vararg params: StoryParam) {
        functions.getOrPut(this) { mutableListOf() } += StoryFunctionSignature(this, params.toList())
    }

    internal fun build(): StoryFunctionCatalog {
        val snapshot = functions.mapValues { (_, overloads) -> overloads.toList() }
        return object : StoryFunctionCatalog {
            override fun overloads(name: String) = snapshot[name]

            override val names: Collection<String> get() = snapshot.keys
        }
    }
}
