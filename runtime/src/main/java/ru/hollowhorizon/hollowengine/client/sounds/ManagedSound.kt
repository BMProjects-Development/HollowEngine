package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundManager
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.valueproviders.ConstantFloat
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import ru.hollowhorizon.hollowengine.common.sounds.SoundEmitterSpec
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.math.ceil

class ManagedSound internal constructor(
    val id: String,
    private val input: SoundSource,
    private var settings: SoundPlayback,
    private var emitter: SoundEmitter,
) : AbstractTickableSoundInstance(
    SoundEvent.createVariableRangeEvent(input.eventLocation()),
    settings.category,
    SoundInstance.createUnseededRandom(),
), AutoCloseable {
    private val streamId = "hollowengine:playback/${ClientSounds.nextToken()}".rl
    private var fadeRemaining = 0f
    private var stopDuration = 0f
    private var stopVolume = 0f
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
    val range: Float get() = settings.range ?: sound?.attenuationDistance?.toFloat() ?: 16f
    var velocity: Vec3 = Vec3.ZERO
        private set

    init {
        volume = settings.volume
        pitch = settings.pitch
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
        resolved = true
        SoundStreams.register(streamId, source, settings, spatial = !relative) { error ->
            Minecraft.getInstance().execute { failure = error; close() }
        }
        sound = object : Sound(
            selected?.location ?: location,
            selected?.volume ?: ConstantFloat.of(1f),
            selected?.pitch ?: ConstantFloat.of(1f),
            1, Sound.Type.FILE, true, false, ceil(settings.range ?: selected?.attenuationDistance?.toFloat() ?: 16f).toInt(),
        ) {
            override fun getPath(): ResourceLocation = streamId
        }
        return events ?: WeighedSoundEvents(location, null).also { it.addSound(sound) }
    }

    override fun canStartSilent() = true

    fun setVolume(value: Float) {
        require(value.isFinite() && value >= 0f)
        volume = value
    }

    fun setPitch(value: Float) {
        require(value.isFinite() && value > 0f)
        pitch = value
    }

    fun follow(value: SoundEmitter) { emitter = value }
    fun follow(value: SoundEmitterSpec) = follow(value.createEmitter())

    internal fun reconfigure(source: SoundSource, playback: SoundPlayback, emitter: SoundEmitter): Boolean {
        if (disposed || source != input || settings.copy(volume = playback.volume, pitch = playback.pitch) != playback) return false
        settings = playback
        volume = playback.volume
        pitch = playback.pitch
        stopDuration = 0f
        this.emitter = emitter
        return true
    }

    internal fun refresh(): SoundEmitterState {
        val state = try { emitter.sample() } catch (error: Exception) {
            failure = error
            close()
            return SoundEmitterState.Removed
        }
        val nextRelative = when (state) {
            SoundEmitterState.Listener -> true
            is SoundEmitterState.Available -> false
            else -> relative
        }
        // OpenAL buffer channel count is immutable. Crossing spaces requires a fresh native voice.
        if (resolved && nextRelative != relative) {
            ClientSounds.replayLater(id, input, settings, emitter)
            close()
            return SoundEmitterState.Removed
        }
        when (state) {
            SoundEmitterState.Listener -> {
                relative = true
                attenuation = SoundInstance.Attenuation.NONE
                x = 0.0; y = 0.0; z = 0.0
                velocity = Vec3.ZERO
            }
            is SoundEmitterState.Available -> {
                if (!state.position.isFinite() || !state.velocity.isFinite()) {
                    failure = IllegalArgumentException("Non-finite sound position")
                    close()
                    return SoundEmitterState.Removed
                }
                relative = false
                attenuation = SoundInstance.Attenuation.LINEAR
                x = state.position.x; y = state.position.y; z = state.position.z
                velocity = state.velocity
            }
            SoundEmitterState.Removed -> close()
            else -> Unit
        }
        return state
    }

    override fun tick() {
        if (disposed) return
        refresh()
        if (stopDuration > 0f) {
            fadeRemaining = (fadeRemaining - 0.05f).coerceAtLeast(0f)
            volume = stopVolume * fadeRemaining / stopDuration
            if (fadeRemaining == 0f) close()
        }
    }

    fun fadeOut(seconds: Float = settings.fadeOut) {
        require(seconds.isFinite() && seconds >= 0f)
        if (seconds == 0f || !started) { close(); return }
        stopVolume = volume
        stopDuration = seconds
        fadeRemaining = seconds
    }

    override fun close() {
        if (disposed) return
        disposed = true
        stop()
        SoundStreams.remove(streamId)
    }
}

private fun SoundSource.eventLocation(): ResourceLocation = when (this) {
    is SoundSource.Event -> id
    is MediaSource.Resource -> id
    else -> "hollowengine:external_sound".rl
}
