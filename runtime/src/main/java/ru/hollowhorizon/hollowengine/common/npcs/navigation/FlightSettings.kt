package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlinx.serialization.Serializable
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange

private const val LANG = "hollowengine.component.hollowengine.entity.navigation"

/** Whether an NPC flies. */
@Serializable
enum class FlightMode {
    /** Walks only. */
    NEVER,

    /** Walks, and flies wherever that is cheaper by [FlightSettings.flightCost]. */
    CAN,

    /** Flies only and never lands, like a phantom or a vex. */
    ALWAYS,
}

/** How an NPC moves in the air. */
@Serializable
enum class FlightStyle {
    /** Can stop in the air and move any way at once, like a parrot or a bee. */
    HOVER,

    /** Always moves forward and turns in arcs, circling when it has nowhere to go, like a phantom or a dragon. */
    GLIDE,
}

/**
 * Flight settings of an NPC. Its full speed in the air is the `flying_speed` attribute, in blocks per tick.
 *
 * @property flies whether and when the NPC flies
 * @property style how it moves in the air
 * @property flightCost cost of one block flown, **in blocks of walking**
 * @property takeoffCost cost of a takeoff, **in blocks of walking**
 * @property cruiseHeight preferred height above the ground, in blocks
 * @property heightCost extra cost per block flown for every block away from [cruiseHeight]
 * @property maxHeight highest height above the ground, in blocks; 0 means no limit
 * @property flightAcceleration seconds from hovering to full speed and back
 * @property turnRate turn speed in the air, in degrees per second
 * @property climbAngle steepest climb or dive, in degrees
 * @property smoothing 0 follows the path tightly, 1 cuts it in wide curves
 * @property wobble how far the NPC randomly strays from its path, in blocks
 * @property wobbleRate how often the stray changes, per second
 * @property landAtGoal lands below the goal of a move instead of staying in the air; only for [FlightMode.CAN]
 */
@Serializable
data class FlightSettings(
    @EditorDescription("$LANG.flies.hint")
    val flies: FlightMode = FlightMode.NEVER,
    @EditorDescription("$LANG.style.hint")
    val style: FlightStyle = FlightStyle.HOVER,
    @EditorDescription("$LANG.flightCost.hint")
    @EditorRange(min = 1.0)
    val flightCost: Float = 2f,
    @EditorDescription("$LANG.takeoffCost.hint")
    @EditorRange(min = 0.0)
    val takeoffCost: Float = 4f,
    @EditorDescription("$LANG.cruiseHeight.hint")
    @EditorRange(0.0, 64.0)
    val cruiseHeight: Float = 3f,
    @EditorDescription("$LANG.heightCost.hint")
    @EditorRange(min = 0.0)
    val heightCost: Float = 0.1f,
    @EditorDescription("$LANG.maxHeight.hint")
    @EditorRange(0.0, 64.0)
    val maxHeight: Float = 0f,
    @EditorDescription("$LANG.flightAcceleration.hint")
    @EditorRange(0.0, 5.0)
    val flightAcceleration: Float = 0.5f,
    @EditorDescription("$LANG.turnRate.hint")
    @EditorRange(10.0, 1080.0)
    val turnRate: Float = 360f,
    @EditorDescription("$LANG.climbAngle.hint")
    @EditorRange(10.0, 90.0, slider = true)
    val climbAngle: Float = 90f,
    @EditorDescription("$LANG.smoothing.hint")
    @EditorRange(0.0, 1.0, slider = true)
    val smoothing: Float = 0.5f,
    @EditorDescription("$LANG.wobble.hint")
    @EditorRange(0.0, 4.0)
    val wobble: Float = 0f,
    @EditorDescription("$LANG.wobbleRate.hint")
    @EditorRange(0.05, 5.0)
    val wobbleRate: Float = 1f,
    @EditorDescription("$LANG.landAtGoal.hint")
    val landAtGoal: Boolean = false,
) {
    val enabled: Boolean get() = flies != FlightMode.NEVER
}

/** Full flight speed of [mob] at [speedModifier], in blocks per tick. */
internal fun flightSpeed(mob: LivingEntity, speedModifier: Double): Double =
    mob.getAttributeValue(Attributes.FLYING_SPEED) * speedModifier
