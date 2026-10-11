package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.json.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorNotAnimated
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurve

/** One montage as it plays this frame: where in it, and how much it counts. */
class MontageMoment(val montage: Montage, val time: Float, val weight: Float)

/**
 * A rig with what the montages playing lay over it. [key] tells which attachments they hang on it: it
 * changes only when those do, which is when a model has to be built again rather than respecced.
 */
class MontageRig(val rig: ModelRig, val key: List<Any>)

/** This rig with what [moments] lay over it, in the order they play. */
fun ModelRig.withMontages(moments: List<MontageMoment>): MontageRig {
    if (moments.isEmpty()) return MontageRig(this, emptyList())
    val rig = moments.fold(this) { rig, moment -> rig.withMontage(moment, moment.present()) }
    return MontageRig(rig, montageKey(moments))
}

/** What [MontageRig.key] is for [moments], without laying them over anything. */
fun montageKey(moments: List<MontageMoment>): List<Any> =
    moments.map { moment -> moment.montage to moment.present().map { it.bone to it.spec.id } }

/**
 * What the montage hangs on the model at its moment: what is in one of its stretches, and what stays between
 * them as [RigAttachmentSpec.held] says, so taking it out of a stretch does not build the model again.
 */
private fun MontageMoment.present(): List<MontageAttachment> = montage.attachments.mapNotNull { attachment ->
    if (attachment.isActive(time)) attachment else attachment.spec.held()?.let { attachment.copy(spec = it) }
}

private fun ModelRig.withMontage(moment: MontageMoment, present: List<MontageAttachment>): ModelRig {
    var rig = if (present.isEmpty()) this else overlay(
        ModelRig(
            bones = present.filter { it.bone != null }.groupBy { it.bone!! }
                .mapValues { (_, hung) -> RigBone(attachments = hung.map { it.spec }) },
            attachments = present.filter { it.bone == null }.map { it.spec },
        )
    )
    return rig.withTracks(moment.montage.tracks, moment.time) { bone, id ->
        if (present.any { it.bone == bone && it.spec.id == id }) 1f else moment.weight
    }
}

/**
 * This rig with the fields [tracks] animate set to what they read at [time], each mixed from what is authored
 * by [weight] of the attachment it belongs to.
 */
fun ModelRig.withTracks(
    tracks: List<MontageTrack>,
    time: Float,
    weight: (bone: String?, id: String) -> Float = { _, _ -> 1f },
): ModelRig = tracks.fold(this) { rig, track ->
    val holder = rig.holder(track.bone)
    val spec = holder.attachment(track.attachment) ?: return@fold rig
    val driven =
        RigFields.drive(spec, track.property, time, track, weight(track.bone, track.attachment)) ?: return@fold rig
    rig.withHolder(track.bone, holder.withAttachment(driven))
}

/**
 * The fields of a rig attachment as numbers, found by name in the attachment as it is stored, so any kind of
 * attachment, an addon's too, can be animated without declaring anything: a number, a flag, a vector, or an
 * expression that is a number.
 */
object RigFields {
    /** What kind of value a field holds, as a timeline keys it. */
    enum class Kind { NUMBER, TOGGLE, VECTOR }

    /** One field of an attachment a montage can animate, with what it holds now. */
    class Field(val name: String, val kind: Kind, val values: FloatArray)

    /** The numbers [property] of [spec] holds, or null when it is no such field or holds no plain numbers. */
    fun read(spec: RigAttachmentSpec, property: String): FloatArray? = tree(spec)?.at(property)?.numbers()

    /** Every field of [spec] a montage can animate, as it is stored; its id and kind are never among them. */
    fun animatable(spec: RigAttachmentSpec): List<Field> {
        val still = stillFields(spec)
        return tree(spec)?.entries.orEmpty()
            .mapNotNull { (name, value) -> if (name in still) null else field(name, value) }
    }

    /** The fields of [spec]'s kind marked [EditorNotAnimated] or hidden from the editor, with its id and kind. */
    private fun stillFields(spec: RigAttachmentSpec): Set<String> {
        val descriptor = RigAttachmentTypes.of(spec)?.serializer?.descriptor ?: return setOf(ID, TYPE)
        return (0 until descriptor.elementsCount).filter { index ->
                descriptor.getElementAnnotations(index).any { it is EditorNotAnimated || it is EditorHidden }
            }.mapTo(hashSetOf(ID, TYPE)) { descriptor.getElementName(it) }
    }

    private fun field(name: String, value: JsonElement): Field? {
        val numbers = value.numbers() ?: floatArrayOf(0f).takeIf { value is JsonObject && value.keys == setOf(SOURCE) }
        ?: return null
        val kind = when {
            value is JsonPrimitive && value.booleanOrNull != null && !value.isString -> Kind.TOGGLE
            numbers.size == 3 -> Kind.VECTOR
            numbers.size == 1 -> Kind.NUMBER
            else -> return null
        }
        return Field(name, kind, numbers)
    }

