package ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs

import kotlinx.coroutines.delay
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcAction
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcActionKeys
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemRequest
import ru.hollowhorizon.hollowengine.common.npcs.items.itemRequest
import ru.hollowhorizon.hollowengine.common.npcs.navigation.*
import java.util.*
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

enum class CollectStopReason {
    COMPLETED,
    NO_CANDIDATES,
    NO_REACHABLE_CANDIDATES,
    INVENTORY_FULL,
}

enum class CollectSearchPolicy {
    WAIT_AND_RETRY,
    FAIL,
}

data class CollectedItem(val stack: ItemStack)

data class CollectResult(
    val collected: List<CollectedItem>,
    val remaining: List<ItemRequest>,
    val reason: CollectStopReason,
    val returnResult: MoveResult? = null,
)

data class CollectOptions(
    val radius: Double = 16.0,
    val movement: MoveOptions = MoveOptions(
        unreachable = UnreachablePolicy.FAIL,
        unavailableTarget = UnavailableTargetPolicy.FAIL,
    ),
    val searchPolicy: CollectSearchPolicy = CollectSearchPolicy.WAIT_AND_RETRY,
    val searchIntervalTicks: Int = 10,
    val returnToStart: Boolean = true,
) {
    init {
        require(radius >= 0.0) { "Collection radius cannot be negative" }
        require(searchIntervalTicks > 0) { "Collection search interval must be greater than zero" }
    }
}

suspend fun NpcEntity.collectItems(
    requests: List<ItemRequest>,
    options: CollectOptions = CollectOptions(),
): CollectResult = startCollectItems(requests, options).await()

fun NpcEntity.startCollectItems(
    requests: List<ItemRequest>,
    options: CollectOptions = CollectOptions(),
): NpcAction<CollectResult> {
    require(requests.isNotEmpty()) { "At least one item request is required" }
    return actions.start(NpcActionKeys.MOVEMENT) { collectItemsInternal(requests, options) }
}

private suspend fun NpcEntity.collectItemsInternal(
    requests: List<ItemRequest>,
    options: CollectOptions,
): CollectResult {
    val startPose = CollectStartPose(position(), yRot, xRot, yHeadRot, yBodyRot)
    val collected = collectRequestedItems(requests, options)
    if (!options.returnToStart) return collected

    val returnResult = moveToPosition({ startPose.position }, options.movement)
    if (returnResult == MoveResult.Arrived) restore(startPose)
    return collected.copy(returnResult = returnResult)
}

private suspend fun NpcEntity.collectRequestedItems(
    requests: List<ItemRequest>,
    options: CollectOptions,
): CollectResult {
    val remaining = requests.map(ItemRequest::count).toMutableList()
    val collected = mutableListOf<CollectedItem>()

    while (true) {
        val requestIndex = remaining.indexOfFirst { it > 0 }
        if (requestIndex < 0) return result(requests, remaining, collected, CollectStopReason.COMPLETED)

        val request = requests[requestIndex]
        val candidates = findItems(request.filter, options.radius)
        if (candidates.isEmpty()) {
            if (options.searchPolicy == CollectSearchPolicy.FAIL) {
                return result(requests, remaining, collected, CollectStopReason.NO_CANDIDATES)
            }
            delay(TICK_MILLIS * options.searchIntervalTicks)
            continue
        }

        var collectedFromCandidate = false
        for (candidateRef in candidates) {
            val candidate = candidateRef.resolve()
            if (!NpcItemClaims.claim(level(), candidate.uuid, uuid)) continue

            try {
                val moveResult = moveToPosition(
                    target = { candidate.takeIf { isCollectable(it, request, options.radius) }?.position() },
                    options = options.movement,
                )
                if (moveResult != MoveResult.Arrived) {
                    continue
                }

                val requestedCount = min(remaining[requestIndex], candidate.item.count)
                val offered = candidate.item.copy().apply { count = requestedCount }
                val remainder = inventory.insert(offered)
                val inserted = offered.count - remainder.count
                if (inserted == 0) {
                    return result(requests, remaining, collected, CollectStopReason.INVENTORY_FULL)
                }

                candidate.item.shrink(inserted)
                if (candidate.item.isEmpty) candidate.discard()
                remaining[requestIndex] -= inserted
                collected += CollectedItem(offered.copy().apply { count = inserted })
                collectedFromCandidate = true
                break
            } finally {
                NpcItemClaims.release(level(), candidate.uuid, uuid)
            }
        }

        if (!collectedFromCandidate) {
            if (options.searchPolicy == CollectSearchPolicy.FAIL) {
                return result(requests, remaining, collected, CollectStopReason.NO_REACHABLE_CANDIDATES)
            }
            delay(TICK_MILLIS * options.searchIntervalTicks)
        }
    }
}

private fun NpcEntity.restore(pose: CollectStartPose) {
    yRot = pose.yRot
    xRot = pose.xRot
    yHeadRot = pose.yHeadRot
    yBodyRot = pose.yBodyRot
}

private fun NpcEntity.isCollectable(item: ItemEntity, request: ItemRequest, radius: Double): Boolean =
    !item.isRemoved &&
            item.level() === level() &&
            !item.item.isEmpty &&
            !item.hasPickUpDelay() &&
            request.filter.matches(item.item) &&
            distanceToSqr(item) <= radius * radius

private fun result(
    requests: List<ItemRequest>,
    remaining: List<Int>,
    collected: List<CollectedItem>,
    reason: CollectStopReason,
): CollectResult = CollectResult(
    collected = collected.toList(),
    remaining = requests.mapIndexedNotNull { index, request ->
        remaining[index].takeIf { it > 0 }?.let { itemRequest(request.filter, it) }
    },
    reason = reason,
)

private data class CollectStartPose(
    val position: Vec3,
    val yRot: Float,
    val xRot: Float,
    val yHeadRot: Float,
    val yBodyRot: Float,
)

private val TICK_MILLIS = 50.milliseconds

private object NpcItemClaims {
    private val claims = Collections.synchronizedMap(WeakHashMap<Level, MutableMap<UUID, UUID>>())

    fun claim(level: Level, item: UUID, npc: UUID): Boolean = synchronized(claims) {
        val levelClaims = claims.getOrPut(level) { mutableMapOf() }
        val owner = levelClaims[item]
        if (owner != null && owner != npc) return@synchronized false
        levelClaims[item] = npc
        true
    }

    fun release(level: Level, item: UUID, npc: UUID) = synchronized(claims) {
        val levelClaims = claims[level] ?: return@synchronized
        if (levelClaims[item] == npc) levelClaims.remove(item)
        if (levelClaims.isEmpty()) claims.remove(level)
    }
}
