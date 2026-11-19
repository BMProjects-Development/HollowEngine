package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import kotlinx.serialization.json.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorNotAnimated
import ru.hollowhorizon.hollowengine.common.sounds.Sound
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurve
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve

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
    moments.map { moment ->
        moment.montage to moment.montage.attachments.mapNotNull {
            if (it.spec.keepsRuntimeBetweenWindows || it.windows.any { window -> window.contains(moment.time) }) {
                it.bone to it.spec.rigId
            } else null
        }
    }

/**
 * Legacy montage windows retain sound and effect runtimes between stretches so playback can stop
 * without dropping the instance or its remaining particles.
 */
private fun MontageMoment.present(): List<MontageAttachment> = montage.attachments.mapNotNull { attachment ->
    val stretch = attachment.windows.firstOrNull { it.contains(time) }
    when (val spec = attachment.spec) {
        is Sound -> {
            val playing = if (stretch == null) spec.copy(playing = false, clock = null)
            else spec.copy(clock = time - stretch.start)
            attachment.copy(spec = playing)
        }
        is VfxComponent -> if (stretch == null) attachment.copy(spec = spec.copy(emitting = false)) else attachment
        else -> attachment.takeIf { stretch != null }
    }
}

private val Any.keepsRuntimeBetweenWindows: Boolean get() = this is Sound || this is VfxComponent

private fun ModelRig.withMontage(moment: MontageMoment, present: List<MontageAttachment>): ModelRig {
    val rig = overlay(moment.montage.attachmentRig(present)).withOriginTracks(moment.montage.bones, moment.time) { bone ->
        if (bone in moment.montage.createdNodes) 1f else moment.weight
    }
    return rig.withTracks(moment.montage.tracks, moment.time) { bone, id ->
        if (present.any { it.bone == bone && it.spec.rigId == id }) 1f else moment.weight
    }
}

internal fun Montage.attachmentRig(entries: List<MontageAttachment> = attachments): ModelRig {
    if (entries.isEmpty() && createdNodes.isEmpty()) return ModelRig.EMPTY
    val bones = createdNodes.mapValuesTo(LinkedHashMap()) { (_, origin) -> RigBone(origin = origin) }
    val roots = ArrayList<Any>()
    for ((bone, spec) in entries) {
        if (bone == null) roots.add(spec)
        else bones[bone] = (bones[bone] ?: RigBone.EMPTY).withAttachment(spec)
    }
    return ModelRig(bones, roots)
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
    val direct = rig.holder(track.bone)
    val spec = direct.attachment(track.attachment)
    if (spec == null) {
        val bone = track.migratedNode
        val child = rig.bone(bone) ?: return@fold rig
        val origin = child.origin ?: return@fold rig
        val property = legacyPlacementProperty(track.property)
        if (property != null) {
            val curves = if (property == MontageBoneProperty.SCALE) {
                track.curves.firstOrNull { it.channel == 0 }?.let { curve -> List(3) { curve.copy(channel = it) } }.orEmpty()
            } else track.curves
            return@fold rig.withBone(bone, child.copy(origin = origin.applyTrack(property, curves, time, weight(track.bone, track.attachment))))
        }
        val existing = child.attachment(track.attachment) ?: return@fold rig
        val driven = RigFields.drive(existing, track.property, time, track, weight(track.bone, track.attachment)) ?: return@fold rig
        return@fold rig.withBone(bone, child.withAttachment(driven))
    }
    val driven =
        RigFields.drive(spec, track.property, time, track, weight(track.bone, track.attachment)) ?: return@fold rig
    rig.withHolder(track.bone, direct.withAttachment(driven))
}

internal fun legacyPlacementProperty(name: String): MontageBoneProperty? = when (name) {
    "offset" -> MontageBoneProperty.POSITION
    "rotation" -> MontageBoneProperty.ROTATION
    "scale" -> MontageBoneProperty.SCALE
    else -> null
}

internal fun ModelRig.withOriginTracks(
    tracks: List<MontageBoneTrack>,
    time: Float,
    weight: (String) -> Float = { 1f },
): ModelRig = tracks.fold(this) { rig, track ->
    if (!track.absoluteOrigin) return@fold rig
    val node = rig.bone(track.bone) ?: return@fold rig
    val origin = node.origin ?: return@fold rig
    rig.withBone(track.bone, node.copy(origin = origin.applyTrack(track.property, track.curves, time, weight(track.bone))))
}

