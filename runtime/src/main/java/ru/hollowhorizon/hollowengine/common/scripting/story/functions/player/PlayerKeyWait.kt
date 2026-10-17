package ru.hollowhorizon.hollowengine.common.scripting.story.functions.player

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.input.ClientKeyWaitManager
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerEvent
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import java.util.*
import kotlin.coroutines.resume

/**
 * Suspends until this player's client releases a key and returns its key code.
 *
 * The function must run for a server-side player. The captured key continues through Minecraft's
 * normal input processing; this API observes it without blocking gameplay or an open screen.
 */
suspend fun Player.waitKey(): Int = waitForKey(null)

/** Suspends until this player releases [key] and returns its key code. */
suspend fun Player.waitKey(key: Int): Int = waitForKey(key)

/** Suspends until this player releases the letter [key], ignoring its case. */
suspend fun Player.waitKey(key: Char): Int = waitForKey(Key.code(key))

private suspend fun Player.waitForKey(key: Int?): Int {
    val player = this as? ServerPlayer
        ?: error("Player.waitKey can only be called for a server-side player")
    return PlayerKeyWaitManager.await(player, key)
}

/**
 * Key codes accepted by [Player.waitKey]. They mirror GLFW's keyboard constants without exposing
 * GLFW to server scripts.
 */
object Key {
    const val SPACE = 32
    const val APOSTROPHE = 39
    const val COMMA = 44
    const val MINUS = 45
    const val PERIOD = 46
    const val SLASH = 47
    const val DIGIT_0 = 48
    const val DIGIT_1 = 49
    const val DIGIT_2 = 50
    const val DIGIT_3 = 51
    const val DIGIT_4 = 52
    const val DIGIT_5 = 53
    const val DIGIT_6 = 54
    const val DIGIT_7 = 55
    const val DIGIT_8 = 56
    const val DIGIT_9 = 57
    const val SEMICOLON = 59
    const val EQUAL = 61
    const val A = 65
    const val B = 66
    const val C = 67
    const val D = 68
    const val E = 69
    const val F = 70
    const val G = 71
    const val H = 72
    const val I = 73
    const val J = 74
    const val K = 75
    const val L = 76
    const val M = 77
    const val N = 78
    const val O = 79
    const val P = 80
    const val Q = 81
    const val R = 82
    const val S = 83
    const val T = 84
    const val U = 85
    const val V = 86
    const val W = 87
    const val X = 88
    const val Y = 89
    const val Z = 90
    const val LEFT_BRACKET = 91
    const val BACKSLASH = 92
    const val RIGHT_BRACKET = 93
    const val GRAVE_ACCENT = 96
    const val WORLD_1 = 161
    const val WORLD_2 = 162
    const val ESCAPE = 256
    const val ENTER = 257
    const val TAB = 258
    const val BACKSPACE = 259
    const val INSERT = 260
    const val DELETE = 261
    const val RIGHT = 262
    const val LEFT = 263
    const val DOWN = 264
    const val UP = 265
    const val PAGE_UP = 266
    const val PAGE_DOWN = 267
    const val HOME = 268
    const val END = 269
    const val CAPS_LOCK = 280
    const val SCROLL_LOCK = 281
    const val NUM_LOCK = 282
    const val PRINT_SCREEN = 283
    const val PAUSE = 284
    const val F1 = 290
    const val F2 = 291
    const val F3 = 292
    const val F4 = 293
    const val F5 = 294
    const val F6 = 295
    const val F7 = 296
    const val F8 = 297
    const val F9 = 298
    const val F10 = 299
    const val F11 = 300
    const val F12 = 301
    const val F13 = 302
    const val F14 = 303
    const val F15 = 304
    const val F16 = 305
    const val F17 = 306
    const val F18 = 307
    const val F19 = 308
    const val F20 = 309
    const val F21 = 310
    const val F22 = 311
    const val F23 = 312
    const val F24 = 313
    const val F25 = 314
    const val KP_0 = 320
    const val KP_1 = 321
    const val KP_2 = 322
    const val KP_3 = 323
    const val KP_4 = 324
    const val KP_5 = 325
    const val KP_6 = 326
    const val KP_7 = 327
    const val KP_8 = 328
    const val KP_9 = 329
    const val KP_DECIMAL = 330
    const val KP_DIVIDE = 331
    const val KP_MULTIPLY = 332
    const val KP_SUBTRACT = 333
    const val KP_ADD = 334
    const val KP_ENTER = 335
    const val KP_EQUAL = 336
    const val LEFT_SHIFT = 340
    const val LEFT_CONTROL = 341
    const val LEFT_ALT = 342
    const val LEFT_SUPER = 343
    const val RIGHT_SHIFT = 344
    const val RIGHT_CONTROL = 345
    const val RIGHT_ALT = 346
    const val RIGHT_SUPER = 347
    const val MENU = 348

