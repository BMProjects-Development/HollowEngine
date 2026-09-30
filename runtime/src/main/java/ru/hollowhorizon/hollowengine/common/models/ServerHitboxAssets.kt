package ru.hollowhorizon.hollowengine.common.models

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import java.util.concurrent.ConcurrentHashMap

/** Server-side companions of the model loaded by [ServerModelAnimationMetadata]. */
object ServerHitboxAssets {
    data class Assets(val rig: ModelRig, val animator: Animator?) {
        val hasHitboxes = rig.attachments.any { it is HitboxAttachmentSpec } ||
            rig.bones.values.any { bone -> bone.attachments.any { it is HitboxAttachmentSpec } }
    }
    private val cache = ConcurrentHashMap<String, Assets>()

    fun of(model: String): Assets = cache.computeIfAbsent(model) {
        val location = net.minecraft.resources.ResourceLocation.tryParse(model)
            ?: return@computeIfAbsent Assets(ModelRig.EMPTY, null)
        val rig = read(location.withSuffix(".rig")) {
            NBTFormat.deserialize(ModelRig.serializer(), it.loadAsNBT())
        } ?: ModelRig.EMPTY
        val metadata = read(location.withSuffix(".hemeta")) {
            ModelMetadata.parse(it.readBytes().decodeToString(), model)
        }
        val controller = metadata?.animationController
        val animator = when (controller?.toString()) {
            null -> null
            StandardPlayerAnimatorPreset.ID -> StandardPlayerAnimatorPreset.create()
            else -> read(controller) { NBTFormat.deserialize(Animator.serializer(), it.loadAsNBT()) }
        }
        Assets(rig, animator)
    }

    fun invalidate(model: String) { cache.remove(model) }
    fun clear() { cache.clear() }

    private fun <T> read(location: net.minecraft.resources.ResourceLocation, decode: (java.io.InputStream) -> T): T? {
        if (!ModelResourceIO.exists(location)) return null
        return runCatching { ModelResourceIO.open(location).use(decode) }.onFailure {
            HollowEngine.LOGGER.warn("Could not read hitbox asset '{}': {}", location, it.message)
        }.getOrNull()
    }
}
