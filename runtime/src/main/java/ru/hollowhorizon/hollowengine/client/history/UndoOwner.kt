package ru.hollowhorizon.hollowengine.client.history

import org.lwjgl.glfw.GLFW

/** Something with a history of its own, which Ctrl+Z and the history window reach while it has the focus. */
interface UndoOwner {
    val history: UndoHistory
}

object UndoKeys {
    /** True for undo, false for redo, null for any other key. */
    fun direction(key: Int, modifiers: Int): Boolean? {
        if (modifiers and GLFW.GLFW_MOD_CONTROL == 0) return null
        val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
        return when (key) {
            GLFW.GLFW_KEY_Z -> !shift
            GLFW.GLFW_KEY_Y -> false
            else -> null
        }
    }

    /** Undoes or redoes in [history] when the key asks for it. */
    fun handle(history: UndoHistory?, key: Int, modifiers: Int): Boolean {
        val undo = direction(key, modifiers) ?: return false
        if (history != null) {
            if (undo) history.undo() else history.redo()
        }
        return true
    }
}
