@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.addons.narrative.components

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import net.minecraft.locale.Language
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParamGroup
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCommand
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorIcon
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

/**
 * A command for every registered component, `component/hollowengine:navigation`, that sets the fields of it a call
 * fills and leaves the rest as they are. The commands follow the registry, so a component of an addon or a script
 * gets one as soon as it is registered.
 */
object ComponentCommands {
    private const val PREFIX = "component/"
    private val WORD = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private const val FUNCTION = "ru.hollowhorizon.hollowengine.addons.narrative.components.configureComponent"
    private const val TITLE = "hollowengine.gui.ide.story.component.configure"
    private val GROUP = StoryParamGroup("fields", "componentFields", keyed = true)
    private val RECEIVER = StoryParam("who", typeOf<Entity>(), receiver = true)

    private class Built(
        val descriptors: List<ComponentDescriptor<*>>,
        val language: Language,
        val signatures: Map<String, StoryFunctionSignature>,
    )

    private var built: Built? = null

    val all: Collection<StoryFunctionSignature> get() = current().signatures.values

    fun signature(action: String?): StoryFunctionSignature? =
        action?.takeIf { it.startsWith(PREFIX) }?.let { current().signatures[it] }

    fun isComponent(action: String?): Boolean = action?.startsWith(PREFIX) == true

    private fun current(): Built {
        val descriptors = ComponentDescriptorRegistry.map { it.value }
        val language = Language.getInstance()
        built?.takeIf { it.language === language && it.descriptors.size == descriptors.size }
            ?.takeIf { it.descriptors.zip(descriptors).all { (a, b) -> a === b } }?.let { return it }
        val signatures =
            descriptors.filterNot { hidden(it.serializer.descriptor) }.associate { PREFIX + it.id to signatureOf(it) }
        return Built(descriptors, language, signatures).also { built = it }
    }

    private fun signatureOf(component: ComponentDescriptor<*>): StoryFunctionSignature {
        val serial = component.serializer.descriptor
        val fields = fieldsOf(keyOf(component.id), serial, component.value, "", null)
        return StoryFunctionSignature(
            name = PREFIX + component.id,
            function = FUNCTION,
            params = listOf(RECEIVER) + fields,
            title = Language.getInstance().getOrDefault(TITLE).format(nameOf(component.id, serial)),
            description = serial.annotations.filterIsInstance<EditorDescription>().firstOrNull()?.description,
            icon = serial.annotations.filterIsInstance<EditorIcon>().firstOrNull()?.icon,
            category = StoryCategory.COMPONENTS,
            bound = mapOf("component" to StoryGraphCompiler.literal(component.id.toString())),
        )
    }

    private fun fieldsOf(
        key: String,
        serial: SerialDescriptor,
        type: KClass<*>,
        path: String,
        section: String?,
    ): List<StoryParam> {
        val language = Language.getInstance()
        val properties = ComponentPatches.propertiesOf(type)
        return (0 until serial.elementsCount).flatMap { index ->
            val annotations = serial.getElementAnnotations(index)
            if (annotations.any { it is EditorHidden }) return@flatMap emptyList()
            val name = serial.getElementName(index)
            val propertyType = properties[name]?.returnType ?: return@flatMap emptyList()
            val title = annotations.filterIsInstance<EditorName>().firstOrNull()?.name?.let(language::getOrDefault)
                ?: "$key.$name".takeIf(language::has)?.let(language::getOrDefault) ?: name.replace(WORD, " ")
                    .lowercase().replaceFirstChar(Char::uppercaseChar)
            val shown = section?.let { "$it: $title" } ?: title
            val element = serial.getElementDescriptor(index)
            if (!StoryCommand.isValue(propertyType)) {
                val nested = propertyType.classifier as? KClass<*>
                if (nested == null || element.kind != StructureKind.CLASS) return@flatMap emptyList()
                return@flatMap fieldsOf(key, element, nested, "$path$name.", shown)
            }
            listOf(
                StoryParam(
                    name = path + name,
                    type = propertyType,
                    optional = true,
                    suggestions = StoryCommand.suggestions(propertyType),
                    title = shown,
                    description = annotations.filterIsInstance<EditorDescription>().firstOrNull()?.description,
                    group = GROUP,
                ),
            )
        }
    }

    private fun hidden(serial: SerialDescriptor) = serial.annotations.any { it is EditorHidden }

    private fun nameOf(id: ResourceLocation, serial: SerialDescriptor): String {
        val language = Language.getInstance()
        serial.annotations.filterIsInstance<EditorName>().firstOrNull()?.let { return language.getOrDefault(it.name) }
        return keyOf(id).takeIf(language::has)?.let(language::getOrDefault) ?: id.path.substringAfterLast('/')
            .replace('_', ' ').replaceFirstChar(Char::uppercaseChar)
    }

    private fun keyOf(id: ResourceLocation) = "hollowengine.component.${id.namespace}.${id.path.replace('/', '.')}"
}
