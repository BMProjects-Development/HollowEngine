package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.math.abs

/**
 * Puts the effect timeline into the editor timeline and takes it back out.
 */
class VfxTimelineSession(
    private val document: HollowIdeVfxDocument,
    private val preview: VfxPreviewState,
) {
    /** Records into the file's history, so Ctrl+Z walks back through effect and tracks in the order they were edited. */
    val timeline = TimelineController(document.history)

    /** Properties the author asked for, as `node|property`. */
    private val pinned = LinkedHashSet<String>()

    /** Properties the author took off the list; they stay off even with keys. */
    private val hidden = HashSet<String>()

    /** Nodes that were selected at some point, so their group row stays. */
    private val touched = LinkedHashSet<String>()

    private var suppressCommit = false
    private var builtFor: Int = -1
    private var signature: List<Any> = emptyList()

    init {
        timeline.onChanged = {
            commit()
            applyListing()
        }
        timeline.onTimeChanged = { preview.requestSeek(timeline.currentTime) }
        timeline.onHideProperty = { property -> hide(property.id) }
        timeline.onHideGroup = { group -> hideNode(group) }
    }

    /** Rebuilds the tracks when the node tree, or the properties it offers, changed under the timeline. */
    fun sync() {
        if (builtFor == document.revision) return
        builtFor = document.revision

        val next = signatureOf()
        if (next == signature && timeline.groups.isNotEmpty()) return
        load()
    }

    /** Resets the editor state and builds it again from the file. */
    fun load() {
        suppressCommit = true
        timeline.groups.clear()

        val effect = document.effect
        timeline.workAreaEnd = effect.timeline.duration.coerceAtLeast(0.1f)

        val names = effect.walk().groupingBy { it.name }.eachCount()
        effect.walk().forEach { node ->
            val label = if ((names[node.name] ?: 0) > 1) "${node.name} (${node.id})" else node.name
            VfxAnimatables.forNode(node).forEach { animatable ->
                val property = timeline.addProperty(listOf(label), buildProperty(node, animatable))
                fill(property, effect.timeline.track(node.id, animatable.property))
            }
        }

        signature = signatureOf()
        builtFor = document.revision
        suppressCommit = false
        applyListing()
    }

    /** Lists the property of [nodeId] the author is editing, so its keys are one glance away. */
    fun focus(nodeId: String, property: VfxProperty) {
        val id = propertyId(nodeId, property)
        touched += nodeId
        pinned += id
        hidden -= id
        applyListing()
    }

    /**
     * While auto-keying, turns an edit of the effect into keys at the playhead: every channel of an
     * animatable property the edit changed, and that does not already hold that value there. The edit
     * itself stays, since the value it changed is only what the track falls back to.
     */
    fun recordEdit(before: VfxEffect, after: VfxEffect) {
        if (!timeline.isRecording) return
        val time = timeline.currentTime
        after.walk().forEach { node ->
            val old = before.node(node.id)?.takeIf { it != node } ?: return@forEach
            VfxAnimatables.forNode(node).forEach animatable@{ animatable ->
                val was = VfxAnimatables.of(old, animatable.property)?.read() ?: return@animatable
                val now = animatable.read()
                val property = propertyOf(node.id, animatable.property) ?: return@animatable
                val shown = FloatArray(property.curves.size) { channel ->
                    property.curves[channel].valueAt(time, was.getOrElse(channel) { 0f })
                }
                val changed = property.curves.indices.filter { channel ->
                    val value = now.getOrNull(channel) ?: return@filter false
                    value != was.getOrNull(channel) && abs(value - shown[channel]) > RecordEpsilon
                }
                if (changed.isEmpty()) return@animatable
                focus(node.id, animatable.property)
                timeline.recordKeys(property, changed.associateWith { now[it] }, shown)
            }
        }
    }

    /**
     * While auto-keying, keys [changes] (channel to value) of [property] of [nodeId] at the playhead;
     * [previous] is what the property showed before, for a channel that gets its first key.
     */
    fun record(nodeId: String, property: VfxProperty, changes: Map<Int, Float>, previous: FloatArray) {
        if (!timeline.isRecording) return
        val track = propertyOf(nodeId, property) ?: return
        focus(nodeId, property)
        timeline.recordKeys(track, changes, previous)
    }

    private fun propertyOf(nodeId: String, property: VfxProperty): AnimProperty<*>? {
        val id = propertyId(nodeId, property)
        return timeline.allProperties().firstOrNull { it.id == id }
    }

    /** Keeps the row of [nodeId] once it has been selected. */
    fun touch(nodeId: String) {
        if (touched.add(nodeId)) applyListing()
    }

    private fun hide(id: String) {
        pinned -= id
        hidden += id
        applyListing()
    }

    private fun hideNode(group: TrackGroup) {
        val properties = group.allProperties()
        val node = properties.firstOrNull()?.let(::nodeOf) ?: return
        touched -= node
        properties.forEach { property ->
            pinned -= property.id
            hidden += property.id
        }
        applyListing()
    }

    /** Decides which rows track list shows. */
    fun applyListing() {
        timeline.groups.forEach { group -> applyListing(group) }
    }

    private fun applyListing(group: TrackGroup) {
        group.children.forEach(::applyListing)
        group.properties.forEach { property ->
            val keyed = property.curves.any { it.keyframes.isNotEmpty() }
            property.isListed = property.id !in hidden && (keyed || property.id in pinned)
        }
        val node = group.properties.firstOrNull()?.let(::nodeOf)
        group.isListed = node != null && node in touched
    }

    /** Writes whatever the editor holds back into the file. */
    fun commit() {
        if (suppressCommit) return

        val tracks = timeline.allProperties().mapNotNull { property ->
            val node = nodeOf(property) ?: return@mapNotNull null
            val animatable = animatableOf(property) ?: return@mapNotNull null
            val curves = property.curves.mapIndexedNotNull { channel, curve ->
                if (curve.keyframes.isEmpty()) null
                else VfxTrackCurve(channel, curve.isVisible, curve.keyframes.map { key -> key.toStored(curve) })
            }
            if (curves.isEmpty()) null else VfxTrack(node, animatable, curves)
        }

        val spec = VfxTimelineSpec(
            duration = timeline.workAreaEnd,
            loop = document.effect.timeline.loop,
            tracks = tracks,
        )
        if (spec == document.effect.timeline) return

        suppressCommit = true
        document.edit(recorded = false) { it.copy(timeline = spec) }
        builtFor = document.revision
        suppressCommit = false
    }

    /** What the timeline rows depend on, which nodes there are, what they are called, what they offer. */
    private fun signatureOf(): List<Any> = document.effect.walk().map { node ->
        Triple(node.id, node.name, VfxAnimatables.forNode(node).map { it.property })
    }

    private fun buildProperty(node: VfxNodeSpec, animatable: VfxAnimatable): AnimProperty<*> {
        val id = propertyId(node.id, animatable.property)
        val title = animatable.titleKey.lang
        val name = animatable.ownerTitleKey?.let { "${it.lang} · $title" } ?: title
        val defaults = animatable.read()

        return when (animatable.kind) {
            VfxAnimatableKind.TOGGLE -> AnimProperty(id, name, TogglePropertyType, defaults.getOrElse(0) { 1f })
            VfxAnimatableKind.FLOAT -> AnimProperty(id, name, FloatPropertyType(name), defaults.getOrElse(0) { 0f })
            VfxAnimatableKind.VEC3 -> AnimProperty(id, name, TranslationPropertyType(), vector(defaults))
            VfxAnimatableKind.ROTATION -> AnimProperty(
                id,
                name,
                RotationPropertyType(RotationMode.EULER),
                vector(defaults),
            )

            VfxAnimatableKind.COLOR -> AnimProperty(id, name, ColorChannelsPropertyType, defaults.copyOf(4))
        }
    }

    private fun vector(values: FloatArray) =
        Vec3f(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f })

    private fun fill(property: AnimProperty<*>, track: VfxTrack?) {
        if (track == null) return

        track.curves.forEach { stored ->
            val curve = property.curves.getOrNull(stored.channel) ?: return@forEach
            curve.isVisible = stored.visible
            stored.keys.forEach { key -> curve.keyframes.add(key.toEditor()) }
            curve.sort()
        }
    }

    private fun propertyId(node: String, property: VfxProperty) = "$node|$property"

    /** The node a timeline row belongs to, as packed into its id. */
    private fun nodeOf(property: AnimProperty<*>): String? = property.id.split('|', limit = 2).getOrNull(0)

    private fun animatableOf(property: AnimProperty<*>): VfxProperty? =
        property.id.split('|', limit = 2).getOrNull(1)?.let(::VfxProperty)
}

