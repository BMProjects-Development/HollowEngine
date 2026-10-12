package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.ChannelAccess
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.sounds.SoundEmitterSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import net.minecraft.sounds.SoundSource as SoundCategory

/** All managed sounds are native Minecraft sound instances, including raw files and addon codecs. */
object ClientSounds {
    private val tokens = AtomicLong()
    private val sounds = LinkedHashMap<String, ManagedSound>()

    /** A sound to start again on the next tick, as one that goes to another moment of itself. */
    private class Replay(
        val source: SoundSource,
        val settings: SoundPlayback,
        val emitter: SoundEmitter,
        val start: Double,
        val timeoutSeconds: Float,
        val paused: Boolean,
        val onEnd: ((Boolean) -> Unit)?,
    )

    private val replays = LinkedHashMap<String, Replay>()
    private var lastFrame = 0L

    /** Whether the sound engine holds every channel, as under the pause menu; nothing moves on then. */
    private var engineHeld = false

    fun enginePaused(paused: Boolean) {
        engineHeld = paused
    }

    internal fun nextToken(): Long = tokens.incrementAndGet()

    /**
     * Plays [source] as [id]. A sound already playing as [id] with the same source takes the new settings and
     * emitter as it plays, unless they change how it is read; then, as any other, it starts over. [onEnd] is told,
     * once it is gone, whether it played through.
     */
    fun play(
        source: SoundSource,
        settings: SoundPlayback = SoundPlayback(),
        emitter: SoundEmitter = SoundEmitter { SoundEmitterState.Listener },
        id: String = UUID.randomUUID().toString(),
        timeoutSeconds: Float = DEFAULT_TIMEOUT,
        start: Double = 0.0,
        onEnd: ((playedThrough: Boolean) -> Unit)? = null,
    ): ManagedSound {
        check(Minecraft.getInstance().isSameThread) { "Play sounds on the client thread" }
        require(timeoutSeconds.isFinite() && timeoutSeconds >= 0f)
        replays.remove(id)
        sounds[id]?.let { playing ->
            if (start == 0.0 && playing.reconfigure(source, settings, emitter)) {
                onEnd?.let { playing.onEnd = it }
                return playing
            }
        }
        sounds.remove(id)?.let { replaced ->
            replaced.onEnd = null
            replaced.close()
        }
        val sound = ManagedSound(id, source, settings, emitter, start.coerceAtLeast(0.0))
        sound.timeoutSeconds = timeoutSeconds
        sound.onEnd = onEnd
        sounds[id] = sound
        start(sound)
        return sound
    }

    fun play(
        source: SoundSource,
        settings: SoundPlayback,
        emitter: SoundEmitterSpec,
        id: String,
        onEnd: ((playedThrough: Boolean) -> Unit)? = null,
    ): ManagedSound = play(source, settings, emitter.createEmitter(), id, emitter.timeoutSeconds(), onEnd = onEnd)

    fun find(id: String): ManagedSound? = sounds[id]

    fun stopMatching(location: ResourceLocation?, category: SoundCategory?) {
        sounds.values.filter { (location == null || it.location == location) && (category == null || it.source == category) }
            .forEach { stop(it.id, 0f) }
    }

    fun stop(id: String, fadeOut: Float? = null) {
        replays.remove(id)
        sounds[id]?.let { if (fadeOut == null) it.fadeOut() else it.fadeOut(fadeOut) }
    }

    /**
     * Takes [id] to [seconds] into itself. The stream cannot go back, so it starts over there on the next tick,
     * with everything else as it was.
     */
    fun seek(id: String, seconds: Double) {
        val playing = sounds[id] ?: return
        replays[id] = Replay(
            playing.input, playing.settings, playing.emitterNow(), seconds.coerceAtLeast(0.0), playing.timeoutSeconds,
            playing.isPaused, playing.onEnd,
        )
        playing.onEnd = null
        playing.close()
    }

    private fun start(sound: ManagedSound) {
        try {
            when (sound.refresh()) {
                SoundEmitterState.Listener, is SoundEmitterState.Available -> {
                    sound.started = true
                    Minecraft.getInstance().soundManager.play(sound)
                }
                else -> Unit
            }
        } catch (error: Exception) {
            sound.failure = error
            sound.close()
        }
    }

    /**
     * Called after SoundEngine.tick: native EOF, /stopsound and rejected plays release the same owner. It runs while
     * the game is paused, as it is under the IDE, whose previews play on; only what waits to start waits for the
     * sound engine to go on when the pause menu holds it.
     */
    fun tick(paused: Boolean) {
        SoundAttachments.prune()
        val manager = Minecraft.getInstance().soundManager
        val iterator = sounds.values.iterator()
        while (iterator.hasNext()) {
            val sound = iterator.next()
            if (!sound.started && !sound.isStopped && !engineHeld) {
                if (!paused) sound.waitSeconds += SECONDS_PER_TICK
                start(sound)
                if (!sound.started && !sound.isStopped && sound.timeoutSeconds > 0f && sound.waitSeconds >= sound.timeoutSeconds) {
                    sound.failure = TimeoutException("Timed out waiting for the sound emitter")
                    sound.close()
                }
            }
            val ended = sound.started && !manager.isActive(sound)
            if (sound.isStopped || ended) {
                val playedThrough = !sound.isStopped && sound.failure == null
                sound.failure?.let { HollowEngine.LOGGER.error("Sound '{}' failed", sound.id, it) }
                sound.close()
                iterator.remove()
                sound.onEnd?.let { report -> runCatching { report(playedThrough) } }
            }
        }
        if (engineHeld) return
        val pending = replays.toMap()
        replays.clear()
        pending.forEach { (id, replay) ->
            val sound = play(replay.source, replay.settings, replay.emitter, id, replay.timeoutSeconds, replay.start, replay.onEnd)
            if (replay.paused) sound.pause()
        }
    }

    fun clear() {
        replays.clear()
        sounds.values.forEach { it.onEnd = null; it.close() }
        sounds.clear()
        SoundListener.reset()
        SoundEfx.reset()
    }

    /** Places every playing sound for this frame; the channels are Minecraft's, run on its sound [executor]. */
    fun frame(channels: Map<SoundInstance, ChannelAccess.ChannelHandle>, executor: Executor) {
        val now = System.nanoTime()
        val seconds = if (lastFrame == 0L) 0f else ((now - lastFrame) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME)
        lastFrame = now
        if (engineHeld) return
        SoundListener.update(seconds)
        channels.forEach { (instance, channel) ->
            if (instance !is ManagedSound || instance.isStopped) return@forEach
            instance.frame(channel, executor, seconds)
        }
    }

    private fun SoundEmitterSpec.timeoutSeconds(): Float = when (this) {
        is SoundEmitterSpec.Attachment -> timeoutSeconds
        is SoundEmitterSpec.Rig -> timeoutSeconds
        is SoundEmitterSpec.Effect -> timeoutSeconds
        else -> DEFAULT_TIMEOUT
    }

    private const val DEFAULT_TIMEOUT = 5f
    private const val SECONDS_PER_TICK = 0.05f
    private const val NANOS_PER_SECOND = 1_000_000_000f

    /** The longest step a frame is taken as, so a hitch does not leap a sound's clock. */
    private const val MAX_FRAME = 0.25f
}
