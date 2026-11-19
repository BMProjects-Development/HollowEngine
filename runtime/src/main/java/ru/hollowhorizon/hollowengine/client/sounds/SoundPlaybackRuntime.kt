package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.scene.ClientComponent
import ru.hollowhorizon.hollowengine.client.scene.ComponentUpdate
import ru.hollowhorizon.hollowengine.client.utils.math.copyTo
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.sounds.Sound
import ru.hollowhorizon.hollowengine.common.sounds.SoundArea
import ru.hollowhorizon.hollowengine.common.sounds.SoundMode
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import java.util.*

/**
 * A sound hung on a bone or on the model: at the point it is placed, or through its areas. World poses are prepared
 * before culling, independently of render passes, so it is heard while its entity is out of sight.
 */
internal class SoundPlaybackRuntime(
    private val node: SceneObject,
    private val id: UUID,
    private var spec: Sound,
) : ClientComponent {
    private var active = false
    private var binding: SoundAttachments.Binding? = null
    private val sound = SyncedSound()
    private var parsed: Pair<String, SoundSource?>? = null
    private val toWorld = Matrix4d()
    private val lastSceneToWorld = Matrix4d()
    private val nodeToScene = Matrix4f()
    private var emitter: SoundEmitter? = null
    private var worldVersion = 0
    private var position = Vec3.ZERO
    private var areas: List<SoundArea>? = null
    private var shape: SoundShape? = null
    private var paused = false

    /** When the preview last asked for the sound, so it falls silent once the preview stops asking. */
    private var previewNanos = 0L
    private val previewEmitter = SoundEmitter {
        if (System.nanoTime() - previewNanos > PREVIEW_TIMEOUT) SoundEmitterState.Removed else SoundEmitterState.Listener
    }

    override fun activate() {
        active = true
    }

    override fun suspend() {
        active = false
        pause()
    }

    override fun change(value: Any) {
        spec = value as Sound
    }

    override fun update(frame: ComponentUpdate) {
        when (frame) {
            is ComponentUpdate.World -> updateWorld(frame.sceneToWorld, frame.entity, frame.scope)
            is ComponentUpdate.Preview -> if (frame.playSounds) updatePreview()
        }
    }

    private fun updateWorld(sceneToWorld: Matrix4dc, entity: Entity, scope: String) {
        if (entity.isRemoved || !active || !node.modelVisible) {
            pause()
            return
        }
        val current = binding ?: run {
            val address = "${entity.uuid}/$scope/${node.address}/$id/${spec.id}"
            SoundAttachments.register(address, alive = { !entity.isRemoved }).also {
                binding = it
                emitter = SoundEmitter(it::sample)
            }
        }
        val transform = node.worldTransform
        val version = node.worldVersion
        val moved = areas == null || worldVersion != version || lastSceneToWorld != sceneToWorld
        if (moved) {
            worldVersion = version
            lastSceneToWorld.set(sceneToWorld)
            toWorld.set(sceneToWorld).mul(transform.copyTo(nodeToScene))
            position = Vec3(toWorld.m30(), toWorld.m31(), toWorld.m32())
        }
        if (moved || areas != spec.areas) {
            areas = spec.areas
            shape = spec.areas.takeIf { it.isNotEmpty() }?.let { SoundShape(Matrix4f().set(toWorld), it) }
        }
        current.update(position, entity.soundVelocity(), shape)
        resume()
        play(checkNotNull(emitter))
    }

    private fun updatePreview() {
        if (!active || !node.modelVisible) {
            pause()
            return
        }
        if (spec.clock == null) {
            if (sound.isPlaying) sound.stop()
            return
        }
        resume()
        previewNanos = System.nanoTime()
        play(previewEmitter, preview = true)
    }

    private fun play(emitter: SoundEmitter, preview: Boolean = false) {
        val source = source()
        val clock = spec.clock
        val wanted = source != null && spec.playing && (clock != null || spec.autoPlay)
        if (!wanted) {
            if (sound.isPlaying) sound.stop()
            return
        }
        val playback = if (preview) spec.playback.copy(mode = SoundMode.GLOBAL) else spec.playback
        sound.update(source, playback, emitter, clock?.toDouble())
    }

    /** What [Sound.source] names, read once per value; null, with one error, when it names nothing. */
    private fun source(): SoundSource? {
        val text = spec.source
        parsed?.takeIf { it.first == text }?.let { return it.second }
        val source = if (text.isBlank()) null else try {
            SoundSource.parse(text)
        } catch (error: Exception) {
            HollowEngine.LOGGER.error("Invalid sound attachment '{}'", spec.id, error)
            null
        }
        parsed = text to source
        return source
    }

    private fun pause() {
        if (paused) return
        paused = true
        binding?.markTemporarilyUnavailable()
        sound.pause()
    }

    private fun resume() {
        if (!paused) return
        paused = false
        sound.resume()
    }

    override fun remove() {
        binding?.close()
        binding = null
        emitter = null
        sound.close()
    }
}

/** How long, in nanoseconds, a preview sound outlives the last frame that asked for it. */
private const val PREVIEW_TIMEOUT = 250_000_000L
