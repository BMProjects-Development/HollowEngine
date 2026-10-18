package ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects

import kotlinx.serialization.Serializable
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.api.system
import ru.hollowhorizon.hollowengine.client.models.bedrock.LivingEntityQuery
import ru.hollowhorizon.hollowengine.client.particles.BedrockParticles
import ru.hollowhorizon.hollowengine.client.particles.ParticleEffect
import ru.hollowhorizon.hollowengine.client.particles.Transform
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.network.sendAllInDimension
import ru.hollowhorizon.hollowengine.common.network.sendTrackingEntity
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForEntity
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForVec3
import ru.hollowhorizon.hollowengine.common.utils.rl

fun Level.bedrockParticles(pos: Vec3, location: String) {
    SpawnParticlesPacket(location, pos).sendAllInDimension(this)
}

fun LivingEntity.bedrockParticles(location: String) {
    SpawnParticlesPacket(location, entity = this).sendTrackingEntity(this)
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
class SpawnParticlesPacket(
    val location: String,
    val pos: @Serializable(ForVec3::class) Vec3? = null,
    val entity: @Serializable(ForEntity::class) Entity? = null,
) : HollowPacket {
    override fun handle(player: Player) {
        val renderer = player.level() as? ClientLevel ?: return

        if (pos != null) {
            renderer.system.spawn(
                ParticleEffect.fromFile(
                    BedrockParticles.PARTICLES[location.rl] ?: error("Particle $location not found!")
                ),
                transform = Transform.create(Vec3f(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat()))
            )
        }
        if (entity is LivingEntity) {
            renderer.system.spawn(
                effect = BedrockParticles.PARTICLES[location.rl] ?: error("Particle $location not found!"),
                entity = LivingEntityQuery(entity)
            )
        }
    }

}
