package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.KType

/** What an options object such as `MoveOptions` a parameter belongs to: [parameter] of the function, built by [constructor]. */
data class StoryParamGroup(val parameter: String, val constructor: String)

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
 * @param assets the ends of the paths of the files it names, when it names one.
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
 * @param hidden whether stories that call it still work while nothing offers it for new ones.
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
    val hidden: Boolean = false,
) {
    /** A new call of this command. */
    fun newCall(id: String) = params.filter { it.implicit != null }
        .fold(StoryNode.Call(id, action = name)) { call, param -> call.with(param, param.implicit.orEmpty()) }
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
