package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeTypes
import ru.hollowhorizon.hollowengine.common.sounds.SoundFilterTypes
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurve
import ru.hollowhorizon.hollowengine.common.vfx.VfxKey
import ru.hollowhorizon.hollowengine.common.vfx.VfxModuleTypes
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeTypes
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve

/**
 * One authored action laid out on a timeline: clips of the model it plays on, things it hangs on the
 * model's rig for a while, fields of what hangs there it animates, and named events.
 */
@Serializable
data class Montage(
    val previewModel: String = "",
    val duration: Float = 1f,
    val clips: List<MontageClip> = emptyList(),
    val attachments: List<MontageAttachment> = emptyList(),
    val tracks: List<MontageTrack> = emptyList(),
    val bones: List<MontageBoneTrack> = emptyList(),
    val events: List<MontageEvent> = emptyList(),
) {
    companion object {
        val EMPTY = Montage()
    }
}

/**
 * A stretch of one clip of the model: from [start] on the montage's timeline, for [length] seconds, from
 * [from] seconds into the clip. Clips that overlap share the bones they both move, by their fades.
 */
@Serializable
data class MontageClip(
    val id: String,
    val animation: String,
    val start: Float = 0f,
    val length: Float = 1f,
    val from: Float = 0f,
    val speed: Float = 1f,
    val loop: Boolean = false,
    val fadeIn: Float = 0f,
    val fadeOut: Float = 0f,
    val mask: BoneMask = BoneMask.full(),
    val row: Int = 0,
    val weight: List<VfxKey> = emptyList(),
) {
    val end: Float get() = start + length
}

@Serializable
data class MontageAttachment(
    val bone: String? = null,
    val spec: RigAttachmentSpec,
    val windows: List<MontageWindow> = listOf(MontageWindow()),
) {
    fun isActive(time: Float): Boolean = windows.any { it.contains(time) }
}

/** A stretch of the montage, from [start] to [end], or to the end of the montage when [end] is null. */
@Serializable
data class MontageWindow(val start: Float = 0f, val end: Float? = null) {
    fun contains(time: Float): Boolean = time >= start && (end == null || time <= end)
}

/** What of a bone a [MontageBoneTrack] moves. */
@Serializable
enum class MontageBoneProperty(val rest: Float) {
    /** Shifted in the parent's space, in blocks of the model. */
    POSITION(0f),

    /** Turned in the bone's own space, by Euler degrees about X, then Y, then Z. */
    ROTATION(0f),

    SCALE(1f),

    /** Whether the bone, and what hangs under it, is drawn: shown from one half up, a flag keyed as a number. */
    VISIBLE(1f),
}

/**
 * One part of a bone the montage moves by keys, over whatever its clips do to it, the way a rig pose does:
 * the clips play, and the bone is shifted, turned or scaled from there. One curve per axis.
 */
@Serializable
data class MontageBoneTrack(
    val bone: String,
    val property: MontageBoneProperty,
    val curves: List<VfxTrackCurve> = emptyList(),
) {
    /** The value of the track at [time], one number per axis. */
    fun valueAt(time: Float): Vec3f {
        fun axis(channel: Int) =
            curves.firstOrNull { it.channel == channel && it.visible }?.keys?.takeIf { it.isNotEmpty() }
                ?.let { VfxCurve(it).valueAt(time) } ?: property.rest
        return Vec3f(axis(0), axis(1), axis(2))
    }
}

/**
 * One field of one attachment the montage animates: of its own or of the model's rig, found by [bone] and
 * [attachment] id. [property] names the field as the attachment is stored; one curve per number in it.
 * While the montage plays, the field takes the track's value over what is authored.
 */
@Serializable
data class MontageTrack(
    val bone: String? = null,
    val attachment: String,
    val property: String,
    val curves: List<VfxTrackCurve> = emptyList(),
)

/** A named moment of the montage, for scripts to act on. */
@Serializable
data class MontageEvent(
    val time: Float,
    val name: String,
)

/** Plays a montage for as long as the controller stays in the state, looped by default like a clip. */
@Serializable
@SerialName("hollowengine:animator/state/montage")
data class MontageStateSpec(
    override val id: String,
    val montage: String = "",
    val playMode: AnimationPlayMode = AnimationPlayMode.Loop,
    val speed: AnimationExpression = AnimationExpression.ONE,
) : AnimationControllerStateSpec() {
    override fun withId(id: String) = copy(id = id)

    override fun expressions() = listOf(speed)
}

