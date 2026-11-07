package ru.hollowhorizon.hollowengine.addons.narrative.components

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.serializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.addons.narrative.story.Action
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.addons.narrative.story.action
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

fun componentFields(vararg fields: Pair<String, Any?>): Map<String, Any?> = mapOf(*fields)

/** [value] for a field of the type [T]: a story names the type of the field, so that Kotlin checks what it is given. */
fun <T> componentField(value: T): T = value

suspend fun Entity.configureComponent(component: String, fields: Map<String, Any?> = emptyMap()) =
    patchComponent(component, ComponentPatches.encode(component, fields))

@EditorHidden
@Action("configure-component")
suspend fun Entity.patchComponent(component: String, patch: CompoundTag) = action(this, component, patch) {
    val id = ResourceLocation.parse(component)
    val descriptor = ComponentPatches.descriptorOf(component)
    val store = AttachmentRegistry.attachments(this@patchComponent).components
    val before = capture {
        CompoundTag().apply { store.readOnly[id]?.let { put("value", ComponentPatches.write(descriptor, it)) } }
    }
    undo {
        val saved = before.get("value")
        if (saved != null) store.put(ComponentPatches.read(descriptor, saved)) else store.asMutableMap().remove(id)
    }
    perform {
        val current = store.readOnly[id] ?: descriptor.create()
        val written = ComponentPatches.write(descriptor, current) as? CompoundTag
            ?: throw StoryException("Component '$component' is not written as a compound, so its fields cannot be set")
        store.put(ComponentPatches.read(descriptor, written.copy().merge(patch)))
    }
}

internal object ComponentPatches {
    fun descriptorOf(component: String): ComponentDescriptor<*> =
        ResourceLocation.tryParse(component)?.let(ComponentDescriptorRegistry::descriptorOrNull)
            ?: throw StoryException("There is no component '$component'")

    fun propertiesOf(type: KClass<*>): Map<String, KProperty1<out Any, *>> =
        type.memberProperties.associateBy { it.findAnnotation<SerialName>()?.value ?: it.name }

    fun encode(component: String, fields: Map<String, Any?>): CompoundTag {
        val root = CompoundTag()
        fields.forEach { (name, value) ->
            if (value == null) return@forEach
            val path = name.split('.')
            var owner: KClass<*> = descriptorOf(component).value
            var target = root
            path.dropLast(1).forEach { section ->
                owner = propertiesOf(owner)[section]?.returnType?.classifier as? KClass<*>
                    ?: throw StoryException("Component '$component' has no section '$section'")
                target = target.getCompound(section).also { target.put(section, it) }
            }
            val type = propertiesOf(owner)[path.last()]?.returnType
                ?: throw StoryException("Component '$component' has no field '$name'")
            target.put(
                path.last(),
                NBTFormat.serialize(NBTFormat.serializersModule.serializer(type), coerce(value, type))
            )
        }
        return root
    }

    @Suppress("UNCHECKED_CAST")
    fun write(descriptor: ComponentDescriptor<*>, component: Any): Tag =
        NBTFormat.serialize(descriptor.serializer as KSerializer<Any>, component)

    fun read(descriptor: ComponentDescriptor<*>, tag: Tag): Any = NBTFormat.deserialize(descriptor.serializer, tag)

    private fun coerce(value: Any, type: KType): Any {
        val kind = type.classifier as? KClass<*> ?: return value
        return when (value) {
            is Number if kind == Float::class -> value.toFloat()
            is Number if kind == Double::class -> value.toDouble()
            is Number if kind == Int::class -> value.toInt()
            is Number if kind == Long::class -> value.toLong()
            is Number if kind == Short::class -> value.toShort()
            is Number if kind == Byte::class -> value.toByte()
            is String if kind.java.isEnum -> kind.java.enumConstants.firstOrNull {
                (it as Enum<*>).name.equals(value.replace('-', '_'), ignoreCase = true)
            } ?: throw StoryException("'$value' is not one of ${kind.simpleName}")

            else -> value
        }
    }
}
