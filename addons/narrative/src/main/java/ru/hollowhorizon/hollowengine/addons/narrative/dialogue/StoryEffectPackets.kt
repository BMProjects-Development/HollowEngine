package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import kotlinx.serialization.Serializable
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.addons.narrative.client.StoryCameraSystem
import ru.hollowhorizon.hollowengine.addons.narrative.client.StoryFadeOverlay
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class StoryFadePacket(
    private val alpha: Float,
    private val durationMillis: Long,
    private val color: Int,
) : HollowPacket {
    override fun handle(player: Player) = StoryFadeOverlay.fadeTo(alpha, durationMillis, color)
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
object StoryClearFadePacket : HollowPacket {
    override fun handle(player: Player) = StoryFadeOverlay.clear()
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class StoryCameraPacket(
    private val x: Float,
    private val y: Float,
    private val z: Float,
    private val pitch: Float,
    private val yaw: Float,
    private val roll: Float,
    private val fov: Float,
    private val durationMillis: Long,
) : HollowPacket {
    override fun handle(player: Player) = StoryCameraSystem.moveTo(
        CameraPose(Vec3f(x, y, z), Vec3f(pitch, yaw, roll), fov),
        durationMillis,
    )
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class StoryReleaseCameraPacket(private val durationMillis: Long) : HollowPacket {
    override fun handle(player: Player) = StoryCameraSystem.release(durationMillis)
}
