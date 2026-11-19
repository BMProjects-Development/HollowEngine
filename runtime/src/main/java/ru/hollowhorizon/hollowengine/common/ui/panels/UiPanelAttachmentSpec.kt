package ru.hollowhorizon.hollowengine.common.ui.panels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
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
    val id: String = "panel",
    @EditorName("$LANG.panel")
    @EditorDescription("$LANG.panel.hint")
    @EditorNotAnimated
    val panel: String = "",
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
) {
    /** The running panel takes every change in place. */
    companion object {
        val TYPE = RigAttachmentType(
            "hollowengine:rig/ui_panel", UiPanelAttachmentSpec::class, UiPanelAttachmentSpec::id, serializer(),
            "hollowengine.gui.rig_editor.kind_ui_panel", { UiPanelAttachmentSpec(id = it) },
            allowedOnModel = true, editorColor = 0x5FB3E6,
        )
    }
}
