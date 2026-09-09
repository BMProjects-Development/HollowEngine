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
 * One kind of thing that can hang on a bone: how it is stored, and what the editor calls it.
 */
class RigAttachmentType<S : RigAttachmentSpec>(
    val id: String,
    val specClass: KClass<S>,
    val serializer: KSerializer<S>,
    val titleKey: String,
    val createDefault: ((id: String) -> S)? = null,
) {
    val key: ResourceLocation = id.rl
}

object RigAttachmentTypes {
    val point = ExtensionPoints.create<RigAttachmentType<*>>("hollowengine:rig/attachment_types".rl)

    init {
        point.onChange(TagModuleRevision::invalidate)

        register(
            RigAttachmentType(
                id = "hollowengine:rig/item",
                specClass = ItemSlotAttachmentSpec::class,
                serializer = ItemSlotAttachmentSpec.serializer(),
                titleKey = "hollowengine.gui.rig_editor.kind_item",
                createDefault = { id -> ItemSlotAttachmentSpec(id = id) },
            )
        )
    }

    fun register(type: RigAttachmentType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<RigAttachmentType<*>> get() = point.extensions

    fun of(spec: RigAttachmentSpec): RigAttachmentType<*>? = all.firstOrNull { it.specClass.isInstance(spec) }

    fun registerInto(builder: SerializersModuleBuilder) {
        builder.polymorphic(RigAttachmentSpec::class) {
            all.forEach { type -> subclass(type) }
            defaultDeserializer { typeId -> typeId?.let(UnknownRigAttachmentSpec::deserializerFor) }
        }
        builder.polymorphicDefaultSerializer(RigAttachmentSpec::class) { spec ->
            (spec as? UnknownRigAttachmentSpec)?.serializer()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PolymorphicModuleBuilder<RigAttachmentSpec>.subclass(type: RigAttachmentType<*>) = subclass(
        type.specClass as KClass<RigAttachmentSpec>,
        type.serializer as KSerializer<RigAttachmentSpec>,
    )
}
