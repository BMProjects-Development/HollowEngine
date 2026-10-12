package ru.hollowhorizon.hollowengine.client.vfx

import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.sounds.SoundEmitter
import ru.hollowhorizon.hollowengine.client.sounds.SoundEmitterState
import ru.hollowhorizon.hollowengine.client.sounds.SyncedSound
import ru.hollowhorizon.hollowengine.common.sounds.SoundMode
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxSoundSpec

/**
 * Plays the sound of a [VfxSoundSpec] node from where the node is, timed from when it turned on. In the world it
 * comes from the effect; in a preview, which has no place in the world, from all around the listener.
 */
class VfxSoundNode(private val spec: VfxSoundSpec, private val node: VfxNodeRuntime) : VfxNodeBehavior {
    private val volume = VfxSamplers.driven(
        VfxSamplers.scalar(spec.volume, node.expressions, 1f, VfxRangeMode.PER_PARTICLE),
        node.drive(VfxProperty.SOUND_VOLUME),
    )
    private val pitch = VfxSamplers.driven(
        VfxSamplers.scalar(spec.pitch, node.expressions, 1f, VfxRangeMode.PER_PARTICLE),
        node.drive(VfxProperty.SOUND_PITCH),
    )
    private val source: SoundSource? = spec.source.takeIf(String::isNotBlank)?.let { text ->
        runCatching { SoundSource.parse(text) }
            .onFailure { HollowEngine.LOGGER.error("Invalid sound '{}' in effect {}", text, node.instance.asset, it) }
            .getOrNull()
    }
    private val sound = SyncedSound()
    private val inWorld = node.instance.environment is VfxWorldEnvironment

    /** The effect's time when the node turned on, NaN while it is off. */
    private var since = Float.NaN
    private var lastFrame = Long.MIN_VALUE

    /** When the effect was last drawn, so the sound falls silent once nobody draws it. */
    private var lastSeen = 0L

    override fun update(dt: Float) {
        val active = node.isActive
        if (active && since.isNaN()) since = node.instance.time
        if (!active) since = Float.NaN
    }

    override fun restart() {
        since = Float.NaN
    }

    override fun stop(immediate: Boolean) {
        sound.stop(if (immediate) 0f else null)
    }

    override fun frame() {
        val frame = TickHandler.renderFrame
        if (frame == lastFrame) return
        lastFrame = frame
        lastSeen = System.nanoTime()

        val source = source ?: return
        val instance = node.instance
        if (since.isNaN() || instance.isPaused || instance.isStopping) {
            if (sound.isPlaying) sound.stop()
            return
        }
        val playback = spec.playback.let {
            it.copy(
                volume = it.volume * volume.eval(node.context).coerceAtLeast(0f),
                pitch = it.pitch * pitch.eval(node.context).coerceAtLeast(MIN_PITCH),
                mode = if (inWorld) it.mode else SoundMode.GLOBAL,
            )
        }
        sound.update(source, playback, emitter(), (instance.time - since).toDouble().coerceAtLeast(0.0))
    }

    private fun emitter(): SoundEmitter = SoundEmitter {
        val instance = node.instance
        when {
            System.nanoTime() - lastSeen > TIMEOUT -> SoundEmitterState.Removed
            !inWorld -> SoundEmitterState.Listener
            else -> {
                val at = node.frame.position
                val velocity = Vec3(instance.velocityX.toDouble(), instance.velocityY.toDouble(), instance.velocityZ.toDouble())
                SoundEmitterState.Available(instance.origin.add(at.x.toDouble(), at.y.toDouble(), at.z.toDouble()), velocity)
            }
        }
    }

    private companion object {
        const val MIN_PITCH = 0.01f

        /** How long, in nanoseconds, the sound outlives the last frame its effect was drawn in. */
        const val TIMEOUT = 250_000_000L
    }
}
