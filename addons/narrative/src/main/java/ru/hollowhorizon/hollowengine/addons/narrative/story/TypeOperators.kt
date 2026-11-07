package ru.hollowhorizon.hollowengine.addons.narrative.story

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * Operators for the types the commands and values of the running game take or give back, at any depth, such as
 * `Shortfall` inside `MoveResult.Closest`. An enum gets a block that names one of its constants; a data class and a
 * sealed type get one that takes a value of it apart, and each class of them one that puts a value together.
 */
object TypeOperators {
    const val ENUM = "enum:"
    const val UNPACK = "unpack:"
    const val PACK = "pack:"

    fun isPin(case: String) = "is__$case"
    fun fieldPin(case: String, field: String) = "${case}__$field"

    private const val VALUE = "value"
    private const val ENUMS = "values.enums"
    private const val TYPES = "values.types"

    private class Found(val actions: List<StoryAction>, val operators: Map<String, StoryOperator>)

    @Volatile
    private var found = Found(emptyList(), emptyMap())

    val all: Collection<StoryOperator> get() = current().values

    fun find(id: String): StoryOperator? =
        if (id.startsWith(ENUM) || id.startsWith(UNPACK) || id.startsWith(PACK)) current()[id] else null

    private fun current(): Map<String, StoryOperator> {
        val actions = StoryActions.all
        found.takeIf { it.actions === actions }?.let { return it.operators }
        val operators = collect(actions).flatMap(::operatorsOf).associateBy { it.id }
        found = Found(actions, operators)
        return operators
    }

    private fun operatorsOf(kind: KClass<*>): List<StoryOperator> {
        val name = kind.qualifiedName ?: return emptyList()
        val type = kind.createType()
        return when {
            kind.java.isEnum -> listOf(enumOf(name, type))
            kind.isSealed -> listOf(unpackSealed(name, kind, type)) + kind.sealedSubclasses.mapNotNull(::packOf)
            else -> listOfNotNull(unpackData(name, kind, type), packOf(kind))
        }
    }

    private fun enumOf(name: String, type: KType) = StoryOperator(
        id = ENUM + name,
        category = ENUMS,
        inputs = listOf(
            StoryOperatorInput(
                VALUE,
                StoryPinType.OBJECT,
                param = StoryParam(VALUE, type, suggestions = StoryCommand.suggestions(type))
            )
        ),
        outputs = listOf(StoryOperatorOutput(VALUE, StoryPinType.OBJECT, $$"$$$VALUE", kotlin = name)),
    )

    private fun unpackSealed(name: String, kind: KClass<*>, type: KType): StoryOperator {
        val outputs = kind.sealedSubclasses.flatMap { case ->
            val caseName = case.simpleName ?: return@flatMap emptyList()
            val qualified = case.qualifiedName ?: return@flatMap emptyList()
            listOf(
                StoryOperatorOutput(
                    isPin(caseName),
                    StoryPinType.BOOL,
                    $$"($$$VALUE is $$qualified)"
                )
            ) + fieldsOf(case).map { field ->
                val fieldType = field.type.toString().replace("!", "").removeSuffix("?") + "?"
                StoryOperatorOutput(
                    fieldPin(caseName, field.name!!),
                    StoryPinType.of(field.type),
                    $$"($$$VALUE as? $$qualified)?.$${field.name}",
                    kotlin = fieldType,
                )
            }
        }
        return StoryOperator(UNPACK + name, TYPES, listOf(subject(type)), outputs)
    }

    private fun unpackData(name: String, kind: KClass<*>, type: KType): StoryOperator? {
        val fields = fieldsOf(kind).ifEmpty { return null }
        val outputs = fields.map { field ->
            StoryOperatorOutput(
                field.name!!,
                StoryPinType.of(field.type),
                $$"$$$VALUE.$${field.name}",
                kotlin = field.type.toString().replace("!", ""),
            )
        }
        return StoryOperator(UNPACK + name, TYPES, listOf(subject(type)), outputs)
    }

    private fun packOf(kind: KClass<*>): StoryOperator? {
        val name = kind.qualifiedName ?: return null
        if (kind.hasAnnotation<EditorHidden>() || Modifier.isAbstract(kind.java.modifiers)) return null
        val output = listOf(StoryOperatorOutput(VALUE, StoryPinType.of(kind.createType()), name, kotlin = name))
        if (kind.objectInstance != null) return StoryOperator(PACK + name, TYPES, emptyList(), output)
        val constructor = kind.primaryConstructor ?: return null
        if (constructor.parameters.any { !it.isOptional && !StoryCommand.isValue(it.type) }) return null
        val inputs = constructor.parameters.filter { StoryCommand.isValue(it.type) }.map(::inputOf)
        return StoryOperator(PACK + name, TYPES, inputs, output.map { it.copy(template = "") }, function = name)
    }

    private fun inputOf(parameter: KParameter): StoryOperatorInput {
        val param = StoryParam(
            parameter.name.orEmpty(),
            parameter.type,
            optional = parameter.isOptional,
            suggestions = StoryCommand.suggestions(parameter.type),
        )
        return StoryOperatorInput(param.name, StoryPinType.of(parameter.type), param = param)
    }

    private fun subject(type: KType) = StoryOperatorInput(VALUE, StoryPinType.OBJECT, param = StoryParam(VALUE, type))

    private fun fieldsOf(kind: KClass<*>): List<KParameter> =
        kind.primaryConstructor?.parameters.orEmpty().filter { it.name != null }

    private fun collect(actions: List<StoryAction>): Collection<KClass<*>> {
        val types = sortedMapOf<String, KClass<*>>()
        val seen = HashSet<KClass<*>>()

        fun visit(type: KType) {
            type.arguments.forEach { it.type?.let(::visit) }
            val kind = type.classifier as? KClass<*> ?: return
            if (!seen.add(kind) || kind.java.isPrimitive || kind.qualifiedName?.startsWith("kotlin.") == true) return
            if (kind.hasAnnotation<EditorHidden>()) return
            if (kind.java.isEnum) kind.qualifiedName?.let { types[it] = kind }
            if (!kind.java.isAnnotationPresent(Metadata::class.java)) return
            if (kind.isSealed || kind.isData) kind.qualifiedName?.let { types[it] = kind }
            val classes = if (kind.isSealed) kind.sealedSubclasses else listOf(kind)
            classes.forEach { each -> fieldsOf(each).forEach { visit(it.type) } }
        }

        actions.filter { Modifier.isStatic(it.method.modifiers) }.forEach { action ->
            action.function.parameters.forEach { visit(it.type) }
            visit(action.function.returnType)
        }
        return types.values
    }
}
