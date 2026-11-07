package ru.hollowhorizon.hollowengine.addons.narrative.entities

import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValue
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRegistry
import ru.hollowhorizon.hollowengine.common.data.DataKey
import ru.hollowhorizon.hollowengine.common.data.data
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForTag

private const val TICKS_PER_SECOND = 20.0
internal const val ATTRIBUTES = "minecraft:attribute"
internal const val EFFECTS = "minecraft:mob_effect"

@EditorCategory(StoryCategory.POSITION)
@StoryValue("entity-position")
fun positionOf(who: Entity): Vec3 = who.position()

/** Where the eyes of [who] are, which is where it looks from. */
@EditorCategory(StoryCategory.POSITION)
@StoryValue("eye-position")
fun eyePositionOf(who: Entity): Vec3 = who.eyePosition

/** The direction [who] turns to, in degrees: 0 is south, 90 west. */
@EditorCategory(StoryCategory.ROTATION)
@StoryValue("yaw")
fun yawOf(who: Entity): Float = who.yRot

/** How far [who] looks up or down, in degrees: -90 is up, 90 down. */
@EditorCategory(StoryCategory.ROTATION)
@StoryValue("pitch")
fun pitchOf(who: Entity): Float = who.xRot

/** The direction [who] looks in, one block long. */
@EditorCategory(StoryCategory.ROTATION)
@StoryValue("look")
fun lookOf(who: Entity): Vec3 = who.lookAngle

/** How fast [who] moves, in blocks per tick. */
@EditorCategory(StoryCategory.MOTION)
@StoryValue("velocity")
fun velocityOf(who: Entity): Vec3 = who.deltaMovement

@EditorCategory(StoryCategory.HEALTH)
@StoryValue("health")
fun healthOf(who: LivingEntity): Float = who.health

@EditorCategory(StoryCategory.HEALTH)
@StoryValue("max-health")
fun maxHealthOf(who: LivingEntity): Float = who.maxHealth

/** The extra health golden apples give, spent before the health itself. */
@EditorCategory(StoryCategory.HEALTH)
@StoryValue("absorption")
fun absorptionOf(who: LivingEntity): Float = who.absorptionAmount

@EditorCategory(StoryCategory.HEALTH)
@StoryValue("armor")
fun armorOf(who: LivingEntity): Int = who.armorValue

/** The value of the attribute [attribute] of [who] with its modifiers, or 0 when [who] has no such attribute. */
@EditorCategory(StoryCategory.ATTRIBUTES)
@StoryValue("attribute")
fun attributeOf(who: LivingEntity, @EditorRegistry(ATTRIBUTES) attribute: ResourceLocation): Double =
    who.getAttribute(attributeHolder(attribute))?.value ?: 0.0

/** The base value of the attribute [attribute] of [who], before any modifier. */
@EditorCategory(StoryCategory.ATTRIBUTES)
@StoryValue("base-attribute")
fun baseAttributeOf(who: LivingEntity, @EditorRegistry(ATTRIBUTES) attribute: ResourceLocation): Double =
    who.getAttribute(attributeHolder(attribute))?.baseValue ?: 0.0

@EditorCategory(StoryCategory.ENTITY_EFFECTS)
@StoryValue("has-effect")
fun hasEffect(who: LivingEntity, @EditorRegistry(EFFECTS) effect: ResourceLocation): Boolean =
    who.hasEffect(effectHolder(effect))

/** The level of the effect [effect] on [who]: 1 for the first, 0 without it. */
@EditorCategory(StoryCategory.ENTITY_EFFECTS)
@StoryValue("effect-level")
fun effectLevelOf(who: LivingEntity, @EditorRegistry(EFFECTS) effect: ResourceLocation): Int =
    who.getEffect(effectHolder(effect))?.let { it.amplifier + 1 } ?: 0

