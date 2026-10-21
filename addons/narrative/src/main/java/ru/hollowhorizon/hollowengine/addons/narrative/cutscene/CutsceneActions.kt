package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneAnchor
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneAnchorKind
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneCameraSystem
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneStorage
import ru.hollowhorizon.hollowengine.common.network.HollowAddonPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler

fun Player.playCutscene(path: String, loop: Boolean = false) {
    sendCutscene(path, loop, CutsceneAnchor.WHERE_RECORDED)
}

/**
 * Plays cutscene around the block's position. The [yaw] parameter rotates the entire scene.
 */
fun Player.playCutsceneAt(
    path: String,
    pos: BlockPos,
    yaw: Float = 0f,
    loop: Boolean = false,
    rotate: Boolean = true,
) {
    playCutsceneAt(path, Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5), yaw, loop, rotate)
}

fun Player.playCutsceneAt(
    path: String,
    pos: Vec3,
    yaw: Float = 0f,
    loop: Boolean = false,
    rotate: Boolean = true,
) {
    sendCutscene(
        path, loop,
        CutsceneAnchor(
            kind = CutsceneAnchorKind.BLOCK,
            x = pos.x.toFloat(),
            y = pos.y.toFloat(),
            z = pos.z.toFloat(),
            yaw = yaw,
            rotate = rotate,
        ),
    )
}

/**
 * Plays the cutscene around [target] (NPC, mob, vehicle, etc.)
 */
fun Player.playCutsceneNear(
    path: String,
    target: Entity,
    loop: Boolean = false,
    follow: Boolean = true,
    rotate: Boolean = true,
) {
    sendCutscene(
        path, loop,
        CutsceneAnchor(
            kind = CutsceneAnchorKind.ENTITY,
            entityId = target.id,
            follow = follow,
            rotate = rotate,
        ),
    )
}

fun Player.playCutsceneHere(
    path: String,
    loop: Boolean = false,
    follow: Boolean = false,
    rotate: Boolean = true,
) {
    sendCutscene(
        path, loop,
        CutsceneAnchor(kind = CutsceneAnchorKind.PLAYER, follow = follow, rotate = rotate),
    )
}

fun Player.stopCutscene() {
    val player = this as? ServerPlayer ?: return
    StopCutscenePacket.send(player)
}

private fun Player.sendCutscene(path: String, loop: Boolean, anchor: CutsceneAnchor) {
    val player = this as? ServerPlayer ?: return
    PlayCutscenePacket(path, loop, anchor).send(player)
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class PlayCutscenePacket(
    val path: String,
    val loop: Boolean = false,
    val anchor: CutsceneAnchor = CutsceneAnchor.WHERE_RECORDED,
) : HollowAddonPacket {
    override fun handle(player: Player) {
        val data = CutsceneStorage.load(path)
        CutsceneCameraSystem.play(data, loop, anchor)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
object StopCutscenePacket : HollowAddonPacket {
    override fun handle(player: Player) {
        CutsceneCameraSystem.stop()
    }
}
