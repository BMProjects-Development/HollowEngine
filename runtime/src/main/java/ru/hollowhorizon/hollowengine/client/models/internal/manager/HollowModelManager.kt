package ru.hollowhorizon.hollowengine.client.models.internal.manager

import com.mojang.blaze3d.systems.RenderSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.HollowEngine.MODID
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockModelLoader
import ru.hollowhorizon.hollowengine.client.models.fbx.FbxModelLoader
import ru.hollowhorizon.hollowengine.client.models.gltf.GltfModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.renameMaterials
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.configureStaticRenderPaths
import ru.hollowhorizon.hollowengine.client.models.obj.ObjModelLoader
import ru.hollowhorizon.hollowengine.client.textures.GlTexture
import ru.hollowhorizon.hollowengine.client.utils.stream
import ru.hollowhorizon.hollowengine.common.coroutines.scopeAsync
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.ModelMetadata
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap


object HollowModelManager : SimplePreparableReloadListener<Map<ResourceLocation, PreparedModelUpdate<Model>>>() {
    lateinit var lightTexture: AbstractTexture
    private val models = ConcurrentHashMap<ResourceLocation, MutableStateFlow<Model>>()
    private val indexedModels = ConcurrentHashMap.newKeySet<ResourceLocation>()
    private val metadata = ConcurrentHashMap<ResourceLocation, ModelMetadata>()
    var glProgramSkinning = -1
    var glProgramMorphing = -1

    private val loaders = mutableListOf<ModelLoader>().apply {
        RegisterModelLoaderEvent.post(RegisterModelLoaderEvent(this))
    }

    private fun loadIntoFlow(location: ResourceLocation, flow: MutableStateFlow<Model>) {
        scopeAsync {
            try {
                val loaded = loadModel(location)
                publish(location, flow, PreparedModelUpdate(exists = true, loaded = Result.success(loaded)))
            } catch (e: Exception) {
                HollowEngine.LOGGER.error("Can't reload model $location", e)
            }
        }
    }

    fun getOrCreate(location: ResourceLocation): StateFlow<Model> {
        return models.computeIfAbsent(location) {
            val flow = MutableStateFlow(Model.EMPTY)
            loadIntoFlow(location, flow)
            flow
        }
    }

    suspend fun loadModel(location: ResourceLocation): Model {
        val extension = location.path.substringAfter('.', "")

        val loader = loaders.find { extension in it.supportedFormats }
            ?: error("No suitable model loader found for format .$extension")

        return loader.load(location).also { it.renameMaterials(metadata(location)) }
            .also(Model::configureStaticRenderPaths)
    }

    override fun prepare(
        manager: ResourceManager,
        profiler: ProfilerFiller,
    ): Map<ResourceLocation, PreparedModelUpdate<Model>> {
        AnimatorAssets.reload(manager)
        val indexed = readMetadata(manager)
        indexedModels.clear()
        indexedModels.addAll(indexed)
        val targets = ModelReloadCoordinator.reloadTargets(models.keys, indexed)

        return runBlocking(Dispatchers.IO) {
            targets.map { location ->
                async {
                    location to prepareModelUpdate(manager, location)
                }
            }.awaitAll().toMap()
        }
    }

    override fun apply(
        prepared: Map<ResourceLocation, PreparedModelUpdate<Model>>,
        manager: ResourceManager,
        profiler: ProfilerFiller,
    ) {
        prepared.forEach { (location, update) ->
            publish(location, models.computeIfAbsent(location) { MutableStateFlow(Model.EMPTY) }, update)
        }
    }

    private fun publish(
        location: ResourceLocation,
        flow: MutableStateFlow<Model>,
        update: PreparedModelUpdate<Model>,
    ) {
        val swap = ModelReloadCoordinator.resolveSwap(flow.value, update, Model.EMPTY)
        flow.value = swap.next
        swap.retired?.let(::destroyLater)

        if (!update.exists && location !in indexedModels) {
            models.remove(location, flow)
        }
    }

    private suspend fun prepareModelUpdate(
        manager: ResourceManager,
        location: ResourceLocation,
    ): PreparedModelUpdate<Model> {
        if (!manager.getResource(location).isPresent) {
            return PreparedModelUpdate(exists = false)
        }

        return PreparedModelUpdate(exists = true, loaded = runCatching { loadModel(location) }.onFailure {
            HollowEngine.LOGGER.error("Can't reload model $location", it)
        })
    }

    private fun readMetadata(manager: ResourceManager): Set<ResourceLocation> {
        val supportedFormats = loaders.flatMap { it.supportedFormats }.toSet()
        metadata.clear()

        val models = manager.listResources("models") { path ->
            path.path.substringAfter('.') in supportedFormats
        }.keys
        models.forEach { location ->
            val resource = manager.getResource(location.withSuffix(".hemeta")).orElse(null) ?: return@forEach
            val source = runCatching { resource.open().use { it.readBytes().decodeToString() } }.getOrDefault("")
            metadata[location] = ModelMetadata.parse(source, location.toString())
        }
        RigAssets.reload(manager, models)

        return metadata.filterValues(ModelMetadata::preload).keys
    }

