@file:Suppress("UNCHECKED_CAST")

package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.history.SnapshotStep
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class PlaybackMode {
    LOOP, ONCE
}

/** Which editor the timeline lanes are showing. */
enum class TimelineViewMode {
    /** Keys as points on their lane. */
    DOPE_SHEET,

    /** Channel values as editable curves, shaping the motion itself. */
    CURVES,
}

class TimelineController(override val history: UndoHistory = UndoHistory()) : UndoOwner {
    companion object {
        const val KEYFRAME_TIME_EPSILON = ChannelCurve.KEY_TIME_EPSILON
        private const val DEFAULT_CURVE_SPAN = 20f
    }

    val groups = mutableStateListOf<TrackGroup>()

    var currentTime by mutableStateOf(0f)
    var isPlaying by mutableStateOf(false)
    var workAreaEnd by mutableStateOf(10f)
    val playbackMode = mutableStateOf(PlaybackMode.LOOP)
    val playbackSpeed = mutableStateOf(1f)

    var pixelsPerSecond by mutableStateOf(100f)
    var viewMode by mutableStateOf(TimelineViewMode.DOPE_SHEET)
    var headerWidth by mutableStateOf(240f)
    val curveAxis = TimelineCurveAxis(DEFAULT_CURVE_SPAN)

    val curveValueCenter: Float get() = curveAxis.center
    val curveValueSpan: Float get() = curveAxis.span

    val selectedKeyframes = mutableStateListOf<Keyframe>()

    val focusedCurves = mutableStateListOf<ChannelCurve>()
    var isWorkAreaSelected by mutableStateOf(false)
    var isCameraPreviewEnabled by mutableStateOf(false)

    /**
     * Auto-keying: while it is on, the owner writes what the author changes in the inspector or with
     * a gizmo as keys at the playhead through [recordKeys], instead of into the value the tracks start from.
     */
    var isRecording by mutableStateOf(false)

    /** The tracks as they were when the transaction under way began. */
    private var transactionStart: TimelineSnapshot? = null
    private var transactionLabel = UndoLabel.EDIT

    var onChanged: (() -> Unit)? = null
    var onTimeChanged: (() -> Unit)? = null
    var onPreviewChanged: (() -> Unit)? = null
    var captureExtraState: (() -> Any?)? = null
    var restoreExtraState: ((Any?) -> Unit)? = null

    var onHideProperty: ((AnimProperty<*>) -> Unit)? = null
    var onHideGroup: ((TrackGroup) -> Unit)? = null

    fun group(path: List<String>): TrackGroup {
        require(path.isNotEmpty()) { "Timeline group path must not be empty" }
        var currentGroups: MutableList<TrackGroup> = groups
        var group: TrackGroup? = null
        for (name in path) {
            group = currentGroups.find { it.nameState == name } ?: TrackGroup(name).also { currentGroups.add(it) }
            currentGroups = group.children
        }
        return group ?: error("Timeline group path is empty")
    }

    fun <T> addProperty(path: List<String>, property: AnimProperty<T>): AnimProperty<T> {
        group(path).properties.add(property)
        return property
    }

    fun allProperties(): List<AnimProperty<*>> = groups.flatMap { it.allProperties() }

    fun allCurves(): List<ChannelCurve> = allProperties().flatMap { it.curves }

    fun propertyOf(curve: ChannelCurve): AnimProperty<*>? =
        allProperties().firstOrNull { property -> property.curves.any { it === curve } }

    fun propertyOf(keyframe: Keyframe): AnimProperty<*>? =
        allProperties().firstOrNull { it.curveOf(keyframe) != null }

    fun curveOf(keyframe: Keyframe): ChannelCurve? = allCurves().firstOrNull { curve ->
        curve.keyframes.any { it === keyframe }
    }

    fun isLocked(property: AnimProperty<*>): Boolean {
        if (property.isLocked) return true
        return groupOf(property)?.let { isLocked(it) } == true
    }

    fun groupOf(property: AnimProperty<*>): TrackGroup? {
        fun findIn(candidates: List<TrackGroup>): TrackGroup? {
            candidates.forEach { group ->
                if (property in group.properties) return group
                findIn(group.children)?.let { return it }
            }
            return null
        }
        return findIn(groups)
    }

