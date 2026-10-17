package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackLanes
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackParts
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
) : TrackListing {
    /** Records into the file's history, so Ctrl+Z walks back through effect and tracks in the order they were edited. */
    val timeline = TimelineController(document.history)

    /** Properties the author asked for, as `node|property`. */
    private val pinned = LinkedHashSet<String>()

    /** Properties the author took off the list; they stay off even with keys. */
    private val hidden = HashSet<String>()

    /** Nodes that were selected at some point, so their group row stays. */
    private val touched = LinkedHashSet<String>()

    /** The node selected in the editor now, or the lane picked in its scene in place of one. */
    private var selection: String? = null
    private var selectedLane: SegmentLane? = null
    private var onlySelectedState by mutableStateOf(false)

    override var onlySelected: Boolean
        get() = onlySelectedState
        set(value) {
            onlySelectedState = value
            applyListing()
        }

    /*
     * Values of keyed properties the author changed without recording keys, as `node|property`: the preview shows
     * them over the tracks until the playhead moves or the timeline plays, as in any animation editor.
     */
    private val heldValues = mutableStateMapOf<String, FloatArray>()
    private var heldAt by mutableStateOf(Float.NaN)

    private val holding: Boolean get() = !timeline.isPlaying && abs(timeline.currentTime - heldAt) <= HoldEpsilon

    private val heldEdits = HeldEdits(
        document.history,
        read = { heldValues.mapValues { it.value.toList() } },
        write = { held ->
            heldValues.clear()
            held.forEach { (id, values) -> heldValues[id] = values.toFloatArray() }
            preview.repose()
        },
        isCurrent = { holding },
    )

    /** The events and sections of the effect's timeline, on lanes of their own. */
    internal val playback = PlaybackLanes(
        read = { document.effect.timeline.let { PlaybackParts(it.events, it.sections) } },
        duration = { document.effect.timeline.duration },
        edit = { label, mergeKey, change ->
            document.edit(mergeKey = mergeKey, label = UndoLabel(label)) { effect ->
                val parts = change(PlaybackParts(effect.timeline.events, effect.timeline.sections))
                effect.copy(timeline = effect.timeline.copy(events = parts.events, sections = parts.sections))
            }
        },
    )

    private var suppressCommit = false
    private var builtFor: Int = -1
    private var signature: List<Any> = emptyList()

    init {
        timeline.onChanged = {
            commit()
            applyListing()
        }
        timeline.onTimeChanged = {
            heldEdits.forget()
            heldValues.clear()
            preview.requestSeek(timeline.currentTime)
        }
        timeline.onHideProperty = { property -> hide(property.id) }
        timeline.onHideGroup = { group -> hideNode(group) }
        timeline.onHideLane = { lane -> lane.isListed = false }
        timeline.lanes += playback.lanes
        playback.refresh()
    }

    /** Rebuilds the tracks when the node tree, or the properties it offers, changed under the timeline. */
    fun sync() {
        if (builtFor == document.revision) return
        builtFor = document.revision
        playback.refresh()

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
     * Turns an edit of the effect into the timeline. While auto-keying, every channel of an animatable property the
     * edit changed, and that does not already hold that value there, is keyed at the playhead; otherwise a property
     * with keys holds the edit until the playhead moves.
     */
    fun recordEdit(before: VfxEffect, after: VfxEffect) {
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
                if (!timeline.isRecording) {
                    if (property.isKeyed()) holdChanges(
                        node.id,
                        animatable.property,
                        now.indices.filter { now[it] != was.getOrNull(it) }.associateWith { now[it] },
                        shown,
                        withEdit = true
                    )
                    return@animatable
                }
                val changed = property.curves.indices.filter { channel ->
                    val value = now.getOrNull(channel) ?: return@filter false
                    value != was.getOrNull(channel) && abs(value - shown[channel]) > RecordEpsilon
                }
                heldValues.remove(property.id)
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
        heldValues.remove(track.id)
        focus(nodeId, property)
        timeline.recordKeys(track, changes, previous)
    }

    /** What is held for [property] of [nodeId] at the playhead, or null. */
    fun held(nodeId: String, property: VfxProperty): FloatArray? =
        if (holding) heldValues[propertyId(nodeId, property)] else null

    /**
     * Holds [changes] (channel to value) of [property] of [nodeId] over the track until the playhead moves; [shown] is
     * what the property shows now, which the channels the edit left alone keep. [withEdit] when an edit of the file
     * comes with it, which one undo then takes back along with the hold.
     */
    fun holdChanges(
        nodeId: String,
        property: VfxProperty,
        changes: Map<Int, Float>,
        shown: FloatArray,
        withEdit: Boolean = false,
    ) {
        if (changes.isEmpty()) return
        if (!holding) heldValues.clear()
        heldAt = timeline.currentTime
        val id = propertyId(nodeId, property)
        val values = (heldValues[id] ?: shown).copyOf()
        changes.forEach { (channel, value) -> if (channel in values.indices) values[channel] = value }
        val hold = { heldValues[id] = values }
        if (withEdit) heldEdits.attach(hold) else heldEdits.record(hold)
        preview.repose()
    }

    /** Lets go of what is held for [properties] of [nodeId], so the tracks show again. */
    fun release(nodeId: String, properties: Collection<VfxProperty>) {
        if (properties.none { heldValues.remove(propertyId(nodeId, it)) != null }) return
        preview.repose()
    }

    /**
     * Keys [parts] of [nodeId] at the playhead with what the preview shows, held edits included: one part of its
     * placement, or the placement and every property that already has keys.
     */
    fun insertKeys(nodeId: String, parts: KeyParts) {
        val node = document.effect.node(nodeId) ?: return
        val runtime = preview.instance?.node(nodeId)
        val placement = when (parts) {
            KeyParts.LOCATION -> listOf(VfxProperty.POSITION)
            KeyParts.ROTATION -> listOf(VfxProperty.ROTATION)
            KeyParts.SCALE -> listOf(VfxProperty.SCALE)
            KeyParts.ALL -> listOf(VfxProperty.POSITION, VfxProperty.ROTATION, VfxProperty.SCALE)
        }
        val keyed = if (parts != KeyParts.ALL) emptyList()
        else VfxAnimatables.forNode(node).map { it.property }.filter { propertyOf(nodeId, it).isKeyed() }
        val properties = (placement + keyed).distinct()
            .mapNotNull { property -> propertyOf(nodeId, property)?.let { property to it } }
        if (properties.isEmpty()) return
        timeline.beginHistoryTransaction(TimelineEdits.ADD_KEY)
        properties.forEach { (property, track) ->
            focus(nodeId, property)
            timeline.addKeyframes(track, timeline.currentTime, runtime?.let { currentValues(it, property) })
        }
        timeline.commitHistoryTransaction()
    }

    private fun AnimProperty<*>?.isKeyed(): Boolean = this?.curves?.any { it.keyframes.isNotEmpty() } == true

    private fun propertyOf(nodeId: String, property: VfxProperty): AnimProperty<*>? {
        val id = propertyId(nodeId, property)
        return timeline.allProperties().firstOrNull { it.id == id }
    }

    /** Opens the rows of [nodeId], the node selected in the editor now, and keeps them once it was; null for none. */
    fun select(nodeId: String?) {
        selection = nodeId
        selectedLane = null
        if (nodeId?.let(touched::add) == true || onlySelectedState) applyListing()
    }

    /** Opens [lane], picked in the scene in place of a node, as what is selected now. */
    fun selectLane(lane: SegmentLane) {
        selection = null
        selectedLane = lane
        lane.isListed = true
        applyListing()
    }

    override fun showAll() {
        hidden.clear()
        timeline.lanes.forEach { it.isListed = true }
        onlySelected = false
    }

    override fun hideUnselected() {
        timeline.groups.filter { group -> group.properties.firstOrNull()?.let(::nodeOf) != selection }.forEach(::close)
        timeline.lanes.filter { it !== selectedLane }.forEach { it.isListed = false }
        applyListing()
    }

    private fun hide(id: String) {
        pinned -= id
        hidden += id
        applyListing()
    }

    private fun hideNode(group: TrackGroup) {
        close(group)
        applyListing()
    }

    /** Takes the rows of the node of [group] off the list, keys and all, until the author selects it again. */
    private fun close(group: TrackGroup) {
        val properties = group.allProperties()
        val node = properties.firstOrNull()?.let(::nodeOf) ?: return
        touched -= node
        properties.forEach { property ->
            pinned -= property.id
            hidden += property.id
        }
    }

    /** Decides which rows track list shows. */
    fun applyListing() {
        timeline.groups.forEach { group -> applyListing(group) }
        timeline.lanes.forEach { lane -> lane.isFiltered = onlySelectedState && lane !== selectedLane }
    }

    private fun applyListing(group: TrackGroup) {
        group.children.forEach(::applyListing)
        val node = group.properties.firstOrNull()?.let(::nodeOf)
        val shown = !onlySelectedState || node == selection
        group.properties.forEach { property ->
            val keyed = property.curves.any { it.keyframes.isNotEmpty() }
            property.isListed = shown && property.id !in hidden && (keyed || property.id in pinned)
        }
        group.isListed = shown && node != null && node in touched
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

        val spec = document.effect.timeline.copy(duration = timeline.workAreaEnd, tracks = tracks)
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
        val name = animatable.ownerTitleKey?.let { "${it.lang} | $title" } ?: title
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
internal object TogglePropertyType : PropertyType<Float> {
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

/** How far the playhead may sit from where an edit was held for it to still show. */
private const val HoldEpsilon = 1.0e-4f
