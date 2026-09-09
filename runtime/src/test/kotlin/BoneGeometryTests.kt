import ru.hollowhorizon.hollowengine.client.models.internal.BoneGeometry
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.Scene
import ru.hollowhorizon.hollowengine.client.models.internal.Skin
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How much room a bone's geometry takes, which is what a collider is then shaped like.
 */
class BoneGeometryTests {
    private fun translation(y: Float): Mat4f = MutableMat4f().translate(0f, y, 0f)

    private fun skinnedModel(): Model {
        val positions = arrayOf(
            Vec3f(-0.1f, 1.0f, 0f),
            Vec3f(0.1f, 1.4f, 0f),
            Vec3f(0f, 2.0f, 0f),
        )
        val primitive = Primitive(
            positions = positions,
            joints = arrayOf(Vec4i(0, 0, 0, 0), Vec4i(0, 0, 0, 0), Vec4i(1, 0, 0, 0)),
            jointWeights = arrayOf(
                Vec4f(1f, 0f, 0f, 0f),
                Vec4f(1f, 0f, 0f, 0f),
                Vec4f(0.25f, 0.2f, 0f, 0f),
            ),
            material = Material(),
        )

        val skin = Skin(listOf(5, 7), arrayOf(translation(-1f), translation(-2f)))
        val node = NodeDefinition(
            index = 9,
            name = "body",
            children = mutableListOf(),
            transform = TrsTransformF(),
            mesh = Mesh(listOf(primitive), FloatArray(0)),
            skin = skin,
        )
        return Model(scene = 0, scenes = listOf(Scene(listOf(node))), materials = setOf())
    }

    @Test
    fun `a skinned bone is measured in its own space`() {
        val bounds = BoneGeometry.of(skinnedModel())

        val lower = requireNotNull(bounds[5]) { "The lower joint owns two vertices" }
        assertEquals(0f, lower.first.y, 0.001f, "A vertex at the joint sits at zero in the bone's space")
        assertEquals(0.4f, lower.second.y, 0.001f)
        assertEquals(-0.1f, lower.first.x, 0.001f)
        assertEquals(0.1f, lower.second.x, 0.001f)
    }

    @Test
    fun `a vertex a bone barely holds is not counted as its geometry`() {
        val bounds = BoneGeometry.of(skinnedModel())

        assertNull(bounds[7], "A quarter of a vertex is not geometry the bone owns")
        assertEquals(0.4f, requireNotNull(bounds[5]).second.y, 0.001f, "and it does not stretch the other bone")
    }

    @Test
    fun `geometry with no skin belongs to the node it hangs on`() {
        val primitive = Primitive(
            positions = arrayOf(Vec3f(-0.2f, 0f, -0.2f), Vec3f(0.2f, 0.5f, 0.2f)),
            material = Material(),
        )
        val node = NodeDefinition(
            index = 3,
            name = "cube",
            children = mutableListOf(),
            transform = TrsTransformF(),
            mesh = Mesh(listOf(primitive), FloatArray(0)),
        )

        val bounds = BoneGeometry.of(Model(0, listOf(Scene(listOf(node))), setOf()))

        assertEquals(Vec3f(-0.2f, 0f, -0.2f), requireNotNull(bounds[3]).first)
        assertEquals(Vec3f(0.2f, 0.5f, 0.2f), requireNotNull(bounds[3]).second)
    }
}
