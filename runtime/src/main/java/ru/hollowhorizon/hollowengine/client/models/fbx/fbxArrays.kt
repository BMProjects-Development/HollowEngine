package ru.hollowhorizon.hollowengine.client.models.fbx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.roundToLong

data class BinaryArray(val type: Char, val count: Int, val data: ByteBuffer, val source: Token)

fun readBinaryArray(token: Token, types: Set<Char>): BinaryArray {
    val source = token.buffer
    if (token.end - token.begin < 13) parseError("truncated array header", token)
    val type = source[token.begin].toInt().toChar()
    if (type !in types) parseError("unexpected array type '$type'", token)
    val count = source.getInt(token.begin + 1)
    val encoding = source.getInt(token.begin + 5)
    val length = source.getInt(token.begin + 9)
    val stride = if (type == 'd' || type == 'l') 8 else 4
    val fullLength = count.toLong() * stride
    if (count < 0 || fullLength > Int.MAX_VALUE || length < 0 || length != token.end - token.begin - 13) {
        parseError("invalid array length", token)
    }
    val input = ByteArray(length) { source[token.begin + 13 + it] }
    val bytes = when (encoding) {
        0 -> {
            if (length.toLong() != fullLength) parseError("uncompressed array length mismatch", token)
            input
        }
        1 -> {
            val inflater = Inflater()
            try {
                inflater.setInput(input)
                val output = ByteArray(fullLength.toInt())
                var offset = 0
                val overflow = ByteArray(1)
                while (!inflater.finished()) {
                    val read = if (offset < output.size) inflater.inflate(output, offset, output.size - offset)
                    else inflater.inflate(overflow)
                    if (offset == output.size && read > 0) parseError("inflated array exceeds declared size", token)
                    offset += read
                    if (read == 0 && !inflater.finished()) parseError("incomplete compressed array", token)
                }
                if (offset != output.size || inflater.remaining != 0) parseError("compressed array length mismatch", token)
                output
            } catch (ex: DataFormatException) {
                throw FbxImportException(Util.addTokenText("FBX-Parser", "invalid compressed array", token), ex)
            } finally {
                inflater.end()
            }
        }
        else -> parseError("unknown array encoding $encoding", token)
    }
    return BinaryArray(type, count, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), token)
}

internal fun BinaryArray.readInteger(round: Boolean = false): Long = when (type) {
    'i' -> data.int.toLong()
    'l' -> data.long
    else -> {
        val number = if (type == 'd') data.double else data.float.toDouble()
        if (!number.isFinite() || number < Long.MIN_VALUE.toDouble() || number >= 9223372036854775808.0 || (!round && number.toLong().toDouble() != number)) {
            parseError("non-integral or out-of-range integer array value: $number", source)
        }
        if (round) number.roundToLong() else number.toLong()
    }
}
