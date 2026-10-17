package ru.hollowhorizon.hollowengine.client.ui.ide.files.montage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeMontageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.TogglePropertyType
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.toEditor
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.toStored
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackLanes
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackParts
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackPick
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import ru.hollowhorizon.hollowengine.common.vfx.VfxKey
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve
import kotlin.math.abs

/** What the author picked on the montage's timeline. */
internal sealed interface MontagePick {
    data class Clip(val id: String) : MontagePick
    data class Playback(val pick: PlaybackPick) : MontagePick
    data class Attachment(val bone: String?, val id: String) : MontagePick
}

internal class MontageTimelineSession(
    private val document: HollowIdeMontageDocument,
    private val rigs: MontageRigEditing,
) : TrackListing, MontageRigTimeline {
    val timeline = TimelineController(document.history)

    /** The clips of the preview model, by name, with how long each is. */
    var clips: () -> Map<String, Float> = { emptyMap() }

    /** Told when the author picks a block or a marker, or lets go of it. */
    var onPick: ((MontagePick?) -> Unit)? = null

    val clipLane = SegmentLane("clips", montageText("lane_clips"), arranged = true, exclusive = true)

    /** The events and sections of the montage, on lanes of their own. */
    val playback = PlaybackLanes(
        read = { PlaybackParts(montage.events, montage.sections) },
        duration = { montage.duration },
        edit = { label, mergeKey, change ->
            document.edit(mergeKey = mergeKey, label = UndoLabel(label)) { montage ->
                change(PlaybackParts(montage.events, montage.sections)).let {
                    montage.copy(
                        events = it.events,
                        sections = it.sections
                    )
                }
            }
        },
    )
    private val windows = HashMap<Pair<String?, String>, SegmentLane>()

    /** Fields the author asked to animate, as property ids; with keys or without. */
    private val pinned = LinkedHashSet<String>()
    private val hidden = HashSet<String>()

    /** What the author picked in the scene at some point, as group keys, so its rows stay until closed. */
    private val touched = LinkedHashSet<String>()

    /** What is selected in the scene now, as group keys, or the key of a lane picked there. */
    private var selection: Set<String> = emptySet()
    private var onlySelectedState by mutableStateOf(false)

    override var onlySelected: Boolean
        get() = onlySelectedState
        set(value) {
            onlySelectedState = value
            applyListing()
        }

    /*
     * What the author changed in the preview without recording keys: shown over the tracks until the playhead moves
     * or the timeline plays, when the tracks have the say again, as in any animation editor. Keys put it in for good.
     */
    private val heldBones = mutableStateMapOf<String, RigPose>()
    private val heldVisibility = mutableStateMapOf<String, Boolean>()
    private val heldFields = mutableStateMapOf<Pair<String?, String>, Pair<RigAttachmentSpec, Set<String>>>()
    private var heldAt by mutableStateOf(Float.NaN)

    private val holding: Boolean get() = !timeline.isPlaying && abs(timeline.currentTime - heldAt) <= EPSILON

    private val heldEdits = HeldEdits(
        document.history,
        read = { Held(heldBones.toMap(), heldVisibility.toMap(), heldFields.toMap()) },
        write = { held ->
            heldBones.clear()
            heldBones.putAll(held.bones)
            heldVisibility.clear()
            heldVisibility.putAll(held.visibility)
            heldFields.clear()
            heldFields.putAll(held.fields)
        },
        isCurrent = { holding },
    )

    private data class Held(
        val bones: Map<String, RigPose>,
        val visibility: Map<String, Boolean>,
        val fields: Map<Pair<String?, String>, Pair<RigAttachmentSpec, Set<String>>>,
    )

    private var suppressCommit = false
    private var builtFor = -1
    private var signature: List<Any> = emptyList()

    private val montage: Montage get() = document.montage

    override val time: Float get() = timeline.currentTime

    override val isRecording: Boolean get() = timeline.isRecording

    init {
        rigs.timeline = this
        timeline.onChanged = {
            commit()
            applyListing()
        }
        timeline.onTimeChanged = ::letGo
        timeline.onHideProperty = { property ->
            pinned -= property.id
            hidden += property.id
            applyListing()
        }
        timeline.onHideGroup = { group ->
            close(group)
            applyListing()
        }
        timeline.onHideLane = { lane -> lane.isListed = false }

        clipLane.isListed = montage.clips.isNotEmpty()
        clipLane.onEdit = ::clipMoved
        clipLane.onSelect = { segment -> onPick?.invoke(segment?.let { MontagePick.Clip(it.id) }) }
        clipLane.menu = ::clipMenu
        playback.onPick = { pick -> onPick?.invoke(pick?.let(MontagePick::Playback)) }
        timeline.listing = this
        timeline.lanes += clipLane
        timeline.lanes += playback.lanes
        timeline.advance = playback::advance
    }

    /** The group of each attachment and bone, by the key [touched] keeps it under. */
    private val groupKeys = HashMap<TrackGroup, String>()

    /** Builds the tracks again when what they stand for changed under the timeline; the blocks always follow. */
    fun sync() {
        if (builtFor == document.revision) return
        builtFor = document.revision
        timeline.workAreaEnd = montage.duration.coerceAtLeast(MIN_DURATION)
        if (signatureOf() != signature || timeline.groups.isEmpty()) load() else refreshSegments()
    }

    fun load() {
        val picked = timeline.selectedKeyframes.mapNotNull { key ->
            val property = timeline.propertyOf(key) ?: return@mapNotNull null
            Triple(property.id, property.curves.indexOfFirst { curve -> curve.keyframes.any { it === key } }, key.time)
        }
        suppressCommit = true
        timeline.groups.clear()
        windows.clear()
        groupKeys.clear()

        rigs.rig.allAttachments().forEach { (bone, spec) ->
            val path = listOf(attachmentLabel(bone, spec))
            val group = timeline.group(path)
            groupKeys[group] = attachmentKey(bone, spec.id)
            if (rigs.owns(bone, spec.id)) group.lanes += windowLane(bone, spec.id)
            RigFields.animatable(spec).forEach { field ->
                val property = timeline.addProperty(path, buildProperty(bone, spec.id, field))
                fill(
                    property,
                    montage.tracks.firstOrNull { it.bone == bone && it.attachment == spec.id && it.property == field.name }?.curves
                )
            }
        }

        bonesShown().forEach { bone -> addBone(bone) }

        if (montage.clips.isNotEmpty()) {
            val path = listOf(montageText("group_weights"))
            montage.clips.forEach { clip ->
                val property = timeline.addProperty(
                    path, AnimProperty(
                        weightId(clip.id), clip.animation, FloatPropertyType(clip.animation, ChannelBounds(0f, 1f)), 1f
                    )
                )
                fill(property, listOf(VfxTrackCurve(0, true, clip.weight.map { it.copy(time = it.time + clip.start) })))
            }
        }

        signature = signatureOf()
        builtFor = document.revision
        suppressCommit = false
        refreshSegments()
        applyListing()

        val again = picked.mapNotNull { (id, channel, time) ->
            propertyOf(id)?.curves?.getOrNull(channel)?.keyAt(time)
        }
        timeline.selectedKeyframes.clear()
        timeline.selectedKeyframes.addAll(again)
    }

    private fun addBone(bone: String) {
        val path = listOf("${montageText("bone")} ($bone)")
        val group = timeline.group(path)
        groupKeys[group] = boneKey(bone)
        MontageBoneProperty.entries.forEach { property ->
            val rest = property.rest
            val id = boneId(bone, property)
            val name = montageText("bone_${property.name.lowercase()}")
            val track = timeline.addProperty(
                path,
                when (property) {
                    MontageBoneProperty.VISIBLE -> AnimProperty(id, name, TogglePropertyType, rest)
                    MontageBoneProperty.ROTATION -> AnimProperty(
                        id, name, RotationPropertyType(RotationMode.EULER), Vec3f(rest, rest, rest)
                    )

                    else -> AnimProperty(id, name, TranslationPropertyType(), Vec3f(rest, rest, rest))
                },
            )
            fill(track, montage.bones.firstOrNull { it.bone == bone && it.property == property }?.curves)
        }
    }

    /** Whether the montage draws [bone] at the playhead. */
    fun isBoneShown(bone: String): Boolean {
        if (holding) heldVisibility[bone]?.let { return it }
        val track = propertyOf(boneId(bone, MontageBoneProperty.VISIBLE)) ?: return true
        @Suppress("UNCHECKED_CAST") return (track as AnimProperty<Float>).valueAt(timeline.currentTime) >= 0.5f
    }

    /** Keys whether [bone] is drawn at the playhead. */
    fun keyBoneShown(bone: String, shown: Boolean) {
        if (!timeline.isRecording) return
        touchBone(bone)
        val track = propertyOf(boneId(bone, MontageBoneProperty.VISIBLE)) ?: return
        heldVisibility.remove(bone)
        val was = if (isBoneShown(bone)) 1f else 0f
        val now = if (shown) 1f else 0f
        if (was == now) return
        pinned += track.id
        hidden -= track.id
        timeline.recordKeys(track, mapOf(0 to now), floatArrayOf(was))
        commit()
        applyListing()
    }

    /** Opens [lane], picked in the scene in place of a bone, as what is selected now. */
    fun selectLane(lane: SegmentLane) {
        selection = setOf(laneKey(lane))
        lane.isListed = true
        applyListing()
    }

    /** Opens the rows of what is selected in the scene: [bone], or the root, and the attachments [attachments] on it. */
    fun select(bone: String?, attachments: Collection<String>) {
        selection = attachments.mapTo(listOfNotNull(bone?.let(::boneKey)).toMutableSet()) { attachmentKey(bone, it) }
        bone?.let(::touchBone)
        attachments.forEach { touchAttachment(bone, it) }
        if (onlySelectedState) applyListing()
    }

    override fun showAll() {
        hidden.clear()
        timeline.lanes.forEach { it.isListed = true }
        onlySelected = false
    }

    override fun hideUnselected() {
        timeline.groups.filter { group -> groupKeys[group]?.let { it !in selection } == true }.forEach(::close)
        timeline.lanes.filter { laneKey(it) !in selection }.forEach { it.isListed = false }
        applyListing()
    }

    /** Takes the rows of [group] off the list, keys and all, until the author picks it again. */
    private fun close(group: TrackGroup) {
        groupKeys[group]?.let(touched::remove)
        group.allProperties().forEach { property ->
            pinned -= property.id
            hidden += property.id
        }
    }

    /** Keeps the rows of the attachment [id] on [bone] on the timeline, now that the author picked it. */
    fun touchAttachment(bone: String?, id: String) {
        touch(attachmentKey(bone, id))
    }

    /** Keeps the rows of [bone] on the timeline, now that the author picked it, building them the first time. */
    fun touchBone(bone: String) {
        if (groupKeys.values.none { it == boneKey(bone) }) addBone(bone)
        touch(boneKey(bone))
    }

    /** Keeps the group under [key] on the timeline, and brings back the tracks of it the author had closed. */
    private fun touch(key: String) {
        val group = groupKeys.entries.firstOrNull { it.value == key }?.key
        val reopened = group?.allProperties()?.any { hidden.remove(it.id) } == true
        if (touched.add(key) || reopened) applyListing()
    }

    /** Whether [field] of the attachment [id] on [bone] has keys. */
    override fun isAnimated(bone: String?, id: String, field: String): Boolean = propertyOf(fieldId(bone, id, field)).isKeyed()

    /**
     * Starts animating [field], with a key at the playhead holding what it is now, so it is written by the
     * timeline from then on; or, when it is animated, takes its keys away and lets the authored value back.
     */
    fun toggle(bone: String?, id: String, field: String) = toggle(fieldId(bone, id, field))

    /** Keys [value] for [field] at the playhead, in the channels it changes; [shown] is what it held before. */
    override fun keyField(bone: String?, id: String, field: String, value: FloatArray, shown: FloatArray) {
        if (!timeline.isRecording) return
        touchAttachment(bone, id)
        val track = propertyOf(fieldId(bone, id, field)) ?: return
        releaseField(bone, id, field)
        val changes = value.indices.filter { abs(value[it] - shown.getOrElse(it) { 0f }) > KEY_EPSILON }
            .associateWith { value[it] }
        if (changes.isEmpty()) return
        pinned += track.id
        hidden -= track.id
        timeline.recordKeys(track, changes, shown)
        commit()
        applyListing()
    }

    fun isBoneAnimated(bone: String, property: MontageBoneProperty): Boolean =
        propertyOf(boneId(bone, property)).isKeyed()

    fun toggleBone(bone: String, property: MontageBoneProperty) {
        touchBone(bone)
        toggle(boneId(bone, property))
    }

    /** Where the montage has [property] of [bone] at the playhead: what is held there, what its keys say, or the rest. */
    fun boneValue(bone: String, property: MontageBoneProperty): Vec3f {
        val held = heldBones[bone]?.takeIf { holding }
        if (held != null) when (property) {
            MontageBoneProperty.POSITION -> return held.position
            MontageBoneProperty.ROTATION -> return held.rotation.eulerDegreesXyz()
            MontageBoneProperty.SCALE -> return held.scale
            else -> Unit
        }
        return keyedValue(bone, property)
    }

    /** What the keys of [property] of [bone] say at the playhead, or the rest, whatever is held over them. */
    private fun keyedValue(bone: String, property: MontageBoneProperty): Vec3f {
        val rest = Vec3f(property.rest, property.rest, property.rest)
        val track = propertyOf(boneId(bone, property)) ?: return rest
        @Suppress("UNCHECKED_CAST") return (track as AnimProperty<Vec3f>).valueAt(timeline.currentTime)
    }

    override fun bonePose(bone: String): RigPose = RigPose(
        boneValue(bone, MontageBoneProperty.POSITION),
        eulerRotationXyz(boneValue(bone, MontageBoneProperty.ROTATION)),
        boneValue(bone, MontageBoneProperty.SCALE),
    )

    /** An edit of [property] of [bone]: keyed while the timeline records, held until the playhead moves otherwise. */
    fun editBone(bone: String, property: MontageBoneProperty, value: Vec3f) {
        val pose = bonePose(bone)
        val edited = when (property) {
            MontageBoneProperty.POSITION -> pose.copy(position = value)
            MontageBoneProperty.ROTATION -> pose.copy(rotation = eulerRotationXyz(value))
            MontageBoneProperty.SCALE -> pose.copy(scale = value)
            else -> pose
        }
        if (timeline.isRecording) keyPose(bone, edited) else holdBone(bone, edited)
    }

    /**
     * Keys [pose] of [bone] at the playhead in the parts it differs from the keys there, what was held of the bone
     * included, and lets go of the hold, so the keys show.
     */
    override fun keyPose(bone: String, pose: RigPose) {
        keyBone(bone, MontageBoneProperty.POSITION, pose.position)
        val turned = eulerRotationXyz(keyedValue(bone, MontageBoneProperty.ROTATION))
        if (abs(turned.dot(pose.rotation)) < SAME_TURN) {
            keyBone(bone, MontageBoneProperty.ROTATION, pose.rotation.eulerDegreesXyz())
        }
        keyBone(bone, MontageBoneProperty.SCALE, pose.scale)
        heldBones.remove(bone)
    }

    /** Shows or hides [bone]: keyed while the timeline records, held until the playhead moves otherwise. */
    fun editBoneShown(bone: String, shown: Boolean) {
        if (timeline.isRecording) return keyBoneShown(bone, shown)
        touchBone(bone)
        beginHold()
        heldEdits.record { heldVisibility[bone] = shown }
    }

    /** Holds [pose] of [bone] at the playhead, as the bone gizmo does while keys are not recorded. */
    override fun holdBone(bone: String, pose: RigPose) {
        touchBone(bone)
        beginHold()
        heldEdits.record { heldBones[bone] = pose }
    }

    /** Holds [names] of the attachment [spec] on [bone] at the values [spec] has, over what the tracks write. */
    override fun holdFields(bone: String?, spec: RigAttachmentSpec, names: Collection<String>) {
        touchAttachment(bone, spec.id)
        beginHold()
        val key = bone to spec.id
        heldEdits.record { heldFields[key] = spec to (heldFields[key]?.second.orEmpty() + names) }
    }

    /** [rig] with the attachment fields held at the playhead written over it. */
    override fun withHeldFields(rig: ModelRig): ModelRig {
        if (!holding) return rig
        return heldFields.entries.fold(rig) { held, (key, value) ->
            val (bone, id) = key
            val (spec, names) = value
            val current = held.holder(bone).attachment(id) ?: return@fold held
            held.withHolder(bone, held.holder(bone).withAttachment(RigFields.copy(spec, current, names) ?: current))
        }
    }

    /** [montage] with the bones held at the playhead in place of what their tracks say there, as the preview plays it. */
    override fun withHeldBones(montage: Montage): Montage {
        if (!holding || heldBones.isEmpty() && heldVisibility.isEmpty()) return montage
        val held = heldBones.flatMap { (bone, pose) ->
            listOf(
                fixedTrack(bone, MontageBoneProperty.POSITION, pose.position),
                fixedTrack(bone, MontageBoneProperty.ROTATION, pose.rotation.eulerDegreesXyz()),
                fixedTrack(bone, MontageBoneProperty.SCALE, pose.scale),
            )
        } + heldVisibility.map { (bone, shown) ->
            val flag = if (shown) 1f else 0f
            fixedTrack(bone, MontageBoneProperty.VISIBLE, Vec3f(flag, flag, flag))
        }
        val replaced = held.mapTo(HashSet()) { it.bone to it.property }
        return montage.copy(bones = montage.bones.filterNot { (it.bone to it.property) in replaced } + held)
    }

    private fun fixedTrack(bone: String, property: MontageBoneProperty, value: Vec3f) = MontageBoneTrack(
        bone,
        property,
        listOf(value.x, value.y, value.z).mapIndexed { channel, axis ->
            VfxTrackCurve(
                channel,
                true,
                listOf(VfxKey(0f, axis))
            )
        },
    )

    /** Lets go of what is held for [field] of the attachment [id] on [bone], now that it is keyed. */
    private fun releaseField(bone: String?, id: String, field: String) {
        val key = bone to id
        val (spec, names) = heldFields[key] ?: return
        val rest = names - field
        if (rest.isEmpty()) heldFields.remove(key) else heldFields[key] = spec to rest
    }

    /** Lets go of everything held, as moving the playhead does: the tracks have the say again. */
    private fun letGo() {
        heldEdits.forget()
        heldBones.clear()
        heldVisibility.clear()
        heldFields.clear()
        heldAt = Float.NaN
    }

    /** Starts holding at the playhead, letting go of what was held at another time. */
    private fun beginHold() {
        if (!holding) {
            heldBones.clear()
            heldVisibility.clear()
            heldFields.clear()
        }
        heldAt = timeline.currentTime
    }

    /** Keys [value] for [property] of [bone] at the playhead, in the channels it changes. */
    fun keyBone(bone: String, property: MontageBoneProperty, value: Vec3f) {
        if (!timeline.isRecording) return
        touchBone(bone)
        val track = propertyOf(boneId(bone, property)) ?: return
        val now = keyedValue(bone, property)
        val before = floatArrayOf(now.x, now.y, now.z)
        val after = floatArrayOf(value.x, value.y, value.z)
        val changes = (0..2).filter { abs(after[it] - before[it]) > KEY_EPSILON }.associateWith { after[it] }
        if (changes.isEmpty()) return
        pinned += track.id
        hidden -= track.id
        timeline.recordKeys(track, changes, before)
        commit()
        applyListing()
    }

    /**
     * Puts a key at the playhead on [parts] of what is picked, holding what it shows there, held edits included: on
     * an attachment, where it is placed and, for everything, its animated fields; on a bone, its position, rotation
     * and scale. A pose blocked out by hand is kept that way, one key per pose.
     */
    fun keyPicked(bone: String?, part: String?, parts: KeyParts = KeyParts.ALL) {
        val spec = part?.let { rigs.rig.holder(bone).attachment(it) }
        val keys: List<Pair<AnimProperty<*>, FloatArray?>> = when {
            spec != null -> {
                touchAttachment(bone, spec.id)
                RigFields.animatable(spec)
                    .filter { field -> parts.covers(field.name, keyed = isAnimated(bone, spec.id, field.name)) }
                    .mapNotNull { field ->
                        propertyOf(fieldId(bone, spec.id, field.name))?.let {
                            it to RigFields.read(
                                spec,
                                field.name
                            )
                        }
                    }
            }

            bone != null -> {
                touchBone(bone)
                PoseParts.filter(parts::covers).mapNotNull { property ->
                    val value = boneValue(bone, property)
                    propertyOf(boneId(bone, property))?.let { it to floatArrayOf(value.x, value.y, value.z) }
                }
            }

            else -> emptyList()
        }
        if (keys.isEmpty()) return
        document.beginGesture()
        keys.forEach { (property, values) ->
            pinned += property.id
            hidden -= property.id
            timeline.addKeyframes(property, timeline.currentTime, values)
        }
        document.endGesture()
        commit()
        applyListing()
    }

    fun isWeightAnimated(clip: String): Boolean = propertyOf(weightId(clip)).isKeyed()

    fun toggleWeight(clip: String) = toggle(weightId(clip))

    private fun toggle(id: String) {
        val property = propertyOf(id) ?: return
        if (property.isKeyed()) {
            timeline.clearSelection()
            timeline.edit(TimelineEdits.DELETE_KEYS) { property.curves.forEach { it.keyframes.clear() } }
            pinned -= property.id
        } else {
            pinned += property.id
            hidden -= property.id
            timeline.addKeyframes(property, timeline.currentTime)
        }
        commit()
        applyListing()
    }

    private fun applyListing() {
        timeline.groups.forEach(::applyListing)
        timeline.lanes.forEach { lane -> lane.isFiltered = onlySelectedState && laneKey(lane) !in selection }
    }

    private fun applyListing(group: TrackGroup) {
        group.children.forEach(::applyListing)
        val key = groupKeys[group]
        val shown = key == null || !onlySelectedState || key in selection
        group.properties.forEach { property ->
            property.isListed = shown && property.id !in hidden && (property.isKeyed() || property.id in pinned)
        }
        group.isListed = shown && key != null && key in touched
        group.lanes.forEach { it.isListed = group.isListed }
    }

    /** Writes the keys the timeline holds back into the montage, with its length. */
    fun commit() {
        if (suppressCommit) return
        val properties = timeline.allProperties()
        val tracks = properties.mapNotNull { property ->
            val (bone, id, field) = parseField(property.id) ?: return@mapNotNull null
            property.stored()?.let { MontageTrack(bone, id, field, it) }
        }
        val bones = properties.mapNotNull { property ->
            val (bone, kind) = parseBone(property.id) ?: return@mapNotNull null
            property.stored()?.let { MontageBoneTrack(bone, kind, it) }
        }
        val weights = properties.mapNotNull { property ->
            val clip = property.id.takeIf { it.startsWith(WEIGHT) }?.removePrefix(WEIGHT) ?: return@mapNotNull null
            clip to (property.stored()?.firstOrNull()?.keys ?: emptyList())
        }.toMap()
        val clips = montage.clips.map { clip ->
            val keys = weights[clip.id] ?: return@map clip
            clip.copy(weight = keys.map { it.copy(time = it.time - clip.start) })
        }
        val duration = timeline.workAreaEnd
        if (tracks == montage.tracks && bones == montage.bones && clips == montage.clips && duration == montage.duration) return

        suppressCommit = true
        document.edit(recorded = false) { it.copy(tracks = tracks, bones = bones, clips = clips, duration = duration) }
        builtFor = document.revision
        suppressCommit = false
    }

    /** Puts the blocks and markers where the montage has them, keeping the one the author picked picked. */
    private fun refreshSegments() {
        val picked = timeline.selectedSegment?.id
        clipLane.segments.clear()
        montage.clips.forEachIndexed { index, clip ->
            clipLane.segments += TimelineSegment(
                id = clip.id, start = clip.start, length = clip.length, label = clip.animation,
                color = ClipColors[index % ClipColors.size], fadeIn = clip.fadeIn, fadeOut = clip.fadeOut, fades = true,
                row = clip.row,
            )
        }
        playback.refresh()
        windows.forEach { (key, lane) ->
            val attachment =
                montage.attachments.firstOrNull { it.bone == key.first && it.spec.id == key.second } ?: return@forEach
            lane.segments.clear()
            attachment.windows.forEachIndexed { index, window ->
                val end = window.end ?: montage.duration
                lane.segments += TimelineSegment(
                    id = "$INDEXED$index", start = window.start, length = (end - window.start).coerceAtLeast(0f),
                    label = key.second, color = windowColor(attachment.spec),
                )
            }
        }
        val lanes = listOf(clipLane) + playback.lanes + windows.values
        val again = picked?.let { id ->
            lanes.firstNotNullOfOrNull { lane ->
                lane.segments.firstOrNull { it.id == id }?.let { lane to it }
            }
        }
        timeline.selectSegment(again?.first, again?.second, notify = again == null && picked != null)
    }

    private fun windowLane(bone: String?, id: String) =
        SegmentLane("window-${bone.orEmpty()}-$id", montageText("lane_window"), exclusive = true).also { lane ->
            lane.onEdit = { segment ->
                segment.index()?.let { index ->
                    editWindow(bone, id, index) {
                        MontageWindow(
                            segment.start, segment.end.openEnded()
                        )
                    }
                }
            }
            lane.onSelect = { segment -> onPick?.invoke(segment?.let { MontagePick.Attachment(bone, id) }) }
            lane.menu = { time, segment -> windowMenu(bone, id, time, segment) }
            windows[bone to id] = lane
        }

    private fun clipMoved(segment: TimelineSegment) {
        document.edit(label = UndoLabel(montageKey("edit_clip"))) { montage ->
            montage.copy(clips = montage.clips.map { clip ->
                if (clip.id != segment.id) return@map clip
                val shift = segment.start - clip.start
                val trimmed = abs(segment.end - clip.end) < EPSILON && abs(shift) > EPSILON
                clip.copy(
                    start = segment.start,
                    length = segment.length,
                    from = if (trimmed) (clip.from + shift * clip.speed).coerceAtLeast(0f) else clip.from,
                    fadeIn = segment.fadeIn,
                    fadeOut = segment.fadeOut,
                    row = segment.row,
                    weight = if (trimmed) clip.weight.map { it.copy(time = it.time - shift) } else clip.weight,
                )
            })
        }
        signature = emptyList()
    }

    private fun clipMenu(time: Float, segment: TimelineSegment?): List<UiDropdownItem> {
        if (segment != null) return listOf(
            UiDropdownItem(montageText("duplicate_clip")) {
                document.edit(label = UndoLabel(montageKey("add_clip"))) { montage ->
                    val clip = montage.clips.firstOrNull { it.id == segment.id } ?: return@edit montage
                    val copy = clip.copy(id = freeClipId(montage), start = clip.end)
                    montage.copy(clips = montage.clips + copy.copy(row = freeRow(montage, copy, clip.row)))
                }
            },
            UiDropdownItem(montageText("remove_clip")) {
                document.edit(label = UndoLabel(montageKey("remove_clip"))) { montage ->
                    montage.copy(clips = montage.clips.filterNot { it.id == segment.id })
                }
            },
        )
        val available = clips()
        if (available.isEmpty()) return listOf(UiDropdownItem(montageText("no_clips"), enabled = false) {})
        return listOf(
            UiDropdownItem(montageText("add_clip"), children = available.keys.sorted().map { name ->
                UiDropdownItem(name) { addClip(name, time, available.getValue(name)) }
            }),
        )
    }

    fun addClip(animation: String, time: Float, length: Float) {
        clipLane.isListed = true
        document.edit(label = UndoLabel(montageKey("add_clip"))) { montage ->
            val clip = MontageClip(
                id = freeClipId(montage),
                animation = animation,
                start = time,
                length = length.coerceAtLeast(MIN_DURATION)
            )
            montage.copy(
                clips = montage.clips + clip.copy(row = freeRow(montage, clip)),
                duration = maxOf(montage.duration, clip.end)
            )
        }
    }

    private fun windowMenu(bone: String?, id: String, time: Float, segment: TimelineSegment?): List<UiDropdownItem> {
        val index = segment?.index()
        val attachment = montage.attachments.firstOrNull { it.bone == bone && it.spec.id == id } ?: return emptyList()
        return listOfNotNull(
            UiDropdownItem(montageText("add_window")) { addWindow(bone, id, time) }.takeIf { index == null },
            UiDropdownItem(montageText("split_window")) {
                editAttachment(bone, id) { owned ->
                    val window =
                        owned.windows.getOrNull(index ?: return@editAttachment owned) ?: return@editAttachment owned
                    val windows = owned.windows.toMutableList()
                    windows[index] = MontageWindow(window.start, time)
                    windows.add(index + 1, MontageWindow(time, window.end))
                    owned.copy(windows = windows)
                }
            }.takeIf { index != null },
            UiDropdownItem(montageText("remove_window"), enabled = attachment.windows.size > 1) {
                editAttachment(
                    bone, id
                ) { owned -> owned.copy(windows = owned.windows.filterIndexed { at, _ -> at != index }) }
            }.takeIf { index != null },
            UiDropdownItem(montageText("remove_attachment"), separatorBefore = true) {
                document.edit(label = UndoLabel(montageKey("remove_attachment"))) { montage ->
                    montage.copy(
                        attachments = montage.attachments.filterNot { it.bone == bone && it.spec.id == id },
                        tracks = montage.tracks.filterNot { it.bone == bone && it.attachment == id },
                    )
                }
            },
        )
    }

    /** Hangs the attachment [id] on [bone] once more, from [time] for a short while. */
    fun addWindow(bone: String?, id: String, time: Float) = editAttachment(bone, id) { owned ->
        val end = (time + NEW_WINDOW).coerceAtMost(montage.duration)
        owned.copy(windows = (owned.windows + MontageWindow(time, end.openEnded())).sortedBy { it.start })
    }

    fun editWindow(bone: String?, id: String, index: Int, change: (MontageWindow) -> MontageWindow) =
        editAttachment(bone, id) { owned ->
            owned.copy(windows = owned.windows.mapIndexed { at, window -> if (at == index) change(window) else window })
        }

    private fun editAttachment(bone: String?, id: String, change: (MontageAttachment) -> MontageAttachment) {
        document.edit(label = UndoLabel(montageKey("edit_window"))) { montage ->
            montage.copy(attachments = montage.attachments.map { if (it.bone == bone && it.spec.id == id) change(it) else it })
        }
    }

    fun editClip(id: String, change: (MontageClip) -> MontageClip) {
        document.edit(mergeKey = "clip-$id", label = UndoLabel(montageKey("edit_clip"))) { montage ->
            montage.copy(clips = montage.clips.map { clip ->
                if (clip.id != id) clip else change(clip).let { changed ->
                    changed.copy(
                        row = freeRow(
                            montage, changed, changed.row
                        )
                    )
                }
            })
        }
        signature = emptyList()
    }

    /** The end of a stretch, or none when it runs to the end of the montage. */
    private fun Float.openEnded(): Float? = takeIf { it < montage.duration - EPSILON }

    private fun TimelineSegment.index(): Int? = id.removePrefix(INDEXED).toIntOrNull()

    /** The bones that have rows: the ones the montage keys, and the ones the author picked. */
    private fun bonesShown(): List<String> = (montage.bones.map { it.bone } + touched.filter { it.startsWith(BONE) }
        .map { it.removePrefix(BONE) }).distinct()

    /** What the tracks depend on: which attachments, clips and bones there are and what they offer. */
    private fun signatureOf(): List<Any> = listOf(
        rigs.rig.allAttachments().map { (bone, spec) ->
            listOf(
                bone.orEmpty(),
                spec.id,
                rigs.owns(bone, spec.id),
                RigFields.animatable(spec).map { it.name to it.kind })
        },
        montage.clips.map { it.id to it.start },
        montage.bones.map { it.bone }.distinct(),
    )

    private fun buildProperty(bone: String?, id: String, field: RigFields.Field): AnimProperty<*> {
        val propertyId = fieldId(bone, id, field.name)
        val values = field.values
        return when (field.kind) {
            RigFields.Kind.TOGGLE -> AnimProperty(propertyId, field.name, TogglePropertyType, values[0])
            RigFields.Kind.NUMBER -> AnimProperty(propertyId, field.name, FloatPropertyType(field.name), values[0])
            RigFields.Kind.VECTOR -> AnimProperty(
                propertyId,
                field.name,
                if (field.name.contains(
                        ROTATION, ignoreCase = true
                    )
                ) RotationPropertyType(RotationMode.EULER) else TranslationPropertyType(),
                Vec3f(values[0], values[1], values[2]),
            )
        }
    }

    private fun fill(property: AnimProperty<*>, curves: List<VfxTrackCurve>?) {
        curves?.forEach { stored ->
            val curve = property.curves.getOrNull(stored.channel) ?: return@forEach
            curve.isVisible = stored.visible
            stored.keys.forEach { key -> curve.keyframes.add(key.toEditor()) }
            curve.sort()
        }
    }

    private fun AnimProperty<*>.stored(): List<VfxTrackCurve>? = curves.mapIndexedNotNull { channel, curve ->
        if (curve.keyframes.isEmpty()) null
        else VfxTrackCurve(channel, curve.isVisible, curve.keyframes.map { key -> key.toStored(curve) })
    }.takeIf { it.isNotEmpty() }

    private fun AnimProperty<*>?.isKeyed(): Boolean = this?.curves?.any { it.keyframes.isNotEmpty() } == true

    private fun propertyOf(id: String): AnimProperty<*>? = timeline.allProperties().firstOrNull { it.id == id }

    private fun fieldId(bone: String?, id: String, field: String) = "$FIELD${bone.orEmpty()}|$id|$field"
    private fun boneId(bone: String, property: MontageBoneProperty) = "$BONE$bone|${property.name}"
    private fun weightId(clip: String) = "$WEIGHT$clip"
    private fun attachmentKey(bone: String?, id: String) = "$FIELD${bone.orEmpty()}|$id"
    private fun boneKey(bone: String) = "$BONE$bone"
    private fun laneKey(lane: SegmentLane) = "$LANE${lane.id}"

    private fun parseField(id: String): Triple<String?, String, String>? {
        if (!id.startsWith(FIELD)) return null
        val parts = id.removePrefix(FIELD).split('|')
        if (parts.size != 3) return null
        return Triple(parts[0].ifEmpty { null }, parts[1], parts[2])
    }

    private fun parseBone(id: String): Pair<String, MontageBoneProperty>? {
        if (!id.startsWith(BONE)) return null
        val bone = id.removePrefix(BONE).substringBeforeLast('|')
        val property = MontageBoneProperty.entries.firstOrNull { it.name == id.substringAfterLast('|') } ?: return null
        return bone to property
    }

    private fun attachmentLabel(bone: String?, spec: RigAttachmentSpec) = "${bone ?: montageText("model")} (${spec.id})"

    private fun windowColor(spec: RigAttachmentSpec): Color =
        RigAttachmentTypes.of(spec)?.editorColor?.let { Color(String.format("%06X", it)) } ?: EventColor

    private companion object {
        const val INDEXED = "at-"
        const val FIELD = "field|"
        const val BONE = "bone|"

        /** How alike two turns are, as the cosine of half the angle between them, for them to count as the same. */
        const val SAME_TURN = 0.99999f
        const val LANE = "lane|"
        const val WEIGHT = "weight|"
        const val ROTATION = "rotation"
        const val EPSILON = 1e-4f
        const val KEY_EPSILON = 1e-5f
        const val MIN_DURATION = 0.1f

        /** How long a stretch added by hand lasts, in seconds, before the author drags it out. */
        const val NEW_WINDOW = 0.5f

        val ClipColors = listOf(Color("61AFEF"), Color("98C379"), Color("C678DD"), Color("E5C07B"), Color("56B6C2"))
        val EventColor = Color("E06C75")
    }
}

