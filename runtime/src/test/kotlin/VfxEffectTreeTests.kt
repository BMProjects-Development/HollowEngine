import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxEmitterSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxGroupSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxKey
import ru.hollowhorizon.hollowengine.common.vfx.VfxPlaneSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxTimelineSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrack
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class VfxEffectTreeTests {
    private val effect = VfxEffect(
        nodes = listOf(
            VfxGroupSpec(
                id = "group-a",
                children = listOf(
                    VfxEmitterSpec(id = "emitter-a", children = listOf(VfxPlaneSpec(id = "plane-a"))),
                ),
            ),
            VfxPlaneSpec(id = "plane-b"),
        ),
        timeline = VfxTimelineSpec(
            tracks = listOf(
                VfxTrack("plane-a", VfxProperty.SCALE, listOf(VfxTrackCurve(keys = listOf(VfxKey(0f, 1f))))),
            ),
        ),
    )

    @Test
    fun `a node cannot be moved under itself or its own children`() {
        assertSame(effect, effect.withMoved("group-a", "emitter-a"))
        assertSame(effect, effect.withMoved("group-a", "group-a"))
    }

    @Test
    fun `a moved node leaves its old parent and lands at the index asked for`() {
        val moved = effect.withMoved("plane-b", "emitter-a", 0)
        assertEquals(listOf("group-a"), moved.nodes.map { it.id })
        assertEquals(listOf("plane-b", "plane-a"), moved.node("emitter-a")?.children?.map { it.id })
        assertEquals(moved.node("emitter-a"), moved.parentOf("plane-b"))
        assertEquals(moved.node("emitter-a"), moved.parentOf("plane-a"))
    }

    @Test
    fun `shifting stays among the siblings`() {
        val up = effect.withShifted("plane-b", -1)
        assertEquals(listOf("plane-b", "group-a"), up.nodes.map { it.id })
        assertSame(up, up.withShifted("plane-b", -1))
    }

    @Test
    fun `a duplicate gets fresh ids all the way down and takes the tracks along`() {
        val (copied, id) = assertNotNull(effect.withDuplicate("emitter-a"))
        val children = copied.node("group-a")?.children.orEmpty()
        assertEquals(2, children.size)
        assertEquals("emitter-a", children[0].id)
        assertEquals(id, children[1].id)

        val plane = children[1].children.single()
        assertNotEquals("plane-a", plane.id)
        assertEquals(setOf("plane-a", plane.id), copied.timeline.tracks.map { it.node }.toSet())
        assertEquals(copied.walk().size, copied.walk().map { it.id }.toSet().size)
    }
}
