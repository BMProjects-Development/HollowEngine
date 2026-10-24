package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoOrientation
import ru.hollowhorizon.hollowengine.client.editor.GizmoSettings
import ru.hollowhorizon.hollowengine.client.editor.GizmoSession
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyAction
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.inputTransparent
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.utils.lang

/** A manipulator of the gizmo as a toolbar shows it: its icon, its name and the key that starts it. */
internal class GizmoModeButton(val mode: GizmoEditMode, val icon: String, val key: HollowKeyAction) {
    val label: String get() = key.hint("hollowengine.gui.ide.gizmo.${mode.name.lowercase()}".lang)
}

internal val GizmoModeButtons = listOf(
    GizmoModeButton(GizmoEditMode.TRANSLATE, "hollowengine:textures/gui/icons/gizmo_translate.svg", HollowKeys.GRAB),
    GizmoModeButton(GizmoEditMode.ROTATE, "hollowengine:textures/gui/icons/gizmo_rotate.svg", HollowKeys.ROTATE),
    GizmoModeButton(GizmoEditMode.SCALE, "hollowengine:textures/gui/icons/gizmo_scale.svg", HollowKeys.SCALE),
)

private val GizmoOrientation.icon: String
    get() = when (this) {
        GizmoOrientation.GLOBAL -> "hollowengine:textures/gui/icons/global.svg"
        GizmoOrientation.LOCAL -> "hollowengine:textures/gui/icons/box.svg"
        GizmoOrientation.VIEW -> "hollowengine:textures/gui/icons/eye.svg"
    }

/** What the orientation button says: which axes the handles follow, and that a click moves on. */
internal val gizmoOrientationTooltip: String
    get() {
        val name = "hollowengine.gui.ide.gizmo.orientation.${GizmoSettings.orientation.name.lowercase()}".lang
        return HollowKeys.ORIENTATION.hint("hollowengine.gui.ide.gizmo.orientation".lang(name))
    }

/**
 * The buttons of the gizmo in a preview's toolbar: the manipulators it shows and the axes they follow. Every preview
 * and the world share them, so switching one switches all.
 */
@Composable
internal fun GizmoToolbarButtons(id: String, iconSize: Float = 12f) {
    GizmoModeButtons.forEach { button ->
        IconButton(
            button.icon, button.label, id = "$id-mode-${button.mode.name.lowercase()}",
            active = button.mode in GizmoSettings.modes, iconSize = iconSize,
        ) {
            GizmoSettings.toggle(button.mode)
        }
    }
    GizmoOrientationButton(iconSize, id = "$id-orientation")
}

@Composable
internal fun GizmoRotationHint(session: GizmoSession, modifier: Modifier = Modifier) {
    val transform = session.keyboard ?: return
    if (transform.mode != GizmoEditMode.ROTATE || transform.axis != null) return
    val mode = transform.rotationMode.name.lowercase()
    Text(
        HollowKeys.ROTATE.hint("hollowengine.gui.ide.gizmo.rotation.$mode".lang),
        tags = listOf("viewport-transform-hint"),
        modifier = modifier.inputTransparent(),
    )
}

@Composable
internal fun GizmoOrientationButton(iconSize: Float = 12f, id: String? = null, tags: List<String> = emptyList()) =
    IconButton(GizmoSettings.orientation.icon, gizmoOrientationTooltip, id = id, iconSize = iconSize, tags = tags) {
        GizmoSettings.nextOrientation()
    }
