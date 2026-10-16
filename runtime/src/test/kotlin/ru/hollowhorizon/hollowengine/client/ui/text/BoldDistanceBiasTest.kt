package ru.hollowhorizon.hollowengine.client.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faux-bold shifts the signed-distance threshold, and the shift has a hard ceiling.*/
class BoldDistanceBiasTest {
    /** MonoCraft as shipped: a 2px distance range at 32.625 atlas pixels per em. */
    private val monocraft = UiGlyphAtlasPage(
        textureId = 1,
        width = 512f,
        height = 8192f,
        distanceRange = 2f,
        sampling = UiGlyphSampling.MSDF,
    )

    /** A face baked from a `.ttf` with the wider default range, chosen so bold fits inside it. */
    private val baked = monocraft.copy(distanceRange = 8f)

    private val bitmap = monocraft.copy(distanceRange = 0f, sampling = UiGlyphSampling.ALPHA)

    private val monocraftEm = 32.625f
    private val bakedEm = 48f

    @Test
    fun `a larger pxRange keeps the background dark, so the cap holds at every scale`() {
        for (pxRange in listOf(2f, 4f, 8f, 32f)) {
            assertEquals(
                0f,
                glyphCoverage(raw = -0.5f, bias = MaxBoldDistanceBias, pxRange = pxRange),
                "background lit up at pxRange $pxRange",
            )
        }
    }

    @Test
    fun `a narrow distance range cannot carry a default bold on its own`() {
        val bias = monocraft.boldBias(monocraftEm, DefaultBoldWeight)
        assertEquals(MaxBoldDistanceBias, bias, "MonoCraft's 2px range saturates the cap")
        val delivered = monocraft.boldBiasWeight(monocraftEm, bias)
        assertTrue(delivered < DefaultBoldWeight, "delivered $delivered of $DefaultBoldWeight")
    }

    @Test
    fun `a wider range carries the whole default bold without a second stamp`() {
        val bias = baked.boldBias(bakedEm, DefaultBoldWeight)
        assertTrue(bias < MaxBoldDistanceBias, "the cap must not bind at a range this wide: $bias")
        assertEquals(DefaultBoldWeight, baked.boldBiasWeight(bakedEm, bias), 1e-5f)
    }

    @Test
    fun `a weight past what the range holds is still measured in full, so the stamp covers it`() {
        val heavy = 0.16f
        val bias = baked.boldBias(bakedEm, heavy)
        assertEquals(MaxBoldDistanceBias, bias, "a heavy weight does saturate even the wide range")
        val residual = heavy - baked.boldBiasWeight(bakedEm, bias)
        assertTrue(residual > 0f, "the shortfall is what the second stamp draws: $residual")
        assertTrue(residual < heavy, "the field still carries most of it: $residual of $heavy")
    }
}
