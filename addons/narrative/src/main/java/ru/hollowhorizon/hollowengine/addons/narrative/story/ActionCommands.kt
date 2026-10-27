package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.scene.*
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryFunctionCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryType
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The actions as the commands of `.story`. `@move-to Кузнец [10, 64, 3]` calls the action `move-to` of whichever
 * namespace has one, `@hollowengine-narrative:move-to` that of the narrative addon. The receiver comes first, the
 * parameters follow in order or by name, and those the scene leaves out keep their defaults.
 */
object ActionCommands : StoryFunctionCatalog {
    private class Built(val actions: List<StoryAction>, val commands: List<StoryCommand>)

    @Volatile
    private var built = Built(emptyList(), emptyList())

    /** Every action a scene can call: one whose required parameters all take values a scene has. */
    val commands: List<StoryCommand>
        get() {
            val actions = StoryActions.all
            built.takeIf { it.actions === actions }?.let { return it.commands }
            return Built(actions, actions.mapNotNull(StoryCommand::of)).also { built = it }.commands
        }

    /** The commands [name] may mean: one, unless several namespaces declare it. */
    fun find(name: String): List<StoryCommand> = commands.filter { it.answersTo(name) }

    override fun overloads(name: String): List<StoryFunctionSignature>? =
        find(name).map { it.signature }.ifEmpty { null }

    override val names: Collection<String> get() = commands.map { it.name }.distinct()

    /** The command a call of [name] with these values goes to. */
    fun resolve(name: String, positional: List<StoryValue>, named: Map<String, StoryValue>): StoryCommand {
        val found = find(name)
        if (found.isEmpty()) throw StoryRuntimeException("There is no command '@$name'")
        if (found.size > 1) {
            throw StoryRuntimeException("'@$name' is ambiguous, write ${found.joinToString(" or ") { "@${it.id}" }}")
        }
        val command = found.single()
        if (!StoryArguments.fits(command.signature, positional, named)) {
            throw StoryRuntimeException("'@$name' does not take these arguments; it takes ${command.describe()}")
        }
        return command
    }
}

