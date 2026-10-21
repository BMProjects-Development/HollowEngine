package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.HandleMode
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyInterpolation
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.RotationMode

@Serializable
enum class CutsceneNodeKind {
    GROUP, PROPERTY,
}

@Serializable
data class CutsceneKeyData(
    val time: Float,
    val value: Float,
    val interpolation: String = CutsceneEnums.DEFAULT_INTERPOLATION,
    val handles: String = CutsceneEnums.DEFAULT_HANDLES,
    val inTime: Float = 0f,
    val inValue: Float = 0f,
    val outTime: Float = 0f,
    val outValue: Float = 0f,
)

@Serializable
data class CutsceneCurveData(
    val channel: String,
    val visible: Boolean = true,
    val keyframes: List<CutsceneKeyData> = emptyList(),
)

/**
 * One animated property: one curve per channel.
 */
@Serializable
data class CutscenePropertyData(
    val type: String,
    val rotationMode: String = CutsceneEnums.DEFAULT_ROTATION_MODE,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val curves: List<CutsceneCurveData> = emptyList(),
)

@Serializable
data class CutsceneNodeData(
    val id: String,
    val name: String,
    val kind: CutsceneNodeKind,
    val children: List<CutsceneNodeData> = emptyList(),
    val property: CutscenePropertyData? = null,
)

@Serializable
data class CutsceneData(
    val name: String = "New Cutscene",
    val duration: Float = 10f,
    val origin: CutsceneOrigin = CutsceneOrigin(),
    val nodes: List<CutsceneNodeData> = emptyList(),
)

object CutsceneEnums {
    const val DEFAULT_INTERPOLATION = "bezier"
    const val DEFAULT_HANDLES = "auto"
    const val DEFAULT_ROTATION_MODE = "euler"

    private val interpolations = mapOf(
        "constant" to KeyInterpolation.CONSTANT,
        "linear" to KeyInterpolation.LINEAR,
        DEFAULT_INTERPOLATION to KeyInterpolation.BEZIER,
    )

    private val handles = mapOf(
        DEFAULT_HANDLES to HandleMode.AUTO,
        "mirrored" to HandleMode.MIRRORED,
        "aligned" to HandleMode.ALIGNED,
        "free" to HandleMode.FREE,
    )

    private val rotationModes = mapOf(
        DEFAULT_ROTATION_MODE to RotationMode.EULER,
        "quaternion" to RotationMode.QUATERNION,
    )

    fun interpolation(name: String): KeyInterpolation = interpolations[name] ?: KeyInterpolation.BEZIER

    fun nameOf(value: KeyInterpolation): String = interpolations.entries.first { it.value == value }.key

    fun handleMode(name: String): HandleMode = handles[name] ?: HandleMode.AUTO

    fun nameOf(value: HandleMode): String = handles.entries.first { it.value == value }.key

    fun rotationMode(name: String): RotationMode = rotationModes[name] ?: RotationMode.EULER

    fun nameOf(value: RotationMode): String = rotationModes.entries.first { it.value == value }.key
}
