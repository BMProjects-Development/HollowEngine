package ru.hollowhorizon.hollowengine.addons.narrative.entities

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRegistry
import ru.hollowhorizon.hollowengine.common.data.data
import ru.hollowhorizon.hollowengine.common.time.inWholeTicks
import ru.hollowhorizon.hollowengine.common.time.sec
import kotlin.time.Duration

/** Moves [who] to [pos]. Undone, it is back where it stood, facing as it faced. */
@EditorCategory(StoryCategory.POSITION)
@Action("teleport")
suspend fun teleport(who: Entity, pos: Vec3) = action(who, pos) {
    val before = capture { Placement.of(who) }
    undo { before.applyTo(who) }
    perform { who.teleportTo(pos.x, pos.y, pos.z) }
}

/** Turns [this] to [yaw] and [pitch], in degrees. Undone, it faces as it faced. */
@EditorCategory(StoryCategory.ROTATION)
@Action("set-rotation")
suspend fun Entity.rotate(yaw: Float, pitch: Float = 0f) = action(this, yaw, pitch) {
    val before = capture { Placement.of(this@rotate) }
    undo { before.applyTo(this@rotate) }
    perform {
        yRot = yaw
        xRot = pitch.coerceIn(-MAX_PITCH, MAX_PITCH)
        yHeadRot = yaw
        setYBodyRot(yaw)
    }
}

/**
 * Sets the motion of [this] to [velocity], in blocks per tick, or adds it to the motion it has with [add]: a jump, a
 * knockback, a throw. Motion runs out by itself, so nothing undoes it.
 */
@EditorCategory(StoryCategory.MOTION)
@SimpleAction("push")
fun Entity.push(velocity: Vec3, add: Boolean = true) {
    deltaMovement = if (add) deltaMovement.add(velocity) else velocity
    hurtMarked = true
}

/** Sets the health of [this], up to its maximum. Undone, it has the health it had. */
@EditorCategory(StoryCategory.HEALTH)
@Action("set-health")
suspend fun LivingEntity.changeHealth(health: Double) = action(this, health) {
    val before = capture { this@changeHealth.health }
    undo { this@changeHealth.health = before }
    perform { this@changeHealth.health = health.toFloat() }
}

/** Hurts [this] by [amount], as any harm with no source would; it can die of it, which nothing undoes. */
@EditorCategory(StoryCategory.HEALTH)
@SimpleAction("damage")
fun LivingEntity.damage(amount: Double) {
    hurt(damageSources().generic(), amount.toFloat())
}

/**
 * Gives [this] the effect [effect], such as `minecraft:speed`, for [time]; an infinite time does not run out.
 * [amplifier] 0 is the first level. Undone, it has the effect it had before, or none.
 */
@EditorCategory(StoryCategory.ENTITY_EFFECTS)
@Action("effect")
suspend fun LivingEntity.giveEffect(
    @EditorRegistry(EFFECTS) effect: ResourceLocation,
    time: Duration = 10.sec,
    amplifier: Int = 0,
    particles: Boolean = true,
) = action(this, effect, time, amplifier, particles) {
    val holder = effectHolder(effect)
    val before = capture { getEffect(holder)?.save() as? CompoundTag }
    undo {
        removeEffect(holder)
        before?.let(MobEffectInstance::load)?.let(::addEffect)
    }
    perform {
        val ticks =
            if (time.isInfinite()) MobEffectInstance.INFINITE_DURATION else time.inWholeTicks.toInt()
        removeEffect(holder)
        addEffect(MobEffectInstance(holder, ticks, amplifier, false, particles))
    }
}

/** Takes the effect [effect] from [this]. Undone, it has it again as it was. */
@EditorCategory(StoryCategory.ENTITY_EFFECTS)
@Action("remove-effect")
suspend fun LivingEntity.takeEffect(@EditorRegistry(EFFECTS) effect: ResourceLocation) = action(this, effect) {
    val holder = effectHolder(effect)
    val before = capture { getEffect(holder)?.save() as? CompoundTag }
    undo { before?.let(MobEffectInstance::load)?.let(::addEffect) }
    perform { removeEffect(holder) }
}

/** Sets [this] on fire for [time]; no time puts the fire out. Undone, it burns as long as it burned before. */
@EditorCategory(StoryCategory.FIRE)
@Action("ignite")
suspend fun Entity.ignite(time: Duration = 5.sec) = action(this, time) {
    val before = capture { remainingFireTicks }
    undo { remainingFireTicks = before }
    perform { remainingFireTicks = time.inWholeTicks.toInt() }
}

/** Sets the base value of the attribute [attribute] of [this]. Undone, it has the base value it had. */
@EditorCategory(StoryCategory.ATTRIBUTES)
@Action("set-attribute")
suspend fun LivingEntity.changeAttribute(
    @EditorRegistry(ATTRIBUTES) attribute: ResourceLocation,
    value: Double,
) = action(this, attribute, value) {
    val instance =
        getAttribute(attributeHolder(attribute)) ?: throw StoryException("${name.string} has no attribute '$attribute'")
    val before = capture { instance.baseValue }
    undo { instance.baseValue = before }
    perform { instance.baseValue = value }
}

/**
 * Keeps [value] in the data of [this] under [key], where `.node.kts` scripts, animations and UI read it. Undone, the
 * key holds what it held, or nothing.
 */
@EditorCategory(StoryCategory.DATA)
@Action("set-data")
suspend fun Entity.setData(key: String, value: Tag) = action(this, key, value) {
    val before = capture { held(dataOf(this@setData, key)) }
    undo { restore(this@setData, key, before) }
    perform { data[rawKey(key)] = value.copy() }
}

/** Removes [key] from the data of [this]. Undone, the key holds what it held. */
@EditorCategory(StoryCategory.DATA)
@Action("remove-data")
suspend fun Entity.removeData(key: String) = action(this, key) {
    val before = capture { held(dataOf(this@removeData, key)) }
    undo { restore(this@removeData, key, before) }
    perform { data.remove(rawKey(key)) }
}

private const val MAX_PITCH = 90f
private const val HELD = "value"

/** [tag] wrapped, as a story keeps a value that may be missing. */
private fun held(tag: Tag?) = CompoundTag().apply { tag?.let { put(HELD, it.copy()) } }

private fun restore(entity: Entity, key: String, held: CompoundTag) {
    val tag = held.get(HELD)
    if (tag != null) entity.data[rawKey(key)] = tag.copy() else entity.data.remove(rawKey(key))
}
