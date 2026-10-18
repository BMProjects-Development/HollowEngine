package ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings

import java.io.InputStream

interface Mapped {
    val names: List<String>
}

sealed interface Commented {
    val comments: List<String>
}

data class MappedClass(
    override val names: List<String>,
    override val comments: List<String>,
    val fields: List<MappedField>,
    val methods: List<MappedMethod>,
) : Mapped, Commented

data class MappedMethod(
    override val names: List<String>,
    override val comments: List<String>,
    val desc: String,
    val parameters: List<MappedParameter>,
    val variables: List<MappedLocal>,
) : Mapped, Commented

data class MappedLocal(
    val index: Int,
    val startOffset: Int,
    val lvtIndex: Int,
    override val names: List<String>,
) : Mapped

data class MappedParameter(
    override val names: List<String>,
    val index: Int,
) : Mapped

data class MappedField(
    override val names: List<String>,
    override val comments: List<String>,
    val desc: String?,
) : Mapped, Commented

interface Mappings {
    val namespaces: List<String>
    val classes: List<MappedClass>

    companion object {
        val EMPTY = object : Mappings {
            override val namespaces: List<String> = emptyList()
            override val classes: List<MappedClass> = emptyList()
        }
    }
}

sealed interface MappingsFormat<T : Mappings> {
    fun detect(lines: List<String>): Boolean
    fun parse(lines: List<String>): T
    fun write(mappings: T): List<String>
}

object MappingsLoader {
    val allMappingsFormats: List<MappingsFormat<*>> = listOf(
        TinyMappingsV1Format, TinyMappingsV2Format,
        TSRGV1MappingsFormat, TSRGV2MappingsFormat
    )

    fun findMappingsFormat(lines: List<String>): MappingsFormat<*> =
        allMappingsFormats.find { it.detect(lines) } ?: error("No format was found for mappings")

    fun loadMappings(lines: List<String>): Mappings = findMappingsFormat(lines).parse(lines)
    fun loadMappings(stream: InputStream): Mappings = loadMappings(stream.use { it.bufferedReader().readLines() })
}

internal fun String.splitAround(c: Char): Pair<String, String> = substringBefore(c) to substringAfter(c, "")