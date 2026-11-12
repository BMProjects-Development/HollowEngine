package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

/** Translation keys of the cutscene editor; the timeline's own are in `TimelineLang`. */
internal object CutsceneLang {
    private const val ROOT = "hollowengine.gui.ide.cutscene."

    const val CREATE = ROOT + "create"
    const val CAMERA = ROOT + "camera"
    const val LOAD_FAILED = ROOT + "load_failed"
    const val INSERT_CAMERA_KEY = ROOT + "preview.insert_camera_key"
    const val PLAYER_VIEW = ROOT + "preview.player_view"
    const val RESET_VIEW = ROOT + "preview.reset_view"
    const val MODE = ROOT + "preview.mode"
    const val FOV = ROOT + "preview.fov"
    const val NO_WORLD = ROOT + "preview.no_world"
    const val ORIGIN = ROOT + "section.origin"

    const val ORIGIN_X = ROOT + "origin.x"
    const val ORIGIN_Y = ROOT + "origin.y"
    const val ORIGIN_Z = ROOT + "origin.z"
    const val ORIGIN_YAW = ROOT + "origin.yaw"
    const val ORIGIN_MOVE_HERE = ROOT + "origin.move_here"
    const val ORIGIN_REBASE = ROOT + "origin.rebase"

    const val WORLD = ROOT + "keyframe.world"
    const val WORLD_ROTATION = ROOT + "keyframe.world_rotation"
}
