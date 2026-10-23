package ru.hollowhorizon.hollowengine.client.models.fbx

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun tokenizeError(message: String, offset: Int): Nothing =
    throw FbxImportException(Util.addOffset("FBX-Tokenize", message, offset))

fun tokenizeBinary(outputTokens: ArrayList<Token>, input: ByteBuffer) {
    BinaryTokenizer(input.order(ByteOrder.LITTLE_ENDIAN), outputTokens).read()
}

private class BinaryTokenizer(val input: ByteBuffer, val tokens: ArrayList<Token>) {
    private var wide = false
    private val recordSize get() = if (wide) 25 else 13

    fun read() {
        val signature = "Kaydara FBX Binary  \u0000\u001a\u0000".toByteArray(Charsets.US_ASCII)
        requireBytes(signature.size + 4, input.limit())
        if (signature.indices.any { input[it] != signature[it] }) fail("invalid binary signature")
        input.position(signature.size)
        wide = input.int >= 7500
        while (readNode(input.limit(), 0)) { /* The null record separates nodes from the footer. */ }
    }

    private fun readNode(parentEnd: Int, depth: Int): Boolean {
        if (depth > 256) fail("maximum nesting depth exceeded")
        requireBytes(recordSize, parentEnd)
        val start = input.position()
        val end = word()
        val count = word()
        val length = word()
        val nameLength = input.get().toInt() and 0xff
        if (end == 0) {
            if (count != 0 || length != 0 || nameLength != 0) fail("invalid null record")
            return false
        }
        if (end < input.position() || end > parentEnd) fail("node end is outside parent scope")
        requireBytes(nameLength, end)
        val nameStart = input.position()
        skip(nameLength, end)
        if (nameLength == 0) fail("empty node name")
        tokens += Token(input, nameStart, input.position(), TokenType.KEY, start)
        requireBytes(length, end)
        val propertyEnd = input.position() + length
        if (count > length) fail("property count exceeds property list length")
        repeat(count) { index ->
            val begin = input.position()
            readProperty(propertyEnd)
            tokens += Token(input, begin, input.position(), TokenType.DATA, begin)
            if (index + 1 < count) punctuation(TokenType.COMMA)
        }
        if (input.position() != propertyEnd) fail("property list length mismatch")
        if (input.position() < end) {
            if (end - input.position() < recordSize) fail("missing node sentinel")
            punctuation(TokenType.OPEN_BRACKET)
            val childrenEnd = end - recordSize
            while (input.position() < childrenEnd) {
                if (!readNode(childrenEnd, depth + 1)) fail("unexpected null child record")
            }
            if (input.position() != childrenEnd) fail("child scope length mismatch")
            requireBytes(recordSize, end)
            repeat(recordSize) { if (input.get() != 0.toByte()) fail("invalid node sentinel") }
            punctuation(TokenType.CLOSE_BRACKET)
        }
        if (input.position() != end) fail("node length mismatch")
        return true
    }

    private fun word(): Int {
        val value = if (wide) input.long else input.int.toLong() and 0xffffffffL
        if (value !in 0..Int.MAX_VALUE.toLong()) fail("size or offset exceeds supported buffer size")
        return value.toInt()
    }

    private fun readProperty(end: Int) {
        requireBytes(1, end)
        when (val type = input.get().toInt().toChar()) {
            'Y' -> skip(2, end)
            'C' -> skip(1, end)
            'I', 'F' -> skip(4, end)
            'L', 'D' -> skip(8, end)
            'S', 'R' -> {
                requireBytes(4, end)
                skip(input.int, end)
            }

            'b', 'c', 'f', 'd', 'i', 'l' -> {
                requireBytes(12, end)
                val count = input.int
                val encoding = input.int
                val length = input.int
                val stride = when (type) {
                    'd', 'l' -> 8; 'f', 'i' -> 4; else -> 1
                }
                if (count < 0 || count.toLong() * stride > Int.MAX_VALUE) fail("invalid array size")
                if (encoding !in 0..1) fail("unknown array encoding: $encoding")
                if (encoding == 0 && count.toLong() * stride != length.toLong()) fail("array length mismatch")
                skip(length, end)
            }

            else -> fail("unknown property type '$type'")
        }
    }

    private fun punctuation(type: TokenType) {
        val offset = input.position()
        tokens += Token(input, offset, offset, type, offset)
    }

    private fun requireBytes(count: Int, end: Int) {
        if (count < 0 || count > end - input.position()) fail("truncated binary data")
    }

    private fun skip(count: Int, end: Int) {
        requireBytes(count, end)
        input.position(input.position() + count)
    }

    private fun fail(message: String): Nothing = tokenizeError(message, input.position())
}
