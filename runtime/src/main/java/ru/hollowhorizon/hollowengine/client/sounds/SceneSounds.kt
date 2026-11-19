package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.scene.ClientSceneContext
import ru.hollowhorizon.hollowengine.client.scene.SceneComponentKey
import ru.hollowhorizon.hollowengine.client.scene.SceneComponentSystem
import ru.hollowhorizon.hollowengine.client.utils.math.copyTo
import ru.hollowhorizon.hollowengine.common.scene.ComponentActivity
import ru.hollowhorizon.hollowengine.common.sounds.*

internal val ClientSceneContext.sounds: SceneSounds?
    get() = systemFor(Sound::class) as? SceneSounds

/** Owns playback resources for scene bindings and reads their latest data at each update. */
internal class SceneSounds : SceneComponentSystem {
    private val voices = HashMap<SceneComponentKey, Voice>()
    private val active = LinkedHashSet<Voice>()
    private var spatialFrame = Long.MIN_VALUE

    val hasActive: Boolean get() = active.isNotEmpty()

    override fun onStateChanged(binding: SceneComponentKey, state: ComponentActivity) {
        when (state) {
            ComponentActivity.ACTIVE -> active.add(voices.getOrPut(binding) { Voice(binding) })
            ComponentActivity.SUSPENDED -> voices[binding]?.let {
                active.remove(it)
                it.pause()
            }

            ComponentActivity.REMOVED -> voices.remove(binding)?.let {
                active.remove(it)
                it.close()
            }
        }
    }

    /** Publishes emitters before culling, with positions and velocities in world space. */
    fun updateSpatial(sceneToWorld: Matrix4dc, host: Entity, scope: String, frame: Long) {
        if (spatialFrame == frame) return
        spatialFrame = frame
        if (host.isRemoved) {
            active.forEach(Voice::pause)
            return
        }
        val velocity = host.soundVelocity()
        for (voice in active) {
            val value = voice.value ?: continue
            if (!voice.key.owner.modelVisible) {
                voice.pause()
                continue
            }
            val emitter = voice.place(sceneToWorld, host, scope, value, velocity)
            voice.play(value, value.playback, emitter)
        }
    }

    /** Auditions sounds with a timeline clock through the listener output. */
    fun updateAudition(enabled: Boolean) {
        for (voice in active) {
            val value = voice.value ?: continue
            if (!enabled || !voice.key.owner.modelVisible) voice.pause()
            else if (value.clock == null) voice.stop()
            else voice.audition(value)
        }
    }

    private class Voice(val key: SceneComponentKey) : AutoCloseable {
        val value: Sound? get() = key.value as? Sound
        private val sound = SyncedSound()
        private var parsed: Pair<String, SoundSource?>? = null
        private var positional: SoundAttachments.Binding? = null
        private var emitter: SoundEmitter? = null
        private val toWorld = Matrix4d()
        private val lastSceneToWorld = Matrix4d()
        private val nodeToScene = Matrix4f()
        private var worldVersion = 0
        private var position = Vec3.ZERO
        private var areas: List<SoundArea>? = null
        private var shape: SoundShape? = null
        private var paused = false
        private var auditionNanos = 0L
        private var auditionPlayback: Pair<SoundPlayback, SoundPlayback>? = null
        private val listener = SoundEmitter {
            if (System.nanoTime() - auditionNanos > AUDITION_TIMEOUT) SoundEmitterState.Removed else SoundEmitterState.Listener
        }

        fun audition(value: Sound) {
            auditionNanos = System.nanoTime()
            val playback = value.playback
            val global =
                auditionPlayback?.takeIf { it.first == playback }?.second ?: playback.copy(mode = SoundMode.GLOBAL)
                    .also { auditionPlayback = playback to it }
            play(value, global, listener)
        }

        fun place(sceneToWorld: Matrix4dc, host: Entity, scope: String, value: Sound, velocity: Vec3): SoundEmitter {
            val node = key.owner
            val binding = positional ?: SoundAttachments.register(
                "${host.uuid}/$scope/${node.address}/${key.id}/${value.id}", alive = { !host.isRemoved },
            ).also {
                positional = it
                emitter = SoundEmitter(it::sample)
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
            if (moved || areas != value.areas) {
                areas = value.areas
                shape = value.areas.takeIf { it.isNotEmpty() }?.let { SoundShape(Matrix4f().set(toWorld), it) }
            }
            binding.update(position, velocity, shape)
            return checkNotNull(emitter)
        }

        fun play(value: Sound, playback: SoundPlayback, emitter: SoundEmitter) {
            if (paused) {
                paused = false
                sound.resume()
            }
            val source = source(value)
            if (source == null || !value.playing || value.clock == null && !value.autoPlay) {
                stop()
                return
            }
            sound.update(source, playback, emitter, value.clock?.toDouble())
        }

        private fun source(value: Sound): SoundSource? {
            val text = value.source
            parsed?.takeIf { it.first == text }?.let { return it.second }
            val source = if (text.isBlank()) null else try {
                SoundSource.parse(text)
            } catch (error: Exception) {
                HollowEngine.LOGGER.error("Invalid sound component '{}'", value.id, error)
                null
            }
            parsed = text to source
            return source
        }

        fun stop() {
            if (sound.isPlaying) sound.stop()
        }

        fun pause() {
            if (paused) return
            paused = true
            positional?.markTemporarilyUnavailable()
            sound.pause()
        }

        override fun close() {
            positional?.close()
            positional = null
            emitter = null
            sound.close()
        }
    }

    private companion object {
        const val AUDITION_TIMEOUT = 250_000_000L
    }
}