    private fun isLocked(group: TrackGroup): Boolean {
        if (group.isLocked) return true
        return parentOf(group)?.let { isLocked(it) } == true
    }

    private fun parentOf(group: TrackGroup): TrackGroup? {
        fun findIn(candidates: List<TrackGroup>): TrackGroup? {
            candidates.forEach { candidate ->
                if (group in candidate.children) return candidate
                findIn(candidate.children)?.let { return it }
            }
            return null
        }
        return findIn(groups)
    }

    fun onUpdate(deltaSeconds: Float) {
        if (isPlaying) {
            applyCurrentTime(currentTime + deltaSeconds * playbackSpeed.value)

            val end = workAreaEnd
            if (currentTime >= end) {
                applyCurrentTime(0f)
                if (playbackMode.value == PlaybackMode.ONCE) {
                    isPlaying = false
                }
            }
        }
        allProperties().forEach { it.update(currentTime) }
    }

    fun applyCurrentTime(time: Float) {
        currentTime = time.coerceIn(0f, workAreaEnd)
        onTimeChanged?.invoke()
    }

    /**
     * Moves the playhead to where whatever is being edited has actually got to.
     */
    fun followPlayhead(time: Float) {
        val end = workAreaEnd
        currentTime = if (end > 0f) time.mod(end) else time.coerceAtLeast(0f)
        allProperties().forEach { it.update(currentTime) }
    }

    fun applyCameraPreviewEnabled(isEnabled: Boolean) {
        isCameraPreviewEnabled = isEnabled
        onPreviewChanged?.invoke()
    }

    fun togglePlayback() {
        isPlaying = !isPlaying
    }

    /**
     * Bumped to ask the window to fit the work area into the lanes; only the window knows how wide
     * they are.
     */
    var frameTimeRequests by mutableStateOf(0)
        private set

    fun requestFrameTime() {
        frameTimeRequests++
    }

    /**
     * Moves the playhead to the nearest key after it ([direction] > 0) or before it, on the tracks the
     * list shows. Returns false when there is none that way.
     */
    fun jumpToKey(direction: Int): Boolean {
        val now = currentTime
        val times = allProperties().filter { it.isListed }.flatMap { it.curves }.flatMap { it.keyframes }.map { it.time }
        val target = if (direction > 0) {
            times.filter { it > now + KEYFRAME_TIME_EPSILON }.minOrNull()
        } else {
            times.filter { it < now - KEYFRAME_TIME_EPSILON }.maxOrNull()
        } ?: return false
        isPlaying = false
        applyCurrentTime(target)
        return true
    }

    fun isSelected(keyframe: Keyframe): Boolean = selectedKeyframes.any { it === keyframe }

    fun clearSelection() {
        selectedKeyframes.clear()
        isWorkAreaSelected = false
    }

    fun select(keys: List<Keyframe>, additive: Boolean) {
        if (!additive) selectedKeyframes.clear()
        keys.forEach { key -> if (!isSelected(key)) selectedKeyframes.add(key) }
        isWorkAreaSelected = false
    }

    fun isFocused(curve: ChannelCurve): Boolean = focusedCurves.any { it === curve }

    fun focusCurves(curves: List<ChannelCurve>, additive: Boolean) {
        val editable = curves.filter { it.spec.supportsCurveEditor }
        if (editable.isEmpty()) return
        if (additive) {
            val allFocused = editable.all { isFocused(it) }
            if (allFocused) editable.forEach { curve -> focusedCurves.removeAll { it === curve } }
            else editable.forEach { curve -> if (!isFocused(curve)) focusedCurves.add(curve) }
            return
        }
        val alreadyExactly = focusedCurves.size == editable.size && editable.all { isFocused(it) }
        focusedCurves.clear()
        if (!alreadyExactly) focusedCurves.addAll(editable)
    }

    fun selectStacked(pressed: Keyframe, stacked: List<Keyframe>, additive: Boolean) {
        if (additive) {
            toggleSelection(listOf(pressed))
            return
        }
        val stack = if (stacked.any { it === pressed }) stacked else stacked + pressed
        if (stack.size > 1 && selectedKeyframes.size == 1) {
            val current = stack.indexOfFirst { isSelected(it) }
            if (current >= 0) {
                select(listOf(stack[(current + 1) % stack.size]), additive = false)
                return
            }
        }
        if (!isSelected(pressed)) select(listOf(pressed), additive = false)
    }

