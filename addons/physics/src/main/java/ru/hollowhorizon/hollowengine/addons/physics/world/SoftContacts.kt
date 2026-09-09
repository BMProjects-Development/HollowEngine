package ru.hollowhorizon.hollowengine.addons.physics.world

import com.github.stephengold.joltjni.Body
import com.github.stephengold.joltjni.ContactSettings
import com.github.stephengold.joltjni.CustomContactListener
import com.github.stephengold.joltjni.PhysicsSystem

/**
 * Lets some bodies push back less hard than others.
 *
 * A collider built off a model overlaps its neighbors, and an author may want it to sink into them
 * rather than be shoved out.
 */
class SoftContacts : CustomContactListener() {
    private var push = HashMap<Int, Float>()

    /** Attaches to [system] and answers its contacts from then on. */
    fun listenTo(system: PhysicsSystem) = system.setContactListener(this)

    /** How hard each of [bodies] pushes back, from 1 for solid down to 0 for passing through. */
    fun remember(bodies: Map<Int, Float>) {
        if (bodies.isEmpty()) return

        push = HashMap(push).apply { putAll(bodies) }
    }

    fun forget(bodies: Collection<Int>) {
        if (bodies.isEmpty() || push.isEmpty()) return

        push = HashMap(push).apply { keys.removeAll(bodies.toSet()) }
    }

    override fun onContactAdded(first: Long, second: Long, manifold: Long, settings: Long) =
        soften(first, second, settings)

    override fun onContactPersisted(first: Long, second: Long, manifold: Long, settings: Long) =
        soften(first, second, settings)

    private fun soften(first: Long, second: Long, settings: Long) {
        if (push.isEmpty()) return

        val firstPush = pushOf(first)
        val secondPush = pushOf(second)
        if (firstPush >= SOLID && secondPush >= SOLID) return

        ContactSettings(settings).apply {
            setInvMassScale1(firstPush)
            setInvInertiaScale1(firstPush)
            setInvMassScale2(secondPush)
            setInvInertiaScale2(secondPush)
        }
    }

    private fun pushOf(bodyVa: Long): Float = push[Body(bodyVa).id] ?: SOLID

    private companion object {
        const val SOLID = 1f
    }
}
