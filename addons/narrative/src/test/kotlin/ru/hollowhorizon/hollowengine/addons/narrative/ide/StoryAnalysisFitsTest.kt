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
        written = mapOf("NpcEntity" to npc),
        relations = mapOf(
            entity to setOf(entity),
            npc to setOf(npc, entity, "$npc?"),
            "$npc?" to setOf(npc, entity, "$npc?"),
        ),
    )

    @Test
    fun `a type written short is the one the compiler names in full`() {
        assertEquals(true, analysis.fits(npc, "NpcEntity"))
        assertEquals(true, analysis.fits("NpcEntity", entity), "a subtype goes where its supertype is taken")
        assertEquals(true, analysis.fits("$npc?", entity), "links unwrap what can be null")
        assertEquals(false, analysis.fits(entity, npc))
    }

    @Test
    fun `what the compiler has not seen is left to the pins`() {
        assertNull(analysis.fits(npc, "Vec3"))
        assertNull(analysis.fits("a.Unknown", npc))
        assertTrue(analysis.takes("a.Unknown", npc))
    }
}
