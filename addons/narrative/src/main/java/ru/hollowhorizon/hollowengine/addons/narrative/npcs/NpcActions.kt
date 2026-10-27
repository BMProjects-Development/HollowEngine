package ru.hollowhorizon.hollowengine.addons.narrative.npcs

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.Action
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionAlias
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.addons.narrative.story.action
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.coroutines.Ref
import ru.hollowhorizon.hollowengine.common.coroutines.entityRef
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcAction
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcActionKeys
import ru.hollowhorizon.hollowengine.common.npcs.actions.Preempted
import ru.hollowhorizon.hollowengine.common.npcs.navigation.*
import ru.hollowhorizon.hollowengine.common.npcs.registry.ref
import ru.hollowhorizon.hollowengine.common.utils.currentServer
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Moves the NPC toward [target] until it is within [MoveOptions.arrivalDistance], following it while it moves.
 * Undone, the NPC is put back where it started; fast-forwarded, it is put next to the target.
 */
@EditorCategory("hollowengine.gui.ide.story.category.npc")
@Action("move-to-entity")
suspend fun NpcEntity.move(target: Entity, options: MoveOptions = MoveOptions()): MoveResult =
    action(this, target, options) {
        val start = capture { NpcPlacement.of(this@move) }
        undo { start.applyTo(this@move) }
        fastForward { arriveAt(target.position()) }
        perform {
            val reference = with(server ?: currentServer) { target.entityRef }
            actions.act(NpcActionKeys.MOVEMENT) { moveToEntity(reference, options) }
        }
    }

/**
 * Moves the NPC toward the specified entity until the distance becomes less than or equal to the specified value.
 *
 * @param entity The entity to move toward.
 * @param dist The minimum distance to the entity; movement stops when this distance is reached.
 * @param speed The NPC's movement speed.
 */
@ActionAlias
suspend fun NpcEntity.move(entity: Entity, dist: Double = 1.5, speed: Double = 1.0): MoveResult =
    move(entity, MoveOptions(arrivalDistance = dist, speed = speed))

@ActionAlias
suspend fun NpcEntity.move(target: Ref<out Entity>, options: MoveOptions = MoveOptions()): MoveResult =
    move(target.resolve(), options)

fun NpcEntity.startMove(target: Ref<out Entity>, options: MoveOptions = MoveOptions()): NpcAction<MoveResult> =
    actions.start(NpcActionKeys.MOVEMENT) { moveToEntity(target, options) }

/**
 * Moves the NPC to the specified entity using the default settings. (Can be specified with a space)
 *
 * @param mob The entity to move to.
 */
@ActionAlias
suspend infix fun NpcEntity.move(mob: Entity): MoveResult = move(entity = mob)

/**
 * Moves the NPC to [pos] until it is within [MoveOptions.arrivalDistance]. Undone, the NPC is put back where it
 * started; fast-forwarded, it is put at [pos].
 */
@EditorCategory("hollowengine.gui.ide.story.category.npc")
@Action("move-to")
suspend fun NpcEntity.move(pos: Vec3, options: MoveOptions = MoveOptions()): MoveResult =
    action(this, pos, options) {
        val start = capture { NpcPlacement.of(this@move) }
        undo { start.applyTo(this@move) }
        fastForward { arriveAt(pos) }
        perform { actions.act(NpcActionKeys.MOVEMENT) { moveToPosition({ pos }, options) } }
    }

/**
 * Moves the NPC to the specified position until the distance becomes less than or equal to the specified value.
 *
 * @param pos The position to move to.
 * @param dist The minimum distance to the position; movement stops when this distance is reached.
 * @param speed The NPC's movement speed.
 */
@ActionAlias
suspend fun NpcEntity.move(pos: Vec3, dist: Double = 1.5, speed: Double = 1.0): MoveResult =
    move(pos, MoveOptions(arrivalDistance = dist, speed = speed))

fun NpcEntity.startMove(pos: Vec3, options: MoveOptions = MoveOptions()): NpcAction<MoveResult> =
    actions.start(NpcActionKeys.MOVEMENT) { moveToPosition({ pos }, options) }

/**
 * Moves the NPC to the specified position using the default settings.
 *
 * @param position The position to move to.
 */
@ActionAlias
suspend infix fun NpcEntity.move(position: Vec3): MoveResult = move(pos = position)

/**
 * Moves the NPC through [points] in turn without stopping at any but the last: each one before it counts as
 * passed within [MoveOptions.passDistance], and [MoveOptions.arrivalDistance] is for the last.
 */
