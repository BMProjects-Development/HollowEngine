package ru.hollowhorizon.hollowengine.client.models.bbmodel

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockExpressions
import ru.hollowhorizon.hollowengine.client.models.bedrock.FloatExpr
import ru.hollowhorizon.hollowengine.client.models.bedrock.MolangDefaults

internal object BBVariablePlaceholders {
    private val namePattern = Regex("[a-zA-Z_][\\w.]*")
    private val controlPattern = Regex("^(slider|toggle|impulse)\\(\\s*(['\"])(.*?)\\2(?:\\s*,[\\s\\S]*)?\\)\\s*;?$", RegexOption.IGNORE_CASE)

    fun convert(file: BlockBenchModel): MolangDefaults {
        val source = file.variablePlaceholders ?: file.animationVariablePlaceholders
        val expressions = linkedMapOf<String, FloatExpr>()
        for (line in source.lineSequence()) {
            val statement = line.trim()
            if (statement.isEmpty() || statement.startsWith("//") || statement.startsWith('#')) continue
            val separator = statement.indexOf('=')
            if (separator < 0) continue
            val name = statement.substring(0, separator).trim().trimEnd(';').trim()
            val value = statement.substring(separator + 1).trim()
            if (!namePattern.matches(name) || value.isEmpty()) {
                HollowEngine.LOGGER.warn("Invalid Blockbench variable placeholder '{}'", line)
                continue
            }
            val control = controlPattern.matchEntire(value)
            val expression = if (control == null) BedrockExpressions.parse(value) else {
                val type = control.groupValues[1].lowercase()
                val id = control.groupValues[3]
                val saved = file.variablePlaceholderButtons.firstOrNull { it.type.equals(type, true) && it.id == id }?.value
                val number = saved?.floatOrNull ?: saved?.booleanOrNull?.let { if (it) 1f else 0f } ?: 0f
                FloatExpr.literal(number.takeIf { it.isFinite() } ?: 0f)
            }
            expressions[normalize(name)] = expression
        }
        return if (expressions.isEmpty()) MolangDefaults.EMPTY else MolangDefaults(expressions)
    }

    private fun normalize(name: String): String {
        val prefix = name.substringBefore('.', "").lowercase()
        val suffix = name.substringAfter('.')
        return when (prefix) {
            "v", "variable" -> "variable.$suffix"
            "q", "query" -> "query.$suffix"
            "t", "temp" -> "temp.$suffix"
            else -> "variable.$name"
        }
    }
}
