package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize

/**
 * Reads cutscenes written before the shape they have now.
 *
 * Version 1 stored one keyframe per *vector*, in absolute world coordinates, with a named easing.
 * Version 2 added an origin and Bezier handles but kept the vector keys.
 * Version 3 had properties whose curves lived inside a stack of blended layers.
 * All of them end up in the current shape: coordinates relative to an origin, one key per channel, and curves directly on the property.
 */
object CutsceneMigrations {
    const val CURRENT_VERSION = 4

    fun read(payload: Tag, version: Int): CutsceneData = when {
        version >= CURRENT_VERSION -> NBTFormat.deserialize(payload)
        version == 3 -> flattenLayers(NBTFormat.deserialize<LayeredCutsceneData, Tag>(payload))
        else -> convert(NBTFormat.deserialize<LegacyCutsceneData, Tag>(payload), version)
    }

    /**
     * Drops the layer stack of a version 3 cutscene, keeping the first layer that did anything.
     */
    private fun flattenLayers(data: LayeredCutsceneData): CutsceneData = CutsceneData(
        name = data.name,
        duration = data.duration,
        origin = data.origin,
        nodes = data.nodes.map { it.flatten() },
    )

    private fun LayeredNodeData.flatten(): CutsceneNodeData = CutsceneNodeData(
        id = id,
        name = name,
        kind = kind,
        children = children.map { it.flatten() },
        property = property?.let { stored ->
            val layer = stored.layers.firstOrNull { it.visible && it.weight != 0f } ?: stored.layers.firstOrNull()
            if (stored.layers.size > 1) {
                HollowEngine.LOGGER.warn(
                    "Cutscene property '{}' had {} layers; keeping '{}' and dropping the rest",
                    name,
                    stored.layers.size,
                    layer?.name,
                )
            }
            CutscenePropertyData(
                type = stored.type,
                rotationMode = stored.rotationMode,
                visible = layer?.visible ?: true,
                locked = layer?.locked ?: false,
                curves = layer?.curves.orEmpty(),
            )
        },
    )

    internal fun convert(legacy: LegacyCutsceneData, version: Int): CutsceneData = legacy.toCutscene(version)

