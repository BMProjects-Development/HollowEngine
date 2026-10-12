package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RespecAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundMode
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

/**
 * A sound hung on a bone or on the model: at the point it is placed, or through its areas. World poses are prepared
 * before culling, independently of render passes, so it is heard while its entity is out of sight.
 */
class SoundBoneAttachment(
    override var spec: SoundBoneAttachmentSpec,
    private val node: RuntimeNode,
    private val model: ModelAttachment?,
) : Attachment(node), RespecAttachment {
    private var binding: SoundAttachments.Binding? = null
    private val sound = SyncedSound()
    private var parsed: Pair<String, SoundSource?>? = null

    /** When the preview last asked for the sound, so it falls silent once the preview stops asking. */
    private var previewNanos = 0L

    override fun respec(spec: RigAttachmentSpec) {
        if (spec is SoundBoneAttachmentSpec) this.spec = spec
    }

    override fun updateWorldPose(modelToWorld: TrsTransformF, entity: Entity) {
        if (entity.isRemoved) return
        val bindingId = "${entity.uuid}/${model?.soundScope ?: node.name}/${spec.id}"
        val current = binding ?: SoundAttachments.register(bindingId, alive = { !entity.isRemoved }).also { binding = it }

        val toWorld = Matrix4f(modelToWorld.matrixF.asMatrix4f()).mul(globalMatrix.asMatrix4f())
        val origin = toWorld.getTranslation(Vector3f())
        val shape = if (spec.areas.isEmpty()) null else SoundShape(toWorld, spec.areas)
        current.update(Vec3(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()), entity.soundVelocity(), shape)
        play(SoundAttachments.emitter(bindingId))
    }

    /**
     * Plays the sound in a preview of the model, around the listener: the preview has no place in the world. Only what
     * a montage keeps time for plays there, so a preview stays quiet while its timeline stands. It falls silent soon
     * after the preview stops calling this.
     */
    fun updatePreview() {
        if (spec.clock == null) {
            if (sound.isPlaying) sound.stop()
            return
        }
        previewNanos = System.nanoTime()
        val emitter = SoundEmitter {
            if (System.nanoTime() - previewNanos > PREVIEW_TIMEOUT) SoundEmitterState.Removed else SoundEmitterState.Listener
        }
        play(emitter, preview = true)
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
        sound.update(source!!, playback, emitter, clock?.toDouble())
    }

    /** What [SoundBoneAttachmentSpec.source] names, read once per value; null, with one error, when it names nothing. */
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

    override fun close() {
        binding?.close()
        binding = null
        sound.close()
    }
}

/** How long, in nanoseconds, a preview sound outlives the last frame that asked for it. */
private const val PREVIEW_TIMEOUT = 250_000_000L
