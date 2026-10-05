package ru.hollowhorizon.hollowengine.client.editor

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent

/**
 * Undo and redo for everything edited in the world, in the order it was done: an object moved by the gizmo or
 * put under another, a bone posed, something hung on a model.
 */
@ClientOnly
internal object WorldHistory {
    const val LIMIT = 200

    /** One step back in the world, on the entity [entityId]. False from [undo] or [redo] means it could not be taken. */
    interface Step {
        val entityId: Int

        fun undo(): Boolean

        fun redo(): Boolean
    }

    private val undoSteps = ArrayDeque<Step>()
    private val redoSteps = ArrayDeque<Step>()

    fun record(step: Step) {
        undoSteps.addLast(step)
        while (undoSteps.size > LIMIT) undoSteps.removeFirst()
        redoSteps.clear()
    }

    /** Takes back the latest step; one that can no longer be taken, say of an entity gone since, is skipped. */
    fun undo(): Boolean = move(undoSteps, redoSteps, Step::undo)

    fun redo(): Boolean = move(redoSteps, undoSteps, Step::redo)

    /** Drops the steps of [entityId]: something else changed it, and taking them back would undo that too. */
    fun forget(entityId: Int) {
        undoSteps.removeAll { it.entityId == entityId }
        redoSteps.removeAll { it.entityId == entityId }
    }

    /**
     * Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z. They belong to this history even when it is empty, so they never fall
     * through to whatever else listens to keys in the world.
     */
    fun handleKey(key: Int, modifiers: Int): Boolean {
        if (modifiers and GLFW.GLFW_MOD_CONTROL == 0) return false
        val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
        when (key) {
            GLFW.GLFW_KEY_Z if !shift -> undo()
            GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_Z -> redo()
            else -> return false
        }
        return true
    }

    @SubscribeEvent
    fun onLevelUnload(event: LevelEvent.Unload) {
        if (!event.level.isClientSide) return
        undoSteps.clear()
        redoSteps.clear()
    }

    private fun move(from: ArrayDeque<Step>, to: ArrayDeque<Step>, take: (Step) -> Boolean): Boolean {
        while (true) {
            val step = from.removeLastOrNull() ?: return false
            if (take(step)) {
                to.addLast(step)
                return true
            }
        }
    }
}
