package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

/**
 * A runtime value of the `.story` language. Variables are dynamically typed and limited to
 * [StoryString], [StoryNumber] and [StoryBool]; [StoryList] exists only as an argument literal
 * (`location=[10, 45, 5]`) and cannot be stored in a variable.
 *
 * Numbers are [Float] by design: dialogue counters never need double precision, and sessions keep
 * a checkpoint of every variable after each statement.
 */
sealed interface StoryValue {
    /** The value as presented to players inside `{...}` interpolation. */
    fun display(): String
}

data class StoryString(val value: String) : StoryValue {
    override fun display() = value
}

data class StoryNumber(val value: Float) : StoryValue {
    override fun display(): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
}

data class StoryBool(val value: Boolean) : StoryValue {
    override fun display() = value.toString()
}

data class StoryList(val values: List<StoryValue>) : StoryValue {
    override fun display() = values.joinToString(", ", "[", "]") { it.display() }
}

/**
 * Actors are runtime-only on purpose: an entity cannot be written into a checkpoint, so the script
 * re-supplies them on every start while variables carry over.
 */
data class StoryActor(val name: String, val character: DialogueCharacter) : StoryValue {
    override fun display() = character.name
}

/** True unless the value is `false`, `0` or an empty string; lists are truthy when non-empty. */
fun StoryValue.isTruthy(): Boolean = when (this) {
    is StoryBool -> value
    is StoryNumber -> value != 0f
    is StoryString -> value.isNotEmpty()
    is StoryList -> values.isNotEmpty()
    is StoryActor -> true
}