    private fun LegacyCutsceneData.toCutscene(version: Int): CutsceneData {
        val tracks = flattenTracks(nodes)
        val origin = if (version >= 2 && anchor != null) {
            CutsceneOrigin(anchor.originX, anchor.originY, anchor.originZ, anchor.yaw)
        } else {
            val first = tracks[LegacyTracks.POSITION]?.minByOrNull { it.time }
            val position = (first?.value as? LegacyKeyframeSnapshot.Vec3fSnapshot)?.vector ?: Vec3f.ZERO
            CutsceneOrigin(position.x, position.y, position.z)
        }
        val frame = origin.frame

        val toLocalPosition: (Vec3f) -> Vec3f = if (version >= 2) ({ it }) else frame::toLocal
        val toLocalRotation: (Vec3f) -> Vec3f =
            if (version >= 2) ({ it }) else ({ Vec3f(it.x, it.y - origin.yaw, it.z) })

        return CutsceneData(
            name = name,
            duration = duration,
            origin = origin,
            nodes = listOf(
                CutsceneNodeData(
                    id = CameraRig.CAMERA_ID,
                    name = "Camera",
                    kind = CutsceneNodeKind.GROUP,
                    children = listOf(
                        CutsceneNodeData(
                            id = CameraRig.TRANSFORM_ID,
                            name = "Transform",
                            kind = CutsceneNodeKind.GROUP,
                            children = listOf(
                                vectorProperty(
                                    id = CameraRig.TRANSLATION_ID,
                                    name = "Translation",
                                    type = TranslationPropertyType.ID,
                                    channelNames = listOf("X", "Y", "Z"),
                                    keys = tracks[LegacyTracks.POSITION].orEmpty(),
                                    convert = toLocalPosition,
                                ),
                                vectorProperty(
                                    id = CameraRig.ROTATION_ID,
                                    name = "Rotation",
                                    type = RotationPropertyType.ID,
                                    channelNames = listOf("Pitch", "Yaw", "Roll"),
                                    keys = tracks[LegacyTracks.ROTATION].orEmpty(),
                                    convert = toLocalRotation,
                                ),
                            ),
                        ),
                        CutsceneNodeData(
                            id = CameraRig.LENS_ID,
                            name = "Lens",
                            kind = CutsceneNodeKind.GROUP,
                            children = listOf(
                                floatProperty(CameraRig.FOV_ID, "FOV", tracks[LegacyTracks.FOV].orEmpty()),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun vectorProperty(
        id: String,
        name: String,
        type: String,
        channelNames: List<String>,
        keys: List<LegacyKeyData>,
        convert: (Vec3f) -> Vec3f,
    ): CutsceneNodeData {
        val vectors = keys.mapNotNull { key ->
            val vector = (key.value as? LegacyKeyframeSnapshot.Vec3fSnapshot)?.vector ?: return@mapNotNull null
            key to convert(vector)
        }.sortedBy { it.first.time }
        val curves = channelNames.mapIndexed { channel, channelName ->
            buildCurve(
                channel = channelName,
                keys = vectors.map { (key, vector) ->
                    LegacySample(key.time, vector.component(channel), LegacyEasings.presetFor(key.easing))
                },
            )
        }
        return propertyNode(id, name, type, curves)
    }

    private fun floatProperty(id: String, name: String, keys: List<LegacyKeyData>): CutsceneNodeData {
        val samples = keys.mapNotNull { key ->
            val value = (key.value as? LegacyKeyframeSnapshot.FloatSnapshot)?.value ?: return@mapNotNull null
            LegacySample(key.time, value, LegacyEasings.presetFor(key.easing))
        }.sortedBy { it.time }
        return propertyNode(id, name, FloatPropertyType.ID, listOf(buildCurve("FOV", samples)))
    }

    private fun buildCurve(channel: String, keys: List<LegacySample>): CutsceneCurveData {
        val result = keys.map { sample ->
            CutsceneKeyData(
                time = sample.time,
                value = sample.value,
                interpolation = CutsceneEnums.nameOf(sample.preset?.interpolation ?: KeyInterpolation.BEZIER),
                handles = "free",
            )
        }.toMutableList()

        keys.forEachIndexed { index, sample ->
            val preset = sample.preset ?: return@forEachIndexed
            if (preset.interpolation != KeyInterpolation.BEZIER) return@forEachIndexed
            val next = keys.getOrNull(index + 1) ?: return@forEachIndexed
            val span = next.time - sample.time
            val delta = next.value - sample.value
            result[index] = result[index].copy(
                outTime = span * preset.outX,
                outValue = delta * preset.outY,
            )
            result[index + 1] = result[index + 1].copy(
                inTime = -span * (1f - preset.inX),
                inValue = -delta * (1f - preset.inY),
            )
        }
        return CutsceneCurveData(channel, keyframes = result)
    }

    private fun propertyNode(id: String, name: String, type: String, curves: List<CutsceneCurveData>) =
        CutsceneNodeData(
            id = id,
            name = name,
            kind = CutsceneNodeKind.PROPERTY,
            property = CutscenePropertyData(type = type, curves = curves),
        )

    private fun Vec3f.component(index: Int): Float = when (index) {
        0 -> x
        1 -> y
        else -> z
    }

    private fun flattenTracks(nodes: List<LegacyNodeData>): Map<String, List<LegacyKeyData>> {
        val result = mutableMapOf<String, List<LegacyKeyData>>()
        fun walk(list: List<LegacyNodeData>) {
            list.forEach { node ->
                node.track?.let { result[it.type] = it.keyframes }
                walk(node.children)
            }
        }
        walk(nodes)
        return result
    }

    private class LegacySample(val time: Float, val value: Float, val preset: CurvePreset?)
}

private object LegacyTracks {
    const val POSITION = "hollowengine:camera.position"
    const val ROTATION = "hollowengine:camera.rotation"
    const val FOV = "hollowengine:camera.fov"
}

private object LegacyEasings {
    private val presets = mapOf(
        "linear" to "linear",
        "smooth" to "smooth",
        "easeInSine" to "sineIn", "easeOutSine" to "sineOut", "easeInOutSine" to "sineInOut",
        "easeInQuad" to "quadIn", "easeOutQuad" to "quadOut", "easeInOutQuad" to "quadInOut",
        "easeInCubic" to "cubicIn", "easeOutCubic" to "cubicOut", "easeInOutCubic" to "cubicInOut",
        "easeInQuart" to "quartIn", "easeOutQuart" to "quartOut", "easeInOutQuart" to "quartInOut",
        "easeInQuint" to "quintIn", "easeOutQuint" to "quintOut", "easeInOutQuint" to "quintInOut",
        "easeInExpo" to "expoIn", "easeOutExpo" to "expoOut", "easeInOutExpo" to "expoInOut",
        "easeInCirc" to "circIn", "easeOutCirc" to "circOut", "easeInOutCirc" to "circInOut",
        "easeInBack" to "backIn", "easeOutBack" to "backOut", "easeInOutBack" to "backInOut",
    )

    fun presetFor(easing: String): CurvePreset? = CurvePresets.byId(presets[easing] ?: "smooth")
}

/** Version 3: properties whose curves lived inside a stack of blended layers. */
@Serializable
internal data class LayeredLayerData(
    val name: String,
    val blend: String = "override",
    val weight: Float = 1f,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val curves: List<CutsceneCurveData> = emptyList(),
)

@Serializable
internal data class LayeredPropertyData(
    val type: String,
    val rotationMode: String = CutsceneEnums.DEFAULT_ROTATION_MODE,
    val layers: List<LayeredLayerData> = emptyList(),
)

@Serializable
internal data class LayeredNodeData(
    val id: String,
    val name: String,
    val kind: CutsceneNodeKind,
    val children: List<LayeredNodeData> = emptyList(),
    val property: LayeredPropertyData? = null,
)

@Serializable
internal data class LayeredCutsceneData(
    val name: String = "New Cutscene",
    val duration: Float = 10f,
    val origin: CutsceneOrigin = CutsceneOrigin(),
    val nodes: List<LayeredNodeData> = emptyList(),
)

@Serializable
internal data class LegacyAnchorData(
    val originX: Float = 0f,
    val originY: Float = 0f,
    val originZ: Float = 0f,
    val yaw: Float = 0f,
)

@Serializable
internal sealed class LegacyKeyframeSnapshot {
    @Serializable
    @SerialName("ru.hollowhorizon.hollowengine.client.gui.timeline.cutscene.KeyframeSnapshot.FloatSnapshot")
    class FloatSnapshot(val value: Float) : LegacyKeyframeSnapshot()

    @Serializable
    @SerialName("ru.hollowhorizon.hollowengine.client.gui.timeline.cutscene.KeyframeSnapshot.Vec3fSnapshot")
    class Vec3fSnapshot(val x: Float, val y: Float, val z: Float) : LegacyKeyframeSnapshot() {
        val vector get() = Vec3f(x, y, z)
    }
}

@Serializable
internal data class LegacyKeyData(
    val time: Float,
    val value: LegacyKeyframeSnapshot,
    val easing: String = "linear",
)

@Serializable
internal data class LegacyTrackData(
    val type: String,
    val valueType: String = "",
    val keyframes: List<LegacyKeyData> = emptyList(),
)

@Serializable
internal data class LegacyNodeData(
    val id: String,
    val name: String,
    val children: List<LegacyNodeData> = emptyList(),
    val track: LegacyTrackData? = null,
)

@Serializable
internal data class LegacyCutsceneData(
    val name: String = "New Cutscene",
    val duration: Float = 10f,
    val anchor: LegacyAnchorData? = null,
    val nodes: List<LegacyNodeData> = emptyList(),
)
