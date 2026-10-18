package ru.hollowhorizon.hollowengine.client.sounds

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.alGetError
import org.lwjgl.openal.AL10.alSourcei
import org.lwjgl.openal.AL11.alSource3i
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.EXTEfx.*
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.sounds.*
import ru.hollowhorizon.hollowengine.common.utils.JavaHacks
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.math.sqrt

/** What one filter does to a sound, in the terms OpenAL's effect extension works in. */
sealed interface SoundFilterStage {
    /**
     * Shapes the sound itself: its level, and how much of its lows (around 250 Hz) and highs (around 5 kHz) stay.
     * Every such stage of a sound folds into the one filter a sound has.
     */
    data class Direct(val gain: Float = 1f, val gainLow: Float = 1f, val gainHigh: Float = 1f) : SoundFilterStage

    /**
     * Sends the sound into an effect of [type], mixed in at [wet], while what goes on straight is scaled by
     * [dry]. [configure] sets the effect's parameters on the effect object it is given.
     */
    class Send(val type: Int, val wet: Float, val dry: Float = 1f, val configure: (effect: Int) -> Unit) :
        SoundFilterStage
}

/** Turns one kind of [SoundFilterSpec] into what it does; null when it does nothing. */
fun interface SoundFilterFactory<S : SoundFilterSpec> {
    fun stage(spec: S): SoundFilterStage?
}

/** How each kind of filter sounds on this client; addons add theirs by the id of their filter's type. */
object SoundFilterFactories {
    val point = ExtensionPoints.create<SoundFilterFactory<*>>("hollowengine:sound/filter_factories".rl)

    private val warned = HashSet<String>()

    init {
        register<LowPassFilter>(LowPassFilter.TYPE_ID) { spec ->
            SoundFilterStage.Direct(gain = spec.gain, gainHigh = lowPassAt(HIGH_REFERENCE, spec.cutoff))
        }
        register<HighPassFilter>(HighPassFilter.TYPE_ID) { spec ->
            SoundFilterStage.Direct(gain = spec.gain, gainLow = highPassAt(LOW_REFERENCE, spec.cutoff))
        }
        register<BandPassFilter>(BandPassFilter.TYPE_ID) { spec ->
            SoundFilterStage.Direct(
                gain = spec.gain,
                gainLow = highPassAt(LOW_REFERENCE, spec.low),
                gainHigh = lowPassAt(HIGH_REFERENCE, spec.high),
            )
        }
        register<ReverbFilter>(ReverbFilter.TYPE_ID) { spec ->
            SoundFilterStage.Send(AL_EFFECT_REVERB, wet = 1f) { effect ->
                alEffectf(effect, AL_REVERB_DENSITY, spec.density.coerceIn(0f, 1f))
                alEffectf(effect, AL_REVERB_DIFFUSION, spec.diffusion.coerceIn(0f, 1f))
                alEffectf(effect, AL_REVERB_GAIN, spec.wet.coerceIn(0f, 1f))
                alEffectf(effect, AL_REVERB_GAINHF, (1f - spec.damping).coerceIn(0f, 1f))
                alEffectf(effect, AL_REVERB_DECAY_TIME, spec.decay.coerceIn(0.1f, 20f))
                alEffectf(effect, AL_REVERB_REFLECTIONS_GAIN, spec.reflections.coerceIn(0f, 3.16f))
                alEffectf(effect, AL_REVERB_REFLECTIONS_DELAY, spec.reflectionsDelay.coerceIn(0f, 0.3f))
                alEffectf(effect, AL_REVERB_LATE_REVERB_GAIN, spec.late.coerceIn(0f, 10f))
            }
        }
        register<EchoFilter>(EchoFilter.TYPE_ID) { spec ->
            SoundFilterStage.Send(AL_EFFECT_ECHO, wet = spec.wet) { effect ->
                alEffectf(effect, AL_ECHO_DELAY, spec.delay.coerceIn(0f, 0.207f))
                alEffectf(effect, AL_ECHO_LRDELAY, spec.stereoDelay.coerceIn(0f, 0.404f))
                alEffectf(effect, AL_ECHO_DAMPING, spec.damping.coerceIn(0f, 0.99f))
                alEffectf(effect, AL_ECHO_FEEDBACK, spec.feedback.coerceIn(0f, 1f))
                alEffectf(effect, AL_ECHO_SPREAD, spec.spread.coerceIn(-1f, 1f))
            }
        }
        register<DistortionFilter>(DistortionFilter.TYPE_ID) { spec ->
            val mix = spec.mix.coerceIn(0f, 1f)
            SoundFilterStage.Send(AL_EFFECT_DISTORTION, wet = mix, dry = 1f - mix) { effect ->
                alEffectf(effect, AL_DISTORTION_EDGE, spec.edge.coerceIn(0f, 1f))
                alEffectf(effect, AL_DISTORTION_GAIN, spec.gain.coerceIn(0.01f, 1f))
                alEffectf(effect, AL_DISTORTION_LOWPASS_CUTOFF, spec.tone.coerceIn(80f, 24000f))
            }
        }
    }

