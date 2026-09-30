package ru.hollowhorizon.hollowengine.common.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.api.ReloadListener
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockModelLoader
import ru.hollowhorizon.hollowengine.client.models.fbx.FbxModelLoader
import ru.hollowhorizon.hollowengine.client.models.gltf.GltfModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.models.obj.ObjModelLoader
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.concurrent.ConcurrentHashMap

@ReloadListener
object ServerModelAnimationMetadata : ResourceManagerReloadListener {
    override fun onResourceManagerReload(resourceManager: ResourceManager) = clearCache()

    private val loaders: List<ModelLoader> = listOf(
        GltfModelLoader,
        ObjModelLoader,
        FbxModelLoader,
        BedrockModelLoader,
    )
    private val modelCache = ConcurrentHashMap<String, Result<Model>>()

    fun animationDuration(model: String, animation: String): Float? =
        model(model)?.animationsByName?.get(animation)?.duration

    fun animationDurations(model: String): Map<String, Float> =
        model(model)
            ?.animationsByName
            ?.mapValues { (_, animation) -> animation.duration }
            .orEmpty()

    fun model(model: String): Model? =
        modelCache.computeIfAbsent(model, ::loadModel).getOrNull()

    fun clearCache() {
        modelCache.clear()
        ServerHitboxAssets.clear()
    }

    private fun loadModel(model: String): Result<Model> =
        runCatching {
            val location = model.rl
            val loader = loaderFor(location)
                ?: error("No suitable model loader found for $location")
            runBlocking(Dispatchers.IO) {
                loader.load(location, ModelSide.SERVER)
            }
        }

    private fun loaderFor(location: ResourceLocation): ModelLoader? =
        loaders.firstOrNull { loader ->
            loader.supportedFormats.any { format ->
                location.path.endsWith(".$format", ignoreCase = true)
            }
        }
}