/** On or off, held between keys; the editor draws it as a step curve and never interpolates it. */
private object TogglePropertyType : PropertyType<Float> {
    override val id = "vfx_toggle"

    override val channels = listOf(
        ChannelSpec(
            name = "On",
            color = ChannelColors.SCALAR,
            sampling = ChannelSampling.DISCRETE,
            valueOptions = listOf(
                ChannelValueOption(0f, "hollowengine.gui.vfx.off"),
                ChannelValueOption(1f, "hollowengine.gui.vfx.on"),
            ),
        ),
    )

    override fun decompose(value: Float, into: FloatArray) {
        into[0] = value
    }

    override fun compose(values: FloatArray): Float = if (values[0] >= 0.5f) 1f else 0f
}

/** Red, green, blue and alpha as four curves, the way a color is keyed. */
private object ColorChannelsPropertyType : PropertyType<FloatArray> {
    override val id = "vfx_color"

    override val channels = listOf(
        ChannelSpec(name = "R", color = Color("E06C75")),
        ChannelSpec(name = "G", color = Color("98C379")),
        ChannelSpec(name = "B", color = Color("61AFEF")),
        ChannelSpec(name = "A", color = Color("ABB2BF")),
    )

    override fun decompose(value: FloatArray, into: FloatArray) {
        for (channel in 0 until 4) into[channel] = value.getOrElse(channel) { 1f }
    }

