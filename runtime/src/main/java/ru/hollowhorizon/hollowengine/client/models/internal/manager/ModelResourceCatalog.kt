package ru.hollowhorizon.hollowengine.client.models.internal.manager

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import ru.hollowhorizon.hollowengine.common.models.ModelMetadata

/** Resources available for selection, independent of load attempts and GPU residency. */
internal class ModelResourceCatalog private constructor(
    val locations: Set<ResourceLocation>,
    private val metadata: Map<ResourceLocation, ModelMetadata>,
) {
    val preloaded: Set<ResourceLocation> = metadata.filterValues(ModelMetadata::preload).keys.toSet()

    fun metadata(location: ResourceLocation?): ModelMetadata = metadata[location] ?: ModelMetadata.EMPTY

    companion object {
        val EMPTY = ModelResourceCatalog(emptySet(), emptyMap())

        fun read(manager: ResourceManager, formats: Set<String>): ModelResourceCatalog {
            val locations = manager.listResources("models") { location ->
                formats.any { location.path.endsWith(".$it", ignoreCase = true) }
            }.keys.toSet()
            val metadata = buildMap {
                for (location in locations) {
                    val resource = manager.getResource(location.withSuffix(".hemeta")).orElse(null) ?: continue
                    val source = runCatching { resource.open().use { it.readBytes().decodeToString() } }.getOrDefault("")
                    put(location, ModelMetadata.parse(source, location.toString()))
                }
            }
            return ModelResourceCatalog(locations, metadata)
        }
    }
}
