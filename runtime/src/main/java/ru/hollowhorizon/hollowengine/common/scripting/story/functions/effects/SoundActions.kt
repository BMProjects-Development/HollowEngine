package ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.sounds.ClientSounds
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.sounds.SoundEmitterSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundRepeat
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import java.util.UUID
import net.minecraft.sounds.SoundSource as SoundCategory

@DslMarker
annotation class SoundDsl

@SoundDsl
open class SoundBuilder {
    var location = ""
    var source: SoundSource? = null
    var id: String = UUID.randomUUID().toString()
    var volume = 1f
    var pitch = 1f
    var fadeIn = 0f
    var fadeOut = 0f
    var fadeEachCycle = false
    var repeat: SoundRepeat = SoundRepeat.Once
    var category = SoundCategory.MASTER
    var range: Float? = null
    var position: Vec3? = null
    var velocity: Vec3? = null
    private var relativeOverride: Boolean? = null
    var relative: Boolean
        get() = relativeOverride ?: (position == null)
        set(value) { relativeOverride = value }
    var emitter: SoundEmitterSpec? = null

    fun at(position: Vec3) { this.position = position; relative = false; emitter = null }
    fun at(x: Double, y: Double, z: Double) = at(Vec3(x, y, z))
    fun follow(entity: Entity, offset: Vec3 = Vec3.ZERO) { emitter = SoundEmitterSpec.Entity(entity.id, offset) }
    fun attach(id: String, timeoutSeconds: Float = 5f) { emitter = SoundEmitterSpec.Attachment(id, timeoutSeconds) }
    fun attach(entity: Entity, attachmentId: String, timeoutSeconds: Float = 5f, modelNodeId: String? = null) {
        emitter = SoundEmitterSpec.Rig(entity.id, attachmentId, timeoutSeconds, modelNodeId)
    }
    fun followEffect(handle: Int, timeoutSeconds: Float = 5f) { emitter = SoundEmitterSpec.Effect(handle, timeoutSeconds) }
    fun cycles(count: Int) { repeat = SoundRepeat.Count(count) }
    internal fun packet(): PlaySoundPacket = PlaySoundPacket(
        id, source ?: SoundSource.parse(location),
        SoundPlayback(repeat, fadeIn, fadeOut, fadeEachCycle, volume, pitch, category, range),
        emitter ?: if (relative) SoundEmitterSpec.Listener else SoundEmitterSpec.Position(position ?: Vec3.ZERO, velocity ?: Vec3.ZERO),
    )
}

infix fun Player.playSound(location: String) = playSound(location, 1f, 1f)
fun Player.playSound(
    location: String, volume: Float = 1f, pitch: Float = 1f,
    position: Vec3? = null, velocity: Vec3? = null, relative: Boolean = position == null,
): String = playSound(location) {
    this.volume = volume; this.pitch = pitch; this.position = position
    this.velocity = velocity; this.relative = relative
}

fun Player.playSound(location: String, block: SoundBuilder.() -> Unit): String =
    playSound { this.location = location; block() }

fun Player.playSound(source: SoundSource, block: SoundBuilder.() -> Unit = {}): String =
    playSound { this.source = source; block() }

fun Player.playSound(block: SoundBuilder.() -> Unit): String {
    val builder = SoundBuilder().apply(block)
    val packet = builder.packet()
    if (this is ServerPlayer) packet.send(this) else packet.handle(this)
    return builder.id
}

infix fun Level.playSound(location: String) = playSound(location, 1f, 1f)
fun Level.playSound(
    location: String, volume: Float = 1f, pitch: Float = 1f,
    position: Vec3? = null, velocity: Vec3? = null, relative: Boolean = position == null,
): String = playSound(location) {
    this.volume = volume; this.pitch = pitch; this.position = position
    this.velocity = velocity; this.relative = relative
}

fun Level.playSound(location: String, block: SoundBuilder.() -> Unit): String =
    playSound { this.location = location; block() }

fun Level.playSound(source: SoundSource, block: SoundBuilder.() -> Unit = {}): String =
    playSound { this.source = source; block() }

fun Level.playSound(block: SoundBuilder.() -> Unit): String {
    val builder = SoundBuilder().apply(block)
    val packet = builder.packet()
    players().forEach { if (it is ServerPlayer) packet.send(it) else packet.handle(it) }
    return builder.id
}

fun Player.stopSound(id: String, fadeOut: Float? = null) {
    val packet = StopSoundPacket(id, fadeOut)
    if (this is ServerPlayer) packet.send(this) else packet.handle(this)
}

fun Level.stopSound(id: String, fadeOut: Float? = null) = players().forEach { it.stopSound(id, fadeOut) }

fun Player.updateSound(id: String, volume: Float? = null, pitch: Float? = null, emitter: SoundEmitterSpec? = null) {
    val packet = UpdateSoundPacket(id, volume, pitch, emitter)
    if (this is ServerPlayer) packet.send(this) else packet.handle(this)
}

fun Level.updateSound(id: String, volume: Float? = null, pitch: Float? = null, emitter: SoundEmitterSpec? = null) =
    players().forEach { it.updateSound(id, volume, pitch, emitter) }

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class PlaySoundPacket(
    private val id: String,
    private val source: SoundSource,
    private val settings: SoundPlayback,
    private val emitter: SoundEmitterSpec,
) : HollowPacket {
    override fun handle(player: Player) { ClientSounds.play(source, settings, emitter, id) }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class StopSoundPacket(private val id: String, private val fadeOut: Float? = null) : HollowPacket {
    override fun handle(player: Player) = ClientSounds.stop(id, fadeOut)
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class UpdateSoundPacket(
    private val id: String, private val volume: Float? = null, private val pitch: Float? = null,
    private val emitter: SoundEmitterSpec? = null,
) : HollowPacket {
    override fun handle(player: Player) {
        val sound = ClientSounds.find(id) ?: return
        volume?.let(sound::setVolume)
        pitch?.let(sound::setPitch)
        emitter?.let(sound::follow)
    }
}

