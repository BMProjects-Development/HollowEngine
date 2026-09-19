package ru.hollowhorizon.hollowengine.common.network

import kotlinx.serialization.Serializable
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import ru.hollowhorizon.hollowengine.client.ui.ide.WorldControlClient
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions

@Serializable
enum class WorldWeather { CLEAR, RAIN, THUNDER }

@Serializable
enum class WorldRule {
    DAYLIGHT_CYCLE,
    WEATHER_CYCLE,
    MOB_SPAWNING,
    MOB_GRIEFING,
    KEEP_INVENTORY,
    FIRE_TICK;

    val key: GameRules.Key<GameRules.BooleanValue>
        get() = when (this) {
            DAYLIGHT_CYCLE -> GameRules.RULE_DAYLIGHT
            WEATHER_CYCLE -> GameRules.RULE_WEATHER_CYCLE
            MOB_SPAWNING -> GameRules.RULE_DOMOBSPAWNING
            MOB_GRIEFING -> GameRules.RULE_MOBGRIEFING
            KEEP_INVENTORY -> GameRules.RULE_KEEPINVENTORY
            FIRE_TICK -> GameRules.RULE_DOFIRETICK
        }
}

@Serializable
data class WorldControlState(
    val dayTime: Long,
    val weather: WorldWeather,
    val difficulty: Int,
    val difficultyLocked: Boolean,
    val gameMode: Int,
    val rules: Map<WorldRule, Boolean>,
)

@Serializable
data class WorldChange(
    val timeOfDay: Long? = null,
    val weather: WorldWeather? = null,
    val difficulty: Int? = null,
    val gameMode: Int? = null,
    val rule: WorldRule? = null,
    val ruleValue: Boolean = false,
)

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RequestWorldControlPacket(val change: WorldChange? = null) : HollowPacket {
    override fun handle(player: Player) {
        val serverPlayer = player as? ServerPlayer ?: return
        if (!player.hasPermissions(PlayerPermissions.GAMEMASTER)) {
            WorldControlStatePacket(error = WorldControlErrors.OPERATOR_REQUIRED).send(serverPlayer)
            return
        }
        change?.let { WorldControl.apply(serverPlayer, it) }
        WorldControlStatePacket(WorldControl.snapshot(serverPlayer)).send(serverPlayer)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class WorldControlStatePacket(
    val state: WorldControlState? = null,
    val error: String? = null,
) : HollowPacket {
    override fun handle(player: Player) {
        WorldControlClient.accept(this)
    }
}

object WorldControlErrors {
    const val OPERATOR_REQUIRED = "hollowengine.gui.ide.world.error.operator_required"
}

internal object WorldControl {
    private const val DAY_TICKS = 24_000L

    fun snapshot(player: ServerPlayer): WorldControlState {
        val server = player.server
        val overworld = server.overworld()
        val levelData = server.worldData.overworldData()
        return WorldControlState(
            dayTime = overworld.dayTime,
            weather = when {
                levelData.isThundering -> WorldWeather.THUNDER
                levelData.isRaining -> WorldWeather.RAIN
                else -> WorldWeather.CLEAR
            },
            difficulty = server.worldData.difficulty.id,
            difficultyLocked = server.worldData.isDifficultyLocked,
            gameMode = player.gameMode.gameModeForPlayer.id,
            rules = WorldRule.entries.associateWith { server.gameRules.getBoolean(it.key) },
        )
    }

    fun apply(player: ServerPlayer, change: WorldChange) {
        val server = player.server
        change.timeOfDay?.let { setTimeOfDay(server, it) }
        change.weather?.let { setWeather(server.overworld(), it) }
        change.difficulty?.let { id ->
            if (!server.worldData.isDifficultyLocked) server.setDifficulty(Difficulty.byId(id), true)
        }
        change.gameMode?.let { id -> player.setGameMode(GameType.byId(id)) }
        change.rule?.let { rule -> server.gameRules.getRule(rule.key).set(change.ruleValue, server) }
    }

    /** Moves every level to [timeOfDay] within the current day, so the day counter and moon phase stay. */
        val target = timeOfDay.mod(DAY_TICKS)
        val daylight = server.gameRules.getBoolean(GameRules.RULE_DAYLIGHT)
        for (level in server.allLevels) {
            level.dayTime = level.dayTime - level.dayTime.mod(DAY_TICKS) + target
            server.playerList.broadcastAll(
                ClientboundSetTimePacket(level.gameTime, level.dayTime, daylight),
                level.dimension(),
            )
        }
    }

    private fun setWeather(level: ServerLevel, weather: WorldWeather) {
        val random = level.random
        when (weather) {
            WorldWeather.CLEAR -> level.setWeatherParameters(ServerLevel.RAIN_DELAY.sample(random), 0, false, false)
            WorldWeather.RAIN -> level.setWeatherParameters(0, ServerLevel.RAIN_DURATION.sample(random), true, false)
            WorldWeather.THUNDER ->
                level.setWeatherParameters(0, ServerLevel.THUNDER_DURATION.sample(random), true, true)
        }
    }
}
