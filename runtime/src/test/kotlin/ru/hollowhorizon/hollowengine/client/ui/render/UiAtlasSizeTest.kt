package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiAtlasSizeTest {
    private val nodes = List(3) { UiLayerRequest(225, 60) } + List(6) { UiLayerRequest(138, 138) }

    @Test
    fun `atlas takes little more than its layers need`() {
        val size = atlasSizeFor(nodes, null, 16_384)

        assertTrue(size.width * size.height < 1024 * 512, "$size")
    }

    @Test
    fun `atlas keeps what it has while the layers fit`() {
        assertEquals(UiAtlasSize(2048, 1024), atlasSizeFor(nodes, UiAtlasSize(2048, 1024), 16_384))
    }

    @Test
    fun `atlas is oversized only when it is large and mostly empty`() {
        assertTrue(isAtlasOversized(nodes, 5010, 3884))
        assertTrue(isAtlasOversized(emptyList(), 5010, 3884))
        assertFalse(isAtlasOversized(emptyList(), 512, 512))
        assertFalse(isAtlasOversized(List(8) { UiLayerRequest(1000, 1000) }, 4000, 2000))
    }
}
