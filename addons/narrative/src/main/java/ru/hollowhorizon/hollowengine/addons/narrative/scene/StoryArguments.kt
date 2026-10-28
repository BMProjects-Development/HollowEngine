package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryParam

/**
 * Evaluated arguments of one call, already matched against the chosen overload. Positional and named
 * arguments end up in the same place: a function reads `args.string("name")` no matter how the story
 * wrote it.
 */
class StoryArguments(
    val signature: StoryFunctionSignature?,
    private val byName: Map<String, StoryValue>,
    private val positional: List<StoryValue>,
    /** Named parameters the signature does not declare, passed through to event handlers. */
    val metadata: Map<String, StoryValue>,
) {
    operator fun get(name: String): StoryValue? = byName[name]

    operator fun get(index: Int): StoryValue? = positional.getOrNull(index)

    fun string(name: String): String = require(name).let {
        (it as? StoryString)?.value ?: it.display()
    }

    fun stringOrNull(name: String): String? = byName[name]?.let { (it as? StoryString)?.value ?: it.display() }

    fun number(name: String): Float = when (val value = require(name)) {
        is StoryNumber -> value.value
        is StoryString -> value.value.toFloatOrNull()
            ?: throw IllegalArgumentException("Argument '$name' is not a number: '${value.value}'")

        else -> throw IllegalArgumentException("Argument '$name' is not a number")
    }

    fun numberOrNull(name: String): Float? = if (byName.containsKey(name)) number(name) else null

    fun int(name: String): Int = number(name).toInt()

    fun bool(name: String): Boolean = when (val value = require(name)) {
        is StoryBool -> value.value
        else -> value.isTruthy()
    }

    fun list(name: String): List<StoryValue> = when (val value = require(name)) {
        is StoryList -> value.values
        else -> listOf(value)
    }

    /** The character bound to an actor argument. */
    fun actor(name: String): DialogueCharacter = when (val value = require(name)) {
        is StoryActor -> value.character
        is StoryEntity -> value.character()
        else -> throw IllegalArgumentException(
            "Argument '$name' is not a character: no actor named '${value.display()}' is bound to this dialogue",
        )
    }

    fun actorOrNull(name: String): DialogueCharacter? = when (val value = byName[name]) {
        is StoryActor -> value.character
        is StoryEntity -> value.character()
        else -> null
    }

    /**
     * The entity behind an actor argument.
     */
    fun entity(name: String): LivingEntity = when (val character = actor(name)) {
        is EntityCharacter -> character.entity
        is StringCharacter -> throw IllegalArgumentException(
            "'${character.name}' is a name without an entity, so it cannot be moved or animated",
        )
    }

    /** Three numbers in a row or a `[x, y, z]` list, whichever the story wrote. */
    fun vec3(name: String): Vec3 = when (val value = require(name)) {
        is StoryList -> {
            require(value.values.size == 3) { "Argument '$name' must hold three numbers, got ${value.values.size}" }
            Vec3(
                (value.values[0] as? StoryNumber)?.value?.toDouble() ?: badVector(name),
                (value.values[1] as? StoryNumber)?.value?.toDouble() ?: badVector(name),
                (value.values[2] as? StoryNumber)?.value?.toDouble() ?: badVector(name),
            )
        }

        else -> throw IllegalArgumentException("Argument '$name' must be a position like [10, 64, 20]")
    }

    fun vec3OrNull(name: String): Vec3? = if (byName.containsKey(name)) vec3(name) else null

    private fun badVector(name: String): Nothing =
        throw IllegalArgumentException("Argument '$name' must hold three numbers")

    /** Milliseconds of a duration argument (`2sec` and `2000` are the same value by then). */
    fun millis(name: String): Long = number(name).toLong()

    private fun require(name: String): StoryValue =
        byName[name] ?: throw IllegalArgumentException("Missing argument '$name'")

    companion object {
        /**
         * Binds evaluated values to [signature]: positional values fill parameters in order, named
         * ones by name; anything the signature does not declare becomes metadata.
         */
        fun bind(
            signature: StoryFunctionSignature?,
            positional: List<StoryValue>,
            named: Map<String, StoryValue>,
            metadata: Map<String, StoryValue>,
        ): StoryArguments {
            val byName = LinkedHashMap<String, StoryValue>()
            val extra = LinkedHashMap(metadata)
            if (signature != null) {
                signature.params.forEach { param -> param.default?.let { byName[param.name] = it } }
                positional.forEachIndexed { index, value ->
                    signature.params.getOrNull(index)?.let { byName[it.name] = value }
                }
                for ((name, value) in named) {
                    if (signature.params.any { it.name == name }) byName[name] = value else extra[name] = value
                }
            } else {
                byName += named
                extra += named
            }
            return StoryArguments(signature, byName, positional, extra)
        }

        fun fits(
            signature: StoryFunctionSignature,
            positional: List<StoryValue>,
            named: Map<String, StoryValue>,
        ): Boolean {
            if (positional.size > signature.params.size) return false
            val bound = HashSet<String>()
            positional.forEachIndexed { index, value ->
                val param: StoryParam = signature.params[index]
                if (!param.type.accepts(value)) return false
                bound += param.name
            }
            for ((name, value) in named) {
                val param = signature.params.firstOrNull { it.name == name } ?: continue
                if (name in bound || !param.type.accepts(value)) return false
                bound += name
            }
            return signature.params.none { !it.optional && it.name !in bound }
        }
    }
}