/**
 * A montage played on an entity over the rest of its animator, from [startGameTime] on the level clock,
 * so both sides play it in step without a packet per frame. It fades out once it has played through, or
 * from [stopAtGameTime] when it is stopped early.
 */
@Serializable
@SerialName("hollowengine:animator/montage")
data class MontageLayerSpec(
    override val id: String = newAnimationLayerId("montage"),
    val montage: String,
    val speed: Float = 1f,
    override val weight: AnimationExpression = AnimationExpression.ONE,
    override val priority: Int = PRIORITY,
    override val blendMode: LayerBlendMode = LayerBlendMode.Override,
    override val mask: BoneMask = BoneMask.full(),
    override val fadeIn: Float = 0.2f,
    override val fadeOut: Float = 0.2f,
    val startGameTime: Long? = null,
    val stopAtGameTime: Long? = null,
    val removeAtGameTime: Long? = null,
) : AnimatorLayerSpec() {
    override fun withCommon(
        id: String,
        weight: AnimationExpression,
        priority: Int,
        blendMode: LayerBlendMode,
        mask: BoneMask,
        fadeIn: Float,
        fadeOut: Float,
    ) = copy(
        id = id, weight = weight, priority = priority, blendMode = blendMode,
        mask = mask, fadeIn = fadeIn, fadeOut = fadeOut,
    )

    /**
     * Where this playback is at [gameTime] on the level clock, counted fully, for what asks which things it
     * hangs on the model rather than how it looks; null when it has no start or its montage cannot be read.
     */
    fun momentAt(gameTime: Long, client: Boolean): MontageMoment? {
        val start = startGameTime ?: return null
        val montage = MontageAssets.of(this.montage, client) ?: return null
        val time = ((gameTime - start) / TICKS_PER_SECOND * speed).coerceIn(0f, montage.duration)
        return MontageMoment(montage, time, 1f)
    }

    companion object {
        /** Above the model's own layers, so an action covers whatever the controller is doing. */
        const val PRIORITY = 100

        private const val TICKS_PER_SECOND = 20f
    }
}

/** Reads and writes json `.montage` files. */
object MontageFormat {
    private class Built(val revision: Int, val file: Json, val fields: Json)

    @Volatile
    private var cached: Built? = null

    /** Built again whenever the kinds of rig attachment change, as when an addon loads. */
    private val built: Built
        get() {
            val revision = TagModuleRevision.current
            cached?.takeIf { it.revision == revision }?.let { return it }
            val module = SerializersModule {
                RigAttachmentTypes.registerInto(this)
                ColliderShapeTypes.registerInto(this)
                VfxNodeTypes.registerInto(this)
                VfxModuleTypes.registerInto(this)
                SoundFilterTypes.registerInto(this)
            }
            val file = Json {
                serializersModule = module
                prettyPrint = true
                prettyPrintIndent = "  "
                ignoreUnknownKeys = true
                encodeDefaults = false
                allowComments = true
                allowTrailingComma = true
                classDiscriminator = "type"
            }
            val fields = Json {
                serializersModule = module
                ignoreUnknownKeys = true
                encodeDefaults = true
                classDiscriminator = "type"
            }
            return Built(revision, file, fields).also { cached = it }
        }

    private val json: Json get() = built.file

    /** Every field of an attachment, defaults too, for animating them by name; see [RigFields]. */
    internal val fields: Json get() = built.fields

    fun read(text: String): Montage =
        if (text.isBlank()) Montage.EMPTY else json.decodeFromString(Montage.serializer(), text)

    fun write(montage: Montage): String = json.encodeToString(Montage.serializer(), montage)
}

/** Montages by id, as `<namespace>:montages/<name>`, the `.montage` suffix optional. */
object MontageAssets {
    private val assets = SharedAssets("montage") { MontageFormat.read(it.readBytes().decodeToString()) }

    fun of(id: String, client: Boolean): Montage? = locationOf(id)?.let { assets.of(it, client) }

    /** Puts an edited montage in place on both sides, as the editor does after saving it. */
    fun put(id: String, montage: Montage) {
        locationOf(id)?.let { assets.put(it, montage) }
    }

    fun locationOf(id: String): ResourceLocation? {
        if (id.isBlank()) return null
        val location = ResourceLocation.tryParse(id) ?: return null
        return if (location.path.endsWith(SUFFIX)) location else location.withSuffix(SUFFIX)
    }

    private const val SUFFIX = ".montage"
}
