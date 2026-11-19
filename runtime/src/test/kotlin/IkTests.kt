import ru.hollowhorizon.hollowengine.client.models.renderModelNode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.Scene
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.v2.*
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IkTests {
    /** A leg standing straight down: hip at the origin, knee one unit below, foot two. */
    private val leg = listOf(Vec3f(0f, 0f, 0f), Vec3f(0f, -1f, 0.01f), Vec3f(0f, -2f, 0f))

    @Test
    fun `two bones reach a goal within reach and keep their lengths`() {
        val goal = Vec3f(0f, -1.2f, 0.6f)
        val solved = IkSolver.solve(leg, goal, bendToward = Vec3f(0f, -1f, 5f), bendAxis = Vec3f.X_AXIS, maxStretch = 1f).joints

        assertClose(goal, solved[2])
        assertEquals(1f, solved[0].distance(solved[1]), 1e-3f)
        assertEquals(1f, solved[1].distance(solved[2]), 1e-3f)
        assertTrue(solved[1].z > 0f, "the knee bends toward the pole")
    }

    @Test
    fun `a goal out of reach straightens the chain toward it, or stretches it as far as allowed`() {
        val goal = Vec3f(0f, -3f, 0f)

        val rigid = IkSolver.solve(leg, goal, null, Vec3f.X_AXIS, maxStretch = 1f)
        assertEquals(1f, rigid.stretch)
        assertClose(Vec3f(0f, -2f, 0f), rigid.joints[2], tolerance = 1e-2f)

        val stretched = IkSolver.solve(leg, goal, null, Vec3f.X_AXIS, maxStretch = 2f)
        assertEquals(1.5f, stretched.stretch, 1e-3f)
        assertClose(goal, stretched.joints[2], tolerance = 1e-2f)
    }

    @Test
    fun `a limb whose pivots are not in line keeps its rest bend when pulled straight`() {
        // The elbow sits off the line from shoulder to hand, as block models place their pivots.
        val arm = listOf(Vec3f(0f, 0f, 0f), Vec3f(-0.2f, -1f, 0f), Vec3f(0f, -2f, 0f))
        val restReach = arm.first().distance(arm.last())
        val solved = IkSolver.solve(arm, Vec3f(5f, 0f, 0f), null, Vec3f.Z_AXIS, maxStretch = 1f, restReach = restReach).joints

        assertEquals(restReach, solved[0].distance(solved[2]), 1e-3f)
        assertEquals(arm[0].distance(arm[1]), solved[0].distance(solved[1]), 1e-3f)
        assertEquals(arm[1].distance(arm[2]), solved[1].distance(solved[2]), 1e-3f)
    }

    @Test
    fun `a knee whose pivot sits behind the leg still bends toward a pole in front`() {
        val leg = listOf(Vec3f(0f, 0f, 0f), Vec3f(0f, -1f, -0.1f), Vec3f(0f, -2f, 0f))
        val goal = Vec3f(0f, -1.5f, 0f)
        val solved = IkSolver.solve(leg, goal, bendToward = Vec3f(0f, -1f, 5f), bendAxis = Vec3f.X_AXIS, maxStretch = 1f,
            restReach = leg[0].distance(leg[2])).joints

        assertClose(goal, solved[2], tolerance = 1e-2f)
        val thigh = (solved[1] - solved[0]).normed()
        val shin = (solved[2] - solved[1]).normed()
        assertTrue(thigh.z > 0.2f, "thigh $thigh")
        assertTrue(shin.z < thigh.z, "shin $shin")
    }

    @Test
    fun `a longer chain reaches its goal without changing its lengths`() {
        val tail = (0..4).map { Vec3f(it.toFloat(), 0f, 0f) }
        val goal = Vec3f(2f, 2f, 0f)
        val solved = IkSolver.solve(tail, goal, null, Vec3f.Z_AXIS, maxStretch = 1f).joints

        assertClose(goal, solved.last(), tolerance = 1e-2f)
        solved.zipWithNext { a, b -> assertEquals(1f, a.distance(b), 1e-3f) }
    }

    @Test
    fun `a chain on model nodes puts its end on the target and keeps the end turned as animated`() {
        val foot = definition(2, "foot", Vec3f(0f, -1f, 0f))
        val knee = definition(1, "knee", Vec3f(0f, -1f, 0.01f), foot)
        val hip = renderModelNode(definition(0, "hip", Vec3f.ZERO, knee), parent = null)
        val nodes = hip.walk().associateBy { it.name }
        val footTurn = nodes.getValue("foot").sceneObject.modelRotation()

        val goal = Vec3f(0.3f, -1.5f, 0.4f)
        val rig = ModelRig(
            bones = mapOf(
                "foot" to RigBone(attachments = listOf(IkChainSpec(id = "leg", target = "step", weight = AnimationExpression.ONE))),
                "step" to RigBone(attachments = listOf(IkTargetSpec(id = "step"))),
            ),
        )
        val step = renderModelNode(definition(3, "step", goal), null)
        applyRigConstraintsToPose(hip.walk().map { it.sceneObject } + step.sceneObject, rig, AnimatorEvaluationContext())

        assertClose(goal, nodes.getValue("foot").sceneObject.modelMatrix().getTranslation(), tolerance = 1e-3f)
        assertEquals(1f, abs(footTurn.dot(nodes.getValue("foot").sceneObject.modelRotation())), 1e-4f)
    }

    @Test
    fun `bones the rig adds are built under their parents, and a chain can end on one`() {
        val leg = definition(0, "leg", Vec3f.ZERO)
        val rig = ModelRig(
            bones = mapOf(
                "foot" to RigBone(origin = RigBoneOrigin("leg", offset = Vec3f(0f, -1f, 0f))),
                "toe" to RigBone(
                    origin = RigBoneOrigin("foot", offset = Vec3f(0f, -0.5f, 0f)),
                    attachments = listOf(IkChainSpec(id = "reach", bones = 2, target = "spot")),
                ),
                "orphan" to RigBone(origin = RigBoneOrigin("missing")),
                "spot" to RigBone(origin = RigBoneOrigin(offset = Vec3f(0.5f, -0.5f, 0f)), attachments = listOf(IkTargetSpec("spot"))),
            ),
        )

        val model = ModelAttachment(MutableStateFlow(Model(0, listOf(Scene(listOf(leg))), emptySet())), null)
        model.rig = rig
        val roots = model.nodes
        val names = roots.flatMap { it.walk() }.map { it.name }
        assertEquals(setOf("leg", "foot", "toe", "spot"), names.toSet())

        model.applyRigConstraints(AnimatorEvaluationContext())
        val toe = roots.flatMap { it.walk() }.single { it.name == "toe" }
        assertClose(Vec3f(0.5f, -0.5f, 0f), toe.sceneObject.modelMatrix().getTranslation(), tolerance = 1e-2f)
        model.close()
    }

    @Test
    fun `a body lowered over a grounded leg bends it and keeps the foot where it stood, but only when drawn`() {
        fun leg(): RenderModelNode {
            val foot = definition(2, "foot", Vec3f(0f, -1f, 0f))
            val knee = definition(1, "knee", Vec3f(0f, -1f, 0.05f), foot)
            return renderModelNode(definition(0, "hip", Vec3f.ZERO, knee), parent = null).also { hip ->
                hip.transform.translation.set(Vec3f(0f, -0.3f, 0f))
                hip.transform.markDirty()
            }
        }
        val rig = ModelRig(bones = mapOf("foot" to RigBone(attachments = listOf(IkChainSpec(id = "leg", ground = true)))))

        val drawn = leg()
        applyRigConstraintsToPose(drawn.walk().map { it.sceneObject }, rig, AnimatorEvaluationContext(), RigGroundingState())
        val foot = drawn.walk().single { it.name == "foot" }.sceneObject.modelMatrix().getTranslation()
        val knee = drawn.walk().single { it.name == "knee" }.sceneObject.modelMatrix().getTranslation()
        assertEquals(-2f, foot.y, 1e-3f)
        assertClose(Vec3f(0f, 1f, 0f), (knee - foot).normed(), tolerance = 1e-2f)

        val forColliders = leg()
        applyRigConstraintsToPose(forColliders.walk().map { it.sceneObject }, rig, AnimatorEvaluationContext())
        assertEquals(-2.3f, forColliders.walk().single { it.name == "foot" }.sceneObject.modelMatrix().getTranslation().y, 1e-3f)
    }

    @Test
    fun `a grounded leg whose knee pivot sits behind it steps up bending toward its pole`() {
        val foot = definition(2, "foot", Vec3f(0f, -1f, 0.1f))
        val knee = definition(1, "knee", Vec3f(0f, -1f, -0.1f), foot)
        val hip = renderModelNode(definition(0, "hip", Vec3f.ZERO, knee), parent = null).apply {
            transform.translation.set(Vec3f(0f, -0.5f, 0f))
            transform.markDirty()
        }
        val rig = ModelRig(
            bones = mapOf(
                "foot" to RigBone(attachments = listOf(IkChainSpec(id = "leg", ground = true, pole = "front"))),
                "front" to RigBone(attachments = listOf(IkTargetSpec(id = "front"))),
            ),
        )
        val pole = renderModelNode(definition(3, "front", Vec3f(0f, -1f, 3f)), null)
        applyRigConstraintsToPose(hip.walk().map { it.sceneObject } + pole.sceneObject, rig, AnimatorEvaluationContext(), RigGroundingState())

        val nodes = hip.walk().associateBy { it.name }
        val placedFoot = nodes.getValue("foot").sceneObject.modelMatrix().getTranslation()
        val placedKnee = nodes.getValue("knee").sceneObject.modelMatrix().getTranslation()
        val placedHip = nodes.getValue("hip").sceneObject.modelMatrix().getTranslation()
        assertEquals(-2f, placedFoot.y, 1e-2f)
        assertTrue((placedKnee - placedHip).normed().z > 0.2f, "the thigh leans toward the pole: ${placedKnee - placedHip}")
    }

    @Test
    fun `removing or renaming an added bone carries the bones added under it along`() {
        val rig = ModelRig().withAddedBone("foot", "leg").withAddedBone("toe", "foot")

        assertEquals("heel", rig.withAddedBoneRenamed("foot", "heel").bones.getValue("toe").origin?.parent)
        assertTrue(rig.withoutAddedBone("foot").bones.isEmpty())
    }

    private fun definition(index: Int, name: String, translation: Vec3f, vararg children: NodeDefinition) = NodeDefinition(
        index = index,
        name = name,
        children = children.toMutableList(),
        transform = TrsTransformF().setCompositionOf(translation, QuatF.IDENTITY, Vec3f.ONES),
    )

    private fun assertClose(expected: Vec3f, actual: Vec3f, tolerance: Float = 1e-3f) {
        assertTrue(expected.distance(actual) <= tolerance, "expected $expected, got $actual")
    }
}
