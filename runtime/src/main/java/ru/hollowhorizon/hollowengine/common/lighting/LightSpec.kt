package ru.hollowhorizon.hollowengine.common.lighting

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import ru.hollowhorizon.hollowengine.common.models.PlacedAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

private const val LANG = "hollowengine.gui.lighting"

@Serializable
enum class LightKind {
    @EditorName("$LANG.point") POINT,
    @EditorName("$LANG.spot") SPOT,
}

@Serializable
enum class LightTarget {
    @EditorName("$LANG.all") ALL,
    @EditorName("$LANG.entities") ENTITIES,
    @EditorName("$LANG.blocks") BLOCKS,
}

/** Linear RGB radiance. A spotlight points down its placement's local -Z axis. */
@Serializable
data class LightSpec(
    @EditorName("$LANG.enabled") val enabled: Boolean = true,
    @EditorName("$LANG.kind") val kind: LightKind = LightKind.POINT,
    @EditorName("$LANG.color") val color: Vec3f = Vec3f.ONES,
    @EditorName("$LANG.intensity") @EditorRange(min = 0.0) val intensity: Float = 4f,
    @EditorName("$LANG.radius") @EditorRange(min = 0.01) val radius: Float = 16f,
    @EditorName("$LANG.inner_angle") @EditorRange(0.0, 179.0) val innerAngle: Float = 25f,
    @EditorName("$LANG.outer_angle") @EditorRange(0.01, 179.0) val outerAngle: Float = 45f,
    @EditorName("$LANG.source_size") @EditorRange(min = 0.001) val sourceSize: Float = 0.1f,
    @EditorName("$LANG.target") val target: LightTarget = LightTarget.ALL,
    @EditorName("$LANG.shadows") val shadows: Boolean = true,
    @EditorName("$LANG.anisotropy") @EditorRange(-0.95, 0.95) val anisotropy: Float = 0.4f,
    @EditorName("$LANG.density") @EditorRange(min = 0.0) val density: Float = 0.2f,
    @EditorName("$LANG.beam") @EditorRange(min = 0.0) val beam: Float = 0f,
    @EditorName("$LANG.cookie") @EditorAsset(".png", ".jpg", ".tga", ".bmp") val cookie: String = "",
    @EditorName("$LANG.cookie_rotation") val cookieRotation: Float = 0f,
    @EditorName("$LANG.cookie_scale") @EditorRange(min = 0.001) val cookieScale: Float = 1f,
    @EditorName("$LANG.cookie_invert") val cookieInvert: Boolean = false,
) {
    init {
        require(color.x.isFinite() && color.y.isFinite() && color.z.isFinite()) { "Light color must be finite" }
        require(color.x >= 0f && color.y >= 0f && color.z >= 0f) { "Light color cannot be negative" }
        require(intensity.isFinite() && intensity >= 0f) { "Light intensity must be finite and nonnegative" }
        require(radius.isFinite() && radius > 0f) { "Light radius must be finite and positive" }
        require(innerAngle.isFinite() && innerAngle in 0f..179f) { "Invalid inner cone angle" }
        require(outerAngle.isFinite() && outerAngle > 0f && outerAngle <= 179f) { "Invalid outer cone angle" }
        require(sourceSize.isFinite() && sourceSize > 0f) { "Light source size must be finite and positive" }
        require(anisotropy.isFinite() && anisotropy in -0.95f..0.95f) { "Invalid volumetric anisotropy" }
        require(density.isFinite() && density >= 0f && beam.isFinite() && beam >= 0f) { "Invalid volumetric density or beam strength" }
        require(cookieRotation.isFinite() && cookieScale.isFinite() && cookieScale > 0f) { "Invalid cookie placement" }
    }
}

/** The engine persists, synchronizes and inspects this just like its built-in entity components. */
@Serializable
@Registerable
@Syncable
@SerialName("hollowengine-lighting:light")
data class LightComponent(
    @EditorName("$LANG.light") val light: LightSpec = LightSpec(),
    @EditorName("$LANG.offset") val offset: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.rotation")
    @EditorDescription("$LANG.rotation.hint")
    val rotation: Vec3f = Vec3f.ZERO,
)

@Serializable
@SerialName("hollowengine-lighting:rig/light")
data class LightAttachmentSpec(
    @EditorHidden override val id: String = "light",
    @EditorName("$LANG.offset") override val offset: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.rotation") override val rotation: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.scale") @EditorRange(min = 0.001) override val scale: Float = 1f,
    @EditorName("$LANG.light") val light: LightSpec = LightSpec(),
) : RigAttachmentSpec(), PlacedAttachmentSpec {
    override val affectsWorld: Boolean get() = true

    override fun withId(id: String) = copy(id = id)
    override fun placedAt(offset: Vec3f, rotation: Vec3f, scale: Float) = copy(offset = offset, rotation = rotation, scale = scale)
    override fun structure() = LightAttachmentSpec(id = id)

    companion object {
        val TYPE = RigAttachmentType(
            "hollowengine-lighting:rig/light", LightAttachmentSpec::class, serializer(),
            "$LANG.light", { LightAttachmentSpec(id = it) }, allowedOnModel = true, editorColor = 0xFFD46B,
        )
    }
}
