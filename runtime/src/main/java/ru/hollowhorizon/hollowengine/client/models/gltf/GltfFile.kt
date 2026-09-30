package ru.hollowhorizon.hollowengine.client.models.gltf

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.utils.stream
import ru.hollowhorizon.hollowengine.common.models.ModelResourceIO
import ru.hollowhorizon.hollowengine.common.utils.Uint8Buffer
import ru.hollowhorizon.hollowengine.common.utils.decodeToString
import ru.hollowhorizon.hollowengine.common.utils.inflate
import ru.hollowhorizon.hollowengine.common.utils.json.JsonFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.ListOrSingle
import java.nio.file.Path
import java.util.*

suspend fun loadGltf(location: ResourceLocation, side: ModelSide = ModelSide.CLIENT): Result<GltfFile> {
    val data = Uint8Buffer(location.readModelBytes(side))

    return try {
        val filePath = location.path
        val gltfData = if (filePath.lowercase().endsWith(".gz")) data.inflate() else data
        val gltfFile = when (val type = filePath.lowercase().removeSuffix(".gz").substringAfterLast('.')) {
            "gltf" -> GltfFile.fromJson(gltfData.decodeToString())
            "glb" -> loadGlb(gltfData)
            else -> error("Invalid gltf file type: $type ($filePath)")
        }

        gltfFile.let { m ->
            coroutineScope {
                withContext(Dispatchers.IO) {
                    m.buffers.filter { it.uri != null }.map {
                        async {
                            val uri = it.uri!!
                            val bufferUri = if (uri.startsWith("data:", true)) {
                                when {
                                    uri.startsWith("data:application/octet-stream;base64,") -> {
                                        it.data = Uint8Buffer(Base64.getDecoder().decode(uri.substring(37)))
                                    }

                                    uri.startsWith("data:image/png;base64,") -> {
                                        it.data = Uint8Buffer(Base64.getDecoder().decode(uri.substring(22)))
                                    }

                                    else -> throw IllegalStateException("Unknown data format: $uri")
                                }
                                return@async
                            } else resolveGltfResource(location, uri)
                            it.data = Uint8Buffer(bufferUri.readModelBytes(side))
                        }
                    }.awaitAll()
                    m.images.filter { it.uri != null }.forEach {
                        if (it.uri?.startsWith("data:") == false) {
                            it.uri = resolveGltfResource(location, it.uri!!).toString()
                        }
                    }
                    m.updateReferences()
                }
            }
        }
        Result.success(gltfFile)
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

/** Рессурсы майнкрафт не могут содержать "." :> */
private fun resolveGltfResource(model: ResourceLocation, uri: String): ResourceLocation {
    val parent = Path.of(model.path).parent ?: Path.of("")
    val path = parent.resolve(uri).normalize()
    require(!path.isAbsolute && !path.startsWith("..")) { "glTF resource escapes its namespace: $uri" }
    return ResourceLocation.fromNamespaceAndPath(model.namespace, path.toString().replace('\\', '/').lowercase(Locale.ROOT))
}

private fun ResourceLocation.readModelBytes(side: ModelSide): ByteArray =
    when (side) {
        ModelSide.CLIENT -> stream.readBytes()
        ModelSide.SERVER -> ModelResourceIO.open(this).use { it.readBytes() }
    }

private fun loadGlb(data: Uint8Buffer): GltfFile {
    val str = DataStream(data)

    val magic = str.readUInt()
    val version = str.readUInt()
    str.readUInt()
    if (magic != GltfFile.GLB_FILE_MAGIC) {
        error("Unexpected glTF magic number: $magic (should be ${GltfFile.GLB_FILE_MAGIC} / 'glTF')")
    }
    if (version != 2) {
        HollowEngine.LOGGER.warn("Unexpected glTF version: $version (should be 2) - stuff might not work as expected")
    }

    var chunkLen = str.readUInt()
    var chunkType = str.readUInt()
    if (chunkType != GltfFile.GLB_CHUNK_MAGIC_JSON) {
        error("Unexpected chunk type for chunk 0: $chunkType (should be ${GltfFile.GLB_CHUNK_MAGIC_JSON} / 'JSON')")
    }
    val jsonData = str.readData(chunkLen).toArray()
    val model = GltfFile.fromJson(jsonData.decodeToString())

    var iChunk = 1
    while (str.hasRemaining()) {
        chunkLen = str.readUInt()
        chunkType = str.readUInt()
        if (chunkType == GltfFile.GLB_CHUNK_MAGIC_BIN) {
            model.buffers[iChunk - 1].data = str.readData(chunkLen)
        } else {
            HollowEngine.LOGGER.warn("Unexpected chunk type for chunk $iChunk: $chunkType (should be ${GltfFile.GLB_CHUNK_MAGIC_BIN} / ' BIN')")
            str.index += chunkLen
        }
        iChunk++
    }

    return model
}

@Serializable
data class GltfFile(
    val extensionsUsed: ListOrSingle<String> = emptyList(),
    val extensionsRequired: ListOrSingle<String> = emptyList(),
    val accessors: List<GltfAccessor> = emptyList(),
    val animations: List<GltfAnimation> = emptyList(),
    val asset: GltfAsset,
    val buffers: List<GltfBuffer> = emptyList(),
    val bufferViews: List<GltfBufferView> = emptyList(),
    val images: ListOrSingle<GltfImage> = emptyList(),
    val materials: ListOrSingle<GltfMaterial> = emptyList(),
    val meshes: List<GltfMesh> = emptyList(),
    val nodes: List<GltfNode> = emptyList(),
    val cameras: List<GltfCamera> = emptyList(),
    val samplers: List<GltfSampler> = emptyList(),
    val scene: Int = 0,
    val scenes: List<GltfScene> = emptyList(),
    val skins: List<GltfSkin> = emptyList(),
    val textures: ListOrSingle<GltfTexture> = emptyList(),
) {
    internal fun updateReferences() {
        accessors.forEach {
            if (it.bufferView >= 0) {
                it.bufferViewRef = bufferViews[it.bufferView]
            }
            it.sparse?.let { sparse ->
                sparse.indices.bufferViewRef = bufferViews[sparse.indices.bufferView]
                sparse.values.bufferViewRef = bufferViews[sparse.values.bufferView]
            }
        }
        animations.forEach { anim ->
            anim.samplers.forEach {
                it.inputAccessorRef = accessors[it.input]
                it.outputAccessorRef = accessors[it.output]
            }
            anim.channels.forEach {
                it.samplerRef = anim.samplers[it.sampler]
                if (it.target.node >= 0) {
                    it.target.nodeRef = nodes[it.target.node]
                }
            }
        }
        bufferViews.forEach { it.bufferRef = buffers[it.buffer] }
        images.filter { it.bufferView >= 0 && it.bufferViewRef == null }
            .forEach { it.bufferViewRef = bufferViews[it.bufferView] }
        meshes.forEach { mesh ->
            mesh.primitives.forEach {
                if (it.material >= 0) {
                    it.materialRef = materials[it.material]
                }
            }
        }
        nodes.forEach {
            it.childRefs = it.children.map { iNd -> nodes[iNd] }
            if (it.mesh >= 0) {
                it.meshRef = meshes[it.mesh]
            }
            if (it.skin >= 0) {
                it.skinRef = skins[it.skin]
            }
        }
        scenes.forEach { it.nodeRefs = it.nodes.map { iNd -> nodes[iNd] } }
        skins.forEach {
            if (it.inverseBindMatrices >= 0) {
                it.inverseBindMatrixAccessorRef = accessors[it.inverseBindMatrices]
            }
            it.jointRefs = it.joints.map { iJt -> nodes[iJt] }
        }
        textures.forEach {
            it.imageRef = images[it.source]
            it.samplerRef = samplers.getOrNull(it.sampler)
        }
    }

    companion object {
        const val GLB_FILE_MAGIC = 0x46546c67
        const val GLB_CHUNK_MAGIC_JSON = 0x4e4f534a
        const val GLB_CHUNK_MAGIC_BIN = 0x004e4942

        fun fromJson(json: String): GltfFile {
            return JsonFormat.decodeFromString(json)
        }
    }
}