    /** What the model's `.hemeta` says, or empty metadata when it has none. */
    fun metadata(location: ResourceLocation?): ModelMetadata = location?.let(metadata::get) ?: ModelMetadata.EMPTY

    /** The animator this model wears by default, named by its metadata. */
    fun animatorOf(location: ResourceLocation?): Animator? =
        AnimatorAssets.get(metadata(location).animationController ?: return null)

    private fun destroyLater(model: Model) {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            model.destroy()
        } else {
            RenderSystem.recordRenderCall(model::destroy)
        }
    }

    private fun createSkinningProgramGL33() {
        var glShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
        GL20.glShaderSource(
            glShader, "hollowengine:shaders/core/gltf_skinning.vsh".rl.stream.readBytes().decodeToString()
        )
        GL20.glCompileShader(glShader)

        glProgramSkinning = GL20.glCreateProgram()
        GL20.glAttachShader(glProgramSkinning, glShader)
        GL20.glDeleteShader(glShader)
        GL30.glTransformFeedbackVaryings(
            glProgramSkinning, arrayOf<CharSequence>("outPosition", "outNormal", "outTangent"), GL30.GL_SEPARATE_ATTRIBS
        )
        GL20.glLinkProgram(glProgramSkinning)


        glShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
        GL20.glShaderSource(
            glShader, "hollowengine:shaders/core/gltf_morphing.vsh".rl.stream.readBytes().decodeToString()
        )
        GL20.glCompileShader(glShader)

        glProgramMorphing = GL20.glCreateProgram()
        GL20.glAttachShader(glProgramMorphing, glShader)
        GL20.glDeleteShader(glShader)
        GL30.glTransformFeedbackVaryings(
            glProgramMorphing, arrayOf<CharSequence>("outPosition", "outNormal", "outTangent"), GL30.GL_SEPARATE_ATTRIBS
        )
        GL20.glLinkProgram(glProgramMorphing)
    }

    fun initialize() {
        val textureManager = Minecraft.getInstance().textureManager

        lightTexture = textureManager.getTexture("dynamic/light_map_1".rl)

        val currentTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)

        val defaultColorMap = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, defaultColorMap)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, create(
                byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)
            )
        )
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0)

        val defaultNormalMap = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, defaultNormalMap)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, create(
                byteArrayOf(-128, -128, -1, -1, -128, -128, -1, -1, -128, -128, -1, -1, -128, -128, -1, -1)
            )
        )
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0)

        val defaultSpecularMap = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, defaultSpecularMap)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, create(
                byteArrayOf(
                    0, 0, 0, 0, // Pixel 1: Black color, Max Roughness
                    0, 0, 0, 0, // Pixel 2
                    0, 0, 0, 0, // Pixel 3
                    0, 0, 0, 0  // Pixel 4
                )
            )
        )
        Minecraft.getInstance().player?.random

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0)



        textureManager.register("${MODID}:default_color_map".rl, GlTexture(defaultColorMap))
        textureManager.register("${MODID}:default_normal_map".rl, GlTexture(defaultNormalMap))
        textureManager.register("${MODID}:default_specular_map".rl, GlTexture(defaultSpecularMap))

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture)

        createSkinningProgramGL33()
    }

    fun supports(location: ResourceLocation): Boolean {
        val extension = location.path.substringAfter('.', "")

        return loaders.any { extension in it.supportedFormats }
    }

    fun supports(location: String): Boolean {
        val extension = location.substringAfter('.', "")

        return loaders.any { extension in it.supportedFormats }
    }

    val allSupportedFormats get() = loaders.flatMap { loader -> loader.supportedFormats }
    val allModels get() = indexedModels + models.keys

}

interface ModelLoader {
    val supportedFormats: Set<String>

    suspend fun load(location: ResourceLocation, side: ModelSide = ModelSide.CLIENT): Model

    companion object {
        val FALLBACK_MODEL = "$MODID:models/error.gltf".rl
    }
}

enum class ModelSide {
    CLIENT, SERVER
}

class RegisterModelLoaderEvent(private val loaders: MutableList<ModelLoader>) : ClientEvent {
    fun register(loader: ModelLoader) {
        loaders.add(loader)
    }

    fun unregister(loader: ModelLoader) = loaders.removeIf { it == loader }

    fun clear() {
        loaders.clear()
    }

    fun getLoaders(): List<ModelLoader> = loaders.toList()

    companion object : EventHandler<RegisterModelLoaderEvent>()
}

@SubscribeEvent
@ClientOnly
fun registerModelLoaders(event: RegisterModelLoaderEvent) {
    event.register(GltfModelLoader)
    event.register(ObjModelLoader)
    event.register(FbxModelLoader)
    event.register(BedrockModelLoader)
}

fun create(data: ByteArray) = create(data, 0, data.size)

fun create(data: ByteArray?, offset: Int, length: Int): ByteBuffer {
    val byteBuffer = ByteBuffer.allocateDirect(length)
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
    byteBuffer.put(data, offset, length)
    byteBuffer.position(0)
    return byteBuffer
}


