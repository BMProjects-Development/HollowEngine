package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange

private const val LANG = "hollowengine.component.hollowengine.entity.body"

@Registerable
@Syncable
@Serializable
@SerialName("hollowengine:entity/body")
data class BodyComponent(
    @EditorDescription("$LANG.pushable.hint")
    val pushable: Boolean = true,
    @EditorDescription("$LANG.pushesOthers.hint")
    val pushesOthers: Boolean = true,
    @EditorDescription("$LANG.solid.hint")
    val solid: Boolean = false,
    @EditorDescription("$LANG.width.hint")
    @EditorRange(min = 0.0)
    val width: Float = 0f,
    @EditorDescription("$LANG.height.hint")
    @EditorRange(min = 0.0)
    val height: Float = 0f,
) {
    val hasSize: Boolean get() = width > 0f && height > 0f

    /** [vanilla] resized to this body, the eyes kept at the same share of the height. */
    fun resize(vanilla: EntityDimensions): EntityDimensions {
        if (!hasSize) return vanilla
        val eyes = if (vanilla.height() > 0f) vanilla.eyeHeight() / vanilla.height() else DEFAULT_EYE_SHARE
        return EntityDimensions.scalable(width, height).withEyeHeight(height * eyes)
    }

    companion object {
        const val DEFAULT_EYE_SHARE = 0.85f
    }
}

/** The body component of this entity, read without creating any attachments for it. */
val Entity.bodyComponent: BodyComponent?
    get() = AttachmentRegistry.attachmentsOrNull(this)?.components?.readOnly?.values?.firstNotNullOfOrNull { it as? BodyComponent }
