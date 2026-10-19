package ru.hollowhorizon.hollowengine.common.npcs.actions

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Runs an NPC's actions on named channels ([NpcActionKeys]) and decides who may use a channel.
 *
 * Every action belongs to an [NpcActionOwner]. Inside one priority the last action on a channel wins, as it
 * replaces the running one. A higher priority takes the channel from a lower one: the lower action ends with
 * [Preempted], and while the higher owner holds the channel a lower one is refused with [Preempted] at once.
 *
 * The suspending calls ([act], [perform], [queue]) take their owner from the coroutine context (see
 * [actingAs]); the others take it as an argument and default to [NpcActionOwner.SCRIPT].
 */
class NpcActionController(private val scope: CoroutineScope) {
    private val channels = mutableMapOf<NpcActionKey, NpcChannel>()
    private val ownerLeases = mutableMapOf<NpcActionOwner, NpcActionLease>()

    /** Starts [block] on [key], replacing what [owner] or its equals run there. */
    fun <R> start(
        key: NpcActionKey,
        owner: NpcActionOwner = NpcActionOwner.SCRIPT,
        block: suspend CoroutineScope.() -> R,
    ): NpcAction<R> = launch(key, leaseFor(owner), concurrent = false, base = null, block)

    /** Starts [block] on [key] beside the actions already running there. */
    fun <R> startConcurrent(
        key: NpcActionKey,
        owner: NpcActionOwner = NpcActionOwner.SCRIPT,
        block: suspend CoroutineScope.() -> R,
    ): NpcAction<R> = launch(key, leaseFor(owner), concurrent = true, base = null, block)

    /** Runs [block] on [key] for the owner or bundle of the calling coroutine and waits for its result. */
    suspend fun <R> act(key: NpcActionKey, block: suspend CoroutineScope.() -> R): R {
        val context = currentCoroutineContext()
        return launch(key, leaseOf(context), concurrent = false, base = context[NpcActionLeases], block).await()
    }

    /** [act] that runs beside the actions already running on [key]. */
    suspend fun <R> actConcurrent(key: NpcActionKey, block: suspend CoroutineScope.() -> R): R {
        val context = currentCoroutineContext()
        return launch(key, leaseOf(context), concurrent = true, base = context[NpcActionLeases], block).await()
    }

    /**
     * Runs several actions as one: every channel they use stays held until [block] returns, and losing any of
     * them cancels the whole block. The caller then gets a single [Preempted].
     */
    suspend fun <R> perform(block: suspend CoroutineScope.() -> R): R = bundle(queued = false, block)

    /**
     * [perform] that waits for the channels it needs instead of taking them: an action inside starts once the
     * actions of others on its channel have finished and no bundle or higher owner holds it.
     */
    suspend fun <R> queue(block: suspend CoroutineScope.() -> R): R = bundle(queued = true, block)

    /** Ends the lease of [owner]: its actions are canceled and its channels are free. */
    fun release(owner: NpcActionOwner) = change { effects ->
        val leases = channels.values.flatMap { channel ->
            (channel.running + channel.waiting).map { it.lease } + listOfNotNull(channel.holder)
        }
        (leases + listOfNotNull(ownerLeases[owner])).filter { it.belongsTo(owner) }.toSet()
            .forEach { release(it, null, effects) }
    }

    /** The priority holding [key], or null when it is free. */
    fun holder(key: NpcActionKey): NpcActionPriority? = channels[key]?.holder?.priority

    fun cancel(key: NpcActionKey) = change { effects ->
        channels[key]?.let { channel -> (channel.running + channel.waiting).forEach { effects.cancel(it, null) } }
    }

    fun cancelAll() = change { effects ->
        channels.values.forEach { channel ->
            (channel.running + channel.waiting).forEach { effects.cancel(it, null) }
        }
    }

    private fun <R> launch(
        key: NpcActionKey,
        lease: NpcActionLease,
        concurrent: Boolean,
        base: NpcActionLeases?,
        block: suspend CoroutineScope.() -> R,
    ): NpcAction<R> {
        val action = NpcActionImpl<R>(key, lease, concurrent)
        action.attach(scope.async(lease.context(this, base), CoroutineStart.LAZY) {
            action.gate?.await()
            block()
        })
        var rejection: Preempted? = null
        change { effects ->
            val channel = channels.getOrPut(key) { NpcChannel(key) }
            rejection = admit(channel, action, effects)
            if (rejection != null) effects.dirty += channel
        }
        action.deferred.invokeOnCompletion { finish(action) }
        if (rejection != null) action.deferred.cancel(rejection) else action.deferred.start()
        return action
    }

    private suspend fun <R> bundle(queued: Boolean, block: suspend CoroutineScope.() -> R): R {
        val context = currentCoroutineContext()
        val parent = leaseOf(context)
        var lease: NpcActionLease? = null
        try {
            return coroutineScope {
                val bundle = NpcActionLease.bundle(parent, coroutineContext.job, queued).also { lease = it }
                withContext(
                    (context[NpcActionLeases] ?: NpcActionLeases.EMPTY).with(this@NpcActionController, bundle),
                    block
                )
            }
        } catch (exception: CancellationException) {
            throw lease?.preemption ?: exception
        } finally {
            lease?.let { bundle -> change { effects -> release(bundle, null, effects) } }
        }
    }

