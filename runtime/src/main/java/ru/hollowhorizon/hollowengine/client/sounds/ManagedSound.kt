package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.ChannelAccess
import net.minecraft.client.sounds.SoundManager
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.Mth
import net.minecraft.util.valueproviders.ConstantFloat
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.EXTSourceRadius.AL_SOURCE_RADIUS
import org.lwjgl.openal.SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT
import ru.hollowhorizon.hollowengine.bridge.AudioChannelAccess
import ru.hollowhorizon.hollowengine.common.sounds.*
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.concurrent.Executor
import kotlin.math.ceil
import kotlin.math.min
import net.minecraft.sounds.SoundSource as SoundCategory

/**
 * One playing sound: a native Minecraft sound whose volume, pitch, place and filters this class sets itself every
 * frame, so they follow what plays it without the sound starting over. It starts [start] seconds in.
 */
class ManagedSound internal constructor(
    val id: String,
    internal val input: SoundSource,
    settings: SoundPlayback,
    private var emitter: SoundEmitter,
    internal val start: Double = 0.0,
) : AbstractTickableSoundInstance(
    SoundEvent.createVariableRangeEvent(input.eventLocation()),
    settings.category,
    SoundInstance.createUnseededRandom(),
), AutoCloseable {
    var settings: SoundPlayback = settings
        private set

    private val streamId = "hollowengine:playback/${ClientSounds.nextToken()}".rl
    private var disposed = false
    private var resolved = false
    var started = false
        internal set
    var waitSeconds = 0f
        internal set
    var timeoutSeconds = 5f
        internal set
    var failure: Throwable? = null
        internal set

    /** Told whether the sound played through, rather than was stopped, once it is gone. */
    internal var onEnd: ((playedThrough: Boolean) -> Unit)? = null

    /** What the sound event adds on top of the settings: its own volume, pitch and range. */
    private var eventVolume = 1f
    private var eventPitch = 1f
    private var eventRange = DEFAULT_RANGE

    private var falloffGain = 1f
    private var dopplerShift = 1f
    private var spread = 0f
    private var spatialized = true

    /** When the fade before stopping began, in nanoseconds, and how long it lasts. */
    private var stopAt = 0L
    private var stopSeconds = 0f
    private var stopFrom = 1f

    /** Whether it is held where it is; its channel is paused, and it goes on from there when let go. */
    var isPaused: Boolean = false
        private set

    /** Seconds into the sound it has played to, as far as the frames tell; see [SyncedSound]. */
    var position: Double = start
        private set

    private val efx = SoundEfxChain()
    private var appliedFilters: List<SoundFilterSpec>? = null
    private var channel: ChannelAccess.ChannelHandle? = null
    private var executor: Executor? = null

    init {
        relative = true
        attenuation = SoundInstance.Attenuation.NONE
    }

    override fun resolve(manager: SoundManager): WeighedSoundEvents? {
        val events = if (input is SoundSource.Event) manager.getSoundEvent(input.id) ?: return null else null
        val selected = events?.getSound(random)
        if (selected === SoundManager.EMPTY_SOUND || selected === SoundManager.INTENTIONALLY_EMPTY_SOUND) return null
        val source = when (input) {
            is SoundSource.Event -> MediaSource.Resource(requireNotNull(selected).path)
            is MediaSource -> input
        }
        // Sampled once, so a range in sounds.json does not flutter every tick.
        eventVolume = selected?.volume?.sample(random) ?: 1f
        eventPitch = selected?.pitch?.sample(random) ?: 1f
        eventRange = selected?.attenuationDistance?.toFloat() ?: DEFAULT_RANGE
        resolved = true
        SoundStreams.register(streamId, source, settings, start) { error ->
            Minecraft.getInstance().execute { failure = error; close() }
        }
        sound = object : Sound(
            selected?.location ?: location,
            ConstantFloat.of(1f),
            ConstantFloat.of(1f),
            1, Type.FILE, true, false, ceil(eventRange).toInt(),
        ) {
            override fun getPath(): ResourceLocation = streamId
        }
        return events ?: WeighedSoundEvents(location, null).also { it.addSound(sound) }
    }

    override fun canStartSilent() = true

    override fun getVolume(): Float = settings.volume * eventVolume * falloffGain * fadeGain()

    override fun getPitch(): Float = settings.pitch * eventPitch * dopplerShift

    fun setVolume(value: Float) {
        require(value.isFinite() && value >= 0f)
        settings = settings.copy(volume = value)
    }

    fun setPitch(value: Float) {
        require(value.isFinite() && value > 0f)
        settings = settings.copy(pitch = value)
    }

    fun follow(value: SoundEmitter) {
        emitter = value
    }

    internal fun emitterNow(): SoundEmitter = emitter
    fun follow(value: SoundEmitterSpec) = follow(value.createEmitter())

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    /**
     * Takes [playback] and [emitter] as it plays; false when [source] or how the stream is read differs, and it has
     * to start over. A sound fading out to stop is kept on.
     */
    internal fun reconfigure(source: SoundSource, playback: SoundPlayback, emitter: SoundEmitter): Boolean {
        if (disposed || source != input || playback.structure() != settings.structure()) return false
        settings = playback
        this.emitter = emitter
        stopSeconds = 0f
        return true
    }

    internal fun refresh(): SoundEmitterState {
        val state = try {
            emitter.sample()
        } catch (error: Exception) {
            failure = error
            close()
            return SoundEmitterState.Removed
        }
        when (state) {
            SoundEmitterState.Listener -> placeAtListener()
            is SoundEmitterState.Available -> {
                if (!state.position.isFinite() || !state.velocity.isFinite()) {
                    failure = IllegalArgumentException("Non-finite sound position")
                    close()
                    return SoundEmitterState.Removed
                }
                place(state)
            }

            SoundEmitterState.Removed -> close()
            else -> Unit
        }
        return state
    }

    private fun placeAtListener() {
        relative = true
        x = 0.0; y = 0.0; z = 0.0
        falloffGain = 1f
        dopplerShift = 1f
        spread = 0f
        spatialized = false
    }

    /** Puts the sound where the listener hears it from, at the volume and pitch it hears it with. */
    private fun place(state: SoundEmitterState.Available) {
        if (settings.mode == SoundMode.GLOBAL) return placeAtListener()
        val listener = SoundListener.position
        val nearest = Vector3f()
        val shape = state.shape
        val depth = if (shape != null) shape.depth(listener, nearest) else {
            nearest.set(state.position.x.toFloat(), state.position.y.toFloat(), state.position.z.toFloat())
            state.position.distanceTo(listener).toFloat()
        }
        falloffGain = settings.falloff.gain(depth, eventRange, hasInside = shape != null)
        val from = Vec3(nearest.x.toDouble(), nearest.y.toDouble(), nearest.z.toDouble())
        dopplerShift = doppler(from, listener, state.velocity)
        if (settings.mode == SoundMode.FIXED) {
            relative = true
            x = 0.0; y = 0.0; z = 0.0
            spread = 0f
            spatialized = false
            return
        }
        relative = false
        x = from.x; y = from.y; z = from.z
        spatialized = true
        // Near and inside the areas the sound spreads around the listener rather than jumping to one side.
        spread = if (shape == null) 0f else min(SPREAD, settings.falloff.distance ?: eventRange)
    }

    /** How much the motion of the sound and of the listener toward each other raises its pitch. */
    private fun doppler(from: Vec3, listener: Vec3, velocity: Vec3): Float {
        val factor = settings.doppler
        if (factor <= 0f) return 1f
        val toListener = listener.subtract(from)
        val distance = toListener.length()
        if (distance < 1e-3) return 1f
        val direction = toListener.scale(1.0 / distance)
        val limit = SPEED_OF_SOUND * 0.9
        val away = (SoundListener.velocity.dot(direction) * factor).coerceIn(-limit, limit)
        val toward = (velocity.dot(direction) * factor).coerceIn(-limit, limit)
        return ((SPEED_OF_SOUND - away) / (SPEED_OF_SOUND - toward)).toFloat().coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /**
     * Sets everything about [handle]'s channel this sound decides, once a frame and [seconds] after the last one,
     * on Minecraft's sound [executor].
     */
    internal fun frame(handle: ChannelAccess.ChannelHandle, executor: Executor, seconds: Float) {
        channel = handle
        this.executor = executor
        refresh()
        if (disposed) return
        if (fadeGain() <= 0f) return close()
        if (started && !isPaused) position += seconds * pitchPlayed()

        val volume = Mth.clamp(getVolume() * categoryVolume(), 0f, 1f)
        val pitch = pitchPlayed()
        val place = Vec3(x, y, z)
        val relative = relative
        val spread = spread
        val spatialized = spatialized
        val paused = isPaused
        val filters = settings.filters.takeIf { it != appliedFilters }?.also { appliedFilters = it }
        val stages = filters?.mapNotNull(SoundFilterFactories::stage)
        val chain = efx
        handle.execute { playing ->
            playing.setVolume(volume)
            playing.setPitch(pitch)
            playing.setSelfPosition(place)
            playing.setRelative(relative)
            playing.disableAttenuation()
            val source = (playing as AudioChannelAccess).`hollowengine$getSource`()
            SoundEfx.check()
            if (SoundEfx.radius) alSourcef(source, AL_SOURCE_RADIUS, spread)
            if (SoundEfx.spatialize) alSourcei(
                source, AL_SOURCE_SPATIALIZE_SOFT, if (spatialized) AL_TRUE else AL_FALSE
            )
            if (stages != null) chain.apply(source, stages)
            if (paused) playing.pause() else playing.unpause()
        }
    }

    private fun pitchPlayed(): Float = Mth.clamp(getPitch(), MIN_PITCH, MAX_PITCH)

    private fun categoryVolume(): Float {
        val category = source
        return if (category == SoundCategory.MASTER) 1f else Minecraft.getInstance().options.getSoundSourceVolume(
            category
        )
    }

    override fun tick() {
        if (disposed) return
        refresh()
        if (fadeGain() <= 0f) close()
    }

    private fun fadeGain(): Float {
        if (stopSeconds <= 0f) return 1f
        val elapsed = (System.nanoTime() - stopAt) / NANOS_PER_SECOND
        return stopFrom * (1f - elapsed / stopSeconds).coerceIn(0f, 1f)
    }

    fun fadeOut(seconds: Float = settings.fadeOut) {
        require(seconds.isFinite() && seconds >= 0f)
        if (seconds == 0f || !started || isPaused) {
            close(); return
        }
        stopFrom = fadeGain()
        stopAt = System.nanoTime()
        stopSeconds = seconds
    }

    override fun close() {
        if (disposed) return
        disposed = true
        stop()
        channel?.execute { it.stop() }
        SoundStreams.remove(streamId)
        val chain = efx
        if (appliedFilters.isNullOrEmpty()) return
        channel?.execute { playing -> chain.release((playing as AudioChannelAccess).`hollowengine$getSource`()) }
        executor?.execute { chain.release(null) }
    }

    private companion object {
        const val DEFAULT_RANGE = 16f

        /** How far, in blocks, a sound with areas spreads around the listener near and inside them. */
        const val SPREAD = 2f

        const val SPEED_OF_SOUND = 343.3

        /** The pitch Minecraft lets a sound have. */
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2f

        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}

private fun SoundSource.eventLocation(): ResourceLocation = when (this) {
    is SoundSource.Event -> id
    is MediaSource.Resource -> id
    else -> "hollowengine:external_sound".rl
}
