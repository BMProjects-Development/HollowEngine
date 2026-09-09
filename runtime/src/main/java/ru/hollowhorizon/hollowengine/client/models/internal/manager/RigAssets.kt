package ru.hollowhorizon.hollowengine.client.models.internal.manager

import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize

/**
 * Rigs, that models wear, by the model they belong to.
 *
 * Rig lives next to its model as `<model file>.rig`.
 */
object RigAssets {
    @Volatile
    private var rigs: Map<ResourceLocation, ModelRig> = emptyMap()

    /** The rig of [model], or an empty one when it has none. */
    fun of(model: ResourceLocation?): ModelRig = model?.let { rigs[it] } ?: ModelRig.EMPTY

    @Synchronized
    fun register(model: ResourceLocation, rig: ModelRig) {
        rigs = rigs + (model to rig)
    }

    fun locationOf(model: ResourceLocation): ResourceLocation = model.withSuffix(SUFFIX)

    @Synchronized
    fun reload(manager: ResourceManager, models: Collection<ResourceLocation>) {
        val next = HashMap<ResourceLocation, ModelRig>()
        models.forEach { model ->
            val resource = manager.getResource(locationOf(model)).orElse(null) ?: return@forEach
            try {
                val tag = resource.open().use { it.loadAsNBT() }
                next[model] = NBTFormat.deserialize<ModelRig, Tag>(tag)
            } catch (e: Exception) {
                HollowEngine.LOGGER.warn("Could not read rig of '{}': {}", model, e.message)
            }
        }
        rigs = next
    }

    private const val SUFFIX = ".rig"
}
