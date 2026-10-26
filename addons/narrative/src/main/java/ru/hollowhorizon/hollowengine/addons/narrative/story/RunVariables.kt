package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.*

/**
 * The [saved] variables of a story as a scene sees them: by the bare property name, for every script whose stages
 * the story is in and the scripts those import. Only variables a scene has values for are seen: strings, numbers,
 * booleans and lists of them. A value the scene writes is converted to the type the script declared.
 */
internal class RunVariables(private val run: StoryRun) : StoryVariableSource {
    private class Declared(val key: String, val variable: SavedVariable<*>)

    private val declared: Map<String, List<Declared>> =
        run.root.tree().flatMap { (it.frames + it.pending).asSequence() }.map { it.instance }.distinct()
            .flatMap { StoryInstances.savedVariablesOf(it).entries }.distinctBy { it.key }
            .filter { fitsScene(it.value.serializer.descriptor) }.map { (key, variable) -> Declared(key, variable) }
            .groupBy { it.key.substringAfterLast('/') }

    override fun get(name: String): StoryValue? {
        val variable = find(name) ?: return null
        val tag = run.vars.get(variable.key)
        val value =
            if (tag == null) variable.variable.default else StoryValues.decode(variable.variable.serializer, tag)
        return StoryConversions.toStory(value)
    }

    override fun set(name: String, value: StoryValue): Boolean {
        val variable = find(name) ?: return false
        val serializer = variable.variable.serializer
        StoryValues.put(run.vars, variable.key, serializer, fromStory(name, value, serializer.descriptor))
        return true
    }

    private fun find(name: String): Declared? {
        val found = declared[name] ?: return null
        if (found.size > 1) {
            throw StoryRuntimeException(
                "'$name' is a story variable of several scripts: ${
                found.joinToString {
                    it.key.substringBeforeLast(
                        '/'
                    )
                }
            }")
        }
        return found.single()
    }

    private fun fromStory(name: String, value: StoryValue, descriptor: SerialDescriptor): Any {
        fun mismatch(): Nothing = throw StoryRuntimeException(
            "'$name' holds ${descriptor.serialName.removeSuffix("?")}, it cannot be set to ${value.display()}"
        )

        fun whole(): Long {
            val number = (value as? StoryNumber)?.value ?: mismatch()
            if (number != number.toLong().toFloat()) mismatch()
            return number.toLong()
        }
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> (value as? StoryString)?.value ?: mismatch()
            PrimitiveKind.BOOLEAN -> (value as? StoryBool)?.value ?: mismatch()
            PrimitiveKind.FLOAT -> (value as? StoryNumber)?.value ?: mismatch()
            PrimitiveKind.DOUBLE -> (value as? StoryNumber)?.value?.toDouble() ?: mismatch()
            PrimitiveKind.INT -> whole().toInt()
            PrimitiveKind.LONG -> whole()
            PrimitiveKind.SHORT -> whole().toShort()
            PrimitiveKind.BYTE -> whole().toByte()
            StructureKind.LIST -> {
                val element = descriptor.getElementDescriptor(0)
                (value as? StoryList)?.values?.map { fromStory(name, it, element) } ?: mismatch()
            }

            else -> mismatch()
        }
    }

    private fun fitsScene(descriptor: SerialDescriptor): Boolean = when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.BOOLEAN, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE,
            -> true

        StructureKind.LIST -> fitsScene(descriptor.getElementDescriptor(0))
        else -> false
    }
}
