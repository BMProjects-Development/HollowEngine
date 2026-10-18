package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.vfx.VfxScenes
import ru.hollowhorizon.hollowengine.common.data.DataKey
import ru.hollowhorizon.hollowengine.common.data.NbtDataStore
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.network.sendAllInDimension
import ru.hollowhorizon.hollowengine.common.network.sendTrackingEntityAndSelf
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForEntity
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForVec3
import java.util.concurrent.atomic.AtomicInteger

/**
 * A running effect, as the side that started it sees it.
 *
 * The store is an ordinary [NbtDataStore] with [DataKey]s.
 */
class VfxHandle internal constructor(
    val id: Int,
    val effect: String,
    private val level: Level,
    private val entity: Entity?,
    private var position: Vec3?,
) {
    val data = NbtDataStore()

    var isStopped: Boolean = false
        private set

    init {
        data.onChange = { pushData() }
    }

    /** Moves a world-anchored effect. Does nothing for one that follows an entity. */
    fun moveTo(next: Vec3) {
        if (entity != null || isStopped) return
        position = next
        broadcast(VfxMovePacket(id, next))
    }

    /**
     * Stops the effect: emitters shut down and what is already alive lives out its lifetime, unless
     * [immediate], which clears everything this frame.
     */
    fun stop(immediate: Boolean = false) {
        if (isStopped) return
        isStopped = true
        broadcast(VfxStopPacket(id, immediate))
    }

    /**
     * Moves the timeline of the effect to its event [name] and plays on from there, on every client that shows it.
     * Rules of the timeline's sections are answered on the clients, which play the effect.
     */
    fun jumpTo(name: String) {
        if (isStopped) return
        broadcast(VfxJumpPacket(id, name))
    }

    /** Writes a value the effect expressions can read as `d.<name>`. */
    operator fun <T : Any> set(key: DataKey<T>, value: T) {
        data[key] = value
    }

    operator fun <T : Any> get(key: DataKey<T>): T? = data[key]

    /** Sends the whole store again, for a client that has just started tracking this effect. */
    fun update() {
        if (isStopped) return
        broadcast(playPacket())
    }

    internal fun playPacket() = VfxPlayPacket(id, effect, position, entity, data.save())

    private fun pushData() {
        if (isStopped) return
        broadcast(VfxDataPacket(id, data.save()))
    }

    private fun broadcast(packet: HollowPacket) {
        if (entity != null) packet.sendTrackingEntityAndSelf(entity) else packet.sendAllInDimension(level)
    }
}

/**
 * Starts effects and hands out their handles.
 */
object Vfx {
    private val nextId = AtomicInteger(1)

    /** Plays [effect] at a point in the world. */
    fun play(level: Level, position: Vec3, effect: String): VfxHandle =
        start(VfxHandle(nextId.getAndIncrement(), effect, level, null, position))

    /** Plays [effect] on an entity, following it while it lives. */
    fun play(entity: Entity, effect: String): VfxHandle =
        start(VfxHandle(nextId.getAndIncrement(), effect, entity.level(), entity, null))

    private fun start(handle: VfxHandle): VfxHandle {
        handle.update()
        return handle
    }
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
class VfxPlayPacket(
    val handle: Int = 0,
    val effect: String = "",
    val position: @Serializable(ForVec3::class) Vec3? = null,
    val entity: @Serializable(ForEntity::class) Entity? = null,
    val data: @Serializable(ForCompoundNBT::class) CompoundTag = CompoundTag(),
) : HollowPacket {
    override fun handle(player: Player) {
        VfxScenes.play(this)
    }
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
class VfxStopPacket(val handle: Int = 0, val immediate: Boolean = false) : HollowPacket {
    override fun handle(player: Player) {
        VfxScenes.stop(handle, immediate)
    }
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
class VfxJumpPacket(val handle: Int = 0, val event: String = "") : HollowPacket {
    override fun handle(player: Player) {
        VfxScenes.jump(handle, event)
    }
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
class VfxMovePacket(val handle: Int = 0, val position: @Serializable(ForVec3::class) Vec3 = Vec3.ZERO) : HollowPacket {
    override fun handle(player: Player) {
        VfxScenes.move(handle, position)
    }
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
class VfxDataPacket(
    val handle: Int = 0,
    val data: @Serializable(ForCompoundNBT::class) CompoundTag = CompoundTag(),
) : HollowPacket {
    override fun handle(player: Player) {
        VfxScenes.applyData(handle, data)
    }
}
