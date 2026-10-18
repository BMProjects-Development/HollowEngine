package ru.hollowhorizon.hollowengine.client.vfx

import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VfxSeekTest {
    /** A flash: one burst of particles that live a second, never again. */
    private val flash = VfxEffect(
        nodes = listOf(
            VfxEmitterSpec(
                id = "flash",
                emission = VfxEmission(rate = VfxValue.ZERO, burst = 10, duration = 0.5f, loop = false),
                spawn = VfxSpawn(lifetime = VfxValue.Const(1f), speed = VfxValue.ZERO),
            )
        ),
    )

    /** A stream of 30 particles a second that live a second. */
    private val stream = VfxEffect(
        nodes = listOf(
            VfxEmitterSpec(
                id = "stream",
                emission = VfxEmission(rate = VfxValue.Const(30f)),
                spawn = VfxSpawn(lifetime = VfxValue.Const(1f), speed = VfxValue.ZERO),
            )
        ),
    )

    @Test
    fun `an effect held before its stretch starts plays from its start when let go`() {
        val instance = VfxInstance(flash, "test")
        instance.seek(0f, 2.5f, emittingAt = { it >= 2f })

        assertEquals(10, instance.particleCount, "the burst fires at 2 s, though the flash would be over by then")
    }

    @Test
    fun `a held effect makes nothing new while what it made lives out its lifetime`() {
        val instance = VfxInstance(stream, "test")
        instance.seek(0f, 1f)
        val alive = instance.particleCount
        instance.seek(1f, 1.5f, emittingAt = { false })

        assertTrue(instance.particleCount in 1 until alive, "half of $alive should be left, got ${instance.particleCount}")
        instance.seek(1.5f, 2.6f, emittingAt = { false })
        assertEquals(0, instance.particleCount)
    }

    @Test
    fun `going back plays the effect again up to the moment`() {
        val straight = VfxInstance(stream, "test", seed = 1)
        straight.seek(0f, 1.5f, emittingAt = { it < 1f })

        val scrubbed = VfxInstance(stream, "test", seed = 1)
        scrubbed.seek(0f, 2f, emittingAt = { it < 1f })
        scrubbed.seek(2f, 1.5f, emittingAt = { it < 1f })

        assertEquals(straight.particleCount, scrubbed.particleCount)
        val (expected, actual) = straight.emitters.single().particles to scrubbed.emitters.single().particles
        for (slot in 0 until expected.count) assertEquals(expected.positionX[slot], actual.positionX[slot], 1e-5f)
    }
}