    fun <S : SoundFilterSpec> register(typeId: String, factory: SoundFilterFactory<S>): ExtensionHandle =
        point.register(typeId.rl, factory)

    fun stage(spec: SoundFilterSpec): SoundFilterStage? {
        val type = SoundFilterTypes.all.firstOrNull { it.specClass.isInstance(spec) }
        val factory = type?.let { point.find(it.key) }
        if (factory == null) {
            val name = type?.id ?: spec::class.simpleName.orEmpty()
            if (warned.add(name)) HollowEngine.LOGGER.warn("Sound filter '{}' has no sound on this client", name)
            return null
        }
        return factory.stage(JavaHacks.forceCast(spec))
    }

    /** What a one-pole low-pass at [cutoff] leaves of [frequency]. */
    private fun lowPassAt(frequency: Float, cutoff: Float): Float {
        val ratio = frequency / cutoff.coerceAtLeast(1f)
        return (1f / sqrt(1f + ratio * ratio)).coerceIn(0f, 1f)
    }

    /** What a one-pole high-pass at [cutoff] leaves of [frequency]. */
    private fun highPassAt(frequency: Float, cutoff: Float): Float {
        val ratio = frequency / cutoff.coerceAtLeast(1f)
        return (ratio / sqrt(1f + ratio * ratio)).coerceIn(0f, 1f)
    }

    /** The frequencies OpenAL's filters measure their low and high gains at. */
    private const val LOW_REFERENCE = 250f
    private const val HIGH_REFERENCE = 5000f
}

/** Whether this sound device has OpenAL's effect extension, and how many effects one sound may be sent to. */
internal object SoundEfx {
    private var checked = false

    /** Whether the device has filters and effects at all. Read on the sound thread, as everything here. */
    var available: Boolean = false
        private set

    /** How many effects a sound may be sent into. */
    var sends: Int = 0
        private set

    var radius: Boolean = false
        private set

    var spatialize: Boolean = false
        private set

    /** Looks at the device once, on the sound thread, where the context is current. */
    fun check() {
        if (checked) return
        checked = true
        val capabilities = runCatching { AL.getCapabilities() }.getOrNull() ?: return
        radius = capabilities.AL_EXT_SOURCE_RADIUS
        spatialize = capabilities.AL_SOFT_source_spatialize
        available = capabilities.ALC_EXT_EFX
        if (!available) return
        val device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext())
        sends = if (device == 0L) 0 else ALC10.alcGetInteger(device, ALC_MAX_AUXILIARY_SENDS).coerceAtLeast(0)
    }

    /** Forgets what it saw, for a sound engine that is starting over on another device. */
    fun reset() {
        checked = false
        available = false
        sends = 0
        radius = false
        spatialize = false
    }
}

/**
 * The filter and the effects one sound plays through. Lives on the sound thread: every call is made from a task
 * run there, so it never races OpenAL calls Minecraft makes.
 */
internal class SoundEfxChain {
    private var direct = 0
    private val slots = ArrayList<Send>()

    private class Send(val slot: Int, val effect: Int, val filter: Int, var type: Int)

