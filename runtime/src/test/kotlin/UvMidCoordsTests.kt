import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.utils.GeometryUtils
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import kotlin.test.assertEquals

class UvMidCoordsTests {
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
