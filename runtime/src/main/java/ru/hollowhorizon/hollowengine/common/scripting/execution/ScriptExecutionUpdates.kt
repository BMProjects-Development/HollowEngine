package ru.hollowhorizon.hollowengine.common.scripting.execution

/** Sends a full snapshot on admission, then only changes. Revocation clears the client's cache. */
internal class ScriptExecutionUpdates<P, S>(private val idle: S) {
    private var previous = emptyMap<String, S>()
    private var recipients = emptySet<P>()

    fun synchronize(current: Map<String, S>, authorized: Set<P>,
                    send: (P, Boolean, Boolean, Map<String, S>) -> Unit) {
        val changes = (previous.keys + current.keys).mapNotNull { path ->
            val before = previous[path] ?: idle
            val after = current[path] ?: idle
            if (before == after) null else path to after
        }.toMap()
        (recipients - authorized).forEach { send(it, false, true, emptyMap()) }
        authorized.forEach { player ->
            if (player !in recipients) send(player, true, true, current)
            else if (changes.isNotEmpty()) send(player, true, false, changes)
        }
        previous = current.toMap()
        recipients = authorized.toSet()
    }
}