    fun toggleSelection(keys: List<Keyframe>) {
        val allSelected = keys.all { isSelected(it) }
        if (allSelected) keys.forEach { key -> selectedKeyframes.removeAll { it === key } }
        else keys.forEach { key -> if (!isSelected(key)) selectedKeyframes.add(key) }
        isWorkAreaSelected = false
    }

    /** Changes the tracks with [block], as one step called [label]; inside a transaction it joins that one. */
    fun edit(label: UndoLabel, block: () -> Unit) {
        val before = createSnapshot().takeIf { transactionStart == null }
        block()
        onChanged?.invoke()
        if (before != null) recordSince(before, label)
    }

    fun deleteSelectedKeyframes() {
        edit(TimelineEdits.DELETE_KEYS) {
            val doomed = selectedKeyframes.toList()
            allProperties().forEach { property ->
                if (isLocked(property)) return@forEach
                property.curves.forEach { curve ->
                    curve.keyframes.removeAll { key -> doomed.any { it === key } }
                }
            }
            selectedKeyframes.clear()
        }
    }

    fun setKey(curve: ChannelCurve, time: Float, value: Float, selectKey: Boolean = true): Keyframe {
        val clamped = time.coerceIn(0f, workAreaEnd)
        val bounded = curve.spec.normalize(boundsOf(curve).clamp(value))
        val existing = curve.keyAt(clamped)
        val key = if (existing != null) {
            existing.value = bounded
            existing
        } else {
            val previous = curve.keyframes.filter { it.time <= clamped }.maxByOrNull { it.time }
            Keyframe(
                time = clamped,
                value = bounded,
                interpolation = if (curve.spec.supportsCurveEditor) {
                    previous?.interpolation ?: KeyInterpolation.BEZIER
                } else {
                    KeyInterpolation.CONSTANT
                },
            ).also { curve.keyframes.add(it) }
        }
        curve.sort()
        if (selectKey) select(listOf(key), additive = false)
        return key
    }

    fun setSelectedKeyframeValue(reference: Keyframe, value: Float) {
        val referenceCurve = curveOf(reference) ?: return
        val delta = value - reference.value
        edit(TimelineEdits.EDIT_VALUE) {
            selectedKeyframes.forEach { key ->
                val property = propertyOf(key) ?: return@forEach
                if (isLocked(property)) return@forEach
                val curve = property.curveOf(key) ?: return@forEach
                val candidate = if (referenceCurve.spec.sampling == ChannelSampling.DISCRETE) {
                    if (curve.spec.valueOptions != referenceCurve.spec.valueOptions) return@forEach
                    value
                } else {
                    key.value + delta
                }
                key.value = curve.spec.normalize(boundsOf(curve).clamp(candidate))
            }
        }
    }

    fun addKeyframes(property: AnimProperty<*>, time: Float): List<Keyframe> {
        if (isLocked(property)) return emptyList()
        return edited(TimelineEdits.ADD_KEY) {
            property.curves.mapIndexed { channel, curve ->
                setKey(curve, time, curve.valueAt(time, defaultChannel(property, channel)), selectKey = false)
            }.also { select(it, additive = false) }
        }
    }

    /**
     * Keys [values] (channel to value) of [property] at the playhead, the way auto-keying records an
     * edit. A channel keyed for the first time away from the start also gets a key at zero holding
     * [previous], what it was before the edit, so the edit animates from there rather than jumping.
     */
    fun recordKeys(property: AnimProperty<*>, values: Map<Int, Float>, previous: FloatArray? = null) {
        if (values.isEmpty() || isLocked(property)) return
        val time = currentTime
        edit(TimelineEdits.RECORD_KEYS) {
            values.forEach { (channel, value) ->
                val curve = property.curves.getOrNull(channel) ?: return@forEach
                val before = previous?.getOrNull(channel) ?: curve.valueAt(time, defaultChannel(property, channel))
                if (curve.keyframes.isEmpty() && time > KEYFRAME_TIME_EPSILON) {
                    setKey(curve, 0f, before, selectKey = false)
                }
                setKey(curve, time, curve.spec.unwrap(value, curve.valueAt(time, before)), selectKey = false)
            }
        }
    }

