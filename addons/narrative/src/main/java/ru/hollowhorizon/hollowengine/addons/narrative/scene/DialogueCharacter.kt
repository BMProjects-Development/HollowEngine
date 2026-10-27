package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.world.entity.LivingEntity

/**
 * Who is speaking. Characters are deliberately *not* variables: an entity cannot be written into a
 * checkpoint, so the script rebinds them on every start while variables carry over.
 */
sealed interface DialogueCharacter {
    /** Name shown to players. */
    val name: String

    companion object {
        fun of(entity: LivingEntity, name: String? = null): DialogueCharacter =
            EntityCharacter(entity, name ?: entity.displayName?.string ?: entity.name.string)

        fun of(name: String): DialogueCharacter = StringCharacter(name)
    }
}

data class EntityCharacter(val entity: LivingEntity, override val name: String) : DialogueCharacter

data class StringCharacter(override val name: String) : DialogueCharacter
