package ru.hollowhorizon.hollowengine.addons.narrative.scene.lang

/** Statements written the way the parser reads them back, for the editors that add them. */
object StoryStatements {
    /** A line of [speaker], or narration without one. [text] is written as is, `{variables}` and `[commands]` included. */
    fun dialogue(speaker: String?, text: String): String {
        val line = lineText(text)
        if (speaker == null) {
            val colon = line.indexOf(':')
            return if (colon > 0 && line.substring(0, colon).all(StoryParser::isNameChar)) {
                line.substring(0, colon) + "\\" + line.substring(colon)
            } else {
                line
            }
        }
        val name = if (speaker.isNotEmpty() && speaker.all(StoryParser::isNameChar)) speaker else "{\"${speaker.replace("\"", "\\\"")}\"}"
        return "$name: $line"
    }

    /** An option of a menu. */
    fun choice(text: String): String = "@choice \"${lineText(text).replace("\"", "\\\"")}\""

    fun label(name: String): String = "# $name"

    fun command(function: String, args: String = ""): String = if (args.isBlank()) "@$function" else "@$function $args"

    /** Text kept on one line: a line break is written as `\n`. */
    fun lineText(text: String): String = text.replace("\r\n", "\n").replace("\n", "\\n")
}
