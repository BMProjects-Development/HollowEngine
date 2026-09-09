import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPlan
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollShape
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachment
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigVector
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachment
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyShape
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Skin
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.byIndex
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Which nodes of model rag-doll takes over.
 */
class RagdollPlanTests {
    private fun model(withSkin: Boolean): PoseTarget {
        val mesh = NodeDefinition(index = 3, name = "body_mesh", children = mutableListOf(), transform = up(0f))
        val lower = NodeDefinition(index = 2, name = "spine", children = mutableListOf(mesh), transform = up(0.5f))
        val upper = NodeDefinition(index = 1, name = "hips", children = mutableListOf(lower), transform = up(0.5f))
        val correction = NodeDefinition(
            index = -1,
            name = "Root",
            children = mutableListOf(upper),
            transform = TrsTransformF().apply { scale(Vec3f(1f / 16f, 1f / 16f, 1f / 16f)) },
            skin = if (withSkin) Skin(listOf(1, 2), arrayOf<Mat4f>(MutableMat4f(), MutableMat4f())) else null,
        )
        mesh.parent = lower
        lower.parent = upper
        upper.parent = correction

        return PoseTarget(listOf(RuntimeNode(correction, parent = null)).byIndex(), emptyMap())
    }

    private fun up(y: Float) = TrsTransformF().apply { translate(Vec3f(0f, y, 0f)) }

    private fun PoseTarget.everyBone(): Set<Int> = mask(BoneMask.full())

    @Test
    fun `a skinned model is simulated by its bones alone`() {
        val target = model(withSkin = true)
        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("hips", "spine"), plan.bones.map { it.name })
    }

    @Test
    fun `a model with no skin keeps its geometry and its space correction out of the simulation`() {
        val target = model(withSkin = false)
        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("hips", "spine"), plan.bones.map { it.name })
    }

    @Test
    fun `a model whose bones carry their cubes in child nodes simulates the bones`() {
        val handCubes = NodeDefinition(index = 3, name = "hand_cubes", children = mutableListOf(), transform = up(0f))
        val hand = NodeDefinition(index = 2, name = "hand", children = mutableListOf(handCubes), transform = up(0.4f))
        val armCubes = NodeDefinition(index = 4, name = "arm_cubes", children = mutableListOf(), transform = up(0f))
        val arm = NodeDefinition(index = 1, name = "arm", children = mutableListOf(hand, armCubes), transform = up(0.4f))
        listOf(handCubes to hand, hand to arm, armCubes to arm).forEach { (child, parent) -> child.parent = parent }
        arm.parent = null

        val target = PoseTarget(listOf(RuntimeNode(arm, parent = null)).byIndex(), emptyMap())
        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("arm", "hand"), plan.bones.map { it.name })
    }

    @Test
    fun `a mask that names bones overrides what the skeleton would have chosen`() {
        val target = model(withSkin = true)
        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.mask(BoneMask.of("body_mesh"))))

        assertEquals(listOf("body_mesh"), plan.bones.map { it.name })
    }

    @Test
    fun `a rig with bodies replaces the guessed skeleton`() {
        val chest = NodeDefinition(index = 3, name = "chest", children = mutableListOf(), transform = up(0.4f))
        val helper = NodeDefinition(index = 2, name = "helper", children = mutableListOf(chest), transform = up(0.1f))
        val hips = NodeDefinition(index = 1, name = "hips", children = mutableListOf(helper), transform = up(0.5f))
        listOf(chest to helper, helper to hips).forEach { (child, parent) -> child.parent = parent }

        val target = PoseTarget(listOf(RuntimeNode(hips, parent = null)).byIndex(), emptyMap())
        target.rig("hips", body(RigidBodyShape.Box(halfExtents = RigVector(0.2f, 0.1f, 0.1f))))
        target.rig("chest", body(RigidBodyShape.Sphere(radius = 0.15f)), JointAttachmentSpec(parent = "hips"))

        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("hips", "chest"), plan.bones.map { it.name })
        assertEquals(-1, plan.bones[0].parent)
        assertEquals(0, plan.bones[1].parent, "The chest hangs off the hips, not off the helper bone")
        assertIs<RagdollShape.Box>(plan.bones[0].shape)
        assertIs<RagdollShape.Sphere>(plan.bones[1].shape)
    }

    @Test
    fun `a body without a joint follows the model hierarchy`() {
        val hand = NodeDefinition(index = 2, name = "hand", children = mutableListOf(), transform = up(0.4f))
        val arm = NodeDefinition(index = 1, name = "arm", children = mutableListOf(hand), transform = up(0.4f))
        hand.parent = arm

        val target = PoseTarget(listOf(RuntimeNode(arm, parent = null)).byIndex(), emptyMap())
        target.rig("arm", body(RigidBodyShape.Capsule()))
        target.rig("hand", body(RigidBodyShape.Capsule()))

        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("arm", "hand"), plan.bones.map { it.name })
        assertEquals(0, plan.bones[1].parent)
    }

    private fun body(shape: RigidBodyShape) = RigidBodyAttachmentSpec(shape = shape)

    private fun PoseTarget.rig(bone: String, body: RigidBodyAttachmentSpec, joint: JointAttachmentSpec? = null) {
        val node = requireNotNull(node(bone)) { "No bone '$bone'" }
        node.attachments += RigidBodyAttachment(body, node)
        joint?.let { node.attachments += JointAttachment(it, node) }
    }
}
