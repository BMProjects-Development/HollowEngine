package ru.hollowhorizon.hollowengine.common.colliders

import ru.hollowhorizon.hollowengine.client.models.renderModelNode
import ru.hollowhorizon.hollowengine.client.models.renderModel
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.v2.BaseAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.models.internal.Scene as ModelScene
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.IkChainSpec
import ru.hollowhorizon.hollowengine.common.models.IkTargetSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.models.RigBoneOrigin
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.math.abs

class ColliderScenePoseTest {
    @Test
    fun `nested collider matrix matches renderer with rotation and scale`() {
        val outerNode = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF().apply {
            translate(1f, 2f, 0f)
            rotate(35f.deg, Vec3f.Y_AXIS)
        })
        val innerNode = NodeDefinition(0, "blade", mutableListOf(), TrsTransformF().translate(0f, 0f, 1f))
        val outer = Model(0, listOf(ModelScene(listOf(outerNode))), emptySet())
        val inner = Model(0, listOf(ModelScene(listOf(innerNode))), emptySet())
        val spec = ModelAttachmentSpec(id = "sword", model = "test:sword")
        val placement = RigBoneOrigin("hand", Vec3f(1f, 0f, 0f), Vec3f(0f, 45f, 0f), Vec3f(1.5f, 1.5f, 1.5f))
        val rig = ModelRig(bones = mapOf("sword" to RigBone(attachments = listOf(spec), origin = placement)))
        val innerRig = ModelRig(bones = mapOf("blade" to RigBone(attachments = listOf(ColliderAttachmentSpec(id = "blade")))))
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val posed = PosedModel(outer, rig, scene.create("entity"), "model", { ColliderPoseAssets(innerRig, null, inner) })

        val legacy = renderModelNode(outerNode, null)
        legacy.updateGlobalMatrix()
        val holder = object : BaseAttachment(legacy) {}
        holder.transform.set(placement.localTransform())
        holder.updateGlobalMatrix()
        val blade = renderModelNode(innerNode, holder)
        blade.updateGlobalMatrix()
        val expected = blade.globalMatrix.getTranslation()
        val actual = posed.placeColliders(MutableMat4f(), Vec3.ZERO).single().volume.center

