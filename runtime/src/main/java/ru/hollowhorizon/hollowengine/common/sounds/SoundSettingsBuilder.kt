package ru.hollowhorizon.hollowengine.common.sounds

import net.minecraft.sounds.SoundSource as SoundCategory

@DslMarker
annotation class SoundDsl

/** How a sound plays, built by a script: what [SoundPlayback] holds, with the filters added by name. */
@SoundDsl
open class SoundSettingsBuilder {
    var volume = 1f
    var pitch = 1f
    var fadeIn = 0f
    var fadeOut = 0f
    var fadeEachCycle = false
    var repeat: SoundRepeat = SoundRepeat.Once
    var category = SoundCategory.MASTER
    var mode = SoundMode.SPATIAL
    var falloff = SoundFalloff()
    var doppler = 1f

    /** What the sound passes through, in order; see [SoundFilterSpec]. */
    val filters = ArrayList<SoundFilterSpec>()

    /** How many blocks past its edge, or from its point, the sound fades out over; the event's range when null. */
    var range: Float?
        get() = falloff.distance
        set(value) { falloff = falloff.copy(distance = value) }

    fun cycles(count: Int) { repeat = SoundRepeat.Count(count) }

    fun filter(spec: SoundFilterSpec) { filters += spec }
    fun lowPass(cutoff: Float, gain: Float = 1f) = filter(LowPassFilter(cutoff, gain))
    fun highPass(cutoff: Float, gain: Float = 1f) = filter(HighPassFilter(cutoff, gain))
    fun bandPass(low: Float, high: Float, gain: Float = 1f) = filter(BandPassFilter(low, high, gain))
    fun reverb(decay: Float = 1.5f, wet: Float = 0.5f, damping: Float = 0.1f) =
        filter(ReverbFilter(decay = decay, damping = damping, wet = wet))
    fun echo(delay: Float = 0.1f, feedback: Float = 0.5f, wet: Float = 0.5f) =
        filter(EchoFilter(delay = delay, stereoDelay = delay, feedback = feedback, wet = wet))
    fun distortion(edge: Float = 0.2f, mix: Float = 1f) = filter(DistortionFilter(edge = edge, mix = mix))

    internal fun take(playback: SoundPlayback) {
        volume = playback.volume
        pitch = playback.pitch
        fadeIn = playback.fadeIn
        fadeOut = playback.fadeOut
        fadeEachCycle = playback.fadeEachCycle
        repeat = playback.repeat
        category = playback.category
        mode = playback.mode
        falloff = playback.falloff
        doppler = playback.doppler
        filters.clear()
        filters += playback.filters
    }

    fun playback(): SoundPlayback = SoundPlayback(
        repeat, fadeIn, fadeOut, fadeEachCycle, volume, pitch, category, mode, falloff, doppler, filters.toList(),
    )
}