@EditorCategory("hollowengine.gui.ide.story.category.npc")
@Action("move-through")
suspend fun NpcEntity.move(points: List<Vec3>, options: MoveOptions = MoveOptions()): MoveResult =
    action(this, points, options) {
        require(points.isNotEmpty()) { "A move needs at least one point" }
        val start = capture { NpcPlacement.of(this@move) }
        undo { start.applyTo(this@move) }
        fastForward { arriveAt(points.last()) }
        perform { actions.act(NpcActionKeys.MOVEMENT) { moveThrough(points.map { point -> { point } }, options) } }
    }

fun NpcEntity.startMove(points: List<Vec3>, options: MoveOptions = MoveOptions()): NpcAction<MoveResult> {
    require(points.isNotEmpty()) { "A move needs at least one point" }
    return actions.start(NpcActionKeys.MOVEMENT) { moveThrough(points.map { point -> { point } }, options) }
}

@EditorCategory("hollowengine.gui.ide.story.category.npc")
@SimpleAction("stop-moving")
fun NpcEntity.stopMoving() {
    actions.cancel(NpcActionKeys.MOVEMENT)
    navigation.stop()
}

/**
 * Runs several actions of the NPC as one: the channels they use stay held until [block] returns, and losing
 * any of them to a higher priority cancels the whole block with a single [Preempted].
 */
suspend fun <R> NpcEntity.bundle(block: suspend NpcEntity.() -> R): R = actions.perform { block() }

/** [bundle] that waits for the actions already running on its channels instead of replacing them. */
suspend fun <R> NpcEntity.queue(block: suspend NpcEntity.() -> R): R = actions.queue { block() }

/**
 * Keeps the NPC out of [zone] on every way it walks, its moves and its goals alike, until [stopAvoiding]. It is
 * not saved with the NPC: a script that needs it after a reload adds it again. Undone, the NPC stops avoiding it.
 */
@EditorCategory("hollowengine.gui.ide.story.category.npc")
@Action("avoid")
suspend fun NpcEntity.avoid(zone: Zone) = action(this, zone) {
    undo { stopAvoiding(zone) }
    fastForward { addAvoided(zone) }
    perform { addAvoided(zone) }
}

private fun NpcEntity.addAvoided(zone: Zone) {
    npcNavigation.avoidedZones += zone
    navigation.recomputePath()
}

fun NpcEntity.stopAvoiding(zone: Zone) {
    if (npcNavigation.avoidedZones.remove(zone)) navigation.recomputePath()
}

fun NpcEntity.stopAvoidingAll() {
    if (npcNavigation.avoidedZones.isEmpty()) return
    npcNavigation.avoidedZones.clear()
    navigation.recomputePath()
}

/**
 * Makes the NPC look at the specified position. Returns value, is npc looked at target, or still rotating head.
 *
 * @param position The position to look at.
 */
fun NpcEntity.lookAtNow(position: Vec3, maxAngularSpeed: Float = 360f): Boolean = faceTowards(position, maxAngularSpeed)

fun NpcEntity.startLookingAt(
    position: Vec3,
    duration: Duration = DEFAULT_LOOK_DURATION,
    maxAngularSpeed: Float = 360f,
): NpcAction<Unit> = actions.start(NpcActionKeys.LOOK) {
    rotate({ position }, duration.inWholeMilliseconds, maxAngularSpeed = maxAngularSpeed)
}

/** Turns the NPC to [position] over [duration]. Undone, it faces where it did before; fast-forwarded, it faces there at once. */
@EditorCategory("hollowengine.gui.ide.story.category.npc")
@Action("look-at")
suspend fun NpcEntity.lookAt(
    position: Vec3,
    duration: Duration = DEFAULT_LOOK_DURATION,
    maxAngularSpeed: Float = 360f,
) = action(this, position, duration, maxAngularSpeed) {
    val before = capture { NpcRotation.of(this@lookAt) }
    undo { before.applyTo(this@lookAt) }
    fastForward { faceAt(position) }
    perform {
        actions.act(NpcActionKeys.LOOK) {
            rotate(
                { position },
                duration.inWholeMilliseconds,
                maxAngularSpeed = maxAngularSpeed
            )
        }
    }
}

@ActionAlias
suspend infix fun NpcEntity.lookAt(position: Vec3) {
    lookAt(position, DEFAULT_LOOK_DURATION)
}