    private fun defaultChannel(property: AnimProperty<*>, channel: Int): Float {
        val values = FloatArray(property.channels.size)
        property.decomposeDefault(values)
        return values.getOrElse(channel) { 0f }
    }

    private class KeyframeClip(val curve: ChannelCurve, val offset: Float, val state: KeyframeState)

    private var clipboard: List<KeyframeClip> = emptyList()

    val canPaste: Boolean get() = clipboard.isNotEmpty()

    fun copySelectedKeyframes() {
        val keys = selectedKeyframes.toList()
        if (keys.isEmpty()) return
        val earliest = keys.minOf { it.time }
        clipboard = keys.mapNotNull { key ->
            val curve = curveOf(key) ?: return@mapNotNull null
            KeyframeClip(curve, key.time - earliest, KeyframeState.of(key))
        }
    }

    fun cutSelectedKeyframes() {
        copySelectedKeyframes()
        deleteSelectedKeyframes()
    }

    fun pasteKeyframes(time: Float = currentTime) {
        if (clipboard.isEmpty()) return
        val live = allCurves()
        edit(TimelineEdits.PASTE_KEYS) {
            val created = mutableListOf<Keyframe>()
            clipboard.forEach { clip ->
                if (live.none { it === clip.curve }) return@forEach
                val owner = propertyOf(clip.curve)
                if (owner != null && isLocked(owner)) return@forEach
                val target = (time + clip.offset).coerceIn(0f, workAreaEnd)
                clip.curve.keyframes.removeAll { abs(it.time - target) <= KEYFRAME_TIME_EPSILON }
                val key = clip.state.toKeyframe().also {
                    it.time = target
                    it.value = boundsOf(clip.curve).clamp(it.value)
                }
                clip.curve.keyframes.add(key)
                clip.curve.sort()
                created += key
            }
            select(created, additive = false)
        }
    }

    private fun boundsOf(curve: ChannelCurve?): ChannelBounds {
        curve ?: return ChannelBounds.Unbounded
        val property = propertyOf(curve) ?: return ChannelBounds.Unbounded
        val channel = property.curves.indexOfFirst { it === curve }.takeIf { it >= 0 }
            ?: return ChannelBounds.Unbounded
        return property.bounds(channel)
    }

    fun duplicateSelectedKeyframes() {

        val originals = selectedKeyframes.toList()
        if (originals.isEmpty()) return
        edit(TimelineEdits.DUPLICATE_KEYS) {
            val created = mutableListOf<Keyframe>()
            originals.forEach { original ->
                val curve = curveOf(original) ?: return@forEach
                val owner = propertyOf(original) ?: return@forEach
                if (isLocked(owner)) return@forEach
                val target = findFreeTime(curve, original.time + KEYFRAME_TIME_EPSILON * 2f)
                if (curve.keyAt(target) != null) return@forEach
                val copy = original.copy(target)
                curve.keyframes.add(copy)
                curve.sort()
                created += copy
            }
            select(created, additive = false)
        }
    }

    var dragStartTimes: Map<Keyframe, Float>? = null
        private set
    var dragFocusKeyframe: Keyframe? = null
        private set

    var dragDriver: Keyframe? = null
        private set

    private var dragStartValues: Map<Keyframe, Float>? = null

    fun isDragDriver(keyframe: Keyframe): Boolean = dragDriver === keyframe

    fun beginKeyframeDrag(focus: Keyframe) {
        beginHistoryTransaction(TimelineEdits.MOVE_KEYS)
        dragStartTimes = selectedKeyframes.associateWith { it.time }
        dragFocusKeyframe = focus
        dragDriver = focus
    }

