import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackPreviewData
import ru.hollowhorizon.hollowengine.common.models.Montage
import ru.hollowhorizon.hollowengine.common.models.MontageLayerSpec
import ru.hollowhorizon.hollowengine.common.playback.PlaybackAction
import ru.hollowhorizon.hollowengine.common.playback.PlaybackRule
import ru.hollowhorizon.hollowengine.common.playback.PlaybackSection
import ru.hollowhorizon.hollowengine.common.playback.PlaybackSections
import ru.hollowhorizon.hollowengine.common.playback.TimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSectionsTest {
    private val events = listOf(TimelineEvent(4f, "finale"))

    private fun advance(
        sections: List<PlaybackSection>,
        from: Float,
        by: Float,
        entering: Boolean = false,
        holds: (String) -> Boolean = { true },
        spans: MutableList<Triple<Float, Float, Boolean>>? = null,
    ) = PlaybackSections.advance(sections, events, from, by, entering, holds) { a, b, landed -> spans?.add(Triple(a, b, landed)) }

    @Test
    fun `a repeating section loops while its condition holds and plays on once it does not`() {
        val loop = PlaybackSection("idle", start = 1f, end = 2f, after = listOf(PlaybackRule("waiting", PlaybackAction.REPEAT)))

        val looped = advance(listOf(loop), from = 1.5f, by = 1f)
        assertEquals(1.5f, looped.time, 1e-4f)
        assertTrue(looped.decided)

        val released = advance(listOf(loop), from = 1.5f, by = 1f, holds = { false })
        assertEquals(2.5f, released.time, 1e-4f)
        assertTrue(released.decided)
    }

    @Test
    fun `a section is skipped only when entered at its start`() {
        val skipped = PlaybackSection("intro", start = 1f, end = 3f, before = listOf(PlaybackRule("", PlaybackAction.SKIP)))

        assertEquals(3.5f, advance(listOf(skipped), from = 0.5f, by = 1f).time, 1e-4f)
        assertEquals(3.5f, advance(listOf(skipped), from = 1f, by = 0.5f, entering = true).time, 1e-4f)
        assertEquals(2f, advance(listOf(skipped), from = 1.5f, by = 0.5f).time, 1e-4f)
    }

    @Test
    fun `the first rule that holds decides`() {
        val section = PlaybackSection(
            "fight", start = 0f, end = 1f,
            after = listOf(
                PlaybackRule("won", PlaybackAction.JUMP, "finale"),
                PlaybackRule("", PlaybackAction.REPEAT),
            ),
        )
        assertEquals(4.2f, advance(listOf(section), from = 0.8f, by = 0.4f, holds = { it == "won" }).time, 1e-4f)
        assertEquals(0.2f, advance(listOf(section), from = 0.8f, by = 0.4f, holds = { false }).time, 1e-4f)
    }

    @Test
    fun `spans follow the path, and a span after a jump starts where the rule landed`() {
        val loop = PlaybackSection("idle", start = 1f, end = 2f, after = listOf(PlaybackRule("", PlaybackAction.REPEAT)))
        val spans = ArrayList<Triple<Float, Float, Boolean>>()
        advance(listOf(loop), from = 1.5f, by = 1f, spans = spans)
        assertEquals(listOf(Triple(1.5f, 2f, false), Triple(1f, 1.5f, true)), spans)
    }

    @Test
    fun `a section without rules is played straight through`() {
        val plain = PlaybackSection("plain", start = 1f, end = 2f)
        val step = advance(listOf(plain), from = 0f, by = 3f)
        assertEquals(3f, step.time, 1e-4f)
        assertFalse(step.decided)
    }

    @Test
    fun `an endless loop with nothing to stop it gives up within one call`() {
        val loop = PlaybackSection("spin", start = 0f, end = 0.01f, after = listOf(PlaybackRule("", PlaybackAction.REPEAT)))
        val step = advance(listOf(loop), from = 0f, by = 10f)
        assertTrue(step.time.isFinite())
    }

    @Test
    fun `a montage layer plays on from the anchor the server last set`() {
        val loop = PlaybackSection("idle", start = 1f, end = 2f, after = listOf(PlaybackRule("waiting", PlaybackAction.REPEAT)))
        val montage = Montage(duration = 5f, sections = listOf(loop))
        val layer = MontageLayerSpec(montage = "test", startGameTime = 0L, anchorGameTime = 40L, anchorTime = 1.5f, anchorEntering = false)

        assertEquals(1.5f, layer.played(montage, 60L, 0f, { true })!!.time, 1e-4f)
        assertEquals(2.5f, layer.played(montage, 60L, 0f, { false })!!.time, 1e-4f)
    }

    @Test
    fun `preview data are what the script assigns, nested paths and flags included`() {
        val values = PlaybackPreviewData.run("d.waiting = true;\nd.hp = 12;\nd.boss.phase = d.hp / 4;")
        assertEquals(mapOf("waiting" to 1f, "hp" to 12f, "boss.phase" to 3f), values)
    }
}
