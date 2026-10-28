package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import net.minecraft.locale.Language
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryParam
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * What the editor calls commands and their parameters. An action's own `@EditorName` or `@Param` comes first, then
 * the keys `<namespace>.story.command.<command>` and `.<command>.<param>` of the addon that declares it, then the key
 * of a parameter of that name in any command, `hollowengine.story.param.<param>`, and the name as written last. Each
 * of them has its `.description` beside it. Built-in statements are named the same way, in the narrative namespace.
 */
internal object StoryTexts {
    /** The namespace of the built-in statements, whose language this is. */
    private const val BUILTIN = "hollowengine-narrative"
    private const val PARAM = "hollowengine.story.param"

    /**
     * @param command the command as written, or the id of a built-in statement.
     * @param keyword how it is written, for when nothing names it.
     */
    fun title(command: String, signature: StoryFunctionSignature? = null, keyword: String = command): String =
        signature?.title?.lang ?: known(key(id(command, signature))) ?: "@$keyword"

    fun description(command: String, signature: StoryFunctionSignature? = null): String? =
        signature?.description?.lang ?: known(key(id(command, signature)) + ".description")

    /** @param command the id of the command, as its signature names it. */
    fun paramTitle(command: String, param: StoryParam): String =
        param.title?.lang ?: field(command, param.name) ?: param.name

    /** What a parameter is for and what it is when left out. */
    fun paramHelp(command: String, param: StoryParam): String? {
        val description = param.description?.lang ?: fieldDescription(command, param.name)
        val default = param.default?.let { storyLang("param.default").format(it.display()) }
        return listOfNotNull(description, default).joinToString("\n").ifEmpty { null }
    }

    /** The name of the part [name] of [command], a parameter or a field of a built-in statement, or null if none. */
    fun field(command: String, name: String): String? = known("${key(command)}.$name") ?: known("$PARAM.$name")

    fun fieldDescription(command: String, name: String): String? =
        known("${key(command)}.$name.description") ?: known("$PARAM.$name.description")

    /** The key of [id]: `ns:path` is named by `ns.story.command.path`. */
    private fun key(id: String) = "${id.substringBefore(':', BUILTIN)}.story.command.${id.substringAfter(':')}"

    private fun id(command: String, signature: StoryFunctionSignature?) = signature?.name ?: command

    private fun known(key: String): String? = key.takeIf(Language.getInstance()::has)?.lang
}