        assertEquals(expected.x.toDouble(), actual.x, 1e-5)
        assertEquals(expected.y.toDouble(), actual.y, 1e-5)
        assertEquals(expected.z.toDouble(), actual.z, 1e-5)
        scene.close()
        blade.close()
        legacy.close()
    }

    @Test
    fun `server scene stretch matches renderer pose including side branches`() {
        val foot = NodeDefinition(2, "foot", mutableListOf(), TrsTransformF().translate(0f, -1f, 0f))
        val knee = NodeDefinition(1, "knee", mutableListOf(foot), TrsTransformF().translate(0f, -1f, 0.01f))
        val tip = NodeDefinition(3, "tip", mutableListOf(), TrsTransformF().translate(0f, -0.5f, 0f))
        val hip = NodeDefinition(0, "hip", mutableListOf(knee, tip), TrsTransformF())
        foot.parent = knee
        knee.parent = hip
        tip.parent = hip
        val model = Model(0, listOf(ModelScene(listOf(hip))), emptySet())
        val rig = ModelRig(
            bones = mapOf(
                "foot" to RigBone(attachments = listOf(IkChainSpec(id = "leg", target = "step", stretch = 2f))),
                "step" to RigBone(origin = RigBoneOrigin(offset = Vec3f(0f, -3f, 0f)), attachments = listOf(IkTargetSpec("step"))),
            ),
        )
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val posed = PosedModel(model, rig, scene.create("entity"), "model", { null })
        val drawn = renderModel(hip).apply { this.rig = rig }
        posed.applyRigConstraints(AnimatorEvaluationContext())
        drawn.applyRigConstraints(AnimatorEvaluationContext())

        val sceneNodes = posed.poseNodes.associateBy { it.poseName }
        drawn.nodes.flatMap { it.walk() }.forEach { node ->
            val actual = sceneNodes.getValue(node.name).localTransform
            assertTrue(Vec3f(node.transform.translation).distance(Vec3f(actual.translation)) < 1e-4f, node.name)
            assertTrue(Vec3f(node.transform.scale).distance(Vec3f(actual.scale)) < 1e-4f, node.name)
            assertEquals(1f, abs(node.transform.rotation.dot(actual.rotation)), 1e-4f, node.name)
        }
        scene.close()
        drawn.close()
    }

    @Test
    fun `server collider follows ik target on scene nodes`() {
        val foot = NodeDefinition(2, "foot", mutableListOf(), TrsTransformF().translate(0f, -1f, 0f))
        val knee = NodeDefinition(1, "knee", mutableListOf(foot), TrsTransformF().translate(0f, -1f, 0.01f))
        val hip = NodeDefinition(0, "hip", mutableListOf(knee), TrsTransformF())
        foot.parent = knee
        knee.parent = hip
        val model = Model(0, listOf(ModelScene(listOf(hip))), emptySet())
        val goal = Vec3f(0.3f, -1.5f, 0.4f)
        val rig = ModelRig(
            bones = mapOf(
                "foot" to RigBone(attachments = listOf(ColliderAttachmentSpec(id = "foot"), IkChainSpec(id = "leg", target = "step"))),
                "step" to RigBone(origin = RigBoneOrigin(offset = goal), attachments = listOf(IkTargetSpec("step"))),
            ),
        )
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val posed = PosedModel(model, rig, scene.create("entity"), "model", { null })
        posed.resetPose()
        posed.applyRigPoses()
        posed.applyRigConstraints(AnimatorEvaluationContext())
        posed.updateMatrices()

        val center = posed.placeColliders(MutableMat4f(), Vec3.ZERO).single().volume.center
        assertEquals(goal.x.toDouble(), center.x, 1e-3)
        assertEquals(goal.y.toDouble(), center.y, 1e-3)
        assertEquals(goal.z.toDouble(), center.z, 1e-3)
        scene.close()
    }

    @Test
    fun `added bones and nested model colliders follow scene pose on server`() {
        fun model(name: String, translation: Vec3f): Model {
            val node = NodeDefinition(0, name, mutableListOf(), TrsTransformF().translate(translation.x, translation.y, translation.z))
            return Model(0, listOf(ModelScene(listOf(node))), emptySet())
        }
        val outer = model("arm", Vec3f(1f, 1f, 0f))
        val inner = model("blade", Vec3f(0f, 0f, 1f))
        val innerRig = ModelRig(bones = mapOf("blade" to RigBone(attachments = listOf(ColliderAttachmentSpec(id = "blade")))))
        val rig = ModelRig(bones = mapOf(
            "hand" to RigBone(
                origin = RigBoneOrigin("arm", offset = Vec3f(1f, 0f, 0f)),
                attachments = listOf(ColliderAttachmentSpec(id = "hand")),
            ),
            "sword" to RigBone(
                origin = RigBoneOrigin("hand", offset = Vec3f(0f, 1f, 0f)),
                attachments = listOf(ModelAttachmentSpec(id = "sword", model = "test:sword")),
            ),
        ))
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val owner = scene.create("entity")
        val posed = PosedModel(outer, rig, owner, "model", { ColliderPoseAssets(innerRig, null, inner) })

        posed.resetPose()
        posed.applyRigPoses()
        posed.updateMatrices()
        val initial = posed.placeColliders(MutableMat4f(), Vec3.ZERO).associateBy { it.name }
        assertEquals(Vec3(2.0, 1.0, 0.0), initial.getValue("hand").volume.center)
        assertEquals(Vec3(2.0, 2.0, 1.0), initial.getValue("blade").volume.center)
        val hand = posed.poseNodes.single { it.poseName == "hand" }
        hand.localTransform.translate(0f, 0f, 2f)
        val moved = posed.placeColliders(MutableMat4f(), Vec3.ZERO).associateBy { it.name }
        assertEquals(Vec3(2.0, 1.0, 2.0), moved.getValue("hand").volume.center)
        assertEquals(Vec3(2.0, 2.0, 3.0), moved.getValue("blade").volume.center)

        hand.setEnabled(false)
        assertTrue(posed.placeColliders(MutableMat4f(), Vec3.ZERO).isEmpty())
        hand.setEnabled(true)
        assertEquals(2, posed.placeColliders(MutableMat4f(), Vec3.ZERO).size)

        posed.close()
        assertTrue(owner.children.isEmpty())
        scene.close()
    }
}
