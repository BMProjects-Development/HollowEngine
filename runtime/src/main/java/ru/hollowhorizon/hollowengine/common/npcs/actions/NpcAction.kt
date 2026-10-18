package ru.hollowhorizon.hollowengine.common.npcs.actions

import kotlinx.coroutines.*

@JvmInline
value class NpcActionKey(val id: String) {
    init {
        require(id.isNotBlank()) { "NPC action key cannot be blank" }
    }
}

object NpcActionKeys {
    val MOVEMENT = NpcActionKey("hollowengine:movement")
    val LOOK = NpcActionKey("hollowengine:look")
    val HANDS = NpcActionKey("hollowengine:hands")
    val ANIMATION = NpcActionKey("hollowengine:animation")
}

enum class NpcActionStatus {
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

interface NpcAction<out R> {
    val status: NpcActionStatus
    val isActive: Boolean

    fun cancel()
    suspend fun await(): R
}

class NpcActionController(private val scope: CoroutineScope) {
    private val lock = Any()
    private val actions = mutableMapOf<NpcActionKey, MutableSet<NpcActionImpl<*>>>()

    fun <R> start(key: NpcActionKey, block: suspend CoroutineScope.() -> R): NpcAction<R> =
        start(key, replaceRunning = true, block)

    fun <R> startConcurrent(key: NpcActionKey, block: suspend CoroutineScope.() -> R): NpcAction<R> =
        start(key, replaceRunning = false, block)

    private fun <R> start(
        key: NpcActionKey,
        replaceRunning: Boolean,
        block: suspend CoroutineScope.() -> R,
    ): NpcAction<R> {
        val deferred = scope.async(start = CoroutineStart.LAZY, block = block)
        val action = NpcActionImpl(deferred)
        val replaced = synchronized(lock) {
            val running = actions.getOrPut(key, ::linkedSetOf)
            val previous = if (replaceRunning) running.toList().also { running.clear() } else emptyList()
            running += action
            previous
        }
        replaced.forEach(NpcActionImpl<*>::cancel)
        deferred.invokeOnCompletion {
            synchronized(lock) {
                actions[key]?.let { running ->
                    running.remove(action)
                    if (running.isEmpty()) actions.remove(key)
                }
            }
        }
        deferred.start()
        return action
    }

    fun cancel(key: NpcActionKey) {
        val running = synchronized(lock) { actions.remove(key)?.toList().orEmpty() }
        running.forEach(NpcActionImpl<*>::cancel)
    }

    fun cancelAll() {
        val running = synchronized(lock) {
            actions.values.flatten().also { actions.clear() }
        }
        running.forEach(NpcActionImpl<*>::cancel)
    }
}

private class NpcActionImpl<R>(private val deferred: Deferred<R>) : NpcAction<R> {
    @Volatile
    override var status: NpcActionStatus = NpcActionStatus.RUNNING
        private set

    init {
        deferred.invokeOnCompletion { cause ->
            status = when (cause) {
                null -> NpcActionStatus.COMPLETED
                is CancellationException -> NpcActionStatus.CANCELLED
                else -> NpcActionStatus.FAILED
            }
        }
    }

    override val isActive: Boolean
        get() = deferred.isActive

    override fun cancel() {
        deferred.cancel()
    }

    override suspend fun await(): R = try {
        deferred.await()
    } catch (exception: CancellationException) {
        deferred.cancel(exception)
        throw exception
    }
}
