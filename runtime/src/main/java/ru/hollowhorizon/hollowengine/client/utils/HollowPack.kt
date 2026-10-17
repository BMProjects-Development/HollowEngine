package ru.hollowhorizon.hollowengine.client.utils


import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.MetadataSectionSerializer
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.IoSupplier
import ru.hollowhorizon.hollowengine.api.AutoModelType
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

object HollowPack : PackResources {
    private val resourceMap = HashMap<ResourceLocation, IoSupplier<InputStream>?>()

    init {
        close() // Uses as reload
    }

    private fun ofText(text: String) = IoSupplier<InputStream> { ByteArrayInputStream(text.toByteArray()) }

    fun addItemModel(location: ResourceLocation, type: AutoModelType) = addCustomItemModel(
        location,
        if (type.blockStateId() == "default")
            "{\"parent\":\"${type.modelId()}\",\"textures\":{\"layer0\":\"" + location.namespace + ":item/" + location.path + "\"}}"
        else type.modelId()
    )

    fun addBlockModel(location: ResourceLocation, type: AutoModelType) {
        when (type.blockStateId()) {
            "default" -> addCustomBlockstate(
                location,
                "{\"variants\":{\"\":{\"model\":\"" + location.namespace + ":block/" + location.path + "\"}}}"
            )

            "directional" -> addCustomBlockstate(
                location, """
                {"variants":{
                    "facing=east":{"model":"${location.namespace}:block/${location.path}","y":90},
                    "facing=north":{"model":"${location.namespace}:block/${location.path}","y":0},
                    "facing=south":{"model":"${location.namespace}:block/${location.path}","y":180},
                    "facing=west":{"model":"${location.namespace}:block/${location.path}","y":270}
                }}
            """.trimIndent()
            )
        }
        addCustomBlock(
            location,
            "{\"parent\":\"${type.modelId()}\",\"textures\":{\"all\":\"" + location.namespace + ":block/" + location.path + "\"}}"
        )
    }

    fun addCustomJSON(modelPath: ResourceLocation, content: String) {
        resourceMap[modelPath] = ofText(content)
    }

    fun addCustomItemModel(location: ResourceLocation, content: String) {
        val model = "${location.namespace}:models/item/${location.path}.json".rl
        addCustomJSON(model, content)
    }

    fun addCustomBlockstate(location: ResourceLocation, content: String) {
        val blockstate = "${location.namespace}:blockstates/${location.path}.json".rl
        addCustomJSON(blockstate, content)
    }

    fun addCustomBlock(location: ResourceLocation, content: String) {
        val model = "${location.namespace}:models/block/${location.path}.json".rl
        addCustomJSON(model, content)
    }

    override fun getRootResource(vararg fileName: String): IoSupplier<InputStream>? {
        return null
    }


    @Throws(IOException::class)
    override fun getResource(type: PackType, pLocation: ResourceLocation): IoSupplier<InputStream>? {
        return resourceMap[pLocation]
    }

    override fun listResources(
        packType: PackType,
        namespace: String,
        prefix: String,
        output: PackResources.ResourceOutput,
    ) {
        resourceMap.filter { it.key.namespace == namespace && it.key.path.startsWith(prefix) }.forEach(output::accept)
    }

    override fun getNamespaces(pType: PackType) = resourceMap.keys.map { it.namespace }.toSet()

    override fun <T> getMetadataSection(pDeserializer: MetadataSectionSerializer<T>): T? {
        if (pDeserializer.metadataSectionName == "pack") {
            //var - java 16 feature
            val obj = JsonObject()
            val supportedFormats = JsonArray()
            for (i in 6..9) {
                supportedFormats.add(i)
            } // From 1.16.2-rc1 to 1.19.3
            obj.addProperty("pack_format", 9)
            obj.add("supported_formats", supportedFormats)
            obj.addProperty("description", "Generated resources for HollowCore")
            return pDeserializer.fromJson(obj)
        }
        return null
    }

    override fun location(): PackLocationInfo {
        return PackLocationInfo(packId(), packId().literal, PackSource.BUILT_IN, Optional.empty())
    }

    override fun packId() = "HollowEngine Embed Resources"
    override fun close() {}

    val resources = asPack()
}

fun PackResources.asPack() =
    Pack.readMetaAndCreate(
        location(), object : Pack.ResourcesSupplier {
            override fun openPrimary(location: PackLocationInfo): PackResources {
                return this@asPack
            }

            override fun openFull(
                location: PackLocationInfo,
                metadata: Pack.Metadata,
            ): PackResources {
                return this@asPack
            }

        }, PackType.CLIENT_RESOURCES, PackSelectionConfig(true, Pack.Position.TOP, true)
    )

