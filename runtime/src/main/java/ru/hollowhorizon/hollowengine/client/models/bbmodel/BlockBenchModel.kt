package ru.hollowhorizon.hollowengine.client.models.bbmodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class BlockBenchModel(
    val meta: BBMeta = BBMeta(),
    val resolution: Resolution = Resolution(),
    val elements: List<BBElement> = emptyList(),
    val groups: List<BBElement> = emptyList(),
    @SerialName("outliner") val nodes: List<JsonElement> = emptyList(),
    val textures: List<BBTexture> = emptyList(),
    val animations: List<BBAnimation> = emptyList(),
) {
    @Serializable
    data class Resolution(val width: Int = 16, val height: Int = 16)

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        fun parse(source: String): BlockBenchModel = JSON.decodeFromString(source)
    }
}

@Serializable
data class BBMeta(
    @SerialName("format_version") val version: String = "4.0",
    @SerialName("model_format") val format: String = "free",
    @SerialName("box_uv") val boxUv: Boolean = false,
)

/** Shared outliner properties; faces are named directions for cubes and arbitrary ids for meshes. */
@Serializable
data class BBElement(
    val uuid: String,
    val name: String = "",
    val type: String = "group",
    val origin: List<Float> = listOf(0f, 0f, 0f),
    val position: List<Float>? = null,
    val rotation: List<Float> = listOf(0f, 0f, 0f),
    val scale: List<Float> = listOf(1f, 1f, 1f),
    val from: List<Float>? = null,
    val to: List<Float>? = null,
    val inflate: Float = 0f,
    val export: Boolean = true,
    val visibility: Boolean = true,
    @SerialName("box_uv") val boxUv: Boolean? = null,
    @SerialName("mirror_uv") val mirrorUv: Boolean = false,
    @SerialName("uv_offset") val uvOffset: List<Float> = listOf(0f, 0f),
    val faces: Map<String, BBFace> = emptyMap(),
    val vertices: Map<String, List<Float>> = emptyMap(),
    val shading: String = "flat",
    @SerialName("vertex_weights") val vertexWeights: Map<String, Float> = emptyMap(),
    @SerialName("ik_target") val ikTarget: String = "",
    @SerialName("ik_source") val ikSource: String = "",
    @SerialName("ik_pole") val ikPole: String = "",
    @SerialName("lock_ik_target_rotation") val lockIkTargetRotation: Boolean = false,
)

@Serializable
data class BBFace(
    val uv: JsonElement = JsonNull,
    val texture: JsonPrimitive? = JsonPrimitive(-1),
    val rotation: Int = 0,
    val vertices: List<String> = emptyList(),
)

@Serializable
data class BBTexture(
    val name: String = "texture",
    val uuid: String = "",
    val id: String = "",
    val source: String = "",
    @SerialName("relative_path") val relativePath: String = "",
    val path: String = "",
    val namespace: String = "",
    val folder: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("uv_width") val uvWidth: Int = 0,
    @SerialName("uv_height") val uvHeight: Int = 0,
    val fps: Float = 7f,
    @SerialName("frame_time") val frameTime: Int = 1,
    @SerialName("frame_order_type") val frameOrderType: String = "loop",
    @SerialName("frame_order") val frameOrder: String = "",
    @SerialName("frame_interpolate") val frameInterpolate: Boolean = false,
    @SerialName("render_mode") val renderMode: String = "default",
    @SerialName("render_sides") val renderSides: String = "auto",
    @SerialName("force_translucent") val forceTranslucent: Boolean = false,
)

@Serializable
data class BBAnimation(
    val name: String,
    val length: Float = 0f,
    val loop: String = "once",
    val animators: Map<String, BBAnimator> = emptyMap(),
)

@Serializable
data class BBAnimator(
    val type: String = "bone",
    @SerialName("rotation_global") val rotationGlobal: Boolean = false,
    @SerialName("quaternion_interpolation") val quaternionInterpolation: Boolean = false,
    val keyframes: List<BBKeyframe> = emptyList(),
)

@Serializable
data class BBKeyframe(
    val channel: String,
    val time: Float,
    val interpolation: String = "linear",
    @SerialName("data_points") val dataPoints: List<JsonObject> = emptyList(),
    @SerialName("bezier_left_time") val bezierLeftTime: List<Float> = listOf(-0.1f, -0.1f, -0.1f),
    @SerialName("bezier_right_time") val bezierRightTime: List<Float> = listOf(0.1f, 0.1f, 0.1f),
    @SerialName("bezier_left_value") val bezierLeftValue: List<Float> = listOf(0f, 0f, 0f),
    @SerialName("bezier_right_value") val bezierRightValue: List<Float> = listOf(0f, 0f, 0f),
)
