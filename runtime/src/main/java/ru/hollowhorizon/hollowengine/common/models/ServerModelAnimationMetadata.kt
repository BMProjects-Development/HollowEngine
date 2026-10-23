package ru.hollowhorizon.hollowengine.common.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockModelLoader
import ru.hollowhorizon.hollowengine.client.models.bbmodel.BlockBenchModelLoader
import ru.hollowhorizon.hollowengine.client.models.fbx.FbxModelLoader
import ru.hollowhorizon.hollowengine.client.models.gltf.GltfModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.models.obj.ObjModelLoader
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.concurrent.ConcurrentHashMap

object ServerModelAnimationMetadata {
    private val loaders: List<ModelLoader> = listOf(
        GltfModelLoader,
        ObjModelLoader,
        FbxModelLoader,
        BedrockModelLoader,
        BlockBenchModelLoader,
    )
    private val modelCache = ConcurrentHashMap<String, Result<Model>>()

    fun animationDuration(model: String, animation: String): Float? =
        model(model)?.animationsByName?.get(animation)?.duration

    fun animationDurations(model: String): Map<String, Float> =
        model(model)
            ?.animationsByName
            ?.mapValues { (_, animation) -> animation.duration }
            .orEmpty()

    // Not computeIfAbsent: runBlocking on the server thread drains coroutines queued on that thread, one of them
    // may ask for the same model, and the re-entrant computeIfAbsent throws "Recursive update".
    fun model(model: String): Model? =
        (modelCache[model] ?: loadModel(model).let { modelCache.putIfAbsent(model, it) ?: it }).getOrNull()

    fun clearCache() {
        modelCache.clear()
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
