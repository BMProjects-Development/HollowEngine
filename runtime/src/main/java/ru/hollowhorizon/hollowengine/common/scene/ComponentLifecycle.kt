package ru.hollowhorizon.hollowengine.common.scene

import java.util.UUID

/** A component that supplies its own runtime for a particular binding. */
interface ComponentBehavior {
    fun bind(owner: ComponentOwner, id: UUID): ComponentLifecycle?
}

/** Optional per-owner behavior. Callbacks run on the scene thread. */
interface ComponentLifecycle {
    fun attach() = Unit
    fun activate() = Unit
    fun change(value: Any) = Unit
    fun suspend() = Unit
    fun remove() = Unit
}

internal class SceneFailures {
    private var first: Exception? = null

    fun capture(action: () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (exception: Exception) {
            val previous = first
            if (previous == null) first = exception else if (previous !== exception) previous.addSuppressed(exception)
            false
        }
    }

    fun throwIfAny() {
        first?.let { throw it }
    }
}
