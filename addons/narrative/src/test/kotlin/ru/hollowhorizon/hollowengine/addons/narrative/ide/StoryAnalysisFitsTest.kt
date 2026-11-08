package ru.hollowhorizon.hollowengine.addons.narrative.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPinType

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

    @Test
    fun `menu candidates and connections use generic relations before pin colors`() {
        val strings = "kotlin.collections.List<kotlin.String>"
        val numbers = "kotlin.collections.List<kotlin.Int>"
        val analysis = StoryAnalysis(relations = mapOf(strings to setOf(strings), numbers to setOf(numbers)))
        assertFalse(analysis.accepts(StoryPinType.LIST, strings, StoryPinType.LIST, numbers))
        assertTrue(analysis.accepts(StoryPinType.LIST, strings, StoryPinType.LIST, strings))
        assertFalse(analysis.accepts(StoryPinType.FLOW, null, StoryPinType.ANY, null))
        assertTrue(analysis.accepts(StoryPinType.NUMBER, "kotlin.Double", StoryPinType.NUMBER, "kotlin.Int"))
    }
}
