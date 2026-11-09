package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

/** Translation keys of the cutscene editor; the timeline's own are in `TimelineLang`. */
internal object CutsceneLang {
    private const val ROOT = "hollowengine.gui.ide.cutscene."

    const val SAVE_TITLE = ROOT + "dialog.save_title"
    const val LOAD_TITLE = ROOT + "dialog.load_title"
    const val NAME = ROOT + "dialog.name"
    const val NO_CUTSCENES = ROOT + "dialog.no_cutscenes"

    const val PREVIEW = ROOT + "section.preview"
    const val ORIGIN = ROOT + "section.origin"

    const val CAMERA_ON = ROOT + "preview.camera_on"
    const val CAMERA_OFF = ROOT + "preview.camera_off"
    const val PLAYING = ROOT + "preview.playing"
    const val PAUSED = ROOT + "preview.paused"
    const val CURRENT_TIME = ROOT + "preview.current_time"
    const val DURATION = ROOT + "preview.duration"

    const val ORIGIN_X = ROOT + "origin.x"
    const val ORIGIN_Y = ROOT + "origin.y"
    const val ORIGIN_Z = ROOT + "origin.z"
    const val ORIGIN_YAW = ROOT + "origin.yaw"
    const val ORIGIN_MOVE_HERE = ROOT + "origin.move_here"
    const val ORIGIN_REBASE = ROOT + "origin.rebase"

    const val WORLD = ROOT + "keyframe.world"
    const val WORLD_ROTATION = ROOT + "keyframe.world_rotation"
}
