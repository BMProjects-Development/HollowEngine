import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockExpressions
import ru.hollowhorizon.hollowengine.client.models.bedrock.Query
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.particles.BedrockParticle
import ru.hollowhorizon.hollowengine.client.particles.ParticleEffect
import ru.hollowhorizon.hollowengine.client.particles.ParticleSystem
import ru.hollowhorizon.hollowengine.client.particles.collision.CollisionProvider
import ru.hollowhorizon.hollowengine.client.particles.file.BedrockParticleFile
import ru.hollowhorizon.hollowengine.client.particles.file.ParticleComponents
import ru.hollowhorizon.hollowengine.client.particles.light.LightProvider
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ParticleMolangContextTest {
    private fun system() =
        ParticleSystem(Random(0), { _, _, _ -> null }, { 0 }, Query.EMPTY)

    private fun effect(
        components: ParticleComponents = ParticleComponents(),
        referencedEffects: Map<String, ParticleEffect> = emptyMap(),
    ) = ParticleEffect(
        "test",
        "test",
        BedrockParticleFile.Material.Cutout,
        components,
        emptyMap(),
        emptyMap(),
        null,
        referencedEffects,
        emptyMap()
    )

    @Test
    fun `emitter creation updates and particle expressions read the supplied animator context`() {
        val source = AnimatorEvaluationContext().apply { variables["health"] = 7f }
        val components = ParticleComponents(
            emitterInitialization = ParticleComponents.EmitterInitialization(
                creationExpression = BedrockExpressions.parse("v.created = q.health"),
                perUpdateExpression = BedrockExpressions.parse("v.updated = q.health; t.counter = t.counter + 1"),
            ),
            particleInitialSpin = ParticleComponents.ParticleInitialSpin(
                rotation = BedrockExpressions.parse("q.health + v.created"),
            ),
        )
        val emitter = system().spawn(effect(components), context = source)
        assertSame(source, emitter.sourceContext)
        assertEquals(7f, emitter.context.variables["created"])
        assertEquals(14f, BedrockParticle(emitter, null).rotationAngle)
        source.variables["health"] = 10f
        emitter.update(0.1f)
        assertEquals(10f, emitter.context.variables["updated"])
        assertEquals(17f, BedrockParticle(emitter, null).rotationAngle)
        assertEquals(1f, emitter.context.temporaries["counter"])
    }

    @Test
    fun `particle clocks follow host time without changing the current model clip time`() {
        val source = AnimatorEvaluationContext().apply {
            time = 100f
            layerTime = 0.5f
        }
        val emitter = system().spawn(effect(), context = source)
        assertEquals(5f, BedrockExpressions.parse("q.anim_time").eval(emitter.context))
        source.time = 120f
        emitter.update(0.1f)
        assertEquals(6f, BedrockExpressions.parse("q.anim_time").eval(emitter.context))
        assertEquals(0.5f, source.layerTime)
    }

    @Test
    fun `emitters sharing a source keep their expression storage isolated`() {
        val source = AnimatorEvaluationContext().apply { variables["health"] = 3f }
        val system = system()
        val first = system.spawn(effect(), context = source)
        val second = system.spawn(effect(), context = source)
        BedrockExpressions.parse("v.local = 10; t.local = 20").eval(first.context)
        assertEquals(0f, BedrockExpressions.parse("v.local + t.local").eval(second.context))
        assertEquals(0f, BedrockExpressions.parse("v.local + t.local").eval(source.molang))
        assertEquals(3f, BedrockExpressions.parse("q.health").eval(second.context))
    }

    @Test
    fun `nested effect events retain their source context`() {
        val source = AnimatorEvaluationContext().apply { variables["health"] = 9f }
        val child = effect(
            ParticleComponents(
                emitterInitialization = ParticleComponents.EmitterInitialization(
                    creationExpression = BedrockExpressions.parse("v.created = q.health"),
                )
            )
        )
        val system = system()
        val parent = system.spawn(effect(referencedEffects = mapOf("child" to child)), context = source)
        parent.fire(
            0f, BedrockParticleFile.Event(
                particle = BedrockParticleFile.Event.Particle(
                    "child", BedrockParticleFile.Event.Particle.Type.EMITTER,
                    BedrockExpressions.parse("v.inherited = q.health"),
                )
            ), null
        )
        val nested = system.emitters.last()
        assertSame(source, nested.sourceContext)
        assertEquals(9f, nested.context.variables["created"])
        assertEquals(9f, nested.context.variables["inherited"])
    }
}