    fun beginCloneDrag(driver: Keyframe, withValues: Boolean): Boolean {
        val originals = selectedKeyframes.toList().ifEmpty { listOf(driver) }
        beginHistoryTransaction(TimelineEdits.CLONE_KEYS)
        val clones = LinkedHashMap<Keyframe, Keyframe>()
        originals.forEach { original ->
            val curve = curveOf(original) ?: return@forEach
            val owner = propertyOf(original) ?: return@forEach
            if (isLocked(owner)) return@forEach
            val target = findFreeTime(curve, original.time + KEYFRAME_TIME_EPSILON * 2f)
            if (curve.keyAt(target) != null) return@forEach
            val copy = original.copy(target)
            curve.keyframes.add(copy)
            curve.sort()
            clones[original] = copy
        }
        if (clones.isEmpty()) {
            commitHistoryTransaction()
            return false
        }
        select(clones.values.toList(), additive = false)
        dragStartTimes = clones.values.associateWith { it.time }
        if (withValues) dragStartValues = clones.values.associateWith { it.value }
        dragFocusKeyframe = clones[driver] ?: clones.values.first()
        dragDriver = driver
        onChanged?.invoke()
        return true
    }

    fun beginCurveDrag(focus: Keyframe) {
        beginKeyframeDrag(focus)
        dragStartValues = selectedKeyframes.associateWith { it.value }
    }

    fun applyKeyframeDrag(deltaSeconds: Float) {
        val starts = dragStartTimes ?: return
        if (moveKeyframesFromStarts(starts, deltaSeconds)) onChanged?.invoke()
    }

    fun applyCurveDrag(deltaSeconds: Float, deltaValue: Float) {
        val starts = dragStartTimes ?: return
        var changed = moveKeyframesFromStarts(starts, deltaSeconds)
        dragStartValues?.forEach { (keyframe, startValue) ->
            val target = boundsOf(curveOf(keyframe)).clamp(startValue + deltaValue)
            if (keyframe.value != target) {
                keyframe.value = target
                changed = true
            }
        }
        if (changed) onChanged?.invoke()
    }

    fun endKeyframeDrag() {
        dragStartTimes = null
        dragFocusKeyframe = null
        dragDriver = null
        dragStartValues = null
        commitHistoryTransaction()
    }

    fun nudgeSelectedKeyframes(deltaSeconds: Float) {
        if (selectedKeyframes.isEmpty()) return
        edit(TimelineEdits.NUDGE_KEYS) {
            val starts = selectedKeyframes.associateWith { it.time }
            moveKeyframesFromStarts(starts, deltaSeconds)
        }
    }

    private fun moveKeyframesFromStarts(starts: Map<Keyframe, Float>, deltaSeconds: Float): Boolean {
        if (starts.isEmpty()) return false
        val clampedDelta = deltaSeconds.coerceIn(
            minimumValue = -starts.values.min(),
            maximumValue = workAreaEnd - starts.values.max(),
        )
        val dragged = starts.keys
        val targets = starts.mapValues { (_, startTime) -> startTime + clampedDelta }
        val blocked = targets.any { (keyframe, target) ->
            curveOf(keyframe)?.keyframes?.any { other ->
                dragged.none { it === other } && abs(other.time - target) <= KEYFRAME_TIME_EPSILON
            } == true
        }
        if (blocked) return false

        var changed = false
        targets.forEach { (keyframe, target) ->
            if (keyframe.time != target) {
                keyframe.time = target
                changed = true
            }
        }
        if (changed) allCurves().forEach { it.sort() }
        return changed
    }

    fun applyPreset(preset: CurvePreset) {
        if (!canEditSelectedCurves) return
        edit(TimelineEdits.CURVE_PRESET) {
            selectedKeyframes.toList().forEach { key ->
                val curve = curveOf(key) ?: return@forEach
                if (!curve.spec.supportsCurveEditor) return@forEach
                val index = curve.keyframes.indexOfFirst { it === key }
                CurvePresets.apply(preset, key, curve.keyframes.getOrNull(index + 1))
            }
        }
    }

    fun setSelectedHandleMode(mode: HandleMode) {
        edit(TimelineEdits.EDIT_HANDLES) {
            selectedKeyframes.forEach { key ->
                val curve = curveOf(key) ?: return@forEach
                if (!curve.spec.supportsCurveEditor) return@forEach
                if (mode != HandleMode.AUTO) freezeAutoTangents(key)
                key.handleMode = mode
            }
        }
    }