    /** Puts [stages] on [source], making or dropping what it needs to. */
    fun apply(source: Int, stages: List<SoundFilterStage>) {
        SoundEfx.check()
        if (!SoundEfx.available) return
        val sends = stages.filterIsInstance<SoundFilterStage.Send>().take(SoundEfx.sends)
        val folded = stages.filterIsInstance<SoundFilterStage.Direct>().fold(SoundFilterStage.Direct()) { all, next ->
            SoundFilterStage.Direct(all.gain * next.gain, all.gainLow * next.gainLow, all.gainHigh * next.gainHigh)
        }.let { direct -> direct.copy(gain = direct.gain * sends.fold(1f) { dry, send -> dry * send.dry }) }
        applyDirect(source, folded)
        sends.forEachIndexed { index, send -> applySend(source, index, send) }
        while (slots.size > sends.size) {
            val index = slots.lastIndex
            alSource3i(source, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, index, AL_FILTER_NULL)
            delete(slots.removeAt(index))
        }
        alGetError()
    }

    private fun applyDirect(source: Int, stage: SoundFilterStage.Direct) {
        val plain = stage.gain >= 1f && stage.gainLow >= 1f && stage.gainHigh >= 1f
        if (plain) {
            if (direct != 0) alSourcei(source, AL_DIRECT_FILTER, AL_FILTER_NULL)
            return
        }
        if (direct == 0) direct = alGenFilters()
        val gain = stage.gain.coerceIn(0f, 1f)
        val low = stage.gainLow.coerceIn(0f, 1f)
        val high = stage.gainHigh.coerceIn(0f, 1f)
        when {
            low < 1f && high < 1f -> {
                alFilteri(direct, AL_FILTER_TYPE, AL_FILTER_BANDPASS)
                alFilterf(direct, AL_BANDPASS_GAIN, gain)
                alFilterf(direct, AL_BANDPASS_GAINLF, low)
                alFilterf(direct, AL_BANDPASS_GAINHF, high)
            }

            low < 1f -> {
                alFilteri(direct, AL_FILTER_TYPE, AL_FILTER_HIGHPASS)
                alFilterf(direct, AL_HIGHPASS_GAIN, gain)
                alFilterf(direct, AL_HIGHPASS_GAINLF, low)
            }

            else -> {
                alFilteri(direct, AL_FILTER_TYPE, AL_FILTER_LOWPASS)
                alFilterf(direct, AL_LOWPASS_GAIN, gain)
                alFilterf(direct, AL_LOWPASS_GAINHF, high)
            }
        }
        alSourcei(source, AL_DIRECT_FILTER, direct)
    }

    private fun applySend(source: Int, index: Int, stage: SoundFilterStage.Send) {
        val send = slots.getOrNull(index) ?: Send(
            alGenAuxiliaryEffectSlots(), alGenEffects(), alGenFilters(), -1
        ).also(slots::add)
        if (send.type != stage.type) {
            alEffecti(send.effect, AL_EFFECT_TYPE, stage.type)
            send.type = stage.type
        }
        stage.configure(send.effect)
        alAuxiliaryEffectSloti(send.slot, AL_EFFECTSLOT_EFFECT, send.effect)
        alFilteri(send.filter, AL_FILTER_TYPE, AL_FILTER_LOWPASS)
        alFilterf(send.filter, AL_LOWPASS_GAIN, stage.wet.coerceIn(0f, 1f))
        alFilterf(send.filter, AL_LOWPASS_GAINHF, 1f)
        alSource3i(source, AL_AUXILIARY_SEND_FILTER, send.slot, index, send.filter)
    }

    /**
     * Takes everything off [source], when it still plays, and deletes it. An effect slot a source still sends
     * into cannot be deleted, so this runs after the source let go of it or is gone.
     */
    fun release(source: Int?) {
        if (source != null) {
            alSourcei(source, AL_DIRECT_FILTER, AL_FILTER_NULL)
            slots.indices.forEach {
                alSource3i(
                    source, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, it, AL_FILTER_NULL
                )
            }
        }
        slots.forEach(::delete)
        slots.clear()
        if (direct != 0) alDeleteFilters(direct)
        direct = 0
        alGetError()
    }

    private fun delete(send: Send) {
        alAuxiliaryEffectSloti(send.slot, AL_EFFECTSLOT_EFFECT, 0)
        alDeleteAuxiliaryEffectSlots(send.slot)
        alDeleteEffects(send.effect)
        alDeleteFilters(send.filter)
    }
}
