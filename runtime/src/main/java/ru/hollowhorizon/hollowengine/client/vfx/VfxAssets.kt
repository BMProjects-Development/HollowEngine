package ru.hollowhorizon.hollowengine.client.vfx

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxShaders
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxFormat

/**
 * The `.vfx` files of every loaded pack, by resource id.
 */
object VfxAssets : ResourceManagerReloadListener {
    private val effects = HashMap<ResourceLocation, VfxEffect>()

    val ids: Set<ResourceLocation> get() = effects.keys

    operator fun get(id: ResourceLocation): VfxEffect? = effects[id]

    operator fun get(id: String): VfxEffect? = ResourceLocation.tryParse(normalize(id))?.let(effects::get)

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        effects.clear()
        resourceManager.listResources(VfxFormat.RESOURCE_FOLDER) { it.path.endsWith(VfxFormat.EXTENSION) }
            .forEach { (location, resource) ->
                try {
                    effects[location] = resource.open().use { stream ->
                        VfxFormat.read(stream.readBytes().toString(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    HollowEngine.LOGGER.warn("Could not read effect {}: {}", location, e.message)
                }
            }
        VfxScenes.onAssetsReloaded()
        VfxShaders.clear()
        VfxModelSurfaces.clear()
    }

    /** Accepts both `pack:vfx/fire.vfx` and the bare `pack:fire` an author is likely to type. */
    private fun normalize(id: String): String {
        val trimmed = id.trim()
        val resource = trimmed.rl
        val path = resource.path
        val withFolder = if (path.startsWith("${VfxFormat.RESOURCE_FOLDER}/")) path else "${VfxFormat.RESOURCE_FOLDER}/$path"
        val withExtension = if (withFolder.endsWith(VfxFormat.EXTENSION)) withFolder else "$withFolder${VfxFormat.EXTENSION}"
        return "${resource.namespace}:$withExtension"
    }
}
