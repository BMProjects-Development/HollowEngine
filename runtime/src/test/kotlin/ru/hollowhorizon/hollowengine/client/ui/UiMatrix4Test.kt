package ru.hollowhorizon.hollowengine.client.ui

import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.assertEquals

class UiMatrix4Test {
    @Test
    fun `composed transform matches the generic multiplication path`() {
        val transform = UiTransform(
            translate = UiVec3(4f, -3f, 2f),
            rotate = UiVec3(17f, -23f, 41f),
            scale = UiVec3(1.4f, 0.75f, 2f),
            perspective = 450f,
        )
        val pivot = UiVec3(31f, 19f, 3f)
        val actual = transform.matrix(pivot, 7f, 11f, -5f)
        val expected = UiMatrix4.translation(11f, 8f, -3f) *
                UiMatrix4.translation(pivot.x, pivot.y, pivot.z) *
                UiMatrix4.perspective(450f) *
                UiMatrix4.rotationX(17f * PI.toFloat() / 180f) *
                UiMatrix4.rotationY(-23f * PI.toFloat() / 180f) *
                UiMatrix4.rotationZ(41f * PI.toFloat() / 180f) *
                UiMatrix4.scale(1.4f, 0.75f, 2f) *
                UiMatrix4.translation(-pivot.x, -pivot.y, -pivot.z)

        for (point in listOf(UiVec3(), UiVec3(10f, 5f, 1f), UiVec3(-8f, 27f, -2f))) {
            val expectedPoint = expected.transform(point.x, point.y, point.z)
            val actualPoint = actual.transform(point.x, point.y, point.z)
            assertEquals(expectedPoint.x, actualPoint.x, 0.0001f)
            assertEquals(expectedPoint.y, actualPoint.y, 0.0001f)
            assertEquals(expectedPoint.z, actualPoint.z, 0.0001f)
        }
    }

    @Test
    fun `specialized translation matches generic multiplication`() {
        val matrix = UiMatrix4.rotationZ(0.37f) * UiMatrix4.scale(1.5f, 0.8f, 1f)
        val specialized = matrix.translated(12f, -7f, 3f)
        val generic = matrix * UiMatrix4.translation(12f, -7f, 3f)
        val point = UiVec3(9f, 4f, -2f)
        val expected = generic.transform(point.x, point.y, point.z)
        val actual = specialized.transform(point.x, point.y, point.z)
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
        assertEquals(expected.z, actual.z, 0.0001f)
    }

    @Test
    fun `axis scale extraction matches transformed unit vectors`() {
        val matrix = UiTransform(
            translate = UiVec3(7f, -4f, 2f),
            rotate = UiVec3(11f, 19f, -33f),
            scale = UiVec3(1.7f, 0.6f, 1f),
            perspective = 500f,
        ).matrix(UiVec3(20f, 15f))
        val scales = FloatArray(2)
        matrix.axisScales(scales)

        val origin = matrix.transform(0f, 0f)
        val xAxis = matrix.transform(1f, 0f)
        val yAxis = matrix.transform(0f, 1f)
        val expectedX = hypot(xAxis.x - origin.x, xAxis.y - origin.y)
        val expectedY = hypot(yAxis.x - origin.x, yAxis.y - origin.y)
        assertEquals(expectedX, scales[0], 0.0001f)
        assertEquals(expectedY, scales[1], 0.0001f)
    }
}
