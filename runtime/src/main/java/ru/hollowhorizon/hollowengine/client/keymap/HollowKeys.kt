package ru.hollowhorizon.hollowengine.client.keymap

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyChord.Companion.ALT
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyChord.Companion.CONTROL
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyChord.Companion.SHIFT
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * Engine's own actions, one place for all of them. Editing keys of a text field (caret moves, Backspace, Tab)
 * and the Enter and Escape of a dialog are not actions: they mean same everywhere.
 */
object HollowKeys {
    val GENERAL = HollowKeyContext("general")
    val WINDOWS = HollowKeyContext("windows")
    val CODE = HollowKeyContext("code")
    val PROJECT = HollowKeyContext("project")
    val TIMELINE = HollowKeyContext("timeline")
    val VIEWPORT = HollowKeyContext("viewport")
    val GRAPH = HollowKeyContext("graph")

    // General
    val SEARCH = action("general.search", GENERAL, key(GLFW.GLFW_KEY_N, CONTROL))
    val SAVE = action("general.save", GENERAL, key(GLFW.GLFW_KEY_S, CONTROL))
    val CLOSE_TAB = action("general.close_tab", GENERAL, key(GLFW.GLFW_KEY_W, CONTROL))
    val UNDO = action("general.undo", GENERAL, key(GLFW.GLFW_KEY_Z, CONTROL))
    val REDO = action("general.redo", GENERAL, key(GLFW.GLFW_KEY_Y, CONTROL), key(GLFW.GLFW_KEY_Z, CONTROL or SHIFT))
    val COPY = action("general.copy", GENERAL, key(GLFW.GLFW_KEY_C, CONTROL))
    val CUT = action("general.cut", GENERAL, key(GLFW.GLFW_KEY_X, CONTROL))
    val PASTE = action("general.paste", GENERAL, key(GLFW.GLFW_KEY_V, CONTROL))
    val DUPLICATE = action("general.duplicate", GENERAL, key(GLFW.GLFW_KEY_D, CONTROL))
    val DELETE = action("general.delete", GENERAL, key(GLFW.GLFW_KEY_DELETE))
    val SELECT_ALL = action("general.select_all", GENERAL, key(GLFW.GLFW_KEY_A, CONTROL))
    val RENAME = action("general.rename", GENERAL, key(GLFW.GLFW_KEY_F2))
    val FIND = action("general.find", GENERAL, key(GLFW.GLFW_KEY_F, CONTROL))
    val MOVE_UP = action("general.move_up", GENERAL, key(GLFW.GLFW_KEY_UP, ALT))
    val MOVE_DOWN = action("general.move_down", GENERAL, key(GLFW.GLFW_KEY_DOWN, ALT))
    val FRAME = action("general.frame", GENERAL, key(GLFW.GLFW_KEY_F))
    val KEYMAP = action("general.keymap", GENERAL)

    // Sidebars
    val STRIPE_LEFT = (1..9).map { index ->
        action("windows.stripe_left_$index", WINDOWS, key(GLFW.GLFW_KEY_0 + index, ALT)) {
            "hollowengine.gui.keymap.action.windows.stripe_left".lang(index)
        }
    }
    val STRIPE_RIGHT = (1..9).map { index ->
        action("windows.stripe_right_$index", WINDOWS, key(GLFW.GLFW_KEY_0 + index, ALT or SHIFT)) {
            "hollowengine.gui.keymap.action.windows.stripe_right".lang(index)
        }
    }

    // Code Editor
    val REPLACE = action("code.replace", CODE, key(GLFW.GLFW_KEY_R, CONTROL))
    val NEXT_MATCH = action("code.next_match", CODE, key(GLFW.GLFW_KEY_F3))
    val PREVIOUS_MATCH = action("code.previous_match", CODE, key(GLFW.GLFW_KEY_F3, SHIFT))
    val DEFINITION = action("code.definition", CODE, key(GLFW.GLFW_KEY_F4))
    val FORMAT = action("code.format", CODE, key(GLFW.GLFW_KEY_L, CONTROL or ALT))
    val COMPLETION = action("code.completion", CODE, key(GLFW.GLFW_KEY_SPACE, CONTROL))

