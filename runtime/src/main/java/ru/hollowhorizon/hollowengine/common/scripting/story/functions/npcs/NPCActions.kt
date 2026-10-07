package ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs

import com.mojang.brigadier.StringReader
import kotlinx.coroutines.delay
import net.minecraft.ChatFormatting
import net.minecraft.commands.arguments.item.ItemParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.*
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.coroutines.Ref
import ru.hollowhorizon.hollowengine.common.coroutines.entityRef
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcAction
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcActionKeys
import ru.hollowhorizon.hollowengine.common.npcs.navigation.*
import ru.hollowhorizon.hollowengine.common.scripting.state.waitPersistent
import ru.hollowhorizon.hollowengine.common.scripting.state.waitRealtime
import ru.hollowhorizon.hollowengine.common.scripting.state.waitUntil
import ru.hollowhorizon.hollowengine.common.utils.colored
import ru.hollowhorizon.hollowengine.common.utils.currentServer
import ru.hollowhorizon.hollowengine.common.utils.currentServerOrNull
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.plus
import java.time.Instant
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Moves the NPC toward the specified entity until the distance becomes less than or equal to the specified value.
 *
 * @param entity The entity to move toward.
 * @param dist The minimum distance to the entity; movement stops when this distance is reached.
 * @param speed The NPC's movement speed.
 */
suspend fun NpcEntity.move(entity: Entity, dist: Double = 1.5, speed: Double = 1.0): MoveResult =
    with(server ?: currentServer) {
        move(entity.entityRef, MoveOptions(arrivalDistance = dist, speed = speed))
    }

suspend fun NpcEntity.move(target: Ref<out Entity>, options: MoveOptions = MoveOptions()): MoveResult =
    startMove(target, options).await()

fun NpcEntity.startMove(target: Ref<out Entity>, options: MoveOptions = MoveOptions()): NpcAction<MoveResult> =
    actions.start(NpcActionKeys.MOVEMENT) { moveToEntity(target, options) }

/**
 * Moves the NPC to the specified entity using the default settings. (Can be specified with a space)
 *
 * @param mob The entity to move to.
 */
suspend infix fun NpcEntity.move(mob: Entity): MoveResult = move(entity = mob)

/**
 * Moves the NPC to the specified position until the distance becomes less than or equal to the specified value.
 *
 * @param pos The position to move to.
 * @param dist The minimum distance to the position; movement stops when this distance is reached.
 * @param speed The NPC's movement speed.
 */
suspend fun NpcEntity.move(pos: Vec3, dist: Double = 1.5, speed: Double = 1.0): MoveResult =
    move(pos, MoveOptions(arrivalDistance = dist, speed = speed))

suspend fun NpcEntity.move(pos: Vec3, options: MoveOptions = MoveOptions()): MoveResult =
    startMove(pos, options).await()

fun NpcEntity.startMove(pos: Vec3, options: MoveOptions = MoveOptions()): NpcAction<MoveResult> =
    actions.start(NpcActionKeys.MOVEMENT) { moveToPosition({ pos }, options) }

/**
 * Moves the NPC to the specified position using the default settings.
 *
 * @param position The position to move to.
 */
suspend infix fun NpcEntity.move(position: Vec3): MoveResult = move(pos = position)

/**
 * Moves the NPC through [points] in turn without stopping at any but the last: each one before it counts as
 * passed within [MoveOptions.passDistance], and [MoveOptions.arrivalDistance] is for the last.
 */
suspend fun NpcEntity.move(points: List<Vec3>, options: MoveOptions = MoveOptions()): MoveResult =
    startMove(points, options).await()

fun NpcEntity.startMove(points: List<Vec3>, options: MoveOptions = MoveOptions()): NpcAction<MoveResult> {
    require(points.isNotEmpty()) { "A move needs at least one point" }
    return actions.start(NpcActionKeys.MOVEMENT) { moveThrough(points.map { point -> { point } }, options) }
}

fun NpcEntity.stopMoving() {
    actions.cancel(NpcActionKeys.MOVEMENT)
    navigation.stop()
}

