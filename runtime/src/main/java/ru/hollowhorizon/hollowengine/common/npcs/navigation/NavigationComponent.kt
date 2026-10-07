package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.tags.EntityTypeTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorIcon
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import kotlin.math.floor
import kotlin.math.min

private const val LANG = "hollowengine.component.hollowengine.entity.navigation"

/**
 * How an engine NPC finds its way and what it may do on it. Lives on the server only: the client sees
 * the NPC move and needs nothing of this.
 */
@Registerable
@Serializable
@SerialName("hollowengine:entity/navigation")
@EditorIcon("hollowengine:textures/gui/icons/curve.svg")
@EditorDescription("$LANG.hint")
data class NavigationComponent(
    val path: PathSettings = PathSettings(),
    val jumps: JumpSettings = JumpSettings(),
)

@Serializable
data class PathSettings(
    /** Walks the found path in straight lines wherever nothing is in the way, instead of block by block. */
    @EditorDescription("$LANG.straighten.hint")
    val straighten: Boolean = true,
    /** Steps off ledges higher than a block instead of looking for a way down. */
    @EditorDescription("$LANG.dropDown.hint")
    val dropDown: Boolean = true,
    /** The fall damage the NPC accepts for a shorter way down, in half-hearts; zero falls only as far as it is safe. */
    @EditorDescription("$LANG.fallDamage.hint")
    @EditorRange(min = 0.0)
    val fallDamage: Float = 0f,
)

@Serializable
data class JumpSettings(
    /** Jumps over gaps onto ground at the height it takes off from. */
    @EditorDescription("$LANG.jumpLevel.hint")
    val jumpLevel: Boolean = true,
    /** Jumps over gaps onto ground a block higher. */
    @EditorDescription("$LANG.jumpUp.hint")
    val jumpUp: Boolean = true,
    /** Jumps over gaps onto lower ground. */
    @EditorDescription("$LANG.jumpDown.hint")
    val jumpDown: Boolean = true,
    /** The widest gap, in blocks, it tries to jump; the run-up decides whether it really can. */
    @EditorDescription("$LANG.maxGap.hint")
    @EditorRange(1.0, 4.0, slider = true)
    val maxGap: Int = 2,
    /** How many blocks of walking a jump is worth: it jumps when going around is longer by more than this. */
    @EditorDescription("$LANG.jumpCost.hint")
    @EditorRange(min = 0.0)
    val jumpCost: Float = 3f,
    /** How much faster than its own speed it runs up to a jump over a gap. */
    @EditorDescription("$LANG.runUp.hint")
    @EditorRange(1.0, 3.0)
    val runUp: Float = 1.5f,
) {
    val jumpsGaps: Boolean get() = jumpLevel || jumpUp || jumpDown
}

/** The highest fall [mob] takes for a shorter way: as high as costs it no more than [PathSettings.fallDamage]. */
internal fun PathSettings.maxFall(mob: LivingEntity): Double {
    if (mob.type.`is`(EntityTypeTags.FALL_DAMAGE_IMMUNE)) return MAX_FALL
    val multiplier = mob.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)
    if (multiplier <= 0.0) return MAX_FALL
    val tolerated = floor(fallDamage.toDouble()) / multiplier
    return min(mob.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) + tolerated, MAX_FALL)
}

/** How many blocks [mob] steps down off a ledge. */
internal fun PathSettings.maxDrop(mob: LivingEntity): Int = if (dropDown) floor(maxFall(mob)).toInt() else 1

/** The most a fall is ever planned for, damage or not. */
private const val MAX_FALL = 64.0

/** The navigation component of this entity, read without creating any attachments for it. */
val Entity.navigationComponent: NavigationComponent?
    get() = AttachmentRegistry.attachmentsOrNull(this)?.components?.readOnly?.values
        ?.firstNotNullOfOrNull { it as? NavigationComponent }
