package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.ExpressionTokenColors
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiInlineStyle
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiSyntaxHighlighter
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextHighlight
import ru.hollowhorizon.hollowengine.client.ui.widgets.withColor

private val Tokens = Regex(
    """(?<string>"(?:[^"\\\n]|\\.)*")|(?<number>\b\d[\d.]*[fL]?\b)|""" +
            """(?<word>\b(?:import|var|val|by|suspend|fun|if|else|when|for|in|true|false|null|as)\b|@\w+)""",
)

// TODO: Replace with compiler plugin's kotlin highlighter
/** The colors of the Kotlin a story generates, by what its tokens look like: nobody edits it, so nothing has to analyze it. */
internal val StoryKotlinHighlighter = UiSyntaxHighlighter { text ->
    Tokens.findAll(text).map { match ->
        val color: UiColor = when {
            match.groups["string"] != null -> ExpressionTokenColors.String
            match.groups["number"] != null -> ExpressionTokenColors.Number
            else -> ExpressionTokenColors.Keyword
        }
        UiTextHighlight(match.range.first, match.range.last + 1, UiInlineStyle().withColor(color))
    }.toList()
}
