package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene

import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.max
import kotlin.math.min

data class CameraPose(
    val position: Vec3f = Vec3f.ZERO,
    val rotation: Vec3f = Vec3f.ZERO,
    val fov: Float = 70f,
) {
    val pitch: Float get() = rotation.x
    val yaw: Float get() = rotation.y
    val roll: Float get() = rotation.z
}

/** The ids the camera rig is built from; a saved file binds back to its drivers through them. */
object CameraRig {
    const val CAMERA_ID = "camera"
    const val TRANSFORM_ID = "camera.transform"
    const val LENS_ID = "camera.lens"
    const val TRANSLATION_ID = "camera.translation"
    const val ROTATION_ID = "camera.rotation"
    const val FOV_ID = "camera.fov"

    const val DEFAULT_FOV = 70f

    val FOV_BOUNDS = ChannelBounds(minimum = 1f, maximum = 200f)
}

class CutscenePlaybackController {
    val timeline = TimelineController()

    private var localPosition = Vec3f.ZERO
    private var localRotation = Vec3f.ZERO
    private var lensFov = CameraRig.DEFAULT_FOV
    private var environmentTime = 0f
    private var environmentWeather = CutsceneWeather.CLEAR

    lateinit var translation: AnimProperty<Vec3f>
        private set
    lateinit var rotation: AnimProperty<Vec3f>
        private set
    lateinit var fov: AnimProperty<Float>
        private set
    lateinit var timeOfDay: AnimProperty<Float>
        private set
    lateinit var weather: AnimProperty<CutsceneWeather>
        private set

    /** Where the coordinates in this scene are measured from */
    var origin: CutsceneOrigin = CutsceneOrigin()
        set(value) {
            field = value
            refreshFrame()
        }

    /** The placement a script asked for. */
    var anchor: CutsceneAnchor = CutsceneAnchor.WHERE_RECORDED
        set(value) {
            field = value
            refreshFrame()
        }

    var frame: CutsceneFrame = CutsceneFrame.IDENTITY
        private set

    private var followsAnchor = false

    var isPlaying: Boolean = false
        private set
    var isLooping: Boolean = false
        private set

    val duration: Float get() = timeline.workAreaEnd
    val currentTime: Float get() = timeline.currentTime

    init {
        buildDefaultRig()
    }

    val currentPose: CameraPose
        get() = CameraPose(
            position = frame.toWorld(localPosition),
            rotation = frame.toWorldRotation(localRotation),
            fov = lensFov,
        )

    val currentEnvironment: CutsceneEnvironment
        get() = CutsceneEnvironment(
            timeOfDay = environmentTime.takeIf { timeOfDay.hasVisibleKeys() },
            weather = environmentWeather.takeIf { weather.hasVisibleKeys() },
        )

    fun setupTracks(data: CutsceneData, loop: Boolean = false, anchor: CutsceneAnchor = CutsceneAnchor.WHERE_RECORDED) {
        timeline.groups.clear()
        timeline.clearSelection()
        timeline.workAreaEnd = max(0.1f, data.duration)
        timeline.currentTime = 0f
        isLooping = loop
        isPlaying = false

        data.nodes.forEach { node -> readNode(node, timeline, emptyList()) }
        buildDefaultRig()

        this.anchor = anchor
        origin = data.origin
        updateProperties()
    }

    fun play() {
        isPlaying = true
    }

    fun pause() {
        isPlaying = false
    }

    fun stop() {
        isPlaying = false
        seek(0f)
    }

    fun seek(time: Float) {
        timeline.currentTime = time.coerceIn(0f, duration)
        updateProperties()
    }

    fun setDuration(duration: Float) {
        timeline.workAreaEnd = max(0.1f, duration)
        if (timeline.currentTime > timeline.workAreaEnd) seek(timeline.workAreaEnd)
    }

    fun update(deltaSeconds: Float) {
        if (followsAnchor) refreshFrame()
        if (isPlaying && duration > 0f) {
            val next = timeline.currentTime + max(0f, deltaSeconds)
            timeline.currentTime = if (isLooping) next % duration else min(duration, next)
            if (!isLooping && timeline.currentTime >= duration) isPlaying = false
        }
        updateProperties()
    }

    fun updateProperties() {
        timeline.allProperties().forEach { it.update(timeline.currentTime) }
    }

    fun refreshFrame() {
        frame = CutsceneAnchors.resolve(origin, anchor)
        followsAnchor = CutsceneAnchors.follows(anchor)
    }

