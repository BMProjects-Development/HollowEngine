import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPlan
import ru.hollowhorizon.hollowengine.addons.physics.collider.SphereColliderShape
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachmentSpec
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Skin
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.byIndex
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.colliders.BoxShapeSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeSpec
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

        val target = rigged(
            hips,
            "hips" to body("hips", Vec3f(0.4f, 0.2f, 0.2f)),
            "chest" to body("chest", Vec3f(0.3f, 0.3f, 0.3f), SphereColliderShape, JointAttachmentSpec(parent = "hips")),
        )

        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("hips", "chest"), plan.bones.map { it.name })
        assertEquals(-1, plan.bones[0].parent)
        assertEquals(0, plan.bones[1].parent, "The chest hangs off the hips, not off the helper bone")
        assertEquals(BoxShapeSpec, plan.bones[0].shape.parts.single().shape, "A body is made of the collider on its bone")
        assertEquals(SphereColliderShape, plan.bones[1].shape.parts.single().shape)
    }

    @Test
    fun `a body without a joint follows the model hierarchy`() {
        val hand = NodeDefinition(index = 2, name = "hand", children = mutableListOf(), transform = up(0.4f))
        val arm = NodeDefinition(index = 1, name = "arm", children = mutableListOf(hand), transform = up(0.4f))
        hand.parent = arm

        val target = rigged(arm, "arm" to body("arm", Vec3f(0.1f, 0.4f, 0.1f)), "hand" to body("hand", Vec3f(0.1f, 0.2f, 0.1f)))

        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("arm", "hand"), plan.bones.map { it.name })
        assertEquals(0, plan.bones[1].parent)
    }

    @Test
    fun `a body takes the colliders it names, and is no body without one`() {
        val hand = NodeDefinition(index = 2, name = "hand", children = mutableListOf(), transform = up(0.4f))
        val arm = NodeDefinition(index = 1, name = "arm", children = mutableListOf(hand), transform = up(0.4f))
        hand.parent = arm

        val armBone = RigBone(
            attachments = listOf(
                ColliderAttachmentSpec(id = "sleeve", size = Vec3f(0.1f, 0.4f, 0.1f)),
                ColliderAttachmentSpec(id = "shield", size = Vec3f(0.6f, 0.6f, 0.05f)),
                RigidBodyAttachmentSpec(colliders = listOf("sleeve")),
            ),
        )
        val bareHand = RigBone(attachments = listOf(RigidBodyAttachmentSpec()))
        val target = rigged(arm, "arm" to armBone, "hand" to bareHand)

        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()))

        assertEquals(listOf("arm"), plan.bones.map { it.name }, "A body with nothing to be made of is left out")
        assertEquals(0.2f, plan.bones.single().shape.parts.single().halfExtents.y, "Only the sleeve: the shield is not named")
    }

    @Test
    fun `bodies with nothing to be made of are not swapped for guessed capsules`() {
        val hand = NodeDefinition(index = 2, name = "hand", children = mutableListOf(), transform = up(0.4f))
        val arm = NodeDefinition(index = 1, name = "arm", children = mutableListOf(hand), transform = up(0.4f))
        hand.parent = arm

        val bare = RigBone(attachments = listOf(RigidBodyAttachmentSpec()))
        val target = rigged(arm, "arm" to bare, "hand" to bare)

        assertNull(RagdollPlan.build(target, RagdollStateSpec(), target.everyBone()), "The rig says what the bodies are, even when it says too little")
    }

    /** A collider of [size] and the body made of it, on one bone. */
    private fun body(collider: String, size: Vec3f, shape: ColliderShapeSpec = BoxShapeSpec, joint: JointAttachmentSpec? = null) =
        RigBone(attachments = listOfNotNull(ColliderAttachmentSpec(id = collider, size = size, shape = shape), RigidBodyAttachmentSpec(), joint))

    private fun rigged(root: NodeDefinition, vararg bones: Pair<String, RigBone>) =
        PoseTarget(listOf(RuntimeNode(root, parent = null)).byIndex(), emptyMap(), rig = ModelRig(bones = mapOf(*bones)))
}
