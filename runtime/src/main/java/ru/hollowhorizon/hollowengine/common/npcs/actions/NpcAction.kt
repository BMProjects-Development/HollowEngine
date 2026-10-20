package ru.hollowhorizon.hollowengine.common.npcs.actions

import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
    RUNNING, COMPLETED, CANCELLED,

    /** The channel was taken by another owner; [NpcAction.await] throws [Preempted]. */
    PREEMPTED, FAILED,
}

interface NpcAction<out R> {
    val status: NpcActionStatus
    val isActive: Boolean

    fun cancel()
    suspend fun await(): R
}

/** Who holds an NPC channel, from the lowest priority to the highest. */
enum class NpcActionPriority {
    SCHEDULE, SCRIPT, STORY, CUTSCENE,
}

/**
 * A party that leases NPC channels.
 *
 * An owner with a [job] keeps the channels it used between its actions, so a lower priority cannot take
 * them in the meantime; the lease ends with the job or with [NpcActionController.release]. An owner without
 * a job holds a channel only while one of its actions runs.
 *
 * Put into a coroutine context, the owner applies to every suspending NPC call made inside it.
 *
 * @property priority the level this owner competes at.
 * @property job the job the lease lives as long as.
 */
class NpcActionOwner(
    val priority: NpcActionPriority,
    val job: Job? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NpcActionOwner> {
        /** The owner of every call made without one. */
        val SCRIPT = NpcActionOwner(NpcActionPriority.SCRIPT)
    }

    override fun toString() = "NpcActionOwner($priority)"
}

/**
 * Thrown to the caller of an action, or of a whole [NpcActionController.perform], whose channel was taken by
 * another owner. Nothing retries it: the caller decides whether to start the action again.
 *
 * @property channel the channel that was taken.
 * @property priority the priority of the owner that took it.
 */
class Preempted(
    val channel: NpcActionKey,
    val priority: NpcActionPriority,
) : CancellationException("NPC channel ${channel.id} was taken by $priority")

/**
 * Runs [block] as a new owner of [priority]: the channels its suspending NPC calls use stay leased to it
 * until [block] returns.
 */
suspend fun <R> actingAs(priority: NpcActionPriority, block: suspend CoroutineScope.() -> R): R = coroutineScope {
    withContext(NpcActionOwner(priority, coroutineContext.job), block)
}
