import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockExpressions
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnimatorMolangContextTest {
    @Test
    fun `controller and Molang entity queries use the same preview values`() {
        val context = AnimatorEvaluationContext()
        val values = mapOf(
            "health" to 7f, "max_health" to 20f, "ground_speed" to -3f,
            "velocity_x" to 0.2f, "velocity_y" to -0.5f, "velocity_z" to 0.4f,
            "vertical_speed" to -10f, "head_x_rotation" to 25f, "head_y_rotation" to 90f,
            "modified_distance_moved" to 8f, "modified_move_speed" to -3f, "fall_ticks" to 4f,
            "is_alive" to 1f, "is_on_ground" to 0f, "is_moving" to 1f,
            "is_sneaking" to 1f, "is_sprinting" to 1f, "is_jumping" to 1f,
            "is_flying" to 1f, "is_swimming" to 1f, "is_in_water" to 1f,
            "is_in_water_or_rain" to 1f, "is_sitting" to 1f, "is_sleeping" to 1f,
            "is_hurt" to 1f, "is_swinging" to 1f,
        )
        context.variables.putAll(values)
        assertSame(context, context.molang.query)
        for ((name, expected) in values) {
            assertEquals(expected, BedrockExpressions.parse("q.$name").eval(context.molang), 0.0001f, name)
            assertTrue(AnimatorExpressionEvaluator.condition("q.$name == $expected", context), name)
        }
    }

    @Test
    fun `Molang seconds and retimed clip clocks preserve the controller tick clock`() {
        val context = AnimatorEvaluationContext().apply {
            time = 125f
            layerTime = 2f
        }
        assertEquals(6.25f, BedrockExpressions.parse("q.life_time").eval(context.molang))
        assertEquals(2f, BedrockExpressions.parse("q.anim_time").eval(context.molang))
        assertEquals(0.5f, BedrockExpressions.parse("q.anim_time").eval(context.molang.atAnimationTime(0.5f)))
        assertTrue(AnimatorExpressionEvaluator.condition("q.life_time == 125 && q.anim_time == 125", context))
        assertEquals(2f, context.layerTime)
    }

    @Test
    fun `reusing a context for an entity-free preview clears the previous host motion`() {
        val context = AnimatorEvaluationContext().apply {
            horizontalSpeed = 4f
            localForwardSpeed = 3f
            localSideSpeed = 2f
            signedHorizontalSpeed = 4f
            flightPitch = 5f
            flightBank = 6f
            bodyYaw = 20f
            headYaw = 25f
            headPitch = 10f
            headBodyYawDelta = 5f
            variables["custom"] = 12f
        }
        fillAnimationVariables(context, null, 0.5f, gameTicks = 40)
        assertEquals(0f, context.horizontalSpeed)
        assertEquals(0f, context.localForwardSpeed)
        assertEquals(0f, context.localSideSpeed)
        assertEquals(0f, context.ground_speed)
        assertEquals(0f, context.flightPitch)
        assertEquals(0f, context.flightBank)
        assertEquals(0f, context.bodyYaw)
        assertEquals(0f, context.head_y_rotation)
        assertEquals(0f, context.head_x_rotation)
        assertEquals(0f, context.headBodyYawDelta)
        assertEquals(40.5f, context.time)
        assertEquals(12f, context.variables["custom"])
    }
}
