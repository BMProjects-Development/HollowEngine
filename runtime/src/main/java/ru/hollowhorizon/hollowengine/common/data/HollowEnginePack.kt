package ru.hollowhorizon.hollowengine.common.data

import com.google.gson.JsonObject
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.FilePackResources
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.metadata.MetadataSectionSerializer
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.IoSupplier
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterResourcePacksEvent
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.utils.literal
import java.io.InputStream
import java.util.*

object HollowEnginePack : PathPackResources(
    PackLocationInfo(
        "HollowEngine Folder Resources", "HollowEngine Folder Resources".literal, PackSource.BUILT_IN,
        Optional.empty()
    ), DirectoryManager.HOLLOW_ENGINE
) {
    private val packMetadata: String = JsonObject().apply {
        add("pack", JsonObject().apply {
            addProperty("description", "HollowEngine Folder Resources")
            addProperty("pack_format", SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES))
            // in new version pack_format not supported
            /*
            // if version >1.21.1
            addProperty("min_format", SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major)
            addProperty("max_format", SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major)
            */
        })
    }.toString()

    override fun getRootResource(vararg strings: String): IoSupplier<InputStream>? {
        return when (strings[0]) {
            PACK_META -> IoSupplier { packMetadata.byteInputStream() }
            else -> super.getRootResource(*strings)
        }
    }
}

@SubscribeEvent
fun addPackListeners(event: RegisterResourcePacksEvent) {
    val supplier = object : Pack.ResourcesSupplier {
        override fun openPrimary(location: PackLocationInfo): PackResources = HollowEnginePack

        override fun openFull(location: PackLocationInfo, metadata: Pack.Metadata): PackResources {
            val addons = HollowAddonManager.resourcePacks.map { addon ->
                val addonLocation = PackLocationInfo(
                    "hollowengine-addon/${addon.addonId}", addon.name.literal, PackSource.BUILT_IN, Optional.empty(),
                )
                FilePackResources.FileResourcesSupplier(addon.file).openPrimary(addonLocation)
            }
            return if (addons.isEmpty()) HollowEnginePack else LayeredPackResources(listOf(HollowEnginePack) + addons)
        }
    }
    val pack = Pack.readMetaAndCreate(
        HollowEnginePack.location(),
        supplier,
        PackType.CLIENT_RESOURCES,
        PackSelectionConfig(true, Pack.Position.TOP, true),
    ) ?: error("HollowEngine folder resources have no pack metadata")
    event.addPack(pack)
}

/** Several packs seen as one, the first above the rest; the root files and metadata are the first's. */
private class LayeredPackResources(private val layers: List<PackResources>) : PackResources {
    override fun getRootResource(vararg path: String): IoSupplier<InputStream>? = layers.first().getRootResource(*path)

    override fun getResource(type: PackType, location: ResourceLocation): IoSupplier<InputStream>? =
        layers.firstNotNullOfOrNull { it.getResource(type, location) }

    override fun listResources(type: PackType, namespace: String, path: String, output: PackResources.ResourceOutput) {
        val merged = LinkedHashMap<ResourceLocation, IoSupplier<InputStream>>()
        layers.asReversed().forEach { layer ->
            layer.listResources(type, namespace, path) { location, resource -> merged[location] = resource }
        }
        merged.forEach(output::accept)
    }

    override fun getNamespaces(type: PackType): Set<String> = layers.flatMapTo(HashSet()) { it.getNamespaces(type) }

    override fun <T> getMetadataSection(serializer: MetadataSectionSerializer<T>): T? =
        layers.first().getMetadataSection(serializer)

    override fun location(): PackLocationInfo = layers.first().location()

    override fun close() = layers.forEach(PackResources::close)
}
