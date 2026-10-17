import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.entities.objects.ObjectPose
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * A world object keeps its pose relative to its parent and works the world pose out from it, and moving
 * it under another parent must not move it. Both rest on [ObjectPose.relativize] undoing [ObjectPose.compose].
 */
class ObjectPoseTests {
    private val parent = ObjectPose(
        position = Vector3d(120.5, 64.0, -33.25),
        rotation = Quaternionf().rotateXYZ(0.4f, 1.2f, -0.7f),
        scale = Vector3f(2f, 0.5f, 1.5f),
    )

    private val local = ObjectPose(
        position = Vector3d(1.0, -2.0, 0.75),
        rotation = Quaternionf().rotateXYZ(-0.3f, 0.9f, 0.2f),
        scale = Vector3f(0.5f, 3f, 1f),
    )

    @Test
    fun `relativize undoes compose`() {
        assertSame(local, parent.relativize(parent.compose(local)))
    }

    @Test
    fun `moving under another parent keeps the world pose`() {
        val world = parent.compose(local)
        val other = ObjectPose(Vector3d(-5.0, 70.0, 12.0), Quaternionf().rotateY(2.5f), Vector3f(0.25f, 1f, 4f))
        assertSame(world, other.compose(other.relativize(world)))
    }

    @Test
    fun `a parent carries its child's offset through its scale and rotation`() {
        val turned = ObjectPose(Vector3d(10.0, 0.0, 0.0), Quaternionf().rotateY((Math.PI / 2).toFloat()), Vector3f(2f))
        val child = turned.compose(ObjectPose(position = Vector3d(1.0, 0.0, 0.0)))
        assertClose(10.0, child.position.x)
        assertClose(-2.0, child.position.z)
    }

    private fun assertSame(expected: ObjectPose, actual: ObjectPose) {
        assertTrue(expected.position.distance(actual.position) < EPSILON, "position ${actual.position}, expected ${expected.position}")
        assertTrue(abs(abs(expected.rotation.dot(actual.rotation)) - 1f) < EPSILON, "rotation ${actual.rotation}, expected ${expected.rotation}")
        assertTrue(expected.scale.distance(actual.scale) < EPSILON, "scale ${actual.scale}, expected ${expected.scale}")
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < EPSILON, "$actual, expected $expected")

    private companion object {
        const val EPSILON = 1.0e-4
    }
}
