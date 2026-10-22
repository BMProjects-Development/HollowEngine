package ru.hollowhorizon.hollowengine.common.fsm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import net.minecraft.nbt.CompoundTag
import kotlin.coroutines.CoroutineContext

class StateStorage(val tag: CompoundTag) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<StateStorage>

    override val key: CoroutineContext.Key<*> get() = Key
}

/** Polls in the caller's coroutine, so cancellation and condition failures belong to the waiter. */
suspend fun await(condition: () -> Boolean) {
    while (true) {
        currentCoroutineContext().ensureActive()
        if (condition()) return
        delay(50L)
    }
}
