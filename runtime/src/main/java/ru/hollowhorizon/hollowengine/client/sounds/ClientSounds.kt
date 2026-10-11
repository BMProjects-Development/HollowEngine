package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.ChannelAccess
import net.minecraft.world.phys.Vec3
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource as SoundCategory
import ru.hollowhorizon.hollowengine.bridge.AudioChannelAccess
import org.lwjgl.openal.AL10.AL_VELOCITY
import org.lwjgl.openal.AL10.alSource3f
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.sounds.SoundEmitterSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** All managed sounds are native Minecraft sound instances, including raw files and addon codecs. */
object ClientSounds {
    private val tokens = AtomicLong()
    private val sounds = LinkedHashMap<String, ManagedSound>()
    private data class Replay(val source: SoundSource, val settings: SoundPlayback, val emitter: SoundEmitter)
    private val replays = LinkedHashMap<String, Replay>()
    internal fun replayLater(id: String, source: SoundSource, settings: SoundPlayback, emitter: SoundEmitter) {
        replays[id] = Replay(source, settings, emitter)
    }
    internal fun nextToken(): Long = tokens.incrementAndGet()

    fun play(
        source: SoundSource,
        settings: SoundPlayback = SoundPlayback(),
        emitter: SoundEmitter = SoundEmitter { SoundEmitterState.Listener },
        id: String = UUID.randomUUID().toString(),
        timeoutSeconds: Float = 5f,
    ): ManagedSound {
        check(Minecraft.getInstance().isSameThread) { "Play sounds on the client thread" }
        require(timeoutSeconds.isFinite() && timeoutSeconds >= 0f)
        replays.remove(id)
        sounds[id]?.let { if (it.reconfigure(source, settings, emitter)) return it }
        sounds.remove(id)?.close()
        val sound = ManagedSound(id, source, settings, emitter)
        sound.timeoutSeconds = timeoutSeconds
        sounds[id] = sound
        start(sound)
        return sound
    }

    fun play(source: SoundSource, settings: SoundPlayback, emitter: SoundEmitterSpec, id: String): ManagedSound =
        play(source, settings, emitter.createEmitter(), id,
            when (emitter) {
                is SoundEmitterSpec.Attachment -> emitter.timeoutSeconds
                is SoundEmitterSpec.Rig -> emitter.timeoutSeconds
                is SoundEmitterSpec.Effect -> emitter.timeoutSeconds
                else -> 5f
            })

    fun find(id: String): ManagedSound? = sounds[id]
    fun stopMatching(location: ResourceLocation?, category: SoundCategory?) {
        sounds.values.filter { (location == null || it.location == location) && (category == null || it.source == category) }
            .forEach { stop(it.id, 0f) }
    }
    fun stop(id: String, fadeOut: Float? = null) {
        replays.remove(id)
        sounds[id]?.let { if (fadeOut == null) it.fadeOut() else it.fadeOut(fadeOut) }
    }

    private fun start(sound: ManagedSound) {
        try {
            when (sound.refresh()) {
                SoundEmitterState.Listener, is SoundEmitterState.Available -> {
                    sound.started = true
                    Minecraft.getInstance().soundManager.play(sound)
                }
                else -> Unit
            }
        } catch (error: Exception) {
            sound.failure = error
            sound.close()
        }
    }

    /** Called after SoundEngine.tick: native EOF, /stopsound and rejected plays release the same owner. */
    fun tick(paused: Boolean) {
        if (paused) return
        SoundAttachments.prune()
        val manager = Minecraft.getInstance().soundManager
        val iterator = sounds.values.iterator()
        while (iterator.hasNext()) {
            val sound = iterator.next()
            if (!sound.started && !sound.isStopped) {
                sound.waitSeconds += 0.05f
                start(sound)
                if (!sound.started && !sound.isStopped && sound.timeoutSeconds > 0f && sound.waitSeconds >= sound.timeoutSeconds) {
                    sound.failure = TimeoutException("Timed out waiting for the sound emitter")
                    sound.close()
                }
            }
            if (sound.isStopped || sound.started && !manager.isActive(sound)) {
                sound.failure?.let { HollowEngine.LOGGER.error("Sound '{}' failed", sound.id, it) }
                sound.close()
                iterator.remove()
            }
        }
        val pending = replays.toMap()
        replays.clear()
        pending.forEach { (id, replay) -> play(replay.source, replay.settings, replay.emitter, id) }
    }

    fun clear() {
        replays.clear()
        sounds.values.forEach(ManagedSound::close)
        sounds.clear()
    }

    fun frame(channels: Map<SoundInstance, ChannelAccess.ChannelHandle>) {
        if (Minecraft.getInstance().isPaused) return
        channels.forEach { (instance, channel) ->
            if (instance !is ManagedSound || instance.isStopped) return@forEach
            instance.refresh()
            val position = Vec3(instance.x, instance.y, instance.z)
            val relative = instance.isRelative
            val range = instance.range
            val velocity = instance.velocity
            channel.execute {
                it.setSelfPosition(position)
                it.setRelative(relative)
                if (relative) it.disableAttenuation() else it.linearAttenuation(range)
                alSource3f((it as AudioChannelAccess).`hollowengine$getSource`(), AL_VELOCITY,
                    velocity.x.toFloat(), velocity.y.toFloat(), velocity.z.toFloat())
            }
        }
    }
}