private fun freeClipId(montage: Montage): String {
    var index = montage.clips.size + 1
    while (montage.clips.any { it.id == "clip$index" }) index++
    return "clip$index"
}

/** The first row from [from] where [clip] covers no other clip, so clips on one row never overlap. */
private fun freeRow(montage: Montage, clip: MontageClip, from: Int = 0): Int {
    var row = from
    while (montage.clips.any { it.id != clip.id && it.row == row && it.start < clip.end && it.end > clip.start }) row++
    return row
}

/** What of a bone is its pose: what the gizmo moves and the bone fields key, its visibility apart. */
internal val PoseParts = listOf(MontageBoneProperty.POSITION, MontageBoneProperty.ROTATION, MontageBoneProperty.SCALE)

/** Whether these parts take the pose part [property] of a bone. */
private fun KeyParts.covers(property: MontageBoneProperty): Boolean = when (this) {
    KeyParts.LOCATION -> property == MontageBoneProperty.POSITION
    KeyParts.ROTATION -> property == MontageBoneProperty.ROTATION
    KeyParts.SCALE -> property == MontageBoneProperty.SCALE
    KeyParts.ALL -> true
}

/** Whether these parts take the attachment field [name]; everything means what is placed and what is animated. */
private fun KeyParts.covers(name: String, keyed: Boolean): Boolean = when (this) {
    KeyParts.LOCATION -> name == "offset"
    KeyParts.ROTATION -> name == "rotation"
    KeyParts.SCALE -> name == "scale"
    KeyParts.ALL -> keyed || name in PLACEMENT
}
