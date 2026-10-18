package ru.hollowhorizon.hollowengine.common.sounds

import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals

class SoundAreaTest {
    private val nearest = MutableVec3f()

    @Test
    fun `a box measures from its nearest face outside and its nearest face inside`() {
        val box = SoundArea.Box(offset = Vec3f(10f, 0f, 0f), size = Vec3f(4f, 2f, 4f))

        assertEquals(3f, box.depth(Vec3f(15f, 0f, 0f), nearest), 1e-5f)
        assertEquals(Vec3f(12f, 0f, 0f), Vec3f(nearest.x, nearest.y, nearest.z))
        assertEquals(-0.5f, box.depth(Vec3f(10f, 0.5f, 0f), nearest), 1e-5f)
    }

    @Test
    fun `a turned box is measured along its own axes`() {
        val box = SoundArea.Box(rotation = Vec3f(0f, 90f, 0f), size = Vec3f(10f, 2f, 2f))

        // Turned a quarter around Y, its long side runs along Z.
        assertEquals(-1f, box.depth(Vec3f(0f, 0f, 4f), nearest), 1e-4f)
        assertEquals(1f, box.depth(Vec3f(2f, 0f, 0f), nearest), 1e-4f)
    }

    @Test
    fun `rooms joined into one space are inside wherever any of them is`() {
        val rooms = listOf(
            SoundArea.Box(size = Vec3f(4f, 4f, 4f)),
            SoundArea.Box(offset = Vec3f(6f, 0f, 0f), size = Vec3f(8f, 4f, 4f)),
            SoundArea.Sphere(offset = Vec3f(0f, 20f, 0f), radius = 2f),
        )

        assertEquals(-2f, rooms.depth(Vec3f(6f, 0f, 0f), nearest), 1e-5f)
        assertEquals(Vec3f(6f, 0f, 0f), Vec3f(nearest.x, nearest.y, nearest.z))
        assertEquals(1f, rooms.depth(Vec3f(0f, 17f, 0f), nearest), 1e-5f)
        assertEquals(Vec3f(0f, 18f, 0f), Vec3f(nearest.x, nearest.y, nearest.z))
    }

    @Test
    fun `a falloff fades past the edge, or before it when it is inside`() {
        val outside = SoundFalloff(distance = 4f)
        assertEquals(1f, outside.gain(-1f, 16f, hasInside = true))
        assertEquals(0.5f, outside.gain(2f, 16f, hasInside = true), 1e-5f)
        assertEquals(0f, outside.gain(4f, 16f, hasInside = true))

        val inside = SoundFalloff(distance = 4f, inside = true)
        assertEquals(1f, inside.gain(-4f, 16f, hasInside = true))
        assertEquals(0.5f, inside.gain(-2f, 16f, hasInside = true), 1e-5f)
        assertEquals(0f, inside.gain(0f, 16f, hasInside = true))
        // A point has no inside to fade in.
        assertEquals(0.5f, inside.gain(2f, 16f, hasInside = false), 1e-5f)
    }

    @Test
    fun `without a distance the falloff takes the one the sound brings`() {
        assertEquals(0.5f, SoundFalloff().gain(8f, 16f, hasInside = false), 1e-5f)
    }
}