/** An action as a scene calls it. */
class StoryCommand private constructor(
    val action: StoryAction,
    val signature: StoryFunctionSignature,
    private val parameters: List<Bound>,
) {
    /**
     * A parameter of the function as the scene fills it: from the argument [name], or, for an options object such as
     * `MoveOptions`, through its [constructor] from the arguments named after the [fields] it has (`speed=1.2`).
     */
    private class Bound(
        val reflected: KParameter,
        val name: String,
        val constructor: KFunction<*>? = null,
        val fields: List<KParameter> = emptyList(),
    )

    val id: String get() = action.id

    /** The id without its namespace, which a scene writes when no other namespace has it. */
    val name: String get() = id.substringAfter(':')

    internal fun answersTo(written: String): Boolean = if (':' in written) id == written else name == written

    internal fun describe(): String =
        signature.params.joinToString(" ") { if (it.optional) "[${it.name}]" else it.name }

    /** Calls the action with [arguments] bound to [signature]; values left out keep their defaults. */
    suspend fun call(arguments: StoryArguments): Any? {
        val function = action.function
        val values = HashMap<KParameter, Any?>()
        function.parameters.firstOrNull { it.kind == KParameter.Kind.INSTANCE }?.let { values[it] = action.owner() }
        parameters.forEach { bound ->
            val constructor = bound.constructor
            if (constructor == null) {
                val value = arguments[bound.name] ?: return@forEach
                values[bound.reflected] = StoryConversions.convert(value, bound.reflected.type, bound.name)
                return@forEach
            }
            val given = bound.fields.mapNotNull { field ->
                arguments[field.name!!]?.let { field to StoryConversions.convert(it, field.type, field.name!!) }
            }
            if (given.isNotEmpty()) values[bound.reflected] = constructor.callBy(given.toMap())
        }
        return try {
            function.callSuspendBy(values)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    internal companion object {
        /** The command [action] is, or null when a scene cannot call it. */
        fun of(action: StoryAction): StoryCommand? {
            val direct = ArrayList<Bound>()
            val options = ArrayList<Bound>()
            for (parameter in action.function.parameters) {
                when (parameter.kind) {
                    KParameter.Kind.INSTANCE -> continue
                    KParameter.Kind.EXTENSION_RECEIVER, KParameter.Kind.VALUE -> Unit
                    else -> return null
                }
                if (parameter.type.classifier == CheckpointScope::class) return null
                if (StoryConversions.typeOf(parameter.type) == null) {
                    val fields = optionsOf(parameter)
                    if (fields != null) options += fields else if (!parameter.isOptional) return null
                    continue
                }
                val name = when {
                    parameter.kind != KParameter.Kind.EXTENSION_RECEIVER -> parameter.name ?: return null
                    StoryConversions.typeOf(parameter.type) == StoryType.ACTOR -> RECEIVER_ENTITY
                    else -> RECEIVER
                }
                direct += Bound(parameter, name)
            }
            val params = direct.map { param(it.name, it.reflected, it.reflected.isOptional) }.toMutableList()
            // A field of an options object named like a parameter of the action is left to the parameter.
            val taken = params.mapTo(HashSet()) { it.name }
            val bound = direct + options.map { bound ->
                val fields = bound.fields.filter { taken.add(it.name!!) }
                params += fields.map { param(it.name!!, it, optional = true) }
                Bound(bound.reflected, bound.name, bound.constructor, fields)
            }
            return StoryCommand(action, StoryFunctionSignature(action.id, params), bound)
        }

        private fun param(name: String, parameter: KParameter, optional: Boolean) = StoryParam(
            name = name,
            type = StoryConversions.typeOf(parameter.type)!!,
            optional = optional,
            suggestions = parameter.findAnnotation<Suggestions>()?.source?.objectInstance?.values
                ?: StoryConversions.suggestions(parameter.type),
        )

        /**
         * An optional parameter whose class can be built from optional constructor parameters alone, such as
         * `MoveOptions`: a scene sets those of them it has values for, by name.
         */
        private fun optionsOf(parameter: KParameter): Bound? {
            if (!parameter.isOptional) return null
            val constructor = (parameter.type.classifier as? KClass<*>)?.primaryConstructor ?: return null
            if (constructor.parameters.any { !it.isOptional }) return null
            val fields = constructor.parameters.filter { it.name != null && StoryConversions.typeOf(it.type) != null }
            if (fields.isEmpty()) return null
            return Bound(parameter, parameter.name ?: return null, constructor, fields)
        }

        /** What a scene calls the receiver: the entity that acts, or the value the action is about. */
        private const val RECEIVER_ENTITY = "who"
        private const val RECEIVER = "this"
    }
}

/** How the values of a scene become the arguments of a Kotlin function. */
internal object StoryConversions {
    /** How a scene writes a value of [type], or null when it has none. */
    fun typeOf(type: KType): StoryType? {
        val classifier = type.classifier as? KClass<*> ?: return null
        return when {
            classifier == String::class || classifier == ResourceLocation::class -> StoryType.STRING
            classifier == Boolean::class -> StoryType.BOOL
            classifier in NUMBERS || classifier == Duration::class -> StoryType.NUMBER
            classifier.isSubclassOf(Entity::class) || classifier.isSubclassOf(DialogueCharacter::class) -> StoryType.ACTOR
            classifier == Vec3::class || classifier == BlockPos::class -> StoryType.LIST
            classifier in COLLECTIONS -> type.arguments.singleOrNull()?.type?.let(::typeOf)?.let { StoryType.LIST }
            classifier.java.isEnum -> StoryType.STRING
            else -> null
        }
    }

    /** The values the editor offers for [type]: the constants of an enum, of a list of them too. */
    fun suggestions(type: KType): List<String> {
        val classifier = type.classifier as? KClass<*> ?: return emptyList()
        if (classifier in COLLECTIONS) return type.arguments.singleOrNull()?.type?.let(::suggestions).orEmpty()
        return classifier.java.enumConstants?.map { (it as Enum<*>).name.lowercase().replace('_', '-') }.orEmpty()
    }

    /** A Kotlin value as a value of a scene; null for null and Unit. */
    fun toStory(value: Any?): StoryValue? = when (value) {
        null, Unit -> null
        is String -> StoryString(value)
        is Boolean -> StoryBool(value)
        is Number -> StoryNumber(value.toFloat())
        is Duration -> StoryNumber(value.inWholeMilliseconds.toFloat())
        is Enum<*> -> StoryString(value.name.lowercase().replace('_', '-'))
        is Collection<*> -> StoryList(value.mapNotNull(::toStory))
        else -> throw StoryRuntimeException("A scene cannot keep a ${value.javaClass.simpleName}")
    }

    fun convert(value: StoryValue, type: KType, name: String): Any {
        val classifier = type.classifier as KClass<*>
        fun fail(why: String = "it takes ${classifier.simpleName}"): Nothing =
            throw StoryRuntimeException("'$name' cannot be ${value.display()}: $why")

        fun number(): Float = when (value) {
            is StoryNumber -> value.value
            is StoryString -> value.value.toFloatOrNull() ?: fail()
            else -> fail()
        }

        fun numbers(count: Int): List<Float> {
            val items = (value as? StoryList)?.values ?: fail("it takes a position like [10, 64, 20]")
            if (items.size != count) fail("it takes $count numbers")
            return items.map { (it as? StoryNumber)?.value ?: fail("it takes $count numbers") }
        }
        return when {
            classifier == String::class -> (value as? StoryString)?.value ?: value.display()
            classifier == ResourceLocation::class -> ResourceLocation.tryParse(value.display())
                ?: fail("it takes an id like minecraft:stone")

            classifier == Boolean::class -> (value as? StoryBool)?.value ?: fail()
            classifier == Float::class -> number()
            classifier == Double::class -> number().toDouble()
            classifier == Int::class -> number().toInt()
            classifier == Long::class -> number().toLong()
            classifier == Short::class -> number().toInt().toShort()
            classifier == Byte::class -> number().toInt().toByte()
            classifier == Duration::class -> number().toDouble().milliseconds
            classifier.isSubclassOf(Entity::class) -> entity(value, classifier, ::fail)
            classifier.isSubclassOf(DialogueCharacter::class) -> (value as? StoryActor)?.character?.takeIf(classifier::isInstance)
                ?: fail("no character of the scene is named so")

            classifier == Vec3::class -> numbers(3).let { Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
            classifier == BlockPos::class -> numbers(3).let { BlockPos(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
            classifier in COLLECTIONS -> {
                val element = type.arguments.single().type!!
                val items = (value as? StoryList)?.values ?: listOf(value)
                val converted = items.map { convert(it, element, name) }
                if (classifier == Set::class) converted.toSet() else converted
            }

            classifier.java.isEnum -> classifier.java.enumConstants.firstOrNull {
                (it as Enum<*>).name.equals(value.display().replace('-', '_'), ignoreCase = true)
            } ?: fail("it takes one of ${suggestions(type).joinToString()}")

            else -> fail("a scene cannot pass a ${classifier.simpleName}")
        }
    }

    private fun entity(value: StoryValue, type: KClass<*>, fail: (String) -> Nothing): Entity {
        val actor = value as? StoryActor ?: fail("no character of the scene is named so")
        val entity =
            (actor.character as? EntityCharacter)?.entity ?: fail("'${actor.name}' is a name without an entity")
        if (!type.isInstance(entity)) fail("'${actor.name}' is a ${entity.type.description.string}, not a ${type.simpleName}")
        return entity
    }

    private val NUMBERS = setOf(Int::class, Long::class, Short::class, Byte::class, Float::class, Double::class)
    private val COLLECTIONS = setOf(List::class, Collection::class, Iterable::class, Set::class)
}
