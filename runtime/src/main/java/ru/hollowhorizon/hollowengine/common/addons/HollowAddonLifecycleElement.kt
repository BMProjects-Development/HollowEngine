package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks the threads that run an addon's `load` or `unload`. The set of addons is locked for the whole
 * call, so an addon that enabled, disabled or reloaded one from there would wait for itself forever.
 */
internal class HollowAddonLifecycleElement : ThreadContextElement<Boolean>, AbstractCoroutineContextElement(Key) {
    override fun updateThreadContext(context: CoroutineContext): Boolean = inside.get().also { inside.set(true) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) = inside.set(oldState)

    companion object Key : CoroutineContext.Key<HollowAddonLifecycleElement> {
        private val inside = ThreadLocal.withInitial { false }

        val isActive: Boolean get() = inside.get()
    }
}