/**
 * Makes the NPC look at the specified entity. Returns value, is npc looked at target, or still rotating head.
 *
 * @param entity The entity to look at.
 */
fun NpcEntity.lookAtNow(entity: Entity, maxAngularSpeed: Float = 360f): Boolean = faceTowards(entity, maxAngularSpeed)

fun NpcEntity.startLookingAt(
    target: Ref<out Entity>,
    duration: Duration = DEFAULT_LOOK_DURATION,
    maxAngularSpeed: Float = 360f,
): NpcAction<Unit> = actions.start(NpcActionKeys.LOOK) {
    rotate({ target.resolve().eyePosition }, duration.inWholeMilliseconds, maxAngularSpeed = maxAngularSpeed)
}

/** Turns the NPC to [target] over [duration], following it as it moves; undone and fast-forwarded like [lookAt] at a position. */
@EditorCategory("hollowengine.gui.ide.story.category.npc")
@Action("look-at-entity")
suspend fun NpcEntity.lookAt(
    target: Entity,
    duration: Duration = DEFAULT_LOOK_DURATION,
    maxAngularSpeed: Float = 360f,
) = action(this, target, duration, maxAngularSpeed) {
    val before = capture { NpcRotation.of(this@lookAt) }
    undo { before.applyTo(this@lookAt) }
    fastForward { faceAt(target.eyePosition) }
    perform {
        actions.act(NpcActionKeys.LOOK) {
            rotate({ target.eyePosition }, duration.inWholeMilliseconds, maxAngularSpeed = maxAngularSpeed)
        }
    }
}

@ActionAlias
suspend fun NpcEntity.lookAt(target: Ref<out Entity>, duration: Duration, maxAngularSpeed: Float = 360f) =
    lookAt(target.resolve(), duration, maxAngularSpeed)

@ActionAlias
suspend infix fun NpcEntity.lookAt(entity: Entity) {
    lookAt(entity, DEFAULT_LOOK_DURATION)
}

@EditorCategory("hollowengine.gui.ide.story.category.npc")
@SimpleAction("stop-looking")
fun NpcEntity.clearLookTarget() {
    actions.cancel(NpcActionKeys.LOOK)
}

/** Puts the NPC at [position] at once, as a fast-forwarded move ends. */
private fun NpcEntity.arriveAt(position: Vec3): MoveResult {
    navigation.stop()
    place(position, yRot, xRot)
    return MoveResult.Arrived
}

/**
 * Puts the NPC at [position], with a body or without one: an NPC whose chunk is unloaded is moved through the
 * registry, which keeps it outside chunks, so an undo or a fast-forward need not wait for it.
 */
private fun NpcEntity.place(position: Vec3, yaw: Float, pitch: Float) {
    val level = level() as? ServerLevel
    if (level == null || ref.isLoaded) teleportTo(position.x, position.y, position.z)
    else ref.teleportTo(level, position, yaw, pitch)
}

/** Turns the NPC to face [target] at once. */
private fun NpcEntity.faceAt(target: Vec3) {
    val dx = target.x - x
    val dy = target.y - eyeY
    val dz = target.z - z
    val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
    NpcRotation(yaw, yaw, -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()).applyTo(this)
}

/** Where an NPC stands and how it faces, which an undone move puts it back to. */
@Serializable
internal class NpcPlacement(val x: Double, val y: Double, val z: Double, val rotation: NpcRotation) {
    fun applyTo(npc: NpcEntity) {
        npc.actions.cancel(NpcActionKeys.MOVEMENT)
        npc.navigation.stop()
        npc.place(Vec3(x, y, z), rotation.body, rotation.pitch)
        rotation.applyTo(npc)
    }

    companion object {
        fun of(npc: NpcEntity) = NpcPlacement(npc.x, npc.y, npc.z, NpcRotation.of(npc))
    }
}

/** How an NPC faces: its body, its head and its pitch. */
@Serializable
internal class NpcRotation(val body: Float, val head: Float, val pitch: Float) {
    fun applyTo(npc: NpcEntity) {
        npc.yRot = body
        npc.yBodyRot = body
        npc.yHeadRot = head
        npc.xRot = pitch
    }

    companion object {
        fun of(npc: NpcEntity) = NpcRotation(npc.yBodyRot, npc.yHeadRot, npc.xRot)
    }
}

internal val DEFAULT_LOOK_DURATION = 1500.milliseconds
