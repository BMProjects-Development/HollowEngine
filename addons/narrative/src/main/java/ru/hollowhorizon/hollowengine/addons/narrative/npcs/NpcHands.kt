package ru.hollowhorizon.hollowengine.addons.narrative.npcs

import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.*
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.common.coroutines.Ref
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcAction
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcActionKeys
import ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveOptions
import ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveResult
import ru.hollowhorizon.hollowengine.common.npcs.navigation.faceTowards
import ru.hollowhorizon.hollowengine.common.npcs.navigation.rotate
import kotlin.time.Duration.Companion.milliseconds

/**
 * Forces an NPC to use a block at the specified position.
 * The NPC will move to the block, look at it, and perform the use action.
 *
 * @param pos The position of the block to be used.
 */
suspend infix fun NpcEntity.useBlock(pos: Vec3): InteractionResult = useBlock(pos, InteractionHand.MAIN_HAND)

@SimpleAction("use_block")
suspend fun NpcEntity.useBlock(
    pos: Vec3,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
    face: Direction? = null,
): InteractionResult {
    require(reach > 0.0) { "Interaction reach must be greater than zero" }
    return actions.act(NpcActionKeys.HANDS) { useBlockReaching(pos, hand, reach, face) }
}

fun NpcEntity.startUseBlock(
    pos: Vec3,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
    face: Direction? = null,
): NpcAction<InteractionResult> {
    require(reach > 0.0) { "Interaction reach must be greater than zero" }
    return actions.start(NpcActionKeys.HANDS) { useBlockReaching(pos, hand, reach, face) }
}

private suspend fun NpcEntity.useBlockReaching(
    pos: Vec3,
    hand: InteractionHand,
    reach: Double,
    face: Direction?,
): InteractionResult {
    val target = Vec3.atCenterOf(BlockPos.containing(pos))
    if (move(target, MoveOptions(arrivalDistance = reach * 0.8f)) != MoveResult.Arrived) return InteractionResult.FAIL
    rotate({ target }, DEFAULT_LOOK_DURATION.inWholeMilliseconds)
    return useBlockAt(target, hand, face)
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
suspend infix fun NpcEntity.destroyBlock(pos: Vec3): Boolean = destroyBlock(pos, DEFAULT_INTERACTION_REACH)

@SimpleAction("destroy_block")
suspend fun NpcEntity.destroyBlock(
    pos: Vec3,
    reach: Double,
    requireCorrectTool: Boolean = true,
): Boolean = actions.act(NpcActionKeys.HANDS) { destroyBlockReaching(pos, reach, requireCorrectTool) }

fun NpcEntity.startDestroyBlock(
    pos: Vec3,
    reach: Double = DEFAULT_INTERACTION_REACH,
    requireCorrectTool: Boolean = true,
): NpcAction<Boolean> = actions.start(NpcActionKeys.HANDS) { destroyBlockReaching(pos, reach, requireCorrectTool) }

private suspend fun NpcEntity.destroyBlockReaching(pos: Vec3, reach: Double, requireCorrectTool: Boolean): Boolean {
    val blockPos = BlockPos.containing(pos)
    val target = Vec3.atCenterOf(blockPos)
    if (move(target, MoveOptions(arrivalDistance = reach * 0.8f)) != MoveResult.Arrived) return false
    rotate({ target }, DEFAULT_LOOK_DURATION.inWholeMilliseconds)
    syncFakePlayer()
    val initialState = level().getBlockState(blockPos)
    if (initialState.isAir || initialState.getDestroyProgress(fakePlayer, level(), blockPos) <= 0f) return false
    if (requireCorrectTool && !fakePlayer.hasCorrectToolForDrops(initialState)) return false

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
        return level().getBlockState(blockPos).isAir
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
): NpcAction<InteractionResult> = actions.start(NpcActionKeys.HANDS) { interactReaching(target, hand, reach) }

private suspend fun NpcEntity.interactReaching(
    target: Ref<out Entity>,
    hand: InteractionHand,
    reach: Double,
): InteractionResult {
    if (move(target, MoveOptions(arrivalDistance = reach)) != MoveResult.Arrived) return InteractionResult.FAIL
    val entity = target.resolve()
    rotate({ entity.eyePosition }, DEFAULT_LOOK_DURATION.inWholeMilliseconds)
    return interactNow(entity, hand, reach)
}

@SimpleAction("interact")
suspend fun NpcEntity.interact(
    target: Ref<out Entity>,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    reach: Double = DEFAULT_INTERACTION_REACH,
): InteractionResult = actions.act(NpcActionKeys.HANDS) { interactReaching(target, hand, reach) }

private fun NpcEntity.syncFakePlayer() {
    val level = level() as ServerLevel
    fakePlayer.gameMode.setLevel(level)
    fakePlayer.moveTo(x, y, z, yRot, xRot)
    InteractionHand.entries.forEach { currentHand ->
        fakePlayer.setItemInHand(currentHand, getItemInHand(currentHand).copy())
    }
}

private val TICK_DURATION = 50.milliseconds
private const val DEFAULT_INTERACTION_REACH = 3.0