    fun setTangent(
        keyframe: Keyframe,
        side: TangentSide,
        tangent: KeyTangent,
        mode: HandleMode,
        timeScale: Float,
        valueScale: Float,
    ) {
        val curve = curveOf(keyframe) ?: return
        if (!curve.spec.supportsCurveEditor) return
        if (keyframe.handleMode == HandleMode.AUTO) freezeAutoTangents(keyframe)
        keyframe.handleMode = mode
        curve.useSpline(keyframe, side)

        val clamped = when (side) {
            TangentSide.OUTGOING -> KeyTangent(tangent.time.coerceAtLeast(0f), tangent.value)
            TangentSide.INCOMING -> KeyTangent(tangent.time.coerceAtMost(0f), tangent.value)
        }
        keyframe.setTangent(side, clamped)

        if (mode == HandleMode.FREE) {
            onChanged?.invoke()
            return
        }
        val partnerSide = if (side == TangentSide.OUTGOING) TangentSide.INCOMING else TangentSide.OUTGOING
        val partner = TimelineCurve.mirror(
            dragged = clamped,
            partner = keyframe.tangent(partnerSide),
            timeScale = timeScale,
            valueScale = valueScale,
            mirrorLength = mode == HandleMode.MIRRORED,
        )
        if (partner != null) {
            keyframe.setTangent(partnerSide, partner)
            curve.useSpline(keyframe, partnerSide)
        }
        onChanged?.invoke()
    }

    fun smoothSelectedKeyframes() {
        if (!canEditSelectedCurves) return
        edit(TimelineEdits.SMOOTH_KEYS) {
            selectedKeyframes.forEach { key ->
                val curve = curveOf(key) ?: return@forEach
                if (!curve.spec.supportsCurveEditor) return@forEach
                key.interpolation = KeyInterpolation.BEZIER
                key.handleMode = HandleMode.AUTO
                key.incoming = KeyTangent.ZERO
                key.outgoing = KeyTangent.ZERO
            }
        }
    }

    private fun freezeAutoTangents(keyframe: Keyframe) {
        if (keyframe.handleMode != HandleMode.AUTO) return
        val curve = curveOf(keyframe) ?: return
        val tangents = curve.effectiveTangents(keyframe)
        keyframe.incoming = tangents.incoming
        keyframe.outgoing = tangents.outgoing
    }

    fun enterCurveView() {
        val wasDopeSheet = viewMode == TimelineViewMode.DOPE_SHEET
        focusedCurves.removeAll { !it.spec.supportsCurveEditor }
        viewMode = TimelineViewMode.CURVES
        if (wasDopeSheet) frameCurves()
    }

    fun frameCurves() {
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        allProperties().forEach { property ->
            if (!property.isVisible) return@forEach
            property.curves.forEach { curve ->
                if (!curve.isVisible) return@forEach
                if (!curve.spec.supportsCurveEditor) return@forEach
                if (focusedCurves.isNotEmpty() && !isFocused(curve)) return@forEach
                curve.keyframes.forEach { key ->
                    lowest = min(lowest, key.value)
                    highest = max(highest, key.value)
                }
            }
        }
        if (lowest > highest) {
            curveAxis.glideTo(0f, DEFAULT_CURVE_SPAN)
            return
        }
        curveAxis.glideTo((lowest + highest) * 0.5f, max(highest - lowest, 1f) * 1.4f)
    }

    val canEditSelectedCurves: Boolean
        get() = selectedKeyframes.any { key -> curveOf(key)?.spec?.supportsCurveEditor == true }

    /** Starts a drag or a recording whose changes go back as one step called [label]. */
    fun beginHistoryTransaction(label: UndoLabel) {
        if (transactionStart != null) return
        transactionStart = createSnapshot()
        transactionLabel = label
    }

    fun commitHistoryTransaction() {
        val before = transactionStart ?: return
        transactionStart = null
        recordSince(before, transactionLabel)
    }

    fun clearHistory() {
        transactionStart = null
        history.clear()
    }

    private fun recordSince(before: TimelineSnapshot, label: UndoLabel) {
        if (createSnapshot() != before) history.record(SnapshotStep(this, label, before, ::createSnapshot, ::restoreFromHistory))
    }

    private fun restoreFromHistory(snapshot: TimelineSnapshot) {
        restoreSnapshot(snapshot)
        onChanged?.invoke()
        onTimeChanged?.invoke()
    }

