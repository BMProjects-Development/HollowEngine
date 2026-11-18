import ru.hollowhorizon.hollowengine.addons.physics.matrixOf
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPlan
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPose
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.RagdollRigGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSceneInstance
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.Skin
import ru.hollowhorizon.hollowengine.client.models.internal.Scene as ModelScene
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigGenerationSource
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RagdollSceneTests {
    @Test
    fun `ragdoll builds and writes its pose directly to scene model nodes`() {
        val tip = NodeDefinition(2, "tip", mutableListOf(), TrsTransformF().translate(0f, 0.5f, 0f))
        val chest = NodeDefinition(1, "chest", mutableListOf(tip), TrsTransformF().translate(0f, 0.5f, 0f))
        val root = NodeDefinition(0, "hips", mutableListOf(chest), TrsTransformF())
        tip.parent = chest
        chest.parent = root
        val model = Model(0, listOf(ModelScene(listOf(root))), emptySet())
        val scene = Scene(object : SceneContext {
            override val side = LogicalSide.SERVER
        })
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val target = instance.poseTarget()
        val spec = RagdollStateSpec()
        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        assertSame(instance.root.children.single(), target.node("hips"))

        val animated = RagdollPose.globals(plan.order, AnimationPose(), HashMap())
        val originalMatrix = animated.getValue(0)
        RagdollPose.globals(plan.order, AnimationPose(), animated)
        assertSame(originalMatrix, animated.getValue(0))
        RagdollPose.globals(plan.order.drop(1), AnimationPose(), animated)
        assertTrue(0 !in animated)
        assertEquals(0.5f, animated.getValue(1).getTranslation().y)
        RagdollPose.globals(plan.order, AnimationPose(), animated)
        val simulated = plan.bones.associate { bone ->
            bone.nodeIndex to (matrixOf(QuatF.IDENTITY, bone.bindPosition + Vec3f(1f, 0f, 0f)) as Mat4f)
        }
        val pose = RagdollPose.write(plan, target, simulated, animated)
        target.apply(pose, LayerBlendMode.Override, 1f)

        plan.bones.forEach { bone ->
            val node = target.poseNodesByIndex.getValue(bone.nodeIndex)
            assertTrue(
                node.worldTransform.getTranslation()
                    .distance(simulated.getValue(bone.nodeIndex).getTranslation()) < 0.001f
            )
        }
        scene.close()
    }

    @Test
    fun `rig bodies and aliases resolve to scene model nodes`() {
        val hand = NodeDefinition(1, "hand", mutableListOf(), TrsTransformF().translate(0f, 1f, 0f))
        val arm = NodeDefinition(0, "arm", mutableListOf(hand), TrsTransformF())
        hand.parent = arm
        val model = Model(0, listOf(ModelScene(listOf(arm))), emptySet())
        val collider = ColliderAttachmentSpec(id = "body", size = Vec3f(0.2f, 0.3f, 0.2f))
        val rig = ModelRig(
            bones = mapOf(
                "arm" to RigBone(attachments = listOf(collider, RigidBodyAttachmentSpec())),
                "hand" to RigBone(
                    alias = "handAlias",
                    attachments = listOf(collider, RigidBodyAttachmentSpec(), JointAttachmentSpec(parent = "arm"))
                ),
            )
        )
        val scene = Scene(object : SceneContext {
            override val side = LogicalSide.SERVER
        })
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val target = instance.poseTarget(rig)
        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.mask(BoneMask.full())))

        assertEquals(1, target.nodeIndex("handAlias"))
        assertEquals(listOf("arm", "hand"), plan.bones.map { it.name })
        assertEquals(listOf(-1, 0), plan.bones.map { it.parent })
        scene.close()
    }

    @Test
    fun `skinned scene uses source joint indices when building the ragdoll`() {
        val mesh = NodeDefinition(3, "mesh", mutableListOf(), TrsTransformF())
        val spine = NodeDefinition(2, "spine", mutableListOf(mesh), TrsTransformF().translate(0f, 0.5f, 0f))
        val hips = NodeDefinition(1, "hips", mutableListOf(spine), TrsTransformF().translate(0f, 0.5f, 0f))
        val skin = Skin(listOf(1, 2), arrayOf<Mat4f>(MutableMat4f(), MutableMat4f()))
        val root = NodeDefinition(0, "root", mutableListOf(hips), TrsTransformF(), skin = skin)
        mesh.parent = spine
        spine.parent = hips
        hips.parent = root
        val model = Model(0, listOf(ModelScene(listOf(root))), emptySet())
        val scene = Scene(object : SceneContext {
            override val side = LogicalSide.SERVER
        })
        val target = ModelSceneInstance.create(scene.create("owner"), "model", model).poseTarget()

        val plan = requireNotNull(RagdollPlan.build(target, RagdollStateSpec(), target.mask(BoneMask.full())))
        assertEquals(listOf("hips", "spine"), plan.bones.map { it.name })
        scene.close()
    }

    @Test
    fun `ragdoll rig generator uses scene geometry like the rendered model`() {
        val primitive = Primitive(
            positions = arrayOf(Vec3f(-0.2f, 0f, -0.2f), Vec3f(0.2f, 0.5f, 0.2f)),
            material = Material(),
        )
        val mesh = NodeDefinition(
            1,
            "mesh",
            mutableListOf(),
            TrsTransformF().translate(0f, 1f, 0f),
            Mesh(listOf(primitive), floatArrayOf())
        )
        val bone = NodeDefinition(0, "arm", mutableListOf(mesh), TrsTransformF())
        mesh.parent = bone
        val model = Model(0, listOf(ModelScene(listOf(bone))), emptySet())
        val scene = Scene(object : SceneContext {
            override val side = LogicalSide.SERVER
        })
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val legacy = ModelAttachment(MutableStateFlow(model), null)

        val sceneRig = RagdollRigGenerator.generate(RigGenerationSource(model, instance.poseNodes), ModelRig.EMPTY)
        assertEquals(
            RagdollRigGenerator.generate(
                RigGenerationSource(
                    model,
                    legacy.poseTarget().poseNodesByIndex.values
                ), ModelRig.EMPTY
            ), sceneRig
        )
        assertTrue(sceneRig.bone("arm")!!.attachments.any { it is RigidBodyAttachmentSpec })
        legacy.close()
        scene.close()
    }
}
