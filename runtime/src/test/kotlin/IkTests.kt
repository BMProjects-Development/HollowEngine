import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.addRigBones
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigConstraints
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelMatrix
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelRotation
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.models.AnimationExpression
import ru.hollowhorizon.hollowengine.common.models.IkChainSpec
import ru.hollowhorizon.hollowengine.common.models.IkSolver
import ru.hollowhorizon.hollowengine.common.models.IkTargetSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.models.RigBoneOrigin
import ru.hollowhorizon.hollowengine.common.models.withAddedBone
import ru.hollowhorizon.hollowengine.common.models.withAddedBoneRenamed
import ru.hollowhorizon.hollowengine.common.models.withoutAddedBone
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
        val hip = RuntimeNode(definition(0, "hip", Vec3f.ZERO, knee), parent = null)
        val nodes = hip.walk().associateBy { it.name }
        val footTurn = nodes.getValue("foot").modelRotation()

        val goal = Vec3f(0.3f, -1.5f, 0.4f)
        val rig = ModelRig(
            bones = mapOf("foot" to RigBone(attachments = listOf(IkChainSpec(id = "leg", target = "step", weight = AnimationExpression.ONE)))),
            attachments = listOf(IkTargetSpec(id = "step", offset = goal)),
        )
        applyRigConstraints(listOf(hip), rig, AnimatorEvaluationContext())

        assertClose(goal, nodes.getValue("foot").modelMatrix().getTranslation(), tolerance = 1e-3f)
        assertEquals(1f, abs(footTurn.dot(nodes.getValue("foot").modelRotation())), 1e-4f)
    }

    @Test
    fun `bones the rig adds are built under their parents, and a chain can end on one`() {
        val leg = RuntimeNode(definition(0, "leg", Vec3f.ZERO), parent = null)
        val rig = ModelRig(
            bones = mapOf(
                "foot" to RigBone(origin = RigBoneOrigin("leg", offset = Vec3f(0f, -1f, 0f))),
                "toe" to RigBone(
                    origin = RigBoneOrigin("foot", offset = Vec3f(0f, -0.5f, 0f)),
                    attachments = listOf(IkChainSpec(id = "reach", bones = 2, target = "spot")),
                ),
                "orphan" to RigBone(origin = RigBoneOrigin("missing")),
            ),
            attachments = listOf(IkTargetSpec(id = "spot", offset = Vec3f(0.5f, -0.5f, 0f))),
        )

        val roots = addRigBones(listOf(leg), rig, holder = null)
        val names = roots.flatMap { it.walk() }.map { it.name }
        assertEquals(listOf("leg", "foot", "toe"), names)

        applyRigConstraints(roots, rig, AnimatorEvaluationContext())
        val toe = roots.flatMap { it.walk() }.single { it.name == "toe" }
        assertClose(Vec3f(0.5f, -0.5f, 0f), toe.modelMatrix().getTranslation(), tolerance = 1e-2f)
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