    /**
     * Shifts the origin without shifting the scene.
     */
    fun reanchor(position: Vec3f, yaw: Float) {
        val previous = origin.frame
        val next = origin.moved(position, yaw).frame
        rebase(translation) { local -> next.toLocal(previous.toWorld(local)) }
        rebase(rotation) { local -> next.toLocalRotation(previous.toWorldRotation(local)) }
        origin = origin.moved(position, yaw)
        updateProperties()
    }

    /**
     * Resamples the keys of a property using [convert]
     */
    private fun <T> rebase(property: AnimProperty<T>, convert: (T) -> T) {
        val type = property.type
        val size = type.channels.size
        val times = property.curves.flatMap { curve -> curve.keyframes.map { it.time } }.distinct().sorted()
        if (times.isEmpty()) return

        val buffer = FloatArray(size)
        val converted = times.associateWith { time ->
            property.decomposeDefault(buffer)
            property.curves.forEachIndexed { index, curve -> buffer[index] = curve.valueAt(time, buffer[index]) }
            val next = FloatArray(size)
            type.decompose(convert(type.compose(buffer)), next)
            next
        }
        property.curves.forEachIndexed { index, curve ->
            converted.forEach { (time, values) ->
                val key = curve.keyAt(time) ?: Keyframe(time, 0f).also { curve.keyframes.add(it) }
                key.value = values.getOrElse(index) { key.value }
            }
            curve.sort()
        }
        rotateTangents(property, convert, times)
    }

    private fun <T> rotateTangents(
        property: AnimProperty<T>,
        convert: (T) -> T,
        times: List<Float>,
    ) {
        val type = property.type
        if (!type.isChannelSpaceLinear) return
        val size = type.channels.size
        val linear = linearPart(type, convert, size) ?: return

        val incoming = FloatArray(size)
        val outgoing = FloatArray(size)
        times.forEach { time ->
            val keys = property.curves.map { it.keyAt(time) }
            if (keys.all { it == null }) return@forEach
            keys.forEachIndexed { index, key ->
                incoming[index] = key?.incoming?.value ?: 0f
                outgoing[index] = key?.outgoing?.value ?: 0f
            }
            val turnedIn = linear.applyTo(incoming)
            val turnedOut = linear.applyTo(outgoing)
            keys.forEachIndexed { index, key ->
                key ?: return@forEachIndexed
                key.incoming = KeyTangent(key.incoming.time, turnedIn[index])
                key.outgoing = KeyTangent(key.outgoing.time, turnedOut[index])
            }
        }
    }

    private fun <T> linearPart(type: PropertyType<T>, convert: (T) -> T, size: Int): LinearMap {
        val zero = FloatArray(size)
        val shifted = FloatArray(size)
        type.decompose(convert(type.compose(zero)), shifted)
        val columns = Array(size) { axis ->
            val basis = FloatArray(size)
            basis[axis] = 1f
            val mapped = FloatArray(size)
            type.decompose(convert(type.compose(basis)), mapped)
            FloatArray(size) { row -> mapped[row] - shifted[row] }
        }
        return LinearMap(columns)
    }

    fun toData(name: String = "New Cutscene"): CutsceneData = CutsceneData(
        name = name,
        duration = duration,
        origin = origin,
        nodes = timeline.groups.map { it.toNode() },
    )

