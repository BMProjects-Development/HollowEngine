package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorIcon
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

private const val LANG = "hollowengine.component.hollowengine.vfx"

/**
 * An effect that plays on the entity for as long as it is in view, like a torch's flame on a lamp.
 * It turns and scales with the entity; a script that wants it on and off adds and removes the component.
 */
@Registerable
@Syncable
@Serializable
@EditorIcon("hollowengine:textures/gui/icons/files/effect.svg")
@SerialName("hollowengine:vfx")
data class VfxComponent(
    @EditorAsset(".vfx")
    val effect: String = "",
    @EditorDescription("$LANG.offset.hint")
    val offset: Vec3f = Vec3f.ZERO,
)

/** The effect component of this entity, read without creating any attachments for it. */
val Entity.vfxComponent: VfxComponent?
    get() = AttachmentRegistry.attachmentsOrNull(this)?.components?.readOnly?.values?.firstNotNullOfOrNull { it as? VfxComponent }