    override fun compose(values: FloatArray): FloatArray = values.copyOf(4)
}

internal fun VfxKey.toEditor(): Keyframe = Keyframe(
    time = time,
    value = value,
    interpolation = when (interpolation) {
        VfxInterpolation.CONSTANT -> KeyInterpolation.CONSTANT
        VfxInterpolation.LINEAR -> KeyInterpolation.LINEAR
        VfxInterpolation.BEZIER -> KeyInterpolation.BEZIER
    },
    handleMode = HandleMode.FREE,
    incoming = KeyTangent(inTime, inValue),
    outgoing = KeyTangent(outTime, outValue),
)

/** A key as the file stores it. */
internal fun Keyframe.toStored(curve: ChannelCurve): VfxKey {
    val tangents = curve.effectiveTangents(this)
    return VfxKey(
        time = time,
        value = value,
        interpolation = when (interpolation) {
            KeyInterpolation.CONSTANT -> VfxInterpolation.CONSTANT
            KeyInterpolation.LINEAR -> VfxInterpolation.LINEAR
            KeyInterpolation.BEZIER -> VfxInterpolation.BEZIER
        },
        inTime = tangents.incoming.time,
        inValue = tangents.incoming.value,
        outTime = tangents.outgoing.time,
        outValue = tangents.outgoing.value,
    )
}

/** How far apart an edited value and what the track already holds must be to be worth a key. */
private const val RecordEpsilon = 1.0e-5f
