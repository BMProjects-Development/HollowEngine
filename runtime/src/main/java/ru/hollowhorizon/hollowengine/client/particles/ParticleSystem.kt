package ru.hollowhorizon.hollowengine.client.particles

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.level.Level
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.models.bedrock.Query
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.particles.collision.CollisionProvider
import ru.hollowhorizon.hollowengine.client.particles.collision.WorldCollisionProvider
import ru.hollowhorizon.hollowengine.client.particles.file.BedrockParticleFile
import ru.hollowhorizon.hollowengine.client.particles.light.LightProvider
import ru.hollowhorizon.hollowengine.client.particles.light.WorldLightProvider
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.client.utils.math.rotateSelfBy
import ru.hollowhorizon.hollowengine.client.utils.use
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.*

class ParticleSystem(
    val random: Random,
    val collisionProvider: CollisionProvider,
    val lightProvider: LightProvider,
    private val timeSource: Query = Query.GLFW_TIME,
) {
    private var lastUpdate = timeSource.anim_time

    internal val emitters = mutableListOf<ParticleEmitter>()
    val billboardRenderPasses = mutableMapOf<ParticleEffect.RenderPass, MutableSet<BedrockParticle>>()

    companion object {
        fun create(level: Level) = ParticleSystem(Random(), WorldCollisionProvider(level), WorldLightProvider(level))
    }

    fun remove(name: String) {
        emitters.removeIf { emitter ->
            val shouldRemove = emitter.effect.identifier == name
            if (shouldRemove) emitter.particles.forEach { emitter.onRemove(it) }
            shouldRemove
        }
    }

    fun remove(emitter: ParticleEmitter) {
        emitter.particles.forEach { emitter.onRemove(it) }
        emitters.remove(emitter)
    }

    fun spawn(
        effect: BedrockParticleFile,
        context: AnimatorEvaluationContext = AnimatorEvaluationContext(),
        transform: Transform = Transform.Zero,
    ) = spawn(ParticleEffect.fromFile(effect), context, transform)

    fun spawn(
        effect: ParticleEffect,
        context: AnimatorEvaluationContext = AnimatorEvaluationContext(),
        transform: Transform = Transform.Zero,
    ): ParticleEmitter {
        val emitter =
            ParticleEmitter(this, effect, context, transform.position, transform.rotation, transform.velocity, transform)
        emitters.add(emitter)

        val dt = (lastUpdate - timeSource.anim_time).coerceAtLeast(0f)
        emitter.startLoop(dt)

        emitter.update(dt)

        return emitter
    }

    fun update() {
        val now = timeSource.anim_time
        val dt = now - lastUpdate
        lastUpdate = now

        emitters.removeIf { !it.update(dt) }
    }

    fun isEmpty() = emitters.isEmpty()
    fun hasAnythingToRender() = billboardRenderPasses.isNotEmpty()

    fun render(
        stack: PoseStack,
        cameraPos: Vec3f,
        cameraRot: QuatF,
        particleVertexConsumerProvider: VertexConsumerProvider,
        cameraUuid: UUID,
        isFirstPerson: Boolean,
    ) = stack.use {
        mulPose(Matrix4f().translate(-cameraPos.x, -cameraPos.y, -cameraPos.z))

        val cameraFacing = Vec3f(0f, 0f, -1f).rotateBy(cameraRot)
        for ((renderPass, particles) in billboardRenderPasses.entries.sortedBy { it.key.material.needsSorting }) {
            particleVertexConsumerProvider.provide(renderPass) { vertexConsumer ->
                drawParticles(
                    particles,
                    this,
                    vertexConsumer,
                    cameraPos,
                    cameraRot,
                    cameraFacing,
                    cameraUuid,
                    isFirstPerson,
                    renderPass.material.needsSorting
                )
            }
        }
    }


    private fun drawParticles(
        particles: MutableSet<BedrockParticle>,
        stack: PoseStack,
        vertexConsumer: VertexConsumer,
        cameraPos: Vec3f,
        cameraRot: QuatF,
        facing: Vec3f,
        uuid: UUID,
        isFirstPersion: Boolean,
        sort: Boolean = false,
    ) {
        if (sort) calculateDistance(particles, cameraPos, cameraRot)

        particles.sortedByDescending { it.distance }.forEach { particle ->
            if (!sort) particle.prepareBillboard(cameraPos, cameraRot)
            particle.renderBillboard(stack, vertexConsumer, facing, uuid, isFirstPersion)
        }
    }

    private fun calculateDistance(
        particles: MutableSet<BedrockParticle>,
        cameraPos: Vec3f,
        cameraRot: QuatF,
    ) {
        particles.forEach { particle ->
            particle.prepareBillboard(cameraPos, cameraRot)

            val billboardNormal = Vec3f(0f, 0f, -1f).rotateSelfBy(particle.billboardRotation)
            particle.distance = cameraPos.minus(particle.billboardPosition).dot(billboardNormal)
        }
    }

}

