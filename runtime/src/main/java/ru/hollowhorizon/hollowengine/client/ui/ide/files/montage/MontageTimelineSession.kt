package ru.hollowhorizon.hollowengine.client.ui.ide.files.montage

import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeMontageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.TogglePropertyType
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.toEditor
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.toStored
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve
import kotlin.math.abs

/** What the author picked on the montage's timeline. */
internal sealed interface MontagePick {
    data class Clip(val id: String) : MontagePick
    data class Event(val index: Int) : MontagePick
    data class Attachment(val bone: String?, val id: String) : MontagePick
}

internal class MontageTimelineSession(
    private val document: HollowIdeMontageDocument,
    private val rigs: MontageRigEditing,
) {
    val timeline = TimelineController(document.history)

    /** The clips of the preview model, by name, with how long each is. */
    var clips: () -> Map<String, Float> = { emptyMap() }

    /** Told when the author picks a block or a marker, or lets go of it. */
    var onPick: ((MontagePick?) -> Unit)? = null

    val clipLane = SegmentLane("clips", montageText("lane_clips"), arranged = true, exclusive = true)
    val eventLane = SegmentLane("events", montageText("lane_events"), markers = true)
    private val windows = HashMap<Pair<String?, String>, SegmentLane>()

    /** Fields the author asked to animate, as property ids; with keys or without. */
    private val pinned = LinkedHashSet<String>()
    private val hidden = HashSet<String>()

    /** What the author picked in the scene at some point, as group keys, so its rows stay until closed. */
    private val touched = LinkedHashSet<String>()

    private var suppressCommit = false
    private var builtFor = -1
    private var signature: List<Any> = emptyList()

    private val montage: Montage get() = document.montage

    init {
        timeline.onChanged = {
            commit()
            applyListing()
        }
        timeline.onHideProperty = { property ->
            pinned -= property.id
            hidden += property.id
            applyListing()
        }
        timeline.onHideGroup = { group ->
            groupKeys[group]?.let(touched::remove)
            group.allProperties().forEach { property ->
                pinned -= property.id
                hidden += property.id
            }
            applyListing()
        }
        timeline.onHideLane = { lane -> lane.isListed = false }

        clipLane.isListed = montage.clips.isNotEmpty()
        eventLane.isListed = montage.events.isNotEmpty()
        clipLane.onEdit = ::clipMoved
        clipLane.onSelect = { segment -> onPick?.invoke(segment?.let { MontagePick.Clip(it.id) }) }
        clipLane.menu = ::clipMenu
        eventLane.onEdit =
            { segment -> segment.index()?.let { index -> editEvent(index) { it.copy(time = segment.start) } } }
        eventLane.onSelect = { segment -> onPick?.invoke(segment?.index()?.let(MontagePick::Event)) }
        eventLane.menu = ::eventMenu
        timeline.lanes += clipLane
        timeline.lanes += eventLane
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
        val path = listOf("${montageText("bone")} · $bone")
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
        val track = propertyOf(boneId(bone, MontageBoneProperty.VISIBLE)) ?: return true
        @Suppress("UNCHECKED_CAST") return (track as AnimProperty<Float>).valueAt(timeline.currentTime) >= 0.5f
    }

    /** Keys whether [bone] is drawn at the playhead. */
    fun keyBoneShown(bone: String, shown: Boolean) {
        if (!timeline.isRecording) return
        touchBone(bone)
        val track = propertyOf(boneId(bone, MontageBoneProperty.VISIBLE)) ?: return
        val was = if (isBoneShown(bone)) 1f else 0f
        val now = if (shown) 1f else 0f
        if (was == now) return
        pinned += track.id
        hidden -= track.id
        timeline.recordKeys(track, mapOf(0 to now), floatArrayOf(was))
        commit()
        applyListing()
    }

    /** Opens [lane] on the timeline, as picking clips or events in the scene does. */
    fun show(lane: SegmentLane) {
        lane.isListed = true
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
    fun isAnimated(bone: String?, id: String, field: String): Boolean = propertyOf(fieldId(bone, id, field)).isKeyed()

    /**
     * Starts animating [field], with a key at the playhead holding what it is now, so it is written by the
     * timeline from then on; or, when it is animated, takes its keys away and lets the authored value back.
     */
    fun toggle(bone: String?, id: String, field: String) = toggle(fieldId(bone, id, field))

    /** Keys [value] for [field] at the playhead, in the channels it changes; [shown] is what it held before. */
    fun keyField(bone: String?, id: String, field: String, value: FloatArray, shown: FloatArray) {
        if (!timeline.isRecording) return
        touchAttachment(bone, id)
        val track = propertyOf(fieldId(bone, id, field)) ?: return
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

    /** Where the montage has [property] of [bone] at the playhead: what its keys say, or the rest. */
    fun boneValue(bone: String, property: MontageBoneProperty): Vec3f {
        val rest = Vec3f(property.rest, property.rest, property.rest)
        val track = propertyOf(boneId(bone, property)) ?: return rest
        @Suppress("UNCHECKED_CAST") return (track as AnimProperty<Vec3f>).valueAt(timeline.currentTime)
    }

    /** Keys [value] for [property] of [bone] at the playhead, in the channels it changes. */
    fun keyBone(bone: String, property: MontageBoneProperty, value: Vec3f) {
        if (!timeline.isRecording) return
        touchBone(bone)
        val track = propertyOf(boneId(bone, property)) ?: return
        val now = boneValue(bone, property)
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
     * Puts a key at the playhead on what is picked, holding what it shows there: on an attachment, its animated
     * fields and where it is placed; on a bone, its position, rotation and scale. A pose blocked out by hand is
     * kept that way, one key per pose.
     */
    fun keyPicked(bone: String?, part: String?) {
        val spec = part?.let { rigs.rig.holder(bone).attachment(it) }
        val properties = when {
            spec != null -> RigFields.animatable(spec).mapNotNull { field ->
                propertyOf(fieldId(bone, spec.id, field.name))?.takeIf { it.isKeyed() || field.name in PLACEMENT }
            }.also { touchAttachment(bone, spec.id) }

            bone != null -> {
                touchBone(bone)
                PoseParts.mapNotNull { propertyOf(boneId(bone, it)) }
            }

            else -> emptyList()
        }
        if (properties.isEmpty()) return
        document.beginGesture()
        properties.forEach { property ->
            pinned += property.id
            hidden -= property.id
            timeline.addKeyframes(property, timeline.currentTime)
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
    }

    private fun applyListing(group: TrackGroup) {
        group.children.forEach(::applyListing)
        group.properties.forEach { property ->
            property.isListed = property.id !in hidden && (property.isKeyed() || property.id in pinned)
        }
        group.isListed = groupKeys[group]?.let { it in touched } == true
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
        eventLane.segments.clear()
        montage.events.forEachIndexed { index, event ->
            eventLane.segments += TimelineSegment("$INDEXED$index", event.time, 0f, event.name, EventColor)
        }
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
        val lanes = listOf(clipLane, eventLane) + windows.values
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

    private fun eventMenu(time: Float, segment: TimelineSegment?): List<UiDropdownItem> {
        val index = segment?.index()
        if (index != null) return listOf(
            UiDropdownItem(montageText("remove_event")) {
                document.edit(label = UndoLabel(montageKey("remove_event"))) { montage ->
                    montage.copy(events = montage.events.filterIndexed { at, _ -> at != index })
                }
            },
        )
        return listOf(UiDropdownItem(montageText("add_event")) { addEvent(time) })
    }

    fun addEvent(time: Float) {
        eventLane.isListed = true
        document.edit(label = UndoLabel(montageKey("add_event"))) { montage ->
            montage.copy(events = montage.events + MontageEvent(time, freeEventName(montage)))
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

    fun editEvent(index: Int, change: (MontageEvent) -> MontageEvent) {
        document.edit(label = UndoLabel(montageKey("edit_event"))) { montage ->
            montage.copy(events = montage.events.mapIndexed { at, event -> if (at == index) change(event) else event })
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

    private fun attachmentLabel(bone: String?, spec: RigAttachmentSpec) = "${bone ?: montageText("model")} · ${spec.id}"

    private fun windowColor(spec: RigAttachmentSpec): Color =
        RigAttachmentTypes.of(spec)?.editorColor?.let { Color(String.format("%06X", it)) } ?: EventColor

    private companion object {
        const val INDEXED = "at-"
        const val FIELD = "field|"
        const val BONE = "bone|"
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

private fun freeEventName(montage: Montage): String {
    var index = montage.events.size + 1
    while (montage.events.any { it.name == "event$index" }) index++
    return "event$index"
}

/** What of a bone is its pose: what the gizmo moves and the bone fields key, its visibility apart. */
internal val PoseParts = listOf(MontageBoneProperty.POSITION, MontageBoneProperty.ROTATION, MontageBoneProperty.SCALE)
