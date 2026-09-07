package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.reflect.KClass

/**
 * One kind of animator layer: how it is stored, and what the editor calls it.
 */
class AnimatorLayerType<S : AnimatorLayerSpec>(
    val id: String,
    val specClass: KClass<S>,
    val serializer: KSerializer<S>,
    val titleKey: String,
    val createDefault: (() -> S)? = null,
) {
    val key: ResourceLocation = id.rl
}

/**
 * The kinds of layer an [Animator] may contain.
 */
object AnimatorLayerTypes {
    val point = ExtensionPoints.create<AnimatorLayerType<*>>("hollowengine:animator/layer_types".rl)

    init {
        point.onChange(TagModuleRevision::invalidate)

        register(
            AnimatorLayerType(
                id = "hollowengine:animator/clip",
                specClass = ClipAnimationLayerSpec::class,
                serializer = ClipAnimationLayerSpec.serializer(),
                titleKey = "hollowengine.gui.animator_editor.kind_clip",
                createDefault = { ClipAnimationLayerSpec(animation = "idle") },
            )
        )
        register(
            AnimatorLayerType(
                id = "hollowengine:animator/controller",
                specClass = AnimationControllerLayerSpec::class,
                serializer = AnimationControllerLayerSpec.serializer(),
                titleKey = "hollowengine.gui.animator_editor.kind_controller",
                createDefault = { AnimationControllerLayerSpec() },
            )
        )
        register(
            AnimatorLayerType(
                id = "hollowengine:animator/procedural",
                specClass = ProceduralLayerSpec::class,
                serializer = ProceduralLayerSpec.serializer(),
                titleKey = "hollowengine.gui.animator_editor.kind_procedural",
            )
        )
    }

    fun register(type: AnimatorLayerType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<AnimatorLayerType<*>> get() = point.extensions

    fun of(spec: AnimatorLayerSpec): AnimatorLayerType<*>? =
        all.firstOrNull { it.specClass.isInstance(spec) }

    fun registerInto(builder: SerializersModuleBuilder) {
        builder.polymorphic(AnimatorLayerSpec::class) {
            all.forEach { type -> subclass(type) }
            defaultDeserializer { typeId -> typeId?.let(UnknownAnimatorLayerSpec::deserializerFor) }
        }
        builder.polymorphicDefaultSerializer(AnimatorLayerSpec::class) { spec ->
            (spec as? UnknownAnimatorLayerSpec)?.serializer()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PolymorphicModuleBuilder<AnimatorLayerSpec>.subclass(type: AnimatorLayerType<*>) = subclass(
        type.specClass as KClass<AnimatorLayerSpec>,
        type.serializer as KSerializer<AnimatorLayerSpec>,
    )
}