    private fun buildDefaultRig() {
        val transform = listOf("Camera", "Transform")
        val lens = listOf("Camera", "Lens")
        val environment = listOf("World", "Environment")
        translation = bind(transform, CameraRig.TRANSLATION_ID, "Translation", TranslationPropertyType(), Vec3f.ZERO) {
            localPosition = it
        }
        rotation = bind(transform, CameraRig.ROTATION_ID, "Rotation", RotationPropertyType(), Vec3f.ZERO) {
            localRotation = it
        }
        fov =
            bind(lens, CameraRig.FOV_ID, "FOV", FloatPropertyType("FOV", CameraRig.FOV_BOUNDS), CameraRig.DEFAULT_FOV) {
                lensFov = it
            }
        timeOfDay = bind(
            environment,
            EnvironmentRig.TIME_OF_DAY_ID,
            "Time of Day",
            TimeOfDayPropertyType(),
            0f,
        ) { environmentTime = it }
        weather = bind(
            environment,
            EnvironmentRig.WEATHER_ID,
            "Weather",
            WeatherPropertyType(),
            CutsceneWeather.CLEAR,
        ) { environmentWeather = it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> bind(
        path: List<String>,
        id: String,
        name: String,
        type: PropertyType<T>,
        defaultValue: T,
        apply: (T) -> Unit,
    ): AnimProperty<T> {
        val existing = timeline.allProperties().firstOrNull { it.id == id }
        if (existing != null && existing.type.id == type.id) {
            val bound = AnimProperty(id, existing.nameState, existing.type as PropertyType<T>, defaultValue, apply)
            bound.adoptCurves(existing)
            val group = timeline.groupOf(existing) ?: timeline.group(path)
            group.properties[group.properties.indexOf(existing)] = bound
            return bound
        }
        if (existing != null) timeline.groupOf(existing)?.properties?.remove(existing)
        return timeline.addProperty(path, AnimProperty(id, name, type, defaultValue, apply))
    }
}

/** Columns of a square matrix over the channel space. */
private class LinearMap(private val columns: Array<FloatArray>) {
    fun applyTo(vector: FloatArray): FloatArray {
        val result = FloatArray(vector.size)
        for (axis in columns.indices) {
            val scale = vector.getOrElse(axis) { 0f }
            if (scale == 0f) continue
            val column = columns[axis]
            for (row in result.indices) result[row] += column.getOrElse(row) { 0f } * scale
        }
        return result
    }
}

private fun readNode(node: CutsceneNodeData, timeline: TimelineController, path: List<String>) {
    when (node.kind) {
        CutsceneNodeKind.GROUP -> {
            val next = path + node.name
            timeline.group(next)
            node.children.forEach { readNode(it, timeline, next) }
        }

        CutsceneNodeKind.PROPERTY -> {
            val data = node.property ?: return
            val property = createProperty(node.id, node.name, data)
            timeline.addProperty(path.ifEmpty { listOf(node.name) }, property)
        }
    }
}

private fun createProperty(id: String, name: String, data: CutscenePropertyData): AnimProperty<*> {
    val property: AnimProperty<*> = when {
        data.type == TranslationPropertyType.ID -> AnimProperty(id, name, TranslationPropertyType(), Vec3f.ZERO)
        data.type == RotationPropertyType.ID -> AnimProperty(
            id, name, RotationPropertyType(CutsceneEnums.rotationMode(data.rotationMode)), Vec3f.ZERO,
        )

        id == CameraRig.FOV_ID -> AnimProperty(
            id, name, FloatPropertyType(name, CameraRig.FOV_BOUNDS), CameraRig.DEFAULT_FOV
        )

        data.type == TimeOfDayPropertyType.ID -> AnimProperty(id, name, TimeOfDayPropertyType(), 0f)
        data.type == WeatherPropertyType.ID ->
            AnimProperty(id, name, WeatherPropertyType(), CutsceneWeather.CLEAR)

        else -> AnimProperty(id, name, FloatPropertyType(name), 0f)
    }
    property.isVisible = data.visible
    property.isLocked = data.locked
    data.curves.forEach { curveData ->
        val curve = property.curves.firstOrNull { it.name == curveData.channel } ?: return@forEach
        curve.isVisible = curveData.visible
        curveData.keyframes.forEach { curve.keyframes.add(it.toKeyframe(curve.spec)) }
        curve.sort()
    }
    return property
}

private fun CutsceneKeyData.toKeyframe(spec: ChannelSpec) = Keyframe(
    time = time,
    value = spec.normalize(value),
    interpolation = if (spec.supportsCurveEditor) {
        CutsceneEnums.interpolation(interpolation)
    } else {
        KeyInterpolation.CONSTANT
    },
    handleMode = CutsceneEnums.handleMode(handles),
    incoming = KeyTangent(inTime, inValue),
    outgoing = KeyTangent(outTime, outValue),
)

private fun AnimProperty<*>.hasVisibleKeys(): Boolean =
    curves.any { curve -> curve.isVisible && curve.keyframes.isNotEmpty() }

private fun TrackGroup.toNode(): CutsceneNodeData = CutsceneNodeData(
    id = nameState,
    name = nameState,
    kind = CutsceneNodeKind.GROUP,
    children = children.map { it.toNode() } + properties.map { it.toNode() },
)

private fun AnimProperty<*>.toNode(): CutsceneNodeData = CutsceneNodeData(
    id = id,
    name = nameState,
    kind = CutsceneNodeKind.PROPERTY,
    property = CutscenePropertyData(
        type = type.id,
        rotationMode = CutsceneEnums.nameOf((type as? RotationPropertyType)?.mode ?: RotationMode.EULER),
        visible = isVisible,
        locked = isLocked,
        curves = curves.map { it.toData() },
    ),
)

private fun ChannelCurve.toData() = CutsceneCurveData(
    channel = name,
    visible = isVisible,
    keyframes = keyframes.map { key ->
        CutsceneKeyData(
            time = key.time,
            value = key.value,
            interpolation = CutsceneEnums.nameOf(key.interpolation),
            handles = CutsceneEnums.nameOf(key.handleMode),
            inTime = key.incoming.time,
            inValue = key.incoming.value,
            outTime = key.outgoing.time,
            outValue = key.outgoing.value,
        )
    },
)
