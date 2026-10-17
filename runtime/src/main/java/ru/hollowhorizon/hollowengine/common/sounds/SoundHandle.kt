package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.sounds.ClientSounds
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.StopSoundPacket
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.UpdateSoundPacket

/**
 * A sound a script started, for the players it plays to: what stops it, holds it, moves it to another moment, changes
 * how it sounds, and waits for it. It is over once it is over for every one of them, played through, stopped, or
 * left behind by a player who went away.
 */
class SoundHandle internal constructor(
    val id: String,
    playback: SoundPlayback,
    private val targets: List<Player>,
    private val deliver: (HollowPacket) -> Unit,
) {
    /** How it plays now, as the last [update] left it. */
    var playback: SoundPlayback = playback
        private set

    private val waiting = targets.toMutableSet()
    private var playedThrough = true
    private val done = CompletableDeferred<Boolean>()

    /** Whether it still plays for anyone. */
    val isPlaying: Boolean get() = !done.isCompleted

    var volume: Float
        get() = playback.volume
        set(value) = update { volume = value }

    var pitch: Float
        get() = playback.pitch
        set(value) = update { pitch = value }

    /** Fades it out over [fadeOut] seconds, or over its own fade when not given. */
    fun stop(fadeOut: Float? = null) = deliver(StopSoundPacket(id, fadeOut))

    /** Holds it where it is, until [resume]. */
    fun pause() = deliver(SoundControlPacket(id, SoundControl.PAUSE))

    fun resume() = deliver(SoundControlPacket(id, SoundControl.RESUME))

    /** Takes it to [seconds] into itself. */
    fun seek(seconds: Float) = deliver(SoundControlPacket(id, SoundControl.SEEK, seconds.coerceAtLeast(0f)))

    /** Changes how it plays as it plays: volume, pitch, falloff, filters. A change of how it is read starts it over. */
    fun update(block: SoundSettingsBuilder.() -> Unit) {
        playback = SoundSettingsBuilder().also { it.take(playback) }.apply(block).playback()
        deliver(UpdateSoundPacket(id, playback = playback))
    }

    /** Moves where it plays from. */
    fun follow(emitter: SoundEmitterSpec) = deliver(UpdateSoundPacket(id, emitter = emitter))

    /** Waits until it is over for everyone: true when it played through for all of them. */
    suspend fun await(): Boolean = done.await()

    internal fun ended(player: Player, through: Boolean) {
        if (!waiting.remove(player)) return
        playedThrough = playedThrough && through
        if (waiting.isEmpty()) {
            done.complete(playedThrough)
            SoundHandles.forget(this)
        }
    }

    /** Lets go of the players who left, as if the sound had stopped for them. */
    internal fun sweep() {
        waiting.filter { it is ServerPlayer && it.hasDisconnected() }.forEach { ended(it, false) }
    }

    internal val isRemote: Boolean get() = targets.any { it is ServerPlayer }

    internal fun startWaiting() {
        if (waiting.isEmpty()) done.complete(false) else SoundHandles.remember(this)
    }
}

/** The sounds scripts wait on, by id: those the server sent, and those a client played for itself. */
internal object SoundHandles {
    private val server = HashMap<String, SoundHandle>()
    private val client = HashMap<String, SoundHandle>()

    fun remember(handle: SoundHandle) {
        (if (handle.isRemote) server else client)[handle.id] = handle
    }

    fun forget(handle: SoundHandle) {
        val map = if (handle.isRemote) server else client
        map.remove(handle.id, handle)
    }

    fun endedOnServer(player: Player, id: String, through: Boolean) = server[id]?.ended(player, through)

    fun endedOnClient(player: Player, id: String, through: Boolean) = client[id]?.ended(player, through)

    fun sweep() {
        if (server.isNotEmpty()) server.values.toList().forEach(SoundHandle::sweep)
    }
}

@SubscribeEvent
fun onSoundServerTick(event: TickEvent.Server) = SoundHandles.sweep()

@Serializable
enum class SoundControl { PAUSE, RESUME, SEEK }

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class SoundControlPacket(private val id: String, private val control: SoundControl, private val seconds: Float = 0f) : HollowPacket {
    override fun handle(player: Player) {
        when (control) {
            SoundControl.PAUSE -> ClientSounds.find(id)?.pause()
            SoundControl.RESUME -> ClientSounds.find(id)?.resume()
            SoundControl.SEEK -> ClientSounds.seek(id, seconds.toDouble())
        }
    }
}

/** Tells the server a sound it started is over for this player, and whether it played through. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class SoundEndedPacket(private val id: String, private val playedThrough: Boolean) : HollowPacket {
    override fun handle(player: Player) {
        SoundHandles.endedOnServer(player, id, playedThrough)
    }
}
