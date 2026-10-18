 import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointLimits
import ru.hollowhorizon.hollowengine.addons.physics.rig.RagdollRigGenerator
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachmentSpec
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.colliders.BoxShapeSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RagdollRigGeneratorTests {
    private val geometry = HashMap<Int, Pair<Vec3f, Vec3f>>()
    private var nextIndex = 0

    private fun node(name: String, x: Float = 0f, y: Float = 0f, z: Float = 0f, vararg children: NodeDefinition): NodeDefinition {
        val node = NodeDefinition(
            index = nextIndex++,
            name = name,
            children = children.toMutableList(),
            transform = TrsTransformF().apply { translate(Vec3f(x, y, z)) },
        )
        children.forEach { it.parent = node }
        return node
    }

    /** A bone holding a cube from [min] to [max] in its own space. */
    private fun bone(name: String, x: Float, y: Float, z: Float, min: Vec3f, max: Vec3f, vararg children: NodeDefinition): NodeDefinition {
        val cubes = node("${name}_cubes")
        geometry[cubes.index] = min to max
        return node(name, x, y, z, cubes, *children)
    }

    private fun playerLike(current: ModelRig = ModelRig.EMPTY): ModelRig {
        val eye = bone("Eye", 0f, 0.25f, -0.25f, Vec3f(-0.075f, -0.035f, 0f), Vec3f(0.075f, 0.035f, 0.01f))
        val head = bone("Head", 0f, 0.375f, 0f, Vec3f(-0.25f, 0f, -0.25f), Vec3f(0.25f, 0.5f, 0.25f), eye)
        val body = bone("Body", 0f, 0.75f, 0f, Vec3f(-0.25f, 0f, -0.125f), Vec3f(0.25f, 0.375f, 0.125f), head)
        val leg = Vec3f(-0.125f, -0.75f, -0.125f) to Vec3f(0.125f, 0f, 0.125f)
        val leftLeg = bone("LeftLeg", -0.125f, 0.75f, 0f, leg.first, leg.second)
        val rightLeg = bone("RightLeg", 0.125f, 0.75f, 0f, leg.first, leg.second)
        val root = node("Model", 0f, 0f, 0f, body, leftLeg, rightLeg)

        val nodes = RuntimeNode(root, parent = null).walk()
        return RagdollRigGenerator.generate(nodes, geometry, current)
    }

    private fun ModelRig.jointOf(bone: String): String? =
        bone(bone)?.attachments?.filterIsInstance<JointAttachmentSpec>()?.singleOrNull()?.parent

    @Test
    fun `legs hung beside the body under a bare root are joined to the body`() {
        val rig = playerLike()

        assertNull(rig.jointOf("Body"), "The largest body is the root")
        assertEquals("Body", rig.jointOf("LeftLeg"))
        assertEquals("Body", rig.jointOf("RightLeg"))
        assertEquals("Body", rig.jointOf("Head"))
    }

    @Test
    fun `every body is made of a collider around its geometry`() {
        val rig = playerLike()

        listOf("Body", "Head", "LeftLeg", "RightLeg").forEach { name ->
            val attachments = requireNotNull(rig.bone(name)).attachments
            assertTrue(attachments.any { it is RigidBodyAttachmentSpec }, "$name has a body")
            assertTrue(attachments.any { it is ColliderAttachmentSpec }, "$name has a collider to be made of")
        }
        val leg = requireNotNull(rig.bone("LeftLeg")).attachments.filterIsInstance<ColliderAttachmentSpec>().single()
        assertEquals(BoxShapeSpec, leg.shape, "A single cube fills its box, so its collider is the box")
    }

    @Test
    fun `a body set up by hand keeps its joint`() {
        val knee = JointAttachmentSpec(id = "joint", parent = "Body", limits = JointLimits.hinge(-130f, 0f))
        val tuned = RigBone(attachments = listOf(RigidBodyAttachmentSpec(id = "body"), knee))
        val rig = playerLike(ModelRig(bones = mapOf("LeftLeg" to tuned)))

        assertEquals(tuned, rig.bone("LeftLeg"))
        assertEquals("Body", rig.jointOf("RightLeg"), "Bones without a body are still filled in")
    }

    @Test
    fun `geometry too thin to have bulk rides on the bone above it`() {
        assertNull(playerLike().bone("Eye"))
    }
}