    fun code(key: Char): Int = when (key) {
        in 'a'..'z' -> key.uppercaseChar().code
        in 'A'..'Z', in '0'..'9' -> key.code
        ' ' -> SPACE
        '\'' -> APOSTROPHE
        ',' -> COMMA
        '-' -> MINUS
        '.' -> PERIOD
        '/' -> SLASH
        ';' -> SEMICOLON
        '=' -> EQUAL
        '[' -> LEFT_BRACKET
        '\\' -> BACKSLASH
        ']' -> RIGHT_BRACKET
        '`' -> GRAVE_ACCENT
        else -> throw IllegalArgumentException("Unsupported character '$key' for Player.waitKey(Char)")
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class RequestPlayerKeyPacket(val requestId: Long, val key: Int? = null) : HollowPacket {
    override fun handle(player: Player) = ClientKeyWaitManager.request(requestId, key)
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class CancelPlayerKeyRequestPacket(val requestId: Long) : HollowPacket {
    override fun handle(player: Player) = ClientKeyWaitManager.cancel(requestId)
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class PlayerKeyPressPacket(
    val requestIds: List<Long>,
    val key: Int,
) : HollowPacket {
    override fun handle(player: Player) {
        PlayerKeyWaitManager.resume(player as? ServerPlayer ?: return, requestIds, key)
    }
}

private object PlayerKeyWaitManager {
    private val lock = Any()
    private val continuations = mutableMapOf<UUID, MutableMap<Long, WaitingKey>>()
    private var nextRequestId = 0L

    suspend fun await(player: ServerPlayer, key: Int?): Int = suspendCancellableCoroutine { continuation ->
        val requestId = synchronized(lock) {
            val id = ++nextRequestId
            continuations.getOrPut(player.uuid, ::mutableMapOf)[id] = WaitingKey(key, continuation)
            id
        }

        RequestPlayerKeyPacket(requestId, key).send(player)
        continuation.invokeOnCancellation { remove(player, requestId, notifyClient = true) }
    }

    fun resume(player: ServerPlayer, requestIds: List<Long>, key: Int) {
        val waiting = synchronized(lock) {
            val playerContinuations = continuations[player.uuid] ?: return
            requestIds.mapNotNull { requestId ->
                val waiting = playerContinuations[requestId] ?: return@mapNotNull null
                if (waiting.key != null && waiting.key != key) return@mapNotNull null
                playerContinuations.remove(requestId)
            }.also {
                if (playerContinuations.isEmpty()) continuations.remove(player.uuid)
            }
        }
        waiting.forEach { waitingKey -> waitingKey.continuation.resume(key) }
    }

    fun cancelAll(player: Player) {
        val waiting = synchronized(lock) { continuations.remove(player.uuid)?.values.orEmpty() }
        waiting.forEach { waitingKey ->
            waitingKey.continuation.cancel(CancellationException("Player disconnected while waiting for key input"))
        }
    }

    private fun remove(player: ServerPlayer, requestId: Long, notifyClient: Boolean) {
        val removed = synchronized(lock) {
            val playerContinuations = continuations[player.uuid] ?: return
            val continuation = playerContinuations.remove(requestId)
            if (playerContinuations.isEmpty()) continuations.remove(player.uuid)
            continuation != null
        }
        if (removed && notifyClient && !player.hasDisconnected()) {
            CancelPlayerKeyRequestPacket(requestId).send(player)
        }
    }

    private data class WaitingKey(val key: Int?, val continuation: CancellableContinuation<Int>)
}

@SubscribeEvent
fun onPlayerLeaveCancelKeyWaits(event: PlayerEvent.Leave) {
    PlayerKeyWaitManager.cancelAll(event.player)
}
