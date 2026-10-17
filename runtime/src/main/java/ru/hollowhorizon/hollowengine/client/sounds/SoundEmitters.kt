package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.sounds.SoundAttachments.register
import ru.hollowhorizon.hollowengine.client.vfx.VfxPlayback
import ru.hollowhorizon.hollowengine.client.vfx.VfxScenes
import ru.hollowhorizon.hollowengine.common.sounds.SoundEmitterSpec

sealed interface SoundEmitterState {
    data object Listener : SoundEmitterState
    /**
     * Plays from [position], moving at [velocity] blocks a second; with a [shape], from the nearest point of its
     * areas and all around inside them.
     */
    data class Available(val position: Vec3, val velocity: Vec3 = Vec3.ZERO, val shape: SoundShape? = null) : SoundEmitterState
    data object Pending : SoundEmitterState
    data object TemporarilyUnavailable : SoundEmitterState
    data object Removed : SoundEmitterState
}

fun interface SoundEmitter {
    fun sample(): SoundEmitterState
}

/**
 * Push bindings keep their last position across culling; closing the owner removes the binding. Read and written on
 * the client thread only: the owners publish while the world is drawn, the sounds sample while the engine ticks.
 */
object SoundAttachments {
    private val bindings = HashMap<String, Binding>()

    /**
     * Publishes [id]. A binding already published under it is closed, and what followed it ends: the owner that
     * registers last is the live one, as a model built again before the old one was released.
     */
    fun register(id: String, alive: () -> Boolean = { true }, source: SoundEmitter? = null): Binding {
        bindings[id]?.let { stale ->
            HollowEngine.LOGGER.warn("Sound attachment '{}' was registered again; the previous owner is dropped", id)
            stale.close()
        }
        return Binding(id, alive, source).also { bindings[id] = it }
    }

    fun emitter(id: String): SoundEmitter {
        var observed: Binding? = null
        return SoundEmitter {
            if (observed == null) observed = bindings[id]
            observed?.sample() ?: SoundEmitterState.Pending
        }
    }

    fun rigEmitter(entity: String, attachment: String, modelNode: String?): SoundEmitter {
        var observed: Binding? = null
        val prefix = "$entity/" + (modelNode?.let { "$it/" } ?: "")
        return SoundEmitter {
            if (observed == null) {
                val candidates = bindings.filterKeys { it.startsWith(prefix) && it.endsWith("/$attachment") }.values
                check(candidates.size <= 1) { "Ambiguous sound attachment '$attachment'; specify the model node id" }
                observed = candidates.singleOrNull()
            }
            observed?.sample() ?: SoundEmitterState.Pending
        }
    }

    /** The areas the sounds an entity publishes fill now, by its [uuid], for showing where they play. */
    fun shapesOf(uuid: String): List<SoundShape> = bindings.filterKeys { it.startsWith("$uuid/") }.values
        .mapNotNull { (it.state as? SoundEmitterState.Available)?.shape }

    fun clear() {
        bindings.values.toList().forEach(Binding::close)
    }

    fun prune() { bindings.values.filter { !it.alive() }.forEach(Binding::close) }

    class Binding internal constructor(
        private val id: String, internal val alive: () -> Boolean, private val source: SoundEmitter?,
    ) : AutoCloseable {
        internal var state: SoundEmitterState = SoundEmitterState.Pending
        private var closed = false
        internal fun sample() = if (closed) SoundEmitterState.Removed else source?.sample() ?: state

        /** Moves the binding; an owner dropped by a later [register] keeps calling this, and is not heard. */
        fun update(position: Vec3, velocity: Vec3 = Vec3.ZERO, shape: SoundShape? = null) {
            if (closed) return
            require(position.isFinite() && velocity.isFinite())
            state = SoundEmitterState.Available(position, velocity, shape)
        }
        fun markTemporarilyUnavailable() {
            if (!closed) state = SoundEmitterState.TemporarilyUnavailable
        }
        override fun close() {
            if (closed) return
            closed = true
            state = SoundEmitterState.Removed
            bindings.remove(id, this)
        }
    }
}

internal fun Vec3.isFinite() = x.toFloat().isFinite() && y.toFloat().isFinite() && z.toFloat().isFinite()

fun SoundEmitterSpec.createEmitter(): SoundEmitter = when (this) {
    SoundEmitterSpec.Listener -> SoundEmitter { SoundEmitterState.Listener }
    is SoundEmitterSpec.Position -> {
        val shape = if (areas.isEmpty()) null else SoundShape(
            Matrix4f().translation(position.x.toFloat(), position.y.toFloat(), position.z.toFloat()), areas,
        )
        SoundEmitter { SoundEmitterState.Available(position, velocity, shape) }
    }
    is SoundEmitterSpec.Attachment -> SoundAttachments.emitter(id)
    is SoundEmitterSpec.Rig -> {
        var observed: SoundEmitter? = null
        var owner: Entity? = null
        SoundEmitter {
            val entity = owner ?: Minecraft.getInstance().level?.getEntity(entityId)?.also { owner = it }
            if (entity == null) SoundEmitterState.Pending
            else if (entity.isRemoved) SoundEmitterState.Removed
            else {
                val binding = observed ?: SoundAttachments.rigEmitter(entity.uuid.toString(), attachmentId, modelNodeId).also { observed = it }
                binding.sample()
            }
        }
    }
    is SoundEmitterSpec.Effect -> {
        var observed: VfxPlayback? = null
        SoundEmitter {
            val current = VfxScenes.current()?.of(handle)
            when {
                observed != null && current !== observed -> SoundEmitterState.Removed
                current == null -> SoundEmitterState.Pending
                current.isFinished -> SoundEmitterState.Removed
                else -> {
                    observed = current
                    SoundEmitterState.Available(current.instance.origin)
                }
            }
        }
    }
    is SoundEmitterSpec.Entity -> {
        var observed: Entity? = null
        SoundEmitter {
            val entity = observed ?: Minecraft.getInstance().level?.getEntity(id)
            when {
                entity == null -> SoundEmitterState.Pending
                entity.isRemoved -> SoundEmitterState.Removed
                else -> {
                    observed = entity
                    SoundEmitterState.Available(entity.getPosition(TickHandler.partialTick).add(offset), entity.soundVelocity())
                }
            }
        }
    }
}

/**
 * How fast [this] moves, in blocks a second. On the ground it is not falling, though its motion says so every tick
 * as gravity pulls it into the floor, which would bend the pitch of what it plays.
 */
internal fun Entity.soundVelocity(): Vec3 {
    val motion = deltaMovement.scale(TICKS_PER_SECOND)
    return if (onGround()) Vec3(motion.x, 0.0, motion.z) else motion
}

private const val TICKS_PER_SECOND = 20.0
