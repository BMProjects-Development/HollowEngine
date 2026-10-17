package ru.hollowhorizon.hollowengine.client.sounds

import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import java.util.*
import kotlin.math.abs

/**
 * A sound something plays on and on, frame after frame, as an attachment or an effect does: it takes what it is
 * given as it plays, and starts over only when how it is read changes.
 */
class SyncedSound(private val id: String = UUID.randomUUID().toString()) : AutoCloseable {
    private var lastClock = Double.NaN
    private var lastNanos = 0L

    /** Set when the sound ended by itself, so it is not started again until the clock goes elsewhere. */
    private var ended = false
    private var source: SoundSource? = null

    /** Whether it is playing or about to. */
    val isPlaying: Boolean get() = ClientSounds.find(id) != null

    /** Seconds into the sound it has played to, or null when it does not play. */
    val position: Double? get() = ClientSounds.find(id)?.position

    /**
     * Plays [source] with [playback] from [emitter], [clock] seconds in when given. Called every frame while it
     * should sound.
     */
    fun update(source: SoundSource, playback: SoundPlayback, emitter: SoundEmitter, clock: Double? = null) {
        val now = System.nanoTime()
        val elapsed = if (lastNanos == 0L) 0.0 else (now - lastNanos) / NANOS_PER_SECOND
        lastNanos = now
        if (source != this.source) {
            if (this.source != null) ClientSounds.stop(id, 0f)
            this.source = source
            ended = false
        }

        val previous = lastClock
        lastClock = clock ?: Double.NaN
        val jumped = clock != null && !previous.isNaN() && abs(clock - previous - elapsed) > JUMP && clock != previous
        if (jumped) ended = false

        val playing = ClientSounds.find(id)
        if (playing == null) {
            if (ended) return
            start(source, playback, emitter, clock ?: 0.0)
            return
        }
        if (!playing.reconfigure(source, playback, emitter)) {
            start(source, playback, emitter, clock ?: playing.position)
            return
        }
        if (jumped) return ClientSounds.seek(id, clock!!)
        if (clock == null) return
        if (clock == previous) playing.pause() else playing.resume()
    }

    private fun start(source: SoundSource, playback: SoundPlayback, emitter: SoundEmitter, at: Double) {
        ClientSounds.play(source, playback, emitter, id, start = at) { playedThrough -> if (playedThrough) ended = true }
    }

    /** Fades it out over [fade] seconds, or its own fade when not given; it may start again later. */
    fun stop(fade: Float? = null) {
        lastClock = Double.NaN
        ended = false
        ClientSounds.stop(id, fade)
    }

    override fun close() = stop(0f)

    private companion object {
        /** How far, in seconds, the clock may stray from the frames before it is taken to have jumped. */
        const val JUMP = 0.15

        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
