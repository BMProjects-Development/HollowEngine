package ru.hollowhorizon.hollowengine.common.decals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
import ru.hollowhorizon.hollowengine.common.models.PlacedAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** How a decal casts its picture onto what is under it. */
@Serializable
enum class DecalProjection {
    /** Straight down through a box: a picture laid on whatever the box holds, graffiti or a puddle. */
    BOX,

    /** Out of a point, widening with distance as a projector's light does: a slide or a film on a wall. */
    PROJECTOR,
}

/** How a decal mixes with the surface it lies on. */
@Serializable
enum class DecalBlend { NORMAL, ADDITIVE, MULTIPLY }

private const val LANG = "hollowengine.gui.decal"

/**
 * A picture projected onto the surfaces in front of it, along its own down axis: the box or the cone of a
 * projector says what it reaches, and everything in there wears the picture, whatever it is.
 */
@Serializable
data class DecalSpec(
    /** A texture, or a video when the Media addon can play one. */
    @EditorName("$LANG.texture")
    @EditorDescription("$LANG.texture.hint")
    @EditorAsset(".png", ".mp4", ".webm", ".mkv", ".mov", ".avi")
    val texture: String = "",
    @EditorName("$LANG.projection")
    @EditorDescription("$LANG.projection.hint")
    val projection: DecalProjection = DecalProjection.BOX,
    /** Across the picture, in blocks; a projector reads width over length as the shape of its picture. */
    @EditorName("$LANG.width")
    @EditorRange(min = 0.01)
    val width: Float = 1f,
    /** Along the picture, in blocks; null keeps the proportions of the picture. */
    @EditorName("$LANG.length")
    @EditorDescription("$LANG.length.hint")
    @EditorDefault("1.0")
    @EditorRange(min = 0.01)
    val length: Float? = null,
    /** How far down from its place the decal reaches, in blocks, a projector included; null reaches the surface below. */
    @EditorName("$LANG.reach")
    @EditorDescription("$LANG.reach.hint")
    @EditorDefault("1.0")
    @EditorRange(min = 0.01)
    val reach: Float? = null,
    /** How wide a projector opens along the length of the picture, in degrees. */
    @EditorName("$LANG.field_of_view")
    @EditorRange(1.0, 170.0)
    val fieldOfView: Float = 45f,
    @EditorName("$LANG.color")
    @EditorDescription("$LANG.color.hint")
    val color: Vec3f = Vec3f.ONES,
    @EditorName("$LANG.opacity")
    @EditorRange(0.0, 1.0, slider = true)
    val opacity: Float = 1f,
    /** How far a surface may turn from facing the decal, in degrees, before the picture fades off it. */
    @EditorName("$LANG.angle_fade")
    @EditorDescription("$LANG.angle_fade.hint")
    @EditorRange(0.0, 90.0)
    val angleFade: Float = 70f,
    @EditorName("$LANG.blend")
    val blend: DecalBlend = DecalBlend.NORMAL,
    /** Whether the picture shines on its own rather than being lit by the world where it lies. */
    @EditorName("$LANG.emissive")
    val emissive: Boolean = false,
) {
    val isVideo: Boolean get() = VideoExtensions.any { texture.endsWith(it, ignoreCase = true) }

    companion object {
        val VideoExtensions = listOf(".mp4", ".webm", ".mkv", ".mov", ".avi")
    }
}

/** A decal hung on a bone or the whole model, placed by hand; it projects along the down axis of its place. */
@Serializable
@SerialName("hollowengine:rig/decal")
data class DecalAttachmentSpec(
    @EditorHidden
    override val id: String = "decal",
    @EditorName("$LANG.offset")
    override val offset: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.rotation")
    override val rotation: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.scale")
    override val scale: Float = 1f,
    @EditorName("$LANG.decal")
    val decal: DecalSpec = DecalSpec(),
) : RigAttachmentSpec(), PlacedAttachmentSpec {
    override fun withId(id: String) = copy(id = id)

    override fun placedAt(offset: Vec3f, rotation: Vec3f, scale: Float) = copy(offset = offset, rotation = rotation, scale = scale)

    /** The running decal takes every change in place. */
    override fun structure() = DecalAttachmentSpec(id)

    companion object {
        val TYPE = RigAttachmentType(
            "hollowengine:rig/decal", DecalAttachmentSpec::class, serializer(),
            "hollowengine.gui.rig_editor.kind_decal", { DecalAttachmentSpec(id = it) },
            allowedOnModel = true, editorColor = 0xC77DDB,
        )
    }
}
