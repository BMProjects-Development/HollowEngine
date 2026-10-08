import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.BlendState
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.common.models.AnimationExpression
import ru.hollowhorizon.hollowengine.common.models.BlendMotion
import ru.hollowhorizon.hollowengine.common.models.BlendStateSpec
import ru.hollowhorizon.hollowengine.common.models.BlendWeights
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlendStateTests {
    @Test
    fun `linear weights share between the two points around the value and clamp past the ends`() {
        val points = listOf(4f, 0f, 1f)

        assertWeights(floatArrayOf(0f, 0.5f, 0.5f), BlendWeights.linear(points, 0.5f))
        assertWeights(floatArrayOf(1f / 3f, 0f, 2f / 3f), BlendWeights.linear(points, 2f))
        assertWeights(floatArrayOf(0f, 1f, 0f), BlendWeights.linear(points, -3f))
        assertWeights(floatArrayOf(1f, 0f, 0f), BlendWeights.linear(points, 9f))
    }

    @Test
    fun `a direction between two points is shared by them alone`() {
        val weights = BlendWeights.planar(locomotion, 0.7071f, 0.7071f)

        assertWeights(floatArrayOf(0f, 0.5f, 0.5f, 0f, 0f), weights)
    }

    @Test
    fun `speed below the outer points blends them with the center`() {
        assertWeights(floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f), BlendWeights.planar(locomotion, 0f, 0.5f))
        assertWeights(floatArrayOf(0f, 0f, 0f, 1f, 0f), BlendWeights.planar(locomotion, -1f, 0f))
    }

    @Test
    fun `planar weights always sum to one`() {
        for (x in -4..4) for (y in -4..4) {
            val weights = BlendWeights.planar(locomotion, x * 0.37f, y * 0.37f)
            assertEquals(1f, weights.sum(), 0.0001f, "at ($x, $y)")
            assertTrue(weights.all { it >= 0f })
        }
    }

    @Test
    fun `clips of different lengths play in step`() {
        val node = RuntimeNode(NodeDefinition(index = 0, name = "Bone", children = mutableListOf(), transform = TrsTransformF()), parent = null)
        val target = PoseTarget(
            mapOf(0 to node),
            mapOf("walk" to timeClip("walk", 1f), "run" to timeClip("run", 0.5f)),
        )
        val state = BlendState(
            BlendStateSpec(
                id = "move",
                motions = listOf(BlendMotion("walk", x = 0f), BlendMotion("run", x = 1f)),
                x = AnimationExpression("0.5"),
            )
        )
        val context = AnimatorEvaluationContext().also { it.deltaTime = 0f }
        state.enter(null)
        state.sample(target, setOf(0), context)

        // Halfway between a one-second walk and a half-second run a cycle lasts 0.75 s; 0.375 s in, both
        // are half way through themselves, at 0.5 s and 0.25 s, and the blend lands between the two.
        context.deltaTime = 0.375f
        val pose = state.sample(target, setOf(0), context)!!

        assertEquals(0.375f, pose[0]!!.translation!!.x, 0.0001f)
        assertEquals(0.375f, state.time, 0.0001f)
    }

    @Test
    fun `opposite signs of the same rotation do not cancel out`() {
        val turn = QuatF(0f, 0.7071f, 0f, 0.7071f)
        val flipped = QuatF(-turn.x, -turn.y, -turn.z, -turn.w)
        val first = AnimationPose().also { it.bone(0).rotation = turn }
        val second = AnimationPose().also { it.bone(0).rotation = flipped }

        val blended = AnimationPose.blend(listOf(first, second), listOf(0.5f, 0.5f))[0]!!.rotation!!

        assertEquals(1f, abs(blended.dot(turn)), 0.0001f)
    }

    /** Idle at the center, then forward, right, left and back at walking speed. */
    private val locomotion = listOf(
        BlendMotion("idle", 0f, 0f),
        BlendMotion("forward", 0f, 1f),
        BlendMotion("right", 1f, 0f),
        BlendMotion("left", -1f, 0f),
        BlendMotion("back", 0f, -1f),
    )

    private fun assertWeights(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 0.001f, "weight $it of ${actual.toList()}") }
    }

    /** A clip that moves the bone along x by the time it is sampled at, so the pose shows where it played. */
    private fun timeClip(name: String, duration: Float) = AnimationClip(
        name = name,
        nodes = mapOf(0 to AnimationData(translation = TimeInterpolator(duration), rotation = null, scale = null, weights = null)),
        duration = duration,
    )

    private class TimeInterpolator(override val duration: Float) : Interpolator<Vec3f> {
        override fun compute(time: Float, context: BedrockContext): Vec3f = Vec3f(time, 0f, 0f)
    }
}