    /**
     * [spec] with [property] set to what [track] reads at [time], mixed by [weight] from what is authored.
     * Null when it has no such field or the result does not read back as that kind of attachment.
     */
    fun drive(
        spec: RigAttachmentSpec,
        property: String,
        time: Float,
        track: MontageTrack,
        weight: Float,
    ): RigAttachmentSpec? {
        val tree = tree(spec) ?: return null
        val authored = tree.at(property) ?: return null
        val from = authored.numbers()
        val channels = from?.size ?: ((track.curves.maxOfOrNull { it.channel } ?: 0) + 1)
        val values = FloatArray(channels) { channel ->
            val start = from?.getOrNull(channel)
            val curve =
                track.curves.firstOrNull { it.channel == channel && it.visible }?.keys?.takeIf { it.isNotEmpty() }
            val value = curve?.let { VfxCurve(it).valueAt(time) } ?: start ?: 0f
            if (start == null) value else start + (value - start) * weight.coerceIn(0f, 1f)
        }
        val replaced = tree.with(property.split('.'), authored.withNumbers(values)) as? JsonObject ?: return null
        return runCatching {
            MontageFormat.fields.decodeFromJsonElement(
                RigAttachmentSpec.serializer(),
                replaced
            )
        }.getOrNull()
    }

    /** The top-level fields [after] holds otherwise than [before]. */
    fun changed(before: RigAttachmentSpec, after: RigAttachmentSpec): List<String> {
        val from = tree(before) ?: return emptyList()
        val to = tree(after) ?: return emptyList()
        return (from.keys + to.keys).filter { from[it] != to[it] }
    }

    /** [into] with the top-level fields [names] taken from [from]; null when that does not read back. */
    fun copy(from: RigAttachmentSpec, into: RigAttachmentSpec, names: Collection<String>): RigAttachmentSpec? {
        if (names.isEmpty()) return into
        val source = tree(from) ?: return null
        val target = tree(into) ?: return null
        val merged = JsonObject(target + names.mapNotNull { name -> source[name]?.let { name to it } })
        return runCatching {
            MontageFormat.fields.decodeFromJsonElement(
                RigAttachmentSpec.serializer(),
                merged
            )
        }.getOrNull()
    }

    private fun tree(spec: RigAttachmentSpec): JsonObject? = runCatching {
        MontageFormat.fields.encodeToJsonElement(
            RigAttachmentSpec.serializer(),
            spec
        ) as? JsonObject
    }.getOrNull()

    private fun JsonElement.at(path: String): JsonElement? =
        path.split('.').fold(this as JsonElement?) { element, name -> (element as? JsonObject)?.get(name) }

    private fun JsonElement.with(path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val obj = this as? JsonObject ?: return this
        val child = obj[path.first()] ?: return this
        return JsonObject(obj + (path.first() to child.with(path.drop(1), value)))
    }

    private fun JsonElement.numbers(): FloatArray? = when (this) {
        is JsonPrimitive -> if (isString) null
        else floatOrNull?.let { floatArrayOf(it) } ?: booleanOrNull?.let { floatArrayOf(if (it) 1f else 0f) }

        is JsonArray -> map { (it as? JsonPrimitive)?.floatOrNull ?: return null }.toFloatArray()
        is JsonObject -> when {
            keys == setOf(SOURCE) -> (get(SOURCE) as? JsonPrimitive)?.content?.toFloatOrNull()?.let { floatArrayOf(it) }
            keys.containsAll(XYZ) -> XYZ.map { (get(it) as? JsonPrimitive)?.floatOrNull ?: return null }.toFloatArray()
            else -> null
        }
    }

    private fun JsonElement.withNumbers(values: FloatArray): JsonElement = when (this) {
        is JsonPrimitive -> when {
            booleanOrNull != null -> JsonPrimitive(values[0] >= 0.5f)
            else -> JsonPrimitive(values[0])
        }

        is JsonArray -> JsonArray(values.map(::JsonPrimitive))
        is JsonObject -> when {
            SOURCE in keys -> JsonObject(this + (SOURCE to JsonPrimitive(values[0].toString())))
            else -> JsonObject(this + XYZ.mapIndexed { i, name -> name to JsonPrimitive(values.getOrElse(i) { 0f }) })
        }
    }

    /** What an [AnimationExpression] is stored as. */
    private const val SOURCE = "source"
    private const val ID = "id"
    private const val TYPE = "type"
    private val XYZ = listOf("x", "y", "z")
}