/** How many seconds the effect [effect] of [who] has left: none without it, infinity when it does not run out. */
@EditorCategory(StoryCategory.ENTITY_EFFECTS)
@StoryValue("effect-seconds")
fun effectSecondsOf(who: LivingEntity, @EditorRegistry(EFFECTS) effect: ResourceLocation): Double {
    val instance = who.getEffect(effectHolder(effect)) ?: return 0.0
    return if (instance.isInfiniteDuration) Double.POSITIVE_INFINITY else instance.duration / TICKS_PER_SECOND
}

@EditorCategory(StoryCategory.STATE)
@StoryValue("name")
fun nameOf(who: Entity): String = who.name.string

@EditorCategory(StoryCategory.STATE)
@StoryValue("uuid")
fun uuidOf(who: Entity): String = who.stringUUID

/** The id of the type of [who], such as `minecraft:zombie`. */
@EditorCategory(StoryCategory.STATE)
@StoryValue("entity-type")
fun entityTypeOf(who: Entity): ResourceLocation = BuiltInRegistries.ENTITY_TYPE.getKey(who.type)

/** The level [who] is in. */
@EditorCategory(StoryCategory.STATE)
@StoryValue("level-of")
fun levelOf(who: Entity): ServerLevel =
    who.level() as? ServerLevel ?: throw StoryException("${who.name.string} is not on the server")

@EditorCategory(StoryCategory.STATE)
@StoryValue("is-alive")
fun isAlive(who: Entity): Boolean = who.isAlive

@EditorCategory(StoryCategory.STATE)
@StoryValue("on-ground")
fun isOnGround(who: Entity): Boolean = who.onGround()

@EditorCategory(StoryCategory.STATE)
@StoryValue("in-water")
fun isInWater(who: Entity): Boolean = who.isInWater

@EditorCategory(StoryCategory.STATE)
@StoryValue("in-lava")
fun isInLava(who: Entity): Boolean = who.isInLava

@EditorCategory(StoryCategory.STATE)
@StoryValue("sneaking")
fun isSneaking(who: Entity): Boolean = who.isShiftKeyDown

@EditorCategory(StoryCategory.STATE)
@StoryValue("sprinting")
fun isSprinting(who: Entity): Boolean = who.isSprinting

@EditorCategory(StoryCategory.STATE)
@StoryValue("invisible")
fun isInvisible(who: Entity): Boolean = who.isInvisible

/** What [who] rides, or null when it rides nothing. */
@EditorCategory(StoryCategory.STATE)
@StoryValue("vehicle")
fun vehicleOf(who: Entity): Entity? = who.vehicle

@EditorCategory(StoryCategory.FIRE)
@StoryValue("on-fire")
fun isOnFire(who: Entity): Boolean = who.isOnFire

/** How much air [who] has left under water, in ticks; 300 is full. */
@EditorCategory(StoryCategory.STATE)
@StoryValue("air")
fun airOf(who: Entity): Int = who.airSupply

/** What [who] keeps in its data under [key], where `.node.kts` scripts, animations and UI read it; null for nothing. */
@EditorCategory(StoryCategory.DATA)
@StoryValue("entity-data")
fun dataOf(who: Entity, key: String): Tag? = who.data[rawKey(key)]

/** The NBT of [who] as `/data get entity` shows it. */
@EditorCategory(StoryCategory.ENTITY_NBT)
@StoryValue("entity-nbt")
fun nbtOf(who: Entity): CompoundTag = who.saveWithoutId(CompoundTag())

/** A key that reads and writes the data of an entity as it is stored, whatever it holds. */
internal fun rawKey(key: String): DataKey<Tag> = DataKey(key, ForTag, null)

internal fun attributeHolder(id: ResourceLocation): Holder<Attribute> =
    BuiltInRegistries.ATTRIBUTE.getHolder(id).orElse(null) ?: throw StoryException("There is no attribute '$id'")

internal fun effectHolder(id: ResourceLocation): Holder<MobEffect> =
    BuiltInRegistries.MOB_EFFECT.getHolder(id).orElse(null) ?: throw StoryException("There is no effect '$id'")
