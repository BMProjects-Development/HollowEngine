package ru.hollowhorizon.hollowengine.client.models.fbx

import java.nio.ByteBuffer

enum class TokenType { OPEN_BRACKET, CLOSE_BRACKET, DATA, COMMA, KEY }

class Token(
    val buffer: ByteBuffer,
    val begin: Int,
    val end: Int,
    val type: TokenType,
    val line: Int,
    val column: Int = -1,
) {
    val isBinary get() = column == -1
    val stringContents get() = text(begin, end)

    private fun text(start: Int, stop: Int) = String(ByteArray(stop - start) { buffer[start + it] }, Charsets.UTF_8)

    private fun requireData() {
        if (type != TokenType.DATA) parseError("expected data token", this)
    }

    val parseAsString: String
        get() {
            requireData()
            if (isBinary) {
                if (buffer[begin].toInt().toChar() != 'S') parseError("expected binary string", this)
                return text(begin + 5, end)
            }
            if (end - begin < 2 || buffer[begin] != '"'.code.toByte() || buffer[end - 1] != '"'.code.toByte()) {
                parseError("expected quoted string", this)
            }
            return text(begin + 1, end - 1)
        }

    val parseAsInt64: Long
        get() {
            requireData()
            if (!isBinary) return stringContents.toLongOrNull() ?: parseError("invalid integer", this)
            return when (buffer[begin].toInt().toChar()) {
                'L' -> buffer.getLong(begin + 1)
                'I' -> buffer.getInt(begin + 1).toLong()
                'Y' -> buffer.getShort(begin + 1).toLong()
                'C' -> (buffer[begin + 1].toInt() and 0xff).toLong()
                else -> parseError("expected binary integer", this)
            }
        }

    val parseAsId get() = parseAsInt64
    val parseAsInt: Int
        get() {
            val value = parseAsInt64
            if (value !in Int.MIN_VALUE..Int.MAX_VALUE) parseError("integer out of range", this)
            return value.toInt()
        }

    val parseAsDim: Int
        get() {
            requireData()
            val value = stringContents
            if (!value.startsWith('*')) parseError("expected array dimension", this)
            return value.drop(1).toIntOrNull()?.takeIf { it >= 0 } ?: parseError("invalid array dimension", this)
        }

    val parseAsFloat: Float get() = parseFloat()

    fun parseFloat(allowNonFinite: Boolean = false): Float {
        requireData()
        val value = if (!isBinary) stringContents.toFloatOrNull() ?: parseError("invalid number", this)
        else when (buffer[begin].toInt().toChar()) {
            'F' -> buffer.getFloat(begin + 1)
            'D' -> buffer.getDouble(begin + 1).toFloat()
            'I', 'L', 'Y', 'C' -> parseAsInt64.toFloat()
            else -> parseError("expected binary number", this)
        }
        return value.takeIf { allowNonFinite || it.isFinite() } ?: parseError("non-finite number", this)
    }

    override fun toString() = if (isBinary) "$type at offset 0x${begin.toString(16)}" else "$type at $line:$column"
}

fun tokenize(outputTokens: ArrayList<Token>, input: ByteBuffer) {
    var cursor = 0
    var line = 1
    var column = 1
    fun charAt(index: Int) = (input[index].toInt() and 0xff).toChar()
    fun advance() {
        val c = charAt(cursor++)
        if (c == '\r' || c == '\n') {
            if (c == '\r' && cursor < input.limit() && charAt(cursor) == '\n') cursor++
            line++
            column = 1
        } else column++
    }
    if (input.limit() >= 3 && input[0] == 0xef.toByte() && input[1] == 0xbb.toByte() && input[2] == 0xbf.toByte()) cursor =
        3
    while (cursor < input.limit()) {
        val c = charAt(cursor)
        if (c.isWhitespace()) {
            advance()
            continue
        }
        if (c == ';') {
            while (cursor < input.limit() && charAt(cursor) != '\r' && charAt(cursor) != '\n') advance()
            continue
        }
        val start = cursor
        val startLine = line
        val startColumn = column
        val punctuation = when (c) {
            '{' -> TokenType.OPEN_BRACKET
            '}' -> TokenType.CLOSE_BRACKET
            ',' -> TokenType.COMMA
            else -> null
        }
        if (punctuation != null) {
            advance()
            outputTokens += Token(input, start, cursor, punctuation, startLine, startColumn)
        } else if (c == '"') {
            advance()
            while (cursor < input.limit() && charAt(cursor) != '"') advance()
            if (cursor == input.limit()) tokenizeError("unterminated string", startLine, startColumn)
            advance()
            outputTokens += Token(input, start, cursor, TokenType.DATA, startLine, startColumn)
        } else {
            while (cursor < input.limit() && !charAt(cursor).isWhitespace() && charAt(cursor) !in ":{},;\"") advance()
            if (cursor == start) tokenizeError("unexpected character '$c'", line, column)
            val stop = cursor
            while (cursor < input.limit() && charAt(cursor) in " \t") advance()
            val type = if (cursor < input.limit() && charAt(cursor) == ':') {
                advance()
                TokenType.KEY
            } else TokenType.DATA
            outputTokens += Token(input, start, stop, type, startLine, startColumn)
        }
    }
}

fun tokenizeError(message: String, line: Int, column: Int): Nothing =
    throw FbxImportException(Util.addLineAndColumn("FBX-Tokenize", message, line, column))