    internal fun createSnapshot(): TimelineSnapshot = TimelineSnapshot(
        properties = allProperties().map { property ->
            PropertySnapshot(
                property = property,
                type = property.type,
                state = PropertyState.of(property),
                curves = property.curves,
                keys = property.curves.map { curve -> curve.keyframes.map { KeyframeState.of(it) } },
            )
        },
        currentTime = currentTime,
        workAreaEnd = workAreaEnd,
        extra = captureExtraState?.invoke(),
    )

    internal fun restoreSnapshot(snapshot: TimelineSnapshot) {
        snapshot.properties.forEach { propertySnapshot ->
            propertySnapshot.state.applyTo(propertySnapshot.property)
            propertySnapshot.curves.forEachIndexed { index, curve ->
                curve.keyframes.clear()
                propertySnapshot.keys.getOrNull(index)
                    ?.let { keys -> curve.keyframes.addAll(keys.map { it.toKeyframe() }) }
            }
            propertySnapshot.property.restoreState(propertySnapshot.type, propertySnapshot.curves)
        }
        selectedKeyframes.clear()
        val liveCurves = allCurves()
        focusedCurves.retainAll { curve -> liveCurves.any { it === curve } }
        currentTime = snapshot.currentTime
        workAreaEnd = snapshot.workAreaEnd
        restoreExtraState?.invoke(snapshot.extra)
    }

    private fun <T> edited(label: UndoLabel, block: () -> T): T {
        var result: T? = null
        edit(label) { result = block() }
        return result as T
    }

    private fun findFreeTime(curve: ChannelCurve, requestedTime: Float): Float {
        var time = requestedTime.coerceIn(0f, workAreaEnd)
        while (curve.keyAt(time) != null && time < workAreaEnd) {
            time = (time + KEYFRAME_TIME_EPSILON * 2f).coerceAtMost(workAreaEnd)
        }
        return time
    }
}


internal fun AnimProperty<*>.decomposeDefault(into: FloatArray) {
    (type as PropertyType<Any?>).decompose(defaultValue, into)
}

internal fun AnimProperty<*>.decomposeAt(time: Float): FloatArray {
    val values = FloatArray(channels.size)
    (type as PropertyType<Any?>).decompose(valueAt(time), values)
    return values
}

class TimelineCurveAxis(defaultSpan: Float) {
    var center by mutableStateOf(0f)
        private set
    var span by mutableStateOf(defaultSpan)
        private set

    var targetCenter by mutableStateOf(0f)
        private set
    var targetSpan by mutableStateOf(defaultSpan)
        private set

    private var centerVelocity = 0f
    private var spanVelocity = 0f
    private var lastFrame = 0L

    fun glideTo(center: Float, span: Float) {
        targetCenter = center
        targetSpan = span
    }

    fun snapTo(center: Float, span: Float) {
        glideTo(center, span)
        this.center = center
        this.span = span
        centerVelocity = 0f
        spanVelocity = 0f
    }

    fun advance(frameNanos: Long): Boolean {
        val previous = lastFrame
        lastFrame = frameNanos
        if (previous == 0L) return false

        val centerError = targetCenter - center
        val spanError = targetSpan - span
        val settled =
            abs(centerError) < span * 0.0005f && abs(centerVelocity) < span * 0.0005f && abs(spanError) < span * 0.0005f && abs(
                spanVelocity
            ) < span * 0.0005f
        if (settled) {
            if (center != targetCenter || span != targetSpan) {
                center = targetCenter
                span = targetSpan
                centerVelocity = 0f
                spanVelocity = 0f
                return true
            }
            return false
        }

        val dt = ((frameNanos - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
        centerVelocity += (centerError * SPRING_STIFFNESS - centerVelocity * SPRING_DAMPING) * dt
        spanVelocity += (spanError * SPRING_STIFFNESS - spanVelocity * SPRING_DAMPING) * dt
        center += centerVelocity * dt
        span = (span + spanVelocity * dt).coerceAtLeast(1e-4f)
        return true
    }

    private companion object {
        const val SPRING_STIFFNESS = 260f
        const val SPRING_DAMPING = 30f
    }
}
