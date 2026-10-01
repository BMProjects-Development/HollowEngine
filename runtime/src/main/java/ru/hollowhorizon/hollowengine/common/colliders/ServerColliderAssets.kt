package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.ReloadListener
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.ModelMetadata
import ru.hollowhorizon.hollowengine.common.models.ModelResourceIO
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.ServerModelAnimationMetadata
import ru.hollowhorizon.hollowengine.common.models.StandardPlayerAnimatorPreset
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import java.io.InputStream

/**
 * What the server reads next to a model to place its colliders: the rig, and the animator the model
 * wears. Read on first use and kept until the next datapack reload.
 */
@ReloadListener
object ServerColliderAssets : ResourceManagerReloadListener {
    class Assets(private val model: String, val rig: ModelRig, val animator: Animator?) {
        /** What the entity's colliders are posed from; loads the model, so only when the rig has colliders. */
        val pose: ColliderPoseAssets? by lazy {
            if (!rig.hasColliders()) null
            else ServerModelAnimationMetadata.model(model)?.let { ColliderPoseAssets(rig, animator, it) }
        }
    }

    private val cache = HashMap<String, Assets>()

    fun of(model: String): Assets = cache.getOrPut(model) { load(model) }

    /** Forgets [model], so an edited rig is read again; the IDE calls it after saving one. */
    fun invalidate(model: String) {
        cache.remove(model)
    }

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        cache.clear()
        ServerModelAnimationMetadata.clearCache()
    }

    private fun load(model: String): Assets {
        val location = ResourceLocation.tryParse(model) ?: return Assets(model, ModelRig.EMPTY, null)
        val rig = read(location.withSuffix(RIG_SUFFIX)) {
            NBTFormat.deserialize(ModelRig.serializer(), it.loadAsNBT())
        } ?: ModelRig.EMPTY
        if (!rig.hasColliders()) return Assets(model, rig, null)

        val controller = read(location.withSuffix(METADATA_SUFFIX)) {
            ModelMetadata.parse(it.readBytes().decodeToString(), model)
        }?.animationController
        val animator = when (controller?.toString()) {
            null -> null
            StandardPlayerAnimatorPreset.ID -> StandardPlayerAnimatorPreset.create()
            else -> read(controller) { NBTFormat.deserialize(Animator.serializer(), it.loadAsNBT()) }
        }
        return Assets(model, rig, animator)
    }

    private fun <T> read(location: ResourceLocation, decode: (InputStream) -> T): T? {
        if (!ModelResourceIO.exists(location)) return null
        return runCatching { ModelResourceIO.open(location).use(decode) }
            .onFailure { HollowEngine.LOGGER.warn("Could not read '{}' for colliders: {}", location, it.message) }
            .getOrNull()
    }

    private const val RIG_SUFFIX = ".rig"
    private const val METADATA_SUFFIX = ".hemeta"
}
