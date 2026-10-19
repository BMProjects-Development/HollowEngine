package ru.hollowhorizon.hollowengine.common.npcs.actions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A claim on NPC channels: either an owner's, or a bundle's ([NpcActionController.perform] and
 * [NpcActionController.queue]) taken on behalf of its [parent].
 */
internal class NpcActionLease private constructor(
    val priority: NpcActionPriority,
    val owner: NpcActionOwner?,
    val parent: NpcActionLease?,
    val job: Job?,
    val queued: Boolean,
) {
    var active = true

    /** Why the whole bundle was taken away; reported to the caller of the bundle instead of what it saw. */
    var preemption: Preempted? = null

    val isBundle: Boolean get() = parent != null

    /** Keeps its channels between actions, until [job] ends. */
    val keepsChannels: Boolean get() = job != null

    /** Leases of one jobless owner are created per call, so they are told apart by the owner they share. */
    private val identity: Any get() = if (isBundle) this else owner ?: this

    /** Whether this lease acts on behalf of [other]: it is [other] or nested inside it. */
    fun isWithin(other: NpcActionLease): Boolean {
        var lease: NpcActionLease? = this
        while (lease != null) {
            if (lease.identity === other.identity) return true
            lease = lease.parent
        }
        return false
    }

    fun belongsTo(owner: NpcActionOwner): Boolean = !isBundle && this.owner === owner

    /** The coroutine context an action of this lease runs in, so the calls it makes stay inside the lease. */
    fun context(controller: NpcActionController, base: NpcActionLeases?): CoroutineContext {
        val leases = (base ?: NpcActionLeases.EMPTY).with(controller, this)
        val rootOwner = generateSequence(this) { it.parent }.last().owner
        return if (rootOwner != null) leases + rootOwner else leases
    }

    companion object {
        fun of(owner: NpcActionOwner, job: Job?) = NpcActionLease(owner.priority, owner, null, job, queued = false)

        fun bundle(parent: NpcActionLease, job: Job, queued: Boolean) =
            NpcActionLease(parent.priority, null, parent, job, queued)
    }
}

/** The bundles that the calls of a coroutine belong to, one per NPC. */
internal class NpcActionLeases private constructor(
    private val leases: Map<NpcActionController, NpcActionLease>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NpcActionLeases> {
        val EMPTY = NpcActionLeases(emptyMap())
    }

    operator fun get(controller: NpcActionController): NpcActionLease? = leases[controller]

    fun with(controller: NpcActionController, lease: NpcActionLease) = NpcActionLeases(leases + (controller to lease))
}

internal class NpcChannel(val key: NpcActionKey) {
    var holder: NpcActionLease? = null
    val running = linkedSetOf<NpcActionImpl<*>>()
    val waiting = ArrayDeque<NpcActionImpl<*>>()

    val isIdle: Boolean get() = holder == null && running.isEmpty() && waiting.isEmpty()
}

/** What a change of channels does once the controller's lock is released. */
internal class NpcActionEffects {
    private val cancelled = mutableListOf<Pair<NpcActionImpl<*>, CancellationException?>>()
    private val jobs = mutableListOf<Pair<Job, CancellationException>>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    val dirty = linkedSetOf<NpcChannel>()

    fun cancel(action: NpcActionImpl<*>, cause: CancellationException?) {
        cancelled += action to cause
    }

    fun cancel(job: Job, cause: CancellationException) {
        jobs += job to cause
    }

    fun open(gate: CompletableDeferred<Unit>) {
        gates += gate
    }

    fun apply() {
        cancelled.forEach { (action, cause) -> action.deferred.cancel(cause) }
        jobs.forEach { (job, cause) -> job.cancel(cause) }
        gates.forEach { it.complete(Unit) }
    }
}

internal class NpcActionImpl<R>(
    val key: NpcActionKey,
    val lease: NpcActionLease,
    val concurrent: Boolean,
) : NpcAction<R> {
    lateinit var deferred: Deferred<R>
        private set

    /** Set while the action waits its turn in a queue. */
    var gate: CompletableDeferred<Unit>? = null

    override var status: NpcActionStatus = NpcActionStatus.RUNNING
        private set

    fun attach(deferred: Deferred<R>) {
        this.deferred = deferred
        deferred.invokeOnCompletion { cause ->
            status = when (cause) {
                null -> NpcActionStatus.COMPLETED
                is Preempted -> NpcActionStatus.PREEMPTED
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
