package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.KType

/**
 * What an options object such as `MoveOptions` a parameter belongs to: [parameter] of the function, built by [constructor].
 *
 * @param keyed whether [constructor] takes the fields as pairs `"name" to value` rather than named arguments, for an
 * object whose fields are only known at runtime, such as a component.
 */
data class StoryParamGroup(val parameter: String, val constructor: String, val keyed: Boolean = false)

enum class StoryParamMeaning {
    /** A clip of the model of the entity the call is made on. */
    ANIMATION,

    /** The model of the entity the call gives back. */
    MODEL,

    /** The author's id of the NPC the call gives back. */
    NPC_ID,
}

/**
 * One parameter of a command as the editors fill it.
 *
 * @param type its Kotlin type, which says how a value typed into a field is written in Kotlin.
 * @param optional whether the call can leave it out.
 * @param suggestions the values it takes, when they are a known set; nothing checks against them.
 * @param title what the editor calls it instead of [name], a lang key or plain text.
 * @param description what it is for, a lang key or plain text.
 * @param receiver whether it is what the function is called on.
 * @param group the options object it is a field of.
 * @param implicit the expression it takes when nobody fills it.
 * @param widget what edits it instead of a plain field, such as `color`.
 * @param assets the ends of the paths of the files it names, when it names one.
 * @param meaning what its value names, when the editor can check that.
 * @param fallback what a call that leaves it out or blank acts as having, when the editor needs to know.
 * @param registry the registry it names an entry of, such as `minecraft:mob_effect`, for the editor to complete.
 * @param tags whether it takes a `#tag` of [registry] too.
 */
data class StoryParam(
    val name: String,
    val type: KType? = null,
    val optional: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val title: String? = null,
    val description: String? = null,
    val receiver: Boolean = false,
    val group: StoryParamGroup? = null,
    val implicit: String? = null,
    val assets: List<String> = emptyList(),
    val widget: String? = null,
    val meaning: StoryParamMeaning? = null,
    val fallback: String? = null,
    val registry: String? = null,
    val tags: Boolean = false,
)

/**
 * A command as the editors show it.
 *
 * @param name its id.
 * @param title what the editor calls it, a lang key or plain text.
 * @param description what it does, a lang key or plain text.
 * @param icon a `namespace:textures/...` icon the editor shows beside it.
 * @param category the heading the editor lists it under, a lang key or plain text.
 * @param categoryColor the `#RRGGBB` nodes of the category take, or null to be given one.
 * @param returns the type of what a call gives back, or null when it gives nothing.
 * @param block whether a call takes the flow from [StoryPins.BODY] as its last argument, such as `temporary { }`.
 * @param plainBlock whether that body is an ordinary lambda, as in `temporary { }`, where no stage can be called.
 * @param hidden whether stories that call it still work while nothing offers it for new ones.
 * @param bound the arguments every call passes as they are, by parameter, such as the id of the component a generated
 * command sets up.
 */
data class StoryFunctionSignature(
    val name: String,
    val function: String,
    val params: List<StoryParam>,
    val title: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val category: String? = null,
    val categoryColor: String? = null,
    val returns: String? = null,
    val block: Boolean = false,
    val plainBlock: Boolean = false,
    val hidden: Boolean = false,
    val bound: Map<String, String> = emptyMap(),
) {
    /** A new call of this command. */
    fun newCall(id: String) = StoryNode.Call(id, action = name)
}

/** The expression [param] has in this call, or null when it is left out. */
fun StoryNode.Call.argument(param: StoryParam): String? = when (val group = param.group) {
    null -> arguments[param.name]
    else -> groups[group.parameter]?.arguments?.get(param.name)
}

/** This call with [param] set to [value]; a blank one leaves it out. */
fun StoryNode.Call.with(param: StoryParam, value: String): StoryNode.Call {
    fun Map<String, String>.set() = if (value.isBlank()) this - param.name else this + (param.name to value)
    val group = param.group ?: return copy(arguments = arguments.set())
    val written = groups[group.parameter] ?: StoryGroup(group.constructor)
    return copy(groups = groups + (group.parameter to written.copy(arguments = written.arguments.set())))
}
