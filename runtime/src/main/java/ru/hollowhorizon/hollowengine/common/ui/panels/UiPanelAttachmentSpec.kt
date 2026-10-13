package ru.hollowhorizon.hollowengine.common.ui.panels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorNotAnimated
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import ru.hollowhorizon.hollowengine.common.models.PlacedAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

private const val LANG = "hollowengine.gui.ui_panel"

/**
 * A panel declared in a `.ui.kts` script, hung on a bone or the whole model: a flat screen facing along +Z of its
 * place, [width] blocks across and as tall as the proportions of the panel make it.
 */
@Serializable
@SerialName("hollowengine:rig/ui_panel")
data class UiPanelAttachmentSpec(
    @EditorHidden
    override val id: String = "panel",
    @EditorName("$LANG.panel")
    @EditorDescription("$LANG.panel.hint")
    @EditorNotAnimated
    val panel: String = "",
    @EditorName("$LANG.offset")
    override val offset: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.rotation")
    override val rotation: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.scale")
    override val scale: Float = 1f,
    @EditorName("$LANG.width")
    @EditorRange(min = 0.05)
    val width: Float = 1f,
    @EditorName("$LANG.interactive")
    @EditorNotAnimated
    val interactive: Boolean = true,
    @EditorName("$LANG.reach")
    @EditorRange(min = 0.5)
    val reach: Float = 5f,
    @EditorName("$LANG.emissive")
    val emissive: Boolean = true,
) : RigAttachmentSpec(), PlacedAttachmentSpec {
    override fun withId(id: String) = copy(id = id)

    override fun placedAt(offset: Vec3f, rotation: Vec3f, scale: Float) = copy(offset = offset, rotation = rotation, scale = scale)

    /** The running panel takes every change in place. */
    override fun structure() = UiPanelAttachmentSpec(id)

    companion object {
        val TYPE = RigAttachmentType(
            "hollowengine:rig/ui_panel", UiPanelAttachmentSpec::class, serializer(),
            "hollowengine.gui.rig_editor.kind_ui_panel", { UiPanelAttachmentSpec(id = it) },
            allowedOnModel = true, editorColor = 0x5FB3E6,
        )
    }
}
