package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.KType

/**
 * What an options object such as `MoveOptions` a parameter belongs to: [parameter] of the function, built by [constructor].
 *
 * @param keyed whether [constructor] takes the fields as pairs `"name" to value` rather than named arguments, for an
 * object whose fields are only known at runtime, such as a component.
 * @param typed the function a value of a [keyed] field passes through with type of the field as its type argument.
 */
data class StoryParamGroup(
    val parameter: String,
    val constructor: String,
    val keyed: Boolean = false,
    val typed: String? = null,
)

/**
 * One parameter of a command as the editors fill it.
 *
 * @param type its Kotlin type, which says how a value typed into a field is written in Kotlin.
 * @param optional whether the call can leave it out.
 * @param title what the editor calls it instead of [name], a lang key or plain text.
 * @param description what it is for, a lang key or plain text.
 * @param receiver whether it is what the function is called on.
 * @param group the options object it is a field of.
 * @param implicit the expression it takes when nobody fills it.
 * @param traits what else the editors know of it, such as the values it takes or the registry it names an entry of.
 */
data class StoryParam(
    val name: String,
    val type: KType? = null,
    val optional: Boolean = false,
    val title: String? = null,
    val description: String? = null,
    val receiver: Boolean = false,
    val group: StoryParamGroup? = null,
    val implicit: String? = null,
    val traits: List<StoryParamTrait> = emptyList(),
) {
    inline fun <reified T : StoryParamTrait> trait(): T? = traits.firstNotNullOfOrNull { it as? T }

    inline fun <reified T : StoryParamTrait> has(): Boolean = traits.any { it is T }

    /** The values it takes, when they are a known set. */
    val choices: List<String> get() = trait<StoryParamTrait.Choices>()?.values.orEmpty()
}

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