    /** Puts [action] on its channel, makes it wait, or returns why it is refused. */
    private fun admit(channel: NpcChannel, action: NpcActionImpl<*>, effects: NpcActionEffects): Preempted? {
        val lease = action.lease
        if (!lease.active) return lease.preemption ?: Preempted(channel.key, lease.priority)
        if (lease.queued) {
            if (channel.waiting.isEmpty() && admitsQueued(channel, lease)) {
                take(channel, action, effects)
            } else {
                action.gate = CompletableDeferred()
                channel.waiting += action
            }
            return null
        }

        val holder = channel.holder
        when {
            holder == null || lease.isWithin(holder) -> take(channel, action, effects)
            lease.priority > holder.priority || lease.priority == holder.priority && !action.concurrent -> {
                if (holder.isBundle) preemptBundle(holder, Preempted(channel.key, lease.priority), effects)
                take(channel, action, effects)
            }

            lease.priority == holder.priority -> channel.running += action
            else -> return Preempted(channel.key, holder.priority)
        }
        return null
    }

    private fun admitsQueued(channel: NpcChannel, lease: NpcActionLease): Boolean {
        if (channel.running.any { it.lease !== lease }) return false
        val holder = channel.holder ?: return true
        return lease.isWithin(holder) || !holder.isBundle && holder.priority <= lease.priority
    }

    /** Makes [action]'s lease the holder of [channel], ending what it replaces or outranks there. */
    private fun take(channel: NpcChannel, action: NpcActionImpl<*>, effects: NpcActionEffects) {
        val lease = action.lease
        channel.running.forEach { other ->
            when {
                lease.isWithin(other.lease) -> if (!action.concurrent) effects.cancel(other, null)
                !action.concurrent || other.lease.priority < lease.priority -> effects.cancel(
                    other,
                    Preempted(channel.key, lease.priority)
                )
            }
        }
        channel.holder = lease
        channel.running += action
    }

    private fun preemptBundle(bundle: NpcActionLease, cause: Preempted, effects: NpcActionEffects) {
        if (!bundle.active) return
        bundle.preemption = cause
        bundle.job?.let { effects.cancel(it, cause) }
        release(bundle, cause, effects)
    }

    private fun release(lease: NpcActionLease, cause: CancellationException?, effects: NpcActionEffects) {
        if (!lease.active) return
        lease.active = false
        lease.owner?.let { owner -> if (ownerLeases[owner] === lease) ownerLeases.remove(owner) }
        channels.values.forEach { channel ->
            channel.running.forEach { if (it.lease === lease) effects.cancel(it, cause) }
            channel.waiting.forEach { if (it.lease === lease) effects.cancel(it, cause) }
            if (channel.holder === lease) effects.dirty += channel
        }
    }

    /** Hands a channel whose holder may have let go to whoever still has a claim on it. */
    private fun settle(channel: NpcChannel, effects: NpcActionEffects) {
        var holder = channel.holder
        while (holder != null && !holder.retains(channel)) holder = holder.parent
        channel.holder = holder ?: channel.running.firstOrNull { it.lease.active }?.lease

        while (true) {
            val next = channel.waiting.firstOrNull() ?: break
            if (!next.lease.active || !admitsQueued(channel, next.lease)) break
            channel.waiting.removeFirst()
            take(channel, next, effects)
            next.gate?.let(effects::open)
        }
        if (channel.isIdle) channels.remove(channel.key)
    }

    private fun NpcActionLease.retains(channel: NpcChannel): Boolean =
        active && (keepsChannels || channel.running.any { it.lease === this })

    private fun settleDirty(effects: NpcActionEffects) {
        while (effects.dirty.isNotEmpty()) {
            val channel = effects.dirty.first()
            effects.dirty.remove(channel)
            settle(channel, effects)
        }
    }

    private fun finish(action: NpcActionImpl<*>) = change { effects ->
        val channel = channels[action.key] ?: return@change
        channel.running.remove(action)
        channel.waiting.remove(action)
        effects.dirty += channel
    }

    /**
     * Applies a change of channels, then the cancellations and wake-ups it causes. Those run last because they
     * complete actions synchronously, and completion changes the channels again.
     */
    private inline fun change(block: (NpcActionEffects) -> Unit) {
        val effects = NpcActionEffects()
        block(effects)
        settleDirty(effects)
        effects.apply()
    }

    private fun leaseOf(context: CoroutineContext): NpcActionLease =
        context[NpcActionLeases]?.get(this) ?: leaseFor(context[NpcActionOwner] ?: NpcActionOwner.SCRIPT)

    private fun leaseFor(owner: NpcActionOwner): NpcActionLease {
        val job = owner.job?.takeIf { it.isActive } ?: return NpcActionLease.of(owner, null)
        ownerLeases[owner]?.let { return it }
        val lease = NpcActionLease.of(owner, job)
        ownerLeases[owner] = lease
        job.invokeOnCompletion { change { effects -> release(lease, null, effects) } }
        return lease
    }
}
