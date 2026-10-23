import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.bbmodel.*
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockExpressions
import ru.hollowhorizon.hollowengine.client.models.bedrock.Query
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockBenchMolangTests {
    private data class Fixture(val clip: AnimationClip, val node: RuntimeNode) {
        val target = PoseTarget(mapOf(node.definition.index to node), mapOf(clip.name to clip))
        val allowed = setOf(node.definition.index)
    }

    private fun fixture(
        x: String = "t.health = q.health; math.sin(q.anim_time * 180) * v.amplitude + t.health",
        y: String = "t.health",
        z: String = "q.life_time",
    ): Fixture {
        val file = BlockBenchModel(
            meta = BBMeta(version = "5.0"), elements = listOf(BBElement("bone", "bone")), animations = listOf(
                BBAnimation(
                    "expression", 1f, animators = mapOf(
                        "bone" to BBAnimator(
                            keyframes = listOf(BBKeyframe("position", 0f, dataPoints = listOf(buildJsonObject {
                                put("x", x)
                                put("y", y)
                                put("z", z)
                            }))),
                        )
                    )
                )
            )
        )
        val model = BlockBenchModelLoader.convert(file, "test:models/molang.bbmodel".rl, ModelSide.SERVER)
        return Fixture(model.animations.single(), RuntimeNode(model.findNodeByName("bone")!!, null))
    }

    private fun context(ticks: Float = 100f) = AnimatorEvaluationContext().also {
        it.time = ticks
        it.deltaTime = 0.5f
        it.variables["health"] = 10f
        it.variables["amplitude"] = 2f
    }

    private fun assertExpression(fixture: Fixture, pose: AnimationPose, expectedX: Float = 12f) {
        val value = pose[fixture.node.definition.index]!!.translation!!
        assertEquals(expectedX, value.x, 0.0001f)
        assertEquals(10f, value.y, 0.0001f)
        assertEquals(5f, value.z, 0.0001f)
    }

    @Test
    fun `a clip layer forwards queries variables temporaries and clip time to imported Molang`() {
        val fixture = fixture()
        val layer = ClipLayer(ClipAnimationLayerSpec(animation = fixture.clip.name, playMode = AnimationPlayMode.Loop))
        assertExpression(fixture, layer.sample(fixture.target, context())!!.pose)
    }

    @Test
    fun `controller clip states use the same Molang context`() {
        val fixture = fixture()
        val state =
            ClipState(ClipStateSpec(id = "state", animation = fixture.clip.name, playMode = AnimationPlayMode.Loop))
        assertExpression(fixture, state.sample(fixture.target, fixture.allowed, context())!!)
    }

    @Test
    fun `blend states pass their individual clip sample time to Molang`() {
        val fixture = fixture()
        val state = BlendState(BlendStateSpec("blend", motions = listOf(BlendMotion(fixture.clip.name))))
        assertExpression(fixture, state.sample(fixture.target, fixture.allowed, context())!!)
    }

    @Test
    fun `montage clips use the offset inside the imported clip instead of montage time`() {
        val fixture = fixture()
        val montage = Montage(clips = listOf(MontageClip("clip", fixture.clip.name, from = 0.25f)))
        assertExpression(
            fixture,
            MontageSampler.sample(montage, 0.25f, fixture.target, fixture.allowed, context().molang)
        )
    }

    @Test
    fun `an additive reference pose receives the same query state at its own time`() {
        val fixture = fixture()
        val layer = ClipLayer(
            ClipAnimationLayerSpec(
                animation = fixture.clip.name, referencePose = fixture.clip.name, playMode = AnimationPlayMode.Loop
            )
        )
        val sample = layer.sample(fixture.target, context())!!
        assertExpression(fixture, sample.pose)
        assertExpression(fixture, sample.reference!!, expectedX = 10f)
    }

    @Test
    fun `model variables persist across frame contexts and stay isolated between model instances`() {
        val fixture = fixture("v.counter = v.counter + 1; v.counter", "t.counter = t.counter + 1; t.counter")
        fun animator() = ModelAnimator().apply {
            configure(
                Animator(
                    layers = listOf(
                        ClipAnimationLayerSpec(
                            animation = fixture.clip.name, playMode = AnimationPlayMode.Loop
                        )
                    )
                ), null
            )
        }

        fun sample(animator: ModelAnimator, ticks: Float): Vec3f {
            fixture.node.resetPose()
            animator.applyTo(fixture.target, context(ticks))
            return Vec3f(fixture.node.transform.translation)
        }

        val first = animator()
        assertEquals(Vec3f(1f, 1f, 5f), sample(first, 100f))
        assertEquals(Vec3f(2f, 1f, 5.05f), sample(first, 101f))
        assertEquals(Vec3f(1f, 1f, 5f), sample(animator(), 100f))
        val shared = context()
        fixture.node.resetPose()
        first.applyTo(fixture.target, shared)
        assertEquals(3f, fixture.node.transform.translation.x)
        fixture.node.resetPose()
        animator().applyTo(fixture.target, shared)
        assertEquals(1f, fixture.node.transform.translation.x)
    }

    @Test
    fun `standalone sampling never writes variables into a shared global context`() {
        val fixture = fixture("v.counter = v.counter + 1; v.counter", "0", "0")
        repeat(2) {
            assertEquals(1f, AnimationPose.sample(fixture.clip, 0f)[fixture.node.definition.index]!!.translation!!.x)
            assertEquals(1f, fixture.clip.nodes.values.single().translation!!.compute(0f).x)
        }
    }

    @Test
    fun `animator conditions can read variables written by an imported clip`() {
        val fixture = fixture("v.result = q.health + 3; v.result", "0", "0")
        val animator = ModelAnimator().apply {
            configure(Animator(layers = listOf(ClipAnimationLayerSpec(animation = fixture.clip.name))), null)
        }
        val context = context()
        animator.applyTo(fixture.target, context)
        assertEquals(13f, context.variable("result"))
        assertEquals(13f, context.molang.variables["result"])
        assertTrue(AnimatorExpressionEvaluator.condition("v.result == 13", context))
    }

    @Test
    fun `retiming a Bedrock context preserves variable and temporary storage`() {
        val context = BedrockContext(object : Query {
            override val health = 7f
        })
        context.variables["amplitude"] = 3f
        context.temporaries["input"] = 5f
        val timed = context.atAnimationTime(2f)
        assertEquals(17f, BedrockExpressions.parse("q.health + v.amplitude + t.input + q.anim_time").eval(timed))
        BedrockExpressions.parse("v.output = t.input; t.output = q.anim_time").eval(timed)
        assertEquals(5f, context.variables["output"])
        assertEquals(2f, context.temporaries["output"])
    }

    @Test
    fun `temporary storage is cleared when a frame is filled`() {
        val context = context()
        context.temporaries["old"] = 10f
        fillAnimationVariables(context, entity = null, partialTick = 0f)
        assertEquals(0f, BedrockExpressions.parse("t.old").eval(context.molang))
    }
}
