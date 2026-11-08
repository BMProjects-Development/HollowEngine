package ru.hollowhorizon.hollowengine.addons.narrative.graph

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinTypeInfo
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.time.Duration


sealed interface StoryFieldForm {
    fun shown(expression: String, type: KType?): String = expression
    fun written(text: String, type: KType?): String = text.trim()

    val choices: List<String> get() = emptyList()

    data object Literal : StoryFieldForm {
        override fun shown(expression: String, type: KType?) = StoryLiterals.text(expression, type)

        override fun written(text: String, type: KType?) = StoryLiterals.expression(text, type)
    }

    data object Code : StoryFieldForm

    data object Template : StoryFieldForm {
        override fun shown(expression: String, type: KType?) = StoryTemplates.content(expression)

        override fun written(text: String, type: KType?) = StoryTemplates.expression(text)
    }

    data class Numeric(val whole: Boolean, val suffix: String = "") : StoryFieldForm {
        fun write(value: Double): String {
            if (whole) return value.toLong().toString()
            val plain = value.toBigDecimal().stripTrailingZeros().toPlainString()
            return when {
                suffix.isNotEmpty() -> plain + suffix
                '.' in plain -> plain
                else -> "$plain.0"
            }
        }

        fun read(expression: String): Double? {
            val written = expression.trim()
            return (NUMBER_ENDS.firstOrNull { written.endsWith(it) }?.let { written.removeSuffix(it) } ?: written)
                .toDoubleOrNull()
        }

        private companion object {
            val NUMBER_ENDS = listOf(".toDouble()", ".toFloat()", ".toShort()", ".toByte()", "f", "F", "L")
        }
    }

    data object Flag : StoryFieldForm

    data class Vector(val helper: String) : StoryFieldForm

    data class Constant(val qualified: String, val short: String, val names: List<String>) : StoryFieldForm {
        constructor(kind: KClass<*>) : this(
            StoryKotlinTypes.className(kind).orEmpty(),
            kind.qualifiedName.orEmpty().removePrefix(kind.java.packageName + "."),
            kind.java.enumConstants.orEmpty().map { (it as Enum<*>).name },
        )

        override val choices: List<String> get() = names.map { "$short.$it" }

        override fun shown(expression: String, type: KType?): String {
            val written = expression.trim()
            return names.firstOrNull { written == "$qualified.$it" }?.let { "$short.$it" } ?: written
        }

        override fun written(text: String, type: KType?): String {
            val typed = text.trim()
            return names.firstOrNull { typed == "$short.$it" || typed == it }?.let { "$qualified.$it" } ?: typed
        }
    }

    companion object {
        fun of(type: KType?): StoryFieldForm {
            val kind = type?.classifier as? KClass<*> ?: return Code
            return when {
                kind == String::class || kind == CharSequence::class -> Template
                kind == Boolean::class -> Flag
                kind == Int::class || kind == Long::class || kind == Short::class || kind == Byte::class -> Numeric(true)
                kind == Float::class -> Numeric(false, "f")
                kind == Double::class || kind == Number::class -> Numeric(false)
                kind == Vec3::class -> Vector(StoryLiterals.POSITION)
                kind == BlockPos::class -> Vector(StoryLiterals.BLOCK)
                kind.java.isEnum -> Constant(kind)
                kind == Duration::class || kind == Any::class -> Code
                StoryLiterals.hasPlainForm(kind) -> Literal
                else -> Code
            }
        }

        fun ofName(name: String?, info: KotlinTypeInfo? = null): StoryFieldForm {
            val classifier = info?.classifier
            val entries = info?.enumEntries
            if (classifier != null && entries != null) {
                return Constant(classifier, StoryPinType.shortName(classifier), entries)
            }
            return of(typeNamed(classifier ?: name))
        }

        fun typeNamed(name: String?): KType? = StoryRuntimeTypes.named(name)
    }
}

object StoryTemplates {
    private const val RAW = "\"\"\""
    private const val QUOTES = $$"\"\"${'\"'}"
    private const val DOLLAR = $$"${'$'}"
    private const val SHOWN_DOLLAR = "\\$"

    fun content(expression: String): String {
        val written = expression.trim()
        if (written.isEmpty()) return ""
        if (written.length >= 2 * RAW.length && written.startsWith(RAW) && written.endsWith(RAW)) {
            return written.substring(RAW.length, written.length - RAW.length).replace(QUOTES, RAW)
                .replace(DOLLAR, SHOWN_DOLLAR)
        }
        return quoted(written) ?: $$"${$$written}"
    }

    fun expression(content: String): String =
        if (content.isEmpty()) "" else RAW + content.replace(RAW, QUOTES).replace(SHOWN_DOLLAR, DOLLAR) + RAW

    fun analyzed(content: String): String = content.replace(RAW, "\"\"'").replace(SHOWN_DOLLAR, "$\\")

    private fun quoted(written: String): String? {
        if (written.length < 2 || !written.startsWith("\"") || !written.endsWith("\"")) return null
        val inner = written.substring(1, written.length - 1)
        return buildString {
            var index = 0
            while (index < inner.length) {
                val char = inner[index++]
                when {
                    char == '"' -> return null
                    char == '$' && inner.getOrNull(index) == '{' -> {
                        val end = insertEnd(inner, index) ?: return null
                        append(inner, index - 1, end)
                        index = end
                    }

                    char != '\\' -> append(char)
                    else -> when (val next = inner.getOrNull(index++) ?: return null) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        '$' -> append(if (startsName(inner.getOrNull(index))) SHOWN_DOLLAR else "$")
                        else -> append(next)
                    }
                }
            }
        }
    }

    private fun insertEnd(text: String, open: Int): Int? {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index++]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun startsName(char: Char?) = char != null && (char == '{' || char == '_' || char.isLetter())
}
