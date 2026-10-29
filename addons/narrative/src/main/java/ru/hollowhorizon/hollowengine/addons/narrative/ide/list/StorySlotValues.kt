package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import ru.hollowhorizon.hollowengine.addons.narrative.scene.*
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*

/** What a slot of a card can be filled with besides a command: the variables and characters of the story. */
internal object StorySlotValues {
    /**
     * The names an expression of [cst] can read, with the type of their value where the story says it: a variable,
     * typed when every `@set` of it puts in a value of one known type; the parameters of a `@define` and what
     * `@call result=` gets, untyped; the speakers whose names are words, and the player, as characters.
     */
    fun of(cst: StoryFileCst): Map<String, StoryType?> {
        val found = LinkedHashMap<String, StoryType?>()
        fun add(name: String, type: StoryType?) {
            found[name] = if (name in found && found[name] != type) null else type
        }
        for (line in cst.lines) {
            when (val kind = line.kind) {
                is StoryLineKind.Set -> add(kind.variable, typeOf(kind.value))
                is StoryLineKind.Define -> kind.params.forEach { add(it, null) }
                is StoryLineKind.CallDefine -> kind.result?.let { add(it, null) }
                else -> Unit
            }
        }
        val speakers = cst.lines.mapNotNull { (it.kind as? StoryLineKind.Dialogue)?.speaker }
        (listOf(ScenePlayer.PLAYER_ACTOR) + speakers).distinct()
            .filter { name -> name !in found && name.all(StoryExpressions::isIdentPart) }
            .forEach { found[it] = StoryType.ACTOR }
        return found
    }

    /** The type of what [expression] gives, when it is a literal or a call of a command that says what it gives. */
    private fun typeOf(expression: StoryExpression): StoryType? {
        expression.wholeCall()?.let { return signature(it.call)?.returns }
        return when (expression.constant) {
            is StoryString -> StoryType.STRING
            is StoryNumber -> StoryType.NUMBER
            is StoryBool -> StoryType.BOOL
            is StoryList -> StoryType.LIST
            is StoryActor, is StoryEntity -> StoryType.ACTOR
            null -> null
        }
    }
}
