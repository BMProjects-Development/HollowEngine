package ru.hollowhorizon.hollowengine.common.montages

import kotlinx.coroutines.CompletableDeferred
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.AnimationsComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.withMontage
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.models.MontageAssets
import ru.hollowhorizon.hollowengine.common.models.MontageEvent
import ru.hollowhorizon.hollowengine.common.models.MontageLayerSpec
import kotlin.math.ceil

/**
 * A montage playing on an entity, as the server that started it sees it: what stops it, what waits for it,
 * and what runs at its events. It ends when it has played through, is stopped, is replaced by the same
 * montage started again, or its entity is gone.
 */
class MontageHandle internal constructor(
    val entity: Entity,
    val montage: String,
    private val layer: MontageLayerSpec,
    private val duration: Float,
    private val events: List<MontageEvent>,
) {
    private val done = CompletableDeferred<Boolean>()
    private val listeners = HashMap<String, MutableList<() -> Unit>>()
    private var reached = -1f

    /** Whether the montage is still playing; false once it has ended, however it did. */
    val isPlaying: Boolean get() = !done.isCompleted

    /** Seconds into the montage, at its own speed. */
    val time: Float get() = (secondsSinceStart() * layer.speed).coerceIn(0f, duration)

    /** Runs [action] each time the montage reaches the event [name]. */
    fun onEvent(name: String, action: () -> Unit): MontageHandle {
        listeners.getOrPut(name) { mutableListOf() } += action
        return this
    }

    /** Waits until the montage ends: true if it played through, false if it was stopped or cut short. */
    suspend fun await(): Boolean = done.await()

    /** Stops the montage, fading it out over [fadeOut] seconds. */
    fun stop(fadeOut: Float = layer.fadeOut) {
        if (!isPlaying) return
        val now = entity.level().gameTime
        MontagePlayback.edit(entity) { animations ->
            val playing =
                animations.montages.firstOrNull { it.id == layer.id && it.startGameTime == layer.startGameTime }
                    ?: return@edit animations
            animations.withMontage(
                playing.copy(fadeOut = fadeOut, stopAtGameTime = now, removeAtGameTime = now + ticks(fadeOut))
            )
        }
        done.complete(false)
    }

    /** Fires the events it has passed since the last tick; false once it no longer plays. */
    internal fun tick(): Boolean {
        if (!isPlaying) return false
        if (entity.isRemoved || !isStillOn()) return false.also { done.complete(false) }

        val now = time
        events.forEach { event -> if (event.time > reached && event.time <= now) fire(event.name) }
        reached = now
        if (now < duration) return true
        done.complete(true)
        return false
    }

    internal fun replaced() {
        done.complete(false)
    }

    private fun fire(name: String) {
        listeners[name]?.toList()?.forEach { action ->
            runCatching(action).onFailure {
                HollowEngine.LOGGER.error(
                    "Montage {} event '{}' failed",
                    montage,
                    name,
                    it
                )
            }
        }
    }

    private fun isStillOn(): Boolean =
        MontagePlayback.animationsOf(entity)?.montages?.any { it.id == layer.id && it.startGameTime == layer.startGameTime && it.stopAtGameTime == null } == true

    private fun secondsSinceStart(): Float =
        (entity.level().gameTime - (layer.startGameTime ?: 0L)).toFloat() / TICKS_PER_SECOND

    internal companion object {
        const val TICKS_PER_SECOND = 20f

        fun ticks(seconds: Float): Long = ceil(seconds.coerceAtLeast(0f) * TICKS_PER_SECOND).toLong()
    }
}

/** Starts montages on entities and keeps their handles ticking on the server. */
object MontagePlayback {
    private val playing = ArrayList<MontageHandle>()

    /**
     * Plays [montage] on [entity] over its animator; null, with a warning, when the server cannot read it.
     * The same montage started again on the same entity starts over and ends the handle it had.
     */
    fun start(entity: Entity, montage: String, fadeIn: Float, fadeOut: Float, speed: Float): MontageHandle? {
        check(!entity.level().isClientSide) { "Montages are started on the server" }
        val asset = MontageAssets.of(montage, client = false) ?: run {
            HollowEngine.LOGGER.warn("Montage '{}' could not be read, so {} plays nothing", montage, entity)
            return null
        }

        val now = entity.level().gameTime
        val pace = speed.coerceAtLeast(MIN_SPEED)
        val layer = MontageLayerSpec(
            id = layerId(montage),
            montage = montage,
            speed = pace,
            fadeIn = fadeIn,
            fadeOut = fadeOut,
            startGameTime = now,
            removeAtGameTime = now + MontageHandle.ticks(asset.duration / pace + fadeOut),
        )
        playing.filter { it.entity === entity && it.montage == montage }.forEach(MontageHandle::replaced)
        edit(entity) { it.withMontage(layer) }
        return MontageHandle(entity, montage, layer, asset.duration, asset.events.sortedBy(MontageEvent::time)).also(
                playing::add
            )
    }

    /** Stops [montage] on [entity] however it was started, as a script that kept no handle does. */
    fun stop(entity: Entity, montage: String, fadeOut: Float) {
        val handles = playing.filter { it.entity === entity && it.montage == montage && it.isPlaying }
        if (handles.isNotEmpty()) return handles.forEach { it.stop(fadeOut) }

        val now = entity.level().gameTime
        edit(entity) { animations ->
            val layer = animations.montages.firstOrNull { it.id == layerId(montage) && it.stopAtGameTime == null }
                ?: return@edit animations
            animations.withMontage(
                layer.copy(
                    fadeOut = fadeOut,
                    stopAtGameTime = now,
                    removeAtGameTime = now + MontageHandle.ticks(fadeOut)
                )
            )
        }
    }

    internal fun animationsOf(entity: Entity): AnimationsComponent? {
        val id = ComponentDescriptorRegistry.idFor(AnimationsComponent::class) ?: return null
        return AttachmentRegistry.componentsById(entity)[id] as? AnimationsComponent
    }

    internal fun edit(entity: Entity, change: (AnimationsComponent) -> AnimationsComponent) {
        val id = ComponentDescriptorRegistry.idFor(AnimationsComponent::class) ?: return
        val components = AttachmentRegistry.componentsById(entity)
        val current = components[id] as? AnimationsComponent ?: AnimationsComponent()
        val updated = change(current)
        if (updated != current) components[id] = updated
    }

    internal fun tick(server: MinecraftServer) {
        if (playing.isEmpty()) return
        playing.toList().forEach { handle -> if (!handle.tick()) playing.remove(handle) }
    }

    private fun layerId(montage: String) = "montage:$montage"

    private const val MIN_SPEED = 0.01f
}

@SubscribeEvent
fun onMontageServerTick(event: TickEvent.Server) {
    MontagePlayback.tick(event.server)
}
