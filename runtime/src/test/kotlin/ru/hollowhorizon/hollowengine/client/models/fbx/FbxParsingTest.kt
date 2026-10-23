package ru.hollowhorizon.hollowengine.client.models.fbx

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FbxParsingTest {
    private fun scope(text: String): Scope {
        val tokens = arrayListOf<Token>()
        tokenize(tokens, ByteBuffer.wrap(text.toByteArray()))
        return Parser(tokens, false).root
    }

    @Test
    fun `EOF whitespace comments and repeated property reads preserve data`() {
        val parsed = scope("; comment\r\nValues : *3 { a: -1, 2e1, +3 }; end\r\nName: \"Юникод\"")
        val array = parsed["Values"]!!
        val values = arrayListOf<Float>()
        array.parseFloatsDataArray(values)
        assertEquals(listOf(-1f, 20f, 3f), values)
        array.parseFloatsDataArray(values)
        assertEquals(listOf(-1f, 20f, 3f), values)
        assertEquals("Юникод", parsed["Name"]!![0].parseAsString)
    }

    @Test
    fun `documents retain their own buffers across interleaved parses`() {
        val first = scope("Value: 123")
        val second = scope("Value: 456")
        assertEquals(123, first["Value"]!![0].parseAsInt)
        assertEquals(456, second["Value"]!![0].parseAsInt)
    }

    @Test
    fun `invalid text fails with a parser error`() {
        for (text in listOf("Value: \"unclosed", "Node: {", "Value: 1,,2", "Value: 1,", "}")) {
            assertFailsWith<IllegalArgumentException>(text) { scope(text) }
        }
        assertFailsWith<IllegalArgumentException> { scope("Values: *2 { a: 1 }")["Values"]!!.parseFloatsDataArray(arrayListOf()) }
    }

    @Test
    fun `triangulation handles concave faces and both windings`() {
        val polygon = listOf(Vec3f(0f, 0f, 0f), Vec3f(2f, 0f, 0f), Vec3f(2f, 2f, 0f), Vec3f(1f, 1f, 0f), Vec3f(0f, 2f, 0f))
        for (points in listOf(polygon, polygon.reversed())) {
            val triangles = triangulatePolygon(points)
            assertEquals(0, triangles.size % 3)
            val area = triangles.chunked(3).sumOf { triangle ->
                val (a, b, c) = triangle.map { points[it] }
                ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)).toDouble() / 2
            }
            assertEquals(if (points === polygon) 3.0 else -3.0, area)
            triangles.chunked(3).forEach { assertNotEquals(it[0], it[1]) }
        }
    }
}
