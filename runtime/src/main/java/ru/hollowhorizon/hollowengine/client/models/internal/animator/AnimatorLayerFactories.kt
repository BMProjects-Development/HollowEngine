package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.rl


fun interface AnimatorLayerFactory {
    fun create(spec: AnimatorLayerSpec): SpecLayer
}

object AnimatorLayerFactories {
    val point = ExtensionPoints.create<AnimatorLayerFactory>("hollowengine:animator/layer_factories".rl)

    init {
        register("hollowengine:animator/clip") { ClipLayer(it as ClipAnimationLayerSpec) }
        register("hollowengine:animator/controller") { ControllerLayer(it as AnimationControllerLayerSpec) }
        register("hollowengine:animator/procedural") { ProceduralLayer(it as ProceduralLayerSpec) }
    }

    fun register(typeId: String, factory: AnimatorLayerFactory): ExtensionHandle = point.register(typeId.rl, factory)

    fun create(spec: AnimatorLayerSpec): SpecLayer? {
        val type = AnimatorLayerTypes.of(spec) ?: return null
        return point.find(type.key)?.create(spec)
    }
}
