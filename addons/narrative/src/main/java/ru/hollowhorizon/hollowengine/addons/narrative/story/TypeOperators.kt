package ru.hollowhorizon.hollowengine.addons.narrative.story

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType

/**
 * Operators for the types the commands and values of the running game take or give back, at any depth, such as
 * `Shortfall` inside `MoveResult.Closest`. An enum gets a block that names one of its constants; a data class and a
 * sealed type get one that takes a value of it apart, and each class of them one that puts a value together.
 */
object TypeOperators {
    const val ENUM = "enum:"
    const val UNPACK = "unpack:"
    const val PACK = "pack:"

    /** The pin of the value a block takes apart or gives. */
    const val SUBJECT = "value"

    fun isPin(case: String) = "is__$case"
    fun asPin(case: String) = "as__$case"
    fun fieldPin(case: String, field: String) = "${case}__$field"

    private const val VALUE = SUBJECT
    private const val TYPES = "values.types"

    private class Found(val actions: List<StoryAction>, val components: Int, val operators: Map<String, StoryOperator>)

    @Volatile
    private var found = Found(emptyList(), -1, emptyMap())

    private val generic: Map<String, StoryOperator> = listOf(
        StoryOperator(UNPACK, TYPES, listOf(StoryOperatorInput(VALUE, StoryPinType.ANY)), emptyList()),
        StoryOperator(PACK, TYPES, emptyList(), listOf(StoryOperatorOutput(VALUE, StoryPinType.ANY, ""))),
        StoryOperator(ENUM, TYPES, emptyList(), listOf(StoryOperatorOutput(VALUE, StoryPinType.ANY, ""))),
    ).associateBy { it.id }

    /** The blocks of the types, those of no type yet first. */
    val all: Collection<StoryOperator> get() = generic.values + current().values

    fun find(id: String): StoryOperator? = generic[id] ?: if (isTyped(id)) current()[id] else null

    fun familyOf(id: String): String? = listOf(ENUM, UNPACK, PACK).firstOrNull { id.startsWith(it) }

    val revision: Any get() = StoryActions.all to TagModuleRevision.current

    fun qualified(type: String): String? = listOf(ENUM, UNPACK, PACK).firstNotNullOfOrNull { family ->
        typed(family, type)?.id?.removePrefix(family)
    }

    fun isGeneric(id: String): Boolean = id in generic

    fun isTyped(id: String): Boolean =
        !isGeneric(id) && (id.startsWith(ENUM) || id.startsWith(UNPACK) || id.startsWith(PACK))

    fun typed(generic: String, type: String): StoryOperator? {
        val written = type.removeSuffix("?").substringBefore('<').replace("!", "")
        val operators = current()
        operators[generic + written]?.let { return it }
        val short = StoryPinType.shortName(written)
        return operators.values
            .filter { it.id.startsWith(generic) && StoryPinType.shortName(it.id.removePrefix(generic)) == short }
            .singleOrNull()
    }

    private fun current(): Map<String, StoryOperator> {
        val actions = StoryActions.all
        val components = TagModuleRevision.current
        found.takeIf { it.actions === actions && it.components == components }?.let { return it.operators }
        val operators = collect(actions).flatMap(::operatorsOf).associateBy { it.id }
        found = Found(actions, components, operators)
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
        category = TYPES,
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
                StoryOperatorOutput(isPin(caseName), StoryPinType.BOOL, $$"($$$VALUE is $$qualified)"),
            ) + listOfNotNull(
                StoryOperatorOutput(
                    asPin(caseName),
                    StoryPinType.of(case.starProjectedType),
                    $$"($$$VALUE as? $$qualified)",
                    kotlin = "$qualified?",
                    path = caseName,
                ).takeIf { fieldsOf(case).isNotEmpty() },
            ) + fieldsOf(case).map { field ->
                val fieldType = field.type.toString().replace("!", "").removeSuffix("?") + "?"
                StoryOperatorOutput(
                    fieldPin(caseName, field.name!!),
                    StoryPinType.of(field.type),
                    $$"($$$VALUE as? $$qualified)?.$${field.name}",
                    kotlin = fieldType,
                    path = "$caseName.${field.name}",
                )
            }
        }
        return StoryOperator(UNPACK + name, TYPES, listOf(subject(type)), outputs)
    }

    private fun unpackData(name: String, kind: KClass<*>, type: KType): StoryOperator? {
        val outputs = fieldOutputs(kind, "", $$"$$$VALUE", false, setOf(kind)).ifEmpty { return null }
        return StoryOperator(UNPACK + name, TYPES, listOf(subject(type)), outputs)
    }

    private fun fieldOutputs(
        kind: KClass<*>,
        path: String,
        owner: String,
        empty: Boolean,
        seen: Set<KClass<*>>,
    ): List<StoryOperatorOutput> = fieldsOf(kind).flatMap { field ->
        val name = path + field.name
        val read = owner + (if (empty) "?." else ".") + field.name
        val written = field.type.toString().replace("!", "")
        val kotlin = if (empty && !written.endsWith("?")) "$written?" else written
        val own = StoryOperatorOutput(name, StoryPinType.of(field.type), read, kotlin = kotlin)
        val nested = sectionOf(field.type)?.takeIf { it !in seen } ?: return@flatMap listOf(own)
        listOf(own) + fieldOutputs(nested, "$name.", read, empty || field.type.isMarkedNullable, seen + nested)
    }

    private fun sectionOf(type: KType): KClass<*>? {
        val kind = type.classifier as? KClass<*> ?: return null
        if (!kind.isData || StoryCommand.isValue(type) || kind.hasAnnotation<EditorHidden>()) return null
        return kind.takeIf { fieldsOf(it).isNotEmpty() }
    }

    private fun packOf(kind: KClass<*>): StoryOperator? {
        val name = kind.qualifiedName ?: return null
        if (kind.hasAnnotation<EditorHidden>() || Modifier.isAbstract(kind.java.modifiers)) return null
        val output = listOf(StoryOperatorOutput(VALUE, StoryPinType.of(kind.createType()), name, kotlin = name))
        if (kind.objectInstance != null) return StoryOperator(PACK + name, TYPES, emptyList(), output)
        val constructor = kind.primaryConstructor ?: return null
        val inputs = inputsOf(constructor.parameters, "", setOf(kind)) ?: return null
        return StoryOperator(PACK + name, TYPES, inputs, output.map { it.copy(template = "") }, function = name)
    }

    private fun inputsOf(parameters: List<KParameter>, path: String, seen: Set<KClass<*>>): List<StoryOperatorInput>? {
        val inputs = ArrayList<StoryOperatorInput>()
        parameters.forEach { parameter ->
            if (StoryCommand.isValue(parameter.type) || StoryCommand.isContainer(parameter.type)) {
                inputs += inputOf(parameter, path)
                return@forEach
            }
            val nested = sectionOf(parameter.type)?.takeIf { it !in seen }
            val parts = nested?.primaryConstructor?.let { inputsOf(it.parameters, "$path${parameter.name}.", seen + nested) }
            when {
                parts != null -> inputs += listOf(inputOf(parameter, path)) + parts
                !parameter.isOptional -> return null
            }
        }
        return inputs
    }

    private fun inputOf(parameter: KParameter, path: String): StoryOperatorInput {
        val param = StoryParam(
            path + parameter.name.orEmpty(),
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
        ComponentDescriptorRegistry.forEach { visit(it.value.value.starProjectedType) }
        return types.values
    }
}
