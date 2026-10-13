import net.minecraft.core.Direction
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.decals.DecalExtent
import ru.hollowhorizon.hollowengine.client.decals.DecalGround
import ru.hollowhorizon.hollowengine.client.decals.DecalShapes
import ru.hollowhorizon.hollowengine.common.decals.DecalProjection
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec
import ru.hollowhorizon.hollowengine.common.entities.objects.decalOf
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecalGeometryTest {
    /** Where [point] of the decal frame lies in the unit cube, as the shader finds it. */
    private fun cubeOf(spec: DecalSpec, point: Vector3f): Vector3f {
        val extent = DecalExtent(spec.width, spec.length ?: spec.width, spec.reach ?: 1f)
        return Matrix4f(DecalShapes.unitCube(spec, extent)).invert().transformProject(Vector3f(point))
    }

    /** The picture coordinates the shader takes from a point of the unit cube. */
    private fun pictureOf(cube: Vector3f) = cube.x * 0.5f + 0.5f to 0.5f - cube.y * 0.5f

    @Test
    fun `a box lays the picture down onto the floor, its top to the north`() {
        val spec = DecalSpec(width = 2f, length = 2f, reach = 1f)
        val center = cubeOf(spec, Vector3f(0f, -0.25f, 0f))
        assertTrue(center.lengthSquared() < 1f && abs(center.x) < 1e-4f && abs(center.y) < 1e-4f)

        val (eastU, _) = pictureOf(cubeOf(spec, Vector3f(0.9f, 0f, 0f)))
        val (_, northV) = pictureOf(cubeOf(spec, Vector3f(0f, 0f, -0.9f)))
        assertTrue(eastU > 0.9f, "east is the right of the picture")
        assertTrue(northV < 0.1f, "north is the top of the picture")
    }

    @Test
    fun `a box reaches from its place straight down its depth`() {
        val spec = DecalSpec(width = 1f, length = 1f, reach = 1f)
        fun inside(y: Float) = cubeOf(spec, Vector3f(0f, y, 0f)).let { abs(it.z) <= 1f }
        assertTrue(!inside(0.1f), "above its place is outside")
        assertTrue(inside(-0.05f) && inside(-0.95f), "down to its depth is inside")
        assertTrue(!inside(-1.1f), "past its depth is outside")
    }

    @Test
    fun `a projector reaches down to its range, widening with distance`() {
        val spec = DecalSpec(projection = DecalProjection.PROJECTOR, width = 1f, length = 1f, reach = 10f, fieldOfView = 90f)
        val near = cubeOf(spec, Vector3f(0.4f, -1f, 0f))
        val far = cubeOf(spec, Vector3f(0.4f, -8f, 0f))
        assertTrue(near.x > far.x && far.x > 0f, "the same offset is a smaller share of the picture further away")
        assertTrue(abs(cubeOf(spec, Vector3f(0f, -12f, 0f)).z) > 1f, "past the range is outside")
        val (_, northV) = pictureOf(cubeOf(spec, Vector3f(0f, -4f, -3.5f)))
        assertTrue(northV < 0.1f, "north is the top of the picture, as for a box")
    }

    @Test
    fun `a preview floor is found in the units of the placement`() {
        val ground = DecalGround.floor(Matrix4f().scale(0.5f))
        val down = Vector3f(0f, -1f, 0f)
        assertEquals(4f, ground.distance(Vector3f(0f, 4f, 0f), down)!!, 1e-4f)
        assertEquals(2f, ground.unitsPerBlock(Vector3f(0f, 4f, 0f), down), 1e-4f)
        assertEquals(null, ground.distance(Vector3f(0f, -1f, 0f), down), "under the floor there is nothing to reach")
    }

    @Test
    fun `a decal dropped on a face looks out of it, its top up on walls and north on floors`() {
        fun turned(face: Direction, local: Vector3f): Vector3f {
            val turn = eulerRotationXyz(decalOf("ns:textures/a.png", face).rotation)
            return Quaternionf(turn.x, turn.y, turn.z, turn.w).transform(Vector3f(local))
        }
        Direction.entries.forEach { face ->
            val normal = face.normal.let { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
            assertEquals(1f, turned(face, Vector3f(0f, 1f, 0f)).dot(normal), 1e-4f, "$face: the decal looks out of the face")
            val top = turned(face, Vector3f(0f, 0f, -1f))
            val wanted = if (face.axis == Direction.Axis.Y) Vector3f(0f, 0f, -1f) else Vector3f(0f, 1f, 0f)
            assertEquals(1f, top.dot(wanted), 1e-4f, "$face: the top of the picture")
        }
    }
}