    // Project Panel
    val NEW_FILE = action("project.new_file", PROJECT, key(GLFW.GLFW_KEY_INSERT, ALT))
    val NEW_FOLDER = action("project.new_folder", PROJECT, key(GLFW.GLFW_KEY_INSERT, ALT or SHIFT))

    // Timeline
    val PLAY = action("timeline.play", TIMELINE, key(GLFW.GLFW_KEY_SPACE))
    val TO_START = action("timeline.to_start", TIMELINE, key(GLFW.GLFW_KEY_HOME))
    val TOGGLE_CURVES = action("timeline.toggle_curves", TIMELINE, key(GLFW.GLFW_KEY_TAB, CONTROL))
    val NEXT_KEY = action("timeline.next_key", TIMELINE, key(GLFW.GLFW_KEY_UP))
    val PREVIOUS_KEY = action("timeline.previous_key", TIMELINE, key(GLFW.GLFW_KEY_DOWN))
    val SMOOTH_KEYS = action("timeline.smooth_keys", TIMELINE, key(GLFW.GLFW_KEY_S))
    val NUDGE_LEFT = action("timeline.nudge_left", TIMELINE, key(GLFW.GLFW_KEY_LEFT))
    val NUDGE_RIGHT = action("timeline.nudge_right", TIMELINE, key(GLFW.GLFW_KEY_RIGHT))
    val NUDGE_LEFT_FAR = action("timeline.nudge_left_far", TIMELINE, key(GLFW.GLFW_KEY_LEFT, SHIFT))
    val NUDGE_RIGHT_FAR = action("timeline.nudge_right_far", TIMELINE, key(GLFW.GLFW_KEY_RIGHT, SHIFT))

    // 3D views
    val GRAB = action("viewport.grab", VIEWPORT, key(GLFW.GLFW_KEY_G))
    val ROTATE = action("viewport.rotate", VIEWPORT, key(GLFW.GLFW_KEY_R))
    val SCALE = action("viewport.scale", VIEWPORT, key(GLFW.GLFW_KEY_S))
    val AXIS_X = action("viewport.axis_x", VIEWPORT, key(GLFW.GLFW_KEY_X))
    val AXIS_Y = action("viewport.axis_y", VIEWPORT, key(GLFW.GLFW_KEY_Y))
    val AXIS_Z = action("viewport.axis_z", VIEWPORT, key(GLFW.GLFW_KEY_Z))
    val INSERT_KEY = action("viewport.insert_key", VIEWPORT, key(GLFW.GLFW_KEY_I))

    // Node Graphs
    val ADD_NODE = action("graph.add_node", GRAPH, key(GLFW.GLFW_KEY_INSERT), key(GLFW.GLFW_KEY_N, CONTROL))
    val GROUP = action("graph.group", GRAPH, key(GLFW.GLFW_KEY_G, CONTROL))
    val UNGROUP = action("graph.ungroup", GRAPH, key(GLFW.GLFW_KEY_G, CONTROL or SHIFT))
    val ENTRY_STATE = action("graph.entry_state", GRAPH, key(GLFW.GLFW_KEY_E))

    fun init() = Unit

    private fun key(code: Int, modifiers: Int = 0) = HollowKeyChord.key(code, modifiers)

    private fun action(id: String, context: HollowKeyContext, vararg defaults: HollowKeyChord) =
        HollowKeymap.register(HollowKeyAction(id, context, defaults.toList()))

    private fun action(id: String, context: HollowKeyContext, default: HollowKeyChord, label: () -> String) =
        HollowKeymap.register(HollowKeyAction(id, context, listOf(default), labelText = label))
}
