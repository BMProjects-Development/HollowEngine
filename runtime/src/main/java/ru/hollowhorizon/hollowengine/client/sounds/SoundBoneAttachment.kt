package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RespecAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource

/** World poses are prepared before culling, independently of render passes and previews. */
class SoundBoneAttachment(
    override var spec: SoundBoneAttachmentSpec,
    private val node: RuntimeNode,
    private val model: ModelAttachment?,
) : Attachment(node), RespecAttachment {
    private var binding: SoundAttachments.Binding? = null
    private var playing: ManagedSound? = null
    private var played = false
    private var boneOffset: Vec3? = null

    override fun respec(spec: RigAttachmentSpec) {
        if (spec !is SoundBoneAttachmentSpec) return
        val previous = this.spec
        this.spec = spec
        if (!spec.autoPlay || previous.source != spec.source ||
            previous.playback.copy(volume = spec.playback.volume, pitch = spec.playback.pitch) != spec.playback) {
            playing?.close()
            playing = null
            played = false
        } else {
            playing?.setVolume(spec.playback.volume)
            playing?.setPitch(spec.playback.pitch)
        }
    }

    override fun updateWorldPose(modelToWorld: TrsTransformF, entity: Entity) {
        val owner = entity
        if (owner.isRemoved) return
        val bindingId = "${owner.uuid}/${model?.soundScope ?: node.name}/${spec.id}"
        val current = binding ?: SoundAttachments.register(bindingId, alive = { !owner.isRemoved }, source = SoundEmitter {
            if (owner.isRemoved) SoundEmitterState.Removed
            else boneOffset?.let { SoundEmitterState.Available(owner.getPosition(TickHandler.partialTick).add(it), owner.deltaMovement.scale(20.0)) }
                ?: SoundEmitterState.Pending
        })
            .also { binding = it }
        val matrix = Matrix4f(modelToWorld.matrixF.asMatrix4f()).mul(globalMatrix.asMatrix4f())
        val translation = matrix.getTranslation(Vector3f())
        val worldPosition = Vec3(translation.x.toDouble(), translation.y.toDouble(), translation.z.toDouble())
        boneOffset = worldPosition.subtract(owner.getPosition(TickHandler.partialTick))
        current.update(worldPosition)
        if (!played && spec.autoPlay && spec.source.isNotBlank()) {
            played = true
            try {
                playing = ClientSounds.play(SoundSource.parse(spec.source), spec.playback, SoundAttachments.emitter(bindingId))
            } catch (error: Exception) {
                HollowEngine.LOGGER.error("Invalid sound attachment '{}'", spec.id, error)
            }
        }
    }

    override fun close() {
        binding?.close()
        binding = null
        playing?.close()
        playing = null
    }
}
