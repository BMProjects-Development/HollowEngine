package ru.hollowhorizon.hollowengine.client.vfx

import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VfxSubEmitterTest {
    private fun effect(event: VfxParticleEvent, count: Float) = VfxEffect(
        nodes = listOf(
            VfxEmitterSpec(
                id = "rockets",
                transform = VfxTransform(position = Vec3f(2f, 0f, 0f)),
                emission = VfxEmission(rate = VfxValue.ZERO, burst = 4, duration = 5f, loop = false),
                spawn = VfxSpawn(lifetime = VfxValue.Const(0.1f), speed = VfxValue.ZERO),
                children = listOf(
                    VfxEmitterSpec(
                        id = "sparks",
                        // A sub-emitter spawns where the particle is, not at its own place.
                        transform = VfxTransform(position = Vec3f(0f, 5f, 0f)),
                        emission = VfxEmission(rate = VfxValue.Const(100f), burst = 50),
                        spawn = VfxSpawn(lifetime = VfxValue.Const(5f), speed = VfxValue.ZERO),
                        subEmission = VfxSubEmission(event = event, count = VfxValue.Const(count)),
                    )
                ),
            )
        ),
    )

    private fun VfxInstance.run(seconds: Float) {
        repeat((seconds * 60f).toInt()) { update(1f / 60f) }
    }

    @Test
    fun `every dying particle spawns its count where it died, and nothing else is emitted`() {
        val instance = VfxInstance(effect(VfxParticleEvent.DEATH, 3f), "test")
        instance.update(1f / 60f)
        val (rockets, sparks) = instance.emitters
        val rocketRandoms = rockets.particles.random.take(rockets.particles.count).toSet()
        instance.run(0.5f)

        assertEquals(0, rockets.particles.count)
        assertEquals(12, sparks.particles.count)
        for (slot in 0 until sparks.particles.count) {
            assertEquals(2f, sparks.particles.positionX[slot], 1e-4f)
            assertEquals(0f, sparks.particles.positionY[slot], 1e-4f)
        }
        val parents = sparks.particles.parentRandom.take(sparks.particles.count)
        assertEquals(rocketRandoms, parents.toSet())
        assertTrue(parents.groupingBy { it }.eachCount().values.all { it == 3 })
    }

    @Test
    fun `a living particle spawns its count per second`() {
        val alive = effect(VfxParticleEvent.ALIVE, 60f).let { effect ->
            val rockets = effect.nodes.single() as VfxEmitterSpec
            effect.copy(nodes = listOf(rockets.copy(spawn = rockets.spawn.copy(lifetime = VfxValue.Const(1f)))))
        }
        val instance = VfxInstance(alive, "test")
        instance.run(0.5f)

        val sparks = instance.emitters[1].particles.count
        assertTrue(sparks in 116..124, "4 particles for half a second at 60 per second, got $sparks")
    }
}
