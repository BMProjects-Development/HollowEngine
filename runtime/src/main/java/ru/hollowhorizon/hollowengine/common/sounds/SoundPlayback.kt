package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
import kotlin.math.max
import net.minecraft.sounds.SoundSource as SoundCategory

private const val LANG = "hollowengine.gui.sound_playback"

@Serializable
sealed interface SoundRepeat {
    @Serializable
    @EditorName("$LANG.repeat.once")
    data object Once : SoundRepeat
    @Serializable
    @EditorName("$LANG.repeat.count")
    data class Count(
        @EditorName("$LANG.repeat.plays")
        @EditorRange(min = 1.0)
        val plays: Int = 1,
    ) : SoundRepeat {
        init { require(plays > 0) { "The number of plays must be positive" } }
    }
    @Serializable
    @EditorName("$LANG.repeat.forever")
    data object Forever : SoundRepeat

    fun hasNext(completed: Long): Boolean = when (this) {
        Once -> completed < 1
        is Count -> completed < plays
        Forever -> true
    }
}

/** How a sound sits around the listener. */
@Serializable
enum class SoundMode {
    /** Comes from where it is: turned toward it and quieter away from it. */
    @EditorName("$LANG.mode.spatial")
    SPATIAL,

    /** Not turned anywhere, as music is, yet quieter away from where it is. */
    @EditorName("$LANG.mode.fixed")
    FIXED,

    /** Not turned anywhere and as loud everywhere, as long as it plays. */
    @EditorName("$LANG.mode.global")
    GLOBAL,
}

/** How the volume goes from full to nothing across a [SoundFalloff]. */
@Serializable
enum class SoundCurve {
    @EditorName("$LANG.curve.linear")
    LINEAR,

    /** Starts and ends gently. */
    @EditorName("$LANG.curve.smooth")
    SMOOTH,

    /** Drops fast near the source and lingers far from it, as sound in the open does. */
    @EditorName("$LANG.curve.natural")
    NATURAL,
    ;

    /** The volume at [t], from 0 at full volume to 1 where it is gone. */
    fun gain(t: Float): Float {
        val left = 1f - t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> left
            SMOOTH -> left * left * (3f - 2f * left)
            NATURAL -> left * left
        }
    }
}

/**
 * Where a sound fades out: over [distance] blocks from the edge of what plays it, its areas or its point.
 * [inside] moves the fade within the areas, so the sound is gone at their edge rather than past it.
 */
@Serializable
data class SoundFalloff(
    @EditorName("$LANG.falloff.distance")
    @EditorDescription("$LANG.falloff.distance.hint")
    @EditorDefault("16.0")
    val distance: Float? = null,
    @EditorName("$LANG.falloff.inside")
    @EditorDescription("$LANG.falloff.inside.hint")
    @EditorNotAnimated
    val inside: Boolean = false,
    @EditorName("$LANG.falloff.curve")
    @EditorNotAnimated
    val curve: SoundCurve = SoundCurve.LINEAR,
) {
    init {
        require(distance == null || distance.isFinite() && distance > 0f) { "The falloff distance must be positive" }
    }

    /**
     * The volume at [depth] blocks past the edge, negative inside the areas; [fallback] is the distance when
     * none is set. A point has no inside, and fades from itself.
     */
    fun gain(depth: Float, fallback: Float, hasInside: Boolean): Float {
        val span = max(distance ?: fallback, MIN_FALLOFF)
        val past = if (inside && hasInside) depth + span else depth
        return curve.gain(past / span)
    }
}

/** The shortest a falloff is taken to be, so a sound never ends in a wall of zero width. */
private const val MIN_FALLOFF = 0.01f

/**
 * How a sound plays. What changes how it is read, [repeat], the fades and the [category], starts it over; the
 * rest changes as it plays.
 */
@Serializable
data class SoundPlayback(
    @EditorName("$LANG.repeat")
    @EditorNotAnimated
    val repeat: SoundRepeat = SoundRepeat.Once,
    @EditorName("$LANG.fade_in")
    @EditorRange(min = 0.0)
    @EditorNotAnimated
    val fadeIn: Float = 0f,
    @EditorName("$LANG.fade_out")
    @EditorRange(min = 0.0)
    @EditorNotAnimated
    val fadeOut: Float = 0f,
    @EditorName("$LANG.fade_each_cycle")
    @EditorNotAnimated
    val fadeEachCycle: Boolean = false,
    @EditorName("$LANG.volume")
    @EditorRange(min = 0.0)
    val volume: Float = 1f,
    @EditorName("$LANG.pitch")
    @EditorRange(min = 0.01)
    val pitch: Float = 1f,
    @EditorName("$LANG.category")
    val category: SoundCategory = SoundCategory.MASTER,
    @EditorName("$LANG.mode")
    @EditorDescription("$LANG.mode.hint")
    @EditorNotAnimated
    val mode: SoundMode = SoundMode.SPATIAL,
    @EditorName("$LANG.falloff")
    val falloff: SoundFalloff = SoundFalloff(),
    @EditorName("$LANG.doppler")
    @EditorDescription("$LANG.doppler.hint")
    @EditorRange(min = 0.0)
    val doppler: Float = 1f,
    @EditorName("$LANG.filters")
    @EditorDescription("$LANG.filters.hint")
    val filters: List<SoundFilterSpec> = emptyList(),
) {
    init {
        require(fadeIn.isFinite() && fadeIn >= 0f && fadeOut.isFinite() && fadeOut >= 0f)
        require(volume.isFinite() && volume >= 0f)
        require(pitch.isFinite() && pitch > 0f)
        require(doppler.isFinite() && doppler >= 0f)
    }

    /** What the stream is read with: a change here starts the sound over. */
    fun structure(): SoundPlayback = SoundPlayback(repeat, fadeIn, fadeOut, fadeEachCycle, category = category)
}
