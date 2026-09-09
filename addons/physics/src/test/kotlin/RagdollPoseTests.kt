import ru.hollowhorizon.hollowengine.addons.physics.matrixOf
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPlan
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPose
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachment
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachment
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachmentSpec
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.byIndex
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagdollPoseTests {
    private val spec = RagdollStateSpec()

    private fun spine(): PoseTarget {
        val tip = NodeDefinition(index = 3, name = "head_tip", children = mutableListOf(), transform = offset(0.3f))
        val leaf = NodeDefinition(index = 2, name = "head", children = mutableListOf(tip), transform = offset(0.5f))
        val middle = NodeDefinition(index = 1, name = "chest", children = mutableListOf(leaf), transform = offset(0.5f))
        val root = NodeDefinition(index = 0, name = "hips", children = mutableListOf(middle), transform = offset(1f))
        tip.parent = leaf
        leaf.parent = middle
        middle.parent = root

        val node = RuntimeNode(root, parent = null)
        return PoseTarget(listOf(node).byIndex(), emptyMap())
    }

    private fun offset(y: Float) = TrsTransformF().apply { translate(Vec3f(0f, y, 0f)) }

    private fun globalsOf(target: PoseTarget): Map<Int, Mat4f> {
        val globals = HashMap<Int, Mat4f>()
        target.nodesByIndex.values.sortedBy { depthOf(it) }.forEach { node ->
            val local = node.transform.matrixF
            val parent = (node.parent as? RuntimeNode)?.let { globals[it.definition.index] }
            globals[node.definition.index] = parent?.mul(local, MutableMat4f()) ?: MutableMat4f().set(local)
        }
        return globals
    }

    private fun depthOf(node: RuntimeNode): Int {
        var depth = 0
        var current: RuntimeNode? = node.parent as? RuntimeNode
        while (current != null) {
            depth++
            current = current.parent as? RuntimeNode
        }
        return depth
    }

    @Test
    fun `bones end up where physics put them`() {
        val target = spine()
        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        assertEquals(3, plan.bones.size)

        val animated = globalsOf(target)

        val fallen = QuatF(90f.deg, Vec3f.X_AXIS)
        val simulated = mapOf(
            0 to matrixOf(fallen, Vec3f(0f, 0.1f, 0f)) as Mat4f,
            1 to matrixOf(fallen, Vec3f(0f, 0.1f, -0.5f)) as Mat4f,
            2 to matrixOf(fallen, Vec3f(0f, 0.1f, -1.0f)) as Mat4f,
        )

        val pose = RagdollPose.write(plan, target, simulated, animated)
        assertEquals(3, pose.entries.size, "The pose left bones out")

        target.apply(pose, LayerBlendMode.Override, weight = 1f)
        target.nodesByIndex.values.forEach { it.updateHierarchyMatrices() }

        val posed = globalsOf(target)
        plan.bones.forEach { bone ->
            val expected = MutableVec3f()
            val actual = MutableVec3f()
            requireNotNull(simulated[bone.nodeIndex]).decompose(expected, null, null)
            requireNotNull(posed[bone.nodeIndex]).decompose(actual, null, null)
            assertTrue(
                MutableVec3f(actual).subtract(expected).length() < 0.001f,
                "Bone ${bone.name} was asked to be at $expected but the model puts it at $actual",
            )
        }
    }

    @Test
    fun `half the weight is half the way there`() {
        val target = spine()
        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        val animated = globalsOf(target)
        val simulated = plan.bones.associate { bone ->
            val position = MutableVec3f()
            requireNotNull(animated[bone.nodeIndex]).decompose(position, null, null)
            bone.nodeIndex to matrixOf(QuatF.IDENTITY, MutableVec3f(position).add(Vec3f(1f, 0f, 0f))) as Mat4f
        }

        val pose = RagdollPose.write(plan, target, simulated, animated)
        target.apply(pose, LayerBlendMode.Override, weight = 0.5f)
        target.nodesByIndex.values.forEach { it.updateHierarchyMatrices() }

        val root = MutableVec3f()
        requireNotNull(globalsOf(target)[0]).decompose(root, null, null)
        assertEquals(0.5f, root.x, 0.001f, "Blending a ragdoll in halfway should move the root halfway")
    }

    @Test
    fun `the pose a state is entered with says where the bodies start`() {
        val target = spine()
        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        val order = plan.order.sortedBy(::depthOf)

        val seed = AnimationPose()
        seed.bone(1).rotation = QuatF(90f.deg, Vec3f.X_AXIS)

        val globals = RagdollPose.globals(order, seed, HashMap())

        val chest = MutableVec3f()
        val head = MutableVec3f()
        requireNotNull(globals[1]).decompose(chest, null, null)
        requireNotNull(globals[2]).decompose(head, null, null)

        assertEquals(1.5f, chest.y, 0.001f)
        assertEquals(1.5f, head.y, 0.001f, "The head should have swung out of the vertical")
        assertEquals(0.5f, head.z, 0.001f, "The head should have swung the half block it stood above out to the side")
    }

    @Test
    fun `an empty pose leaves every bone in its bind pose`() {
        val target = spine()
        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        val order = plan.order.sortedBy(::depthOf)

        val globals = RagdollPose.globals(order, AnimationPose(), HashMap())

        val bind = globalsOf(target)
        bind.keys.forEach { index ->
            val expected = MutableVec3f()
            val actual = MutableVec3f()
            requireNotNull(bind[index]).decompose(expected, null, null)
            requireNotNull(globals[index]).decompose(actual, null, null)
            assertTrue(
                MutableVec3f(actual).subtract(expected).length() < 0.001f,
                "Node $index should have stayed at $expected, but the seed pose put it at $actual",
            )
        }
    }

    @Test
    fun `a bone hanging through a bone nobody simulates lands where physics put it`() {
        val head = NodeDefinition(index = 3, name = "head", children = mutableListOf(), transform = offset(0.25f))
        val helper = NodeDefinition(index = 2, name = "helper", children = mutableListOf(head), transform = offset(0.5f))
        val chest = NodeDefinition(index = 1, name = "chest", children = mutableListOf(helper), transform = offset(1f))
        head.parent = helper
        helper.parent = chest

        val target = PoseTarget(listOf(RuntimeNode(chest, parent = null)).byIndex(), emptyMap())
        listOf("chest", "head").forEach { name ->
            val node = requireNotNull(target.node(name))
            node.attachments += RigidBodyAttachment(RigidBodyAttachmentSpec(), node)
        }
        requireNotNull(target.node("head")).let {
            it.attachments += JointAttachment(JointAttachmentSpec(parent = "chest"), it)
        }

        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        assertEquals(listOf("chest", "head"), plan.bones.map { it.name })

        val animated = RagdollPose.globals(plan.order, AnimationPose(), HashMap())
        val simulated = mapOf(
            1 to matrixOf(QuatF.IDENTITY, Vec3f(1f, 1f, 0f)) as Mat4f,
            3 to matrixOf(QuatF.IDENTITY, Vec3f(1f, 1.75f, 0f)) as Mat4f,
        )

        val pose = RagdollPose.write(plan, target, simulated, animated)
        target.apply(pose, LayerBlendMode.Override, weight = 1f)
        target.nodesByIndex.values.forEach { it.updateHierarchyMatrices() }

        val drawn = globalsOf(target)
        plan.bones.forEach { bone ->
            val expected = MutableVec3f()
            val actual = MutableVec3f()
            requireNotNull(simulated[bone.nodeIndex]).decompose(expected, null, null)
            requireNotNull(drawn[bone.nodeIndex]).decompose(actual, null, null)
            assertTrue(
                MutableVec3f(actual).subtract(expected).length() < 0.001f,
                "Bone ${bone.name} was asked to be at $expected but the model puts it at $actual",
            )
        }
    }
}
