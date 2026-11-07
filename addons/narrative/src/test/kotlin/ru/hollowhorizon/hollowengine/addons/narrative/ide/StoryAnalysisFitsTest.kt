package ru.hollowhorizon.hollowengine.addons.narrative.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Links are checked by what the Kotlin compiler says of the types, whichever way they are written. */
class StoryAnalysisFitsTest {
    private val npc = "ru.hollowhorizon.hollowengine.common.npcs.NpcEntity"
    private val entity = "net.minecraft.world.entity.Entity"
    private val analysis = StoryAnalysis(
        fits = mapOf("$npc?" to setOf(npc, entity), entity to setOf(entity)),
        places = setOf(npc, entity),
        written = mapOf("NpcEntity" to npc),
    )

    @Test
    fun `a type written short is the one the compiler names in full`() {
        assertEquals(true, analysis.fits(npc, "NpcEntity"))
        assertEquals(true, analysis.fits("NpcEntity", entity), "a subtype goes where its supertype is taken")
        assertEquals(false, analysis.fits(entity, npc))
    }

    @Test
    fun `what the compiler has not seen is left to the pins`() {
        assertNull(analysis.fits(npc, "kotlin.String"))
        assertTrue(analysis.takes("a.Unknown", npc))
    }
}
