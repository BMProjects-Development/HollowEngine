import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.utils.GeometryUtils
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeometryQuadTests {
    private val square = arrayOf(Vec3f(0f, 0f, 0f), Vec3f(1f, 0f, 0f), Vec3f(1f, 1f, 0f), Vec3f(0f, 1f, 0f))
    private val up = Array(4) { Vec3f(0f, 0f, 1f) }

    @Test
    fun `fan triangulation becomes the original quad`() {
        val order = GeometryUtils.flatQuadOrder(intArrayOf(0, 1, 2, 0, 2, 3), square, up)
        assertContentEquals(intArrayOf(0, 1, 2, 3), order)
    }

    @Test
    fun `shared edge in the middle of the first triangle keeps its winding`() {
        val order = GeometryUtils.flatQuadOrder(intArrayOf(3, 0, 1, 1, 2, 3), square, up)
        assertContentEquals(intArrayOf(3, 0, 1, 2), order)
    }

    @Test
    fun `smooth or folded pairs stay triangles`() {
        val smooth = arrayOf(Vec3f(0f, 0f, 1f), Vec3f(0f, 0f, 1f), Vec3f(0.6f, 0f, 0.8f), Vec3f(0f, 0f, 1f))
        assertNull(GeometryUtils.flatQuadOrder(intArrayOf(0, 1, 2, 0, 2, 3), square, smooth))

        val folded = square.copyOf().also { it[3] = Vec3f(0f, 1f, 1f) }
        assertNull(GeometryUtils.flatQuadOrder(intArrayOf(0, 1, 2, 0, 2, 3), folded, up))
    }

    @Test
    fun `split rectangle shares one uv center at every corner`() {
        val uvs = arrayOf(Vec2f(0.25f, 0.5f), Vec2f(0.75f, 0.5f), Vec2f(0.75f, 1f), Vec2f(0.25f, 1f))
        val mids = GeometryUtils.recalculateMidCoords(intArrayOf(0, 1, 2, 0, 2, 3), uvs)
        mids.forEach {
            assertEquals(0.5f, it.x, 1e-6f)
            assertEquals(0.75f, it.y, 1e-6f)
        }
    }
}
