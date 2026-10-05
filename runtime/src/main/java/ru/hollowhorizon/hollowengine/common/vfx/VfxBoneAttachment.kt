package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.models.PlacedAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * An effect attached to bone, authored in the rig editor.
 */
@Serializable
@SerialName("hollowengine:rig/vfx")
data class VfxBoneAttachmentSpec(
    @EditorHidden
    override val id: String = "vfx",
    @EditorName("hollowengine.gui.vfx.effect")
    @EditorAsset(".vfx")
    val effect: String = "",
    @EditorName("hollowengine.gui.vfx.spawn_offset")
    override val offset: Vec3f = Vec3f.ZERO,
    @EditorName("hollowengine.gui.vfx.rotation")
    override val rotation: Vec3f = Vec3f.ZERO,
    @EditorName("hollowengine.gui.vfx.scale")
    override val scale: Float = 1f,
    /** Whether it starts as soon as the model is drawn; otherwise a script starts it. */
    @EditorName("hollowengine.gui.vfx.auto_play")
    val autoPlay: Boolean = true,
    @EditorHidden
    val ownEffect: VfxEffect? = null,
) : RigAttachmentSpec(), PlacedAttachmentSpec {
    override fun withId(id: String) = copy(id = id)

    override fun placedAt(offset: Vec3f, rotation: Vec3f, scale: Float) = copy(offset = offset, rotation = rotation, scale = scale)

    /** The running effect takes every change in place. */
    override fun structure() = VfxBoneAttachmentSpec(id)
}
