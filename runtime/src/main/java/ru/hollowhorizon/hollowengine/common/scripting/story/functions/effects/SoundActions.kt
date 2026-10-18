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
import ru.hollowhorizon.hollowengine.common.sounds.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.*

/** A sound to play: what it is and where it plays from, on top of how it plays. */
@SoundDsl
open class SoundBuilder : SoundSettingsBuilder() {
    var location = ""
    var source: SoundSource? = null
    var id: String = UUID.randomUUID().toString()
    var position: Vec3? = null
    var velocity: Vec3? = null
    private var relativeOverride: Boolean? = null
    var relative: Boolean
        get() = relativeOverride ?: (position == null)
        set(value) { relativeOverride = value }
    var emitter: SoundEmitterSpec? = null

    /** The space it fills around [position], each piece placed relative to it; see [SoundArea]. */
    val areas = ArrayList<SoundArea>()

    fun at(position: Vec3) { this.position = position; relative = false; emitter = null }
    fun at(x: Double, y: Double, z: Double) = at(Vec3(x, y, z))
    fun follow(entity: Entity, offset: Vec3 = Vec3.ZERO) { emitter = SoundEmitterSpec.Entity(entity.id, offset) }
    fun attach(id: String, timeoutSeconds: Float = 5f) { emitter = SoundEmitterSpec.Attachment(id, timeoutSeconds) }
    fun attach(entity: Entity, attachmentId: String, timeoutSeconds: Float = 5f, modelNodeId: String? = null) {
        emitter = SoundEmitterSpec.Rig(entity.id, attachmentId, timeoutSeconds, modelNodeId)
    }
    fun followEffect(handle: Int, timeoutSeconds: Float = 5f) { emitter = SoundEmitterSpec.Effect(handle, timeoutSeconds) }

    /** Adds a box of [size] blocks to the space it fills, centered [offset] from where it plays, turned by [rotation] degrees. */
    fun box(size: Vec3, offset: Vec3 = Vec3.ZERO, rotation: Vec3 = Vec3.ZERO) {
        areas += SoundArea.Box(offset.toVec3f(), rotation.toVec3f(), size.toVec3f())
    }

    /** Adds a ball of [radius] blocks to the space it fills, centered [offset] from where it plays. */
    fun sphere(radius: Float, offset: Vec3 = Vec3.ZERO) {
        areas += SoundArea.Sphere(offset.toVec3f(), radius)
    }

    internal fun packet(report: Boolean): PlaySoundPacket = PlaySoundPacket(
        id, source ?: SoundSource.parse(location), playback(),
        emitter ?: if (relative) SoundEmitterSpec.Listener
        else SoundEmitterSpec.Position(position ?: Vec3.ZERO, velocity ?: Vec3.ZERO, areas.toList()),
        report,
    )

    private fun Vec3.toVec3f() = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())
}

infix fun Player.playSound(location: String) = playSound(location, 1f, 1f)
fun Player.playSound(
    location: String, volume: Float = 1f, pitch: Float = 1f,
    position: Vec3? = null, velocity: Vec3? = null, relative: Boolean = position == null,
): SoundHandle = playSound(location) {
    this.volume = volume; this.pitch = pitch; this.position = position
    this.velocity = velocity; this.relative = relative
}

fun Player.playSound(location: String, block: SoundBuilder.() -> Unit): SoundHandle =
    playSound { this.location = location; block() }

fun Player.playSound(source: SoundSource, block: SoundBuilder.() -> Unit = {}): SoundHandle =
    playSound { this.source = source; block() }

fun Player.playSound(block: SoundBuilder.() -> Unit): SoundHandle = listOf(this).play(SoundBuilder().apply(block))

infix fun Level.playSound(location: String) = playSound(location, 1f, 1f)
fun Level.playSound(
    location: String, volume: Float = 1f, pitch: Float = 1f,
    position: Vec3? = null, velocity: Vec3? = null, relative: Boolean = position == null,
): SoundHandle = playSound(location) {
    this.volume = volume; this.pitch = pitch; this.position = position
    this.velocity = velocity; this.relative = relative
}

fun Level.playSound(location: String, block: SoundBuilder.() -> Unit): SoundHandle =
    playSound { this.location = location; block() }

