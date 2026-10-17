package ru.hollowhorizon.hollowengine.client.history

import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys

/** Something with a history of its own, which Ctrl+Z and the history window reach while it has the focus. */
interface UndoOwner {
    val history: UndoHistory
}

object UndoKeys {
    /** True for undo, false for redo, null for any other key. */
    fun direction(key: Int, modifiers: Int): Boolean? = when {
        HollowKeys.UNDO.matches(key, modifiers) -> true
        HollowKeys.REDO.matches(key, modifiers) -> false
        else -> null
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
