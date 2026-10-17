package ru.hollowhorizon.hollowengine.common.scripting.ide

/** Keep the declaration position aligned with the line separators used by the editor. */
fun DefinitionLocation.withNormalizedLineSeparators(): DefinitionLocation {
    val source = text ?: return this
    if ('\r' !in source) return this
    val prefix = source.substring(0, offset.coerceIn(0, source.length))
    return copy(
        text = source.replace("\r\n", "\n").replace('\r', '\n'),
        offset = prefix.replace("\r\n", "\n").length,
    )
}