/**
 * Keeps the NPC out of [zone] on every way it walks, its moves and its goals alike, until [stopAvoiding]. It is
 * not saved with the NPC: a script that needs it after a reload adds it again.
 */
fun NpcEntity.avoid(zone: Zone) {
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
fun NpcEntity.lookAtNow(position: Vec3, maxAngularSpeed: Float = 360f): Boolean =
    faceTowards(position, maxAngularSpeed)

fun NpcEntity.startLookingAt(
    position: Vec3,
    duration: Duration = DEFAULT_LOOK_DURATION,
    maxAngularSpeed: Float = 360f,
): NpcAction<Unit> = actions.start(NpcActionKeys.LOOK) {
    rotate({ position }, duration.inWholeMilliseconds, maxAngularSpeed = maxAngularSpeed)
}

suspend fun NpcEntity.lookAt(position: Vec3, duration: Duration, maxAngularSpeed: Float = 360f) {
    startLookingAt(position, duration, maxAngularSpeed).await()
}

suspend infix fun NpcEntity.lookAt(position: Vec3) {
    lookAt(position, DEFAULT_LOOK_DURATION)
}

/**
 * Makes the NPC look at the specified entity. Returns value, is npc looked at target, or still rotating head.
 *
 * @param entity The entity to look at.
 */
fun NpcEntity.lookAtNow(entity: Entity, maxAngularSpeed: Float = 360f): Boolean =
    faceTowards(entity, maxAngularSpeed)

fun NpcEntity.startLookingAt(
    target: Ref<out Entity>,
    duration: Duration = DEFAULT_LOOK_DURATION,
    maxAngularSpeed: Float = 360f,
): NpcAction<Unit> = actions.start(NpcActionKeys.LOOK) {
    rotate({ target.resolve().eyePosition }, duration.inWholeMilliseconds, maxAngularSpeed = maxAngularSpeed)
}

suspend fun NpcEntity.lookAt(
    target: Ref<out Entity>,
    duration: Duration,
    maxAngularSpeed: Float = 360f,
) {
    startLookingAt(target, duration, maxAngularSpeed).await()
}

suspend infix fun NpcEntity.lookAt(entity: Entity) {
    with(server ?: currentServer) {
        lookAt(entity.entityRef, DEFAULT_LOOK_DURATION)
    }
}

fun NpcEntity.clearLookTarget() {
    actions.cancel(NpcActionKeys.LOOK)
}

/**
 * Forces an NPC to use a block at the specified position.
 * The NPC will move to the block, look at it, and perform the use action.
 *
 * @param pos The position of the block to be used.
 */
suspend infix fun NpcEntity.useBlock(pos: Vec3): InteractionResult = useBlock(pos, InteractionHand.MAIN_HAND)

suspend fun NpcEntity.useBlock(
    pos: Vec3,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
    face: Direction? = null,
): InteractionResult = startUseBlock(pos, hand, reach, face).await()

fun NpcEntity.startUseBlock(
    pos: Vec3,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
    face: Direction? = null,
): NpcAction<InteractionResult> {
    require(reach > 0.0) { "Interaction reach must be greater than zero" }
    return actions.start(NpcActionKeys.HANDS) {
        val target = Vec3.atCenterOf(BlockPos.containing(pos))
        val movement = startMove(target, MoveOptions(arrivalDistance = reach * 0.8f))
        try {
            if (movement.await() != MoveResult.Arrived) return@start InteractionResult.FAIL
            rotate({ target }, DEFAULT_LOOK_DURATION.inWholeMilliseconds)
            useBlockAt(target, hand, face)
        } finally {
            if (movement.isActive) movement.cancel()
        }
    }
}

fun NpcEntity.useBlockNow(
    pos: Vec3,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
    face: Direction? = null,
): InteractionResult {
    val blockPos = BlockPos.containing(pos)
    val target = Vec3.atCenterOf(blockPos)
    if (eyePosition.distanceToSqr(target) > reach * reach) return InteractionResult.FAIL

    return useBlockAt(target, hand, face)
}

private fun NpcEntity.useBlockAt(target: Vec3, hand: InteractionHand, face: Direction?): InteractionResult {
    val blockPos = BlockPos.containing(target)
    syncFakePlayer()
    val hit = BlockHitResult(target, face ?: interactionFace(target), blockPos, false)
    swing(hand)
    val result = fakePlayer.gameMode.useItemOn(fakePlayer, level(), fakePlayer.getItemInHand(hand), hand, hit)
    setItemInHand(hand, fakePlayer.getItemInHand(hand).copy())
    return result
}

/**
 * Forces the NPC to destroy the block at the specified position.
 * The NPC will move to the block, look at it, and destroy it.
 *
 * @param pos The position of the block to be destroyed.
 */
suspend infix fun NpcEntity.destroyBlock(pos: Vec3): Boolean = startDestroyBlock(pos).await()

fun NpcEntity.startDestroyBlock(
    pos: Vec3,
    reach: Double = DEFAULT_INTERACTION_REACH,
    requireCorrectTool: Boolean = true,
): NpcAction<Boolean> = actions.start(NpcActionKeys.HANDS) {
    val blockPos = BlockPos.containing(pos)
    val target = Vec3.atCenterOf(blockPos)
    val movement = startMove(target, MoveOptions(arrivalDistance = reach * 0.8f))
    try {
        if (movement.await() != MoveResult.Arrived) return@start false
        rotate({ target }, DEFAULT_LOOK_DURATION.inWholeMilliseconds)
        syncFakePlayer()
        val initialState = level().getBlockState(blockPos)
        if (initialState.isAir || initialState.getDestroyProgress(fakePlayer, level(), blockPos) <= 0f) {
            return@start false
        }
        if (requireCorrectTool && !fakePlayer.hasCorrectToolForDrops(initialState)) return@start false

        val face = interactionFace(target)
        fakePlayer.gameMode.handleBlockBreakAction(blockPos, START_DESTROY_BLOCK, face, level().maxBuildHeight, 0)
        try {
            var elapsedTicks = 0
            while (level().getBlockState(blockPos) == initialState) {
                fakePlayer.gameMode.tick()
                elapsedTicks++
                faceTowards(target)
                swing(InteractionHand.MAIN_HAND)
                val progress = initialState.getDestroyProgress(fakePlayer, level(), blockPos)
                if (progress * (elapsedTicks + 1) >= 1f) {
                    fakePlayer.gameMode.handleBlockBreakAction(
                        blockPos,
                        STOP_DESTROY_BLOCK,
                        face,
                        level().maxBuildHeight,
                        0,
                    )
                    break
                }
                delay(TICK_DURATION)
            }
            level().getBlockState(blockPos).isAir
        } finally {
            if (!level().getBlockState(blockPos).isAir) {
                fakePlayer.gameMode.handleBlockBreakAction(
                    blockPos,
                    ABORT_DESTROY_BLOCK,
                    face,
                    level().maxBuildHeight,
                    0,
                )
            }
            level().destroyBlockProgress(fakePlayer.id, blockPos, -1)
            setItemInHand(InteractionHand.MAIN_HAND, fakePlayer.mainHandItem.copy())
        }
    } finally {
        if (movement.isActive) movement.cancel()
    }
}

private fun NpcEntity.interactionFace(target: Vec3): Direction = Direction.getNearest(eyePosition.subtract(target))

fun NpcEntity.interactNow(
    target: Entity,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
): InteractionResult {
    if (target.level() !== level() || distanceToSqr(target) > reach * reach) return InteractionResult.FAIL
    syncFakePlayer()
    val result = fakePlayer.interactOn(target, hand)
    setItemInHand(hand, fakePlayer.getItemInHand(hand).copy())
    swing(hand)
    return result
}

fun NpcEntity.startInteract(
    target: Ref<out Entity>,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
): NpcAction<InteractionResult> = actions.start(NpcActionKeys.HANDS) {
    val movement = startMove(target, MoveOptions(arrivalDistance = reach))
    try {
        if (movement.await() != MoveResult.Arrived) return@start InteractionResult.FAIL
        val entity = target.resolve()
        rotate({ entity.eyePosition }, DEFAULT_LOOK_DURATION.inWholeMilliseconds)
        interactNow(entity, hand, reach)
    } finally {
        if (movement.isActive) movement.cancel()
    }
}

suspend fun NpcEntity.interact(
    target: Ref<out Entity>,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
): InteractionResult = startInteract(target, hand, reach).await()

private fun NpcEntity.syncFakePlayer() {
    val level = level() as ServerLevel
    fakePlayer.gameMode.setLevel(level)
    fakePlayer.moveTo(x, y, z, yRot, xRot)
    InteractionHand.entries.forEach { currentHand ->
        fakePlayer.setItemInHand(currentHand, getItemInHand(currentHand).copy())
    }
}

/**
 * Forces an NPC to drop an item from its inventory.
 *
 * @param item The item to be dropped.
 */
fun NpcEntity.dropItem(item: ItemStack) {
    val p = position()
    val entityStack = ItemEntity(level(), p.x, p.y + eyeHeight, p.z, item)
    entityStack.setDefaultPickUpDelay()
    val f8 = Mth.sin(xRot * Mth.PI / 180f)
    val f3 = Mth.sin(yHeadRot * Mth.PI / 180f)
    val f4 = Mth.cos(yHeadRot * Mth.PI / 180f)
    entityStack.setDeltaMovement(-f3 * 0.3, -f8 * 0.3 + 0.1, f4 * 0.3)
    level().addFreshEntity(entityStack)
}

/**
 * Pauses the coroutine for the specified number of ticks.
 *
 * @param time The number of ticks to delay.
 */
suspend fun wait(time: Int) {
    delay((time * 50L).milliseconds)
}

/** Waits for loaded server ticks inside `@State` and resumes the same countdown after a restart. */
suspend fun wait(time: Int, name: String) {
    waitPersistent(name, time)
}

/** Waits inside `@State` for wall-clock time, including time for which the game was not running. */
suspend fun wait(time: Duration, name: String) {
    waitRealtime(name, time)
}

/** Waits inside `@State` until an absolute date and resumes the same deadline after a restart. */
suspend fun wait(time: Instant, name: String) {
    waitUntil(name, time)
}

fun uuid(uuid: String): UUID = UUID.fromString(uuid)

/**
 * Создает ItemStack с указанным предметом, количеством и NBT-тегом.
 *
 * @param item Идентификатор предмета (например, "minecraft:apple").
 * @param count Количество предметов в стаке.
 * @param nbt NBT-тег для предмета.
 * @return ItemStack с заданными параметрами.
 */
fun item(item: String, count: Int = 1): ItemStack = item(item, count, DataComponentPatch.EMPTY)

private val builtInRegistryAccess by lazy {
    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
}

fun item(item: String, count: Int = 1, components: DataComponentPatch): ItemStack {
    require(count > 0) { "Item count must be greater than zero" }
    val reader = StringReader(item)
    val registries = currentServerOrNull()?.registryAccess() ?: builtInRegistryAccess
    val parsed = runCatching { ItemParser(registries).parse(reader) }
        .getOrElse { error -> throw IllegalArgumentException("Invalid item specification '$item'", error) }
    require(!reader.canRead()) { "Unexpected trailing data in item specification '$item'" }

    return ItemStack(parsed.item, count).apply {
        applyComponents(parsed.components)
        applyComponents(components)
    }
}

/**
 * A getter variable that converts seconds to ticks.
 *
 * @param this The number to be converted from ticks to seconds
 * @template
 * ```node.kts
 * 10.sec // Converts 10 seconds to ticks (10 * 20 = 200)
 * ```
 */
val Int.sec get() = this * 20

val Double.sec get() = (this * 20).toInt()

private val DEFAULT_LOOK_DURATION = 1500.milliseconds
private val TICK_DURATION = 50.milliseconds
private const val DEFAULT_INTERACTION_REACH = 3.0

/**
 * Makes an NPC "say" the text by sending a message to all players on the server.
 *
 * @param text The text of the message.
 */
infix fun NpcEntity.say(text: String) {
    server?.playerList?.players?.forEach {
        it.sendSystemMessage(
            "[".literal.colored(ChatFormatting.GRAY) +
            name +
            "]: ".literal.colored(ChatFormatting.GRAY) +
            text.literal
        )
    }
}