private fun RigBoneOrigin.applyTrack(
    property: MontageBoneProperty,
    curves: List<VfxTrackCurve>,
    time: Float,
    weight: Float,
): RigBoneOrigin {
    fun drive(base: Vec3f): Vec3f {
        fun value(axis: Int, rest: Float): Float {
            val curve = curves.firstOrNull { it.channel == axis && it.visible && it.keys.isNotEmpty() } ?: return rest
            return rest + (VfxCurve(curve.keys).valueAt(time) - rest) * weight.coerceIn(0f, 1f)
        }
        return Vec3f(value(0, base.x), value(1, base.y), value(2, base.z))
    }
    return when (property) {
        MontageBoneProperty.POSITION -> copy(offset = drive(offset))
        MontageBoneProperty.ROTATION -> copy(rotation = drive(rotation))
        MontageBoneProperty.SCALE -> copy(scale = drive(scale))
        MontageBoneProperty.VISIBLE -> this
    }
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
    fun read(spec: Any, property: String): FloatArray? = tree(spec)?.at(fieldName(spec, property))?.numbers()

    internal fun fieldName(spec: Any, stored: String): String =
        if (spec is Decal) stored.removePrefix("decal.") else stored

    /**
     * Every field of [spec] a montage can animate, as it is stored; its id and kind are never among them. Fields of
     * nested objects and lists are named by their path, as `playback.volume` or `playback.filters.0.cutoff`.
     */
    fun animatable(spec: Any): List<Field> {
        val tree = tree(spec) ?: return emptyList()
        val descriptor = RigAttachmentTypes.of(spec)?.serializer?.descriptor
        return buildList { collect(tree, descriptor, "", this) }
    }

    /** The animatable fields of [value], what [descriptor] says of it, into [into], each named after [prefix]. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun collect(value: JsonObject, descriptor: SerialDescriptor?, prefix: String, into: MutableList<Field>) {
        value.forEach { (name, element) ->
            if (name == TYPE || name == ID && prefix.isEmpty()) return@forEach
            val index = descriptor?.getElementIndex(name)?.takeIf { it >= 0 }
            if (descriptor != null && index == null) return@forEach
            if (index != null && descriptor.getElementAnnotations(index).any { it is EditorNotAnimated || it is EditorHidden }) {
                return@forEach
            }
            val path = prefix + name
            field(path, element)?.let { into += it; return@forEach }
            descend(element, index?.let { descriptor.getElementDescriptor(it) }, "$path.", into)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun descend(element: JsonElement, descriptor: SerialDescriptor?, prefix: String, into: MutableList<Field>) {
        when (element) {
            is JsonObject -> collect(element, descriptor?.let { concrete(it, element) }, prefix, into)
            is JsonArray -> element.forEachIndexed { index, item ->
                descend(item, descriptor?.takeIf { it.elementsCount > 0 }?.getElementDescriptor(0), "$prefix$index.", into)
            }
            else -> Unit
        }
    }

    /** What [descriptor] is for [value]: the class it names by its type, when it is one of several. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun concrete(descriptor: SerialDescriptor, value: JsonObject): SerialDescriptor? {
        if (descriptor.kind !is PolymorphicKind) return descriptor
        val type = (value[TYPE] as? JsonPrimitive)?.contentOrNull ?: return null
        val candidates = if (descriptor.kind == PolymorphicKind.OPEN) {
            MontageFormat.fields.serializersModule.getPolymorphicDescriptors(descriptor)
        } else if (descriptor.elementsCount > 1) {
            descriptor.getElementDescriptor(1).elementDescriptors.toList()
        } else emptyList()
        return candidates.firstOrNull { it.serialName == type }
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
        spec: Any,
        property: String,
        time: Float,
        track: MontageTrack,
        weight: Float,
    ): Any? {
        val serializer = serializerOf(spec) ?: return null
        val tree = tree(spec, serializer) ?: return null
        val name = fieldName(spec, property)
        val authored = tree.at(name) ?: return null
        val from = authored.numbers()
        val channels = from?.size ?: ((track.curves.maxOfOrNull { it.channel } ?: 0) + 1)
        val values = FloatArray(channels) { channel ->
            val start = from?.getOrNull(channel)
            val curve =
                track.curves.firstOrNull { it.channel == channel && it.visible }?.keys?.takeIf { it.isNotEmpty() }
            val value = curve?.let { VfxCurve(it).valueAt(time) } ?: start ?: 0f
            if (start == null) value else start + (value - start) * weight.coerceIn(0f, 1f)
        }
        val replaced = tree.with(name.split('.'), authored.withNumbers(values)) as? JsonObject ?: return null
        return runCatching { MontageFormat.fields.decodeFromJsonElement(serializer, replaced) }.getOrNull()
    }

    /**
     * The fields [after] holds otherwise than [before], by their paths: as deep as both hold the same shape, so an
     * edit of one number in a nested object names that number.
     */
    fun changed(before: Any, after: Any): List<String> {
        val from = tree(before) ?: return emptyList()
        val to = tree(after) ?: return emptyList()
        if (before::class != after::class) return listOf("")
        return buildList { diff(from, to, "", this) }
    }

    private fun diff(from: JsonElement?, to: JsonElement?, path: String, into: MutableList<String>) {
        if (from == to) return
        when {
            from is JsonObject && to is JsonObject && from[TYPE] == to[TYPE] && from.numbers() == null ->
                (from.keys + to.keys).forEach { diff(from[it], to[it], join(path, it), into) }

            from is JsonArray && to is JsonArray && from.size == to.size && from.numbers() == null ->
                from.indices.forEach { diff(from[it], to[it], join(path, it.toString()), into) }

            else -> into += path
        }
    }

    private fun join(path: String, name: String) = if (path.isEmpty()) name else "$path.$name"

    /** [into] with the fields at [names] taken from [from]; null when that does not read back. */
    fun copy(from: Any, into: Any, names: Collection<String>): Any? {
        if (names.isEmpty()) return into
        val source = tree(from) ?: return null
        val serializer = serializerOf(into) ?: return null
        val target = tree(into, serializer) ?: return null
        val merged = names.fold(target as JsonElement) { tree, name ->
            val value = source.at(name) ?: return@fold tree
            if (tree.at(name) == null) tree.withNew(name, value) else tree.with(name.split('.'), value)
        }
        return runCatching { MontageFormat.fields.decodeFromJsonElement(serializer, merged) }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerOf(spec: Any): KSerializer<Any>? =
        RigAttachmentTypes.of(spec)?.serializer as? KSerializer<Any>

    private fun tree(spec: Any): JsonObject? = serializerOf(spec)?.let { tree(spec, it) }

    private fun tree(spec: Any, serializer: KSerializer<Any>): JsonObject? =
        runCatching { MontageFormat.fields.encodeToJsonElement(serializer, spec) as? JsonObject }.getOrNull()

    /** What lies at [path], its steps names of fields or indices of lists. */
    private fun JsonElement.at(path: String): JsonElement? = path.split('.').fold(this as JsonElement?) { element, name ->
        when (element) {
            is JsonObject -> element[name]
            is JsonArray -> name.toIntOrNull()?.let(element::getOrNull)
            else -> null
        }
    }

    /** This with [value] at [path], when there is something there to replace. */
    private fun JsonElement.with(path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val name = path.first()
        return when (this) {
            is JsonObject -> {
                val child = this[name] ?: return this
                JsonObject(this + (name to child.with(path.drop(1), value)))
            }

            is JsonArray -> {
                val index = name.toIntOrNull()?.takeIf { it in indices } ?: return this
                JsonArray(mapIndexed { at, item -> if (at == index) item.with(path.drop(1), value) else item })
            }

            else -> this
        }
    }

    /** This with a field at [path] that it did not hold, put into the object that holds its last step. */
    private fun JsonElement.withNew(path: String, value: JsonElement): JsonElement {
        val steps = path.split('.')
        val parent = steps.dropLast(1)
        val holder = (if (parent.isEmpty()) this else at(parent.joinToString("."))) as? JsonObject ?: return this
        val updated = JsonObject(holder + (steps.last() to value))
        return if (parent.isEmpty()) updated else with(parent, updated)
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
