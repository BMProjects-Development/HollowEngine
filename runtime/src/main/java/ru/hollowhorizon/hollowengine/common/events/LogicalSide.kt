package ru.hollowhorizon.hollowengine.common.events

import ru.hollowhorizon.hollowengine.common.utils.isLogicalClient
import kotlin.coroutines.CoroutineContext

/**
 * The logical side a scope listens on. Subscriptions made from a scope that carries it hear only the posts
 * of that side; subscriptions from any other scope hear both.
 */
enum class LogicalSide : CoroutineContext.Element {
    CLIENT, SERVER;

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<LogicalSide> {
        /**
         * The side of [event], that being posted. An event may state it.
         */
        fun of(event: Event): LogicalSide = when (event) {
            is ClientEvent -> CLIENT
            is ServerEvent -> SERVER
            else -> if (isLogicalClient) CLIENT else SERVER
        }
    }
}
