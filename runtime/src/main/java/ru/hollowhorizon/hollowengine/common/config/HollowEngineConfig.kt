package ru.hollowhorizon.hollowengine.common.config

import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoOrientation

@ConfigName("hollowengine")
object HollowEngineConfig : Config() {
    val debugMode by property(true)

    @PropertyComment("Enables editor button in top left corner")
    @PropertyName("edit_mode")
    var editMode by property(EditMode.ENABLED)

    @PropertyComment("Show a confirmation dialog before hiding the HollowEngine toolbar")
    @PropertyName("show_toolbar_hide_confirmation")
    var showToolbarHideConfirmation by property(true)

    @PropertyComment("Font size used by the Hollow IDE code editor")
    @PropertyName("ide_editor_font_size")
    @PropertyRange(6.0f, 36.0f)
    var ideEditorFontSize by property(12f)

    @PropertyComment("Font size used by the Hollow IDE console")
    @PropertyName("ide_console_font_size")
    @PropertyRange(6.0f, 36.0f)
    var ideConsoleFontSize by property(10f)

    @PropertyComment("Gui Scale used by the Hollow IDE code editor; 0 follows the game, fractions like 1.5 are allowed")
    @PropertyName("ide_gui_scale")
    @PropertyRange(0.0f, 6.0f)
    var ideGuiScale by property(3f)

    @PropertyComment("Turns the world into an editor: clicking something fills the inspector with it")
    @PropertyName("editor_mode")
    var editorMode by property(false)

    @PropertyComment("Draws the transform handles on scene nodes. Independent of editor mode; a development tool")
    @PropertyName("transform_gizmo_enabled")
    var gizmoEnabled by property(false)

    @PropertyComment("Gizmo editing modes shown together, separated by commas: TRANSLATE, ROTATE, SCALE")
    @PropertyName("transform_gizmo_modes")
    var gizmoModes by property(GizmoEditMode.TRANSLATE.name)

    @PropertyComment("Which axes the gizmo's handles follow: GLOBAL (the world's), LOCAL (the object's) or VIEW (the camera's)")
    @PropertyName("transform_gizmo_orientation")
    var gizmoOrientation by property(GizmoOrientation.GLOBAL.name)

    @PropertyComment("Whether the timeline snaps times to frames: keys, the playhead, blocks")
    @PropertyName("timeline_snap")
    var timelineSnap by property(true)

    @PropertyComment("Frames per second the timeline snaps times to")
    @PropertyName("timeline_snap_fps")
    @PropertyRange(1.0f, 240.0f)
    var timelineSnapFps by property(20)

    @PropertyComment("Whether nodes dragged in a graph land on its grid")
    @PropertyName("graph_snap_to_grid")
    var graphSnapToGrid by property(false)

    @PropertyComment(
        "Characters kept ready before a TrueType font is first drawn. Anything outside this still " +
                "renders, it just appears a frame or two after it is first met, so widen it only for " +
                "alphabets used constantly. Presets joined by '+': ascii, latin, latin-ext, cyrillic, " +
                "greek, hiragana, katakana, punctuation, none; or explicit ranges like U+4E00-U+4EFF. " +
                "A font-family may override this with its own '?charset=' argument."
    )
    @PropertyName("font_preload_charset")
    var fontPreloadCharset by property("latin+latin-ext+cyrillic+punctuation")

    @PropertyComment("Mods that be available inside scripting & compilation")
    @PropertyName("scripting_mods")
    var scriptingMods by list("hollowengine")
}

enum class EditMode {
    DISABLED, ENABLED, CHAT_ONLY
}
