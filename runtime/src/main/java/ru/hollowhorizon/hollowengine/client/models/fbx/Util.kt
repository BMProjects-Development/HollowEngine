package ru.hollowhorizon.hollowengine.client.models.fbx

object Util {
    fun addOffset(prefix: String, text: String, offset: Int) =
        "$prefix (offset 0x${Integer.toHexString(offset)}) $text"

    fun addLineAndColumn(prefix: String, text: String, line: Int, column: Int) =
        "$prefix (line $line, col $column) $text"

    fun addTokenText(prefix: String, text: String, tok: Token) = "$prefix ($tok) $text"
}