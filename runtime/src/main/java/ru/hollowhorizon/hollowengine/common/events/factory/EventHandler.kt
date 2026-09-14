package ru.hollowhorizon.hollowengine.common.events.factory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.hollowhorizon.hollowengine.common.events.Cancellable
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.EventListener
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.StartupEvent
import ru.hollowhorizon.hollowengine.common.events.eventListenerOf
import java.lang.reflect.ParameterizedType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

open class EventHandler<T : Event> {
    /** Ordered by priority; replaced as a whole on every change, so posting never copies or locks. */
    @Volatile
    private var subscriptions: Array<Subscription<T>> = emptyArray()

    /** The event class, read from the type argument of the companion object that declares this handler. */
    private val eventType: Class<*>? by lazy {
        (javaClass.genericSuperclass as? ParameterizedType)?.actualTypeArguments?.firstOrNull() as? Class<*>
    }

    fun register(priority: Int = 0, listener: (T) -> Unit): (T) -> Unit {
        add(Subscription(eventListenerOf(priority, listener), side = null, job = null))
        return listener
    }

    /** Subscribes [listener] until it is [unregister]ed. Meant for the engine's own lifelong listeners. */
    fun register(listener: EventListener<T>): EventListener<T> {
        add(Subscription(listener, side = null, job = null))
        return listener
    }

    /**
     * Subscribes [listener] for as long as [scope] is active. A [LogicalSide] in the scope's context limits
     * it to the posts of that side.
     */
    fun register(scope: CoroutineScope, listener: EventListener<T>): EventListener<T> {
        val job = requireNotNull(scope.coroutineContext[Job]) {
            "Event subscriptions require a CoroutineScope with a Job"
        }
        val side = scope.coroutineContext[LogicalSide]
        if (side != null) checkReachableFrom(side)
        if (!job.isActive) return listener

        val subscription = Subscription(listener, side, job)
        add(subscription)
        job.invokeOnCompletion { remove(subscription) }
        return listener
    }

    fun subscribe(
        scope: CoroutineScope,
        priority: Int = 0,
        listener: (T) -> Unit,
    ): EventListener<T> = register(scope, eventListenerOf(priority, listener))

    @Synchronized
    fun unregister(listener: EventListener<T>) {
        val index = subscriptions.indexOfFirst { it.listener == listener }
        if (index >= 0) subscriptions = subscriptions.filterIndexed { i, _ -> i != index }.toTypedArray()
    }

    @Synchronized
    fun clear() {
        subscriptions = emptyArray()
    }

    open fun post(event: T): T {
        val current = subscriptions
        val cancellable = event as? Cancellable
        var postingSide: LogicalSide? = null

        for (subscription in current) {
            val side = subscription.side
            if (side != null) {
                val posting = postingSide ?: LogicalSide.of(event).also { postingSide = it }
                if (side != posting) continue
            }
            if (subscription.job?.isActive == false) continue

            subscription.listener(event)
            if (cancellable?.isCanceled == true) break
        }
        return event
    }

    private fun checkReachableFrom(side: LogicalSide) {
        val type = eventType ?: return
        require(!StartupEvent::class.java.isAssignableFrom(type)) {
            "${type.simpleName} fires once while the game starts, before any ${side.displayName} scope exists; " +
                "subscribe to it from a .startup.kts"
        }
        val postedOn = when {
            ClientEvent::class.java.isAssignableFrom(type) -> LogicalSide.CLIENT
            ServerEvent::class.java.isAssignableFrom(type) -> LogicalSide.SERVER
            else -> return
        }
        require(postedOn == side) {
            "${type.simpleName} is only posted on the ${postedOn.displayName}, " +
                "so a subscription from the ${side.displayName} would never hear it"
        }
    }

    @Synchronized
    private fun add(subscription: Subscription<T>) {
        subscriptions = (subscriptions + subscription).sortedByDescending { it.listener.priority }.toTypedArray()
    }

    @Synchronized
    private fun remove(subscription: Subscription<T>) {
        subscriptions = subscriptions.filter { it !== subscription }.toTypedArray()
    }

    private class Subscription<T : Event>(
        val listener: EventListener<T>,
        /** Only posts of this side reach the listener; `null` hears both. */
        val side: LogicalSide?,
        /** The listener is skipped as soon as this is no longer active, before its removal lands. */
        val job: Job?,
    )

    companion object {
        private val handlers = HashMap<KClass<*>, EventHandler<*>>()

        @Suppress("UNCHECKED_CAST")
        fun <T : Event> get(type: KClass<T>): EventHandler<T> = handlers.getOrPut(type) {
            type.companionObjectInstance as EventHandler<T>
        } as EventHandler<T>
    }
}

private val LogicalSide.displayName: String
    get() = when (this) {
        LogicalSide.CLIENT -> "client side"
        LogicalSide.SERVER -> "server side"
    }

suspend inline fun <reified T : Event> EventHandler<T>.await(
    priority: Int = 0,
    crossinline filter: (T) -> Boolean = { true },
): T = suspendCancellableCoroutine { continuation ->
    val isDone = AtomicBoolean(false)

    val listener = object : EventListener<T> {
        override val priority = priority

        override fun invoke(event: T) {
            if (filter(event) && isDone.compareAndSet(false, true)) {
                unregister(this)
                continuation.resume(event)
            }
        }
    }

    register(listener)

    continuation.invokeOnCancellation {
        if (isDone.compareAndSet(false, true)) {
            unregister(listener)
        }
    }
}

context(scope: CoroutineScope)
fun <T : Event> EventHandler<T>.subscribe(priority: Int = 0, listener: (T) -> Unit): EventListener<T> =
    subscribe(scope, priority, listener)
