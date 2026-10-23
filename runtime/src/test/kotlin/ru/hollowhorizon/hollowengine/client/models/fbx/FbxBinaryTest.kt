package ru.hollowhorizon.hollowengine.client.models.fbx

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FbxBinaryTest {
    @ParameterizedTest
    @ValueSource(ints = [7400, 7500, 7700])
    fun `binary node layouts decode compressed and plain arrays`(version: Int) {
        val bytes = binary(version, listOf(
            Node("Values", array('d', 3, doubles(1.5, -2.0, 3.25), true)),
            Node("Integers", array('i', 2, ints(-1, 1234567), false)),
            Node("Empty", array('f', 0, byteArrayOf(), true)),
            Node("Parent", children = listOf(Node("LongName".repeat(20), string("mesh\u0000\u0001Model")))),
        ))
        val root = parse(bytes)
        val floats = arrayListOf<Float>()
        root["Values"]!!.parseFloatsDataArray(floats)
        assertEquals(listOf(1.5f, -2f, 3.25f), floats)
        val integers = arrayListOf<Int>()
        root["Integers"]!!.parseIntsDataArray(integers)
        assertEquals(listOf(-1, 1234567), integers)
        root["Empty"]!!.parseFloatsDataArray(floats)
        assertEquals(emptyList(), floats)
        assertEquals("mesh\u0000\u0001Model", root["Parent"]!!.scope["LongName".repeat(20)]!![0].parseAsString)
        for (length in listOf(10, 26, bytes.size - (if (version >= 7500) 25 else 13) - 1)) {
            assertFailsWith<IllegalArgumentException> { parse(bytes.copyOf(length)) }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [7400, 7500])
    fun `corrupt compressed arrays are rejected`(version: Int) {
        val data = array('d', 3, doubles(1.0, 2.0, 3.0), true)
        data[data.lastIndex] = (data.last().toInt() xor 0xff).toByte()
        val root = parse(binary(version, listOf(Node("Broken", data))))
        assertFailsWith<IllegalArgumentException> { root["Broken"]!!.parseFloatsDataArray(arrayListOf()) }
    }

    private fun parse(bytes: ByteArray): Scope {
        val tokens = arrayListOf<Token>()
        tokenizeBinary(tokens, ByteBuffer.wrap(bytes))
        return Parser(tokens, true).root
    }

    private data class Node(val name: String, val property: ByteArray? = null, val children: List<Node>? = null)

    private fun binary(version: Int, nodes: List<Node>): ByteArray {
        val wide = version >= 7500
        val sentinel = ByteArray(if (wide) 25 else 13)
        val output = ByteArrayOutputStream()
        output.write("Kaydara FBX Binary  \u0000\u001a\u0000".toByteArray())
        output.write(ints(version))
        fun write(node: Node) {
            val start = output.size()
            output.write(sentinel)
            val name = node.name.toByteArray()
            output.write(name)
            node.property?.let { output.write(it) }
            node.children?.let { children -> children.forEach { write(it) }; output.write(sentinel) }
            val bytes = output.toByteArray()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).position(start)
            if (wide) header.putLong(output.size().toLong()).putLong(if (node.property == null) 0L else 1L).putLong(node.property?.size?.toLong() ?: 0L)
            else header.putInt(output.size()).putInt(if (node.property == null) 0 else 1).putInt(node.property?.size ?: 0)
            header.put(name.size.toByte())
            output.reset()
            output.write(bytes)
        }
        nodes.forEach { write(it) }
        output.write(sentinel)
        return output.toByteArray()
    }

    private fun array(type: Char, count: Int, bytes: ByteArray, compressed: Boolean): ByteArray {
        val data = if (compressed) {
            val deflater = Deflater()
            try {
                deflater.setInput(bytes)
                deflater.finish()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
                output.toByteArray()
            } finally { deflater.end() }
        } else bytes
        return byteArrayOf(type.code.toByte()) + ints(count, if (compressed) 1 else 0, data.size) + data
    }

    private fun string(value: String): ByteArray = value.toByteArray().let { byteArrayOf('S'.code.toByte()) + ints(it.size) + it }
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
    private fun doubles(vararg values: Double) = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putDouble(it) } }.array()
}
