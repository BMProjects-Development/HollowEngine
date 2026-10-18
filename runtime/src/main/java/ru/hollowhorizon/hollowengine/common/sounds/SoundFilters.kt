package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.VfxModuleRevision
import kotlin.reflect.KClass

private const val LANG = "hollowengine.gui.sound_filter"

/**
 * Something a sound passes through on its way to the listener: a filter that takes away frequencies, or an
 * effect, as an echo, mixed in with it. The server names them, so each kind is registered with [SoundFilterTypes];
 * the client decides how it sounds.
 */
@Serializable
abstract class SoundFilterSpec

/** One kind of [SoundFilterSpec]: how it is stored, and what the editor calls it. */
class SoundFilterType<S : SoundFilterSpec>(
    val id: String,
    val specClass: KClass<S>,
    val serializer: KSerializer<S>,
) {
    val key: ResourceLocation = id.rl
}

/** The kinds of filter a sound may have; addons add their own. */
object SoundFilterTypes {
    val point = ExtensionPoints.create<SoundFilterType<*>>("hollowengine:sound/filter_types".rl)

    init {
        point.onChange(VfxModuleRevision::invalidate)
        register(SoundFilterType(LowPassFilter.TYPE_ID, LowPassFilter::class, LowPassFilter.serializer()))
        register(SoundFilterType(HighPassFilter.TYPE_ID, HighPassFilter::class, HighPassFilter.serializer()))
        register(SoundFilterType(BandPassFilter.TYPE_ID, BandPassFilter::class, BandPassFilter.serializer()))
        register(SoundFilterType(ReverbFilter.TYPE_ID, ReverbFilter::class, ReverbFilter.serializer()))
        register(SoundFilterType(EchoFilter.TYPE_ID, EchoFilter::class, EchoFilter.serializer()))
        register(SoundFilterType(DistortionFilter.TYPE_ID, DistortionFilter::class, DistortionFilter.serializer()))
    }

    fun register(type: SoundFilterType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<SoundFilterType<*>> get() = point.extensions

    fun registerInto(builder: SerializersModuleBuilder) {
        builder.polymorphic(SoundFilterSpec::class) {
            all.forEach { type -> subclass(type) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PolymorphicModuleBuilder<SoundFilterSpec>.subclass(type: SoundFilterType<*>) =
        subclass(type.specClass as KClass<SoundFilterSpec>, type.serializer as KSerializer<SoundFilterSpec>)
}

/** Takes away what is above [cutoff], as a wall or water does. */
@Serializable
@SerialName(LowPassFilter.TYPE_ID)
@EditorName("$LANG.low_pass")
data class LowPassFilter(
    @EditorName("$LANG.cutoff")
    @EditorDescription("$LANG.cutoff.hint")
    @EditorRange(min = 20.0, max = 20000.0)
    val cutoff: Float = 1000f,
    @EditorName("$LANG.gain")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val gain: Float = 1f,
) : SoundFilterSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:sound/low_pass"
    }
}

/** Takes away what is below [cutoff], as a small speaker does. */
@Serializable
@SerialName(HighPassFilter.TYPE_ID)
@EditorName("$LANG.high_pass")
data class HighPassFilter(
    @EditorName("$LANG.cutoff")
    @EditorDescription("$LANG.cutoff.hint")
    @EditorRange(min = 20.0, max = 20000.0)
    val cutoff: Float = 1000f,
    @EditorName("$LANG.gain")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val gain: Float = 1f,
) : SoundFilterSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:sound/high_pass"
    }
}

/** Keeps what lies between [low] and [high], as a radio or a telephone does. */
@Serializable
@SerialName(BandPassFilter.TYPE_ID)
@EditorName("$LANG.band_pass")
data class BandPassFilter(
    @EditorName("$LANG.low")
    @EditorRange(min = 20.0, max = 20000.0)
    val low: Float = 300f,
    @EditorName("$LANG.high")
    @EditorRange(min = 20.0, max = 20000.0)
    val high: Float = 3000f,
    @EditorName("$LANG.gain")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val gain: Float = 1f,
) : SoundFilterSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:sound/band_pass"
    }
}

/** The sound of a room, a cave or a hall: what comes back from the walls. */
@Serializable
@SerialName(ReverbFilter.TYPE_ID)
@EditorName("$LANG.reverb")
data class ReverbFilter(
    @EditorName("$LANG.decay")
    @EditorDescription("$LANG.decay.hint")
    @EditorRange(min = 0.1, max = 20.0)
    val decay: Float = 1.5f,
    @EditorName("$LANG.density")
    @EditorDescription("$LANG.density.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val density: Float = 1f,
    @EditorName("$LANG.diffusion")
    @EditorDescription("$LANG.diffusion.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val diffusion: Float = 1f,
    @EditorName("$LANG.damping")
    @EditorDescription("$LANG.damping.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val damping: Float = 0.1f,
    @EditorName("$LANG.reflections")
    @EditorRange(min = 0.0, max = 3.16)
    val reflections: Float = 0.05f,
    @EditorName("$LANG.reflections_delay")
    @EditorRange(min = 0.0, max = 0.3)
    val reflectionsDelay: Float = 0.007f,
    @EditorName("$LANG.late")
    @EditorRange(min = 0.0, max = 10.0)
    val late: Float = 1.26f,
    @EditorName("$LANG.wet")
    @EditorDescription("$LANG.wet.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val wet: Float = 0.5f,
) : SoundFilterSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:sound/reverb"
    }
}

/** Repeats of the sound that come back and die away: a delay. */
@Serializable
@SerialName(EchoFilter.TYPE_ID)
@EditorName("$LANG.echo")
data class EchoFilter(
    @EditorName("$LANG.delay")
    @EditorDescription("$LANG.delay.hint")
    @EditorRange(min = 0.0, max = 0.207)
    val delay: Float = 0.1f,
    @EditorName("$LANG.stereo_delay")
    @EditorRange(min = 0.0, max = 0.404)
    val stereoDelay: Float = 0.1f,
    @EditorName("$LANG.feedback")
    @EditorDescription("$LANG.feedback.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val feedback: Float = 0.5f,
    @EditorName("$LANG.damping")
    @EditorRange(min = 0.0, max = 0.99, slider = true)
    val damping: Float = 0.5f,
    @EditorName("$LANG.spread")
    @EditorRange(min = -1.0, max = 1.0, slider = true)
    val spread: Float = -1f,
    @EditorName("$LANG.wet")
    @EditorDescription("$LANG.wet.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val wet: Float = 0.5f,
) : SoundFilterSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:sound/echo"
    }
}

/** Overdrives the sound until it breaks up, as a broken speaker or a radio does. */
@Serializable
@SerialName(DistortionFilter.TYPE_ID)
@EditorName("$LANG.distortion")
data class DistortionFilter(
    @EditorName("$LANG.edge")
    @EditorDescription("$LANG.edge.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val edge: Float = 0.2f,
    @EditorName("$LANG.gain")
    @EditorRange(min = 0.01, max = 1.0, slider = true)
    val gain: Float = 0.05f,
    @EditorName("$LANG.tone")
    @EditorDescription("$LANG.tone.hint")
    @EditorRange(min = 80.0, max = 24000.0)
    val tone: Float = 8000f,
    @EditorName("$LANG.mix")
    @EditorDescription("$LANG.mix.hint")
    @EditorRange(min = 0.0, max = 1.0, slider = true)
    val mix: Float = 1f,
) : SoundFilterSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:sound/distortion"
    }
}
