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
 * One kind of controller state: how it is stored, and what the editor calls it.
 */
class AnimatorStateType<S : AnimationControllerStateSpec>(
    val id: String,
    val specClass: KClass<S>,
    val serializer: KSerializer<S>,
    val titleKey: String,
    val createDefault: ((id: String) -> S)? = null,
) {
    val key: ResourceLocation = id.rl
}

/**
 * The kinds of state an [AnimationControllerLayerSpec] may contain.
 */
object AnimatorStateTypes {
    val point = ExtensionPoints.create<AnimatorStateType<*>>("hollowengine:animator/state_types".rl)

    init {
        point.onChange(TagModuleRevision::invalidate)

        register(
            AnimatorStateType(
                id = "hollowengine:animator/state/clip",
                specClass = ClipStateSpec::class,
                serializer = ClipStateSpec.serializer(),
                titleKey = "hollowengine.gui.animator_editor.state_kind_clip",
                createDefault = { id -> ClipStateSpec(id = id, animation = id) },
            )
        )
    }

    fun register(type: AnimatorStateType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<AnimatorStateType<*>> get() = point.extensions

    fun of(spec: AnimationControllerStateSpec): AnimatorStateType<*>? =
        all.firstOrNull { it.specClass.isInstance(spec) }

    fun registerInto(builder: SerializersModuleBuilder) {
        builder.polymorphic(AnimationControllerStateSpec::class) {
            all.forEach { type -> subclass(type) }
            defaultDeserializer { typeId -> typeId?.let(UnknownAnimatorStateSpec::deserializerFor) }
        }
        builder.polymorphicDefaultSerializer(AnimationControllerStateSpec::class) { spec ->
            (spec as? UnknownAnimatorStateSpec)?.serializer()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PolymorphicModuleBuilder<AnimationControllerStateSpec>.subclass(type: AnimatorStateType<*>) =
        subclass(
            type.specClass as KClass<AnimationControllerStateSpec>,
            type.serializer as KSerializer<AnimationControllerStateSpec>,
        )
}
