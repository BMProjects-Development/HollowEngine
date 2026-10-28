package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValues
import java.util.UUID

/**
 * A runtime value of the `.story` language. Variables are dynamically typed: strings, numbers, bools, lists and
 * entities are saved with the scene, actors only live while it plays.
 *
 * Numbers are [Float] by design: dialogue counters never need double precision, and sessions keep
 * a checkpoint of every variable after each statement.
 */
sealed interface StoryValue {
    /** The value as presented to players inside `{...}` interpolation. */
    fun display(): String
}

data class StoryString(val value: String) : StoryValue {
    override fun display() = value
}

data class StoryNumber(val value: Float) : StoryValue {
    override fun display(): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
}

data class StoryBool(val value: Boolean) : StoryValue {
    override fun display() = value.toString()
}

data class StoryList(val values: List<StoryValue>) : StoryValue {
    override fun display() = values.joinToString(", ", "[", "]") { it.display() }
}

/**
 * Actors are runtime-only on purpose: an entity cannot be written into a checkpoint, so the script
 * re-supplies them on every start while variables carry over.
 */
data class StoryActor(val name: String, val character: DialogueCharacter) : StoryValue {
    override fun display() = character.name
}

/**
 * An entity a scene keeps in a variable, such as the NPC `npc(...)` spawned. Unlike an actor it is saved, by [uuid],
 * and found again each time it is used.
 */
data class StoryEntity(val uuid: UUID, val name: String) : StoryValue {
    override fun display() = name

    /** The entity wherever it is loaded, or null when it is not. */
    fun find(): Entity? = StoryValues.entities(uuid, Entity::class)

    /** The entity as a character of the scene; fails when it is not loaded or cannot speak. */
    fun character(): DialogueCharacter {
        val entity = find() as? LivingEntity ?: throw StoryRuntimeException("'$name' ($uuid) is not loaded")
        return DialogueCharacter.of(entity, name)
    }

    companion object {
        fun of(entity: Entity) = StoryEntity(entity.uuid, entity.displayName?.string ?: entity.name.string)
    }
}

/** True unless the value is `false`, `0` or an empty string; lists are truthy when non-empty. */
fun StoryValue.isTruthy(): Boolean = when (this) {
    is StoryBool -> value
    is StoryNumber -> value != 0f
    is StoryString -> value.isNotEmpty()
    is StoryList -> values.isNotEmpty()
    is StoryActor, is StoryEntity -> true
}