fun Level.playSound(source: SoundSource, block: SoundBuilder.() -> Unit = {}): SoundHandle =
    playSound { this.source = source; block() }

fun Level.playSound(block: SoundBuilder.() -> Unit): SoundHandle = audience().play(SoundBuilder().apply(block))

/**
 * Plays [builder] to these players. A client plays it for its own player alone, however many it shows, and waits
 * for it itself; a server sends it out and hears back from each when it is over.
 */
private fun List<Player>.play(builder: SoundBuilder): SoundHandle {
    val remote = filterIsInstance<ServerPlayer>()
    val local = if (remote.isEmpty()) take(1) else emptyList()
    val targets = remote.ifEmpty { local }
    val packet = builder.packet(report = remote.isNotEmpty())
    val handle = SoundHandle(builder.id, packet.settings, targets) { next ->
        if (remote.isNotEmpty()) next.send(remote) else local.forEach(next::handle)
    }
    handle.startWaiting()
    if (remote.isNotEmpty()) packet.send(remote) else local.forEach(packet::handle)
    return handle
}

/** Everyone a sound played in this level reaches: its players on a server, the one playing on a client. */
private fun Level.audience(): List<Player> = if (isClientSide) players().take(1) else players().toList()

fun Player.stopSound(id: String, fadeOut: Float? = null) = deliver(StopSoundPacket(id, fadeOut))

fun Level.stopSound(id: String, fadeOut: Float? = null) = audience().forEach { it.stopSound(id, fadeOut) }

fun Player.updateSound(id: String, volume: Float? = null, pitch: Float? = null, emitter: SoundEmitterSpec? = null) =
    deliver(UpdateSoundPacket(id, volume, pitch, emitter))

fun Level.updateSound(id: String, volume: Float? = null, pitch: Float? = null, emitter: SoundEmitterSpec? = null) =
    audience().forEach { it.updateSound(id, volume, pitch, emitter) }

fun Player.stopSound(sound: SoundHandle, fadeOut: Float? = null) = stopSound(sound.id, fadeOut)

fun Level.stopSound(sound: SoundHandle, fadeOut: Float? = null) = stopSound(sound.id, fadeOut)

fun Player.updateSound(sound: SoundHandle, volume: Float? = null, pitch: Float? = null, emitter: SoundEmitterSpec? = null) =
    updateSound(sound.id, volume, pitch, emitter)

fun Level.updateSound(sound: SoundHandle, volume: Float? = null, pitch: Float? = null, emitter: SoundEmitterSpec? = null) =
    updateSound(sound.id, volume, pitch, emitter)

private fun Player.deliver(packet: HollowPacket) {
    if (this is ServerPlayer) packet.send(this) else packet.handle(this)
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class PlaySoundPacket(
    private val id: String,
    private val source: SoundSource,
    internal val settings: SoundPlayback,
    private val emitter: SoundEmitterSpec,
    /** Whether the server waits to hear when it is over; a sound a client played for itself is waited on there. */
    private val report: Boolean = true,
) : HollowPacket {
    override fun handle(player: Player) {
        ClientSounds.play(source, settings, emitter, id) { through ->
            if (report) SoundEndedPacket(id, through).send() else SoundHandles.endedOnClient(player, id, through)
        }
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class StopSoundPacket(private val id: String, private val fadeOut: Float? = null) : HollowPacket {
    override fun handle(player: Player) = ClientSounds.stop(id, fadeOut)
}

/** Changes a playing sound: its volume and pitch alone, all of how it plays, or where it plays from. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class UpdateSoundPacket(
    private val id: String,
    private val volume: Float? = null,
    private val pitch: Float? = null,
    private val emitter: SoundEmitterSpec? = null,
    private val playback: SoundPlayback? = null,
) : HollowPacket {
    override fun handle(player: Player) {
        val sound = ClientSounds.find(id) ?: return
        playback?.let { ClientSounds.play(sound.input, it, sound.emitterNow(), id) }
        val current = ClientSounds.find(id) ?: return
        volume?.let(current::setVolume)
        pitch?.let(current::setPitch)
        emitter?.let(current::follow)
    }
}
