package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import net.minecraft.server.MinecraftServer
import ru.hollowhorizon.hollowengine.common.utils.currentServerOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs coroutines on the thread of whichever server is running. Server reload scripts start while the
 * datapacks of a world load, before that world's server exists, so work dispatched until then waits for it.
 */
@OptIn(InternalCoroutinesApi::class)
internal object ServerThreadDispatcher : CoroutineDispatcher(), Delay {
    private val waiting = ArrayList<Runnable>()

    private fun target(): SingleThreadDispatcher? =
        currentServerOrNull()?.let(RuntimeDispatcherState::serverDispatcherOrNull)

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = currentServerOrNull()?.isSameThread != true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val target = target() ?: synchronized(this) {
            target() ?: run {
                waiting += block
                return
            }
        }
        target.dispatch(context, block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val target = target()
        if (target != null) {
            target.scheduleResumeAfterDelay(timeMillis, continuation)
        } else {
            dispatch(continuation.context, Runnable { scheduleResumeAfterDelay(timeMillis, continuation) })
        }
    }

    /** Hands what waited for a server to [server], whose dispatcher has just been created. */
    fun onServerCreated(server: MinecraftServer) {
        val target = RuntimeDispatcherState.serverDispatcherOrNull(server) ?: return
        val ready = synchronized(this) {
            waiting.toList().also { waiting.clear() }
        }
        ready.forEach { block -> target.dispatch(EmptyCoroutineContext, block) }
    }

    override fun toString(): String = "ServerThreadDispatcher"
}
